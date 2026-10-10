<?php

namespace App\Http\Controllers;

use App\Support\Billing;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\View\View;

/**
 * The public site — the page a shopkeeper lands on before they have an account.
 *
 * It is deliberately not a static brochure: the plans, the support number, the app
 * version offered for download and the sign-up form all read and write the same tables
 * the panel edits, so "update the price" is a panel click and not a redeploy.
 */
class SiteController extends Controller
{
    public function home(Request $request): View
    {
        return view('site.home', $this->shared($request) + [
            'stats' => $this->stats(),
            'plans' => $this->plans(),
        ]);
    }

    public function plans(Request $request): View
    {
        return view('site.plans', $this->shared($request) + [
            'plans' => $this->plans(),
            'faq' => [
                ['Q' => 'Internet na ho to billing rukegi?',
                 'A' => 'Nahi. Till offline apni copy par billing karta rehta hai; sync aap aate hi chala jata hai.'],
                ['Q' => 'Licence expire ho gaya?',
                 'A' => 'Receipts chalte rehte hain (offline grace), magar naye bills server par tab tak nahi jaate jab tak aap panel se licence na barhayein.'],
                ['Q' => 'Kitni counters ek hi shop par?',
                 'A' => 'Jitne aap ke plan me seats hain. Har counter apna device record rakhta hai, aur seat aap khud release kar sakte hain.'],
                ['Q' => 'Mera data kahan hai?',
                 'A' => 'Aap ki phone me, aur Drive backup me (encrypted). Third-party ko kuch nahi milta.'],
            ],
        ]);
    }

    public function software(Request $request): View
    {
        $slug = (string) $request->query('slug', '');
        $page = $slug === '' ? null : DB::table('site_pages')->where('slug', $slug)
            ->where('is_active', 1)->first();
        if ($page === null) {
            $page = DB::table('site_pages')->where('is_active', 1)->orderBy('sort_order')->first();
        }
        abort_if($page === null, 404, 'Yeh page mojood nahi.');

        return view('site.software', $this->shared($request) + ['page' => (array) $page]);
    }

    /** The APK list: what the admin uploaded, straight from the same folder. */
    public function download(Request $request): View
    {
        $settings = Billing::settings();
        $file = (string) ($settings['apk_file'] ?? '');
        $path = $file !== '' ? public_path('apk/' . $file) : null;

        return view('site.download', $this->shared($request) + [
            'version' => (string) ($settings['apk_version_name'] ?? '—'),
            'code' => (string) ($settings['apk_version_code'] ?? ''),
            'notes' => (string) ($settings['apk_notes'] ?? ''),
            'file' => $file,
            'size' => $path && is_file($path) ? round(filesize($path) / 1048576, 1) : null,
            'built' => $path && is_file($path) ? date('Y-m-d', (int) filemtime($path)) : null,
            'steps' => [
                'APK download karein (Play Store ki zaroorat nahi).',
                'Install ke liye "Unknown sources" allow karein.',
                'Phone number / username se sign in karein — licence server deta hai.',
            ],
        ]);
    }

