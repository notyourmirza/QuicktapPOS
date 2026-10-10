<?php

namespace App\Http\Controllers\Admin;

use App\Http\Controllers\Admin\Concerns\Panels;
use App\Http\Controllers\Controller;
use App\Support\Billing;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\View\View;

/**
 * The money side of the panel: the licence codes a shop redeems in the app, and the plan
 * catalogue the app renders. Prices and seats live in rows on purpose — a price change is
 * not a code change, and the in-app store cannot be argued with.
 */
class BillingController extends Controller
{
    use Panels;

    public function codes(Request $request): View
    {
        $codes = DB::table('activation_codes')->orderByDesc('id')->paginate(40)->withQueryString();
        foreach ($codes as $code) {
            $code->shop_name = $code->used_by_shop
                ? (string) (DB::table('shops')->where('id', $code->used_by_shop)->value('name') ?? '?')
                : null;
        }

        return view('admin.codes', [
            'codes' => $codes,
            'fresh' => (array) $request->session()->pull('codes.fresh', []),
            'shops' => DB::table('shops')->orderBy('name')->limit(500)->get(['id', 'name'])->map(
                static fn ($row) => (array) $row)->all(),
        ]);
    }

    /**
     * Generates codes. Several at once, because the desk hands a batch to a reseller and
     * only ever shows each one once — the panel stores them, never the marketing copy.
     */
    public function generateCode(Request $request)
    {
        $data = $request->validate([
            'days' => 'required|integer|min:1|max:3650',
            'count' => 'nullable|integer|min:1|max:50',
            'note' => 'nullable|string|max:180',
            'shop_id' => 'nullable|integer|min:1',
        ]);
        $count = (int) ($data['count'] ?? 1);
        $made = [];

        for ($i = 0; $i < $count; $i++) {
            $code = Billing::newKey();
            DB::table('activation_codes')->insert([
                'code' => $code,
                'days' => (int) $data['days'],
                'note' => (string) ($data['note'] ?? 'panel'),
                'created_at' => Billing::stamp(),
            ]);
            $made[] = $code;
        }

        // Attaching one to a shop directly is what "I already paid in cash" means; the
        // shop does not have to type anything into the app.
        $shopId = (int) ($data['shop_id'] ?? 0);
        if ($shopId > 0) {
            DB::table('activation_codes')->where('code', $made[0])->update([
                'used_at' => Billing::stamp(), 'used_by_shop' => $shopId,
            ]);
            Billing::extend($shopId, (int) $data['days'], 'panel code ' . $made[0]);
            Billing::audit('panel_extend', $shopId, $made[0] . ' · ' . $data['days'] . ' days');
        }

        $request->session()->put('codes.fresh', $made);

        return $this->flash($request, count($made) . ' code(s) ready — copy them now, they are listed with "unused" after that.');
    }

    public function plans(): View
    {
        return view('admin.plans', [
            'plans' => DB::table('plans')->orderBy('sort_order')->get()->map(static function ($row) {
                return (array) $row + ['features' => implode("\n", Billing::features($row->features ?? ''))];
            })->all(),
        ]);
    }

    public function savePlan(Request $request, int $plan)
    {
        $data = $request->validate([
            'name' => 'required|string|max:60',
            'tagline' => 'nullable|string|max:140',
            'tag' => 'nullable|string|max:32',
            'price' => 'required|numeric|min:0',
            'price_yearly' => 'nullable|numeric|min:0',
            'max_devices' => 'required|integer|min:1|max:999',
            'features' => 'nullable|string|max:4000',
        ]);
        DB::table('plans')->where('id', $plan)->update([
            'name' => $data['name'],
            'tagline' => (string) ($data['tagline'] ?? ''),
            'tag' => strtoupper((string) ($data['tag'] ?? '')),
            'price' => (float) $data['price'],
            'price_yearly' => (float) ($data['price_yearly'] ?? 0),
            'max_devices' => (int) $data['max_devices'],
            'features' => (string) ($data['features'] ?? ''),
        ]);
        Billing::audit('plan_edit', 0, 'plan #' . $plan . ' · ' . $data['name']);

        return $this->flash($request, 'Plan saved. The app shows the new price on its next plans fetch.');
    }

    public function togglePlan(Request $request, int $plan)
    {
        $row = DB::table('plans')->where('id', $plan)->first();
        if ($row === null) {
            return $this->flash($request, 'No such plan.', true);
        }
        DB::table('plans')->where('id', $plan)->update(['is_active' => (int) $row->is_active === 1 ? 0 : 1]);

        return $this->flash($request, 'Plan visibility changed.');
    }
}
