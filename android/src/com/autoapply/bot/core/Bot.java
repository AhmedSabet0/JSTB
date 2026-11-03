package com.autoapply.bot.core;

import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.autoapply.bot.core.Util.log;
import static com.autoapply.bot.core.Util.pause;
import static com.autoapply.bot.core.Util.sleep;

/**
 * The bot: searches LinkedIn via Voyager API, scores jobs with AI, prepares answers,
 * and queues them as 'ready'. NEVER submits on its own: strictly waits for explicit user Apply.
 */
public final class Bot {
    public static class TooManyErrors extends RuntimeException {
        public TooManyErrors() {
            super("Too many errors in a row");
        }
    }

    /** Run options, as the dashboard passes them. */
    public static final class Options {
        public boolean dryRun;
        public boolean noSubmit;
        public boolean once;
        public boolean retryFailed;
        public boolean sendOnly;

        public Map<String, Object> toMap() {
            return Json.obj("dry_run", dryRun, "no_submit", noSubmit, "once", once, "retry_failed", retryFailed,
                    "send_only", sendOnly);
        }
    }

    // ------------------------------------------------------------------ pacing
    /** {last hour, today} */
    public static int[] appliedCounts(Map<String, Object> state, double now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis((long) (now * 1000));
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        double midnight = c.getTimeInMillis() / 1000.0;
        int hour = 0;
        int today = 0;
        for (Object o : Json.arr(state.get("submit_times"))) {
            double t = ((Number) o).doubleValue();
            if (now - t < 3600) {
                hour++;
            }
            if (t >= midnight) {
                today++;
            }
        }
        return new int[]{hour, today};
    }

    public static double tomorrowMorning(double now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis((long) (now * 1000));
        c.add(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 9);
        c.set(Calendar.MINUTE, Util.RANDOM.nextInt(45));
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis() / 1000.0;
    }

    /** Seconds to wait before the next application, and why ("slot" or "day"). */
    public static Object[] slotWait(Map<String, Object> state, int perHour, int perDay, double now) {
        int[] counts = appliedCounts(state, now);
        if (counts[1] >= perDay) {
            return new Object[]{tomorrowMorning(now) - now, "day"};
        }
        double until = Json.dbl(state, "next_slot_at", 0);
        if (counts[0] >= perHour) {
            double first = Double.MAX_VALUE;
            for (Object o : Json.arr(state.get("submit_times"))) {
                double t = ((Number) o).doubleValue();
                if (now - t < 3600) {
                    first = Math.min(first, t);
                }
            }
            until = Math.max(until, first + 3600 + Util.uniform(5, 60));
        }
        return new Object[]{Math.max(0.0, until - now), "slot"};
    }

    public static void recordSubmit(Map<String, Object> state, int perHour, double now) {
        List<Object> times = new ArrayList<Object>();
        for (Object o : Json.arr(state.get("submit_times"))) {
            if (now - ((Number) o).doubleValue() < 48 * 3600) {
                times.add(o);
            }
        }
        times.add(now);
        state.put("submit_times", times);
        state.put("next_slot_at", now + 3600.0 / Math.max(1, perHour) * Util.uniform(0.85, 1.15));
    }

    // ------------------------------------------------------------------ the bot
    final Engine engine;
    final Map<String, String> cfg;
    final Options args;
    final Store store;
    final Ai ai;
    public final LinkedInVoyager voyager;
    final List<String> keywords;
    final String location;
    public final int perHour;
    public final int perDay;
    public final int minScore;
    final int pages;
    final int everyMin;
    final List<String> skipCompanies = new ArrayList<String>();
    final List<String> skipWords = new ArrayList<String>();
    int errorStreak = 0;
    Map<String, Object> job = new LinkedHashMap<String, Object>();
    int roundApplied = 0;
    final Session session;
    final boolean noSubmit;
    final String cvText;
    final List<String> retryStatuses = new ArrayList<String>();

