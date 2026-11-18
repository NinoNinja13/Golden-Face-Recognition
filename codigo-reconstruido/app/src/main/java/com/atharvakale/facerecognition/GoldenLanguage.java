package com.atharvakale.facerecognition;

import android.content.Context;

/** Application-language lookup usable by activities, services and boot receivers. */
final class GoldenLanguage {
    private GoldenLanguage() { }
    static String text(Context context, String portuguese, String english) {
        String code = context.getSharedPreferences("GoldenOptions", Context.MODE_PRIVATE)
                .getString("language", "pt-BR");
        return "en".equals(code) ? english : portuguese;
    }
}
