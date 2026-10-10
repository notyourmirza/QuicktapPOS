package com.quicktap.pos.ui.license;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.quicktap.pos.BuildConfig;
import com.quicktap.pos.R;
import com.quicktap.pos.auth.类003;
import com.quicktap.pos.databinding.ActivityDeviceAccessBinding;
import com.quicktap.pos.license.DeviceRegistry;
import com.quicktap.pos.theme.类036;
import com.quicktap.pos.util.类046;
import com.quicktap.pos.util.类056;

import java.util.List;

/**
 * The plain answer to "which tills are registered against my account?".
 *
 * <p>The licence is bound to this device's opaque id, so the shop can see the
 * seats its plan allows, which phone is this one, when every device was last seen,
 * and free a seat itself when a till is replaced. Everything is read from the
 * server and cached, so the screen still explains the situation offline.</p>
 */
public class DeviceAccessActivity extends AppCompatActivity {

    private ActivityDeviceAccessBinding binding;
    private 类046 prefs;
    private String myHash;
    private boolean busy;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDeviceAccessBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        prefs = 类046.get(this);
        myHash = 类003.id(this);

        binding.textDeviceName.setText(类003.name());
        binding.textDeviceId.setText("Device id: " + shorten(myHash));
        binding.textDeviceMeta.setText(类003.osVersion() + "  ·  app " + BuildConfig.VERSION_NAME);

        binding.buttonCopyId.setOnClickListener(v -> {
            类056.copy(this, myHash);
            Toast.makeText(this, "Device id copied", Toast.LENGTH_SHORT).show();
        });
        binding.buttonRefresh.setOnClickListener(v -> load());
        binding.buttonRelease.setOnClickListener(v -> confirmRelease());

        render(DeviceRegistry.cached(this), DeviceRegistry.cachedSeats(this), false,
                prefs.getDeviceSeatsLimit() > 0 ? "" : "Device list not loaded yet.");
        load();
    }

    // ------------------------------------------------------------- data

    private void load() {
        if (busy) return;
        setBusy(true);
        DeviceRegistry.load(this, (ok, message, devices, seats) -> {
            setBusy(false);
            render(devices, seats, !ok, message);
        });
    }

    private void render(List<DeviceRegistry.Device> devices, DeviceRegistry.Seats seats,
                        boolean stale, String message) {
        binding.containerDevices.removeAllViews();
        for (DeviceRegistry.Device device : devices) {
            binding.containerDevices.addView(row(device));
        }
        binding.textEmptyDevices.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
        binding.textSeatSummary.setText(seats.known()
                ? seats.label() + " used by this shop"
                : devices.size() + " device" + (devices.size() == 1 ? "" : "s") + " bound");
        binding.textLicenceLine.setText(licenceLine());
        showStatus(stale ? message : null);

        // Releasing a seat only makes sense when the plan has more than one.
        binding.buttonRelease.setVisibility(seats.known() && seats.limit > 1
                ? View.VISIBLE : View.GONE);
    }

    private String licenceLine() {
        String state = prefs.getLicenseState();
        String label = prefs.getLicenseDurationLabel();
        int days = prefs.getLicenseDaysLeft();
        StringBuilder sb = new StringBuilder("Licence: ");
        sb.append(state == null || state.isEmpty() ? "unknown" : state);
        if (label != null && !label.isEmpty()) sb.append("  ·  ").append(label);
        else if (days >= 0) sb.append("  ·  ").append(days).append(" days left");
        return sb.toString();
    }

    /** One cached row of the list, including the last answer the server gave. */
    private View row(DeviceRegistry.Device device) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(density, 9), 0, dp(density, 9));

        TextView title = new TextView(this);
        title.setTextSize(14f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(类036.textPrimary(this));
        String name = device.label == null || device.label.isEmpty() ? device.model : device.label;
        title.setText((device.current ? "▸ " : "") + (name == null || name.isEmpty()
                ? "Unknown device" : name) + "  ·  " + device.shortId());
        row.addView(title);

        TextView meta = new TextView(this);
        meta.setTextSize(12f);
        meta.setTextColor(类036.textMuted(this));
        meta.setText(device.active()
                ? "Last seen " + device.lastSeenLabel()
                : device.status + " · last seen " + device.lastSeenLabel());
        row.addView(meta);

        if (device.current) {
            TextView badge = new TextView(this);
            badge.setTextSize(11f);
            badge.setBackgroundResource(R.drawable.bg_pill_brand_soft);
            badge.setText("THIS DEVICE");
            badge.setTextColor(类036.primary(this));
            badge.setPadding(dp(density, 8), dp(density, 3), dp(density, 8), dp(density, 3));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(density, 4);
            row.addView(badge, params);
        }

        View line = new View(this);
        line.setBackgroundColor(类036.outline(this));
        row.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        row.setGravity(Gravity.START);
        return row;
    }

    // ------------------------------------------------------------- release

    private void confirmRelease() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Release this device?")
                .setMessage("This till will stop being able to bill until the "
                        + "administrator re-binds it. Your saved data stays on the "
                        + "phone — only the licence seat is freed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Release", (d, w) -> {
                    setBusy(true);
                    DeviceRegistry.release(this, myHash, (ok, message, devices, seats) -> {
                        setBusy(false);
                        render(devices, seats, !ok, message);
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    });
                })
                .show();
    }

    // -------------------------------------------------------------- helpers

    private void setBusy(boolean value) {
        busy = value;
        binding.buttonRefresh.setEnabled(!value);
        binding.buttonRelease.setEnabled(!value);
        binding.buttonRefresh.setText(value ? "Loading…" : "Refresh");
    }

    private void showStatus(@Nullable String message) {
        if (message == null || message.trim().isEmpty()) {
            binding.textStatus.setVisibility(View.GONE);
            return;
        }
        binding.textStatus.setVisibility(View.VISIBLE);
        binding.textStatus.setText(message);
    }

    private static int dp(float density, int value) { return Math.round(density * value); }

    private static String shorten(String hash) {
        if (hash == null || hash.length() <= 16) return hash;
        return hash.substring(0, 8) + "…" + hash.substring(hash.length() - 8);
    }
}
