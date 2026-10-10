<?php

namespace App\Support;

use Illuminate\Support\Facades\DB;
use Throwable;

/**
 * The billing authority: licence state, device seats, sessions, tokens and the shared
 * record store the till, a web page and a desktop program all read and write.
 *
 * This is a faithful port of billing-api/lib/{license,support,records,access}.php, kept
 * field-for-field on purpose — the Android app reads these exact names and fails closed
 * on anything it does not recognise, so a renamed key is a broken till, not a typo. Both
 * implementations run against the same tables, which is what lets the API move hosts
 * while old installs keep working (a till only needs a new `api_base_url` answer).
 *
 * Every method is pure PHP + the query builder; nothing here knows about HTTP.
 */
final class Billing
{
    private static ?array $settings = null;

    // ------------------------------------------------------------------ time

    public static function nowMs(): int
    {
        return (int) (microtime(true) * 1000);
    }

    public static function stamp(): string
    {
        return date('Y-m-d H:i:s');
    }

    /** A DATETIME column in millis; the MySQL zero date and null both read as 0. */
    public static function millis(?string $databaseTime): int
    {
        if ($databaseTime === null || $databaseTime === '' || $databaseTime === '0000-00-00 00:00:00') {
            return 0;
        }
        $normalized = preg_replace('/Z$/', '', str_replace('T', ' ', $databaseTime));
        $normalized = (string) preg_replace('/\.\d+/', '', (string) $normalized);
        $time = strtotime(trim($normalized));
        return $time === false ? 0 : $time * 1000;
    }

    /**
     * Millis from anything a client sends — epoch seconds, epoch milliseconds or a
     * datetime string. 0 means "not a time", never a wrong date.
     */
    public static function toMs(int|float|string|null $value): int
    {
        if ($value === null || $value === '' || $value === 'null') {
            return 0;
        }
        if (is_numeric($value)) {
            $number = (int) $value;
            return $number > 1_000_000_000_000 ? $number : $number * 1000;
        }
        $normalized = preg_replace('/[Z.]\d*$|Z$/', '', str_replace('T', ' ', (string) $value));
        $parsed = strtotime(trim((string) $normalized));
        return $parsed === false ? 0 : $parsed * 1000;
    }

    /** Anything that parses to a time as a DATETIME the panel and the till can both read. */
    public static function cleanTime(?string $candidate): string
    {
        $normalized = preg_replace('/Z$/', '', str_replace('T', ' ', (string) $candidate));
        $parsed = strtotime(trim((string) $normalized));
        return $parsed === false ? self::stamp() : date('Y-m-d H:i:s', $parsed);
    }

    // -------------------------------------------------------------- settings

    /** One query per request; the panel writes these, the app reads them. */
    public static function settings(): array
    {
        if (self::$settings === null) {
            self::$settings = DB::table('app_settings')->pluck('setting_value', 'setting_key')->all();
        }
        return self::$settings;
    }

    public static function setting(string $key, mixed $default = null): mixed
    {
        $value = self::settings()[$key] ?? null;
        return ($value === null || $value === '') ? $default : $value;
    }

    public static function forgetSettings(): void
    {
        self::$settings = null;
    }

    /** Newlines or a JSON array — the plan features are stored both ways. */
    public static function features(mixed $raw): array
    {
        if (is_array($raw)) {
            return array_values($raw);
        }
        $raw = trim((string) $raw);
        if ($raw === '') {
            return [];
        }
        if ($raw[0] === '[') {
            $decoded = json_decode($raw, true);
            return is_array($decoded) ? array_values($decoded) : [];
        }
        return array_values(array_filter(array_map('trim', preg_split('/\r\n|\r|\n/', $raw)),
            static fn ($line) => $line !== ''));
    }

    // ------------------------------------------------------------------ shop

    public static function shop(int $shopId): ?array
    {
        if ($shopId <= 0) {
            return null;
        }
        $row = DB::table('shops')->where('id', $shopId)->first();
        return $row === null ? null : (array) $row;
    }

    public static function user(int $userId): ?array
    {
        if ($userId <= 0) {
            return null;
        }
        $row = DB::table('users')->where('id', $userId)->where('is_active', 1)->first();
        return $row === null ? null : (array) $row;
    }

    public static function planFor(?array $shop): ?array
    {
        if ($shop === null) {
            return null;
        }
        $code = (string) ($shop['plan_code'] ?? '');
        if ($code !== '') {
            $plan = DB::table('plans')->where('code', $code)->where('is_active', 1)->first();
            if ($plan !== null) {
                return (array) $plan;
            }
        }
        $fallback = DB::table('plans')->where('is_active', 1)->orderBy('sort_order')->orderBy('id')->first();
        return $fallback === null ? null : (array) $fallback;
    }

    // ----------------------------------------------------------------- licence

