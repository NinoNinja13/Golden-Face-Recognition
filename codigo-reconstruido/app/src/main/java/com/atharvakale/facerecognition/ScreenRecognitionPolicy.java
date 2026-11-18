package com.atharvakale.facerecognition;
/** Independent of Android for JVM tests. */
final class ScreenRecognitionPolicy {
    static boolean shouldRun(boolean interactive, boolean continueScreenOff) {
        return interactive || continueScreenOff;
    }
}
