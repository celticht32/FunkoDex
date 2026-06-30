# FunkoDex — Session Handoff
**Date:** 2026-06-29
**Sessions completed:** 1 (initial build), 2 (enricher/catalog import), 3 (UI/variant/photo/backup), 4 (enriched catalog import implementation + handle repair), 5 (16 KB page-size compliance + Drive auth migration), 6 (Photo Picker migration + P3 deprecation cleanup), 7 (CBL Collection API migration), 8 (Keystore/security-crypto migration), 9 (wiring gaps closed — Reports/CatalogDataSection; enriched-import parse fix + on-device verification; category data/filter bugfix; deprecation cleanup), 10 (UPC scan-only enforcement; Funko ID row removal; category-key search fix), 11 (scanner retry/frame-confirmation; punctuation-tolerant search; manual add of catalog-missing items + community contribution; image URL entry + http→https fix; eBay RSS→HTML pricing; manual market value with feed-overwrite; multiple bugfixes; community-distribution architecture design doc), 12 (image-URL clear on edit; scan-again goes straight to camera; manual-UPC check-digit validation; third "enter details manually" button on Add screen; conditional manual-add subtitle; variant-aware pricing across eBay/HobbyDB/Channel3; eBay price-band ceiling raise; OkHttp response-leak fixes in PriceService; UPCitemdb regex→gson rewrite; camera-executor thread-leak fix; enriched-data malformed-UPC salvage), 13 (PriceCharting end-to-end: marketValueComplete + UPCs + metadata through enricher→import→catalog→FunkoItem; scan reads Couchbase catalog not bundled JSON; live PriceCharting refresh tier re-scrapes stored product page via OkHttp; UPC-based import de-dup; import merges instead of skipping priced-but-incomplete records; Channel3 manual-key UI hidden), 14 (catalog data-quality + re-link + field protection, code-only: enriched-import parser now reads the 9 dropped keys incl. `marketValueComplete`; catalog merge switched to last-enricher-wins and recomputes series-derived fields via the new shared `CatalogMapper.deriveSeriesFields`, so re-importing an improved enrich.js run upgrades existing catalog records; new `CollectionRelinkService` + Settings "Re-link collection to catalog" row refreshes owned items from the enriched catalog; `userEditedFields` marker + `DetailViewModel` stamping protect user-edited fields during re-link, with an absent-marker→fill-only migration guard; backup/restore audited as field-agnostic, no change needed), 15 (series completion + franchise grouping + auto want-list: enricher emits `setTag` (most-specific named set) and `franchiseSuggestion` (PriceCharting `pcSeries` property, cleaned; console fallback) via new POST-PROCESS 5; app gains two-level grouping — franchise/property (user-authoritative, seeded from `pcSeries`/console, no longer from the raw series tag) and named set — with per-group COMPLETE/CHERRY_PICK intent stored in `group_pref::{LEVEL}::{key}` docs (backs up via the existing denylist); `getCollectionStats` rewritten to read the catalog for true X-of-Y denominators and a computed want list (COMPLETE groups only; manual wants kept); Reports shows Option-A rows (fraction, progress bar gray for cherry-pick, intent pill, Set badge); detail screen gains a Complete/Just-this-one toggle per group; Box Number (`funkoNumber`) now preferred over title `seriesNumber` for display; compiles clean on-device), 16 (streaming `CatalogImporter` rewrite — Gson `JsonReader`, 500-record `inBatch` batches, flat memory regardless of catalog size, removes OOM risk on the larger enriched catalog; flow-invariant emit bug caught+fixed; COMPILE-PENDING on-device. `CollectionRelinkService` made golden-source — enriched catalog OVERWRITES franchise/category (re-derives genre) when non-blank, UPC stays fill-only, ownership data untouched, dead canRefresh block removed; COMPILE-PENDING. enrich.js side emits new `priceSource` flag (pricecharting|none). Specs added for next build: SPEC_variant_hierarchy (base→official-variant→user-copy 3 levels), SPEC_regional_currency (store currency code, no auto-FX, eBay-domain-pin + travel warning), TODO_app_autofill_prices (live-price fill ONLY on user add, not import/view), TODO_remote_catalog_autoupdate (small APK + manifest-pointed gzip catalog hydrate), IDEA_browse_set_wantlist (set browse + eBay-active buy row). Non-figure cleanup: pure merch removed by exact title via funko_enrich/clean_nonfigures.js (FunkO's/advent/boxes KEPT — they bundle figures)), 17 (on-device validation of S16 streaming import + golden-source relink — both confirmed working on hardware: import 16,149 updated + 9,546 added in 14 s, no OOM; data-corruption bug class found and repaired — 8 owned items had `catalog::` IDs squatting on catalog record slots, blocking those records' creation and the relink's UPC match; repair re-homes them to `funko::{upc}`; also cleaned 21 corrupted franchises, normalized ~30 franchises to canonical spellings, cleaned 15 junk retail names with metadata extraction (honoring catalog franchises where matched), pruned catalog 28,008→21,989 (stale drift dropped, owned-refs preserved), cleared 1,404 catalog + 4 owned non-figure images (pins/keychains) to placeholders; delivered as a repaired full backup restored via "Restore full". New app code: streaming `exportFullBackup` (dumps every doc incl. catalog, streams to zip — naive version OOM'd at 150 MB), streaming `forceRestoreDatabase` (Gson `JsonReader`, 500-doc batches), four-row backup/restore UI (Backup/Restore × collection/full) with scoped `Exporting` state so only the tapped row spins and scope-named confirm dialogs, `CollectionRelinkService` line-190 always-true warning fixed. enrich.js side: `isFigureImage()` filter rejects non-figure HobbyDB media (Pins_and_Badges/Keychains/Plush_Toys/PEZ/etc.) at both image-assignment points so new bad images never enter. App code built + verified on-device, then pushed))
**Next session focus:** PUSH the Session 17 app code, then resume the build roadmap.
**(1) App code pushed (S17):** the 4 app files are in the repo —
`DatabaseTransferViewModel.kt` (streaming full backup/restore + scoped Exporting
state), `SettingsScreen.kt` (four-row backup/restore UI + scope-named dialogs),
`CollectionRelinkService.kt` (line-190 warning fix). Update CHANGELOG/HANDOFF/CLAUDE
to reflect them as pushed. **(2) Shared grouping-field investigation** (carried from
S16, still the key unlock): sample the FINAL enriched data for a named set (Haunted
Mansion), a mainline line, and a Minis set to lock WHICH field holds the specific
set/property name. Unblocks relink field-mapping, variant grouping
(SPEC_variant_hierarchy — group official variants without merging Vegeta/Great Ape
Vegeta), set membership, and want-list. Design against final data with rendered UI
options for approval. **(3) priceSource reader:** add a reader for the `priceSource`
field in `toEnrichedRecord`/`EnrichedRecord`/`CatalogMapper` so the on-add
live-price fill (TODO_app_autofill_prices) can act on `'none'`. **(4) Then build, in
spec-priority order:** variant hierarchy, regional currency, on-add price fill,
remote catalog auto-update, browse-set want-list — all designed, none built.
**Release-prep (new, from S17):** the 1,404 cleared catalog images show placeholders
— a full re-enrichment with the fixed enricher (now filtering non-figure images) is
the realistic way to repopulate correct figure images at catalog scale before
release; then take a full backup and extract the final golden-master enriched file
from it. **Carried:** S15 series-completion verification; SERIES_COMPLETION_SPEC §9 +
RELINK_FIELD_PROTECTION_SPEC unit tests; HobbyDB tier-4 pricing never verified on-device.

