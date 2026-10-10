<?php

namespace Database\Seeders;

use App\Support\Billing;
use Illuminate\Database\Seeder;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Hash;

/**
 * A first-run database, idempotent: every insert is guarded by an existence check, so
 * `php artisan db:seed` is safe on a host that already has shops (it only fills holes) and
 * enough on a fresh one (it creates a login, the plans and the site copy).
 *
 * Passwords come from .env and are never written into a file that ships: change them in the
 * panel the first time you log in.
 */
class DatabaseSeeder extends Seeder
{
    public function run(): void
    {
        $this->settings();
        $this->plans();
        $this->client();
        $this->admin();
        $this->pages();
    }

    private function settings(): void
    {
        foreach (config('billing.seed.settings', []) as $key => $value) {
            $this->put('app_settings', 'setting_key', $key, ['setting_value' => (string) $value]);
        }
    }

    private function plans(): void
    {
        foreach (config('billing.seed.plans', []) as $plan) {
            if (DB::table('plans')->where('code', $plan['code'])->exists()) {
                continue;
            }
            DB::table('plans')->insert([
                'code' => $plan['code'],
                'name' => $plan['name'],
                'tagline' => $plan['tagline'] ?? '',
                'tag' => $plan['tag'] ?? '',
                'price' => (float) ($plan['price'] ?? 0),
                'price_yearly' => (float) ($plan['price_yearly'] ?? 0),
                'max_devices' => (int) ($plan['devices'] ?? 1),
                'features' => implode("\n", (array) ($plan['features'] ?? [])),
                'sort_order' => (int) ($plan['sort'] ?? 0),
                'is_active' => 1,
                'created_at' => Billing::stamp(),
            ]);
        }
    }

    /** The build credentials every app request is checked against. */
    private function client(): void
    {
        $appId = (string) config('billing.app_id', '');
        if ($appId === '' || DB::table('api_clients')->where('app_id', $appId)->exists()) {
            return;
        }
        $key = (string) config('billing.app_api_key', '') ?: bin2hex(random_bytes(16));
        DB::table('api_clients')->insert([
            'app_id' => $appId,
            'api_key' => $key,
            'name' => 'QuickTap POS',
            'created_at' => Billing::stamp(),
        ]);
        $this->command?->info('Build credentials — APP_ID=' . $appId . ' APP_API_KEY=' . $key);
        $this->command?->info('Put that key in app/build.gradle and in .env; it is not stored anywhere else.');
    }

    private function admin(): void
    {
        $username = (string) config('billing.seed.admin_username', 'quickadmin');
        if (DB::table('admin_users')->where('username', $username)->exists()) {
            return;
        }
        $password = (string) config('billing.seed.admin_password', '');
        if (strlen($password) < 10) {
            $this->command?->warn('ADMIN_PASSWORD is empty or short — skipping the admin user. '
                . 'Set it in .env, then run db:seed again.');
            return;
        }
        DB::table('admin_users')->insert([
            'username' => $username,
            'password_hash' => Hash::make($password),
            'created_at' => Billing::stamp(),
        ]);
    }

    /** The two marketing pages /software?slug= serves, with the copy the site already uses. */
    private function pages(): void
    {
        foreach ([
            ['quicktap-retail', 'Retail Pro', 'Dukan ki poori billing, inventory aur customers — ek app me.', 'MOST POPULAR',
                'Rs 799/mo', "Kya milta hai:
Bill + GST, 300 products 10 minute me import
Barcode scanner aur USB printer support
Khata, due list aur WhatsApp reminder
Kasouli (stock) aur low-stock alert
Din/week ka report, English ya Urdu"],
            ['quicktap-books', 'Books & Khata', 'Kitaon ki dukan ka hisab — Urdu aur English dono.', 'URDU + ENGLISH',
                'Rs 599/saal', "Kya milta hai:
Udhaari khata, party-wise
Sale, return aur kist ka hisab
Printable bill (80mm) aur PDF
Report: aaj, mahina, saal
Ek hi data phone aur website par"],
        ] as [$slug, $title, $subtitle, $badge, $price, $body]) {
            $this->put('site_pages', 'slug', $slug, [
                'title' => $title, 'subtitle' => $subtitle, 'badge' => $badge,
                'price_label' => $price, 'body' => $body, 'is_active' => 1,
                'created_at' => Billing::stamp(), 'updated_at' => Billing::stamp(),
            ]);
        }
    }

    /** Inserts the row only if it is missing; `$extra` carries the payload columns. */
    private function put(string $table, string $keyColumn, string $keyValue, array $extra): void
    {
        if (DB::table($table)->where($keyColumn, $keyValue)->exists()) {
            return;
        }
        DB::table($table)->insert([$keyColumn => $keyValue] + $extra);
    }
}
