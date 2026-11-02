# Publishing Auto Apply on Google Play

A checklist for Play Console, with the text to paste. `DOMAIN` below is your server's domain (see the server README).

## 0. Read this first: the risks

- **Device and Network Abuse policy.** Play does not allow apps that use a service in a way that breaks that service's
  terms. LinkedIn's User Agreement forbids bots and other automated access. So Play may reject the app, or remove it
  later, and LinkedIn may restrict the accounts of people who use it. Decide this before you spend money on it.
- **Payments.** Pro is switched on by you in the admin page, and the app takes no payments and never says how to pay.
  Keep it that way. If people will pay you for Pro, Play counts that as paid app functionality, which must go through
  Google Play Billing (Egypt has no exception). Selling outside the app and keeping the app "consumption-only" (no
  prices, no payment links, no "pay us at...") is allowed.
- **Names and logos.** Do not put "LinkedIn" (or its logo) in the app name, the icon or the feature graphic. In the
  description, mention it only to say what the app works with, and say it is not affiliated with LinkedIn.

## 1. Developer account

- One-time fee of 25 USD and identity verification.
- **Personal accounts created after 13 November 2023** must run a **closed test with at least 12 testers who stay
  opted in for 14 days in a row** before they can apply for production. (The rule is written for personal accounts;
  an organization account needs a D-U-N-S number.)

## 2. Before the first upload

1. Run the server (server README) and set the OpenRouter key and model in the admin page.
2. Build the bundle with your server built in, so nobody has to type its address:
   ```bash
   SERVER_URL=https://DOMAIN FLAVOR=play ./build.sh        # -> out/play/AutoApply.aab
   ```
   Every later upload needs a higher version: `VERSION_CODE=7 VERSION_NAME=2.1 SERVER_URL=... FLAVOR=play ./build.sh`.
3. The bundle is signed with `keystore/autoapply.jks`, which becomes your **upload key**. Keep that file private and
   backed up. When Play asks about app signing, let Google create and keep the app signing key (the default).
4. A phone that has the GitHub version must uninstall it before installing from Play (different signature).

## 3. Create the app

All apps → Create app: name (up to 30 characters, for example `Auto Apply: Job Applications`), default language
English (United States), App, Free. Accept the declarations.

## 4. Closed test (the 14 days)

1. Testing → Closed testing → Create track → Create new release → upload `AutoApply.aab`.
2. Testers: an email list (or a Google Group) with at least 12 people. Share the opt-in link with them. They must stay
   opted in for 14 days in a row.
3. In the admin page turn on **New accounts start with Pro** so testers can use auto apply. Turn it off afterwards.
4. After 14 days: Dashboard → Apply for production, and answer the questions about the test.

## 5. App content (Policy and programs → App content)

| Section | Answer |
|---|---|
| Privacy policy | `https://DOMAIN/privacy` |
| Ads | No, the app has no ads |
| App access | All or some functionality is restricted. Add a reviewer account: create it in the app, turn its **Pro** on in the admin page, and paste its email and password here, with the note below. |
| Content rating | Category: Utility, Productivity, Communication or Other. Answer No to everything. |
| Target audience | 18 and over |
| News app | No |
| Data safety | See section 6 |
| Government apps / Financial features / Health | No / none / none |
| Foreground service permissions | `FOREGROUND_SERVICE_SPECIAL_USE`, text below |

**App access note for the reviewer**

> Sign in with the account above (it has Pro). The app asks for a CV (any text-based PDF works) and a few answers, then
> asks you to sign in to LinkedIn inside the app; applying needs a LinkedIn account. Without LinkedIn you can still see
> the setup, "Your info", the settings and the account screen. Start applying runs in the background with a
> notification; Stop is on the home screen and in the notification.

**Foreground service declaration (special use)**

- What it does: *"When the user presses Start, the app runs a foreground service that searches the job listings the
  user chose and submits the job applications the user asked for, at the pace the user set (for example at most 15 an
  hour), while the phone is locked. A persistent notification shows what it is doing; the user stops it from the app
  or the notification."*
- If it is stopped or delayed: *"Applications the user started stop half way, and an open application form can be
  left unfinished. The user would have to keep the app open on screen for hours."*
- Play may ask for a short video: record the phone screen (Start → the notification → Stop), upload it to YouTube as
  unlisted, and paste the link.

## 6. Data safety answers

Check them against your final setup before you submit.

- Does your app collect or share any of the required user data types? **Yes**
- Is all of the user data collected by your app encrypted in transit? **Yes**
- Do you provide a way for users to request that their data is deleted? **Yes** → `https://DOMAIN/delete-account`
  (and Settings → Account → Delete account in the app)
- Shared with third parties: **none**. OpenRouter processes AI requests on your behalf (a service provider), which Play
  does not count as sharing.

| Data type | Collected | Optional? | Ephemeral? | Purposes |
|---|---|---|---|---|
| Personal info → Name | Yes | Optional | No | Account management |
| Personal info → Email address | Yes | Required | No | Account management |
| Personal info → User IDs | Yes (account ID) | Required | No | Account management |
| Personal info → Phone number, Address (city), Other info (work history, education, salary expectations from the CV and your answers) | Yes, sent to the AI to fill forms | Required | **Yes** | App functionality |
| App activity → App interactions (jobs applied to: title, company, result) | Yes | Required | No | App functionality |

Not collected: location, contacts, photos, files (the CV file stays on the phone; only its text goes to the AI),
device IDs, crash logs, financial info.

## 7. Store listing (Grow → Store presence → Main store listing)

**Short description** (80 characters max)

> Applies to jobs that fit your CV, at your pace. You answer the questions once.

**Full description**

> Auto Apply finds job posts that fit your CV and applies to the ones with Easy Apply, in the background, at a pace you
> set. You answer the questions forms ask most (expected salary, notice period, years of experience…) once, and it
> fills them in every form. When a form asks something new, it asks you with suggestions from your CV, or lets the AI
> answer, as you prefer.
>
> • Picks jobs that match your CV and skips the rest, with the reason
> • Fills in application forms from your CV and your saved answers
> • Asks you only what it cannot know, and remembers your answer
> • Keeps working with the screen locked, with a notification you can stop it from
> • A summary of every session: what was sent, what got stuck and why
> • "Your info": your answers by category, easy to change
>
> You need an Auto Apply account (activated by the service) and your own LinkedIn account, which you sign in to inside
> the app. Your CV and answers stay on your phone.
>
> Auto Apply is not affiliated with, endorsed by or sponsored by LinkedIn. You are responsible for following LinkedIn's
> terms when you use your account.

**Graphics** (in `play-store-kit`): `icon-512.png`, `feature-graphic-1024x500.png`, and five phone screenshots
(1080 x 1920). The screenshots come from the app's test run with made-up employers; replace them with your own when you
have real ones.

**Category**: Business (or Productivity). **Contact email**: your `CONTACT_EMAIL`. **Website**: `https://DOMAIN`.

## Policy pages used for this checklist

- Target API level: https://developer.android.com/google/play/requirements/target-sdk
- Testing requirements for new personal accounts: https://support.google.com/googleplay/android-developer/answer/14151465
- Device and Network Abuse: https://support.google.com/googleplay/android-developer/answer/9888379
- Payments: https://support.google.com/googleplay/android-developer/answer/10281818
- Account deletion: https://support.google.com/googleplay/android-developer/answer/13327111