    /**
     * The single answer to "may this shop use the app right now?".
     *
     * The order is the whole point: an administrator's block beats an unexpired
     * licence, and an expiry date beats a friendly status string. Expiry is judged on the
     * server clock, which is the only clock a till cannot be blamed for.
     */
    public static function state(?array $shop, ?array $user, string $deviceId = ''): array
    {
        $state = [
            'state' => 'UNKNOWN', 'unlocked' => false, 'confirmed' => false,
            'expires_at_ms' => 0, 'days_left' => -1, 'duration_label' => '',
            'request_status' => '', 'plan_code' => '', 'plan_name' => '',
            // Not part of the app contract (payload() decides that); the panel and the
            // shop's own dashboard print the key, so state() has to know it.
            'license_key' => '', 'note' => '',
            'seat' => ['used' => 0, 'limit' => 0],
        ];
        if ($shop === null) {
            return $state;
        }

        $shopStatus = strtoupper((string) ($shop['status'] ?? 'ACTIVE'));
        if ($shopStatus === 'BLOCKED') {
            return $state + ['state' => 'BLOCKED', 'request_status' => 'ACCOUNT_BLOCKED'];
        }
        if ($shopStatus === 'SUSPENDED') {
            return $state + ['state' => 'SUSPENDED', 'request_status' => 'SHOP_INACTIVE'];
        }

        $licence = DB::table('licences')->where('shop_id', (int) $shop['id'])
            ->orderByDesc('id')->first();
        $licence = $licence === null ? null : (array) $licence;
        $state['seat'] = self::seat($shop);

        if ($licence === null) {
            return $state + ['state' => 'PENDING', 'request_status' => 'NO_LICENSE'];
        }

        $licenceStatus = strtoupper((string) ($licence['status'] ?? 'PENDING'));
        $expiresAt = (int) ($licence['expires_at'] ?? 0);
        $state['request_status'] = $licenceStatus;
        $state['license_key'] = (string) ($licence['license_key'] ?? '');
        $state['note'] = (string) ($licence['note'] ?? '');

        if ($licenceStatus === 'REVOKED' || $licenceStatus === 'PENDING') {
            return $state + ['state' => $licenceStatus];
        }

        $state['expires_at_ms'] = $expiresAt > 0 ? $expiresAt * 1000 : 0;
        if ($expiresAt > 0) {
            $state['days_left'] = (int) floor(($expiresAt - time()) / 86400);
            $state['duration_label'] = $state['days_left'] > 0 ? $state['days_left'] . ' Days' : 'Expired';
        } else {
            $state['duration_label'] = 'Lifetime';
        }

        if ($expiresAt > 0 && $expiresAt < time()) {
            return $state + ['state' => 'EXPIRED', 'days_left' => 0];
        }

        $plan = self::planFor($shop);
        return $state + [
            'state' => 'ACTIVE',
            'unlocked' => true,
            'confirmed' => !empty($user['licence_confirmed_at']),
            'plan_code' => (string) ($plan['code'] ?? ''),
            'plan_name' => (string) ($plan['name'] ?? ''),
        ];
    }

    /** Exactly the keys /v1/license/status answers with — the app reads these names. */
    public static function payload(array $state): array
    {
        return [
            'license_status' => $state['state'],
            'account_status' => $state['request_status'],
            'unlocked' => $state['state'] === 'ACTIVE' && $state['unlocked'],
            'confirmed' => $state['state'] === 'ACTIVE' && $state['confirmed'],
            'request_status' => $state['request_status'],
            'duration_label' => $state['duration_label'],
            'expires_at_ms' => $state['expires_at_ms'],
            'server_time_ms' => self::nowMs(),
            'server_time' => time(),
            'days_left' => $state['days_left'] >= 0 ? $state['days_left'] : null,
            'plan' => ['code' => $state['plan_code'], 'name' => $state['plan_name']],
            'device_seat' => ['used' => $state['seat']['used'], 'limit' => $state['seat']['limit']],
        ];
    }

    /** How many tills may bill for this shop, and how many are bound right now. */
    public static function seat(array $shop): array
    {
        $plan = self::planFor($shop);
        $limit = $plan === null ? 1 : max(1, (int) ($plan['max_devices'] ?? 1));
        $used = DB::table('app_devices')->where('shop_id', (int) $shop['id'])
            ->where('status', 'ACTIVE')->count();
        return ['used' => max(0, min($used, $limit)), 'limit' => $limit];
    }

    /** QT-XXXXX-XXXXX-XXXXX-XXXXX, without the letters a cashier can misread. */
    public static function newKey(): string
    {
        $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
        $blocks = [];
        for ($block = 0; $block < 4; $block++) {
            $chars = '';
            for ($i = 0; $i < 5; $i++) {
                $chars .= $alphabet[random_int(0, strlen($alphabet) - 1)];
            }
            $blocks[] = $chars;
        }
        return 'QT-' . implode('-', $blocks);
    }

