---
project: FunkoDex / funko_enrich
branch: master
updated: 2026-09-13
last_session: 24 (code committed + pushed; NOT verified on device)
---

## Current Focus
S24 was a bug-hunt session driven by real device data, not a feature session. Three
data-quality/robustness decisions landed (DEC-031/032/033) plus a full-tree code scan.
Everything is committed and pushed (`master`, commit after `f13c3de0`) but **none of it
has been verified on hardware**. Verify first, then resume the build roadmap.

## Active Tasks
- [ ] VERIFY S24 ON DEVICE. Rebuild, run `testDebugUnitTest` (expect 31, was 24), then:
      open a catalog figure you do NOT own — expect no write, no DEC-018 warning, and a
      DEBUG "not persisting onto non-collection record" line; open a corrupted one —
      expect re-home AND the new "deleted the corrupted collection document" line, with
      owned count staying 373 (it went 374 when the guard still copied); search "BTS" —
      expect the degenerate-title records to appear.
- [ ] Repair script for the 11 `catalog::`-id documents + the Gotta-the-hutt/Grogu UPC
      transposition (three-way swap through a temp id). Standalone, NOT an in-app
      migration. Until the backup is repaired, every force-restore re-injects all 11.
- [ ] funko_enrich side: expand `v` -> "V (BTS)" and `l` -> "L (Death Note)" in the base
      catalog. Those two are the ONLY degenerate titles in the shipped asset.
- [ ] Then the build roadmap, unchanged from S17: lock the grouping field, add the
      `priceSource` reader, then build in spec-priority order.

## Blockers
- Build-roadmap items still gated on the grouping-field lock + priceSource reader.
- The 23 mislinked `catalogRef` rows (the "wrong picture" class) need a disambiguation
  rule for duplicate-UPC clusters — a DEC-026 judgement call, not a patch.

## Context (max 5)
- DEC-032 root-caused the `catalog::` corruption at last: it was `refreshPrices` writing
  back from `loadItem`, NOT `toggleOwned`. Guarding one caller can't hold an invariant —
  the guard now lives at `FunkoRepository.saveItem`, the single write boundary.
- CATALOG_VER is ALREADY "6" and the shipped asset holds 26,654 records (verified by
  decompressing it). The device showing 30,179 is force-restored backups bypassing the
  preloader — do NOT "fix" this with a version bump.
- Backups are the carrier for the 11 corrupt documents. The runtime guard heals them as
  you open each figure; a restore puts them all back.
- Catalog-wide there are 5,272 duplicate-UPC clusters (12,106 rows, 40%), mostly
  legitimate chase/variant editions sharing a base UPC. This caps UPC-based relink and is
  why 5 owned items can never auto-link (DEC-026 territory).
- Toolchain VERIFIED unchanged: AGP 8.13.2 / Gradle 8.13 / Kotlin 2.0.21 / CBL 3.2.4 /
  Compose BOM 2024.09.00 / minSdk 26 / targetSdk 36. Coil is 2.7.0 — it DOES support
  ByteArray (added 2.1.0); an earlier claim that it did not was wrong.

## Repo state (S24)
- 172 tracked files (was 163). `LICENSE` (MIT) and `.gitattributes` added; three dead
  `.gitignore` rules removed — `CatalogPreloader.kt` and the catalog asset were both
  already TRACKED, so those rules were inert but actively misleading.
- Two findings documents at root: `FunkoDex_DQ_findings_20260913.md` (images/links) and
  `FunkoDex_code_scan_20260913.md` (full-tree scan, including the negative results).

## Next Session
Verify S24 on device before anything else — nothing in it has run on hardware. Then the
repair script, then the V/L source fix, then back to the roadmap.
