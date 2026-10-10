<?php
/**
 * One-shot installer for the QuickTap billing API.
 *
 *   php install.php                 create the schema, seed the plans, create an
 *                                   admin login and an app credential pair
 *   php install.php --print         only print the SQL (for phpMyAdmin on a host
 *                                   without shell access)
 *   php install.php --admin-user=… --admin-pass=…
 *   php install.php --force         re-run after a schema change (never drops)
 *
 * From a browser: https://your-host/quicktap-api/install.php?key=<INSTALL_KEY>
 * — INSTALL_KEY lives in config.php. Delete this file when the install is done.
 */

declare(strict_types=1);

require __DIR__ . '/lib/support.php';

$cli = PHP_SAPI === 'cli';
$args = [];
foreach ($cli ? array_slice($_SERVER['argv'], 1) : [] as $argument) {
    if (preg_match('/^--([\w-]+)(?:=(.*))?$/', $argument, $match)) {
        $args[$match[1]] = $match[2] ?? '1';
    }
}

$config = qt_config();

// Web installs must prove they were asked for by whoever wrote config.php.
if (!$cli) {
    $key = (string) ($_GET['key'] ?? '');
    $expected = (string) ($config['install_key'] ?? '');
    header('Content-Type: text/plain; charset=utf-8');
    if ($expected === '' || !hash_equals($expected, $key)) {
        http_response_code(403);
        echo "Refused.\n\nAdd 'install_key' to config.php, open this URL again with\n"
            . "?key=that-value, then delete install.php from the server.\n";
        exit;
    }
}

$lines = [];
$say = static function (string $line) use (&$lines, $cli): void {
    $lines[] = $line;
    if ($cli) {
        fwrite(STDOUT, $line . PHP_EOL);
    }
};

if (isset($args['print'])) {
    foreach (qt_schema_statements((string) ($_GET['driver'] ?? ($config['driver'] ?? 'mysql'))) as $statement) {
        echo $statement . ";\n";
    }
    exit;
}

$driver = (string) ($config['driver'] ?? 'mysql');
try {
    $pdo = qt_db();
} catch (Throwable $failure) {
    $say('Cannot connect to the database: ' . $failure->getMessage());
    $say('Check db_host / db_name / db_user / db_pass in config.php.');
    exit($cli ? 1 : 0);
}

$say('Driver: ' . $driver);
$created = 0;
foreach (qt_schema_statements($driver) as $statement) {
    try {
        $pdo->exec($statement);
        $created++;
    } catch (Throwable $failure) {
        // "index already exists" is normal on a re-run; anything else is worth seeing.
        $message = $failure->getMessage();
        if (stripos($message, 'exists') === false && stripos($message, 'duplicate') === false) {
            $say('  skipped: ' . $message);
        }
    }
}
$say(sprintf('Schema ready (%d statements applied).', $created));

// Columns added after the first release: CREATE TABLE IF NOT EXISTS will not touch
// an existing table, so each one gets its own guarded ALTER.
foreach ([
    'sync_records' => ['occurred_at DATETIME NULL', 'occurred_ms INTEGER NOT NULL DEFAULT 0'],
    'app_devices' => ['seen_count INTEGER NOT NULL DEFAULT 0', 'released_at DATETIME NULL'],
    'licences' => ['activated_at DATETIME NULL'],
] as $table => $columns) {
    foreach ($columns as $column) {
        try {
            $pdo->exec('ALTER TABLE `' . $table . '` ADD COLUMN `'
                . explode(' ', $column)[0] . '` ' . substr($column, strlen(explode(' ', $column)[0]) + 1));
        } catch (Throwable $ignored) {
            // "duplicate column name" is the normal, expected answer on re-runs.
        }
    }
}

$tables = $pdo->query($driver === 'sqlite'
    ? "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name"
    : 'SHOW TABLES')->fetchAll(PDO::FETCH_COLUMN);
$say('Tables: ' . implode(', ', $tables));

// Seed the plan catalogue once; re-running must never overwrite live prices.
$plans = (int) $pdo->query('SELECT COUNT(*) FROM plans')->fetchColumn();
if ($plans === 0) {
    foreach (qt_schema_seed() as [$statement, $bindings]) {
        $pdo->prepare($statement)->execute($bindings);
    }
    $say('Seeded 3 plans and the default settings.');
} else {
    $say('Plans already present (' . $plans . ') — seed skipped.');
}

// Admin login.
$adminUser = (string) ($args['admin-user'] ?? getenv('QT_ADMIN_USER') ?: 'admin');
$adminPass = (string) ($args['admin-pass'] ?? getenv('QT_ADMIN_PASS') ?: '');
if ($adminPass === '') {
    $adminPass = bin2hex(random_bytes(8));
    $generated = true;
} else {
    $generated = false;
}
$existing = $pdo->prepare('SELECT id FROM admin_users WHERE username = ?');
$existing->execute([$adminUser]);
if ($existing->fetchColumn() === false) {
    $insert = $pdo->prepare('INSERT INTO admin_users (username, password_hash, created_at) VALUES (?, ?, ?)');
    $insert->execute([$adminUser, password_hash($adminPass, PASSWORD_DEFAULT), date('Y-m-d H:i:s')]);
    $say('Admin created: ' . $adminUser);
} else {
    $say('Admin already exists: ' . $adminUser . ' (password left unchanged).');
    $generated = false;
}

// Application credentials for app/build.gradle.
$appId = 'app_' . bin2hex(random_bytes(6));
$apiKey = bin2hex(random_bytes(24));
$clients = (int) $pdo->query('SELECT COUNT(*) FROM api_clients')->fetchColumn();
$clientCreated = false;
if (!isset($args['no-client']) && $clients === 0) {
    $clientCreated = true;
    $pdo->prepare('INSERT INTO api_clients (app_id, api_key, name, default_plan, is_active, created_at)
        VALUES (?, ?, ?, ?, 1, ?)')
        ->execute([$appId, $apiKey, 'quicktap-pos', 'starter', date('Y-m-d H:i:s')]);
    $say('App credential created.');
}

if (!$generated) {
    $adminPass = '(unchanged)';
}

$say('');
$say('Put these three lines into app/build.gradle → android.defaultConfig:');
$say('    buildConfigField "String", "BOOTSTRAP_BASE_URL", "https://<your host>/api/"');
$say('    buildConfigField "String", "APP_ID",  "'
    . ($clientCreated ? $appId : '(see admin > Clients)') . '"');
$say('    buildConfigField "String", "API_KEY", "'
    . ($clientCreated ? $apiKey : '(see admin > Clients)') . '"');
$say('');
if ($clientCreated) {
    $say('Write the same pair into config.php as app_id / app_api_key too, so the');
    $say('app-client check has a fallback if the database row is ever removed.');
}
$say('');
$say('Admin panel: the admin/ folder next to this file  ·  login ' . $adminUser . ' / ' . $adminPass);
$say('Then delete install.php from the server.');

if (!$cli) {
    echo implode("\n", $lines);
}
