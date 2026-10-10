<?php
/**
 * The knobs the API contract depends on. Everything here is also readable from the
 * panel (app_settings) when a value is meant to change per shop or per day; the pair
 * below the line is deployment-level and lives in .env only, because the answer a till
 * must fail closed on cannot depend on a row somebody might delete.
 */

return [
    'app_id' => env('BILLING_APP_ID', ''),
    'app_api_key' => env('BILLING_APP_API_KEY', ''),
    'jwt_secret' => env('BILLING_JWT_SECRET', ''),
    'jwt_ttl' => (int) env('BILLING_JWT_TTL', 7200),
    'refresh_ttl_days' => (int) env('BILLING_REFRESH_TTL_DAYS', 30),
    'api_base_url' => env('BILLING_API_BASE_URL', ''),
    'trial_days' => (int) env('BILLING_TRIAL_DAYS', 0),
    'default_currency' => env('BILLING_DEFAULT_CURRENCY', 'PKR'),
    'cors_origin' => env('BILLING_CORS_ORIGIN', ''),
    'sync_batch_limit' => (int) env('BILLING_SYNC_BATCH_LIMIT', 500),
    'offline_grace_hours' => (int) env('BILLING_OFFLINE_GRACE_HOURS', 24),
    'install_key' => env('BILLING_INSTALL_KEY', ''),

    /*
    |--------------------------------------------------------------------------
    | First-run content
    |--------------------------------------------------------------------------
    |
    | What `php artisan db:seed` writes into an empty database. Every insert is
    | idempotent and only fills holes: a host that already has shops keeps its
    | own prices and settings. Change prices later in the panel — they are rows,
    | not code, and the app reads them from /v1/app/plans.
    |
    */

    'seed' => [
        'admin_username' => env('SEED_ADMIN_USERNAME', 'quickadmin'),
        'admin_password' => env('SEED_ADMIN_PASSWORD', ''),

        'settings' => [
            'app_name' => env('SEED_APP_NAME', 'QuickTap POS'),
            'currency' => env('BILLING_DEFAULT_CURRENCY', 'PKR'),
            'support_whatsapp' => env('SUPPORT_WHATSAPP', '923114836011'),
            'license_sync_minutes' => '5',
            'offline_grace_hours' => (string) env('BILLING_OFFLINE_GRACE_HOURS', '72'),
            'auto_lock_minutes' => '0',
            'tax_percent' => '0',
            'receipt_template' => 'classic',
            'receipt_footer' => 'Shukriya! Aap ka business, QuickTap ke saath.',
            'api_base_url' => env('BILLING_API_BASE_URL', ''),
            'min_app_version' => env('SEED_MIN_APP_VERSION', '1.3.0'),
            'force_update' => '0',
        ],

        'plans' => [
            ['code' => 'free', 'name' => 'POS Core', 'tagline' => 'Chhoti dukan, shuru karne ke liye — hamesha free.',
             'tag' => 'FREE', 'price' => 0, 'price_yearly' => 0, 'devices' => 1, 'sort' => 1,
             'features' => ['Roz ki bill aur report', 'Ek counter', 'Offline billing + Drive backup', 'WhatsApp support']],
            ['code' => 'retail', 'name' => 'Retail Pro', 'tagline' => 'Poori billing, inventory aur customers ek app me.',
             'tag' => 'MOST POPULAR', 'price' => 799, 'price_yearly' => 7990, 'devices' => 3, 'sort' => 2,
             'features' => ['Bill + GST, 300 products 10 minute me import', 'Barcode scanner aur USB printer',
                 'Khata, due list aur WhatsApp reminder', 'Kasouli aur low-stock alert', 'Din/week/saal report']],
            ['code' => 'books', 'name' => 'Books & Khata', 'tagline' => 'Kitaon ka hisab — Urdu aur English dono.',
             'tag' => 'URDU + ENGLISH', 'price' => 599, 'price_yearly' => 5990, 'devices' => 2, 'sort' => 3,
             'features' => ['Udhaari khata, party-wise', 'Sale/return aur kist ka hisab',
                 'Printable bill (80mm) aur PDF', 'Ek data, phone aur website par']],
            ['code' => 'chain', 'name' => 'Chain / Franchise', 'tagline' => 'Multi-outlet, counter-wise report aur role control.',
             'tag' => '', 'price' => 2499, 'price_yearly' => 24990, 'devices' => 12, 'sort' => 4,
             'features' => ['jitne counters utni seats', 'outlet-wise comparison', 'staff role aur pin control',
                 'API token (website aur desktop ke liye)', 'priority support']],
        ],
    ],

    'firebase' => [
        'app_id' => env('FIREBASE_APP_ID', ''),
        'api_key' => env('FIREBASE_API_KEY', ''),
        'project_id' => env('FIREBASE_PROJECT_ID', ''),
        'sender_id' => env('FIREBASE_SENDER_ID', ''),
    ],
];
