<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Support\Facades\Schema;

/**
 * Columns the plain-PHP API added over time. On a database that predates them,
 * `CREATE TABLE IF NOT EXISTS` never touches the existing table, so each one gets its own
 * guarded add — the same list install.php applies, ported so `artisan migrate` is the only
 * command a deployer has to remember.
 *
 * Nothing here removes or rewrites a column: shop history is not a thing a deploy may lose.
 */
return new class extends Migration
{
    /** @var array<string, array<int, array{0:string,1:string}>> */
    private const COLUMNS = [
        'sync_records' => [
            ['occurred_at', 'DATETIME NULL'],
            ['occurred_ms', 'INTEGER NOT NULL DEFAULT 0'],
        ],
        'app_devices' => [
            ['seen_count', 'INTEGER NOT NULL DEFAULT 0'],
            ['released_at', 'DATETIME NULL'],
        ],
        'licences' => [
            ['activated_at', 'DATETIME NULL'],
        ],
        'api_tokens' => [
            ['expires_at', 'INTEGER NOT NULL DEFAULT 0'],
        ],
    ];

    public function up(): void
    {
        foreach (self::COLUMNS as $table => $columns) {
            if (! Schema::hasTable($table)) {
                continue;
            }
            foreach ($columns as [$name, $definition]) {
                if (Schema::hasColumn($table, $name)) {
                    continue;
                }
                \Illuminate\Support\Facades\DB::statement(
                    sprintf('ALTER TABLE `%s` ADD COLUMN `%s` %s', $table, $name, $definition)
                );
            }
        }

        if (Schema::hasTable('sync_records') && ! $this->hasIndex('sync_records', 'idx_sync_occurred')) {
            try {
                \Illuminate\Support\Facades\DB::statement(
                    'CREATE INDEX `idx_sync_occurred` ON `sync_records` (`shop_id`, `entity`, `occurred_ms`)');
            } catch (Throwable) {
                // SQLite wants no backticks and MySQL 5.7 no IF NOT EXISTS; the index is a
                // speed-up, and a missing one must not fail a deploy.
            }
        }
    }

    public function down(): void
    {
        //
    }

    private function hasIndex(string $table, string $index): bool
    {
        try {
            return \Illuminate\Support\Facades\Schema::hasIndex($table, $index);
        } catch (Throwable) {
            return true;
        }
    }
};
