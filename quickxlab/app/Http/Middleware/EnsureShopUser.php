<?php

namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\Response;

/** The shop owner's own screens (/account) — a row in `users`, signed in on the site. */
class EnsureShopUser
{
    public function handle(Request $request, Closure $next): Response
    {
        if (! $request->session()->has('shop_user_id')) {
            return redirect()->route('account.login');
        }
        return $next($request);
    }
}
