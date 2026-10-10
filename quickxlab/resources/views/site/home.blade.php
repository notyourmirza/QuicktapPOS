@extends('layouts.site')

@section('title', 'QuickTap POS — dukan ka apna software (free to start)')

@section('content')
<section class="hero">
    <div class="wrap grid" style="gap:2rem;grid-template-columns:repeat(auto-fit,minmax(320px,1fr));align-items:center">
        <div>
            <span class="badge live">🇵🇰 Pakistan's #1 retail engine · live</span>
            <h1 class="mt1">Aap ka Business,<br>QuickTap Software ke Sath.</h1>
            <p>Bill, stock, khata aur report — sab ek app me. Internet jao ya na jao, dukan
                chalti rahegi: har cheez pehle aap ki machine par likhi jati hai, phir server
                par sync hoti hai. Website aur PC software bhi isi data par chalte hain.</p>
            <div class="row">
                <a class="btn btn-primary" href="{{ route('signup') }}">Get Started Free</a>
                <a class="btn btn-ghost" href="#live-counter">Watch the live counter</a>
            </div>
            <div class="stats mt2">
                <div class="stat"><b>{{ number_format(($stats['shops'] ?? 0) / 1000, 1) }}k+</b><span>Dukanein registered</span></div>
                <div class="stat"><b>0 ms</b><span>SQLite lag (offline-first)</span></div>
                <div class="stat"><b>99.9%</b><span>Sync uptime</span></div>
            </div>
        </div>

        <div id="live-counter">
            <div class="term" data-ticker="{{ number_format($stats['today']['revenue'] ?? 0, 2, '.', '') }}"
                 data-slips="{{ $stats['today']['bills'] ?? 0 }}">
                <header>
                    <span class="dot"></span>
                    <span>LIVE COUNTER · aaj, poori platform</span>
                    <span style="margin-left:auto">ESC/POS 80mm</span>
                </header>
                <div class="ticker">
                    <small>Aaj ki revenue</small>
                    <span class="money">Rs 0</span>
                    <small><span class="slips">0</span> slips · {{ $site['currency'] ?? 'PKR' }}</small>
                </div>
                <div class="code">
                    <span class="chip">licence sample</span>
                    <span class="copy mono" data-copy="QT-DEMO-KEY-0000-NOT-REAL">QT-DEMO-KEY-0000-NOT-REAL</span>
                </div>
                <table class="feed">
                    <tr><td>#1048 iMin Falcon · sample</td><td>Rs 4,920 · Cash</td></tr>
                    <tr><td>#1047 Sunmi V2 · sample</td><td>Rs 2,750 · Raast QR</td></tr>
                </table>
                <footer>
                    <span class="chip">Test slip print</span>
                    <span class="chip">Instant sync</span>
                    <span class="chip" style="margin-left:auto">avg {{ money(($stats['today']['revenue'] ?? 0) / max(1, (int) ($stats['today']['bills'] ?? 1))) }}</span>
                </footer>
            </div>
        </div>
    </div>
</section>

<section class="section" data-plans id="plans">
    <div class="wrap">
        <h2>Software Packages</h2>
        <p>Ek hi app, teen tarah ki dukanon ke liye. Price qabool karne se pehle 14 din free —
            card ki zaroorat nahi.</p>
        <div class="grid g3 mt2">
            @foreach($plans as $plan)
                <div class="card">
                    @if($plan['tag'])<span class="chip">{{ $plan['tag'] }}</span>@endif
                    <h3 class="mt1">{{ $plan['name'] }}</h3>
                    <p class="small">{{ $plan['tagline'] }}</p>
                    <div class="row spread" style="align-items:baseline">
                        <b style="font-size:1.7rem">{{ $plan['price'] > 0 ? 'Rs ' . number_format($plan['price']) : 'Rs 0' }}</b>
                        <span class="muted small">{{ $plan['price'] > 0 ? '/month' : 'lifetime free' }}</span>
                    </div>
                    <p class="small">{{ $plan['devices'] }} counter{{ $plan['devices'] > 1 ? 's' : '' }}
                        @if($plan['yearly'] > 0) · Rs {{ number_format($plan['yearly']) }} saalana @endif</p>
                    <ul class="small">
                        @foreach($plan['features'] as $feature)<li>{{ $feature }}</li>@endforeach
                    </ul>
                    <a class="btn {{ $plan['tag'] === 'MOST POPULAR' ? 'btn-primary' : 'btn-ghost' }}"
                       href="{{ $plan['price'] > 0 ? route('signup', ['plan' => $plan['code']]) : route('download') }}">
                        {{ $plan['price'] > 0 ? 'Start 14-day trial' : 'Download free' }}</a>
                    @if($plan['yearly'] > 0)
                        <p class="hint mb0">14-Din Free Trial · year par 2 mahine free</p>
                    @endif
                </div>
            @endforeach
        </div>
    </div>
