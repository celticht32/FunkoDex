# FunkoDex — Future Functionality Roadmap

This document describes planned and suggested enhancements for FunkoDex.
Each item includes enough detail to implement it in a future Claude session —
paste this file (or the relevant section) into a new session along with `CLAUDE.md`.

Items are grouped by theme and ordered by rough implementation priority within each group.

---

## Community Catalog Distribution (major initiative — design complete, not built)

A full architecture & design document was produced in Session 11:
**`FunkoDex_Catalog_Distribution_Architecture_v1.2.docx`** (in repo root / docs).
Design only; nothing implemented. Summary:

- **Golden master base.** Bundle the enriched catalog + maintainer's accumulated
  data as `funko_data_golden.json`, loaded on first install. Deprecates the
  Enriched Catalog Import feature. NOTE: the current `CatalogPreloader` reads only
  `handle/title/imageName/series` (old thin format) — bundling enriched data
  requires teaching the preloader the richer schema (upc, funkoNumber, etc.).
- **Core/user field split.** Every record splits into core (shared, syncable —
  name, franchise, category, upc, imageUrl, exclusive flags, etc.) and user
  (private, never synced — pricePaid, marketAvg, notes, condition, photos, owned).
- **Community hub.** GitHub repo `funko-upc-community`; a merge/moderation process
  emits dated update packets (core data only). Builds on the existing
  `CatalogContribution` (`source = "USER_MANUAL"`) already written by manual-add.
- **Client update cycle.** Monthly scan pulls packets newer than last imported.
- **Per-field conflict resolution.** Empty → fill; never-update flag → keep
  (resettable in Settings); else field policy: "always update" (overwrite) or
  "ask me" (keep→set flag, or accept→overwrite).
- **Five open decisions** (see doc §7): record identity across sync; flag storage/
  granularity; packet authority for hand-entered fields; contribution moderation;
  delta vs. cumulative packets.
- **Five-phase build** (see doc §8): (1) golden-master base + preloader, (2) field
  policy schema + Settings UI, (3) contribution export, (4) client import + conflict
  engine, (5) hub merge/distribution. Phase 1 is the foundational first step.

---

## Authentication & OAuth

### F-AUTH-1: Silent eBay token refresh via Browse API (not RSS)
**Current state (updated Session 12):** The RSS feed (`_rss=1`) is retired; Tier 2a
scrapes the sold-listings HTML. **The parser is current and correct** — verified
Session 12 against a real captured sold-listings page (57 valid prices parsed). The
403s seen in logs are a *fetch-time* bot challenge against datacenter/test IPs, not a
parse failure, and may not occur on a real device's residential connection — so Tier
2a is *possibly* functional on-device and should be tested there before being written
off. eBay OAuth is implemented but the Browse API is never called. NOTE: the Browse
API returns only *active* listings, not sold comps — so it is not a drop-in
replacement for sold-price data. If the HTML scrape proves reliably blocked on real
devices, the Browse API (active asking prices) or the Marketplace Insights API (sold
comps, requires application approval) are the alternatives.
**What to build:** Once `isEbayTokenValid()` is true, `PriceService` should attempt a
Browse API call before falling back. Return a `PriceSnapshot` with
`source = PriceSource.EBAY_BROWSE`.
**Files to change:** `PriceService.kt` — add `fetchEbayBrowseApi(item, token)`.
**Effort:** ~1 session. Requires a real eBay CLIENT_ID (developer.ebay.com).

### ~~F-AUTH-2~~: Automatic token refresh notification ✅ DONE
**Current state:** When `TokenKeeperWorker` detects an expired refresh token
(400/401 from provider), it logs a warning but the user has no idea.
**What to build:** Post a notification on the `backup_status` channel:
"HobbyDB session expired — tap to re-authenticate in Settings".
Tapping opens Settings > Data Sources via a deep-link PendingIntent.
**Files to change:** `TokenKeeperWorker.kt` — add `sendReauthNotification(provider)`.
**Effort:** ~30 minutes.

