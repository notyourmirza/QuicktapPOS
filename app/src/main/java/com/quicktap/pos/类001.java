package com.quicktap.pos;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.quicktap.pos.auth.类004;
import com.quicktap.pos.data.类009;
import com.quicktap.pos.data.类008;
import com.quicktap.pos.backup.类005;
import com.quicktap.pos.sync.类035;
import com.quicktap.pos.license.类015;
import com.quicktap.pos.net.类018;
import com.quicktap.pos.theme.类036;
import com.quicktap.pos.util.类046;
import com.quicktap.pos.util.类054;

/**
 * Application entry point. Initialises prefs, theme, the singleton repository,
 * the auto session lock watcher and the weekly backup worker.
 */
public class 类001 extends Application {

    private static 类001 instance;
    private 类009 repository;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        类046.get(this);
        // Resolve the API endpoint (Firebase-controlled, BuildConfig fallback)
        // before any network call is made.
        类018.init(this);
        // Light / dark / system is the shop's choice and survives restarts.
        com.quicktap.pos.theme.类038.apply(this);

        try {
            repository = new 类009(this);
        } catch (Throwable databaseFailure) {
            // If a legacy database is malformed or only partially migrated,
            // recover once so the POS can launch instead of stopping at splash.
            try {
                com.quicktap.pos.data.AppDatabase.closeInstance();
                deleteDatabase(com.quicktap.pos.data.AppDatabase.NAME);
                repository = new 类009(this);
            } catch (Throwable recoveryFailure) {
                android.util.Log.e("类001", "Database startup failed", recoveryFailure);
                throw recoveryFailure;
            }
        }

        类004.get(this);
        类054.install(this);
        // Sync and backups are nonessential maintenance and must not block launch.
        try { 类035.applySchedule(this); } catch (Throwable ignored) { }
        try { 类005.schedule(this); } catch (Throwable ignored) { }
        try { 类008.schedule(this); } catch (Throwable ignored) { }
        try { 类015.schedule(this); } catch (Throwable ignored) { }
        try { repository.purgeBillsOlderThan(com.quicktap.pos.util.类049.oneMonthAgo()); } catch (Throwable ignored) { }
        try { com.quicktap.pos.util.类052.check(this); } catch (Throwable ignored) { }

        // Pull the cached remote configuration (app name, support number,
        // announcements) on cold start. Colours are never remote controlled.
        try { 类036.refresh(this, null); } catch (Throwable ignored) { }
    }


    public static 类001 get() { return instance; }

    public 类009 repo() { return repository; }
}
