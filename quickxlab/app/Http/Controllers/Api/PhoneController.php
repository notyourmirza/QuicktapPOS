<?php

namespace App\Http\Controllers\Api;

use App\Support\ApiException;
use App\Support\Billing;
use Illuminate\Http\JsonResponse;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Hash;

/**
 * Everything the Android till itself calls: sign-up and sessions, the licence answer,
 * the device list, plan prices and the remote configuration.
 *
 * The field names below are the contract — the app parses them with optString/optInt and
 * fails closed on an unknown `code`, so renaming one is a broken till, not a refactor.
 */
class PhoneController extends ApiController
{
    // ------------------------------------------------------- start-up / config

    /** The one call made to the hard-coded address: where to go from now on. */
    public function bootstrap(): JsonResponse
    {
        $firebase = (array) config('billing.firebase');
        foreach (['app_id', 'api_key', 'project_id', 'sender_id'] as $key) {
            $fromSettings = Billing::setting('fb_' . $key);
            if ($fromSettings) {
                $firebase[$key] = (string) $fromSettings;
            }
        }
        $base = (string) (Billing::setting('api_base_url') ?: config('billing.api_base_url'));

        return $this->ok('OK', [
            'firebase' => array_filter($firebase),
            'api_base_url' => $base,
            'min_app_version' => (string) Billing::setting('min_app_version', ''),
            'force_update' => (int) Billing::setting('force_update', 0) === 1,
        ]);
    }

    /** Branding, receipt template, the support number and the notices. */
    public function config(): JsonResponse
    {
        $settings = Billing::settings();
        $shop = $this->ctx()['shop'] ?? null;
        $appName = (string) ($settings['app_name'] ?? config('app.name'));
        if (!empty($shop['app_name'])) {
            $appName = (string) $shop['app_name'];
        }
        $whatsapp = preg_replace('/\D+/', '', (string) ($settings['support_whatsapp'] ?? ''));

        return $this->ok('OK', [
            'app_name' => $appName,
            'logo_url' => (string) ($settings['logo_url'] ?? ''),
            'receipt_template' => (string) ($settings['receipt_template'] ?? 'classic'),
            'receipt_footer' => (string) ($settings['receipt_footer'] ?? ''),
            'support' => ['whatsapp' => $whatsapp],
            'support_whatsapp' => $whatsapp,
            'sync_minutes' => max(0, (int) ($settings['license_sync_minutes'] ?? 60)),
            'notifications' => $this->noticeRows(),
        ]);
    }

    public function notifications(): JsonResponse
    {
        return $this->ok('OK', ['notifications' => $this->noticeRows()]);
    }

    /** A shop renaming itself everywhere it prints. */
    public function appName(): JsonResponse
    {
        $name = $this->str('app_name');
        if (mb_strlen($name) < 3 || mb_strlen($name) > 40) {
            $this->fail('BAD_APP_NAME', 'The shop name must be between 3 and 40 characters.');
        }
        DB::table('shops')->where('id', $this->shopId())->update([
            'app_name' => $name, 'updated_at' => Billing::stamp(),
        ]);
        Billing::audit('app_name', $this->shopId(), $name);

        return $this->ok('Name saved.', ['app_name' => $name]);
    }

    public function plans(): JsonResponse
    {
        $rows = DB::table('plans')->where('is_active', 1)->orderBy('sort_order')->orderBy('id')->get();
        $plans = [];
        foreach ($rows as $row) {
            $row = (array) $row;
            $plans[] = [
                'code' => (string) $row['code'],
                'name' => (string) $row['name'],
                'tagline' => (string) $row['tagline'],
                'tag' => (string) $row['tag'],
                'price' => (float) $row['price'],
                'price_yearly' => (float) $row['price_yearly'],
                'max_devices' => (int) $row['max_devices'],
                'features' => Billing::features($row['features'] ?? ''),
            ];
        }

        $data = ['plans' => $plans, 'currency' => (string) Billing::setting('currency', config('billing.default_currency'))];
        $shop = $this->ctx()['shop'] ?? null;
        if (is_array($shop)) {
            $state = Billing::state($shop, $this->ctx()['user'] ?? null, (string) $this->ctx()['device_id']);
            $licence = DB::table('licences')->where('shop_id', (int) $shop['id'])->orderByDesc('id')->first();
            $data['current'] = [
                'code' => (string) ($state['plan_code'] ?? ''),
                'name' => (string) ($state['plan_name'] ?? ''),
                'status' => $state['state'],
                'days_left' => $state['days_left'] >= 0 ? $state['days_left'] : null,
                // The app parses this string, so keep the DATETIME shape.
                'ends_at' => $licence !== null && (int) $licence->expires_at > 0
                    ? date('Y-m-d H:i:s', (int) $licence->expires_at) : '',
                'expires_at_ms' => $state['expires_at_ms'],
                'device_seat' => $state['seat'],
            ];
            $data['currency'] = (string) $shop['currency'];
        }

        return $this->ok('OK', $data);
    }

