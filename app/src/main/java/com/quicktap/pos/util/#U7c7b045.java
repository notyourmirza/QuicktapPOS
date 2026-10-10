package com.quicktap.pos.util;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny thread pool so Room never runs on the UI thread. */
public final class 类045 {

    private static final ExecutorService IO = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private 类045() { }

    public static ExecutorService io() { return IO; }

    public static Handler main() { return MAIN; }
}
