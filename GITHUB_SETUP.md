# FunkoDex — GitHub Setup Guide

This guide covers pushing both repositories to GitHub so the project is
under version control and ready for development. Read it top to bottom
before running any commands.

---

## What you're setting up

Two separate GitHub repositories:

| Repo | Contents | Visibility |
|---|---|---|
| `celtic-heart-steamworks/funkodex` | Android app source code | Private (recommended) |
| `celtic-heart-steamworks/funko-upc-community` | Community UPC database | Public |

The app repo must be private — it contains your OAuth client IDs and app
architecture. The community repo must be public so the app can download the
community UPC file anonymously without authentication.

---

## Prerequisites

```bash
# Verify git is installed
git --version          # must be 2.x

# Verify you're authenticated with GitHub
gh auth status         # if using GitHub CLI (recommended)
# or ensure your SSH key is added to GitHub
ssh -T git@github.com  # should say "Hi username!"
```

---

## Part 1 — Android App Repo (funkodex)

### 1a. Create the repo on GitHub

**Option A — GitHub CLI:**
```bash
gh repo create celtic-heart-steamworks/funkodex \
    --private \
    --description "FunkoDex — Android Funko Pop collection manager" \
    --confirm
```

**Option B — GitHub website:**
1. Go to github.com → "+" → New repository
2. Owner: `celtic-heart-steamworks`
3. Name: `funkodex`
4. Visibility: **Private**
5. **Do NOT** initialize with README, .gitignore, or licence — the repo is already initialised locally
6. Click "Create repository"

### 1b. Push the local repo

```bash
cd /path/to/FunkoDex        # wherever you extracted FunkoDex.zip

git remote add origin https://github.com/celtic-heart-steamworks/funkodex.git
# or with SSH:
git remote add origin git@github.com:celtic-heart-steamworks/funkodex.git

git push -u origin master
```

You should see all 11 commits push successfully.

### 1c. Verify

```bash
git log --oneline           # should show 11 commits
git remote -v               # should show your GitHub URL
```

### 1d. Branch protection (recommended)

In GitHub → Settings → Branches → Add branch protection rule:
- Branch name pattern: `master`
- Require pull request reviews: optional for a solo project
- **Require status checks to pass**: enable once you have CI

### 1e. Secrets (for future CI)

If you add GitHub Actions CI later, add these as repository secrets
(Settings → Secrets and variables → Actions):
- `WORKER_URL` — your Cloudflare Worker URL (optional, for community uploads)

---

## Part 2 — Community UPC Repo (funko-upc-community)

### 2a. Create the repo on GitHub

**Option A — GitHub CLI:**
```bash
gh repo create celtic-heart-steamworks/funko-upc-community \
    --public \
    --description "Community UPC database for FunkoDex — open source Funko Pop barcode data" \
    --confirm
```

**Option B — GitHub website:**
1. Owner: `celtic-heart-steamworks`
2. Name: `funko-upc-community`
3. Visibility: **Public**
4. **Do NOT** initialise with README — the repo already has one

### 2b. Initialise and push

```bash
cd /path/to/FunkoDex-Community    # extracted from FunkoDex-Community-Repo.zip

git init
git add -A
git commit -m "Initial commit — FunkoDex community UPC database v1.0"

git remote add origin https://github.com/celtic-heart-steamworks/funko-upc-community.git
git push -u origin main
```

**Note:** Use `main` for the community repo — GitHub Actions workflows reference `main`.
If git creates `master`, rename it:
```bash
git branch -M main
```

### 2c. Add the Cloudflare Worker secret

The weekly merge workflow commits to the repo using `${{ secrets.GITHUB_TOKEN }}` —
this is automatic and requires no setup.

The quarterly rebase workflow opens pull requests — also automatic.

**No additional secrets are needed for the community repo itself.**

### 2d. Verify workflows

1. Go to `github.com/celtic-heart-steamworks/funko-upc-community/actions`
2. You should see two workflows: "Weekly delta merge" and "Quarterly rebase"
3. Trigger "Weekly delta merge" manually to test (it will do nothing — no delta files yet)

### 2e. Enable GitHub Mobile notifications