    // -------------------------------------------------------------- sessions

    public function register(): JsonResponse
    {
        $shopName = $this->str('shop_name');
        $username = strtolower($this->str('username'));
        $password = (string) ($this->input()['password'] ?? '');

        if (mb_strlen($shopName) < 3 || mb_strlen($shopName) > 60) {
            $this->fail('BAD_SHOP_NAME', 'The shop name must be between 3 and 60 characters.');
        }
        if (!preg_match('/^[a-z0-9._-]{4,32}$/', $username)) {
            $this->fail('BAD_USERNAME', 'A username is 4-32 characters: letters, numbers, dot, dash or underscore.');
        }
        if (strlen($password) < 8) {
            $this->fail('WEAK_PASSWORD', 'Please choose a password of at least 8 characters.');
        }
        if (DB::table('users')->where('username', $username)->exists()) {
            $this->fail('USERNAME_TAKEN', 'That username is already used by another shop.');
        }

        $created = Billing::createShop($shopName, $username, $password,
            $this->str('full_name'), preg_replace('/[^0-9+]/', '', $this->str('phone')),
            (string) ($this->ctx()['client']['default_plan'] ?? ''), 'registered from the app');
        $shopId = (int) $created['shop_id'];
        $userId = (int) $created['user_id'];
        // The site hands a ?ref= code through, and the referral is worth Rs 500 of
        // licence time — recorded, because "who brought whom" is a money question.
        $referral = $this->str('referral_code');
        if ($referral !== '') {
            Billing::audit('referral', $shopId, 'code ' . $referral);
        }

        $shop = Billing::shop($shopId);
        $user = Billing::user($userId);
        $this->adopt($user, $shop);
        Billing::bind($this->ctx(), $shopId);

        $state = Billing::state($shop, $user, (string) $this->ctx()['device_id']);
        return $this->ok($state['state'] === 'ACTIVE'
            ? 'Account created.'
            : 'Account created — your licence is waiting for activation.', array_merge(
            ['tokens' => Billing::tokensFor($userId, (string) $this->ctx()['device_id'])],
            Billing::payload($state),
            [
                'user' => ['id' => $userId, 'role' => 'owner', 'full_name' => $user['full_name'],
                    'fingerprint_enabled' => 0],
                'shop' => ['name' => $shop['name'], 'currency' => $shop['currency']],
            ]));
    }

    public function login(): JsonResponse
    {
        [$user, $shop] = $this->credentials($this->str('username'), (string) ($this->input()['password'] ?? ''));
        $this->adopt($user, $shop);

        DB::table('users')->where('id', (int) $user['id'])->update(['last_login_at' => Billing::stamp(),
            'device_id' => (string) $this->ctx()['device_id'] ?: $user['device_id']]);
        Billing::bind($this->ctx(), (int) $shop['id']);
        Billing::audit('login', (int) $shop['id'], (string) $user['username']);

        $state = Billing::state($shop, $user, (string) $this->ctx()['device_id']);

        return $this->ok('Welcome back.', array_merge(
            ['tokens' => Billing::tokensFor((int) $user['id'], (string) $this->ctx()['device_id'])],
            Billing::payload($state),
            ['user' => ['id' => (int) $user['id'], 'role' => (string) $user['role'],
                'full_name' => (string) $user['full_name'],
                'fingerprint_enabled' => (int) $user['fingerprint_enabled']],
             'shop' => ['name' => (string) $shop['name'], 'currency' => (string) $shop['currency']]]));
    }

    public function refresh(): JsonResponse
    {
        $raw = $this->str('refresh_token');
        $row = $raw === '' ? null : Billing::takeRefresh($raw);
        if ($row === null) {
            ApiException::deny('TOKEN_EXPIRED', 'Your session expired. Please sign in again.', 401);
        }
        $user = Billing::user((int) $row['user_id']);
        if ($user === null) {
            ApiException::deny('USER_DISABLED', 'This account is no longer active.', 403);
        }
        $deviceId = (string) ($row['device_id'] ?? '');

        return $this->ok('Session renewed.', [
            'tokens' => Billing::tokensFor((int) $user['id'], $deviceId),
            'license_status' => Billing::state(Billing::shop((int) $user['shop_id']), $user, $deviceId)['state'],
        ]);
    }