    /**
     * /privacy and /terms. A `site_pages` row with the same slug wins, so legal copy can be
     * updated in the panel; the text below is what the software actually does today, which is
     * the minimum a shopkeeper should be able to read before installing an APK.
     */
    public function legal(Request $request, string $slug): View
    {
        $slug = $slug === 'terms' ? 'terms' : 'privacy';
        $row = DB::table('site_pages')->where('slug', $slug)->where('is_active', 1)->first();
        $defaults = [
            'privacy' => [
                'title' => 'Privacy — what the server keeps',
                'points' => [
                    'Dukan ka data aap ke phone ke SQLite database me rehta hai; server par sirf wahi jata hai jo sync hota hai (bills, products, categories).',
                    'Licence ke liye: shop ka naam, username/phone, device id, app version aur last-seen time. Isse zyada kuch nahi.',
                    'Passwords bcrypt hashes ke tor par rehte hain; API tokens sha256 hash — plaintext ek hi baar dikhta hai.',
                    'Drive backup aap ke apne Google account ke appData folder me, device-keystore/passphrase se encrypt hoti hai. Humaare server par backup copy nahi hoti.',
                    'Koi ad SDK, koi tracker, koi third-party analytics nahi. Support WhatsApp par aap khud bhejte hain; uska record hum support ke liye rakhte hain.',
                    'Data delete karwana ho to /support par likhein — shop, uske bills aur device rows hata diye jate hain.',
                ],
            ],
            'terms' => [
                'title' => 'Terms — software ka use',
                'points' => [
                    'Licence per shop hai, aur jitne plan me seats hain utne tills. Seat release karna aap ke haath me hai.',
                    'Trial ke baad licence na lein to bills print hona band nahi hote (offline grace), magar sync aur naye devices band ho jate hain.',
                    'Licence codes share na karein; ek code ek hi dukan lagati hai. Galat code se licence kharab nahi hota — wapas pending ho jata hai.',
                    'Software “as is” hai. Billing record aap ki zimmedari — har mahine Drive backup check karein, aur tax papers ke liye apni copies rakhein.',
                    'Refund: jo licence time abhi use nahi hua, us par 7 din me refund mil sakta hai; support se baat karein.',
                    'Prices badal sakte hain; aap ke chalte hue plan ki price aap ke renewal tak wahi rehti hai.',
                ],
            ],
        ];
        if ($row !== null) {
            $points = array_values(array_filter(array_map('trim',
                explode("\n", (string) $row->body)), static fn ($line) => $line !== ''));
            $content = ['title' => (string) $row->title, 'points' => $points];
        } else {
            $content = $defaults[$slug];
        }

        return view('site.legal', $this->shared($request) + [
            'slug' => $slug,
            'title' => $content['title'],
            'points' => $content['points'],
            'updated' => $row === null ? null : stamp_date((string) $row->updated_at),
        ]);
    }

    public function support(Request $request): View
    {
        return view('site.support', $this->shared($request) + [
            'done' => $request->session()->pull('ticket.sent'),
        ]);
    }

    /** A ticket lands in the panel's Support queue — the desk answers it on WhatsApp. */
    public function sendTicket(Request $request)
    {
        $data = $request->validate([
            'name' => 'required|string|max:80',
            'phone' => 'required|string|max:24',
            'shop' => 'nullable|string|max:80',
            'topic' => 'required|string|max:40',
            'message' => 'required|string|max:2000',
        ]);
        DB::table('support_tickets')->insert([
            'name' => $data['name'],
            'phone' => preg_replace('/[^0-9+]/', '', $data['phone']),
            'shop' => (string) ($data['shop'] ?? ''),
            'topic' => $data['topic'],
            'message' => $data['message'],
            'status' => 'OPEN',
            'created_at' => Billing::stamp(),
        ]);
        $request->session()->put('ticket.sent', true);

        return redirect()->route('support');
    }

    public function showSignup(Request $request): View
    {
        // /signup?plan=retail from a price card — remembered for the shop row, so the plan
        // a person clicked is the plan they land on.
        if ($plan = $request->query('plan')) {
            $request->session()->put('ref_plan', substr((string) $plan, 0, 32));
        }

        return view('site.signup', $this->shared($request) + [
            'ref' => (string) $request->session()->get('ref', $request->query('ref', '')),
        ]);
    }

