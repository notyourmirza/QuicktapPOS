<?php

namespace App\Http\Controllers\Admin;

use App\Http\Controllers\Admin\Concerns\Panels;
use App\Http\Controllers\Controller;
use App\Support\Billing;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\View\View;

/**
 * Every till the server has ever seen — the device accounting the whole licence depends
 * on. "Free seat" is the lost-phone fix that removes the need for a support call, and it
 * revokes that device's sessions in the same breath so the freed seat cannot be used twice.
 */
class DeviceController extends Controller
{
    use Panels;

    public function index(Request $request): View
    {
        $status = strtoupper(trim((string) $request->query('status', '')));
        $needle = trim((string) $request->query('q', ''));

        $devices = DB::table('app_devices')
            ->leftJoin('shops', 'shops.id', '=', 'app_devices.shop_id')
            ->select('app_devices.*', 'shops.name as shop_name', 'shops.plan_code')
            ->when(in_array($status, ['ACTIVE', 'RELEASED', 'BLOCKED'], true),
                fn ($q) => $q->where('app_devices.status', $status))
            ->when($needle !== '', function ($q) use ($needle) {
                $like = '%' . str_replace(['%', '_'], ['\%', '\_'], $needle) . '%';
                $q->where(fn ($inner) => $inner->where('app_devices.device_id', 'like', $like)
                    ->orWhere('app_devices.device_name', 'like', $like)
                    ->orWhere('shops.name', 'like', $like));
            })
            ->orderByDesc('app_devices.last_seen_at')->paginate(40)->withQueryString();

        foreach ($devices as $device) {
            $device->age = $device->last_seen_at
                ? (time() - (int) strtotime((string) $device->last_seen_at)) / 86400 : null;
        }

        return view('admin.devices', [
            'devices' => $devices,
            'status' => $status,
            'q' => $needle,
            'counts' => [
                'ACTIVE' => DB::table('app_devices')->where('status', 'ACTIVE')->count(),
                'RELEASED' => DB::table('app_devices')->where('status', 'RELEASED')->count(),
                'BLOCKED' => DB::table('app_devices')->where('status', 'BLOCKED')->count(),
            ],
        ]);
    }

    public function act(Request $request, int $device)
    {
        $row = DB::table('app_devices')->where('id', $device)->first();
        if ($row === null) {
            return $this->flash($request, 'No such device.', true);
        }
        $action = (string) $request->input('action', '');
        $shopId = (int) $row->shop_id;

        if ($action === 'free') {
            Billing::releaseDevice($shopId, (string) $row->device_id);
            Billing::audit('device_release', $shopId, (string) $row->device_id);
        } elseif (in_array($action, ['block', 'unblock'], true)) {
            DB::table('app_devices')->where('id', $device)->update([
                'status' => $action === 'block' ? 'BLOCKED' : 'ACTIVE',
                'last_seen_at' => Billing::stamp(),
            ]);
            if ($action === 'block') {
                DB::table('refresh_tokens')->where('device_id', $row->device_id)->whereNull('revoked_at')
                    ->update(['revoked_at' => Billing::stamp()]);
            }
            Billing::audit('device_' . $action, $shopId, (string) $row->device_id);
        } else {
            return $this->flash($request, 'That action is not allowed here.', true);
        }

        $message = $action === 'free'
            ? 'Seat freed for shop #' . $shopId . ' — its sessions are revoked too.'
            : 'Device marked ' . strtolower($action) . '.';

        return $this->flash($request, $message);
    }
}