    public function logout(): JsonResponse
    {
        $user = $this->ctx()['user'] ?? null;
        if (is_array($user)) {
            Billing::revokeSessions((int) $user['id'], (string) $this->ctx()['device_id']);
        }
        return $this->ok('Signed out.', ['revoked' => true]);
    }

    /** Flat on purpose: the app reads these with optInt/optString at the top level. */
    public function me(): JsonResponse
    {
        $user = $this->ctx()['user'];
        $shop = $this->shop();

        return $this->ok('OK', [
            'id' => (int) $user['id'],
            'username' => (string) $user['username'],
            'role' => (string) $user['role'],
            'full_name' => (string) $user['full_name'],
            'phone' => (string) $user['phone'],
            'shop_id' => (int) $shop['id'],
            'shop_name' => (string) ($shop['app_name'] ?: $shop['name']),
            'currency' => (string) $shop['currency'],
            'fingerprint_enabled' => (int) $user['fingerprint_enabled'],
            'licence_confirmed' => !empty($user['licence_confirmed_at']) ? 1 : 0,
        ]);
    }

    public function unlock(): JsonResponse
    {
        $user = $this->ctx()['user'];
        $password = (string) ($this->input()['password'] ?? '');
        if ($password === '' || !Hash::check($password, (string) $user['password_hash'])) {
            ApiException::deny('BAD_CREDENTIALS', 'That password is not correct.', 401);
        }
        $state = Billing::state($this->shop(), $user, (string) $this->ctx()['device_id']);

        return $this->ok('Unlocked.', array_merge(['unlocked' => $state['unlocked']], Billing::payload($state)));
    }

    public function fingerprint(): JsonResponse
    {
        $enabled = !empty($this->input()['enabled']) && $this->input()['enabled'] !== false
            && $this->input()['enabled'] !== 'false' && $this->input()['enabled'] !== 0;
        DB::table('users')->where('id', (int) $this->ctx()['user']['id'])
            ->update(['fingerprint_enabled' => $enabled ? 1 : 0]);

        return $this->ok('Saved.', ['fingerprint_enabled' => $enabled ? 1 : 0]);
    }

    // ----------------------------------------------------------------- licence

    public function status(): JsonResponse
    {
        $state = Billing::state($this->shop(), $this->ctx()['user'] ?? null, (string) $this->ctx()['device_id']);

        return $this->ok($state['state'] === 'ACTIVE' ? 'Licence active' : $state['duration_label'],
            Billing::payload($state));
    }

    /**
     * Redeem a licence key — either the shop's own key (re-activating it) or an unused
     * activation code. Reachable while PENDING or EXPIRED: this is how a shop gets back in.
     */
    public function verify(): JsonResponse
    {
        $key = strtoupper(trim($this->str('license_key', $this->str('key'))));
        if ($key === '') {
            $this->fail('LICENSE_NOT_FOUND', 'Type or paste the licence key.');
        }

        $mine = DB::table('licences')->where('shop_id', $this->shopId())->where('license_key', $key)->first();
        if ($mine !== null) {
            if (strtoupper((string) $mine->status) === 'REVOKED') {
                ApiException::deny('LICENSE_REVOKED', 'This licence was revoked. Contact support.', 403);
            }
            $state = Billing::state($this->shop(), $this->ctx()['user'] ?? null, (string) $this->ctx()['device_id']);
            return $this->ok('Licence recognised.', Billing::payload($state));
        }

        if (DB::table('licences')->where('license_key', $key)->exists()) {
            ApiException::deny('LICENSE_FOREIGN', 'That key belongs to another shop.', 403);
        }

        $code = DB::table('activation_codes')->where('code', $key)->whereNull('used_at')->first();
        if ($code === null) {
            ApiException::deny('LICENSE_NOT_FOUND', 'That licence key is not recognised or was already used.', 404);
        }
        DB::table('activation_codes')->where('id', (int) $code->id)->update([
            'used_at' => Billing::stamp(), 'used_by_shop' => $this->shopId(),
        ]);
        Billing::extend($this->shopId(), (int) $code->days, 'code ' . $key);
        DB::table('licences')->where('shop_id', $this->shopId())->orderByDesc('id')->limit(1)->update([
            'license_key' => $key,
        ]);
        Billing::audit('activate', $this->shopId(), 'code ' . $key);
        $state = Billing::state($this->shop(), $this->ctx()['user'] ?? null, (string) $this->ctx()['device_id']);

        return $this->ok('Licence activated.', Billing::payload($state));
    }

