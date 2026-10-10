<?php

return [
    'default' => env('LOG_CHANNEL', 'daily'),
    'deprecations' => ['channel' => 'single', 'trace' => false, 'level' => 'warning'],
    'channels' => [
        'single' => ['driver' => 'single', 'path' => storage_path('logs/laravel.log'), 'level' => 'debug'],
        'daily' => ['driver' => 'daily', 'path' => storage_path('logs/laravel.log'), 'level' => env('LOG_LEVEL', 'warning'), 'days' => 14],
        'stderr' => ['driver' => 'monolog', 'handler' => Monolog\Handler\StreamHandler::class, 'with' => ['stream' => 'php://stderr'], 'level' => 'debug'],
        'null' => ['driver' => 'monolog', 'handler' => Monolog\Handler\NullHandler::class],
    ],
];
