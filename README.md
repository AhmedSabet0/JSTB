# JSTB: Auto Apply

| Folder | What |
|---|---|
| `android/` | The Android app (see `android/README.md`, Google Play: `android/PLAY_CONSOLE.md`) |
| `server/` | Accounts, AI proxy, reports and the admin page (see `server/README.md`) |

GitHub Actions:

- **Android**: every push to `android/` builds a test APK and Play bundle (Actions → the run → Artifacts).
  Push a tag such as `v2.1` to publish a release: the APK there is what the app's updater installs.
- **Server**: every push to `server/` runs the tests and deploys to the VM.

Secrets and variables (Settings → Secrets and variables → Actions):

| Name | Kind | Value |
|---|---|---|
| `KEYSTORE_BASE64` | secret | `base64 -w0 autoapply.jks` (PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("autoapply.jks"))`) |
| `KS_PASS` | secret | the keystore password |
| `SERVER_SSH_KEY` | secret | the VM's private SSH key (whole file) |
| `SERVER_URL` | variable | `https://145-241-153-177.sslip.io` |
| `SERVER_HOST` | variable | `145.241.153.177` |
| `SERVER_USER` | variable | `ubuntu` |

The keystore never goes in this repo (it is in `.gitignore`).
