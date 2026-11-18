package com.atharvakale.facerecognition;

/** Exported entry point; uses the same guided flow and storage as MainActivity. */
public class GuidedEnrollmentActivity extends MainActivity {
    @Override protected void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        setTitle(GoldenLanguage.text(this, "Golden Face: cadastro guiado", "Golden Face: guided enrollment"));
    }
}