    /** Extends (or starts) a licence — used by the code redemption and by the panel. */
    public static function extend(int $shopId, int $days, string $note = ''): void
    {
        $licence = DB::table('licences')->where('shop_id', $shopId)->orderByDesc('id')->first();
        $licence = $licence === null ? null : (array) $licence;
        $base = time();
        if ($licence !== null && (int) $licence['expires_at'] > $base) {
            $base = (int) $licence['expires_at'];
        }
        $expires = $days > 0 ? $base + $days * 86400 : 0;

        if ($licence === null) {
            DB::table('licences')->insert([
                'shop_id' => $shopId,
                'license_key' => self::newKey(),
                'status' => 'ACTIVE',
                'started_at' => self::stamp(),
                'expires_at' => $expires,
                'activated_at' => self::stamp(),
                'note' => $note,
                'created_at' => self::stamp(),
            ]);
            return;
        }
        DB::table('licences')->where('id', (int) $licence['id'])->update([
            'status' => 'ACTIVE',
            'expires_at' => $expires,
            'started_at' => self::stamp(),
            'note' => $note !== '' ? $note : (string) $licence['note'],
        ]);
    }

    public static function setLicenceStatus(int $shopId, string $status): void
    {
        $licence = DB::table('licences')->where('shop_id', $shopId)->orderByDesc('id')->first();
        if ($licence !== null) {
            DB::table('licences')->where('id', (int) $licence['id'])->update(['status' => $status]);
        }
    }

    /**
     * Creates a shop, its owner and the licence row — the same three inserts for the APK's
     * `POST v1/auth/register` and for the site's /signup, so a shop that registered on the
     * website can sign in on the phone with those credentials and nothing else differs.
     *
     * @return array{shop_id:int,user_id:int}
     */
    public static function createShop(string $shopName, string $username, string $password,
                                      string $fullName = '', string $phone = '',
                                      string $planCode = '', string $note = 'registered'): array
    {
        $now = self::stamp();
        $shopId = (int) DB::table('shops')->insertGetId([
            'name' => $shopName,
            'currency' => (string) self::setting('currency', config('billing.default_currency', 'PKR')),
            'status' => 'ACTIVE',
            'plan_code' => $planCode,
            'app_name' => '',
            'created_at' => $now,
            'updated_at' => $now,
        ]);
        $userId = (int) DB::table('users')->insertGetId([
            'shop_id' => $shopId,
            'username' => $username,
            'password_hash' => password_hash($password, PASSWORD_DEFAULT),
            'full_name' => $fullName !== '' ? $fullName : $shopName,
            'phone' => $phone,
            'role' => 'owner',
            'fingerprint_enabled' => 0,
            'is_active' => 1,
            'created_at' => $now,
            'last_login_at' => $note === 'site signup' ? null : $now,
        ]);

        // PENDING until the panel approves, unless the host hands out a trial by default.
        $trial = (int) config('billing.trial_days', 0);
        if ($trial > 0) {
            self::extend($shopId, $trial, 'trial on sign-up');
        } else {
            DB::table('licences')->insert([
                'shop_id' => $shopId,
                'license_key' => self::newKey(),
                'status' => 'PENDING',
                'started_at' => $now,
                'expires_at' => 0,
                'note' => $note,
                'created_at' => $now,
            ]);
        }
        self::audit('register', $shopId, $username . ' · ' . $shopName . ' · ' . $note);

        return ['shop_id' => $shopId, 'user_id' => $userId];
    }

    // ---------------------------------------------------------------- devices

    /**
     * Records or refreshes the till that is calling. A new device may register while the
     * plan has a free seat; a released one is never re-activated here (that needs
     * bind()), and a blocked one stays blocked however hard the app tries.
     */
    public static function touch(array $context): ?array
    {
        $deviceId = trim((string) ($context['device_id'] ?? ''));
        if (strlen($deviceId) < 16) {
            return null;
        }
        $shopId = (int) ($context['shop']['id'] ?? 0);
        if ($shopId <= 0) {
            return null;
        }
        $input = $context['input'] ?? [];
        $existing = DB::table('app_devices')->where('shop_id', $shopId)->where('device_id', $deviceId)->first();

        if ($existing === null) {
            $seat = self::seat($context['shop']);
            if ($seat['limit'] > 0 && $seat['used'] >= $seat['limit']) {
                ApiException::deny('DEVICE_LIMIT', sprintf(
                    'This plan allows %d billing device%s and all of them are already linked. '
                    . 'Release a device in Settings › Device access, or ask for a bigger plan.',
                    $seat['limit'], $seat['limit'] === 1 ? '' : 's'), 409);
            }
            DB::table('app_devices')->insert([
                'shop_id' => $shopId,
                'device_id' => $deviceId,
                'device_name' => (string) ($input['device_name'] ?? ''),
                'os_version' => (string) ($input['os_version'] ?? ''),
                'app_version' => (string) ($context['app_version'] ?? ''),
                'status' => 'ACTIVE',
                'first_seen_at' => self::stamp(),
                'last_seen_at' => self::stamp(),
                'seen_count' => 1,
            ]);
            return (array) DB::table('app_devices')->where('shop_id', $shopId)
                ->where('device_id', $deviceId)->first();
        }

        $existing = (array) $existing;
        if (strtoupper((string) $existing['status']) === 'BLOCKED') {
            ApiException::deny('DEVICE_BLOCKED', 'This device has been blocked for the shop.', 403);
        }
        DB::table('app_devices')->where('id', (int) $existing['id'])->update([
            'last_seen_at' => self::stamp(),
            'app_version' => (string) ($context['app_version'] ?? $existing['app_version']),
            'device_name' => (string) ($input['device_name'] ?? $existing['device_name']),
            'seen_count' => (int) $existing['seen_count'] + 1,
        ]);
        return (array) DB::table('app_devices')->where('id', (int) $existing['id'])->first();
    }

