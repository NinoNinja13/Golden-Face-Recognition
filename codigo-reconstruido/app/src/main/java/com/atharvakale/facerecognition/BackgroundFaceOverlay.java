package com.atharvakale.facerecognition;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/** Service-owned, non-interactive overlay; every update rechecks consent and permission. */
final class BackgroundFaceOverlay {
    private final Context context;
    private final WindowManager windows;
    private TextView view;
    BackgroundFaceOverlay(Context c) {
        context = c;
        windows = (WindowManager)c.getSystemService(Context.WINDOW_SERVICE);
    }
    void update(String name) {
        boolean enabled = context.getSharedPreferences("GoldenOptions", Context.MODE_PRIVATE).getBoolean("overlay", false);
        boolean allowed = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context);
        if (!enabled || !allowed || name == null || name.trim().isEmpty()) { clear(); return; }
        if (view != null) { view.setText(name); return; }
        TextView label = new TextView(context);
        label.setText(name); label.setTextColor(Color.WHITE); label.setTextSize(16);
        int pad = (int)(8 * context.getResources().getDisplayMetrics().density + .5f);
        label.setPadding(pad*2, pad, pad*2, pad);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xdd202020); bg.setCornerRadius(pad*2);
        label.setBackground(bg);
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; lp.y = pad*8;
        try { windows.addView(label, lp); view = label; } catch (RuntimeException ex) { view = null; }
    }
    void clear() {
        if (view != null) { try { windows.removeView(view); } catch (RuntimeException ignored) {} view = null; }
    }
}
