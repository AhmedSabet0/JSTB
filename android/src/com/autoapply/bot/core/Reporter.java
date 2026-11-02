package com.autoapply.bot.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.autoapply.bot.core.Util.log;

/** While auto apply runs: tells the server what it is doing every half minute (and right after each application),
 *  and stops the run when the server says so (the admin pressed Stop, or turned Pro off). */
public final class Reporter implements Store.Listener {
    static final List<String> KEPT = Arrays.asList("applied", "error", "stuck", "needs_answer");
    /** Seconds between reports (tests use less). */
    public static volatile double every = 30;
    /** Without any contact for this long the run stops: the admin must be able to stop it. */
    public static volatile double giveUpAfter = 600;

    private final Engine engine;
    private final Account account;
    private final String mode;
    private final File queueFile;
    private final List<Map<String, Object>> queue = new ArrayList<Map<String, Object>>();
    private final Object lock = new Object();
    private volatile boolean done;
    private boolean finished;
    private boolean started;
    private double lastOk;
    private Thread thread;

    Reporter(Engine engine, Account account, String mode) {
        this.engine = engine;
        this.account = account;
        this.mode = mode;
        this.queueFile = new File(engine.config.dir, "report_queue.json");
        for (Object o : Json.arr(Json.tryParse(Util.readText(queueFile)))) {  // not sent at the end of the last run
            if (o instanceof Map) {
                queue.add(Json.map(o));
            }
        }
    }

    void start() {
        lastOk = Util.now();
        engine.store.listener = this;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "report");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void onJob(Map<String, Object> job) {
        String status = Json.str(job, "status");
        if (!KEPT.contains(status)) {
            return;
        }
        synchronized (lock) {
            queue.add(Json.obj("job_id", Json.str(job, "id"), "title", Util.cut(Json.str(job, "title"), 200),
                    "company", Util.cut(Json.str(job, "company"), 120), "status", status, "score", job.get("score"),
                    "at", Json.dbl(job, "ts", Util.now())));
            if ("applied".equals(status)) {
                lock.notifyAll();  // the admin sees it right away
            }
        }
    }

    private void loop() {
        while (!done) {
            send(true);
            synchronized (lock) {
                if (!done) {
                    try {
                        lock.wait((long) (every * 1000));
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }
    }

    private boolean send(boolean running) {
        List<Map<String, Object>> events;
        synchronized (lock) {
            events = new ArrayList<Map<String, Object>>(queue);
        }
        Map<String, Object> st = Util.getStatus();
        int[] counts = Bot.appliedCounts(engine.store.stateCopy(), Util.now());
        Map<String, Object> body = Json.obj("start", running && !started, "running", running, "mode", mode,
                "message", Util.cut(Json.str(st, "message"), 200), "applied_today", counts[1],
                "applied_total", engine.store.count("applied"), "app_version", Account.appVersion, "device", Account.device,
                "events", events);
        try {
            Map<String, Object> r = account.report(body);
            if (running) {
                started = true;
            }
            lastOk = Util.now();
            synchronized (lock) {
                queue.subList(0, Math.min(events.size(), queue.size())).clear();
            }
            account.learned(Json.bool(r, "pro"), (int) Json.num(r, "max_per_day", 0));
            if (running && !done && Json.bool(r, "stop")) {
                String why = Json.str(r, "reason");
                stopRun(why.isEmpty() ? "Stopped by the admin." : why);
            }
            return true;
        } catch (Account.AuthError e) {
            if (running && !done) {
                stopRun("You were signed out of your Auto Apply account. Sign in again in Settings.");
            }
        } catch (RuntimeException e) {
            if (running && !done && Util.now() - lastOk > giveUpAfter) {
                stopRun("Lost contact with the Auto Apply server for " + Math.round(giveUpAfter / 60) + " minutes. Start again "
                        + "when the internet is back.");
            }
        }
        return false;
    }

    private void stopRun(String why) {
        if (Util.stopReason.isEmpty()) {
            Util.stopReason = why;
            log(why);
        }
        engine.stop();
    }

    /** The run ended: one last report (in the background); what cannot be sent now goes with the next run. */
    synchronized void finish() {
        if (finished) {
            return;
        }
        finished = true;
        done = true;
        synchronized (lock) {
            lock.notifyAll();
        }
        if (engine.store.listener == this) {
            engine.store.listener = null;
        }
        final Thread t = thread;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (t != null) {
                        t.join(45000);
                    }
                } catch (InterruptedException e) {
                    // send anyway
                }
                boolean sent = send(false);
                synchronized (lock) {
                    if (sent || queue.isEmpty()) {
                        queueFile.delete();
                    } else {
                        Util.writeText(queueFile, Json.stringify(queue));
                    }
                }
            }
        }, "report-end").start();
    }
}
