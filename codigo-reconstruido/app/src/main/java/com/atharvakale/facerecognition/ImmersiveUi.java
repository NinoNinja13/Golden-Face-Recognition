package com.atharvakale.facerecognition;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/** Only the owning activity window is affected, never service/overlay windows. */
final class ImmersiveUi {
    private final Activity activity;
    private final View decor;
    private boolean keyboardWasVisible;

    ImmersiveUi(Activity activity) {
        this.activity = activity;
        this.decor = activity.getWindow().getDecorView();
        this.keyboardWasVisible = keyboardVisible();
        decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            boolean visible = keyboardVisible();
            boolean closed = keyboardWasVisible && !visible;
            keyboardWasVisible = visible;
            // Do not re-hide on every layout: edge swipes must remain usable.
            if (closed) applyIfAllowed();
        });
    }

    void onFocusChanged(boolean focused) {
        if (focused) applyIfAllowed();
    }

    private boolean keyboardVisible() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsets insets = decor.getRootWindowInsets();
            return insets != null && insets.isVisible(WindowInsets.Type.ime());
        }
        // Before API 30 no IME visibility API exists. Avoid interpreting ordinary
        // status/navigation bars as a keyboard, including on high-density devices.
        Rect visible = new Rect();
        decor.getWindowVisibleDisplayFrame(visible);
        if (visible.isEmpty()) return false;
        DisplayMetrics metrics = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        return metrics.heightPixels - visible.bottom > 120 * metrics.density;
    }

    @SuppressWarnings("deprecation")
    private void applyIfAllowed() {
        if (!ImmersivePolicy.shouldApply(decor.hasWindowFocus(), keyboardVisible())) return;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = activity.getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            decor.setSystemUiVisibility(decor.getSystemUiVisibility() | flags);
        }
    }
}
