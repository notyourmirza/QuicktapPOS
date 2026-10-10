package com.quicktap.pos.ui.license;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.quicktap.pos.databinding.ActivityConfirmCredentialsBinding;
import com.quicktap.pos.license.类012;
import com.quicktap.pos.license.类013;
import com.quicktap.pos.license.类014;
import com.quicktap.pos.ui.类042;
import com.quicktap.pos.util.类046;

/**
 * Final step: the user confirms username + password (POST /v1/license/confirm).
 * The password lives only in the input field and the request body — it is never
 * stored, cached or logged. Only a successful SERVER confirmation unlocks the app.
 */
public class 类073 extends AppCompatActivity {

    private ActivityConfirmCredentialsBinding binding;
    private boolean busy;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityConfirmCredentialsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        类046 prefs = 类046.get(this);
        if (prefs.getUsername() != null) binding.inputUsername.setText(prefs.getUsername());
        String label = prefs.getLicenseDurationLabel();
        binding.textDuration.setText(label == null || label.isEmpty()
                ? "Licence verified" : "License: " + label);

        binding.buttonConfirm.setOnClickListener(v -> submit());
    }

    private void submit() {
        if (busy) return;
        String username = binding.inputUsername.getText() == null
                ? "" : binding.inputUsername.getText().toString().trim();
        String password = binding.inputPassword.getText() == null
                ? "" : binding.inputPassword.getText().toString();

        binding.layoutUsername.setError(null);
        binding.layoutPassword.setError(null);
        if (username.isEmpty()) {
            binding.layoutUsername.setError("Enter your username");
            return;
        }
        if (password.isEmpty()) {
            binding.layoutPassword.setError("Enter your password");
            return;
        }

        setBusy(true);
        binding.textStatus.setVisibility(View.GONE);
        类013.confirm(this, username, password, state -> {
            setBusy(false);
            binding.inputPassword.setText("");
            if (state.success) {
                startActivity(new Intent(this, 类042.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                finish();
                return;
            }
            if (类014.OFFLINE.equals(state.state)) {
                showStatus("No connection to the server. Please try again.");
                return;
            }
            if ("BAD_CREDENTIALS".equals(state.code)) {
                showStatus("Username or password is incorrect.");
                return;
            }
            if (类014.NO_ACCOUNT.equals(state.state)
                    || 类014.BLOCKED.equals(state.state)
                    || 类014.EXPIRED.equals(state.state)
                    || 类014.REVOKED.equals(state.state)
                    || 类014.SUSPENDED.equals(state.state)) {
                类012.route(this, state);
                return;
            }
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
        binding.buttonConfirm.setEnabled(!value);
        binding.buttonConfirm.setText(value ? "Confirming…" : "Confirm & Unlock");
    }
}
