@extends('layouts.admin')

@section('title', 'Dashboard')

@section('content')
<h1>Dashboard</h1>
<p class="small muted">{{ date('l, j F Y') }} · server time {{ date('H:i') }}
    ({{ config('app.timezone') }}) · grace {{ config('billing.offline_grace_hours') }}h</p>

<div class="kpis">
    @foreach($stats as [$label, $value, $href])
        <a class="kpi" href="{{ $href }}"><b>{{ is_int($value) ? number_format($value) : $value }}</b>
            <span>{{ $label }}</span></a>
    @endforeach
</div>

<div class="grid g2 mt2" style="align-items:start">
    <div class="card">
        <div class="row spread"><h3 class="mt0">7 din me khatam</h3>
            <a class="btn btn-ghost btn-sm" href="{{ route('admin.shops') }}">All shops</a></div>
        @if($expiring === [])
            <p class="small mb0">Koi shop agle 7 din me expire nahi ho rahi.</p>
        @else
            <table class="data">
                <tbody>
                @foreach($expiring as $row)
                    <tr>
                        <td><a href="{{ route('admin.shop', $row['shop_id']) }}">{{ $row['shop']['name'] ?? ('#' . $row['shop_id']) }}</a></td>
                        <td class="mono small">{{ $row['license_key'] }}</td>
                        <td class="right">{!! state_pill('EXPIRING') !!}</td>
                        <td class="right small nowrap">{{ date('M j', (int) $row['expires_at']) }}</td>
                    </tr>
                @endforeach
                </tbody>
            </table>
        @endif
    </div>

    <div class="card">
        <div class="row spread"><h3 class="mt0">Activation baki (PENDING)</h3>
            <a class="btn btn-ghost btn-sm" href="{{ route('admin.codes') }}">Codes</a></div>
        @if($pending === [])
            <p class="small mb0">Sab approved. Naya sign-up aane par yahan nazar aayega.</p>
        @else
            <table class="data">
                <tbody>
                @foreach($pending as $row)
                    <tr>
                        <td><a href="{{ route('admin.shop', $row['shop_id']) }}">{{ $row['shop']['name'] ?? ('#' . $row['shop_id']) }}</a></td>
                        <td class="mono small">{{ $row['license_key'] }}</td>
                        <td class="right">{!! state_pill('PENDING') !!}</td>
                        <td class="right"><form method="post" class="inline-form"
                                action="{{ route('admin.shop.act', $row['shop_id']) }}">@csrf
                                <input type="hidden" name="action" value="extend">
                                <input type="hidden" name="days" value="30">
                                <button class="btn btn-ghost btn-sm" type="submit">+30 d</button></form></td>
                    </tr>
                @endforeach
                </tbody>
            </table>
        @endif
    </div>
</div>

<div class="grid g2 mt2" style="align-items:start">
    <div class="card">
        <div class="row spread"><h3 class="mt0">Support queue</h3>
            <a class="btn btn-ghost btn-sm" href="{{ route('admin.tickets') }}">Open</a></div>
        @if($tickets === [])
            <p class="small mb0">Koi naya ticket nahi.</p>
        @else
            <table class="data">
                <tbody>
                @foreach($tickets as $ticket)
                    <tr>
                        <td><b>{{ $ticket['name'] }}</b><br>
                            <span class="small muted">{{ $ticket['topic'] }} · {{ ago($ticket['created_at']) }}</span></td>
                        <td class="small">{{ \Illuminate\Support\Str::limit($ticket['message'], 90) }}</td>
                        <td class="right mono small"><a href="tel:{{ $ticket['phone'] }}">{{ $ticket['phone'] }}</a></td>
                    </tr>
                @endforeach
                </tbody>
            </table>
        @endif
    </div>
    <div class="card">
        <div class="row spread"><h3 class="mt0">Aakhri activity</h3>
            <a class="btn btn-ghost btn-sm" href="{{ route('admin.activity') }}">Audit log</a></div>
        <table class="data">
            <tbody>
            @foreach($activity as $row)
                <tr>
                    <td class="small mono">{{ $row['action'] }}</td>
                    <td class="small">{{ $row['detail'] }}</td>
                    <td class="right small muted nowrap">{{ $row['shop_id'] ? 'shop ' . $row['shop_id'] : '—' }}</td>
                    <td class="right small nowrap">{{ ago($row['created_at']) }}</td>
                </tr>
            @endforeach
            </tbody>
        </table>
    </div>
</div>
@endsection
