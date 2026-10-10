<?php
/**
 * Copy this file to config.php and fill it in. config.php is the only file that
 * holds secrets, and it is never committed.
 *
 * On Hostinger: create the MySQL database and user in hPanel → MySQL, then put
 * those values here. Everything else can stay as it is.
 */

return [
    // ---- database ----------------------------------------------------------
    // 'mysql' for a real host, 'sqlite' for a quick local test (no server needed).
    'driver' => 'mysql',
    'db_host' => 'localhost',
    'db_port' => 3306,
    'db_name' => 'u0000000_quicktap',
    'db_user' => 'u0000000_quicktap',
    'db_pass' => 'change-me',
    // Only used when driver = sqlite.
    'sqlite_path' => __DIR__ . '/storage/quicktap.sqlite',

    // ---- signing -----------------------------------------------------------
    // Generate once:  php -r "echo bin2hex(random_bytes(32)), PHP_EOL;"
    // Every access token is signed with this; changing it signs everybody out.
    'jwt_secret' => 'REPLACE-ME-64-HEX-CHARACTERS',
    'jwt_ttl' => 3600,           // access token lifetime in seconds
    'refresh_ttl_days' => 60,    // a till offline longer than this must sign in again

    // ---- the app credential -------------------------------------------------
    // Fallback pair for the X-App-Id / X-Api-Key check. The database (table
    // api_clients) is consulted first so several builds can be allowed at once.
    // install.php prints the pair it created — paste it here and in app/build.gradle.
    'app_id' => 'app_41f7d123706a',
    'app_api_key' => 'a94791db4b426d74bccd78155b2b4eedc773488ee7b4c7d2',
    // Plan a self-registering shop gets when it comes from a build with no row in
    // the api_clients table (the panel writes that table, this is the fallback).
    'default_plan' => 'starter',

    // ---- billing rules ------------------------------------------------------
    'trial_days' => 0,            // free days given to a self-registered shop (0 = must be approved)
    'default_currency' => 'PKR',
    'sync_batch_limit' => 200,    // max records per /v1/sync/push category
    // How often a till re-checks its licence, and how long it may bill offline, are
    // NOT here: they live in the app_settings table so the panel can change them
    // without a redeploy. See admin > App settings.

    // ---- administration -----------------------------------------------------
    // Needed only for install.php run from a browser; delete install.php after.
    'install_key' => '',
    'debug' => false,            // true shows real exception text instead of SERVER_ERROR

    // Set to an origin (e.g. 'https://admin.example.com') if a browser is ever
    // going to call this API. The Android app does not need CORS.
    'cors_origin' => '',
];
