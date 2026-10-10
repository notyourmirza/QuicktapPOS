@extends('layouts.site')

@section('title', 'API — apni website aur PC software ke liye')
@section('description', 'QuickTap POS ka REST API: token se apne shop ke bills, products aur reports parhein ya likhein. Ek hi database, ek hi licence.')

@php
    // One honest marker line, so a copy-paste never leaks a real token.
    $placeholder = 'qt_pat_' . str_repeat('X', 24);
@endphp

@section('content')
<section class="section">
    <div class="wrap">
        <span class="badge live">same database as the till</span>
        <h1 class="mt1">API for websites &amp; PC software</h1>
        <p style="max-width:70ch">Aap ke phone ka till jo data server par bhejta hai, wahi
            data aap ki website, kiosk page ya desktop counter token se parh likh sakte hain.
            Ek hi licence, ek hi dukan, do raaste — is liye kabhi do ledger match karne ka
            masla nahi hota.</p>

        <div class="grid g3 mt2">
            <div class="card"><h3 class="mt0">1 · Token</h3>
                <p class="small mb0">
                    <a href="{{ route('account') }}">Shop dashboard</a> me "API token" banayein.
                    <span class="mono">read</span> sirf parhne ke liye,
                    <span class="mono">read+write</span> bill likhne ke liye. Plaintext ek hi
                    baar dikhta hai — uske baad sirf hash store hota hai.</p></div>
            <div class="card"><h3 class="mt0">2 · Base URL</h3>
                <p class="small mb0 mono" style="word-break:break-all">{{ $base }}</p>
                <p class="small mb0">Har request me header
                    <span class="mono">Authorization: Bearer {{ $placeholder }}</span>. HTTPS ke
                    bagair token kabhi na bhejein.</p></div>
            <div class="card"><h3 class="mt0">3 · Answer</h3>
                <p class="small mb0">Har jawab ek jaisa: <span class="mono">success, message,
                    data, code, timestamp</span>. <span class="mono">code</span> machine-readable
                    hai (<span class="mono">SCOPE_DENIED</span>, <span class="mono">TOKEN_EXPIRED</span>…),
                    is liye aap ka UI message strings par depend nahi karta.</p></div>
        </div>

        <h2 class="mt2">Endpoints</h2>
        <p class="small">Yeh list isi server ke route table se bani hai — jo dikha raha hai,
            wahi chalta hai. <span class="chip">token</span> wale rows aap ke token ke liye hain;
            baqi phone app ke (JWT) hain, un par token se nahi ja sakte.</p>
        <div class="card scroll">
            <table class="data">
                <thead><tr><th>Method</th><th>Path</th><th>Kya karta hai</th><th>Auth</th></tr></thead>
                <tbody>
                @foreach($routes as $route)
                    <tr>
                        <td class="mono">{{ $route['method'] }}</td>
                        <td class="mono">{{ $route['path'] }}</td>
                        <td>{{ endpoint_help($route['path']) }}</td>
                        <td>@if($route['token'])<span class="chip">token</span>@else<span class="chip">app</span>@endif</td>
                    </tr>
                @endforeach
                </tbody>
            </table>
        </div>
        <p class="hint">Sab se aasan test:
            <a class="mono" href="{{ $base }}">GET {{ $base }}</a> — yeh endpoint ki khud ki
            JSON listing deta hai.</p>

        <h2 class="mt2">Examples</h2>
        <div class="grid g2">
            <div class="card"><b class="small">curl</b>
                <pre class="small" style="white-space:pre-wrap">{{ $samples['curl'] }}</pre></div>
            <div class="card"><b class="small">Browser / kiosk (JavaScript)</b>
                <pre class="small" style="white-space:pre-wrap">{{ $samples['js'] }}</pre></div>
        </div>
        <div class="card mt1"><b class="small">Desktop (Python)</b>
            <pre class="small" style="white-space:pre-wrap">{{ $samples['py'] }}</pre></div>

        <h2 class="mt2">Rules jo zaroori hain</h2>
        <ul class="small">
            <li>Token sirf apni dukan ka data dekhta hai — <span class="mono">shop_id</span>
                token se nikalta hai, request se nahi.</li>
            <li><span class="mono">read</span> token write karne ki koshish kare to
                <span class="mono">403 SCOPE_DENIED</span>; expire hone par
                <span class="mono">401 TOKEN_EXPIRED</span>.</li>
            <li>Bill ka total server dobara ginata hai (items + discount% + tax%) — client ka
                bheja hua <span class="mono">total</span> qabool nahi hota, taake website aur
                till ka hisab hamesha match kare.</li>
            <li>Ek bill = ek <span class="mono">client_ref</span>: wahi ref dobara bhejein to
                record update hota hai, duplicate nahi banta (offline retries safe).</li>
            <li>Rate limit: ek IP se 60 requests/minute; <span class="mono">429</span> aaye to
                ruk kar dobara try karein.</li>
            <li>Licence expired ho to till grace me chalta hai, magar API write band ho jati
                hai — pehle licence barhayein.</li>
        </ul>

        <div class="card mt2 row spread">
            <div><b>Token chahiye ya scope badalna hai?</b>
                <p class="small mb0">Shop dashboard se khud bana lein, revoke bhi wahin se.
                    Purani <span class="mono">billing-api/API.md</span> (26 endpoints, har code)
                    isi server par laagu hai.</p></div>
            <a class="btn btn-primary" href="{{ route('account.login') }}">Open my dashboard</a>
        </div>
    </div>
</section>
@endsection
