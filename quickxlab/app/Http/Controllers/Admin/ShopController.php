<?php

namespace App\Http\Controllers\Admin;

use App\Http\Controllers\Admin\Concerns\Panels;
use App\Http\Controllers\Controller;
use App\Support\Billing;
use Illuminate\Http\Request;
use Illuminate\Pagination\LengthAwarePaginator;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Hash;
use Illuminate\View\View;

/**
 * One shop = one billing account = one subscription. Every action that changes money or
 * access goes through this file, and every one of them writes an audit row with the panel
 * user in it — "who extended this shop" must always have an answer.
 */
class ShopController extends Controller
{
    use Panels;

    /** The only actions a form may post; anything else is refused without a query. */
    private const ACTIONS = ['extend', 'lifetime', 'revoke', 'block', 'unblock', 'suspend',
        'plan', 'app_name', 'password', 'delete_user', 'currency'];

    public function index(Request $request): View
    {
        $needle = trim((string) $request->query('q', ''));
        $status = strtoupper(trim((string) $request->query('status', '')));

        // `users.username` is what a shopkeeper quotes on WhatsApp, so the list joins the
        // owner's first user row in and lets the search hit it.
        $query = DB::table('shops')
            ->leftJoin('users as owner', function ($join) {
                $join->on('owner.shop_id', '=', 'shops.id')
                    ->whereRaw('owner.id = (select min(u2.id) from users u2 where u2.shop_id = shops.id)');
            })
            ->select('shops.*', 'owner.username', 'owner.last_login_at')
            ->when($needle !== '', function ($q) use ($needle) {
                $like = '%' . str_replace(['%', '_'], ['\\%', '\\_'], $needle) . '%';
                $q->where(function ($inner) use ($like) {
                    $inner->where('shops.name', 'like', $like)
                        ->orWhere('shops.app_name', 'like', $like)
                        ->orWhere('shops.plan_code', 'like', $like)
                        ->orWhere('owner.username', 'like', $like);
                });
            })
            ->when(in_array($status, ['ACTIVE', 'BLOCKED', 'SUSPENDED'], true),
                fn ($q) => $q->where('shops.status', $status));

        /** @var LengthAwarePaginator $shops */
        $shops = $query->orderByDesc('shops.id')->paginate(25)->withQueryString();

        // The newest licence per shop, in one query instead of one per row. A shop with no
        // licence row is PENDING — which is exactly what the app is told too.
        $ids = array_map(static fn ($row) => (int) $row->id, $shops->items());
        $latest = [];
        if ($ids !== []) {
            foreach (DB::table('licences')->whereIn('shop_id', $ids)->orderBy('id')->get() as $licence) {
                $latest[(int) $licence->shop_id] = $licence;
            }
        }
        foreach ($shops as $shop) {
            $licence = $latest[(int) $shop->id] ?? null;
            $shop->licence_status = $licence === null ? 'PENDING' : (string) $licence->status;
            $shop->expires_at = $licence === null ? 0 : (int) $licence->expires_at;
            $shop->license_key = $licence === null ? '' : (string) $licence->license_key;
            $shop->tills = DB::table('app_devices')->where('shop_id', $shop->id)->where('status', 'ACTIVE')->count();
            $shop->state = Billing::state((array) $shop, null, '')['state'];
        }

        return view('admin.shops', ['shops' => $shops, 'q' => $needle, 'status' => $status]);
    }

    public function show(Request $request, int $shop): View
    {
        $row = $this->find($shop);
        $shopId = (int) $row['id'];
        $dayStart = (int) (strtotime(date('Y-m-d') . ' 00:00:00') * 1000);

        return view('admin.shop', [
            'shop' => $row,
            'state' => Billing::state($row, null, ''),
            'seat' => Billing::seat($row),
            'licences' => DB::table('licences')->where('shop_id', $shopId)->orderByDesc('id')->limit(20)
                ->get()->map(fn ($r) => (array) $r)->all(),
            'users' => DB::table('users')->where('shop_id', $shopId)->orderBy('id')->get()
                ->map(static fn ($r) => (array) $r)->all(),
            'devices' => DB::table('app_devices')->where('shop_id', $shopId)->orderByDesc('last_seen_at')->get()
                ->map(static fn ($r) => (array) $r)->all(),
            'tokens' => DB::table('api_tokens')->where('shop_id', $shopId)->orderByDesc('id')->get()
                ->map(static fn ($r) => (array) $r)->all(),
            'plans' => DB::table('plans')->where('is_active', 1)->orderBy('sort_order')->get()
                ->map(static fn ($r) => (array) $r)->all(),
            'summary' => Billing::summary($row, $dayStart, $dayStart + 86399_000),
            'recent' => collect(DB::table('sync_records')->where('shop_id', $shopId)->where('entity', 'orders')
                ->whereNull('deleted_at')->orderByDesc('occurred_ms')->limit(15)->get())
                ->map(static fn ($r) => Billing::billShape(
                    (array) json_decode((string) $r->payload, true) + ['_occurred_ms' => (int) $r->occurred_ms]))
                ->values()->all(),
            'audit' => DB::table('audit_log')->where('shop_id', $shopId)->orderByDesc('id')->limit(25)
                ->get()->map(static fn ($r) => (array) $r)->all(),
        ]);
    }

