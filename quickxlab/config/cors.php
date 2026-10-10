<?php

return [
    'paths' => ['api/*'],
    'allowed_methods' => ['GET', 'POST', 'OPTIONS'],
    'allowed_origins' => array_values(array_filter([env('BILLING_CORS_ORIGIN')])),
    'allowed_origins_patterns' => [],
    // The headers the contract needs; X-Api-Token is what a website or desktop client sends.
    'allowed_headers' => ['Content-Type', 'Authorization', 'X-App-Id', 'X-Api-Key', 'X-App-Version', 'X-Device-Id', 'X-Api-Token'],
    'exposed_headers' => [],
    'max_age' => 600,
    'supports_credentials' => false,
];
