<?php

/**
 * The two helpers Blade layouts use, plus the money/date formatters every screen wants.
 * They are plain functions loaded by composer's `autoload.files` — a `use` statement in a
 * view is not a thing, and one small file beats a copy-pasted `number_format` in nine
 * templates that then disagree about decimals.
 */

use Illuminate\Support\Facades\Route;

if (! function_exists('active')) {
    /** `on` for the nav link that is showing, so the layout stays free of route gymnastics. */
    function active(string ...$names): string
    {
        $current = optional(Route::current())->getName() ?: '';
        foreach ($names as $name) {
            if ($current === $name || str_starts_with($current, rtrim($name, '.*') . '.')) {
                return 'on';
            }
        }
        return '';
    }
}

if (! function_exists('auth_shop')) {
    /** Is a shop owner signed in to the site? (The panel has its own session.) */
    function auth_shop(): bool
    {
        return request()->session()->has('shop_user_id');
    }
}

if (! function_exists('money')) {
    function money(float|int|string|null $value, string $currency = 'PKR'): string
    {
        $number = rtrim(rtrim(number_format((float) $value, 2, '.', ','), '0'), '.') ?: '0';
        return $number . ' ' . $currency;
    }
}

if (! function_exists('stamp_date')) {
    function stamp_date(?string $mysql): string
    {
        if ($mysql === null || $mysql === '' || ! str_contains($mysql, '-')) {
            return '—';
        }
        $time = strtotime($mysql);

        return $time === false ? '—' : date('Y-m-d H:i', $time);
    }
}

if (! function_exists('ago')) {
    /** "4 h ago", "in 3 d" — the panel reads ages, not timestamps. */
    function ago(?string $mysql): string
    {
        if ($mysql === null || $mysql === '' || ! str_contains($mysql, '-')) {
            return 'never';
        }
        $time = strtotime($mysql);
        if ($time === false) {
            return 'never';
        }
        $delta = time() - $time;
        $past = $delta >= 0;
        $delta = abs($delta);
        $unit = match (true) {
            $delta < 90 => $delta . ' s',
            $delta < 5400 => round($delta / 60) . ' min',
            $delta < 172800 => round($delta / 3600) . ' h',
            $delta < 2592000 => round($delta / 86400) . ' d',
            default => round($delta / 2592000) . ' mo',
        };

        return $past ? $unit . ' ago' : 'in ' . $unit;
    }
}

if (! function_exists('days_left_label')) {
    function days_left_label(?int $days): string
    {
        if ($days === null) {
            return 'Lifetime';
        }
        if ($days < 0) {
            return 'Expired ' . abs($days) . ' d ago';
        }
        return $days === 0 ? 'Expires today' : $days . ' day' . ($days === 1 ? '' : 's') . ' left';
    }
}

if (! function_exists('state_pill')) {
    /** One colour rule for every state word in every table. */
    function state_pill(string $state): string
    {
        return match (strtoupper($state)) {
            'ACTIVE', 'PAID', 'DONE', 'OK', 'RELEASED' => '<span class="pill ok">' . e($state) . '</span>',
            'PENDING', 'SUSPENDED', 'WAITING', 'PARTLY', 'UNPAID', 'DUE', 'EXPIRING' =>
                '<span class="pill warn">' . e($state) . '</span>',
            'BLOCKED', 'EXPIRED', 'REVOKED', 'FAIL', 'ALERT', 'CANCELLED' =>
                '<span class="pill bad">' . e($state) . '</span>',
            default => '<span class="pill dim">' . e($state) . '</span>',
        };
    }
}

if (! function_exists('short_id')) {
    /** Device ids are 64 hex chars; tables need a handle people can read back over the phone. */
    function short_id(?string $id, int $head = 8, int $tail = 5): string
    {
        $id = (string) $id;
        if ($id === '') {
            return '—';
        }
        if (mb_strlen($id) <= $head + $tail + 1) {
            return $id;
        }
        return mb_substr($id, 0, $head) . '…' . mb_substr($id, -$tail);
    }
}
if (! function_exists('endpoint_help')) {
    /** What each API path does, in one line of shopkeeper Urdu-English. */
    function endpoint_help(string $path): string
    {
        $map = [
            'v1/app/bootstrap' => 'Till ko batata hai kaun se server par jana hai',
            'v1/app/config' => 'App branding, notices aur feature flags',
            'v1/app/notifications' => 'Sirf notifications ki list',
            'v1/app/plans' => 'Plans aur prices (jo panel me hain, wahi)',
            'v1/auth/register' => 'Naya shop + owner account (app ke andar se)',
            'v1/auth/login' => 'Username/password se JWT + refresh token',
            'v1/auth/refresh' => 'Access token dobara, usi device ke liye',
            'v1/auth/logout' => 'Is device ka session khatam',
            'v1/auth/me' => 'Jo signed in hai us ka profile',
            'v1/auth/unlock' => 'PIN / fingerprint unlock (failed attempts ka count)',
            'v1/license/fingerprint' => 'Machine fingerprint aur biometric settings',
            'v1/license/status' => 'Licence ka haal — yehi expired page chalata hai',
            'v1/license/verify' => 'Licence code verify / redeem',
            'v1/license/confirm' => 'User ne licence dekh liya — server par flag',
            'v1/license/devices' => 'Seats: kaun se till live hain, kaun release ho chuka hai',
            'v1/license/deactivate' => 'Is device ki seat free karta hai',
            'v1/shop' => 'Dukan ka record (naam, currency, plan, seats)',
            'v1/shop/name' => 'Receipt par chhapne wala naam',
            'v1/catalog' => 'Products + categories, dono ek saath',
            'v1/catalog/products' => 'Products (paginated)',
            'v1/catalog/categories' => 'Categories',
            'v1/catalog/products/save' => 'Product insert/update — stock yahin se sync hota hai',
            'v1/catalog/categories/save' => 'Category save',
            'v1/bills' => 'Bills parhna (from/to/q) aur likhna (POST)',
            'v1/bills/save' => 'Ek bill items ke saath; total server ginata hai',
            'v1/reports/day' => 'Aaj ka total (bills, revenue, dues, average)',
            'v1/reports/range' => 'Date range ka summary + per-day rows',
            'v1/sync/push' => 'Till ka local queue server par',
            'v1/sync/pull' => 'Server se doosre tills ka data neeche',
        ];
        if (isset($map[$path])) {
            return $map[$path];
        }

        return str_contains($path, 'sync')
            ? 'Till ke liye sync endpoint (app-only)'
            : 'App-only endpoint — token is par nahi chalta';
    }
}
