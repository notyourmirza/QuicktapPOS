<?php

return [
    'default' => env('FILESYSTEM_DISK', 'local'),
    'disks' => [
        'local' => ['driver' => 'local', 'root' => storage_path('app'), 'throw' => false],
        'public' => ['driver' => 'local', 'root' => storage_path('app/public'), 'url' => rtrim(env('APP_URL', ''), '/') . '/storage', 'visibility' => 'public', 'throw' => false],
        // The APK an admin uploads for the public /download page.
        'apk' => ['driver' => 'local', 'root' => public_path('apk'), 'url' => rtrim(env('APP_URL', ''), '/') . '/apk', 'throw' => false],
    ],
    'links' => [public_path('storage') => storage_path('app/public')],
];
