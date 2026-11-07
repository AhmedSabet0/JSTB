package com.autoapply.bot.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import com.autoapply.bot.core.Json;

/** Android's answer while installing an update (confirm / done / failed), and "the app was updated". */
public final class UpdateReceiver extends BroadcastReceiver {
    static final String ACTION_STATUS = "com.autoapply.bot.INSTALL_STATUS";

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        AndroidPlatform p = AndroidPlatform.get(context);
        String action = intent.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Notifs.updated(context, Json.str(p.appVersion(), "name"));
            final java.util.Map<String, Object> o = p.engine.takeResume();
            if (o != null) {        // it was applying when the update came: it goes on
                final AndroidPlatform pf = p;
                final PendingResult pending = goAsync();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            String mode = Json.bool(o, "dry_run") ? "dry" : Json.bool(o, "no_submit") ? "test" : "apply";
                            pf.api.handle("/api/start", Json.stringify(Json.obj("mode", mode, "once", Json.bool(o, "once"))));
                        } finally {
                            pending.finish();
                        }
                    }
                }).start();
            }
            return;
        }
        if (!ACTION_STATUS.equals(action)) {
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) {
                p.setUpdate("error", 0, "Android did not show the install window. Try again.");
                return;
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            p.setUpdate("confirm", 100, "Tap Update in Android's window to finish.");
            boolean shown = false;
            try {
                MainActivity a = p.activity();
                if (a != null && p.activityResumed) {
                    a.startActivity(confirm);
                    shown = true;
                }
            } catch (RuntimeException e) {
                shown = false;
            }
            if (!shown) {
                Notifs.installPrompt(context, confirm);  // tap the notification to finish
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            p.setUpdate("done", 100, "Updated. Open the app again if it closed.");
        } else {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            p.setUpdate("error", 0, status == PackageInstaller.STATUS_FAILURE_ABORTED ? "Update cancelled."
                    : "Android did not install the update" + (msg == null ? "." : ": " + msg));
        }
    }
}