**Resolved Session 15:** series completion now uses real catalog denominators (was owned-count); franchise/property grouping works and resolves small properties like Hocus Pocus via `pcSeries` (the umbrella console alone gave "disney"); per-group completion intent persists as backed-up user data; Box Number reaches the display; enricher emits the two grouping fields with verified rules (Haunted Mansion → its set, 19/19; franchise coverage 57→630 with pcSeries). Both repos confirmed IDENTICAL to live after push. **Late S15 fix:** `CatalogImporter` now reads `marketValueIsApproximate` from the enriched JSON — a S14 bug (parser skipped the key on a wrong comment) had it dropping the flag on insert and overwriting a stored `true` with `false` on every re-import, so ~198 approximate base-variant prices showed as exact. Import the corrected catalog after this lands.

**Resolved Session 16:** `CatalogImporter` rewritten to stream the enriched JSON
(Gson `JsonReader`, 500-record `inBatch` batches) so import memory is flat
regardless of catalog size — removes the OOM risk that grew as the catalog grew
(the app does not request `largeHeap`). All merge logic preserved; a flow-invariant
emit bug (emit from a nested suspend fun) was caught in review and fixed (commit is
non-suspend, emits inline). `CollectionRelinkService` made golden-source: enriched
catalog overwrites franchise/category (re-derives genre) when non-blank; UPC
fill-only; ownership data never touched; dead `canRefresh` marker block removed.
Both COMPILE-PENDING on-device — not yet verified. On the enricher side (funko_enrich
repo, separate push): Pass 3b crawl made verifiably complete (target-count scroll +
completeness gate + retry-on-empty — was silently truncating big sets); Pass 3 gained
UPC-first matching and funko-number-in-query; `priceCheckedAt` + `--reprice-older-than`
for stale-price refresh; `priceSource` flag for the app's on-add fill. New tools:
`pc_match_diagnostic.js`, `clean_nonfigures.js`. Golden master rebuilt: ~25,806
records, ~76% PriceCharting-priced (the unpriced tail is mostly items PC doesn't
carry — a data ceiling, not a matcher gap). All repos confirmed IDENTICAL to live
after push (raw CDN may lag; GitHub API confirms).

