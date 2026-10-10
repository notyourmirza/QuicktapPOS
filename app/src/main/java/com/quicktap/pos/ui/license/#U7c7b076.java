package com.quicktap.pos.ui.license;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.quicktap.pos.auth.类004;
import com.quicktap.pos.databinding.ActivityLicenseStatusBinding;
import com.quicktap.pos.license.类012;
import com.quicktap.pos.license.类013;
import com.quicktap.pos.license.类014;
import com.quicktap.pos.util.类046;
import com.quicktap.pos.util.类056;

/**
 * One professional screen for every "the app is not unlocked yet" state:
 * PENDING, EXPIRED, REVOKED, SUSPENDED, BLOCKED, INVALID and OFFLINE.
 *
 * It never decides anything itself — it shows the last server answer and offers
 * "Check License" (GET /v1/license/status), "Verify Your License" and
 * "Contact For License Activation".
 */
public class 类076 extends AppCompatActivity {

    public static final String EXTRA_STATE = "state";

    /** Gentle auto-refresh while the screen is visible. */
    private static final long AUTO_REFRESH_MS = 60_000L;

    private ActivityLicenseStatusBinding binding;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable autoRefresh;
    private boolean busy;
    private String state = 类014.PENDING;

    public static Intent intent(Context ctx, String state) {
        return new Intent(ctx, 类076.class).putExtra(EXTRA_STATE, state);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLicenseStatusBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        state = getIntent().getStringExtra(EXTRA_STATE);
        if (state == null || state.isEmpty()) state = 类046.get(this).getLicenseState();

        类046 prefs = 类046.get(this);
        String user = prefs.getUsername() == null ? prefs.getStoreName() : prefs.getUsername();
        binding.textAccount.setText("Account: " + user);
        binding.textShop.setText(prefs.getStoreName());

        binding.buttonCheck.setOnClickListener(v -> check(true));
        binding.buttonVerify.setOnClickListener(v ->
                startActivity(new Intent(this, 类079.class)));
        binding.buttonContact.setOnClickListener(v -> 类056.chat(this,
                "*License activation request*\nStatus: " + state + 类056.signature(this)));
        binding.buttonSignOut.setOnClickListener(v -> signOut());

        render(state, 类046.get(this).getLicenseMessage());
    }

    @Override
    protected void onResume() {
        super.onResume();
        check(false);
        autoRefresh = new Runnable() {
            @Override public void run() {
                check(false);
                handler.postDelayed(this, AUTO_REFRESH_MS);
            }
        };
        handler.postDelayed(autoRefresh, AUTO_REFRESH_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (autoRefresh != null) handler.removeCallbacks(autoRefresh);
    }

    // -------------------------------------------------------------- check

    private void check(boolean userInitiated) {
        if (busy) return;
        if (!类004.get(this).isSignedIn()) {
            类012.route(this, offline());
            return;
        }
        setBusy(true, userInitiated);
        类013.status(this, result -> {
            setBusy(false, userInitiated);
            if (类014.ACTIVE.equals(result.state) && result.unlocked) {
                // The admin activated it: continue into the verification flow.
                if (result.confirmed) {
                    类012.route(this, result);
                } else {
                    类077.show(this, result, () -> {
                        startActivity(new Intent(this, 类073.class));
                        finish();
                    });
                }
                return;
            }
            if (类014.NO_ACCOUNT.equals(result.state)) {
                类012.route(this, result);
                return;
            }
            state = result.state;
            render(result.state, result.describe());
        });
    }

    // ------------------------------------------------------------- render

    private void render(String state, String message) {
        String title;
        String body;
        switch (state == null ? "" : state) {
            case 类014.EXPIRED:
                title = "YOUR LICENSE HAS EXPIRED";
                body = "Your licence period has ended. Contact support to renew, then check again.";
                break;
            case 类014.REVOKED:
                title = "LICENSE REVOKED";
                body = "This licence has been revoked by the administrator.";
                break;
            case 类014.SUSPENDED:
                title = "ACCOUNT SUSPENDED";
                body = "This account is suspended. Please contact support.";
                break;
            case 类014.BLOCKED:
                title = "ACCOUNT BLOCKED";
                body = "This account has been blocked. Please contact support.";
                break;
            case 类014.INVALID:
                title = "LICENSE NOT VERIFIED";
                body = "The licence could not be verified. Please check the key or contact support.";
                break;
            case 类014.OFFLINE:
                title = "CONNECTION PROBLEM";
                body = "We could not reach the licence server. Check your internet and try again.";
                break;
            case 类014.PENDING:
            default:
                title = "LICENSE STATUS PENDING";
                body = "Your account has been created successfully. "
                        + "Please contact support for license activation.";
                break;
        }
        binding.textTitle.setText(title);
        binding.textBody.setText(body);
        binding.textServerMessage.setVisibility(
                message == null || message.isEmpty() ? View.GONE : View.VISIBLE);
        binding.textServerMessage.setText(message);
    }

    private void setBusy(boolean value, boolean visible) {
        busy = value;
        binding.buttonCheck.setEnabled(!value);
        binding.buttonVerify.setEnabled(!value);
        binding.buttonCheck.setText(value ? "Checking…" : "Check License");
        binding.progress.setVisibility(value && visible ? View.VISIBLE : View.GONE);
    }

    private void signOut() {
        类004.get(this).logout((ok, message, code) -> {
            类046.get(this).clearLicenseCache();
            startActivity(new Intent(this, com.quicktap.pos.ui.类041.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        });
    }

    private 类014 offline() {
        类014 s = new 类014();
        s.state = 类014.NO_ACCOUNT;
        return s;
    }
}
