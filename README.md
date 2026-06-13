# FunkoDex — Android Funko Pop Collection Manager

A native Android app for cataloguing your Funko Pop collection.
Scan UPC barcodes, track prices, manage your collection offline-first,
and get notified when market prices drop.

---

## Features

- **Barcode scanner** — ML Kit, 5-tier lookup (eBay, UPCitemdb, Channel3, HobbyDB)
- **Batch scan** — scan multiple items without stopping
- **Store check** — pre-purchase scanner: "do I already own this?"
- **Collection grid** — searchable, filterable by category and franchise, sortable
- **Price tracking** — eBay sold listings, Channel3, HobbyDB (with sign-in)
- **Price drop alerts** — daily background checks, notification at target price
- **Reports** — cost breakdown, series completion, want list, market value
- **Export** — Excel (4 sheets) or CSV via email / Files / Drive
- **Home screen widget** — owned count + top market value
- **Offline-first** — 23 K+ Funko records bundled; no network needed for common items
- **Google Drive backup** — automatic daily backup on Wi-Fi
- **Community UPC database** — opt-in anonymous contribution of UPC→product matches
- **OAuth token management** — silent refresh + weekly background keep-alive
- **Configurable logging** — VERBOSE through ERROR, share log from Settings

---

## Project structure

```
FunkoDex/
├── app/src/main/java/com/funkodex/
│   ├── auth/           OAuth 2.0 PKCE flows, TokenRefreshManager, TokenKeeperWorker
│   ├── data/           Models, database, repositories, workers, export
│   ├── di/             Hilt dependency injection (13 providers)
│   ├── network/        5-tier lookup + price waterfall services
│   ├── security/       SecureKeyStore (AES-256-GCM via AndroidKeyStore) + Keystore HMAC
│   ├── util/           FunkoDexLogger, CrashHandler, LogLevel
│   └── ui/             Compose screens, NavHost, themes, widget
├── app/src/test/java/com/funkodex/
│   ├── auth/           PkceHelperTest (RFC 7636 compliance incl. test vector)
│   ├── data/db/        FunkoMapperTest (Couchbase roundtrip)
│   ├── data/repository/CollectionStatsTest (FunkoItem arithmetic)
│   ├── network/        FunkoLookupServiceTest (record mapping)
│   ├── security/       SecureKeyStoreTokenTest (token parsing/expiry)
│   └── ui/screens/     ScannerViewModelStateTest (20 Mockk tests)
├── app/src/main/assets/    funko_data.json (bundled, 23,940 records)
├── app/src/main/res/font/  cinzel_decorative_{regular,bold,black}.ttf (bundled)
├── launcher-icon/          SVG source + generation instructions
├── gradle/libs.versions.toml
├── CLAUDE.md               Full architecture guide for future Claude sessions
├── LESSONS_LEARNED.md      Practical lessons from the build
├── FUTURE.md               25 future enhancements with implementation instructions
├── GITHUB_SETUP.md         Step-by-step GitHub + Cloudflare + eBay setup guide
├── cloudflare-worker/      Cloudflare Worker (worker.js, wrangler.toml, README)
├── community-repo/         Community UPC database (merge scripts, workflows, schema)
└── docs/                   Word documents (dependency guide, security architecture, etc.)
```

---

## Prerequisites

| Tool | Version |
|------|---------|
| Android Studio | Ladybug (2024.2.1) or newer (required for AGP 8.13.x) |
| JDK | 17 |
| Android SDK | API 26 min, API 36 target/compile |
| Device or emulator | Camera required for scanning |

---

## Setup

### 1. Clone or extract

```bash
git clone https://github.com/celticht32/FunkoDex.git
# or unzip FunkoDex.zip
cd FunkoDex
```

### 2. Open in Android Studio

`File → Open → select the FunkoDex folder` — Android Studio auto-detects the Gradle project.

### 3. Bundled assets

The Funko catalog dataset (`app/src/main/assets/funko_data.json`, 23,940
records), splash font (`cinzel_decorative_{regular,bold,black}.ttf`), and
launcher icons (all mipmap densities) are already committed — no manual
download or generation needed.

### 4. Gradle sync

`File → Sync Project with Gradle Files` — all dependencies resolve from Maven Central automatically.

### 5. Git + Cloudflare setup

