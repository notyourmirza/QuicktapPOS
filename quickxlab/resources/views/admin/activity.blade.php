@extends('layouts.admin')

@section('title', 'Audit log')

@section('content')
<div class="bar">
    <div>
        <h1 class="mb0">Audit log</h1>
        <p class="small muted mb0">Har licence change, code, block, token aur setting yahan hai —
            kaun, kab, kya. Kuch bhi "pata nahi kisne kiya" nahi hota.</p>
    </div>
</div>

<div class="card scroll">
    <table class="data">
        <thead><tr><th>When</th><th>Actor</th><th>Action</th><th>Shop</th><th>Detail</th></tr></thead>
        <tbody>
        @forelse($rows as $row)
            <tr>
                <td class="small nowrap">{{ stamp_date($row->created_at) }}<br>
                    <span class="hint">{{ ago($row->created_at) }}</span></td>
                <td class="mono small">{{ $row->actor ?: 'system' }}</td>
                <td class="mono small">{{ $row->action }}</td>
                <td class="small">@if($row->shop_id)
                    <a href="{{ route('admin.shop', $row->shop_id) }}">#{{ $row->shop_id }}</a>@else — @endif</td>
                <td class="small">{{ $row->detail }}</td>
            </tr>
        @empty
            <tr><td colspan="5" class="muted">Log khali hai.</td></tr>
        @endforelse
        </tbody>
    </table>
</div>
<div class="pager mt1">{{ $rows->links() }}</div>
<p class="hint">Actions: <span class="mono">
    @foreach($actions as $action){{ $action }} @endforeach</span> — yeh list bhi isi table se
    aati hai, is liye kabhi purani nahi hoti.
    30 din se purani rows khud delete nahi hoti (billing history ka hissa hain); space chahiye to
    <span class="mono">DELETE FROM audit_log WHERE created_at &lt; now()-30d</span> khud chalayen.</p>
@endsection
