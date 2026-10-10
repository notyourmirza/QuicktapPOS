@extends('layouts.site')

@section('title', $page['title'] . ' — QuickTap POS')
@section('description', $page['subtitle'])

@section('content')
<section class="section">
    <div class="wrap" style="max-width:820px">
        @if($page['badge'])<span class="chip">{{ $page['badge'] }}</span>@endif
        <h1 class="mt1">{{ $page['title'] }}</h1>
        <p style="font-size:1.1rem">{{ $page['subtitle'] }}</p>
        @if($page['price_label'])
            <div class="row spread card" style="align-items:center">
                <div><b style="font-size:1.5rem">{{ $page['price_label'] }}</b>
                    <span class="muted small">· 14 din free trial</span></div>
                <a class="btn btn-primary" href="{{ route('signup') }}">Get Started Free</a>
            </div>
        @endif
        <div class="card mt2">
            {!! nl2br(e($page['body'])) !!}
        </div>
        <div class="row mt2">
            <a class="btn btn-ghost" href="{{ route('download') }}">Download the app</a>
            <a class="btn btn-ghost" href="{{ route('developers') }}">Is data ko API se parhein</a>
            <a class="btn btn-ghost" href="{{ route('support') }}">Sawaal? Poochein</a>
        </div>
        <p class="hint mt2">Yeh page panel se badalta hai (<span class="mono">admin → Pages</span>,
            slug <span class="mono">{{ $page['slug'] }}</span>) — is liye copy ek deploy nahi hai.</p>
    </div>
</section>
@endsection
