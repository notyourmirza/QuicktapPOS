@extends('layouts.admin')

@section('title', 'Support queue')

@section('content')
<div class="bar">
    <div>
        <h1 class="mb0">Support queue</h1>
        <p class="small muted mb0">Site ke <span class="mono">/support</span> form se yahan aate
            hain. Desk inka jawab WhatsApp par deta hai; yahan sirf status note hota hai.</p>
    </div>
    <span class="chip">support number: {{ $site['whatsapp'] ?: 'not set' }}</span>
</div>

@forelse($tickets as $ticket)
    <div class="card mt1">
        <div class="row spread" style="align-items:start">
            <div>
                <b>{{ $ticket['name'] }}</b>
                <span class="mono small">{{ $ticket['phone'] }}</span>
                @if($ticket['shop'])<span class="hint"> · {{ $ticket['shop'] }}</span>@endif
                <br><span class="hint">{{ $ticket['topic'] }} · {{ stamp_date($ticket['created_at']) }}
                    ({{ ago($ticket['created_at']) }})</span>
            </div>
            <div class="row" style="gap:.4rem">
                {!! state_pill((string) $ticket['status']) !!}
                <a class="btn btn-wa btn-sm"
                   href="https://wa.me/{{ preg_replace('/\D+/','',$ticket['phone']) }}?text={{ rawurlencode('Salam — aap ka message: ' . $ticket['message']) }}"
                   rel="noopener">WhatsApp</a>
            </div>
        </div>
        <p class="mt1 mb1" style="color:var(--ink)">{{ $ticket['message'] }}</p>
        @if($ticket['answered_at'])
            <p class="hint mb1">Last touched {{ stamp_date($ticket['answered_at']) }} ·
                {{ $ticket['admin_note'] ?: 'no note' }}</p>
        @endif
        <form method="post" action="{{ route('admin.ticket.answer', $ticket['id']) }}" class="row">
            @csrf
            <select name="status" style="max-width:140px">
                @foreach(['OPEN','WAITING','DONE'] as $status)
                    <option value="{{ $status }}" @selected($ticket['status'] === $status)>{{ $status }}</option>
                @endforeach
            </select>
            <input name="note" maxlength="400" placeholder="internal note (customer ko nahi dikhta)"
                   value="{{ $ticket['admin_note'] }}" style="flex:1 1 240px">
            <button class="btn btn-ghost btn-sm" type="submit">Update</button>
        </form>
    </div>
@empty
    <div class="card mt1"><p class="mb0">Queue khali hai. Kabhi kabhi yeh sab se acha screen hai.</p></div>
@endforelse
<p class="hint">Ticket me koi password ya API key nahi hoti — form par woh likha hai, aur
    <span class="mono">support_tickets</span> table me bhi sirf naam/mobile/message rehte hain.</p>
@endsection
