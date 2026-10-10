<?php
/**
 * Self-check for the billing API. No web server, no MySQL and no Android involved:
 * it builds a throw-away SQLite database, walks the licence state machine and the
 * device-seat maths, and proves the JWT layer signs and verifies.
 *
 *   php tests/smoke.php
 *
 * Exits 0 when every line printed ✓. Run it after any change to lib/ — the state
 * machine in license.php is the one thing that must never be wrong.
 */

declare(strict_types=1);

$root = dirname(__DIR__);
$database = sys_get_temp_dir() . '/quicktap-smoke-' . getmypid() . '.sqlite';
$configFile = sys_get_temp_dir() . '/quicktap-smoke-config-' . getmypid() . '.php';
@unlink($database);

file_put_contents($configFile, '<?php return ['
    . "'driver' => 'sqlite',"
    . "'sqlite_path' => " . var_export($database, true) . ','
    . "'jwt_secret' => 'smoke-test-secret-not-a-real-one-000000',"
    . "'jwt_ttl' => 60,"
    . "'app_id' => 'app_smoke',"
    . "'app_api_key' => 'key_smoke',"
    . "'trial_days' => 0,"
    . "'default_currency' => 'PKR',"
    . "'debug' => true,"
    . '];');
putenv('QT_CONFIG=' . $configFile);
$_SERVER['HTTP_X_APP_ID'] = 'app_smoke';
$_SERVER['HTTP_X_API_KEY'] = 'key_smoke';

require $root . '/lib/support.php';

$failures = 0;
$checks = 0;

function check(string $label, bool $okay, string $detail = ''): void
{
    global $failures, $checks;
    $checks++;
    if (!$okay) {
        $failures++;
    }
    echo ($okay ? "\033[32m✓\033[0m " : "\033[31m✗\033[0m ") . $label
        . ($detail !== '' ? "  ({$detail})" : '') . PHP_EOL;
}

function check_same(string $label, $expected, $actual): void
{
    check($label, $expected === $actual, 'expected ' . var_export($expected, true)
        . ', got ' . var_export($actual, true));
}

echo 'QuickTap billing API smoke test' . PHP_EOL . str_repeat('─', 60) . PHP_EOL;

// ----------------------------------------------------------------- schema (DB)

$pdo = qt_db();
$applied = 0;
foreach (qt_schema_statements('sqlite') as $statement) {
    $pdo->exec($statement);
    $applied++;
}
check('schema applies on sqlite', $applied > 10, $applied . ' statements');
foreach (['api_clients', 'api_tokens', 'app_settings', 'plans', 'shops', 'users', 'licences', 'activation_codes',
    'app_devices', 'refresh_tokens', 'sync_records', 'notifications', 'audit_log', 'admin_users'] as $table) {
    $exists = $pdo->query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = '"
        . $table . "'")->fetchColumn();
    check('table ' . $table, $exists !== false);
}
foreach (qt_schema_seed() as [$statement, $bindings]) {
    $pdo->prepare($statement)->execute($bindings);
}
check_same('plans seeded', 3, (int) $pdo->query('SELECT COUNT(*) FROM plans')->fetchColumn());
check_same('settings seeded', 16, (int) $pdo->query('SELECT COUNT(*) FROM app_settings')->fetchColumn());
check('db readiness probe', qt_db_ready());

// ------------------------------------------------------------------- credentials

$client = qt_find_client('app_smoke', 'key_smoke');
check('config credential pair is accepted', $client !== null);
check('a wrong key is refused', qt_find_client('app_smoke', 'nope') === null);
check('an unknown app id is refused', qt_find_client('app_other', 'key_smoke') === null);

qt_insert('api_clients', [
    'app_id' => 'app_db', 'api_key' => 'key_db', 'name' => 'from the database',
    'default_plan' => 'growth', 'is_active' => 1, 'created_at' => qt_sql_now(),
]);
check('database credential is accepted', qt_find_client('app_db', 'key_db') !== null);
check_same('and it carries its default plan', 'growth',
    (string) (qt_find_client('app_db', 'key_db')['default_plan'] ?? ''));

