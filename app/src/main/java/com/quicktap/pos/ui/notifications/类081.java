package com.quicktap.pos.ui.notifications;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.quicktap.pos.databinding.ActivityNotificationsBinding;
import com.quicktap.pos.net.类016;
import com.quicktap.pos.net.类017;
import com.quicktap.pos.theme.类036;
import com.quicktap.pos.util.类045;

import org.json.JSONArray;

import java.util.List;

/**
 * In-app notification centre. Announcements are published by the Super Admin
 * and pulled from /v1/notifications; the last payload is cached for offline use.
 */
public class 类081 extends AppCompatActivity {

    private ActivityNotificationsBinding binding;
    private 类082 adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityNotificationsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.toolbar.setNavigationOnClickListener(v -> finish());

        int accent = 类036.primary(this);
        int onAccent = 类036.onColor(accent);
        binding.heroNotifications.setCardBackgroundColor(accent);
        binding.textHeroTitle.setTextColor(onAccent);
        binding.textHeroSubtitle.setTextColor(onAccent);
        binding.textHeroSubtitle.setAlpha(0.85f);
        binding.swipeNotifications.setColorSchemeColors(accent);

        adapter = new 类082();
        binding.recyclerNotifications.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerNotifications.setNestedScrollingEnabled(false);
        binding.recyclerNotifications.setAdapter(adapter);

        binding.swipeNotifications.setOnRefreshListener(this::refresh);

        render(类080.cached(this));
        refresh();
    }

    private void refresh() {
        binding.swipeNotifications.setRefreshing(true);
        类045.io().execute(() -> {
            String json = null;
            try {
                类017 res = 类016.get(this, "v1/notifications", null, true);
                if (res != null && res.success && res.data != null) {
                    JSONArray array = res.data.optJSONArray("notifications");
                    if (array != null) json = array.toString();
                }
            } catch (Exception ignored) { }
            final String payload = json;
            类045.main().post(() -> {
                if (binding == null) return;
                binding.swipeNotifications.setRefreshing(false);
                if (payload == null) return;
                类080.cache(this, payload);
                render(类080.parse(payload));
            });
        });
    }

    private void render(List<类080.Notice> notices) {
        adapter.submit(notices);
        boolean empty = notices == null || notices.isEmpty();
        binding.textNotificationsEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.recyclerNotifications.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Shared mechanism: banner + server-authoritative licence gate.
        com.quicktap.pos.ui.license.类075.protect(this);
    }
}
