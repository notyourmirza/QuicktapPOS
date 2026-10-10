package com.quicktap.pos.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.quicktap.pos.auth.类002;
import com.quicktap.pos.auth.类004;
import com.quicktap.pos.databinding.ActivityUnlockBinding;
import com.quicktap.pos.util.类046;

/**
 * Auto session lock screen.
 *
 * Shown when the app has been idle longer than the configured timeout. The
 * cashier unlocks with a fingerprint (when enabled) or with their password,
 * which is verified server-side against /v1/auth/unlock.
 */
public class 类044 extends FragmentActivity {

    private ActivityUnlockBinding binding;
    private 类004 session;
    private boolean autoPromptAttempted;
    private boolean biometricInFlight;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityUnlockBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        session = 类004.get(this);
        session.lockNow();

        类046 prefs = 类046.get(this);
        String who = prefs.getFullName();
        if (TextUtils.isEmpty(who)) who = session.username();
        binding.textUser.setText(TextUtils.isEmpty(who) ? "Signed in" : "Signed in as " + who);

        boolean biometrics = session.isFingerprintEnabled() && 类002.isAvailable(this);
        binding.buttonFingerprint.setVisibility(biometrics ? View.VISIBLE : View.GONE);

        binding.buttonUnlock.setOnClickListener(v -> unlockWithPassword());
        binding.buttonFingerprint.setOnClickListener(v -> promptBiometric());
        binding.buttonSignOut.setOnClickListener(v -> signOut());

    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        // Android may ignore an authentication request made during onCreate()
        // before the Activity is visible. Post it after the first resume.
        if (!autoPromptAttempted && session != null && session.isFingerprintEnabled()
                && 类002.isAvailable(this)) {
            autoPromptAttempted = true;
            binding.getRoot().postDelayed(this::promptBiometric, 250L);
        }
    }

    private void promptBiometric() {
        if (isFinishing() || biometricInFlight) return;
        if (!类002.isAvailable(this)) {
            showStatus(类002.unavailableReason(this) + " — use your password instead");
            return;
        }
        biometricInFlight = true;
        类002.prompt(this, "Unlock " + com.quicktap.pos.util.类046.get(this).getThemeAppName(), "Confirm it's you to continue",
                new 类002.Listener() {
                    @Override public void onSuccess() {
                        biometricInFlight = false;
                        unlocked();
                    }

                    @Override public void onFailure(String message, boolean fatal) {
                        biometricInFlight = false;
                        if (fatal) showStatus(message + " — use your password instead");
                    }
                });
    }

    private void unlockWithPassword() {
        String password = binding.inputPassword.getText() == null
                ? "" : binding.inputPassword.getText().toString();
        if (password.length() < 4) {
            binding.layoutPassword.setError("Enter your password");
            return;
        }
        binding.layoutPassword.setError(null);
        setBusy(true);
        session.unlockWithPassword(password, (ok, message, code) -> {
            setBusy(false);
            if (ok) unlocked();
            else showStatus(message);
        });
    }

    private void unlocked() {
        // 类042 is intentionally underneath this screen. Returning a
        // result is safer than launching a second 类042 and avoids a
        // duplicate-task crash on OEM Android builds.
        session.markUnlocked();
        setResult(RESULT_OK);
        finish();
    }

    private void signOut() {
        setBusy(true);
        session.logout((ok, message, code) -> {
            startActivity(new Intent(this, 类041.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
        });
    }

    private void showStatus(String message) {
        binding.textStatus.setText(message);
        binding.textStatus.setVisibility(View.VISIBLE);
    }

    private void setBusy(boolean busy) {
        binding.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        binding.buttonUnlock.setEnabled(!busy);
    }

    /** Keep the app in the unlock flow; Back must not background the POS task. */
    @Override public void onBackPressed() { }
}
