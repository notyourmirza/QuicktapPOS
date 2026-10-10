<?php

namespace App\Http\Controllers;

use App\Support\Billing;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Hash;
use Illuminate\View\View;

/**
 * A shop owner's own screen: what their licence says, which tills hold a seat, today's
 * total, and the token their website or desktop program should use.
 *
 * This exists so support questions ("meri license date kya hai?", "API token bana do")
 * answer themselves. The panel keeps the authority: a shop can read and extend nothing
 * here, only name itself and manage its own tokens.
 */
class AccountController extends Controller
{
    public function showLogin(Request $request): View
    {
        return view('account.login', ['username' => old('username', '')]);
    }

    public function login(Request $request)
    {
        $credentials = $request->validate([
            'username' => 'required|string|max:32',
            'password' => 'required|string',
        ]);
        $username = strtolower(trim($credentials['username']));
        $row = DB::table('users')->where('username', $username)->first();
        $user = $row === null ? null : (array) $row;

        if ($user === null || !Hash::check($credentials['password'], (string) $user['password_hash'])) {
            return back()->withInput()->withErrors(['username' => 'Username ya password theek nahi.']);
        }
        if ((int) $user['is_active'] !== 1) {
            return back()->withInput()->withErrors(['username' => 'Yeh account band hai — support se baat karein.']);
        }

        $request->session()->put('shop_user_id', (int) $user['id']);
        $request->session()->put('shop_id', (int) $user['shop_id']);
        DB::table('users')->where('id', (int) $user['id'])->update(['last_login_at' => Billing::stamp()]);

        return redirect()->route('account');
    }

    public function logout(Request $request)
    {
        $request->session()->forget(['shop_user_id', 'shop_id', 'token.once']);

        return redirect()->route('home')->with('flash.ok', 'Logout ho gaya.');
    }

    public function dashboard(Request $request): View
    {
        $shop = $this->shop($request);
        $user = $this->user($request);
        $shopId = (int) $shop['id'];
        $today = [(int) (strtotime(date('Y-m-d') . ' 00:00:00') * 1000),
                  (int) (strtotime(date('Y-m-d') . ' 23:59:59') * 1000 + 999)];

        $devices = DB::table('app_devices')->where('shop_id', $shopId)
            ->orderByDesc('last_seen_at')->get()->map(fn ($row) => (array) $row)->all();
        $tokens = DB::table('api_tokens')->where('shop_id', $shopId)
            ->orderByDesc('id')->limit(20)->get()->map(fn ($row) => (array) $row)->all();
        $bills = DB::table('sync_records')->where('shop_id', $shopId)->where('entity', 'orders')
            ->whereNull('deleted_at')->orderByDesc('occurred_ms')->limit(12)->get()
            ->map(static fn ($row) => Billing::billShape(
                (array) json_decode((string) $row->payload, true) + ['_occurred_ms' => (int) $row->occurred_ms]))->all();

        return view('account.dashboard', [
            'shop' => $shop,
            'user' => $user,
            'state' => Billing::state($shop, $user, ''),
            'seat' => Billing::seat($shop),
            'devices' => $devices,
            'tokens' => $tokens,
            'once' => (string) $request->session()->pull('token.once', ''),
            'today' => Billing::summary($shop, $today[0], $today[1]),
            'bills' => $bills,
            'products' => DB::table('sync_records')->where('shop_id', $shopId)->where('entity', 'products')->count(),
            'whatsapp' => (string) Billing::setting('support_whatsapp', ''),
            'appName' => (string) Billing::setting('app_name', config('app.name')),
        ]);
    }

    /** A shop hands its own website a token; scopes and expiry stay the shop's business. */
    public function issueToken(Request $request)
    {
        $shop = $this->shop($request);
        $request->validate([
            'label' => 'required|string|max:60',
            'scopes' => 'required|array',
            'scopes.*' => 'in:read,write',
            'days' => 'nullable|integer|min:0|max:3650',
        ]);
        $issued = Billing::issueToken((int) $shop['id'], (string) $request->input('label'),
            array_values(array_unique((array) $request->input('scopes'))),
            (int) $request->input('days', 0));
        $request->session()->put('token.once', $issued['token']);
        Billing::audit('token_issue', (int) $shop['id'], (string) $request->input('label'),
            (string) ($shop['name'] ?? ''));

        return redirect()->route('account');
    }

    public function revokeToken(Request $request)
    {
        $shop = $this->shop($request);
        $id = (int) $request->input('token_id');
        $gone = Billing::revokeToken($id, (int) $shop['id']);
        Billing::audit('token_revoke', (int) $shop['id'], '#' . $id . ($gone ? '' : ' (not found)'),
            (string) ($shop['name'] ?? ''));

        return redirect()->route('account');
    }

    /** The name a shop prints on its own receipts — the till's /v1/settings/app-name. */
    public function rename(Request $request)
    {
        $shop = $this->shop($request);
        $name = trim((string) $request->input('app_name', ''));
        if (mb_strlen($name) < 3 || mb_strlen($name) > 40) {
            return back()->with('flash.bad', 'Naam 3 se 40 characters ka hona chahiye.');
        }
        DB::table('shops')->where('id', (int) $shop['id'])->update([
            'app_name' => $name, 'updated_at' => Billing::stamp(),
        ]);

        return back()->with('flash.ok', 'Naam mehfooz ho gaya — phone agli sync par utha lega.');
    }

    /**
     * "Phone gum ho gaya" without a support ticket: the shop releases its own seat, and the
     * stale device stops holding one. The till itself stays untouched — its data is on the
     * phone, not here — but its sessions die, so nobody else can bill as that counter.
     */
    public function releaseDevice(Request $request)
    {
        $shop = $this->shop($request);
        $deviceId = trim((string) $request->input('device_id', ''));
        $row = DB::table('app_devices')->where('shop_id', (int) $shop['id'])
            ->where('device_id', $deviceId)->first();
        if ($row === null) {
            return back()->with('flash.bad', 'Yeh device is dukan ke naam par nahi hai.');
        }
        Billing::releaseDevice((int) $shop['id'], $deviceId);
        DB::table('refresh_tokens')->where('device_id', $deviceId)->whereNull('revoked_at')
            ->update(['revoked_at' => Billing::stamp()]);
        Billing::audit('shop_device_release', (int) $shop['id'], $deviceId,
            (string) ($this->user($request)['username'] ?? ''));

        return back()->with('flash.ok', 'Seat free ho gayi — naye phone par ab login ho jayega.');
    }

    private function user(Request $request): array
    {
        $row = DB::table('users')->where('id', (int) $request->session()->get('shop_user_id'))->first();

        return $row === null ? [] : (array) $row;
    }

    private function shop(Request $request): array
    {
        $shop = Billing::shop((int) $request->session()->get('shop_id'));
        if ($shop === null) {
            $request->session()->forget(['shop_user_id', 'shop_id']);
            // abort() with a Response keeps the caller's redirect typed as an array return.
            abort(redirect()->route('account.login'));
        }
        return $shop;
    }
}
