@extends('layouts.admin')

@section('title', 'Devices & seats')

@section('content')
<div class="bar">
    <div>
        <h1 class="mb0">Devices &amp; seats</h1>
        <p class="small muted mb0">Ek device = ek seat. Release ka matlab: seat khali, us
            till ke sessions khatam.</p>
    </div>
    <form method="get" action="{{ route('admin.devices') }}">
        <div><label for="q">Search</label>
            <input id="q" name="q" value="{{ $q }}" placeholder="device id, till name, shop"></div>
        <div><label for="status">Status</label>
            <select id="status" name="status">
                <option value="">all ({{ $counts['ACTIVE'] + $counts['RELEASED'] + $counts['BLOCKED'] }})</option>
                @foreach(['ACTIVE','RELEASED','BLOCKED'] as $option)
                    <option value="{{ $option }}" @selected($status === $option)>{{ $option }} ({{ $counts[$option] }})</option>
                @endforeach
            </select></div>
        <button class="btn btn-ghost" type="submit">Filter</button>
    </form>
</div>

<div class="card scroll">
    <table class="data">
        <thead><tr><th>Shop</th><th>Device</th><th>App / OS</th><th>Last seen</th><th>Seen</th><th>State</th><th></th></tr></thead>
        <tbody>
        @forelse($devices as $device)
            <tr>
                <td><a href="{{ route('admin.shop', $device->shop_id) }}">{{ $device->shop_name ?: '#' . $device->shop_id }}</a>
                    <br><span class="hint">{{ $device->plan_code ?: 'no plan' }}</span></td>
                <td><b>{{ $device->device_name ?: 'till' }}</b>
                    <br><span class="hint mono copy" data-copy="{{ $device->device_id }}"
                          title="{{ $device->device_id }}">{{ short_id($device->device_id, 12, 6) }}</span></td>
                <td class="small">{{ $device->app_version ?: '—' }}<br>
                    <span class="hint">{{ $device->os_version ?: 'android' }}</span></td>
                <td class="small nowrap">{{ ago($device->last_seen_at) }}
                    <br><span class="hint">{{ stamp_date($device->last_seen_at) }}</span></td>
                <td class="mono small">{{ $device->seen_count }}</td>
                <td>{!! state_pill((string) $device->status) !!}</td>
                <td class="right nowrap">
                    @if($device->status === 'ACTIVE')
                        <form method="post" action="{{ route('admin.device.act', $device->id) }}" class="inline-form">@csrf
                            <input type="hidden" name="action" value="free">
                            <button class="btn btn-ghost btn-sm" type="submit">Free seat</button>
                        </form>
                        <form method="post" action="{{ route('admin.device.act', $device->id) }}" class="inline-form">@csrf
                            <input type="hidden" name="action" value="block">
                            <button class="btn btn-danger btn-sm" type="submit">Block</button>
                        </form>
                    @else
                        <form method="post" action="{{ route('admin.device.act', $device->id) }}" class="inline-form">@csrf
                            <input type="hidden" name="action" value="unblock">
                            <button class="btn btn-ghost btn-sm" type="submit">Make active</button>
                        </form>
                    @endif
                </td>
            </tr>
        @empty
            <tr><td colspan="7" class="muted">Koi device nahi mila. Till pehli baar login kare
                to yahan aa jata hai.</td></tr>
        @endforelse
        </tbody>
    </table>
</div>
<div class="pager mt1">{{ $devices->links() }}</div>
<p class="hint">Release ke baad shop ka agla login (ya till ka refresh) seat le leta hai —
    "phone gum" ka masla 30 second me halak ho jata hai, support ticket ke bagair.
    Seat count ka rule wahi hai jo app ko /v1/license/status me milta hai.</p>
@endsection
