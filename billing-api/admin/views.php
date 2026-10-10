<?php
/**
 * The whole HTML layer of the billing panel: no rule is decided here, only
 * rendered. Every form posts to index.php with an `a=` action verb.
 */

declare(strict_types=1);

// -------------------------------------------------------------- tiny utilities

function qt_admin_h($value): string
{
    return htmlspecialchars((string) $value, ENT_QUOTES, 'UTF-8');
}

function qt_admin_redirect(string $query = ''): void
{
    header('Location: index.php' . ($query === '' ? '' : '?' . $query));
    exit;
}

function qt_admin_post(string $key, string $default = ''): string
{
    $value = $_POST[$key] ?? $default;
    return is_string($value) ? trim($value) : $default;
}

function qt_admin_flash(string $message): void
{
    $_SESSION['qt_flash'] = $message;
}

function qt_admin_take_flash(): string
{
    $message = (string) ($_SESSION['qt_flash'] ?? '');
    $error = (string) ($_SESSION['qt_error'] ?? '');
    unset($_SESSION['qt_flash'], $_SESSION['qt_error']);
    if ($error !== '') {
        return '<div class="flash bad">' . qt_admin_h($error) . '</div>';
    }
    return $message === '' ? '' : '<div class="flash">' . qt_admin_h($message) . '</div>';
}

function qt_admin_csrf_token(): string
{
    if (empty($_SESSION['qt_csrf'])) {
        $_SESSION['qt_csrf'] = bin2hex(random_bytes(16));
    }
    return $_SESSION['qt_csrf'];
}

function qt_admin_csrf_check(): void
{
    if (!hash_equals(qt_admin_csrf_token(), (string) ($_POST['csrf'] ?? ''))) {
        http_response_code(400);
        exit('The form had expired. Reload the page and try again.');
    }
}

/** Headers every response carries, sent before any body byte. */
function qt_admin_assets(): void
{
    if (headers_sent()) {
        return;
    }
    header('X-Frame-Options: DENY');
    header('X-Content-Type-Options: nosniff');
    header('Referrer-Policy: same-origin');
    header('Content-Type: text/html; charset=utf-8');
}