    public Bot(Engine engine, Map<String, String> cfg, Options args, Ai ai, LinkedInVoyager voyager, List<String> keywords, String location,
               Session session) {
        this.engine = engine;
        this.cfg = cfg;
        this.args = args;
        this.store = engine.store;
        this.ai = ai;
        this.voyager = voyager;
        this.keywords = keywords;
        this.location = location;
        this.perHour = Config.cfgInt(cfg, "PER_HOUR", 1, 30);
        this.perDay = Config.cfgInt(cfg, "PER_DAY", 1, 300);
        this.minScore = Config.cfgInt(cfg, "MIN_SCORE", 0, 10);
        this.pages = Config.cfgInt(cfg, "PAGES_PER_KEYWORD", 1, 10);
        this.everyMin = Config.cfgInt(cfg, "SEARCH_EVERY_MIN", 5, 720);
        for (String c : Util.splitList(cfg.get("SKIP_COMPANIES"))) {
            skipCompanies.add(c.toLowerCase(Locale.ROOT));
        }
        for (String w : Util.splitList(cfg.get("SKIP_TITLE_WORDS"))) {
            skipWords.add(w.toLowerCase(Locale.ROOT));
        }
        this.session = session;
        this.noSubmit = args.noSubmit;
        this.cvText = engine.config.cvText();
        if (!args.dryRun) {
            retryStatuses.add("answered");
            if (!noSubmit) {
                retryStatuses.add("dry_run_match");
            }
        }
    }

    /** Always true: every application strictly waits for explicit user Apply tap. */
    boolean approveFirst() {
        return true;
    }

    boolean seen(String jobId) {
        return store.seen(jobId, retryStatuses);
    }

    public static int readyMin = 3;

    int hourCap() {
        return Math.max(1, perHour);
    }

    int readyCap() {
        return Math.max(readyMin, hourCap());
    }

    boolean roomToPrepare() {
        if (store.idsWithStatus("ready").size() < readyCap()) {
            return true;
        }
        if (args.once) {
            log(waitingLine() + " No more are prepared in this run.");
            return false;
        }
        log(waitingLine() + " Preparing more after you review some.");
        while (store.idsWithStatus("ready").size() >= readyCap()) {
            int n = store.idsWithStatus("ready").size();
            Util.setStatus("state", "waiting", "message", "Waiting for your approval: " + n + (n == 1 ? " application is" : " applications are")
                    + " ready to send", "job", null, "next_slot_at", 0);
            sleep(10);
            sendApproved();
        }
        return true;
    }

    String waitingLine() {
        int n = store.idsWithStatus("ready").size();
        return n + (n == 1 ? " application waits" : " applications wait") + " for your approval.";
    }

    // ------------------------------------------------------------------ loop
    public void run() {
        if (args.sendOnly) {
            sendApproved();
            return;
        }
        while (true) {
            roundApplied = 0;
            cycle();
            sendApproved();
            if (args.once) {
                return;
            }
            double wait = everyMin * 60 * Util.uniform(0.9, 1.2);
            double at = Util.now() + wait;
            log("Search round finished. Next search at " + Util.fmtWhen(at) + ".");
            Util.setStatus("state", "waiting", "message", "Round finished. Searching again at " + Util.fmtWhen(at), "job", null,
                    "next_slot_at", at);
            double left = wait;
            while (left > 0) {
                double chunk = Math.min(10, left);
                sleep(chunk);
                left -= chunk;
                if (!store.idsWithStatus("approved").isEmpty()) {
                    sendApproved();
                    Util.setStatus("state", "waiting", "message", "Round finished. Searching again at " + Util.fmtWhen(at),
                            "job", null, "next_slot_at", at);
                }
            }
            Util.setStatus("next_slot_at", 0);
        }
    }