**Resolved Session 17:** Session 16's streaming import + golden-source relink VALIDATED on-device (import 16,149 updated + 9,546 added in 14 s, no OOM; relink overwrites franchise/category, leaves ownership intact). A long-chased bug class was root-caused via a new full-state dump: 8 owned collection items carried `catalog::` document IDs (e.g. owned "Maid" as `catalog::maid`), squatting on the IDs the catalog's own records need — so those catalog records were never created on import and the relink's UPC index (queries `type=="catalog"`) never found them. This produced the misleading symptoms (wrong/pin images, "not matched", junk names) chased separately for many rounds. Fix: re-home the 8 to `funko::{upc}` IDs, vacating the slots so a re-import creates the catalog records and the relink matches by UPC — after which relink reported 0 remaining bugs (the 75 unmatched are all legitimate: 64 not-in-catalog coverage gaps + 11 shared-UPC variants the relink correctly won't guess between). Delivered as a repaired full backup (restore via "Restore full") that also: cleaned 21 corrupted franchises, normalized ~30 franchises to canonical spellings, cleaned 15 junk retail names with metadata extraction (honoring catalog franchises where matched — caught the GotG-cameo Stan Lee), pruned the catalog 28,008->21,989 (stale import drift; owned-refs preserved), and cleared 1,404 catalog + 4 owned non-figure images to placeholders. All verified: 175/175 items preserved with prices/conditions/photos, 0 junk names, 0 Funko/unknown franchises, 0 dup IDs, 0 orphaned refs. New app code (built + verified on-device, pushed): streaming `exportFullBackup` (every doc incl. catalog, streamed to zip — the normal backup excludes catalog, which is why on-device catalog state was previously invisible; naive in-memory version OOM'd at 150 MB), streaming `forceRestoreDatabase` (Gson `JsonReader`, 500-doc batches), four-row backup/restore UI with scoped `Exporting` state and scope-named confirm dialogs, `CollectionRelinkService` line-190 always-true warning fixed. enrich.js gained `isFigureImage()` filtering non-figure HobbyDB media at both image-assignment points (see funko_enrich).

---

## Project

Android Funko Pop collectibles tracker.
- **Repo:** github.com/celticht32/FunkoDex
- **Local:** C:\Downloads\Development\FunkoDex\
- **Toolchain:** AGP 8.13.2, Gradle 8.13, Kotlin 2.0.21, Couchbase Lite 3.2.4, CameraX 1.6.1, minSdk 26, targetSdk 36

---

## Current State

Sessions 5–8 complete. Per `docs/PlayStore_Readiness_Migration_SPEC.md`'s
execution plan (Sessions A–C), the app is code-complete for Play submission
readiness: 16 KB page-size compliant, Drive auth migrated off GoogleSignIn, Photo
Picker replaces the storage-permission gallery flow, and the P3 deprecation items
(kotlinOptions, accompanist-flowlayout) are cleaned up.

Session 7 (Session D from the spec, P2) converted all database-level Couchbase
Lite calls to the Collection API (`database.defaultCollection`) across 12 files
— `database.getDocument/save/delete/createQuery/createIndex` and
`DataSource.database(db)` → `collection.X` / `DataSource.collection(col)`.
`inBatch()` correctly remains database-level (transaction wrapper, not
deprecated, not moved to Collection). Full functional test pass (see
`SESSION_D_TRACKER.md`) is deferred — code-only checkpoint per session
instructions.

Session 8 (Session E from the spec, P2) replaced `androidx.security:security-crypto`
(pinned at deprecated `1.1.0-alpha06`, no stable 1.1.0 exists) with a direct
AES-256-GCM `AndroidKeyStore` wrapper in `SecureKeyStore.kt`. Public API
unchanged (12 calling files untouched). The old `funkodex_secure_prefs`
(EncryptedSharedPreferences) file is abandoned on disk, not migrated — users
re-enter Channel3 key and re-link HobbyDB/eBay once on upgrade. `security-crypto`
dependency removed entirely from `libs.versions.toml`/`build.gradle.kts`.

Both sessions build and run clean.

### Session 14 (2026-06-20) — catalog data quality, re-link, field protection

Code changes only; compiles clean; nothing run on device this session. Files touched:
- `data/preload/CatalogImporter.kt` — `toEnrichedRecord()` now reads the 9 keys it
  was silently dropping (`marketValueComplete` + `releaseDate`, `ebayEpid`,
  `amazonAsin`, `printRun`, `publisher`, `pcSeries`, `pcDescription`).
  `mergeRecordInto()` rewritten to last-enricher-wins: overwrites every enrichment
  field the record supplies and recomputes the series-derived fields from the
  incoming tags. Preserves only handle/title/imageUrl. The narrow "Pop! Vinyl"
  category-repair block is gone (category recomputed every import).
- `data/preload/CatalogMapper.kt` — extracted `deriveSeriesFields(seriesList, title)`
  (returns `SeriesDerived`) as the single source of truth for primarySeries /
  category / isExclusive / exclusiveRetailer / isChase / seriesNumber. `mapRecord`
  refactored to call it (insert output unchanged), so insert and merge can't drift.
- `data/preload/CollectionRelinkService.kt` — NEW. Refresh owned funko:: items from
  the enriched catalog. Match by catalogRef → UPC (ambiguous UPCs dropped). Pure-
  enrichment fields refresh unconditionally (retailPrice, pricechartingUrl, funkoId,
  market value when not manual); user-editable fields (upc, franchise, category,
  imageUrl) refresh only when the userEditedFields marker is present and doesn't
  list them, else fill-only; absent marker → fill-only. Every write guarded by a
  value-changed check (idempotent). Run AFTER the enriched import.
- `ui/screens/settings/SettingsViewModel.kt` + `SettingsScreen.kt` — injects the
  service, "Re-link collection to catalog" row in the Catalog section (under Import
  Enriched Catalog), progress/result/error dialogs mirroring the enriched-import
  pattern; the two rows guard each other from concurrent runs.
- `data/model/FunkoItem.kt` — adds `userEditedFields: List<String>? = null`
  (null = pre-marker doc).
- `data/db/FunkoDexDatabase.kt` — adds `FIELD_USER_EDITED = "userEditedFields"`.
- `data/db/FunkoMapper.kt` — serializes the marker as a JSON-array string, removed
  when null (absent stays distinguishable from empty); reads it back (missing → null).
- `ui/screens/detail/DetailViewModel.kt` — `markEdited()` helper + stamping on
  `updateFranchise/updateCategory/updateUpc/updateImageUrl`.

**Backup/restore:** audited, no change. `DatabaseTransferViewModel`'s serializer is
field-agnostic (walks `doc.keys`, handles every type FunkoMapper writes), so the new
enriched fields and the marker ride through backup / restore / force-restore with no
stale schema. Verified against FunkoMapper, not assumed.

**Golden-master sequence:** re-run enrich.js → import enriched JSON (last-enricher-wins)
→ catalog at max quality. **On-device sequence:** import → re-link → owned items
upgraded without clobbering user edits. The master ships catalog-only / empty user
collection, so re-link + field protection are runtime features, not part of the master.

**Open for next session:** add the S14 unit tests (`RELINK_FIELD_PROTECTION_SPEC.md`:
FunkoMapper marker roundtrip, re-link preserve-vs-refresh, edit-screen stamping) before
ship; on-device re-link verification (see Next session focus).

### Session 12 (2026-06-19) — pricing, scanner UX, leak fixes

Code changes only; no functional/device test run this session. Files touched:
`ScannerScreen.kt`, `PreScanScreen.kt`, `DetailScreen.kt`, `PriceService.kt`,
new `util/UpcValidation.kt`, and the enriched data file (`funko_data_enriched.json`).

- **Scanner/Add UX.** Image-URL field on detail edit gained a clear (✕) button.
  "Add another" after a save now goes straight to the live camera
  (`startScanning`) instead of the Idle chooser. A third button, "Enter details
  manually," was added to the Add-to-Collection start screen (opens
  `openManualAddBlank`). The manual-add subtitle is now conditional — it only
  promises "future scans will match" when a UPC is present.
