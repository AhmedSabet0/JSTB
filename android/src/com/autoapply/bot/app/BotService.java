package com.autoapply.bot.app;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.autoapply.bot.core.Bot;
import com.autoapply.bot.core.Util;

import java.util.Map;

/** Keeps the app alive while the bot works in the background (foreground service with a status notification). */
public final class BotService extends Service {
    private PowerManager.WakeLock wake;
    private AndroidPlatform platform;
    private long lastUpdate = 0;
    private boolean updatePending = false;

    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            if (platform == null) {
                return;
            }
            if (!platform.engine.running()) {
                stopSelf();
                return;
            }
            platform.main.postDelayed(this, 2000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        platform = AndroidPlatform.get(this);
        Notifs.channels(this);
        Notification n = Notifs.running(this, Util.getStatus(), sub());
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifs.ID_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(Notifs.ID_RUN, n);
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoApply:bot");
        wake.setReferenceCounted(false);
        wake.acquire();
        Util.statusListener = new Util.StatusListener() {
            @Override
            public void onStatus(Map<String, Object> status) {
                scheduleUpdate();
            }
        };
        platform.main.postDelayed(watch, 2000);
    }

    private String sub() {
        int[] counts = Bot.appliedCounts(platform.engine.store.stateCopy(), Util.now());
        return "Applied today: " + counts[1];
    }

    private void scheduleUpdate() {
        platform.main.post(new Runnable() {
            @Override
            public void run() {
                if (updatePending) {
                    return;
                }
                long wait = Math.max(0, 1500 - (System.currentTimeMillis() - lastUpdate));
                updatePending = true;
                platform.main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        updatePending = false;
                        lastUpdate = System.currentTimeMillis();
                        if (platform.engine.running()) {
                            Notifs.nm(BotService.this).notify(Notifs.ID_RUN, Notifs.running(BotService.this, Util.getStatus(), sub()));
                        }
                    }
                }, wait);
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Util.statusListener = null;
        if (platform != null) {
            platform.main.removeCallbacks(watch);
        }
        if (wake != null && wake.isHeld()) {
            wake.release();
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
