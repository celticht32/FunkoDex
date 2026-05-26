# FunkoDex — Claude Project Context

## What this is

FunkoDex is an Android Kotlin/Jetpack Compose app for managing a Funko Pop collection.
It was built entirely in Claude, from architecture through implementation, across multiple
sessions. This file gives Claude the context needed to work on the codebase effectively.

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
| Security | AndroidX EncryptedSharedPreferences + Android Keystore HMAC |
| Prefs | DataStore Preferences |

---

## Package structure

```
com.funkodex/
├── FunkoDexApp.kt              Application class — init, notification channels, worker scheduling
├── MainActivity.kt
├── data/
│   ├── backup/                 DriveBackupWorker, GitHubUploadWorker
│   ├── db/                     FunkoDexDatabase (constants), FunkoMapper
│   ├── export/                 CollectionExporter, ExportScreen, ExportViewModel
│   ├── model/                  All data classes (FunkoItem, PriceAlert, PendingUpcScan, etc.)
│   ├── preload/                CatalogPreloader, CatalogRefreshWorker, CatalogMapper,
│   │                           PriceAlertWorker
│   └── repository/             FunkoRepository, AlertRepository, CategoryPreferenceRepository,
│                               ContributionRepository, ImageBlobRepository, PhotoRepository
├── di/
│   └── AppModule.kt            All Hilt @Provides bindings
├── network/
│   ├── ConnectivityObserver.kt Watches for network restore, processes pending UPC queue
│   ├── FunkoLookupService.kt   Waterfall UPC/name lookup: Kenny Chan → Channel3 → UPCitemdb → Barcode Spider
│   └── PriceService.kt         4-tier price waterfall: retail → eBay RSS → UPCitemdb → Channel3
├── security/
│   ├── HmacKeyStore.kt         Hardware-backed HMAC-SHA256 for Cloudflare Worker signing
│   └── SecureKeyStore.kt       EncryptedSharedPreferences for API keys (Channel3, etc.)
└── ui/
    ├── FunkoDexNavHost.kt      5-tab nav + Detail + CategoryFilter routes
    ├── help/                   HelpContent (all strings), HelpBanner, HelpCard, HelpEmptyState
    ├── screens/
    │   ├── SplashScreen.kt
    │   ├── collection/
    │   ├── detail/             DetailScreen + DetailViewModel (price fetch, photo, alerts)
    │   ├── prescan/            Pre-purchase "do I own this?" scanner
    │   ├── reports/
    │   ├── scanner/            ScannerScreen, ScannerViewModel, BatchScanScreen, BatchScanViewModel,
    │   │                       BarcodeAnalyzer
    │   └── settings/           SettingsScreen, CatalogSettingsViewModel, CategoryFilterScreen,
    │                           CategoryFilterViewModel, DatabaseTransferViewModel, SettingsViewModel
    ├── theme/                  Theme.kt (6 themes, steel blue default)
    └── widget/                 CollectionWidget, CollectionWidgetReceiver (Glance)
```

---

## Key architectural decisions

### Database — Couchbase Lite Community
No server, no sync subscription, works 100% offline. Document types:
- `funko::{upc|uuid}` — personal collection items (owned/wanted)
- `catalog::{handle}` — global product catalog (Kenny Chan + Channel3 + community UPCs)
- `price::{itemId}::{source}` — cached market price snapshots
- `alert::{itemId}` — price drop alerts
- `pending_upc::{upc}` — offline UPC scan queue
- `contrib::{upc}` — pending community UPC contributions
- `cat_pref::{category}` — category filter preferences

**All constants live in FunkoDexDatabase.kt.** Field names for every doc type are there.
The Mapper (FunkoMapper.kt) handles FunkoItem ↔ Couchbase Document conversion.

### Schema split — global vs personal
`catalog::` docs hold product facts true for every user (name, image, retail price, UPC).
`funko::` docs hold personal data (owned/wanted, price paid, notes, condition, photo blob).
`funko::` denormalises `name` and `imageUrl` for offline display without joins.
**Never upload `funko::` docs. Only `catalog::` / `contrib::` data goes to GitHub.**

