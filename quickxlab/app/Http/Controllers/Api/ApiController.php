<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Support\ApiException;
use App\Support\Billing;
use App\Support\Gate;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

/**
 * The shared shape of every API answer: one envelope, always the same keys, so a till
 * can branch on `code` and show `message` without knowing which endpoint answered.
 *
 * @see ../../LICENSE_API.md for the contract this file exists to satisfy.
 */
abstract class ApiController extends Controller
{
    public function __construct(protected Request $request)
    {
    }

    /**
     * Attaches the caller to the context once a request has identified them itself
     * (register and login have no session to read yet, but the device seat rules still
     * need to know which shop is calling).
     */
    protected function adopt(?array $user, ?array $shop): void
    {
        $context = $this->ctx();
        $context['user'] = $user;
        $context['shop'] = $shop;
        $context['licence'] = is_array($shop)
            ? Billing::state($shop, $user, (string) $context['device_id'])
            : null;
        $this->request->attributes->set('qt.context', $context);
    }

    protected function ctx(): array
    {
        return $this->request->attributes->get('qt.context')
            ?? throw new ApiException('This endpoint is not wired to the auth middleware.', 'NOT_HANDLED', 500);
    }

    protected function input(): array
    {
        return $this->ctx()['input'] ?? [];
    }

    protected function shop(): array
    {
        $shop = $this->ctx()['shop'] ?? null;
        if (!is_array($shop)) {
            ApiException::deny('LICENSE_NOT_FOUND', 'No billing account was found for this caller.', 403);
        }
        return $shop;
    }

    protected function shopId(): int
    {
        return (int) ($this->shop()['id'] ?? 0);
    }

    protected function origin(): string
    {
        return Gate::origin($this->ctx());
    }

    protected function needWrite(): void
    {
        Gate::scope($this->ctx(), 'write');
    }

    /** Numbers stay numbers: the app reads `optDouble` / `optInt`, never strings. */
    protected function str(string $key, string $default = ''): string
    {
        $value = $this->input()[$key] ?? null;
        if (is_array($value) || is_object($value)) {
            return $default;
        }
        $value = trim((string) $value);
        return $value === '' ? $default : $value;
    }

    protected function int(string $key, int $default = 0): int
    {
        $value = $this->input()[$key] ?? null;
        return is_numeric($value) ? (int) $value : $default;
    }

    protected function float(string $key, float $default = 0.0): float
    {
        $value = $this->input()[$key] ?? null;
        return is_numeric($value) ? (float) $value : $default;
    }

    protected function ok(string $message, array $data = [], int $status = 200): JsonResponse
    {
        return response()->json([
            'success' => true,
            'message' => $message,
            'data' => (object) $data,
            'code' => 'OK',
            'timestamp' => Billing::nowMs(),
        ], $status);
    }

    protected function fail(string $code, string $message, int $status = 422): JsonResponse
    {
        throw new ApiException($message, $code, $status);
    }
}
