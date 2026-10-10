<?php
/**
 * API access for clients that are not the Android app: a website, a desktop program,
 * another server, or a script.
 *
 * A token names exactly one shop and carries scopes. It is shown once, stored only as a
 * sha256 hash, and revoking it takes effect on the next request. That is deliberately
 * less powerful than a phone session: it can read and write the shop's own records, and
 * nothing else — no licence changes, no device list, no settings.
 */

declare(strict_types=1);

/** Scopes a token may hold, and what each one unlocks. */
function qt_scope_catalog(): array
{
    return [
        'read' => 'Read the catalogue, bills and reports of one shop.',
        'write' => 'Create or update bills and catalogue rows for that shop.',
    ];
}

/** Raw lookup by secret — the caller decides what an expired or revoked row means. */
function qt_find_token(string $token): ?array
{
    return qt_row('SELECT * FROM api_tokens WHERE token_hash = ? LIMIT 1', [qt_hash_token($token)]);
}

/**
 * Creates a token and returns ['token' => plaintext, 'row' => array]. The plaintext is
 * never stored: it is printed once by the panel and then only its hash exists.
 * $days > 0 makes it expire, which is how a temporary integrator gets access.
 */
function qt_issue_api_token(int $shopId, string $label, array $scopes = ['read', 'write'],
                           int $days = 0): array
{
    $clean = [];
    foreach ($scopes as $scope) {
        $scope = strtolower(trim((string) $scope));
        if ($scope !== '' && isset(qt_scope_catalog()[$scope]) && !in_array($scope, $clean, true)) {
            $clean[] = $scope;
        }
    }
    if (!$clean) {
        $clean = ['read'];
    }

    $token = 'qt_pat_' . bin2hex(random_bytes(24));
    $id = qt_insert('api_tokens', [
        'shop_id' => $shopId,
        'label' => mb_substr($label !== '' ? $label : 'API token', 0, 60),
        'token_hash' => qt_hash_token($token),
        'token_prefix' => substr($token, 0, 12),
        'scopes' => implode(',', $clean),
        'expires_at' => $days > 0 ? time() + $days * 86400 : 0,
        'created_at' => qt_sql_now(),
    ]);
    return [
        'token' => $token,
        'row' => qt_row('SELECT * FROM api_tokens WHERE id = ?', [$id]),
    ];
}

/** Tokens of one shop, newest first, with the plaintext never recoverable. */
function qt_list_api_tokens(int $shopId): array
{
    $rows = qt_rows('SELECT * FROM api_tokens WHERE shop_id = ? ORDER BY revoked_at IS NOT NULL, id DESC',
        [$shopId]);
    $out = [];
    foreach ($rows as $row) {
        $out[] = [
            'id' => (int) $row['id'],
            'label' => (string) $row['label'],
            'prefix' => (string) $row['token_prefix'],
            'scopes' => array_values(array_filter(explode(',', (string) $row['scopes']))),
            'created_at' => (string) $row['created_at'],
            'last_used_at' => (string) ($row['last_used_at'] ?? ''),
            'revoked' => !empty($row['revoked_at']),
            'expires_at' => (int) ($row['expires_at'] ?? 0),
        ];
    }
    return $out;
}

function qt_revoke_api_token(int $tokenId, int $shopId): bool
{
    $statement = qt_db()->prepare('UPDATE api_tokens SET revoked_at = ?
        WHERE id = ? AND shop_id = ? AND revoked_at IS NULL');
    $statement->execute([qt_sql_now(), $tokenId, $shopId]);
    return $statement->rowCount() > 0;
}

/** The exact snippet a customer can paste, with their own token in it. */
function qt_token_example(string $baseUrl, string $token, int $shopId): string
{
    $base = rtrim($baseUrl, '/') . '/';
    return implode("\n", [
        '# who am I, and is this shop allowed to bill right now?',
        'curl -s ' . $base . 'v1/shop \\',
        '  -H "X-Api-Token: ' . $token . '"',
        '',
        '# bills of today',
        'curl -s "' . $base . 'v1/bills?from=' . date('Y-m-d') . '" -H "X-Api-Token: ' . $token . '"',
        '',
        '# one new bill (totals are calculated by the server)',
        'curl -s -X POST ' . $base . 'v1/bills -H "Content-Type: application/json" \\',
        '  -H "X-Api-Token: ' . $token . '" \\',
        '  -d \'{"order_type":"TAKE_AWAY","items":[{"name":"Chicken Roll","price":250,"qty":2}]}\'',
    ]);
}
