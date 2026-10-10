@extends('layouts.admin')

@section('title', 'Notices')

@section('content')
<div class="bar">
    <div>
        <h1 class="mb0">Notices</h1>
        <p class="small muted mb0">Yeh text teen jagah dikhta hai: site ke upar banner, till ki
            bell (Notifications), aur <span class="mono">/v1/app/config</span> — ek hi row se.</p>
    </div>
</div>

<div class="card">
    <form method="post" action="{{ route('admin.notice.save') }}">
        @csrf
        <input type="hidden" name="id" value="{{ $editing['id'] ?? '' }}">
        <div class="grid g4">
            <div><label for="nt">Title</label>
                <input id="nt" name="title" required maxlength="120" value="{{ $editing['title'] ?? '' }}"
                       placeholder="e.g. Sunday night maintenance 2-4 AM"></div>
            <div><label for="lvl">Level</label>
                <select id="lvl" name="level">
                    @foreach(['info','warn','alert'] as $level)
                        <option value="{{ $level }}" @selected(($editing['level'] ?? 'info') === $level)>{{ $level }}</option>
                    @endforeach
                </select></div>
            <div><label for="st">Starts (optional)</label>
                <input id="st" type="datetime-local" name="starts_at"
                       value="{{ !empty($editing['starts_at']) ? str_replace(' ', 'T', substr((string) $editing['starts_at'], 0, 16)) : '' }}"></div>
            <div><label for="en">Ends (optional)</label>
                <input id="en" type="datetime-local" name="ends_at"
                       value="{{ !empty($editing['ends_at']) ? str_replace(' ', 'T', substr((string) $editing['ends_at'], 0, 16)) : '' }}"></div>
        </div>
        <div class="field mt1"><label for="nb">Body</label>
            <textarea id="nb" name="body" rows="3" maxlength="2000">{{ $editing['body'] ?? '' }}</textarea></div>
        <div class="row">
            <label style="display:flex;gap:.4rem;align-items:center;color:var(--ink)">
                <input type="checkbox" name="is_active" value="1"
                       @checked(empty($editing) || (int) ($editing['is_active'] ?? 0) === 1)> live</label>
            <button class="btn btn-primary btn-sm" type="submit">{{ $editing ? 'Save changes' : 'Save notice' }}</button>
            @if($editing)
                <a class="btn btn-ghost btn-sm" href="{{ route('admin.notices') }}">New instead</a>
            @endif
        </div>
    </form>
</div>

<div class="card scroll mt2">
    <table class="data">
        <thead><tr><th>Title</th><th>Level</th><th>Window</th><th>State</th><th></th></tr></thead>
        <tbody>
        @forelse($notices as $notice)
            <tr>
                <td><b>{{ $notice['title'] }}</b>
                    @if($notice['body'])<br><span class="small muted">{{ $notice['body'] }}</span>@endif</td>
                <td class="small mono">{{ $notice['level'] }}</td>
                <td class="small">{{ $notice['starts_at'] ? stamp_date($notice['starts_at']) : 'now' }}
                    → {{ $notice['ends_at'] ? stamp_date($notice['ends_at']) : 'khud band na karein' }}</td>
                <td>{!! state_pill($notice['is_active'] ? 'ACTIVE' : 'HIDDEN') !!}</td>
                <td class="right nowrap">
                    <a class="btn btn-ghost btn-sm" href="{{ route('admin.notices', ['id' => $notice['id']]) }}">Edit</a>
                    <form method="post" action="{{ route('admin.notice.delete', $notice['id']) }}" class="inline-form">@csrf
                        <button class="btn btn-danger btn-sm" type="submit">{{ $notice['is_active'] ? 'Turn off' : 'Off' }}</button>
                    </form>
                </td>
            </tr>
        @empty
            <tr><td colspan="5" class="muted">Koi notice nahi.</td></tr>
        @endforelse
        </tbody>
    </table>
</div>
@endsection
