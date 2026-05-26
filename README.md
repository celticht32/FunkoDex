# FunkoDex — Android Funko Pop Collection Manager

A native Android app for cataloguing your Funko Pop collection.
Scan UPC barcodes, track prices, manage your collection offline-first with Couchbase Lite,
and get notified when market prices drop.

---

## Features

- **Barcode scanner** — ML Kit camera scanner with 5-tier lookup waterfall
- **Batch scan** — scan multiple items without stopping
- **Store check** — pre-purchase scanner: "do I already own this?"
- **Collection grid** — searchable, filterable by category and franchise, sortable
- **Price tracking** — eBay sold listings, UPCitemdb, Channel3, HobbyDB (with sign-in)
- **Price drop alerts** — daily background checks, notification when target price is met
- **Reports** — cost breakdown, series completion, want list, estimated market value
- **Export** — Excel (.xlsx, 4 sheets) or CSV via email / Files / Drive
- **Home screen widget** — owned count + top market value
- **Offline-first** — 23K+ Funko records bundled locally; no network needed for common items
- **Google Drive backup** — automatic daily backup on Wi-Fi
- **Community UPC database** — opt-in anonymous contribution of scanned UPC→product matches
- **Configurable logging** — VERBOSE through ERROR, share log from Settings for support

---

## Project structure

```
FunkoDex/
├── app/src/main/java/com/funkodex/
│   ├── auth/           OAuth 2.0 PKCE flows (HobbyDB, eBay)
│   ├── data/           Models, database, repositories, workers, export
│   ├── di/             Hilt dependency injection
│   ├── network/        Lookup + price waterfall services
│   ├── security/       EncryptedSharedPreferences + Keystore HMAC
│   ├── util/           FunkoDexLogger, CrashHandler, LogLevel
│   └── ui/             Compose screens, NavHost, themes, widget
├── app/src/main/assets/          funko_data.json (download separately — see Setup)
├── app/src/main/res/font/        cinzel_decorative_bold.ttf (download separately)
├── launcher-icon/                SVG source files + generation instructions
├── gradle/libs.versions.toml
├── CLAUDE.md                     Full architecture guide for Claude sessions
└── LESSONS_LEARNED.md            Practical lessons from the build
```

---

## Prerequisites

| Tool | Version |
|------|---------|
| Android Studio | Hedgehog (2023.1.1) or newer |
| JDK | 17 |
| Android SDK | API 26 min, API 35 target |
| Device or emulator | Camera required for scanning |

---

## Setup (one-time)

### 1. Open in Android Studio
`File → Open → select the FunkoDex folder`

### 2. Add the Funko dataset (offline lookup)
Download from the Kenny Chan GitHub repo and save as:
```
app/src/main/assets/funko_data.json
```
Without this file the app still works — lookups just require network.

### 3. Add the splash screen font
Download **Cinzel Decorative Bold** from Google Fonts and save as:
```
app/src/main/res/font/cinzel_decorative_bold.ttf
```

### 4. Generate launcher icons
SVG source files are in `launcher-icon/`. Follow `launcher-icon/ICON_INSTRUCTIONS.md`
to generate all mipmap densities using Android Studio Image Asset Studio.

### 5. Gradle sync
All 30 dependencies resolve automatically from Maven Central.

### 6. (Optional) Cloudflare Worker for community contributions
See `CLAUDE.md` for the full setup guide. Leave `workerUrl=` blank in `local.properties`
to disable community uploads (the app works fine without it).

---

## API keys and OAuth

All external accounts are configured inside the running app — **nothing goes in local.properties**:

| Service | Where to configure | Notes |
|---|---|---|
| Channel3 API | Settings → Data Sources → Channel3 | Free at trychannel3.com |
| HobbyDB | Settings → Data Sources → HobbyDB → Sign in | Free account; provides market pricing + vaulted status |
| eBay | Settings → Data Sources → eBay → Sign in | Optional; higher-quality sold prices than RSS |
| Google Drive backup | Settings → Database → Connect Google Drive | Uses standard Google Sign-In |

---

## Installing on your phone

**USB (fastest during development)**
1. Phone: Settings → Developer Options → USB Debugging → On
2. Connect USB → Android Studio: Run ▶ → select device

**Sideload APK**
1. Android Studio: Build → Build APK(s)
2. APK at `app/build/outputs/apk/debug/app-debug.apk`
3. Transfer to phone and install (enable "Install unknown apps" first)

**Play Store internal testing**
1. Build → Generate Signed Bundle → AAB
2. Upload to Google Play Console → Internal Testing track

---

## Couchbase Lite document schema

Two document types per item:

**`catalog::{handle}`** — global product facts (same for all users):
```json
{
  "type": "catalog",
  "handle": "batman-1989",
  "name": "Batman (1989)",
  "franchise": "DC Comics",
  "seriesNumber": "#01",
  "upc": "889698123456",
  "category": "Pop! Movies",
  "imageUrl": "https://...",
  "retailPrice": 11.99,
  "isVaulted": false,
  "isChase": false,
  "isExclusive": true,
  "exclusiveRetailer": "Target",
  "source": "CHANNEL3"
}
```

**`funko::{upc|uuid}`** — personal data (per user):
```json
{
  "type": "funko",
  "catalogRef": "batman-1989",
  "name": "Batman (1989)",
  "franchise": "DC Comics",
  "pricePaid": 14.99,
  "isOwned": true,
  "condition": "NEAR_MINT",
  "notes": "",
  "dateAdded": "2025-09-01",
  "dateAcquired": "2025-08-31"
}
```

Indexes: `idx_owned`, `idx_upc`, `idx_franchise`, `idx_type`, `idx_category`, `idx_genre`,
`idx_alert_enabled`, `idx_alert_item`, `idx_contrib_uploaded`

---

## Price lookup waterfall

| Tier | Source | Auth | Speed |
|------|--------|------|-------|
| 1 | Retail price (catalog) | None | Instant |
| 2a | eBay sold listings RSS | None | ~400ms |
| 2b | UPCitemdb | None (100/day free) | ~500ms |
| 2c | Channel3 free | None (100/day free) | ~300ms |
| 3 | Channel3 premium | User's API key | ~300ms |
| 4 | HobbyDB | OAuth sign-in | ~400ms |

---

## Logging and diagnostics

Settings → Diagnostics:
- Select log level (VERBOSE / DEBUG / **INFO** / WARN / ERROR)
- Share today's log file via email or any installed app
- Log files: `<app-internal-storage>/logs/funkodex_YYYY-MM-DD.log`
- Crash reports: `<app-internal-storage>/logs/crash_TIMESTAMP.log` (written before app init completes)

---

## Running tests

```bash
# Unit tests (no device needed)
./gradlew test

# Instrumented tests (device/emulator required)
./gradlew connectedAndroidTest
```

---

## Future work

- HobbyDB `client_id` requires a registered eBay developer account for production
- eBay OAuth PKCE requires eBay developer program approval (sign up free at developer.ebay.com)
- Play Integrity API verification in Cloudflare Worker (optional hardening)
- Wear OS companion app
- Tablet two-pane layout
- Collection value over time chart
