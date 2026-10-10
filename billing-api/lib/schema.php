<?php
/**
 * The schema, expressed once and rendered per driver.
 *
 * Shared hosts usually offer MySQL through phpMyAdmin, while a developer testing
 * the API locally wants SQLite. Instead of maintaining two files that can drift,
 * each table is declared here and {@see qt_schema_statements()} emits the dialect.
 */

declare(strict_types=1);

/** @return string[] ordered SQL statements; each is idempotent-safe by IF NOT EXISTS */
function qt_schema_statements(string $driver = 'mysql'): array
{
    $mysql = $driver !== 'sqlite';
    $id = $mysql
        ? 'INT NOT NULL AUTO_INCREMENT PRIMARY KEY'
        : 'INTEGER PRIMARY KEY AUTOINCREMENT';
    $suffix = $mysql ? ' ENGINE=InnoDB DEFAULT CHARSET=utf8mb4' : '';

    $tables = [
        // The application credentials the APK ships with (X-App-Id / X-Api-Key).
        'api_clients' => [
            'id' => $id,
            'app_id' => 'VARCHAR(64) NOT NULL UNIQUE',
            'api_key' => 'VARCHAR(128) NOT NULL',
            'name' => "VARCHAR(80) NOT NULL DEFAULT 'quicktap-pos'",
            'default_plan' => "VARCHAR(32) NOT NULL DEFAULT ''",
            'is_active' => "INTEGER NOT NULL DEFAULT 1",
            'created_at' => 'DATETIME NULL',
        ],
        // Key/value remote configuration the admin panel edits (branding, support…).
        'app_settings' => [
            'setting_key' => 'VARCHAR(64) NOT NULL PRIMARY KEY',
            'setting_value' => 'TEXT NULL',
        ],
        // The plan catalogue: price, device seats and features live here, so the
        // in-app store is data rather than code.
        'plans' => [
            'id' => $id,
            'code' => 'VARCHAR(32) NOT NULL UNIQUE',
            'name' => 'VARCHAR(60) NOT NULL',
            'tagline' => "VARCHAR(140) NOT NULL DEFAULT ''",
            'tag' => "VARCHAR(32) NOT NULL DEFAULT ''",
            'price' => 'DECIMAL(10,2) NOT NULL DEFAULT 0',
            'price_yearly' => 'DECIMAL(10,2) NOT NULL DEFAULT 0',
            'max_devices' => 'INTEGER NOT NULL DEFAULT 1',
            'features' => 'TEXT NULL',
            'sort_order' => 'INTEGER NOT NULL DEFAULT 0',
            'is_active' => 'INTEGER NOT NULL DEFAULT 1',
            'created_at' => 'DATETIME NULL',
        ],
        // One shop = one billing account = one subscription.
        'shops' => [
            'id' => $id,
            'name' => 'VARCHAR(80) NOT NULL',
            'currency' => "VARCHAR(8) NOT NULL DEFAULT 'PKR'",
            'status' => "VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'",
            'plan_code' => "VARCHAR(32) NOT NULL DEFAULT ''",
            // Optional per-shop override of the panel-wide app name.
            'app_name' => "VARCHAR(40) NOT NULL DEFAULT ''",
            'created_at' => 'DATETIME NULL',
            'updated_at' => 'DATETIME NULL',
        ],
        'users' => [
            'id' => $id,
            'shop_id' => 'INTEGER NOT NULL',
            'username' => 'VARCHAR(32) NOT NULL UNIQUE',
            'password_hash' => 'VARCHAR(255) NOT NULL',
            'full_name' => "VARCHAR(80) NOT NULL DEFAULT ''",
            'phone' => "VARCHAR(24) NOT NULL DEFAULT ''",
            'role' => "VARCHAR(16) NOT NULL DEFAULT 'owner'",
            'fingerprint_enabled' => 'INTEGER NOT NULL DEFAULT 0',
            // The opaque installation fingerprint this account may sign in from.
            'device_id' => 'VARCHAR(64) NULL',
            'licence_confirmed_at' => 'DATETIME NULL',
            'is_active' => 'INTEGER NOT NULL DEFAULT 1',
            'created_at' => 'DATETIME NULL',
            'last_login_at' => 'DATETIME NULL',
        ],
        // The licence is what expires. A shop may have history rows; the newest one
        // is the answer /v1/license/status gives.
        'licences' => [
            'id' => $id,
            'shop_id' => 'INTEGER NOT NULL',
            'license_key' => 'VARCHAR(40) NOT NULL UNIQUE',
            'status' => "VARCHAR(16) NOT NULL DEFAULT 'PENDING'",
            'started_at' => 'DATETIME NULL',
            // Unix seconds; 0 means "lifetime" (no expiry to enforce).
            'expires_at' => 'INTEGER NOT NULL DEFAULT 0',
            'activated_at' => 'DATETIME NULL',
            'note' => "VARCHAR(190) NOT NULL DEFAULT ''",
            'created_at' => 'DATETIME NULL',
        ],
        // One-time codes the panel hands out; redeeming one extends the licence.
        'activation_codes' => [
            'id' => $id,
            'code' => 'VARCHAR(40) NOT NULL UNIQUE',
            'days' => 'INTEGER NOT NULL DEFAULT 30',
            'note' => "VARCHAR(190) NOT NULL DEFAULT ''",
            'used_at' => 'DATETIME NULL',
            'used_by_shop' => 'INTEGER NULL',
            'created_at' => 'DATETIME NULL',
        ],
        // Programmatic access for a web page, a desktop program or another server:
        // one token per shop, hashed like a password, revocable, scope limited.
        'api_tokens' => [
            'id' => $id,
            'shop_id' => 'INTEGER NOT NULL',
            'label' => "VARCHAR(60) NOT NULL DEFAULT ''",
            'token_hash' => 'CHAR(64) NOT NULL UNIQUE',
            'token_prefix' => 'VARCHAR(16) NOT NULL',
            'scopes' => "VARCHAR(60) NOT NULL DEFAULT 'read,write'",
            'last_used_at' => 'DATETIME NULL',
            // Unix seconds, 0 = never; a shop can hand a temp token to an integrator.
            'expires_at' => 'INTEGER NOT NULL DEFAULT 0',
            'created_at' => 'DATETIME NULL',
            'revoked_at' => 'DATETIME NULL',
        ],
        // The device registry: this is the "hisab" the app shows under Device access.
        'app_devices' => [
            'id' => $id,
            'shop_id' => 'INTEGER NOT NULL',
            'device_id' => 'VARCHAR(64) NOT NULL',
            'device_name' => "VARCHAR(80) NOT NULL DEFAULT ''",
            'os_version' => "VARCHAR(60) NOT NULL DEFAULT ''",
            'app_version' => "VARCHAR(20) NOT NULL DEFAULT ''",
            'status' => "VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'",
            'first_seen_at' => 'DATETIME NULL',
            'last_seen_at' => 'DATETIME NULL',
            'seen_count' => 'INTEGER NOT NULL DEFAULT 0',
            'released_at' => 'DATETIME NULL',
        ],
        'refresh_tokens' => [
            'id' => $id,
            'user_id' => 'INTEGER NOT NULL',
            'token_hash' => 'CHAR(64) NOT NULL UNIQUE',
            'device_id' => "VARCHAR(64) NOT NULL DEFAULT ''",
            'expires_at' => 'INTEGER NOT NULL DEFAULT 0',
            'created_at' => 'DATETIME NULL',
            'revoked_at' => 'DATETIME NULL',
        ],
        // The shared record store: the till pushes catalogue rows and bills here, and
        // the web page / desktop program read and write the same rows. `occurred_ms` is
        // the business time (bill created_at, catalogue updated_at) kept as an integer so
        // a date filter or a newest-first page is a plain index range instead of a scan.
        'sync_records' => [
            'id' => $id,
            'shop_id' => 'INTEGER NOT NULL',
            'entity' => 'VARCHAR(24) NOT NULL',
            'uuid' => 'VARCHAR(40) NOT NULL',
            'payload' => 'TEXT NULL',
            'updated_at' => 'DATETIME NULL',
            'deleted_at' => 'DATETIME NULL',
            'occurred_at' => 'DATETIME NULL',
            'occurred_ms' => 'INTEGER NOT NULL DEFAULT 0',
        ],
        'notifications' => [
            'id' => $id,
            'title' => 'VARCHAR(120) NOT NULL',
            'body' => 'TEXT NULL',
            'level' => "VARCHAR(16) NOT NULL DEFAULT 'info'",
            'starts_at' => 'DATETIME NULL',
            'ends_at' => 'DATETIME NULL',
            'is_active' => 'INTEGER NOT NULL DEFAULT 1',
            'created_at' => 'DATETIME NULL',
        ],
        'audit_log' => [
            'id' => $id,
            'actor' => "VARCHAR(64) NOT NULL DEFAULT ''",
            'action' => 'VARCHAR(40) NOT NULL',
            'shop_id' => 'INTEGER NOT NULL DEFAULT 0',
            'detail' => "VARCHAR(400) NOT NULL DEFAULT ''",
            'created_at' => 'DATETIME NULL',
        ],
        'admin_users' => [
            'id' => $id,
            'username' => 'VARCHAR(32) NOT NULL UNIQUE',
            'password_hash' => 'VARCHAR(255) NOT NULL',
            'created_at' => 'DATETIME NULL',
            'last_login_at' => 'DATETIME NULL',
        ],
    ];

    $indexes = [
        'idx_users_shop' => ['users', 'shop_id', false],
        'idx_licences_shop' => ['licences', 'shop_id', false],
        'idx_devices_shop' => ['app_devices', 'shop_id', false],
        'idx_devices_seen' => ['app_devices', 'last_seen_at', false],
        'idx_tokens_user' => ['refresh_tokens', 'user_id', false],
        'idx_audit_shop' => ['audit_log', 'shop_id', false],
        'idx_tokens_shop' => ['api_tokens', 'shop_id', false],
        'uq_devices_shop_device' => ['app_devices', 'shop_id, device_id', true],
        'uq_sync_entity_uuid' => ['sync_records', 'shop_id, entity, uuid', true],
        'idx_sync_occurred' => ['sync_records', 'shop_id, entity, occurred_ms', false],
    ];

    $statements = [];
    foreach ($tables as $name => $columns) {
        $parts = [];
        foreach ($columns as $column => $definition) {
            $parts[] = sprintf('`%s` %s', $column, $definition);
        }
        $statements[] = sprintf('CREATE TABLE IF NOT EXISTS `%s` (%s)%s',
            $name, implode(', ', $parts), $suffix);
    }
    foreach ($indexes as $label => [$table, $columns, $unique]) {
        $statements[] = sprintf('CREATE %sINDEX `%s` ON `%s` (%s)',
            $unique ? 'UNIQUE ' : '', $label, $table,
            implode(', ', array_map(static fn ($column) => '`' . trim($column) . '`',
                explode(',', $columns))));
    }
    return $statements;
}

