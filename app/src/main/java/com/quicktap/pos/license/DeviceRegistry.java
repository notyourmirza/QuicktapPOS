package com.quicktap.pos.license;

import android.content.Context;

import com.quicktap.pos.BuildConfig;
import com.quicktap.pos.auth.类003;
import com.quicktap.pos.net.类016;
import com.quicktap.pos.net.类017;
import com.quicktap.pos.util.类045;
import com.quicktap.pos.util.类046;
import com.quicktap.pos.util.类049;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Device accounting for one shop.
 *
 * <p>Every install already has a stable, opaque identity ({@link 类003}) that is
 * sent on every request as {@code X-Device-Id}, and the server binds a licence to
 * it. This class is the missing other half: it lets the shop <em>see</em> which
 * devices the panel has recorded for its account, which one is this phone, when
 * each was last seen, and how many of the seats its plan allows are used.</p>
 *
 * <p>The list is cached (as raw JSON in prefs) so the screen renders instantly and
 * still shows something useful offline. Nothing here decides access: the server
 * rejects protected calls for an unbound or blocked device no matter what the
 * cache says.</p>
 */
public final class DeviceRegistry {

    /** One device as recorded by the server. */
    public static final class Device {
        public String hash = "";
        public String label = "";
        public String model = "";
        public String osVersion = "";
        public String appVersion = "";
        public String status = "ACTIVE";
        public long firstSeenAt;
        public long lastSeenAt;
        public boolean current;

        public boolean active() { return "ACTIVE".equalsIgnoreCase(status); }

        public String lastSeenLabel() {
            return lastSeenAt <= 0 ? "never" : 类049.receipt(lastSeenAt);
        }

        /** Short, human-quotable form of the hash: first 8 hex characters. */
        public String shortId() {
            return hash.length() <= 8 ? hash : hash.substring(0, 8).toUpperCase();
        }
    }

    /** How many devices the plan allows and how many are bound right now. */
    public static final class Seats {
        public int used;
        public int limit;
        public boolean known() { return limit > 0; }
        public boolean full() { return known() && used >= limit; }
        public String label() {
            return known() ? used + " of " + limit + (limit == 1 ? " device" : " devices")
                           : used + " devices";
        }
    }

    public interface Callback {
        /** {@code ok} is false when the server could not be reached. */
        void onResult(boolean ok, String message, List<Device> devices, Seats seats);
    }

    private DeviceRegistry() { }

    // ------------------------------------------------------------------ load

    /** Loads the live list on a background thread and calls back on the main one. */
    public static void load(Context ctx, Callback callback) {
        final Context app = ctx.getApplicationContext();
        类045.io().execute(() -> {
            类017 res = 类016.get(app, "v1/devices", null, true);
            List<Device> devices = Collections.emptyList();
            Seats seats = new Seats();
            String message = res.message == null ? "" : res.message;
            if (res.success && res.data != null) {
                devices = parseAndCache(app, res.data);
                readSeats(app, res.data.optJSONObject("seat"));
            }
            seats = cachedSeats(app);
            final List<Device> out = devices;
            final Seats outSeats = seats;
            final boolean ok = res.success && res.data != null;
            final String outMessage = ok ? message : fallbackMessage(message);
            类045.main().post(() -> {
                if (callback == null) return;
                if (!ok) callback.onResult(false, outMessage, cached(app), outSeats);
                else callback.onResult(true, outMessage, out, outSeats);
            });
        });
    }

    /** The last list the device actually saw — used offline and on cold start. */
    public static List<Device> cached(Context ctx) {
        try {
            return parse(类046.get(ctx).getDeviceListJson(), 类003.id(ctx));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static Seats cachedSeats(Context ctx) {
        类046 prefs = 类046.get(ctx);
        Seats seats = new Seats();
        seats.used = prefs.getDeviceSeatsUsed();
        seats.limit = prefs.getDeviceSeatsLimit();
        return seats;
    }

    // ---------------------------------------------------------------- release

    /**
     * Releases one device from this account so a spare seat can be used for a
     * replacement phone. The server then refuses that device; it can be re-bound
     * later by the administrator.
     */
    public static void release(Context ctx, String deviceHash, Callback callback) {
        final Context app = ctx.getApplicationContext();
        类045.io().execute(() -> {
            类017 res;
            try {
                res = 类016.post(app, "v1/devices/deactivate",
                        new JSONObject().put("device_id", deviceHash), true);
            } catch (Exception e) {
                res = 类017.offline(null);
            }
            final boolean ok = res.success;
            final String message = res.message == null || res.message.isEmpty()
                    ? (ok ? "Device released" : "Could not release the device.") : res.message;
            if (!ok) {
                类045.main().post(() -> {
                    if (callback != null) callback.onResult(false, message, cached(app),
                            cachedSeats(app));
                });
                return;
            }
            // Reload so the screen shows the new seat count straight away.
            load(app, (fresh, listMessage, devices, seats) -> {
                if (callback != null) callback.onResult(true, message, devices, seats);
            });
        });
    }

    // ------------------------------------------------------------- internals

    private static List<Device> parseAndCache(Context app, JSONObject data) {
        JSONArray array = data.optJSONArray("devices");
        String raw = array == null ? "" : array.toString();
        类046.get(app).setDeviceListJson(raw);
        readSeats(app, data.optJSONObject("seat"));
        return parse(raw, 类003.id(app));
    }

    private static void readSeats(Context app, JSONObject seat) {
        if (seat == null) return;
        类046.get(app).setDeviceSeats(seat.optInt("used", 0), seat.optInt("limit", 0));
    }

    private static List<Device> parse(String raw, String myHash) {
        List<Device> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.optJSONObject(i);
                if (row == null) continue;
                Device device = new Device();
                device.hash = row.optString("device_id", row.optString("hash", ""));
                device.label = first(row, "device_name", "label", "name");
                device.model = first(row, "model", "device_model");
                device.osVersion = row.optString("os_version", "");
                device.appVersion = row.optString("app_version", "");
                device.status = row.optString("status", "ACTIVE");
                device.firstSeenAt = row.optLong("first_seen_at_ms", 0L);
                device.lastSeenAt = row.optLong("last_seen_at_ms", row.optLong("last_seen_at", 0L));
                device.current = !device.hash.isEmpty() && device.hash.equals(myHash);
                out.add(device);
            }
        } catch (Exception ignored) {
            // A malformed cache is simply "no devices yet" — never a crash.
        }
        return out;
    }

    private static String first(JSONObject row, String... keys) {
        for (String key : keys) {
            String value = row.optString(key, "");
            if (value != null && !value.isEmpty() && !"null".equals(value)) return value;
        }
        return "";
    }

    private static String fallbackMessage(String message) {
        return message == null || message.isEmpty()
                ? "Device list is offline — showing the last known state." : message;
    }

    /** The app version the server has recorded for this install. */
    public static String appVersion() { return BuildConfig.VERSION_NAME; }
}
