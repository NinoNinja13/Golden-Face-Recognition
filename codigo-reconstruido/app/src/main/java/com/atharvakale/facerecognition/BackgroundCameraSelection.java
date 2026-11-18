package com.atharvakale.facerecognition;
/** CameraX uses 0 for front and 1 for back. */
final class BackgroundCameraSelection {
    static int normalize(int saved) { return saved == 0 ? 0 : 1; }
    static int fromChoice(int choice) { if (choice != 0 && choice != 1) throw new IllegalArgumentException("camera"); return choice; }
    static boolean canChange(boolean active) { return !active; }
}
