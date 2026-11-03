package com.autoapply.bot.app;

/** The Google Play build: Play installs updates, the app never updates itself. */
final class SelfUpdate {
    static final boolean AVAILABLE = false;

    private SelfUpdate() {
    }

    static void install(AndroidPlatform p, String url, String version, boolean auto) {
        p.setUpdate("error", 0, "Updates come from Google Play.");
    }

    static void installReady(AndroidPlatform p) {
        // Google Play installs updates
    }
}
