@extends('layouts.site')

@section('title', 'Download QuickTap POS APK')

@section('content')
<section class="section">
    <div class="wrap grid g2" style="align-items:start">
        <div class="card">
            <h1 class="mt0">Download</h1>
            <p>Android 8+. Play Store ki zaroorat nahi — aur na hi koi app store aap ka
                business data dekh sakta hai.</p>
            <div class="stats mb2">
                <div class="stat"><b>{{ $version }}</b><span>latest version</span></div>
                <div class="stat"><b>{{ $code ?: '—' }}</b><span>versionCode</span></div>
                <div class="stat"><b>{{ $size ? $size . ' MB' : '—' }}</b><span>APK size</span></div>
            </div>
            @if($file)
                <a class="btn btn-primary" href="{{ asset('apk/' . $file) }}" download>
                    Download {{ $file }}</a>
                <p class="hint">Publish: {{ $built ?? '—' }}</p>
                @if($notes)
                    <div class="card mt1"><b class="small">Release notes</b>{!! nl2br(e($notes)) !!}</div>
                @endif
            @else
                <div class="flash bad">Abhi koi APK publish nahi hui. Support se rabta karein —
                    panel ke <span class="mono">Settings &amp; APK</span> se upload hoti hai.</div>
            @endif
        </div>
        <div class="card">
            <h3 class="mt0">Install ke 3 step</h3>
            <ol class="small">
                @foreach($steps as $step)<li>{{ $step }}</li>@endforeach
            </ol>
            <h3 class="mt1">Pehle se account hai?</h3>
            <p class="small mb0">Usi username/password se login karein — licence, seats aur
                data server se aa jate hain. Naya device register hone par seat lagti hai;
                aap <a href="{{ route('account') }}">apne dashboard</a> se purani seat free kar sakte hain.</p>
            <h3 class="mt1">Drive backup</h3>
            <p class="small mb0">App ke andar Settings → Backup. Aap ki own Drive folder me
                encrypted copy; phone gum ho jaye to login + restore.</p>
        </div>
    </div>
</section>
@endsection
