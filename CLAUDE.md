# FunkoDex — Claude Project Context

## What this is

FunkoDex is an Android Kotlin/Jetpack Compose app for managing a Funko Pop collection.
Built entirely in Claude across multiple sessions. This file gives Claude the full
context needed to work on the codebase without re-explaining architecture.

**63 Kotlin files. 6 git commits. All phases complete (S through F + security + logging + OAuth).**

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
├── FunkoDexApp.kt              CrashHandler+Logger init first, HiltWorkerFactory, channels, workers
├── MainActivity.kt             Deep-link NAVIGATE_TO_ITEM handling (validated funko:: prefix), onNewIntent
├── auth/
│   ├── OAuthConfig.kt          HobbyDB + eBay endpoint constants, redirect URIs, scopes
│   ├── OAuthCallbackActivity.kt Handles funkodex://oauth/{hobbydb|ebay} redirects, PKCE token exchange
│   ├── OAuthLauncher.kt        Builds PKCE auth URL, opens Chrome Custom Tab
│   └── PkceHelper.kt           RFC 7636 code_verifier/challenge generation; OAuthSession in-memory store
├── data/
│   ├── backup/                 DriveBackupWorker, GitHubUploadWorker
│   ├── db/                     FunkoDexDatabase (all constants+indexes), FunkoMapper
│   ├── export/                 CollectionExporter, ExportScreen (ExportButton), ExportViewModel
│   ├── model/                  FunkoItem (27 fields), PriceData, PriceAlert (+upc field),
│   │                           CategoryPreference, PendingUpcScan, CatalogContribution, LogLevel
│   ├── preload/                CatalogPreloader, CatalogMapper, CatalogRefreshWorker
│   │                           (+community UPC download +vaulted refresh from HobbyDB),
│   │                           PriceAlertWorker (@HiltWorker + POST_NOTIF guard)
│   └── repository/             FunkoRepository (+updateWidget +getOwnedFiltered +savePendingUpc),
│                               CategoryPreferenceRepository, AlertRepository (+upc field),
│                               ContributionRepository, ImageBlobRepository, PhotoRepository
├── di/AppModule.kt             All @Provides — 11 providers; OkHttp writeTimeout(30s)
├── network/                    ConnectivityObserver (+POST_NOTIF guard), FunkoLookupService,
│                               PriceService (5-tier waterfall including HobbyDB Tier 4)
├── security/                   SecureKeyStore (EncryptedSharedPrefs — Channel3, HobbyDB,
│                               eBay tokens, install ID), HmacKeyStore (AndroidKeystore HMAC)
├── util/                       FunkoDexLogger (rotating file, async queue), CrashHandler,
│                               LogLevel enum (VERBOSE/DEBUG/INFO/WARN/ERROR)
└── ui/
    ├── FunkoDexNavHost.kt      5-tab + Detail + CategoryFilter + deepLinkItemId LaunchedEffect
    ├── help/                   HelpContent (all strings), HelpComponents (Banner/Card/EmptyState)
    └── screens/
        ├── SplashScreen.kt
        ├── collection/         CollectionScreen + CollectionViewModel (live category filter via combine())
        ├── detail/             DetailScreen + DetailViewModel (price 2-phase, photo, alerts+upc, AlertBellRow)
        ├── prescan/            PreScanScreen + PreScanViewModel
        ├── reports/            ReportsScreen + ReportsViewModel (empty state guard)
        ├── scanner/            ScannerScreen (ALL 10 ScanState branches + POST_NOTIF request),
        │                       ScannerViewModel (ConnectivityObserver injected, no deprecated API),
        │                       BatchScanScreen/VM, BarcodeAnalyzer
        └── settings/           SettingsScreen (Drive sign-in/out + import file picker +
                                    Diagnostics: log level picker + VERBOSE warning + share log +
                                    HobbyDB OAuth sign-in + eBay OAuth sign-in),
                                CatalogSettingsViewModel (+isHobbyDbConnected +disconnectHobbyDb),
                                CategoryFilterScreen/VM, DatabaseTransferViewModel
                                (importDatabase + Importing/ImportSuccess),
                                SettingsViewModel (+logLevel StateFlow + setLogLevel)
```

---

## Key architectural decisions

### Database — Couchbase Lite Community
No server, no sync subscription, 100% offline. Document types:
- `funko::{upc|uuid}` — personal collection items (owned/wanted)
- `catalog::{handle}` — global product catalog (Kenny Chan + Channel3 + community UPCs)
- `price::{itemId}::{source}` — cached market price snapshots
- `alert::{itemId}` — price drop alerts (includes `upc` field for better price lookups)
- `pending_upc::{upc}` — offline UPC scan queue
- `contrib::{upc}` — pending community UPC contributions
- `cat_pref::{category}` — category filter preferences

**All constants in FunkoDexDatabase.kt.** The Mapper (FunkoMapper.kt) handles conversion.

### Price waterfall — 5 tiers (PriceService.kt)
1. **Retail** — instant, from catalog data, no network
2. **eBay RSS** — real sold prices, XmlPullParser, no auth, free
3. **UPCitemdb** — generic pricing, 100/day free, UPC required
4. **Channel3** — free tier (100/day) then premium with user's API key
5. **HobbyDB** — OAuth token required; market pricing + vaulted status

### OAuth flow (auth/ package)
PKCE (RFC 7636) — no client secret in APK. Flow:
1. `OAuthLauncher.launch()` — generates verifier+challenge, opens Chrome Custom Tab
2. Provider redirects to `funkodex://oauth/{hobbydb|ebay}?code=AUTH_CODE`
3. `OAuthCallbackActivity` receives redirect, exchanges code+verifier for token
4. Token stored as `accessToken|expireAtMs|refreshToken` in `SecureKeyStore`
5. Settings UI reads `isHobbyDbConnected()`/`isEbayConnected()` from `CatalogSettingsViewModel`
6. Broadcast (`ACTION_SUCCESS`/`ACTION_FAILURE`) updates UI state immediately