---

## Prices & Market Data

### F-PRICE-1: Pop Price Guide integration (free tier)
**Current state:** HobbyDB is the only market price source requiring auth.
**What to build:** Pop Price Guide (`popriceinfo.com`) has a free read API returning
recent sale prices without OAuth. Add as Tier 3.5 (between Channel3 premium and HobbyDB).
The API is `GET https://popriceinfo.com/api/v1/search?q={name}`.
**Files to change:** `PriceService.kt` — add `fetchPopPriceGuide(item)` method;
`OAuthConfig.kt` — add `PopPriceGuide` object with BASE_URL;
`PriceSource` enum in `FunkoItem.kt` — add `POP_PRICE_GUIDE`.
**Effort:** ~1 session.

### F-PRICE-2: Price history chart in Detail screen
**Current state:** The Detail screen shows the current cached market price but no history.
**What to build:** Store price snapshots with timestamps; show a simple Compose Canvas
line chart of the last 12 fetches. Data model: add `fetchedAt: LocalDate` to
`PriceSnapshot` (already present) and query the `price::` docs sorted by date.
**Files to change:** `DetailScreen.kt`, `DetailViewModel.kt` — add `priceHistory` StateFlow;
new `PriceHistoryChart.kt` composable using `androidx.compose.foundation.Canvas`.
**Effort:** ~2 sessions.

### F-PRICE-3: Bulk price refresh for entire collection
**Current state:** Prices are refreshed per-item when the user views the Detail screen.
**What to build:** A "Refresh all prices" button in Reports that queues a batch price
fetch for every owned item. Use a coroutine `Flow` with rate limiting (100ms delay
between requests to avoid API throttling). Show a progress dialog.
**Files to change:** `ReportsScreen.kt`, `ReportsViewModel.kt`, `FunkoRepository.kt`.
**Effort:** ~1 session.

---

## Collection Features

### F-COLL-1: Tablet two-pane layout
**Current state:** App uses a single-column layout optimised for phones.
**What to build:** Use `WindowSizeClass` (already a Compose dependency) to show a
list+detail split on tablets (width ≥ 840dp). `CollectionScreen` becomes the master
pane; selecting an item updates the `DetailScreen` in the detail pane without
pushing a new back-stack entry.
**Files to change:** `FunkoDexNavHost.kt` — add `WindowSizeClass`-aware navigation;
`CollectionScreen.kt`, `DetailScreen.kt`.
**Effort:** ~2 sessions.

