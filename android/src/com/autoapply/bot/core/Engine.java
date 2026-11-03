package com.autoapply.bot.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.autoapply.bot.core.Util.log;

/** Starts and stops the bot on its own thread and runs one session: sign in, search, apply, brief. */
public final class Engine {
    /** A setup problem with a message you can act on. */
    public static class SetupError extends RuntimeException {
        public SetupError(String m) {
            super(m);
        }
    }

    public final Platform platform;
    public final Config config;
    public final Store store;
    public final Questions.Waiting waiting;
    public final Profile profile;
    public final Account account;
    public final File summaryFile;
    public final File briefFile;
    public final File debugZip;
    private Thread thread;
    private Bot.Options options;
    /** Keeps the server up to date (activity, applications, stops from the admin). */
    public final Reporter reporter;
    /** Jobs you apply to yourself with the app's help, and their stages. */
    public final Copilot copilot;
    /** Your data, the same in your account: every phone you sign in on, and your Google Drive. */
    public final Sync sync;
    public volatile Session session;
    public volatile LinkedInVoyager currentVoyager;

    public Engine(Platform platform, File dataDir) {
        this.platform = platform;
        this.config = new Config(dataDir);
        this.store = new Store(dataDir);
        this.waiting = new Questions.Waiting(dataDir);
        this.profile = new Profile(dataDir);
        this.profile.store = store;
        this.account = new Account(dataDir);
        this.reporter = new Reporter(this, account);
        this.copilot = new Copilot(this, dataDir);
        this.sync = new Sync(this, dataDir);
        if (account.signedIn()) {
            reporter.ensureStarted();
        }
        this.summaryFile = new File(dataDir, "last_summary.json");
        this.briefFile = new File(config.logDir, "last_session.txt");
        this.debugZip = new File(config.logDir, "debug.zip");
        Util.logFile = new File(config.logDir, "bot.log");
        Map<String, String> cfg = config.load();
        Questions.askMode = Questions.askModeOf(cfg.get("NEW_QUESTIONS"));
        Questions.askTimeoutMin = Config.cfgInt(cfg, "ASK_TIMEOUT_MIN", 0, 1440);
    }

    // ------------------------------------------------------------------ the bot thread
    public synchronized boolean running() {
        return thread != null && thread.isAlive();
    }

    public synchronized Bot.Options options() {
        return running() ? options : null;
    }

