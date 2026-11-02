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

It prints the admin password. Open `https://DOMAIN/admin`, sign in, and add the OpenRouter key in Settings.

**2. GitHub secrets and variables** (repo > Settings > Secrets and variables > Actions):

| Name | Kind | Value |
|---|---|---|
| `KEYSTORE_BASE64` | secret | `base64 -w0 autoapply.jks` (PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("autoapply.jks"))`) |
| `KS_PASS` | secret | the keystore password |
| `SERVER_SSH_KEY` | secret | the VM's private SSH key (the whole file) |
| `SERVER_URL` | variable | `https://145-241-153-177.sslip.io` |
| `SERVER_HOST` | variable | `145.241.153.177` |
| `SERVER_USER` | variable | `ubuntu` |
| `PLAY_SERVICE_ACCOUNT_JSON` | secret | optional, after the first Play release: uploads new versions to Google Play |
| `PLAY_TRACK` | variable | optional: `internal` (default), `alpha` (closed testing), `production` |

The keystore never goes in this repo (`.gitignore` keeps `*.jks` out). Keep a backup: every update must be signed
with it.

## Deploy and update

| What | Command | What happens |
|---|---|---|
| Server | `git push` (a change in `server/`) | Actions runs the server tests, copies the files to the VM and restarts it. `.env` and the database stay. |
| App, new version | `git tag v3.1.0 && git push origin v3.1.0` | Actions builds both apps and makes a GitHub release with the APK and the Play bundle. |
| Phones with the GitHub build | nothing more | The app checks the latest release when it opens (at most every 6 hours) and offers *Download and install*; Settings > App updates checks now. |
| Phones with the Google Play build | upload the bundle (or let Actions do it with `PLAY_SERVICE_ACCOUNT_JSON`) | Google Play updates the app. |
| Everybody must update now | Admin page > Settings > App > Oldest version allowed | Older apps show only an Update screen until they update. Do this after the new version is out. |
| Tell everybody something | Admin page > Settings > App > Message | It shows on everyone's Home screen. |

Version numbers come from the tag (`v3.1.0` is version 3.1.0) and the Actions run number (the version code). The
admin page lists the versions in use.

## Actions

- **Android**: every push to `android/` builds a test APK and Play bundle (Actions > the run > Artifacts; without the
  keystore secret they are signed with a throwaway key). A `v*` tag publishes the release.
- **Server**: every push to `server/` runs the tests, then deploys (skipped, with a warning, until the deploy secrets
  are set).
