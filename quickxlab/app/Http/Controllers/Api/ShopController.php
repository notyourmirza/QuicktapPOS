<?php

namespace App\Http\Controllers\Api;

use App\Support\Billing;
use Illuminate\Http\JsonResponse;

/**
 * The same billing, for a client that is not a phone: a shop's website, a kiosk page, a
 * desktop counter program, an accounting script. One header (`X-Api-Token`) identifies
 * the shop, and every answer is the shop's own records — nothing else exists here.
 *
 * Money is computed in this file, never trusted from the caller, and stock moves by the
 * difference to what a uuid held before. Those two rules are why a browser client can be
 * allowed to bill at all.
 *
 * @see ../../../../billing-api/API.md  (the guide clients read; keep both in step)
 */
class ShopController extends ApiController
{
    private const PRODUCT_FIELDS = ['uuid', 'category_uuid', 'name', 'price', 'barcode',
        'stock', 'is_active', 'updated_at', 'deleted_at'];

    private const CATEGORY_FIELDS = ['uuid', 'name', 'sort_order', 'updated_at', 'deleted_at'];

    /** Who this token belongs to, plus the licence state the client must respect. */
    public function shop(): JsonResponse
    {
        $shop = $this->shop();
        $settings = Billing::settings();
        $appName = (string) ($shop['app_name'] ?: ($settings['app_name'] ?: config('app.name')));

        return $this->ok('OK', [
            'shop' => [
                'id' => (int) $shop['id'],
                'name' => (string) $shop['name'],
                'currency' => (string) $shop['currency'],
                'app_name' => $appName,
                'logo_url' => (string) ($settings['logo_url'] ?? ''),
                'receipt_template' => (string) ($settings['receipt_template'] ?? 'classic'),
                'receipt_footer' => (string) ($settings['receipt_footer'] ?? ''),
                'tax_percent' => (float) ($settings['tax_percent'] ?? 0),
                'support_whatsapp' => (string) ($settings['support_whatsapp'] ?? ''),
            ],
            'licence' => Billing::payload($this->ctx()['licence'] ?? Billing::state($shop, null, '')),
            'token' => [
                'scopes' => $this->ctx()['scopes'] ?? [],
                'device_id' => (string) ($this->ctx()['device_id'] ?? ''),
            ],
        ]);
    }

    /** The catalogue — the same rows the till pushes and pulls. */
    public function catalog(): JsonResponse
    {
        $filter = [
            'include_deleted' => $this->int('include_deleted', 0) === 1,
            'limit' => 500,
            'q' => $this->str('q'),
        ];
        $categories = Billing::records($this->shopId(), 'categories', $filter);
        $products = Billing::records($this->shopId(), 'products', $filter);

        return $this->ok('OK', [
            'categories' => array_map(fn (array $row) => $this->keep($row, self::CATEGORY_FIELDS), $categories['rows']),
            'products' => array_map(fn (array $row) => $this->keep($row, self::PRODUCT_FIELDS), $products['rows']),
            'counts' => ['categories' => $categories['total'], 'products' => $products['total']],
            'currency' => (string) $this->shop()['currency'],
        ]);
    }

    /** POST v1/catalog/products — one product, or {products:[…]} as a batch. */
    public function saveProducts(): JsonResponse
    {
        return $this->saveCatalog('products');
    }

    public function saveCategories(): JsonResponse
    {
        return $this->saveCatalog('categories');
    }

    /** @see GET v1/bills for the filters */
    public function bills(): JsonResponse
    {
        [$fromMs, $toMs] = Billing::dateRange($this->input());
        $limit = max(1, min(500, $this->int('limit', 50)));
        $offset = max(0, $this->int('offset', 0));
        $result = Billing::records($this->shopId(), 'orders', [
            'from_ms' => $fromMs, 'to_ms' => $toMs, 'limit' => $limit, 'offset' => $offset,
            'q' => $this->str('q'),
            'include_deleted' => $this->int('include_deleted', 0) === 1,
        ]);

        return $this->ok('OK', [
            'bills' => array_map([Billing::class, 'billShape'], $result['rows']),
            'total' => $result['total'],
            'limit' => $limit,
            'offset' => $offset,
            'currency' => (string) $this->shop()['currency'],
            'range' => [
                'from' => $fromMs > 0 ? date('Y-m-d', (int) ($fromMs / 1000)) : null,
                'to' => $toMs > 0 ? date('Y-m-d', (int) ($toMs / 1000)) : null,
            ],
        ]);
    }

