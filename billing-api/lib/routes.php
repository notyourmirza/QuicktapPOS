<?php
/**
 * Every endpoint the Android app calls. Handlers are intentionally thin: they
 * read input, apply one rule, and answer with the shared envelope. The licence
 * decision itself lives in license.php so there is exactly one of it.
 */

declare(strict_types=1);

// ------------------------------------------------------------- app bootstrap

/** GET v1/app/bootstrap — fixed URL first, Firebase options from the panel. */
function qt_app_bootstrap(array $input, array $context): void
{
    $settings = qt_settings_all();
    qt_json(true, 'OK', [
        'api_base_url' => (string) ($settings['api_base_url'] ?? ''),
        'firebase' => [
            'app_id' => (string) ($settings['fb_app_id'] ?? ''),
            'api_key' => (string) ($settings['fb_api_key'] ?? ''),
            'project_id' => (string) ($settings['fb_project_id'] ?? ''),
            'sender_id' => (string) ($settings['fb_sender_id'] ?? ''),
        ],
        'min_app_version' => (string) ($settings['min_app_version'] ?? ''),
        'force_update' => (int) ($settings['force_update'] ?? 0),
    ]);
}

/** GET v1/app/config — the whole remote configuration in one answer. */
function qt_app_config(array $input, array $context): void
{
    $settings = qt_settings_all();
    $appName = (string) ($settings['app_name'] ?? 'QuickTap POS');
    if (!empty($context['shop']['app_name'])) {
        // A shop may carry its own name (POST v1/settings/app-name).
        $appName = (string) $context['shop']['app_name'];
    }
    qt_json(true, 'OK', [
        'app_name' => $appName,
        'logo_url' => (string) ($settings['logo_url'] ?? ''),
        'receipt_template' => (string) ($settings['receipt_template'] ?? 'classic'),
        'support' => ['whatsapp' => (string) ($settings['support_whatsapp'] ?? '')],
        'support_whatsapp' => (string) ($settings['support_whatsapp'] ?? ''),
        'sync_minutes' => (int) ($settings['license_sync_minutes'] ?? 60),
        'offline_grace_hours' => (int) ($settings['offline_grace_hours'] ?? 24),
        'notifications' => qt_notification_rows(),
    ]);
}

// ------------------------------------------------------------------- session