- **Manual UPC validation.** New `UpcValidation` object validates UPC-A (12) and
  EAN-13 (13) by check digit. The editable manual-UPC field shows an error when
  a non-empty entry is malformed and a check-circle + "Valid UPC" when valid; the
  Add button is blocked on a non-blank invalid UPC (blank still allowed).
- **Variant-aware pricing.** `PriceService` now appends chase/exclusive terms to
  the eBay, HobbyDB, and Channel3 *name* queries via a shared `variantSuffix`
  helper, so a valuable variant is priced against its own listings rather than
  the common version. eBay tries the variant query first and falls back to the
  broad query if it returns fewer than `MIN_VARIANT_SALES` (3) sales. The
  UPC-based lookups (UPCitemdb, Channel3-by-UPC) are unchanged — a UPC is already
  variant-specific. The eBay price band ceiling was raised $500 → $5000 so a
  correctly-priced expensive variant isn't clipped (the $3 floor stays).
- **Robustness.** All four `PriceService` HTTP calls now close their `Response`
  via `.use {}` (they previously leaked the connection on the error path).
  UPCitemdb parsing moved from regex to typed gson — and in the process dropped a
  bogus `retail` read from a `price` field that doesn't exist at item level on the
  trial plan. Channel3's regex was deliberately left as-is (tier is dormant).