    /** Sends applications that you approved. Submits via Voyager API with user cookies. */
    void sendApproved() {
        if (args.dryRun || noSubmit) {
            return;
        }
        List<String> approved = store.idsWithStatus("approved");
        if (!approved.isEmpty()) {
            engine.sendApprovedViaVoyager(approved);
        }
    }

    void cycle() {
        sendApproved();
        for (String keyword : keywords) {
            for (int pageNo = 0; pageNo < pages; pageNo++) {
                String where = location.isEmpty() ? "" : " in " + location;
                log("Searching \"" + keyword + "\"" + where + " (page " + (pageNo + 1) + ")");
                Util.setStatus("state", "searching", "message", "Searching \"" + keyword + "\"" + where + ", page " + (pageNo + 1),
                        "job", null);
                List<String> ids = voyager.searchJobs(keyword, location, pageNo);
                List<String> fresh = new ArrayList<String>();
                for (String id : ids) {
                    if (!seen(id)) {
                        fresh.add(id);
                    }
                }
                log("  " + ids.size() + " jobs found, " + fresh.size() + " new");
                if (ids.isEmpty()) {
                    break;
                }
                for (String jobId : fresh) {
                    if (seen(jobId)) {
                        continue;
                    }
                    if (!roomToPrepare()) {
                        return;
                    }
                    prepareJob(jobId);
                    pause(2, 5);
                    sendApproved();
                }
                pause(3, 6);
            }
        }
    }

    void prepareJob(String jobId) {
        String url = "https://www.linkedin.com/jobs/view/" + jobId + "/";
        job = Json.obj("id", jobId, "url", url);
        Util.setStatus("state", "working", "message", "Reading job details", "job", job);
        Map<String, Object> info = voyager.getJob(jobId);
        if (!Json.bool(info, "ok")) {
            record(jobId, "error", null, Json.str(info, "error"), null, false);
            return;
        }
        job.putAll(info);
        String title = Json.str(info, "title");
        String company = Json.str(info, "company");
        String desc = Json.str(info, "description");
        boolean easy = Json.bool(info, "easy_apply");
        log((title.isEmpty() ? "Job " + jobId : title) + " @ " + (company.isEmpty() ? "?" : company) + "   " + url);

        if (!easy) {
            record(jobId, "no_easy_apply", null, "No Easy Apply available", null, false);
            return;
        }

        String lc = company.toLowerCase(Locale.ROOT);
        String lt = title.toLowerCase(Locale.ROOT);
        for (String c : skipCompanies) {
            if (lc.contains(c)) {
                record(jobId, "blacklisted", null, "Matches a skip filter in Settings", null, false);
                return;
            }
        }
        for (String w : skipWords) {
            if (lt.contains(w)) {
                record(jobId, "blacklisted", null, "Matches a skip filter in Settings", null, false);
                return;
            }
        }

        Util.setStatus("message", "AI is checking CV match");
        Object[] sc = ai.score(title, company, desc);
        int score = (Integer) sc[0];
        String reason = (String) sc[1];
        job.put("score", score);
        log("  Match " + score + "/10: " + reason);
        if (score < minScore) {
            record(jobId, "skipped_low_score", score, reason, null, false);
            return;
        }
        if (args.dryRun) {
            record(jobId, "dry_run_match", score, reason, null, false);
            return;
        }

        // Prepare form answers from profile, facts and CV context
        Map<String, Object> answers = voyager.prepareApplication(jobId, engine.profile, store, cvText, ai);

        // SAVE AS READY: NEVER submits on its own!
        store.add(jobId, "ready", title, company, score, reason, answers);
        log("  READY: " + title + " @ " + company + " (match " + score + "/10). It waits for your Apply.");
        engine.readyChanged();
    }

    void record(String jobId, String status, Object score, String reason, Object qa, boolean streak) {
        store.add(jobId, status, Json.str(job, "title"), Json.str(job, "company"), score, reason, qa);
        if (streak) {
            errorStreak++;
        } else {
            errorStreak = 0;
        }
        engine.readyChanged();
    }
}
