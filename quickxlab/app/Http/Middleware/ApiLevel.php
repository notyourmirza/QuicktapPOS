<?php

namespace App\Http\Middleware;

use App\Support\Gate;
use Closure;
use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\Response;

/**
 * `api.level:<auth>[:licence]` — the one place that decides who may call an endpoint and
 * whether an active licence is required for it.
 *
 * Declaring it as route middleware (instead of inside each handler) is what keeps a
 * future endpoint from being written without the rules: a forgotten check here is a
 * missing middleware, which is visible in `php artisan route:list`.
 */
class ApiLevel
{
    public function handle(Request $request, Closure $next, string $level, ?string $licence = null): Response
    {
        // Built once per request so the handler and every helper agree on who is calling.
        $context = Gate::context($request, $level);
        if ($licence === 'licence') {
            Gate::licence($context);
        }
        $request->attributes->set('qt.context', $context);

        return $next($request);
    }
}
