@extends('layouts.site')

@section('title', 'Packages & prices — QuickTap POS')

@section('content')
<section class="section">
    <div class="wrap">
        <span class="badge live">Sab prices {{ $site['currency'] ?? 'PKR' }} me, tax alag</span>
        <h1 class="mt1">Packages</h1>
        <p>Jo plan aap lete hain, usi par app ka unlock aur seat limit chalti hai — code me
            kuch fix nahi. Is liye price badalne par aap ko naya APK install nahi karna parta.</p>

        <div class="grid g3 mt2">
            @foreach($plans as $plan)
                <div class="card"
                     style="{{ $plan['tag'] === 'MOST POPULAR' ? 'border-color:var(--accent)' : '' }}">
                    @if($plan['tag'])<span class="chip">{{ $plan['tag'] }}</span>@endif
                    <h3 class="mt1">{{ $plan['name'] }}</h3>
                    <p class="small">{{ $plan['tagline'] }}</p>
                    <div class="row spread" style="align-items:baseline">
                        <b style="font-size:2rem">{{ $plan['price'] > 0 ? 'Rs ' . number_format($plan['price']) : 'Rs 0' }}</b>
                        <span class="muted small">{{ $plan['price'] > 0 ? '/ month' : '/ forever' }}</span>
                    </div>
                    @if($plan['yearly'] > 0)
                        <p class="small muted mb0">saalana Rs {{ number_format($plan['yearly']) }}
                            ({{ number_format($plan['price'] * 12 - $plan['yearly']) }} bachat)</p>
                    @endif
                    <ul class="small">
                        @foreach($plan['features'] as $feature)<li>{{ $feature }}</li>@endforeach
                        <li>{{ $plan['devices'] }} till seat{{ $plan['devices'] > 1 ? 's' : '' }}
                            — seat aap khud release kar sakte hain</li>
                    </ul>
                    <a class="btn {{ $plan['price'] > 0 ? 'btn-primary' : 'btn-ghost' }}"
                       href="{{ $plan['price'] > 0 ? route('signup', ['plan' => $plan['code']]) : route('download') }}">
                        {{ $plan['price'] > 0 ? '14 din free try' : 'Free download' }}</a>
                </div>
            @endforeach
        </div>

        <div class="card mt2">
            <h3 class="mt0">Payment kaise?</h3>
            <p class="mb0">Raast / bank transfer / EasyPaisa — payment ke baad licence code
                WhatsApp par milta hai, aap usse app me redeem kar lete hain. Koi card
                details server par nahi rehti. Kisi bhi waqt plan badal sakte hain: naya
                code daalein, balance din add ho jate hain.</p>
        </div>

        <h2 class="mt2">Sawaal</h2>
        <div class="grid g2">
            @foreach($faq as $row)
                <div class="card"><b>{{ $row['Q'] }}</b><p class="small mb0">{{ $row['A'] }}</p></div>
            @endforeach
        </div>

        <div class="center mt2">
            <a class="btn btn-primary" href="{{ route('signup') }}">Account banayein</a>
            <a class="btn btn-ghost" href="{{ route('home') }}#live-counter">Live counter dekhein</a>
        </div>
    </div>
</section>
@endsection
