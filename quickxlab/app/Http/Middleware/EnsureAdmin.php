<?php

namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\Response;

/**
 * The panel's own guard. It reads admin_users (the table install.php created), so the
 * login a host already has keeps working after the move to Laravel.
 */
class EnsureAdmin
{
    public function handle(Request $request, Closure $next): Response
    {
        if (! $request->session()->has('admin_id')) {
            $request->session()->put('admin.intended', $request->fullUrl());
            return redirect()->route('admin.login')
                ->with('flash.bad', 'Please sign in to the panel again.');
        }
        return $next($request);
    }
}