    /**
     * One POST for every state change, dispatched on a whitelist. A shop's licence, block
     * state, plan, printable name and owner password are all here, and nothing outside
     * this list can be reached from a form.
     */
    public function act(Request $request, int $shop)
    {
        $row = $this->find($shop);
        $shopId = (int) $row['id'];
        $action = (string) $request->input('action', '');
        if (!isset(self::ACTIONS[$action])) {
            return $this->flash($request, 'That action is not allowed here.', true);
        }

        switch ($action) {
            case 'extend':
                $days = max(1, min(3650, (int) $request->input('days', 30)));
                Billing::extend($shopId, $days, 'panel: +' . $days . 'd by ' . ($this->admin($request)['username'] ?? '?'));
                Billing::audit('licence_extend', $shopId, '+' . $days . ' days');
                break;
            case 'lifetime':
                Billing::extend($shopId, 0, 'panel: lifetime');
                Billing::audit('licence_lifetime', $shopId, 'expires_at = 0');
                break;
            case 'revoke':
                Billing::setLicenceStatus($shopId, 'REVOKED');
                Billing::audit('licence_revoke', $shopId, 'panel');
                break;
            case 'block':
            case 'unblock':
            case 'suspend':
                $status = $action === 'unblock' ? 'ACTIVE' : strtoupper($action);
                DB::table('shops')->where('id', $shopId)->update(['status' => $status, 'updated_at' => Billing::stamp()]);
                if ($status !== 'ACTIVE') {
                    // A blocked shop must not keep a live session either.
                    foreach (DB::table('users')->where('shop_id', $shopId)->pluck('id') as $userId) {
                        Billing::revokeSessions((int) $userId);
                    }
                }
                Billing::audit('shop_' . $status, $shopId, (string) ($row['name'] ?? ''));
                break;
            case 'plan':
                $code = (string) $request->input('plan_code', '');
                if (!DB::table('plans')->where('code', $code)->exists()) {
                    return $this->flash($request, 'No such plan: ' . $code, true);
                }
                DB::table('shops')->where('id', $shopId)->update(['plan_code' => $code,
                    'updated_at' => Billing::stamp()]);
                Billing::audit('plan', $shopId, $code);
                break;
            case 'currency':
                $code = strtoupper(preg_replace('/[^A-Za-z]/', '', (string) $request->input('currency', 'PKR')));
                DB::table('shops')->where('id', $shopId)->update(['currency' => substr($code, 0, 8) ?: 'PKR',
                    'updated_at' => Billing::stamp()]);
                Billing::audit('currency', $shopId, $code);
                break;
            case 'app_name':
                $name = trim((string) $request->input('app_name', ''));
                if (mb_strlen($name) > 40) {
                    return $this->flash($request, 'The printed name is at most 40 characters.', true);
                }
                DB::table('shops')->where('id', $shopId)->update(['app_name' => $name,
                    'updated_at' => Billing::stamp()]);
                Billing::audit('app_name', $shopId, $name);
                break;
            case 'password':
                $password = (string) $request->input('password', '');
                if (strlen($password) < 8) {
                    return $this->flash($request, 'A new password needs at least 8 characters.', true);
                }
                $userId = (int) $request->input('user_id', 0);
                $user = DB::table('users')->where('shop_id', $shopId)->where('id', $userId)->first();
                if ($user === null) {
                    return $this->flash($request, 'That user does not belong to this shop.', true);
                }
                DB::table('users')->where('id', $userId)->update(['password_hash' => Hash::make($password),
                    'is_active' => 1]);
                Billing::revokeSessions($userId);
                Billing::audit('password_reset', $shopId, (string) $user->username);
                break;
            case 'delete_user':
                $userId = (int) $request->input('user_id', 0);
                // Disabled, never deleted: their bills stay readable in the shop's history.
                DB::table('users')->where('shop_id', $shopId)->where('id', $userId)->update(['is_active' => 0]);
                Billing::revokeSessions($userId);
                Billing::audit('user_disable', $shopId, '#' . $userId);
                break;
        }

        return $this->flash($request, 'Done. ' . $action . ' · shop #' . $shopId);
    }

    private function find(int $id): array
    {
        $row = DB::table('shops')->where('id', $id)->first();
        if ($row === null) {
            abort(404, 'No shop with id ' . $id . '.');
        }
        return (array) $row;
    }
}
