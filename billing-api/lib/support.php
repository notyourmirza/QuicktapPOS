<?php
/**
 * Shared plumbing for the QuickTap billing API: configuration, the response
 * envelope the Android client parses, the database handle, the app-credential
 * check, JWT sessions and small helpers. Kept deliberately plain so it can be
 * read (and audited) on any shared host.
 */

declare(strict_types=1);

require_once __DIR__ . '/license.php';
require_once __DIR__ . '/routes.php';

// -------------------------------------------------------------------- config

/** Reads config.php once per request. */
function qt_config(): array
{
    static $config = null;
    if ($config !== null) {
        return $config;
    }
    // QT_CONFIG lets the tests (and a second staging install) point at another file.
    $file = (string) (getenv('QT_CONFIG') ?: dirname(__DIR__) . '/config.php');
    if (!is_file($file)) {
        qt_json(false, 'The API is not configured yet. Copy config.example.php to config.php.',
            null, 'NOT_CONFIGURED', 500);
    }
    $loaded = require $file;
    if (!is_array($loaded)) {
        qt_json(false, 'config.php must return an array.', null, 'BAD_CONFIG', 500);
    }
    $config = $loaded + [
        'driver' => 'mysql',
        'jwt_ttl' => 3600,
        'refresh_ttl_days' => 60,
        'cors_origin' => '',
        'debug' => false,
    ];
    return $config;
}

/** One PDO connection for the whole request. */
function qt_db(): PDO
{
    static $pdo = null;
    if ($pdo instanceof PDO) {
        return $pdo;
    }
    $config = qt_config();
    $options = [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES => false,
    ];
    if (($config['driver'] ?? 'mysql') === 'sqlite') {
        $path = $config['sqlite_path'] ?? (dirname(__DIR__) . '/storage/quicktap.sqlite');
        if (!is_dir(dirname($path))) {
            mkdir(dirname($path), 0775, true);
        }
        $pdo = new PDO('sqlite:' . $path, null, null, $options);
        $pdo->exec('PRAGMA foreign_keys = ON');
        return $pdo;
    }
    $dsn = sprintf(
        'mysql:host=%s;port=%d;dbname=%s;charset=utf8mb4',
        $config['db_host'] ?? '127.0.0.1',
        (int) ($config['db_port'] ?? 3306),
        $config['db_name'] ?? 'quicktap'
    );
    $pdo = new PDO($dsn, $config['db_user'] ?? '', $config['db_pass'] ?? '', $options);
    return $pdo;
}

/** True when the schema is present; used to give a clear error instead of a 500. */
function qt_db_ready(): bool
{
    try {
        qt_db()->query('SELECT 1 FROM api_clients LIMIT 1');
        return true;
    } catch (Throwable $e) {
        return false;
    }
}

// ------------------------------------------------------------------- envelope

