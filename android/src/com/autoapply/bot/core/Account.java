package com.autoapply.bot.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Map;

/** Your Auto Apply account on the server: sign up / sign in, whether Pro is on, and the token that the AI requests
 *  and the reports use. The OpenRouter key stays on the server; the app only ever holds this token. */
public final class Account {
    /** The server built into the app. A server typed in the app (when none is built in) is saved in account.json. */
    public static volatile String defaultServer = "";
    /** "Samsung SM-A546E" (shown to the admin). */
    public static volatile String device = "";
    public static volatile String appVersion = "";
    /** The build ("github" or "play") and its versionCode: the server tells an app that is too old to update. */
    public static volatile String flavor = "";
    public static volatile long versionCode = 0;

    /** The server does not know this sign-in any more: sign in again. */
    public static class AuthError extends RuntimeException {
        public AuthError(String m) {
            super(m);
        }
    }

    /** The server answered with an error you can read. */
    public static class ServerError extends RuntimeException {
        public final int status;
        public final String code;

        public ServerError(int status, String message, String code) {
            super(message);
            this.status = status;
            this.code = code == null ? "" : code;
        }
    }

    /** The server could not be reached. */
    public static class NetError extends RuntimeException {
        public NetError(String m) {
            super(m);
        }
    }

    private final File file;
    private final Map<String, Object> data;

    public Account(File dir) {
        file = new File(dir, "account.json");
        Object saved = Json.tryParse(Util.readText(file));
        data = saved instanceof Map ? Json.map(saved) : Json.obj();
    }

    private void save() {
        Util.writeText(file, Json.pretty(data));
    }

    // ------------------------------------------------------------------ state
    public synchronized String server() {
        String s = Json.str(data, "server").trim();
        if (s.isEmpty()) {
            s = defaultServer == null ? "" : defaultServer.trim();
        }
        return s.replaceAll("/+$", "");
    }

    /** True when the server is built into the app (it cannot be changed then). */
    public static boolean serverBuiltIn() {
        return defaultServer != null && !defaultServer.trim().isEmpty();
    }

    /** "autoapply.example.com" -> "https://autoapply.example.com". Plain http only for this computer (tests). */
    public static String normalizeServer(String raw) {
        String s = String.valueOf(raw == null ? "" : raw).trim().replaceAll("/+$", "");
        if (s.isEmpty()) {
            return "";
        }
        if (!s.matches("(?i)^https?://.*")) {
            s = "https://" + s;
        }
        if (s.toLowerCase(Locale.ROOT).startsWith("http://") && !s.matches("(?i)^http://(127\\.0\\.0\\.1|localhost)(:\\d+)?(/.*)?$")) {
            throw new IllegalArgumentException("The server address must start with https://");
        }
        if (!s.matches("(?i)^https?://[a-z0-9.\\-]+(:\\d+)?(/[\\w./\\-]*)?$")) {
            throw new IllegalArgumentException("That does not look like a server address (for example autoapply.example.com).");
        }
        return s;
    }

    public synchronized void setServer(String raw) {
        if (serverBuiltIn()) {
            throw new IllegalArgumentException("This app's server is built in.");
        }
        String s = normalizeServer(raw);
        if (!s.equals(Json.str(data, "server"))) {
            data.put("server", s);
            data.remove("token");
            data.remove("user");
            save();
        }
    }

    public synchronized String token() {
        return Json.str(data, "token");
    }

    public synchronized boolean signedIn() {
        return !token().isEmpty();
    }

    public synchronized boolean pro() {
        return Json.bool(Json.map(data.get("user")), "pro");
    }

    /** The admin's cap on applications a day (0 = none). */
    public synchronized int maxPerDay() {
        return (int) Json.num(Json.map(data.get("user")), "max_per_day", 0);
    }

    /** The admin's cap on applications an hour (0 = none). */
    public synchronized int maxPerHour() {
        return (int) Json.num(Json.map(data.get("user")), "max_per_hour", 0);
    }

    /** True when the server says this version is too old to use. */
    public synchronized boolean updateRequired() {
        return Json.bool(Json.map(Json.map(data.get("user")).get("update")), "required");
    }

    /** What the last report learned: Pro, the caps, the admin's message and whether an update is required. */
    synchronized void learned(Map<String, Object> r) {
        Map<String, Object> u = Json.map(data.get("user"));
        String before = Json.stringify(u);
        u.put("pro", Json.bool(r, "pro"));
        for (String k : new String[]{"max_per_day", "max_per_hour", "announcement", "update"}) {
            if (r.containsKey(k)) {
                u.put(k, r.get(k));
            }
        }
        if (!Json.stringify(u).equals(before)) {
            data.put("user", u);
            save();
        }
    }

