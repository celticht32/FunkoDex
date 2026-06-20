# Series Completion — App + Enricher Change Handoff

Spec: SERIES_COMPLETION_SPEC_v0.2.md. This package implements the data + UI layer
in the FunkoDex app and the enricher field emission in funko_enrich.

Status: code-complete for the data layer, the report UI (Option A), and the
detail-screen intent toggle. NOT compiled against the pinned toolchain (this
environment has no kotlinc); brace/paren balance and version-pinned symbol
presence were checked statically. "Compiles" is your local confirmation.

## What this delivers

1. Real "X of Y" series completion, sourced from the catalog (not owned-count).
2. Two grouping levels: FRANCHISE/property (Hocus Pocus) and named SET
   (Haunted Mansion Mini Vinyl Figures).
3. Per-group completion intent (Complete / Cherry-pick), default COMPLETE,
   stored as user data that backs up.
4. Auto want-list (the missing figures) shown inline per completing group.

## Enricher (funko_enrich) — apply first

- `enrich.js` — adds POST-PROCESS 5 emitting two fields per record:
  - `setTag` — most-specific named set (13 clean sets verified; Haunted Mansion
    resolves all 19 records).
  - `franchiseSuggestion` — property-specific PriceCharting console only
    (umbrella consoles like disney/animation omitted, so Hocus Pocus is NOT
    mislabeled — the user assigns it).
- Validated against the live 12,176-record output. Node --check passes.
- Re-run the enricher to populate the fields, then import the enriched JSON.

## App (FunkoDex) — destination paths

NEW files:
- app/src/main/java/com/funkodex/data/model/GroupModels.kt
- app/src/main/java/com/funkodex/data/util/ConsoleFranchise.kt
- app/src/main/java/com/funkodex/data/repository/GroupPrefRepository.kt

CHANGED files (drop-in replacements):
- app/src/main/java/com/funkodex/data/model/FunkoItem.kt
- app/src/main/java/com/funkodex/data/db/FunkoDexDatabase.kt
- app/src/main/java/com/funkodex/data/db/FunkoMapper.kt
- app/src/main/java/com/funkodex/data/preload/EnrichedRecord.kt
- app/src/main/java/com/funkodex/data/preload/CatalogMapper.kt
- app/src/main/java/com/funkodex/data/preload/CatalogImporter.kt
- app/src/main/java/com/funkodex/data/preload/CollectionRelinkService.kt
- app/src/main/java/com/funkodex/network/FunkoLookupService.kt
- app/src/main/java/com/funkodex/data/repository/FunkoRepository.kt
- app/src/main/java/com/funkodex/ui/screens/reports/ReportsScreen.kt
- app/src/main/java/com/funkodex/ui/screens/detail/DetailViewModel.kt
- app/src/main/java/com/funkodex/ui/screens/detail/DetailScreen.kt

## Key implementation notes

- `FunkoItem.franchise` is now the user-authoritative property field. It is no
  longer seeded from the catalog "series" tag (the O-5 fix). On scan it is seeded
  from `franchiseSuggestion` / the property-specific console, else left blank.
  It already routes through `markEdited()`, so user edits are protected on re-link.
- `FunkoItem.setTag` is pure-enrichment: refreshed from the catalog on re-link.
- Group intent lives in `group_pref::{LEVEL}::{groupKey}` docs. Backup/restore
  needs NO change — the export denylist (type != catalog/system) already includes
  it; the field-agnostic serializer carries setTag automatically.
- `getCollectionStats()` now scans the catalog (`loadCatalogGroupingRows`),
  builds franchise + set denominators, diffs against owned (by catalogRef handle),
  and applies intent. CHERRY_PICK groups contribute 0 wants. A `getWantList()`
  helper is also provided (aggregated, de-duped to most-specific group) but the
  report UI uses the existing inline per-card want list.
- Report row = Option A: fraction "owned/total", progress bar (gray for
  cherry-pick), intent pill, "Set" badge for named sets. Want-list rows show
  seriesNumber only when present (name-only otherwise — no fabrication).
- Detail screen gains a "Collecting" section with Complete / Just-this-one chips
  per franchise and per set.

## Deferred (NOT in this package)

- First-scan auto-prompt (ask Complete/Cherry-pick on the first add of a new
  franchise). The capability is fully available via the detail screen; the
  scanner auto-prompt was deferred because it touches 5 save sites in
  ScannerViewModel and is higher regression risk without compile testing. Add it
  as a focused follow-up: on saveItem with isOwned=true, if the item's franchise
  is non-blank and groupPrefs.hasIntent(FRANCHISE, franchise) is false, show a
  dialog; re-ask on dismissal (O-3).
- Unit tests from the spec (§9). Recommended before ship since this is
  schema-touching.

## Verify-against-live before merge

Per the project workflow: after you drop these in and push, re-fetch a fresh
codeload tarball and diff to confirm parity. Run ./gradlew test and a device pass
covering the spec's §9 device items (first set the franchise on an item, set
Cherry-pick, confirm its missing figures leave the want list; confirm a
COMPLETE set shows "X of Y" and the missing figures appear).