/** Seed data: the plan catalogue the app ships with, and nothing secret. */
function qt_schema_seed(): array
{
    $now = date('Y-m-d H:i:s');
    $statements = [];
    foreach ([
        ['starter', 'Starter', 'For a single counter finding its rhythm.', '', 1500, 15000, 1,
            "1 billing counter\nUnlimited products & categories\nBluetooth thermal printing\n"
            . "Daily sales summary\nSupport on WhatsApp", 1],
        ['growth', 'Growth', 'Busy kitchens with reporting and cloud backup.', 'MOST POPULAR',
            3500, 35000, 3,
            "3 billing counters\nCloud backup for every till\nAdvanced reports & exports\n"
            . "Fingerprint session lock\nPriority WhatsApp support", 2],
        ['enterprise', 'Enterprise', 'Multi-branch shops with their own branding.', 'BEST VALUE',
            7500, 75000, 99,
            "Unlimited counters & branches\nYour own app name and logo\nAPI access & integrations\n"
            . "Dedicated account manager\n99.9% uptime commitment", 3],
    ] as $plan) {
        $statements[] = [
            'INSERT INTO plans (code, name, tagline, tag, price, price_yearly, max_devices,'
            . ' features, sort_order, is_active, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)',
            [$plan[0], $plan[1], $plan[2], $plan[3], $plan[4], $plan[5], $plan[6], $plan[7], $plan[8], $now],
        ];
    }
    foreach ([
        'app_name' => 'QuickTap POS',
        'logo_url' => '',
        'receipt_template' => 'classic',
        'support_whatsapp' => '',
        'license_sync_minutes' => '60',
        'offline_grace_hours' => '24',
        'api_base_url' => '',
        'fb_app_id' => '',
        'fb_api_key' => '',
        'fb_project_id' => '',
        'fb_sender_id' => '',
        'min_app_version' => '',
        'force_update' => '0',
        'auto_lock_minutes' => '15',
        'tax_percent' => '0',
        'receipt_footer' => '',
    ] as $key => $value) {
        $statements[] = [
            'INSERT INTO app_settings (setting_key, setting_value) VALUES (?, ?)',
            [$key, $value],
        ];
    }
    return $statements;
}
