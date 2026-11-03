# Auto Apply server

The server behind the Auto Apply Android app (version 2.0 and later; 3.0 adds the activity feed, jobs people apply to
themselves, the announcement and required updates; 3.1 keeps each user's data and syncs it with their Google Drive).

- **Accounts**: people sign up in the app with an email and a password.
- **AI proxy**: the app sends its AI requests here; the server adds the OpenRouter key and the model you choose in the
  admin page. The key never reaches a phone.
- **Admin page** (`/admin`): every user, Pro on/off (Pro is needed for auto apply and the AI's help), applications
  today / last 7 days / all time, what each user's app is doing right now, **Stop** per user and **Stop everyone**,
  each user's own limits, password reset, notes, deleting users, the AI settings (key, model, daily limits), and:
  - **Activity**: the lines each app shows its user (searches, every job and why it was sent or skipped, forms filled,
    jobs added and marked applied, errors), live, for everyone or one user;
  - **App versions**: which build and version each user has, a message shown on everyone's Home screen, and the oldest
    version allowed (older apps show only an Update screen).
- **Each user's data** (3.1): the CV file and its text, the setup answers, "Your info", settings and the job lists are
  kept in the user's account, so every phone they sign in on has the same (`/v1/me/data`, `/v1/me/cv`). The admin page
  shows how much each account holds (Details > Data in the account).
- **Google Drive** (3.1, optional): a user connects their Drive in the app; the server keeps an **Auto Apply** folder in
  it the same as their data, both ways: the CV file, **My answers** and **My jobs** (Google Sheets). What they edit there
  comes back to the app. See "Google Drive" below.
- **Google Play pages**: `/privacy` (privacy policy) and `/delete-account` (account deletion on the web).

Python (FastAPI) with SQLite, in Docker. Caddy in front gets the HTTPS certificate by itself.

## Run it on Azure (free VM)

1. **Create a virtual machine** (Azure free services): Ubuntu Server 24.04 or 22.04 LTS x64, size **B2ats v2** (or
   B1s), authentication **SSH public key** (user `azureuser`, generate a new key pair and download the `.pem`), and
   allow inbound ports **22, 80, 443**.
2. **A free domain name**: the VM's Public IP → Configuration → **DNS name label**, for example `autoapply-sabet`.
   The domain is then `autoapply-sabet.<region>.cloudapp.azure.com`.
3. **From your PC** (PowerShell, in the folder with the key and the zip):
   ```powershell
   icacls autoapply-key.pem /inheritance:r /grant:r "$($env:USERNAME):R"
   scp -i autoapply-key.pem autoapply-server.zip azureuser@VM_IP:~
   ssh -i autoapply-key.pem azureuser@VM_IP
   ```
4. **On the VM**:
   ```bash
   sudo apt-get update && sudo apt-get install -y unzip
   unzip autoapply-server.zip && cd autoapply-server
   bash setup-vm.sh      # asks for the domain, contact email and your name; prints the admin password
   ```
5. Open `https://DOMAIN/admin` → Settings → add the OpenRouter key and model → **Check the AI** → Save.

`setup-vm.sh` adds 2 GB of swap (the VM has 1 GB of RAM), installs Docker, writes `.env` with a random admin password,
and starts the server. Run it again after changing `.env` or updating the files.

## Run it on a server (AWS EC2 or Lightsail)

1. **A small Linux server.** Ubuntu 24.04, 1 GB RAM is enough to start (EC2 `t3.micro` / `t4g.small`, or a Lightsail
   instance). Give it a static IP (Elastic IP on EC2).
2. **Open ports 80 and 443** (EC2: the security group; Lightsail: Networking → IPv4 firewall). Keep 22 for SSH.
3. **A domain name.** Add an `A` record, for example `autoapply.example.com` → the server's IP.
   No domain yet? `3-120-45-67.sslip.io` (your IP with dashes) works without any DNS setup.
4. **Install Docker** on the server:
   ```bash
   curl -fsSL https://get.docker.com | sudo sh
   sudo usermod -aG docker $USER   # then log out and back in
   ```
5. **Copy this folder** to the server (`scp -r autoapply-server ubuntu@SERVER:~/`, or clone your repo), then:
   ```bash
   cd autoapply-server
   cp .env.example .env
   nano .env          # DOMAIN, ADMIN_PASSWORD (long!), CONTACT_EMAIL, OPERATOR_NAME
   docker compose up -d --build
   ```
6. Open `https://YOUR_DOMAIN/admin`, sign in with `ADMIN_PASSWORD`, go to **Settings**, paste the **OpenRouter key**,
   pick the **model**, press **Check the AI**, then **Save**.

Check it: `https://YOUR_DOMAIN/health` answers `{"ok":true}`. Logs: `docker compose logs -f app`.

### Point the app at it

The app asks for the server address on its first screen when none is built in. For Google Play, build the server into
the app so nobody has to type it:

```bash
SERVER_URL=https://autoapply.example.com FLAVOR=play ./build.sh     # in android/
```

GitHub Actions does this with the `SERVER_URL` variable (top README).

## Using the admin page

| You want to | Do this |
|---|---|
| Let someone use auto apply | Turn on their **Pro** switch. Their app can start right away (Start checks again). |
| Stop someone's run now | **Stop** on their row. It stops within 30 seconds; they can start again later. |
| Keep someone from starting | Turn **Pro** off. A running app stops within 30 seconds too. |
| Stop everybody (an emergency) | **Stop everyone** at the top. |
| Limit applications | Settings → **Applications a day, each user** (0 = each user's own setting). |
| Control AI cost | Settings → AI requests a day for Pro / not Pro. The tiles show today's AI requests and tokens. |
| Testers (Google Play closed test) | Settings → **New accounts start with Pro**, turn it off afterwards. |
| Someone forgot their password | Their row → Details → **Reset password**, tell them the new one. |
| Close sign-ups | Settings → turn off **Allow new accounts**. |
| See what an app is doing | **Activity** tab (pick the user), or their row → Details. Live, every few seconds. |
| Different limits for one user | Their row → Details → **Limits** (empty = the default). |
| Sign someone out on all phones | Their row → Details → **Sign out everywhere**. |
| Tell every user something | Settings → App → **Message on everyone's Home screen**. |
| Force an update (a fix everybody needs) | Publish the new version first, then Settings → App → **Oldest version allowed** (GitHub build and Google Play build separately; the versions in use are listed there). |

How "applied" is counted: the app reports every job it applies to (and the ones that got stuck or failed), and every job
its user marked as applied (with the channel: auto, LinkedIn or a company site, and the stage). The counts are what the
app reported since the account was created. "Today" uses the time zone in `TIMEZONE` (default
Africa/Cairo).

## Google Drive (one time, in Google Cloud)

The server signs users in to Google with OAuth and the `drive.file` permission (it sees only the files it makes, not the
rest of anyone's Drive). Make the OAuth client once:

1. [console.cloud.google.com](https://console.cloud.google.com) → New project (for example "Auto Apply").
2. APIs & Services → Library → turn on **Google Drive API** and **Google Sheets API**.
3. Google Auth Platform → **Get started**: app name "Auto Apply", your email, Audience **External**, contact email →
   Create.
4. **Branding**: app home page `https://DOMAIN/`, privacy policy `https://DOMAIN/privacy`, authorized domain: the top
   domain (for `145-241-153-177.sslip.io` it is `sslip.io`) → Save.
5. **Data access** → Add or remove scopes → `https://www.googleapis.com/auth/drive.file` → Update → Save.
6. **Clients** → Create client → **Web application** → Authorized redirect URI `https://DOMAIN/oauth/google/callback`
   (the admin page shows it with a Copy button) → Create. Copy the **Client ID** and the **Client secret** right away
   (the secret is shown once).
7. **Audience** → **Publish app** (In production). In "Testing" Google ends every user's connection after 7 days and
   only test users can connect. `drive.file` is not a sensitive scope, so publishing needs no Google review.
8. Admin page → Settings → **Google Drive** → paste the Client ID and secret → Save.

Users then connect from the app: Settings → Your data → **Connect Google Drive**. The server looks for edits in each
Drive every 5 minutes (`DRIVE_CHECK_EVERY`), writes changes from the phones within seconds, and the app's **Sync now**
does both at once. If a user removes the app's access in their Google account, their app asks them to connect again.

The tokens are encrypted with AES-GCM; the key is `DATA_KEY` in `.env`, or a random one made on first start in
`data.key` next to the database (in the `data` volume). Keep it with your database backups: without it users must
connect Google Drive again.

## Updating the server

GitHub Actions deploys every push to `server/` on `main` (top README). By hand, on the VM:

```bash
cd ~/autoapply-server
# copy the new files over (keep .env), then:
sudo docker compose up -d --build
```

The database stays in the `data` Docker volume; new columns and tables are added on start.

## Backups

The whole state is one SQLite file in the `data` volume (and `data.key`, which encrypts the Google sign-ins):

```bash
docker compose exec app python -c "import sqlite3; s=sqlite3.connect('/data/autoapply.db'); d=sqlite3.connect('/data/backup.db'); s.backup(d)"
docker compose cp app:/data/backup.db ./autoapply-backup-$(date +%F).db
```

## Security notes

- The admin page needs `ADMIN_PASSWORD` (it is off without one). Sign-in attempts are rate-limited; the session cookie
  is HttpOnly, Secure and SameSite=Strict, and lasts 12 hours.
- Passwords are stored as scrypt hashes; app sign-in tokens are stored only as SHA-256 hashes.
- The server keeps no request logs (`--no-access-log`). AI requests pass through and are not stored. Each user's own
  data (CV, answers, jobs) is stored for them, and deleted with their account.
- Google Drive: OAuth with PKCE, one-time state, the `drive.file` scope only; refresh tokens encrypted at rest. Sheets are
  written as plain text (a cell is never read as a formula).
- Only Caddy is published (ports 80/443). The app container is reachable only inside the Docker network.
- Keep `.env` private; it holds the admin password.

## API (for reference)

App, with `Authorization: Bearer <token>`:

| Method | Path | |
|---|---|---|
| POST | `/v1/auth/signup` | `{email, password, name, device}` → `{token, user}` |
| POST | `/v1/auth/login` | `{email, password, device}` → `{token, user}` |
| POST | `/v1/auth/logout` | ends this token |
| GET | `/v1/me` | `{user: {pro, max_per_day, ai_per_day, ai_used_today}}` |
| POST | `/v1/me/delete` | `{password}`: deletes the account and its data |
| POST | `/v1/ai/chat` | `{messages, max_tokens, temperature, purpose}` → OpenRouter's answer |
| POST | `/v1/report` | `{start, running, mode, message, events: [...]}` → `{stop, reason, pro, max_per_day}` |
| GET | `/v1/me/data?since=REV` | what changed since that revision: `{rev, records: [{k, id, d, rev, t}], more}` (deleted: `d` null) |
| POST | `/v1/me/data` | `{now, changes: [{k, id, d, base, t}]}` → `{rev, results: [{status: saved, same or conflict}]}` |
| GET / POST | `/v1/me/cv` | the CV file `{name, data (base64), text, sha256, rev}`; POST `{name, data, text, base, t}` |
| GET | `/v1/me/drive` | Google Drive: `{available, connected, email, folder_url, files, synced_at, error, reconnect}` |
| POST | `/v1/me/drive/link`, `/sync`, `/unlink` | the Google page to open; sync now; disconnect |
| GET | `/v1/config` | `{app_name, contact_email, signups_open}` |

A change to an item that also changed on the server since the phone's `base` revision is kept only when it is the newer
one (times are moved to the server's clock with the phone's `now`); otherwise the answer carries the server's item.

## Tests

```bash
pip install -r requirements.txt
python3 tests/test_server.py
python3 tests/test_data.py      # each user's data and Google Drive, with a fake Google (tests/fake_google.py)
```

The admin page's font is Figtree (SIL Open Font License, `app/static/FIGTREE-OFL.txt`).
