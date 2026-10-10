package com.quicktap.pos.cloudsync;

/**
 * Status of the paid Cloud Sync feature.
 *
 * It is deliberately kept separate from the existing licence state machine
 * (com.quicktap.pos.license.*) so that a Cloud Sync problem can never change
 * what the cashier sees on the licence banner.
 */
public enum CloudSyncState {

    /** No endpoint / key saved, or the user disconnected. Nothing is sent. */
    DISCONNECTED,

    /** A verify or sync round trip is in flight. */
    CONNECTING,

    /** Key accepted, plan entitled, last round trip succeeded. */
    CONNECTED,

    /** Key accepted but the last round trip failed because there is no network. */
    OFFLINE,

    /** Server answered 401 - key unknown, revoked or inactive. Sync is stopped. */
    INVALID_KEY,

    /** Server answered 402 UPGRADE_REQUIRED - plan has no Cloud Sync. Stopped. */
    NOT_IN_PLAN,

    /** Server answered 402 PLAN_EXPIRED - subscription window ended. Stopped. */
    PLAN_EXPIRED,

    /** Any other failure (5xx, parse error, unexpected body). */
    ERROR;

    /** True when the feature may talk to the server at all. */
    public boolean isNetworkAllowed() {
        return this == CONNECTING || this == CONNECTED || this == OFFLINE || this == ERROR;
    }

    /** True when a saved key exists and the plan allows syncing. */
    public boolean isUsable() {
        return this == CONNECTING || this == CONNECTED || this == OFFLINE;
    }
}
