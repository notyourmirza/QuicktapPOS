@extends('layouts.admin')

@section('title', $shop['name'])

@php $currency = $shop['currency'] ?: 'PKR'; @endphp

@section('content')
<div class="bar">
    <div>
        <h1 class="mb0">{{ $shop['name'] }}</h1>
        <p class="small muted mb0">#{{ $shop['id'] }} · plan {{ $shop['plan_code'] ?: '—' }} ·
            {{ $shop['currency'] }} · created {{ stamp_date($shop['created_at']) }}</p>
    </div>
    <div class="row">
        <a class="btn btn-ghost btn-sm" href="{{ route('admin.bills') }}?shop_id={{ $shop['id'] }}">Bills</a>
        <a class="btn btn-ghost btn-sm" href="{{ route('admin.devices') }}?q={{ $shop['id'] }}">Devices</a>
        {!! state_pill((string) $state['state']) !!}
    </div>
</div>

<div class="kpis">
    <div class="kpi"><span>Licence</span><b>{{ days_left_label($state['days_left'] ?? null) }}</b>
        <span class="small">{{ $state['duration_label'] ?? '' }}</span></div>
    <div class="kpi"><span>Seats</span><b>{{ $seat['used'] }} / {{ $seat['limit'] }}</b>
        <span class="small">tills active</span></div>
    <div class="kpi"><span>Aaj (server)</span><b>{{ money($summary['revenue'] ?? 0, $currency) }}</b>
        <span class="small">{{ $summary['bill_count'] ?? 0 }} bills · unpaid {{ money($summary['unpaid'] ?? 0, $currency) }}</span></div>
    <div class="kpi"><span>Key</span><b class="mono" style="font-size:.95rem">{{ $state['license_key'] ?? '—' }}</b>
        <span class="small">customer ko WhatsApp karein</span></div>
</div>

<div class="grid g2 mt2" style="align-items:start">
    <div class="card">
        <h3 class="mt0">Licence</h3>
        <form method="post" action="{{ route('admin.shop.act', $shop['id']) }}" class="row">
            @csrf
            <input type="hidden" name="action" value="extend">
            <div style="flex:1 1 110px"><label for="d">Din</label>
                <input id="d" type="number" name="days" min="1" max="3650" value="30"></div>
            <button class="btn btn-primary btn-sm" type="submit">Extend</button>
            <button class="btn btn-ghost btn-sm" type="submit" form="life-{{ $shop['id'] }}">Lifetime</button>
        </form>
        <form id="life-{{ $shop['id'] }}" method="post" action="{{ route('admin.shop.act', $shop['id']) }}" class="inline-form">@csrf
            <input type="hidden" name="action" value="lifetime"></form>

        <table class="data mt1">
            <thead><tr><th>Key</th><th>Status</th><th>Expires</th><th>Note</th></tr></thead>
            <tbody>
            @foreach($licences as $row)
                <tr>
                    <td class="mono small">{{ $row['license_key'] }}</td>
                    <td>{!! state_pill((string) $row['status']) !!}</td>
                    <td class="small">{{ $row['expires_at'] > 0 ? date('Y-m-d', (int) $row['expires_at']) : 'Lifetime' }}</td>
                    <td class="small muted">{{ $row['note'] }}</td>
                </tr>
            @endforeach
            </tbody>
        </table>
        @if($state['request_status'] !== '' && !in_array($state['request_status'], ['ACTIVE','NONE'], true))
            <p class="hint mb0">App ka last request status: <b>{{ $state['request_status'] }}</b>
                @if($state['note'])· <span class="mono">{{ $state['note'] }}</span>@endif</p>
        @endif
    </div>

    <div class="card">
        <h3 class="mt0">Shop ko block / suspend</h3>
        <p class="small">Block ka matlab: app foran lock ho jata hai (grace bhi khatam), naye
            devices register nahi hote, aur sessions mar jate hain. Data delete nahi hota.</p>
        <div class="row">
            @foreach(['block' => 'Block', 'suspend' => 'Suspend (grace rahegi)', 'unblock' => 'Active karo', 'revoke' => 'Licence revoke'] as $action => $label)
                <form method="post" action="{{ route('admin.shop.act', $shop['id']) }}">@csrf
                    <input type="hidden" name="action" value="{{ $action }}">
                    <button class="btn {{ $action === 'unblock' ? 'btn-ghost' : 'btn-danger' }} btn-sm" type="submit">{{ $label }}</button>
                </form>
            @endforeach
        </div>
        <p class="hint mb0">Shop status abhi: <b>{{ $shop['status'] }}</b></p>
    </div>
