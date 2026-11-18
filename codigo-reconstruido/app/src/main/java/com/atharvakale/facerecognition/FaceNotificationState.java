package com.atharvakale.facerecognition;

/** Single-thread state: report recognition transitions, never refresh an unchanged name. */
final class FaceNotificationState {
    // A watchdog for a stalled analyzer, not a grace period after a negative result.
    static final long STALLED_MS = 3000;
    enum Action { NONE, POST, CLEAR }
    private String name;
    private boolean initialized;
    private long lastResult;

    String name() { return name; }
    Action observe(String observed, long now) {
        String next = observed == null || observed.trim().isEmpty() ? null : observed;
        lastResult = now;
        if (!initialized || (name == null ? next != null : !name.equals(next))) {
            initialized = true;
            name = next;
            return Action.POST;
        }
        return Action.NONE;
    }
    Action tick(long now) {
        // If frames stop altogether, replace a stale identity with unknown once.
        if (initialized && name != null && now - lastResult >= STALLED_MS) {
            name = null;
            return Action.POST;
        }
        return Action.NONE;
    }
    Action clear() {
        if (!initialized) return Action.NONE;
        initialized = false;
        name = null;
        return Action.CLEAR;
    }
}
