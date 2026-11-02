package com.autoapply.bot.app;

import android.app.NotificationManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.webkit.CookieManager;
import android.webkit.WebSettings;

import com.autoapply.bot.core.Account;
import com.autoapply.bot.core.Api;
import com.autoapply.bot.core.Engine;
import com.autoapply.bot.core.Json;
import com.autoapply.bot.core.Page;
import com.autoapply.bot.core.Platform;
import com.autoapply.bot.core.Util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Connects the engine to the phone: the hidden LinkedIn page, notifications, sharing, permissions. */
public final class AndroidPlatform implements Platform {
    private static AndroidPlatform instance;

    public static synchronized AndroidPlatform get(Context c) {
        if (instance == null) {
            instance = new AndroidPlatform(c.getApplicationContext());
        }
        return instance;
    }

    final Context app;
    final Handler main = new Handler(Looper.getMainLooper());
    public final Engine engine;
    public final Api api;
    volatile WeakReference<MainActivity> activity = new WeakReference<MainActivity>(null);
    volatile boolean activityResumed = false;
    volatile WebViewPage page;
    private String scripts;

    private AndroidPlatform(Context app) {
        this.app = app;
        Util.echo = false;
        Account.defaultServer = BuildConfig.SERVER_URL;
        Account.device = ((Build.MANUFACTURER == null ? "" : Build.MANUFACTURER) + " " + (Build.MODEL == null ? "" : Build.MODEL)).trim();
        Account.appVersion = Json.str(appVersion(), "name");
        Notifs.channels(app);
        engine = new Engine(this, app.getFilesDir());
        api = new Api(engine);
    }

    MainActivity activity() {
        return activity.get();
    }

    // ------------------------------------------------------------------ the bot's page
    @Override
    public Page newPage() {
        WebViewPage p = new WebViewPage(app, main);
        page = p;
        return p;
    }

    @Override
    public void closePage(Page p) {
        if (p instanceof WebViewPage) {
            ((WebViewPage) p).destroy();
        }
        if (page == p) {
            page = null;
        }
    }

    @Override
    public synchronized String pageScripts() {
        if (scripts == null) {
            try {
                InputStream in = app.getAssets().open("li.js");
                try {
                    scripts = new String(Util.readAll(in), Util.UTF8);
                } finally {
                    in.close();
                }
            } catch (IOException e) {
                throw new IllegalStateException("li.js is missing from the app", e);
            }
        }
        return scripts;
    }

    @Override
    public String browserName() {
        WebViewPage p = page;
        String where = p == null ? "" : ", " + p.screenInfo();
        try {
            return "Android WebView (" + WebSettings.getDefaultUserAgent(app).replaceAll(".*(Chrome/[\\d.]+).*", "$1")
                    + "), desktop layout" + where;
        } catch (RuntimeException e) {
            return "Android WebView, desktop layout" + where;
        }
    }

    // ------------------------------------------------------------------ engine events
    @Override
    public void onRunning(boolean running) {
        if (!running) {
            return;  // BotService notices and stops itself
        }
        Intent i = new Intent(app, BotService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            app.startForegroundService(i);
        } else {
            app.startService(i);
        }
    }

    @Override
    public void onQuestion(Map<String, Object> item) {
        Notifs.question(app, item);
    }

    @Override
    public void onQuestionDone() {
        Notifs.cancel(app, Notifs.ID_ASK);
    }

    @Override
    public void onSignInNeeded() {
        Notifs.signIn(app);
    }

    @Override
    public void onSessionEnd(Map<String, Object> summary, List<String> lines) {
        Notifs.cancel(app, Notifs.ID_SIGNIN);
        Notifs.brief(app, summary, lines);
    }

    // ------------------------------------------------------------------ dashboard actions
    @Override
    public void openSignIn() {
        main.post(new Runnable() {
            @Override
            public void run() {
                MainActivity a = activity();
                if (a != null && !a.isFinishing()) {
                    a.showSignIn();
                } else {
                    Intent i = new Intent(app, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    i.putExtra("open", "signin");
                    app.startActivity(i);
                }
            }
        });
    }

    @Override
    public boolean signedIn() {
        try {
            String c = CookieManager.getInstance().getCookie("https://www.linkedin.com");
            return c != null && c.contains("li_at=");
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void share(final File file, final String mime, final String title) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(mime);
                send.putExtra(Intent.EXTRA_STREAM, FilesProvider.uriFor(file));
                send.putExtra(Intent.EXTRA_SUBJECT, title);
                send.setClipData(ClipData.newRawUri(title, FilesProvider.uriFor(file)));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(send, title);
                MainActivity a = activity();
                if (a != null) {
                    a.startActivity(chooser);
                } else {
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    app.startActivity(chooser);
                }
            }
        });
    }

    @Override
    public Map<String, Object> permissions() {
        boolean notes;
        if (Build.VERSION.SDK_INT >= 33) {
            notes = app.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
        } else {
            notes = ((NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE)).areNotificationsEnabled();
        }
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        boolean battery = pm != null && pm.isIgnoringBatteryOptimizations(app.getPackageName());
        return Json.obj("notifications", notes, "battery", battery, "maker", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER);
    }

    @Override
    public void requestPermission(final String which) {
        main.post(new Runnable() {
            @Override
            public void run() {
                MainActivity a = activity();
                if (a != null) {
                    a.requestPerm(which);
                }
            }
        });
    }

    // ------------------------------------------------------------------ app updates
    final Object updLock = new Object();
    Map<String, Object> upd = Json.obj("state", "idle", "progress", 0, "message", "");

    void setUpdate(String state, int progress, String message) {
        synchronized (updLock) {
            upd = Json.obj("state", state, "progress", progress, "message", message);
        }
    }

    @Override
    public Map<String, Object> updateState() {
        synchronized (updLock) {
            return new LinkedHashMap<String, Object>(upd);
        }
    }

    @SuppressWarnings("deprecation")
    long versionCode(PackageInfo pi) {
        return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
    }

    @Override
    public Map<String, Object> appVersion() {
        try {
            PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            return Json.obj("name", pi.versionName, "code", versionCode(pi));
        } catch (Exception e) {
            return Json.obj("name", "0", "code", 0);
        }
    }

    @Override
    public void installUpdate(String url, String version) {
        SelfUpdate.install(this, url, version);  // the Google Play build has none: Play updates it
    }

    @Override
    public Map<String, Object> buildInfo() {
        return Json.obj("flavor", BuildConfig.FLAVOR, "self_update", SelfUpdate.AVAILABLE, "server_built_in",
                !BuildConfig.SERVER_URL.isEmpty());
    }
}
