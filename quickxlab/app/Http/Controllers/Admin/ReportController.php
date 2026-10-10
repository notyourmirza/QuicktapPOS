<?php

namespace App\Http\Controllers\Admin;

use App\Http\Controllers\Admin\Concerns\Panels;
use App\Http\Controllers\Controller;
use App\Support\Billing;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\View\View;
use Symfony\Component\HttpFoundation\StreamedResponse;

/**
 * Bills and reports over the shared record store. Reading a shop's day means one pass over
 * the rows the tills pushed and the web page wrote — no second ledger to reconcile, which
 * is the whole point of putting the site and the till on the same table.
 */
class ReportController extends Controller
{
    use Panels;

    public function bills(Request $request): View
    {
        [$from, $to, $rows, $totals, $shop] = $this->collect($request);

        return view('admin.bills', [
            'bills' => $rows,
            'totals' => $totals,
            'from' => $from,
            'to' => $to,
            'shop' => $shop,
            'shops' => DB::table('shops')->orderBy('name')->get(['id', 'name'])->map(
                static fn ($row) => (array) $row)->all(),
            'q' => $request->query('q', ''),
        ]);
    }

    /** CSV, because the accountant opens that and nothing else. */
    public function export(Request $request): StreamedResponse
    {
        [$from, $to, $rows] = $this->collect($request, 5000);

        return response()->streamDownload(function () use ($rows, $from, $to) {
            $handle = fopen('php://output', 'w');
            fputcsv($handle, ['shop_id', 'shop', 'invoice_no', 'date', 'time', 'order_type',
                'customer', 'payment', 'status', 'subtotal', 'discount', 'tax', 'total', 'source', 'items']);
            foreach ($rows as $bill) {
                fputcsv($handle, [
                    $bill['_shop_id'], $bill['_shop_name'], $bill['invoice_no'], $bill['date'],
                    substr($bill['created_at'], 11), $bill['order_type'],
                    trim($bill['customer_name'] . ' ' . $bill['customer_phone']),
                    $bill['payment_method'], $bill['status'], $bill['subtotal'], $bill['discount'],
                    $bill['tax'], $bill['total'], $bill['source'],
                    implode(' | ', array_map(static fn ($item) => $item['name'] . ' x' . $item['qty'],
                        $bill['items'])),
                ]);
            }
            fclose($handle);
        }, sprintf('quicktap-bills-%s-to-%s.csv', $from, $to), [
            'Content-Type' => 'text/csv; charset=UTF-8',
        ]);
    }

    /** Revenue per day for the shops on the platform, for the panel's own chart. */
    public function revenue(Request $request): View
    {
        $days = max(1, min(90, (int) $request->query('days', 14)));
        $fromMs = (int) (strtotime(date('Y-m-d', strtotime('-' . ($days - 1) . ' days')) . ' 00:00:00') * 1000);
        $toMs = (int) (strtotime(date('Y-m-d') . ' 23:59:59') * 1000 + 999);

        $buckets = [];
        for ($i = 0; $i < $days; $i++) {
            $buckets[date('Y-m-d', strtotime('+' . $i . ' days', (int) ($fromMs / 1000)))]
                = ['date' => date('Y-m-d', strtotime('+' . $i . ' days', (int) ($fromMs / 1000))),
                   'bills' => 0, 'revenue' => 0.0, 'shops' => []];
        }
        $rows = DB::table('sync_records')->where('entity', 'orders')->whereNull('deleted_at')
            ->whereBetween('occurred_ms', [$fromMs, $toMs])->orderBy('occurred_ms')->limit(20000)->get();
        foreach ($rows as $row) {
            $payload = json_decode((string) $row->payload, true);
            if (!is_array($payload)) {
                continue;
            }
            $key = date('Y-m-d', (int) ($row->occurred_ms / 1000));
            if (!isset($buckets[$key])) {
                continue;
            }
            $buckets[$key]['bills']++;
            $buckets[$key]['revenue'] = round($buckets[$key]['revenue'] + (float) ($payload['total'] ?? 0), 2);
            $buckets[$key]['shops'][(int) $row->shop_id] = ($buckets[$key]['shops'][(int) $row->shop_id] ?? 0) + 1;
        }
        $buckets = array_values($buckets);
        $peak = max(1, (int) max(array_column($buckets, 'revenue')));

        return view('admin.revenue', [
            'days' => $days,
            'buckets' => $buckets,
            'peak' => $peak,
            'total' => round(array_sum(array_column($buckets, 'revenue')), 2),
            'currency' => (string) Billing::setting('currency', config('billing.default_currency', 'PKR')),
        ]);
    }

    public function activity(Request $request): View
    {
        $rows = DB::table('audit_log')->orderByDesc('id')->paginate(80)->withQueryString();

        return view('admin.activity', [
            'rows' => $rows,
            'actions' => DB::table('audit_log')->select('action')->distinct()->orderBy('action')->pluck('action')->all(),
        ]);
    }

    /**
     * @return array{0:string,1:string,2:array,3:array,4:?array} from, to, bills, totals, shop
     */
    private function collect(Request $request, int $cap = 200): array
    {
        $from = (string) $request->query('from', date('Y-m-d'));
        $to = (string) $request->query('to', date('Y-m-d'));
        [$fromMs, $toMs] = Billing::dateRange(['from' => $from, 'to' => $to]);
        $shopId = (int) $request->query('shop_id', 0);
        $needle = trim((string) $request->query('q', ''));

        $query = DB::table('sync_records')->where('entity', 'orders')->whereNull('deleted_at')
            ->whereBetween('occurred_ms', [$fromMs, $toMs])
            ->when($shopId > 0, fn ($q) => $q->where('shop_id', $shopId))
            ->when($needle !== '', function ($q) use ($needle) {
                $like = '%' . str_replace(['%', '_'], ['\%', '\_'], $needle) . '%';
                $q->where('payload', 'like', $like);
            })
            ->orderByDesc('occurred_ms')->limit($cap);

        $names = DB::table('shops')->pluck('name', 'id')->all();
        $bills = [];
        $totals = ['bills' => 0, 'revenue' => 0.0, 'unpaid' => 0.0, 'discount' => 0.0, 'web' => 0];
        foreach ($query->get() as $row) {
            $payload = json_decode((string) $row->payload, true);
            if (!is_array($payload)) {
                continue;
            }
            $bill = Billing::billShape($payload + ['_occurred_ms' => (int) $row->occurred_ms]);
            $bill['_shop_id'] = (int) $row->shop_id;
            $bill['_shop_name'] = (string) ($names[$row->shop_id] ?? ('#' . $row->shop_id));
            $bills[] = $bill;
            $totals['bills']++;
            $totals['revenue'] += $bill['total'];
            if ($bill['status'] !== 'paid') {
                $totals['unpaid'] += $bill['total'];
            }
            $totals['discount'] += $bill['discount'];
            if ($bill['source'] === 'api') {
                $totals['web']++;
            }
        }
        $totals['revenue'] = round($totals['revenue'], 2);
        $totals['unpaid'] = round($totals['unpaid'], 2);
        $totals['discount'] = round($totals['discount'], 2);

        return [$from, $to, $bills, $totals, $shopId > 0 ? Billing::shop($shopId) : null];
    }
}
