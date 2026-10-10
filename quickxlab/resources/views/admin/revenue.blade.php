@extends('layouts.admin')

@section('title', 'Revenue')

@php $currency = $site['currency'] ?? 'PKR'; @endphp

@section('content')
<div class="bar">
    <div>
        <h1 class="mb0">Revenue</h1>
        <p class="small muted mb0">Aaj se {{ $days }} din tak, poori platform ke bills — server
            par jo record hai usi se gin kar.</p>
    </div>
    <form method="get" action="{{ route('admin.revenue') }}">
        <div><label for="days"> Din</label>
            <input id="days" type="number" name="days" min="1" max="90" value="{{ $days }}"></div>
        <button class="btn btn-ghost" type="submit">Refresh</button>
        <a class="btn btn-ghost" href="{{ route('admin.bills', ['from' => $buckets[0]['date'] ?? date('Y-m-d'), 'to' => date('Y-m-d')]) }}">
            Bills khol</a>
    </form>
</div>

<div class="kpis">
    <div class="kpi"><span>{{ $days }}-din total</span><b>{{ money($total, $currency) }}</b>
        <span class="small">{{ array_sum(array_column($buckets, 'bills')) }} bills</span></div>
    <div class="kpi"><span>Average / bill</span>
        <b>{{ money($total / max(1, array_sum(array_column($buckets, 'bills'))), $currency) }}</b>
        <span class="small">poor platform</span></div>
    <div class="kpi"><span>Best day</span>
        <b>{{ money(max(array_column($buckets, 'revenue')), $currency) }}</b>
        <span class="small">{{ $buckets ? collect($buckets)->sortByDesc('revenue')->first()['date'] : '—' }}</span></div>
    <div class="kpi"><span>Peak (1 din)</span><b>{{ money($peak, $currency) }}</b>
        <span class="small">chart ki height isi par hai</span></div>
</div>

<div class="card mt2">
    <div class="spark">
        @foreach($buckets as $bucket)
            <i style="height:{{ max(2, (int) round(($bucket['revenue'] / $peak) * 100)) }}%"
               title="{{ $bucket['date'] }} · {{ money($bucket['revenue'], $currency) }} · {{ $bucket['bills'] }} bills"></i>
        @endforeach
    </div>
    <div class="row spread mt1 small muted">
        <span>{{ $buckets[0]['date'] ?? '' }}</span><span>{{ end($buckets)['date'] ?? '' }}</span>
    </div>
</div>

<div class="card scroll mt2">
    <table class="data">
        <thead><tr><th>Day</th><th class="right">Bills</th><th class="right">Revenue</th>
            <th class="right">Shops billing</th><th class="right">Avg / bill</th></tr></thead>
        <tbody>
        @foreach(array_reverse($buckets) as $bucket)
            <tr>
                <td class="mono">{{ $bucket['date'] }}
                    @if($bucket['date'] === date('Y-m-d'))<span class="chip">aaj</span>@endif</td>
                <td class="right mono">{{ $bucket['bills'] }}</td>
                <td class="right mono">{{ money($bucket['revenue'], $currency) }}</td>
                <td class="right mono">{{ count($bucket['shops']) }}</td>
                <td class="right mono">{{ money($bucket['bills'] > 0 ? $bucket['revenue'] / $bucket['bills'] : 0, $currency) }}</td>
            </tr>
        @endforeach
        </tbody>
    </table>
</div>
<p class="hint">Yeh number marketing page ke live counter se bhi milta-julta hai, kyunke dono
    <span class="mono">sync_records</span> ke <span class="mono">orders</span> rows parhte hain.
    Offline grace me likhe gaye bills sync hone par hi yahan aate hain — jaisa app ko bhi
    dikhta hai.</p>
@endsection
