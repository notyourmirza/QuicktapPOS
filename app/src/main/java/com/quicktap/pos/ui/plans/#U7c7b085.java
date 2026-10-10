package com.quicktap.pos.ui.plans;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.quicktap.pos.R;
import com.quicktap.pos.databinding.ActivityPlansBinding;
import com.quicktap.pos.net.类016;
import com.quicktap.pos.net.类017;
import com.quicktap.pos.util.类045;
import com.quicktap.pos.util.类046;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Premium in-app plan store: compare, pick a cycle and request the upgrade. */
public class 类085 extends AppCompatActivity {

    private ActivityPlansBinding binding;
    private 类083 adapter;
    private List<类084.Plan> plans;
    private 类046 prefs;
    private boolean yearly;
    private int selected = 1;
    /** Active subscription published by the admin, or null when none is active. */
    private JSONObject current;
    private final Handler countdownHandler = new Handler(Looper.getMainLooper());
    private final Runnable countdownRunnable = new Runnable() {
        @Override public void run() {
            updateExpiryCountdown();
            if (!isFinishing()) countdownHandler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPlansBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        prefs = 类046.get(this);
        plans = 类084.load(this);

        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.textCurrentPlan.setText("Current licence · " + prefs.getLicenseStatus()
                + " · " + prefs.getStoreName());

        adapter = new 类083(plans, prefs.getCurrency(), selected, position -> {
            selected = position;
            renderBar();
        });
        binding.recyclerPlans.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerPlans.setNestedScrollingEnabled(false);
        binding.recyclerPlans.setAdapter(adapter);

        binding.toggleCycle.check(binding.buttonMonthly.getId());
        binding.toggleCycle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            yearly = checkedId == binding.buttonYearly.getId();
            adapter.setCycle(yearly);
            renderBar();
        });

