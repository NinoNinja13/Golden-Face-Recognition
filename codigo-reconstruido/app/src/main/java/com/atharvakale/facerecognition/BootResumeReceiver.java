package com.atharvakale.facerecognition;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.camera.core.CameraSelector;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/** Never starts a camera from BOOT_COMPLETED on Android 11 and later. */
public final class BootResumeReceiver extends BroadcastReceiver {
    static final String EXTRA_RESUME = "golden_resume_after_boot";
    static final String EXTRA_BOOT = "golden_boot_start";
    static final String PREF = "GoldenBoot";
    static final int NOTIFICATION_ID = 8767;
    @Override public void onReceive(Context c, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
            !c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("resume", false)) return;
        if (Build.VERSION.SDK_INT <= 29 && ContextCompat.checkSelfPermission(c, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try {
                int lens = BackgroundCameraSelection.normalize(c.getSharedPreferences("GoldenBackgroundCamera", Context.MODE_PRIVATE)
                    .getInt("lens", CameraSelector.LENS_FACING_BACK));
                ContextCompat.startForegroundService(c, new Intent(c, FaceCameraService.class)
                    .putExtra(FaceCameraService.LENS, lens).putExtra(EXTRA_BOOT, true));
                return;
            } catch (RuntimeException ignored) { }
        }
        notifyResume(c);
    }
    static void notifyResume(Context c) {
        NotificationManager nm = (NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        String channel = "golden_face_boot";
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(channel,
            GoldenLanguage.text(c, "Golden Face retomada", "Golden Face resume"), NotificationManager.IMPORTANCE_DEFAULT));
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        Intent open = new Intent(c, MainActivity.class).setAction("com.golden.facerecognition.RESUME_AFTER_BOOT")
            .putExtra(EXTRA_RESUME, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 8767, open, PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        try { nm.notify(NOTIFICATION_ID, new NotificationCompat.Builder(c, channel)
            .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle(GoldenLanguage.text(c, "Retomar Golden Face", "Resume Golden Face"))
            .setContentText(GoldenLanguage.text(c, "Toque para abrir o app e retomar a câmera.", "Tap to open the app and resume the camera."))
            .setContentIntent(pi).setAutoCancel(true).build()); } catch (SecurityException ignored) { }
    }
}