// --------------------------------------------------------------------- sessions

$token = qt_jwt_issue(['sub' => 7, 'dev' => 'deadbeef'], qt_config());
$claims = qt_jwt_verify($token, qt_config());
check('jwt round-trips', is_array($claims) && (int) $claims['sub'] === 7);
check('jwt rejects a tampered body',
    qt_jwt_verify(substr($token, 0, -2) . 'ab', qt_config()) === null);
check('jwt rejects another secret', qt_jwt_verify($token,
    ['jwt_secret' => 'different', 'jwt_ttl' => 60]) === null);
check('expired token is refused', qt_jwt_verify(
    qt_jwt_issue(['sub' => 1], ['jwt_secret' => 'x', 'jwt_ttl' => -10]),
    ['jwt_secret' => 'x', 'jwt_ttl' => -10]) === null);

// ----------------------------------------------------------- the licence machine

$shopId = qt_insert('shops', [
    'name' => 'Smoke Test Store', 'currency' => 'PKR', 'status' => 'ACTIVE',
    'plan_code' => 'starter', 'app_name' => '', 'created_at' => qt_sql_now(), 'updated_at' => qt_sql_now(),
]);
$userId = qt_insert('users', [
    'shop_id' => $shopId, 'username' => 'smoke', 'password_hash' => password_hash('secret123', PASSWORD_DEFAULT),
    'full_name' => 'Smoke Tester', 'phone' => '', 'role' => 'owner', 'fingerprint_enabled' => 0,
    'device_id' => 'dev-smoke-1', 'licence_confirmed_at' => null, 'is_active' => 1,
    'created_at' => qt_sql_now(), 'last_login_at' => qt_sql_now(),
]);
$shop = qt_load_shop($shopId);
$user = qt_load_user($userId);

check('password_verify works on the stored hash', password_verify('secret123', (string) $user['password_hash']));
check('a disabled user is not loaded', qt_load_user(0) === null);

// 1. registered but never approved
$state = qt_license_state($shop, $user, 'dev-smoke-1');
check_same('no licence row → PENDING', 'PENDING', $state['state']);
check_same('  …and the app is locked', false, $state['unlocked']);

// 2. approved, 30 days ahead
qt_extend_licence($shopId, 30, 'smoke');
$state = qt_license_state($shop, $user, 'dev-smoke-1');
check_same('30 days ahead → ACTIVE', 'ACTIVE', $state['state']);
check_same('  …unlocked', true, $state['unlocked']);
check('  …with about 30 days left', $state['days_left'] >= 29 && $state['days_left'] <= 30,
    (string) $state['days_left']);
check_same('  …and the confirmation step is still owed', false, $state['confirmed']);
check_same('  …plan name reached the app', 'Starter', $state['plan_name']);

// 3. confirmation recorded
qt_run('UPDATE users SET licence_confirmed_at = ? WHERE id = ?', [qt_sql_now(), $userId]);
$user = qt_load_user($userId);
check_same('after confirming, confirmed → true', true,
    qt_license_state($shop, $user, 'dev-smoke-1')['confirmed']);

// 4. expired yesterday
$pdo->prepare('UPDATE licences SET expires_at = ? WHERE shop_id = ?')->execute([time() - 86400, $shopId]);
$state = qt_license_state($shop, $user, 'dev-smoke-1');
check_same('yesterday → EXPIRED', 'EXPIRED', $state['state']);
check_same('  …locked again', false, $state['unlocked']);
check_same('  …days_left clamped to 0', 0, $state['days_left']);
check_same('  …label reads Expired', 'Expired', $state['duration_label']);

// 5. lifetime licence
$pdo->prepare('UPDATE licences SET expires_at = 0 WHERE shop_id = ?')->execute([$shopId]);
$state = qt_license_state($shop, $user, 'dev-smoke-1');
check_same('expires_at = 0 → ACTIVE forever', 'ACTIVE', $state['state']);
check_same('  …label reads Lifetime', 'Lifetime', $state['duration_label']);

