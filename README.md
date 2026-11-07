# Auto Apply (GitHub Edition)

**Auto Apply** is an autonomous LinkedIn job search and application engine for Android. It runs in the background at a pace you choose, answers form questions using your CV and saved answers, prepares applications, and automatically updates itself from GitHub releases.

---

## Features

- **Autonomous Application Engine:** Automatically applies to matching LinkedIn Easy Apply jobs in the background.
- **Review Before Send:** Prepares applications up to the final submit step and prompts you for 1-tap confirmation, or runs fully hands-free.
- **AI-Powered Question Answering:** Uses your CV and profile facts to answer complex application questions.
- **Self-Updating:** Automatically downloads and updates to newer releases directly from GitHub.
- **Google Drive Sync:** Syncs your CV, answers, and application history directly to your Google Drive account.
- **Full Centralized Backend:** Backend server for account management, token exchange, and administrative controls.

---

## Architecture

| Component | Description |
|---|---|
| `android/` | Android application with background Foreground Service, Headless WebView, and Self-Updater. |
| `server/` | FastAPI backend for centralized account syncing, AI routing, and public job collectors. |

---

## Building from Source

### Requirements
- JDK 17+
- Android SDK (API 34+)
- `aapt`, `dalvik-exchange`, `zipalign`, `apksigner`

```bash
cd android
./build.sh          # Produces out/AutoApply.apk
```

---

## Server Deployment

```bash
git clone https://github.com/AhmedSabet0/JSTB.git
cp -r JSTB/server ~/autoapply-server
cd ~/autoapply-server && bash setup-vm.sh
```

---

## Authors & Contributors

- **Ahmed Sabet** ([@AhmedSabet0](https://github.com/AhmedSabet0)) - Lead Developer & Creator
- **Antigravity** (Google DeepMind) - Co-Author & Technical Contributor