- **Camera thread leak fixed.** `ScannerScreen` and `PreScanScreen` created a
  `newSingleThreadExecutor()` per camera start (per `ON_RESUME` in Scanner) and
  never shut it down. The executor is now `remember`-ed once per composable and
  shut down in `onDispose`. Behavior-neutral; removes an accumulating thread leak.
- **Enriched data cleanup.** In `funko_data_enriched.json`, 10 malformed 11-digit
  UPCs were salvaged by zero-padding to a check-digit-valid UPC-A; 57 unrecoverable
  malformed UPCs had their `upc` field removed (record kept). UPC count 9,440 → 9,383.

Verified by static reading + brace/symbol checks; **not compiled against the pinned
toolchain and not run on device.** The eBay parser was verified against a real
captured sold-listings page; the live fetch and the HobbyDB/Channel3 paths were not
(eBay/those APIs unreachable from the work environment).

Ready for: full Session 7 + 8 functional/device test pass, then Cloud Console
OAuth client confirmation, device tests T-D1–T-D5 (Drive auth), Photo Picker
smoke test (API 33+ and API 26–32), then physical device testing per
`DEVICE_TEST_PLAN.md`.

### Pre-Play Store blockers remaining
- [ ] Session 7 functional/device test pass — `SESSION_D_TRACKER.md` checklist.
      **Highest priority: backup, restore, and force-restore** (force-restore
      involves `db.close()` → wipe → `db.reopen()` → fresh `Collection` accessor)
- [ ] Session 8 device verification — confirm Channel3 API key entry,
      HobbyDB OAuth link, and eBay OAuth link all save/read correctly through
      the new AES/GCM `SecureKeyStore` on a real device (encrypt/decrypt
      round-trip via the hardware Keystore has not been device-tested)
- [ ] All unit test suites (FunkoMapperTest, CollectionStatsTest,
      FunkoLookupServiceTest, full `./gradlew test`)
- [ ] Cloud Console: confirm Android OAuth client ID (`com.funkodex` + signing SHA-1)
- [ ] Device tests T-D1–T-D5 (`docs/CredentialManager_Migration_SPEC.md` §9) —
      T-D3 (lapsed grant) is the critical one
- [ ] Photo Picker smoke test — gallery pick on API 33+ and API 26–32
      (`docs/PlayStore_Readiness_Migration_SPEC.md` §2.3)
- [ ] Community contribution Cloudflare Worker deployment (infrastructure)
- [ ] 16 KB emulator regression — re-run smoke test (CBL access patterns changed)
- [x] Device testing — enriched catalog import (full 14,314-record file),
      run twice on-device (Session 9): first run 13,585/725/4/0 (matches
      dry-run estimate), re-import after category fix 14,310/0/4/0
      (idempotent, repairs applied). D1b confirmed PASS. See
      "Result for funko_data_enriched.json" above.

### Already resolved
- [x] `android:enableOnBackInvokedCallback="true"` manifest warning
- [x] Diagnostic logs removed from FunkoLookupService and CatalogPreloader
- [x] All emulator tests passing
- [x] Enriched catalog import feature (implemented Session 4 — see section below)
- [x] 16 KB page-size compliance — Couchbase Lite 3.2.4, CameraX 1.6.1,
      ResolutionSelector migration (Session 5)
- [x] GoogleSignIn → AuthorizationClient Drive auth migration (Session 5 —
      code complete, device tests pending)
- [x] Photo Picker migration — `PickVisualMedia`, removed READ_MEDIA_IMAGES/
      READ_EXTERNAL_STORAGE (Session 6 — code complete, smoke test pending)
- [x] P3 cleanup — `kotlinOptions` → `compilerOptions`, `accompanist-flowlayout`
      → Compose Foundation `FlowRow` (Session 6). Note: `Icons.Default.ArrowBack`
      left as-is. `Icons.Default.Logout` was fixed in Session 9 —
      `Icons.AutoMirrored.Filled.Logout` resolves fine against this project's
      compose-bom; the Session 6 claim that it didn't resolve was incorrect
      (or the bom/icons-extended version has since changed). Conversely,
      `Icons.Default.TrendingUp` (used in `ReportsScreen.kt`, added Session 9)
      has NO `AutoMirrored` equivalent at all — left as `Icons.Default`, or
      swap for `Icons.Default.AttachMoney` (done) to silence the warning.