// 6. revoked, then blocked by an administrator
$pdo->prepare('UPDATE licences SET status = ? WHERE shop_id = ?')->execute(['REVOKED', $shopId]);
check_same('revoked → REVOKED', 'REVOKED', qt_license_state($shop, $user, '')['state']);
$pdo->prepare('UPDATE licences SET status = ? WHERE shop_id = ?')->execute(['ACTIVE', $shopId]);
$pdo->prepare('UPDATE shops SET status = ? WHERE id = ?')->execute(['BLOCKED', $shopId]);
$state = qt_license_state(qt_load_shop($shopId), $user, '');
check_same('a blocked shop beats a live licence', 'BLOCKED', $state['state']);
check_same('  …with the code the app maps to BLOCKED', 'ACCOUNT_BLOCKED', $state['request_status']);
$pdo->prepare('UPDATE shops SET status = ? WHERE id = ?')->execute(['ACTIVE', $shopId]);
$pdo->prepare('UPDATE shops SET status = ? WHERE id = ?')->execute(['SUSPENDED', $shopId]);
check_same('suspended → SUSPENDED', 'SUSPENDED',
    qt_license_state(qt_load_shop($shopId), $user, '')['state']);
$pdo->prepare('UPDATE shops SET status = ? WHERE id = ?')->execute(['ACTIVE', $shopId]);

// 7. the payload must carry exactly the field names the app parses
$payload = qt_license_payload(qt_license_state($shop, $user, 'dev-smoke-1'));
foreach (['license_status', 'unlocked', 'confirmed', 'account_status', 'request_status',
    'duration_label', 'expires_at_ms', 'server_time_ms', 'days_left', 'device_seat'] as $key) {
    check('payload has ' . $key, array_key_exists($key, $payload));
}
check_same('device_seat is used/limit', [0, 1],
    [$payload['device_seat']['used'], $payload['device_seat']['limit']]);

// ------------------------------------------------------------------ device seats

$starter = qt_row("SELECT max_devices FROM plans WHERE code = 'starter'");
check_same('starter allows one till', 1, (int) $starter['max_devices']);
qt_insert('app_devices', [
    'shop_id' => $shopId, 'device_id' => 'dev-smoke-1', 'device_name' => 'Test till',
    'os_version' => '14', 'app_version' => '1.2.0', 'status' => 'ACTIVE',
    'first_seen_at' => qt_sql_now(), 'last_seen_at' => qt_sql_now(), 'seen_count' => 3,
]);
$seat = qt_device_seat(qt_load_shop($shopId));
check_same('one registered device → used 1', 1, $seat['used']);
check_same('  …of limit 1', 1, $seat['limit']);
$pdo->prepare('UPDATE app_devices SET status = ? WHERE device_id = ?')
    ->execute(['RELEASED', 'dev-smoke-1']);
check_same('released device frees the seat', 0, qt_device_seat(qt_load_shop($shopId))['used']);
$pdo->prepare('UPDATE app_devices SET status = ? WHERE device_id = ?')
    ->execute(['BLOCKED', 'dev-smoke-1']);
check_same('a blocked device still holds no seat', 0, qt_device_seat(qt_load_shop($shopId))['used']);

// ----------------------------------------------------------------- activations

$key = qt_new_license_key();
check('licence key format QT-XXXXX-XXXXX-XXXXX-XXXXX',
    (bool) preg_match('/^QT-[A-Z0-9]{5}(-[A-Z0-9]{5}){3}$/', $key), $key);
check('keys are unique', qt_new_license_key() !== qt_new_license_key());

$code = 'QT-TEST-' . strtoupper(bin2hex(random_bytes(3)));
qt_insert('activation_codes', ['code' => $code, 'days' => 7, 'note' => 'smoke', 'created_at' => qt_sql_now()]);
$found = qt_row('SELECT * FROM activation_codes WHERE code = ? AND used_at IS NULL', [$code]);
check('an unused code is redeemable', $found !== null);
qt_extend_licence($shopId, (int) $found['days'], 'code ' . $code);
check('redeeming it extends the licence',
    (int) qt_row('SELECT expires_at FROM licences WHERE shop_id = ?', [$shopId])['expires_at']
        > time() + 6 * 86400);

