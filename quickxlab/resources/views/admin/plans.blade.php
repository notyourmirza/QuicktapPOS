@extends('layouts.admin')

@section('title', 'Plans')

@php $currency = $site['currency'] ?? 'PKR'; @endphp

@section('content')
<div class="bar">
    <div>
        <h1 class="mb0">Plans</h1>
        <p class="small muted mb0">Price, seats aur features — yehi rows app ki store screen,
            site ke packages aur licence rules ko chalate hain. Ek jagah badlo, teen jagah
            theek.</p>
    </div>
    <a class="btn btn-ghost" href="{{ route('plans') }}">Site par dekhein ↗</a>
</div>

@forelse($plans as $plan)
    <div class="card mt1">
        <form method="post" action="{{ route('admin.plan.save', $plan['id']) }}">
            @csrf
            <div class="row spread" style="align-items:center">
                <b class="mono">{{ $plan['code'] }}</b>
                <span class="row" style="gap:.4rem">
                    <span class="chip">sort {{ $plan['sort_order'] }}</span>
                    <span class="chip">{{ $plan['is_active'] ? 'visible' : 'hidden' }}</span>
                    <button class="btn btn-ghost btn-sm" type="submit"
                            form="tg-{{ $plan['id'] }}">{{ $plan['is_active'] ? 'Hide from app' : 'Show in app' }}</button>
                </span>
            </div>
            <div class="grid g4 mt1">
                <div><label for="n{{ $plan['id'] }}">Name</label>
                    <input id="n{{ $plan['id'] }}" name="name" required maxlength="60" value="{{ $plan['name'] }}"></div>
                <div><label for="t{{ $plan['id'] }}">Tag (badge)</label>
                    <input id="t{{ $plan['id'] }}" name="tag" maxlength="32" value="{{ $plan['tag'] }}"
                           placeholder="MOST POPULAR"></div>
                <div><label for="p{{ $plan['id'] }}">Monthly ({{ $currency }})</label>
                    <input id="p{{ $plan['id'] }}" type="number" step="0.01" min="0" name="price"
                           required value="{{ $plan['price'] }}"></div>
                <div><label for="y{{ $plan['id'] }}">Yearly ({{ $currency }})</label>
                    <input id="y{{ $plan['id'] }}" type="number" step="0.01" min="0" name="price_yearly"
                           value="{{ $plan['price_yearly'] }}"></div>
            </div>
            <div class="grid g2 mt1">
                <div><label for="s{{ $plan['id'] }}">Tagline</label>
                    <input id="s{{ $plan['id'] }}" name="tagline" maxlength="140" value="{{ $plan['tagline'] }}"></div>
                <div><label for="d{{ $plan['id'] }}">Seats (tills)</label>
                    <input id="d{{ $plan['id'] }}" type="number" min="1" max="999" name="max_devices"
                           required value="{{ $plan['max_devices'] }}"></div>
            </div>
            <div class="field mt1"><label for="f{{ $plan['id'] }}">Features — ek line me ek</label>
                <textarea id="f{{ $plan['id'] }}" name="features" rows="4">{{ $plan['features'] }}</textarea></div>
            <button class="btn btn-primary btn-sm" type="submit">Save plan</button>
            <span class="hint">app ko naya price <span class="mono">/v1/app/plans</span> ki
                agli fetch par mil jata hai (5 minute ka cache)</span>
        </form>
        <form id="tg-{{ $plan['id'] }}" method="post" action="{{ route('admin.plan.toggle', $plan['id']) }}"
              class="inline-form">@csrf</form>
    </div>
@empty
    <div class="card mt1">
        <p class="mb0">Koi plan nahi — tab tak har shop <b>1 seat, free</b> par chalegi (app
            "no plan" dikhata hai). <span class="mono">php artisan db:seed</span> chaar plans daal deta hai.</p>
    </div>
@endforelse
<p class="hint mt1">Price 0 rakhne wala plan "lifetime free" hota hai — us shop ka licence
    <span class="mono">expires_at = 0</span> (Lifetime) reh sakta hai, aur expiry ka koi page
    nahi aayega. Seats ka rule: plan ke <span class="mono">max_devices</span> se zyada tills
    register nahi hoti; pehle wali release karein.</p>
@endsection