/** The only way this API answers a JSON call. */
function qt_json(bool $success, string $message, $data = null, string $code = '', int $status = 200): void
{
    if (!headers_sent()) {
        http_response_code($status);
        header('Content-Type: application/json; charset=utf-8');
        header('Cache-Control: no-store');
    }
    echo json_encode([
        'success' => $success,
        'message' => $message,
        'data' => $data,
        'code' => $code !== '' ? $code : ($success ? 'OK' : 'ERROR'),
        'timestamp' => (int) (microtime(true) * 1000),
    ], JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    exit;
}

/** A business rule refused the action: 4xx plus the machine-readable code. */
function qt_deny(string $code, string $message, int $status = 403): void
{
    qt_json(false, $message, null, $code, $status);
}

/** Reads the JSON body, falling back to form fields (handy for curl testing). */
function qt_input(): array
{
    static $parsed = null;
    if ($parsed !== null) {
        return $parsed;
    }
    $raw = file_get_contents('php://input');
    $data = [];
    if (is_string($raw) && $raw !== '') {
        $decoded = json_decode($raw, true);
        if (is_array($decoded)) {
            $data = $decoded;
        }
    }
    if (!$data) {
        $data = $_POST;
    }
    // The app posts JSON; query parameters are the fallback (GETs use those).
    $parsed = is_array($data) ? $data : [];
    return array_merge($_GET, $parsed);
}

function qt_string(array $input, string $key, string $default = ''): string
{
    if (!isset($input[$key]) || !is_scalar($input[$key])) {
        return $default;
    }
    return trim((string) $input[$key]);
}

function qt_int(array $input, string $key, int $default = 0): int
{
    if (!isset($input[$key]) || !is_scalar($input[$key]) || $input[$key] === '') {
        return $default;
    }
    return (int) $input[$key];
}

function qt_now_ms(): int
{
    return (int) (microtime(true) * 1000);
}

function qt_cors_headers(array $config): void
{
    $origin = $config['cors_origin'] ?? '';
    if ($origin === '' || headers_sent()) {
        return;
    }
    header('Access-Control-Allow-Origin: ' . $origin);
    header('Vary: Origin');
    header('Access-Control-Allow-Headers: Content-Type, Authorization, X-App-Id, X-Api-Key, X-App-Version, X-Device-Id');
    header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
    header('Access-Control-Max-Age: 600');
    if (strtoupper($_SERVER['REQUEST_METHOD'] ?? '') === 'OPTIONS') {
        http_response_code(204);
        exit;
    }
}

// ------------------------------------------------------------- request routing

/** The path below the base directory, e.g. "v1/license/status". */
function qt_request_path(): string
{
    $uri = $_SERVER['REQUEST_URI'] ?? '/';
    $path = parse_url($uri, PHP_URL_PATH) ?: '/';
    $script = rtrim(str_replace('\\', '/', dirname($_SERVER['SCRIPT_NAME'] ?? '')), '/');
    if ($script !== '' && strpos($path, $script) === 0) {
        $path = substr($path, strlen($script));
    }
    return trim($path, '/');
}

/**
 * The route table: HTTP verb + path → handler, required auth level, and whether an
 * active licence is needed. This is the only list of endpoints; index.php prints it
 * and the tests walk it, so a new route cannot be "forgotten" in the docs.
 *
 *   auth 'app'  → any verified app build may call it (no sign-in)
 *   auth 'user' → a valid access token of an active user is required
 */
function qt_routes(): array
{
    return [
        // open before sign-in: bootstrap + the small public configuration
        'GET v1/app/bootstrap'       => ['qt_app_bootstrap', 'app', false],
        'GET v1/app/config'         => ['qt_app_config', 'app', false],
        // session
        'POST v1/auth/register'      => ['qt_auth_register', 'app', false],
        'POST v1/auth/login'         => ['qt_auth_login', 'app', false],
        'POST v1/auth/refresh'       => ['qt_auth_refresh', 'app', false],
        'POST v1/auth/logout'        => ['qt_auth_logout', 'user', false],
        'GET v1/auth/me'             => ['qt_auth_me', 'user', false],
        'POST v1/auth/unlock'        => ['qt_auth_unlock', 'user', false],
        'POST v1/auth/fingerprint'   => ['qt_auth_fingerprint', 'user', false],
        // billing / licence
        'GET v1/license/status'      => ['qt_license_status', 'user', false],
        'POST v1/license/verify'     => ['qt_license_verify', 'user', false],
        'POST v1/license/confirm'    => ['qt_license_confirm', 'user', false],
        'GET v1/plans'               => ['qt_plans', 'app', false],
        // device accounting
        'GET v1/devices'             => ['qt_devices_list', 'user', false],
        'POST v1/devices/deactivate' => ['qt_devices_deactivate', 'user', false],
        // remote configuration writes back through these
        'POST v1/settings/app-name'  => ['qt_settings_app_name', 'user', false],
        'GET v1/notifications'       => ['qt_notifications', 'user', false],
        // two-way catalogue sync (the app's legacy "Sync now")
        'POST v1/sync/push'          => ['qt_sync_push', 'user', true],
        'GET v1/sync/pull'           => ['qt_sync_pull', 'user', true],
    ];
}

/**
 * Dispatches one request. The two global rules — who may call at all, and whether
 * an active licence is required — are decided here, in one place, so no handler
 * can forget the gate.
 */
function qt_route(string $method, string $path): void
{
    $routes = qt_routes();
    $entry = $routes[$method . ' ' . $path] ?? null;
    if ($entry === null) {
        qt_deny('NOT_FOUND', 'Unknown endpoint: ' . $method . ' ' . $path, 404);
    }
    [$handler, $auth, $needsLicence] = $entry;

    // One place decides the two global rules: who may call at all, and whether an
    // active licence is required. Handlers therefore never re-implement the gate.
    $context = qt_context($auth);
    if ($needsLicence) {
        qt_require_licence($context);
    }
    call_user_func($handler, qt_input(), $context);
    qt_deny('NOT_HANDLED', 'The request was not handled.', 500);
}

/**
 * Everything an endpoint needs: the verified app client, the signed-in user (when
 * a token was sent) and this device's fingerprint. Also enforces the two rules
 * the app trusts: the licence must be ACTIVE for data-modifying endpoints, and a
 * device may only ever talk for the shop it was registered to.
 */
function qt_context(string $auth = 'app'): array
{
    $config = qt_config();
    if (!qt_db_ready()) {
        qt_deny('DB_MISSING', 'The database schema is not installed. Run install.php', 500);
    }

    $appId = (string) ($_SERVER['HTTP_X_APP_ID'] ?? '');
    $apiKey = (string) ($_SERVER['HTTP_X_API_KEY'] ?? '');
    $client = qt_find_client($appId, $apiKey);
    if ($client === null) {
        qt_deny('NO_API_KEY', 'Missing or unknown X-App-Id / X-Api-Key.', 401);
    }

    $context = [
        'config' => $config,
        'input' => qt_input(),
        'client' => $client,
        // The header is authoritative; a body field is accepted as a fallback so a
        // hand-written curl test (or an older build) still binds a device properly.
        'device_id' => (string) ($_SERVER['HTTP_X_DEVICE_ID'] ?? ''),
        'app_version' => (string) ($_SERVER['HTTP_X_APP_VERSION'] ?? ''),
        'user' => null,
        'shop' => null,
        'licence' => null,
    ];
    if ($context['device_id'] === '' && !empty($context['input']['device_id'])) {
        $candidate = (string) $context['input']['device_id'];
        $context['device_id'] = preg_match('/^[0-9a-f]{16,128}$/i', $candidate) ? $candidate : '';
    }

    if ($auth === 'user') {
        $token = qt_bearer_token();
        if ($token === '') {
            qt_deny('NO_TOKEN', 'Sign in again to continue.', 401);
        }
        qt_who_is_calling($context, $token, true);
    } elseif (qt_bearer_token() !== '') {
        // 'app' endpoints answer without a session, but a till that happens to be
        // signed in sends its token anyway — and then it gets ITS shop's branding
        // (v1/app/config is per shop). A stale token is simply ignored here.
        qt_who_is_calling($context, qt_bearer_token(), false);
    }

    return $context;
}

/**
 * Resolves the bearer token into user + shop + licence state and writes them into
 * the context. When $required is true a broken session is a refusal; when it is
 * false the request continues as anonymous, which is what the pre-login screens
 * need: they must never be locked out by their own expired token.
 */
function qt_who_is_calling(array &$context, string $token, bool $required): void
{
    $config = $context['config'];
    $claims = qt_jwt_verify($token, $config);
    if ($claims === null) {
        if ($required) {
            qt_deny('TOKEN_INVALID', 'Your session is not valid. Sign in again.', 401);
        }
        return;
    }
    $user = qt_load_user((int) ($claims['sub'] ?? 0));
    if ($user === null) {
        if ($required) {
            qt_deny('USER_DISABLED', 'This account is no longer active.', 403);
        }
        return;
    }
    if ($context['device_id'] !== '' && !empty($user['device_id'])
        && $user['device_id'] !== $context['device_id']) {
        if ($required) {
            qt_deny('DEVICE_MISMATCH', 'This account is bound to a different device.', 403);
        }
        return;
    }
    $shop = qt_load_shop((int) $user['shop_id']);
    if ($shop === null) {
        if ($required) {
            qt_deny('SHOP_INACTIVE', 'This shop is not available.', 403);
        }
        return;
    }
    if (strtoupper((string) ($shop['status'] ?? '')) === 'BLOCKED') {
        if ($required) {
            qt_deny('ACCOUNT_BLOCKED', 'This account has been blocked.', 403);
        }
        // A blocked shop may still be told its own name, so the identity is kept.
    }
    if (strtoupper((string) ($shop['status'] ?? '')) === 'SUSPENDED' && $required) {
        qt_deny('SHOP_INACTIVE', 'This account is suspended. Contact support.', 403);
    }
    $context['user'] = $user;
    $context['shop'] = $shop;
    $context['licence'] = qt_license_state($shop, $user, $context['device_id']);
}

/** The gate the app's licence screen is built around. */
function qt_require_licence(array $context): void
{
    $licence = $context['licence'];
    if (($licence['state'] ?? 'UNKNOWN') === 'ACTIVE') {
        return;
    }
    $map = [
        'EXPIRED' => ['LICENSE_EXPIRED', 'Your licence has expired. Renew it to keep billing.'],
        'PENDING' => ['LICENSE_PENDING', 'Your licence is waiting for activation.'],
        'REVOKED' => ['LICENSE_REVOKED', 'This licence has been revoked.'],
        'SUSPENDED' => ['SHOP_INACTIVE', 'This account is suspended.'],
        'BLOCKED' => ['ACCOUNT_BLOCKED', 'This account has been blocked.'],
    ];
    [$code, $message] = $map[$licence['state'] ?? ''] ?? ['LICENSE_INACTIVE', 'This licence is not active.'];
    qt_deny($code, $message, 403);
}

function qt_bearer_token(): string
{
    $header = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
    if ($header === '' && function_exists('apache_request_headers')) {
        $headers = apache_request_headers();
        $header = is_array($headers) ? ($headers['Authorization'] ?? '') : '';
    }
    if (stripos($header, 'bearer ') === 0) {
        return trim(substr($header, 7));
    }
    return '';
}

// ------------------------------------------------------------- app credentials

function qt_find_client(string $appId, string $apiKey): ?array
{
    if ($appId === '' || $apiKey === '') {
        return null;
    }
    $statement = qt_db()->prepare('SELECT * FROM api_clients WHERE app_id = ? LIMIT 1');
    $statement->execute([$appId]);
    $client = $statement->fetch() ?: null;
    if ($client === null) {
        // config.php may carry the pair instead (a host where the install has not
        // been run against the database yet, or a read-only deployment).
        $config = qt_config();
        if ($appId === (string) ($config['app_id'] ?? '')
            && hash_equals((string) ($config['app_api_key'] ?? ''), $apiKey)) {
            return ['app_id' => $appId, 'api_key' => $apiKey, 'name' => 'config',
                    'default_plan' => (string) ($config['default_plan'] ?? ''),
                    'is_active' => 1];
        }
        return null;
    }
    if (!hash_equals((string) $client['api_key'], $apiKey)) {
        return null;
    }
    if ((int) ($client['is_active'] ?? 1) !== 1) {
        return null;
    }
    return $client;
}

// -------------------------------------------------------------------- JWT (HS256)

function qt_jwt_issue(array $payload, array $config): string
{
    $header = qt_base64url(json_encode(['alg' => 'HS256', 'typ' => 'JWT'], JSON_THROW_ON_ERROR));
    $now = time();
    $payload = array_merge(['iat' => $now, 'exp' => $now + (int) $config['jwt_ttl']], $payload);
    $body = qt_base64url(json_encode($payload, JSON_THROW_ON_ERROR));
    $signature = qt_base64url(hash_hmac('sha256', $header . '.' . $body, $config['jwt_secret'], true));
    return $header . '.' . $body . '.' . $signature;
}

function qt_jwt_verify(string $token, array $config): ?array
{
    $parts = explode('.', $token);
    if (count($parts) !== 3) {
        return null;
    }
    [$header, $body, $signature] = $parts;
    $expected = qt_base64url(hash_hmac('sha256', $header . '.' . $body, $config['jwt_secret'], true));
    if (!hash_equals($expected, $signature)) {
        return null;
    }
    $claims = json_decode(qt_base64url_decode($body), true);
    if (!is_array($claims)) {
        return null;
    }
    if (($claims['exp'] ?? 0) < time()) {
        return null;
    }
    return $claims;
}

function qt_base64url(string $value): string
{
    return rtrim(strtr(base64_encode($value), '+/', '-_'), '=');
}

function qt_base64url_decode(string $value): string
{
    $pad = strlen($value) % 4;
    if ($pad > 0) {
        $value .= str_repeat('=', 4 - $pad);
    }
    return (string) base64_decode(strtr($value, '-_', '+/'));
}

// ------------------------------------------------------------------- db helpers

function qt_row(string $sql, array $bindings = []): ?array
{
    $statement = qt_db()->prepare($sql);
    $statement->execute($bindings);
    $row = $statement->fetch();
    return $row === false ? null : $row;
}

function qt_rows(string $sql, array $bindings = []): array
{
    $statement = qt_db()->prepare($sql);
    $statement->execute($bindings);
    return $statement->fetchAll() ?: [];
}

function qt_run(string $sql, array $bindings = []): void
{
    $statement = qt_db()->prepare($sql);
    $statement->execute($bindings);
}

function qt_insert(string $table, array $values): int
{
    $columns = array_keys($values);
    $sql = sprintf(
        'INSERT INTO %s (%s) VALUES (%s)',
        $table,
        '`' . implode('`,`', $columns) . '`',
        ':' . implode(',:', $columns)
    );
    qt_db()->prepare($sql)->execute(qt_named($values));
    return (int) qt_db()->lastInsertId();
}

/** Some PDO builds insist on the colon in an execute() array, so always add it. */
function qt_named(array $values): array
{
    $named = [];
    foreach ($values as $key => $value) {
        $named[(strpos((string) $key, ':') === 0 ? $key : ':' . $key)] = $value;
    }
    return $named;
}

function qt_update(string $table, array $values, string $where, array $bindings = []): void
{
    $sets = [];
    foreach (array_keys($values) as $column) {
        $sets[] = sprintf('`%s` = :%s', $column, $column);
    }
    $sql = sprintf('UPDATE %s SET %s WHERE %s', $table, implode(', ', $sets), $where);
    qt_db()->prepare($sql)->execute(array_merge(qt_named($values), qt_named($bindings)));
}

/** ISO timestamp for storage; the app converts it back for display. */
function qt_timestamp(?int $millis = null): string
{
    $millis = $millis ?? qt_now_ms();
    return date('Y-m-d H:i:s', (int) ($millis / 1000));
}

/** Milliseconds from a stored datetime (or null when unset). */
function qt_millis(?string $databaseTime): int
{
    if ($databaseTime === null || $databaseTime === '' || $databaseTime === '0000-00-00 00:00:00') {
        return 0;
    }
    $normalized = str_replace('T', ' ', $databaseTime);
    $normalized = (string) preg_replace('/\.\d+/', '', $normalized);
    $normalized = (string) preg_replace('/Z$/', '', $normalized);
    $time = strtotime(trim($normalized));
    return $time === false ? 0 : $time * 1000;
}

/** A driver-portable "now" so inserts do not depend on MySQL functions. */
function qt_sql_now(): string
{
    return date('Y-m-d H:i:s');
}
