package com.atharvakale.facerecognition;
final class ImmersivePolicy {
    static boolean shouldApply(boolean focused, boolean keyboardVisible) {
        return focused && !keyboardVisible;
    }
}