For pushing to GitHub and deploying the Cloudflare Worker, follow **`GITHUB_SETUP.md`** in the project root.
It covers both repos, Cloudflare Worker deployment, eBay developer registration, and a first-build checklist.

### 6. Run

Connect a device (USB debugging on) or start an emulator. Press **Run ▶**.

---

## API keys and OAuth (all configured inside the app)

| Service | Where | Notes |
|---|---|---|
| Channel3 | Settings → Data Sources | Free at trychannel3.com |
| HobbyDB | Settings → Data Sources → Sign in | Free account; market pricing + vaulted status |
| eBay | Settings → Data Sources → Sign in | Optional; higher-quality sold prices |
| Google Drive | Settings → Database → Connect | Standard Google Sign-In |

**Nothing goes in `local.properties` except the optional Cloudflare Worker URL.**

### eBay developer registration

Before users can sign in with eBay, replace the placeholder `CLIENT_ID` in
`app/src/main/java/com/funkodex/auth/OAuthConfig.kt`:

```kotlin
const val CLIENT_ID = "FunkoDex-FunkoDex-PRD-xxxxxxxx-xxxxxxxx"
// ↑ Replace with your RuName from developer.ebay.com
```

Also register `funkodex://oauth/ebay` as an accepted redirect URI in the eBay
developer portal. The rest of the OAuth flow requires no code changes.

---

## Couchbase Lite document schema

Two document types per item:

**`catalog::{handle}`** — global product facts:
```json
{
  "type": "catalog",  "handle": "batman-1989",
  "name": "Batman (1989)",  "franchise": "DC Comics",
  "upc": "889698123456",  "category": "Pop! Movies",
  "retailPrice": 11.99,  "isVaulted": false,
  "isChase": false,  "isExclusive": true,  "exclusiveRetailer": "Target"
}
```

**`funko::{upc|uuid}`** — personal data:
```json
{
  "type": "funko",  "catalogRef": "batman-1989",
  "name": "Batman (1989)",  "franchise": "DC Comics",
  "pricePaid": 14.99,  "isOwned": true,
  "condition": "NEAR_MINT",  "notes": "",
  "dateAdded": "2025-09-01"
}
```

Indexes: `idx_owned`, `idx_upc`, `idx_franchise`, `idx_category`, `idx_genre`,
`idx_date_added`, `idx_franchise_owned`, `idx_catalog_name`, `idx_catalog_franchise`,
`idx_cat_pref`, `idx_type`, `idx_alert_enabled`, `idx_alert_item`, `idx_contrib_uploaded`

---

## Price lookup waterfall

| Tier | Source | Auth | Notes |
|------|--------|------|-------|
| 1 | Retail (catalog) | None | Instant |
| 2a | eBay completed-listings RSS | None | Real sold prices |
| 2b | UPCitemdb | None (100/day) | UPC required |
| 2c | Channel3 free | None (100/day) | — |
| 3 | Channel3 premium | User's API key | Higher limits |
| 4 | HobbyDB | OAuth sign-in | Silent token refresh via TokenRefreshManager |

---

## OAuth token lifecycle

```
Sign in (Chrome Custom Tab + PKCE)
    └─ OAuthCallbackActivity exchanges code → stores access|expireAt|refresh
            │
            ├─ TokenRefreshManager (on-demand, 5-min buffer)
            │       Called before every HobbyDB API call
            │       Refreshes silently if within 5 minutes of expiry
            │
            └─ TokenKeeperWorker (weekly, background @HiltWorker)
                    Proactively refreshes refresh tokens
                    Prevents 18-month eBay refresh token expiry
                    Retries with exponential backoff on failure
```

---

## Running tests

```bash
./gradlew test                 # 72 unit tests, no device needed (~2 seconds)
./gradlew connectedAndroidTest # instrumented tests (device/emulator required)
```

---

## Logging and diagnostics

Settings → Diagnostics:
- Log level: VERBOSE / DEBUG / **INFO** / WARN / ERROR
- Share today's log file (text/plain via FileProvider)
- Log files: `<private storage>/logs/funkodex_YYYY-MM-DD.log`
- Crash reports: `<private storage>/logs/crash_TIMESTAMP.log`

---

## Future work

- eBay `CLIENT_ID` needs developer.ebay.com registration before users can sign in
- Play Integrity API in Cloudflare Worker (optional additional hardening)
- Wear OS companion app
- Tablet two-pane layout
- Collection value over time chart
