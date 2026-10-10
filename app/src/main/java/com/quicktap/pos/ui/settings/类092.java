package com.quicktap.pos.ui.settings;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.chip.Chip;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes;

import com.quicktap.pos.auth.类002;
import com.quicktap.pos.auth.类003;
import com.quicktap.pos.auth.类004;
import com.quicktap.pos.backup.类005;
import com.quicktap.pos.backup.类006;
import com.quicktap.pos.backup.类007;
import com.quicktap.pos.databinding.FragmentSettingsBinding;
import com.quicktap.pos.sync.类034;
import com.quicktap.pos.sync.类035;
import com.quicktap.pos.ui.类041;
import com.quicktap.pos.util.类045;
import com.quicktap.pos.util.类046;
import com.quicktap.pos.util.类047;
import com.quicktap.pos.util.类049;

/**
 * Store profile, tax/discount, theme, printer, security (fingerprint + auto
 * lock), cloud sync, Google Drive backup and sign-out.
 */
public class 类092 extends Fragment {

    private FragmentSettingsBinding binding;
    private 类046 prefs;
    private 类004 session;
    private ActivityResultLauncher<String> backupPicker;
    private ActivityResultLauncher<String[]> restorePicker;
    private ActivityResultLauncher<Intent> driveSignIn;
    private ActivityResultLauncher<Intent> localFolderPicker;
    private ActivityResultLauncher<String> backupFileSaver;
    private ActivityResultLauncher<String[]> backupFileOpener;
    private ActivityResultLauncher<String> logoPicker;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        prefs = 类046.get(requireContext());
        session = 类004.get(requireContext());

