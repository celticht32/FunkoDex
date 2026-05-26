# FunkoDex — Claude Project Context

## What this is

FunkoDex is an Android Kotlin/Jetpack Compose app for managing a Funko Pop collection.
Built entirely in Claude across multiple sessions. This file gives Claude the full
context needed to work on the codebase without re-explaining architecture.

**65 Kotlin source files. 6 JUnit test files. 9 git commits. All features complete.**

---

## Technology stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.0, coroutines |
| UI | Jetpack Compose + Material 3 |
| Database | Couchbase Lite 3.2.0 (Community — free, no server, offline-first) |
| DI | Hilt (KSP processor) |
| Background | WorkManager + HiltWorker |
| Networking | OkHttp 4.12 + Gson |
| Camera | CameraX 1.3.4 + ML Kit Barcode |
| Images | Coil 2.7 |
| Export | Apache POI (Excel) |
| Widget | Jetpack Glance 1.1.0 |
| Security | EncryptedSharedPreferences + Android Keystore HMAC |
| OAuth | Chrome Custom Tabs + PKCE (HobbyDB, eBay) |
| Browser | androidx.browser 1.8.0 (Chrome Custom Tabs for OAuth) |
| Logging | FunkoDexLogger (rotating file, configurable level, crash handler) |
| Prefs | DataStore Preferences |

---

## Package structure

```
com.funkodex/
├── FunkoDexApp.kt              CrashHandler+Logger init first, HiltWorkerFactory,
│                               channels, 5 workers scheduled
├── MainActivity.kt             Deep-link NAVIGATE_TO_ITEM handling (funko:: validated),
│                               onNewIntent
├── auth/
│   ├── OAuthConfig.kt          HobbyDB + eBay endpoint constants, redirect URIs, scopes
│   ├── OAuthCallbackActivity.kt Handles funkodex://oauth/{hobbydb|ebay} redirects,
│                               PKCE token exchange, broadcasts ACTION_SUCCESS/FAILURE
│   ├── OAuthLauncher.kt        Builds PKCE auth URL, opens Chrome Custom Tab
│   ├── PkceHelper.kt           RFC 7636 code_verifier/challenge; OAuthSession in-memory store
│   ├── TokenRefreshManager.kt  Silent token refresh with per-provider Mutex; 5-min buffer;
│   │                           handles token rotation; hasHobbyDbRefreshToken/hasEbayRefreshToken
│   └── TokenKeeperWorker.kt    @HiltWorker — weekly proactive token refresh to keep
│                               refresh tokens alive (eBay 18-month expiry, HobbyDB similar)
├── data/
│   ├── backup/                 DriveBackupWorker, GitHubUploadWorker
│   ├── db/                     FunkoDexDatabase (all constants + 9 indexes), FunkoMapper
│   ├── export/                 CollectionExporter, ExportScreen (ExportButton), ExportViewModel
│   ├── model/                  FunkoItem (27 fields), PriceData, PriceAlert (+upc field),
│   │                           CategoryPreference, PendingUpcScan, CatalogContribution
│   ├── preload/                CatalogPreloader, CatalogMapper, CatalogRefreshWorker
│   │                           (Kenny Chan + community UPC + HobbyDB vaulted refresh),
│   │                           PriceAlertWorker (@HiltWorker + POST_NOTIF guard)
│   └── repository/             FunkoRepository (+updateWidget +getOwnedFiltered),
│                               CategoryPreferenceRepository, AlertRepository (+upc field),
│                               ContributionRepository, ImageBlobRepository, PhotoRepository
├── di/AppModule.kt             All @Provides — 12 providers; OkHttp writeTimeout(30s)
├── network/                    ConnectivityObserver (+POST_NOTIF guard), FunkoLookupService,
│                               PriceService (5-tier: retail → eBay RSS → UPCitemdb →
│                               Channel3 free/premium → HobbyDB via TokenRefreshManager)
├── security/                   SecureKeyStore (EncryptedSharedPrefs — Channel3, HobbyDB,
│                               eBay tokens, install ID), HmacKeyStore (Keystore HMAC)
├── util/                       FunkoDexLogger (rotating file, async queue),
│                               CrashHandler, LogLevel enum
└── ui/
    ├── FunkoDexNavHost.kt      5-tab + Detail + CategoryFilter + deepLinkItemId
    ├── help/                   HelpContent (28 strings), HelpBanner, HelpCard, HelpEmptyState
    └── screens/
        ├── SplashScreen.kt
        ├── collection/         CollectionScreen + CollectionViewModel
        │                       (live category filter via combine())
        ├── detail/             DetailScreen + DetailViewModel (2-phase price, photo, alerts)
        ├── prescan/            PreScanScreen + PreScanViewModel
        ├── reports/            ReportsScreen + ReportsViewModel (empty-state guard)
        ├── scanner/            ScannerScreen (all 10 ScanState branches + POST_NOTIF),
        │                       ScannerViewModel (ConnectivityObserver, no deprecated API),
        │                       BatchScanScreen/VM, BarcodeAnalyzer
        └── settings/           SettingsScreen (Drive sign-in/out, import file picker,
                                    Diagnostics: log level + VERBOSE warning + share log,
                                    HobbyDB OAuth sign-in, eBay OAuth sign-in),
                                CatalogSettingsViewModel (+OAuth helpers),
                                CategoryFilterScreen/VM, DatabaseTransferViewModel,
                                SettingsViewModel (+logLevel StateFlow + setLogLevel)
```

