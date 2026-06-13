# FunkoDex Changelog

All notable changes to this project are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

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
