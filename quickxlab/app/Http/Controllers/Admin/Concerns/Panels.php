<?php

namespace App\Http\Controllers\Admin\Concerns;

use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;

/**
 * Small panel plumbing shared by every admin screen: the flash line, and the rule that a
 * state-changing click must come from a form that carried the session token (Laravel's
 * VerifyCsrfToken middleware covers it; the double-check is for the actions that a
 * half-deployed cache could otherwise let through).
 */
trait Panels
{
    protected function flash(Request $request, string $message, bool $bad = false): RedirectResponse
    {
        $request->session()->put($bad ? 'flash.bad' : 'flash.ok', $message);

        return redirect($request->headers->get('referer', route('admin.dashboard')));
    }

    protected function admin(Request $request): array
    {
        return (array) $request->session()->get('admin') ?? [];
    }
}