---

## Key architectural decisions

### Database — Couchbase Lite Community
No server, no sync subscription, 100% offline. Document types:
- `funko::{upc|uuid}` — personal collection items
- `catalog::{handle}` — global product catalog (Kenny Chan + Channel3 + community UPCs)
- `price::{itemId}::{source}` — cached market price snapshots
- `alert::{itemId}` — price drop alerts (includes `upc` field)
- `pending_upc::{upc}` — offline UPC scan queue
- `contrib::{upc}` — pending community UPC contributions
- `cat_pref::{category}` — category filter preferences

All constants in `FunkoDexDatabase.kt`. The Mapper handles `FunkoItem` ↔ Document conversion.

### Price waterfall — 5 tiers (`PriceService.kt`)
1. **Retail** — instant, from catalog data
2. **eBay RSS** — real sold prices, XmlPullParser, no auth
3. **UPCitemdb** — 100/day free, UPC required
4. **Channel3** — free tier (100/day) then premium with user's API key
5. **HobbyDB** — `TokenRefreshManager.getValidHobbyDbToken()`, silent refresh

### OAuth flow (`auth/` package)
PKCE (RFC 7636) — no client secret in APK. Code verifier stored in `OAuthSession` (memory only).
`OAuthCallbackActivity` uses `lifecycleScope` (no leak), `finish()` on Main thread.
Broadcasts restricted to own package via `setPackage(packageName)`.

### Token refresh strategy
- **On-demand** (`TokenRefreshManager`): called by `PriceService` and `CatalogRefreshWorker`
  before every API call. 5-minute buffer. Per-provider `Mutex` prevents refresh storms.
- **Proactive** (`TokenKeeperWorker`): weekly `@HiltWorker`. Keeps refresh tokens alive
  even when the app is opened infrequently. eBay refresh tokens last 18 months;
  without weekly use the refresh token itself can expire. Uses KEEP policy.

### Security model (all implemented)
- `allowBackup=false`, HTTPS-only, 10-domain allowlist
- No secrets in APK — all keys in `EncryptedSharedPreferences`
- HMAC key in hardware-backed Android Keystore
- Install ID (anon UUID) in `EncryptedSharedPreferences` (SEC-A fix)
- Deep-link `itemId` validated against `funko::` prefix (SEC-B fix)
- VERBOSE log shows data-privacy warning (SEC-C fix)
- OkHttp `writeTimeout(30s)` (SEC-D fix)
- `POST_NOTIFICATIONS` runtime check before every `nm.notify()` (Android 13+)
- All `PendingIntent` use `FLAG_IMMUTABLE`
- OAuth broadcasts restricted to own package

