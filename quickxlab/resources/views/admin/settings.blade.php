@extends('layouts.admin')

@section('title', 'Settings')

@section('content')
<div class="bar">
    <div>
        <h1 class="mb0">Settings</h1>
        <p class="small muted mb0">Yeh <span class="mono">app_settings</span> hain — wahi jo har
            till <span class="mono">/v1/app/config</span> par parhta hai. Site ki copy inhi se
            chalti hai (naam, logo, WhatsApp).</p>
    </div>
</div>

@if(session('flash.ok'))<div class="flash ok">{{ session('flash.ok') }}</div>@endif

<div class="card">
    <form method="post" action="{{ route('admin.settings.save') }}">
        @csrf
        <div class="grid g2">
            @foreach($labels as $key => $help)
                <div class="field">
                    <label for="s-{{ $key }}">{{ $key }}</label>
                    @if($key === 'api_base_url')
                        <input id="s-{{ $key }}" name="settings[{{ $key }}]" value="{{ $current[$key] ?? '' }}"
                               placeholder="{{ $base }}">
                    @elseif($key === 'force_update')
                        <label style="display:flex;gap:.4rem;align-items:center;color:var(--ink)">
                            <input type="checkbox" name="settings[force_update]" value="1"
                                   @checked(($current['force_update'] ?? '0') === '1')>
                            force (1 = purani build reject)</label>
                    @elseif(in_array($key, ['license_sync_minutes','offline_grace_hours','auto_lock_minutes','tax_percent','apk_version_code'], true))
                        <input id="s-{{ $key }}" type="number" step="any" name="settings[{{ $key }}]"
                               value="{{ $current[$key] ?? '' }}">
                    @elseif($key === 'receipt_footer')
                        <textarea id="s-{{ $key }}" name="settings[{{ $key }}]" rows="2">{{ $current[$key] ?? '' }}</textarea>
                    @else
                        <input id="s-{{ $key }}" name="settings[{{ $key }}]" value="{{ $current[$key] ?? '' }}">
                    @endif
                    <span class="hint">{{ $help }}</span>
                </div>
            @endforeach
        </div>
        <button class="btn btn-primary btn-sm" type="submit">Save settings</button>
        <span class="hint">Firebase keys blank chhodein to app ka Google sign-in/backup
            <b>pehle wale</b> values par rahega (Remote Config + build me jo hain) — yahan se
            sirf <span class="mono">/v1/app/config</span> ko bataya jata hai.</span>
    </form>
</div>

<div class="grid g2 mt2" style="align-items:start">
    <div class="card">
        <h3 class="mt0">APK publish karein</h3>
        <p class="small">Download page isi file ko offer karta hai jo yahan chhapi ho.
            <span class="mono">public/apk/</span> me rakhi jati hai — folder .gitignore me hai,
            is liye APK repo me nahi jati.</p>
        <form method="post" action="{{ route('admin.settings.apk') }}" enctype="multipart/form-data">
            @csrf
            <div class="grid g2">
                <div class="field"><label for="vn">versionName</label>
                    <input id="vn" name="version_name" required maxlength="20" placeholder="1.3.0"
                           value="{{ $current['apk_version_name'] ?? '' }}"></div>
                <div class="field"><label for="vc">versionCode</label>
                    <input id="vc" type="number" name="version_code" required min="1" value="5"></div>
            </div>
            <div class="field"><label for="notes">Release notes (ek line me ek)</label>
                <textarea id="notes" name="notes" rows="3" maxlength="600"
                          placeholder="Urdu support button&#10;Drive backup fix"></textarea></div>
            <div class="field"><label for="apk">File (.apk, max 120 MB)</label>
                <input id="apk" type="file" name="apk" accept=".apk" required></div>
            <button class="btn btn-primary btn-sm" type="submit">Upload &amp; publish</button>
        </form>
    </div>
    <div class="card">
        <h3 class="mt0">Jo folder me hai</h3>
        @if($files === [])
            <p class="small mb0">Abhi <span class="mono">public/apk/</span> khali hai.</p>
        @else
            <table class="data">
                <thead><tr><th>File</th><th>Size</th><th>Published</th><th></th></tr></thead>
                <tbody>
                @foreach($files as $file)
                    <tr>
                        <td class="mono small">{{ $file['name'] }}</td>
                        <td class="small">{{ $file['size'] }} MB</td>
                        <td class="small">{{ $file['date'] }}</td>
                        <td class="right"><a class="btn btn-ghost btn-sm" href="{{ asset('apk/' . $file['name']) }}">Serve</a></td>
                    </tr>
                @endforeach
                </tbody>
            </table>
        @endif
        <p class="hint mb0">App khud update check karta hai: <span class="mono">min_app_version</span>
            se purani build aayi aur <span class="mono">force_update=1</span> ho, to till update
            page khol leta hai — is liye versionCode pehle barhayein, phir upload.</p>
    </div>
</div>
@endsection
