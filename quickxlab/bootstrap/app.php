<?php

use App\Support\ApiException;
use Illuminate\Foundation\Application;
use Illuminate\Foundation\Configuration\Exceptions;
use Illuminate\Foundation\Configuration\Middleware;
use Illuminate\Http\Request;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;

/**
 * One application, three audiences:
 *
 *   /            the public QuickXLab site (Blade, no build step)
 *   /account     a shop owner's own screen — licence, seats, tokens, APK
 *   /admin       the panel: shops, licences, devices, codes, plans, settings, pages, tokens, bills
 *   /api/v1/…    the JSON contract the Android till, a website and a desktop program call
 *
 * Everything reads and writes the same MySQL database the billing system already used,
 * so moving here changes no data and no client except the address the APK was built with.
 */
return Application::configure(basePath: dirname(__DIR__))
    ->withRouting(
        web: __DIR__ . '/../routes/web.php',
        api: __DIR__ . '/../routes/api.php',
        commands: __DIR__ . '/../routes/console.php',
        apiPrefix: 'api',
        health: '/up',
    )
    ->withMiddleware(function (Middleware $middleware) {
        $middleware->alias([
            'api.level' => \App\Http\Middleware\ApiLevel::class,
            'admin' => \App\Http\Middleware\EnsureAdmin::class,
            'account' => \App\Http\Middleware\EnsureShopUser::class,
        ]);
        // The API is stateless; the CSRF token belongs to the site and the panel only.
        $middleware->validateCsrfTokens(except: ['api/*']);
    })
    ->withExceptions(function (Exceptions $exceptions) {
        // Every API answer — success or refusal — uses the same envelope, and the app
        // branches on `code`. An exception that escapes a controller must not break that,
        // or a till would see HTML and fail closed for a reason nobody can read.
        $exceptions->renderable(function (ApiException $exception, Request $request) {
            if (! $request->is('api/*')) {
                return null;
            }
            return response()->json([
                'success' => false,
                'message' => $exception->getMessage(),
                'data' => null,
                'code' => $exception->errorCode ?: 'ERROR',
                'timestamp' => (int) (microtime(true) * 1000),
            ], $exception->status);
        });

        $exceptions->renderable(function (NotFoundHttpException $exception, Request $request) {
            if (! $request->is('api/*')) {
                return null;
            }
            return response()->json([
                'success' => false,
                'message' => 'Unknown endpoint: ' . $request->method() . ' ' . $request->path(),
                'data' => null,
                'code' => 'NOT_FOUND',
                'timestamp' => (int) (microtime(true) * 1000),
            ], 404);
        });

        // A host whose schema is not installed yet must say so, in the envelope, rather
        // than answer 500 with a stack trace.
        $exceptions->renderable(function (Throwable $exception, Request $request) {
            if (! $request->is('api/*')) {
                return null;
            }
            report($exception);
            return response()->json([
                'success' => false,
                'message' => config('app.debug')
                    ? get_class($exception) . ': ' . $exception->getMessage()
                    : 'The server could not handle this request. Please try again.',
                'data' => null,
                'code' => 'SERVER_ERROR',
                'timestamp' => (int) (microtime(true) * 1000),
            ], 500);
        });
    })->create();