### Logging system (`util/` package)
- `FunkoDexLogger` — async rotating file (`filesDir/logs/funkodex_YYYY-MM-DD.log`),
  7-day retention, 5MB rotation, level gate
- `CrashHandler` — `Thread.UncaughtExceptionHandler` installed before all other init;
  writes to `filesDir/logs/crash_TIMESTAMP.log`
- Level: VERBOSE/DEBUG/**INFO**/WARN/ERROR — persisted in DataStore, configurable in Settings
- Share from Settings > Diagnostics > Share log file

### Workers (all scheduled in `FunkoDexApp.onCreate()`)
| Worker | Type | Frequency | Notes |
|---|---|---|---|
| `CatalogRefreshWorker` | Plain CoroutineWorker | 7 days (KEEP) | Kenny Chan + community UPC + HobbyDB vaulted |
| `PriceAlertWorker` | @HiltWorker | Daily (KEEP) | POST_NOTIFICATIONS guard |
| `DriveBackupWorker` | @HiltWorker | Daily (UPDATE) | WiFi only, POST_NOTIFICATIONS guard |
| `GitHubUploadWorker` | @HiltWorker | Daily, opt-in | HMAC-signed community contributions |
| `TokenKeeperWorker` | @HiltWorker | Weekly (KEEP) | Proactive OAuth token refresh |

---

## Manual steps required before first build

1. `app/src/main/assets/funko_data.json` — Kenny Chan dataset; see `DOWNLOAD_FUNKO_DATA.md`
2. `app/src/main/res/font/cinzel_decorative_bold.ttf` — from fonts.google.com
3. Generate launcher icons via Android Studio Image Asset Studio; see `launcher-icon/ICON_INSTRUCTIONS.md`
4. `local.properties`: add `workerUrl=https://funkodex-contrib.YOUR.workers.dev` (optional)
5. Gradle sync — all 30 deps resolve automatically

**Channel3 API key:** entered in Settings > Data Sources (not `local.properties`).
**HobbyDB / eBay:** one-time OAuth sign-in from Settings > Data Sources.
**eBay `CLIENT_ID`:** replace placeholder in `OAuthConfig.eBay.CLIENT_ID` after
registering at `developer.ebay.com`.

---

## Running tests

```bash
./gradlew test                     # 6 unit test files, no device needed
./gradlew connectedAndroidTest     # instrumented (device/emulator required)
```

Test files:
- `data/db/FunkoMapperTest.kt` — Couchbase document roundtrip (9 tests)
- `data/repository/CollectionStatsTest.kt` — FunkoItem defaults + arithmetic (11 tests)
- `network/FunkoLookupServiceTest.kt` — record mapping (10 tests)
- `ui/screens/scanner/ScannerViewModelStateTest.kt` — state machine skeleton
- `auth/PkceHelperTest.kt` — RFC 7636 crypto incl. official test vector (9 tests)
- `security/SecureKeyStoreTokenTest.kt` — token parsing/expiry logic (17 tests)

---

## Remaining limitations / future work

- Couchbase Lite Community is unencrypted on disk (accepted — collector data, not financial)
- eBay RSS URL blocked in some regions (falls back to UPCitemdb gracefully)
- Play Integrity API in Cloudflare Worker not yet implemented (optional hardening)
- eBay `CLIENT_ID` requires developer.ebay.com registration
- Wear OS companion, tablet two-pane layout, value-over-time chart not built
- `ScannerViewModelStateTest` is a skeleton — needs Mockk + real assertions

---

## Lessons learned (see LESSONS_LEARNED.md)

1–9: Architecture, security, data, SVG, dev workflow, dependency management
10–15: OAuth PKCE, install ID storage, deep-link validation, central logger, CrashHandler,
        POST_NOTIFICATIONS guards
