package com.quicktap.pos.ui.cloudsync;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.quicktap.pos.R;
import com.quicktap.pos.cloudsync.CloudSyncEngine;
import com.quicktap.pos.cloudsync.CloudSyncGate;
import com.quicktap.pos.cloudsync.CloudSyncHooks;
import com.quicktap.pos.cloudsync.CloudSyncSecureStore;
import com.quicktap.pos.cloudsync.CloudSyncState;
import com.quicktap.pos.cloudsync.CloudSyncStatus;
import com.quicktap.pos.cloudsync.CloudSyncStore;
import com.quicktap.pos.cloudsync.CloudSyncWorker;
import com.quicktap.pos.databinding.ActivityCloudSyncBinding;
import com.quicktap.pos.util.类045;

import java.util.Locale;

/**
 * Cloud Sync screen: enter the endpoint + API key, see the plan status, sync
 * now, import onto a new device, upload pre-existing local data, disconnect.
 *
 * Nothing here touches POS data directly - every action goes through
 * com.quicktap.pos.cloudsync.CloudSyncEngine on a background thread.
 */
public class CloudSyncActivity extends AppCompatActivity
        implements CloudSyncStatus.Listener {

    private ActivityCloudSyncBinding binding;
    private boolean busy;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.quicktap.pos.ui.license.类075.protect(this);
        binding = ActivityCloudSyncBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.toolbar.setNavigationOnClickListener(v -> finish());

        CloudSyncSecureStore secure = CloudSyncSecureStore.get(this);
        binding.inputCloudEndpoint.setText(secure.endpoint());
        if (!secure.apiKey().isEmpty()) {
            binding.inputCloudApiKey.setHint("A key is saved - type a new one to replace it");
        }

        binding.buttonCloudConnect.setOnClickListener(v -> saveAndConnect());
        binding.buttonCloudSyncNow.setOnClickListener(v -> syncNow());
        binding.buttonCloudImport.setOnClickListener(v -> confirmImport());
        binding.buttonCloudUploadAll.setOnClickListener(v -> confirmUploadAll());
        binding.buttonCloudDisconnect.setOnClickListener(v -> confirmDisconnect());

        CloudSyncStatus.addListener(this);
        render(CloudSyncStatus.get());
        类045.io().execute(() -> {
            CloudSyncStatus status = CloudSyncStatus.refresh(this);
            类045.main().post(() -> render(status));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        com.quicktap.pos.ui.license.类075.protect(this);
        类045.io().execute(() -> {
            CloudSyncStatus status = CloudSyncStatus.refresh(this);
            类045.main().post(() -> {
                if (!isFinishing()) {
                    render(status);
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        CloudSyncStatus.removeListener(this);
        super.onDestroy();
    }

    @Override
    public void onChanged(CloudSyncStatus status) {
        if (!isFinishing()) {
            render(status);
        }
    }

    // ------------------------------------------------------------- rendering

    private void render(CloudSyncStatus status) {
        if (binding == null) {
            return;
        }
        binding.textCloudStatus.setText(label(status.state));
        binding.textCloudStatus.setTextColor(statusColor(status.state));

        String plan = status.planName == null || status.planName.isEmpty()
                ? "-" : status.planName;
        binding.textCloudPlan.setText("Plan: " + plan);

        org.json.JSONObject snapshot = CloudSyncStoreHolder.planSnapshot(this);
        int usersUsed = snapshot.optJSONObject("usage") == null ? -1
                : snapshot.optJSONObject("usage").optInt("users_active", -1);
        int usersMax = snapshot.optJSONObject("limits") == null ? -1
                : snapshot.optJSONObject("limits").optInt("max_users", -1);
        int devUsed = snapshot.optJSONObject("usage") == null ? -1
                : snapshot.optJSONObject("usage").optInt("devices_active", -1);
        int devMax = snapshot.optJSONObject("limits") == null ? -1
                : snapshot.optJSONObject("limits").optInt("max_devices", -1);
        binding.textCloudUsage.setText(String.format(Locale.US,
                "Users %s/%s   Devices %s/%s",
                usersUsed < 0 ? "-" : usersUsed, usersMax < 0 ? "-" : usersMax,
                devUsed < 0 ? "-" : devUsed, devMax < 0 ? "-" : devMax));

        binding.textCloudPending.setText("Pending changes: " + status.pending);
        binding.textCloudLastSync.setText("Last sync: "
                + (status.lastSyncAt <= 0 ? "never" : formatTime(status.lastSyncAt)));
        binding.textCloudDevice.setText("This device: " + status.deviceName);

        boolean connected = status.state == CloudSyncState.CONNECTED
                || status.state == CloudSyncState.OFFLINE
                || status.state == CloudSyncState.CONNECTING;
        binding.buttonCloudSyncNow.setEnabled(connected && !busy);
        binding.buttonCloudImport.setEnabled(connected && !busy);
        binding.buttonCloudUploadAll.setEnabled(connected && !busy);
        binding.buttonCloudDisconnect.setEnabled(!busy
                && CloudSyncSecureStore.get(this).isConfigured());
        binding.buttonCloudConnect.setEnabled(!busy);

        String hint = status.message == null || status.message.isEmpty() ? "" : status.message;
        if (hint.isEmpty()) {
            hint = hintFor(status.state);
        }
        // A seat / device limit is not a sync failure, so show it alongside.
        String limit = CloudSyncEngine.planLimitMessage(this);
        if (!limit.isEmpty()) {
            hint = hint.isEmpty() ? limit : limit + "\n" + hint;
        }
        binding.textCloudHint.setText(hint);
    }

    private String hintFor(CloudSyncState state) {
        switch (state) {
            case CONNECTED:
                return "Changes upload automatically. Tap Sync now to force it.";
            case OFFLINE:
                return "No internet right now. Changes stay queued and upload later.";
            case INVALID_KEY:
                return "This key was rejected. Enter the current key from the portal.";
            case NOT_IN_PLAN:
                return "Cloud Sync is not included in your plan. Upgrade to switch it on.";
            case PLAN_EXPIRED:
                return "Your subscription ended. Renew to resume syncing - nothing was deleted.";
            case ERROR:
                return "The last attempt failed. It will be retried automatically.";
            default:
                return "Paste the endpoint and API key from your customer portal.";
        }
    }

    private String label(CloudSyncState state) {
        switch (state) {
            case CONNECTING:
                return "Connecting…";
            case CONNECTED:
                return "Connected";
            case OFFLINE:
                return "Offline - changes queued";
            case INVALID_KEY:
                return "Invalid key";
            case NOT_IN_PLAN:
                return "Not included in your plan";
            case PLAN_EXPIRED:
                return "Plan expired";
            case ERROR:
                return "Error";
            default:
                return "Not connected";
        }
    }

    private int statusColor(CloudSyncState state) {
        int resId;
        switch (state) {
            case CONNECTED:
                resId = android.R.attr.textColorPrimary;
                break;
            case OFFLINE:
                resId = android.R.attr.textColorSecondary;
                break;
            case CONNECTING:
                resId = android.R.attr.textColorSecondary;
                break;
            default:
                resId = android.R.attr.textColorPrimary;
                break;
        }
        int[] attrs = new int[]{resId};
        android.content.res.TypedArray a = obtainStyledAttributes(attrs);
        int color = a.getColor(0, 0xFF000000);
        a.recycle();

        if (state == CloudSyncState.INVALID_KEY
                || state == CloudSyncState.NOT_IN_PLAN
                || state == CloudSyncState.PLAN_EXPIRED
                || state == CloudSyncState.ERROR) {
            return getResources().getColor(R.color.danger, getTheme());
        }
        return color;
    }

    private String formatTime(long millis) {
        return java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(new java.util.Date(millis));
    }

    // --------------------------------------------------------------- actions

    private void saveAndConnect() {
        String endpoint = text(binding.inputCloudEndpoint.getText());
        String apiKey = text(binding.inputCloudApiKey.getText());

        if (endpoint.isEmpty()) {
            binding.inputCloudEndpoint.setError("Endpoint is required");
            return;
        }
        if (!endpoint.toLowerCase(Locale.US).startsWith("https://")) {
            binding.inputCloudEndpoint.setError("The endpoint must start with https://");
            return;
        }
        if (apiKey.isEmpty()) {
            binding.inputCloudApiKey.setError("API key is required");
            return;
        }

        setBusy(true);
        类045.io().execute(() -> {
            CloudSyncEngine.ConnectResult result = CloudSyncEngine.connect(
                    getApplicationContext(), endpoint, apiKey);
            类045.main().post(() -> {
                setBusy(false);
                if (result.ok) {
                    CloudSyncGate.invalidate();
                    CloudSyncWorker.applySchedule(getApplicationContext());
                    binding.inputCloudApiKey.setText("");
                    afterConnect();
                    toast("Connected");
                } else {
                    toast(result.message);
                }
                类045.io().execute(() -> {
                    CloudSyncStatus status = CloudSyncStatus.refresh(this);
                    类045.main().post(() -> render(status));
                });
            });
        });
    }

    /**
     * First successful connect, per spec 3.5:
     *  - the cloud already holds data  -> offer to import it onto this device
     *  - the cloud is empty            -> offer to seed it from this device
     */
    private void afterConnect() {
        类045.io().execute(() -> {
            final int total = CloudSyncEngine.serverRecordCount(getApplicationContext());
            final boolean hasLocal = com.quicktap.pos.cloudsync.CloudSyncCodec
                    .localRecordCount(getApplicationContext()) > 0;
            类045.main().post(() -> {
                if (isFinishing()) {
                    return;
                }
                if (total > 0) {
                    showImportDialog(hasLocal
                            ? "Your cloud account already holds " + total + " records."
                            : "Your cloud account holds " + total + " records.");
                } else if (total == 0 && hasLocal) {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Upload this device's data?")
                            .setMessage("Your cloud account is empty. Upload the products, "
                                    + "categories, sales and customers that are already on "
                                    + "this device so other devices can import them.")
                            .setNegativeButton("Not now", null)
                            .setPositiveButton("Upload", (d, w) -> uploadAll())
                            .show();
                }
            });
        });
    }

    private void syncNow() {
        setBusy(true);
        CloudSyncEngine.syncNow(getApplicationContext(), (ok, message) -> {
            setBusy(false);
            toast(message);
        });
    }

    private void confirmImport() {
        showImportDialog(null);
    }

    /**
     * Spec 3.5: warn when the device already holds data and give three choices.
     * The safest one (merge, nothing deleted) is the default.
     */
    private void showImportDialog(String extraLine) {
        boolean hasLocal = com.quicktap.pos.cloudsync.CloudSyncCodec
                .localRecordCount(getApplicationContext()) > 0;
        StringBuilder message = new StringBuilder();
        if (extraLine != null && !extraLine.isEmpty()) {
            message.append(extraLine).append("\n\n");
        }
        if (hasLocal) {
            message.append("This device already contains data.\n\n")
                    .append("Merge - combine both sides; when the same record exists in "
                            + "both places the most recently changed one wins. Nothing local "
                            + "is deleted. (recommended)\n\n")
                    .append("Import only missing - add the records this device has never "
                            + "seen and leave every existing local record untouched.\n\n")
                    .append("A safety copy of your local database is taken automatically "
                            + "before the import starts.");
        } else {
            message.append("Records stored on the server will be downloaded to this device.");
        }

        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Import data from cloud?")
                .setMessage(message.toString())
                .setNegativeButton("Cancel", null);

        if (hasLocal) {
            dialog.setPositiveButton("Merge", (d, w) ->
                            runImport(CloudSyncEngine.ImportMode.MERGE))
                    .setNeutralButton("Import only missing", (d, w) ->
                            runImport(CloudSyncEngine.ImportMode.ONLY_MISSING));
        } else {
            dialog.setPositiveButton("Import", (d, w) ->
                    runImport(CloudSyncEngine.ImportMode.MERGE));
        }
        dialog.show();
    }

    private void runImport(CloudSyncEngine.ImportMode mode) {
        setBusy(true);
        类045.io().execute(() -> {
            int applied = CloudSyncEngine.importFromCloud(getApplicationContext(),
                    (done, total) -> 类045.main().post(() -> {
                        if (binding == null) {
                            return;
                        }
                        binding.textCloudLog.setText(total > 0
                                ? "Importing… " + done + " / " + total + " records"
                                : "Importing... " + done + " records");
                    }), mode);
            类045.main().post(() -> {
                setBusy(false);
                toast(applied < 0 ? "Import failed. Please try again."
                        : "Imported " + applied + " records");
                类045.io().execute(() -> {
                    CloudSyncStatus status = CloudSyncStatus.refresh(this);
                    类045.main().post(() -> {
                        render(status);
                        if (binding != null) {
                            binding.textCloudLog.setText("");
                        }
                    });
                });
            });
        });
    }

    private void confirmUploadAll() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Upload existing data?")
                .setMessage("Everything already stored on this device will be queued and "
                        + "uploaded once. Use this after switching Cloud Sync on for the "
                        + "first time, or after restoring a backup.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Upload", (d, w) -> uploadAll())
                .show();
    }

    private void uploadAll() {
        setBusy(true);
        类045.io().execute(() -> {
            int queued = CloudSyncHooks.enqueueEverything(getApplicationContext());
            CloudSyncEngine.SyncOutcome outcome = CloudSyncEngine.syncOnce(getApplicationContext());
            类045.main().post(() -> {
                setBusy(false);
                toast(queued == 0 ? "Nothing to upload"
                        : outcome.ok ? "Uploaded " + queued + " records"
                        : queued + " records queued - they will upload automatically");
                类045.io().execute(() -> {
                    CloudSyncStatus status = CloudSyncStatus.refresh(this);
                    类045.main().post(() -> render(status));
                });
            });
        });
    }

    private void confirmDisconnect() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Disconnect Cloud Sync?")
                .setMessage("The API key is removed and syncing stops.\n\n"
                        + "Everything already on this device stays exactly as it is.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Disconnect", (d, w) -> {
                    CloudSyncWorker.cancelSchedule(getApplicationContext());
                    CloudSyncEngine.disconnect(getApplicationContext());
                    CloudSyncGate.invalidate();
                    if (binding != null) {
                        binding.inputCloudApiKey.setText("");
                    }
                    toast("Disconnected - your local data is untouched");
                    render(CloudSyncStatus.get());
                })
                .show();
    }

    // ---------------------------------------------------------------- utils

    private void setBusy(boolean value) {
        busy = value;
        if (binding == null) {
            return;
        }
        binding.progressCloud.setVisibility(value ? View.VISIBLE : View.GONE);
        render(CloudSyncStatus.get());
    }

    private String text(@Nullable CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private void toast(String message) {
        if (!isFinishing() && !TextUtils.isEmpty(message)) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }

    /** Tiny holder so the UI can read the cached plan snapshot off the main thread. */
    private static final class CloudSyncStoreHolder {
        static org.json.JSONObject planSnapshot(Context context) {
            try {
                return CloudSyncStore.get(context).plan();
            } catch (Throwable e) {
                return new org.json.JSONObject();
            }
        }
    }
}