// -------------------------------------------------------------------- settings

qt_setting_set('support_whatsapp', '923001234567');
check_same('setting written and read back', '923001234567', qt_settings_all()['support_whatsapp']);
qt_setting_set('support_whatsapp', '923009998877');
check_same('setting updated in place', '923009998877', qt_settings_all()['support_whatsapp']);

// ------------------------------------------------------------------ time helpers

check_same('qt_millis reads a datetime', 1735689600000, qt_millis('2025-01-01 00:00:00'));
check_same('qt_millis of nothing is 0', 0, qt_millis(null));
check_same('qt_millis survives MySQL zero dates', 0, qt_millis('0000-00-00 00:00:00'));
check('server time is in milliseconds', strlen((string) qt_now_ms()) === 13);

// ----------------------------------------------------------- features + sync db

check_same('newline features parse', ['a', 'b'], qt_features("a\nb\n"));
check_same('json features parse', ['a', 'b'], qt_features('["a","b"]'));
check_same('empty features', [], qt_features(''));

qt_upsert_sync($shopId, 'products', 'p-1', ['uuid' => 'p-1', 'name' => 'Chai', 'price' => 40],
    date('Y-m-d H:i:s', time() - 60), null, 'dev-smoke-1');
qt_upsert_sync($shopId, 'products', 'p-1', ['uuid' => 'p-1', 'name' => 'Karak Chai', 'price' => 50],
    date('Y-m-d H:i:s'), null, 'dev-smoke-2');
$row = qt_row('SELECT payload FROM sync_records WHERE shop_id = ? AND entity = ? AND uuid = ?',
    [$shopId, 'products', 'p-1']);
$stored = json_decode((string) $row['payload'], true);
check_same('newer push wins', 'Karak Chai', $stored['name']);
qt_upsert_sync($shopId, 'products', 'p-1', ['uuid' => 'p-1', 'name' => 'Older write'],
    date('Y-m-d H:i:s', time() - 3600), null, 'dev-smoke-3');
$stored = json_decode((string) qt_row('SELECT payload FROM sync_records WHERE shop_id = ?',
    [$shopId])['payload'], true);
check_same('an older push is ignored', 'Karak Chai', $stored['name']);

// ------------------------------------------------- external clients (web / desktop)

$issued = qt_issue_api_token($shopId, 'website', ['read', 'write']);
$plain = (string) $issued['token'];
$record = (array) $issued['row'];
check('a token is handed out once, in full', strncmp($plain, 'qt_pat_', 7) === 0 && strlen($plain) >= 40);
check('the token is found again by its hash', qt_find_token($plain) !== null);
check_same('  …under the right shop', $shopId, (int) ($record['shop_id'] ?? -1));
check_same('  …labelled for the panel', 'website', (string) ($record['label'] ?? ''));
check_same('  …with both scopes', 'read,write', (string) ($record['scopes'] ?? ''));
check('the plaintext itself is never stored',
    strpos((string) json_encode($record), substr($plain, 7)) === false);
check('a guessed token matches nothing', qt_find_token('qt_pat_' . str_repeat('0', 40)) === null);

$_SERVER['HTTP_X_API_TOKEN'] = $plain;
$apiContext = qt_context('shop');
unset($_SERVER['HTTP_X_API_TOKEN']);
check_same('a token alone names the shop', $shopId, (int) $apiContext['shop']['id']);
check_same('  …and says the call came from a token', 'token', $apiContext['via']);
check_same('  …carrying that shop\'s licence state', 'ACTIVE', $apiContext['licence']['state']);
check('  …with the scopes to write', qt_scope_allows($apiContext, 'write'));
check_same('  …and an origin that is not a till', 'token:' . (int) $record['id'],
    (string) $apiContext['device_id']);

