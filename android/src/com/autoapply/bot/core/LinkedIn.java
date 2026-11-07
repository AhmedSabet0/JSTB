package com.autoapply.bot.core;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.autoapply.bot.core.Util.log;
import static com.autoapply.bot.core.Util.pause;

/** LinkedIn automation: sign-in check, search, job pages, Easy Apply forms (classic and newer layouts). */
public final class LinkedIn {
    public static final String JOB_URL = "https://www.linkedin.com/jobs/view/%s/";
    public static final String APPLY_URL = "https://www.linkedin.com/jobs/view/%s/apply/?openSDUIApplyFlow=true";
    public static final String FEED_URL = "https://www.linkedin.com/feed/";
    static final int MAX_STEPS = 15;
    static final int MAX_FIXES = 3;

    // LinkedIn selectors. When LinkedIn changes its layout, fix them here (and in li.js).
    static final List<String> SEL_TITLE = strs(".job-details-jobs-unified-top-card__job-title h1",
            ".job-details-jobs-unified-top-card__job-title", ".jobs-unified-top-card__job-title", "h1.t-24");
    static final List<String> SEL_TITLE_GENERIC = strs("main h1", "h1");
    static final List<String> SEL_COMPANY = strs(".job-details-jobs-unified-top-card__company-name a",
            ".job-details-jobs-unified-top-card__company-name", ".jobs-unified-top-card__company-name a",
            ".jobs-unified-top-card__company-name");
    static final List<String> SEL_DESCRIPTION = strs("#job-details", ".jobs-description__content",
            ".jobs-description-content__text", ".jobs-box__html-content", "[class*='jobs-description']",
            "[data-testid*='description']");
    static final List<String> SEL_TOP_CARD = strs(".job-details-jobs-unified-top-card__container--two-pane",
            ".jobs-unified-top-card", ".jobs-details__main-content", "main");
    static final String SEL_APPLY_BUTTONS = ".jobs-apply-button--top-card button, .jobs-s-apply button, "
            + "button.jobs-apply-button, button[aria-label*='Easy Apply'], a[aria-label*='Easy Apply'], "
            + "a[href*='openSDUIApplyFlow']";
    static final String SEL_RESULTS_ANY = "[data-occludable-job-id], [data-job-id], a[href*='/jobs/view/'], "
            + "a[href*='currentJobId='], [data-view-name*='job-card'], .jobs-search-no-results-banner, "
            + ".jobs-search-two-pane__no-results-banner--expand";
    static final String SEL_RESULTS_LIST = ".jobs-search-results-list, .scaffold-layout__list, .scaffold-layout__list > div";
    static final String SEL_MODAL = ".jobs-easy-apply-modal, [data-test-modal-id='easy-apply-modal'], [role='dialog'], "
            + "dialog[open], .artdeco-modal";
    static final String SEL_DISMISS = "button[aria-label='Dismiss'], button[aria-label='Close'], .artdeco-modal__dismiss";
    static final String SEL_DISCARD_CONFIRM = "button[data-control-name='discard_application_confirm_btn']";
    static final String SEL_TYPEAHEAD_OPTION = "[role='listbox'] [role='option'], .basic-typeahead__selectable, "
            + ".search-typeahead-v2__hit";
    static final String SEL_LOGGED_IN = "#global-nav, .global-nav, a[href*='/mynetwork/'], a[href*='/messaging/']";
    static final String SEL_LOGGED_OUT = "a[href*='/login'], a[href*='/signup'], form.login__form";

    // Text patterns (used in the page too, so they are JavaScript regexes as well).
    static final String PAT_APPLIED = "\\bApplied\\s+\\d+\\s+\\w+\\s+ago\\b|\\bApplied now\\b|Application submitted|See application";
    static final String PAT_CLOSED = "No longer accepting applications";
    static final String PAT_LIMIT = "limit daily submissions|reached (?:the|your) (?:daily )?(?:easy apply )?(?:application )?limit"
            + "|come back tomorrow";
    static final String PAT_SENT = "application (?:was )?sent|application submitted";

    static final Pattern AUTH_PATH = Pattern.compile("^/(login|checkpoint|authwall|uas|signup)");
    /** Questions that need a number even when the field is a plain text box. */
    public static final Pattern NUMERIC_Q = Pattern.compile(
            "\\b(how many|number of|how long)\\b|\\byears?\\b.{0,40}\\bexperience\\b|\\bexperience\\b.{0,40}\\byears?\\b"
                    + "|\\bsalary\\b|\\bcompensation\\b|\\bctc\\b|\\bnotice period\\b|\\bgpa\\b|\\bgrade point\\b",
            Pattern.CASE_INSENSITIVE);
    static final Pattern NUM_ERR = Pattern.compile("number|numeric|digit|decimal|whole|integer", Pattern.CASE_INSENSITIVE);
    static final Pattern INVALID_ERR = Pattern.compile("\\binvalid\\b", Pattern.CASE_INSENSITIVE);
    static final List<Object> NEXT = Json.list(
            Json.list("Review your application", "^\\s*review( your application)?\\s*$"),
            Json.list("Continue to next step", "^\\s*(next|continue|continue to next step)\\s*$"));
    static final List<Object> SUBMIT = Json.list(Json.list("Submit application", "^\\s*submit( application)?\\s*$"));
    static final String NUM = "(-?\\d+(?:\\.\\d+)?)";

