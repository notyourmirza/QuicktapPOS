<?php

namespace App\Support;

use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;
use Throwable;

/**
 * Who is calling, and is this call allowed at all.
 *
 * Three levels, exactly as billing-api's route table declared them:
 *   app   the build credentials only; a session, if present, is used for per-shop answers
 *   user  a signed-in till (JWT)
 *   shop  a signed-in till OR a shop API token — what a website or desktop client uses
 *
 * The gate lives here rather than in each controller so that no endpoint can be written
 * later without the licence rule and the token rule.
 */
final class Gate
{
    /** @return array the request context every handler receives */
    public static function context(Request $request, string $auth = 'app'): array
    {
        self::guardSchema();

        $appId = (string) $request->header('X-App-Id', '');
        $apiKey = (string) $request->header('X-Api-Key', '');
        $client = Billing::client($appId, $apiKey);

        $bearer = self::bearer($request);
        $supplied = $bearer !== '' ? $bearer : (string) $request->header('X-Api-Token', '');
        $isPat = strncmp($supplied, 'qt_pat_', 7) === 0;

        // A shop's own API token is a credential of the same standing as the build pair,
        // so a browser page or a desktop program needs exactly one header to get going.
        if ($client === null && !$isPat) {
            ApiException::deny('NO_API_KEY',
                'Send X-App-Id and X-Api-Key (the app), or X-Api-Token (a website or desktop client).',
                401);
        }
        if ($client === null) {
            $client = ['app_id' => 'api-token', 'name' => 'api token', 'default_plan' => '', 'is_active' => 1];
        }

        $context = [
            'auth' => $auth,
            'input' => self::input($request),
            'client' => $client,
            // The header is authoritative; a body field is accepted so a hand-written curl
            // test (or an older build) still binds a device properly.
            'device_id' => (string) $request->header('X-Device-Id', ''),
            'app_version' => (string) $request->header('X-App-Version', ''),
            'user' => null,
            'shop' => null,
            'licence' => null,
            'via' => 'app',
            'scopes' => ['read', 'write'],
            'token_id' => 0,
        ];
        if ($context['device_id'] === '' && !empty($context['input']['device_id'])) {
            $candidate = (string) $context['input']['device_id'];
            $context['device_id'] = preg_match('/^[0-9a-f]{16,128}$/i', $candidate) ? $candidate : '';
        }

        if ($isPat) {
            $context['via'] = 'token';
            self::fromToken($context, $supplied);
        } elseif ($auth === 'user') {
            if ($supplied === '') {
                ApiException::deny('NO_TOKEN', 'Sign in again to continue.', 401);
            }
            $context['via'] = 'session';
            self::fromSession($context, $supplied, true);
        } elseif ($supplied !== '') {
            // A till that happens to be signed in sends its token anyway — and then it gets
            // ITS shop's branding. A stale token is simply ignored here.
            $context['via'] = 'session';
            self::fromSession($context, $supplied, false);
        }

        if ($auth !== 'app' && empty($context['shop'])) {
            ApiException::deny('NO_TOKEN', 'This endpoint needs a signed-in till or an API token.', 401);
        }

        // A token reaches the shop's records and nothing else, so a token that leaks off
        // a web server cannot extend a licence, free a seat or change settings.
        if ($context['via'] === 'token' && !Billing::tokenMayReach($auth)) {
            ApiException::deny('SCOPE_DENIED',
                'An API token may only call v1/shop, v1/catalog, v1/bills and v1/reports. '
                . 'That endpoint belongs to the till itself.', 403);
        }

        return $context;
    }

    /** The licence gate: every write to a shop's history passes through it. */
    public static function licence(array $context): void
    {
        $state = (string) ($context['licence']['state'] ?? 'UNKNOWN');
        if ($state === 'ACTIVE') {
            return;
        }
        $map = [
            'EXPIRED' => ['LICENSE_EXPIRED', 'Your licence has expired. Renew it to keep billing.'],
            'PENDING' => ['LICENSE_PENDING', 'Your licence is waiting for activation.'],
            'REVOKED' => ['LICENSE_REVOKED', 'This licence has been revoked.'],
            'SUSPENDED' => ['SHOP_INACTIVE', 'This account is suspended.'],
            'BLOCKED' => ['ACCOUNT_BLOCKED', 'This account has been blocked.'],
        ];
        [$code, $message] = $map[$state] ?? ['LICENSE_INACTIVE', 'This licence is not active.'];
        ApiException::deny($code, $message, 403);
    }

