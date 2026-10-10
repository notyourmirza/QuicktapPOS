package com.quicktap.pos.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.quicktap.pos.R;
import com.quicktap.pos.auth.类004;
import com.quicktap.pos.license.类012;
import com.quicktap.pos.net.类018;
import com.quicktap.pos.sync.类034;
import com.quicktap.pos.theme.类036;
import com.quicktap.pos.util.类046;

/**
 * Startup screen. It is a fixed branded layout — nothing about it is fetched from
 * the network — and its only job is to give the single licence gate a moment to
 * resolve the endpoint and the account state before routing.
 */
public class 类043 extends AppCompatActivity {

    /** How long the artwork stays on screen before routing continues. */
    private static final long SPLASH_MS = 2000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable routeRunnable;
    private boolean hasRouted;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        类046 prefs = 类046.get(this);
        AppCompatDelegate.setDefaultNightMode(prefs.isDarkMode()
                ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);

        setContentView(R.layout.activity_splash);

        // The shop's name is the one thing the panel publishes that the customer
        // sees on every launch, so it is applied to the branded layout directly.
        android.widget.TextView title = findViewById(R.id.splashTitleXml);
        String name = 类036.appName(this);
        if (name != null && !name.trim().isEmpty()) title.setText(name);

        // The endpoint (Firebase controlled) must be resolved before the licence
        // gate asks the server anything. Application#onCreate already primed it;
        // this refresh keeps a changed endpoint / support number up to date.
        类018.init(getApplicationContext());
        类018.fetchAsync(getApplicationContext());

        类004 session = 类004.get(this);

        routeRunnable = () -> {
            if (hasRouted || isFinishing()) return;
            hasRouted = true;
            if (!session.isSignedIn()) {
                // No account / no session at all -> sign in or create an account.
                go(类041.class);
                return;
            }
            if (session.shouldLock()) {
                // Keep 类042 underneath the lock screen so a successful
                // password/fingerprint unlock returns to the POS instead of
                // finishing the task and appearing to close the app.
                go(类042.class);
                return;
            }
            session.markActivity();

            // Quietly catch up with the server in the background…
            类036.refresh(getApplicationContext(), null);
            类034.syncAuto(getApplicationContext());

            // …and let the single licence gate decide the destination. Every
            // state (pending / expired / revoked / suspended / blocked /
            // unconfirmed / active) is routed there, never here.
            类012.resolveAndRoute(类043.this);
        };
        handler.postDelayed(routeRunnable, SPLASH_MS);
    }

    private void go(Class<?> target) {
        if (isFinishing()) return;
        startActivity(new Intent(this, target));
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (routeRunnable != null) handler.removeCallbacks(routeRunnable);
    }
}
