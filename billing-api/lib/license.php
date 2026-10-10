<?php
/**
 * The licence and device authority of the whole product.
 *
 * The Android app never decides anything on its own: it asks
 * {@see qt_license_state()} through /v1/license/status and routes whatever comes back.
 * A locally patched app therefore gains nothing, because the router applies the same
 * answer again in front of every data-modifying endpoint (see the route table in
 * support.php), and {@see qt_touch_device()} keeps the device registry honest.
 *
 * States the app understands:
 *   ACTIVE, PENDING, EXPIRED, REVOKED, SUSPENDED, BLOCKED, UNKNOWN
 */

declare(strict_types=1);

/** Shop row (also the "billing account": one shop = one subscription). */
function qt_load_shop(int $shopId): ?array
{
    if ($shopId <= 0) {
        return null;
    }
    return qt_row('SELECT * FROM shops WHERE id = ?', [$shopId]);
}

/** User row, refusing users that are not attached to an active shop. */
function qt_load_user(int $userId): ?array
{
    if ($userId <= 0) {
        return null;
    }
    return qt_row('SELECT * FROM users WHERE id = ? AND is_active = 1', [$userId]);
}

/**
 * The single answer to "may this shop use the app right now?".
 *
 * Order matters and is intentional: an administrator's block always beats an
 * unexpired licence, and an expiry date always beats a friendly status string.
 */
function qt_license_state(?array $shop, ?array $user, string $deviceId = ''): array
{
    $state = [
        'state' => 'UNKNOWN',
        'unlocked' => false,
        'confirmed' => false,
        'expires_at_ms' => 0,
        'days_left' => -1,
        'duration_label' => '',
        'request_status' => '',
        'plan_code' => '',
        'plan_name' => '',
        'seat' => ['used' => 0, 'limit' => 0],
    ];
    if ($shop === null) {
        return $state;
    }

    $shopStatus = strtoupper((string) ($shop['status'] ?? 'ACTIVE'));
    if ($shopStatus === 'BLOCKED') {
        $state['state'] = 'BLOCKED';
        $state['request_status'] = 'ACCOUNT_BLOCKED';
        return $state;
    }
    if ($shopStatus === 'SUSPENDED') {
        $state['state'] = 'SUSPENDED';
        $state['request_status'] = 'SHOP_INACTIVE';
        return $state;
    }

    $licence = qt_row('SELECT * FROM licences WHERE shop_id = ? ORDER BY id DESC LIMIT 1',
        [(int) $shop['id']]);
    if ($licence === null) {
        $state['state'] = 'PENDING';
        $state['request_status'] = 'NO_LICENSE';
        $state['seat'] = qt_device_seat($shop);
        return $state;
    }

    $licenceStatus = strtoupper((string) ($licence['status'] ?? 'PENDING'));
    $expiresAt = (int) ($licence['expires_at'] ?? 0);
    $state['seat'] = qt_device_seat($shop);
    $state['request_status'] = $licenceStatus;

    if ($licenceStatus === 'REVOKED') {
        $state['state'] = 'REVOKED';
        return $state;
    }
    if ($licenceStatus === 'PENDING') {
        $state['state'] = 'PENDING';
        return $state;
    }

    $state['expires_at_ms'] = $expiresAt > 0 ? $expiresAt * 1000 : 0;
    if ($expiresAt > 0) {
        $state['days_left'] = (int) floor(($expiresAt - time()) / 86400);
        $state['duration_label'] = $state['days_left'] > 0
            ? $state['days_left'] . ' Days' : 'Expired';
    } else {
        $state['duration_label'] = 'Lifetime';
    }

    // A lifetime licence (expires_at = 0) never blocks; anything else is judged on
    // the server clock, which is the only clock a till cannot be blamed for.
    $expired = $expiresAt > 0 && $expiresAt < time();
    if ($expired) {
        $state['state'] = 'EXPIRED';
        $state['days_left'] = 0;
        return $state;
    }

    $state['state'] = 'ACTIVE';
    $state['unlocked'] = true;
    $state['confirmed'] = !empty($user['licence_confirmed_at']);
    $plan = qt_plan_for($shop);
    if ($plan !== null) {
        $state['plan_code'] = (string) $plan['code'];
        $state['plan_name'] = (string) $plan['name'];
    }
    return $state;
}

/** The plan row behind a shop (by shop.plan_code, falling back to the default). */
function qt_plan_for(?array $shop): ?array
{
    if ($shop === null) {
        return null;
    }
    $code = (string) ($shop['plan_code'] ?? '');
    if ($code !== '') {
        $plan = qt_row('SELECT * FROM plans WHERE code = ? AND is_active = 1', [$code]);
        if ($plan !== null) {
            return $plan;
        }
    }
    return qt_row('SELECT * FROM plans WHERE is_active = 1 ORDER BY sort_order ASC, id ASC LIMIT 1');
}

/** How many tills may bill for this shop, and how many are bound right now. */
function qt_device_seat(array $shop): array
{
    $plan = qt_plan_for($shop);
    $limit = $plan === null ? 1 : max(1, (int) ($plan['max_devices'] ?? 1));
    $counted = qt_row(
        'SELECT COUNT(*) AS total FROM app_devices WHERE shop_id = ? AND status = ?',
        [(int) $shop['id'], 'ACTIVE']
    );
    $used = (int) ($counted['total'] ?? 0);
    if ($limit > 0 && $used > $limit) {
        $used = $limit;
    }
    return ['used' => max(0, $used), 'limit' => $limit];
}

