<?php
/**
 * API tokens from the command line — for a server you reach over SSH, or a test box.
 *
 *   php token.php --list
 *   php token.php --shop="Smoke Test Store" --label=website --scopes=read,write
 *   php token.php --shop=3 --label=kiosk-1 --days=30 --print-token
 *   php token.php --revoke=7
 *
 * The admin panel (→ API access) does the same with clicks; this exists because the
 * snippet in a support ticket should not need a browser, and because a deploy script
 * wants the plaintext exactly once. HTTP requests are refused: this file only runs
 * from a shell on the server itself.
 */

declare(strict_types=1);

if (PHP_SAPI !== 'cli') {
    http_response_code(404);
    header('Content-Type: text/plain; charset=utf-8');
    echo "Refused. Tokens are issued from a shell on the server, or in the admin panel.\n";
    exit;
}

require __DIR__ . '/lib/support.php';

$args = [];
foreach (array_slice($_SERVER['argv'], 1) as $argument) {
    if (preg_match('/^--([\w-]+)(?:=(.*))?$/', $argument, $match)) {
        $args[$match[1]] = $match[2] ?? '1';
    }
}

$out = static function (string $line) use ($args): void {
    // --print-token and --json must be pipeable, so they silence the prose.
    if (!isset($args['print-token']) && !isset($args['json'])) {
        fwrite(STDOUT, $line . PHP_EOL);
    }
};

try {
    if (!qt_db_ready()) {
        fwrite(STDERR, "The database schema is not installed. Run install.php first.\n");
        exit(2);
    }

    // ------------------------------------------------------------------ --list
    if (isset($args['list'])) {
        $rows = isset($args['shop'])
            ? qt_rows('SELECT t.*, s.name AS shop_name FROM api_tokens t
                    LEFT JOIN shops s ON s.id = t.shop_id
                    WHERE t.shop_id = ? OR s.name = ?
                    ORDER BY t.id DESC LIMIT 200',
                [(int) $args['shop'], (string) $args['shop']])
            : qt_rows('SELECT t.*, s.name AS shop_name FROM api_tokens t
                    LEFT JOIN shops s ON s.id = t.shop_id ORDER BY t.id DESC LIMIT 200');
        if (!$rows) {
            $out('No tokens yet.');
            exit(0);
        }
        foreach ($rows as $row) {
            $state = !empty($row['revoked_at']) ? 'REVOKED'
                : ((int) ($row['expires_at'] ?? 0) > 0 && (int) $row['expires_at'] < time() ? 'EXPIRED' : 'ACTIVE');
            $out(sprintf('#%-4s  %-9s  %-10s  %-22s  %s  %s%s',
                $row['id'], $row['shop_name'] ?? ('shop ' . $row['shop_id']), $state,
                substr((string) $row['token_prefix'], 0, 12) . '…', $row['label'],
                $row['scopes'],
                empty($row['last_used_at']) ? '' : '  last used ' . $row['last_used_at']));
        }
        exit(0);
    }

    // ----------------------------------------------------------------- --revoke
    if (isset($args['revoke'])) {
        $id = (int) $args['revoke'];
        $row = qt_row('SELECT * FROM api_tokens WHERE id = ?', [$id]);
        if ($row === null) {
            fwrite(STDERR, "No token #{$id}.\n");
            exit(2);
        }
        $changed = qt_revoke_api_token($id, (int) $row['shop_id']);
        $out($changed
            ? "Revoked #{$id} (“{$row['label']}”). It is refused from its next request."
            : "Token #{$id} was already revoked.");
        exit(0);
    }

    // ------------------------------------------------------------------- issue
    if (!isset($args['shop'])) {
        fwrite(STDOUT, implode(PHP_EOL, [
            'QuickTap API tokens',
            '',
            '  php token.php --list [--shop=ID|name]      what exists and who used it',
            '  php token.php --revoke=ID                  stop accepting a token',
            '  php token.php --shop=ID|name --label=website [--scopes=read,write]',
            '                     [--days=30] [--print-token] [--json]',
            '',
            '  --print-token prints only the secret, so a deploy script can capture it.',
            '  A read-only token can never bill; --scopes=read,write can.',
            '',
        ]) . PHP_EOL);
        exit(0);
    }

    $shop = qt_row('SELECT * FROM shops WHERE id = ? OR name = ? LIMIT 1',
        [(int) $args['shop'], (string) $args['shop']]);
    if ($shop === null) {
        fwrite(STDERR, "No shop with id or name “{$args['shop']}”. Shops:\n");
        foreach (qt_rows('SELECT id, name FROM shops ORDER BY name') as $row) {
            fwrite(STDERR, "  #{$row['id']}  {$row['name']}\n");
        }
        exit(2);
    }

    $scopes = array_values(array_filter(array_map('trim', explode(',', strtolower((string) ($args['scopes'] ?? 'read'))))));
    $days = (int) ($args['days'] ?? 0);
    $issued = qt_issue_api_token((int) $shop['id'], (string) ($args['label'] ?? 'cli'), $scopes, max(0, $days));

    if ($days > 0) {
        $out('Expires ' . date('Y-m-d H:i:s', time() + $days * 86400) . '.');
    }
    if (isset($args['print-token'])) {
        fwrite(STDOUT, $issued['token'] . PHP_EOL);
        exit(0);
    }

    $example = qt_token_example(qt_public_base_url(), $issued['token'], (int) $shop['id']);
    if (isset($args['json'])) {
        fwrite(STDOUT, json_encode([
            'token' => $issued['token'],
            'shop_id' => (int) $shop['id'],
            'shop_name' => (string) $shop['name'],
            'label' => (string) ($args['label'] ?? 'cli'),
            'scopes' => $scopes,
            'expires_at' => (int) ($issued['row']['expires_at'] ?? 0),
            'curl' => $example,
        ], JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES) . PHP_EOL);
        exit(0);
    }

    $out("Token for “{$shop['name']}” (" . implode('+', $scopes) . "):");
    $out('');
    $out('  ' . $issued['token']);
    $out('');
    $out('Show this to the integrator once — only its hash is stored from now on. Their side:');
    $out('');
    foreach (explode("\n", $example) as $line) {
        $out('  ' . $line);
    }
    $out('');
    $out('Revoke it with: php token.php --revoke=' . (int) $issued['row']['id']);
} catch (Throwable $failure) {
    fwrite(STDERR, get_class($failure) . ': ' . $failure->getMessage() . "\n");
    exit(1);
}