$readOnly = (string) qt_issue_api_token($shopId, 'public price list', ['read'])['token'];
$_SERVER['HTTP_X_API_TOKEN'] = $readOnly;
$narrow = qt_context('shop');
unset($_SERVER['HTTP_X_API_TOKEN']);
check_same('a read-only token may read', true, qt_scope_allows($narrow, 'read'));
check_same('a read-only token may not bill', false, qt_scope_allows($narrow, 'write'));
check_same('  …while the app itself always may', true,
    qt_scope_allows(['via' => 'session', 'scopes' => []], 'write'));
check_same('a token may reach the shop records', true, qt_token_may_reach('shop'));
check_same('  …and never the licence, devices or sign-in', false, qt_token_may_reach('user'));
check_same('  …nor the build-only endpoints', false, qt_token_may_reach('app'));

$burnt = qt_issue_api_token($shopId, 'temp', ['read'], 1);
check_same('a token with an expiry is accepted today', '', qt_token_rejection($burnt['row']));
check('revoking it in the panel takes effect on the next request',
    qt_revoke_api_token((int) $burnt['row']['id'], $shopId)
        && qt_token_rejection(qt_find_token($burnt['token'])) === 'TOKEN_REVOKED');
qt_run('UPDATE api_tokens SET expires_at = ? WHERE id = ?', [time() - 10, (int) $record['id']]);
check_same('an expired token is refused by code', 'TOKEN_EXPIRED', qt_token_rejection(qt_find_token($plain)));
qt_run('UPDATE api_tokens SET expires_at = 0 WHERE id = ?', [(int) $record['id']]);
check_same('  …and works again when the panel extends it', '', qt_token_rejection(qt_find_token($plain)));

check_same('millis pass through', 1735689600000, qt_unix_ms_of(1735689600000));
check_same('seconds are widened', 1735689600000, qt_unix_ms_of(1735689600));
check_same('a datetime is read', 1735689600000, qt_unix_ms_of('2025-01-01 00:00:00'));
check_same('ISO with Z is read', 1735689600000, qt_unix_ms_of('2025-01-01T00:00:00Z'));
check_same('junk is 0, never a wrong date', 0, qt_unix_ms_of('tomday'));

$invoice = qt_next_invoice_no($shopId, qt_now_ms());
check('a web bill gets its own invoice number', (bool) preg_match('/^INV-\d{8}-\d{3}$/', $invoice), $invoice);
check_same('  …twice in a row is the same number until one is stored', $invoice,
    qt_next_invoice_no($shopId, qt_now_ms()));

// A bill written from a website lands in the one record store the till pushes into.
$bill = qt_record_store($shopId, 'orders', [
    'invoice_no' => $invoice, 'order_type' => 'TAKE_AWAY', 'payment_method' => 'CASH',
    'status' => 'paid', 'subtotal' => 500, 'discount' => 0, 'tax' => 0, 'total' => 500,
    'created_at' => qt_now_ms(), 'updated_at' => qt_now_ms(),
    'items' => [['name' => 'Karak Chai', 'price' => 50, 'qty' => 10, 'line_total' => 500,
                 'product_uuid' => 'p-1']],
    'source' => 'api',
], 'token:' . (int) $record['id']);
check('the stored bill has a generated uuid', preg_match('/^[0-9a-f]{32}$/', (string) $bill['uuid']) === 1);
$storedRow = (array) qt_row('SELECT * FROM sync_records WHERE shop_id = ? AND entity = ? AND uuid = ?',
    [$shopId, 'orders', $bill['uuid']]);
check('  …in sync_records, so the next pull carries it', $storedRow !== []);
check_same('  …indexed by its own business time', (int) $bill['_occurred_ms'], (int) $storedRow['occurred_ms']);
check_same('  …and the next invoice number counts up', 'INV-' . date('Ymd') . '-002',
    qt_next_invoice_no($shopId, qt_now_ms()));
$replaced = qt_record_store($shopId, 'orders', array_merge($bill, ['total' => 480, 'discount' => 20]),
    'token:' . (int) $record['id']);
check_same('  …so a retry replaces, never duplicates', 1,
    (int) qt_row('SELECT COUNT(*) AS n FROM sync_records WHERE shop_id = ? AND entity = ?',
        [$shopId, 'orders'])['n']);

