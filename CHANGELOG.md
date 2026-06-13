# FunkoDex Changelog

All notable changes to this project are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [Unreleased] — Session 10 — 2026-06-13

### Fixed

**Reports — "Est. Market Value" and "Total Retail Value" always showed $0.00**

- Root cause: `FunkoItem.marketAvg` and `retailPrice` were only ever *read*
  into `CollectionStats` (`totalMarketValue` / `totalRetailValue`), never
  *written*. `DetailViewModel.refreshPrices` resolved a `ResolvedPrice` for
  display on the Detail screen's "Market Price" card but never persisted any
  of it back onto the saved `FunkoItem` document — so per-item and aggregate
  totals stayed at their defaults regardless of what the Detail screen showed.
- `DetailViewModel.kt::refreshPrices` — after resolving a price, if
  `resolved.marketAvg` or `resolved.retail` differ from the stored values,
  `repository.saveItem(item.copy(marketAvg = ..., resolvedRetail = ...))` and
  update `_state` so the UI reflects the persisted values immediately.
- **On-device result:** Stitch with Frog (UPC `889698517959`) — Market avg
  $37.94 / Retail $26.93 (UPCitemdb) now persist to the item record; Reports
  "Est. Market Value", "Highest Market Value", and series "Value" all show
  $37.94 after a price refresh.

**Reports — stats not recomputed after returning from Detail screen**

- `ReportsViewModel.refresh()` only ran once in `init {}`. Refreshing a
  price on the Detail screen and navigating back to Reports showed the
  stale `CollectionStats` snapshot from before the refresh.
- `ReportsScreen.kt` — added a `DisposableEffect` + `LifecycleEventObserver`
  that calls `viewModel.refresh()` on `ON_RESUME`, so reopening the Reports
  tab recomputes stats.

**"Total Retail Value" — new `resolvedRetail` field, distinct from catalog
`retailPrice`**

- `item.retailPrice` is catalog-sourced Funko MSRP and also gates
  `PriceService` Tier 1 (a non-zero `retailPrice` short-circuits the entire
  price waterfall on every future refresh, returning `source =
  RETAIL_CATALOG`). Writing a marketplace-resolved retail value (e.g. from
  UPCitemdb) into `retailPrice` would mislabel its provenance and disable
  eBay/Channel3/HobbyDB tiers for that item permanently.
- Added `FunkoItem.resolvedRetail: Double = 0.0` — the best "retail" figure
  from the price waterfall, refreshed independently of catalog data — plus
  `FunkoItem.effectiveRetail` (`retailPrice` if > 0, else `resolvedRetail`).
  All "retail" *display/total* sites now use `effectiveRetail`:
  `FunkoRepository.totalRetailValue`, `DetailScreen`'s Pricing card,
  `ReportsScreen`'s per-series item rows, `PreScanScreen`'s preview label,
  and every retail column/sum in `CollectionExporter` (xlsx + CSV).
  Catalog-input contexts (`ScannerScreen`/`ScannerViewModel` price-paid
  defaults) intentionally continue to use `retailPrice` (catalog MSRP),
  unchanged.
- `FunkoDexDatabase.FIELD_RESOLVED_RETAIL = "resolvedRetail"` /
  `FunkoMapper` — persist/read the new field.

**"I only have the variant — want the original" control looked like static
text, not a button**

- `DetailScreen.kt` — the variant-only flag control (shown for owned items
  not yet flagged) was a `TextButton` with no visible chrome, indistinguishable
  from a label. Changed to `OutlinedButton` so it reads as tappable, matching
  the outlined style of the "FYE Exclusive" chip above it.

### Changed

**Deprecation cleanup (Material icons, CBL, CameraX, Vibrator)**

- `DetailScreen.kt`, `CategoryFilterScreen.kt` — `Icons.Default.ArrowBack` →
  `Icons.AutoMirrored.Filled.ArrowBack` (with the corresponding
  `androidx.compose.material.icons.automirrored.filled.ArrowBack` import —
  `Icons.AutoMirrored` is not a member of the wildcard
  `androidx.compose.material.icons.filled.*` import and needs its own import
  to resolve).
- `PreScanScreen.kt` — `Icons.Default.HelpOutline` →
  `Icons.AutoMirrored.Filled.HelpOutline` (+ import).
