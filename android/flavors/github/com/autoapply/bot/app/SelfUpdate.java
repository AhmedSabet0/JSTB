package com.autoapply.bot.app;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.autoapply.bot.core.Json;
import com.autoapply.bot.core.Util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;

/** The GitHub build updates itself: download the APK of a newer release and ask Android to install it over this app.
 *  (Not in the Google Play build: Play does not allow apps to update themselves.) */
final class SelfUpdate {
    static final boolean AVAILABLE = true;

    private SelfUpdate() {
    }

    static void install(final AndroidPlatform p, final String url, final String version) {
        synchronized (p.updLock) {
            String st = Json.str(p.upd, "state");
            if (st.equals("downloading") || st.equals("installing")) {
                return;
            }
        }
        if (Build.VERSION.SDK_INT >= 26 && !p.app.getPackageManager().canRequestPackageInstalls()) {
            p.setUpdate("permission", 0, "Allow \"Install unknown apps\" for Auto Apply (Android asks once), then tap Download and install again.");
            p.main.post(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + p.app.getPackageName()));
                    MainActivity a = p.activity();
                    try {
                        if (a != null) {
                            a.startActivity(i);
                        } else {
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            p.app.startActivity(i);
                        }
                    } catch (RuntimeException e) {
                        p.setUpdate("permission", 0, "Open Settings > Apps > Auto Apply > Install unknown apps, allow it, then try again.");
                    }
                }
            });
            return;
        }
        p.setUpdate("downloading", 0, "Downloading version " + version + "...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = new File(p.app.getCacheDir(), "update");
                    dir.mkdirs();
                    File apk = new File(dir, "update.apk");
                    download(p, url, apk, version);
                    String problem = checkApk(p, apk);
                    if (problem != null) {
                        p.setUpdate("error", 0, problem);
                        return;
                    }
                    p.setUpdate("installing", 100, "Installing version " + version + "...");
                    installApk(p, apk);
                } catch (Exception e) {
                    p.setUpdate("error", 0, "Update failed: " + Util.shortError(e, 150));
                }
            }
        }, "update").start();
    }

    private static void download(AndroidPlatform p, String url, File dest, String version) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "AutoApply-Android");
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                throw new IOException("the download answered HTTP " + code);
            }
            long total = c.getContentLengthLong();
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(dest);
            try {
                byte[] buf = new byte[32768];
                long done = 0;
                int last = -1;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    int pct = total > 0 ? (int) (done * 100 / total) : 0;
                    if (pct != last) {
                        last = pct;
                        p.setUpdate("downloading", pct, "Downloading version " + version + "... " + (total > 0 ? pct + "%" : ""));
                    }
                }
            } finally {
                out.close();
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }

    /** Make sure the file is a newer Auto Apply signed with the same key; returns a problem, or null. */
    @SuppressWarnings("deprecation")
    private static String checkApk(AndroidPlatform p, File apk) {
        PackageManager pm = p.app.getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        if (info == null) {
            return "The downloaded file is not a valid Android app.";
        }
        if (!p.app.getPackageName().equals(info.packageName)) {
            return "The downloaded file is not Auto Apply.";
        }
        try {
            PackageInfo mine = pm.getPackageInfo(p.app.getPackageName(), PackageManager.GET_SIGNATURES);
            if (p.versionCode(info) <= p.versionCode(mine)) {
                return "The downloaded version is not newer than the installed one.";
            }
            Signature[] a = mine.signatures;
            Signature[] b = info.signatures;
            if (a != null && b != null && a.length > 0 && b.length > 0 && !Arrays.equals(a, b)) {
                return "This update is signed with a different key, so Android cannot install it over this app.";
            }
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        return null;
    }

    private static void installApk(AndroidPlatform p, File apk) throws IOException {
        PackageInstaller installer = p.app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(p.app.getPackageName());
        params.setSize(apk.length());
        int id = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(id);
        try {
            OutputStream out = session.openWrite("base.apk", 0, apk.length());
            InputStream in = new FileInputStream(apk);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            } finally {
                in.close();
                out.close();
            }
            Intent status = new Intent(p.app, UpdateReceiver.class);
            status.setAction(UpdateReceiver.ACTION_STATUS);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pi = PendingIntent.getBroadcast(p.app, 77, status, flags);
            session.commit(pi.getIntentSender());
        } catch (IOException e) {
            session.abandon();
            throw e;
        } finally {
            session.close();
        }
    }
}
