# FunkoDex Changelog

All notable changes to this project are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

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