/**
 * Records (or refreshes) the device that is calling and returns its row.
 * A new device may register while the plan still has a free seat; a device the
 * administrator blocked stays blocked even if the app keeps trying.
 */
function qt_touch_device(array $context): ?array
{
    $deviceId = trim((string) ($context['device_id'] ?? ''));
    if ($deviceId === '' || strlen($deviceId) < 16) {
        return null;
    }
    $shopId = (int) ($context['shop']['id'] ?? 0);
    if ($shopId <= 0) {
        return null;
    }
    $input = $context['input'] ?? [];
    $existing = qt_row('SELECT * FROM app_devices WHERE shop_id = ? AND device_id = ?',
        [$shopId, $deviceId]);

    if ($existing === null) {
        $seat = qt_device_seat($context['shop']);
        if ($seat['limit'] > 0 && $seat['used'] >= $seat['limit']) {
            // The seat is full: the shop is told to free one instead of silently
            // sharing a licence across unlimited tills.
            qt_deny('DEVICE_LIMIT', sprintf(
                'This plan allows %d billing device%s and all of them are already '
                . 'linked. Release a device in Settings › Device access, or ask for '
                . 'a bigger plan.', $seat['limit'], $seat['limit'] === 1 ? '' : 's'
            ), 409);
        }
        qt_insert('app_devices', [
            'shop_id' => $shopId,
            'device_id' => $deviceId,
            'device_name' => (string) ($input['device_name'] ?? ''),
            'os_version' => (string) ($input['os_version'] ?? ''),
            'app_version' => (string) ($context['app_version'] ?? ''),
            'status' => 'ACTIVE',
            'first_seen_at' => qt_sql_now(),
            'last_seen_at' => qt_sql_now(),
            'seen_count' => 1,
        ]);
        return qt_row('SELECT * FROM app_devices WHERE shop_id = ? AND device_id = ?',
            [$shopId, $deviceId]);
    }

    if (strtoupper((string) $existing['status']) === 'BLOCKED') {
        qt_deny('DEVICE_BLOCKED', 'This device has been blocked for the shop.', 403);
    }
    qt_update('app_devices', [
        'last_seen_at' => qt_sql_now(),
        'app_version' => (string) ($context['app_version'] ?? $existing['app_version']),
        'device_name' => (string) ($input['device_name'] ?? $existing['device_name']),
        'seen_count' => (int) $existing['seen_count'] + 1,
    ], 'id = :id', [':id' => (int) $existing['id']]);
    return qt_row('SELECT * FROM app_devices WHERE id = ?', [(int) $existing['id']]);
}

/** The JSON shape /v1/license/status answers with (and that register/login reuse). */
function qt_license_payload(array $state): array
{
    return [
        'license_status' => $state['state'],
        'account_status' => $state['request_status'],
        'unlocked' => $state['state'] === 'ACTIVE' && $state['unlocked'],
        'confirmed' => $state['state'] === 'ACTIVE' && $state['confirmed'],
        'request_status' => $state['request_status'],
        'duration_label' => $state['duration_label'],
        'expires_at_ms' => $state['expires_at_ms'],
        'server_time_ms' => qt_now_ms(),
        'server_time' => time(),
        'days_left' => $state['days_left'] >= 0 ? $state['days_left'] : null,
        'plan' => ['code' => $state['plan_code'], 'name' => $state['plan_name']],
        'device_seat' => ['used' => $state['seat']['used'], 'limit' => $state['seat']['limit']],
    ];
}

/** QT-XXXXX-XXXXX-XXXXX-XXXXX — the format the app's verify screen expects. */
function qt_new_license_key(): string
{
    $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    $blocks = [];
    for ($block = 0; $block < 4; $block++) {
        $chars = '';
        for ($i = 0; $i < 5; $i++) {
            $chars .= $alphabet[random_int(0, strlen($alphabet) - 1)];
        }
        $blocks[] = $chars;
    }
    return 'QT-' . implode('-', $blocks);
}

/** Extends (or starts) a licence; used by both the key redemption and the panel. */
function qt_extend_licence(int $shopId, int $days, string $note = ''): void
{
    $licence = qt_row('SELECT * FROM licences WHERE shop_id = ? ORDER BY id DESC LIMIT 1', [$shopId]);
    $base = time();
    if ($licence !== null) {
        $current = (int) ($licence['expires_at'] ?? 0);
        if ($current > $base) {
            $base = $current;
        }
    }
    $expires = $days > 0 ? $base + $days * 86400 : 0;

    if ($licence === null) {
        qt_insert('licences', [
            'shop_id' => $shopId,
            'license_key' => qt_new_license_key(),
            'status' => 'ACTIVE',
            'started_at' => qt_sql_now(),
            'expires_at' => $expires,
            'note' => $note,
            'created_at' => qt_sql_now(),
        ]);
        return;
    }
    qt_update('licences', [
        'status' => 'ACTIVE',
        'expires_at' => $expires,
        'started_at' => qt_sql_now(),
        'note' => $note !== '' ? $note : (string) $licence['note'],
    ], 'id = :id', [':id' => (int) $licence['id']]);
}