    private static List<String> strs(String... a) {
        List<String> l = new ArrayList<String>();
        for (String s : a) {
            l.add(s);
        }
        return l;
    }

    /** A page script failed or an element could not be used. */
    public static class PageError extends RuntimeException {
        public PageError(String m) {
            super(m);
        }
    }

    /** Answers a form question: a String, a List of options (checkboxes), or null. */
    public interface Resolver {
        Object resolve(String question, String kind, List<String> options, String hint, String placeholder, boolean noCache,
                       List<String> rejected, int maxlen, List<String> extra);
    }

    /** Set by the bot. */
    public interface Hooks {
        void remember(String question, Object value);

        void forget(String question);

        /** True when new questions (and answers LinkedIn rejects) are asked to you. */
        boolean manual();

        void signInNeeded();
    }

    public static final class Outcome {
        public final String status;
        public final String note;
        public final List<Object> qa;

        Outcome(String status, String note, List<Object> qa) {
            this.status = status;
            this.note = note;
            this.qa = qa;
        }
    }

    public Page page;
    private final Scripts scripts;
    private final File debugDir;
    public Hooks hooks;
    public boolean dumped = false;
    private final Map<String, Integer> dumpCounts = new HashMap<String, Integer>();
    private Map<String, List<String>> rejectedHistory = new HashMap<String, List<String>>();
    private Map<String, Integer> numTries = new HashMap<String, Integer>();
    private boolean signedInLogged = false;
    private final String resultKey = "__r" + Long.toHexString(Util.RANDOM.nextLong() & 0xffffffffffL);
    private long evalSeq = 0;
    /** Set when you finished signing in on the sign-in screen. */
    public final AtomicBoolean signInSignal = new AtomicBoolean(false);
    // the CV to upload when a form asks for one
    private File cvFile;
    private String cvName = "cv.pdf";
    private String cvB64;

    public LinkedIn(Page page, Scripts scripts, File debugDir) {
        this.page = page;
        this.scripts = scripts;
        this.debugDir = debugDir;
    }

    public void setCv(File file, String displayName) {
        cvFile = file;
        cvName = displayName == null || displayName.isEmpty() ? (file == null ? "cv.pdf" : file.getName()) : displayName;
        cvB64 = null;
    }

    // ------------------------------------------------------------------ page scripts
    /** Run a section of li.js with `arg` and return its result (Map, List, String, Number, Boolean or null). */
    public Object js(String name, Object arg) {
        String body = scripts.get(name);
        String helpers = scripts.helpers();
        String argJson = Json.stringify(arg);
        boolean async = body.contains("await ");
        for (int attempt = 0; attempt < 3; attempt++) {
            if (page.isClosed()) {
                throw new Util.BrowserClosed("The bot's page was closed");
            }
            Map<String, Object> res;
            if (!async) {
                String code = "(function(arg){\n" + helpers + "\ntry { return {v: (function(){\n" + body
                        + "\n})()}; } catch (e) { return {e: String(e && e.message || e)}; }\n})(" + argJson + ")";
                res = Json.map(Json.tryParse(page.eval(code)));
            } else {
                res = jsAsync(body, helpers, argJson);
            }
            if (res.containsKey("v")) {
                return res.get("v");
            }
            if (res.containsKey("e")) {
                throw new PageError("Page script " + name + " failed: " + res.get("e"));
            }
            Util.realSleep(1.0);  // usually a page that is still loading
        }
        throw new PageError("Page script " + name + " did not run");
    }

    private Map<String, Object> jsAsync(String body, String helpers, String argJson) {
        String id = "k" + (++evalSeq);
        String store = "(window['" + resultKey + "'] || (Object.defineProperty(window, '" + resultKey
                + "', {value: {}, configurable: true, enumerable: false}), window['" + resultKey + "']))";
        String code = "(function(arg){\n" + helpers + "\nvar s = " + store + ";\n(async function(){\n" + body
                + "\n})().then(function(v){ s['" + id + "'] = {v: v === undefined ? null : v}; }, function(e){ s['" + id
                + "'] = {e: String(e && e.message || e)}; });\nreturn 1;\n})(" + argJson + ")";
        if (!"1".equals(page.eval(code).trim())) {
            return Json.obj();
        }
        String poll = "(function(){ var s = window['" + resultKey + "']; if (!s || !s['" + id + "']) return null; var r = s['"
                + id + "']; delete s['" + id + "']; return r; })()";
        long end = System.currentTimeMillis() + 60000;
        while (System.currentTimeMillis() < end) {
            Object r = Json.tryParse(page.eval(poll));
            if (r instanceof Map) {
                return Json.map(r);
            }
            Util.realSleep(0.15);
        }
        return Json.obj("v", null);
    }

    private boolean jsTrue(String name, Object arg) {
        try {
            return Json.truthy(js(name, arg));
        } catch (PageError e) {
            return false;
        }
    }

    /** Click an element (waits up to `timeout` seconds for it). */
    public boolean click(final String selector, double timeout) {
        Boolean ok = Util.waitUntil(new Util.Check<Boolean>() {
            @Override
            public Boolean get() {
                return Json.truthy(js("click", selector));
            }
        }, timeout, 0.4);
        return ok != null && ok;
    }