    /**
     * Sign-up on the site creates exactly what the APK's register endpoint creates — one
     * code path (Billing::createShop) so a shop can sign in on the phone immediately.
     */
    public function signup(Request $request)
    {
        $data = $request->validate([
            'shop_name' => 'required|string|min:3|max:60',
            'full_name' => 'nullable|string|max:80',
            'phone' => 'required|string|max:24',
            'username' => ['required', 'string', 'min:4', 'max:32',
                'regex:/^[a-z0-9._-]+$/'],
            'password' => 'required|string|min:8|max:200|confirmed',
        ], [], [
            'shop_name' => 'shop name',
            'full_name' => 'aap ka naam',
            'username' => 'username',
            'password' => 'password',
        ]);

        $username = strtolower($data['username']);
        if (DB::table('users')->where('username', $username)->exists()) {
            return back()->withInput()->withErrors(['username' => 'Yeh username pehle se liya gaya hai.']);
        }

        $created = Billing::createShop($data['shop_name'], $username, $data['password'],
            (string) ($data['full_name'] ?? ''), preg_replace('/[^0-9+]/', '', $data['phone']),
            (string) $request->session()->get('ref_plan', ''), 'site signup');

        $request->session()->put('shop_user_id', (int) $created['user_id']);
        $request->session()->put('shop_id', (int) $created['shop_id']);
        Billing::audit('signup_site', (int) $created['shop_id'], $username);

        return redirect()->route('account')->with('flash.ok',
            'Account ban gaya. Ab phone me isi username se sign in karein.');
    }

    public function refer(Request $request, string $code)
    {
        $request->session()->put('ref', strtoupper(substr($code, 0, 24)));

        return redirect()->route('home');
    }

    /**
     * The developer page: what a website or a desktop program sends. Rendered from the
     * live route list, so it can never claim an endpoint that does not exist.
     */
    public function developers(Request $request): View
    {
        // Rendered from the live route table, so the page can never advertise an endpoint
        // this build does not have.
        $routes = collect(\Illuminate\Support\Facades\Route::getRoutes()->getRoutes())
            ->filter(fn ($route) => str_starts_with($route->uri(), 'api/v1/'))
            ->map(fn ($route) => [
                'method' => implode('|', array_diff($route->methods(), ['HEAD'])),
                'path' => substr($route->uri(), 4),
                'token' => in_array('api.level:shop', $route->gatherMiddleware(), true),
            ])
            ->sortBy('path')->values()->all();

        $base = rtrim((string) (Billing::setting('api_base_url') ?: config('billing.api_base_url')
            ?: url('/api')), '/') . '/';

        return view('site.developers', $this->shared($request) + [
            'routes' => $routes,
            'base' => $base,
            'samples' => $this->apiSamples($base),
        ]);
    }

    /**
     * The copy-paste blocks. Assembled here rather than inside Blade so the snippets are
     * plain strings — `{{ }}` inside a heredoc would be a template inside a template, and
     * nobody should have to reason about that at 1 a.m.
     */
    private function apiSamples(string $base): array
    {
        $curl = 'curl -H "Authorization: Bearer qt_pat_..." ' . $base . 'v1/shop';
        $post = "curl -X POST " . $base . "v1/bills \\\n"
            . "  -H 'Authorization: Bearer qt_pat_...' \\\n"
            . "  -H 'Content-Type: application/json' \\\n"
            . '  -d \'{"client_ref":"web-101","order_type":"sale","status":"paid",'
            . '"items":[{"product_ref":"7","name":"Rice 5kg","qty":1,"price":1450}]}\'';

        $js = "const base = '" . $base . "';\n"
            . "const token = 'qt_pat_...';   // from your shop dashboard\n\n"
            . "const api = (path, opt = {}) => fetch(base + path, {\n"
            . "  ...opt,\n"
            . "  headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },\n"
            . "  body: opt.body ? JSON.stringify(opt.body) : undefined,\n"
            . "}).then((r) => r.json());\n\n"
            . "const res = await api('v1/bills?from=today&to=today');\n"
            . "if (res.success) {\n"
            . "  res.data.bills.forEach((b) => console.log(b.invoice_no, b.total, b.status));\n"
            . "}\n\n"
            . "// write: needs a read+write token\n"
            . "// await api('v1/bills', { method: 'POST', body: {\n"
            . "//   client_ref: 'web-101', order_type: 'sale', status: 'paid',\n"
            . "//   items: [{ product_ref: '7', name: 'Rice 5kg', qty: 1, price: 1450 }],\n"
            . "// });";

        $py = "from quicktap_api import QuickTap        # billing-api/client/quicktap-api.py\n\n"
            . "qt = QuickTap('" . $base . "', token='qt_pat_...')\n\n"
            . "for bill in qt.bills('2026-10-10'):\n"
            . "    print(bill['invoice_no'], bill['total'], bill['status'])\n\n"
            . "qt.save_bill({\n"
            . "    'client_ref': 'pc-1',\n"
            . "    'order_type': 'sale',\n"
            . "    'status': 'paid',\n"
            . "    'items': [{'product_ref': '7', 'name': 'Rice 5kg', 'qty': 1, 'price': 1450}],\n"
            . "})";

        return ['curl' => $curl . "\n\n" . $post, 'js' => $js, 'py' => $py];
    }

