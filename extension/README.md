# JSTB Auto Apply (Chrome extension)

Applies to your matched jobs by itself, from your own Chrome, with your JSTB account:

- **LinkedIn Easy Apply**, **Wuzzuf** and **company career sites**.
- Jobs come from your account's matched list (`/v1/jobs/matched`), best score first.
- Answers come from what you set up in the JSTB app (phone, city, salary, notice period...), your saved answers,
  then the AI of your account with your CV. Your CV file is attached where a form asks for one.
- It keeps your pace (15 an hour and 40 a day unless you change them) and never goes over the limits of your account.
- Every application is recorded in your account, and the popup shows what happened.

## Install

1. Unzip `jstb-extension.zip` (or use this folder).
2. Open `chrome://extensions`, turn on **Developer mode**, click **Load unpacked** and pick the folder.
3. Click the JSTB icon, **Continue with Google**, and sign in with the account you use in the app.
4. Be signed in to LinkedIn and Wuzzuf in the same Chrome. Then press **Start**.

Chrome has to stay open while it works. Each job opens in a background tab that closes when it is done.

## Good to know

- Auto apply needs **Pro** on the account, like in the app.
- It skips what it cannot finish by itself: a sign-in wall, a CAPTCHA, a form it does not understand. Those show as
  "Needs you" or "Stuck" in the activity.
- **Test run** in Settings fills the forms and stops before Submit.
- LinkedIn's terms do not allow automated applying. Using it on your LinkedIn account is your choice and your risk.

## Test

`python3 extension/test/test_extension.py [LinkedIn mock server script]` runs the real server with a fake Google and a
fake AI, mock job pages, and Chrome with the extension loaded.
