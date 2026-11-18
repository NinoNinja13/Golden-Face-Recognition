package com.atharvakale.facerecognition;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;

/** First stage: integration while the original recognition activity is visible. */
final class GoldenIntegration {
    static final String ACTION = "com.golden.facerecognition.FACE_RECOGNIZED";
    static final String EXTRA_NAME = "name";
    private final Activity activity;
    private final SharedPreferences prefs;
    private final WindowManager windows;
    private TextView overlay;
    private boolean active;
    private String lastName;

    GoldenIntegration(Activity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences("GoldenOptions", Context.MODE_PRIVATE);
        this.windows = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
    }

    void configure() {
        final boolean[] checked = {prefs.getBoolean("overlay", false)};
        new AlertDialog.Builder(activity).setTitle(GoldenLanguage.text(activity, "Golden — opções", "Golden — options"))
            .setMultiChoiceItems(new String[]{GoldenLanguage.text(activity, "Nomes em janelas flutuantes (também em segundo plano)", "Names in floating windows (also in background)")}, checked,
                (dialog, which, value) -> checked[which] = value)
            .setPositiveButton(GoldenLanguage.text(activity, "Salvar", "Save"), (dialog, which) -> {
                boolean permissionNeeded = checked[0] && Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(activity);
                prefs.edit().putBoolean("overlay", checked[0] && !permissionNeeded)
                    .remove("macrodroid").apply();
                removeOverlay();
                lastName = null;
                if (permissionNeeded) {
                    Toast.makeText(activity, GoldenLanguage.text(activity, "Autorize a sobreposição e depois ative a opção novamente.", "Allow overlay permission, then enable the option again."), Toast.LENGTH_LONG).show();
                    try {
                        activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + activity.getPackageName())));
                    } catch (RuntimeException ex) {
                        Toast.makeText(activity, GoldenLanguage.text(activity, "Não foi possível abrir as configurações de sobreposição.", "Could not open overlay settings."), Toast.LENGTH_LONG).show();
                    }
                }
            }).setNegativeButton(GoldenLanguage.text(activity, "Cancelar", "Cancel"), null).show();
    }

    void resume() { active = true; lastName = null; }
    void pause() { active = false; clear(); }

    /** Call on the UI thread; null means no recognized person. */
    void publish(String name) {
        if (!active || name == null || name.trim().isEmpty()) { clear(); return; }
        if (name.equals(lastName)) return;
        lastName = name;
        if (prefs.getBoolean("overlay", false)) showOverlay(name);

    }

    void clear() { lastName = null; removeOverlay(); }

    private void showOverlay(String name) {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(activity)) return;
        removeOverlay();
        TextView label = new TextView(activity);
        label.setText(name);
        label.setTextColor(Color.WHITE);
        label.setTextSize(16);
        int pad = (int) (8 * activity.getResources().getDisplayMetrics().density + .5f);
        label.setPadding(pad * 2, pad, pad * 2, pad);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xdd202020);
        background.setCornerRadius(pad * 2);
        label.setBackground(background);
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = pad * 8;
        try { windows.addView(label, params); overlay = label; }
        catch (RuntimeException ex) { overlay = null; }
    }

    private void removeOverlay() {
        if (overlay != null) {
            try { windows.removeView(overlay); } catch (RuntimeException ignored) { }
            overlay = null;
        }
    }
}