- Open GitHub Mobile → your profile → Notifications
- Enable notifications for scheduled workflow runs on `funko-upc-community`
- You'll get a push notification quarterly when the rebase needs review

---

## Part 3 — Cloudflare Worker (community upload proxy)

The Cloudflare Worker sits between the Android app and the GitHub community
repo. It validates, rate-limits, and signs community UPC contributions before
writing them as delta files to GitHub.

**The Worker code is in:** `cloudflare-worker/worker.js` in this package.

### 3a. Install Wrangler

```bash
npm install -g wrangler
wrangler login
```

### 3b. Create the Worker

```bash
cd /path/to/cloudflare-worker

wrangler deploy
```

This creates a Worker at `https://funkodex-contrib.YOUR_ACCOUNT.workers.dev`.

### 3c. Add Worker Secrets

```bash
# GitHub PAT with repo write access (Settings → Developer settings → Personal access tokens)
# Scope: repo (for the community repo only)
echo "YOUR_GITHUB_PAT" | wrangler secret put GITHUB_PAT

# Random 32-byte signing secret (must match nothing — the Android HMAC key is device-generated)
# This is used for Worker-side rate limiting, not for verifying the Android HMAC
openssl rand -hex 32 | wrangler secret put WORKER_SECRET

# The GitHub repo to write delta files to
echo "celtic-heart-steamworks/funko-upc-community" | wrangler secret put GITHUB_REPO
```

### 3d. Update local.properties in the Android project

```
workerUrl=https://funkodex-contrib.YOUR_ACCOUNT.workers.dev
```

### 3e. Test

```bash
# Health check
curl https://funkodex-contrib.YOUR_ACCOUNT.workers.dev/health
# Expected: {"status":"ok","version":"1.0"}
```

---

## Part 4 — eBay Developer Registration

Before users can sign in with eBay, you need a free eBay developer account.

1. Go to `developer.ebay.com` → "Get a Developer Account"
2. Create an app → Production keys
3. Note your **RuName** (looks like `YourName-YourApp-PRD-xxxxxxxx-xxxxxxxx`)
4. In "User Tokens" → "Get a Token from eBay via Your Application":
   - Add `funkodex://oauth/ebay` as an accepted redirect URI
5. Edit `app/src/main/java/com/funkodex/auth/OAuthConfig.kt`:
   ```kotlin
   const val CLIENT_ID = "YourName-YourApp-PRD-xxxxxxxx-xxxxxxxx"  // your RuName
   ```
6. Commit and push:
   ```bash
   git add app/src/main/java/com/funkodex/auth/OAuthConfig.kt
   git commit -m "Set eBay CLIENT_ID after developer.ebay.com registration"
   git push
   ```

---

## Part 5 — First Build Checklist

After all repos are pushed and Cloudflare is deployed:

- [ ] `git clone https://github.com/celtic-heart-steamworks/funkodex.git`
- [ ] Download `funko_data.json` — see `app/src/main/assets/DOWNLOAD_FUNKO_DATA.md`
- [ ] Download Cinzel Decorative Bold — `app/src/main/res/font/cinzel_decorative_bold.ttf`
- [ ] Generate launcher icons — see `launcher-icon/ICON_INSTRUCTIONS.md`
- [ ] Android Studio → File → Sync Project with Gradle Files
- [ ] Run on device or emulator
- [ ] Settings → Data Sources → Channel3: enter your free key from trychannel3.com
- [ ] Settings → Data Sources → HobbyDB: sign in
- [ ] (Optional) Settings → Data Sources → eBay: sign in
- [ ] (Optional) Settings → Database → Connect Google Drive: sign in

---

## Quick Reference — Both Repos

```
funkodex (private)
  https://github.com/celtic-heart-steamworks/funkodex
  Local path: FunkoDex/
  Push: git push origin master

funko-upc-community (public)
  https://github.com/celtic-heart-steamworks/funko-upc-community
  Local path: FunkoDex-Community/
  Push: git push origin main
  Workflows: Sunday 02:00 UTC (merge), quarterly (rebase)

Cloudflare Worker
  https://funkodex-contrib.YOUR_ACCOUNT.workers.dev
  Secrets: GITHUB_PAT, WORKER_SECRET, GITHUB_REPO
```