    /** Links a signing-in account to its device — the only path that re-uses a seat. */
    public static function bind(array $context, int $shopId): void
    {
        $deviceId = trim((string) ($context['device_id'] ?? ''));
        if (strlen($deviceId) < 16) {
            return;
        }
        $foreign = DB::table('app_devices')->where('device_id', $deviceId)
            ->where('shop_id', '!=', $shopId)->where('status', 'ACTIVE')->first();
        if ($foreign !== null) {
            ApiException::deny('DEVICE_MISMATCH',
                'This device is linked to another billing account. Sign in there, or ask for the seat to be released.', 409);
        }
        $row = DB::table('app_devices')->where('shop_id', $shopId)->where('device_id', $deviceId)->first();
        if ($row !== null) {
            $row = (array) $row;
            if (strtoupper((string) $row['status']) === 'BLOCKED') {
                ApiException::deny('DEVICE_BLOCKED', 'This device has been blocked for the shop.', 403);
            }
            DB::table('app_devices')->where('id', (int) $row['id'])->update([
                'status' => 'ACTIVE',
                'released_at' => null,
                'last_seen_at' => self::stamp(),
                'app_version' => (string) ($context['app_version'] ?? $row['app_version']),
            ]);
            return;
        }
        self::touch($context);
    }

    public static function releaseDevice(int $shopId, string $deviceId): bool
    {
        $updated = DB::table('app_devices')->where('shop_id', $shopId)->where('device_id', $deviceId)
            ->where('status', 'ACTIVE')->update([
                'status' => 'RELEASED', 'released_at' => self::stamp(), 'last_seen_at' => self::stamp(),
            ]);
        if ($updated > 0) {
            // The seat must mean "signed in too": an old access token is worthless once
            // the till it belongs to has been released.
            DB::table('refresh_tokens')->where('device_id', $deviceId)->whereNull('revoked_at')
                ->update(['revoked_at' => self::stamp()]);
        }
        return $updated > 0;
    }

    // ----------------------------------------------------------------- tokens

    public static function hashToken(string $token): string
    {
        return hash('sha256', $token);
    }

    public static function findToken(string $token): ?array
    {
        $row = DB::table('api_tokens')->where('token_hash', self::hashToken($token))->first();
        return $row === null ? null : (array) $row;
    }

    /** Why a stored token must not be accepted, or '' when it may. */
    public static function tokenRejection(array $row): string
    {
        if (!empty($row['revoked_at'])) {
            return 'TOKEN_REVOKED';
        }
        $expires = (int) ($row['expires_at'] ?? 0);
        if ($expires > 0 && $expires < time()) {
            return 'TOKEN_EXPIRED';
        }
        return '';
    }

    public static function issueToken(int $shopId, string $label, array $scopes = ['read', 'write'],
                                      int $days = 0): array
    {
        $clean = [];
        foreach ($scopes as $scope) {
            $scope = strtolower(trim((string) $scope));
            if (in_array($scope, ['read', 'write'], true) && !in_array($scope, $clean, true)) {
                $clean[] = $scope;
            }
        }
        if (!$clean) {
            $clean = ['read'];
        }
        $token = 'qt_pat_' . bin2hex(random_bytes(24));
        $id = DB::table('api_tokens')->insertGetId([
            'shop_id' => $shopId,
            'label' => mb_substr($label !== '' ? $label : 'API token', 0, 60),
            'token_hash' => self::hashToken($token),
            'token_prefix' => substr($token, 0, 12),
            'scopes' => implode(',', $clean),
            'expires_at' => $days > 0 ? time() + $days * 86400 : 0,
            'created_at' => self::stamp(),
        ]);
        return ['token' => $token, 'id' => $id];
    }

