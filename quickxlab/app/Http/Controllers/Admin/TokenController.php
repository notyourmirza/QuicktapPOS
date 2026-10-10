<?php

namespace App\Http\Controllers\Admin;

use App\Http\Controllers\Admin\Concerns\Panels;
use App\Http\Controllers\Controller;
use App\Support\Billing;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\View\View;

/**
 * Tokens for everything that is not the Android app: a shop's website, a kiosk page, a
 * desktop counter, an accounting script. One token = one shop = one program, shown once,
 * hashed at rest, scoped so a public price list gets a token that cannot bill.
 */
class TokenController extends Controller
{
    use Panels;

    public function index(Request $request): View
    {
        $tokens = DB::table('api_tokens')
            ->leftJoin('shops', 'shops.id', '=', 'api_tokens.shop_id')
            ->select('api_tokens.*', 'shops.name as shop_name')
            ->orderByRaw('CASE WHEN api_tokens.revoked_at IS NULL THEN 0 ELSE 1 END')
            ->orderByDesc('api_tokens.id')->paginate(40)->withQueryString();
        foreach ($tokens as $token) {
            $token->state = !empty($token->revoked_at) ? 'REVOKED'
                : ((int) $token->expires_at > 0 && (int) $token->expires_at < time() ? 'EXPIRED' : 'ACTIVE');
        }

        return view('admin.tokens', [
            'tokens' => $tokens,
            'fresh' => (string) $request->session()->pull('token.plain', ''),
            'shops' => DB::table('shops')->orderBy('name')->limit(500)->get(['id', 'name'])
                ->map(static fn ($row) => (array) $row)->all(),
            'base' => rtrim((string) (Billing::setting('api_base_url')
                ?: config('billing.api_base_url') ?: url('/api')), '/') . '/',
        ]);
    }

    public function issue(Request $request)
    {
        $data = $request->validate([
            'shop_id' => 'required|integer|min:1',
            'label' => 'required|string|max:60',
            'scopes' => 'required|array',
            'scopes.*' => 'in:read,write',
            'days' => 'nullable|integer|min:0|max:3650',
        ]);
        if (!DB::table('shops')->where('id', $data['shop_id'])->exists()) {
            return $this->flash($request, 'Pick the shop the token may read (or write).', true);
        }
        $issued = Billing::issueToken((int) $data['shop_id'], (string) $data['label'],
            array_values(array_unique((array) $data['scopes'])), (int) ($data['days'] ?? 0));
        $request->session()->put('token.plain', $issued['token']);
        Billing::audit('panel_token', (int) $data['shop_id'],
            $data['label'] . ' (' . implode('+', $data['scopes']) . ')');

        return $this->flash($request, 'Token created — copy it now, only its hash is kept from here on.');
    }

    public function revoke(Request $request, int $token)
    {
        $row = DB::table('api_tokens')->where('id', $token)->first();
        if ($row === null) {
            return $this->flash($request, 'No such token.', true);
        }
        $gone = Billing::revokeToken($token, (int) $row->shop_id);
        Billing::audit('panel_token_revoke', (int) $row->shop_id, (string) $row->label);

        return $this->flash($request, $gone
            ? 'Token revoked — that program stops being accepted on its very next request.'
            : 'That token was already revoked.');
    }
}
