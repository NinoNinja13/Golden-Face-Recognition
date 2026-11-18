package com.atharvakale.facerecognition;
/** Pure first-use decision, independent of Android UI and storage. */
final class FirstPermissionPlan {
    enum Step { DONE, INTRO, CAMERA, NOTIFICATIONS, FINISH }
    static Step next(boolean done, boolean started, boolean cameraAllowed, boolean notificationsAllowed,
                     boolean cameraAsked, boolean notificationsAsked) {
        if (done || (cameraAllowed && notificationsAllowed)) return Step.DONE;
        if (!started) return Step.INTRO;
        if (!cameraAllowed && !cameraAsked) return Step.CAMERA;
        if (!notificationsAllowed && !notificationsAsked) return Step.NOTIFICATIONS;
        return Step.FINISH;
    }
}