    public static function revokeToken(int $tokenId, int $shopId): bool
    {
        return DB::table('api_tokens')->where('id', $tokenId)->where('shop_id', $shopId)
            ->whereNull('revoked_at')->update(['revoked_at' => self::stamp()]) > 0;
    }

    public static function scopeAllows(array $context, string $scope): bool
    {
        if (($context['via'] ?? 'session') !== 'token') {
            return true;   // a signed-in till may always write its own shop's data
        }
        return in_array($scope, $context['scopes'] ?? [], true);
    }

    /** A token is a shop key, not a session: records only, never the licence or devices. */
    public static function tokenMayReach(string $auth): bool
    {
        return $auth === 'shop';
    }

    // --------------------------------------------------------------- sessions

    public static function jwt(array $claims): string
    {
        $secret = (string) config('billing.jwt_secret');
        if ($secret === '') {
            ApiException::deny('NOT_CONFIGURED', 'The server cannot sign sessions yet: BILLING_JWT_SECRET is empty.', 500);
        }
        $header = self::base64Url(json_encode(['alg' => 'HS256', 'typ' => 'JWT'], JSON_THROW_ON_ERROR));
        $now = time();
        $body = self::base64Url(json_encode(array_merge(
            ['iat' => $now, 'exp' => $now + (int) config('billing.jwt_ttl', 7200)], $claims), JSON_THROW_ON_ERROR));
        return $header . '.' . $body . '.'
            . self::base64Url(hash_hmac('sha256', $header . '.' . $body, $secret, true));
    }

    public static function jwtVerify(string $token): ?array
    {
        $parts = explode('.', $token);
        if (count($parts) !== 3) {
            return null;
        }
        [$header, $body, $signature] = $parts;
        $expected = self::base64Url(hash_hmac('sha256', $header . '.' . $body,
            (string) config('billing.jwt_secret'), true));
        if (!hash_equals($expected, $signature)) {
            return null;
        }
        $claims = json_decode(self::base64UrlDecode($body), true);
        if (!is_array($claims) || ($claims['exp'] ?? 0) < time()) {
            return null;
        }
        return $claims;
    }

    private static function base64Url(string $value): string
    {
        return rtrim(strtr(base64_encode($value), '+/', '-_'), '=');
    }

    private static function base64UrlDecode(string $value): string
    {
        return (string) base64_decode(strtr($value, '-_', '+/'), true);
    }

    /** The pair the app stores; only the refresh token's hash survives here. */
    public static function tokensFor(int $userId, string $deviceId): array
    {
        $refresh = bin2hex(random_bytes(32));
        DB::table('refresh_tokens')->insert([
            'user_id' => $userId,
            'token_hash' => self::hashToken($refresh),
            'device_id' => $deviceId,
            'expires_at' => time() + 86400 * (int) config('billing.refresh_ttl_days', 30),
            'created_at' => self::stamp(),
        ]);
        return [
            'access_token' => self::jwt(['sub' => $userId, 'dev' => $deviceId]),
            'refresh_token' => $refresh,
            'expires_in' => (int) config('billing.jwt_ttl', 7200),
            'token_type' => 'Bearer',
        ];
    }

    public static function takeRefresh(string $raw): ?array
    {
        $row = DB::table('refresh_tokens')->where('token_hash', self::hashToken($raw))
            ->whereNull('revoked_at')->first();
        $row = $row === null ? null : (array) $row;
        if ($row === null || (int) $row['expires_at'] < time()) {
            return null;
        }
        DB::table('refresh_tokens')->where('id', (int) $row['id'])->update(['revoked_at' => self::stamp()]);
        return $row;
    }

    public static function revokeSessions(int $userId, string $deviceId = ''): int
    {
        $query = DB::table('refresh_tokens')->where('user_id', $userId)->whereNull('revoked_at');
        if ($deviceId !== '') {
            $query->where('device_id', $deviceId);
        }
        return $query->update(['revoked_at' => self::stamp()]) > 0 ? 1 : 0;
    }

    // -------------------------------------------------------------- credentials

    /** The build credentials, from .env or from the api_clients table (several builds live). */
    public static function client(string $appId, string $apiKey): ?array
    {
        if ($appId !== '' && $apiKey !== '') {
            $envId = (string) config('billing.app_id');
            $envKey = (string) config('billing.app_api_key');
            if ($envId !== '' && $envKey !== '' && hash_equals($envId, $appId) && hash_equals($envKey, $apiKey)) {
                return ['app_id' => $envId, 'name' => 'config', 'default_plan' => '', 'is_active' => 1];
            }
            $row = DB::table('api_clients')->where('app_id', $appId)->where('api_key', $apiKey)
                ->where('is_active', 1)->first();
            if ($row !== null) {
                return (array) $row;
            }
        }
        return null;
    }

    // -------------------------------------------------------------- the store