    /** For the dashboard (no network). */
    public synchronized Map<String, Object> info() {
        Map<String, Object> u = Json.map(data.get("user"));
        return Json.obj("signed_in", signedIn(), "email", Json.str(data, "email"), "name", Json.str(u, "name"),
                "pro", Json.bool(u, "pro"), "max_per_day", Json.num(u, "max_per_day", 0),
                "max_per_hour", Json.num(u, "max_per_hour", 0), "announcement", Json.str(u, "announcement"),
                "update", Json.map(u.get("update")), "server", server(),
                "server_built_in", serverBuiltIn(), "checked_at", Json.dbl(data, "checked_at", 0),
                "ai_per_day", Json.num(u, "ai_per_day", 0), "ai_used_today", Json.num(u, "ai_used_today", 0),
                "contact", Json.str(data, "contact"), "signups_open", !data.containsKey("signups_open") || Json.bool(data, "signups_open"));
    }

    public String aiUrl() {
        return server() + "/v1/ai/chat";
    }

    private synchronized void signedInAs(Map<String, Object> reply, String email) {
        data.put("token", Json.str(reply, "token"));
        data.put("email", email.trim().toLowerCase(Locale.ROOT));
        data.put("user", Json.map(reply.get("user")));
        data.put("checked_at", Util.now());
        save();
    }

    private synchronized void forget(boolean all) {
        data.remove("token");
        data.remove("user");
        if (all) {
            data.remove("email");
        }
        save();
    }

    // ------------------------------------------------------------------ requests
    public Map<String, Object> signUp(String email, String password, String name) {
        Map<String, Object> r = call("POST", "/v1/auth/signup", Json.obj("email", email, "password", password, "name", name,
                "device", device), false);
        signedInAs(r, email);
        return info();
    }

    public Map<String, Object> signIn(String email, String password) {
        Map<String, Object> r = call("POST", "/v1/auth/login", Json.obj("email", email, "password", password, "device", device), false);
        signedInAs(r, email);
        return info();
    }

    public void signOut() {
        if (signedIn()) {
            try {
                call("POST", "/v1/auth/logout", Json.obj(), true);
            } catch (RuntimeException e) {
                // signed out on this phone anyway
            }
        }
        forget(false);
    }

    /** Your account as the server has it now (Pro, limits). Throws AuthError when signed out, NetError when offline. */
    public Map<String, Object> refresh() {
        Map<String, Object> r = call("GET", "/v1/me", null, true);
        synchronized (this) {
            data.put("user", Json.map(r.get("user")));
            data.put("checked_at", Util.now());
            save();
        }
        return Json.map(r.get("user"));
    }

    /** The contact address and whether new accounts are open (shown on the sign-up screen). */
    public Map<String, Object> serverInfo() {
        Map<String, Object> r = call("GET", "/v1/config", null, false);
        synchronized (this) {
            data.put("contact", Json.str(r, "contact_email"));
            data.put("signups_open", Json.bool(r, "signups_open"));
            save();
        }
        return r;
    }

    public void delete(String password) {
        call("POST", "/v1/me/delete", Json.obj("password", password), true);
        forget(true);
    }

    public Map<String, Object> report(Map<String, Object> body) {
        return call("POST", "/v1/report", body, true);
    }

    Map<String, Object> call(String method, String path, Object body, boolean auth) {
        String base = server();
        if (base.isEmpty()) {
            throw new ServerError(0, "Type the server address first.", "no_server");
        }
        String token = token();
        if (auth && token.isEmpty()) {
            throw new AuthError("Sign in to your account first.");
        }
        int code;
        String text;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
            try {
                c.setRequestMethod(method);
                c.setConnectTimeout(20000);
                c.setReadTimeout(40000);
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("User-Agent", "AutoApply-Android/" + appVersion);
                c.setRequestProperty("X-App-Flavor", flavor);
                c.setRequestProperty("X-App-Version", String.valueOf(versionCode));
                if (auth) {
                    c.setRequestProperty("Authorization", "Bearer " + token);
                }
                if (body != null) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    OutputStream out = c.getOutputStream();
                    try {
                        out.write(Json.stringify(body).getBytes(Util.UTF8));
                    } finally {
                        out.close();
                    }
                }
                code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                text = in == null ? "" : new String(Util.readAll(in), Util.UTF8);
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            throw new NetError("Could not reach the server (" + Util.shortError(e, 80) + ")");
        }
        Object parsed = Json.tryParse(text);
        Map<String, Object> d = parsed instanceof Map ? Json.map(parsed) : Json.obj();
        if (code == 401 && auth) {
            forget(false);
            throw new AuthError(errorMessage(d, "You were signed out of your account. Sign in again."));
        }
        if (code >= 400 || (d.containsKey("ok") && !Json.bool(d, "ok"))) {
            Object err = d.get("error");
            throw new ServerError(code, errorMessage(d, "The server answered HTTP " + code + "."),
                    err instanceof Map ? Json.str(Json.map(err), "code") : "");
        }
        if (!(parsed instanceof Map)) {
            throw new ServerError(code, "The server sent an unexpected answer. Check the server address.", "bad_reply");
        }
        return d;
    }

    static String errorMessage(Map<String, Object> d, String fallback) {
        Object err = d.get("error");
        if (err instanceof Map && !Json.str(Json.map(err), "message").isEmpty()) {
            return Json.str(Json.map(err), "message");
        }
        if (err instanceof String && !((String) err).isEmpty()) {
            return (String) err;
        }
        return fallback;
    }
}