### F-COLL-2: Custom tags / labels on items
**Current state:** Items have `notes` (free text) and `condition` (enum) but no tagging.
**What to build:** Allow users to add arbitrary tags (e.g. "gift", "grail", "damaged
box"). Tags stored as a string list in the `funko::` document. Filter collection by tag.
**Files to change:** `FunkoItem.kt` — add `tags: List<String> = emptyList()`;
`FunkoMapper.kt` — serialize as JSON array; `DetailScreen.kt` — tag editor UI;
`CollectionViewModel.kt` — filter by selected tags.
**Effort:** ~1 session.

### F-COLL-3: Duplicate / variant detection
**Current state:** Scanning an already-owned UPC shows the AlreadyOwned sheet but
doesn't detect if the user has a different variant (e.g. regular + chase) of the same figure.
**What to build:** When a UPC matches an owned item, check if `isChase` differs.
Prompt: "You own the regular version — is this a Chase variant?". Allow recording
both as separate items with a shared `catalogRef`.
**Files to change:** `ScannerViewModel.kt`, `ScannerScreen.kt` — update AlreadyOwned sheet.
**Effort:** ~1 session.

### F-COLL-4: Want list notifications (restock alerts)
**Current state:** Price alerts notify when market low drops below target. No alert
for vaulted items returning to retail.
**What to build:** When `isVaulted` flips from `true` to `false` in a `catalog::` doc
(detected during `CatalogRefreshWorker`), notify the user if they have that item on
their want list.
**Files to change:** `CatalogRefreshWorker.kt` — compare old vs new `isVaulted` and
post a notification if the item is on the want list.
**Effort:** ~1 session.

---

## Platform & Integration

### F-PLAT-1: Wear OS companion app
**Current state:** No Wear OS support.
**What to build:** Simple watchface-adjacent tile showing owned count, today's total
market value, and the most-wanted item. Uses Wear OS Tiles API (`androidx.wear.tiles`)
and reads from Couchbase via `FunkoRepository`.
**New module:** `wear/` sub-module. Shared data via `DataLayer` (Wearable Data API).
**Effort:** ~3 sessions (new module, DataLayer setup, tile design).

### F-PLAT-2: Android Auto display
**Current state:** No automotive integration.
**What to build:** When driving past a store, show the want list on Android Auto.
Read-only — just top 5 wanted items. Uses `androidx.car.app`.
**Effort:** ~2 sessions. Low priority for a collector app.

### F-PLAT-3: Google Assistant / App Actions
**Current state:** No voice/shortcut integration.
**What to build:** Register App Actions so "Hey Google, scan a Funko" opens the
scanner directly. Register a shortcut for "Show my collection value".
**Files:** `shortcuts.xml` in `res/xml/`; `AndroidManifest.xml`.
**Effort:** ~1 session.

### ~~F-PLAT-4~~: Home screen shortcut (quick scan) ✅ DONE
**Current state:** Widget shows stats only.
**What to build:** A pinned shortcut (long-press the app icon) that opens directly
to `ScannerScreen`, bypassing the splash and collection grid.
**Files to change:** `FunkoDexApp.kt` — register `ShortcutInfo`; `MainActivity.kt`
— handle `SHORTCUT_SCAN` intent action.
**Effort:** ~30 minutes.

---

## Data & Sync

### F-DATA-1: iCloud / Dropbox backup (in addition to Drive)
**Current state:** Only Google Drive backup is supported.
**What to build:** Abstract the backup target behind a `BackupProvider` interface.
Implement `DropboxBackupProvider` using the Dropbox SDK.
iCloud is not directly accessible from Android — skip.
**New files:** `auth/DropboxOAuthActivity.kt`, `data/backup/DropboxBackupWorker.kt`.
**Effort:** ~2 sessions.

### F-DATA-2: Couchbase Lite → Capella sync (multi-device)
**Current state:** Collection is local-only (Couchbase Lite Community).
**What to build:** Switch to Couchbase Lite Enterprise edition (requires licence for
commercial distribution, free for personal use). Configure `Replicator` pointing at a
Capella cloud endpoint. The `funko::` document schema is unchanged — Capella syncs as-is.
**Files to change:** `build.gradle.kts` — swap `couchbase-lite-android-ktx` for
`couchbase-lite-android-ee-ktx`; `FunkoDexDatabase.kt` — add `Replicator` setup.
**Effort:** ~1 session + Capella account setup.

### F-DATA-3: CSV/JSON import from other collection apps
**Current state:** Users can import a FunkoDex database backup but not from other apps.
**What to build:** A CSV import flow that maps columns from common apps
(Vaulted, Pop In A Box, MyFunkoCollection). User picks a CSV file; a
`CsvMappingScreen` lets them assign columns; items are bulk-imported.
**Files:** New `data/import/CsvImporter.kt`, `ui/screens/settings/ImportScreen.kt`.
**Effort:** ~2 sessions.

---

## Quality & Testing

### ~~F-QA-1~~: Wire ScannerViewModelStateTest with Mockk ✅ DONE
**Current state:** `ScannerViewModelStateTest` contains only placeholder assertions.
**What to build:** Add `io.mockk:mockk:1.13.12` to `testImplementation` in
`build.gradle.kts`. Replace placeholder tests with real `mockk()` fakes for
`FunkoRepository`, `FunkoLookupService`, `ImageBlobRepository`, and
`ContributionRepository`. Test all 10 `ScanState` transitions.
**Files to change:** `app/build.gradle.kts`, `ScannerViewModelStateTest.kt`.
**Effort:** ~1 session.

### F-QA-2: Instrumented tests for Couchbase operations
**Current state:** No instrumented tests (require a device/emulator).
**What to build:** `FunkoRepositoryInstrumentedTest` using an in-memory Couchbase Lite
database (`DatabaseConfiguration.setFullSync(false)` + temp dir). Test `saveItem()`,
`deleteItem()`, `collectionFlow()`, and `getCollectionStats()`.
**Files:** New `androidTest/java/com/funkodex/data/repository/FunkoRepositoryTest.kt`.
**Effort:** ~1 session.

### F-QA-3: Crashlytics integration
**Current state:** `CrashHandler` writes local crash logs but nothing goes to a
remote crash reporting service.
**What to build:** Add Firebase Crashlytics (`com.google.firebase:firebase-crashlytics`).
`CrashHandler` already catches uncaught exceptions — add `FirebaseCrashlytics.getInstance().recordException(throwable)` before calling the previous handler.
**Effort:** ~30 minutes + Firebase project setup.

---

## UI & UX Polish

### F-UI-1: Collection value over time chart
**Current state:** Reports shows current total market value but no history.
**What to build:** Persist daily snapshots of `totalMarketValue` to a
`value_history::YYYY-MM-DD` Couchbase doc. Show a `Canvas`-drawn line chart
in Reports covering the last 90 days.
**Files:** `ReportsViewModel.kt` — add `valueHistory` StateFlow;
new `CollectionValueChart.kt` composable.
**Effort:** ~1.5 sessions.

### ~~F-UI-2~~: Barcode scan sound / haptic feedback ✅ DONE
**Current state:** No feedback when a barcode is successfully scanned.
**What to build:** Brief haptic pulse (`VibrationEffect.createOneShot(50, 200)`) on
successful barcode detection. Optional: play a short beep using `ToneGenerator`.
Both are gated by system settings (Do Not Disturb, vibrate mode).
**Files to change:** `ScannerScreen.kt` or `ScannerViewModel.kt`.
**Effort:** ~30 minutes.

### F-UI-3: Animated scan line overlay
**Current state:** Scanner preview has a static frame overlay.
**What to build:** Add an animated horizontal scan line (like a classic barcode scanner)
using `infiniteTransition` + `Canvas`. Purely cosmetic.
**Files to change:** `ScannerScreen.kt` — update `ScanFrameOverlay()`.
**Effort:** ~45 minutes.

### F-UI-4: Item sharing card
**Current state:** Users cannot share information about a specific Funko.
**What to build:** "Share" action on `DetailScreen` that generates a stylised card
image (using `Canvas` + `Picture`) showing name, image, condition, price paid, and
market value. Shared via `ACTION_SEND` with `image/png` MIME type.
**Files:** New `ui/screens/detail/ShareCard.kt`.
**Effort:** ~1.5 sessions.

### F-UI-5: Dark/light mode scheduled switching
**Current state:** Theme follows system setting or is manually selected.
**What to build:** Add a "schedule" option: auto-switch to dark at sunset, light at
sunrise using the device's location-based sunset time from `android.location`.
**Files to change:** `SettingsViewModel.kt`, `UserPreferencesRepository.kt`,
`Theme.kt`.
**Effort:** ~1 session.

---

## Security & Compliance

### F-SEC-1: Play Integrity API in Cloudflare Worker
**Current state:** The Cloudflare Worker validates HMAC signatures but does not
verify that the request came from an unmodified Play Store build.
**What to build:** Add Play Integrity token generation to `GitHubUploadWorker.kt`
(`com.google.android.play:integrity:1.3.0`). Include the token as an `X-Integrity`
header. In the Cloudflare Worker, validate the token via the Play Integrity API
(`https://playintegrity.googleapis.com/v1/{packageName}:decodeIntegrityToken`).
**Effort:** ~1 session (Android side) + Worker update.

### F-SEC-2: Biometric lock for collection
**Current state:** Collection is accessible to anyone who unlocks the phone.
**What to build:** Optional biometric prompt (fingerprint/face) using
`androidx.biometric:biometric:1.1.0` before showing the collection. State stored
in `EncryptedSharedPreferences`. When enabled, a 5-minute grace window prevents
constant re-authentication.
**Files:** New `security/BiometricGuard.kt`; `MainActivity.kt`.
**Effort:** ~1 session.

### F-SEC-3: Certificate pinning for critical endpoints
**Current state:** Network security config uses system CA store only.
**What to build:** Add OkHttp `CertificatePinner` for `hobby-db.com` and
`api.ebay.com`. Pin the leaf certificate SHA-256 hash.
**Risk:** Certificate rotation by the provider will break the app until updated.
Only recommended if the threat model justifies it (high-value collector data).
**Files to change:** `AppModule.kt` — add `.certificatePinner(...)` to OkHttpClient.
**Effort:** ~30 minutes (getting the pin hashes takes most of the time).

---

## Performance

### ~~F-PERF-1~~: Lazy image loading with memory cache tuning ✅ DONE
**Current state:** Coil uses default memory cache (25% of heap).
**What to build:** Configure `ImageLoader` singleton in `FunkoDexApp` with
`memoryCachePolicy = CachePolicy.ENABLED`, `diskCachePolicy = CachePolicy.ENABLED`,
and `availableMemoryPercentage = 0.3`. Also set `crossfade(true)` globally so all
`AsyncImage` calls animate smoothly without per-call configuration.
**Files to change:** `FunkoDexApp.kt` — add `Coil.setImageLoader(...)`.
**Effort:** ~20 minutes.

### F-PERF-2: Database query caching layer
**Current state:** `getCollectionStats()` runs the full query every time Reports is opened.
**What to build:** Cache the result in a `MutableStateFlow<CollectionStats?>` on
`FunkoRepository`. Invalidate on `saveItem()`/`deleteItem()`. Reports observes the
StateFlow rather than calling the suspend function directly.
**Files to change:** `FunkoRepository.kt`, `ReportsViewModel.kt`.
**Effort:** ~30 minutes.

### F-PERF-3: Paging for large collections
**Current state:** `collectionFlow()` loads all `funko::` documents into a `List`.
For a collection of 5,000+ items, this is 5,000 `FunkoItem` objects in memory.
**What to build:** Use `androidx.paging:paging-compose` (`Pager` + `PagingSource`)
backed by a Couchbase query with `LIMIT` and `OFFSET`. The `LazyColumn` in
`CollectionScreen` uses `LazyPagingItems`.
**Files to change:** New `data/repository/FunkoPagingSource.kt`;
`FunkoRepository.kt`; `CollectionViewModel.kt`; `CollectionScreen.kt`.
**Effort:** ~2 sessions. Only valuable for collectors with >2,000 items.

---

## Internationalisation

### F-I18N-1: Multi-language support
**Current state:** All strings are hardcoded in English.
**What to build:** Extract all UI strings (including `HelpContent.kt`) to
`app/src/main/res/values/strings.xml`. Add `values-es/`, `values-de/`, `values-fr/`
folders. Use `stringResource(R.string.*)` in Composables.
**Note:** `HelpContent.kt` was designed for easy extraction — all strings are
already constants.
**Effort:** ~2 sessions for extraction + 1 per language for translation.

---

*Last updated: May 2026. Maintained by Celtic Heart Steamworks.
5 items completed this session: F-QA-1, F-PERF-1, F-PLAT-4, F-AUTH-2, F-UI-2.*
*To implement any item: start a new Claude session, share `CLAUDE.md` and `FUTURE.md`,
and say "Implement F-XXX-N".*
