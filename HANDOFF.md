# FunkoDex — Session Handoff
**Date:** 2026-06-04
**Session:** 3 (UI fixes, backup/restore overhaul, photo/variant system, reports redesign, settings cleanup)
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

### Already resolved this session
- [x] `android:enableOnBackInvokedCallback="true"` manifest warning
- [x] Diagnostic logs removed from FunkoLookupService and CatalogPreloader

---

## Architecture

**Database:** Couchbase Lite — single `funkodex` database
- Document types: `funko` (user items), `catalog` (23k Funko catalog), `cat_pref` (category filter prefs), `system` (markers), `contribution` (pending UPC uploads)
- Backup/restore: JSON-based, blobs as base64, system+catalog docs excluded from backup

**Key files:**
```
data/model/FunkoItem.kt          — main data model incl. FunkoVariant
data/db/FunkoDexDatabase.kt      — Couchbase singleton, lazy → nullable var for force restore
data/db/FunkoMapper.kt           — Couchbase ↔ FunkoItem serialization
data/repository/FunkoRepository.kt
data/repository/CategoryPreferenceRepository.kt
data/preload/CatalogPreloader.kt — seeds 23k catalog from assets/funko_data.json
network/FunkoLookupService.kt    — catalog search + Channel3 API
ui/screens/detail/DetailScreen.kt + DetailViewModel.kt
ui/screens/scanner/ScannerScreen.kt + ScannerViewModel.kt
ui/screens/settings/SettingsScreen.kt + DatabaseTransferViewModel.kt
ui/screens/reports/ReportsScreen.kt + ReportsViewModel.kt
ui/screens/collection/CollectionScreen.kt
```

**Important invariants:**
- `funko::UUID` IDs for collection items — never use catalog:: IDs
- `FunkoMapper.toDocument` MUST use `existing?.toMutable()` to preserve blobs
- Catalog docs and system docs are NEVER deleted by backup/restore
- `celticht.svg` path data must ALWAYS be used verbatim — never approximate

---

## Variant System

Variants are stored as a JSON string on the parent `FunkoItem.variants: List<FunkoVariant>`.
- `FunkoVariant` has: id, note, photo (ByteArray), pricePaid, condition, dateAdded
- Variants do NOT create separate collection records
- `isMissingOriginal = true` means: owns variant, want the standard version
- Missing originals appear in Want list/report as "[Name] (original)"
- "Got it!" chip at top of detail screen clears the flag and enters edit mode

---

## Photo System

Two separate blob fields per document:
- `thumbnailBlob` — official catalog image (downloaded by ImageBlobRepository)
- `userPhoto` — user's own camera/gallery photo (managed by PhotoRepository)

Collection card priority: `imageUrl` (remote) → error fallback to `userPhoto` → `thumbnailBlob`

---

## Backup / Restore

- Normal restore: deletes non-catalog/non-system docs, reinserts from JSON
- Force restore: closes DB, wipes entire directory, reopens fresh, inserts from JSON, catalog re-preloads on next start
- Backup file: `FunkoDex_backup_YYYYMMDD_HHmmss.zip` containing `funkodex_backup.json`

---

## Google Drive / Credential Manager Migration

Current `DriveBackupWorker` uses deprecated `GoogleSignIn` API.
Migration path:
1. Replace `GoogleSignIn` with `CredentialManager` + `GetGoogleIdOption`
2. Update `DriveBackupWorker` to use the new auth token
3. Test automatic daily backup worker
Reference: https://developer.android.com/identity/sign-in/credential-manager-siwg

---

## Known Deferred

- Price alerts (Channel3 API) — untested
- Google Drive backup — blocked on Credential Manager migration
- Community UPC upload — needs Cloudflare Worker deployed
- Catalog refresh worker — weekly update, untested
- Check/PreScan screen — never tested (device test plan item #5)
