<!doctype html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Panel login — QuickXLab</title>
    <meta name="robots" content="noindex,nofollow">
    <link rel="stylesheet" href="{{ asset('css/quicktap.css') }}">
</head>
<body class="panel" style="display:grid;place-items:center;min-height:100vh">
<div style="width:min(420px,92vw)">
    <div class="card">
        <div class="brand mb2"><span>Quick<em>XLab</em> Panel</span></div>
        <p class="small">Shops, licences, codes, API tokens aur site ki copy — sab yahan se.
            Yeh screen sirf un logon ke liye hai jinke paas <span class="mono">admin_users</span>
            ka login hai.</p>
        <form method="post" action="{{ route('admin.login.post') }}">
            @csrf
            <div class="field"><label for="u">Username</label>
                <input id="u" name="username" required autofocus autocomplete="username" value="{{ $username }}"></div>
            <div class="field"><label for="p">Password</label>
                <input id="p" name="password" type="password" required autocomplete="current-password"></div>
            <button class="btn btn-primary" type="submit" style="width:100%">Sign in</button>
        </form>
        @if($errors->any())
            <div class="flash bad mt1">{{ $errors->first() }}</div>
        @endif
        <p class="hint mt1 mb0">Pehla login? <span class="mono">php artisan db:seed</span>
            <span class="mono">SEED_ADMIN_*</span> values se <span class="mono">quickadmin</span> banata hai.</p>
    </div>
</div>
</body>
</html>
