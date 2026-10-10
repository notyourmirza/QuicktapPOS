<?php

use Illuminate\Support\Facades\Artisan;

/*
 * One scheduled job matters here: the shop owners whose licence runs out in three days
 * get a WhatsApp reminder line in the panel (the desk sends it), and every shop's
 * expiring licences are marked so the list can be filtered. `licence:remind` prints the
 * list; add it to the host cron if you want it emailed instead of looked at.
 */
Artisan::command('licence:remind {--days=3}', function (string $days) {
    $until = time() + ((int) $days) * 86400;
    $rows = Illuminate\Support\Facades\DB::table('licences')
        ->where('status', 'ACTIVE')->where('expires_at', '>', 0)
        ->where('expires_at', '<=', $until)->orderBy('expires_at')->get();
    foreach ($rows as $row) {
        $shop = Illuminate\Support\Facades\DB::table('shops')->where('id', $row->shop_id)->first();
        $this->line(sprintf('%s  #%d  %s  expires %s',
            (string) ($shop->name ?? '?'), (int) $row->shop_id,
            date('Y-m-d', (int) $row->expires_at), (string) $row->license_key));
    }
    $this->info($rows->count() . ' licence(s) expiring within ' . $days . ' day(s).');
})->purpose('Shops whose licence is about to run out');

Artisan::command('billing:seed-panel {--admin-user=} {--admin-pass=}', function () {
    $user = (string) $this->option('admin-user') ?: 'admin';
    $pass = (string) $this->option('admin-pass') ?: (bin2hex(random_bytes(6)));
    $exists = Illuminate\Support\Facades\DB::table('admin_users')->where('username', $user)->exists();
    if ($exists) {
        $this->warn("The panel login “{$user}” already exists — nothing was changed.");
        return;
    }
    Illuminate\Support\Facades\DB::table('admin_users')->insert([
        'username' => $user,
        'password_hash' => Illuminate\Support\Facades\Hash::make($pass),
        'created_at' => now(),
    ]);
    $this->info('Panel login ready. Username: ' . $user);
    $this->info('Password (shown once): ' . $pass);
})->purpose('Create the panel login on a fresh install');

Artisan::command('api:token {shop} {--label=website} {--scopes=read,write} {--days=0} {--revoke=}',
    function (string $shop) {
        $revoke = (string) $this->option('revoke');
        if ($revoke !== '') {
            $row = Illuminate\Support\Facades\DB::table('api_tokens')->where('id', (int) $revoke)->first();
            if (! $row) {
                $this->error('No token #' . $revoke . '.');
                return 1;
            }
            $gone = App\Support\Billing::revokeToken((int) $row->id, (int) $row->shop_id);
            $this->info($gone ? 'Revoked.' : 'Already revoked.');
            return 0;
        }
        $shopRow = Illuminate\Support\Facades\DB::table('shops')->where('id', (int) $shop)->orWhere('name', $shop)->first();
        if (! $shopRow) {
            $this->error('No shop “' . $shop . '”.');
            return 1;
        }
        $issued = App\Support\Billing::issueToken((int) $shopRow->id, (string) $this->option('label'),
            array_map('trim', explode(',', strtolower((string) $this->option('scopes')))),
            max(0, (int) $this->option('days')));
        $this->line($issued['token']);
        return 0;
    })->purpose('Issue or revoke a shop API token from the shell');
