package com.quicktap.pos.ui;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.quicktap.pos.R;
import com.quicktap.pos.auth.类004;
import com.quicktap.pos.license.类012;
import com.quicktap.pos.license.类013;
import com.quicktap.pos.license.类014;
import com.quicktap.pos.theme.类038;
import com.quicktap.pos.databinding.ActivityMainBinding;
import com.quicktap.pos.ui.billing.类062;
import com.quicktap.pos.ui.dashboard.类068;
import com.quicktap.pos.ui.history.类072;
import com.quicktap.pos.ui.products.类088;
import com.quicktap.pos.ui.reports.类089;
import com.quicktap.pos.ui.expenses.类070;
import com.quicktap.pos.ui.commercial.类067;
import com.quicktap.pos.ui.reviews.类090;
import com.quicktap.pos.ui.settings.类091;
import com.quicktap.pos.ui.settings.类092;
import com.quicktap.pos.util.类046;
import com.quicktap.pos.device.类010;

import android.widget.TextView;

/**
 * Application shell. A Material 3 top app bar plus a navigation drawer replaces
 * the old bottom navigation so the point-of-sale surface stays distraction free.
 */
public class 类042 extends AppCompatActivity {

    private ActivityMainBinding binding;
    private 类046 prefs;
    private String currentTitle = "";
    private boolean unlockLaunched;
    private static final int REQUEST_UNLOCK = 701;
    private 类010 deviceOwnerManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        prefs = 类046.get(this);
        deviceOwnerManager = new 类010(this);

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayShowTitleEnabled(false);

        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, binding.drawerLayout, binding.toolbar,
                R.string.open_menu, R.string.close_menu);
        binding.drawerLayout.addDrawerListener(toggle);
        toggle.syncState();
        binding.drawerLayout.setScrimColor(0x66101828);

        binding.navigationView.setNavigationItemSelectedListener(this::onDrawerItem);
        binding.toolbar.setOnMenuItemClickListener(this::onTopBarItem);

        bindDrawerHeader();
        syncThemeIcon();

        if (savedInstanceState == null) {
            binding.navigationView.setCheckedItem(R.id.nav_billing);
            select(R.id.nav_billing);
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START);
                } else if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                    getSupportFragmentManager().popBackStackImmediate();
                    syncCurrentSection();
                } else {
                    // POS is the root screen. Back should leave the application;
                    // secondary sections are handled by the back stack above.
                    finishAndRemoveTask();
                }
            }
        });

        requestBluetoothPermissionIfNeeded();
        requestNotificationPermissionIfNeeded();
        enforceAccess();
        // onResume() runs right after onCreate() and owns the licence check,
        // so the gate is asked exactly once per foreground.
    }

    private void bindDrawerHeader() {
        android.view.View header = binding.navigationView.getHeaderView(0);
        applyHeaderInset(header);
        TextView store = header.findViewById(R.id.headerStore);
        TextView user = header.findViewById(R.id.headerUser);
        TextView plan = header.findViewById(R.id.headerPlan);
        TextView licenseDays = header.findViewById(R.id.headerLicenseDays);
        store.setText(prefs.getThemeAppName());
        String name = prefs.getFullName();
        if (name == null || name.trim().isEmpty()) name = prefs.getUsername();
        user.setText((name == null ? "Signed in" : name) + " · " + prefs.getUserRole());
        plan.setVisibility(View.GONE);
        licenseDays.setText(formatLicenseDays(prefs));
    }

    /** Keeps the drawer header clear of the status bar on edge-to-edge devices. */
    private void applyHeaderInset(android.view.View header) {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(header, (view, insets) -> {
            int top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).top;
            view.setPadding(view.getPaddingLeft(), Math.round(20 * getResources()
                            .getDisplayMetrics().density) + top,
                    view.getPaddingRight(), view.getPaddingBottom());
            return insets;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(header);
    }

    /** Opens the existing navigation drawer from an in-screen POS header. */
    public void openNavigationDrawer() {
        binding.drawerLayout.openDrawer(GravityCompat.START);
    }

    /** Opens the existing invoice history screen from the POS invoice badge. */
    public void openInvoiceHistory() {
        binding.navigationView.setCheckedItem(R.id.nav_history);
        open(new 类072(), "Sales history");
    }

    private boolean onDrawerItem(MenuItem item) {
        int id = item.getItemId();
        binding.drawerLayout.closeDrawer(GravityCompat.START);
        if (id == R.id.nav_printer) {
            startActivity(new Intent(this, 类091.class));
            return true;
        }
        if (id == R.id.nav_plans) { showPlans(); return true; }
        if (id == R.id.nav_notifications) {
            startActivity(new Intent(this,
                    com.quicktap.pos.ui.notifications.类081.class));
            return true;
        }

        if (id == R.id.nav_contact) { contactAdmin(); return true; }
        if (id == R.id.nav_logout) { confirmSignOut(); return true; }
        select(id);
        return true;
    }

    /** Swaps the hosted section and keeps the top app bar copy in sync. */
    private void select(int id) {
        if (id == R.id.nav_dashboard) open(new 类068(), "Dashboard");
        else if (id == R.id.nav_products) open(new 类088(), "Catalogue");
        else if (id == R.id.nav_inventory) open(new 类040(), "Stock management");
        else if (id == R.id.nav_history) open(new 类072(), "Sales history");
        else if (id == R.id.nav_reports) open(new 类089(), "Reports");
        else if (id == R.id.nav_expenses) open(new 类070(), "Expense management");
        else if (id == R.id.nav_commercial) open(new 类067(), "Commercial modules");
        else if (id == R.id.nav_reviews) open(new 类090(), "Our reviews");
        else if (id == R.id.nav_settings) open(new 类092(), "Business settings");
        else open(new 类062(), "Point of sale");
    }

    private void open(Fragment fragment, String subtitle) {
        currentTitle = subtitle;
        applyToolbarTitles();
        binding.toolbar.setVisibility(fragment instanceof 类062 ? View.GONE : View.VISIBLE);
        if (fragment instanceof 类062) {
            getSupportFragmentManager().popBackStackImmediate(null,
                    androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
            show(fragment, false);
        } else {
            Fragment current = getSupportFragmentManager().findFragmentById(R.id.container);
            show(fragment, current != null);
        }
    }

    /** Restores the toolbar and drawer selection after a back-stack pop. */
    private void syncCurrentSection() {
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.container);
        if (current instanceof 类062) {
            currentTitle = "Point of sale";
            binding.toolbar.setVisibility(View.GONE);
            binding.navigationView.setCheckedItem(R.id.nav_billing);
        } else if (current instanceof 类068) {
            currentTitle = "Dashboard";
            binding.navigationView.setCheckedItem(R.id.nav_dashboard);
        } else if (current instanceof 类088) {
            currentTitle = "Catalogue";
            binding.navigationView.setCheckedItem(R.id.nav_products);
        } else if (current instanceof 类040) {
            currentTitle = "Stock management";
            binding.navigationView.setCheckedItem(R.id.nav_inventory);
        } else if (current instanceof 类072) {
            currentTitle = "Sales history";
            binding.navigationView.setCheckedItem(R.id.nav_history);
        } else if (current instanceof 类089) {
            currentTitle = "Reports";
            binding.navigationView.setCheckedItem(R.id.nav_reports);
        } else if (current instanceof 类070) {
            currentTitle = "Expense management";
            binding.navigationView.setCheckedItem(R.id.nav_expenses);
        } else if (current instanceof 类067) {
            currentTitle = "Commercial modules";
            binding.navigationView.setCheckedItem(R.id.nav_commercial);
        } else if (current instanceof 类090) {
            currentTitle = "Our reviews";
            binding.navigationView.setCheckedItem(R.id.nav_reviews);
        } else if (current instanceof 类092) {
            currentTitle = "Business settings";
            binding.navigationView.setCheckedItem(R.id.nav_settings);
        }
        binding.toolbar.setVisibility(current instanceof 类062 ? View.GONE : View.VISIBLE);
        applyToolbarTitles();
    }

    /** Called by Settings after the shop renames the app. */
    public void refreshAppName() {
        applyToolbarTitles();
        bindDrawerHeader();
    }

    /** The toolbar always shows the (server driven) app name plus the section. */
    private void applyToolbarTitles() {
        String name = prefs.getThemeAppName();
        if (name == null || name.trim().isEmpty()) name = prefs.getStoreName();
        String appName = prefs.getThemeAppName();
        if (appName == null || appName.trim().isEmpty()) appName = prefs.getStoreName();
        binding.toolbar.setTitle(appName == null ? "QuickTap POS" : appName);
        binding.toolbar.setSubtitle(currentTitle == null ? "" : currentTitle.toUpperCase(java.util.Locale.US));
    }

    private String formatLicenseDays(类046 prefs) {
        int days = prefs.getLicenseDaysLeft();
        if (days >= 0) {
            if (days == 0) return "License expires today";
            return "License: " + days + (days == 1 ? " day left" : " days left");
        }
        String duration = prefs.getLicenseDurationLabel();
        if (duration != null && !duration.trim().isEmpty()) return "License: " + duration;
        return "License: Active";
    }

    /** Keeps the top bar icon in sync with the active day / night mode. */
    private void syncThemeIcon() {
        MenuItem item = binding.toolbar.getMenu().findItem(R.id.action_theme);
        if (item == null) return;
        boolean dark = 类038.DARK.equals(类038.current(this));
        item.setIcon(dark ? R.drawable.ic_sun : R.drawable.ic_moon);
        item.setTitle(dark ? R.string.action_light_mode : R.string.action_dark_mode);
    }

    /** Light / Dark / System default — saved locally and applied instantly. */
    private void showThemePicker() {
        final String[] labels = {"Light", "Dark", "System default"};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.theme_mode_title)
                .setSingleChoiceItems(labels, 类038.index(this), (dialog, which) -> {
                    dialog.dismiss();
                    String chosen = 类038.fromIndex(which);
                    if (chosen.equals(类038.current(this))) {
                        syncThemeIcon();
                        return;
                    }
                    类038.set(this, chosen);
                    syncThemeIcon();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean onTopBarItem(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_theme) {
            showThemePicker();
            return true;
        }
        if (id == R.id.action_search) {
            binding.navigationView.setCheckedItem(R.id.nav_products);
            open(new 类088(), "Catalogue");
            return true;
        }

        if (id == R.id.action_notifications) {
            startActivity(new Intent(this,
                    com.quicktap.pos.ui.notifications.类081.class));
            return true;
        }

        if (id == R.id.action_profile) {
            String name = prefs.getFullName();
            if (name == null || name.trim().isEmpty()) name = prefs.getUsername();
            new MaterialAlertDialogBuilder(this)
                    .setTitle(name == null ? "Account" : name)
                    .setMessage("Role: " + prefs.getUserRole()
                            + "\nStore: " + prefs.getStoreName()
                            + "\nDevice: " + prefs.getDeviceId())
                    .setPositiveButton("Done", null)
                    .setNegativeButton("Sign out", (d, w) -> confirmSignOut())
                    .show();
            return true;
        }
        return false;
    }

    private void showPlans() {
        startActivity(new Intent(this, com.quicktap.pos.ui.plans.类085.class));
    }

    private void contactAdmin() {
        com.quicktap.pos.ui.support.类093.show(this, "Technical help");
    }


    private void confirmSignOut() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Sign out?")
                .setMessage("This counter will need to sign in again before taking orders.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Sign out", (d, w) -> {
                    类004.get(this).forceSignOut();
                    prefs.setDemoMode(false);
                    startActivity(new Intent(this, 类041.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    finish();
                })
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Only a formally provisioned Device Owner may enter kiosk mode. On
        // ordinary devices this is a safe no-op and normal POS behavior stays unchanged.
        if (deviceOwnerManager != null) deviceOwnerManager.enableKioskMode(this);
        类004 session = 类004.get(this);
        if (prefs.isDemoMode()) {
            applyFeatureMenuVisibility();
            session.markActivity();
            refreshTheme();
            bindDrawerHeader();
            applyToolbarTitles();
            syncThemeIcon();
            return;
        }
        if (!session.isSignedIn()) {
            startActivity(new Intent(this, 类041.class));
            finish();
            return;
        }
        if (session.shouldLock()) {
            if (!unlockLaunched) {
                unlockLaunched = true;
                startActivityForResult(new Intent(this, 类044.class), REQUEST_UNLOCK);
            }
            return;
        }
        unlockLaunched = false;
        applyFeatureMenuVisibility();
        session.markActivity();
        refreshTheme();
        bindDrawerHeader();
        applyToolbarTitles();
        syncThemeIcon();
        enforceLicense();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_UNLOCK) {
            unlockLaunched = false;
            if (resultCode == RESULT_OK) {
                类004.get(this).markActivity();
            }
        }
    }

    private void applyFeatureMenuVisibility() {
        if (binding == null || prefs == null) return;
        MenuItem stock = binding.navigationView.getMenu().findItem(R.id.nav_inventory);
        if (stock != null) stock.setVisible(true);
        MenuItem commercial = binding.navigationView.getMenu().findItem(R.id.nav_commercial);
        if (commercial != null) commercial.setVisible(prefs.isCustomerManagementEnabled()
                || prefs.isPurchaseSupplierEnabled() || prefs.isCashRegisterEnabled()
                || prefs.isReturnsRefundsEnabled() || prefs.isAdvancedReportsEnabled());
    }

    /**
     * Re-asks the SERVER for the licence state on every resume. The shared
     * banner shows "VERIFY YOUR LICENSE / CONTACT FOR LICENSE ACTIVATION" while
     * the account is not active, and the single {@link 类012} routes the
     * user away when the server says the app must stay locked.
     */
    private void enforceLicense() {
        binding.licenseBanner.apply(cachedHint());
        类013.status(this, state -> {
            if (isFinishing()) return;
            binding.licenseBanner.apply(state);
            if (类014.OFFLINE.equals(state.state)) {
                // Offline never activates anything: only an already confirmed
                // ACTIVE licence inside its grace window keeps selling.
                if (!类012.offlineGraceAllows(this)) 类012.route(this, state);
                return;
            }
            if (类014.ACTIVE.equals(state.state) && state.unlocked && state.confirmed) return;
            类012.route(this, state);
        });
    }

    /** Cached snapshot used only to paint the banner before the server answers. */
    private 类014 cachedHint() {
        类014 hint = new 类014();
        hint.state = prefs.getLicenseState();
        hint.unlocked = 类014.ACTIVE.equals(hint.state);
        hint.confirmed = prefs.isLicenseConfirmed();
        return hint;
    }

    /** Pulls the brand colours published by the Super Admin, at most once a minute. */
    private void refreshTheme() {
        long now = System.currentTimeMillis();
        if (now - lastThemeCheck < 60_000L) return;
        lastThemeCheck = now;
        com.quicktap.pos.theme.类036.refresh(this, changed -> {
            if (changed && !isFinishing()) recreate();
        });
    }

    private long lastThemeCheck;

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        类004.get(this).markActivity();
    }

    /** Pushes a fragment; secondary screens (like Reports) go on the back stack. */
    public void show(Fragment fragment, boolean addToBackStack) {
        androidx.fragment.app.FragmentTransaction tx = getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(R.anim.fragment_enter, R.anim.fragment_exit,
                        R.anim.fragment_pop_enter, R.anim.fragment_pop_exit)
                .replace(R.id.container, fragment);
        if (addToBackStack) tx.addToBackStack(null);
        tx.commit();
    }

    private void requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN}, 42);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 43);
        }
    }

    /**
     * Re-validates the account with the server. A blocked device, disabled user
     * or suspended shop sends the app to the locked screen.
     */
    private void enforceAccess() {
        if (prefs.isDemoMode()) return;
        类004.get(this).refreshProfile((ok, message, code) -> {
            if (ok) return;
            if ("DEVICE_MISMATCH".equals(code) || "SHOP_INACTIVE".equals(code)
                    || "USER_DISABLED".equals(code) || "SUBSCRIPTION_EXPIRED".equals(code)) {
                // The server is the authority: hand the routing to the single gate.
                类013.status(this, state -> 类012.route(this, state));
            }
        });
    }
}
