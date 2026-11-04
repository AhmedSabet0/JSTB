package com.autoapply.bot.core;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** The Google Play build: no auto apply, so nothing here opens or fills LinkedIn. It only keeps the types and the two
 *  plain helpers (a job's link, shortening a text) that the shared code uses. */
public final class LinkedIn {
    public static final String JOB_URL = "https://www.linkedin.com/jobs/view/%s/";

    public static class PageError extends RuntimeException {
        public PageError(String m) {
            super(m);
        }
    }

    public interface Resolver {
        Object resolve(String question, String kind, List<String> options, String hint, String placeholder, boolean noCache,
                       List<String> rejected, int maxlen, List<String> extra);
    }

    public interface Hooks {
        void remember(String question, Object value);

        void forget(String question);

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

    static final String NOT_HERE = "Auto apply is not part of this version.";

    public Page page;
    public Hooks hooks;
    public boolean dumped = false;
    public final AtomicBoolean signInSignal = new AtomicBoolean(false);

    public LinkedIn(Page page, Scripts scripts, File debugDir) {
        throw new UnsupportedOperationException(NOT_HERE);
    }

    public void setCv(File file, String displayName) {
        throw new UnsupportedOperationException(NOT_HERE);
    }

    public void ensureSignedIn() {
        throw new UnsupportedOperationException(NOT_HERE);
    }

    public String uiLanguage() {
        return "";
    }

    public List<String> search(String url) {
        throw new UnsupportedOperationException(NOT_HERE);
    }

    public Map<String, Object> openJob(String jobId) {
        throw new UnsupportedOperationException(NOT_HERE);
    }

    public Outcome apply(String jobId, Resolver resolve, boolean noSubmit) {
        throw new UnsupportedOperationException(NOT_HERE);
    }

    public void discard() {
    }

    public void dump(String name, String kind, int limit) {
    }
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
