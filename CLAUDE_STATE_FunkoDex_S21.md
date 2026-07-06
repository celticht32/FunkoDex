# CLAUDE_STATE — FunkoDex — Session 21

STATUS: COMPLETE. Owned↔catalog linking corrected (100→234), manual-search junk filter + name-based pre-purchase check implemented and CONFIRMED BUILT/INSTALLED on device. Enrichment workstream formally CLOSED as a dead end (see below). Supersedes the S19 "next workstream: enrichment run" direction.

Repo: FunkoDex (branch `master`, `C:\Downloads\Development\FunkoDex\`)
Toolchain (pinned): AGP 8.13.2, Gradle 8.13, Kotlin 2.0.21, material3 1.3.0 (BOM 2024.09.00), CBL 3.2.4. package com.funkodex, minSdk 26, targetSdk 36.
Companion repo: funko_enrich (branch `main`), Node v24, Python/Pillow.

## Current authoritative backup
`FunkoDex_LINKED_20260706_linked.zip` — restore via Settings > Restore full (wipe + import). Inner file `funkodex_backup.json`, 26,878 docs preserved.
State: 356 owned figures, of which 234 linked to a catalog row (`catalogRef`), 122 standalone (no catalog row exists for them — normal per DEC-020). Prior good backup: `FunkoDex_REPAIRED_20260706_050617.zip` (S19, pre-linking, 100 linked).

## S21 WORK COMPLETED

### 1. Enrichment / catalog-completeness chase — CLOSED as a dead end (measured)
The S19 plan was "run enrichment, let it overwrite inferred franchises/categories with real catalog data." S20/S21 measured that this does not pay off and formally retired it:
- A full Pass 3 (PriceCharting) + Pass 5 run over ~11,423 candidates added **8 UPCs and 10 prices** across 3,240 processed records. Pass 3 re-searches PriceCharting by NAME and correctly rejects most poisoned records as different figures (they are variants/prototypes/box-sets/exclusives). Net: enrichment is effectively done.
- Catalog coverage: 30,047 records (funko_enrich golden), ~68% with UPC, ~62% with franchise. The no-UPC tail is unresolvable by any scraper and is mostly figures Chris does not own.
- HobbyDB slug-guessing produces 404s (confirmed via error-page HTML: `errors.hobbydb.com`); HobbyDB slugs cannot be deterministically reconstructed from store handles.
- **DO NOT run enrich.js again expecting owned-match improvement.** The problem was never catalog completeness — it was owned↔catalog *linking* (item 2) and, structurally, the architecture (DEC-020).
- Data-safety rule surfaced and carried: enrich.js post-processes ALWAYS write to `opts.output` (default `funko_data_enriched.json`) regardless of `--input`; one run clobbered 30,047→13,578 records. ALWAYS pass explicit `--input` AND `--output`. Recovery file: `funko_data_enriched_RESTORE.json` (30,047).
- Rejected alternatives (documented, not pursued): Android emulator + Funko app to harvest UPCs (app has no bulk import/export; and the unmatched owned figures already HAVE UPCs — missing UPCs was never the blocker); hobbyDB Premium CSV export (would fix today's gap only, not future ones — the snapshot problem DEC-020 addresses).

### 2. Owned↔catalog linking pass — DONE (100 → 234)
Root finding: the reports looked wrong because owned figures did not *link* to catalog rows, not because the catalog was incomplete. Deterministic pass over the backup:
- Link unlinked owned figures by UNIQUE UPC (no name gate — catalog and collection legitimately name the same figure differently, e.g. "Donald Duck on the Casey Jr. Circus" == "Donald Duck ToyZilla Signed Edition", same UPC, Chris-confirmed) then by UNIQUE exact name.
- Applied **134 new links** (67 UPC + 67 name). `catalogRef` = the catalog row's `_id` (format `catalog::{handle}`). All 26,878 docs preserved.
- Deeper fuzzy pass on the remaining 122 unlinked, UPC-verified: only **1** was genuinely linkable ("Josh w/Piano Outfit" → `catalog::josh-baskin-piano-outfit`, UPC-confirmed). All STRONG/NEAR name-matches were UPC-verified as DIFFERENT figures (e.g. owned "Bride Of Frankenstein" UPC `889698928847` matches neither catalog Bride row — three distinct Universal Monsters figures with three UPCs). Ceiling for this catalog = 235/356. Remaining ~121 need a bigger catalog, not scraping (DEC-020).
- Deliverables (in outputs, for reference): `FunkoDex_LINKED_20260706_linked.zip`, `LINK_REPORT.json`, `FunkoDex_Unlinked_Review.xlsx` (122 rows, tiered STRONG/NEAR/WEAK/NONE with per-row UPC verdict).
- NOTE: the Josh link is NOT yet folded into the shipped backup — see OPEN #1.

### 3. Manual-search junk filter — DONE, built & installed
`FunkoLookupService.searchByName` now drops identify-only dead-end rows (per DEC-021). Filter keeps a result if it has ANY of {upc, seriesNumber, pricechartingUrl, franchise}; drops rows with none. Validated on catalog data: **19,891 kept, 6,360 dropped, ZERO UPC-bearing rows lost.** Nothing deleted from the DB — rows remain, filtered from this surface only.
- File: `app/src/main/java/com/funkodex/network/FunkoLookupService.kt` (the `searchByName` body, category-filter block).

### 4. Name-based pre-purchase check (Option A) — DONE, built & installed
Added a "No barcode? Search by name" fallback to the `prescan` screen for loose figures (per DEC-022). Name → matching catalog figures (junk-filtered) → each badged OWNED (green ✓) / WANTED (orange ★) / NOT_IN_COLLECTION (grey). Read-only, no add flow (matches prescan's purpose). Badge join: owned collection item whose `catalogRef` == picked figure id, UPC fallback.
- `app/src/main/java/com/funkodex/data/repository/FunkoRepository.kt` — added `findCollectionItemForCatalog(catalogId, upc)` (catalogRef query, UPC fallback; follows existing QueryBuilder patterns).
- `app/src/main/java/com/funkodex/ui/screens/prescan/PreScanViewModel.kt` — added `PreScanState.NameSearch`, `PreScanMatch`, `enum OwnStatus`, and `openNameSearch`/`closeNameSearch`/`onNameQueryChanged`/`submitNameSearch`.
- `app/src/main/java/com/funkodex/ui/screens/prescan/PreScanScreen.kt` — added the "Search by name" button (scanning state), `NameSearchPanel`, `PreScanMatchRow`. Imports added: `androidx.compose.foundation.lazy.LazyColumn`, `.items`.

### 5. Tests — added to close out items 3 & 4
- `FunkoLookupServiceTest.kt` — added cases asserting the actionability predicate used by the `searchByName` filter (keep when any of upc/seriesNumber/pricechartingUrl/franchise present; drop when none; never drop a UPC-bearing row).
- `PreScanBadgeLogicTest.kt` (new) — asserts the OwnStatus mapping (null collection item → NOT_IN_COLLECTION; isOwned=true → OWNED; isOwned=false → WANTED).
Note: these are pure-logic unit tests (no CBL/Android runtime). On-device verification of the prescan name-search UI: DONE (2026-07-06) — button surfaces, results are actionable-only, all three ownership badges resolve correctly, close returns to scanning. Test plan A10b records it.

## OPEN / NEXT SESSION

1. **Fold the Josh w/Piano Outfit link into the shipped backup.** The 234-link backup shipped; the +1 UPC-verified link (`catalog::josh-baskin-piano-outfit`) was found afterward and not yet applied. One-line addition to the linking pass, re-emit the zip. Low priority (1 figure).
2. **Wire opportunistic relink (DEC-020).** Verify `CollectionRelinkService` / `CatalogRefreshWorker` actually link-on-refresh by UPC when a catalog row appears for a previously-standalone owned figure. Not yet audited this session.
3. **Optional Option-B upgrade for the name-check:** make a `PreScanMatchRow` tap open detail / add-to-collection. Currently inert by design (DEC-022).
4. **Image-vector-search (future, big):** the 6,360 filtered no-UPC rows (DEC-021) are the coverage case for camera→embed→top-K→pick identification of loose oddball figures. Design sketched only (top-K human-in-the-loop picker, not single-answer classifier — Funko's low visual variance makes confident auto-ID unreliable). Not started.

## CARRIED FROM S19 (still open, unchanged)
- Cost Breakdown label clarity (approved, not written): "Total Retail Value"→"Total Retail (MSRP)", "Above Retail"→"Paid Above Retail", + info popup with 4 definitions. ReportsScreen card ~195-225.
- Report code fixes (completion math + most-common category derivation): confirm all three S19 files landed and compiled — the FunkoRepository category fix was suspected not to have applied. Re-verify against current repo.
- Casey Jr. Mickey/Donald franchise mismatch — Chris hasn't decided to pair.
- Manual edge records (Chris on-device): Easter Stitch #1533, Elvira (on Red Sofa), Mad Sweeney, "Stitch with Mood Chart" variants:127 anomaly.

## KEY CONSTRAINTS (carried)
- Owned records are self-sufficient; catalog link is opportunistic (DEC-020). Do not chase 100% link coverage.
- No-UPC-no-identity catalog rows are filtered, never deleted (DEC-021).
- Catalog is golden source; fix collection to catalog. Records with NO catalog UPC match use documented inference.
- Number-based catalog matching is UNSAFE (Pop numbers not unique across lines). UPC-match or human-confirm only.
- Doc `_id`s NEVER changed. `catalogRef` = the catalog row's `_id` (`catalog::{handle}`).
- Verify version-sensitive symbols against pinned versions + in-repo usage; never guess.
