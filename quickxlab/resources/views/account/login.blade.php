@extends('layouts.site')

@section('title', 'Shop login — QuickTap POS')

@section('content')
<section class="section">
    <div class="wrap grid g2" style="align-items:center;max-width:900px">
        <div>
            <h1 class="mt0">Shop dashboard</h1>
            <p>Apne aap dekhein: licence kab tak hai, kaun se counter par seat lagi hai, aaj
                ka total — aur apni website ke liye API token banayein.</p>
            <ul class="small mb0">
                <li>Wahi username/password jo aap ke phone ke login me chalta hai</li>
                <li>Yahan se kuch delete nahi hota — sirf naam, seat aur token</li>
                <li>Payment ya plan badalna hai to <a href="{{ route('support') }}">support desk</a> par likhein</li>
            </ul>
        </div>
        <div class="card">
            <form method="post" action="{{ route('account.login.post') }}">
                @csrf
                <div class="field"><label for="l-user">Username</label>
                    <input id="l-user" name="username" required maxlength="32" autofocus value="{{ $username }}"></div>
                <div class="field"><label for="l-pass">Password</label>
                    <input id="l-pass" name="password" type="password" required autocomplete="current-password"></div>
                <button class="btn btn-primary" type="submit">Login</button>
                <p class="hint mt1 mb0">Naya dukan? <a href="{{ route('signup') }}">Account banayein</a> ·
                    password bhool gaye? <a href="{{ route('support') }}">Support</a> se theek ho jata hai.</p>
            </form>
        </div>
    </div>
</section>
@endsection
