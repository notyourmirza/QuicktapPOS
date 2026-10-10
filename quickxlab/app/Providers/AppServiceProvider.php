<?php

namespace App\Providers;

use App\Support\Billing;
use Illuminate\Database\Events\Query;
use Illuminate\Pagination\Paginator;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\URL;
use Illuminate\Support\ServiceProvider;
use Illuminate\View\Facade\View;

/**
 * The few things every screen needs and none of them want to remember:
 * shared site variables for Blade, https URLs behind a shared host's load balancer, and a
 * settings read that is cached per request because the app reads the same keys ten times.
 */
class AppServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        //
    }

    public function boot(): void
    {
        Paginator::useBootstrapSimple();
        if (str_starts_with((string) config('app.url'), 'https://')) {
            URL::forceScheme('https');
        }

        View::composer('*', function ($view) {
            // A host that has not run `artisan migrate` yet must still get the marketing
            // page — an empty settings read is a worse failure than a 500 on /plans.
            try {
                $settings = Billing::settings();
            } catch (\Throwable) {
                $settings = [];
            }
            $view->with('site', [
                'name' => (string) ($settings['app_name'] ?? config('app.name')),
                'logo' => (string) ($settings['logo_url'] ?? ''),
                'whatsapp' => (string) ($settings['support_whatsapp'] ?? ''),
                'currency' => (string) ($settings['currency'] ?? 'PKR'),
                'tax' => (float) ($settings['tax_percent'] ?? 0),
            ]);
        });

        // A query log in storage/logs/sql.log is the difference between "the panel is
        // slow" and "it is the sync_records scan" — off unless someone asks for it.
        if (env('DB_QUERY_LOG') === 'true') {
            DB::listen(fn (Query $query) => logger('sql', [
                'query' => $query->sql,
                'ms' => round($query->time, 1),
            ]));
        }
    }
}