    /**
     * Last-write-wins upsert of one record, keyed by (shop, entity, uuid).
     * @return bool whether the row was written (false = an older push than what we hold)
     */
    public static function upsert(int $shopId, string $entity, string $uuid, array $payload,
                                  string $updatedAt, ?string $deletedAt, string $origin): bool
    {
        $payload['__device'] = $origin;
        $encoded = json_encode($payload, JSON_UNESCAPED_UNICODE);
        $existing = DB::table('sync_records')->where('shop_id', $shopId)->where('entity', $entity)
            ->where('uuid', $uuid)->first();

        if ($existing === null) {
            DB::table('sync_records')->insert([
                'shop_id' => $shopId, 'entity' => $entity, 'uuid' => $uuid,
                'payload' => $encoded, 'updated_at' => $updatedAt, 'deleted_at' => $deletedAt,
            ]);
            return true;
        }
        if (strtotime($updatedAt) < strtotime((string) $existing->updated_at)) {
            return false;
        }
        DB::table('sync_records')->where('id', (int) $existing->id)->update([
            'payload' => $encoded, 'updated_at' => $updatedAt, 'deleted_at' => $deletedAt,
        ]);
        return true;
    }

    /** Stores one record and returns the payload that was kept, with its business time. */
    public static function store(int $shopId, string $entity, array $payload, string $origin): array
    {
        $uuid = trim((string) ($payload['uuid'] ?? ''));
        if ($uuid === '') {
            $uuid = bin2hex(random_bytes(16));
        }
        $payload['uuid'] = $uuid;

        $occurredMs = self::toMs($payload['created_at'] ?? null);
        $updatedAtMs = self::toMs($payload['updated_at'] ?? null);
        if ($occurredMs === 0) {
            $occurredMs = $updatedAtMs ?: self::nowMs();
        }
        if ($updatedAtMs === 0) {
            $updatedAtMs = self::nowMs();
        }

        // Timestamps are stored as the caller wrote them, because the receiving app
        // accepts both millis and "Y-m-d H:i:s"; only the index columns are normalised.
        $payload['_occurred_ms'] = $occurredMs;
        $deletedAt = empty($payload['deleted_at']) ? null : self::cleanTime((string) $payload['deleted_at']);
        self::upsert($shopId, $entity, $uuid, $payload, date('Y-m-d H:i:s', (int) ($updatedAtMs / 1000)),
            $deletedAt, $origin);
        DB::table('sync_records')->where('shop_id', $shopId)->where('entity', $entity)->where('uuid', $uuid)
            ->update([
                'occurred_ms' => $occurredMs,
                'occurred_at' => date('Y-m-d H:i:s', (int) ($occurredMs / 1000)),
            ]);
        return $payload;
    }

    /**
     * Decoded records for one shop + entity, newest business time first.
     * @param array $filter from_ms, to_ms, limit, offset, q, include_deleted
     * @return array{rows:array,total:int}
     */
    public static function records(int $shopId, string $entity, array $filter = []): array
    {
        $query = DB::table('sync_records')->where('shop_id', $shopId)->where('entity', $entity);
        if (empty($filter['include_deleted'])) {
            $query->whereNull('deleted_at');
        }
        if (!empty($filter['from_ms'])) {
            $query->where('occurred_ms', '>=', (int) $filter['from_ms']);
        }
        if (!empty($filter['to_ms'])) {
            $query->where('occurred_ms', '<=', (int) $filter['to_ms']);
        }
        if (!empty($filter['q'])) {
            $needle = str_replace(['%', '_'], ['\%', '\_'], (string) $filter['q']);
            $query->where('payload', 'like', '%' . $needle . '%');
        }

        $total = (clone $query)->count();
        $limit = max(1, min(500, (int) ($filter['limit'] ?? 100)));
        $offset = max(0, (int) ($filter['offset'] ?? 0));
        $rows = [];
        foreach ($query->orderByDesc('occurred_ms')->orderByDesc('id')->skip($offset)->take($limit)->get() as $record) {
            $payload = json_decode((string) $record->payload, true);
            if (!is_array($payload)) {
                continue;
            }
            unset($payload['__device']);
            if (!empty($record->deleted_at)) {
                $payload['deleted_at'] = (string) $record->deleted_at;
            }
            $payload['_occurred_ms'] = (int) $record->occurred_ms;
            $payload['_synced_at'] = (string) $record->updated_at;
            $rows[] = $payload;
        }
        return ['rows' => $rows, 'total' => $total];
    }

