package com.atharvakale.facerecognition;

/** Recover an interrupted restore before any component can read preferences. */
public final class GoldenApplication extends android.app.Application {
    @Override public void onCreate() {
        super.onCreate();
        try { GoldenBackup.recover(this); }
        catch (Exception ex) { throw new IllegalStateException("Golden Face restore recovery failed; journal retained", ex); }
    }
}
