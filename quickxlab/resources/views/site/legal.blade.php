@extends('layouts.site')

@section('title', $title)

@section('content')
<section class="section">
    <div class="wrap" style="max-width:760px">
        <h1 class="mt0">{{ $title }}</h1>
        <p class="muted small">
            {{ $updated ? 'Last updated ' . $updated : 'Yeh text panel ke Pages (slug <span class="mono">' . $slug . '</span>) se badal sakta hai.' }}
        </p>
        <div class="card">
            <ul style="margin:0;padding-left:1.1rem">
                @foreach($points as $point)
                    <li style="margin-bottom:.6rem">{{ $point }}</li>
                @endforeach
            </ul>
        </div>
        <div class="row mt2">
            <a class="btn btn-ghost" href="{{ route('support') }}">Sawaal? Support desk</a>
            <a class="btn btn-ghost" href="{{ route('plans') }}">Packages</a>
        </div>
    </div>
</section>
@endsection
