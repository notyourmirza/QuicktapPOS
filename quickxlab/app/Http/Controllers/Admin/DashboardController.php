<?php

namespace App\Http\Controllers\Admin;

use App\Http\Controllers\Admin\Concerns\Panels;
use App\Http\Controllers\Controller;
use App\Support\Billing;
use Illuminate\Support\Facades\DB;
use Illuminate\View\View;

/**
 * The one screen that answers "kya sab theek hai?" before opening a ticket: how many
 * shops are live, who is about to expire, what was billed today, and what the support
 * queue looks like.
 */
class DashboardController extends Controller
{
    use Panels;

    public function index(): View
    {
        $now = time();
        $dayStart = (int) (strtotime(date('Y-m-d') . ' 00:00:00') * 1000);
        $dayEnd = (int) (strtotime(date('Y-m-d') . ' 23:59:59') * 1000 + 999);

        $bills = DB::table('sync_records')->where('entity', 'orders')->whereNull('deleted_at')
            ->whereBetween('occurred_ms', [$dayStart, $dayEnd])->limit(4000)->get();
        $revenue = 0.0;
        $count = 0;
        foreach ($bills as $row) {
            $payload = json_decode((string) $row->payload, true);
            if (!is_array($payload)) {
                continue;
            }
            $revenue += (float) ($payload['total'] ?? 0);
            $count++;
        }

        return view('admin.dashboard', [
            'stats' => [
                ['Shops', DB::table('shops')->count(), route('admin.shops')],
                ['Blocked', DB::table('shops')->where('status', 'BLOCKED')->count(), route('admin.shops') . '?status=BLOCKED'],
                ['Active tills', DB::table('app_devices')->where('status', 'ACTIVE')->count(), route('admin.devices')],
                ['Bills today', $count, route('admin.bills') . '?from=' . date('Y-m-d')],
                ['Revenue today', number_format($revenue, 2), route('admin.revenue')],
                ['Tokens', DB::table('api_tokens')->whereNull('revoked_at')->count(), route('admin.tokens')],
            ],
            'expiring' => DB::table('licences')->where('status', 'ACTIVE')
                ->where('expires_at', '>', 0)->where('expires_at', '<=', $now + 7 * 86400)
                ->orderBy('expires_at')->limit(12)->get()->map(function ($row) {
                    return (array) $row + ['shop' => (array) DB::table('shops')->where('id', $row->shop_id)->first()];
                })->all(),
            'pending' => DB::table('licences')->where('status', 'PENDING')->orderByDesc('id')->limit(8)
                ->get()->map(function ($row) {
                    return (array) $row + ['shop' => (array) DB::table('shops')->where('id', $row->shop_id)->first()];
                })->all(),
            'tickets' => DB::table('support_tickets')->where('status', 'OPEN')->orderByDesc('id')->limit(8)->get()->map(
                fn ($row) => (array) $row)->all(),
            'activity' => DB::table('audit_log')->orderByDesc('id')->limit(15)->get()->map(fn ($row) => (array) $row)->all(),
            'days' => Billing::dateRange([]),
        ]);
    }
}
