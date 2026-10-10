package com.quicktap.pos.cloudsync;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

/**
 * HTTPS transport for the customer's Cloud Sync endpoint.
 *
 * It ONLY ever sends the per-customer API key (Authorization: Bearer pos_live_…)
 * and never the app-level BuildConfig.API_KEY, so a shop key can be revoked
 * without touching the app credentials.
 *
 * Always call from a background thread.
 */
public final class CloudSyncTransport {

    public static final int CONNECT_TIMEOUT_MS = 20000;
    public static final int READ_TIMEOUT_MS = 30000;

    /** Outcome of one call. */
    public static final class Result {

        public final int http;
        public final String body;

        Result(int http, String body) {
            this.http = http;
            this.body = body == null ? "" : body;
        }

        public boolean ok() {
            return http >= 200 && http < 300;
        }

        public boolean unavailable() {
            return http == 0;
        }

        /** Parses the standard envelope: {"success":bool,"message":..,"data":..,"code":..}. */
        public JSONObject envelope() {
            try {
                return new JSONObject(body);
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        public JSONObject data() {
            JSONObject data = envelope().optJSONObject("data");
            if (data != null) {
                return data;
            }
            Object raw = envelope().opt("data");
            if (raw instanceof String && !((String) raw).isEmpty()) {
                try {
                    return new JSONObject((String) raw);
                } catch (Exception ignored) {
                    // fall through
                }
            }
            return new JSONObject();
        }

        public String message() {
            return envelope().optString("message", "");
        }

        public String code() {
            return envelope().optString("code", "");
        }
    }

    /** Thrown for anything that is not a clean HTTP response. */
    public static final class TransportException extends IOException {
        public final boolean offline;

        TransportException(String message, boolean offline) {
            super(message);
            this.offline = offline;
        }
    }

    private final Context context;
    private final CloudSyncSecureStore store;

    public CloudSyncTransport(Context context) {
        this.context = context.getApplicationContext();
        this.store = CloudSyncSecureStore.get(this.context);
    }

    public Result get(String path) throws IOException {
        return get(path, null);
    }

    public Result get(String path, Map<String, String> query) throws IOException {
        return call("GET", withQuery(path, query), null);
    }

    public Result post(String path, JSONObject body) throws IOException {
        return call("POST", path, body == null ? "{}" : body.toString());
    }

    public Result delete(String path) throws IOException {
        return call("DELETE", path, null);
    }

    // ------------------------------------------------------------------ core

    private String withQuery(String path, Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return path;
        }
        StringBuilder sb = new StringBuilder(path);
        sb.append(path.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (!first) {
                sb.append('&');
            }
            first = false;
            try {
                sb.append(java.net.URLEncoder.encode(entry.getKey(), "UTF-8"))
                        .append('=')
                        .append(java.net.URLEncoder.encode(entry.getValue(), "UTF-8"));
            } catch (Exception ignored) {
                // a malformed query value must never abort the call
            }
        }
        return sb.toString();
    }

    private Result call(String method, String path, String body) throws IOException {
        String endpoint = store.endpoint() == null ? "" : store.endpoint().trim();
        if (!endpoint.toLowerCase(java.util.Locale.US).startsWith("https://")) {
            throw new TransportException("Cloud Sync endpoint must start with https://", false);
        }
        String key = store.apiKey();
        if (key.isEmpty()) {
            throw new TransportException("Cloud Sync is disconnected", false);
        }

        URL url = new URL(stripTrailingSlash(endpoint) + "/" + stripLeadingSlash(path));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            if (connection instanceof HttpsURLConnection) {
                // Reuses the app's pinned socket factory when it is available.
                try {
                    ((HttpsURLConnection) connection).setSSLSocketFactory(
                            com.quicktap.pos.net.类019.socketFactory(context));
                } catch (Throwable ignored) {
                    // Not fatal - fall back to the platform default trust manager.
                }
            }
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            // Per-customer key: this is the ONLY credential that decides which
            // shop's data is reachable (the server derives the tenant from it).
            connection.setRequestProperty("Authorization", "Bearer " + key);
            // App-level credentials: api/index.php runs Auth::requireApiClient()
            // before the router, so every call - keyed or not - must carry them.
            connection.setRequestProperty("X-App-Id", com.quicktap.pos.BuildConfig.APP_ID);
            connection.setRequestProperty("X-Api-Key", com.quicktap.pos.BuildConfig.API_KEY);
            connection.setRequestProperty("X-App-Version", com.quicktap.pos.BuildConfig.VERSION_NAME);
            connection.setRequestProperty("X-Device-Id", store.deviceUid());

            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                OutputStream out = connection.getOutputStream();
                try {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                } finally {
                    out.close();
                }
            }

            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Result(code, read(stream));
        } catch (UnknownHostException | SocketTimeoutException e) {
            throw new TransportException(friendly(e), true);
        } catch (TransportException e) {
            throw e;
        } catch (IOException e) {
            throw new TransportException(friendly(e), isOffline(e));
        } finally {
            connection.disconnect();
        }
    }

    private static boolean isOffline(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String name = t.getClass().getName();
            if (name.contains("UnknownHostException") || name.contains("SocketTimeoutException")
                    || name.contains("ConnectException")) {
                return true;
            }
        }
        return false;
    }

    private static String friendly(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String name = t.getClass().getName();
            if (name.contains("CertPathValidatorException") || name.contains("CertificateException")
                    || name.contains("SSLHandshakeException") || name.contains("SSLPeerUnverified")) {
                return "Secure connection failed. Check the device date & time, then try again.";
            }
            if (name.contains("UnknownHostException")) {
                return "No internet connection.";
            }
            if (name.contains("SocketTimeoutException")) {
                return "Server took too long to respond.";
            }
        }
        return "Network error. Please try again.";
    }

    private static String read(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } finally {
            reader.close();
        }
        return sb.toString();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String stripLeadingSlash(String value) {
        return value.startsWith("/") ? value.substring(1) : value;
    }

    // --------------------------------------------------------------- helpers

    /** Small helper so callers do not have to import org.json collections. */
    public static List<JSONObject> arrayOf(JSONArray array) {
        List<JSONObject> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }
}
