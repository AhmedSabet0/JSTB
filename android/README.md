# Auto Apply for Android

The LinkedIn Easy Apply bot as a phone app. It searches jobs, scores each one against your CV with AI (OpenRouter),
fills in and submits Easy Apply forms at a safe pace, asks you application questions it cannot answer (with AI
suggestions), and gives you a brief at the end.

Since 2.0 the app works with the **Auto Apply server** (`autoapply-server`): you sign in with an account, the AI
requests go through the server (which holds the OpenRouter key and picks the model), and the admin turns **Pro** on
for the accounts that may run auto apply, sees what each one applied to, and can stop any run.

## First start

A setup walks you through: your account (sign up or sign in; the server address too when none is built into the
app), your CV, the answers forms ask most (phone, city, graduation year, current
and expected salary, notice period, military service, English, on-site work, relocation, driving license, work
permit, visa sponsorship; the AI fills in what it can read from the CV), the job titles, and the LinkedIn sign-in.
Then the home screen has one Start button and a ring with today's applications. Everything else is behind the gear.
Until the admin turns Pro on, the home screen says auto apply isn't active yet (Start asks the server again).

How form questions are answered (`core/Profile.java`):

- Years of experience with something in your CV: the years since you graduated. Not in your CV: you are asked (or
  the AI answers, if you chose that).
- "How many" questions get a number only. Notice period in days (weeks or months when the question asks).
- Salary per month in your currency; converted with today's rate when the question names another currency
  (rates from exchangerate-api.com, cached for a day, built-in estimates offline), x12 when it asks per year,
  and the matching range when the form offers ranges.
- Then your info, then you (ask-me mode) or the AI.

## Your info

Everything forms ask about you is kept in `facts.json`, sorted into categories: Personal, Contact, Education,
Experience, Skills, Certifications, Languages, Salary & availability, Work preferences, Work permit, Links, Other
(`core/Facts.java`, `core/Store.java`). Each fact keeps the wordings it already answered. A new question finds its
fact by its exact words, by nearly the same words (the same meaning words, no AI), or by meaning through the AI
(`Ai.matchFact`: only a fact that gives the answer counts; a similar topic does not). Answers you give on a question
card join your info; years you enter for a skill come before the years since you graduated. The Your info screen
lists the main answers and every category, with search, add, edit, move and delete. `answers.json` (question ->
answer) is still written next to it.

## How it works on the phone

- LinkedIn runs inside the app, in its own browser page with LinkedIn's desktop layout. That page sits on a private,
  invisible screen (a virtual display), so LinkedIn's pages are drawn and behave like in a browser on a screen, also
  in the background (an undrawn page never opens LinkedIn's newer Easy Apply window). When a script click does not
  open something, the bot taps it with real touch input. You sign in once on the app's "Sign in to LinkedIn"
  screen; the bot uses that session. LinkedIn does not take Easy Apply applications without a signed-in account.
- A foreground service keeps the bot running with the screen off; the status shows in a notification with Stop.
- Questions come as notifications: tap an option, a suggestion, or Reply to answer right from the notification,
  or open the app for the full question card.
- When a session ends you get the brief as a notification and on the dashboard.
- Live view shows the bot's LinkedIn page while it works (shrunk to fit).
- Share the applied jobs as CSV, the log, or debug.zip from Settings > Help.

## Your account and the server

- `core/Account.java`: sign up / sign in / sign out / delete, Pro and the admin's daily cap. Only a sign-in token is
  kept on the phone (`account.json`); no password and no AI key.
- `core/Ai.java` sends the AI requests to the server's `/v1/ai/chat` with that token and a purpose (`setup` while
  reading your CV, `check`, `apply`); the server allows `apply` only with Pro, and limits requests per day.
- `core/Reporter.java`: while auto apply runs, the app reports its status and each application every 30 seconds (and
  right after each application). The answer can say stop (the admin pressed Stop, or turned Pro off); the run then
  stops by itself with the reason on the home screen. Without contact for 10 minutes the run stops too.
- Settings > Account: check again, sign out, delete the account (also on the web at `https://DOMAIN/delete-account`),
  and the privacy policy link.

## Updates

The **Google Play** build does not update itself (Play does not allow it): Play updates it, and the update parts are
left out of that build. The **GitHub** build checks the latest GitHub release of `AhmedSabet0/JSTB` when it opens (at most every 6 hours; the source can
be changed in Settings > App updates, also to a `latest.json` link). To publish a new version:

1. Raise `versionCode` / `versionName` in AndroidManifest.xml (or `VERSION_CODE=4 VERSION_NAME=1.3 ./build.sh`).
2. On GitHub: JSTB > Releases > Draft a new release > tag `v1.3` > write what changed > attach the APK > Publish.

The repo must be public so phones can download the release. The app checks that the download is Auto Apply, newer,
and signed with the same key, then Android asks you to confirm the update. Settings, answers and the LinkedIn
sign-in stay.

## Build

Needs a JDK, the Ubuntu packages `aapt aapt2 dalvik-exchange zipalign apksigner`, the Android 14 `android.jar`
(set `ANDROID_JAR`) and, for Google Play, `bundletool.jar` (set `BUNDLETOOL`).

```bash
./build.sh                                           # out/github/AutoApply.apk  (updates itself from GitHub)
SERVER_URL=https://DOMAIN FLAVOR=play ./build.sh     # out/play/AutoApply.aab    (for Google Play)
VERSION_CODE=7 VERSION_NAME=2.1 ./build.sh           # a new version
```

`SERVER_URL` builds the server's address into the app (otherwise the first screen asks for it). Both are signed with
`keystore/autoapply.jks`; keep it private and backed up: GitHub updates must be signed with it, and it is the upload
key on Google Play. The app targets Android 16 (API 36), as Google Play requires: it draws edge to edge and pads itself
around the system bars, and it handles Back through the Android 13+ back callback.

`flavors/github` and `flavors/play` hold the parts that differ (the self-updater). Publishing on Google Play:
see `PLAY_CONSOLE.md`.

Tests: `test/run.sh` runs the engine on the JVM against LinkedIn mocks and the real server (`../autoapply-server`).

## Layout

| Path | What it is |
|---|---|
| `src/com/autoapply/bot/core/` | The engine, plain Java (also runs on a PC for tests) |
| `src/com/autoapply/bot/app/` | Android: activity, background service, hidden WebView page, notifications |
| `flavors/github/`, `flavors/play/` | What differs between the GitHub build and the Google Play build |
| `assets/li.js` | Scripts that read and fill LinkedIn's pages |
| `assets/ui/` | The app's screens (HTML/CSS/JS), pdf.js to read the CV's text, the Figtree font (OFL) |