- [x] CBL Collection API migration — Session 7 (Session D, P2). 12 files
      converted: `FunkoDexDatabase`, `FunkoRepository`, `AlertRepository`,
      `ContributionRepository`, `CategoryPreferenceRepository`,
      `ImageBlobRepository`, `CatalogPreloader`, `CatalogImporter`,
      `CatalogRefreshWorker`, `FunkoLookupService`, `ConnectivityObserver`,
      `DatabaseTransferViewModel`. Code complete and compiling/running clean;
      full functional test pass pending (see `SESSION_D_TRACKER.md`)
- [x] Keystore/security-crypto migration — Session 8 (Session E, P2).
      `SecureKeyStore` rewritten as a direct AES-256-GCM `AndroidKeyStore`
      wrapper (alias `funkodex_secure_key`); `security-crypto` dependency
      removed entirely. Code complete and compiling/running clean; device
      verification of encrypt/decrypt round-trip pending

---

## Architecture

**Database:** Couchbase Lite — single `funkodex` database
- Document types: `funko` (user items), `catalog` (23k Funko catalog), `cat_pref` (category filter prefs), `system` (markers), `contribution` (pending UPC uploads)
- Backup/restore: JSON-based, blobs as base64, system+catalog docs excluded from backup
- Force restore: wipes entire database, restores user data from backup JSON, catalog re-preloads on next start

**Key invariants:**
- `funko::UUID` IDs for collection items — never use `catalog::` IDs
- `FunkoMapper.toDocument` MUST use `existing?.toMutable()` to preserve blobs
- Catalog docs and system docs are NEVER deleted by backup/restore
- `celticht.svg` path data must ALWAYS be used verbatim — never approximate

**Key files:**
```
data/model/FunkoItem.kt                    — main data model incl. FunkoVariant
data/db/FunkoDexDatabase.kt                — Couchbase singleton (nullable var for force restore reopen)
data/db/FunkoMapper.kt                     — Couchbase ↔ FunkoItem serialization
data/repository/FunkoRepository.kt
data/repository/CategoryPreferenceRepository.kt
data/preload/CatalogPreloader.kt           — seeds 23k catalog from assets/funko_data.json
data/preload/CatalogMapper.kt              — maps raw JSON → Couchbase document
network/FunkoLookupService.kt              — catalog search + Channel3 API, category-filtered
ui/screens/detail/DetailScreen.kt + DetailViewModel.kt
ui/screens/scanner/ScannerScreen.kt + ScannerViewModel.kt
ui/screens/settings/SettingsScreen.kt + DatabaseTransferViewModel.kt
ui/screens/reports/ReportsScreen.kt + ReportsViewModel.kt
ui/screens/collection/CollectionScreen.kt
```

---

## Variant System

Variants are stored as a JSON string on the parent `FunkoItem.variants: List<FunkoVariant>`.
- `FunkoVariant` has: id, note, photo (ByteArray), pricePaid, condition, dateAdded
- Variants do NOT create separate collection records — one record, N physical copies
- `isMissingOriginal = true` means: owns a variant, wants the standard version
- Missing originals appear in Want list/report as "[Name] (original)"
- "Got it!" chip at top of detail screen opens confirmation dialog, then enters edit mode to clear flag

---

## Photo System

Two separate blob fields per document:
- `thumbnailBlob` — official catalog image (downloaded by ImageBlobRepository)
- `userPhoto` — user's own camera/gallery photo (managed by PhotoRepository)

Collection card priority: `imageUrl` (remote) → error fallback to `userPhoto` → `thumbnailBlob`

---

## Backup / Restore

- Normal restore: deletes non-catalog/non-system docs, reinserts from JSON
- Force restore: closes DB, wipes entire directory, reopens fresh, inserts user data from JSON, catalog re-preloads on next start
- Backup file: `FunkoDex_backup_YYYYMMDD_HHmmss.zip` containing `funkodex_backup.json`
- `system` type docs (markers) are preserved through backup/restore — not exported, not deleted

---

## Google Drive Auth Migration (Implemented — Session 5)

`DriveBackupWorker` now uses `AuthorizationClient` (DRIVE_FILE scope) via
`data/backup/DriveAuthManager.kt`, replacing the deprecated `GoogleSignIn`/
`GoogleAccountCredential` path. Authorization-only — no Credential Manager
dependency (the original "Credential Manager" framing in earlier sessions was
half right; see `docs/CredentialManager_Migration_SPEC.md` §1 for the full
reasoning). Key facts:
- No access token is persisted — `DriveAuthManager.authorize()` is called fresh
  each use (worker run, connect, etc.); tokens are ~1h-lived and
  `AuthorizationClient` caches internally.
