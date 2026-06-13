# FunkoDex — Backend Setup for Full On-Device Testing

This guide stands up every backend the app talks to, so that all functionality
in `DEVICE_TEST_PLAN.md` can be exercised on a physical phone. It is written for
a reader with **zero prior context** — a fresh Claude session or a second person
could follow it without the conversation that produced it.

All shell commands are **Windows** (`cmd`/PowerShell) syntax. Where a step needs
PowerShell specifically, it says so.

## What you need before starting

- Android Studio installed and able to build the project (this guide does not
  cover toolchain install — pinned toolchain is AGP 8.13.2, Gradle 8.13,
  Kotlin 2.0.21, JDK 17, compileSdk/targetSdk 36, minSdk 26).
- `git` 2.x and the GitHub CLI (`gh`) authenticated (`gh auth status`).
- A Node.js install (for `wrangler` and the community-repo workflows; the
  workflows themselves run Node 20 / Python 3.12 on GitHub's runners, not on
  your machine).
- Accounts you will sign into during setup: GitHub, Cloudflare, eBay developer,
  Channel3, HobbyDB, and a Google account for Drive.

## Verified facts worth knowing up front

These were checked against the repository source and live vendor docs, not
assumed:

- The app's hardcoded help text says Channel3 is "100 lookups/day free." That is
  stale. As of 2026, Channel3's free tier is **1,000 lifetime query credits,
  then $7 per 1,000 queries** (`api.trychannel3.com/v1`, `x-api-key` header).
  The key still works; only the quota expectation changes.
- The Cloudflare Worker exposes exactly two routes: `GET /health` and
  `POST /contribute`. `/contribute` requires `X-Device-ID`, `X-Timestamp`, and
  `X-Signature` headers, enforces a 5-minute replay window, and rate-limits to
  50 contributions per device per day. The HMAC signature is generated on the
  device, which is why **no shared signing secret** is configured between the
  app and the Worker.
- `workerUrl` is the **only** value that belongs in `local.properties`. Every
  other credential is entered in-app and stored encrypted via `SecureKeyStore`
  (AES-256-GCM, AndroidKeyStore).
- The community repo content ships in this project under `community-repo\`. It
  is a complete, pushable tree: both GitHub Actions workflows
  (`merge-deltas.yml` → "Weekly delta merge"; `quarterly-rebase.yml` →
  "Quarterly rebase"), `README.md`, the seed `funko_upc_community.json`, schema
  docs, and `deltas\.gitkeep`.

## Order of operations (dependency chain)

The community-upload pipeline has hard ordering: the Worker writes into the
community GitHub repo, so that repo must exist first; the Worker's
`wrangler.toml` requires a KV namespace ID before it will deploy; and the app
can't point at the Worker until the Worker has a URL.

1. Community GitHub repo
2. GitHub PAT for the Worker
3. Cloudflare Worker (KV namespace → secrets → deploy)
4. Point the app at the Worker (`local.properties`)
5. eBay developer registration
6. Channel3 key (obtained now, entered in-app later)
7. HobbyDB account (no pre-setup)
8. Build and install on the phone
9. In-app backend connections
10. Verification checklist

---

## 1. Community GitHub repo (`funko-upc-community`)

Must be **public** so the app can download the merged community UPC file
anonymously, and must exist before the Worker is deployed.

Create the repo:

```
gh repo create celticht32/funko-upc-community --public --description "Community UPC database for FunkoDex"
```

Push the contents that already exist under `community-repo\`. **Note:** this
folder lives inside the parent FunkoDex git tree, so initializing a nested git
repo here is intentional — you are publishing this subtree as its own
standalone repository.

```
cd C:\Downloads\Development\FunkoDex\community-repo
git init
git add -A
git commit -m "Initial commit — community UPC database v1.0"
git branch -M main
git remote add origin https://github.com/celticht32/funko-upc-community.git
git push -u origin main
```

Use `main` (the workflows reference it).

**Verify:** open `github.com/celticht32/funko-upc-community/actions`. You should
see two workflows — **Weekly delta merge** and **Quarterly rebase**. Trigger
"Weekly delta merge" manually (Run workflow); it will succeed doing nothing
because there are no delta files yet. That confirms the workflow and its
permissions are wired.

## 2. GitHub PAT for the Worker

The Worker authenticates to GitHub with a fine-grained token scoped to the
community repo only.

GitHub → Settings → Developer settings → Personal access tokens → Fine-grained
tokens → Generate new token.

- Repository access: **Only select repositories** → `funko-upc-community`
- Permissions: **Contents → Read and write**

Copy the token string. You will paste it in step 3c. Treat it like a password.

## 3. Cloudflare Worker (community upload proxy)

The Worker code is `cloudflare-worker\worker.js`. Its `wrangler.toml` binds a KV
namespace named `RATE_LIMIT` and reads three secrets. The repo's older
`GITHUB_SETUP.md` omits the KV-namespace step — without it, `wrangler deploy`
fails. Do these in order.

### 3a. Install and authenticate Wrangler

```
npm install -g wrangler
wrangler login
```

### 3b. Create the KV namespace and wire its ID

```
cd C:\Downloads\Development\FunkoDex\cloudflare-worker
wrangler kv namespace create RATE_LIMIT
```

Copy the `id` value from the output, then open `wrangler.toml` and replace
`REPLACE_WITH_KV_NAMESPACE_ID` with that id.

### 3c. Set the three required secrets

These names are read directly by `worker.js`:

```
echo YOUR_GITHUB_PAT| wrangler secret put GITHUB_PAT
wrangler secret put WORKER_SECRET
echo celticht32/funko-upc-community| wrangler secret put GITHUB_REPO
```

For `WORKER_SECRET`, paste any 32-byte random hex string when prompted. To
generate one in PowerShell (no OpenSSL needed):

```
powershell -Command "-join ((1..32) | ForEach-Object { '{0:x2}' -f (Get-Random -Max 256) })"
```

(`WORKER_SECRET` is used for Worker-side bookkeeping; it does **not** need to
match anything on the device — the device HMAC is self-generated.)

### 3d. Deploy and health-check

```
wrangler deploy
curl https://funkodex-contrib.YOUR_ACCOUNT.workers.dev/health
```

Expected response:

```
{"status":"ok","version":"1.0"}
```

A `200` on `/health` is your confirmation the Worker is live. Note the full URL
— you need it in step 4.

## 4. Point the app at the Worker

Edit `local.properties` in the project root and add the one line:

```
workerUrl=https://funkodex-contrib.YOUR_ACCOUNT.workers.dev
```

Without this line the app logs "WORKER_URL not configured — skipping upload" and
community uploads silently no-op (the app still runs normally). With it, the
daily `GitHubUploadWorker` posts HMAC-signed contributions to the Worker's
`POST /contribute` route.

## 5. eBay developer registration

Required before the in-app eBay sign-in (price tier 2a RSS plus the Browse API)
works. The app calls live **production** eBay endpoints
(`api.ebay.com/identity/v1/oauth2/token`,
`api.ebay.com/buy/browse/v1/...`), so use Production keys, not Sandbox.

- Go to `developer.ebay.com` and create a developer account.
- Create an app and obtain **Production** keys.
- Under User Tokens, add `funkodex://oauth/ebay` as an accepted redirect URI.
- Note your **RuName** (format `YourName-YourApp-PRD-xxxxxxxx-xxxxxxxx`).
- Edit `app\src\main\java\com\funkodex\auth\OAuthConfig.kt`:

```kotlin
const val CLIENT_ID = "YourName-YourApp-PRD-xxxxxxxx-xxxxxxxx"  // your RuName
```

- Commit and push the change to the private app repo.

## 6. Channel3 key

Sign up at `trychannel3.com/sign-up` and obtain an API key. You do **not** put
it in any config file — it is entered in-app in step 9 and stored encrypted.
(Quota note: 1,000 lifetime credits then paid, despite the app's "100/day"
text.)

## 7. HobbyDB

No pre-registration on your side. The app performs a standard OAuth sign-in
in-app (step 9) against `hobby-db.com/oauth/authorize` and `/oauth/token`. You
only need a HobbyDB account to sign in with. This is what unblocks the
catalog-photo fetch test (device test 6), since HobbyDB image URLs were the
piece blocked on the emulator.

## 8. Build and install on the phone

- Enable Developer Options and USB debugging on the device.
- Connect over USB and confirm it is recognized:

```
adb devices
```

- Install the debug build (or press Run in Android Studio):

```
gradlew installDebug
```

- Optional pre-flight: run the JVM unit suite (no device needed), which the
  test plan tracks as 72 green tests:

```
gradlew test
```

## 9. In-app backend connections (on the phone)

After install, open the app and connect each integration. These light up the
flows the device test plan exercises.

- **Settings → Data Sources → Channel3** → enter the key from step 6. Row should
  read "Connected · UPC lookup · pricing."
- **Settings → Data Sources → HobbyDB** → sign in (browser OAuth). Row reads
  "Connected · market pricing · vaulted status enabled."
- **Settings → Data Sources → eBay** → sign in (requires step 5 done first).
- **Settings → Database → Connect Google Drive** → sign in. Arms
  `DriveBackupWorker`; needed for backup/transfer and the lapsed-grant test.
- **Settings → Database → Contribute to community database** → toggle on. With
  step 4 done, this arms the daily upload worker that talks to your Worker.

## 10. Verification checklist

Backends:

- [ ] Community repo public, two workflows visible, "Weekly delta merge" ran clean
- [ ] Worker `/health` returns `{"status":"ok","version":"1.0"}`
- [ ] `local.properties` has the `workerUrl` line
- [ ] eBay `CLIENT_ID` set in `OAuthConfig.kt` and pushed
- [ ] App installed on the device (`adb devices` lists it)

In-app rows after step 9:

- [ ] Channel3 — "Connected · UPC lookup · pricing"
- [ ] HobbyDB — "Connected · market pricing · vaulted status enabled"
- [ ] eBay — connected
- [ ] Google Drive — connected
- [ ] Community contribution toggle on

Once all of the above are green, every backend referenced by
`DEVICE_TEST_PLAN.md` is live: live UPC scan, Check/PreScan, catalog photo fetch
(HobbyDB), all three price-source sign-ins, Drive backup, and the community
contribution round-trip through Cloudflare to GitHub. The only test that needs a
**second** physical device is the receive side of "Send to another phone."

## Known gaps to confirm yourself

These could not be verified without your accounts/machine and should be checked
at their steps:

- The community-repo push (step 1) assumes `community-repo\` is intact in your
  working tree; confirm the workflow files are present before pushing.
- `wrangler deploy` (step 3d) has not been run against your Cloudflare account;
  the KV-namespace ID and secrets must be correct or it will fail.
- The eBay portal UI changes over time; if the redirect-URI or key-generation
  screens differ from the description, the portal is authoritative.

---

*Maintained by Celtic Heart Steamworks. Companion to `DEVICE_TEST_PLAN.md`,
`COMPLETE_TEST_PLAN.md`, and `GITHUB_SETUP.md`.*