        // Purchases were removed: the plan is assigned by the administrator only.
        binding.buttonBuy.setVisibility(View.GONE);
        binding.toggleCycle.setVisibility(View.GONE);
        binding.textPlansFootnote.setText(R.string.plans_admin_managed);
        renderBar();
        applyCurrent();
        refreshFromServer();
        startExpiryCountdown();
    }

    @Override protected void onResume() {
        super.onResume();
        startExpiryCountdown();
    }

    @Override protected void onPause() {
        stopExpiryCountdown();
        super.onPause();
    }

    private void startExpiryCountdown() {
        countdownHandler.removeCallbacks(countdownRunnable);
        countdownHandler.post(countdownRunnable);
    }

    private void stopExpiryCountdown() {
        countdownHandler.removeCallbacks(countdownRunnable);
    }

    private long expiryMillis() {
        if (current != null) {
            long fromCurrent = current.optLong("expires_at_ms", 0L);
            if (fromCurrent <= 0L) fromCurrent = current.optLong("expiresAtMs", 0L);
            if (fromCurrent > 0L) return fromCurrent;

            String endsAt = current.optString("ends_at", "");
            long parsed = parseExpiryDate(endsAt);
            if (parsed > 0L) return parsed;
        }
        return prefs == null ? 0L : prefs.getLicenseExpiresAt();
    }

    private long parseExpiryDate(String raw) {
        if (raw == null || raw.trim().isEmpty() || "null".equalsIgnoreCase(raw)) return 0L;
        String value = raw.trim();
        String[] patterns = new String[] {
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setLenient(false);
                if (pattern.endsWith("'Z'")) format.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date date = format.parse(value);
                if (date != null) return date.getTime();
            } catch (ParseException ignored) { }
        }
        return 0L;
    }

    private void updateExpiryCountdown() {
        if (binding == null) return;
        long expiry = expiryMillis();
        if (expiry <= 0L) {
            binding.textExpiryCountdown.setText("License expiry: no fixed expiry");
            binding.textDaysValue.setText("--");
            binding.textHoursValue.setText("--");
            binding.textMinutesValue.setText("--");
            binding.textSecondsValue.setText("--");
            return;
        }
        long remaining = Math.max(0L, expiry - System.currentTimeMillis());
        long totalSeconds = remaining / 1000L;
        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (remaining <= 0L) {
            binding.textExpiryCountdown.setText("License expired");
            binding.textDaysValue.setText("00");
            binding.textHoursValue.setText("00");
            binding.textMinutesValue.setText("00");
            binding.textSecondsValue.setText("00");
        } else {
            binding.textExpiryCountdown.setText(String.format(Locale.getDefault(),
                    "License remaining  ·  %d days  %02d hours  %02d minutes  %02d seconds",
                    days, hours, minutes, seconds));
            binding.textDaysValue.setText(String.format(Locale.getDefault(), "%02d", days));
            binding.textHoursValue.setText(String.format(Locale.getDefault(), "%02d", hours));
            binding.textMinutesValue.setText(String.format(Locale.getDefault(), "%02d", minutes));
            binding.textSecondsValue.setText(String.format(Locale.getDefault(), "%02d", seconds));
        }
    }

    /** Pulls the live plan store from the admin panel and repaints the list. */
    private void refreshFromServer() {
        类045.io().execute(() -> {
            final List<类084.Plan> fresh;
            List<类084.Plan> parsed = null;
            JSONObject active = null;
            try {
                类017 res = 类016.get(this, "v1/plans", null, true);
                if (res != null && res.success && res.data != null) {
                    JSONArray array = res.data.optJSONArray("plans");
                    if (array != null && array.length() > 0) {
                        parsed = 类084.parse(array.toString());
                        类046.get(this).setPlanCatalog(array.toString());
                    }
                    active = res.data.optJSONObject("current");
                    prefs.setCurrentPlan(active == null ? "" : active.toString());
                }
            } catch (Exception ignored) { }
            fresh = parsed;
            final JSONObject freshCurrent = active;
            类045.main().post(() -> {
                if (binding == null) return;
                if (fresh != null && !fresh.isEmpty()) {
                    plans = fresh;
                    selected = Math.min(selected, plans.size() - 1);
                    adapter.submit(plans, selected);
                }
                current = freshCurrent;
                renderBar();
                applyCurrent();
            });
        });
    }

    /**
     * With an active subscription the catalogue is hidden: the user only sees
     * their own plan and can extend it. Otherwise the full store is shown.
     */
    private void applyCurrent() {
        if (current == null) {
            String cached = prefs.getCurrentPlan();
            if (cached != null && !cached.trim().isEmpty()) {
                try { current = new JSONObject(cached); } catch (Exception ignored) { }
            }
        }
        类084.Plan active = null;
        if (current != null) {
            String code = current.optString("code", "");
            for (类084.Plan plan : plans) {
                if (plan.code != null && plan.code.equalsIgnoreCase(code)) { active = plan; break; }
            }
        }
        boolean hasActive = active != null;

        binding.cardActivePlan.setVisibility(View.VISIBLE);
        binding.recyclerPlans.setVisibility(View.GONE);
        binding.textPlansFootnote.setVisibility(View.VISIBLE);
        
        binding.buttonBuy.setVisibility(View.GONE);
        binding.cardActivePlan.setVisibility(View.VISIBLE);
        if (!hasActive) {
            binding.textCurrentPlan.setText(R.string.plans_none);
            binding.textActivePlanName.setText("Scale your counter");
            binding.textActivePlanMeta.setText("No active plan — please contact your administrator.");
            binding.containerActiveFeatures.setVisibility(View.GONE);
            updateExpiryCountdown();
            return;
        }

        selected = plans.indexOf(active);
        adapter.submit(plans, selected);
        binding.textActivePlanName.setText(active.name);

        String endsAt = current.optString("ends_at", "");
        int daysLeft = current.optInt("days_left", -1);
        StringBuilder meta = new StringBuilder(active.tagline == null ? "" : active.tagline);
        if (endsAt != null && !endsAt.isEmpty() && !"null".equals(endsAt)) {
            if (meta.length() > 0) meta.append('\n');
            meta.append("Renews on ").append(endsAt.substring(0, Math.min(10, endsAt.length())));
            if (daysLeft >= 0) meta.append(" · ").append(daysLeft).append(" days left");
        }
        binding.textActivePlanMeta.setText(meta.toString());
        updateExpiryCountdown();

        binding.containerActiveFeatures.removeAllViews();
        if (active.features != null) {
            for (String feature : active.features) {
                TextView row = new TextView(this);
                row.setText("•  " + feature);
                row.setTextSize(13f);
                row.setTextColor(com.quicktap.pos.theme.类036.textPrimary(this));
                int pad = Math.round(getResources().getDisplayMetrics().density * 3);
                row.setPadding(0, pad, 0, pad);
                binding.containerActiveFeatures.addView(row);
            }
        }
        renderBar();
        updateExpiryCountdown();
    }

    /** Read-only summary bar: plan name and how long it still runs. */
    private void renderBar() {
        if (current == null) {
            binding.textSelectedName.setText(R.string.plans_none);
            binding.textSelectedPrice.setText("");
            return;
        }
        binding.textSelectedName.setText(current.optString("code", "").toUpperCase(Locale.getDefault())
                + " · " + current.optString("status", "inactive"));
        int daysLeft = current.optInt("days_left", -1);
        binding.textSelectedPrice.setText(daysLeft >= 0
                ? daysLeft + " days left" : getString(R.string.plans_no_expiry));
    }

}
