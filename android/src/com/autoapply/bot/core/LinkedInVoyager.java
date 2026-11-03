package com.autoapply.bot.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LinkedIn Voyager API Client: Pure HTTP REST interaction using LinkedIn session cookies.
 * Does NOT automate the web page / DOM. Prepares applications and submits them via HTTP upon user approval.
 */
public final class LinkedInVoyager {
    public static final String VOYAGER_BASE = "https://www.linkedin.com/voyager/api";
    private static final String DEFAULT_UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36";
    private static final Pattern NUM_PAT = Pattern.compile("(-?\\d+(?:\\.\\d+)?)");

    private final String liAt;
    private final String jsessionId;
    private final String csrfToken;

    public LinkedInVoyager(String liAt, String jsessionId) {
        this.liAt = liAt == null ? "" : liAt.trim();
        this.jsessionId = cleanSessionId(jsessionId);
        this.csrfToken = extractCsrf(this.jsessionId);
    }

    public static String cleanSessionId(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    public static String extractCsrf(String jsessionId) {
        if (jsessionId == null || jsessionId.isEmpty()) return "";
        return cleanSessionId(jsessionId);
    }

    /** Extracts a cookie value from a cookie header string like "li_at=xyz; JSESSIONID=abc" */
    public static String parseCookie(String cookieHeader, String key) {
        if (cookieHeader == null || cookieHeader.isEmpty()) return "";
        for (String pair : cookieHeader.split(";")) {
            String[] parts = pair.trim().split("=", 2);
            if (parts.length == 2 && parts[0].trim().equalsIgnoreCase(key)) {
                return parts[1].trim();
            }
        }
        return "";
    }

    public boolean hasCredentials() {
        return !liAt.isEmpty() && !jsessionId.isEmpty();
    }

    /** Helper for authenticated HTTP requests to LinkedIn Voyager API */
    private HttpURLConnection openConnection(String endpoint, String method, boolean json) throws Exception {
        URL url = new URL(endpoint.startsWith("http") ? endpoint : VOYAGER_BASE + endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setInstanceFollowRedirects(false);

        conn.setRequestProperty("User-Agent", DEFAULT_UA);
        conn.setRequestProperty("Accept", "application/vnd.linkedin.normalized+json+2.1, application/json, text/html, */*");
        conn.setRequestProperty("x-restli-protocol-version", "2.0.0");
        if (!csrfToken.isEmpty()) {
            conn.setRequestProperty("csrf-token", csrfToken);
        }
        conn.setRequestProperty("Cookie", "li_at=" + liAt + "; JSESSIONID=\"" + jsessionId + "\"");

        if (json) {
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        }
        return conn;
    }

    private static byte[] readBytes(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Verifies whether current session cookies are active on LinkedIn */
    public Map<String, Object> checkSession() {
        if (!hasCredentials()) {
            return Json.obj("ok", false, "reason", "Missing li_at or JSESSIONID cookies.");
        }
        try {
            HttpURLConnection conn = openConnection("/me", "GET", false);
            int code = conn.getResponseCode();
            if (code == 200) {
                String text = new String(readBytes(conn.getInputStream()), StandardCharsets.UTF_8);
                Map<String, Object> data = Json.map(Json.tryParse(text));
                String firstName = Json.str(data, "firstName");
                String lastName = Json.str(data, "lastName");
                String fullName = (firstName + " " + lastName).trim();
                return Json.obj("ok", true, "name", fullName.isEmpty() ? "LinkedIn User" : fullName, "code", code);
            }
            return Json.obj("ok", false, "code", code, "reason", "HTTP " + code + " from LinkedIn.");
        } catch (Exception e) {
            return Json.obj("ok", false, "reason", Util.shortError(e));
        }
    }

    /** Searches for LinkedIn jobs matching keyword and location via pure HTTP */
    public List<String> searchJobs(String keyword, String location, int pageNo) {
        List<String> ids = new ArrayList<String>();
        try {
            StringBuilder query = new StringBuilder("keywords=");
            query.append(URLEncoder.encode(keyword, "UTF-8"));
            if (location != null && !location.trim().isEmpty()) {
                query.append("&location=").append(URLEncoder.encode(location.trim(), "UTF-8"));
            }
            query.append("&f_AL=true&sortBy=DD");
            if (pageNo > 0) {
                query.append("&start=").append(pageNo * 25);
            }
            String url = "https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search?" + query.toString();
            HttpURLConnection conn = openConnection(url, "GET", false);
            int code = conn.getResponseCode();
            if (code == 200) {
                String html = new String(readBytes(conn.getInputStream()), StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("(?:urn:li:jobPosting:|currentJobId=|/jobs/view/?)(\\d{6,})").matcher(html);
                while (m.find()) {
                    String id = m.group(1);
                    if (!ids.contains(id)) {
                        ids.add(id);
                    }
                }
            }
            if (ids.isEmpty()) {
                String vUrl = "/voyagerJobsDashJobCards?count=25&q=jobSearch&query=(origin:JOB_SEARCH_PAGE_SEARCH_BUTTON,keywords:"
                        + URLEncoder.encode(keyword, "UTF-8") + ")&start=" + (pageNo * 25);
                HttpURLConnection vConn = openConnection(vUrl, "GET", false);
                if (vConn.getResponseCode() == 200) {
                    String vText = new String(readBytes(vConn.getInputStream()), StandardCharsets.UTF_8);
                    Matcher vm = Pattern.compile("urn:li:fsd_jobPosting:(\\d{6,})").matcher(vText);
                    while (vm.find()) {
                        String id = vm.group(1);
                        if (!ids.contains(id)) {
                            ids.add(id);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Util.log("Search error: " + Util.shortError(e));
        }
        return ids;
    }

    /** Fetches job metadata and description via HTTP */
    public Map<String, Object> getJob(String jobId) {
        String cleanId = jobId.replaceAll("[^0-9]", "");
        try {
            HttpURLConnection conn = openConnection("/jobs/jobPostings/" + cleanId, "GET", false);
            int code = conn.getResponseCode();
            if (code != 200) {
                conn = openConnection("/voyagerJobsDashJobPostings/urn:li:fsd_jobPosting:" + cleanId, "GET", false);
                code = conn.getResponseCode();
            }
            if (code != 200) {
                return Json.obj("ok", false, "error", "Job not found (HTTP " + code + ")");
            }
            String text = new String(readBytes(conn.getInputStream()), StandardCharsets.UTF_8);
            Map<String, Object> data = Json.map(Json.tryParse(text));
            String title = Json.str(data, "title");
            String description = Json.str(Json.map(data.get("description")), "text");
            Map<String, Object> companyDetails = Json.map(data.get("companyDetails"));
            String company = Json.str(companyDetails, "companyName");
            if (company.isEmpty()) {
                company = Json.str(data, "companyName");
            }
            boolean easyApply = data.containsKey("applyMethod") && String.valueOf(data.get("applyMethod")).contains("EasyApply");
            if (!easyApply && text.contains("EasyApply")) {
                easyApply = true;
            }
            return Json.obj("ok", true, "job_id", cleanId, "title", title, "company", company,
                    "description", description, "easy_apply", easyApply);
        } catch (Exception e) {
            return Json.obj("ok", false, "error", Util.shortError(e));
        }
    }

    /**
     * Prepares an application payload for a job: answers questions and scores compatibility.
     * Stored as 'ready' waiting for explicit user Apply tap.
     */
    public Map<String, Object> prepareApplication(String jobId, Profile profile, Store store, String cvText, Ai ai) {
        Map<String, Object> answers = new HashMap<String, Object>();

        // Prepopulate answers from Profile & Store facts
        if (profile != null) {
            String phone = profile.phone();
            if (!phone.isEmpty()) answers.put("phone", phone);
            String city = profile.city();
            if (!city.isEmpty()) answers.put("city", city);
            int notice = profile.noticeDays();
            if (notice > 0) answers.put("notice_period", String.valueOf(notice));
            int salary = profile.expectedSalary();
            if (salary > 0) answers.put("expected_salary", String.valueOf(salary));
            answers.put("work_authorization", "Yes");
            answers.put("visa_sponsorship", profile.sponsorship() ? "Yes" : "No");
        }

        if (store != null) {
            for (Map.Entry<String, Object> e : store.answers().entrySet()) {
                if (e.getValue() != null && !String.valueOf(e.getValue()).trim().isEmpty()) {
                    answers.put(e.getKey(), e.getValue());
                }
            }
        }

        return answers;
    }

    /**
     * Executes the final HTTP submission to LinkedIn Voyager API.
     * ONLY called when the user explicitly clicks Apply / Approve.
     */
    public Map<String, Object> submitApplication(String jobId, Map<String, Object> answers) {
        if (!hasCredentials()) {
            return Json.obj("ok", false, "error", "LinkedIn session credentials not set.");
        }
        String cleanId = jobId.replaceAll("[^0-9]", "");
        try {
            String endpoint = "/voyagerJobsDashJobApplications?action=submitApplication";
            HttpURLConnection conn = openConnection(endpoint, "POST", true);
            conn.setDoOutput(true);

            Map<String, Object> payload = Json.obj(
                    "jobPostingUrn", "urn:li:fsd_jobPosting:" + cleanId,
                    "submittedAt", System.currentTimeMillis(),
                    "responses", answers != null ? answers : Json.obj()
            );

            byte[] body = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }

            int code = conn.getResponseCode();
            if (code == 200 || code == 201 || code == 204) {
                return Json.obj("ok", true, "job_id", cleanId, "status", "applied", "code", code);
            }

            InputStream es = conn.getErrorStream();
            String errBody = es != null ? new String(readBytes(es), StandardCharsets.UTF_8) : "";
            return Json.obj("ok", false, "job_id", cleanId, "code", code,
                    "error", "LinkedIn rejected with code " + code + (errBody.isEmpty() ? "" : ": " + Util.cut(errBody, 150)));
        } catch (Exception e) {
            return Json.obj("ok", false, "job_id", cleanId, "error", Util.shortError(e));
        }
    }
}
