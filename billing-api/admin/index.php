<?php
/**
 * The QuickTap billing panel: shops, licences, activation codes, device seats,
 * plan prices, the support number, and the Firebase/branding configuration the
 * app reads on start-up.
 *
 * Two files on purpose (this one plus views.php) — a shared host should not need a
 * framework, a build step or a template engine. The panel reuses the same lib/ the
 * API uses, so a rule can never be spelled two different ways.
 */

declare(strict_types=1);

require __DIR__ . '/../lib/support.php';
require __DIR__ . '/views.php';

session_name('QTADMIN');
session_start();
qt_admin_assets();

$action = (string) ($_POST['a'] ?? $_GET['a'] ?? '');
$page = (string) ($_GET['p'] ?? 'dashboard');
$page = preg_replace('/[^a-z_]/', '', $page) ?: 'dashboard';
$id = (int) ($_GET['id'] ?? 0);

// ------------------------------------------------------------------ login gate

if ($action === 'logout') {
    $_SESSION = [];
    session_destroy();
    qt_admin_redirect();
}

if ($action === 'login') {
    qt_admin_csrf_check();
    $username = qt_admin_post('username');
    $password = (string) ($_POST['password'] ?? '');
    $admin = $username === '' ? null : qt_row('SELECT * FROM admin_users WHERE username = ?', [$username]);
    if ($admin !== null && password_verify($password, (string) $admin['password_hash'])) {
        session_regenerate_id(true);
        $_SESSION['qt_admin'] = ['id' => (int) $admin['id'], 'username' => (string) $admin['username']];
        qt_run('UPDATE admin_users SET last_login_at = ? WHERE id = ?', [qt_sql_now(), (int) $admin['id']]);
        qt_admin_redirect();
    }
    usleep(400000); // a wrong password costs as much time as a right one
    $_SESSION['qt_error'] = 'That login did not work.';
    qt_admin_redirect('p=login');
}

$loggedIn = !empty($_SESSION['qt_admin']['id']);
if (!$loggedIn) {
    echo render_login();
    exit;
}

// --------------------------------------------------------------------- actions

