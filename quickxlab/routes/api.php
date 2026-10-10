<?php

use App\Http\Controllers\Api\PhoneController;
use App\Http\Controllers\Api\ShopController;
use App\Http\Controllers\Api\SyncController;
use Illuminate\Support\Facades\Route;

/**
 * The v1 contract, exactly as ../LICENSE_API.md describes it — the Android till, a
 * website and a desktop program all call these. `apiPrefix: 'api'` in bootstrap/app.php
 * puts every path under /api, so the APK's base URL is https://quickxlab.com/api/.
 *
 * The third argument of route() below is the auth level (see App\Support\Gate):
 *   app   build credentials only · user  a signed-in till · shop  till OR a shop token
 * and the fourth, `licence`, is the write gate — declared here rather than inside the
 * handlers so a new endpoint cannot be added without it.
 */
$on = static function (string $verb, string $path, string $controller, string $action,
                      string $level, bool $licence = false): void {
    Route::{$verb}('v1/' . $path, [$controller, $action])
        ->name('api.' . str_replace('/', '.', $path))
        ->middleware('api.level:' . $level . ($licence ? ':licence' : ''));
};

// ------------------------------------------------------------------ start-up
$on('get', 'app/bootstrap', PhoneController::class, 'bootstrap', 'app');
$on('get', 'app/config', PhoneController::class, 'config', 'app');

// ------------------------------------------------------------------ sessions
$on('post', 'auth/register', PhoneController::class, 'register', 'app');
$on('post', 'auth/login', PhoneController::class, 'login', 'app');
$on('post', 'auth/refresh', PhoneController::class, 'refresh', 'app');
$on('post', 'auth/logout', PhoneController::class, 'logout', 'user');
$on('get', 'auth/me', PhoneController::class, 'me', 'user');
$on('post', 'auth/unlock', PhoneController::class, 'unlock', 'user');
$on('post', 'auth/fingerprint', PhoneController::class, 'fingerprint', 'user');

// ------------------------------------------------------------------- licence
$on('get', 'license/status', PhoneController::class, 'status', 'user');
$on('post', 'license/verify', PhoneController::class, 'verify', 'user');
$on('post', 'license/confirm', PhoneController::class, 'confirm', 'user');
$on('get', 'plans', PhoneController::class, 'plans', 'app');

// -------------------------------------------------------- devices & settings
$on('get', 'devices', PhoneController::class, 'devices', 'user');
$on('post', 'devices/deactivate', PhoneController::class, 'deactivate', 'user');
$on('post', 'settings/app-name', PhoneController::class, 'appName', 'user');
$on('get', 'notifications', PhoneController::class, 'notifications', 'user');

// ------------------------------------------------------------------ the sync
$on('post', 'sync/push', SyncController::class, 'push', 'user', true);
$on('get', 'sync/pull', SyncController::class, 'pull', 'user', true);

// --------------------------------------------- the same billing, from web or PC
$on('get', 'shop', ShopController::class, 'shop', 'shop');
$on('get', 'catalog', ShopController::class, 'catalog', 'shop');
$on('post', 'catalog/products', ShopController::class, 'saveProducts', 'shop', true);
$on('post', 'catalog/categories', ShopController::class, 'saveCategories', 'shop', true);
$on('get', 'bills', ShopController::class, 'bills', 'shop');
$on('post', 'bills', ShopController::class, 'saveBill', 'shop', true);
$on('get', 'reports/day', ShopController::class, 'reportDay', 'shop');
$on('get', 'reports/range', ShopController::class, 'reportRange', 'shop');

// Landing on the base URL is the "is it alive?" check, and it doubles as the live list of
// endpoints — so a support ticket can start with a paste of this instead of a screenshot.
Route::get('/', static fn () => response()->json([
    'success' => true,
    'message' => 'QuickTap billing API is running',
    'data' => [
        'time' => date('c'),
        'licence_gate' => 'every answer below v1/ carries the shop state; the app never decides',
        'endpoints' => collect(Route::getRoutes()->getRoutes())
            ->filter(fn ($route) => str_starts_with($route->uri(), 'api/v1/'))
            ->map(fn ($route) => str_pad(implode('|', array_diff($route->methods(), ['HEAD'])), 5)
                . ' ' . substr($route->uri(), 4))
            ->values()->all(),
    ],
    'code' => 'OK',
    'timestamp' => App\Support\Billing::nowMs(),
]));