    public static function billShape(array $row): array
    {
        $createdMs = self::toMs($row['created_at'] ?? null) ?: (int) ($row['_occurred_ms'] ?? 0);
        $items = [];
        foreach (is_array($row['items'] ?? null) ? $row['items'] : [] as $item) {
            if (!is_array($item)) {
                continue;
            }
            $qty = (int) ($item['qty'] ?? 0);
            $price = (float) ($item['price'] ?? 0);
            $items[] = [
                'name' => (string) ($item['name'] ?? 'Item'),
                'price' => $price,
                'qty' => $qty,
                'line_total' => (float) ($item['line_total'] ?? round($price * $qty, 2)),
                'product_uuid' => (string) ($item['product_uuid'] ?? ''),
            ];
        }
        $subtotal = (float) ($row['subtotal'] ?? array_sum(array_column($items, 'line_total')));
        return [
            'uuid' => (string) ($row['uuid'] ?? ''),
            'invoice_no' => (string) ($row['invoice_no'] ?? ''),
            'created_at_ms' => $createdMs,
            'created_at' => date('Y-m-d H:i:s', (int) ($createdMs / 1000)),
            'date' => date('Y-m-d', (int) ($createdMs / 1000)),
            'order_type' => (string) ($row['order_type'] ?? 'TAKE_AWAY'),
            'table_no' => (string) ($row['table_no'] ?? ''),
            'customer_name' => (string) ($row['customer_name'] ?? ''),
            'customer_phone' => (string) ($row['customer_phone'] ?? ''),
            'customer_code' => (int) ($row['customer_code'] ?? 0),
            'address' => (string) ($row['address'] ?? ''),
            'notes' => (string) ($row['notes'] ?? ''),
            'subtotal' => round($subtotal, 2),
            'discount' => (float) ($row['discount'] ?? 0),
            'tax' => (float) ($row['tax'] ?? 0),
            'total' => (float) ($row['total'] ?? $subtotal),
            'status' => (string) ($row['status'] ?? ((isset($row['paid']) && !$row['paid']) ? 'unpaid' : 'paid')),
            'payment_method' => (string) ($row['payment_method'] ?? 'CASH'),
            'source' => (string) ($row['source'] ?? 'till'),
            'items' => $items,
        ];
    }

    /** One pass over the shop's bills: totals, mix and top sellers, for a day or a range. */
    public static function summary(array $shop, int $fromMs, int $toMs): array
    {
        $bills = [];
        $offset = 0;
        do {
            $page = self::records((int) $shop['id'], 'orders',
                ['from_ms' => $fromMs, 'to_ms' => $toMs, 'limit' => 500, 'offset' => $offset]);
            foreach ($page['rows'] as $row) {
                $bills[] = self::billShape($row);
            }
            $count = count($page['rows']);
            $offset += $count;
        } while ($count === 500 && $offset < 5000);

        $revenue = 0.0; $paidSum = 0.0; $unpaidSum = 0.0; $discountSum = 0.0; $taxSum = 0.0;
        $byMethod = []; $byType = []; $products = [];
        foreach ($bills as $bill) {
            $revenue += $bill['total'];
            if ($bill['status'] === 'paid') {
                $paidSum += $bill['total'];
            } else {
                $unpaidSum += $bill['total'];
            }
            $discountSum += $bill['discount'];
            $taxSum += $bill['tax'];
            $method = $bill['payment_method'] ?: 'CASH';
            $byMethod[$method] = round(($byMethod[$method] ?? 0) + $bill['total'], 2);
            $type = $bill['order_type'] ?: 'TAKE_AWAY';
            $byType[$type] = round(($byType[$type] ?? 0) + $bill['total'], 2);
            foreach ($bill['items'] as $item) {
                $key = $item['name'];
                $products[$key] ??= ['name' => $key, 'qty' => 0, 'revenue' => 0.0];
                $products[$key]['qty'] += $item['qty'];
                $products[$key]['revenue'] = round($products[$key]['revenue'] + $item['line_total'], 2);
            }
        }
        usort($products, static fn ($a, $b) => $b['revenue'] <=> $a['revenue']);
        $count = count($bills);

        return [
            'currency' => (string) ($shop['currency'] ?? 'PKR'),
            'bills' => $bills,
            'bill_count' => $count,
            'revenue' => round($revenue, 2),
            'paid' => round($paidSum, 2),
            'unpaid' => round($unpaidSum, 2),
            'discount' => round($discountSum, 2),
            'tax' => round($taxSum, 2),
            'average_bill' => $count > 0 ? round($revenue / $count, 2) : 0.0,
            'by_payment_method' => $byMethod,
            'by_order_type' => $byType,
            'top_products' => array_slice(array_values($products), 0, 10),
            'server_time_ms' => self::nowMs(),
        ];
    }

    public static function hourBuckets(array $bills): array
    {
        $buckets = [];
        for ($hour = 0; $hour < 24; $hour++) {
            $buckets[$hour] = ['hour' => sprintf('%02d:00', $hour), 'bills' => 0, 'revenue' => 0.0];
        }
        foreach ($bills as $bill) {
            $hour = (int) date('G', (int) ($bill['created_at_ms'] / 1000));
            $buckets[$hour]['bills']++;
            $buckets[$hour]['revenue'] = round($buckets[$hour]['revenue'] + $bill['total'], 2);
        }
        return array_values($buckets);
    }

