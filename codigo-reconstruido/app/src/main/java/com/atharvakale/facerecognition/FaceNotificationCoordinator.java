package com.atharvakale.facerecognition;

/** Main-thread coordinator shared by preview and service. Only the owner may publish or clear. */
final class FaceNotificationCoordinator {
    static final int PREVIEW = 1, SERVICE = 2;
    private final FaceNotificationState state = new FaceNotificationState();
    private int owner;
    void acquire(int newOwner) {
        if (newOwner != PREVIEW && newOwner != SERVICE) throw new IllegalArgumentException();
        owner = newOwner; // A handoff retains the last result and does not repost it.
    }
    boolean owns(int who) { return owner == who; }
    String name() { return state.name(); }
    FaceNotificationState.Action observe(int who, String name, long now) {
        return owns(who) ? state.observe(name, now) : FaceNotificationState.Action.NONE;
    }
    FaceNotificationState.Action tick(int who, long now) {
        return owns(who) ? state.tick(now) : FaceNotificationState.Action.NONE;
    }
    FaceNotificationState.Action clear(int who) {
        return owns(who) ? state.clear() : FaceNotificationState.Action.NONE;
    }
    FaceNotificationState.Action release(int who) {
        if (!owns(who)) return FaceNotificationState.Action.NONE;
        owner = 0;
        return state.clear();
    }
}
