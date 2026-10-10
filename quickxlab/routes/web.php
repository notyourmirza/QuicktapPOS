<?php

use App\Http\Controllers\AccountController;
use App\Http\Controllers\Admin\AuthController;
use App\Http\Controllers\Admin\BillingController;
use App\Http\Controllers\Admin\ContentController;
use App\Http\Controllers\Admin\DashboardController;
use App\Http\Controllers\Admin\DeviceController;
use App\Http\Controllers\Admin\ReportController;
use App\Http\Controllers\Admin\ShopController;
use App\Http\Controllers\Admin\TokenController;
use App\Http\Controllers\SiteController;
use Illuminate\Support\Facades\Route;

/*
 * The public site and the shop owner's own screens. Everything here is Blade on the
 * server: no npm, no build step, because the host is shared and a deploy should be one
 * `git pull`, not a compile.
 */
Route::get('/', [SiteController::class, 'home'])->name('home');
Route::get('/plans', [SiteController::class, 'plans'])->name('plans');
Route::get('/software', [SiteController::class, 'software'])->name('software');
Route::get('/download', [SiteController::class, 'download'])->name('download');
Route::get('/support', [SiteController::class, 'support'])->name('support');
Route::post('/support', [SiteController::class, 'sendTicket'])->middleware('throttle:6,1')->name('support.send');
Route::get('/developers', [SiteController::class, 'developers'])->name('developers');
Route::get('/privacy', [SiteController::class, 'legal'])->defaults('slug', 'privacy')->name('privacy');
Route::get('/terms', [SiteController::class, 'legal'])->defaults('slug', 'terms')->name('terms');
Route::get('/ref/{code}', [SiteController::class, 'refer'])->name('referral');

Route::get('/signup', [SiteController::class, 'showSignup'])->name('signup');
Route::post('/signup', [SiteController::class, 'signup'])->middleware('throttle:8,1')->name('signup.post');

Route::get('/login', [AccountController::class, 'showLogin'])->name('account.login');
Route::post('/login', [AccountController::class, 'login'])->middleware('throttle:10,1')->name('account.login.post');
Route::post('/logout', [AccountController::class, 'logout'])->name('account.logout');
Route::middleware('account')->group(function () {
    Route::get('/account', [AccountController::class, 'dashboard'])->name('account');
    Route::post('/account/token', [AccountController::class, 'issueToken'])->name('account.token');
    Route::post('/account/token/revoke', [AccountController::class, 'revokeToken'])->name('account.token.revoke');
    Route::post('/account/name', [AccountController::class, 'rename'])->name('account.name');
    Route::post('/account/device/release', [AccountController::class, 'releaseDevice'])->name('account.device.release');
});

/*
 * The panel. Sessions are Laravel's, the login is the same admin_users row install.php
 * created, and every action writes an audit row — the panel is the one place a licence
 * changes, so "who did it" is never optional.
 */
Route::prefix('admin')->name('admin.')->group(function () {
    Route::get('/login', [AuthController::class, 'show'])->name('login');
    Route::post('/login', [AuthController::class, 'login'])->middleware('throttle:10,1')->name('login.post');
    Route::post('/logout', [AuthController::class, 'logout'])->name('logout');

    Route::middleware('admin')->group(function () {
        Route::get('/', [DashboardController::class, 'index'])->name('dashboard');

        Route::get('/shops', [ShopController::class, 'index'])->name('shops');
        Route::get('/shops/{shop}', [ShopController::class, 'show'])->name('shop');
        Route::post('/shops/{shop}', [ShopController::class, 'act'])->name('shop.act');

        Route::get('/devices', [DeviceController::class, 'index'])->name('devices');
        Route::post('/devices/{device}', [DeviceController::class, 'act'])->name('device.act');

        Route::get('/billing', [BillingController::class, 'codes'])->name('codes');
        Route::post('/billing/codes', [BillingController::class, 'generateCode'])->name('codes.generate');
        Route::get('/plans', [BillingController::class, 'plans'])->name('plans');
        Route::post('/plans/{plan}', [BillingController::class, 'savePlan'])->name('plan.save');
        Route::post('/plans/{plan}/toggle', [BillingController::class, 'togglePlan'])->name('plan.toggle');

        Route::get('/tokens', [TokenController::class, 'index'])->name('tokens');
        Route::post('/tokens', [TokenController::class, 'issue'])->name('tokens.issue');
        Route::post('/tokens/{token}/revoke', [TokenController::class, 'revoke'])->name('tokens.revoke');

        Route::get('/bills', [ReportController::class, 'bills'])->name('bills');
        Route::get('/bills/export', [ReportController::class, 'export'])->name('bills.export');
        Route::get('/revenue', [ReportController::class, 'revenue'])->name('revenue');
        Route::get('/activity', [ReportController::class, 'activity'])->name('activity');

        Route::get('/pages', [ContentController::class, 'pages'])->name('pages');
        Route::post('/pages', [ContentController::class, 'savePage'])->name('page.save');
        Route::post('/pages/{page}/delete', [ContentController::class, 'deletePage'])->name('page.delete');
        Route::get('/notices', [ContentController::class, 'notices'])->name('notices');
        Route::post('/notices', [ContentController::class, 'saveNotice'])->name('notice.save');
        Route::post('/notices/{notice}/delete', [ContentController::class, 'deleteNotice'])->name('notice.delete');
        Route::get('/settings', [ContentController::class, 'settings'])->name('settings');
        Route::post('/settings', [ContentController::class, 'saveSettings'])->name('settings.save');
        Route::post('/settings/apk', [ContentController::class, 'uploadApk'])->name('settings.apk');
        Route::get('/tickets', [ContentController::class, 'tickets'])->name('tickets');
        Route::post('/tickets/{ticket}', [ContentController::class, 'answerTicket'])->name('ticket.answer');
    });
});

// A host that lost its rewrite rules still gets a JSON answer on the old health path.
Route::get('/api', fn () => redirect('/api/', 301));
