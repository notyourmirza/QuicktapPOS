<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

/**
 * The billing schema, in Laravel's own migration format — but every table is created only
 * when it is missing, because the intended target is the database billing-api already
 * filled. `php artisan migrate --force` on that host is therefore a no-op plus the two new
 * portal tables; on an empty database it is a full install.
 *
 * Column names and types mirror billing-api/lib/schema.php exactly (the till and the old
 * panel read the same rows), which is why nothing here uses a `prefix` or an Eloquent
 * naming convention.
 */
return new class extends Migration
{
    public function up(): void
    {
        foreach ($this->tables() as $name => $build) {
            if (Schema::hasTable($name)) {
                continue;
            }
            Schema::create($name, function ($table) use ($build) {
                $build($table);
            });
            $this->indexes($name);
        }
    }

    public function down(): void
    {
        // Never dropped. This database holds other people's billing history, and a
        // rollback that deletes it is not a rollback.
    }

    private function tables(): array
    {
        return [
            'api_clients' => function ($t) {
                $t->increments('id');
                $t->string('app_id', 64)->unique();
                $t->string('api_key', 128);
                $t->string('name', 80)->default('quicktap-pos');
                $t->string('default_plan', 32)->default('');
                $t->integer('is_active')->default(1);
                $t->dateTime('created_at')->nullable();
            },
            'app_settings' => function ($t) {
                $t->string('setting_key', 64)->primary();
                $t->text('setting_value')->nullable();
            },
            'plans' => function ($t) {
                $t->increments('id');
                $t->string('code', 32)->unique();
                $t->string('name', 60);
                $t->string('tagline', 140)->default('');
                $t->string('tag', 32)->default('');
                $t->decimal('price', 10, 2)->default(0);
                $t->decimal('price_yearly', 10, 2)->default(0);
                $t->integer('max_devices')->default(1);
                $t->text('features')->nullable();
                $t->integer('sort_order')->default(0);
                $t->integer('is_active')->default(1);
                $t->dateTime('created_at')->nullable();
            },
            'shops' => function ($t) {
                $t->increments('id');
                $t->string('name', 80);
                $t->string('currency', 8)->default('PKR');
                $t->string('status', 16)->default('ACTIVE');
                $t->string('plan_code', 32)->default('');
                $t->string('app_name', 40)->default('');
                $t->dateTime('created_at')->nullable();
                $t->dateTime('updated_at')->nullable();
            },
            'users' => function ($t) {
                $t->increments('id');
                $t->integer('shop_id');
                $t->string('username', 32)->unique();
                $t->string('password_hash', 255);
                $t->string('full_name', 80)->default('');
                $t->string('phone', 24)->default('');
                $t->string('role', 16)->default('owner');
                $t->integer('fingerprint_enabled')->default(0);
                $t->string('device_id', 64)->nullable();
                $t->dateTime('licence_confirmed_at')->nullable();
                $t->integer('is_active')->default(1);
                $t->dateTime('created_at')->nullable();
                $t->dateTime('last_login_at')->nullable();
                $t->index('shop_id', 'idx_users_shop');
            },
            'licences' => function ($t) {
                $t->increments('id');
                $t->integer('shop_id');
                $t->string('license_key', 40)->unique();
                $t->string('status', 16)->default('PENDING');
                $t->dateTime('started_at')->nullable();
                // Unix seconds; 0 means lifetime (no expiry to enforce).
                $t->integer('expires_at')->default(0);
                $t->dateTime('activated_at')->nullable();
                $t->string('note', 190)->default('');
                $t->dateTime('created_at')->nullable();
                $t->index('shop_id', 'idx_licences_shop');
            },
            'activation_codes' => function ($t) {
                $t->increments('id');
                $t->string('code', 40)->unique();
                $t->integer('days')->default(30);
                $t->string('note', 190)->default('');
                $t->dateTime('used_at')->nullable();
                $t->integer('used_by_shop')->nullable();
                $t->dateTime('created_at')->nullable();
            },
            'api_tokens' => function ($t) {
                $t->increments('id');
                $t->integer('shop_id');
                $t->string('label', 60)->default('');
                $t->char('token_hash', 64)->unique();
                $t->string('token_prefix', 16);
                $t->string('scopes', 60)->default('read,write');
                $t->dateTime('last_used_at')->nullable();
                $t->integer('expires_at')->default(0);
                $t->dateTime('created_at')->nullable();
                $t->dateTime('revoked_at')->nullable();
                $t->index('shop_id', 'idx_tokens_shop');
            },
            'app_devices' => function ($t) {
                $t->increments('id');
                $t->integer('shop_id');
                $t->string('device_id', 64);
                $t->string('device_name', 80)->default('');
                $t->string('os_version', 60)->default('');
                $t->string('app_version', 20)->default('');
                $t->string('status', 16)->default('ACTIVE');
                $t->dateTime('first_seen_at')->nullable();
                $t->dateTime('last_seen_at')->nullable();
                $t->integer('seen_count')->default(0);
                $t->dateTime('released_at')->nullable();
                $t->unique(['shop_id', 'device_id'], 'uq_devices_shop_device');
                $t->index('shop_id', 'idx_devices_shop');
                $t->index('last_seen_at', 'idx_devices_seen');
            },
            'refresh_tokens' => function ($t) {
                $t->increments('id');
                $t->integer('user_id');
                $t->char('token_hash', 64)->unique();
                $t->string('device_id', 64)->default('');
                $t->integer('expires_at')->default(0);
                $t->dateTime('created_at')->nullable();
                $t->dateTime('revoked_at')->nullable();
                $t->index('user_id', 'idx_tokens_user');
            },
            'sync_records' => function ($t) {
                $t->increments('id');
                $t->integer('shop_id');
                $t->string('entity', 24);
                $t->string('uuid', 40);
                $t->text('payload')->nullable();
                $t->dateTime('updated_at')->nullable();
                $t->dateTime('deleted_at')->nullable();
                $t->dateTime('occurred_at')->nullable();
                $t->integer('occurred_ms')->default(0);
                $t->unique(['shop_id', 'entity', 'uuid'], 'uq_sync_entity_uuid');
                $t->index(['shop_id', 'entity', 'occurred_ms'], 'idx_sync_occurred');
            },
            'notifications' => function ($t) {
                $t->increments('id');
                $t->string('title', 120);
                $t->text('body')->nullable();
                $t->string('level', 16)->default('info');
                $t->dateTime('starts_at')->nullable();
                $t->dateTime('ends_at')->nullable();
                $t->integer('is_active')->default(1);
                $t->dateTime('created_at')->nullable();
            },
            'audit_log' => function ($t) {
                $t->increments('id');
                $t->string('actor', 64)->default('');
                $t->string('action', 40);
                $t->integer('shop_id')->default(0);
                $t->string('detail', 400)->default('');
                $t->dateTime('created_at')->nullable();
                $t->index('shop_id', 'idx_audit_shop');
            },
            'admin_users' => function ($t) {
                $t->increments('id');
                $t->string('username', 32)->unique();
                $t->string('password_hash', 255);
                $t->dateTime('created_at')->nullable();
                $t->dateTime('last_login_at')->nullable();
            },
        ];
    }

    /** Anything a fresh install needs that the blueprint above did not carry. */
    private function indexes(string $table): void
    {
        try {
            if ($table === 'sync_records') {
                DB::statement('ALTER TABLE `sync_records` ADD INDEX `idx_sync_shop` (`shop_id`, `entity`)');
            }
        } catch (Throwable) {
            // An index that already exists or a driver that has no ADD INDEX syntax: the
            // unique key above is the one that keeps data honest, so this is optional.
        }
    }
};
