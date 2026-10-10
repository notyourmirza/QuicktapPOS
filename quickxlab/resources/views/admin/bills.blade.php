@extends('layouts.admin')

@section('title', 'Bills')

@php $currency = $site['currency'] ?? 'PKR'; @endphp

@section('content')
<div class="bar">
    <div>
        <h1 class="mb0">Bills</h1>
        <p class="small muted mb0">Server par jo record hai — till se bhi, website ke token se
            bhi. Dono ki source column neeche dikhti hai.</p>
    </div>
    <form method="get" action="{{ route('admin.bills') }}">
        <div><label for="shop">Shop</label>
            <select id="shop" name="shop_id" style="min-width:180px">
                <option value="">all shops</option>
                @foreach($shops as $option)
                    <option value="{{ $option['id'] }}" @selected((int) request('shop_id') === (int) $option['id'])>
                        {{ $option['name'] }}</option>
                @endforeach
            </select></div>
        <div><label for="from">From</label><input id="from" type="date" name="from" value="{{ $from }}"></div>
        <div><label for="to">To</label><input id="to" type="date" name="to" value="{{ $to }}"></div>
        <div><label for="q">Andar dhoondein</label><input id="q" name="q" value="{{ $q }}"
            placeholder="invoice, customer"></div>
        <button class="btn btn-ghost" type="submit">Filter</button>
        <a class="btn btn-primary" href="{{ route('admin.bills.export', request()->query()) }}">CSV</a>
    </form>
</div>

<div class="kpis">
    <div class="kpi"><span>Bills</span><b>{{ number_format($totals['bills']) }}</b>
        <span class="small">{{ $from }} → {{ $to }}</span></div>
    <div class="kpi"><span>Revenue</span><b>{{ money($totals['revenue'], $currency) }}</b>
        <span class="small">server copy</span></div>
    <div class="kpi"><span>Unpaid</span><b>{{ money($totals['unpaid'], $currency) }}</b>
        <span class="small">due + khata</span></div>
    <div class="kpi"><span>Discount</span><b>{{ money($totals['discount'], $currency) }}</b>
        <span class="small">given</span></div>
    <div class="kpi"><span>Website/API</span><b>{{ $totals['web'] }}</b>
        <span class="small">token se likhe gaye</span></div>
</div>

@if($shop)
    <p class="mt1"><a class="chip" href="{{ route('admin.shop', $shop['id']) }}">
        {{ $shop['name'] }} — poori file</a>
        <span class="hint">filter hata kar sab shops dekhein</span></p>
@endif

<div class="card scroll mt1">
    <table class="data">
        <thead><tr><th>Shop</th><th>Invoice</th><th>When</th><th>Type</th><th>Customer</th>
            <th>Payment</th><th class="right">Total</th><th>Status</th><th>Source</th></tr></thead>
        <tbody>
        @forelse($bills as $bill)
            <tr>
                <td class="small"><a href="{{ route('admin.shop', $bill['_shop_id']) }}">{{ $bill['_shop_name'] }}</a></td>
                <td class="mono small">{{ $bill['invoice_no'] ?: short_id($bill['client_ref'], 10, 4) }}</td>
                <td class="small nowrap">{{ $bill['date'] }} {{ substr($bill['created_at'], 11, 5) }}</td>
                <td class="small">{{ $bill['order_type'] }}</td>
                <td class="small">{{ trim($bill['customer_name'] . ' ' . $bill['customer_phone']) ?: '—' }}</td>
                <td class="small">{{ $bill['payment_method'] }}</td>
                <td class="right mono">{{ money($bill['total'], $currency) }}</td>
                <td>{!! state_pill($bill['status'] === 'paid' ? 'PAID' : 'DUE') !!}</td>
                <td class="small">{{ $bill['source'] }}</td>
            </tr>
        @empty
            <tr><td colspan="9" class="muted">Is date range me koi bill nahi.
                <a href="{{ route('admin.bills', ['from' => date('Y-m-d', strtotime('-6 days')), 'to' => date('Y-m-d')]) }}">
                    pichle 7 din</a> try karein.</td></tr>
        @endforelse
        </tbody>
    </table>
</div>
<p class="hint">Sirf aakhri 200 rows table me hain (CSV poori range leta hai, max 5000).
    Ek bill ka poora item-level hisab <span class="mono">GET /v1/bills?from=…&amp;to=…&amp;q=INV-…</span>
    se milta hai — <span class="mono">limit</span> (max 500) aur <span class="mono">offset</span>
    se page kar lein.</p>
@endsection
