@extends('layouts.admin')

@section('title', 'Shops')

@section('content')
<div class="bar">
    <div>
        <h1 class="mb0">Shops</h1>
        <p class="small muted mb0">Har shop = ek billing account. Licence, seats aur plan yahin se.</p>
    </div>
    <form method="get" action="{{ route('admin.shops') }}">
        <div><label for="q">Search</label>
            <input id="q" name="q" value="{{ $q }}" placeholder="shop, username, plan"></div>
        <div><label for="status">Status</label>
            <select id="status" name="status">
                <option value="">all</option>
                @foreach(['ACTIVE','BLOCKED','SUSPENDED'] as $option)
                    <option value="{{ $option }}" @selected($status === $option)>{{ $option }}</option>
                @endforeach
            </select></div>
        <button class="btn btn-ghost" type="submit">Filter</button>
    </form>
</div>

<div class="card scroll">
    <table class="data">
        <thead><tr>
            <th>Shop</th><th>Username</th><th>Plan</th><th>Licence</th><th>Expires</th>
            <th class="right">Seats</th><th>State</th>
        </tr></thead>
        <tbody>
        @forelse($shops as $shop)
            <tr>
                <td><a href="{{ route('admin.shop', $shop->id) }}"><b>{{ $shop->name }}</b></a>
                    @if($shop->app_name)<br><span class="hint mono">{{ $shop->app_name }}</span>@endif
                    <br><span class="hint">#{{ $shop->id }} · {{ $shop->currency }}</span></td>
                <td class="small">{{ $shop->username ?? '' }}
                    <span class="hint">{{ ago($shop->last_login_at) }}</span></td>
                <td class="small">{{ $shop->plan_code ?: '—' }}</td>
                <td class="mono small">{{ $shop->license_key ?: 'no row' }}</td>
                <td class="small nowrap">{{ $shop->expires_at > 0 ? date('Y-m-d', (int) $shop->expires_at) : 'Lifetime' }}</td>
                <td class="right mono">{{ $shop->tills }}</td>
                <td>{!! state_pill((string) $shop->state) !!}</td>
            </tr>
        @empty
            <tr><td colspan="7" class="muted">Koi shop nahi mila.
                @if($q || $status)<a href="{{ route('admin.shops') }}">Reset filter</a>@endif</td></tr>
        @endforelse
        </tbody>
    </table>
</div>
<div class="pager mt1">{{ $shops->links() }}</div>
<p class="hint">Licence state wahi hai jo app ko milta hai (
    <span class="mono">ACTIVE / PENDING / EXPIRED / BLOCKED</span>) — koi dusri calculation
    panel me nahi hoti, is liye "web par kuch aur, phone par kuch aur" ka mauqa nahi.</p>
@endsection
