package com.quicktap.pos.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.quicktap.pos.R;
import com.quicktap.pos.auth.类004;
import com.quicktap.pos.license.类012;
import com.quicktap.pos.license.类013;
import com.quicktap.pos.net.类018;
import com.quicktap.pos.sync.类034;
import com.quicktap.pos.theme.类036;
import com.quicktap.pos.util.类046;

/**
 * Remote-controlled, animated splash screen. Every visual detail is driven
 * by the Super Admin's cached splash configuration (see {@link 类046})
 * so it renders correctly offline, then routes exactly as before.
 */
public class 类043 extends AppCompatActivity {

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

        // The endpoint (Firebase controlled) must be resolved before the licence
        // gate asks the server anything. Application#onCreate already primed it;
        // this refresh keeps a changed endpoint / support number up to date.
        类018.init(getApplicationContext());
        类018.fetchAsync(getApplicationContext());
        类013.syncAppConfig(getApplicationContext());

        类004 session = 类004.get(this);

        routeRunnable = () -> {
            if (hasRouted || isFinishing()) return;
            hasRouted = true;
            if (prefs.isDemoMode()) {
                session.markActivity();
                go(类042.class);
                return;
            }
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
        // Image-only splash: keep the exact ultra-HD artwork visible for the
        // requested startup duration, then continue through the normal route.
        handler.postDelayed(routeRunnable, 2000L);
    }

    private void renderAndAnimate(类046 prefs) {
        View root = findViewById(R.id.splashRoot);
        ImageView referenceImage = findViewById(R.id.splashReferenceImage);
        TextView creditOverlay = findViewById(R.id.splashCreditOverlay);
        FrameLayout logoWrap = findViewById(R.id.splashLogoWrap);
        ImageView logo = findViewById(R.id.splashLogo);
        TextView title = findViewById(R.id.splashTitle);
        TextView tagline = findViewById(R.id.splashTagline);
        ProgressBar progress = findViewById(R.id.splashProgress);
        LinearLayout creditGroup = findViewById(R.id.splashCreditGroup);
        TextView creditPrefix = findViewById(R.id.splashCreditPrefix);
        TextView creditText = findViewById(R.id.splashCreditText);

        int background = parse(prefs.getSplashBackgroundColor(), 0);
        int textColor = parse(prefs.getSplashTextColor(), 0);
        int accent = parse(prefs.getSplashAccentColor(), 0);

        // Keep the professional branded splash background instead of allowing a
        // cached flat color to erase the composed presentation layer.
        if (accent != 0) {
            GradientDrawable frame = new GradientDrawable();
            frame.setShape(GradientDrawable.RECTANGLE);
            frame.setCornerRadius(28f * getResources().getDisplayMetrics().density);
            frame.setColor(Color.WHITE);
            frame.setStroke((int) (1f * getResources().getDisplayMetrics().density), accent);
            logoWrap.setBackground(frame);
            progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(accent));
            creditText.setTextColor(accent);
        }
        // The premium splash intentionally uses a dark canvas with high-contrast type.
        // Keep cached legacy text-color settings from washing out the new composition.
        title.setTextColor(Color.WHITE);
        tagline.setTextColor(Color.parseColor("#D9E2EF"));

        title.setText(prefs.getSplashTitle());
        tagline.setText(prefs.getSplashTagline());

        boolean showProgress = prefs.isSplashShowProgress();
        progress.setVisibility(showProgress ? View.VISIBLE : View.GONE);

        boolean showCredit = prefs.isSplashShowCredit();
        creditGroup.setVisibility(showCredit ? View.VISIBLE : View.GONE);
        creditPrefix.setText(prefs.getSplashCreditPrefix());
        creditText.setText(prefs.getSplashCreditText());

        // The old brown splash is no longer used. Animate the new orange
        // reference artwork directly so launch feels polished rather than
        // showing a second pre-splash screen.
        referenceImage.setAlpha(0f);
        referenceImage.setScaleX(1.04f);
        referenceImage.setScaleY(1.04f);
        referenceImage.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(650)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();

        creditOverlay.setAlpha(0f);
        creditOverlay.setTranslationY(8f);
        creditOverlay.animate().alpha(1f).translationY(0f)
                .setStartDelay(420)
                .setDuration(380)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();

        animateLogo(logoWrap, prefs.getSplashAnimation());

        title.animate().alpha(1f).translationY(0f).setStartDelay(150)
                .setDuration(360).setInterpolator(new AccelerateDecelerateInterpolator()).start();
        title.setTranslationY(24f);
        tagline.setTranslationY(24f);
        tagline.animate().alpha(1f).translationY(0f).setStartDelay(240)
                .setDuration(360).setInterpolator(new AccelerateDecelerateInterpolator()).start();
        if (showProgress) {
            progress.animate().alpha(1f).setStartDelay(470).setDuration(300).start();
        }
        if (showCredit) {
            creditGroup.setTranslationY(16f);
            creditGroup.animate().alpha(1f).translationY(0f).setStartDelay(560)
                    .setDuration(360).start();
        }
    }

    /** Applies the entrance animation named by the Super Admin's config. */
    private void animateLogo(View logo, String animation) {
        logo.setAlpha(0f);
        switch (animation == null ? "fade" : animation) {
            case "zoom":
                logo.setScaleX(0.4f);
                logo.setScaleY(0.4f);
                logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(500)
                        .setInterpolator(new OvershootInterpolator()).start();
                break;
            case "slide_up":
                logo.setTranslationY(80f);
                logo.animate().alpha(1f).translationY(0f).setDuration(450)
                        .setInterpolator(new AccelerateDecelerateInterpolator()).start();
                break;
            case "pulse":
                logo.setScaleX(0.85f);
                logo.setScaleY(0.85f);
                logo.animate().alpha(1f).setDuration(300).withEndAction(() -> pulse(logo)).start();
                break;
            case "rotate":
                logo.setRotation(-90f);
                logo.setScaleX(0.6f);
                logo.setScaleY(0.6f);
                logo.animate().alpha(1f).rotation(0f).scaleX(1f).scaleY(1f).setDuration(520)
                        .setInterpolator(new OvershootInterpolator()).start();
                break;
            case "fade":
            default:
                logo.setScaleX(0.85f);
                logo.setScaleY(0.85f);
                logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(450).start();
                break;
        }
    }

    /** Subtle continuous breathing effect for the "pulse" style. */
    private void pulse(View view) {
        ValueAnimator animator = ValueAnimator.ofFloat(1f, 1.08f, 1f);
        animator.setDuration(1100);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.addUpdateListener(a -> {
            float scale = (float) a.getAnimatedValue();
            view.setScaleX(scale);
            view.setScaleY(scale);
        });
        animator.start();
    }

    private int parse(String hex, int fallback) {
        try {
            return (hex == null || hex.trim().isEmpty()) ? fallback : Color.parseColor(hex);
        } catch (Exception e) {
            return fallback;
        }
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
