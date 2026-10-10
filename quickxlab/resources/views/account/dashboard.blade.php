@extends('layouts.site')

@section('title', 'My account — ' . ($shop['name'] ?? 'QuickTap POS'))

@php $currency = $shop['currency'] ?: 'PKR'; @endphp

@section('content')
<section class="section">
    <div class="wrap">
        <div class="row spread">
            <div>
                <h1 class="mt0">{{ $shop['name'] }}</h1>
                <p class="mb0">{{ $user['full_name'] ?: $user['username'] }} ·
                    <span class="chip">{{ strtoupper((string) $state['state']) }}</span>
                    @if($shop['app_name'])· till par: <b>{{ $shop['app_name'] }}</b>@endif
                </p>
            </div>
            <div class="row">
                <a class="btn btn-ghost btn-sm" href="{{ route('plans') }}">Plan badalna</a>
                <form method="post" action="{{ route('account.logout') }}">@csrf
                    <button class="btn btn-ghost btn-sm" type="submit">Sign out</button></form>
            </div>
        </div>

        @if($once)
            <div class="flash ok mt1">Naya token (sirf ek baar dikhta hai):
                <span class="copy mono" data-copy="{{ $once }}">{{ $once }}</span>
                — abhi copy karein. Iske baad server par sirf hash rehta hai.</div>
        @endif

        <div class="grid g4 mt2">
            <div class="kpi"><span>Licence</span>
                <b>{{ days_left_label($state['days_left'] ?? null) }}</b>
                <span class="small">{{ $state['duration_label'] ?? '' }}</span></div>
            <div class="kpi"><span>Plan</span><b>{{ $state['plan_name'] ?: '—' }}</b>
                <span class="small">{{ $shop['plan_code'] ?: 'no code' }}</span></div>
            <div class="kpi"><span>Seats</span>
                <b>{{ $seat['used'] }} / {{ $seat['limit'] }}</b>
                <span class="small">tills live</span></div>
            <div class="kpi"><span>Aaj</span>
                <b>{{ money($today['revenue'] ?? 0, $currency) }}</b>
                <span class="small">{{ $today['bill_count'] ?? 0 }} bills · avg {{ money($today['average_bill'] ?? 0, $currency) }}</span></div>
        </div>

        <div class="grid g2 mt2" style="align-items:start">
            <div class="card">
                <h3 class="mt0">Counters / seats</h3>
                @if($devices === [])
                    <p class="small mb0">Abhi koi device register nahi hua. Phone par app install
                        karke isi username se login karein — woh khud yahan aa jayega.</p>
                @else
                    <div class="scroll">
                        <table class="data">
                            <thead><tr><th>Device</th><th>Last seen</th><th>State</th><th></th></tr></thead>
                            <tbody>
                            @foreach($devices as $device)
                                <tr>
                                    <td><b>{{ $device['device_name'] ?: 'till' }}</b>
                                        <span class="hint mono">{{ short_id($device['device_id']) }}</span></td>
                                    <td>{{ ago($device['last_seen_at']) }}</td>
                                    <td>{!! state_pill((string) $device['status']) !!}</td>
                                    <td>@if($device['status'] === 'ACTIVE')
                                        <button class="btn btn-ghost btn-sm" type="submit"
                                                form="free-{{ $device['id'] }}">Free seat</button>
                                        <form id="free-{{ $device['id'] }}" method="post"
                                              action="{{ route('account.device.release') }}" class="inline-form">@csrf
                                            <input type="hidden" name="device_id" value="{{ $device['device_id'] }}"></form>
                                    @endif</td>
                                </tr>
                            @endforeach
                            </tbody>
                        </table>
                    </div>
                    <p class="hint mb0">Phone gum ya badal jaye to purani seat free karein —
                        naye device ka login foran ho jayega.</p>
                @endif
            </div>

            <div class="card">
                <h3 class="mt0">API token (website / PC software)</h3>
                <p class="small">Aap ki website ya desktop counter isi token se aap ke bills
                    parhega ya likhega. Docs: <a href="{{ route('developers') }}">/developers</a>.</p>
                <form method="post" action="{{ route('account.token') }}" class="row">
                    @csrf
                    <div class="field" style="flex:2 1 160px"><label for="tk-label">Kahan use hoga</label>
                        <input id="tk-label" name="label" required maxlength="60" placeholder="e.g. website counter"></div>
                    <div class="field" style="flex:1 1 90px"><label for="tk-days">Din (0 = hamesha)</label>
                        <input id="tk-days" name="days" type="number" min="0" max="3650" value="365"></div>
                    <div class="field" style="flex:1 1 150px"><label>Scope</label>
                        <div class="checks">
                            <label><input type="checkbox" name="scopes[]" value="read" checked> read</label>
                            <label><input type="checkbox" name="scopes[]" value="write"> write</label>
                        </div></div>
                    <button class="btn btn-primary btn-sm" type="submit" style="align-self:end">Create token</button>
                </form>
                @if($tokens !== [])
                    <div class="scroll mt1">
                        <table class="data">
                            <thead><tr><th>Label</th><th>Prefix</th><th>Scope</th><th>Last used</th><th></th></tr></thead>
                            <tbody>
                            @foreach($tokens as $token)
                                <tr>
                                    <td>{{ $token['label'] ?: 'token' }}</td>
                                    <td class="mono">{{ $token['token_prefix'] }}…</td>
                                    <td class="small">{{ $token['scopes'] }}</td>
                                    <td class="small">{{ ago($token['last_used_at']) }}</td>
                                    <td>@if(empty($token['revoked_at']))
                                        <button class="btn btn-danger btn-sm" type="submit" form="rv-{{ $token['id'] }}">Revoke</button>
                                        <form id="rv-{{ $token['id'] }}" method="post" action="{{ route('account.token.revoke') }}"
                                              class="inline-form">@csrf
                                            <input type="hidden" name="token_id" value="{{ $token['id'] }}"></form>
                                    @else<span class="chip">revoked</span>@endif</td>
                                </tr>
                            @endforeach
                            </tbody>
                        </table>
                    </div>
                @endif
            </div>
        </div>

        <div class="grid g2 mt2" style="align-items:start">
            <div class="card">
                <h3 class="mt0">Recent bills</h3>
                @if($bills === [])
                    <p class="small mb0">Abhi koi bill sync nahi hua. Till billing karta rahe —
                        sync hote hi yahan nazar aayega. Catalog me {{ $products }} product
                        record{{ $products === 1 ? '' : 's' }} hain.</p>
                @else
                    <div class="scroll"><table class="data">
                        <thead><tr><th>Invoice</th><th>When</th><th>Payment</th><th class="right">Total</th><th>Status</th></tr></thead>
                        <tbody>
                        @foreach($bills as $bill)
                            <tr>
                                <td class="mono">{{ $bill['invoice_no'] ?: short_id($bill['client_ref'], 10, 4) }}</td>
                                <td class="small">{{ $bill['date'] }} {{ substr($bill['created_at'], 11, 5) }}</td>
                                <td class="small">{{ $bill['payment_method'] }}</td>
                                <td class="right mono">{{ money($bill['total'], $currency) }}</td>
                                <td>{!! state_pill($bill['status'] === 'paid' ? 'PAID' : 'DUE') !!}</td>
                            </tr>
                        @endforeach
                        </tbody></table></div>
                @endif
            </div>
            <div>
                <div class="card">
                    <h3 class="mt0">Receipt par chhapne wala naam</h3>
                    <form method="post" action="{{ route('account.name') }}" class="row">
                        @csrf
                        <input name="app_name" maxlength="40" required minlength="3"
                               value="{{ $shop['app_name'] }}" placeholder="e.g. Madina General Store">
                        <button class="btn btn-ghost btn-sm" type="submit">Save</button>
                    </form>
                    <p class="hint mb0">Phone agli baar config le, yeh naam uske bill print karne
                        lag jayega.</p>
                </div>
                <div class="card mt1">
                    <h3 class="mt0">Madad</h3>
                    <p class="small mb0">
                        Licence ya payment ke liye:
                        @if($whatsapp)
                            <a class="btn btn-wa btn-sm" href="https://wa.me/{{ preg_replace('/\D+/', '', $whatsapp) }}" rel="noopener">WhatsApp</a>
                        @endif
                        <a href="{{ route('support') }}">ticket</a> ·
                        <a href="{{ route('developers') }}">API docs</a> ·
                        <a href="{{ route('download') }}">APK</a>
                    </p>
                </div>
            </div>
        </div>
    </div>
</section>
@endsection