    /**
     * POST v1/bills — write one bill from anywhere.
     *
     * Lines in, totals out: subtotal, discount, tax and total are calculated here so a
     * website and a till cannot disagree about money. Sending the same `uuid` twice
     * replaces that bill instead of duplicating it, which is what makes "just retry" safe
     * on a flaky counter connection.
     */
    public function saveBill(): JsonResponse
    {
        $this->needWrite();
        $shopId = $this->shopId();
        $input = $this->input();
        $items = $input['items'] ?? null;
        if (!is_array($items) || !$items) {
            $this->fail('NO_ITEMS', 'A bill needs at least one line in "items".');
        }

        $lines = [];
        $subtotal = 0.0;
        foreach ($items as $item) {
            if (!is_array($item)) {
                continue;
            }
            $qty = (int) ($item['qty'] ?? 1);
            $price = is_numeric($item['price'] ?? null) ? (float) $item['price'] : 0.0;
            if ($qty <= 0) {
                $this->fail('BAD_QTY', 'Every line needs a quantity above zero.');
            }
            if ($price < 0) {
                $this->fail('BAD_PRICE', 'A price cannot be negative.');
            }
            $name = trim((string) ($item['name'] ?? '')) ?: (string) ($item['product_uuid'] ?? 'Item');
            $lineTotal = round($price * $qty, 2);
            $subtotal += $lineTotal;
            $lines[] = ['name' => $name, 'price' => $price, 'qty' => $qty,
                        'line_total' => $lineTotal,
                        'product_uuid' => trim((string) ($item['product_uuid'] ?? ''))];
        }
        if (!$lines) {
            $this->fail('NO_ITEMS', 'None of the lines could be read.');
        }

        $discount = max(0.0, (float) ($input['discount'] ?? 0));
        $discountPercent = min(100.0, max(0.0, (float) ($input['discount_percent'] ?? 0)));
        if ($discountPercent > 0) {
            $discount = round($subtotal * $discountPercent / 100, 2);
        }
        $tax = (float) ($input['tax'] ?? 0);
        if ($tax == 0.0 && isset($input['tax_percent']) && is_numeric($input['tax_percent'])) {
            $tax = round(($subtotal - $discount) * (float) $input['tax_percent'] / 100, 2);
        }
        if ($discount > $subtotal) {
            $this->fail('BAD_DISCOUNT', 'The discount cannot be larger than the subtotal.');
        }
        $total = round($subtotal - $discount + $tax, 2);

        // "paid"/"unpaid" wins; a bare `paid` flag is accepted too; silence means paid,
        // because the counter was already emptied when a web page sends a bill.
        $status = strtolower(trim((string) ($input['status'] ?? '')));
        if ($status === '') {
            $paid = !array_key_exists('paid', $input)
                || filter_var($input['paid'], FILTER_VALIDATE_BOOLEAN);
        } else {
            $paid = $status !== 'unpaid';
        }

        $createdMs = Billing::toMs($input['created_at'] ?? null) ?: Billing::nowMs();
        $uuid = trim((string) ($input['uuid'] ?? ''));
        $payload = [
            'uuid' => $uuid,
            'invoice_no' => trim((string) ($input['invoice_no'] ?? '')) ?: Billing::nextInvoice($shopId, $createdMs),
            'order_type' => strtoupper(trim((string) ($input['order_type'] ?? '')) ?: 'TAKE_AWAY'),
            'table_no' => (string) ($input['table_no'] ?? ''),
            'customer_name' => (string) ($input['customer_name'] ?? ''),
            'customer_phone' => (string) ($input['customer_phone'] ?? ''),
            'customer_code' => (int) ($input['customer_code'] ?? 0),
            'address' => (string) ($input['address'] ?? ''),
            'notes' => (string) ($input['notes'] ?? ''),
            'subtotal' => round($subtotal, 2),
            'discount' => round($discount, 2),
            'tax' => round($tax, 2),
            'total' => $total,
            'status' => $paid ? 'paid' : 'unpaid',
            'paid' => $paid ? 1 : 0,
            'payment_method' => strtoupper(trim((string) ($input['payment_method'] ?? '')) ?: 'CASH'),
            'items' => $lines,
            'created_at' => $createdMs,
            'updated_at' => Billing::nowMs(),
            'source' => 'api',
        ];

        // What the uuid held before this write, so stock moves by the difference only.
        $before = [];
        if ($uuid !== '') {
            $existing = \Illuminate\Support\Facades\DB::table('sync_records')
                ->where('shop_id', $shopId)->where('entity', 'orders')->where('uuid', $uuid)->first();
            $decoded = $existing === null ? null : json_decode((string) $existing->payload, true);
            if (is_array($decoded) && is_array($decoded['items'] ?? null)) {
                $before = $decoded['items'];
            }
        }

        $stored = Billing::store($shopId, 'orders', $payload, $this->origin());
        \Illuminate\Support\Facades\DB::table('sync_records')
            ->where('shop_id', $shopId)->where('entity', 'orders')->where('uuid', $stored['uuid'])
            ->update(['deleted_at' => null]);

        $reduced = Billing::reduceStock($shopId, $lines, $this->origin(), $before);
        Billing::audit('bill_write', $shopId, sprintf('%s · %s', $stored['invoice_no'], number_format($total, 2)));

        return $this->ok('Bill saved.', [
            'bill' => Billing::billShape($stored),
            'stock_reduced' => $reduced,
            'hint' => 'The till picks this bill up as a catalogue change on its next sync.',
        ]);
    }