try {
    switch ($action) {
        case '':
            break;

        case 'settings':
            qt_admin_csrf_check();
            $fields = ['app_name', 'logo_url', 'receipt_template', 'support_whatsapp', 'api_base_url',
                'fb_app_id', 'fb_api_key', 'fb_project_id', 'fb_sender_id', 'min_app_version',
                'license_sync_minutes', 'offline_grace_hours', 'auto_lock_minutes', 'tax_percent',
                'receipt_footer', 'force_update'];
            foreach ($fields as $field) {
                qt_setting_set($field, qt_admin_post('setting_' . $field));
            }
            qt_admin_flash('Settings saved. A till picks them up on its next licence check.');
            qt_admin_redirect('p=settings');

        case 'licence':
            qt_admin_csrf_check();
            $shopId = (int) qt_admin_post('shop_id');
            $shop = qt_load_shop($shopId);
            if ($shop === null) {
                qt_admin_flash('Unknown shop.');
                qt_admin_redirect('p=shops');
            }
            $licence = qt_row('SELECT * FROM licences WHERE shop_id = ? ORDER BY id DESC LIMIT 1', [$shopId]);
            $verb = qt_admin_post('verb');
            $days = max(1, (int) qt_admin_post('days', '30'));
            if ($verb === 'extend') {
                qt_extend_licence($shopId, $days, 'extended from the panel by ' . $days . ' days');
                qt_admin_flash(sprintf('Added %d days to %s.', $days, $shop['name']));
            } elseif ($verb === 'lifetime') {
                qt_extend_licence($shopId, 0, 'lifetime licence from the panel');
                qt_admin_flash($shop['name'] . ' now has a lifetime licence.');
            } elseif ($verb === 'approve') {
                qt_extend_licence($shopId, $days, 'self-registration approved');
                qt_admin_flash($shop['name'] . ' approved for ' . $days . ' days.');
            } elseif ($verb === 'revoke') {
                if ($licence === null) {
                    qt_admin_flash('That shop has no licence row to revoke.');
                } else {
                    qt_update('licences', ['status' => 'REVOKED'], 'id = :id', [':id' => (int) $licence['id']]);
                    qt_admin_flash('Licence revoked — the till locks on its next check.');
                }
            } elseif ($verb === 'block') {
                qt_run('UPDATE shops SET status = ?, updated_at = ? WHERE id = ?',
                    ['BLOCKED', qt_sql_now(), $shopId]);
                qt_admin_flash($shop['name'] . ' blocked: the app shows the blocked page.');
            } elseif ($verb === 'unblock') {
                qt_run('UPDATE shops SET status = ?, updated_at = ? WHERE id = ?',
                    ['ACTIVE', qt_sql_now(), $shopId]);
                qt_admin_flash($shop['name'] . ' unblocked.');
            } elseif ($verb === 'suspend') {
                qt_run('UPDATE shops SET status = ?, updated_at = ? WHERE id = ?',
                    ['SUSPENDED', qt_sql_now(), $shopId]);
                qt_admin_flash($shop['name'] . ' suspended.');
            }
            qt_log_audit('panel_' . $verb, $shopId, (string) $shop['name']);
            qt_admin_redirect('p=shop&id=' . $shopId);

        case 'codes':
            qt_admin_csrf_check();
            $days = max(1, (int) qt_admin_post('days', '30'));
            $count = min(20, max(1, (int) qt_admin_post('count', '1')));
            $note = qt_admin_post('note');
            $codes = [];
            for ($i = 0; $i < $count; $i++) {
                $code = qt_new_license_key();
                qt_insert('activation_codes', [
                    'code' => $code, 'days' => $days, 'note' => $note, 'created_at' => qt_sql_now(),
                ]);
                $codes[] = $code;
            }
            $_SESSION['qt_codes'] = $codes;
            qt_log_audit('panel_codes', 0, $days . ' days x ' . $count);
            qt_admin_flash('Generated ' . count($codes) . ' code(s) for ' . $days . ' days each.');
            qt_admin_redirect('p=codes');

        case 'device':
            qt_admin_csrf_check();
            $rowId = (int) qt_admin_post('device_row');
            $device = qt_row('SELECT * FROM app_devices WHERE id = ?', [$rowId]);
            if ($device === null) {
                qt_admin_flash('Unknown device.');
                qt_admin_redirect('p=devices');
            }
            $verb = qt_admin_post('verb');
            if ($verb === 'release') {
                qt_run('UPDATE app_devices SET status = ?, released_at = ?, last_seen_at = ? WHERE id = ?',
                    ['RELEASED', qt_sql_now(), qt_sql_now(), $rowId]);
                qt_run('UPDATE refresh_tokens SET revoked_at = ? WHERE device_id = ? AND revoked_at IS NULL',
                    [qt_sql_now(), (string) $device['device_id']]);
                qt_run('UPDATE users SET device_id = NULL WHERE shop_id = ? AND device_id = ?',
                    [(int) $device['shop_id'], (string) $device['device_id']]);
                qt_admin_flash('Seat freed — another till can be linked in its place.');
            } elseif ($verb === 'block') {
                qt_run('UPDATE app_devices SET status = ? WHERE id = ?', ['BLOCKED', $rowId]);
                qt_admin_flash('Device blocked. It shows the blocked page on its next check.');
            } elseif ($verb === 'unblock') {
                qt_run('UPDATE app_devices SET status = ? WHERE id = ?', ['ACTIVE', $rowId]);
                qt_admin_flash('Device allowed again.');
            }
            qt_log_audit('panel_device_' . $verb, (int) $device['shop_id'], (string) $device['device_id']);
            qt_admin_redirect('p=shop&id=' . (int) $device['shop_id']);

        case 'plan':
            qt_admin_csrf_check();
            $planId = (int) qt_admin_post('plan_id');
            $plan = qt_row('SELECT * FROM plans WHERE id = ?', [$planId]);
            if ($plan === null) {
                qt_admin_flash('Unknown plan.');
                qt_admin_redirect('p=plans');
            }
            qt_update('plans', [
                'name' => qt_admin_post('name', (string) $plan['name']),
                'tagline' => qt_admin_post('tagline', (string) $plan['tagline']),
                'tag' => qt_admin_post('tag', (string) $plan['tag']),
                'price' => (float) qt_admin_post('price', (string) $plan['price']),
                'price_yearly' => (float) qt_admin_post('price_yearly', (string) $plan['price_yearly']),
                'max_devices' => max(1, (int) qt_admin_post('max_devices', (string) $plan['max_devices'])),
                'features' => qt_admin_post('features'),
                'is_active' => qt_admin_post('is_active') === '1' ? 1 : 0,
            ], 'id = :id', [':id' => $planId]);
            qt_admin_flash('Plan saved. The in-app store reads it the next time it is opened.');
            qt_admin_redirect('p=plans');

        case 'shop_plan':
            qt_admin_csrf_check();
            $shopId = (int) qt_admin_post('shop_id');
            $code = qt_admin_post('plan_code');
            $plan = $code === '' ? null : qt_row('SELECT * FROM plans WHERE code = ?', [$code]);
            qt_run('UPDATE shops SET plan_code = ?, updated_at = ? WHERE id = ?',
                [(string) ($plan['code'] ?? ''), qt_sql_now(), $shopId]);
            qt_admin_flash($plan === null
                ? 'Plan cleared — the shop falls back to the entry plan.'
                : 'Shop moved to ' . $plan['name'] . '.');
            qt_admin_redirect('p=shop&id=' . $shopId);

        case 'user':
            qt_admin_csrf_check();
            $userId = (int) qt_admin_post('user_id');
            $user = qt_row('SELECT * FROM users WHERE id = ?', [$userId]);
            if ($user === null) {
                qt_admin_flash('Unknown user.');
                qt_admin_redirect();
            }
            $verb = qt_admin_post('verb');
            if ($verb === 'password') {
                $password = (string) ($_POST['password'] ?? '');
                if (strlen($password) < 8) {
                    qt_admin_flash('Pick a password of at least 8 characters.');
                } else {
                    qt_update('users', ['password_hash' => password_hash($password, PASSWORD_DEFAULT)],
                        'id = :id', [':id' => $userId]);
                    qt_run('UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL',
                        [qt_sql_now(), $userId]);
                    qt_admin_flash('Password changed; every session of that till was ended.');
                }
            } elseif ($verb === 'disable') {
                qt_run('UPDATE users SET is_active = 0 WHERE id = ?', [$userId]);
                qt_admin_flash('User disabled — the app refuses the sign-in.');
            } elseif ($verb === 'enable') {
                qt_run('UPDATE users SET is_active = 1 WHERE id = ?', [$userId]);
                qt_admin_flash('User enabled.');
            }
            qt_log_audit('panel_user_' . $verb, (int) $user['shop_id'], (string) $user['username']);
            qt_admin_redirect('p=shop&id=' . (int) $user['shop_id']);

        case 'notification':
            qt_admin_csrf_check();
            if (qt_admin_post('verb') === 'delete') {
                qt_run('DELETE FROM notifications WHERE id = ?', [(int) qt_admin_post('notification_id')]);
                qt_admin_flash('Notice deleted.');
            } else {
                $noticeId = (int) qt_admin_post('notification_id');
                $values = [
                    'title' => qt_admin_post('title'),
                    'body' => (string) ($_POST['body'] ?? ''),
                    'level' => qt_admin_post('level', 'info'),
                    'is_active' => qt_admin_post('is_active', '1') === '1' ? 1 : 0,
                ];
                if ($noticeId > 0 && qt_row('SELECT id FROM notifications WHERE id = ?', [$noticeId]) !== null) {
                    qt_update('notifications', $values, 'id = :id', [':id' => $noticeId]);
                } else {
                    $values['created_at'] = qt_sql_now();
                    $values['starts_at'] = qt_sql_now();
                    qt_insert('notifications', $values);
                }
                qt_admin_flash('Notice saved. It appears in the bell on the next fetch.');
            }
            qt_admin_redirect('p=notifications');

        case 'token':
            qt_admin_csrf_check();
            $verb = qt_admin_post('verb');
            if ($verb === 'revoke') {
                $gone = qt_revoke_api_token((int) qt_admin_post('token_id'), (int) qt_admin_post('shop_id'));
                qt_admin_flash($gone
                    ? 'Token revoked — that program stops being accepted on its very next request.'
                    : 'That token is already revoked or belongs to another shop.');
                qt_admin_redirect('p=access');
            }
            $shopId = (int) qt_admin_post('shop_id');
            if (qt_load_shop($shopId) === null) {
                qt_admin_flash('Pick the shop the token may read (or write).');
                qt_admin_redirect('p=access');
            }
            $scopes = array_values(array_intersect(['read', 'write'], (array) ($_POST['scopes'] ?? [])));
            if (!$scopes) {
                $scopes = ['read'];
            }
            $days = (int) qt_admin_post('days', '0');
            $issued = qt_issue_api_token($shopId, qt_admin_post('label'), $scopes, max(0, $days));
            $_SESSION['qt_token'] = $issued['token'];
            qt_log_audit('panel_token', $shopId, qt_admin_post('label') . ' (' . implode('+', $scopes) . ')');
            qt_admin_flash('Token created. Copy it now — only its hash is kept from here on.');
            qt_admin_redirect('p=access');

        case 'client':
            qt_admin_csrf_check();
            if (qt_admin_post('verb') === 'disable') {
                qt_run('UPDATE api_clients SET is_active = 0 WHERE id = ?',
                    [(int) qt_admin_post('client_id')]);
                qt_admin_flash('That build can no longer reach the API.');
            } else {
                $appId = qt_admin_post('app_id') ?: 'app_' . bin2hex(random_bytes(6));
                $apiKey = qt_admin_post('api_key') ?: bin2hex(random_bytes(24));
                if (qt_row('SELECT id FROM api_clients WHERE app_id = ?', [$appId]) === null) {
                    qt_insert('api_clients', [
                        'app_id' => $appId,
                        'api_key' => $apiKey,
                        'name' => qt_admin_post('name', 'manual'),
                        'default_plan' => qt_admin_post('default_plan', 'starter'),
                        'is_active' => 1,
                        'created_at' => qt_sql_now(),
                    ]);
                    $_SESSION['qt_client'] = ['app_id' => $appId, 'api_key' => $apiKey];
                    qt_admin_flash('Credential created — copy it into app/build.gradle now.');
                } else {
                    qt_admin_flash('That app id already exists.');
                }
            }
            qt_admin_redirect('p=clients');

        default:
            qt_admin_flash('Unknown action: ' . qt_admin_h($action));
            qt_admin_redirect($page === 'dashboard' ? '' : 'p=' . $page);
    }
} catch (Throwable $failure) {
    qt_admin_flash('Something went wrong: ' . $failure->getMessage());
    qt_admin_redirect($page === 'dashboard' ? '' : 'p=' . $page);
}

// ---------------------------------------------------------------------- render

if ($page !== 'dashboard') {
    echo render_page($page, $id);
    exit;
}

echo render_dashboard();