### UPC lookup waterfall (FunkoLookupService)
1. Kenny Chan local JSON (23K items, bundled as asset, offline, name/image only — no UPCs)
2. Channel3 API (free tier, user's key from SecureKeyStore)
3. UPCitemdb (100/day free, no key)
4. Barcode Spider HTML scrape (last resort before not-found sheet)

Kenny Chan is for **name search only** — it has zero UPC codes.
First UPC scan always needs network. Repeat scans hit local Couchbase instantly.

### Security — NO SECRETS IN APK
- Channel3 API key: user-entered in Settings, stored in AndroidX EncryptedSharedPreferences
- GitHub PAT: stored only in Cloudflare Worker Secrets — never in APK
- HMAC signing key for community uploads: hardware-backed Android Keystore (HmacKeyStore.kt)
- Cloudflare Worker URL: safe in BuildConfig (not a secret — public endpoint)
- `allowBackup=false` in manifest — prevents adb backup extraction
- `network_security_config.xml` — HTTPS-only for all named domains

### Community GitHub repository
When a user manually matches a not-found UPC → saves a `contrib::` doc locally.
If they opt in (Settings toggle), GitHubUploadWorker POSTs signed contributions to
the Cloudflare Worker, which validates and writes delta files to the community repo.
Weekly GitHub Actions merges deltas into `funko_upc_community.json`.
CatalogRefreshWorker downloads the community file and merges UPCs into `catalog::` docs.
**See: `FunkoDex-Community-Repo/` and `FunkoDex_Security_Architecture.docx`**

### Workers (all @HiltWorker requiring HiltWorkerFactory in FunkoDexApp)
- `CatalogRefreshWorker` — periodic Kenny Chan + community file refresh
- `PriceAlertWorker` — daily price check, fires notifications
- `DriveBackupWorker` — daily Google Drive backup (WiFi only)
- `GitHubUploadWorker` — daily community UPC upload (opt-in)

### Images — two-tier strategy
- Catalog items (not owned): Coil lazy-loads from HobbyDB CDN URL, disk-cached
- Owned items: `ImageBlobRepository` downloads `_large` image as Couchbase Blob on confirmation
- User box photos: `PhotoRepository` handles camera/gallery, EXIF correction, compression → Blob

---

## Manual steps required before first build

1. `app/src/main/assets/funko_data.json` — download from Kenny Chan GitHub or use the provided file
2. `app/src/main/res/font/cinzel_decorative_bold.ttf` — download from fonts.google.com
3. Update `cinzelDecorative()` in SplashScreen.kt to use `R.font.cinzel_decorative_bold`
4. Generate launcher icons: Android Studio → right-click res → New → Image Asset
5. `local.properties`: add `workerUrl=https://funkodex-contrib.YOUR.workers.dev` (Phase F)
6. Gradle sync — all 28 deps resolve automatically
7. Set up Cloudflare Worker and GitHub community repo — see Security Architecture doc

---

## Help system

All in-app help strings live in `HelpContent.kt` (ui/help/).
Three composables in `HelpComponents.kt`:
- `HelpBanner` — dismissible info bar for contextual hints
- `HelpCard` — persistent info card for settings explanations
- `HelpEmptyState` — full empty state with icon, title, body, optional CTA

---

## Known limitations / future work

- Couchbase Lite Community is unencrypted on disk (accepted risk — collector data, not financial)
- `isVaulted` is always false until HobbyDB OAuth is implemented (Phase D stub)
- eBay RSS URL blocked in some regions (graceful fallback to UPCitemdb)
- Play Integrity API verification in Cloudflare Worker not yet implemented (Phase F optional hardening)
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
8. SVG `viewBox="0 0 116.99 108.79"` with `translate(-46.3,-94.2)` on the `<g>` is the correct pattern for Inkscape SVGs
9. Phase ordering matters: security fixes before feature work, schema split before data services
