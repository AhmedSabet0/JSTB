# JSTB: Auto Apply

| Folder | What |
|---|---|
| `android/` | The Android app, two builds: Google Play (you apply, the app helps) and GitHub (also auto apply). See `android/README.md`; Google Play: `android/PLAY_CONSOLE.md` |
| `server/` | Accounts, the AI proxy, reports, and the admin page that shows and controls the apps (see `server/README.md`) |

## One-time setup

**1. The server on the VM** (Oracle, Azure, AWS... Ubuntu 24.04, ports 80 and 443 open):

```bash
git clone https://github.com/AhmedSabet0/JSTB.git
cp -r JSTB/server ~/autoapply-server
cd ~/autoapply-server && bash setup-vm.sh          # asks for the domain, e.g. 145-241-153-177.sslip.io
```

It prints the admin password and installs the timer that keeps the server up to date by itself. Open
`https://DOMAIN/admin`, sign in, and add the OpenRouter key in Settings.

**2. GitHub secrets and variables** (repo > Settings > Secrets and variables > Actions):

| Name | Kind | Value |
|---|---|---|
| `KEYSTORE_BASE64` | secret | `base64 -w0 autoapply.jks` (PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("autoapply.jks"))`) |
| `KS_PASS` | secret | the keystore password |
| `SERVER_SSH_KEY` | secret | optional (the VM updates itself): the VM's private SSH key, for an instant deploy |
| `SERVER_URL` | variable | optional: `https://145-241-153-177.sslip.io` is the default |
| `SERVER_HOST` | variable | optional, with `SERVER_SSH_KEY`: `145.241.153.177` |
| `SERVER_USER` | variable | optional, with `SERVER_SSH_KEY`: `ubuntu` (default) |
| `PLAY_SERVICE_ACCOUNT_JSON` | secret | optional, after the first Play release: uploads new versions to Google Play |
| `PLAY_TRACK` | variable | optional: `internal` (default), `alpha` (closed testing), `production` |

The keystore never goes in this repo (`.gitignore` keeps `*.jks` out). Keep a backup: every update must be signed
with it.

**3. Sign in with Google and Google Drive**: make a Google Cloud OAuth client (scopes `openid`, `email`, `profile` and
`drive.file`) and paste it in the admin page (Settings > Google Drive). Steps: `server/README.md`, "Sign in with Google
and Google Drive". From then on new accounts are made with **Continue with Google** (older email accounts keep working).

## Sending: you say yes first (GitHub build)

Auto apply fills each Easy Apply form up to **Submit** and stops there: a notification shows it with **Apply** and
**Skip** (or **Apply all** for several), and Home lists them under **Ready to send**. Meanwhile it goes on preparing the
next jobs, until as many wait as your pace sends in an hour; then it waits for you. Apply sends it at your pace (the
hourly and daily limits still count). Settings > How auto apply works >
**Ask me before sending each application** turns this off.

New questions: by default the AI answers what your CV, notes and answers already say, and asks you the rest (one
notification, and your answer is used next time too). Settings can switch to Ask me (every new question) or Let the
AI answer (all of them).

## Your data and Google Drive

Each user's CV, setup answers, "Your info", settings and jobs are kept in their account on the server: a new phone gets
them when they sign in, and a change on one phone reaches the others within a couple of minutes (or right away with
Sync now). With Google Drive connected (it is, for accounts made with Google; others connect it in Settings > Your
data), the server keeps an **Auto Apply** folder in their Drive the same, both ways: the CV, **My answers** and **My
jobs** (Google Sheets). Edits there come back to the app, and an account made again with the same Google account gets
everything back from that folder.

## Deploy and update: automatic

| What | How | What happens |
|---|---|---|
| Server | `git push` (a change in `server/`) | Actions runs the server tests. The VM updates itself to the tested version within 5 minutes (`server/auto-update.sh`, a timer that `setup-vm.sh` installs); `.env` and the database stay. With the `SERVER_SSH_KEY` secret Actions also deploys right away. |
| App | `git push` (a change in `android/`) | Actions builds both apps and publishes a release, version `<versionName>.<run number>` (like 3.0.12). `[skip release]` in the commit message: a test build only. |
| Phones with the GitHub build | nothing | The app looks for a release when it opens and every 30 minutes, downloads it and installs it when the screen turns off; while auto apply runs it waits for the pause between rounds, then auto apply starts again by itself. Android asks once to allow installs and confirms the first update; from Android 12 on the next ones install without asking. Settings > App updates can turn this off. |
| Phones with the Google Play build | nothing, once `PLAY_SERVICE_ACCOUNT_JSON` is set | Each release goes to Google Play (internal testing by default, `PLAY_TRACK` for another track); Play updates the app. The first upload is by hand. |
| A release right now | Actions > Android > Run workflow (empty: the next number; or type `3.1.0`) | The same release, without a push. A tag works too: `git tag v3.1.0 && git push origin v3.1.0`. |
| Everybody must update now | Admin page > Settings > App > Oldest version allowed | Older apps show only an Update screen until they update. |
| Tell everybody something | Admin page > Settings > App > Message | It shows on everyone's Home screen. |

Releases need the `KEYSTORE_BASE64` and `KS_PASS` secrets; until they are set, pushes make test builds only.

## Actions

- **Android**: every push to `android/` builds a test APK and Play bundle (Actions > the run > Artifacts; without the
  keystore secret they are signed with a throwaway key). A `v*` tag publishes the release.
- **Server**: every push to `server/` runs the tests, then deploys (skipped, with a warning, until the deploy secrets
  are set).
