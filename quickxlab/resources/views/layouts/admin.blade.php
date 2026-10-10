{{-- Panel chrome. One nav for the whole admin, and the same stylesheet as the site: the
     panel is the business's own tool, it should not look like a different company. --}}
<!doctype html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>@yield('title', 'Panel') — QuickXLab</title>
    <meta name="robots" content="noindex,nofollow">
    <link rel="stylesheet" href="{{ asset('css/quicktap.css') }}">
</head>
<body class="panel">
<div class="shell">
    <aside class="side">
        <div class="brand mb2"><span>Quick<em>XLab</em></span></div>
        <a class="{{ active('admin.dashboard') }}" href="{{ route('admin.dashboard') }}">Dashboard</a>
        <span class="group">Billing</span>
        <a class="{{ active('admin.shops') }}" href="{{ route('admin.shops') }}">Shops</a>
        <a class="{{ active('admin.devices') }}" href="{{ route('admin.devices') }}">Devices / seats</a>
        <a class="{{ active('admin.codes') }}" href="{{ route('admin.codes') }}">Licence codes</a>
        <a class="{{ active('admin.plans') }}" href="{{ route('admin.plans') }}">Plans</a>
        <a class="{{ active('admin.tokens') }}" href="{{ route('admin.tokens') }}">API tokens</a>
        <span class="group">Reports</span>
        <a class="{{ active('admin.bills') }}" href="{{ route('admin.bills') }}">Bills</a>
        <a class="{{ active('admin.revenue') }}" href="{{ route('admin.revenue') }}">Revenue</a>
        <a class="{{ active('admin.activity') }}" href="{{ route('admin.activity') }}">Activity</a>
        <span class="group">Site</span>
        <a class="{{ active('admin.pages') }}" href="{{ route('admin.pages') }}">Pages</a>
        <a class="{{ active('admin.notices') }}" href="{{ route('admin.notices') }}">Notices</a>
        <a class="{{ active('admin.settings') }}" href="{{ route('admin.settings') }}">Settings</a>
        <a class="{{ active('admin.tickets') }}" href="{{ route('admin.tickets') }}">Support tickets</a>
        <span class="group">Shortcuts</span>
        <a href="{{ route('home') }}">View site ↗</a>
        <a href="{{ url('/apk') }}">APK folder ↗</a>
        <form method="post" action="{{ route('admin.logout') }}" class="mt2">
            @csrf
            <button class="btn btn-ghost btn-sm" type="submit">Sign out {{ session('admin.username', '') }}</button>
        </form>
    </aside>
    <div class="main">
        @if(session('flash.ok'))<div class="flash ok">{{ session('flash.ok') }}</div>@endif
        @if(session('flash.bad'))<div class="flash bad">{{ session('flash.bad') }}</div>@endif
        @if($errors->any())
            <div class="flash bad"><ul>@foreach($errors->all() as $message)<li>{{ $message }}</li>@endforeach</ul></div>
        @endif
@yield('content')
    </div>
</div>
</body>
</html>
