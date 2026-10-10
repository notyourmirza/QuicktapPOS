<?php

namespace App\Http\Controllers\Admin;

use App\Http\Controllers\Admin\Concerns\Panels;
use App\Http\Controllers\Controller;
use App\Support\Billing;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\View\View;

/**
 * The parts of the site and the app that are copy rather than code: the marketing pages,
 * the notices in the till's bell, the app-wide settings (including the support WhatsApp
 * number the whole product points at) and the APK the download page offers.
 *
 * All of it is rows in the same tables the plain-PHP panel wrote, so both panels can be
 * used during the move and neither one hides data from the other.
 */
class ContentController extends Controller
{
    use Panels;

    /** The app_settings keys the panel may edit, and what each one does. */
    private const SETTINGS = [
        'app_name' => 'Naam jo login screen aur receipts par dikhta hai',
        'logo_url' => 'Logo (https URL) — login screen aur receipts',
        'receipt_template' => 'Receipt layout: classic | compact | full',
        'receipt_footer' => 'Receipt ka aakhri line (shukriya, address…)',
        'support_whatsapp' => 'Support WhatsApp number (digits, country code ke saath)',
        'license_sync_minutes' => 'Licence dobara check karne ka interval (minutes)',
        'offline_grace_hours' => 'Licence ke baad kitne ghante offline billing chalti rahe',
        'auto_lock_minutes' => 'Session auto-lock (minutes)',
        'tax_percent' => 'Default tax %',
        'currency' => 'Currency code (PKR…)',
        'api_base_url' => 'Yo URL har till ko next bootstrap par mil jati hai',
        'min_app_version' => 'Minimum allowed versionName (e.g. 1.3.0)',
        'force_update' => '1 = is se purani build chalegi hi nahi',
        'fb_app_id' => 'Firebase app id (optional)',
        'fb_api_key' => 'Firebase API key (optional)',
        'fb_project_id' => 'Firebase project id (optional)',
        'fb_sender_id' => 'Firebase sender id (optional)',
        'apk_version_name' => 'Download page par version label',
        'apk_version_code' => 'versionCode',
        'apk_file' => 'public/apk/ ke andar APK ka file name',
        'apk_notes' => 'Download page ki release notes (niyam, ek line me)',
    ];

    // ------------------------------------------------------------------ pages

    public function pages(): View
    {
        return view('admin.pages', [
            'pages' => DB::table('site_pages')->orderBy('sort_order')->orderBy('id')->get()
                ->map(static fn ($row) => (array) $row)->all(),
            'editing' => $this->page((int) request('id', 0)),
        ]);
    }

    public function savePage(Request $request)
    {
        $data = $request->validate([
            'id' => 'nullable|integer',
            'slug' => 'required|string|max:60|regex:/^[a-z0-9-]+$/',
            'title' => 'required|string|max:120',
            'subtitle' => 'nullable|string|max:200',
            'badge' => 'nullable|string|max:32',
            'price_label' => 'nullable|string|max:40',
            'body' => 'nullable|string|max:20000',
            'sort_order' => 'nullable|integer|min:0|max:999',
            'is_active' => 'nullable|boolean',
        ]);
        $row = [
            'slug' => $data['slug'],
            'title' => $data['title'],
            'subtitle' => (string) ($data['subtitle'] ?? ''),
            'badge' => strtoupper((string) ($data['badge'] ?? '')),
            'price_label' => (string) ($data['price_label'] ?? ''),
            'body' => (string) ($data['body'] ?? ''),
            'sort_order' => (int) ($data['sort_order'] ?? 0),
            'is_active' => isset($data['is_active']) ? 1 : 0,
            'updated_at' => Billing::stamp(),
        ];
        $id = (int) ($data['id'] ?? 0);
        if ($id > 0) {
            DB::table('site_pages')->where('id', $id)->update($row);
            Billing::audit('page_edit', 0, $row['slug']);
        } else {
            $row['created_at'] = Billing::stamp();
            DB::table('site_pages')->insert($row);
            Billing::audit('page_new', 0, $row['slug']);
        }

        return redirect()->route('admin.pages')->with('flash.ok', 'Page saved. `/software?slug=' . $row['slug'] . '`');
    }

    public function deletePage(Request $request, int $page)
    {
        DB::table('site_pages')->where('id', $page)->update(['is_active' => 0, 'updated_at' => Billing::stamp()]);
        Billing::audit('page_off', 0, 'site page #' . $page);

        return $this->flash($request, 'Page unpublished (the row stays, so an old link still resolves to nothing but no data is lost).');
    }

    private function page(int $id): ?array
    {
        if ($id <= 0) {
            return null;
        }
        $row = DB::table('site_pages')->where('id', $id)->first();

        return $row === null ? null : (array) $row;
    }

    // ----------------------------------------------------------------- notices

    public function notices(): View
    {
        // ?id=N loads one row into the form above — the same one-form-two-modes trick the
        // pages screen uses, so there is no edit dialog to keep in sync.
        $editing = null;
        if ($id = (int) request('id', 0)) {
            $row = DB::table('notifications')->where('id', $id)->first();
            $editing = $row === null ? null : (array) $row;
        }

        return view('admin.notices', [
            'notices' => DB::table('notifications')->orderByDesc('id')->limit(100)->get()
                ->map(static fn ($row) => (array) $row)->all(),
            'editing' => $editing,
        ]);
    }

