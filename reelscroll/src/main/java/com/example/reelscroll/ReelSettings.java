package com.example.reelscroll;

import android.content.Context;
import android.content.SharedPreferences;

/** Saved on/off state and delay. Read by the service and the menu. */
final class ReelSettings {

    static final String INSTAGRAM = "com.instagram.android";
    static final String FACEBOOK = "com.facebook.katana";
    static final String YOUTUBE = "com.google.android.youtube";

    static final int DEFAULT_DELAY = 15;
    static final int MIN_DELAY = 3;
    static final int MAX_DELAY = 120;

    private static final String PREFS = "reel_prefs";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_DELAY = "delay_seconds";

    private ReelSettings() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, true);
    }

    static void setEnabled(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply();
    }

    static int delaySeconds(Context context) {
        return prefs(context).getInt(KEY_DELAY, DEFAULT_DELAY);
    }

    static void setDelaySeconds(Context context, int seconds) {
        int clamped = Math.max(MIN_DELAY, Math.min(MAX_DELAY, seconds));
        prefs(context).edit().putInt(KEY_DELAY, clamped).apply();
    }

    static boolean isKnownApp(String pkg) {
        return INSTAGRAM.equals(pkg) || FACEBOOK.equals(pkg) || YOUTUBE.equals(pkg);
    }

    /** True if auto-scroll should work in this app (it is one of the three and not switched off). */
    static boolean isTarget(Context context, String pkg) {
        return isKnownApp(pkg) && prefs(context).getBoolean("app_" + pkg, true);
    }

    static void setApp(Context context, String pkg, boolean on) {
        prefs(context).edit().putBoolean("app_" + pkg, on).apply();
    }
}