- `SecureKeyStore.isDriveConnected()` is the only persisted state — a boolean flag.
- UI shows "Connected · Tap to back up now" (no email — `AuthorizationResult`
  carries no identity by design).
- Worker: `NeedsConsent` → reconnect notification (id 3002), skip without retry
  (a worker can't show consent UI). 401/403 mid-flight → `clearToken()` +
  `Result.retry()`.
- Settings: connect → `connectDrive()` (may surface consent `PendingIntent` via
  `IntentSenderRequest`); disconnect → clears the flag + cancels the periodic
  worker; reconnect re-schedules it.

**Remaining:** Cloud Console OAuth client confirmation (package `com.funkodex` +
signing SHA-1) and device tests T-D1–T-D5 (`docs/CredentialManager_Migration_SPEC.md`
§9) — T-D3 (lapsed grant) is the one that catches worker-lifecycle mistakes.

---

## Enriched Catalog Import Feature (Implemented — Session 4)

### What it is
A Settings menu item — **"Import Enriched Catalog"** — that lets the user pick a
`funko_data_enriched.json` file from their device and merge it into the live Couchbase
catalog. Existing catalog docs are enriched (merge by handle, then unambiguous
normalized-title fallback); net-new records are inserted.

### As-built file map
```
app/src/main/java/com/funkodex/data/preload/
  EnrichedRecord.kt      — Deserialization target; all fields nullable except
                           `series: List<String> = emptyList()`. Populated via
                           explicit JSON-tree extraction (see Session 9 note
                           below), NOT Gson reflective binding. Unknown JSON
                           keys (hdbid, hdbChecked, franchise, funkoSection,
                           funkoNumberFromTitle) are simply not read — harmless.
  CatalogImporter.kt     — core logic (see behaviour below)
  CatalogMapper.kt       — field constants incl. FIELD_FUNKO_NUMBER, FIELD_POP_TYPE;
                           mapRecord() extended with defaulted enriched params

app/src/main/java/com/funkodex/ui/screens/settings/
  SettingsScreen.kt      — "Import Enriched Catalog" row (Catalog section), OpenDocument
                           picker, non-dismissable progress dialog, result + error dialogs
  SettingsViewModel.kt   — importEnrichedCatalog(uri) + importProgress StateFlow
```

### Importer behaviour (as built)
0. **Parse** — `JsonParser.parseString(json)` → `JsonArray` → each element mapped
   to `EnrichedRecord` via explicit `JsonObject` field extraction
   (`optString`/`optBoolean`/`optStringList` in `CatalogImporter.kt`). NOT
   `gson.fromJson(json, TypeToken<List<EnrichedRecord>>)` — that reflective path
   threw `ArrayList cannot be cast to java.lang.Void` on-device (Session 9,
   Kotlin-bytecode-specific Gson issue, root cause not fully isolated but
   bypassed entirely by the tree-parse approach).
1. **Match by handle** — `catalog::$handle` exact lookup.
2. **Title fallback** — one upfront query builds normalized-title → docId map over all
   catalog docs; titles shared by >1 doc are removed as ambiguous (a fallback merge must
   be unambiguous). Index failure degrades to handle-only matching.
3. **Merge path** — writes only non-null enriched fields (isAvailable, productUrl,
   funkoImageUrl, funkoShopId, funkoNumber, popType, retailPrice, marketValue*, pc*).
   UPC written only if doc has none. NEVER overwrites imageUrl, title, handle, seriesList.
   Merges are NOT filtered by the non-Pop regex — enriching an existing doc is harmless.
   **Category repair (Session 9):** if the existing doc's `category` field is the
   legacy bad value `"Pop! Vinyl"`, recompute from the record's series (excluding
   `"Pop! Vinyl"`/`"Pop!"`) and overwrite — self-heals docs inserted before the
   Session 9 `CatalogMapper` fix, on re-import.
4. **Insert path** —
   - Non-Pop filter (spec regex, verbatim) skips merchandise.
   - **Handle repair:** funko.com Pass-2 emits page filenames (`^\d+\.html$`, e.g.
     `91991.html`) as handles for records it could not match to HobbyDB. These are
     replaced with a title-derived slug (lowercase, non-alphanumeric runs → single
     hyphen, trimmed). Verified against the 2026-06-12 enriched JSON: 729/729 clean,
     zero collisions internally and against the 23,940 base handles.
   - **Never-clobber guard:** if a doc already exists at the insert docId, the record
     is skipped — `database.save(MutableDocument(id, map))` would otherwise replace the
     existing doc's entire content.