    // ------------------------------------------------------------------ shared

    private function shared(Request $request): array
    {
        $settings = Billing::settings();
        $whatsapp = preg_replace('/\D+/', '', (string) ($settings['support_whatsapp'] ?? ''));

        return [
            'appName' => (string) ($settings['app_name'] ?? config('app.name')),
            'logo' => (string) ($settings['logo_url'] ?? ''),
            'whatsapp' => $whatsapp,
            'waMessage' => rawurlencode('Salam, QuickTap POS ke baare me poochna tha.'),
            'ref' => (string) $request->session()->get('ref', ''),
            'notices' => DB::table('notifications')->where('is_active', 1)
                ->where(fn ($q) => $q->whereNull('ends_at')->orWhere('ends_at', '>=', Billing::stamp()))
                ->orderByDesc('id')->limit(2)->get()->map(fn ($row) => (array) $row)->all(),
        ];
    }

    private function plans(): array
    {
        $rows = DB::table('plans')->where('is_active', 1)->orderBy('sort_order')->orderBy('id')->get();
        return array_map(static fn ($row) => [
            'code' => (string) $row->code,
            'name' => (string) $row->name,
            'tagline' => (string) $row->tagline,
            'tag' => (string) $row->tag,
            'price' => (float) $row->price,
            'yearly' => (float) $row->price_yearly,
            'devices' => (int) $row->max_devices,
            'features' => Billing::features($row->features ?? ''),
        ], $rows->all());
    }

    /**
     * Real numbers from the shop table — no invented "10k+ shops" counter. The home page's
     * live terminal ticks over today's totals, so a slow day has to look like one.
     */
    private function stats(): array
    {
        $dayStart = (int) (strtotime(date('Y-m-d') . ' 00:00:00') * 1000);
        $dayEnd = (int) (strtotime(date('Y-m-d') . ' 23:59:59') * 1000 + 999);
        $today = ['bills' => 0, 'revenue' => 0.0];
        try {
            $rows = DB::table('sync_records')->where('entity', 'orders')->whereNull('deleted_at')
                ->whereBetween('occurred_ms', [$dayStart, $dayEnd])->limit(3000)->get();
            foreach ($rows as $row) {
                $payload = json_decode((string) $row->payload, true);
                if (!is_array($payload)) {
                    continue;
                }
                $today['bills']++;
                $today['revenue'] += (float) ($payload['total'] ?? 0);
            }
        } catch (\Throwable) {
            // No sync table yet (a fresh host): the page still renders, the terminal shows 0.
        }

        return [
            'today' => $today,
            'shops' => DB::table('shops')->count(),
            'active' => DB::table('licences')->where('status', 'ACTIVE')->where('expires_at', '>', time())->count(),
            'tills' => DB::table('app_devices')->where('status', 'ACTIVE')->count(),
            'bills' => DB::table('sync_records')->where('entity', 'orders')->whereNull('deleted_at')->count(),
        ];
    }
}
