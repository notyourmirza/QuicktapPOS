<?php

namespace App\Http\Controllers\Admin;

use App\Http\Controllers\Controller;
use App\Support\Billing;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Hash;
use Illuminate\View\View;

/**
 * Panel sign-in against `admin_users` — the same table and the same bcrypt hashes the
 * plain-PHP panel used, so a host that already has an admin login does not need a new one
 * after the move. Sessions are short and there is no "remember me" on purpose.
 */
class AuthController extends Controller
{
    public function show(Request $request): View
    {
        return view('admin.login', ['username' => old('username', '')]);
    }

    public function login(Request $request)
    {
        $data = $request->validate([
            'username' => 'required|string|max:32',
            'password' => 'required|string',
        ]);
        $row = DB::table('admin_users')->where('username', strtolower(trim($data['username'])))->first();
        $admin = $row === null ? null : (array) $row;

        // Failures say the same sentence either way: which half was wrong is not useful
        // information to whoever is guessing.
        if ($admin === null || !Hash::check($data['password'], (string) $admin['password_hash'])) {
            return back()->withInput()->withErrors(['username' => 'Login ya password ghalat hai.']);
        }

        $request->session()->put('admin_id', (int) $admin['id']);
        $request->session()->put('admin', ['id' => (int) $admin['id'], 'username' => (string) $admin['username']]);
        DB::table('admin_users')->where('id', (int) $admin['id'])->update(['last_login_at' => Billing::stamp()]);
        Billing::audit('panel_login', 0, (string) $admin['username'], (string) $admin['username']);

        $intended = (string) $request->session()->pull('admin.intended', '');

        return redirect($intended !== '' ? $intended : route('admin.dashboard'));
    }

    public function logout(Request $request)
    {
        $request->session()->forget(['admin_id', 'admin']);

        return redirect()->route('admin.login')->with('flash.ok', 'Signed out.');
    }
}