    /** The "this is really me" step after an activation, on this device. */
    public function confirm(): JsonResponse
    {
        $user = $this->ctx()['user'];
        $username = strtolower($this->str('username', (string) $user['username']));
        $password = (string) ($this->input()['password'] ?? '');
        if ($username !== strtolower((string) $user['username'])
            || !Hash::check($password, (string) $user['password_hash'])) {
            ApiException::deny('BAD_CREDENTIALS', 'Username and password do not match this account.', 401);
        }
        DB::table('users')->where('id', (int) $user['id'])
            ->update(['licence_confirmed_at' => Billing::stamp()]);
        Billing::audit('confirm', $this->shopId(), (string) $user['username']);
        $state = Billing::state($this->shop(), Billing::user((int) $user['id']), (string) $this->ctx()['device_id']);

        return $this->ok('Confirmed.', Billing::payload($state));
    }

    // ---------------------------------------------------------- device accounting

    public function devices(): JsonResponse
    {
        $current = (string) $this->ctx()['device_id'];
        $rows = DB::table('app_devices')->where('shop_id', $this->shopId())
            ->orderByRaw('CASE WHEN status = ? THEN 0 ELSE 1 END', ['ACTIVE'])
            ->orderByDesc('last_seen_at')->get();

        $devices = [];
        foreach ($rows as $row) {
            $row = (array) $row;
            $devices[] = [
                'device_id' => (string) $row['device_id'],
                'device_name' => (string) $row['device_name'],
                'model' => (string) $row['os_version'],
                'os_version' => (string) $row['os_version'],
                'app_version' => (string) $row['app_version'],
                'status' => (string) $row['status'],
                'first_seen_at_ms' => Billing::millis($row['first_seen_at'] ? (string) $row['first_seen_at'] : null),
                'last_seen_at_ms' => Billing::millis($row['last_seen_at'] ? (string) $row['last_seen_at'] : null),
                'seen_count' => (int) $row['seen_count'],
                'is_current' => $row['device_id'] === $current,
            ];
        }

        return $this->ok('OK', [
            'devices' => $devices,
            'seat' => Billing::seat($this->shop()),
            'shop_name' => (string) ($this->shop()['app_name'] ?: $this->shop()['name']),
        ]);
    }

    /** Freeing a seat is the lost-phone fix; releasing your own is allowed too. */
    public function deactivate(): JsonResponse
    {
        $current = (string) $this->ctx()['device_id'];
        $target = $this->str('device_id', $current);
        if ($target === '') {
            $this->fail('BAD_REQUEST', 'No device was given to release.');
        }
        $released = Billing::releaseDevice($this->shopId(), $target);
        Billing::audit('device_release', $this->shopId(), $target . ($target === $current ? ' (self)' : ''));

        return $this->ok($released
            ? 'Device released — its seat is free for another till.'
            : 'That device was not linked to this shop.', [
            'released' => $target,
            'seat' => Billing::seat($this->shop()),
            'this_device' => $target === $current,
        ]);
    }

    // ------------------------------------------------------------------ shared

    /** @return array{0:array,1:array} user + shop, or a refusal the forms can show */
    private function credentials(string $username, string $password): array
    {
        $username = strtolower(trim($username));
        $row = $username === '' ? null : DB::table('users')->where('username', $username)->first();
        $user = $row === null ? null : (array) $row;
        if ($user === null || $password === '' || !Hash::check($password, (string) $user['password_hash'])) {
            ApiException::deny('BAD_CREDENTIALS', 'Username or password is not correct.', 401);
        }
        if ((int) $user['is_active'] !== 1) {
            ApiException::deny('USER_DISABLED', 'This account was disabled. Contact support.', 403);
        }
        $shop = Billing::shop((int) $user['shop_id']);
        if ($shop === null) {
            ApiException::deny('LICENSE_NOT_FOUND', 'No billing account was found for this user.', 403);
        }
        if (strtoupper((string) $shop['status']) === 'BLOCKED') {
            ApiException::deny('ACCOUNT_BLOCKED', 'This account has been blocked.', 403);
        }

        return [$user, $shop];
    }

    private function noticeRows(): array
    {
        $now = Billing::stamp();
        $rows = DB::table('notifications')->where('is_active', 1)
            ->where(function ($query) use ($now) {
                $query->whereNull('starts_at')->orWhere('starts_at', '<=', $now);
            })
            ->where(function ($query) use ($now) {
                $query->whereNull('ends_at')->orWhere('ends_at', '>=', $now);
            })
            ->orderByDesc('id')->limit(20)->get();

        $notices = [];
        foreach ($rows as $row) {
            $notices[] = [
                'id' => (int) $row->id,
                'title' => (string) $row->title,
                'body' => (string) ($row->body ?? ''),
                'level' => (string) $row->level,
                'created_at' => (string) $row->created_at,
            ];
        }
        return $notices;
    }
}