5. Batches of 500 inside `database.inBatch(UnitOfWork { … })`; `ImportProgress` emitted
   per batch; final emission carries `ImportResult(enriched, added, skipped, errors,
   durationMs)`.

### Result for funko_data_enriched.json (14,314 records) — CONFIRMED ON DEVICE 2026-06-13

**First run (after Session 9 parse fix):** 13,585 enriched, 725 added, 4
skipped, 0 errors, 51s. Matches the 2026-06-12 dry-run estimate
(~13,583/~725/~4).

**Second run — re-import after the category fix (idempotency + repair check):**
14,310 updated, 0 added, 4 skipped, 47s. 0-added confirms every record now
matches an existing doc by handle. The category-repair branch in the merge
path fixed the 714 docs that had been stored with `category = "Pop! Vinyl"`
on the first run.

**Verified:** `Search Catalog → "perpetua"` returns "Papa V Perpetua · Music"
(net-new funko.com record, handle `84933.html` → repaired to
`papa-v-perpetua`). This was zero results before the Session 9 category fix.

D1a (5-record file, exact counts) has NOT been run — only the full file has
been tested.

### Accepted spec behaviour (do not "fix" without discussion)
- `NON_POP_TITLE` regex is verbatim from spec and false-positives on real Pops whose
  titles contain shirt/soda/bag as descriptors — e.g. "Hulk Hogan (Tearing Shirt)",
  "LA Knight (Yellow Shirt)", "Jinu (Soda Pop)", "Bilbo Baggins in Bag-End". These 4
  are skipped, by decision (stay close to spec).
- The series-tag list in `isStandardPop()` does not include "pocket pop" — Pocket Pops
  whose titles lack the phrase pass the filter. No impact on the current file (all such
  records merge into existing docs), but a future raw dataset could insert Pocket Pops
  as standard records.

### What NOT to do (unchanged)
- Do NOT bump `CATALOG_VER` in `CatalogPreloader`
- Do NOT replace `funko_data.json` asset for existing installs
- Do NOT overwrite `imageUrl` (HobbyDB) — only write `funkoImageUrl`
- Do NOT overwrite `title`, `handle`, or `seriesList` on existing docs
- Do NOT use WorkManager — this is user-triggered

### Funko enricher tool (funko-enricher folder)
Node.js three-pass pipeline (`enrich.js`):
- Pass 1 — Kenny Chan GitHub: always `--skip-kenny` (same dataset as bundled JSON)
- Pass 2 — funko.com scrape (Puppeteer + stealth, `--max-pages 160`)
- Pass 3 — PriceCharting API (free, no key): adds `marketValueLoose`, `marketValueNew`
- Pass 4 — HobbyDB Reference Numbers (Puppeteer): adds `upc`, `funkoNumber`, retailer SKUs

Standard run:
```cmd
node enrich.js --input funko_data.json --output funko_data_enriched.json --skip-kenny --max-pages 160 --skip-pc
```

HobbyDB batches (resumable):
```cmd
node enrich.js --input funko_data_enriched.json --output funko_data_enriched.json --skip-kenny --skip-funko --skip-pc --hdb-limit 500
```

### Known data quality issues
- **funko.com page-name handles:** Pass 2 assigns `NNNNN.html` (the product page
  filename) as `handle` for records it cannot match to a HobbyDB handle — 729 such
  records in the 2026-06-12 file. The importer repairs these with a title slug, but the
  proper fix is upstream in `enrich.js` (slugify the title when no HobbyDB match).
- **Shared UPCs:** Some HobbyDB records share the same UPC (e.g. `889698491181` assigned to both `Zombie Gambit` and `Zombie She-Hulk`). User is the safety net — wrong name shows in Preview and they can cancel.
- **Duplicate handles:** Enricher's `mergeDuplicateHandles()` post-process handles ~3,200 duplicates. Import a raw dataset and the second of each pair silently overwrites the first.
- **Shared funkoNumber:** Multiple records legitimately share the same number (e.g. `#157` for Darth Vader variants). Display only — no impact on scanner.

### Community catalog distribution (deferred)
Enriched catalog should eventually be hosted on GitHub and pulled on launch rather than
bundled as a static asset. Design the full update architecture before implementing.
Key decisions needed: host location, update trigger, delta vs full, version endpoint.
**Do not implement until architecture is designed end-to-end.**

---

## Known Deferred

- Price alerts (Channel3 API) — untested
- Google Drive backup — blocked on Credential Manager migration
- Community UPC upload — needs Cloudflare Worker deployed
- Catalog refresh worker — weekly update, untested
- Check/PreScan screen — never tested (device test plan item #5)
- Enriched catalog import on-device run — code complete, fold into device test pass
- Scan-time funko.com enrichment — future, needs Item Number → URL resolution research