- `DetailViewModel.kt` — `db.getDatabase().getDocument(id)` /
  `db.getDatabase().save(doc)` (deprecated CBL 3.x `Database` API) →
  `db.getCollection().getDocument(id)` / `db.getCollection().save(doc)`,
  matching the default-collection convention already used throughout
  `FunkoRepository`.
- `FunkoRepository.kt` — `query.removeChangeListener(token)` (deprecated) →
  `token.remove()` in both `collectionFlow()` and `wantListFlow()`.
- `ScannerScreen.kt` —
  - `@OptIn(ExperimentalGetImage::class)` on `startCamera` removed: this
    CameraX version's `ExperimentalGetImage` is not a `@RequiresOptIn` marker,
    so the `@OptIn` was a no-op flagged by the compiler ("annotation ... is
    not annotated with '@OptIn'. '@OptIn' has no effect."). `ImageProxy.image`
    is not used in this file, so no opt-in is actually required.
  - Legacy haptic fallback (`Vibrator.vibrate(Long)`, API < 31) — the
    `@Suppress("DEPRECATION")` only covered the `val v = ...` declaration, not
    the separate `v?.vibrate(50)` call. Wrapped both in a `run { }` block under
    one `@Suppress("DEPRECATION")`.

---

### Fixed

**Wiring gaps from Session 8 handoff (commits `74c5616`, `6f2c523`)**

- **`ReportsScreen.kt`** — was referenced/imported by `FunkoDexNavHost.kt` but
  absent from the repo (local-only file from a prior session). Created
  `ui/screens/reports/ReportsScreen.kt` and `ReportsViewModel.kt`: summary
  stat cards (owned/want-list/franchises/market value), cost breakdown card,
  `ExportButton()`, per-series completion cards with expandable want-list
  rows, and the existing `REPORTS_EMPTY`/`REPORTS_MARKET_NOTE` help strings
  (previously dead). Unblocked test item **A9**.
- **`CatalogDataSection`** — was defined in `SettingsScreen.kt` but never
  invoked, so the Channel3 API key dialog, HobbyDB/eBay OAuth connect rows,
  and the catalog auto-refresh controls (interval, Wi-Fi-only, "Refresh now")
  were unreachable. Wired into the "Catalog" section of `SettingsScreen`,
  reusing the existing `catalogSettingsViewModel` instance. Unblocked test
  items **B1, B2, B3, B6**.
- **`.gitignore`** — a blanket `reports/` rule (intended for
  `app/build/reports/` Gradle test output) was also matching the new
  `ui/screens/reports/` source package, silently excluding it from `git add`.
  Narrowed to `app/build/reports/`.

**Enriched catalog import — JSON parse failure (`ArrayList cannot be cast to
java.lang.Void`)**

- Root cause: Gson's reflective `TypeToken<List<EnrichedRecord>>` binding
  mis-resolved the `EnrichedRecord` data class's field types under Kotlin's
  emitted bytecode/metadata, throwing on every import attempt regardless of
  field nullability (verified both nullable and non-nullable `List<String>`
  variants parse correctly via plain Java + Gson 2.11.0 reflection — the
  failure is Kotlin-bytecode-specific and not reproducible with `kotlinc`
  unavailable in the sandbox).
- Fix: `CatalogImporter.importFromUri` no longer uses
  `gson.fromJson(json, TypeToken<List<EnrichedRecord>>)`. Parses the JSON tree
  (`JsonParser` → `JsonArray` → `JsonObject`) and maps each object to
  `EnrichedRecord` via explicit field-by-field extension functions
  (`optString`/`optBoolean`/`optStringList`), bypassing Gson's reflective
  `TypeAdapter` entirely. Validated against the full 14,314-record
  `funko_data_enriched.json` with a standalone Gson 2.11.0 build (compiled
  from source in-sandbox) — all records parse, including `series` arrays and
  null `available`/`funkoNumber` fields.
- `EnrichedRecord.kt` — `series: List<String>? = null` → `series: List<String>
  = emptyList()` (the tree parser always supplies a list, never null); removed
  now-redundant `?: emptyList()` elsis at the three call sites and the
  now-unused `gson`/`Gson`/`TypeToken` members/imports in `CatalogImporter.kt`.
- **On-device result (full 14,314-record file, first run):** 13,585 enriched,
  725 added, 4 skipped, 0 errors, completed in 51s — matches
  `HANDOFF.md`'s "~13,583 enriched, ~725 added, ~4 skipped" expectation from
  the 2026-06-12 dry-run estimate. **D1b confirmed PASS.**

**Catalog category data bug — "Pop! Vinyl" stored as `category`, hiding 714
records from search**

- `CatalogMapper.mapRecord`'s `category` field picked the first series tag
  starting with `"Pop!"`, including the generic format descriptor `"Pop!
  Vinyl"`. For the 729 funko.com-sourced `.html`-handle records (series like
  `["Pop! Vinyl", "Music"]`), this produced `category = "Pop! Vinyl"` for 714
  of them — a value that doesn't correspond to any entry in
  `FunkoCategories.ALL`.
- `FunkoLookupService.searchByName`'s category filter compared
  `item.category.contains(key)` where `key` is a normalized slug (e.g.
  `pop_music`) and `item.category` is a display string (e.g. `"Pop! Music"`)
  — `"Pop! Music".contains("pop_music")` is always `false`. Every catalog
  search result was being silently dropped by this filter unless
  `item.category` was empty (the only path that passed).
- Fixes:
  - `CatalogMapper.kt` — `category` selection now excludes `"Pop! Vinyl"` and
    bare `"Pop!"`, mirroring the existing `primarySeries` exclusion. Falls
    back to `""` (uncategorized) when no real Pop! category tag is present.
  - `FunkoLookupService.kt` — category filter now normalizes
    `item.category` via the canonical `FunkoCategories.toKey()` before
    checking set membership against `enabled` (which holds keys, not display
    strings).
  - `CatalogImporter.kt` (merge path) — if an existing doc's stored
    `category` is `"Pop! Vinyl"`, recompute from the record's series and
    overwrite, so **re-running the import self-heals previously-inserted bad
    categories** without a catalog wipe.
- **On-device result (re-import after fix):** 14,310 updated, 0 added, 4
  skipped, completed in 47s — confirms all 14,310 records now match by
  handle (idempotent) and the 714 bad categories were repaired. Verified
  `Search Catalog → "perpetua"` now returns "Papa V Perpetua · Music" (was
  previously zero results).

**File picker — enriched catalog import defaulted away from Downloads**

- `SettingsScreen.kt` — added `OpenDocumentInDownloads`, a small
  `ActivityResultContracts.OpenDocument` subclass that sets
  `EXTRA_INITIAL_URI` to the AOSP Downloads root
  (`DocumentsContract.buildDocumentUri("com.android.providers.downloads.documents",
  "downloads")`, API 26+, matches minSdk 26) so the "Import Enriched Catalog"
  picker opens directly in Downloads instead of the picker's default location.
  Most pickers (incl. AOSP DocumentsUI) honor this; some OEM pickers may
  ignore it.

### Changed

**Deprecation cleanup (Material icons + CBL)**

- `ReportsScreen.kt` — `Icons.Default.TrendingUp` (deprecated, no
  `AutoMirrored` equivalent exists) → `Icons.Default.AttachMoney` for the
  "Market Value" stat card.
- `SettingsScreen.kt` — `Icons.Default.Logout` →
  `Icons.AutoMirrored.Filled.Logout` (Disconnect Google Drive row).
- `FunkoLookupService.kt` — `db.getDatabase().getDocument(docId)` (deprecated
  in the CBL 3.x Collection API) → `db.getCollection().getDocument(docId)`,
  matching the Session 7 Collection API migration pattern already used in
  `CatalogImporter`.

### Commits
`74c5616`, `6f2c523`, `4e6759d`, `d69a4ec`

---

## [Unreleased] — Session 8 — 2026-06-12

### Changed

**Keystore / security-crypto Migration (Play P2 — Session E)**

`SecureKeyStore.kt` rewritten to remove the dependency on
`androidx.security:security-crypto`, which was pinned at `1.1.0-alpha06` —
verified via web search to be the latest available release with no stable
1.1.0 ever published (open Google issue tracker requests for a stable release
and for clearer deprecation signaling).

- New implementation: AES-256-GCM key generated directly in `AndroidKeyStore`
  (alias `funkodex_secure_key`, `PURPOSE_ENCRYPT or PURPOSE_DECRYPT`,
  `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`, 256-bit, randomized).
- Ciphertext stored as `base64(iv):base64(ciphertext)` strings in a plain
  `SharedPreferences` file `funkodex_secure_prefs_v2`.
- Public API of `SecureKeyStore` is unchanged — all 12 calling files
  (`OAuthCallbackActivity`, `OAuthConfig`, `OAuthLauncher`,
  `TokenRefreshManager`, `DriveBackupWorker`, `CatalogRefreshWorker`,
  `AppModule`, `FunkoLookupService`, `PriceService`, `HmacKeyStore`,
  `CatalogSettingsViewModel`, `SettingsViewModel`) required no edits.
- `app/build.gradle.kts` and `gradle/libs.versions.toml` — removed
  `security-crypto` dependency and version entry entirely.
- `HmacKeyStore.kt`, `TokenRefreshManager.kt`, `PriceService.kt`,
  `FunkoLookupService.kt`, `app/build.gradle.kts` — updated stale doc
  comments referencing "EncryptedSharedPreferences" to describe the new
  AES/GCM Keystore wrapper.

**No migration from old encrypted prefs (deliberate, user-approved tradeoff):**
The old `funkodex_secure_prefs` (EncryptedSharedPreferences) file is abandoned
on disk — still encrypted, inert, never read or deleted. On upgrade, users
will need to re-enter their Channel3 API key and re-link HobbyDB/eBay accounts
once. A migration shim was drafted but rejected because it would have required
keeping `security-crypto` as a dependency solely to read the old file once,
defeating the purpose of the migration.

### Outstanding
- Device verification: confirm Channel3 key entry, HobbyDB link, and eBay
  link all round-trip correctly through the new AES/GCM wrapper on a real
  device (hardware Keystore behavior not verified by compile/run alone)
- Carried over from Sessions 5–7: full Session 7 functional/device test pass
  (`SESSION_D_TRACKER.md`), unit test suites, Cloud Console OAuth client,
  device tests T-D1–T-D5, Photo Picker smoke test, 16 KB emulator regression

---

## [Unreleased] — Session 7 — 2026-06-12

### Changed

**CBL Collection API Migration (Play P2 — Session D)**

Migrated all database-level Couchbase Lite calls to the Collection API ahead
of CBL 4.x. `database.defaultCollection` (non-null — the default collection
always exists and cannot be deleted) replaces direct `Database` access for
document and query operations:

- `database.getDocument/save/delete` → `collection.getDocument/save/delete`
- `database.createQuery(...)`, `DataSource.database(db)` → `DataSource.collection(col)`
- `database.createIndex(name, index)` → `collection.createIndex(name, index)`
  (same `IndexBuilder`/`ValueIndexItem` signature)
- `database.inBatch(UnitOfWork {...})` — **unchanged**, remains
  database-level (transaction wrapper, not deprecated, not moved to
  Collection in 3.2.x). Operations inside the lambda convert to `collection.X`.

`FunkoDexDatabase.kt` — added `fun getCollection(): com.couchbase.lite.Collection
= getDatabase().defaultCollection`. Return type is fully-qualified to avoid
`kotlin.collections.Collection<T>` shadowing from the implicit Kotlin
collections import (this caused a cascade of ~50 "Unresolved reference"
errors on first attempt — see Lessons Learned below).

12 files converted (~98 call sites):
- `data/db/FunkoDexDatabase.kt` — added `getCollection()`; `ensureIndexes()` (12 sites)
- `data/repository/FunkoRepository.kt` (21 sites)
- `data/repository/AlertRepository.kt` (10 sites)
- `data/repository/ContributionRepository.kt` (8 sites)
- `data/repository/CategoryPreferenceRepository.kt` (16 sites; `inBatch` × 3 stays on `database`)
- `data/repository/ImageBlobRepository.kt` (3 sites)
- `data/preload/CatalogPreloader.kt` (8 sites; `ensureCatalogIndexes` now takes a `Collection` param)
- `data/preload/CatalogImporter.kt` (8 sites; `buildTitleIndex` now takes a `Collection` param)
- `data/preload/CatalogRefreshWorker.kt` (12 sites across 3 functions; `inBatch` × 3 stays on `database`)
- `network/FunkoLookupService.kt` (2 sites)
- `network/ConnectivityObserver.kt` (7 sites)
- `ui/screens/settings/DatabaseTransferViewModel.kt` (export/import/force-restore)

**Force-restore care (`DatabaseTransferViewModel.forceRestoreDatabase`)** —
`db.close()` → wipe `funkodex.cblite2` directory → `db.reopen()` → `liveCollection
= db.getCollection()` obtained AFTER reopen, so it derives from the fresh
`Database` instance via `getDatabase().defaultCollection` rather than a stale
reference.

### Lessons Learned
- `fun getCollection(): Collection` (unqualified) resolves to
  `kotlin.collections.Collection<T>` in files with `import com.couchbase.lite.*`
  — Kotlin's implicit `kotlin.collections.*` import wins. Always fully-qualify
  as `com.couchbase.lite.Collection` for any function/parameter signature named
  `Collection`. One bad declaration cascaded into ~50 compiler errors across
  3 files on first attempt.

### Outstanding
- Full functional/device test pass — see `SESSION_D_TRACKER.md` checklist.
  Backup/restore/force-restore is highest priority given the `inBatch`/
  `reopen()` interaction above.
- All unit test suites (FunkoMapperTest, CollectionStatsTest, FunkoLookupServiceTest, `./gradlew test`)
- 16 KB emulator regression re-run (CBL access patterns changed)
- Carried over from Sessions 5–6: Cloud Console OAuth client, device tests
  T-D1–T-D5, Photo Picker smoke test

---

## [Unreleased] — Session 6 — 2026-06-12

### Changed

**Photo Picker Migration (Play P1)**
- `DetailScreen.kt` — replaced `ActivityResultContracts.GetContent()` with
  `ActivityResultContracts.PickVisualMedia()` + `PickVisualMediaRequest(ImageOnly)`
  for the "Choose from gallery" flow
- Removed the `READ_MEDIA_IMAGES`/`READ_EXTERNAL_STORAGE` runtime permission gate
  (`storagePermission` block) — Photo Picker requires no storage permission
- `AndroidManifest.xml` — removed `READ_MEDIA_IMAGES` and `READ_EXTERNAL_STORAGE`
  permissions entirely (addresses Play Photo and Video Permissions policy)
- Removed now-unused `android.os.Build` import from `DetailScreen.kt`

**P3 Deprecation Cleanup**
- `app/build.gradle.kts` — replaced deprecated `kotlinOptions { jvmTarget = "17" }`
  with `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }`
- `CollectionScreen.kt` — replaced `com.google.accompanist.flowlayout.FlowRow`
  (`mainAxisSpacing`) with Compose Foundation `FlowRow`
  (`horizontalArrangement = Arrangement.spacedBy(8.dp)`,
  `@OptIn(ExperimentalLayoutApi::class)`); removed `accompanist-flowlayout`
  dependency and version-catalog entry entirely
- `Icons.Default.ArrowBack`/`Icons.Default.Logout` — left unchanged.
  `Icons.AutoMirrored.Filled.*` variants did not resolve against the current
  `compose-bom` (2024.09.00); reverted after compile failure rather than bump
  the BOM for this alone

### Outstanding
- Photo Picker smoke test: gallery pick on API 33+ and API 26–32
- Cloud Console OAuth client + device tests T-D1–T-D5 (carried over from Session 5)

---

## [Unreleased] — Session 5 — 2026-06-12

### Changed

**16 KB Page Size Compliance (Play P0)**
- Bumped `couchbase-lite` 3.2.1 → 3.2.4 (16 KB-aligned `libLiteCore.so`/`libLiteCoreJNI.so`,
  per Couchbase engineering confirmation)
- Bumped `camerax` 1.3.4 → 1.6.1 (16 KB-aligned `libimage_processing_util_jni.so`/
  `libsurface_util_jni.so`)
- `ScannerScreen.kt` / `PreScanScreen.kt` — replaced deprecated
  `ImageAnalysis.Builder().setTargetResolution(Size(1280,720))` with
  `ResolutionSelector`/`ResolutionStrategy(FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)`
- Fixed broken unit test: `ScannerViewModelStateTest.kt` referenced a non-existent
  `selectManualResult()`; replaced with a test of the actual implemented flow
  (`toggleManualSelection` + `confirmBulkAdd` → `ScanState.Saved`)
- Verified via Analyze APK on release build: all `.so` files across all ABIs
  (arm64-v8a, armeabi-v7a, x86, x86_64) — including the previously-contested
  `libbarhopper_v3.so` (ML Kit barcode 17.3.0) — report 16 KB alignment. No
  fallback to `play-services-mlkit-barcode-scanning` needed.
- Smoke-tested on a 16 KB-page-size emulator (catalog preload, barcode scan,
  photo capture) — no errors

**Google Drive Auth Migration (Play P1)**
- Replaced deprecated `GoogleSignIn`/`GoogleAccountCredential` with
  `AuthorizationClient` (DRIVE_FILE scope) — authorization-only, no Credential
  Manager dependency (see `docs/CredentialManager_Migration_SPEC.md`)
- New `data/backup/DriveAuthManager.kt` — single owner of `AuthorizationClient`
  interaction; normalizes results to `Authorized`/`NeedsConsent`/`Failed`
- `SecureKeyStore.kt` — added `isDriveConnected()`/`setDriveConnected()`/
  `clearDriveConnected()` boolean flag; no access token persisted (1h lifetime,
  re-`authorize()` each use)
- `DriveBackupWorker.kt` — worker calls `authorize()` every run; `NeedsConsent`
  → reconnect notification (id 3002), skip without retry; 401/403 mid-flight →
  `clearToken()` + `Result.retry()`
- `SettingsViewModel.kt` — `driveConnected` StateFlow, `connectDrive()`,
  `onConsentResult()`, `disconnectDrive()`, consent `PendingIntent` StateFlow
- `SettingsScreen.kt` — "Connect Google Drive" / "Connected · Tap to back up now"
  / "Disconnect Google Drive" rows; dropped "Signed in as {email}" (no identity
  in AuthorizationResult by design); disconnect cancels periodic worker, connect
  re-schedules it (`ExistingPeriodicWorkPolicy.UPDATE`, idempotent)
- Bumped `play-services-auth` 21.2.0 → 21.6.0; added
  `kotlinx-coroutines-play-services` for `Task.await()`
- Zero remaining references to `GoogleSignIn`/`GoogleAccountCredential`

### Outstanding
- Cloud Console: confirm Android OAuth client ID (`com.funkodex` + signing SHA-1)
- Device tests T-D1–T-D5 (`docs/CredentialManager_Migration_SPEC.md` §9),
  especially T-D3 (lapsed grant)

---

## [Unreleased] — Session 4 — 2026-06-12

### Added

**Enriched Catalog Import**
- Settings → Catalog → "Import Enriched Catalog" — user picks `funko_data_enriched.json`
  via file picker, merges into the live Couchbase catalog
- `EnrichedRecord.kt` — Gson deserialization target for the enriched JSON superset
- `CatalogImporter.kt` — handle-match → normalized-title fallback (ambiguous titles
  excluded) → merge or insert
- Merge path writes only new enriched fields (`isAvailable`, `productUrl`,
  `funkoImageUrl`, `funkoShopId`, `funkoNumber`, `popType`, `retailPrice`,
  `marketValueLoose/New`, `pricechartingId/Url`); never overwrites `imageUrl`, `title`,
  `handle`, `seriesList`; UPC written only if missing
- Insert path applies non-Pop merchandise filter, repairs funko.com page-name handles
  (`NNNNN.html` → title slug), and skips on docId collision (never-clobber guard)
- `CatalogMapper.kt` — added `FIELD_FUNKO_NUMBER`, `FIELD_POP_TYPE` and corresponding
  `mapRecord()` parameters
- Progress dialog (live record counter) and result summary dialog
  (enriched/added/skipped/errors/duration)
- `DEVICE_TEST_PLAN.md` — added Test 9 for on-device import verification

---

## [Unreleased] — Session 3 — 2026-06-04

### Added

**Variant System**
- `FunkoVariant` data class — id, note, photo (ByteArray), pricePaid, condition, dateAdded
- `FunkoItem.variants: List<FunkoVariant>` — variants stored on parent record, not as separate collection entries
- `FunkoItem.isVariant`, `variantNote`, `isMissingOriginal` flags
- Variants serialized as base64 JSON string in `FIELD_VARIANTS` Couchbase field
- Collection stats (totalOwned, totalPaid, ownedCount per series) sum across parent + variants
- Variant count badge on collection card — green when all have photos, red with camera icon when any are missing
- "NO ORIGINAL" badge on collection card (bottom-left) when `isMissingOriginal = true`
- Detail screen variants section — shows each variant with photo thumbnail or red "No photo" placeholder
- Edit screen variants section — editable note, price, delete per variant
- "Got it!" chip at top of detail screen when `isMissingOriginal = true` — opens confirmation dialog, enters edit mode

**Photo System**
- Single camera FAB on edit screen replaces three-button row
- Bottom sheet with three options: Take a photo, Choose from gallery, Fetch from catalog
- Photo target sheet — Main photo / Variation photo / Both after taking/choosing
- `fetchImageFromCatalog` — downloads official Funko image from catalog URL with status dialog (Fetching / Success / Failed with URL shown)
- `userPhoto` field added to `FunkoItem` and read in collection flow — user photos now show on collection card
- Collection card image priority: official URL first → user photo on error → thumbnail blob
- `FunkoMapper.toDocument` uses `existing?.toMutable()` to preserve blobs on save

**Add Flow**
- Nav bar "Add" label (was "Scan")
- `SavedConfirmation` screen after bulk add — "Add another", "I only have the variant — want the original", "Done"
- `AlreadyOwned` bottom sheet — "I have a variant", "I have a variant but NOT the original", "Update existing", Cancel
- Duplicate detection in `confirmBulkAdd` via `findOwnedByNameAndFranchise`
- `markVariantMissingOriginal` — flags item from add confirmation screen

**Detail Screen**
- All fields shown in view mode matching edit screen: Name, Series, Number, Category, Condition, Price paid, UPC, Funko ID, Date added, Notes
- "I only have the variant — want the original" text button in view mode
- UPC field in edit screen with manual entry and barcode scan (camera dialog)
- UPC contribution prompt after saving a new or corrected UPC
- Pending UPC contribution cancelled automatically on item delete or UPC change

**Reports**
- Three-tab layout: Have, Want, Combined — each a focused standalone report
- Have: collection summary + series breakdown with costs
- Want: want list summary + individual items including missing originals
- Combined: series completion bars + expandable want list per series
- Export CSV FAB saves current tab to Downloads as `FunkoDex_Have/Want/Combined_Report_YYYYMMDD.csv`
- `totalWanted` includes missing originals
- `isMissingOriginal` items appear in Want report as "[Name] (original)"

**Backup / Restore**
- Complete overhaul — JSON-based export/import (no Couchbase file copying)
- Export: queries all non-catalog, non-system docs, serializes to JSON with blobs as base64, zips as `FunkoDex_backup_YYYYMMDD_HHmmss.zip`
- Export saves to Downloads automatically AND shows share sheet as optional
- Restore: extracts JSON, deletes non-catalog/non-system docs, reinserts — no file locking, no restart needed
- Old-format backup detection with clear error message
- Force restore option — wipes entire database including catalog, rebuilds from backup + re-preloads catalog on next start
- Restore confirmation dialog shows file location hint
- Success/failure dialogs with clear messaging
- `takePersistableUriPermission` for file picker URI

**Settings**
- Force restore (corrupt database) option in Backup section
- Category filter now correctly applied to catalog search results (was only applied to collection display)
- `system` type added to marker documents — preserved through backup/restore
- `ensureDefaults` re-seeds category prefs if marker exists but docs were wiped (e.g. post-restore)

**System Splash**
- Celtic heart icon from `celticht.svg` (verbatim path data) centered in Android 12 system splash circle
- Navy background matching Compose splash
- Scale 0.5641 — calculated to fit 116.99×108.79 viewBox into 66dp safe zone

**Community Contributions**
- UPC contribution prompt after saving new or corrected UPC in detail edit
- Contribution auto-cancelled when item deleted or UPC changed before upload
- `deletePendingContribution`, `hasPendingContribution` added to `ContributionRepository`

### Changed

- Splash minimum display time: 4200ms (was 3600ms)
- `FunkoDexDatabase._database` changed from `lazy val` to nullable `var` to support force restore reopen
- Backup filename includes timestamp: `FunkoDex_backup_YYYYMMDD_HHmmss.zip`
- `AlreadyOwned` sheet redesigned as bottom sheet with clear variant options
- `SavedConfirmation` buttons: "Add another" (was "Scan another"), "Done"
- Detail view Pricing card simplified — Price paid moved into Details card
- Separate Notes card removed — Notes inline in Details card
- Want report "Items wanted" count includes missing originals

### Fixed

- `fetchImageFromCatalog` was checking `Viewing` state only — now checks `Editing` state too
- `addVariantPhoto` in edit mode now updates draft instead of saving mid-edit
- `removeVariant` now explicitly removes `FIELD_VARIANTS` field when list becomes empty
- `isMissingOriginal` and `isVariant` now use `remove` + conditional set to reliably clear `true` values
- `confirmBulkAdd` returns `Saved` state correctly after successful add
- `clearMissingOriginal` enters edit mode directly instead of silently saving
- Category filter applied to `searchByName` in `FunkoLookupService` (was only applied to collection display)
- Collection card `error` parameter added — falls back to `userPhoto` when `imageUrl` fails to load
- `FunkoMapper.toDocument` preserves existing blobs via `existing?.toMutable()`
- System and catalog marker documents preserved through backup/restore via `type = "system"`

### Files Changed

```
app/src/main/java/com/funkodex/data/model/FunkoItem.kt
app/src/main/java/com/funkodex/data/db/FunkoDexDatabase.kt
app/src/main/java/com/funkodex/data/db/FunkoMapper.kt
app/src/main/java/com/funkodex/data/repository/FunkoRepository.kt
app/src/main/java/com/funkodex/data/repository/CategoryPreferenceRepository.kt
app/src/main/java/com/funkodex/data/repository/ContributionRepository.kt
app/src/main/java/com/funkodex/data/repository/ImageBlobRepository.kt
app/src/main/java/com/funkodex/data/preload/CatalogPreloader.kt
app/src/main/java/com/funkodex/network/FunkoLookupService.kt
app/src/main/java/com/funkodex/ui/FunkoDexNavHost.kt
app/src/main/java/com/funkodex/ui/screens/SplashScreen.kt
app/src/main/java/com/funkodex/ui/screens/SplashViewModel.kt
app/src/main/java/com/funkodex/ui/screens/collection/CollectionScreen.kt
app/src/main/java/com/funkodex/ui/screens/detail/DetailScreen.kt
app/src/main/java/com/funkodex/ui/screens/detail/DetailViewModel.kt
app/src/main/java/com/funkodex/ui/screens/reports/ReportsScreen.kt
app/src/main/java/com/funkodex/ui/screens/reports/ReportsViewModel.kt
app/src/main/java/com/funkodex/ui/screens/scanner/ScannerScreen.kt
app/src/main/java/com/funkodex/ui/screens/scanner/ScannerViewModel.kt
app/src/main/java/com/funkodex/ui/screens/settings/SettingsScreen.kt
app/src/main/java/com/funkodex/ui/screens/settings/DatabaseTransferViewModel.kt
app/src/main/res/drawable/ic_splash_icon.xml
app/src/main/res/values/themes.xml
```

---

## [Unreleased] — Session 2 — 2026-06-03

### Fixed

**Collection**
- Owned items now always appear in the Collection screen regardless of category filter settings
- Category filter key normalization fixed — `"Pop! Heroes"` now correctly maps to `"pop_heroes"` key format
- Category filter no longer hides items with unrecognized category values

**Manual Search / Add**
- Search results list is now fully scrollable with correct bounded height via `BoxWithConstraints`
- Keyboard dismissed on search trigger (IME Done action replaces Search action)
- Items saved with `funko::UUID` IDs — previously saved with `catalog::` IDs which caused overwrites
- After bulk add, returns to scanner idle screen instead of showing stale prompt

**Delete**
- Delete now correctly removes items from both Collection screen and Reports screen
- Reports screen refreshes on every tab visit via `LaunchedEffect(Unit)`

**Reports**
- Series completion now groups by `franchise + category` instead of `franchise` alone
- `getCollectionStats` uses correct owned/wanted filtering

**Scanner**
- Scan tab shows idle screen first instead of auto-starting camera
- Manual search sheet `skipPartiallyExpanded = true`

**Category Defaults**
- All 22 categories default to enabled
- v3 migration force-sets `enabled = true` on all existing category preference documents

### Changed

- Settings — Appearance, Diagnostics, About all converted to single-row → dialog pattern
- Settings — Database section reorganized with Backup group
- Splash screen replaced with animated Celtic heart SVG
- Log retention changed from count-based to age-based (3 days)

---

## [0.1.0] — Session 1 — 2026-06-02

### Added
- Initial working build on API 34 emulator
- Couchbase Lite local database with catalog preloader (23,940 records)
- Collection, Scanner, Reports, Settings screens
- Price alerts via Channel3 API
- Google Drive backup integration
- Community UPC contribution toggle
- Splash screen (static placeholder)

### Fixed
- `collectionFlow()` moved to `Dispatchers.IO` — eliminated 74-second main thread freeze
- `DockedSearchBar` → `OutlinedTextField` — fixed Compose focus/JIT deadlock on API 34
- Splash screen simplified — original caused ART JIT verification overflow
- `confirmBulkAdd` ID generation — fresh `funko::UUID`
- Category preference seed defaults all set to `true`
