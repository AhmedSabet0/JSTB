package com.autoapply.bot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.autoapply.bot.core.Util.log;

/** Fills a job application form on a company's site that you have open in the app (you check it and submit it
 *  yourself): your name and email from your account, everything else the same way auto apply answers (your setup
 *  answers, your info, then the AI). The page part is assets/fill.js. */
public final class Filler {
    private Filler() {
    }

    static final Pattern FIRST = Pattern.compile("(?i)\\b(first|given|fore)\\s*name\\b");
    static final Pattern LAST = Pattern.compile("(?i)\\b(last|family|sur)\\s*name\\b|\\bsurname\\b");
    static final Pattern FULL = Pattern.compile("(?i)^(full\\s+|your\\s+)?name$|\\bfull name\\b|\\bname \\(as");
    static final Pattern EMAIL = Pattern.compile("(?i)e-?mail");
    static final Pattern CONSENT = Pattern.compile("(?i)agree|consent|privacy|terms|confirm|acknowledge|certify|accurate");

    /** {answers: {key: value}, filled: n, skipped: [labels]} for the fields fill.js collected. */
    public static Map<String, Object> answers(Engine engine, List<Object> fields, String title, String company) {
        Map<String, String> cfg = engine.config.load();
        Bot bot = new Bot(engine, cfg, new Bot.Options(), engine.newAi(engine.config.cvText(), engine.notesForAi(), "apply"),
                null, new ArrayList<String>(), cfg.get("LOCATION") == null ? "" : cfg.get("LOCATION"), new Session("apply"));
        bot.neverAsk = true;
        bot.setJob(title, company);
        Map<String, Object> acc = engine.account.info();
        String name = Json.str(acc, "name").trim();
        String email = Json.str(acc, "email").trim();
        String[] parts = name.split("\\s+", 2);
        Map<String, Object> out = Json.obj();
        List<Object> skipped = new ArrayList<Object>();
        boolean aiDown = false;
        String aiProblem = "";
        for (Object o : fields) {
            Map<String, Object> f = Json.map(o);
            String key = Json.str(f, "key");
            String label = Json.str(f, "label").trim();
            String kind = Json.str(f, "kind");
            List<String> options = Json.strings(f.get("options"));
            if (key.isEmpty() || label.isEmpty() || !Json.str(f, "value").trim().isEmpty()) {
                continue;  // already filled (the site or you), or nothing to go by
            }
            Object value = null;
            boolean identity = true;  // your name and email come from your account, never from the AI
            if ("checkbox".equals(kind)) {
                if (CONSENT.matcher(label).find()) {
                    value = Boolean.TRUE;
                }
            } else if ("email".equals(kind) || EMAIL.matcher(label).find()) {
                value = email.isEmpty() ? null : email;
            } else if (FIRST.matcher(label).find()) {
                value = name.isEmpty() ? null : parts[0];
            } else if (LAST.matcher(label).find()) {
                value = parts.length > 1 ? parts[1] : null;
            } else if (FULL.matcher(label).find()) {
                value = name.isEmpty() ? null : name;
            } else {
                identity = false;
            }
            if (value == null && !identity && !aiDown) {
                String botKind = "number".equals(kind) ? "numeric" : "textarea".equals(kind) ? "textarea"
                        : ("select".equals(kind) || "radio".equals(kind)) ? "choice" : "multi".equals(kind) ? "multi" : "text";
                try {
                    value = bot.resolve(label, botKind, options.isEmpty() ? null : options, "", Json.str(f, "placeholder"), false,
                            null, (int) Json.num(f, "maxlength", 0), null);
                } catch (Ai.FatalAIError e) {
                    aiDown = true;  // not Pro, AI limit...: fill what your answers cover, leave the rest to you
                    aiProblem = Util.cut(String.valueOf(e.getMessage()), 200);
                    log("  The AI is not available for this form: " + e.getMessage());
                } catch (RuntimeException e) {
                    log("  Could not answer: " + Util.cut(label, 80) + " (" + Util.shortError(e, 80) + ")");
                }
            }
            if (value == null || Facts.answerText(value).trim().isEmpty()) {
                if (Json.bool(f, "required")) {
                    skipped.add(label);
                }
                continue;
            }
            out.put(key, value);
        }
        log("Filled " + out.size() + " field(s) on " + (company == null || company.isEmpty() ? "a company site" : company)
                + (skipped.isEmpty() ? "" : "; " + skipped.size() + " required left for you"));
        return Json.obj("answers", out, "filled", out.size(), "skipped", skipped, "ai_problem", aiProblem);
    }
}
