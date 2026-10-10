<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Support\Facades\Schema;

/**
 * The two tables that belong to the portal rather than to billing: the marketing pages the
 * panel edits, and the support queue that /support writes into.
 */
return new class extends Migration
{
    public function up(): void
    {
        if (! Schema::hasTable('site_pages')) {
            Schema::create('site_pages', function ($table) {
                $table->increments('id');
                $table->string('slug', 60)->unique();
                $table->string('title', 120);
                $table->string('subtitle', 200)->default('');
                $table->string('badge', 32)->default('');
                $table->string('price_label', 40)->default('');
                $table->text('body')->nullable();
                $table->integer('sort_order')->default(0);
                $table->integer('is_active')->default(1);
                $table->dateTime('created_at')->nullable();
                $table->dateTime('updated_at')->nullable();
            });
        }

        if (! Schema::hasTable('support_tickets')) {
            Schema::create('support_tickets', function ($table) {
                $table->increments('id');
                $table->string('name', 80);
                $table->string('phone', 24)->default('');
                $table->string('shop', 80)->default('');
                $table->string('topic', 40)->default('general');
                $table->text('message');
                $table->string('status', 12)->default('OPEN');
                $table->string('admin_note', 400)->default('');
                $table->dateTime('created_at')->nullable();
                $table->dateTime('answered_at')->nullable();
                $table->index('status', 'idx_tickets_status');
            });
        }
    }

    public function down(): void
    {
        Schema::dropIfExists('support_tickets');
        Schema::dropIfExists('site_pages');
    }
};
