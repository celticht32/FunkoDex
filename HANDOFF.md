# FunkoDex — Session Handoff
**Date:** 2026-06-04
**Sessions completed:** 1 (initial build), 2 (enricher/catalog import), 3 (UI/variant/photo/backup)
**Next session focus:** Device testing results + GoogleSignIn → Credential Manager migration

---

## Project

Android Funko Pop collectibles tracker.
- **Repo:** github.com/celticht32/FunkoDex
- **Local:** C:\Downloads\Development\FunkoDex\
- **Toolchain:** AGP 8.13.2, Gradle 8.13, Kotlin 2.0.21, Couchbase Lite 3.2.1, minSdk 26, targetSdk 35

---

## Current State

All emulator testing complete. Ready for physical device testing.
See `DEVICE_TEST_PLAN.md` for the 8 on-device tests to run.

### Pre-Play Store blockers remaining
- [ ] GoogleSignIn → Credential Manager migration (significant — own session)
- [ ] Community contribution Cloudflare Worker deployment (infrastructure)
- [ ] Device testing results (may surface new bugs)
- [ ] Enriched catalog import feature (see section below — spec complete, not implemented)

### Already resolved
- [x] `android:enableOnBackInvokedCallback="true"` manifest warning
- [x] Diagnostic logs removed from FunkoLookupService and CatalogPreloader
- [x] All emulator tests passing

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

## Google Drive / Credential Manager Migration

Current `DriveBackupWorker` uses deprecated `GoogleSignIn` API.
Migration path:
1. Replace `GoogleSignIn` with `CredentialManager` + `GetGoogleIdOption`
2. Update `DriveBackupWorker` to use the new auth token
3. Test automatic daily backup worker
Reference: https://developer.android.com/identity/sign-in/credential-manager-siwg

---

## Enriched Catalog Import Feature (Spec Complete — Not Implemented)

### What it is
A Settings menu item — **"Import Enriched Catalog"** — that lets the user pick a
`funko_data_enriched.json` file from their device and merge it into the live Couchbase
catalog. Handles both:
- **New fields on existing catalog docs** (upsert / merge by handle)
- **Net-new records** not in the original HobbyDB/Kenny Chan dataset (insert)

### Files to create/touch
```
app/src/main/java/com/funkodex/data/preload/
  EnrichedRecord.kt      ← new data class
  CatalogImporter.kt     ← new, core upsert + insert logic
  CatalogMapper.kt       ← add new field constants

app/src/main/java/com/funkodex/ui/screens/settings/
  SettingsScreen.kt      ← menu item + file picker + progress dialog
  SettingsViewModel.kt   ← importEnrichedCatalog() + importProgress flow
```

### Upsert logic
Match by `handle` exact match first, then normalized title match. Low match rate (~5%)
expected between funko.com and HobbyDB titles due to different prefix formats.
```kotlin
val existing = database.getDocument("catalog::${record.handle}")
if (existing != null) {
    val mutable = existing.toMutable()
    // only write new fields — never overwrite imageUrl, title, handle, seriesList
    record.funkoImageUrl?.let  { mutable.setString(FIELD_FUNKO_IMAGE_URL, it) }
    record.retailPrice?.let    { mutable.setDouble(FIELD_RETAIL_PRICE, it) }
    record.popType?.let        { mutable.setString(FIELD_POP_TYPE, it) }
    record.funkoNumber?.let    { mutable.setString(FIELD_FUNKO_NUMBER, it) }
    // ... other enriched fields
    database.save(mutable)
} else {
    // full insert via CatalogMapper.mapRecord(...)
}
```

### Non-Pop filter — add to CatalogImporter
```kotlin
private val NON_POP_TITLE = Regex(
    "\\b(tee|shirt|backpack|bag|wallet|keychain|soda|mystery minis|wacky wobbler|" +
    "funkoverse|bitty pop|pocket pop|pin set|enamel pin|dorbz|hikari|rock candy|" +
    "fabrikations|paka paka|plush|mug|cup|cushion)\\b",
    RegexOption.IGNORE_CASE
)
private fun isStandardPop(record: EnrichedRecord): Boolean {
    if (NON_POP_TITLE.containsMatchIn(record.title ?: "")) return false
    val series = record.series?.map { it.lowercase() } ?: return true
    return listOf("pop! tees","loungefly","mystery minis","wacky wobblers",
        "vinyl soda","funkoverse","dorbz","rock candy","hikari","fabrikations")
        .none { tag -> series.any { it.contains(tag) } }
}
```

### Progress / result data classes
```kotlin
data class ImportProgress(val processed: Int, val total: Int, val enriched: Int, val added: Int)
data class ImportResult(val enriched: Int, val added: Int, val skipped: Int, val errors: Int, val durationMs: Long)
```

### Settings wiring
```kotlin
val filePicker = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument()
) { uri -> uri?.let { viewModel.importEnrichedCatalog(it) } }

SettingsItem(
    title    = "Import Enriched Catalog",
    subtitle = "Load enriched funko.com data from a JSON file",
    onClick  = { filePicker.launch(arrayOf("application/json")) }
)
```

### What NOT to do
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
- Enriched catalog import — spec complete, not implemented
- Scan-time funko.com enrichment — future, needs Item Number → URL resolution research