        backupPicker = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/octet-stream"),
                this::doBackup);
        restorePicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::doRestore);
        driveSignIn = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> handleDriveSignInResult(result.getResultCode(), result.getData()));
        localFolderPicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> { });
        backupFileSaver = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/octet-stream"),
                uri -> 类007.writeBackupFile(requireContext(), uri, pendingPassphrase,
                        (ok, message) -> toast(message)));
        backupFileOpener = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri == null) { toast("No backup file selected"); return; }
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Restore backup file?")
                            .setMessage("The selected QuickTap backup will replace current local data after integrity checks. A safety copy is created first.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Restore", (d, w) -> askPassphrase(
                                    "Restore backup file",
                                    "If this file was saved with a passphrase, type it here. "
                                            + "Otherwise leave it empty to use this device's key.",
                                    false, passphrase -> 类007.restoreBackupFile(
                                            requireContext(), uri, passphrase,
                                            (ok, message) -> toast(message))))
                            .show();
                });

        binding.inputStoreName.setText(prefs.getStoreName());
        binding.inputPhone.setText(prefs.getStorePhone());
        binding.inputAddress.setText(prefs.getStoreAddress());
        binding.inputCurrency.setText(prefs.getCurrency());
        binding.inputFooter.setText(prefs.getReceiptFooter());
        binding.inputInvoicePrefix.setText(prefs.getInvoicePrefix());
        binding.inputTax.setText(String.valueOf(prefs.getTaxPercent()));
        binding.inputDiscount.setText(String.valueOf(prefs.getDiscountPercent()));
        binding.inputReceiptCopies.setText(String.valueOf(prefs.getReceiptCopies()));
        binding.switchDark.setChecked(prefs.isDarkMode());

        binding.buttonSave.setOnClickListener(v -> save());
        binding.switchDark.setOnCheckedChangeListener((v, checked) -> {
            prefs.setDarkMode(checked);
            AppCompatDelegate.setDefaultNightMode(checked
                    ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        });
        binding.buttonPrinter.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), 类091.class)));
        binding.buttonBackup.setOnClickListener(v ->
                backupPicker.launch(类047.suggestedFileName()));
        binding.buttonRestore.setOnClickListener(v ->
                restorePicker.launch(new String[]{"*/*"}));
        binding.buttonSignOut.setOnClickListener(v -> signOut());

        setUpAppName();
        setUpReceiptDesign();
        setUpLiveReceiptSettings();
        setUpSecurity();
        setUpInventoryAlerts();
        setUpCommercialModules();
        setUpOrderTypes();
        setUpCloud();
    }

    // ------------------------------------------------------------- app name

    /**
     * The app name lives on the server (themes.app_name) so every device of the
     * shop follows it. We show the cached value instantly, refresh it from the
     * API, and push edits back through the existing endpoint.
     */
    private void setUpAppName() {
        binding.inputAppName.setText(prefs.getThemeAppName());
        binding.textAppNameStatus.setText("Current: " + prefs.getThemeAppName());
        binding.buttonSaveAppName.setOnClickListener(v -> saveAppName());
        fetchAppName();
    }

    /** Pulls the live value so the field never shows a stale name. */
    private void fetchAppName() {
        类045.io().execute(() -> {
            boolean changed = com.quicktap.pos.theme.类036.refreshBlocking(requireContext().getApplicationContext());
            类045.main().post(() -> {
                if (binding == null) return;
                binding.inputAppName.setText(prefs.getThemeAppName());
                binding.textAppNameStatus.setText("Current: " + prefs.getThemeAppName());
                if (changed) applyAppNameToShell();
            });
        });
    }

    private void saveAppName() {
        String name = binding.inputAppName.getText() == null
                ? "" : binding.inputAppName.getText().toString().trim();
        if (name.isEmpty() || name.length() > 40) {
            binding.textAppNameStatus.setText("Enter a name between 1 and 40 characters.");
            return;
        }
        binding.buttonSaveAppName.setEnabled(false);
        binding.textAppNameStatus.setText("Saving…");
        类045.io().execute(() -> {
            org.json.JSONObject body = new org.json.JSONObject();
            try { body.put("app_name", name); } catch (org.json.JSONException ignored) { }
            com.quicktap.pos.net.类017 res = com.quicktap.pos.net.类016.post(
                    requireContext().getApplicationContext(), "v1/settings/app-name", body, true);
            类045.main().post(() -> {
                if (binding == null) return;
                binding.buttonSaveAppName.setEnabled(true);
                if (res.success) {
                    prefs.setThemeAppName(name);
                    // The panel may answer with the full cached configuration.
                    org.json.JSONObject updated = res.dataOrEmpty().optJSONObject("config");
                    if (updated != null) {
                        com.quicktap.pos.theme.类036.applyConfig(
                                requireContext().getApplicationContext(), updated);
                    }
                    binding.textAppNameStatus.setText("Current: " + prefs.getThemeAppName());
                    applyAppNameToShell();
                    toast("App name updated");
                } else {
                    binding.textAppNameStatus.setText(res.message == null || res.message.isEmpty()
                            ? "Could not save the app name." : res.message);
                }
            });
        });
    }

    /** Refreshes the toolbar (and therefore the visible app name) right away. */
    private void applyAppNameToShell() {
        if (getActivity() instanceof com.quicktap.pos.ui.类042) {
            ((com.quicktap.pos.ui.类042) getActivity()).refreshAppName();
        }
    }

    // ------------------------------------------------------- receipt design

    private void setUpLiveReceiptSettings() {
        binding.inputStoreName.addTextChangedListener(watch(() -> prefs.setStoreName(text(binding.inputStoreName.getText()))));
        binding.inputPhone.addTextChangedListener(watch(() -> prefs.setStorePhone(text(binding.inputPhone.getText()))));
        binding.inputAddress.addTextChangedListener(watch(() -> prefs.setStoreAddress(text(binding.inputAddress.getText()))));
        binding.inputCurrency.addTextChangedListener(watch(() -> prefs.setCurrency(text(binding.inputCurrency.getText()))));
        binding.inputFooter.addTextChangedListener(watch(() -> prefs.setReceiptFooter(text(binding.inputFooter.getText()))));
        binding.inputReceiptNote.addTextChangedListener(watch(() -> prefs.setReceiptNote(text(binding.inputReceiptNote.getText()))));
    }

    private TextWatcher watch(Runnable save) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { save.run(); }
            @Override public void afterTextChanged(Editable s) { }
        };
    }

    private void setUpReceiptDesign() {
        logoPicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(), this::saveLogo);

        binding.switchReceiptLogo.setChecked(prefs.isReceiptLogoEnabled());
        binding.switchReceiptNote.setChecked(prefs.isReceiptNoteEnabled());
        binding.inputReceiptNote.setText(prefs.getReceiptNote());
        refreshLogoStatus();

        binding.switchReceiptLogo.setOnCheckedChangeListener((v, checked) -> {
            prefs.setReceiptLogoEnabled(checked);
            refreshLogoStatus();
        });
        binding.switchReceiptNote.setOnCheckedChangeListener((v, checked) ->
                prefs.setReceiptNoteEnabled(checked));
        binding.buttonReceiptLogo.setOnClickListener(v -> logoPicker.launch("image/*"));
    }

    /** Copies the picked image into app storage so printing works offline. */
    private void saveLogo(@Nullable Uri uri) {
        if (uri == null) return;
        类045.io().execute(() -> {
            boolean ok = false;
            java.io.File target = new java.io.File(requireContext().getFilesDir(), "receipt_logo.png");
            try (java.io.InputStream in = requireContext().getContentResolver().openInputStream(uri);
                 java.io.OutputStream out = new java.io.FileOutputStream(target)) {
                if (in != null) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                    ok = true;
                }
            } catch (Exception ignored) { }
            boolean saved = ok;
            类045.main().post(() -> {
                if (binding == null) return;
                if (saved) {
                    prefs.setReceiptLogoPath(target.getAbsolutePath());
                    prefs.setReceiptLogoEnabled(true);
                    binding.switchReceiptLogo.setChecked(true);
                    refreshLogoStatus();
                    Toast.makeText(requireContext(), "Logo saved", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(requireContext(), "Could not read that image",
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void refreshLogoStatus() {
        String path = prefs.getReceiptLogoPath();
        boolean has = path != null && !path.isEmpty() && new java.io.File(path).exists();
        binding.textReceiptLogoStatus.setText(has
                ? "Logo ready · printed at the top of every slip"
                : "No logo selected yet. A simple black and white image prints best.");
    }


    // ------------------------------------------------------------- security

    private void setUpSecurity() {
        boolean available = 类002.isAvailable(requireContext());
        binding.switchFingerprint.setEnabled(available);
        binding.switchFingerprint.setChecked(session.isFingerprintEnabled() && available);
        binding.textFingerprintHint.setText(available
                ? "Unlock the locked session with your fingerprint."
                : 类002.unavailableReason(requireContext()));

        binding.switchFingerprint.setOnCheckedChangeListener((v, checked) -> {
            if (checked && !类002.isAvailable(requireContext())) {
                binding.switchFingerprint.setChecked(false);
                toast(类002.unavailableReason(requireContext()));
                return;
            }
            session.setFingerprintEnabled(checked,
                    (ok, message, code) -> toast(checked ? "Fingerprint unlock on" : "Fingerprint unlock off"));
        });

        binding.inputAutoLock.setText(String.valueOf(session.autoLockMinutes()));
        binding.textDeviceBinding.setText("This device is bound to your account\n"
                + 类003.name());
    }

    // --------------------------------------------------------- inventory

    private void setUpInventoryAlerts() {
        binding.switchLowStockAlerts.setChecked(prefs.isLowStockAlertsEnabled());
        binding.switchLowStockAlerts.setOnCheckedChangeListener((button, checked) -> {
            prefs.setLowStockAlertsEnabled(checked);
            toast(checked ? "Low stock alerts on" : "Low stock alerts off");
        });
    }

    // ---------------------------------------------------------------- cloud

    private void setUpCloud() {
        refreshCloudStatus();

        // Sync is customer-triggered by default; the switch opts into background runs.
        binding.switchAutoSync.setChecked(prefs.isAutoSyncEnabled());
        binding.switchAutoSync.setOnCheckedChangeListener((v, checked) -> {
            prefs.setAutoSyncEnabled(checked);
            类035.applySchedule(requireContext().getApplicationContext());
            toast(checked ? "Auto sync on" : "Auto sync off — use Sync now");
        });

        binding.switchWeeklyBackup.setChecked(prefs.isWeeklyBackupEnabled());
        binding.switchWeeklyBackup.setOnCheckedChangeListener((v, checked) -> {
            prefs.setWeeklyBackupEnabled(checked);
            if (checked) 类005.schedule(requireContext().getApplicationContext());
            else 类005.cancel(requireContext().getApplicationContext());
            toast(checked ? "Weekly backup on" : "Weekly backup off");
        });

        binding.buttonSyncNow.setOnClickListener(v -> {
            binding.buttonSyncNow.setEnabled(false);
            类034.syncNow(requireContext().getApplicationContext(), (ok, message) -> {
                if (!isAdded()) return;
                binding.buttonSyncNow.setEnabled(true);
                toast(message);
                refreshCloudStatus();
            });
        });
        binding.buttonDriveConnect.setOnClickListener(v -> {
            if (类006.isConnected(requireContext())) {
                类006.disconnect(requireContext(), (ok, message) -> {
                    toast(message);
                    refreshCloudStatus();
                });
            } else {
                int playServices = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(requireContext());
                if (playServices != ConnectionResult.SUCCESS) {
                    if (GoogleApiAvailability.getInstance().isUserResolvableError(playServices)) {
                        GoogleApiAvailability.getInstance().getErrorDialog(requireActivity(), playServices, 9041).show();
                    } else {
                        toast("Google Play Services is unavailable on this device.");
                    }
                    return;
                }
                driveSignIn.launch(类006.signInIntent(requireContext()));
            }
        });
        binding.buttonChooseLocalBackupFolder.setVisibility(View.GONE);
        binding.buttonLocalBackup.setOnClickListener(v -> askPassphrase(
                "Back up to this device",
                "A copy is written to Download/QuickTap POS. Leave the passphrase empty to seal "
                        + "it with this device's key; set one if you may need to restore it on "
                        + "another phone.",
                true, passphrase -> {
                    pendingPassphrase = passphrase;
                    类007.downloadToDownloads(requireContext(), passphrase, (ok, message) -> {
                        if (ok) { toast(message); return; }
                        backupFileSaver.launch("QuickTap_POS_Backup_"
                                + 类049.fileStamp(System.currentTimeMillis()) + ".qtpbackup");
                    });
                }));
        binding.buttonLocalRestore.setOnClickListener(v -> backupFileOpener.launch(new String[]{"application/octet-stream", "*/*"}));
        binding.buttonDriveBackup.setOnClickListener(v -> askPassphrase(
                "Back up to Google Drive",
                "Leave this empty to seal the copy with this device's hardware key — it can "
                        + "then only be restored on this phone. Set a passphrase instead if a "
                        + "till may be replaced, so the same copy opens anywhere.",
                true, passphrase -> {
                    binding.buttonDriveBackup.setEnabled(false);
                    类006.backupNow(requireContext(), passphrase, (ok, message) -> {
                        if (!isAdded()) return;
                        binding.buttonDriveBackup.setEnabled(true);
                        toast(message);
                        refreshCloudStatus();
                    });
                }));
        binding.buttonDriveRestore.setOnClickListener(v -> {
            if (!类006.isConnected(requireContext())) { toast("Connect a Google account first"); return; }
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Restore QuickTap POS data?")
                    .setMessage("This may replace current local POS data. A safety copy is created before restore.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Restore", (dialog, which) -> {
                        runDriveRestore();
                    }).show();
        });
    }

    /** Restores the newest Drive copy, asking for the passphrase when it has one. */
    private void runDriveRestore() {
        if (!prefs.isDriveBackupPassphrased()) {
            类006.restoreLatest(requireContext(), (ok, message) -> onRestoreFinished(ok, message));
            return;
        }
        askPassphrase("Restore from Google Drive",
                "This backup is protected by a passphrase. Type the one used when it was made.",
                false, passphrase -> {
                    binding.buttonDriveRestore.setEnabled(false);
                    类006.restoreLatest(requireContext(), passphrase,
                            (ok, message) -> onRestoreFinished(ok, message));
                });
    }

    private void onRestoreFinished(boolean ok, String message) {
        if (!isAdded()) return;
        binding.buttonDriveRestore.setEnabled(true);
        toast(message);
        refreshCloudStatus();
    }

    /**
     * Asks for the optional backup passphrase.
     *
     * @param confirm when true the value must be typed twice and be at least 8
     *                characters — a passphrase that is mistyped once makes the
     *                backup unreadable forever, so the extra field is worth it.
     */
    private void askPassphrase(String title, String blurb, boolean confirm,
                               PassphraseListener listener) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(density * 20);
        box.setPadding(pad, Math.round(density * 6), pad, 0);

        TextView hint = new TextView(requireContext());
        hint.setText(blurb);
        hint.setTextSize(13f);
        hint.setTextColor(com.quicktap.pos.theme.类036.textMuted(requireContext()));
        box.addView(hint);

        EditText input = new EditText(requireContext());
        input.setHint("Passphrase — leave empty for this device's key");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(input);

        EditText again = null;
        if (confirm) {
            again = new EditText(requireContext());
            again.setHint("Type it again to be sure");
            again.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            box.addView(again);
        }

        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(box);

        final EditText second = again;
        final androidx.appcompat.app.AlertDialog dialog =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle(title)
                        .setView(scroll)
                        .setNegativeButton(confirm ? "Use device key" : "No passphrase", null)
                        .setPositiveButton(confirm ? "Use passphrase" : "Restore", null)
                        .create();
        dialog.setOnShowListener(shown -> {
            if (shown.getWindow() != null) {
                shown.getWindow().setSoftInputMode(
                        android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        });
        dialog.show();

        // Validated on click, never on dismiss, so a typo cannot silently produce a
        // backup that can never be opened again.
        android.widget.Button positive = dialog.findViewById(android.R.id.button1);
        android.widget.Button negative = dialog.findViewById(android.R.id.button2);
        if (positive != null) positive.setOnClickListener(v -> {
            String value = input.getText() == null ? "" : input.getText().toString();
            if (value.isEmpty()) { dialog.dismiss(); listener.onPassphrase(""); return; }
            if (value.trim().length() < 8) { input.setError("Use at least 8 characters"); return; }
            if (second != null && !value.equals(second.getText().toString())) {
                second.setError("The two entries do not match");
                return;
            }
            dialog.dismiss();
            listener.onPassphrase(value);
        });
        if (negative != null) negative.setOnClickListener(v -> {
            dialog.dismiss();
            listener.onPassphrase("");
        });
    }

    /** What the caller does once the passphrase question is answered. */
    private interface PassphraseListener { void onPassphrase(String passphrase); }

    /** Passphrase chosen for the backup that is currently waiting on a file picker. */
    private String pendingPassphrase = "";

    private void handleDriveSignInResult(int resultCode, @Nullable Intent data) {
        if (!isAdded()) return;
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            String detail = "";
            if (data != null) {
                int status = data.getIntExtra("googleSignInStatus", Integer.MIN_VALUE);
                if (status != Integer.MIN_VALUE) detail = " (" + GoogleSignInStatusCodes.getStatusCodeString(status) + ", code " + status + ")";
                else if (data.getExtras() != null && !data.getExtras().isEmpty()) detail = " (Google returned an authorization error)";
            }
            toast(resultCode == android.app.Activity.RESULT_CANCELED
                    ? "Google Drive authorization was cancelled or rejected" + detail + ". Check that a Google account is present and the app's package/SHA-1 is registered."
                    : "Google Drive sign-in did not complete" + detail);
            refreshCloudStatus();
            return;
        }
        try {
            GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException.class);
            toast("Google Drive connected");
        } catch (ApiException e) {
            String message;
            switch (e.getStatusCode()) {
                case 10: message = "Google OAuth configuration error. Add this app package and SHA-1 certificate in Google Cloud."; break;
                case 7: message = "Google Drive connection needs internet access."; break;
                case 12501: message = "Google Drive connection cancelled."; break;
                case 12500: message = "Google sign-in failed. Check the Google account and OAuth setup."; break;
                default: message = "Google Drive connection failed (code " + e.getStatusCode() + ").";
            }
            toast(message);
        } catch (Exception e) {
            toast("Google Drive connection failed. Please try again.");
        }
        refreshCloudStatus();
    }

    private void refreshCloudStatus() {
        if (!isAdded() || binding == null) return;
        long lastSync = prefs.getLastSyncAt();
        binding.textSyncStatus.setText(lastSync == 0
                ? "Never synced — data is stored offline until the first sync"
                : "Last sync: " + 类049.receipt(lastSync));

        boolean connected = 类006.isConnected(requireContext());
        String email = 类006.connectedEmail(requireContext());
        long lastBackup = prefs.getLastBackupAt();
        StringBuilder status = new StringBuilder();
        if (!connected) {
            status.append(类006.isOAuthConfigured(requireContext())
                    ? "Google Drive not connected"
                    : "Google Drive needs setup: this build has no OAuth client id "
                            + "(see DRIVE_BACKUP_SETUP.md)");
        } else {
            status.append("Connected: ").append(email == null ? "Google account" : email);
            status.append(prefs.isDriveBackupPassphrased()
                    ? "\nNewest copy: protected by your passphrase"
                    : "\nNewest copy: this device's key");
            status.append(lastBackup == 0
                    ? "\nNo backup yet\nNext backup: when weekly backup is due"
                    : "\nLast backup: " + 类049.receipt(lastBackup)
                            + "\nNext backup: " + 类049.receipt(lastBackup + 类006.WEEK_MS));
        }
        String lastError = prefs.getDriveLastError();
        if (!lastError.isEmpty()) status.append("\nLast problem: ").append(lastError);
        if (prefs.isDriveNeedsConsent()) status.append("\nGoogle is waiting for the Drive permission.");
        binding.textDriveStatus.setText(status);
        binding.buttonDriveConnect.setText(connected ? "Disconnect Google Drive" : "Connect Google Drive");
        binding.buttonDriveBackup.setEnabled(connected);
        binding.textLocalBackupStatus.setText("Backup files are downloaded manually to a location you choose. Restore accepts a QuickTap .qtpbackup file.");
        binding.buttonLocalBackup.setEnabled(true);
        binding.buttonLocalRestore.setEnabled(true);
    }

    private void setUpOrderTypes() {
        renderOrderTypes();
        binding.buttonAddOrderType.setOnClickListener(v -> showOrderTypeDialog(-1));
    }

    private void renderOrderTypes() {
        if (binding == null) return;
        binding.orderTypesGroup.removeAllViews();
        java.util.List<String> types = prefs.getOrderTypes();
        for (int i = 0; i < types.size(); i++) {
            final int index = i;
            Chip chip = new Chip(requireContext());
            chip.setText(types.get(i));
            chip.setCloseIconVisible(true);
            chip.setOnClickListener(v -> showOrderTypeDialog(index));
            chip.setOnCloseIconClickListener(v -> {
                java.util.List<String> next = prefs.getOrderTypes();
                if (next.size() <= 1) { toast("Keep at least one order type"); return; }
                next.remove(index); prefs.setOrderTypes(next); renderOrderTypes();
            });
            binding.orderTypesGroup.addView(chip);
        }
    }

    private void showOrderTypeDialog(int index) {
        EditText input = new EditText(requireContext());
        input.setSingleLine(true); input.setHint("e.g. Dine In, Rider, Take Away");
        if (index >= 0) input.setText(prefs.getOrderTypes().get(index));
        LinearLayout box = new LinearLayout(requireContext());
        box.setPadding(40, 0, 40, 0); box.addView(input);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(index < 0 ? "Add order type" : "Edit order type")
                .setView(box).setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    String value = input.getText() == null ? "" : input.getText().toString().trim();
                    if (value.isEmpty()) { toast("Enter an order type"); return; }
                    java.util.List<String> next = prefs.getOrderTypes();
                    for (int i = 0; i < next.size(); i++) if (i != index && next.get(i).equalsIgnoreCase(value)) { toast("Order type already exists"); return; }
                    if (index < 0) next.add(value); else next.set(index, value);
                    prefs.setOrderTypes(next); renderOrderTypes();
                }).show();
    }

    private void setUpCommercialModules() {
        binding.switchCustomers.setChecked(prefs.isCustomerLedgerEnabled());
        binding.switchCustomers.setOnCheckedChangeListener((button, checked) -> prefs.setCustomerLedgerEnabled(checked));
        binding.switchPurchases.setOnCheckedChangeListener(null);
        binding.switchCashRegister.setOnCheckedChangeListener(null);
        binding.switchReturns.setOnCheckedChangeListener(null);
        binding.switchAdvancedReports.setOnCheckedChangeListener(null);
    }

    private void signOut() {
        session.logout((ok, message, code) -> {
            startActivity(new Intent(requireContext(), 类041.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            requireActivity().finish();
        });
    }

    private void save() {
        prefs.setStoreName(text(binding.inputStoreName.getText()));
        prefs.setStorePhone(text(binding.inputPhone.getText()));
        prefs.setStoreAddress(text(binding.inputAddress.getText()));
        prefs.setCurrency(text(binding.inputCurrency.getText()));
        prefs.setReceiptFooter(text(binding.inputFooter.getText()));
        prefs.setReceiptNote(text(binding.inputReceiptNote.getText()));
        prefs.setInvoicePrefix(text(binding.inputInvoicePrefix.getText()));
        prefs.setTaxPercent(parse(text(binding.inputTax.getText())));
        prefs.setDiscountPercent(parse(text(binding.inputDiscount.getText())));
        int copies = Math.round(parse(text(binding.inputReceiptCopies.getText())));
        prefs.setReceiptCopies(copies <= 0 ? 1 : copies);
        session.setAutoLockMinutes((int) parse(text(binding.inputAutoLock.getText())));
        Toast.makeText(requireContext(), "Settings saved", Toast.LENGTH_SHORT).show();
    }


    private void doBackup(Uri uri) {
        if (uri == null) return;
        类045.io().execute(() -> {
            try {
                类047.backupTo(requireContext(), uri);
                类045.main().post(() -> toast("Backup saved"));
            } catch (Exception e) {
                类045.main().post(() -> toast("Backup failed: " + e.getMessage()));
            }
        });
    }

    private void doRestore(Uri uri) {
        if (uri == null) return;
        类045.io().execute(() -> {
            try {
                类047.restoreFrom(requireContext(), uri);
                类045.main().post(() -> toast("Restored. Please reopen the app."));
            } catch (Exception e) {
                类045.main().post(() -> toast("Restore failed: " + e.getMessage()));
            }
        });
    }

    private void toast(String message) {
        if (isAdded()) Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
    }

    private float parse(String value) {
        try { return Float.parseFloat(value); } catch (Exception e) { return 0f; }
    }

    private String text(CharSequence value) { return value == null ? "" : value.toString().trim(); }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
