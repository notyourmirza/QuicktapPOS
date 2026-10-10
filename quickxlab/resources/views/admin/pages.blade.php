@extends('layouts.admin')

@section('title', 'Site pages')

@section('content')
<div class="bar">
    <div>
        <h1 class="mb0">Site pages</h1>
        <p class="small muted mb0"><span class="mono">/software?slug=…</span> inhi rows se banta
            hai — copy badlo, deploy ki zaroorat nahi.</p>
    </div>
    <a class="btn btn-ghost" href="{{ route('software') }}">Site ↗</a>
</div>

<div class="card">
    <form method="post" action="{{ route('admin.page.save') }}">
        @csrf
        <input type="hidden" name="id" value="{{ $editing['id'] ?? '' }}">
        <div class="grid g4">
            <div><label for="slug">Slug (lowercase, dash)</label>
                <input id="slug" name="slug" required maxlength="60" pattern="[a-z0-9-]+"
                       value="{{ $editing['slug'] ?? '' }}" placeholder="quicktap-retail"></div>
            <div><label for="title">Title</label>
                <input id="title" name="title" required maxlength="120" value="{{ $editing['title'] ?? '' }}"></div>
            <div><label for="badge">Badge</label>
                <input id="badge" name="badge" maxlength="32" value="{{ $editing['badge'] ?? '' }}"
                       placeholder="MOST POPULAR"></div>
            <div><label for="price">Price label</label>
                <input id="price" name="price_label" maxlength="40" value="{{ $editing['price_label'] ?? '' }}"
                       placeholder="Rs 799/mo"></div>
        </div>
        <div class="field mt1"><label for="subtitle">Subtitle (ek line)</label>
            <input id="subtitle" name="subtitle" maxlength="200" value="{{ $editing['subtitle'] ?? '' }}"></div>
        <div class="field"><label for="body">Body — ek line me ek point; <span class="mono">kya milta hai:</span> jaisa header khud theek lagta hai</label>
            <textarea id="body" name="body" rows="7">{{ $editing['body'] ?? '' }}</textarea></div>
        <div class="row">
            <div style="flex:0 1 120px"><label for="sort">Sort</label>
                <input id="sort" type="number" name="sort_order" min="0" max="999" value="{{ $editing['sort_order'] ?? 0 }}"></div>
            <label style="display:flex;gap:.4rem;align-items:center;color:var(--ink);margin-top:1.1rem">
                <input type="checkbox" name="is_active" value="1" @checked(empty($editing) || (int) ($editing['is_active'] ?? 0) === 1)>
                active (site par dikhe)</label>
            <button class="btn btn-primary btn-sm" type="submit" style="margin-top:1rem">
                {{ $editing ? 'Save page' : 'Create page' }}</button>
            @if($editing)
                <a class="btn btn-ghost btn-sm" style="margin-top:1rem" href="{{ route('admin.pages') }}">New instead</a>
            @endif
        </div>
    </form>
</div>

<div class="card scroll mt2">
    <table class="data">
        <thead><tr><th>Slug</th><th>Title</th><th>Badge</th><th>Price</th><th>Sort</th><th>Updated</th><th>State</th><th></th></tr></thead>
        <tbody>
        @foreach($pages as $page)
            <tr>
                <td class="mono small">{{ $page['slug'] }}</td>
                <td><a href="{{ route('software', ['slug' => $page['slug']]) }}">{{ $page['title'] }}</a></td>
                <td class="small">{{ $page['badge'] ?: '—' }}</td>
                <td class="small">{{ $page['price_label'] ?: '—' }}</td>
                <td class="mono small">{{ $page['sort_order'] }}</td>
                <td class="small">{{ stamp_date($page['updated_at']) }}</td>
                <td>{!! state_pill($page['is_active'] ? 'ACTIVE' : 'HIDDEN') !!}</td>
                <td class="right nowrap">
                    <a class="btn btn-ghost btn-sm" href="{{ route('admin.pages', ['id' => $page['id']]) }}">Edit</a>
                    @if($page['is_active'])
                        <form method="post" action="{{ route('admin.page.delete', $page['id']) }}" class="inline-form">@csrf
                            <button class="btn btn-danger btn-sm" type="submit">Unpublish</button></form>
                    @endif
                </td>
            </tr>
        @endforeach
        </tbody>
    </table>
</div>
<p class="hint">Home page ke packages alag hain — woh <span class="mono">plans</span> table se
    aate hain (<a href="{{ route('admin.plans') }}">Plans</a>). Yahan sirf prose copy hai.</p>
@endsection
