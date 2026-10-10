package com.quicktap.pos.print;

import android.content.Context;

import com.quicktap.pos.util.类045;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Raw TCP ("JetDirect" / port 9100) ESC/POS transport for network thermal
 * printers. A fresh socket is opened per job, which is simple and matches how
 * these printers are normally driven from POS software.
 */
public class 类022 {

    private static final int CONNECT_TIMEOUT_MS = 5000;

    public interface Callback {
        void onSuccess();
        void onError(String message);
    }

    private static 类022 instance;

    private final Context context;

    private 类022(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized 类022 get(Context context) {
        if (instance == null) instance = new 类022(context);
        return instance;
    }

    public void print(byte[] payload, Callback callback) {
        类045.io().execute(() -> {
            try {
                writeBlocking(payload);
                类045.main().post(callback::onSuccess);
            } catch (Exception e) {
                String message = e.getMessage() == null ? "Printer not reachable" : e.getMessage();
                类045.main().post(() -> callback.onError(message));
            }
        });
    }

    private void writeBlocking(byte[] payload) throws IOException {
        类024 prefs = 类024.get(context);
        String host = prefs.getNetworkHost();
        int port = prefs.getNetworkPort();
        if (host == null || host.trim().isEmpty()) {
            throw new IOException("No network printer configured. Open Settings > Printer.");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host.trim(), port), CONNECT_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            out.write(payload);
            out.flush();
        }
    }
}
