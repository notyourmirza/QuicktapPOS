<?php
/**
 * The record store behind the shop's shared data — and the endpoints a website or a
 * desktop program calls.
 *
 * The till (Android) already pushes categories, products and bills through
 * v1/sync/push, so those rows exist in `sync_records` as JSON payloads keyed by uuid.
 * Instead of inventing a second copy of the data for web clients, this file reads and
 * writes the very same rows: one bill store, three front ends.
 *
 * Nothing here changes the phone's Room database — the till stays the printer of
 * record; a web bill reaches the phone as a catalogue (stock) change on its next sync.
 */

declare(strict_types=1);

// --------------------------------------------------------------- read together

/**
 * Decoded records for one shop + entity, newest business time first.
 *
 * @param array $filter from_ms, to_ms, limit, offset, q, include_deleted
 * @return array{rows:array,total:int}
 */
function qt_records_of(int $shopId, string $entity, array $filter = []): array
{
    $where = 'WHERE shop_id = ? AND entity = ?';
    $bindings = [$shopId, $entity];

    if (empty($filter['include_deleted'])) {
        $where .= ' AND deleted_at IS NULL';
    }
    if (!empty($filter['from_ms'])) {
        $where .= ' AND occurred_ms >= ?';
        $bindings[] = (int) $filter['from_ms'];
    }
    if (!empty($filter['to_ms'])) {
        $where .= ' AND occurred_ms <= ?';
        $bindings[] = (int) $filter['to_ms'];
    }
    if (!empty($filter['q'])) {
        $where .= ' AND payload LIKE ?';
        $needle = str_replace(['%', '_'], ['\\%', '\\_'], (string) $filter['q']);
        $bindings[] = '%' . $needle . '%';
    }

    $total = qt_row("SELECT COUNT(*) AS total FROM sync_records {$where}", $bindings);

    $limit = max(1, min(500, (int) ($filter['limit'] ?? 100)));
    $offset = max(0, (int) ($filter['offset'] ?? 0));
    $rows = [];
    $found = qt_rows("SELECT uuid, payload, updated_at, deleted_at, occurred_ms FROM sync_records
        {$where} ORDER BY occurred_ms DESC, id DESC LIMIT {$limit} OFFSET {$offset}", $bindings);
    foreach ($found as $record) {
        $payload = json_decode((string) $record['payload'], true);
        if (!is_array($payload)) {
            continue;
        }
        unset($payload['__device']);
        if (!empty($record['deleted_at'])) {
            $payload['deleted_at'] = $record['deleted_at'];
        }
        $payload['_occurred_ms'] = (int) $record['occurred_ms'];
        $payload['_synced_at'] = (string) $record['updated_at'];
        $rows[] = $payload;
    }
    return ['rows' => $rows, 'total' => (int) ($total['total'] ?? count($rows))];
}

// ------------------------------------------------------------------- write path

/**
 * Stores (or replaces) one record with last-write-wins semantics and returns the
 * normalised payload that was kept. `$origin` is the device id or "token:7" so the
 * pushing till never receives its own write echoed back.
 */
function qt_record_store(int $shopId, string $entity, array $payload, string $origin): array
{
    $uuid = qt_string($payload, 'uuid');
    if ($uuid === '') {
        $uuid = bin2hex(random_bytes(16));
    }
    $payload['uuid'] = $uuid;

    $occurredMs = qt_unix_ms_of($payload['created_at'] ?? null);
    $updatedAtMs = qt_unix_ms_of($payload['updated_at'] ?? null);
    if ($occurredMs === 0) {
        $occurredMs = $updatedAtMs ?: qt_now_ms();
    }
    if ($updatedAtMs === 0) {
        $updatedAtMs = qt_now_ms();
    }

    // Timestamps are stored in the shape the caller used, because the receiving app
    // accepts both (millis or "Y-m-d H:i:s"); only the index columns are normalised.
    $payload['_occurred_ms'] = $occurredMs;
    $deletedAt = empty($payload['deleted_at']) ? null : qt_clean_time((string) $payload['deleted_at']);
    qt_upsert_sync($shopId, $entity, $uuid, $payload,
        date('Y-m-d H:i:s', (int) ($updatedAtMs / 1000)), $deletedAt, $origin);
    qt_run('UPDATE sync_records SET occurred_ms = ?, occurred_at = ? WHERE shop_id = ? AND entity = ? AND uuid = ?',
        [$occurredMs, date('Y-m-d H:i:s', (int) ($occurredMs / 1000)), $shopId, $entity, $uuid]);

    return $payload;
}

/**
 * Milliseconds from anything a client may send: epoch seconds, epoch milliseconds or
 * a "Y-m-d H:i:s" / ISO string. 0 means "not a time".
 */
function qt_unix_ms_of($value): int
{
    if ($value === null || $value === '' || $value === 'null') {
        return 0;
    }
    if (is_numeric($value)) {
        $number = (int) $value;
        return $number > 1_000_000_000_000 ? $number : $number * 1000;
    }
    $normalized = preg_replace('/[Z.]\d*$|Z$/', '', str_replace('T', ' ', (string) $value));
    $parsed = strtotime(trim((string) $normalized));
    return $parsed === false ? 0 : $parsed * 1000;
}

// ---------------------------------------------------------------- v1/shop

/** GET v1/shop — who this token belongs to, plus the branding and licence state. */
function qt_shop_profile(array $input, array $context): void
{
    $shop = $context['shop'];
    $settings = qt_settings_all();
    $state = $context['licence'] ?? qt_license_state($shop, null, '');
    $appName = (string) ($settings['app_name'] ?? 'QuickTap POS');
    if (!empty($shop['app_name'])) {
        $appName = (string) $shop['app_name'];
    }
    qt_json(true, 'OK', [
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
        'licence' => qt_license_payload($state),
        'token' => [
            'scopes' => $context['scopes'] ?? [],
            'device_id' => (string) ($context['device_id'] ?? ''),
        ],
    ]);
}

// -------------------------------------------------------------- v1/catalog

/** GET v1/catalog?include_deleted=1 — the catalogue every client shares. */
function qt_catalog_list(array $input, array $context): void
{
    $shopId = (int) $context['shop']['id'];
    $includeDeleted = qt_int($input, 'include_deleted', 0) === 1;
    $filter = [
        'include_deleted' => $includeDeleted,
        'limit' => 500,
        'q' => qt_string($input, 'q'),
    ];
    $categories = qt_records_of($shopId, 'categories', $filter);
    $products = qt_records_of($shopId, 'products', $filter);

    $out = static function (array $rows, array $keep): array {
        $list = [];
        foreach ($rows as $row) {
            $row = array_intersect_key($row, array_flip($keep));
            $list[] = $row;
        }
        return $list;
    };
    qt_json(true, 'OK', [
        'categories' => $out($categories['rows'], ['uuid', 'name', 'sort_order', 'updated_at', 'deleted_at']),
        'products' => $out($products['rows'], [
            'uuid', 'category_uuid', 'name', 'price', 'barcode', 'stock', 'is_active',
            'updated_at', 'deleted_at',
        ]),
        'counts' => ['categories' => $categories['total'], 'products' => $products['total']],
        'currency' => (string) $context['shop']['currency'],
    ]);
}

/** POST v1/catalog/products — create or update one product, or a batch. */
function qt_catalog_save_product(array $input, array $context): void
{
    qt_require_scope($context, 'write');
    $saved = qt_save_catalog_records($context, 'products');
    qt_json(true, sprintf('%d product(s) saved.', count($saved)), ['products' => $saved]);
}

/** POST v1/catalog/categories — create or update a category, or a batch. */
function qt_catalog_save_category(array $input, array $context): void
{
    qt_require_scope($context, 'write');
    $saved = qt_save_catalog_records($context, 'categories');
    qt_json(true, sprintf('%d category(ies) saved.', count($saved)), ['categories' => $saved]);
}

/**
 * Shared tail of both catalogue endpoints: accepts one record or {products:[…]},
 * keeps only the fields the till also uses, and stamps the write so the phones pull
 * it on their next sync.
 * The body arrives through the context, which `qt_context()` already parsed.
 */
function qt_save_catalog_records(array $context, string $entity): array
{
    $input = $context['input'];
    $rows = $input[$entity] ?? null;
    if (!is_array($rows) || !$rows) {
        $rows = [$input];
    }
    $shopId = (int) $context['shop']['id'];
    $origin = (string) ($context['device_id'] ?: 'token:' . (int) ($context['token_id'] ?? 0));
    $saved = [];
    foreach ($rows as $row) {
        if (!is_array($row)) {
            continue;
        }
        $name = qt_string($row, 'name');
        if ($name === '' && empty($row['deleted_at'])) {
            qt_deny('BAD_RECORD', sprintf('Each %s record needs a name.', $entity), 422);
        }
        if ($entity === 'products' && isset($row['price']) && !is_numeric($row['price'])) {
            qt_deny('BAD_PRICE', 'A product price must be a number.', 422);
        }
        $payload = array_intersect_key($row, array_flip([
            'uuid', 'name', 'sort_order', 'category_uuid', 'price', 'barcode', 'stock',
            'is_active', 'updated_at', 'deleted_at',
        ]));
        $payload['name'] = $payload['name'] ?? $name;
        $payload['updated_at'] = qt_now_ms();
        $saved[] = qt_record_store($shopId, $entity, $payload, $origin);
    }
    if (!$saved) {
        qt_deny('BAD_RECORD', 'Send one record, or a "' . $entity . '" array of records.', 422);
    }
    return $saved;
}

// ------------------------------------------------------------------ v1/bills

/** GET v1/bills?from=YYYY-MM-DD&to=&limit=&offset=&q= — newest bill first. */
function qt_bills_list(array $input, array $context): void
{
    $shopId = (int) $context['shop']['id'];
    [$fromMs, $toMs] = qt_date_range($input);
    $result = qt_records_of($shopId, 'orders', [
        'from_ms' => $fromMs,
        'to_ms' => $toMs,
        'limit' => qt_int($input, 'limit', 50),
        'offset' => qt_int($input, 'offset', 0),
        'q' => qt_string($input, 'q'),
        'include_deleted' => qt_int($input, 'include_deleted', 0) === 1,
    ]);

    $bills = [];
    foreach ($result['rows'] as $row) {
        $bills[] = qt_bill_shape($row);
    }
    qt_json(true, 'OK', [
        'bills' => $bills,
        'total' => $result['total'],
        'limit' => max(1, min(500, qt_int($input, 'limit', 50))),
        'offset' => max(0, qt_int($input, 'offset', 0)),
        'currency' => (string) $context['shop']['currency'],
        'range' => ['from' => $fromMs > 0 ? date('Y-m-d', (int) ($fromMs / 1000)) : null,
            'to' => $toMs > 0 ? date('Y-m-d', (int) ($toMs / 1000)) : null],
    ]);
}

/**
 * POST v1/bills — write one bill from anywhere.
 *
 * The caller sends lines (and optionally a discount and tax); the totals are
 * calculated here, so a website and the till can never disagree about money. Sending
 * the same `uuid` twice replaces that bill instead of duplicating it, which is what
 * makes a flaky network "just retry" safe.
 */
function qt_bills_save(array $input, array $context): void
{
    qt_require_scope($context, 'write');
    $shopId = (int) $context['shop']['id'];
    $items = $input['items'] ?? null;
    if (!is_array($items) || !$items) {
        qt_deny('NO_ITEMS', 'A bill needs at least one line in "items".', 422);
    }
    $lines = [];
    $subtotal = 0.0;
    foreach ($items as $item) {
        if (!is_array($item)) {
            continue;
        }
        $qty = (int) qt_int($item, 'qty', 1);
        $price = (float) (isset($item['price']) && is_numeric($item['price']) ? $item['price'] : 0);
        if ($qty <= 0) {
            qt_deny('BAD_QTY', 'Every line needs a quantity above zero.', 422);
        }
        if ($price < 0) {
            qt_deny('BAD_PRICE', 'A price cannot be negative.', 422);
        }
        $name = qt_string($item, 'name', $item['product_uuid'] ?? 'Item');
        $lineTotal = round($price * $qty, 2);
        $subtotal += $lineTotal;
        $lines[] = [
            'name' => $name,
            'price' => $price,
            'qty' => $qty,
            'line_total' => $lineTotal,
            'product_uuid' => qt_string($item, 'product_uuid'),
        ];
    }
    if (!$lines) {
        qt_deny('NO_ITEMS', 'None of the lines could be read.', 422);
    }

    $discount = max(0.0, (float) (isset($input['discount']) && is_numeric($input['discount'])
        ? $input['discount'] : 0));
    $discountPercent = max(0.0, min(100.0, (float) (isset($input['discount_percent'])
        && is_numeric($input['discount_percent']) ? $input['discount_percent'] : 0)));
    if ($discountPercent > 0) {
        $discount = round($subtotal * $discountPercent / 100, 2);
    }
    $tax = (float) (isset($input['tax']) && is_numeric($input['tax']) ? $input['tax'] : 0);
    if ($tax == 0.0 && isset($input['tax_percent']) && is_numeric($input['tax_percent'])) {
        $tax = round(($subtotal - $discount) * (float) $input['tax_percent'] / 100, 2);
    }
    if ($discount > $subtotal) {
        qt_deny('BAD_DISCOUNT', 'The discount cannot be larger than the subtotal.', 422);
    }
    $total = round($subtotal - $discount + $tax, 2);

    // `status:"paid"|"unpaid"` wins; a bare `paid:true|false|1|0` is also accepted, and
    // a bill that says nothing is treated as paid (the counter was already emptied).
    $status = strtolower(qt_string($input, 'status', ''));
    if ($status === '') {
        $paid = !array_key_exists('paid', $input)
            || filter_var($input['paid'], FILTER_VALIDATE_BOOLEAN);
    } else {
        $paid = $status !== 'unpaid';
    }

    $createdMs = qt_unix_ms_of($input['created_at'] ?? null) ?: qt_now_ms();
    $payload = [
        'uuid' => qt_string($input, 'uuid'),
        'invoice_no' => qt_string($input, 'invoice_no') ?: qt_next_invoice_no($shopId, $createdMs),
        'order_type' => strtoupper(qt_string($input, 'order_type', 'TAKE_AWAY')),
        'table_no' => qt_string($input, 'table_no'),
        'customer_name' => qt_string($input, 'customer_name'),
        'customer_phone' => qt_string($input, 'customer_phone'),
        'customer_code' => qt_int($input, 'customer_code', 0),
        'address' => qt_string($input, 'address'),
        'notes' => qt_string($input, 'notes'),
        'subtotal' => round($subtotal, 2),
        'discount' => round($discount, 2),
        'tax' => round($tax, 2),
        'total' => $total,
        'status' => $paid ? 'paid' : 'unpaid',
        'paid' => $paid ? 1 : 0,
        'payment_method' => strtoupper(qt_string($input, 'payment_method', 'CASH')),
        'items' => $lines,
        'created_at' => $createdMs,
        'updated_at' => qt_now_ms(),
        'source' => 'api',
    ];

    $origin = (string) ($context['device_id'] ?: 'token:' . (int) ($context['token_id'] ?? 0));
    // What the uuid held before this write, so an edited or retried bill moves stock by
    // the difference only — a network retry must never sell the same items twice.
    $before = [];
    $existing = qt_row('SELECT payload FROM sync_records WHERE shop_id = ? AND entity = ? AND uuid = ?',
        [$shopId, 'orders', $payload['uuid']]);
    if ($existing !== null) {
        $decoded = json_decode((string) $existing['payload'], true);
        if (is_array($decoded) && is_array($decoded['items'] ?? null)) {
            $before = $decoded['items'];
        }
    }

    $stored = qt_record_store($shopId, 'orders', $payload, $origin);
    qt_run('UPDATE sync_records SET deleted_at = NULL WHERE shop_id = ? AND entity = ? AND uuid = ?',
        [$shopId, 'orders', $stored['uuid']]);

    $reduced = qt_reduce_stock($shopId, $lines, $origin, $before);
    qt_log_audit('bill_write', $shopId, sprintf('%s · %s', $stored['invoice_no'], number_format($total, 2)));

    qt_json(true, 'Bill saved.', [
        'bill' => qt_bill_shape($stored),
        'stock_reduced' => $reduced,
        'hint' => 'The till picks this bill up as a catalogue change on its next sync.',
    ]);
}

/**
 * Move the catalogue stock the sold products carry, so every client agrees.
 *
 * $previous is the line list the same uuid held before this write: stock moves by the
 * difference, which is what makes a retried POST safe and an edit that removes a line
 * give the units back.
 */
function qt_reduce_stock(int $shopId, array $lines, string $origin, array $previous = []): array
{
    $sold = [];
    foreach ($lines as $line) {
        $uuid = (string) ($line['product_uuid'] ?? '');
        if ($uuid === '') {
            continue;
        }
        $sold[$uuid] = ($sold[$uuid] ?? 0) + (int) ($line['qty'] ?? 0);
    }
    foreach ($previous as $line) {
        $uuid = (string) ($line['product_uuid'] ?? '');
        if ($uuid === '') {
            continue;
        }
        $sold[$uuid] = ($sold[$uuid] ?? 0) - (int) ($line['qty'] ?? 0);
    }

    $products = qt_records_of($shopId, 'products', ['limit' => 500])['rows'];
    $byUuid = [];
    foreach ($products as $product) {
        $byUuid[(string) ($product['uuid'] ?? '')] = $product;
    }

    $changed = [];
    foreach ($sold as $uuid => $delta) {
        if ($delta === 0 || !isset($byUuid[$uuid])) {
            continue;
        }
        $product = $byUuid[$uuid];
        if (!isset($product['stock']) || !is_numeric($product['stock'])) {
            continue;   // -1 / missing means "not tracked"
        }
        $product['stock'] = (int) $product['stock'] - $delta;
        $product['updated_at'] = qt_now_ms();
        $byUuid[$uuid] = $product;
        qt_record_store($shopId, 'products', $product, $origin);
        $changed[] = ['uuid' => $uuid, 'name' => (string) ($product['name'] ?? ''),
                     'stock' => $product['stock'], 'delta' => -$delta];
    }
    return $changed;
}

/** Shape a stored order payload for API output (numbers and names, no internals). */
function qt_bill_shape(array $row): array
{
    $createdMs = qt_unix_ms_of($row['created_at'] ?? null) ?: (int) ($row['_occurred_ms'] ?? 0);
    $items = [];
    foreach (is_array($row['items'] ?? null) ? $row['items'] : [] as $item) {
        if (!is_array($item)) {
            continue;
        }
        $qty = (int) ($item['qty'] ?? 0);
        $price = (float) ($item['price'] ?? 0);
        $items[] = [
            'name' => (string) ($item['name'] ?? 'Item'),
            'price' => $price,
            'qty' => $qty,
            'line_total' => (float) ($item['line_total'] ?? round($price * $qty, 2)),
            'product_uuid' => (string) ($item['product_uuid'] ?? ''),
        ];
    }
    $subtotal = (float) ($row['subtotal'] ?? array_sum(array_column($items, 'line_total')));
    return [
        'uuid' => (string) ($row['uuid'] ?? ''),
        'invoice_no' => (string) ($row['invoice_no'] ?? ''),
        'created_at_ms' => $createdMs,
        'created_at' => date('Y-m-d H:i:s', (int) ($createdMs / 1000)),
        'date' => date('Y-m-d', (int) ($createdMs / 1000)),
        'order_type' => (string) ($row['order_type'] ?? 'TAKE_AWAY'),
        'table_no' => (string) ($row['table_no'] ?? ''),
        'customer_name' => (string) ($row['customer_name'] ?? ''),
        'customer_phone' => (string) ($row['customer_phone'] ?? ''),
        'customer_code' => (int) ($row['customer_code'] ?? 0),
        'address' => (string) ($row['address'] ?? ''),
        'notes' => (string) ($row['notes'] ?? ''),
        'subtotal' => round($subtotal, 2),
        'discount' => (float) ($row['discount'] ?? 0),
        'tax' => (float) ($row['tax'] ?? 0),
        'total' => (float) ($row['total'] ?? $subtotal),
        'status' => (string) ($row['status'] ?? ((isset($row['paid']) && !$row['paid']) ? 'unpaid' : 'paid')),
        'payment_method' => (string) ($row['payment_method'] ?? 'CASH'),
        'source' => (string) ($row['source'] ?? 'till'),
        'items' => $items,
    ];
}

// ----------------------------------------------------------------- v1/reports

/** GET v1/reports/day?date=YYYY-MM-DD — the numbers a counter page needs. */
function qt_report_day(array $input, array $context): void
{
    $date = qt_string($input, 'date', date('Y-m-d'));
    $day = strtotime($date . ' 00:00:00') ?: strtotime(date('Y-m-d') . ' 00:00:00');
    $summary = qt_summary_for($context, $day * 1000, ($day + 86399) * 1000);
    $summary['date'] = date('Y-m-d', $day);
    $summary['hours'] = qt_hour_buckets($summary['bills']);
    unset($summary['bills']);
    qt_json(true, 'OK', $summary);
}

/** GET v1/reports/range?from=…&to=… — per-day totals for a week or a month. */
function qt_report_range(array $input, array $context): void
{
    [$fromMs, $toMs] = qt_date_range($input);
    $summary = qt_summary_for($context, $fromMs, $toMs);
    $days = [];
    foreach ($summary['bills'] as $bill) {
        $key = $bill['date'];
        if (!isset($days[$key])) {
            $days[$key] = ['date' => $key, 'bills' => 0, 'revenue' => 0.0, 'unpaid' => 0.0];
        }
        $days[$key]['bills']++;
        $days[$key]['revenue'] = round($days[$key]['revenue'] + $bill['total'], 2);
        if ($bill['status'] !== 'paid') {
            $days[$key]['unpaid'] = round($days[$key]['unpaid'] + $bill['total'], 2);
        }
    }
    ksort($days);
    $summary['days'] = array_values($days);
    $summary['range'] = ['from' => $fromMs > 0 ? date('Y-m-d', (int) ($fromMs / 1000)) : null,
        'to' => $toMs > 0 ? date('Y-m-d', (int) ($toMs / 1000)) : null];
    unset($summary['bills']);
    qt_json(true, 'OK', $summary);
}

/** One pass over the shop's bills: totals, payment mix, order mix and top sellers. */
function qt_summary_for(array $context, int $fromMs, int $toMs): array
{
    $shopId = (int) $context['shop']['id'];
    $bills = [];
    $offset = 0;
    do {
        $page = qt_records_of($shopId, 'orders', [
            'from_ms' => $fromMs, 'to_ms' => $toMs, 'limit' => 500, 'offset' => $offset,
        ]);
        foreach ($page['rows'] as $row) {
            $bills[] = qt_bill_shape($row);
        }
        $count = count($page['rows']);
        $offset += $count;
    } while ($count === 500 && $offset < 5000);

    $revenue = 0.0; $paidSum = 0.0; $unpaidSum = 0.0; $discountSum = 0.0; $taxSum = 0.0;
    $byMethod = []; $byType = []; $products = [];
    foreach ($bills as $bill) {
        $revenue += $bill['total'];
        if ($bill['status'] === 'paid') { $paidSum += $bill['total']; } else { $unpaidSum += $bill['total']; }
        $discountSum += $bill['discount'];
        $taxSum += $bill['tax'];
        $method = $bill['payment_method'] ?: 'CASH';
        $byMethod[$method] = round(($byMethod[$method] ?? 0) + $bill['total'], 2);
        $type = $bill['order_type'] ?: 'TAKE_AWAY';
        $byType[$type] = round(($byType[$type] ?? 0) + $bill['total'], 2);
        foreach ($bill['items'] as $item) {
            $key = $item['name'];
            if (!isset($products[$key])) {
                $products[$key] = ['name' => $key, 'qty' => 0, 'revenue' => 0.0];
            }
            $products[$key]['qty'] += $item['qty'];
            $products[$key]['revenue'] = round($products[$key]['revenue'] + $item['line_total'], 2);
        }
    }
    usort($products, static fn ($a, $b) => $b['revenue'] <=> $a['revenue']);

    $count = count($bills);
    return [
        'currency' => (string) $context['shop']['currency'],
        'bills' => $bills,
        'bill_count' => $count,
        'revenue' => round($revenue, 2),
        'paid' => round($paidSum, 2),
        'unpaid' => round($unpaidSum, 2),
        'discount' => round($discountSum, 2),
        'tax' => round($taxSum, 2),
        'average_bill' => $count > 0 ? round($revenue / $count, 2) : 0.0,
        'by_payment_method' => $byMethod,
        'by_order_type' => $byType,
        'top_products' => array_slice($products, 0, 10),
        'server_time_ms' => qt_now_ms(),
    ];
}

/** 24 buckets for a chart, from the bills of one day. */
function qt_hour_buckets(array $bills): array
{
    $hours = [];
    for ($hour = 0; $hour < 24; $hour++) {
        $hours[$hour] = ['hour' => $hour, 'bills' => 0, 'revenue' => 0.0];
    }
    foreach ($bills as $bill) {
        $hour = (int) date('G', (int) ($bill['created_at_ms'] / 1000));
        $hours[$hour]['bills']++;
        $hours[$hour]['revenue'] = round($hours[$hour]['revenue'] + $bill['total'], 2);
    }
    return array_values($hours);
}

// -------------------------------------------------------------------- helpers

/** from/to as `YYYY-MM-DD` (or raw millis) → [fromMs, toMs]; default = last 30 days. */
function qt_date_range(array $input): array
{
    $fromRaw = qt_string($input, 'from');
    $toRaw = qt_string($input, 'to');
    $fromMs = $fromRaw === '' ? 0 : qt_unix_ms_of($fromRaw);
    if ($fromRaw !== '' && $fromMs === 0) {
        $day = strtotime($fromRaw . ' 00:00:00');
        $fromMs = $day === false ? 0 : $day * 1000;
    }
    $toMs = $toRaw === '' ? 0 : qt_unix_ms_of($toRaw);
    if ($toRaw !== '' && preg_match('/^\d{4}-\d{2}-\d{2}$/', $toRaw)) {
        // "to=2025-01-03" means the whole of the 3rd, not its first second — the most
        // common way an integration loses the last day of a month.
        $end = strtotime($toRaw . ' 23:59:59');
        $toMs = $end === false ? 0 : $end * 1000 + 999;
    } elseif ($toRaw !== '' && $toMs === 0) {
        $day = strtotime($toRaw . ' 23:59:59');
        $toMs = $day === false ? 0 : $day * 1000;
    }
    if ($fromMs === 0 && $toMs === 0) {
        $toMs = qt_now_ms();
        $fromMs = $toMs - 30 * 86400 * 1000;
    } elseif ($fromMs === 0) {
        $fromMs = 0;
    } elseif ($toMs === 0) {
        $toMs = qt_now_ms();
    }
    return [$fromMs, $toMs];
}

/** INV-YYYYMMDD-### when a web bill has no invoice number of its own. */
function qt_next_invoice_no(int $shopId, int $createdMs): string
{
    $prefix = 'INV-' . date('Ymd', (int) ($createdMs / 1000));
    $row = qt_row('SELECT COUNT(*) AS total FROM sync_records WHERE shop_id = ? AND entity = ?
        AND payload LIKE ?', [$shopId, 'orders', '%' . $prefix . '%']);
    $next = 1 + (int) ($row['total'] ?? 0);
    $candidate = sprintf('%s-%03d', $prefix, $next);
    while (qt_row('SELECT id FROM sync_records WHERE shop_id = ? AND entity = ? AND payload LIKE ?',
        [$shopId, 'orders', '%"invoice_no":"' . $candidate . '"%']) !== null) {
        $next++;
        $candidate = sprintf('%s-%03d', $prefix, $next);
    }
    return $candidate;
}