$shaped = qt_bill_shape($replaced);
check_same('a shaped bill reports the stored money', 480.0, $shaped['total']);
check_same('  …as paid', 'paid', $shaped['status']);
check_same('  …from the api source', 'api', $shaped['source']);
check_same('  …with one line', 1, count($shaped['items']));
check_same('  …and a date to group by', date('Y-m-d'), $shaped['date']);

$listed = qt_records_of($shopId, 'orders');
check_same('the bill list finds it', 1, $listed['total']);
check('  …without leaking the device internals', !isset($listed['rows'][0]['__device']));

$day = qt_summary_for(['shop' => $shop], (int) (strtotime(date('Y-m-d') . ' 00:00:00') * 1000),
    (int) ((strtotime(date('Y-m-d') . ' 23:59:59') * 1000) + 999));
check_same('the day report counts the bill', 1, $day['bill_count']);
check_same('  …and its money', 480.0, $day['revenue']);
check_same('  …paid, not unpaid', 480.0, $day['paid']);
check_same('  …average bill', 480.0, $day['average_bill']);
check_same('  …top seller', 'Karak Chai', (string) $day['top_products'][0]['name']);
check_same('  …payment mix', 480.0, $day['by_payment_method']['CASH']);
check_same('  …order-type mix', 480.0, $day['by_order_type']['TAKE_AWAY']);
check_same('hour buckets are always 24', 24, count(qt_hour_buckets($day['bills'])));
check_same('the currency travels', 'PKR', $day['currency']);

$range = qt_date_range(['from' => '2025-01-01', 'to' => '2025-01-03']);
check_same('a range starts at midnight', '2025-01-01 00:00:00', date('Y-m-d H:i:s', (int) ($range[0] / 1000)));
check_same('  …and covers the whole last day', '2025-01-03 23:59:59',
    date('Y-m-d H:i:s', (int) ($range[1] / 1000)));

qt_record_store($shopId, 'products', ['uuid' => 'p-2', 'name' => 'Naan', 'price' => 30,
    'stock' => 10, 'is_active' => 1, 'updated_at' => qt_now_ms()], 'token:' . (int) $record['id']);
$after = qt_reduce_stock($shopId, [['name' => 'Naan', 'price' => 30, 'qty' => 2,
    'product_uuid' => 'p-2']], 'token:1');
check_same('selling decrements catalogue stock', 8, (int) ($after[0]['stock'] ?? -1));
$naan = json_decode((string) qt_row('SELECT payload FROM sync_records WHERE shop_id = ? AND entity = ? AND uuid = ?',
    [$shopId, 'products', 'p-2'])['payload'], true);
check_same('  …so the till pulls the new number', 8, (int) $naan['stock']);
check_same('a product with untracked stock is left alone', 0,
    count(qt_reduce_stock($shopId, [['name' => 'Karak Chai', 'price' => 50, 'qty' => 1,
        'product_uuid' => 'p-1']], 'token:1')));
$naam = [['name' => 'Naan', 'price' => 30, 'qty' => 2, 'product_uuid' => 'p-2']];
check_same('the same bill sent twice moves no stock', 0,
    count(qt_reduce_stock($shopId, $naam, 'token:1', $naam)));
$one = [['name' => 'Naan', 'price' => 30, 'qty' => 1, 'product_uuid' => 'p-2']];
$giveBack = qt_reduce_stock($shopId, $one, 'token:1', $naam);
check_same('  …a smaller edit returns the difference', 9, (int) ($giveBack[0]['stock'] ?? -1));
check_same('  …reported as +1', 1, (int) ($giveBack[0]['delta'] ?? 0));

// --------------------------------------------------------------------- envelope

check('route table covers the endpoints the app calls', count(qt_routes()) >= 19,
    count(qt_routes()) . ' routes');
foreach (qt_routes() as $label => $entry) {
    check('handler for ' . $label . ' exists', function_exists($entry[0]));
}

@unlink($database);
@unlink($configFile);

echo str_repeat('─', 60) . PHP_EOL;
echo $checks . ' checks, ' . $failures . ' failure(s)' . PHP_EOL;
exit($failures === 0 ? 0 : 1);