    /** Start a session. Returns an error message, or "" when started. */
    public synchronized String start(final Bot.Options opts) {
        if (running()) {
            return "The bot is already running.";
        }
        Map<String, Object> setup = config.status(config.load());
        if (!account.signedIn()) {
            return "Sign in to your Auto Apply account first.";
        }
        if (!Json.bool(setup, "has_cv")) {
            return "Add your CV in Settings first.";
        }
        options = opts;
        Util.STOP.set(false);
        Util.stopReason = "";
        Util.setStatus("state", "starting", "message", "Starting...", "job", null, "next_slot_at", 0, "error", "");
        platform.onRunning(true);
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Engine.this.run(opts);
                } catch (Throwable t) {  // last line of defence: show it on the dashboard
                    log("Unexpected error: " + Util.shortError(t));
                    Util.setStatus("state", "error", "message", Util.shortError(t), "error", Util.shortError(t), "job", null);
                } finally {
                    reporter.runEnded();  // the server must not think it still runs
                    platform.onRunning(false);
                }
            }
        }, "bot");
        thread.start();
        return "";
    }

    public void stop() {
        if (running()) {
            Util.STOP.set(true);
            Util.setStatus("message", "Stopping after the current step...");
        }
    }

    public void signInDone() {
        // LinkedIn session cookies refreshed in CookieManager
    }

    public void join(long millis) throws InterruptedException {
        Thread t;
        synchronized (this) {
            t = thread;
        }
        if (t != null) {
            t.join(millis);
        }
    }

    // ------------------------------------------------------------------ one session
    void checkAi(Ai ai) {
        try {
            ai.ping();
            log("AI connected" + (ai.servedBy.isEmpty() ? "" : " (model: " + ai.servedBy + ")") + ".");
        } catch (Ai.FatalAIError e) {
            log("AI error: " + e.getMessage());
            throw new SetupError(e.getMessage());
        } catch (Util.StopRequested e) {
            throw e;
        } catch (RuntimeException e) {
            log("Could not reach the AI: " + Util.shortError(e));
            throw new SetupError("Could not reach the AI service. Check the internet connection and start again.");
        }
    }

    public static final String NOT_ACTIVE = "Auto apply isn't active on your account yet. You can start once it is.";

    // ------------------------------------------------------------------ ready to send: your yes first
    /** Applications filled up to Submit that wait for your yes, and the ones you said yes to. */
    public List<Map<String, Object>> ready() {
        return store.ready();
    }

    /** You said yes (Apply, or Apply all with ids null): sends them via Voyager API with user cookies. */
    public synchronized int approve(List<String> ids) {
        final List<String> targetIds = ids == null ? store.idsWithStatus("ready") : new ArrayList<String>(ids);
        int n = store.changeStatus(targetIds, "ready", "approved");
        if (n > 0) {
            log("You said yes to " + n + (n == 1 ? " application." : " applications."));
        }
        readyChanged();
        if (n > 0) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    sendApprovedViaVoyager(targetIds);
                }
            }, "voyager-send").start();
        }
        return n;
    }

    /** Submits the approved applications via LinkedIn Voyager HTTP REST using user session cookies */
    public void sendApprovedViaVoyager(List<String> ids) {
        String cookieStr = platform.getCookies("https://www.linkedin.com");
        String liAt = LinkedInVoyager.parseCookie(cookieStr, "li_at");
        String jsessionId = LinkedInVoyager.parseCookie(cookieStr, "JSESSIONID");
        if (liAt.isEmpty() || jsessionId.isEmpty()) {
            log("Cannot apply: Missing LinkedIn cookies. Sign in to LinkedIn on Home first.");
            platform.onSignInNeeded();
            return;
        }
        LinkedInVoyager voyager = new LinkedInVoyager(liAt, jsessionId);
        for (String jobId : ids) {
            Map<String, Object> j = store.find(jobId);
            if (j == null) continue;
            String title = Json.str(j, "title");
            String company = Json.str(j, "company");
            log("Sending application to: " + title + (company.isEmpty() ? "" : " @ " + company));
            Map<String, Object> answers = (j.get("qa") instanceof Map) ? (Map<String, Object>) j.get("qa") : Json.obj();
            Map<String, Object> res = voyager.submitApplication(jobId, answers);
            if (Json.bool(res, "ok")) {
                Bot.recordSubmit(store.state(), 15, Util.now());
                store.saveState();
                store.setJobStatus(jobId, "applied", "Applied via LinkedIn Voyager API");
                log("  APPLIED: " + title + (company.isEmpty() ? "" : " @ " + company));
                platform.toast("🎉 Applied to " + title + " at " + company + "!");
                sync.kick(2);
            } else {
                String err = Json.str(res, "error");
                log("  Failed to apply: " + err);
                store.setJobStatus(jobId, "error", err);
                platform.toast("Could not submit: " + err);
            }
            readyChanged();
            Util.pause(3, 6);
        }
    }

    /** You said no (Skip): not sent. ids null = every ready one. */
    public int skipReady(List<String> ids) {
        int n = store.changeStatus(ids == null ? store.idsWithStatus("ready") : ids, "ready", "user_skipped");
        readyChanged();
        return n;
    }

    /** The notification follows the applications that wait for you. */
    public void readyChanged() {
        try {
            platform.onReady(store.ready());
        } catch (RuntimeException e) {
            log("Could not show the notification: " + Util.shortError(e, 80));
        }
    }

    /** Signed in, and Pro is on. Returns the problem, or null. */
    String checkAccount() {
        if (!account.signedIn()) {
            return "Sign in to your Auto Apply account first.";
        }
        try {
            Map<String, Object> me = account.refresh();
            return Json.bool(me, "pro") ? null : NOT_ACTIVE;
        } catch (Account.AuthError e) {
            return "You were signed out of your Auto Apply account. Sign in again in Settings.";
        } catch (Account.ServerError e) {
            return e.getMessage();
        } catch (RuntimeException e) {
            log("Could not reach the Auto Apply server: " + Util.shortError(e));
            return "Could not reach the Auto Apply server. Check the internet connection and start again.";
        }
    }

    /** The AI, through your account on the server. */
    public Ai newAi(String cvText, String notes, String purpose) {
        Ai ai = new Ai(account.token(), "", cvText, notes);
        ai.url = account.aiUrl();
        ai.purpose = purpose;
        return ai;
    }

    /** Your setup answers and your extra notes, for the AI. */
    public String notesForAi() {
        String setup = profile.notes(Util.now());
        String about = config.about();
        return (setup + (setup.isEmpty() || about.isEmpty() ? "" : "\n") + about).trim();
    }

    List<String> pickSearch(Map<String, String> cfg, Ai ai, StringBuilder locationOut) {
        List<String> keywords = Util.splitList(cfg.get("KEYWORDS"));
        String location = cfg.get("LOCATION") == null ? "" : cfg.get("LOCATION").trim();
        if (!keywords.isEmpty() && !location.isEmpty()) {
            locationOut.append(location);
            return keywords;
        }
        log("Asking the AI which jobs to search for, based on your CV...");
        StringBuilder aiLocation = new StringBuilder();
        List<String> aiKeywords = ai.suggestSearch(aiLocation);
        if (keywords.isEmpty()) {
            keywords = aiKeywords;
        }
        if (location.isEmpty()) {
            location = aiLocation.toString();
        }
        if (keywords.isEmpty()) {
            throw new SetupError("The AI did not suggest any keywords. Add job titles in Settings and start again.");
        }
        log("AI picked: " + Util.join(", ", keywords) + (location.isEmpty() ? "" : " in " + location)
                + " (set your own job titles and location in Settings).");
        locationOut.append(location);
        return keywords;
    }

    public void run(Bot.Options args) {
        Util.STOP.set(false);
        Util.stopReason = "";
        String mode = args.dryRun ? "dry" : (args.noSubmit ? "test" : "apply");
        Util.setStatus("state", "starting", "message", "Starting...", "job", null, "next_slot_at", 0, "mode", mode, "error", "");
        final Session sess = new Session(mode);
        session = sess;
        Map<String, String> cfg = config.load();
        Questions.askMode = Questions.askModeOf(cfg.get("NEW_QUESTIONS"));
        Questions.askTimeoutMin = Config.cfgInt(cfg, "ASK_TIMEOUT_MIN", 0, 1440);
        if (args.retryFailed) {
            log(store.forgetFailed() + " stuck / error jobs will be tried again.");
        }
        int oldErrors = store.forgetOldOpenErrors();
        if (oldErrors > 0) {
            log(oldErrors + " job(s) where errors occurred before will be tried again.");
        }
        File cv = config.cvFile(cfg);
        String cvText = config.cvText();
        String problem = null;
        if (cv == null) {
            problem = "Add your CV in Settings first.";
        } else if (cvText.length() < 150) {
            problem = "Could not read the text of your CV (it may be a scanned image). Upload a text-based PDF or a .txt file.";
        }
        if (problem == null) {
            Util.setStatus("message", "Checking your account...");
            problem = checkAccount();
        }
        if (problem != null) {
            log(problem);
            Util.setStatus("state", "error", "message", problem, "error", problem);
            return;
        }
        Ai ai = newAi(cvText, notesForAi(), "apply");
        reporter.runStarted(mode);
        String[] fin = {"idle", "Finished"};

        // Authenticate to LinkedIn via Voyager cookies (NO page automation)
        String cookieStr = platform.getCookies("https://www.linkedin.com");
        String liAt = LinkedInVoyager.parseCookie(cookieStr, "li_at");
        String jsessionId = LinkedInVoyager.parseCookie(cookieStr, "JSESSIONID");
        if (liAt.isEmpty() || jsessionId.isEmpty()) {
            String msg = "Sign in to LinkedIn on Home first.";
            log(msg);
            Util.setStatus("state", "signin", "message", msg, "error", msg);
            platform.onSignInNeeded();
            reporter.runEnded();
            return;
        }
        LinkedInVoyager voyager = new LinkedInVoyager(liAt, jsessionId);
        currentVoyager = voyager;
        Util.setStatus("message", "Connecting to LinkedIn via cookies...");
        Map<String, Object> sessionCheck = voyager.checkSession();
        if (!Json.bool(sessionCheck, "ok")) {
            String msg = "LinkedIn session expired. Sign in again on Home.";
            log(msg);
            Util.setStatus("state", "signin", "message", msg, "error", msg);
            platform.onSignInNeeded();
            reporter.runEnded();
            return;
        }
        log("Connected to LinkedIn as " + Json.str(sessionCheck, "name") + " (pure HTTP via cookies).");

        boolean started = false;
        Bot bot = null;
        try {
            Util.setStatus("message", "Checking the AI connection...");
            checkAi(ai);
            Util.setStatus("message", "Choosing what to search for...");
            StringBuilder location = new StringBuilder();
            List<String> keywords = pickSearch(cfg, ai, location);
            bot = new Bot(this, cfg, args, ai, voyager, keywords, location.toString(), sess);
            started = true;
            log("Searches: " + Util.join(" | ", keywords) + (location.length() > 0 ? "  (" + location + ")" : "")
                    + ". Pace: max " + bot.perHour + " per hour, " + bot.perDay + " per day. Evaluates at " + bot.minScore
                    + "/10 or more.");
            bot.run();
        } catch (Util.StopRequested e) {
            String why = Util.stopReason;
            if (why.isEmpty()) {
                log("Stopped by you.");
                fin = new String[]{"idle", "Stopped"};
            } else {
                fin = new String[]{"idle", why};
            }
        } catch (Ai.FatalAIError e) {
            log("AI error: " + e.getMessage());
            fin = new String[]{"error", e.getMessage()};
        } catch (Bot.TooManyErrors e) {
            log("Stopped after 10 errors in a row.");
            fin = new String[]{"error", "Stopped after 10 errors in a row"};
        } catch (SetupError e) {
            log(e.getMessage());
            fin = new String[]{"error", e.getMessage()};
        } catch (RuntimeException e) {
            log("Unexpected error: " + Util.shortError(e));
            fin = new String[]{"error", Util.shortError(e)};
        } finally {
            currentVoyager = null;
            sess.ended = Util.now();
            List<String> lines = new ArrayList<String>();
            Map<String, Object> summary = null;
            if (started) {
                summary = sess.save(summaryFile, briefFile);
                lines = sess.lines();
                for (String line : lines) {
                    log(line);
                }
                int[] counts = Bot.appliedCounts(store.stateCopy(), Util.now());
                log("Applied today: " + counts[1] + ".");
            }
            Util.STOP.set(false);
            Util.stopReason = "";
            Util.setStatus("state", fin[0], "message", fin[1], "job", null, "next_slot_at", 0,
                    "error", "error".equals(fin[0]) ? fin[1] : "");
            reporter.runEnded();
            if (started) {
                platform.onSessionEnd(summary, lines);
            }
        }
    }
}
