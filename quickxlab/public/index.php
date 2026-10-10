<?php

use Illuminate\Http\Request;

define('LARAVEL_START', microtime(true));

// A shared host has the app one level above the document root, so index.php is copied
// into public_html/ and needs this line; `..` is the app root either way.
if (file_exists($autoload = __DIR__ . '/../vendor/autoload.php')) {
    require $autoload;
} elseif (file_exists($autoload = __DIR__ . '/../../quickxlab/vendor/autoload.php')) {
    require $autoload;
} else {
    http_response_code(503);
    header('Content-Type: text/plain; charset=utf-8');
    echo "Dependencies are not installed.\n\nRun:  composer install --no-dev --optimize-autoloader\n"
       . "Then:  cp .env.example .env && php artisan key:generate && php artisan migrate --force\n";
    exit;
}

$app = require_once __DIR__ . '/../bootstrap/app.php';

$app->handleRequest(Request::capture());