/** POST v1/auth/register — shop + owner + licence row + device binding, at once. */
function qt_auth_register(array $input, array $context): void
{
    $shopName = qt_string($input, 'shop_name');
    $username = strtolower(qt_string($input, 'username'));
    $password = (string) ($input['password'] ?? '');
    $fullName = qt_string($input, 'full_name');
    $phone = (string) preg_replace('/[^0-9+]/', '', qt_string($input, 'phone'));
    $deviceId = (string) ($context['device_id'] ?? '');

    if ($shopName === '' || mb_strlen($shopName) > 60) {
        qt_deny('BAD_SHOP_NAME', 'Enter a shop name between 1 and 60 characters.', 422);
    }
    if (!preg_match('/^[a-z0-9._-]{4,32}$/', $username)) {
        qt_deny('BAD_USERNAME', 'Use 4-32 characters: letters, numbers, dot, dash or underscore.', 422);
    }
    if (strlen($password) < 8) {
        qt_deny('WEAK_PASSWORD', 'Choose a password of at least 8 characters.', 422);
    }
    if ($deviceId === '') {
        qt_deny('NO_DEVICE_ID', 'This device could not be identified. Update the app and try again.', 422);
    }
    if (qt_row('SELECT id FROM users WHERE username = ?', [$username]) !== null) {
        qt_deny('USERNAME_TAKEN', 'That username is already used. Pick another one.', 409);
    }

    // ONE DEVICE = ONE ACCOUNT. A device that already owns an account may not open a
    // second one, which is what stops a licence being copied phone to phone.
    $existingOnDevice = qt_row('SELECT u.id, u.shop_id, s.name AS shop_name FROM users u
        JOIN shops s ON s.id = u.shop_id WHERE u.device_id = ? AND u.is_active = 1', [$deviceId]);
    if ($existingOnDevice !== null) {
        qt_deny('DEVICE_TAKEN', sprintf(
            'This device is already registered to "%s". Sign in there instead of '
            . 'creating a second account.', $existingOnDevice['shop_name']), 409);
    }

    $planCode = (string) ($context['client']['default_plan'] ?? '');
    $trialDays = (int) ($context['config']['trial_days'] ?? 0);

    $shopId = qt_insert('shops', [
        'name' => $shopName,
        'currency' => (string) ($context['config']['default_currency'] ?? 'PKR'),
        'status' => 'ACTIVE',
        'plan_code' => $planCode,
        'app_name' => '',
        'created_at' => qt_sql_now(),
        'updated_at' => qt_sql_now(),
    ]);
    $userId = qt_insert('users', [
        'shop_id' => $shopId,
        'username' => $username,
        'password_hash' => password_hash($password, PASSWORD_DEFAULT),
        'full_name' => $fullName,
        'phone' => $phone,
        'role' => 'owner',
        'fingerprint_enabled' => 0,
        'device_id' => $deviceId,
        'licence_confirmed_at' => null,
        'is_active' => 1,
        'created_at' => qt_sql_now(),
        'last_login_at' => qt_sql_now(),
    ]);

    // New accounts start PENDING: the administrator has to approve them (or hand out
    // a licence key), so no shop can bill without being recorded in the panel.
    qt_insert('licences', [
        'shop_id' => $shopId,
        'license_key' => qt_new_license_key(),
        'status' => 'PENDING',
        'started_at' => qt_sql_now(),
        'expires_at' => $trialDays > 0 ? time() + $trialDays * 86400 : 0,
        'note' => 'created by self-registration',
        'created_at' => qt_sql_now(),
    ]);
    if ($trialDays > 0) {
        qt_run('UPDATE licences SET status = ? WHERE shop_id = ? AND expires_at > 0',
            ['ACTIVE', $shopId]);
    }
    qt_log_audit('register', $shopId, 'user ' . $username);

    $shop = qt_load_shop($shopId);
    $user = qt_load_user($userId);
    qt_bind_device($context, $shopId);
    qt_json(true, 'Account created. It is waiting for licence activation.',
        qt_session_payload($user, $shop) + qt_license_payload(qt_license_state($shop, $user, $deviceId)));
}

/** POST v1/auth/login — verifies the password and the device binding. */
function qt_auth_login(array $input, array $context): void
{
    $username = strtolower(qt_string($input, 'username'));
    $password = (string) ($input['password'] ?? '');
    $deviceId = (string) ($context['device_id'] ?? '');

    $user = $username === '' ? null : qt_row('SELECT * FROM users WHERE username = ?', [$username]);
    if ($user === null || !password_verify($password, (string) $user['password_hash'])) {
        qt_log_audit('login_failed', (int) ($user['shop_id'] ?? 0), $username);
        qt_deny('BAD_CREDENTIALS', 'Username or password is not correct.', 401);
    }
    if ((int) $user['is_active'] !== 1) {
        qt_deny('USER_DISABLED', 'This user account is disabled.', 403);
    }
    $shop = qt_load_shop((int) $user['shop_id']);
    if ($shop === null) {
        qt_deny('SHOP_INACTIVE', 'This shop is not available any more.', 403);
    }
    if (strtoupper((string) $shop['status']) === 'BLOCKED') {
        qt_deny('ACCOUNT_BLOCKED', 'This account has been blocked. Contact support.', 403);
    }

    // First sign-in on a new till takes over the device binding only when the plan
    // still has a free seat; otherwise it is a clear refusal, not a silent share.
    if ($deviceId !== '' && (string) $user['device_id'] !== $deviceId) {
        $seat = qt_device_seat($shop);
        $boundToOtherShop = qt_row('SELECT shop_id FROM app_devices WHERE device_id = ? AND status = ? LIMIT 1',
            [$deviceId, 'ACTIVE']);
        if ($boundToOtherShop !== null && (int) $boundToOtherShop['shop_id'] !== (int) $shop['id']) {
            qt_deny('DEVICE_MISMATCH', 'This device is registered to another shop.', 403);
        }
        if ($seat['limit'] > 0 && $seat['used'] >= $seat['limit']
            && qt_row('SELECT id FROM app_devices WHERE shop_id = ? AND device_id = ?',
                [(int) $shop['id'], $deviceId]) === null) {
            qt_deny('DEVICE_LIMIT', sprintf(
                'This plan allows %d billing device%s and all of them are in use. '
                . 'Release one in Settings › Device access, or ask for a bigger plan.',
                $seat['limit'], $seat['limit'] === 1 ? '' : 's'), 409);
        }
        qt_bind_device($context, (int) $shop['id']);
        qt_update('users', ['device_id' => $deviceId], 'id = :id', [':id' => (int) $user['id']]);
        $user = qt_load_user((int) $user['id']);
    }
    qt_run('UPDATE users SET last_login_at = ? WHERE id = ?', [qt_sql_now(), (int) $user['id']]);
    qt_log_audit('login', (int) $shop['id'], $username);

    $licence = qt_license_state($shop, $user, $deviceId);
    qt_json(true, 'Signed in', qt_session_payload($user, $shop) + qt_license_payload($licence));
}

/** POST v1/auth/refresh — swaps a refresh token for a fresh access token. */
function qt_auth_refresh(array $input, array $context): void
{
    $token = qt_string($input, 'refresh_token');
    if ($token === '') {
        qt_deny('NO_TOKEN', 'No refresh token was sent.', 401);
    }
    $stored = qt_row('SELECT * FROM refresh_tokens WHERE token_hash = ? LIMIT 1',
        [qt_hash_token($token)]);
    if ($stored === null) {
        qt_deny('TOKEN_INVALID', 'This session is not recognised. Sign in again.', 401);
    }
    if (!empty($stored['revoked_at'])) {
        qt_deny('TOKEN_REVOKED', 'This session was ended. Sign in again.', 401);
    }
    if ((int) $stored['expires_at'] < time()) {
        qt_run('UPDATE refresh_tokens SET revoked_at = ? WHERE id = ?',
            [qt_sql_now(), (int) $stored['id']]);
        qt_deny('TOKEN_EXPIRED', 'This session expired. Sign in again.', 401);
    }
    $deviceId = (string) ($context['device_id'] ?? '');
    if ($deviceId !== '' && (string) $stored['device_id'] !== '' && $deviceId !== (string) $stored['device_id']) {
        qt_deny('DEVICE_MISMATCH', 'This session belongs to a different device.', 401);
    }
    $user = qt_load_user((int) $stored['user_id']);
    if ($user === null) {
        qt_deny('USER_DISABLED', 'This user account is disabled.', 403);
    }
    $shop = qt_load_shop((int) $user['shop_id']);
    qt_run('UPDATE refresh_tokens SET revoked_at = ? WHERE id = ?',
        [qt_sql_now(), (int) $stored['id']]);

    qt_json(true, 'Session renewed', [
        'tokens' => qt_tokens_for((int) $user['id'], $deviceId),
        'license_status' => qt_license_state($shop, $user, $deviceId)['state'],
    ]);
}

/** POST v1/auth/logout — ends this device's refresh tokens. */
function qt_auth_logout(array $input, array $context): void
{
    $user = $context['user'];
    qt_run('UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL',
        [qt_sql_now(), (int) $user['id']]);
    qt_json(true, 'Signed out', ['revoked' => true]);
}

/** GET v1/auth/me — the profile the app caches for role + shop display. */
function qt_auth_me(array $input, array $context): void
{
    $user = $context['user'];
    $shop = $context['shop'];
    $state = $context['licence'];
    qt_json(true, 'OK', [
        'id' => (int) $user['id'],
        'username' => (string) $user['username'],
        'full_name' => (string) $user['full_name'],
        'phone' => (string) $user['phone'],
        'role' => (string) $user['role'],
        'fingerprint_enabled' => (int) $user['fingerprint_enabled'],
        'shop_id' => (int) $shop['id'],
        'shop_name' => (string) $shop['name'],
        'currency' => (string) $shop['currency'],
        'device_id' => (string) ($context['device_id'] ?? ''),
        'license_status' => $state['state'],
        'plan' => ['code' => $state['plan_code'], 'name' => $state['plan_name']],
    ]);
}

/** POST v1/auth/unlock — the auto-lock screen verifies against the server. */
function qt_auth_unlock(array $input, array $context): void
{
    $password = (string) ($input['password'] ?? '');
    if ($password === '' || !password_verify($password, (string) $context['user']['password_hash'])) {
        qt_deny('BAD_CREDENTIALS', 'That password is not correct.', 401);
    }
    // Nothing is stored for a successful unlock: the app owns the idle clock, the
    // server only proves the password is real on this device.
    qt_json(true, 'Unlocked', ['unlocked' => true]);
}

/** POST v1/auth/fingerprint — remembers the cashier's biometric preference. */
function qt_auth_fingerprint(array $input, array $context): void
{
    $enabled = qt_int($input, 'enabled', 0) === 1 ? 1 : 0;
    qt_update('users', ['fingerprint_enabled' => $enabled], 'id = :id', [':id' => (int) $context['user']['id']]);
    qt_json(true, $enabled ? 'Fingerprint unlock enabled' : 'Fingerprint unlock disabled',
        ['fingerprint_enabled' => $enabled]);
}

// --------------------------------------------------------- billing / licence

/** GET v1/license/status — the only answer the app trusts. */
function qt_license_status(array $input, array $context): void
{
    qt_touch_device($context);
    $state = $context['licence'];
    $payload = qt_license_payload($state);
    $plan = qt_plan_for($context['shop']);
    $payload['plan'] = $plan === null ? null : [
        'code' => (string) $plan['code'],
        'name' => (string) $plan['name'],
        'tagline' => (string) $plan['tagline'],
        'price' => (float) $plan['price'],
        'price_yearly' => (float) $plan['price_yearly'],
        'max_devices' => (int) $plan['max_devices'],
        'features' => qt_features($plan['features'] ?? ''),
    ];
    qt_json(true, $state['state'] === 'ACTIVE'
        ? 'Licence active' : 'Licence status: ' . $state['state'], $payload);
}

/**
 * POST v1/license/verify — redeems the key the administrator handed out.
 * Allowed while PENDING or EXPIRED, which is exactly the point: this is how a shop
 * gets back in. The key is matched on the shop that owns it, so a key cannot be
 * replayed across shops.
 */
function qt_license_verify(array $input, array $context): void
{
    $key = strtoupper((string) preg_replace('/\s+/', '', qt_string($input, 'license_key')));
    if ($key === '') {
        qt_deny('LICENSE_NOT_FOUND', 'Enter the licence key you received.', 422);
    }
    $licence = qt_row('SELECT * FROM licences WHERE license_key = ?', [$key]);
    if ($licence === null) {
        $code = qt_row('SELECT * FROM activation_codes WHERE code = ? AND used_at IS NULL', [$key]);
        if ($code === null) {
            qt_deny('LICENSE_NOT_FOUND', 'That key is not a QuickTap licence key.', 404);
        }
        qt_extend_licence((int) $context['shop']['id'], (int) $code['days'],
            'activated with code ' . $key);
        qt_run('UPDATE activation_codes SET used_at = ?, used_by_shop = ? WHERE id = ?',
            [qt_sql_now(), (int) $context['shop']['id'], (int) $code['id']]);
        qt_log_audit('activate_code', (int) $context['shop']['id'], $key);
        qt_finish_activation($context, $key);
    }
    if ((int) $licence['shop_id'] !== (int) $context['shop']['id']) {
        qt_deny('LICENSE_FOREIGN', 'This licence key belongs to another shop.', 403);
    }
    if (strtoupper((string) $licence['status']) === 'REVOKED') {
        qt_deny('LICENSE_REVOKED', 'This licence has been revoked. Contact support.', 403);
    }
    qt_update('licences', ['status' => 'ACTIVE', 'activated_at' => qt_sql_now()],
        'id = :id', [':id' => (int) $licence['id']]);
    qt_finish_activation($context, $key);
}

/** POST v1/license/confirm — the "this is really me" step after activation. */
function qt_license_confirm(array $input, array $context): void
{
    $username = strtolower(qt_string($input, 'username'));
    $password = (string) ($input['password'] ?? '');
    $user = $context['user'];
    if ($username !== '' && $username !== strtolower((string) $user['username'])) {
        qt_deny('BAD_CREDENTIALS', 'That username does not match this account.', 401);
    }
    if (!password_verify($password, (string) $user['password_hash'])) {
        qt_deny('BAD_CREDENTIALS', 'Password is not correct.', 401);
    }
    qt_update('users', ['licence_confirmed_at' => qt_sql_now()], 'id = :id', [':id' => (int) $user['id']]);
    $state = qt_license_state($context['shop'], qt_load_user((int) $user['id']),
        (string) ($context['device_id'] ?? ''));
    qt_log_audit('licence_confirmed', (int) $context['shop']['id'], (string) $user['username']);
    $payload = qt_license_payload($state);
    $payload['confirmed'] = true;
    qt_json(true, 'Licence confirmed for this device', $payload);
}

// ----------------------------------------------------------------- plan store

/** GET v1/plans — the catalogue the panel publishes, plus what the shop has. */
function qt_plans(array $input, array $context): void
{
    $rows = qt_rows('SELECT * FROM plans WHERE is_active = 1 ORDER BY sort_order ASC, id ASC');
    $plans = [];
    foreach ($rows as $plan) {
        $plans[] = [
            'code' => (string) $plan['code'],
            'name' => (string) $plan['name'],
            'tagline' => (string) $plan['tagline'],
            'tag' => (string) $plan['tag'],
            'price' => (float) $plan['price'],
            'price_yearly' => (float) $plan['price_yearly'],
            'max_devices' => (int) $plan['max_devices'],
            'features' => qt_features($plan['features'] ?? ''),
        ];
    }
    $data = ['plans' => $plans, 'currency' => (string) ($context['config']['default_currency'] ?? 'PKR')];

    $shop = $context['shop'] ?? null;
    if ($shop !== null) {
        $state = qt_license_state($shop, $context['user'] ?? null, '');
        $plan = qt_plan_for($shop);
        $data['current'] = $plan === null ? null : [
            'code' => (string) $plan['code'],
            'name' => (string) $plan['name'],
            'status' => $state['state'],
            'days_left' => $state['days_left'] >= 0 ? $state['days_left'] : null,
            'ends_at' => $state['expires_at_ms'] > 0
                ? date('Y-m-d H:i:s', (int) ($state['expires_at_ms'] / 1000)) : '',
            'expires_at_ms' => $state['expires_at_ms'],
            'device_seat' => $state['seat'],
        ];
    }
    qt_json(true, 'OK', $data);
}

// ----------------------------------------------------------- device accounting

/** GET v1/devices — every till the panel has recorded for this shop. */
function qt_devices_list(array $input, array $context): void
{
    $shopId = (int) $context['shop']['id'];
    $mine = (string) ($context['device_id'] ?? '');
    $rows = qt_rows('SELECT * FROM app_devices WHERE shop_id = ? ORDER BY last_seen_at DESC, id DESC',
        [$shopId]);
    $devices = [];
    foreach ($rows as $row) {
        $devices[] = [
            'device_id' => (string) $row['device_id'],
            'device_name' => (string) $row['device_name'],
            'model' => (string) $row['device_name'],
            'os_version' => (string) $row['os_version'],
            'app_version' => (string) $row['app_version'],
            'status' => (string) $row['status'],
            'first_seen_at_ms' => qt_millis((string) $row['first_seen_at']),
            'last_seen_at_ms' => qt_millis((string) $row['last_seen_at']),
            'seen_count' => (int) $row['seen_count'],
            'is_current' => $mine !== '' && $mine === (string) $row['device_id'],
        ];
    }
    qt_json(true, 'OK', [
        'devices' => $devices,
        'seat' => qt_device_seat($context['shop']),
        'shop_name' => (string) $context['shop']['name'],
    ]);
}

/**
 * POST v1/devices/deactivate — frees a seat (lost or replaced till).
 * Only ever the caller's own shop, and the released device stops being accepted
 * immediately because its rows are marked RELEASED.
 */
function qt_devices_deactivate(array $input, array $context): void
{
    $shopId = (int) $context['shop']['id'];
    $target = qt_string($input, 'device_id');
    if ($target === '') {
        $target = (string) ($context['device_id'] ?? '');
    }
    $device = $target === '' ? null
        : qt_row('SELECT * FROM app_devices WHERE shop_id = ? AND device_id = ?', [$shopId, $target]);
    if ($device === null) {
        qt_deny('DEVICE_NOT_FOUND', 'That device is not registered to this shop.', 404);
    }
    qt_run('UPDATE app_devices SET status = ?, released_at = ?, last_seen_at = ? WHERE id = ?',
        ['RELEASED', qt_sql_now(), qt_sql_now(), (int) $device['id']]);
    qt_run('UPDATE refresh_tokens SET revoked_at = ? WHERE device_id = ? AND revoked_at IS NULL',
        [qt_sql_now(), $target]);
    qt_run('UPDATE users SET device_id = NULL WHERE shop_id = ? AND device_id = ?',
        [$shopId, $target]);
    qt_log_audit('device_release', $shopId, $target);

    $isSelf = $target === (string) ($context['device_id'] ?? '');
    qt_json(true, $isSelf
        ? 'This device was released. It must be re-added before it can bill again.'
        : 'Device released. The seat is free for another till.', [
            'released' => $target,
            'seat' => qt_device_seat($context['shop']),
            'this_device' => $isSelf,
        ]);
}

// --------------------------------------------------------- settings + notices

/** POST v1/settings/app-name — per-shop name override, read back by every device. */
function qt_settings_app_name(array $input, array $context): void
{
    $name = qt_string($input, 'app_name');
    if ($name === '' || mb_strlen($name) > 40) {
        qt_deny('BAD_APP_NAME', 'Enter a name between 1 and 40 characters.', 422);
    }
    $shopId = (int) $context['shop']['id'];
    qt_run('UPDATE shops SET app_name = ?, updated_at = ? WHERE id = ?',
        [$name, qt_sql_now(), $shopId]);
    $settings = qt_settings_all();
    qt_log_audit('app_name', $shopId, $name);
    qt_json(true, 'App name updated', [
        'app_name' => $name,
        'config' => [
            'app_name' => $name,
            'logo_url' => (string) ($settings['logo_url'] ?? ''),
            'receipt_template' => (string) ($settings['receipt_template'] ?? 'classic'),
            'support_whatsapp' => (string) ($settings['support_whatsapp'] ?? ''),
            'sync_minutes' => (int) ($settings['license_sync_minutes'] ?? 60),
        ],
    ]);
}

/** GET v1/notifications — active announcements for the bell screen. */
function qt_notifications(array $input, array $context): void
{
    qt_json(true, 'OK', ['notifications' => qt_notification_rows()]);
}

// ------------------------------------------------------------- catalogue sync

/**
 * POST v1/sync/push — upsert by uuid, last write wins.
 *
 * A shop without Cloud Sync still gets multi-till catalogue sync from the app's
 * "Sync now" button, so the rows are stored per shop and replayed to the others.
 */
function qt_sync_push(array $input, array $context): void
{
    $shopId = (int) $context['shop']['id'];
    $deviceId = (string) ($context['device_id'] ?? '');
    // Writing to the shop's history is also what proves the till still deserves its
    // seat, so the registry is refreshed here rather than only on sign-in.
    qt_touch_device($context);
    $accepted = ['categories' => [], 'products' => [], 'orders' => []];
    $limit = (int) ($context['config']['sync_batch_limit'] ?? 200);

    foreach (['categories', 'products', 'orders'] as $entity) {
        $rows = $input[$entity] ?? [];
        if (!is_array($rows)) {
            continue;
        }
        if (count($rows) > $limit) {
            qt_deny('BATCH_TOO_LARGE', sprintf(
                'Send at most %d %s records per push.', $limit, $entity), 413);
        }
        foreach ($rows as $row) {
            if (!is_array($row)) {
                continue;
            }
            $uuid = qt_string($row, 'uuid');
            if ($uuid === '') {
                continue;
            }
            // The device's own clock decides which write wins, so a malformed
            // timestamp must never be stored: fall back to "now" instead.
            $updatedAt = qt_clean_time(qt_string($row, 'updated_at', qt_sql_now()));
            $deletedAt = empty($row['deleted_at']) ? null : qt_string($row, 'deleted_at');
            qt_upsert_sync($shopId, $entity, $uuid, $row, $updatedAt, $deletedAt, $deviceId);
            $accepted[$entity][] = $uuid;
        }
    }
    qt_run('UPDATE shops SET updated_at = ? WHERE id = ?', [qt_sql_now(), $shopId]);
    qt_json(true, 'Accepted', ['accepted' => $accepted, 'server_time' => qt_now_ms()]);
}

/** GET v1/sync/pull?since=… — everything that changed after the device's cursor. */
function qt_sync_pull(array $input, array $context): void
{
    qt_touch_device($context);
    $shopId = (int) $context['shop']['id'];
    $sinceMillis = qt_int($input, 'since', 0);
    $since = $sinceMillis > 0
        ? date('Y-m-d H:i:s', (int) ($sinceMillis / 1000))
        : date('Y-m-d H:i:s', time() - 30 * 86400);
    $deviceId = (string) ($context['device_id'] ?? '');

    $changes = ['categories' => [], 'products' => [], 'orders' => []];
    $rows = qt_rows('SELECT entity, payload, deleted_at FROM sync_records
        WHERE shop_id = ? AND updated_at > ? ORDER BY updated_at ASC LIMIT 2000',
        [$shopId, $since]);
    foreach ($rows as $row) {
        $entity = (string) $row['entity'];
        if (!isset($changes[$entity])) {
            continue;
        }
        $payload = json_decode((string) $row['payload'], true);
        if (!is_array($payload)) {
            continue;
        }
        // A device never needs its own push echoed back to it.
        if (!empty($payload['__device']) && $payload['__device'] === $deviceId) {
            unset($payload['__device']);
            continue;
        }
        unset($payload['__device']);
        if (!empty($row['deleted_at'])) {
            $payload['deleted_at'] = $row['deleted_at'];
        }
        $changes[$entity][] = $payload;
    }

    $settings = qt_settings_all();
    $shop = $context['shop'];
    qt_json(true, 'OK', [
        'changes' => $changes,
        'server_time' => qt_now_ms(),
        'cursor' => qt_now_ms(),
        'settings' => [
            'auto_lock_minutes' => (int) ($settings['auto_lock_minutes'] ?? 0),
            'currency' => (string) $shop['currency'],
            'receipt_footer' => (string) ($settings['receipt_footer'] ?? ''),
            'tax_percent' => (float) ($settings['tax_percent'] ?? 0),
        ],
    ]);
}

// ---------------------------------------------------------------- internals

/** Everything a signed-in device needs to store as its session. */
function qt_session_payload(array $user, array $shop): array
{
    return [
        'tokens' => qt_tokens_for((int) $user['id'], (string) ($user['device_id'] ?? '')),
        'user' => [
            'id' => (int) $user['id'],
            'username' => (string) $user['username'],
            'full_name' => (string) $user['full_name'],
            'role' => (string) $user['role'],
            'fingerprint_enabled' => (int) $user['fingerprint_enabled'],
        ],
        'shop' => [
            'id' => (int) $shop['id'],
            'name' => (string) $shop['name'],
            'currency' => (string) $shop['currency'],
        ],
    ];
}

/** Issues an access token and one refresh token bound to this device. */
function qt_tokens_for(int $userId, string $deviceId): array
{
    $config = qt_config();
    $access = qt_jwt_issue(['sub' => $userId, 'dev' => $deviceId], $config);
    $refresh = bin2hex(random_bytes(32));
    qt_insert('refresh_tokens', [
        'user_id' => $userId,
        'token_hash' => qt_hash_token($refresh),
        'device_id' => $deviceId,
        'expires_at' => time() + 86400 * (int) ($config['refresh_ttl_days'] ?? 60),
        'created_at' => qt_sql_now(),
    ]);
    return [
        'access_token' => $access,
        'refresh_token' => $refresh,
        'expires_in' => (int) $config['jwt_ttl'],
        'token_type' => 'Bearer',
    ];
}

function qt_hash_token(string $token): string
{
    return hash('sha256', $token);
}

/** Links (or refreshes) the device row for a shop. */
function qt_bind_device(array $context, int $shopId): void
{
    $deviceId = (string) ($context['device_id'] ?? '');
    if ($deviceId === '') {
        return;
    }
    $input = $context['input'] ?? [];
    $shop = qt_load_shop($shopId);
    if ($shop === null) {
        return;
    }
    $existing = qt_row('SELECT * FROM app_devices WHERE shop_id = ? AND device_id = ?',
        [$shopId, $deviceId]);
    if ($existing === null) {
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
        return;
    }
    // A device the administrator blocked stays blocked, and one that was released may
    // only come back while the plan still has a seat — otherwise "release + sign in on
    // the next till" would be a way around the limit instead of the fix it is meant to be.
    if (strtoupper((string) ($existing['status'] ?? '')) === 'BLOCKED') {
        qt_deny('DEVICE_BLOCKED', 'This device has been blocked for the shop.', 403);
    }
    $seat = qt_device_seat($shop);
    if ((string) ($existing['status'] ?? '') !== 'ACTIVE' && $seat['limit'] > 0
        && $seat['used'] >= $seat['limit']) {
        qt_deny('DEVICE_LIMIT', sprintf(
            'All %d device seat%s on this plan are in use. Release one in '
            . 'Settings › Device access first.', $seat['limit'], $seat['limit'] === 1 ? '' : 's'
        ), 409);
    }
    qt_run('UPDATE app_devices SET status = ?, last_seen_at = ?, device_name = ?, app_version = ?
        WHERE shop_id = ? AND device_id = ?',
        ['ACTIVE', qt_sql_now(), (string) ($input['device_name'] ?? ''),
         (string) ($context['app_version'] ?? ''), $shopId, $deviceId]);
}

/** Shared tail of both verification paths: report the freshly active licence. */
function qt_finish_activation(array $context, string $key): void
{
    $shop = qt_load_shop((int) $context['shop']['id']);
    $user = qt_load_user((int) $context['user']['id']);
    $state = qt_license_state($shop, $user, (string) ($context['device_id'] ?? ''));
    $payload = qt_license_payload($state);
    $payload['license_key'] = $key;
    // The app shows a congratulations dialog and then asks for the password.
    $payload['confirmed'] = false;
    qt_json(true, 'Licence activated. Confirm your username and password to finish.', $payload);
}

/** Accepts only a parseable datetime; anything else becomes the server's now. */
function qt_clean_time(string $candidate): string
{
    $normalized = preg_replace('/Z$/', '', str_replace('T', ' ', $candidate));
    $parsed = strtotime(trim($normalized));
    return $parsed === false ? qt_sql_now() : date('Y-m-d H:i:s', $parsed);
}

/** Inserts or replaces one synced record, keeping the newest write. */
function qt_upsert_sync(int $shopId, string $entity, string $uuid, array $payload,
                       string $updatedAt, ?string $deletedAt, string $deviceId): void
{
    $payload['__device'] = $deviceId;
    $existing = qt_row('SELECT id, updated_at FROM sync_records WHERE shop_id = ? AND entity = ? AND uuid = ?',
        [$shopId, $entity, $uuid]);
    $encoded = json_encode($payload, JSON_UNESCAPED_UNICODE);
    if ($existing === null) {
        qt_insert('sync_records', [
            'shop_id' => $shopId,
            'entity' => $entity,
            'uuid' => $uuid,
            'payload' => $encoded,
            'updated_at' => $updatedAt,
            'deleted_at' => $deletedAt,
        ]);
        return;
    }
    // Last write wins, decided on the timestamp the device wrote.
    if (strtotime($updatedAt) < strtotime((string) $existing['updated_at'])) {
        return;
    }
    qt_run('UPDATE sync_records SET payload = ?, updated_at = ?, deleted_at = ? WHERE id = ?',
        [$encoded, $updatedAt, $deletedAt, (int) $existing['id']]);
}

/** Feature lists are stored as one line per feature, returned as a JSON array. */
function qt_features($stored): array
{
    if (is_array($stored)) {
        return $stored;
    }
    $text = (string) $stored;
    $trimmed = trim($text);
    if ($trimmed !== '' && ($trimmed[0] === '[' || $trimmed[0] === '{')) {
        $decoded = json_decode($trimmed, true);
        if (is_array($decoded)) {
            return array_values(array_map('strval', $decoded));
        }
    }
    $lines = preg_split('/\r\n|\r|\n/', $trimmed) ?: [];
    return array_values(array_filter(array_map('trim', $lines), static fn ($line) => $line !== ''));
}

/** The rows the notification centre renders. */
function qt_notification_rows(): array
{
    $rows = qt_rows("SELECT * FROM notifications WHERE is_active = 1
        AND (ends_at IS NULL OR ends_at >= ?) ORDER BY created_at DESC LIMIT 30",
        [qt_sql_now()]);
    $out = [];
    foreach ($rows as $row) {
        $out[] = [
            'id' => (int) $row['id'],
            'title' => (string) $row['title'],
            'body' => (string) $row['body'],
            'level' => (string) ($row['level'] ?: 'info'),
            'created_at' => (string) $row['created_at'],
            'starts_at' => (string) ($row['starts_at'] ?? ''),
        ];
    }
    return $out;
}

/** All app_settings as a key => value map (the panel edits these). */
function qt_settings_all(): array
{
    static $cache = null;
    if ($cache !== null) {
        return $cache;
    }
    $cache = [];
    foreach (qt_rows('SELECT setting_key, setting_value FROM app_settings') as $row) {
        $cache[(string) $row['setting_key']] = (string) $row['setting_value'];
    }
    return $cache;
}

function qt_setting_set(string $key, string $value): void
{
    $existing = qt_row('SELECT setting_key FROM app_settings WHERE setting_key = ?', [$key]);
    if ($existing === null) {
        qt_insert('app_settings', ['setting_key' => $key, 'setting_value' => $value]);
        return;
    }
    qt_run('UPDATE app_settings SET setting_value = ? WHERE setting_key = ?', [$value, $key]);
}

/** Everything interesting is written down: renewals, blocks, device changes. */
function qt_log_audit(string $action, int $shopId, string $detail): void
{
    try {
        qt_insert('audit_log', [
            'actor' => (string) ($_SERVER['REMOTE_ADDR'] ?? 'app'),
            'action' => $action,
            'shop_id' => $shopId,
            'detail' => mb_substr($detail, 0, 400),
            'created_at' => qt_sql_now(),
        ]);
    } catch (Throwable $ignored) {
        // Auditing must never break a sale.
    }
}
