<?php

namespace App\Http\Controllers\Api;

use App\Support\Billing;
use Illuminate\Http\JsonResponse;
use Illuminate\Support\Facades\DB;

/**
 * "Sync now" between tills of one shop — and the reason a web bill can reach the phone's
 * catalogue: both sides read and write the same `sync_records` rows, keyed by
 * (shop, entity, uuid), with last write wins judged on the timestamp the sender wrote.
 *
 * The till is the ledger of record for its own receipts: `pull` does return `orders`, and
 * 类034 deliberately applies only categories and products. Stock edits made elsewhere
 * arrive inside the product rows, which is what a till needs.
 */
class SyncController extends ApiController
{
    private const ENTITIES = ['categories', 'products', 'orders'];

    /** @see billing-api/API.md for the payload of each entity */
    public function push(): JsonResponse
    {
        $shopId = $this->shopId();
        $origin = $this->origin();
        $limit = max(1, (int) config('billing.sync_batch_limit', 500));
        $accepted = [];

        foreach (self::ENTITIES as $entity) {
            $rows = $this->input()[$entity] ?? null;
            if (!is_array($rows)) {
                $rows = [];
            }
            if (count($rows) > $limit) {
                $this->fail('BATCH_TOO_LARGE',
                    sprintf('Send at most %d %s per push.', $limit, $entity), 413);
            }
            foreach ($rows as $row) {
                if (!is_array($row)) {
                    continue;
                }
                $uuid = trim((string) ($row['uuid'] ?? ''));
                if ($uuid === '') {
                    continue;   // nothing to key it by; the till always sends one
                }
                $stored = Billing::store($shopId, $entity, $row, $origin);
                $accepted[$entity][] = $stored['uuid'];
            }
        }

        // A bill the till pushed carries product lines: moving stock here is what keeps
        // a phone's own sale and a website's sale in one number.
        $orders = $this->input()['orders'] ?? null;
        if (is_array($orders) && $orders) {
            foreach ($orders as $order) {
                if (!is_array($order) || !is_array($order['items'] ?? null)) {
                    continue;
                }
                $lines = [];
                foreach ($order['items'] as $item) {
                    if (is_array($item)) {
                        $lines[] = ['product_uuid' => (string) ($item['product_uuid'] ?? ''),
                                    'qty' => (int) ($item['qty'] ?? 0)];
                    }
                }
                if ($lines) {
                    Billing::reduceStock($shopId, $lines, $origin);
                }
            }
        }

        Billing::audit('sync_push', $shopId, sprintf('%d record(s)', array_sum(array_map('count', $accepted))));

        return $this->ok('Synced.', [
            'accepted' => ['categories' => $accepted['categories'] ?? [],
                           'products' => $accepted['products'] ?? [],
                           'orders' => $accepted['orders'] ?? []],
            'server_time' => Billing::nowMs(),
        ]);
    }

    /** Everything that changed after the caller's cursor, plus the shared settings. */
    public function pull(): JsonResponse
    {
        Billing::touch($this->ctx());
        $shopId = $this->shopId();
        $sinceMs = $this->int('since', 0);
        $since = $sinceMs > 0
            ? date('Y-m-d H:i:s', (int) ($sinceMs / 1000))
            : date('Y-m-d H:i:s', time() - 30 * 86400);
        $origin = (string) ($this->ctx()['device_id'] ?? '');
        $settings = Billing::settings();

        $changes = ['categories' => [], 'products' => [], 'orders' => []];
        $rows = DB::table('sync_records')->where('shop_id', $shopId)
            ->where('updated_at', '>', $since)
            ->orderBy('updated_at')->limit(2000)->get();
        foreach ($rows as $row) {
            if (!isset($changes[(string) $row->entity])) {
                continue;
            }
            $payload = json_decode((string) $row->payload, true);
            if (!is_array($payload)) {
                continue;
            }
            // A device never needs its own push echoed back to it.
            if (($payload['__device'] ?? null) === $origin && $origin !== '') {
                continue;
            }
            unset($payload['__device']);
            if (!empty($row->deleted_at)) {
                $payload['deleted_at'] = (string) $row->deleted_at;
            }
            $changes[(string) $row->entity][] = $payload;
        }

        return $this->ok('OK', [
            'changes' => $changes,
            'server_time' => Billing::nowMs(),
            'cursor' => Billing::nowMs(),
            'settings' => [
                'auto_lock_minutes' => (int) ($settings['auto_lock_minutes'] ?? 0),
                'currency' => (string) $this->shop()['currency'],
                'receipt_footer' => (string) ($settings['receipt_footer'] ?? ''),
                'tax_percent' => (float) ($settings['tax_percent'] ?? 0),
            ],
        ]);
    }
}