    public static function scope(array $context, string $scope): void
    {
        if (Billing::scopeAllows($context, $scope)) {
            return;
        }
        $scopes = $context['scopes'] ?? [];
        ApiException::deny('SCOPE_DENIED', sprintf(
            'This API token is %s-only; it may not change data. Create one with the "read,write" '
            . 'scope in the admin panel.', in_array('write', $scopes, true) ? 'write' : 'read'), 403);
    }

    public static function shopId(array $context): int
    {
        return (int) ($context['shop']['id'] ?? 0);
    }

    /** The device or token origin a write is attributed to, so a pull never echoes it back. */
    public static function origin(array $context): string
    {
        return (string) ($context['device_id'] ?: 'token:' . (int) ($context['token_id'] ?? 0));
    }

    // ----------------------------------------------------------------- helpers

    private static function fromToken(array &$context, string $token): void
    {
        if (strlen($token) < 20) {
            ApiException::deny('TOKEN_INVALID', 'That API token is not complete.', 401);
        }
        $row = Billing::findToken($token);
        if ($row === null) {
            ApiException::deny('TOKEN_INVALID', 'That API token is not recognised.', 401);
        }
        $refusal = Billing::tokenRejection($row);
        if ($refusal === 'TOKEN_REVOKED') {
            ApiException::deny('TOKEN_REVOKED', 'That API token was revoked.', 401);
        }
        if ($refusal === 'TOKEN_EXPIRED') {
            ApiException::deny('TOKEN_EXPIRED', 'That API token expired. Ask for a new one in the admin panel.', 401);
        }
        $shop = Billing::shop((int) $row['shop_id']);
        if ($shop === null) {
            ApiException::deny('SHOP_INACTIVE', 'The shop behind this token is not available.', 403);
        }
        $context['shop'] = $shop;
        $context['scopes'] = array_values(array_filter(array_map('trim',
            explode(',', (string) $row['scopes']))));
        $context['token_id'] = (int) $row['id'];
        $context['device_id'] = 'token:' . (int) $row['id'];
        $context['licence'] = Billing::state($shop, null, '');
        \Illuminate\Support\Facades\DB::table('api_tokens')->where('id', (int) $row['id'])
            ->update(['last_used_at' => Billing::stamp()]);
    }

    private static function fromSession(array &$context, string $token, bool $required): void
    {
        $claims = Billing::jwtVerify($token);
        if ($claims === null) {
            if ($required) {
                ApiException::deny(str_contains($token, '.') ? 'TOKEN_EXPIRED' : 'TOKEN_INVALID',
                    'Sign in again to continue.', 401);
            }
            return;
        }
        $user = Billing::user((int) ($claims['sub'] ?? 0));
        if ($user === null) {
            if ($required) {
                ApiException::deny('USER_DISABLED', 'This account is no longer active.', 403);
            }
            return;
        }
        $shop = Billing::shop((int) $user['shop_id']);
        if ($shop === null) {
            ApiException::deny('LICENSE_NOT_FOUND', 'No billing account was found for this session.', 403);
        }
        if (strtoupper((string) $shop['status']) === 'BLOCKED') {
            ApiException::deny('ACCOUNT_BLOCKED', 'This account has been blocked.', 403);
        }
        if (strtoupper((string) $shop['status']) === 'SUSPENDED') {
            ApiException::deny('SHOP_INACTIVE', 'This account is suspended.', 403);
        }
        $context['user'] = $user;
        $context['shop'] = $shop;
        if (($context['device_id'] ?? '') === '') {
            $context['device_id'] = (string) ($claims['dev'] ?? '');
        }
        $context['licence'] = Billing::state($shop, $user, (string) $context['device_id']);
    }

    private static function bearer(Request $request): string
    {
        $header = (string) $request->header('Authorization', '');
        if (stripos($header, 'bearer ') === 0) {
            return trim(substr($header, 7));
        }
        return '';
    }

    /** JSON body, with query parameters underneath — the app uses both. */
    public static function input(Request $request): array
    {
        $body = $request->isJson() ? (array) $request->json()->all() : [];
        if ($body === []) {
            $body = $request->request->all();
        }
        return array_merge($request->query(), $body);
    }

    private static function guardSchema(): void
    {
        static $checked = false;
        if ($checked) {
            return;
        }
        $checked = true;
        try {
            $missing = !Schema::hasTable('shops');
        } catch (Throwable $failure) {
            ApiException::deny('DB_MISSING',
                'The database is not reachable on this host. Check DB_* in .env, then run: php artisan migrate --force',
                500);
        }
        if ($missing) {
            ApiException::deny('DB_MISSING',
                'The database schema is not installed. Run: php artisan migrate --force', 500);
        }
    }
}
