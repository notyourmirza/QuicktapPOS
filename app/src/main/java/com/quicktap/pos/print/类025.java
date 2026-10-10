package com.quicktap.pos.print;

/** The physical channel a receipt gets sent through. */
public enum 类025 {
    BLUETOOTH("bluetooth"),
    USB("usb"),
    NETWORK("network");

    public final String key;

    类025(String key) {
        this.key = key;
    }

    public static 类025 fromKey(String key) {
        if (USB.key.equals(key)) return USB;
        if (NETWORK.key.equals(key)) return NETWORK;
        return BLUETOOTH;
    }
}
