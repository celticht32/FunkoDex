# FunkoDex — Session Handoff
**Date:** 2026-06-12
**Sessions completed:** 1 (initial build), 2 (enricher/catalog import), 3 (UI/variant/photo/backup), 4 (enriched catalog import implementation + handle repair), 5 (16 KB page-size compliance + Drive auth migration)
**Next session focus:** Cloud Console OAuth client verification + device tests T-D1–T-D5 (Drive auth), then physical device testing (DEVICE_TEST_PLAN.md, incl. on-device enriched import run)

---

## Project

Android Funko Pop collectibles tracker.
- **Repo:** github.com/celticht32/FunkoDex
- **Local:** C:\Downloads\Development\FunkoDex\
- **Toolchain:** AGP 8.13.2, Gradle 8.13, Kotlin 2.0.21, Couchbase Lite 3.2.4, CameraX 1.6.1, minSdk 26, targetSdk 36

---

## Current State

Session 5 complete: 16 KB page-size compliance (P0) verified via Analyze APK on
release build — all `.so` files across all ABIs report 16 KB alignment, including
the previously-contested ML Kit barcode `libbarhopper_v3.so` (no fallback needed).
Smoke-tested on a 16 KB-page emulator with no errors. Drive auth migration (P1)
also complete and building/running clean — see `docs/CredentialManager_Migration_SPEC.md`.

Ready for: Cloud Console OAuth client confirmation, device tests T-D1–T-D5, then
physical device testing per `DEVICE_TEST_PLAN.md`.

### Pre-Play Store blockers remaining
- [ ] Cloud Console: confirm Android OAuth client ID (`com.funkodex` + signing SHA-1)
- [ ] Device tests T-D1–T-D5 (`docs/CredentialManager_Migration_SPEC.md` §9) —
      T-D3 (lapsed grant) is the critical one
- [ ] Photo Picker migration (P1 — `docs/PlayStore_Readiness_Migration_SPEC.md` §2.3,
      READ_MEDIA_IMAGES policy)
- [ ] Community contribution Cloudflare Worker deployment (infrastructure)
- [ ] Device testing results (may surface new bugs) — now includes on-device enriched import run

### Already resolved
- [x] `android:enableOnBackInvokedCallback="true"` manifest warning
- [x] Diagnostic logs removed from FunkoLookupService and CatalogPreloader
- [x] All emulator tests passing
- [x] Enriched catalog import feature (implemented Session 4 — see section below)
- [x] 16 KB page-size compliance — Couchbase Lite 3.2.4, CameraX 1.6.1,
      ResolutionSelector migration (Session 5)
- [x] GoogleSignIn → AuthorizationClient Drive auth migration (Session 5 —
      code complete, device tests pending)

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
  EnrichedRecord.kt      — Gson target; all fields nullable. Unknown JSON keys
                           (hdbid, hdbChecked, franchise, funkoSection,
                           funkoNumberFromTitle) are ignored by Gson — harmless.
  CatalogImporter.kt     — core logic (see behaviour below)
  CatalogMapper.kt       — field constants incl. FIELD_FUNKO_NUMBER, FIELD_POP_TYPE;
                           mapRecord() extended with defaulted enriched params

app/src/main/java/com/funkodex/ui/screens/settings/
  SettingsScreen.kt      — "Import Enriched Catalog" row (Catalog section), OpenDocument
                           picker, non-dismissable progress dialog, result + error dialogs
  SettingsViewModel.kt   — importEnrichedCatalog(uri) + importProgress StateFlow
```

### Importer behaviour (as built)
1. **Match by handle** — `catalog::$handle` exact lookup.
2. **Title fallback** — one upfront query builds normalized-title → docId map over all
   catalog docs; titles shared by >1 doc are removed as ambiguous (a fallback merge must
   be unambiguous). Index failure degrades to handle-only matching.
3. **Merge path** — writes only non-null enriched fields (isAvailable, productUrl,
   funkoImageUrl, funkoShopId, funkoNumber, popType, retailPrice, marketValue*, pc*).
   UPC written only if doc has none. NEVER overwrites imageUrl, title, handle, seriesList.
   Merges are NOT filtered by the non-Pop regex — enriching an existing doc is harmless.
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

### Expected result for funko_data_enriched.json (2026-06-12, 14,314 records)
13,583 enriched · 2 title-fallback merges · ~725 added · ~4 skipped.
On-device run still pending (add to device test pass).

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
