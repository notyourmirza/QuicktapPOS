@extends('layouts.admin')

@section('title', 'Licence codes')

@section('content')
<div class="bar">
    <div>
        <h1 class="mb0">Licence codes</h1>
        <p class="small muted mb0">Jo code aap banate hain, woh app me <span class="mono">Licence →
            Redeem</span> se lagta hai. Code = kitne din, bas.</p>
    </div>
</div>

@if($fresh !== [])
    <div class="flash ok">
        <b>Abhi generate hue (sirf ek baar dikh rahe hain):</b>
        <ul style="margin:.35rem 0">
            @foreach($fresh as $code)
                <li><span class="mono copy" data-copy="{{ $code }}">{{ $code }}</span>
                    <span class="hint">— copy kar ke WhatsApp kar dein</span></li>
            @endforeach
        </ul>
    </div>
@endif

<div class="card">
    <form method="post" action="{{ route('admin.codes.generate') }}" class="row">
        @csrf
        <div style="flex:0 1 110px"><label for="days">Din</label>
            <input id="days" type="number" name="days" min="1" max="3650" value="30"></div>
        <div style="flex:0 1 90px"><label for="count">Kitne</label>
            <input id="count" type="number" name="count" min="1" max="50" value="1"></div>
        <div style="flex:1 1 180px"><label for="note">Note (reseller, order no…)</label>
            <input id="note" name="note" maxlength="180" placeholder="e.g. Raast 12-3456"></div>
        <div style="flex:1 1 200px"><label for="shop">Kisi shop ko seedha de dein (optional)</label>
            <select id="shop" name="shop_id">
                <option value="">— kisi ko nahi, sirf code —</option>
                @foreach($shops as $option)
                    <option value="{{ $option['id'] }}">{{ $option['name'] }} (#{{ $option['id'] }})</option>
                @endforeach
            </select></div>
        <button class="btn btn-primary" type="submit" style="align-self:end">Generate</button>
    </form>
    <p class="hint mb0">Shop chunne se code "used" mark ho jata hai aur us shop ka licence
        foran barh jata hai — cash payment ke baad wahi karna hota hai.</p>
</div>

<div class="card scroll mt2">
    <table class="data">
        <thead><tr><th>Code</th><th>Days</th><th>Note</th><th>Used by</th><th>When</th><th>State</th></tr></thead>
        <tbody>
        @forelse($codes as $code)
            <tr>
                <td class="mono">{{ $code->code }}</td>
                <td class="right mono">{{ $code->days }}</td>
                <td class="small">{{ $code->note ?: '—' }}</td>
                <td class="small">{{ $code->shop_name ?: '—' }}</td>
                <td class="small nowrap">{{ $code->used_at ? stamp_date($code->used_at) : '—' }}</td>
                <td>{!! state_pill($code->used_at ? 'DONE' : 'PENDING') !!}</td>
            </tr>
        @empty
            <tr><td colspan="6" class="muted">Abhi koi code nahi. Upar se banayein — ek code
                ek hi baar chalta hai (redeem par "used" ho jata hai), is liye har dukan ke liye
                alag banayein. "Kitne" me 10 daal kar ek batch bana lein.</td></tr>
        @endforelse
        </tbody>
    </table>
</div>
<div class="pager mt1">{{ $codes->links() }}</div>
@endsection