    public function reportDay(): JsonResponse
    {
        $date = $this->str('date', date('Y-m-d'));
        $day = strtotime($date . ' 00:00:00') ?: strtotime(date('Y-m-d') . ' 00:00:00');
        $summary = Billing::summary($this->shop(), $day * 1000, ($day + 86399) * 1000);
        $summary['date'] = date('Y-m-d', $day);
        $summary['hours'] = Billing::hourBuckets($summary['bills']);
        unset($summary['bills']);

        return $this->ok('OK', $summary);
    }

    public function reportRange(): JsonResponse
    {
        [$fromMs, $toMs] = Billing::dateRange($this->input());
        $summary = Billing::summary($this->shop(), $fromMs, $toMs);
        $days = [];
        foreach ($summary['bills'] as $bill) {
            $key = $bill['date'];
            $days[$key] ??= ['date' => $key, 'bills' => 0, 'revenue' => 0.0, 'unpaid' => 0.0];
            $days[$key]['bills']++;
            $days[$key]['revenue'] = round($days[$key]['revenue'] + $bill['total'], 2);
            if ($bill['status'] !== 'paid') {
                $days[$key]['unpaid'] = round($days[$key]['unpaid'] + $bill['total'], 2);
            }
        }
        ksort($days);
        $summary['days'] = array_values($days);
        $summary['range'] = [
            'from' => $fromMs > 0 ? date('Y-m-d', (int) ($fromMs / 1000)) : null,
            'to' => $toMs > 0 ? date('Y-m-d', (int) ($toMs / 1000)) : null,
        ];
        unset($summary['bills']);

        return $this->ok('OK', $summary);
    }

    // ------------------------------------------------------------------ shared

    private function saveCatalog(string $entity): JsonResponse
    {
        $this->needWrite();
        $shopId = $this->shopId();
        $input = $this->input();
        $rows = $input[$entity] ?? null;
        if (!is_array($rows) || !$rows) {
            $rows = [$input];
        }
        $fields = $entity === 'products' ? self::PRODUCT_FIELDS : self::CATEGORY_FIELDS;
        $saved = [];
        foreach ($rows as $row) {
            if (!is_array($row)) {
                continue;
            }
            $name = trim((string) ($row['name'] ?? ''));
            if ($name === '' && empty($row['deleted_at'])) {
                $this->fail('BAD_RECORD', sprintf('Each %s record needs a name.', $entity));
            }
            if ($entity === 'products' && isset($row['price']) && !is_numeric($row['price'])) {
                $this->fail('BAD_PRICE', 'A product price must be a number.');
            }
            $payload = $this->keep($row, $fields);
            $payload['name'] = $payload['name'] ?? $name;
            $payload['updated_at'] = Billing::nowMs();
            $saved[] = Billing::store($shopId, $entity, $payload, $this->origin());
        }
        if (!$saved) {
            $this->fail('BAD_RECORD', 'Send one record, or a "' . $entity . '" array of records.');
        }

        return $this->ok(sprintf('%d %s saved.', count($saved), $entity), [$entity => $saved]);
    }

    /** Only the fields a client may write — internals like __device stay ours. */
    private function keep(array $row, array $fields): array
    {
        return array_intersect_key($row, array_flip($fields));
    }
}