</section>

<section class="section">
    <div class="wrap">
        <h2>Field Proven</h2>
        <p>Jahan roz 400+ bikri hoti hai, wahan yeh software pehle se chalta hai.</p>
        <div class="grid g2 mt2">
            @foreach([
                ['src' => 'assets/img/v8/img1_o.jpg', 'shop' => 'Anarkali Superstore', 'meta' => 'LHR · Grocery'],
                ['src' => 'assets/img/v8/img2_o.jpg', 'shop' => 'Al-Shifa Pharmacy', 'meta' => 'KHI · Pharmacy'],
            ] as $shot)
                <figure class="card mb0" style="padding:0;overflow:hidden">
                    <img src="{{ asset($shot['src']) }}" alt="{{ $shot['shop'] }}"
                         style="width:100%;display:block;aspect-ratio:16/10;object-fit:cover"
                         onerror="this.closest('figure').style.display='none'">
                    <figcaption class="row spread" style="padding:.7rem 1rem">
                        <b>{{ $shot['shop'] }}</b><span class="chip">{{ $shot['meta'] }}</span>
                    </figcaption>
                </figure>
            @endforeach
        </div>
    </div>
</section>

<section class="section">
    <div class="wrap">
        <h2>Zero Compromise Tech</h2>
        <div class="grid g4 mt2">
            @foreach([
                ['100% Offline SQLite', 'Har bill pehle aap ke phone ke database me. Server na ho to bhi printing chalti hai.'],
                ['Auto Mesh Sync', 'Internet aate hi queue khali hoti hai — conflicts me aap ki local copy jeetti hai.'],
                ['Hardware Hub', 'Sunmi, iMin, Xprinter, QR/raast aur barcode scanner — sab tested.'],
                ['AES-256 Vault', 'Licence machine-locked. Data aap ke paas, backup aap ki Drive par.'],
            ] as [$title, $body])
                <div class="card"><h3>{{ $title }}</h3><p class="small mb0">{{ $body }}</p></div>
            @endforeach
        </div>
    </div>
</section>

<section class="section">
    <div class="wrap grid g2" style="align-items:center">
        <div class="card">
            <h2 class="mt0">Refer Karo, Rs 500 Kamao</h2>
            <p>Dost ko apna code bhejein. Woh account khole to aap ke licence me 500 coins
                (≈ ek mahina) add ho jate hain — payout Raast P2M, NayaPay ya SadaPay par.</p>
            <div class="row">
                <input value="{{ url('/signup') }}{{ $ref ? '?ref=' . $ref : '' }}" id="reflink" readonly>
                <button class="btn btn-ghost" type="button" data-copy="{{ url('/signup') }}{{ $ref ? '?ref=' . $ref : '' }}">Copy</button>
            </div>
            <p class="hint mb0">Aap ka link: <span class="mono">{{ url('/signup') }}</span> · code signup form me chala jata hai.</p>
        </div>
        <div class="card">
            <h3 class="mt0">3 Minute Setup Flow</h3>
            <ol class="small">
                <li>APK download karein (Play Store ki zaroorat nahi) — <a href="{{ route('download') }}">/download</a></li>
                <li>Account banayein: naam, username, password — <a href="{{ route('signup') }}">/signup</a></li>
                <li>Phone/counter register hota hai, licence server se aata hai</li>
                <li>Products import karein (CSV ya camera se) — pehla bill 60 second me</li>
            </ol>
            <a class="btn btn-primary btn-sm" href="{{ route('signup') }}">Abhi shuru karein</a>
        </div>
    </div>
</section>

<section class="section">
    <div class="wrap">
        <div class="card row spread" style="gap:1rem">
            <div>
                <h3 class="mt0">Urdu Support Desk</h3>
                <p class="mb0">Roz 9 AM – 11 PM. Licence, printer ya backup — WhatsApp par
                    screen shot bhejein, jawab aap ko mil jayega.</p>
            </div>
            <div class="row">
                @if(!empty($whatsapp))
                    <a class="btn btn-wa" href="https://wa.me/{{ $whatsapp }}?text={{ $waMessage ?? '' }}" rel="noopener">WhatsApp</a>
                    <a class="btn btn-ghost" href="tel:+{{ $whatsapp }}">Call</a>
                @endif
                <a class="btn btn-ghost" href="{{ route('support') }}">Ticket likhein</a>
            </div>
        </div>
        <div class="center mt2">
            <h2>0 ms sync. Poora control aap ke haath.</h2>
            <div class="row" style="justify-content:center">
                <a class="btn btn-primary" href="{{ route('signup') }}">Get Started Free</a>
                <a class="btn btn-ghost" href="{{ route('plans') }}">Compare packages</a>
                <a class="btn btn-ghost" href="{{ route('developers') }}">API for my website</a>
            </div>
        </div>
    </div>
</section>
@endsection
