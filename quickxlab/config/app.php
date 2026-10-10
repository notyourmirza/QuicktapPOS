<?php

return [
    'name' => env('APP_NAME', 'QuickTap POS'),
    'env' => env('APP_ENV', 'production'),
    'debug' => (bool) env('APP_DEBUG', false),
    'url' => env('APP_URL', 'https://quickxlab.com'),
    'frontend_cache_path' => storage_path('framework/cache/data'),
    'timezone' => env('APP_TIMEZONE', 'UTC'),
    'locale' => 'en',
    'fallback_locale' => 'en',
    'faker_locale' => 'en_US',
    'cipher' => 'AES-256-CBC',
    'key' => env('APP_KEY'),
    'previous_keys' => array_filter(explode(',', (string) env('PREVIOUS_APP_KEYS', ''))),
    'maintenance' => ['driver' => 'file'],
    // Framework and package providers. The application's own providers live in
    // bootstrap/providers.php, so nothing is registered twice.
    'providers' => \Illuminate\Support\ServiceProvider::defaultProviders()->toArray(),
];
