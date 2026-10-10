package com.quicktap.pos.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.quicktap.pos.auth.类003;
import com.quicktap.pos.auth.类004;
import com.quicktap.pos.databinding.ActivityLoginBinding;
import com.quicktap.pos.license.类012;
import com.quicktap.pos.ui.license.类078;
import com.quicktap.pos.util.类046;

/**
 * Username + password sign-in against the QuickTap REST API.
 *
 * On success the server binds this device to the account (device binding) and
 * returns a JWT access/refresh pair which {@link 类004} stores.
 */
public class 类041 extends AppCompatActivity {

    private ActivityLoginBinding binding;
    private 类004 session;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        session = 类004.get(this);

        binding.textDevice.setText("Device: " + 类003.name());
        binding.layoutEmail.setHint("Username");
        binding.buttonLogin.setOnClickListener(v -> submit());
        binding.buttonDemo.setOnClickListener(v -> enterDemoMode());
        binding.buttonRegister.setOnClickListener(v ->
                startActivity(new Intent(this, 类078.class)));
        binding.buttonPlans.setOnClickListener(v -> startActivity(
                new android.content.Intent(this, com.quicktap.pos.ui.plans.类085.class)));
        binding.buttonContact.setOnClickListener(v -> contactAdmin());
        binding.textForgot.setOnClickListener(v -> contactAdmin());
    }

    private void submit() {
        String username = value(binding.inputEmail.getText());
        String password = value(binding.inputPassword.getText());

        if (TextUtils.isEmpty(username)) {
            binding.layoutEmail.setError("Enter your username");
            return;
        }
        binding.layoutEmail.setError(null);
        if (password.length() < 4) {
            binding.layoutPassword.setError("Enter your password");
            return;
        }
        binding.layoutPassword.setError(null);

        类046.get(this).setDemoMode(false);
        setBusy(true);
        session.login(username, password, (ok, message, code) -> {
            setBusy(false);
            if (ok) {
                session.markUnlocked();
                // Never open the app straight away: the server-authoritative
                // licence gate decides where this account belongs.
                setBusy(true);
                类012.resolveAndRoute(this);
                return;
            }
            // Blocked device / suspended shop: surface the server's reason inline.
            showStatus(message == null || message.isEmpty()
                    ? "Sign in failed. Please try again." : message);

        });
    }

    private void enterDemoMode() {
        类046.get(this).setDemoMode(true);
        startActivity(new Intent(this, 类042.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    /** Plan catalogue is owned by the Super Admin panel; this is the read-only view. */
    private void showPlans() {
        startActivity(new Intent(this, com.quicktap.pos.ui.plans.类085.class));
    }

    private void contactAdmin() {
        com.quicktap.pos.ui.support.类093.show(this, "Licence / approval");
    }


    private void showStatus(String message) {
        binding.textStatus.setText(message);
        binding.textStatus.setVisibility(View.VISIBLE);
    }

    private void setBusy(boolean busy) {
        binding.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        binding.buttonLogin.setEnabled(!busy);
    }

    private String value(CharSequence text) { return text == null ? "" : text.toString().trim(); }
}