</div>

<div class="grid g2 mt2" style="align-items:start">
    <div class="card">
        <h3 class="mt0">Users (app login)</h3>
        <table class="data">
            <thead><tr><th>Username</th><th>Role</th><th>Last login</th><th>Active</th><th></th></tr></thead>
            <tbody>
            @foreach($users as $user)
                <tr>
                    <td><b>{{ $user['username'] }}</b><br><span class="hint">{{ $user['full_name'] }} · {{ $user['phone'] }}</span></td>
                    <td class="small">{{ $user['role'] }}</td>
                    <td class="small">{{ ago($user['last_login_at']) }}</td>
                    <td>{!! state_pill($user['is_active'] ? 'ACTIVE' : 'REVOKED') !!}</td>
                    <td class="right">
                        <button class="btn btn-ghost btn-sm" type="submit" form="pw-{{ $user['id'] }}">Reset pw</button>
                        <button class="btn btn-danger btn-sm" type="submit" form="off-{{ $user['id'] }}">Disable</button>
                    </td>
                </tr>
                <tr><td colspan="5">
                    <form id="pw-{{ $user['id'] }}" method="post" action="{{ route('admin.shop.act', $shop['id']) }}" class="row inline-form">@csrf
                        <input type="hidden" name="action" value="password">
                        <input type="hidden" name="user_id" value="{{ $user['id'] }}">
                        <input name="password" placeholder="naya password (8+)" minlength="8" style="max-width:220px">
                        <button class="btn btn-ghost btn-sm" type="submit">Set</button>
                        <span class="hint">sessions revoke ho jate hain</span>
                    </form>
                    <form id="off-{{ $user['id'] }}" method="post" action="{{ route('admin.shop.act', $shop['id']) }}" class="inline-form">@csrf
                        <input type="hidden" name="action" value="delete_user">
                        <input type="hidden" name="user_id" value="{{ $user['id'] }}"></form>
                </td></tr>
            @endforeach
            </tbody>
        </table>
    </div>

    <div class="card">
        <h3 class="mt0">Settings</h3>
        <form method="post" action="{{ route('admin.shop.act', $shop['id']) }}" class="mb1">
            @csrf
            <input type="hidden" name="action" value="app_name">
            <label for="an">Receipt par chhapne wala naam</label>
            <div class="row"><input id="an" name="app_name" maxlength="40" value="{{ $shop['app_name'] }}"
                                    style="max-width:260px">
                <button class="btn btn-ghost btn-sm" type="submit">Save</button></div>
        </form>
        <form method="post" action="{{ route('admin.shop.act', $shop['id']) }}" class="mb1">
            @csrf
            <input type="hidden" name="action" value="plan">
            <label for="pl">Plan</label>
            <div class="row">
                <select id="pl" name="plan_code" style="max-width:220px">
                    @foreach($plans as $plan)
                        <option value="{{ $plan['code'] }}" @selected($shop['plan_code'] === $plan['code'])>
                            {{ $plan['name'] }} ({{ $plan['max_devices'] }} seats)</option>
                    @endforeach
                </select>
                <button class="btn btn-ghost btn-sm" type="submit">Apply</button>
            </div>
        </form>
        <form method="post" action="{{ route('admin.shop.act', $shop['id']) }}">
            @csrf
            <input type="hidden" name="action" value="currency">
            <label for="cu">Currency</label>
            <div class="row"><input id="cu" name="currency" maxlength="8" value="{{ $shop['currency'] }}" style="max-width:110px">
                <button class="btn btn-ghost btn-sm" type="submit">Save</button></div>
        </form>
    </div>
