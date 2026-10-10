@extends('layouts.site')

@section('title', 'Support — QuickTap POS')

@section('content')
<section class="section">
    <div class="wrap grid g2" style="align-items:start">
        <div>
            <h1 class="mt0">Support Desk</h1>
            <p>Urdu ya English — jo aaram ho. Zyada tar masle (licence, printer, backup)
                WhatsApp par ek screenshot se halak ho jate hain.</p>
            <div class="card">
                <h3 class="mt0">Seedha raasta</h3>
                @if(!empty($whatsapp))
                    <p class="row mb0">
                        <a class="btn btn-wa" href="https://wa.me/{{ $whatsapp }}?text={{ $waMessage ?? '' }}" rel="noopener">
                            WhatsApp {{ $whatsapp }}</a>
                        <a class="btn btn-ghost" href="tel:+{{ $whatsapp }}">Call karein</a>
                    </p>
                @else
                    <p class="mb0">Abhi koi support number publish nahi kiya gaya — neeche form
                        bhar dein, hum aap ko call karenge.</p>
                @endif
                <p class="hint mt1">Roz 9 AM – 11 PM (PKT). Emergency (server down) ka jawab
                    aam tor par 15 minute me.</p>
            </div>
            <div class="card mt1">
                <h3 class="mt0">Pehle yeh check karein</h3>
                <ul class="small mb0">
                    <li><b>Licence expired?</b> Receipts chalte rehte hain (grace), naye bills
                        sync nahi honge jab tak panel se extension na ho.</li>
                    <li><b>Printer nahi chala?</b> Settings → Printer → Test print; USB cable
                        aur model ka naam likh kar bhejein.</li>
                    <li><b>Naya phone?</b> Login karein → seat full ka message aaye to purana
                        device dashboard se free karein.</li>
                    <li><b>Drive backup atka?</b> Google account same hona chahiye jisse
                        backup bana tha (appData folder usi khatay ka hai).</li>
                </ul>
            </div>
        </div>
        <div class="card">
            <h3 class="mt0">Ticket likhein</h3>
            @if($done ?? false)
                <div class="flash ok">Shukriya — ticket panel me aa gaya. Desk usi mobile par
                    jawab dega jo aap ne likha.</div>
            @endif
            <form method="post" action="{{ route('support.send') }}">
                @csrf
                <div class="field"><label for="t-name">Aap ka naam</label>
                    <input id="t-name" name="name" required maxlength="80" value="{{ old('name', $user['full_name'] ?? '') }}"></div>
                <div class="field"><label for="t-phone">Mobile (WhatsApp)</label>
                    <input id="t-phone" name="phone" required maxlength="24" inputmode="tel"
                           placeholder="03xx xxxxxxx" value="{{ old('phone') }}"></div>
                <div class="field"><label for="t-shop">Dukan ka naam (optional)</label>
                    <input id="t-shop" name="shop" maxlength="80" value="{{ old('shop', $shop['name'] ?? '') }}"></div>
                <div class="field"><label for="t-topic">Mauqa</label>
                    <select id="t-topic" name="topic">
                        @foreach(['licence' => 'Licence / activation', 'billing' => 'Bill / pricing',
                                  'sync' => 'Sync ya data', 'hardware' => 'Printer / scanner',
                                  'app' => 'App install / update', 'general' => 'Kuch aur'] as $value => $text)
                            <option value="{{ $value }}" @selected(old('topic') === $value)>{{ $text }}</option>
                        @endforeach
                    </select></div>
                <div class="field"><label for="ticket-message">Masla (screenshot ka link bhi de sakte hain)</label>
                    <textarea id="ticket-message" name="message" rows="6" maxlength="2000" required>{{ old('message') }}</textarea>
                    <span class="hint" data-counter-note>0 / 2000</span></div>
                <button class="btn btn-primary" type="submit">Send ticket</button>
                <p class="hint mb0">Ticket sirf support queue me jata hai — koi password,
                    API key ya client data yahan na likhein.</p>
            </form>
        </div>
    </div>
</section>
@endsection
