package com.autoapply.bot.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds a newer version of the app: the latest GitHub release of a repo, or a latest.json file. */
public final class Updater {
    /** Test hook. */
    public static volatile String githubApi = "https://api.github.com";

    private Updater() {
    }

    /** "owner/repo" from "owner/repo", "https://github.com/owner/repo" or ".../owner/repo/releases". */
    public static String repoOf(String source) {
        String s = source == null ? "" : source.trim();
        s = s.replaceFirst("(?i)^https?://(www\\.)?github\\.com/", "").replaceFirst("\\.git$", "");
        Matcher m = Pattern.compile("^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(/.*)?$").matcher(s);
        return m.matches() ? m.group(1) + "/" + m.group(2) : null;
    }

    /** Compare versions like "1.10.2" and "v1.9" (numbers only). */
    public static int compare(String a, String b) {
        List<Long> x = numbers(a);
        List<Long> y = numbers(b);
        for (int k = 0; k < Math.max(x.size(), y.size()); k++) {
            long p = k < x.size() ? x.get(k) : 0;
            long q = k < y.size() ? y.get(k) : 0;
            if (p != q) {
                return p < q ? -1 : 1;
            }
        }
        return 0;
    }

    private static List<Long> numbers(String v) {
        List<Long> out = new ArrayList<Long>();
        Matcher m = Pattern.compile("\\d+").matcher(v == null ? "" : v);
        while (m.find() && out.size() < 6) {
            try {
                out.add(Long.parseLong(m.group()));
            } catch (NumberFormatException e) {
                out.add(0L);
            }
        }
        return out;
    }

    /** GET a URL: {code, body}. */
    static Object[] get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "application/vnd.github+json, application/json");
            c.setRequestProperty("User-Agent", "AutoApply-Android");
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Object[]{code, in == null ? "" : new String(Util.readAll(in), Util.UTF8)};
        } finally {
            c.disconnect();
        }
    }

    /** Check the source. Returns {ok, available, version, notes, url, size, page, message, checked_at}. */
    public static Map<String, Object> check(String source, String current) {
        Map<String, Object> out = Json.obj("ok", true, "available", false, "current", current, "checked_at", Util.now(),
                "version", "", "notes", "", "url", "", "size", 0, "page", "", "message", "");
        String src = source == null ? "" : source.trim();
        try {
            if (src.isEmpty()) {
                return fail(out, "No update source is set (Settings > App updates).");
            }
            if (src.toLowerCase(Locale.ROOT).matches("^https?://.+\\.json(\\?.*)?$")) {
                Object[] r = get(src);
                if ((Integer) r[0] != 200) {
                    return fail(out, "Could not read " + src + " (HTTP " + r[0] + ").");
                }
                Map<String, Object> d = Json.map(Json.tryParse((String) r[1]));
                out.put("version", first(d, "version", "versionName"));
                out.put("url", first(d, "url", "apk"));
                out.put("notes", first(d, "notes", "changes"));
                out.put("page", src);
            } else {
                String repo = repoOf(src);
                if (repo == null) {
                    return fail(out, "Use a GitHub repo (owner/name) or a link to a latest.json file.");
                }
                Object[] r = get(githubApi + "/repos/" + repo + "/releases/latest");
                int code = (Integer) r[0];
                if (code == 404) {
                    out.put("message", "No version has been published in " + repo + " yet (or the repo is private).");
                    return out;
                }
                if (code == 403 || code == 429) {
                    return fail(out, "GitHub is limiting requests right now. Try again in a while.");
                }
                if (code != 200) {
                    return fail(out, "GitHub answered HTTP " + code + ".");
                }
                Map<String, Object> d = Json.map(Json.tryParse((String) r[1]));
                out.put("version", Json.str(d, "tag_name").replaceFirst("^[vV]", ""));
                out.put("notes", Json.str(d, "body").trim());
                out.put("page", Json.str(d, "html_url"));
                for (Object a : Json.arr(d.get("assets"))) {
                    Map<String, Object> asset = Json.map(a);
                    if (Json.str(asset, "name").toLowerCase(Locale.ROOT).endsWith(".apk")) {
                        out.put("url", Json.str(asset, "browser_download_url"));
                        out.put("size", Json.num(asset, "size", 0));
                        break;
                    }
                }
                if (Json.str(out, "url").isEmpty()) {
                    return fail(out, "The latest release (" + Json.str(d, "tag_name") + ") has no .apk file attached.");
                }
            }
        } catch (IOException e) {
            return fail(out, "Could not check for updates: " + Util.shortError(e, 120));
        }
        if (Json.str(out, "version").isEmpty()) {
            return fail(out, "The update source does not say which version it has.");
        }
        boolean newer = compare(Json.str(out, "version"), current) > 0;
        out.put("available", newer && !Json.str(out, "url").isEmpty());
        out.put("message", newer ? "Version " + Json.str(out, "version") + " is available." : "You have the latest version.");
        return out;
    }

    private static String first(Map<String, Object> d, String a, String b) {
        String v = Json.str(d, a);
        return v.isEmpty() ? Json.str(d, b) : v;
    }

    private static Map<String, Object> fail(Map<String, Object> out, String message) {
        out.put("ok", false);
        out.put("message", message);
        return out;
    }
}