    public function saveNotice(Request $request)
    {
        $data = $request->validate([
            'id' => 'nullable|integer',
            'title' => 'required|string|max:120',
            'body' => 'nullable|string|max:2000',
            'level' => 'required|in:info,warn,alert',
            'starts_at' => 'nullable|string|max:20',
            'ends_at' => 'nullable|string|max:20',
            'is_active' => 'nullable|boolean',
        ]);
        $row = [
            'title' => $data['title'],
            'body' => (string) ($data['body'] ?? ''),
            'level' => $data['level'],
            'starts_at' => empty($data['starts_at']) ? null : Billing::cleanTime((string) $data['starts_at']),
            'ends_at' => empty($data['ends_at']) ? null : Billing::cleanTime((string) $data['ends_at']),
            'is_active' => isset($data['is_active']) ? 1 : 0,
        ];
        $id = (int) ($data['id'] ?? 0);
        if ($id > 0) {
            DB::table('notifications')->where('id', $id)->update($row);
        } else {
            $row['created_at'] = Billing::stamp();
            DB::table('notifications')->insert($row);
        }
        Billing::audit('notice', 0, ($id > 0 ? 'edited #' . $id : 'new') . ' · ' . $row['title']);

        return redirect()->route('admin.notices')->with('flash.ok',
            'Notice saved. Tills pick it up on their next config fetch.');
    }

    public function deleteNotice(Request $request, int $notice)
    {
        DB::table('notifications')->where('id', $notice)->update(['is_active' => 0]);

        return $this->flash($request, 'Notice turned off.');
    }

    // ---------------------------------------------------------------- settings

    public function settings(): View
    {
        $current = Billing::settings();

        return view('admin.settings', [
            'current' => $current,
            'labels' => self::SETTINGS,
            'files' => $this->apks(),
            'base' => rtrim((string) (config('billing.api_base_url') ?: url('/api')), '/') . '/',
        ]);
    }

    public function saveSettings(Request $request)
    {
        Billing::forgetSettings();
        $posted = (array) $request->input('settings', []);
        $changed = 0;

        foreach (array_keys(self::SETTINGS) as $key) {
            if ($key === 'force_update') {
                continue;   // a checkbox posts nothing when it is off; handled below
            }
            if (! array_key_exists($key, $posted)) {
                continue;
            }
            $value = trim((string) $posted[$key]);
            if ($key === 'support_whatsapp') {
                $value = preg_replace('/[^0-9+]/', '', $value);
            }
            if ($key === 'api_base_url' && $value !== '') {
                $value = rtrim($value, '/') . '/';
            }
            if ($value === (string) (Billing::settings()[$key] ?? '')) {
                continue;
            }
            $this->putSetting($key, $value);
            $changed++;
        }

        $this->putSetting('force_update', empty($posted['force_update']) ? '0' : '1');
        Billing::forgetSettings();
        Billing::audit('settings', 0, $changed . ' key(s) updated');

        return redirect()->route('admin.settings')->with('flash.ok',
            $changed . ' setting(s) saved — tills read them on their next config fetch.');
    }

    private function putSetting(string $key, string $value): void
    {
        $updated = DB::table('app_settings')->where('setting_key', $key)->update(['setting_value' => $value]);
        if ($updated === 0) {
            DB::table('app_settings')->insert(['setting_key' => $key, 'setting_value' => $value]);
        }
    }

    /** The APK upload; the download page then offers exactly this file. */
    public function uploadApk(Request $request)
    {
        $request->validate([
            'apk' => 'required|file|mimes:apk|max:120000',
            'version_name' => 'required|string|max:20',
            'version_code' => 'required|integer|min:1',
            'notes' => 'nullable|string|max:600',
        ]);
        $name = 'quicktap-' . preg_replace('/[^A-Za-z0-9._-]/', '', $request->input('version_name')) . '.apk';
        $request->file('apk')->move(public_path('apk'), $name);
        foreach ([
            'apk_file' => $name,
            'apk_version_name' => (string) $request->input('version_name'),
            'apk_version_code' => (string) $request->input('version_code'),
            'apk_notes' => (string) $request->input('notes', ''),
        ] as $key => $value) {
            if (DB::table('app_settings')->where('setting_key', $key)->exists()) {
                DB::table('app_settings')->where('setting_key', $key)->update(['setting_value' => $value]);
            } else {
                DB::table('app_settings')->insert(['setting_key' => $key, 'setting_value' => $value]);
            }
        }
        Billing::forgetSettings();
        Billing::audit('apk', 0, $name);

        return redirect()->route('admin.settings')->with('flash.ok', 'APK published: ' . $name);
    }

    private function apks(): array
    {
        $files = [];
        foreach ((array) glob(public_path('apk/*.apk')) as $path) {
            $files[] = [
                'name' => basename($path),
                'size' => round(filesize($path) / 1048576, 1),
                'date' => date('Y-m-d H:i', (int) filemtime($path)),
            ];
        }
        usort($files, static fn ($a, $b) => strcmp($b['date'], $a['date']));

        return $files;
    }

    // ----------------------------------------------------------------- tickets

    public function tickets(): View
    {
        return view('admin.tickets', [
            'tickets' => DB::table('support_tickets')->orderByRaw(
                "CASE WHEN status = 'OPEN' THEN 0 ELSE 1 END, id DESC")->limit(200)->get()
                ->map(static fn ($row) => (array) $row)->all(),
        ]);
    }

    public function answerTicket(Request $request, int $ticket)
    {
        $status = (string) $request->input('status', 'DONE');
        if (! in_array($status, ['OPEN', 'DONE', 'WAITING'], true)) {
            return $this->flash($request, 'Unknown status.', true);
        }
        DB::table('support_tickets')->where('id', $ticket)->update([
            'status' => $status,
            'admin_note' => mb_substr((string) $request->input('note', ''), 0, 400),
            'answered_at' => $status === 'OPEN' ? null : Billing::stamp(),
        ]);
        Billing::audit('ticket', 0, '#' . $ticket . ' → ' . $status);

        return $this->flash($request, 'Ticket marked ' . strtolower($status) . '.');
    }
}
