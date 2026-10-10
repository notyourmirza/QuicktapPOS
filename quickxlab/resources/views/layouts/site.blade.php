{{-- The public site's chrome: nav, notices, flash, footer. Everything the panel edits
     (name, logo, WhatsApp, notices) arrives from AppServiceProvider's view composer, so a
     page that only wants to say something never has to fetch a setting itself. --}}
<!doctype html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>@yield('title', ($site['name'] ?? 'QuickTap POS') . ' — QuickXLab')</title>
    <meta name="description" content="@yield('description', 'QuickTap POS — dukan ki billing, inventory aur khata. Offline chalta hai, sync foran. Pakistan ka apna server.')">
    <meta name="theme-color" content="#06080f">
    <link rel="icon" href="{{ !empty($site['logo']) ? $site['logo'] : asset('assets/logo.svg') }}">
    <link rel="stylesheet" href="{{ asset('css/quicktap.css') }}">
    @stack('head')
</head>
<body class="site">
<header class="nav">
    <div class="wrap">
        <a class="brand" href="{{ route('home') }}">
            @if(!empty($site['logo']))<img src="{{ $site['logo'] }}" alt="logo">@endif
            <span>Quick<em>XLab</em></span>
        </a>
        <nav id="nav">
            <a href="{{ route('plans') }}">Packages</a>
            <a href="{{ route('software', ['slug' => 'quicktap-retail']) }}">Software</a>
            <a href="{{ route('download') }}">Download</a>
            <a href="{{ route('developers') }}">API</a>
            <a href="{{ route('support') }}">Support</a>
            <a href="{{ route('account') }}">{{ auth_shop() ? 'My account' : 'Shop login' }}</a>
        </nav>
        <div class="row" style="gap:.4rem">
            <button class="btn btn-ghost btn-sm" data-omni type="button" title="Ctrl+K">⌕ <span class="mono small">Ctrl K</span></button>
            <a class="btn btn-primary btn-sm" href="{{ route('signup') }}">Get Started Free</a>
            <button class="burger" type="button" aria-label="Menu">≡</button>
        </div>
    </div>
</header>

@if(($notices ?? []) !== [])
    <div class="wrap" style="padding-top:.8rem">
        @foreach($notices as $notice)
            <div class="flash {{ $notice['level'] === 'alert' ? 'bad' : 'ok' }}">
                <b>{{ $notice['title'] }}</b> — {{ $notice['body'] }}
            </div>
        @endforeach
    </div>
@endif

@if(session('flash.ok') || session('flash.bad') || $errors->any())
    <div class="wrap" style="padding-top:.8rem">
        @if(session('flash.ok'))<div class="flash ok">{{ session('flash.ok') }}</div>@endif
        @if(session('flash.bad'))<div class="flash bad">{{ session('flash.bad') }}</div>@endif
        @if($errors->any())
            <div class="flash bad">
                <ul>@foreach($errors->all() as $message)<li>{{ $message }}</li>@endforeach</ul>
            </div>
        @endif
    </div>
@endif

<main>
@yield('content')
</main>

<div class="omni hide" id="omni" role="dialog" aria-label="Jump to">
    <div class="card" style="width:min(520px,92vw)">
        <input id="omni-q" placeholder="Kahan jana hai? (plans, download, API…)" autocomplete="off">
        <div class="grid g2 mt1" id="omni-list">
            <a class="btn btn-ghost" href="{{ route('plans') }}">Packages &amp; prices</a>
            <a class="btn btn-ghost" href="{{ route('download') }}">Download APK</a>
            <a class="btn btn-ghost" href="{{ route('developers') }}">API for website / PC</a>
            <a class="btn btn-ghost" href="{{ route('support') }}">Support desk</a>
            <a class="btn btn-ghost" href="{{ route('signup') }}">Naya account</a>
            <a class="btn btn-ghost" href="{{ route('account') }}">Shop dashboard</a>
        </div>
    </div>
</div>

<footer class="section">
    <div class="wrap grid g3">
        <div>
            <h3>{{ $site['name'] ?? 'QuickTap POS' }}</h3>
            <p class="small mb0">Dukan ka poora hisab — bill, stock, khata. App offline chalta
                hai; server sirf licence aur sync ke liye.</p>
            @if(!empty($whatsapp))
                <a class="btn btn-wa btn-sm mt1" href="https://wa.me/{{ $whatsapp }}?text={{ $waMessage ?? '' }}" rel="noopener">
                    WhatsApp support</a>
            @endif
        </div>
        <div>
            <h4>Site</h4>
            <p class="small mb0">
                <a href="{{ route('plans') }}">Packages</a><br>
                <a href="{{ route('software', ['slug' => 'quicktap-retail']) }}">Retail Pro</a><br>
                <a href="{{ route('software', ['slug' => 'quicktap-books']) }}">Books &amp; Khata</a><br>
                <a href="{{ route('download') }}">Download APK</a><br>
                <a href="{{ route('developers') }}">API &amp; tokens</a>
            </p>
        </div>
        <div>
            <h4>Shop / admin</h4>
            <p class="small mb0">
                <a href="{{ route('account') }}">Shop dashboard</a><br>
                <a href="{{ route('account.login') }}">Shop login</a><br>
                <a href="{{ route('admin.dashboard') }}">Billing panel</a><br>
                @if(!empty($whatsapp))<a href="tel:+{{ $whatsapp }}">Call {{ $whatsapp }}</a>@endif
            </p>
        </div>
    </div>
    <div class="wrap mt2 small muted">
        © {{ date('Y') }} QuickXLab · {{ $site['currency'] ?? 'PKR' }} · Support 9 AM – 11 PM (PKT)
        @if(!empty($ref)) · Referral <span class="mono">{{ $ref }}</span>@endif
    </div>
</footer>
<script src="{{ asset('js/quicktap.js') }}" defer></script>
@stack('scripts')
</body>
</html>
