package com.quicktap.pos.ui.license;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.quicktap.pos.databinding.ActivityVerifyLicenseBinding;
import com.quicktap.pos.license.类012;
import com.quicktap.pos.license.类013;
import com.quicktap.pos.license.类014;
import com.quicktap.pos.util.类056;

/**
 * Licence key verification (POST /v1/license/verify).
 *
 * The key format is the one the server issues (QT-XXXXX-XXXXX-XXXXX-XXXXX);
 * the client never decides whether it is valid — success is only what the
 * server reports.
 */
public class 类079 extends AppCompatActivity {

    private ActivityVerifyLicenseBinding binding;
    private boolean busy;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityVerifyLicenseBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.buttonVerify.setOnClickListener(v -> submit());
        binding.buttonBack.setOnClickListener(v -> finish());
        binding.buttonContact.setOnClickListener(v -> 类056.chat(this,
                "*License activation request*" + 类056.signature(this)));
    }

    private void submit() {
        if (busy) return;
        String key = binding.inputKey.getText() == null
                ? "" : binding.inputKey.getText().toString().trim().toUpperCase();
        binding.layoutKey.setError(null);
        if (key.length() < 6) {
            binding.layoutKey.setError("Enter the licence key you received");
            return;
        }

        setBusy(true);
        binding.textStatus.setVisibility(View.GONE);
        类013.verify(this, key, state -> {
            setBusy(false);
            if (state.success && state.unlocked) {
                类077.show(this, state, () -> {
                    startActivity(new Intent(this, 类073.class));
                    finish();
                });
                return;
            }
            if (类014.OFFLINE.equals(state.state)) {
                showStatus("No connection to the licence server. Please try again.");
                return;
            }
            if (类014.NO_ACCOUNT.equals(state.state)) {
                类012.route(this, state);
                return;
            }
            // Fail closed for everything else — nothing is unlocked locally.
            showStatus(state.describe());
        });
    }

    private void showStatus(String message) {
        binding.textStatus.setText(message);
        binding.textStatus.setVisibility(View.VISIBLE);
    }

    private void setBusy(boolean value) {
        busy = value;
        binding.progress.setVisibility(value ? View.VISIBLE : View.GONE);
        binding.buttonVerify.setEnabled(!value);
        binding.buttonVerify.setText(value ? "Verifying…" : "Verify License");
        binding.inputKey.setEnabled(!value);
    }
}
