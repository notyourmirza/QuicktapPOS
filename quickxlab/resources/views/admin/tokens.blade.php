@extends('layouts.admin')

@section('title', 'API tokens')

@section('content')
<div class="bar">
    <div>
        <h1 class="mb0">API tokens</h1>
        <p class="small muted mb0">Website, kiosk page, desktop counter ya accounting script —
            sab ke liye token. Har token ek shop se bandha hai; scopes usi ke andar ka
            authority tay karte hain.</p>
    </div>
</div>

@if($fresh)
    <div class="flash ok">Token (sirf ek baar): <span class="mono copy" data-copy="{{ $fresh }}">{{ $fresh }}</span>
        <br><span class="hint">Base URL: <span class="mono">{{ $base }}</span> · header
            <span class="mono">Authorization: Bearer …</span></span></div>
@endif

<div class="card">
    <form method="post" action="{{ route('admin.tokens.issue') }}" class="row">
        @csrf
        <div style="flex:1 1 200px"><label for="shop">Shop</label>
            <select id="shop" name="shop_id" required>
                <option value="">— chunein —</option>
                @foreach($shops as $option)
                    <option value="{{ $option['id'] }}">{{ $option['name'] }} (#{{ $option['id'] }})</option>
                @endforeach
            </select></div>
        <div style="flex:1 1 180px"><label for="label">Label</label>
            <input id="label" name="label" required maxlength="60" placeholder="website counter"></div>
        <div style="flex:0 1 150px"><label>Scope</label>
            <div class="checks">
                <label><input type="checkbox" name="scopes[]" value="read" checked> read</label>
                <label><input type="checkbox" name="scopes[]" value="write"> write</label>
            </div></div>
        <div style="flex:0 1 120px"><label for="days">Din (0 = hamesha)</label>
            <input id="days" type="number" name="days" min="0" max="3650" value="365"></div>
        <button class="btn btn-primary" type="submit" style="align-self:end">Create token</button>
    </form>
    <p class="hint mb0">Server par sirf SHA-256 hash rehta hai — kho jane par naya banayein,
        purana revoke kar dein. CLI se bhi ban jata hai:
        <span class="mono">php artisan api:token 3 --label=kiosk --scopes=read,write --days=30</span></p>
</div>

<div class="card scroll mt2">
    <table class="data">
        <thead><tr><th>Shop</th><th>Label</th><th>Prefix</th><th>Scope</th><th>Created</th>
            <th>Last used</th><th>Expires</th><th>State</th><th></th></tr></thead>
        <tbody>
        @forelse($tokens as $token)
            <tr>
                <td class="small"><a href="{{ route('admin.shop', $token->shop_id) }}">{{ $token->shop_name ?: '#' . $token->shop_id }}</a></td>
                <td class="small">{{ $token->label ?: 'token' }}</td>
                <td class="mono small">{{ $token->token_prefix }}…</td>
                <td class="small mono">{{ $token->scopes }}</td>
                <td class="small">{{ stamp_date($token->created_at) }}</td>
                <td class="small nowrap">{{ ago($token->last_used_at) }}</td>
                <td class="small nowrap">{{ $token->expires_at > 0 ? date('Y-m-d', (int) $token->expires_at) : 'never' }}</td>
                <td>{!! state_pill((string) $token->state) !!}</td>
                <td class="right">@if(empty($token->revoked_at))
                    <form method="post" action="{{ route('admin.tokens.revoke', $token->id) }}" class="inline-form">@csrf
                        <button class="btn btn-danger btn-sm" type="submit">Revoke</button></form>
                @endif</td>
            </tr>
        @empty
            <tr><td colspan="9" class="muted">Abhi koi token nahi.</td></tr>
        @endforelse
        </tbody>
    </table>
</div>
<div class="pager mt1">{{ $tokens->links() }}</div>
<p class="hint">Token wale endpoints: <span class="mono">v1/shop · v1/catalog* · v1/bills* ·
    v1/reports/*</span>. Sync aur licence endpoints sirf app ke JWT se chalte hain —
    website se koi till ki seat release nahi kar sakta.</p>
@endsection