### isVaulted population
`CatalogRefreshWorker.refreshVaultedStatus()` calls `hobby-db.com/api/v1/items/vaulted`
when a valid HobbyDB token is present. Updates `FIELD_IS_VAULTED` in `catalog::` docs.
Called automatically during each periodic catalog refresh.

### Security model (fully implemented)
- `allowBackup=false`, network security config, HTTPS-only
- No secrets in BuildConfig or APK — all user-entered keys in EncryptedSharedPreferences
- HMAC signing key in hardware-backed Android Keystore (cannot be extracted)
- Install ID (anonymous UUID for rate-limiting) in EncryptedSharedPreferences (SEC-A)
- Deep-link itemId validated against `funko::` prefix before navigation (SEC-B)
- VERBOSE log level shows warning about behavioural data in log files (SEC-C)
- OkHttp `writeTimeout(30s)` for community uploads on slow connections (SEC-D)
- `POST_NOTIFICATIONS` runtime check before every `nm.notify()` call (Android 13+)
- All `PendingIntent` use `FLAG_IMMUTABLE`
- `OAuthCallbackActivity` broadcasts restricted to own package

### Logging system (util/ package)
`FunkoDexLogger` — singleton, wraps Android Log, async rotating file writes.
- File: `filesDir/logs/funkodex_YYYY-MM-DD.log` (daily rotation, max 7 files, 5MB limit)
- Level: VERBOSE/DEBUG/**INFO**/WARN/ERROR — persisted in DataStore, configurable in Settings
- `CrashHandler` installed before all other init — writes crash reports to `filesDir/logs/crash_TIMESTAMP.log`
- Share log from Settings > Diagnostics > Share log file (FileProvider, `text/plain`)
- All `android.util.Log` calls replaced with `FunkoDexLogger` throughout codebase

### Workers (all @HiltWorker)
- `CatalogRefreshWorker` — scheduled on first launch (KEEP policy); periodic Kenny Chan +
  community UPC download + HobbyDB vaulted refresh (when token valid)
- `PriceAlertWorker` — daily price check + notifications (with POST_NOTIFICATIONS guard)
- `DriveBackupWorker` — daily Google Drive backup (WiFi only, POST_NOTIFICATIONS guard)
- `GitHubUploadWorker` — daily community UPC upload (opt-in, HMAC-signed)

---

## Manual steps required before first build

1. `app/src/main/assets/funko_data.json` — Kenny Chan dataset (~7MB)
2. `app/src/main/res/font/cinzel_decorative_bold.ttf` — from fonts.google.com
3. Generate launcher icons via Android Studio Image Asset Studio (SVGs in `launcher-icon/`)
4. `local.properties`: add `workerUrl=https://funkodex-contrib.YOUR.workers.dev` (Cloudflare, optional)
5. Gradle sync — all 30 deps resolve automatically
6. Optional: set up Cloudflare Worker + GitHub community repo for contribution uploads

**Channel3 API key is entered in Settings > Data Sources (not local.properties).**
**HobbyDB and eBay require one-time OAuth sign-in from Settings > Data Sources.**

---

## Help system

`HelpContent.kt` — all 28 in-app help strings as named constants.
`HelpComponents.kt` — `HelpBanner` (dismissible), `HelpCard` (persistent), `HelpEmptyState` (with CTA).
Wired into: CollectionScreen, ScannerScreen, BatchScanScreen, ReportsScreen, SettingsScreen,
CategoryFilterScreen, ExportScreen.

---

## Remaining limitations / future work

- Couchbase Lite Community is unencrypted on disk (accepted — collector data, not financial)
- eBay RSS URL blocked in some regions (graceful fallback to UPCitemdb)
- Play Integrity API verification in Cloudflare Worker not yet implemented (optional hardening)
- HobbyDB client_id in OAuthConfig.eBay is a placeholder — requires eBay developer account approval
- Wear OS companion, tablet two-pane layout, value-over-time chart not built

---

## Lessons learned (see LESSONS_LEARNED.md for full detail)

1. Split global metadata from personal data early — retrofitting is expensive
2. Never put secrets in BuildConfig — EncryptedSharedPreferences from day one
3. Keep `allowBackup=false` in the manifest from the start
4. GS1 UPC check digit: even-indexed digits ×3, odd-indexed ×1 (not the reverse)
5. CatalogMapper methods must be inside the class body, not after the closing brace
6. `@HiltWorker` + `@AssistedInject` requires `HiltWorkerFactory` in `FunkoDexApp`
7. Couchbase `inBatch {}` is required for bulk writes — individual saves are very slow
8. Inkscape SVG viewBox: `viewBox="0 0 116.99 108.79"` with layer translate — keep viewBox at 0,0
9. Phase ordering matters: security fixes before feature work, schema split before data services
10. PKCE OAuth for mobile — generates verifier+challenge client-side, no client secret in APK
11. Install ID for rate-limiting belongs in EncryptedSharedPreferences, not plain SharedPreferences
12. Validate deep-link extras against expected prefix before navigating (funko:: check)
13. `FunkoDexLogger` pattern: replace all `Log.x()` calls with a central logger that gates on level
14. Install `CrashHandler` as the absolute first thing in `Application.onCreate()`
15. Guard every `nm.notify()` with `POST_NOTIFICATIONS` permission check on Android 13+