</div>

<div class="grid g2 mt2" style="align-items:start">
    <div class="card">
        <h3 class="mt0">Devices / seats</h3>
        @if($devices === [])
            <p class="small mb0">Abhi koi till register nahi hua.</p>
        @else
            <table class="data">
                <thead><tr><th>Device</th><th>App</th><th>Last seen</th><th>State</th><th></th></tr></thead>
                <tbody>
                @foreach($devices as $device)
                    <tr>
                        <td><b>{{ $device['device_name'] ?: 'till' }}</b><br>
                            <span class="hint mono" title="{{ $device['device_id'] }}">{{ short_id($device['device_id']) }}</span></td>
                        <td class="small">{{ $device['app_version'] }}<br><span class="hint">{{ $device['os_version'] }}</span></td>
                        <td class="small">{{ ago($device['last_seen_at']) }}</td>
                        <td>{!! state_pill((string) $device['status']) !!}</td>
                        <td class="right"><a class="btn btn-ghost btn-sm"
                                             href="{{ route('admin.devices') }}?q={{ urlencode($device['device_id']) }}">Manage</a></td>
                    </tr>
                @endforeach
                </tbody>
            </table>
        @endif
    </div>
    <div class="card">
        <h3 class="mt0">API tokens ({{ count($tokens) }})</h3>
        @if($tokens === [])
            <p class="small mb0">Is shop ka koi token nahi.
                <a href="{{ route('admin.tokens') }}">Create one</a> — website ya desktop counter ke liye.</p>
        @else
            <table class="data">
                <thead><tr><th>Label</th><th>Scope</th><th>Last used</th><th>State</th></tr></thead>
                <tbody>
                @foreach($tokens as $token)
                    <tr>
                        <td class="small">{{ $token['label'] }} <span class="hint mono">{{ $token['token_prefix'] }}…</span></td>
                        <td class="small">{{ $token['scopes'] }}</td>
                        <td class="small">{{ ago($token['last_used_at']) }}</td>
                        <td>{!! state_pill(empty($token['revoked_at']) ? 'ACTIVE' : 'REVOKED') !!}</td>
                    </tr>
                @endforeach
                </tbody>
            </table>
        @endif
    </div>
</div>

<div class="card mt1">
    <h3 class="mt0">Aakhri {{ count($recent) }} bills (server copy)</h3>
    <table class="data">
        <thead><tr><th>Invoice</th><th>When</th><th>Type</th><th>Payment</th><th class="right">Total</th><th>Status</th></tr></thead>
        <tbody>
        @forelse($recent as $bill)
            <tr>
                <td class="mono small">{{ $bill['invoice_no'] ?: short_id($bill['client_ref'], 10, 4) }}</td>
                <td class="small">{{ $bill['date'] }} {{ substr($bill['created_at'], 11, 5) }}</td>
                <td class="small">{{ $bill['order_type'] }}</td>
                <td class="small">{{ $bill['payment_method'] }}</td>
                <td class="right mono">{{ money($bill['total'], $currency) }}</td>
                <td>{!! state_pill($bill['status'] === 'paid' ? 'PAID' : 'DUE') !!}</td>
            </tr>
        @empty
            <tr><td colspan="6" class="muted">Abhi koi bill sync nahi hua.</td></tr>
        @endforelse
        </tbody>
    </table>
</div>

<div class="card mt1">
    <h3 class="mt0">Is shop ki history</h3>
    <table class="data">
        <tbody>
        @forelse($audit as $row)
            <tr>
                <td class="small mono">{{ $row['action'] }}</td>
                <td class="small">{{ $row['detail'] }}</td>
                <td class="small muted">{{ $row['actor'] }}</td>
                <td class="right small nowrap">{{ stamp_date($row['created_at']) }}</td>
            </tr>
        @empty
            <tr><td class="muted">Kuch record nahi.</td></tr>
        @endforelse
        </tbody>
    </table>
</div>
@endsection