    /** from/to as YYYY-MM-DD (or millis) → [fromMs, toMs]; default = last 30 days. */
    public static function dateRange(array $input): array
    {
        $fromRaw = trim((string) ($input['from'] ?? ''));
        $toRaw = trim((string) ($input['to'] ?? ''));
        $fromMs = $fromRaw === '' ? 0 : self::toMs($fromRaw);
        if ($fromRaw !== '' && $fromMs === 0) {
            $fromMs = strtotime($fromRaw . ' 00:00:00') * 1000 ?: 0;
        }
        $toMs = $toRaw === '' ? 0 : self::toMs($toRaw);
        if (preg_match('/^\d{4}-\d{2}-\d{2}$/', $toRaw)) {
            // "to=2025-01-03" means the whole of the 3rd, not its first second — the most
            // common way an integration quietly loses the last day of a month.
            $toMs = (strtotime($toRaw . ' 23:59:59') * 1000) + 999;
        } elseif ($toRaw !== '' && $toMs === 0) {
            $toMs = (strtotime($toRaw . ' 23:59:59') ?: 0) * 1000;
        }
        if ($fromMs === 0 && $toMs === 0) {
            $toMs = self::nowMs();
            $fromMs = $toMs - 30 * 86400 * 1000;
        } elseif ($toMs === 0) {
            $toMs = self::nowMs();
        }
        return [$fromMs, $toMs];
    }

    /** INV-YYYYMMDD-### when a web bill has no invoice number of its own. */
    public static function nextInvoice(int $shopId, int $createdMs): string
    {
        $prefix = 'INV-' . date('Ymd', (int) ($createdMs / 1000));
        $used = DB::table('sync_records')->where('shop_id', $shopId)->where('entity', 'orders')
            ->where('payload', 'like', '%' . $prefix . '%')->count();
        $candidate = sprintf('%s-%03d', $prefix, 1 + $used);
        while (DB::table('sync_records')->where('shop_id', $shopId)->where('entity', 'orders')
            ->where('payload', 'like', '%"invoice_no":"' . $candidate . '"%')->exists()) {
            $candidate = sprintf('%s-%03d', $prefix, ++$used + 1);
        }
        return $candidate;
    }

    /**
     * Moves catalogue stock for the products a bill sold.
     *
     * $previous is what the same uuid held before this write, so stock changes by the
     * difference: a retried POST sells nothing twice, and an edit that drops a line gives
     * the units back. -1 or a missing stock means "not counted" and is never touched.
     */
    public static function reduceStock(int $shopId, array $lines, string $origin, array $previous = []): array
    {
        $sold = [];
        foreach ($lines as $line) {
            $uuid = (string) ($line['product_uuid'] ?? '');
            if ($uuid !== '') {
                $sold[$uuid] = ($sold[$uuid] ?? 0) + (int) ($line['qty'] ?? 0);
            }
        }
        foreach ($previous as $line) {
            $uuid = (string) ($line['product_uuid'] ?? '');
            if ($uuid !== '') {
                $sold[$uuid] = ($sold[$uuid] ?? 0) - (int) ($line['qty'] ?? 0);
            }
        }

        $byUuid = [];
        foreach (self::records($shopId, 'products', ['limit' => 500])['rows'] as $product) {
            $byUuid[(string) ($product['uuid'] ?? '')] = $product;
        }

        $changed = [];
        foreach ($sold as $uuid => $delta) {
            if ($delta === 0 || !isset($byUuid[$uuid])) {
                continue;
            }
            $product = $byUuid[$uuid];
            if (!isset($product['stock']) || !is_numeric($product['stock'])) {
                continue;
            }
            $product['stock'] = (int) $product['stock'] - $delta;
            $product['updated_at'] = self::nowMs();
            $byUuid[$uuid] = $product;
            self::store($shopId, 'products', $product, $origin);
            $changed[] = ['uuid' => $uuid, 'name' => (string) ($product['name'] ?? ''),
                          'stock' => $product['stock'], 'delta' => -$delta];
        }
        return $changed;
    }

    // ------------------------------------------------------------------ audit

    public static function audit(string $action, int $shopId, string $detail, string $actor = ''): void
    {
        try {
            DB::table('audit_log')->insert([
                'actor' => mb_substr($actor !== '' ? $actor : (string) (auth()->guard('admin')->user()->username ?? 'system'), 0, 64),
                'action' => $action,
                'shop_id' => $shopId,
                'detail' => mb_substr($detail, 0, 400),
                'created_at' => self::stamp(),
            ]);
        } catch (Throwable) {
            // An audit row is never worth failing a shopkeeper's request over.
        }
    }
}
