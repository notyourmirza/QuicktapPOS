<?php
/**
 * The panel and the shop-owner screens authenticate against the tables the billing
 * system already owns, so no users table is invented next to them and a password
 * changed anywhere works everywhere (password_hash is bcrypt in both).
 */

return [
    'defaults' => ['guard' => 'web', 'passwords' => 'shops'],

    'guards' => [
        'web' => ['driver' => 'session', 'provider' => 'shops'],
        'admin' => ['driver' => 'session', 'provider' => 'admins'],
    ],

    'providers' => [
        'shops' => ['driver' => 'database', 'table' => 'users', 'column' => 'username'],
        'admins' => ['driver' => 'database', 'table' => 'admin_users', 'column' => 'username'],
    ],

    'passwords' => [
        'shops' => ['provider' => 'shops', 'table' => 'password_reset_tokens', 'expire' => 60, 'throttle' => 60],
    ],

    'password_timeout' => 10800,
];
