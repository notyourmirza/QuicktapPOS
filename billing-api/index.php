<?php
/**
 * QuickTap POS — billing API front controller.
 *
 * One file, no framework, no Composer. Every request the Android app makes
 * arrives here as /v1/<group>/<action>. The rules the app relies on:
 *
 *   - every answer uses the same envelope:
 *       {"success":bool,"message":string,"data":mixed,"code":string,"timestamp":int}
 *   - every request must carry X-App-Id + X-Api-Key (the app credentials)
 *   - the device fingerprint arrives as X-Device-Id and is bound to one shop
 *   - the licence answer (ACTIVE / EXPIRED / …) is decided HERE, never by the app
 *
 * Requires PHP 8.0+ and MySQL 5.7+/8.0 (or SQLite for local testing).
 */

declare(strict_types=1);

require __DIR__ . '/lib/support.php';

$config = qt_config();
qt_cors_headers($config);

$path = qt_request_path();
$method = strtoupper($_SERVER['REQUEST_METHOD'] ?? 'GET');

// The self-hosted admin panel is plain PHP under /admin and is served directly
// by the web server; anything else is the JSON API.
// Asking for the base URL is the "is it alive?" check, and it doubles as the list of
// endpoints a client can call — no separate status page to keep in sync.
if ($path === '' || $path === 'index.php' || $path === 'v1') {
    $endpoints = [];
    foreach (array_keys(qt_routes()) as $label) {
        [$verb, $route] = explode(' ', $label, 2);
        $endpoints[] = str_pad($verb, 5) . ' ' . $route;
    }
    qt_json(true, 'QuickTap billing API is running', [
        'time' => date('c'),
        'licence_gate' => 'every answer below v1/ carries the shop state; the app never decides',
        'endpoints' => $endpoints,
    ]);
}

try {
    qt_route($method, $path);
} catch (Throwable $exception) {
    // Never leak a stack trace to a till: log it, answer with the envelope.
    error_log('[quicktap-api] ' . $exception->getMessage()
        . ' @ ' . $path . ' (' . $exception->getFile() . ':' . $exception->getLine() . ')');
    $debug = !empty($config['debug']);
    qt_json(false, $debug
        ? 'Server error: ' . $exception->getMessage()
        : 'The server could not handle this request. Please try again.',
        null, 'SERVER_ERROR', 500);
}
