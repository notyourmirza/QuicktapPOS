<?php

namespace App\Support;

use RuntimeException;
use Throwable;

/**
 * A refusal the API answers with the envelope: `code` is what the till branches on,
 * `message` is what it shows the shopkeeper, `status` is the HTTP code.
 *
 * Throwing instead of echoing is the one deliberate difference from the plain-PHP
 * billing-api: Laravel still gets to finish the request (headers, CORS, logging), and a
 * forgotten `return` in a controller cannot send a half-written body.
 */
final class ApiException extends RuntimeException
{
    public function __construct(string $message = '', public string $errorCode = 'ERROR',
                                public int $status = 400, ?Throwable $previous = null)
    {
        parent::__construct($message, 0, $previous);
    }

    public static function deny(string $code, string $message, int $status = 403): never
    {
        throw new self($message, $code, $status);
    }

    /** The four answers a till must route to the licence screen, and nothing else. */
    public static function licenceRefusal(string $code): bool
    {
        return in_array($code, ['LICENSE_EXPIRED', 'LICENSE_PENDING', 'LICENSE_REVOKED',
            'LICENSE_INACTIVE', 'ACCOUNT_BLOCKED', 'SHOP_INACTIVE', 'DEVICE_BLOCKED',
            'USER_DISABLED', 'DEVICE_LIMIT', 'DEVICE_MISMATCH'], true);
    }
}
