@extends('layouts.site')

@section('title', 'Naya account — QuickTap POS')

@section('content')
<section class="section">
    <div class="wrap grid g2" style="align-items:start">
        <div>
            <h1 class="mt0">Account banayein</h1>
            <p>Pehle 14 din free. Card ki zaroorat nahi, aur account banate hi aap app me
                login kar sakte hain — licence panel se approve hota hai.</p>
            <ul class="small">
                <li>Ek shop = ek billing account = jitne counters chahiye utne seats</li>
                <li>Username aur password wahi hain jo phone ke login screen par chalte hain</li>
                <li>Data aap ka; hum sirf bill ka record aur licence status rakhte hain</li>
            </ul>
            @if($ref)
                <div class="flash ok">Referral code <b>{{ $ref }}</b> sath ja raha hai —
                    aap ke licence me credit add hoga.</div>
            @endif
            <p class="hint mb0">Pehle se account hai? <a href="{{ route('account.login') }}">Login</a> ·
                License kharidni hai? <a href="{{ route('plans') }}">Packages</a></p>
        </div>
        <div class="card">
            <form method="post" action="{{ route('signup.post') }}">
                @csrf
                <div class="field"><label for="s-shop">Dukan ka naam</label>
                    <input id="s-shop" name="shop_name" required minlength="3" maxlength="60"
                           placeholder="e.g. Madina General Store" value="{{ old('shop_name') }}"></div>
                <div class="field"><label for="s-name">Aap ka naam</label>
                    <input id="s-name" name="full_name" maxlength="80" value="{{ old('full_name') }}"></div>
                <div class="field"><label for="s-phone">Mobile (WhatsApp)</label>
                    <input id="s-phone" name="phone" required maxlength="24" inputmode="tel"
                           placeholder="03xx xxxxxxx" value="{{ old('phone') }}"></div>
                <div class="grid g2">
                    <div class="field"><label for="s-user">Username (app me bhi yahi)</label>
                        <input id="s-user" name="username" required minlength="4" maxlength="32"
                               pattern="[a-z0-9._-]+" value="{{ old('username') }}"></div>
                    <div class="field"><label for="s-pass">Password (8+ characters)</label>
                        <input id="s-pass" name="password" type="password" required minlength="8"
                               autocomplete="new-password"></div>
                </div>
                <div class="field"><label for="s-pass2">Password dobara</label>
                    <input id="s-pass2" name="password_confirmation" type="password" required
                           autocomplete="new-password"></div>
                <button class="btn btn-primary" type="submit">Create my account</button>
                <p class="hint mb0">Submit karne se pehle <a href="{{ route('plans') }}">prices</a>
                    dekh lein — plan baad me bhi badal sakte hain.</p>
            </form>
        </div>
    </div>
</section>
@endsection
