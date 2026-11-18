package com.atharvakale.facerecognition;
/** UI-thread gate: no step mutation while photo detection is pending. */
final class GalleryImportGate {
    private boolean busy;
    boolean isBusy() { return busy; }
    void start() { if (busy) throw new IllegalStateException("Import already pending"); busy = true; }
    void finish() { busy = false; }
}