    /** Save the page (HTML) to logs/debug for troubleshooting. */
    public void dump(String name, String kind, int limit) {
        int count = dumpCounts.containsKey(kind) ? dumpCounts.get(kind) : 0;
        if (count >= limit) {
            return;
        }
        dumpCounts.put(kind, count + 1);
        try {
            Object html = js("html", null);
            debugDir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            Util.writeText(new File(debugDir, stamp + "-" + name + ".html"), String.valueOf(html));
            dumped = true;
        } catch (RuntimeException e) {
            if (e instanceof Util.BrowserClosed || e instanceof Util.StopRequested) {
                throw e;
            }
        }
    }

    public void gotoUrl(String url) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                page.navigate(url, 60);
                break;
            } catch (Util.BrowserClosed e) {
                throw e;
            } catch (Util.StopRequested e) {
                throw e;
            } catch (RuntimeException e) {
                if (attempt == 2) {
                    throw e;
                }
                pause(3, 6);
            }
        }
        if (onAuthPage()) {
            waitForLogin(null);
            page.navigate(url, 60);
        }
    }

    // ------------------------------------------------------------------ sign-in
    public boolean onAuthPage() {
        try {
            String path = new URL(page.url()).getPath();
            return AUTH_PATH.matcher(path == null ? "" : path).find();
        } catch (IOException e) {
            return false;
        } catch (RuntimeException e) {
            if (e instanceof Util.BrowserClosed) {
                throw e;
            }
            return false;
        }
    }

    public String authState() {
        if (onAuthPage()) {
            return "auth";
        }
        try {
            Object s = js("auth", Json.obj("inSel", SEL_LOGGED_IN, "outSel", SEL_LOGGED_OUT));
            return s == null ? "unknown" : String.valueOf(s);
        } catch (PageError e) {
            return "unknown";
        }
    }

    private String knownState() {
        String s = authState();
        return "unknown".equals(s) ? null : s;
    }

    public void ensureSignedIn() {
        gotoUrl(FEED_URL);
        String state = Util.waitUntil(new Util.Check<String>() {
            @Override
            public String get() {
                return knownState();
            }
        }, 15);
        if (!"in".equals(state)) {
            waitForLogin(state == null ? "unknown" : state);
        } else if (!signedInLogged) {
            signedInLogged = true;
            log("Signed in to LinkedIn.");
        }
    }

    /** Wait until you sign in on the app's LinkedIn screen (the bot's own page is not shown on the phone). */
    public void waitForLogin(String state) {
        log("Please sign in to LinkedIn: open the app and tap \"Sign in to LinkedIn\". The bot continues by itself.");
        Util.setStatus("state", "signin", "message", "Sign in to LinkedIn in the app. The bot continues by itself.");
        if (hooks != null) {
            hooks.signInNeeded();
        }
        int waited = 0;
        while (true) {
            Util.realSleep(3);
            waited += 3;
            if (signInSignal.getAndSet(false) || waited % 30 == 0) {
                try {
                    page.navigate(FEED_URL, 60);
                } catch (Util.BrowserClosed e) {
                    throw e;
                } catch (Util.StopRequested e) {
                    throw e;
                } catch (RuntimeException e) {
                    continue;
                }
                String s = Util.waitUntil(new Util.Check<String>() {
                    @Override
                    public String get() {
                        return knownState();
                    }
                }, 15);
                if ("in".equals(s)) {
                    break;
                }
            }
        }
        signedInLogged = true;
        log("Signed in. Continuing.");
        Util.setStatus("state", "working", "message", "Signed in. Continuing...");
    }

    public String uiLanguage() {
        try {
            Object l = js("lang", null);
            return l == null ? "" : String.valueOf(l);
        } catch (PageError e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ search
    public List<String> search(String url) {
        gotoUrl(url);
        Util.waitUntil(new Util.Check<Boolean>() {
            @Override
            public Boolean get() {
                return Json.truthy(js("exists", SEL_RESULTS_ANY));
            }
        }, 15);
        for (int k = 0; k < 8; k++) {
            jsTrue("scroll", SEL_RESULTS_LIST);
            pause(0.4, 0.9);
        }
        List<String> ids = new ArrayList<String>();
        try {
            ids.addAll(Json.strings(js("ids", null)));
        } catch (PageError e) {
            log("  Could not read the results: " + e.getMessage());
        }
        if (ids.size() < 5) {
            String query = "";
            try {
                query = new URL(url).getQuery();
            } catch (IOException e) {
                // keep empty
            }
            Object html = null;
            try {
                html = js("guest", query == null ? "" : query);
            } catch (PageError e) {
                // the guest list is only a fallback
            }
            Matcher m = Pattern.compile("urn:li:jobPosting:(\\d+)").matcher(html == null ? "" : String.valueOf(html));
            while (m.find()) {
                if (!ids.contains(m.group(1))) {
                    ids.add(m.group(1));
                }
            }
        }
        if (ids.size() < 2) {
            dump("search", "search", 2);
        }
        return ids;
    }

    // ------------------------------------------------------------------ job page
    public Map<String, Object> openJob(String jobId) {
        gotoUrl(String.format(JOB_URL, jobId));
        final List<String> titles = new ArrayList<String>(SEL_TITLE);
        titles.addAll(SEL_TITLE_GENERIC);
        Util.waitUntil(new Util.Check<Boolean>() {
            @Override
            public Boolean get() {
                return Json.truthy(js("title_ready", titles));
            }
        }, 15);
        Util.waitUntil(new Util.Check<Boolean>() {
            @Override
            public Boolean get() {
                return Json.truthy(js("desc_ready", SEL_DESCRIPTION));
            }
        }, 10);
        Map<String, Object> info = Json.map(js("read_job", Json.obj("title", SEL_TITLE, "title_generic", SEL_TITLE_GENERIC,
                "company", SEL_COMPANY, "description", SEL_DESCRIPTION)));
        String state = Util.waitUntil(new Util.Check<String>() {
            @Override
            public String get() {
                return jobState();
            }
        }, 12);
        info.put("state", state);
        if ("page".equals(Json.str(info, "source"))) {
            dump("job-" + jobId, "job", 2);
        }
        return info;
    }

    public String jobState() {
        Map<String, Object> s = Json.map(js("job_state", Json.obj("sel", SEL_APPLY_BUTTONS, "top", SEL_TOP_CARD,
                "applied", PAT_APPLIED, "closed", PAT_CLOSED)));
        if (Json.bool(s, "easy")) {
            return "easy";
        }
        if (Json.bool(s, "closed")) {
            return "closed";
        }
        if (Json.bool(s, "applied")) {
            return "already_applied";
        }
        if (Json.bool(s, "other")) {
            return "no_easy_apply";
        }
        return null;
    }

    // ------------------------------------------------------------------ Easy Apply
    /** Fill and submit the Easy Apply form. */
    public Outcome apply(String jobId, Resolver resolve, boolean noSubmit) {
        Object control = js("tag_easy_apply", SEL_APPLY_BUTTONS);
        if (!(control instanceof Map)) {
            return new Outcome("no_easy_apply", "", new ArrayList<Object>());
        }
        rejectedHistory = new HashMap<String, List<String>>();
        numTries = new HashMap<String, Integer>();
        pause(0.8, 1.8);
        String href = Json.str(Json.map(control), "href");
        Util.Check<String> afterClick = new Util.Check<String>() {
            @Override
            public String get() {
                return afterClick();
            }
        };
        String opened;
        if (href.contains("/apply")) {
            String target = href;
            try {
                target = new URL(new URL(page.url()), href).toString();
            } catch (IOException e) {
                // use as it is
            }
            gotoUrl(target);  // newer layout: Easy Apply is a link
            opened = Util.waitUntil(afterClick, 20);
        } else {
            click("[data-aa-ea='1']", 10);
            opened = Util.waitUntil(afterClick, 8);
            if (opened == null) {
                opened = tapEasyApply(afterClick);  // some pages only react to a real tap
            }
        }
        if (opened == null) {
            gotoUrl(String.format(APPLY_URL, jobId));  // LinkedIn's direct apply link
            opened = Util.waitUntil(afterClick, 15);
            if (opened == null) {
                opened = tapEasyApply(afterClick);
            }
        }
        if ("limit".equals(opened)) {
            return new Outcome("daily_limit", "", new ArrayList<Object>());
        }
        if (opened == null) {
            dump("no-form-" + jobId, "no-form", 3);
            return new Outcome("error", "The Easy Apply form did not open (page saved for troubleshooting)",
                    new ArrayList<Object>());
        }
        return steps(jobId, resolve, noSubmit);
    }

    /** The Easy Apply window did not open from a script click: tap the button like a finger. */
    private String tapEasyApply(Util.Check<String> afterClick) {
        Object again;
        try {
            again = js("tag_easy_apply", SEL_APPLY_BUTTONS);
        } catch (PageError e) {
            return null;
        }
        if (!(again instanceof Map) || !Json.str(Json.map(again), "href").isEmpty() || !tapEl("[data-aa-ea='1']")) {
            return null;
        }
        log("    Tapped Easy Apply (the first click did not open it).");
        return Util.waitUntil(afterClick, 12);
    }

    /** Tap an element like a finger (real input), for buttons that ignore script clicks. */
    boolean tapEl(String selector) {
        try {
            if (js("rect", selector) == null) {  // scroll it into view first, then measure where it ended up
                return false;
            }
            Util.realSleep(0.6);
            Object r = js("rect", selector);
            if (!(r instanceof Map) || Json.bool(Json.map(r), "covered")) {
                return false;
            }
            Map<String, Object> at = Json.map(r);
            return page.tap(Json.dbl(at, "x", 0), Json.dbl(at, "y", 0), Json.dbl(at, "vw", 0));
        } catch (PageError e) {
            return false;
        }
    }

    private String afterClick() {
        if (limitReached()) {
            return "limit";
        }
        if (findModal()) {
            return "modal";
        }
        if (jsTrue("tag_continue", null)) {
            click("[data-aa-continue='1']", 8);
        }
        return null;
    }

    public boolean findModal() {
        return jsTrue("tag_modal", SEL_MODAL);
    }

    boolean limitReached() {
        return jsTrue("limit", PAT_LIMIT);
    }

    /** Tag the first matching form button; click it with click("[data-aa-btn='1']"). */
    boolean button(List<Object> specs) {
        return jsTrue("button", specs);
    }

    List<String> errors() {
        try {
            return Json.strings(js("errors", null));
        } catch (PageError e) {
            return new ArrayList<String>();
        }
    }

    private String fingerprint() {
        try {
            return String.valueOf(js("fingerprint", null));
        } catch (PageError e) {
            return "";
        }
    }

    Map<String, Object> sentArg() {
        return Json.obj("sent", PAT_SENT, "applied", PAT_APPLIED, "top", SEL_TOP_CARD);
    }

    private Outcome steps(String jobId, Resolver resolve, boolean noSubmit) {
        List<Object> qa = new ArrayList<Object>();
        int same = 0;
        for (int step = 0; step < MAX_STEPS; step++) {
            if (!findModal()) {
                if (jsTrue("sent", sentArg())) {
                    closeDialogs();
                    return new Outcome("applied", "", qa);
                }
                dump("closed-" + jobId, "closed", 3);
                return new Outcome("error", "The form closed unexpectedly", qa);
            }
            if (limitReached()) {
                discard();
                return new Outcome("daily_limit", "", qa);
            }
            fill(resolve, qa, 0);
            pause(0.6, 1.4);

            if (button(SUBMIT)) {
                if (noSubmit) {
                    discard();
                    return new Outcome("dry_run_match", "Stopped before Submit (test run)", qa);
                }
                return submit(jobId, qa);
            }
            if (!button(NEXT)) {
                return giveUp(jobId, "No Next / Review / Submit button found", qa, false);
            }
            String before = fingerprint();
            click("[data-aa-btn='1']", 8);
            pause(1.2, 2.4);
            findModal();
            if (fingerprint().equals(before) && errors().isEmpty()) {
                pause(1.0, 1.6);  // a slow step, or the page ignored the script click
                findModal();
                if (fingerprint().equals(before) && errors().isEmpty() && button(NEXT) && tapEl("[data-aa-btn='1']")) {
                    log("    Tapped the button (the click did not go through).");
                    pause(1.2, 2.4);
                    findModal();
                }
            }

            // LinkedIn rejected something: repair the answers and try again, changing approach each time.
            List<String> errs = errors();
            int attempt = 0;
            while (!errs.isEmpty() && attempt < MAX_FIXES) {
                attempt++;
                log("    LinkedIn rejected: " + Util.cut(Util.join(" / ", errs), 150) + "  -> fixing (try " + attempt + "/"
                        + MAX_FIXES + ")");
                boolean fixed = false;
                if (Pattern.compile("resume|cv\\b|upload|file", Pattern.CASE_INSENSITIVE).matcher(Util.join(" ", errs)).find()) {
                    fixed = uploadCv(true);
                }
                fixed = fill(resolve, qa, attempt) || fixed;
                if (!fixed) {
                    break;
                }
                pause(0.6, 1.2);
                if (!button(NEXT)) {
                    break;
                }
                click("[data-aa-btn='1']", 8);
                pause(1.2, 2.4);
                findModal();
                errs = errors();
            }
            if (!errs.isEmpty()) {
                return giveUp(jobId, "Form error: " + Util.cut(Util.join(" / ", errs), 200), qa, true);
            }
            if (fingerprint().equals(before)) {
                same++;
                if (same >= 3) {
                    return giveUp(jobId, "The form did not move to the next step", qa, false);
                }
            } else {
                same = 0;
            }
        }
        return giveUp(jobId, "Too many form steps", qa, false);
    }

    private Outcome giveUp(String jobId, String reason, List<Object> qa, boolean formError) {
        if (formError && hooks != null) {  // do not reuse answers that LinkedIn still rejects
            try {
                for (Object o : Json.arr(js("scan", null))) {
                    Map<String, Object> f = Json.map(o);
                    if (!Json.str(f, "error").isEmpty() && !Json.str(f, "question").isEmpty()) {
                        hooks.forget(Json.str(f, "question"));
                    }
                }
            } catch (PageError e) {
                // nothing to forget
            }
        }
        dump("stuck-" + jobId, "stuck", 10);
        discard();
        return new Outcome("stuck", reason, qa);
    }

    private Outcome submit(String jobId, List<Object> qa) {
        if (jsTrue("tag_follow", null)) {
            click("[data-aa-follow='1']", 4);
        }
        pause(0.8, 1.8);
        Util.shield();  // once Submit is clicked, finish and record the result even if Stop is pressed
        try {
            if (!button(SUBMIT) || !click("[data-aa-btn='1']", 10)) {
                return giveUp(jobId, "The Submit button could not be clicked", qa, false);
            }
            Util.Check<String> result = new Util.Check<String>() {
                @Override
                public String get() {
                    if (jsTrue("sent", sentArg())) {
                        return "ok";
                    }
                    if (limitReached()) {
                        return "limit";
                    }
                    return null;
                }
            };
            String done = Util.waitUntil(result, 15);
            if (done == null && findModal() && errors().isEmpty() && button(SUBMIT) && tapEl("[data-aa-btn='1']")) {
                log("    Tapped Submit (the click did not go through).");
                done = Util.waitUntil(result, 15);
            }
            if ("limit".equals(done)) {
                discard();
                return new Outcome("daily_limit", "", qa);
            }
            if (done == null && findModal() && button(SUBMIT)) {
                List<String> errs = errors();
                String note = errs.isEmpty() ? "Submit did not complete" : "Submit failed: " + Util.cut(Util.join(" / ", errs), 200);
                return giveUp(jobId, note, qa, false);
            }
            pause(1, 2);
            closeDialogs();
            return new Outcome("applied", done == null ? "Submitted (no confirmation seen)" : "", qa);
        } finally {
            Util.unshield();
        }
    }

    public void closeDialogs() {
        for (int k = 0; k < 3; k++) {
            if (!jsTrue("tag_close", null)) {
                return;
            }
            if (!click("[data-aa-close='1']", 4)) {
                return;
            }
            pause(0.8, 1.5);
        }
    }

    /** Close the form without sending it (and confirm "Discard"). */
    public void discard() {
        try {
            if (!findModal()) {
                return;
            }
            if (jsTrue("tag_dismiss", SEL_DISMISS)) {
                click("[data-aa-x='1']", 4);
                pause(0.8, 1.5);
            }
            Boolean confirm = Util.waitUntil(new Util.Check<Boolean>() {
                @Override
                public Boolean get() {
                    return jsTrue("tag_discard", SEL_DISCARD_CONFIRM);
                }
            }, 2, 0.3);
            if (confirm != null && confirm) {
                click("[data-aa-discard='1']", 4);
            }
            pause(0.8, 1.5);
        } catch (PageError e) {
            jsTrue("key", Json.obj("sel", null, "key", "Escape"));
        }
    }

    private boolean uploadCv(boolean force) {
        if (cvFile == null || !cvFile.getName().toLowerCase(Locale.ROOT).matches(".*\\.(pdf|docx?)$")) {
            return false;
        }
        Map<String, Object> info = Json.map(js("tag_file", null));
        if (!Json.bool(info, "found")) {
            return false;
        }
        if (Json.bool(info, "hasResume") && !force) {
            return false;
        }
        if (cvB64 == null) {
            try {
                cvB64 = Util.base64(Util.readBytes(cvFile));
            } catch (IOException e) {
                log("    Could not read your CV file: " + e.getMessage());
                return false;
            }
        }
        String lower = cvFile.getName().toLowerCase(Locale.ROOT);
        String mime = lower.endsWith(".pdf") ? "application/pdf" : lower.endsWith(".docx")
                ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document" : "application/msword";
        Object n = js("upload", Json.obj("sel", "[data-aa-file='1']", "name", cvName, "mime", mime, "b64", cvB64));
        if (!Json.truthy(n)) {
            return false;
        }
        log("    Uploaded your CV");
        pause(2.5, 4);
        return true;
    }

    // ------------------------------------------------------------------ form filling
    /** Answer the fields of the current step. attempt > 0 = repair fields LinkedIn rejected. True if anything changed. */
    public boolean fill(Resolver resolve, List<Object> qa, int attempt) {
        uploadCv(false);
        Set<String> handled = new HashSet<String>();
        boolean changed = false;
        for (int pass = 0; pass < 2; pass++) {  // a second pass catches questions revealed by earlier answers
            boolean acted = false;
            List<Object> fields;
            try {
                fields = Json.arr(js("scan", null));
            } catch (PageError e) {
                log("    Could not read the form: " + Util.shortError(e, 120));
                break;
            }
            for (Object o : fields) {
                Map<String, Object> field = Json.map(o);
                try {
                    acted = fillField(field, resolve, qa, attempt, handled) || acted;
                } catch (PageError e) {
                    log("    Could not fill '" + Util.cut(Json.str(field, "question"), 60) + "': " + Util.shortError(e, 120));
                }
            }
            changed = changed || acted;
            if (!acted) {
                break;
            }
            pause(0.3, 0.8);
        }
        return changed;
    }

    private List<String> noteRejected(String key, String value) {
        List<String> history = rejectedHistory.get(key);
        if (history == null) {
            history = new ArrayList<String>();
            rejectedHistory.put(key, history);
        }
        if (value != null && !value.isEmpty() && !history.contains(value)) {
            history.add(value);
        }
        return history;
    }

    private String ask(Resolver resolve, String q, String kind, String hint, String placeholder, boolean redo,
                       List<String> rejected, int maxlen, List<String> extra) {
        Object ans = resolve.resolve(q, kind, null, hint, placeholder, redo,
                rejected.isEmpty() ? null : new ArrayList<String>(rejected), maxlen, extra);
        if (ans == null) {
            return null;
        }
        if (ans instanceof List) {
            List<Object> l = Json.arr(ans);
            return l.isEmpty() ? null : String.valueOf(l.get(0));
        }
        return String.valueOf(ans);
    }

    /** First ask for a number with LinkedIn's message; later (or if it repeats itself) pick one that fits the message. */
    private String numericFix(Resolver resolve, String key, String q, String err, String placeholder, List<String> rejected,
                              int maxlen) {
        int tries = numTries.containsKey(key) ? numTries.get(key) : 0;
        numTries.put(key, tries + 1);
        String value = tries == 0 ? ask(resolve, q, "numeric", err, placeholder, true, rejected, maxlen, null) : null;
        if (value == null || rejected.contains(value)) {
            value = fixNumber(err, rejected, tries >= 2);
        }
        if (rejected.contains(value)) {
            value = fixNumber(err, rejected, true);
        }
        if (rejected.contains(value)) {
            return null;
        }
        if (hooks != null) {
            hooks.remember(q, value);
        }
        return value;
    }

    private boolean fillField(Map<String, Object> f, Resolver resolve, List<Object> qa, int attempt, Set<String> handled) {
        String q = Json.str(f, "question");
        String key = q.toLowerCase(Locale.ROOT);
        String kind = Json.str(f, "kind");
        String err = Json.str(f, "error");
        boolean redo = attempt > 0 && !err.isEmpty();
        String hint = redo ? err : "";
        if (!key.isEmpty() && handled.contains(key)) {
            return false;
        }

        if (kind.equals("text") || kind.equals("numeric") || kind.equals("textarea")) {
            String current = Json.str(f, "value").trim();
            if (!current.isEmpty() && !redo) {
                return false;
            }
            if (q.isEmpty()) {
                return false;
            }
            if (kind.equals("text") && (NUMERIC_Q.matcher(q).find() || (redo && NUM_ERR.matcher(err).find()))) {
                kind = "numeric";
            }
            handled.add(key);
            int maxlen = (int) Json.num(f, "maxlen", 0);
            String placeholder = Json.str(f, "placeholder");
            boolean typeahead = Json.bool(f, "typeahead");
            List<String> rejected = redo ? noteRejected(key, current) : new ArrayList<String>();
            String value;
            if (!redo) {
                value = ask(resolve, q, kind, hint, placeholder, false, rejected, maxlen, null);
            } else if (hooks != null && hooks.manual()) {
                // You decide: LinkedIn's message is shown with suggestions, plus a number that fits the message.
                boolean numberish = kind.equals("numeric") || (NUM_ERR.matcher(err).find() && !typeahead);
                String fit = numberish ? fixNumber(err, rejected, false) : null;
                if (fit != null && rejected.contains(fit)) {
                    fit = fixNumber(err, rejected, true);
                }
                List<String> extra = null;
                if (fit != null && !rejected.contains(fit)) {
                    extra = new ArrayList<String>();
                    extra.add(fit);
                }
                value = ask(resolve, q, kind, hint, placeholder, true, rejected, maxlen, extra);
            } else if (kind.equals("numeric")) {
                value = numericFix(resolve, key, q, err, placeholder, rejected, maxlen);
            } else {
                // Free text was rejected: ask again with LinkedIn's message first. A bare "Invalid input" on a text box
                // usually means it only takes a number, so that is the next thing to try.
                boolean wantsNumber = (INVALID_ERR.matcher(err).find() || NUM_ERR.matcher(err).find()) && !typeahead;
                value = (attempt == 1 || (attempt == 2 && !wantsNumber))
                        ? ask(resolve, q, kind, hint, placeholder, true, rejected, maxlen, null) : null;
                if ((value == null || rejected.contains(value)) && wantsNumber) {
                    value = numericFix(resolve, key, q, err, placeholder, rejected, maxlen);
                }
            }
            if (value == null) {
                return false;
            }
            if (maxlen > 0 && value.length() > maxlen) {
                value = shorten(value, maxlen);
                if (hooks != null) {
                    hooks.remember(q, value);
                }
            }
            if (redo) {
                if (rejected.contains(value)) {
                    return false;
                }
                log("    Fixing '" + Util.cut(q, 60) + "': '" + (rejected.isEmpty() ? "" : rejected.get(rejected.size() - 1))
                        + "' -> '" + value + "'");
            }
            typeInto(Json.str(f, "id"), value, typeahead);
            addQa(qa, q, value);
            return true;
        }

        if (kind.equals("select")) {
            if (!Json.str(f, "selected").isEmpty() && !redo) {
                return false;
            }
            List<Object> opts = Json.arr(f.get("options"));
            List<String> labels = new ArrayList<String>();
            for (Object o : opts) {
                labels.add(Json.str(Json.map(o), "label"));
            }
            if (q.isEmpty() || labels.isEmpty()) {
                return false;
            }
            handled.add(key);
            List<String> rejected = redo ? noteRejected(key, Json.str(f, "selected")) : new ArrayList<String>();
            Object ans = resolve.resolve(q, "choice", labels, hint, "", redo,
                    rejected.isEmpty() ? null : new ArrayList<String>(rejected), 0, null);
            if (!(ans instanceof String) || !labels.contains(ans)) {
                return false;
            }
            long index = Json.num(Json.map(opts.get(labels.indexOf(ans))), "index", 0);
            if (!Json.truthy(js("select", Json.obj("sel", "[data-aa='" + Json.str(f, "id") + "']", "index", index)))) {
                throw new PageError("the list could not be changed");
            }
            addQa(qa, q, ans);
            pause(0.3, 0.7);
            return true;
        }

        List<Object> options = Json.arr(f.get("options"));
        if (options.isEmpty()) {
            return false;
        }
        boolean checked = false;
        List<String> labels = new ArrayList<String>();
        List<String> checkedLabels = new ArrayList<String>();
        for (Object o : options) {
            Map<String, Object> opt = Json.map(o);
            labels.add(Json.str(opt, "label"));
            if (Json.bool(opt, "checked")) {
                checked = true;
                checkedLabels.add(Json.str(opt, "label"));
            }
        }
        if (kind.equals("single_checkbox")) {
            if (checked || !Json.bool(f, "required")) {
                return false;
            }
            handled.add(key.isEmpty() ? labels.get(0).toLowerCase(Locale.ROOT) : key);
            clickOption(Json.map(options.get(0)));
            addQa(qa, q.isEmpty() ? labels.get(0) : q, "Checked");
            return true;
        }
        if (kind.equals("resume")) {
            if (checked) {
                return false;
            }
            clickOption(Json.map(options.get(0)));
            return true;
        }
        if ((checked && !redo) || q.isEmpty()) {
            return false;
        }
        handled.add(key);
        List<String> rejected = redo ? noteRejected(key, Util.join(", ", checkedLabels)) : new ArrayList<String>();
        Object ans = resolve.resolve(q, kind.equals("radio") ? "choice" : "multi", labels, hint, "", redo,
                rejected.isEmpty() ? null : new ArrayList<String>(rejected), 0, null);
        if (ans == null) {
            return false;
        }
        List<String> picks = ans instanceof List ? Json.strings(ans) : Json.strings(Json.list(ans));
        for (String pick : picks) {
            int idx = labels.indexOf(pick);
            if (idx >= 0) {
                Map<String, Object> opt = Json.map(options.get(idx));
                if (!Json.bool(opt, "checked")) {
                    clickOption(opt);
                    pause(0.2, 0.5);
                }
            }
        }
        addQa(qa, q, Util.join(", ", picks));
        return true;
    }

    private void typeInto(String id, String value, boolean typeahead) {
        String sel = "[data-aa='" + id + "']";
        if (!Json.truthy(js("type", Json.obj("sel", sel, "value", value, "typeahead", typeahead)))) {
            throw new PageError("the text box disappeared");
        }
        if (typeahead) {
            pickTypeahead(sel);
        }
        pause(0.3, 0.8);
    }

    private void pickTypeahead(String sel) {
        Boolean found = Util.waitUntil(new Util.Check<Boolean>() {
            @Override
            public Boolean get() {
                return jsTrue("tag_option", SEL_TYPEAHEAD_OPTION);
            }
        }, 5);
        if (found != null && found && click("[data-aa-opt='1']", 5)) {
            pause(0.3, 0.8);
            return;
        }
        jsTrue("key", Json.obj("sel", sel, "key", "ArrowDown"));
        pause(0.2, 0.4);
        jsTrue("key", Json.obj("sel", sel, "key", "Enter"));
    }

    private void clickOption(Map<String, Object> opt) {
        js("check", Json.obj("id", Json.str(opt, "id"), "hasLabel", Json.bool(opt, "hasLabel")));
    }

    static void addQa(List<Object> qa, String q, Object a) {
        for (int k = qa.size() - 1; k >= 0; k--) {
            if (q.equals(Json.str(Json.map(qa.get(k)), "q"))) {
                qa.remove(k);
            }
        }
        qa.add(Json.obj("q", q, "a", a instanceof List ? Util.join(", ", Json.strings(a)) : String.valueOf(a)));
    }

    // ------------------------------------------------------------------ numbers and text limits
    /** Pick a number that satisfies LinkedIn's message, e.g. "Enter a whole number between 1 and 99". */
    public static String fixNumber(String message, List<String> rejected, boolean bump) {
        String msg = message == null ? "" : message;
        boolean decimal = Pattern.compile("decimal", Pattern.CASE_INSENSITIVE).matcher(msg).find();
        double step = decimal ? 0.1 : 1;
        Double lo = null;
        Double hi = null;
        Matcher m = Pattern.compile("between\\s+" + NUM + "\\s+and\\s+" + NUM, Pattern.CASE_INSENSITIVE).matcher(msg);
        if (m.find()) {
            lo = Double.parseDouble(m.group(1));
            hi = Double.parseDouble(m.group(2));
        } else {
            m = Pattern.compile("(?:larger|greater|more|bigger|higher) than\\s+" + NUM, Pattern.CASE_INSENSITIVE).matcher(msg);
            if (m.find()) {
                lo = Double.parseDouble(m.group(1)) + step;
            }
            m = Pattern.compile("(?:at least|minimum(?: of| is)?|no less than)\\s+" + NUM, Pattern.CASE_INSENSITIVE).matcher(msg);
            if (m.find()) {
                lo = Double.parseDouble(m.group(1));
            }
            m = Pattern.compile("(?:less|smaller|lower|fewer) than\\s+" + NUM, Pattern.CASE_INSENSITIVE).matcher(msg);
            if (m.find()) {
                hi = Double.parseDouble(m.group(1)) - step;
            }
            m = Pattern.compile("(?:at most|maximum(?: of| is)?|no more than)\\s+" + NUM, Pattern.CASE_INSENSITIVE).matcher(msg);
            if (m.find()) {
                hi = Double.parseDouble(m.group(1));
            }
        }
        Double base = null;
        if (rejected != null) {
            for (int k = rejected.size() - 1; k >= 0; k--) {
                Matcher n = Pattern.compile(NUM).matcher(String.valueOf(rejected.get(k)));
                if (n.find()) {
                    base = Double.parseDouble(n.group(1));
                    break;
                }
            }
        }
        double value = base == null ? 1.0 : base;
        if (bump) {
            value += 1;
        }
        if (lo != null && value < lo) {
            value = Math.ceil(lo);
        }
        if (hi != null && value > hi) {
            value = hi;
        }
        value = Math.max(value, 0.0);
        return decimal ? String.format(Locale.US, "%.1f", value) : String.valueOf(Math.round(value));
    }

    /** Cut text to maxlen characters at a word boundary. */
    public static String shorten(String text, int maxlen) {
        if (maxlen <= 0 || text.length() <= maxlen) {
            return text;
        }
        String cut = text.substring(0, maxlen);
        if (cut.contains(" ")) {
            cut = cut.substring(0, cut.lastIndexOf(' '));
        }
        String trimmed = cut.replaceAll("[ ,.;:\\-]+$", "");
        return trimmed.isEmpty() ? text.substring(0, maxlen) : trimmed;
    }
}
