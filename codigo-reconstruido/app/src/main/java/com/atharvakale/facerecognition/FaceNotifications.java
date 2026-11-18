package com.atharvakale.facerecognition;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import androidx.core.app.NotificationCompat;

/** All entry points run on the main thread. One notification identity for both camera owners. */
final class FaceNotifications {
    static final int PREVIEW = FaceNotificationCoordinator.PREVIEW;
    static final int SERVICE = FaceNotificationCoordinator.SERVICE;
    static final int ID = 8766;
    static final String CHANNEL = "golden_face_names";
    private static final FaceNotificationCoordinator coordinator = new FaceNotificationCoordinator();
    private FaceNotifications() {}
    static void acquire(Context context, int owner) {
        coordinator.acquire(owner);
        createChannel(context);
    }
    static boolean owns(int owner) { return coordinator.owns(owner); }
    static String name() { return coordinator.name(); }
    static void observe(Context context, int owner, String name, long now) {
        apply(context, coordinator.observe(owner, name, now));
    }
    static void tick(Context context, int owner, long now) {
        apply(context, coordinator.tick(owner, now));
    }
    static void clear(Context context, int owner) {
        apply(context, coordinator.clear(owner));
    }
    static void release(Context context, int owner) {
        boolean hadOwner = coordinator.owns(owner);
        coordinator.release(owner);
        if (hadOwner) manager(context).cancel(ID);
    }
    private static NotificationManager manager(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }
    static void createChannel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    GoldenLanguage.text(c, "Golden Face rostos", "Golden Face faces"), NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            channel.enableVibration(false);
            manager(c).createNotificationChannel(channel);
        }
    }
    private static void apply(Context c, FaceNotificationState.Action action) {
        if (action == FaceNotificationState.Action.NONE) return;
        NotificationManager manager = manager(c);
        manager.cancel(ID); // Preserve the service's transition semantics for notification listeners.
        if (action == FaceNotificationState.Action.POST) {
            String name = coordinator.name();
            Notification notification = new NotificationCompat.Builder(c, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle(name == null ? GoldenLanguage.text(c, "Golden Face: sem reconhecimento", "Golden Face: no recognition") : GoldenLanguage.text(c, "Golden Face: rosto reconhecido", "Golden Face: face recognized"))
                    .setContentText(name == null ? GoldenLanguage.text(c, "Sem reconhecimento", "No recognition") : name)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setSilent(true).setOnlyAlertOnce(true).build();
            try { manager.notify(ID, notification); }
            catch (SecurityException e) { android.util.Log.w("GoldenFace", "Notification permission not granted", e); }
        }
    }
}