function qt_admin_css(): string
{
    return <<<'CSS'
:root{--bg:#0f1216;--panel:#171c22;--line:#242c35;--ink:#e8edf2;--dim:#8b98a6;--ok:#1f9d55;--bad:#d64545;--warn:#c98a12;--accent:#2f81f7}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif}
a{color:var(--accent);text-decoration:none}
header.top{display:flex;align-items:center;gap:14px;padding:14px 18px;border-bottom:1px solid var(--line);background:var(--panel);flex-wrap:wrap}
header.top .brand{font-weight:700;letter-spacing:.2px}
header.top nav{display:flex;gap:4px;flex-wrap:wrap}
header.top nav a{padding:6px 10px;border-radius:8px;color:var(--dim)}
header.top nav a.on,header.top nav a:hover{background:#20262e;color:var(--ink)}
main{padding:18px;max-width:1180px;margin:0 auto}
h1{font-size:20px;margin:2px 0 4px}
h2{font-size:17px;margin:26px 0 10px}
h3{font-size:13px;margin:20px 0 8px;color:var(--dim);text-transform:uppercase;letter-spacing:.6px}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px;margin:6px 0 8px}
.card{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:14px}
.card-value{font-size:24px;font-weight:700}
.card-label{color:var(--dim);font-size:13px}
.card-hint{color:#6c7a88;font-size:12px;margin-top:6px}
table{width:100%;border-collapse:collapse;background:var(--panel);border:1px solid var(--line);border-radius:12px;overflow:hidden}
th,td{padding:10px 12px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}
th{font-size:12px;text-transform:uppercase;letter-spacing:.5px;color:var(--dim);background:#141920}
tr:last-child td{border-bottom:0}
.dim{color:var(--dim);font-size:12px;font-weight:500}
.muted{color:var(--dim)}
.right{text-align:right;white-space:nowrap}
.pill{display:inline-block;padding:3px 9px;border-radius:999px;font-size:12px;font-weight:600;white-space:nowrap}
.pill.ok{background:rgba(31,157,85,.16);color:#5cd08c}
.pill.bad{background:rgba(214,69,69,.16);color:#ff8f8f}
.pill.warn{background:rgba(201,138,18,.18);color:#f0c062}
.pill.mute{background:#222932;color:var(--dim)}
.flash{background:rgba(47,129,247,.12);border:1px solid rgba(47,129,247,.35);color:#cfe3ff;padding:10px 12px;border-radius:10px;margin:10px 0}
.flash.bad{background:rgba(214,69,69,.12);border-color:rgba(214,69,69,.4);color:#ffc9c9}
code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:13px}
.mono{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:13px}
pre.codes{background:#0b0e12;border:1px solid var(--line);border-radius:10px;padding:14px;overflow:auto;white-space:pre-wrap}
.row-form{display:flex;gap:8px;align-items:flex-end;flex-wrap:wrap;margin:8px 0}
.field{display:flex;flex-direction:column;gap:4px;min-width:180px}
.field span{font-size:12px;color:var(--dim)}
.field small{font-size:11px;color:#6c7a88}
input,textarea,select{background:#0d1116;border:1px solid var(--line);color:var(--ink);border-radius:9px;padding:9px 10px;font:inherit;min-width:120px}
textarea{width:100%}
input:focus,textarea:focus,select:focus{outline:2px solid rgba(47,129,247,.4)}
.button{background:var(--accent);border:0;color:#fff;border-radius:9px;padding:9px 13px;font:inherit;font-weight:600;cursor:pointer;display:inline-block}
.button.ghost{background:#20262e;color:var(--ink)}
.button.danger{background:var(--bad)}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:14px}
.panel{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:16px;margin-bottom:14px}
.panel form{display:flex;flex-direction:column;gap:10px}
.split{display:grid;grid-template-columns:1fr 1fr;gap:14px}
@media(max-width:820px){.split{grid-template-columns:1fr}}
.login{max-width:380px;margin:14vh auto;background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:22px}
.login h2{margin-top:0}
.login form{display:flex;flex-direction:column;gap:12px}
.notice{color:var(--dim);font-size:13px}
CSS;
}

/** The licence pill: colour carries the meaning, the words stay explicit. */
function badge(string $state, ?int $daysLeft = null): string
{
    $state = trim($state) === '' ? 'NONE' : trim($state);
    // Lower-case keys so licence states and notice levels share one pill.
    $tones = [
        'active' => 'ok', 'success' => 'ok', 'free' => 'ok',
        'pending' => 'warn', 'suspended' => 'warn', 'warn' => 'warn', 'warning' => 'warn',
        'expired' => 'bad', 'revoked' => 'bad', 'blocked' => 'bad', 'disabled' => 'bad',
        'error' => 'bad',
        'released' => 'mute', 'none' => 'mute', 'unknown' => 'mute', 'info' => 'mute',
    ];
    $tone = $tones[strtolower($state)] ?? 'mute';
    $label = qt_admin_h(strtoupper($state));
    if (strtoupper($state) === 'ACTIVE' && $daysLeft !== null) {
        $label .= ' <span class="dim">' . ($daysLeft >= 0 ? $daysLeft . ' d' : 'overdue') . '</span>';
    }
    return '<span class="pill ' . $tone . '">' . $label . '</span>';
}

function stat_card(string $label, $value, string $hint = ''): string
{
    return '<div class="card"><div class="card-value">' . qt_admin_h((string) $value) . '</div>'
        . '<div class="card-label">' . qt_admin_h($label) . '</div>'
        . ($hint === '' ? '' : '<div class="card-hint">' . qt_admin_h($hint) . '</div>') . '</div>';
}

function qt_admin_when(?string $databaseTime): string
{
    $millis = qt_millis($databaseTime);
    return $millis === 0 ? '—' : date('Y-m-d H:i', (int) ($millis / 1000));
}

function qt_admin_age(?string $databaseTime): string
{
    $millis = qt_millis($databaseTime);
    if ($millis === 0) {
        return 'never';
    }
    $minutes = (int) ((time() - (int) ($millis / 1000)) / 60);
    if ($minutes < 1) {
        return 'just now';
    }
    if ($minutes < 60) {
        return $minutes . ' min ago';
    }
    if ($minutes < 60 * 48) {
        return intdiv($minutes, 60) . ' h ago';
    }
    return intdiv($minutes, 1440) . ' d ago';
}

function form_open(string $action, array $extra = []): string
{
    $html = '<form method="post" action="index.php" class="row-form">';
    $html .= '<input type="hidden" name="csrf" value="' . qt_admin_csrf_token() . '">';
    $html .= '<input type="hidden" name="a" value="' . qt_admin_h($action) . '">';
    foreach ($extra as $key => $value) {
        $html .= '<input type="hidden" name="' . qt_admin_h($key) . '" value="' . qt_admin_h($value) . '">';
    }
    return $html;
}

function field(string $name, string $label, string $value = '', string $type = 'text',
               string $hint = '', string $attrs = ''): string
{
    return '<label class="field"><span>' . qt_admin_h($label) . '</span>'
        . '<input type="' . $type . '" name="' . qt_admin_h($name) . '" value="' . qt_admin_h($value) . '" '
        . $attrs . '>'
        . ($hint === '' ? '' : '<small>' . qt_admin_h($hint) . '</small>') . '</label>';
}

function textarea(string $name, string $label, string $value = '', string $hint = ''): string
{
    return '<label class="field"><span>' . qt_admin_h($label) . '</span>'
        . '<textarea name="' . qt_admin_h($name) . '" rows="6">' . qt_admin_h($value) . '</textarea>'
        . ($hint === '' ? '' : '<small>' . qt_admin_h($hint) . '</small>') . '</label>';
}

function submit(string $label, string $tone = ''): string
{
    return '<button type="submit" class="button ' . $tone . '">' . qt_admin_h($label) . '</button>';
}

function select_box(string $name, string $label, array $options, string $current): string
{
    $html = '<label class="field"><span>' . qt_admin_h($label) . '</span><select name="'
        . qt_admin_h($name) . '">';
    foreach ($options as $value => $text) {
        $html .= '<option value="' . qt_admin_h((string) $value) . '"'
            . ((string) $value === $current ? ' selected' : '') . '>' . qt_admin_h($text) . '</option>';
    }
    return $html . '</select></label>';
}

function layout(string $title, string $page, string $body): string
{
    $navigation = [
        'dashboard' => ['Shops', ''],
        'devices' => ['Devices', 'p=devices'],
        'codes' => ['Licence codes', 'p=codes'],
        'plans' => ['Plans', 'p=plans'],
        'notifications' => ['Notices', 'p=notifications'],
        'settings' => ['App settings', 'p=settings'],
        'clients' => ['Clients', 'p=clients'],
        'access' => ['API access', 'p=access'],
        'log' => ['Activity', 'p=log'],
    ];
    $links = '';
    foreach ($navigation as $key => [$label, $query]) {
        $links .= '<a href="index.php' . ($query === '' ? '' : '?' . $query) . '"'
            . ($page === $key ? ' class="on"' : '') . '>' . qt_admin_h($label) . '</a>';
    }
    $who = (string) ($_SESSION['qt_admin']['username'] ?? '');
    return '<!doctype html><html lang="en"><head><meta charset="utf-8">'
        . '<meta name="viewport" content="width=device-width, initial-scale=1">'
        . '<title>' . qt_admin_h($title) . ' · QuickTap billing</title>'
        . '<style>' . qt_admin_css() . '</style></head><body>'
        . '<header class="top"><div class="brand">QuickTap · billing</div><nav>' . $links . '</nav>'
        . '<div style="margin-left:auto" class="dim">' . qt_admin_h($who)
        . ' · <a href="index.php?a=logout">Sign out</a></div></header>'
        . '<main><h1>' . qt_admin_h($title) . '</h1>' . $body . '</main></body></html>';
}

// ----------------------------------------------------------------------- login

function render_login(): string
{
    $body = '<div class="login">' . qt_admin_take_flash()
        . '<h2 style="margin-top:0">Sign in</h2>'
        . '<form method="post" action="index.php">'
        . '<input type="hidden" name="csrf" value="' . qt_admin_csrf_token() . '">'
        . '<input type="hidden" name="a" value="login">'
        . field('username', 'Username')
        . field('password', 'Password', '', 'password')
        . submit('Sign in')
        . '</form><p class="notice">The login was created by <code>install.php</code>. If the '
        . 'installer has not been run yet, open <code>../install.php</code> first.</p></div>';
    return '<!doctype html><html lang="en"><head><meta charset="utf-8">'
        . '<meta name="viewport" content="width=device-width, initial-scale=1">'
        . '<title>Sign in · QuickTap billing</title><style>' . qt_admin_css() . '</style>'
        . '</head><body>' . $body . '</body></html>';
}

// ------------------------------------------------------------------- dashboard

function render_dashboard(): string
{
    $shops = qt_rows('SELECT s.*,
            (SELECT COUNT(*) FROM users u WHERE u.shop_id = s.id AND u.is_active = 1) AS user_count,
            (SELECT COUNT(*) FROM app_devices d WHERE d.shop_id = s.id AND d.status = ?) AS device_count,
            (SELECT l.expires_at FROM licences l WHERE l.shop_id = s.id ORDER BY l.id DESC LIMIT 1) AS expires_at,
            (SELECT l.status FROM licences l WHERE l.shop_id = s.id ORDER BY l.id DESC LIMIT 1) AS licence_status
        FROM shops s ORDER BY s.id DESC', ['ACTIVE']);

    $active = 0;
    $expiring = 0;
    $overdue = 0;
    $pending = 0;
    foreach ($shops as $shop) {
        $expires = (int) ($shop['expires_at'] ?? 0);
        $licenceStatus = strtoupper((string) ($shop['licence_status'] ?? ''));
        if ($licenceStatus === 'ACTIVE' && strtoupper((string) $shop['status']) === 'ACTIVE') {
            $active++;
        }
        if ($licenceStatus === 'PENDING') {
            $pending++;
        }
        if ($expires > 0 && $expires < time() + 7 * 86400) {
            if ($expires < time()) {
                $overdue++;
            } else {
                $expiring++;
            }
        }
    }

    $body = '<div class="cards">'
        . stat_card('Billing accounts', count($shops), 'one shop = one subscription')
        . stat_card('Billing now', $active, 'licence active')
        . stat_card('Waiting for approval', $pending, 'registered, no licence yet')
        . stat_card('Ending in 7 days', $expiring, 'renew before the till locks')
        . stat_card('Expired', $overdue, 'these see the expired page')
        . '</div>';

    if ($pending > 0) {
        $body .= '<div class="flash">A shop is waiting for a licence. Open it and either approve it '
            . 'for N days or send it an activation code.</div>';
    }

    $body .= '<h2>Shops</h2><table><thead><tr><th>Shop</th><th>Plan</th><th>Licence</th>'
        . '<th>Expires</th><th>Tills</th><th>Users</th><th></th></tr></thead><tbody>';
    if (!$shops) {
        $body .= '<tr><td colspan="7" class="muted">Nothing here yet — a shop appears the moment '
            . 'someone registers from the app.</td></tr>';
    }
    foreach ($shops as $shop) {
        $expires = (int) ($shop['expires_at'] ?? 0);
        $daysLeft = $expires > 0 ? (int) floor(($expires - time()) / 86400) : null;
        $state = ($daysLeft !== null && $daysLeft < 0) ? 'EXPIRED' : (string) ($shop['licence_status'] ?? '');
        $body .= '<tr><td><a href="?p=shop&amp;id=' . (int) $shop['id'] . '"><strong>'
            . qt_admin_h($shop['name']) . '</strong></a>'
            . '<div class="dim">shop #' . (int) $shop['id']
            . ($shop['app_name'] !== '' ? ' · shows as “' . qt_admin_h($shop['app_name']) . '”' : '')
            . (strtoupper((string) $shop['status']) !== 'ACTIVE'
                ? ' · ' . qt_admin_h($shop['status']) : '')
            . '</div></td>'
            . '<td>' . qt_admin_h($shop['plan_code'] !== '' ? $shop['plan_code'] : '—') . '</td>'
            . '<td>' . badge($state, $daysLeft) . '</td>'
            . '<td>' . ($expires > 0 ? qt_admin_h(date('Y-m-d', $expires)) : 'lifetime') . '</td>'
            . '<td>' . (int) $shop['device_count'] . '</td>'
            . '<td>' . (int) $shop['user_count'] . '</td>'
            . '<td class="right"><a class="button ghost" href="?p=shop&amp;id=' . (int) $shop['id']
            . '">Manage</a></td></tr>';
    }
    $body .= '</tbody></table>';

    $body .= '<h2>Latest activity</h2><table><thead><tr><th>When</th><th>Action</th>'
        . '<th>Shop</th><th>Detail</th></tr></thead><tbody>';
    $log = qt_rows('SELECT a.*, s.name AS shop_name FROM audit_log a
        LEFT JOIN shops s ON s.id = a.shop_id ORDER BY a.id DESC LIMIT 12');
    if (!$log) {
        $body .= '<tr><td colspan="4" class="muted">No activity recorded yet.</td></tr>';
    }
    foreach ($log as $row) {
        $body .= '<tr><td class="dim">' . qt_admin_when($row['created_at']) . '</td><td>'
            . qt_admin_h($row['action']) . '</td><td>' . qt_admin_h((string) ($row['shop_name'] ?? '—'))
            . '</td><td class="dim">' . qt_admin_h($row['detail']) . '</td></tr>';
    }
    $body .= '</tbody></table>';
    return layout('Shops', 'dashboard', $body);
}

// ---------------------------------------------------------------- shop detail

function render_shop(int $shopId): string
{
    $shop = qt_load_shop($shopId);
    if ($shop === null) {
        return layout('Shop', 'dashboard',
            '<p class="muted">No such shop. <a href="index.php">Back to the list</a></p>');
    }
    $users = qt_rows('SELECT * FROM users WHERE shop_id = ? ORDER BY id', [$shopId]);
    $devices = qt_rows('SELECT * FROM app_devices WHERE shop_id = ? ORDER BY status, last_seen_at DESC',
        [$shopId]);
    $licence = qt_row('SELECT * FROM licences WHERE shop_id = ? ORDER BY id DESC LIMIT 1', [$shopId]);
    $state = qt_license_state($shop, $users[0] ?? null, '');
    $plan = qt_plan_for($shop);
    $expires = (int) ($licence['expires_at'] ?? 0);

    $body = '<div class="cards">'
        . stat_card('Licence', $state['state'], $state['duration_label'])
        . stat_card('Seats', $state['seat']['used'] . ' / ' . $state['seat']['limit'],
            'billing devices this plan allows')
        . stat_card('Plan', $plan === null ? '—' : (string) $plan['name'])
        . stat_card('Expires', $expires > 0 ? date('Y-m-d', $expires) : 'never',
            'decided here, never on the phone')
        . '</div>';

    $body .= '<div class="split">';

    // --- billing actions ----------------------------------------------------
    $body .= '<div class="panel"><h3>Renew</h3>'
        . form_open('licence', ['shop_id' => $shopId, 'verb' => 'extend'])
        . field('days', 'Days to add', '30', 'number', 'added to whatever is left', 'min="1" max="3650"')
        . submit('Extend licence')
        . '</form>'
        . '<div class="row-form">'
        . form_open('licence', ['shop_id' => $shopId, 'verb' => 'lifetime'])
        . submit('Set lifetime', 'ghost') . '</form>'
        . form_open('licence', ['shop_id' => $shopId, 'verb' => 'approve', 'days' => '30'])
        . submit('Approve 30 days', 'ghost') . '</form>'
        . '</div><h3>Stop it</h3><div class="row-form">'
        . form_open('licence', ['shop_id' => $shopId, 'verb' => 'revoke', 'days' => '30'])
        . submit('Revoke licence', 'danger') . '</form>'
        . form_open('licence', ['shop_id' => $shopId, 'verb' => 'suspend', 'days' => '30'])
        . submit('Suspend', 'ghost') . '</form>'
        . form_open('licence', ['shop_id' => $shopId, 'verb' =>
            strtoupper((string) $shop['status']) === 'BLOCKED' ? 'unblock' : 'block', 'days' => '30'])
        . submit(strtoupper((string) $shop['status']) === 'BLOCKED' ? 'Unblock' : 'Block account',
            strtoupper((string) $shop['status']) === 'BLOCKED' ? 'ghost' : 'danger') . '</form>'
        . '</div><p class="notice">Blocking is what the app cannot work around: every endpoint '
        . 'answers the blocked state and the till shows the forced page.</p></div>';

    // --- plan + seat --------------------------------------------------------
    $planOptions = [];
    foreach (qt_rows('SELECT code, name FROM plans ORDER BY sort_order, id') as $option) {
        $planOptions[(string) $option['code']] = (string) $option['name'];
    }
    $body .= '<div class="panel"><h3>Plan</h3>'
        . form_open('shop_plan', ['shop_id' => $shopId])
        . select_box('plan_code', 'Plan for this shop', $planOptions, (string) $shop['plan_code'])
        . submit('Save plan', 'ghost')
        . '</form><h3>Device seat</h3><table><tbody>';
    if (!$devices) {
        $body .= '<tr><td class="muted">No till has talked to the server yet.</td></tr>';
    }
    foreach ($devices as $device) {
        $body .= '<tr><td><span class="mono">'
            . qt_admin_h(substr((string) $device['device_id'], 0, 12)) . '…</span>'
            . '<div class="dim">' . qt_admin_h($device['device_name'] !== '' ? $device['device_name']
                : 'unnamed till') . ' · Android ' . qt_admin_h($device['os_version'])
            . ' · app ' . qt_admin_h($device['app_version']) . '<br>last seen '
            . qt_admin_age($device['last_seen_at']) . ' · ' . (int) $device['seen_count'] . ' checks'
            . '</div></td><td class="right">' . badge((string) $device['status']) . '<br>';
        if ((string) $device['status'] === 'ACTIVE') {
            $body .= form_open('device', ['device_row' => $device['id'], 'verb' => 'release'])
                . submit('Free seat', 'ghost') . '</form>'
                . form_open('device', ['device_row' => $device['id'], 'verb' => 'block'])
                . submit('Block', 'danger') . '</form>';
        } elseif ((string) $device['status'] === 'BLOCKED') {
            $body .= form_open('device', ['device_row' => $device['id'], 'verb' => 'unblock'])
                . submit('Allow again', 'ghost') . '</form>';
        } else {
            $body .= '<span class="dim">released</span>';
        }
        $body .= '</td></tr>';
    }
    $body .= '</tbody></table></div></div>';

    // --- users --------------------------------------------------------------
    $body .= '<h2>Users</h2><table><thead><tr><th>Username</th><th>Name</th><th>Role</th>'
        . '<th>Bound device</th><th>Last sign-in</th><th>Confirm step</th><th></th></tr></thead><tbody>';
    foreach ($users as $user) {
        $body .= '<tr><td class="mono">' . qt_admin_h($user['username'])
            . ((int) $user['is_active'] === 1 ? '' : ' ' . badge('BLOCKED')) . '</td>'
            . '<td>' . qt_admin_h($user['full_name']) . '<div class="dim">'
            . qt_admin_h($user['phone']) . '</div></td>'
            . '<td>' . qt_admin_h($user['role'])
            . ((int) $user['fingerprint_enabled'] === 1 ? ' <span class="dim">· finger</span>' : '')
            . '</td>'
            . '<td class="mono dim">' . qt_admin_h(substr((string) $user['device_id'], 0, 12)) . '</td>'
            . '<td class="dim">' . qt_admin_when($user['last_login_at']) . '</td>'
            . '<td>' . (empty($user['licence_confirmed_at'])
                ? badge('PENDING') : badge('ACTIVE')) . '</td>'
            . '<td class="right">'
            . form_open('user', ['user_id' => $user['id'], 'verb' => 'password'])
            . '<input type="password" name="password" placeholder="new password" '
            . 'autocomplete="new-password">' . submit('Set password', 'ghost') . '</form>'
            . form_open('user', ['user_id' => $user['id'],
                'verb' => (int) $user['is_active'] === 1 ? 'disable' : 'enable'])
            . submit((int) $user['is_active'] === 1 ? 'Disable' : 'Enable', 'ghost') . '</form>'
            . '</td></tr>';
    }
    $body .= '</tbody></table>';

    $body .= '<h2>Licence row</h2><table><tbody>'
        . '<tr><th style="width:180px">Key</th><td class="mono">'
        . qt_admin_h((string) ($licence['license_key'] ?? '—'))
        . '<div class="dim">the shop can type this into the app to prove itself</div></td></tr>'
        . '<tr><th>Status</th><td>' . badge((string) ($licence['status'] ?? 'NONE')) . '</td></tr>'
        . '<tr><th>Started</th><td class="dim">' . qt_admin_when($licence['started_at'] ?? null)
        . '</td></tr><tr><th>Activated</th><td class="dim">'
        . qt_admin_when($licence['activated_at'] ?? null) . '</td></tr>'
        . '<tr><th>Note</th><td class="dim">' . qt_admin_h((string) ($licence['note'] ?? ''))
        . '</td></tr></tbody></table>';

    $log = qt_rows('SELECT * FROM audit_log WHERE shop_id = ? ORDER BY id DESC LIMIT 25', [$shopId]);
    if ($log) {
        $body .= '<h2>Activity</h2><table><thead><tr><th>When</th><th>Who</th><th>Action</th>'
            . '<th>Detail</th></tr></thead><tbody>';
        foreach ($log as $row) {
            $body .= '<tr><td class="dim">' . qt_admin_when($row['created_at'])
                . '</td><td class="dim mono">' . qt_admin_h($row['actor']) . '</td><td>'
                . qt_admin_h($row['action']) . '</td><td class="dim">'
                . qt_admin_h($row['detail']) . '</td></tr>';
        }
        $body .= '</tbody></table>';
    }
    return layout((string) $shop['name'], 'dashboard', $body);
}

// ------------------------------------------------------------ devices (global)

function render_devices(): string
{
    $rows = qt_rows('SELECT d.*, s.name AS shop_name FROM app_devices d
        LEFT JOIN shops s ON s.id = d.shop_id ORDER BY d.last_seen_at DESC LIMIT 300');
    $body = '<h2>Every till the server has seen</h2>'
        . '<p class="notice">The fingerprint is what a licence is bound to. Freeing a seat here is '
        . 'what lets a replaced phone sign in without waiting for support.</p>'
        . '<table><thead><tr><th>Shop</th><th>Device</th><th>App</th><th>Last seen</th>'
        . '<th>Status</th><th></th></tr></thead><tbody>';
    if (!$rows) {
        $body .= '<tr><td colspan="6" class="muted">No device has registered yet.</td></tr>';
    }
    foreach ($rows as $device) {
        $body .= '<tr><td><a href="?p=shop&amp;id=' . (int) $device['shop_id'] . '">'
            . qt_admin_h((string) ($device['shop_name'] ?? ('#' . $device['shop_id']))) . '</a></td>'
            . '<td><span class="mono">' . qt_admin_h(substr((string) $device['device_id'], 0, 12))
            . '…</span><div class="dim">'
            . qt_admin_h($device['device_name'] !== '' ? $device['device_name'] : 'unnamed')
            . ' · Android ' . qt_admin_h($device['os_version']) . '</div></td>'
            . '<td class="dim">' . qt_admin_h($device['app_version']) . '</td>'
            . '<td class="dim">' . qt_admin_age($device['last_seen_at']) . '</td>'
            . '<td>' . badge((string) $device['status']) . '</td><td class="right">';
        if ((string) $device['status'] === 'ACTIVE') {
            $body .= form_open('device', ['device_row' => $device['id'], 'verb' => 'release'])
                . submit('Free seat', 'ghost') . '</form>';
        } elseif ((string) $device['status'] === 'BLOCKED') {
            $body .= form_open('device', ['device_row' => $device['id'], 'verb' => 'unblock'])
                . submit('Allow again', 'ghost') . '</form>';
        }
        $body .= '</td></tr>';
    }
    return layout('Devices', 'devices', $body . '</tbody></table>');
}

// ------------------------------------------------------------------ plan store

function render_plans(): string
{
    $plans = qt_rows('SELECT * FROM plans ORDER BY sort_order, id');
    $body = '<h2>Plans</h2><p class="notice">These rows are what the in-app store renders, and '
        . 'what decides how many tills a shop may run. A price change is therefore not a code change.</p>';
    foreach ($plans as $plan) {
        $used = qt_row('SELECT COUNT(*) AS total FROM shops WHERE plan_code = ?', [(string) $plan['code']]);
        $features = implode("\n", qt_features($plan['features'] ?? ''));
        $body .= '<div class="panel">' . form_open('plan', ['plan_id' => $plan['id']])
            . '<div class="grid">'
            . field('name', 'Name', (string) $plan['name'])
            . field('tagline', 'Tagline', (string) $plan['tagline'])
            . field('tag', 'Badge', (string) $plan['tag'], 'text', 'e.g. MOST POPULAR')
            . field('price', 'Price per month', (string) $plan['price'], 'number',
                'shown in the app', 'step="1" min="0"')
            . field('price_yearly', 'Price per year', (string) $plan['price_yearly'], 'number',
                '', 'step="1" min="0"')
            . field('max_devices', 'Billing devices', (string) $plan['max_devices'], 'number',
                'the seat the API enforces', 'min="1" max="99"')
            . '</div>'
            . textarea('features', 'Features (one per line)', $features)
            . select_box('is_active', 'Active', ['1' => 'Yes', '0' => 'No'],
                (int) $plan['is_active'] === 1 ? '1' : '0')
            . submit('Save ' . $plan['code'])
            . '<div class="dim">' . qt_admin_h($plan['code']) . ' · ' . (int) ($used['total'] ?? 0)
            . ' shop(s) on it</div></form></div>';
    }
    if (!$plans) {
        $body .= '<p class="muted">Run <code>install.php</code> to seed the three default plans.</p>';
    }
    return layout('Plans', 'plans', $body);
}

// -------------------------------------------------------------------- settings

function render_settings(): string
{
    $settings = qt_settings_all();
    $value = static function (string $key) use ($settings): string {
        return (string) ($settings[$key] ?? '');
    };
    $body = '<h2>Branding and support</h2><div class="panel">' . form_open('settings')
        . '<div class="grid">'
        . field('setting_app_name', 'App name', $value('app_name'), 'text',
            'the login screen and the receipts')
        . field('setting_logo_url', 'Logo URL', $value('logo_url'), 'text', 'optional https:// image')
        . field('setting_support_whatsapp', 'Support WhatsApp', $value('support_whatsapp'), 'text',
            'digits with country code, e.g. 923001234567')
        . field('setting_receipt_template', 'Receipt template', $value('receipt_template'), 'text',
            'classic or compact')
        . field('setting_receipt_footer', 'Receipt footer', $value('receipt_footer'), 'text',
            'one line printed under every bill')
        . field('setting_tax_percent', 'Tax percent', $value('tax_percent'), 'text',
            '0 turns it off')
        . field('setting_auto_lock_minutes', 'Auto-lock after (minutes)', $value('auto_lock_minutes'),
            'number', '0 keeps the till unlocked')
        . '</div><h3>Licence checks</h3><div class="grid">'
        . field('setting_license_sync_minutes', 'Re-check licence every (minutes)',
            $value('license_sync_minutes'), 'number', 'the app default is 60')
        . field('setting_offline_grace_hours', 'Offline grace (hours)', $value('offline_grace_hours'),
            'number', 'how long a till may bill without the server')
        . field('setting_api_base_url', 'API base url', $value('api_base_url'), 'text',
            'empty keeps the address the APK was built with')
        . field('setting_min_app_version', 'Minimum app version', $value('min_app_version'), 'text',
            'e.g. 1.2.0 — empty allows every build')
        . field('setting_force_update', 'Force update (0 or 1)', $value('force_update'), 'text',
            '1 sends old builds to the update screen')
        . '</div><h3>Firebase (push and the start-up bootstrap)</h3><div class="grid">'
        . field('setting_fb_app_id', 'Firebase app id', $value('fb_app_id'), 'text',
            'mobilesdk_app_id from google-services.json')
        . field('setting_fb_api_key', 'Firebase API key', $value('fb_api_key'), 'text',
            'android_api_key')
        . field('setting_fb_project_id', 'Project id', $value('fb_project_id'))
        . field('setting_fb_sender_id', 'Sender id', $value('fb_sender_id'), 'text',
            'the legacy GCM sender id')
        . '</div><p class="notice">The app reads all of this through <code>GET v1/app/config</code> '
        . 'and <code>GET v1/app/bootstrap</code>. An empty field falls back to what the APK was built '
        . 'with, so nothing breaks because a box was left blank.</p>'
        . submit('Save settings') . '</form></div>';
    return layout('App settings', 'settings', $body);
}

// -------------------------------------------------------------- activation codes

function render_codes(): string
{
    $fresh = isset($_SESSION['qt_codes']) && is_array($_SESSION['qt_codes'])
        ? $_SESSION['qt_codes'] : [];
    unset($_SESSION['qt_codes']);

    $body = '<h2>Generate codes</h2><div class="panel">' . form_open('codes')
        . '<div class="grid">'
        . field('days', 'Days', '30', 'number', 'what one code buys', 'min="1" max="3650"')
        . field('count', 'How many', '1', 'number', 'up to 20 at a time', 'min="1" max="20"')
        . field('note', 'Note', '', 'text', 'e.g. invoice number or customer name')
        . '</div>' . submit('Generate') . '</form></div>';

    if ($fresh) {
        $body .= '<h3>Send these now</h3><pre class="codes">' . qt_admin_h(implode("\n", $fresh))
            . '</pre><p class="notice">They are listed here once and stay in the database until '
            . 'redeemed. The shop types one into the app under Licence → Enter key.</p>';
    }

    $rows = qt_rows('SELECT c.*, s.name AS shop_name FROM activation_codes c
        LEFT JOIN shops s ON s.id = c.used_by_shop ORDER BY c.id DESC LIMIT 100');
    $body .= '<h2>All codes</h2><table><thead><tr><th>Code</th><th>Days</th><th>State</th>'
        . '<th>Redeemed by</th><th>Created</th><th>Note</th></tr></thead><tbody>';
    if (!$rows) {
        $body .= '<tr><td colspan="6" class="muted">No codes yet.</td></tr>';
    }
    foreach ($rows as $row) {
        $body .= '<tr><td class="mono">' . qt_admin_h($row['code']) . '</td><td>' . (int) $row['days']
            . '</td><td>' . (empty($row['used_at']) ? badge('ACTIVE') : badge('RELEASED')) . '</td><td>'
            . qt_admin_h((string) ($row['shop_name'] ?? '—')) . '</td><td class="dim">'
            . qt_admin_when($row['created_at']) . '</td><td class="dim">'
            . qt_admin_h((string) $row['note']) . '</td></tr>';
    }
    return layout('Licence codes', 'codes', $body . '</tbody></table>');
}

// --------------------------------------------------------------- notifications

function render_notifications(): string
{
    $editing = qt_row('SELECT * FROM notifications WHERE id = ?', [(int) ($_GET['edit'] ?? 0)]);
    $body = '<h2>' . ($editing === null ? 'New notice' : 'Edit notice') . '</h2><div class="panel">'
        . form_open('notification', $editing === null ? [] : ['notification_id' => $editing['id']])
        . '<div class="grid">'
        . field('title', 'Title', (string) ($editing['title'] ?? ''), 'text', 'the bell list line')
        . select_box('level', 'Level',
            ['info' => 'Info', 'warn' => 'Warning', 'success' => 'Success'],
            (string) ($editing['level'] ?? 'info'))
        . select_box('is_active', 'Visible', ['1' => 'Yes', '0' => 'No'],
            (int) ($editing['is_active'] ?? 1) === 1 ? '1' : '0')
        . '</div>'
        . textarea('body', 'Message', (string) ($editing['body'] ?? ''))
        . submit($editing === null ? 'Create notice' : 'Save notice')
        . '</form></div>';

    $rows = qt_rows('SELECT * FROM notifications ORDER BY id DESC LIMIT 50');
    $body .= '<h2>All notices</h2><table><thead><tr><th>Title</th><th>Body</th><th>Level</th>'
        . '<th>State</th><th></th></tr></thead><tbody>';
    if (!$rows) {
        $body .= '<tr><td colspan="5" class="muted">Nothing has been announced yet.</td></tr>';
    }
    foreach ($rows as $row) {
        $body .= '<tr><td>' . qt_admin_h($row['title']) . '</td><td class="dim">'
            . qt_admin_h(mb_substr((string) $row['body'], 0, 140)) . '</td><td>'
            . badge((string) $row['level']) . '</td><td class="dim">'
            . ((int) $row['is_active'] === 1 ? 'visible' : 'hidden')
            . '<br>' . qt_admin_when($row['created_at']) . '</td><td class="right">'
            . '<a class="button ghost" href="?p=notifications&amp;edit=' . (int) $row['id']
            . '">Edit</a> '
            . form_open('notification', ['notification_id' => $row['id'], 'verb' => 'delete'])
            . submit('Delete', 'danger') . '</form></td></tr>';
    }
    return layout('Notices', 'notifications', $body . '</tbody></table>');
}

// --------------------------------------------------------------------- clients

function render_clients(): string
{
    $credential = $_SESSION['qt_client'] ?? null;
    unset($_SESSION['qt_client']);
    $body = '<h2>App credentials</h2><p class="notice">Every request from the APK carries '
        . '<code>X-App-Id</code> and <code>X-Api-Key</code>. They keep random scripts off the '
        . 'licence endpoints; they are not a secret that could survive a decompiled app, which is '
        . 'why no rule here depends on them alone.</p>';
    if (is_array($credential)) {
        $body .= '<div class="flash">New pair — shown in full exactly once: <code>'
            . qt_admin_h($credential['app_id']) . '</code> and <code>'
            . qt_admin_h($credential['api_key']) . '</code>. Put both in '
            . '<code>app/build.gradle</code> and ship that build.</div>';
    }
    $rows = qt_rows('SELECT * FROM api_clients ORDER BY id DESC');
    $body .= '<table><thead><tr><th>App id</th><th>Api key</th><th>Name</th>'
        . '<th>Plan on signup</th><th>State</th><th></th></tr></thead><tbody>';
    if (!$rows) {
        $body .= '<tr><td colspan="6" class="muted">No client rows yet — the pair in config.php '
            . 'is being used instead.</td></tr>';
    }
    foreach ($rows as $row) {
        $body .= '<tr><td class="mono">' . qt_admin_h($row['app_id']) . '</td>'
            . '<td class="mono dim">' . qt_admin_h(substr((string) $row['api_key'], 0, 8)) . '…</td>'
            . '<td>' . qt_admin_h($row['name']) . '</td>'
            . '<td class="dim">' . qt_admin_h($row['default_plan']) . '</td><td>'
            . ((int) $row['is_active'] === 1 ? badge('ACTIVE') : badge('BLOCKED')) . '</td>'
            . '<td class="right">' . ((int) $row['is_active'] === 1
                ? form_open('client', ['client_id' => $row['id'], 'verb' => 'disable'])
                    . submit('Disable', 'danger') . '</form>' : '') . '</td></tr>';
    }
    $body .= '</tbody></table><h2>Add a build</h2><div class="panel">' . form_open('client')
        . '<div class="grid">'
        . field('app_id', 'App id', '', 'text', 'left blank = generated')
        . field('api_key', 'Api key', '', 'text', 'left blank = generated')
        . field('name', 'Label', 'release build')
        . field('default_plan', 'Plan given on self-registration', 'starter')
        . '</div>' . submit('Create credential') . '</form></div>';
    return layout('Clients', 'clients', $body);
}

// ------------------------------------------------------------------ API access

/**
 * Tokens for everything that is not the Android app: a website, a kiosk page, a desktop
 * counter. Shown once, hashed at rest, revocable per program, and scoped — so a public
 * price list can be handed a token that physically cannot bill.
 */
function render_access(): string
{
    $fresh = (string) ($_SESSION['qt_token'] ?? '');
    unset($_SESSION['qt_token']);
    $shops = qt_rows('SELECT id, name FROM shops ORDER BY name');
    $rows = qt_rows('SELECT t.*, s.name AS shop_name FROM api_tokens t
        LEFT JOIN shops s ON s.id = t.shop_id
        ORDER BY t.revoked_at IS NOT NULL, t.id DESC LIMIT 200');

    $body = '<h2>API access for a website or a desktop program</h2>'
        . '<p class="dim">A token names one shop and needs one header: <code>X-Api-Token</code>. '
        . 'Anything that can send that header can use the shop — <code>curl</code>, a browser page, '
        . 'Python, C#, Excel. The guide with copy-paste examples is <code>billing-api/API.md</code>.</p>';

    if ($fresh !== '') {
        $body .= '<div class="flash"><strong>Copy this now — only its hash is kept from here on:</strong><br>'
            . '<code style="user-select:all;word-break:break-all">' . qt_admin_h($fresh) . '</code>'
            . '<div class="row-form" style="margin-top:8px"><input value="' . qt_admin_h($fresh)
            . '" readonly size="56" onclick="this.select()"></div></div>';
    }

    $body .= '<div class="panel" style="margin:14px 0"><h3>New token</h3>' . form_open('token')
        . '<div class="grid">'
        . '<label class="field"><span>Shop</span><select name="shop_id">';
    if (!$shops) {
        $body .= '<option value="0">no shops yet</option>';
    }
    foreach ($shops as $shop) {
        $body .= '<option value="' . (int) $shop['id'] . '">' . qt_admin_h((string) $shop['name']) . '</option>';
    }
    $body .= '</select></label>'
        . field('label', 'Label this program', 'website', 'text', 'one per program: website, kiosk-1, accounting-sync')
        . select_box('days', 'Expires', ['0' => 'never', '30' => '30 days', '90' => '90 days', '365' => '1 year'], '0')
        . '</div><div class="row-form" style="margin:8px 0">'
        . '<label class="dim"><input type="checkbox" name="scopes[]" value="read" checked> read</label>'
        . '<label class="dim"><input type="checkbox" name="scopes[]" value="write"> write (may create bills)</label>'
        . '</div>'
        . '<p class="dim">Read-only answers <code>403 SCOPE_DENIED</code> to every write — give that one to '
        . 'anything running in a customer\'s browser. Only <code>read,write</code> can bill, and even then '
        . 'the shop\'s licence must be ACTIVE, exactly like a phone.</p>'
        . submit('Create token') . '</form></div>';

    $body .= '<h2>Tokens</h2><table><thead><tr><th>Shop</th><th>Label</th><th>Token</th><th>Scopes</th>'
        . '<th>Created / last used</th><th></th></tr></thead><tbody>';
    if (!$rows) {
        $body .= '<tr><td colspan="6" class="dim">Nothing issued yet — no external program can reach a shop.</td></tr>';
    }
    foreach ($rows as $row) {
        $expires = (int) ($row['expires_at'] ?? 0);
        $expired = $expires > 0 && $expires < time();
        $state = !empty($row['revoked_at']) ? 'REVOKED' : ($expired ? 'EXPIRED' : 'ACTIVE');
        $body .= '<tr><td><a href="?p=shop&amp;id=' . (int) $row['shop_id'] . '">'
            . qt_admin_h((string) ($row['shop_name'] ?? ('#' . $row['shop_id']))) . '</td>'
            . '<td>' . qt_admin_h((string) $row['label']) . '</td>'
            . '<td class="mono dim">' . qt_admin_h((string) $row['token_prefix']) . '… '
            . ($state === 'ACTIVE' ? '' : badge($state)) . '</td>'
            . '<td class="dim">' . qt_admin_h(str_replace(',', ', ', (string) $row['scopes'])) . '</td>'
            . '<td class="dim">' . qt_admin_when($row['created_at']) . '<br>'
            . (!empty($row['last_used_at']) ? 'used ' . qt_admin_age($row['last_used_at']) : 'never used')
            . ($expires > 0 ? '<br>expires ' . qt_admin_h(date('Y-m-d', $expires)) : '') . '</td>'
            . '<td class="right">' . (empty($row['revoked_at'])
                ? form_open('token', ['verb' => 'revoke', 'token_id' => $row['id'], 'shop_id' => $row['shop_id']])
                    . submit('Revoke', 'danger') . '</form>'
                : '<span class="dim">—</span>') . '</td></tr>';
    }
    $body .= '</tbody></table>';

    $sample = $rows ? qt_admin_h((string) $rows[0]['token_prefix']) . '…' : 'qt_pat_…';
    $body .= '<h2>What a client sends</h2><pre class="codes"># who am I, and may this shop bill today?
'
        . 'curl -s ' . qt_admin_h(qt_public_base_url()) . 'v1/shop \\\n'
        . '  -H "X-Api-Token: ' . $sample . '"</pre>'
        . '<p class="dim">Two rules gate every call: the token decides <em>which shop</em>, and that shop\'s '
        . 'licence decides <em>whether it may bill right now</em>. Import <code>billing-api/openapi.json</code> '
        . 'into Postman or a client generator instead of reading the endpoint table by hand — '
        . '<code>tools/build_openapi.py</code> regenerates it from the route table, so it cannot go stale.</p>';

    return layout('API access', 'access', $body);
}

// ------------------------------------------------------------------ audit trail

function render_log(): string
{
    $rows = qt_rows('SELECT a.*, s.name AS shop_name FROM audit_log a
        LEFT JOIN shops s ON s.id = a.shop_id ORDER BY a.id DESC LIMIT 200');
    $body = '<h2>Activity</h2><p class="notice">Sign-ins, activations, blocks and seat changes — '
        . 'everything that explains why a till is billing or is not.</p>'
        . '<table><thead><tr><th>When</th><th>Who</th><th>Action</th><th>Shop</th>'
        . '<th>Detail</th></tr></thead><tbody>';
    if (!$rows) {
        $body .= '<tr><td colspan="5" class="muted">Nothing recorded yet.</td></tr>';
    }
    foreach ($rows as $row) {
        $body .= '<tr><td class="dim">' . qt_admin_when($row['created_at'])
            . '</td><td class="dim mono">' . qt_admin_h($row['actor']) . '</td><td>'
            . qt_admin_h($row['action']) . '</td><td>'
            . qt_admin_h((string) ($row['shop_name'] ?? '—')) . '</td><td class="dim">'
            . qt_admin_h($row['detail']) . '</td></tr>';
    }
    return layout('Activity', 'log', $body . '</tbody></table>');
}

// ------------------------------------------------------------- page dispatcher

function render_page(string $page, int $id): string
{
    switch ($page) {
        case 'shop': return render_shop($id);
        case 'devices': return render_devices();
        case 'plans': return render_plans();
        case 'settings': return render_settings();
        case 'notifications': return render_notifications();
        case 'codes': return render_codes();
        case 'clients': return render_clients();
        case 'access': return render_access();
        case 'log': return render_log();
        default:
            return layout('Not found', 'dashboard',
                '<p class="muted">No such page. <a href="index.php">Back to shops</a></p>');
    }
}
