# CLAUDE_STATE — FunkoDex — Session 20

STATUS: COMPLETE. Reports/want-list model redesigned, four source files changed, built + installed on-device and verified working. **Changes are NOT yet committed/pushed** — the GitHub repo (`master` @ `1a1eec9`) still holds the OLD S19 report code. Next session must apply the four S20 files before doing anything else, or commit them.

Repo: FunkoDex (branch `master`, `C:\Downloads\Development\FunkoDex\`)
Toolchain (pinned): AGP 8.13.2, Gradle 8.13, Kotlin 2.0.21, material3 1.3.0 (BOM 2024.09.00), CBL 3.2.4. package com.funkodex, minSdk 26, targetSdk 36.
Companion repo: funko_enrich (branch `main`, `C:\Downloads\Development\funko_enrich\`), Node v24, Python/Pillow. enrich.js is the catalog enricher.

## Current authoritative backup
`FunkoDex_RESET_ZERO_20260706.zip` — the clean-slate restore (want-list reset to 0). Restore via Settings > Restore full (wipe + import). Inner file `funkodex_backup.json` in a `.zip`.
State after restore: 356 owned records, 136 distinct owned franchises, want list = 0, zero stored group intents. Two data corrections baked in (see below).

## S20 WORK COMPLETED — reports/want-list redesign (code) + data reset

### The core realization
The old reports code fought the data with defensive patches (`coerceAtMost(100)`, `maxOf`, an `isTracked` fork) because it computed a completion RATIO against `totalInCatalog`, which is CATALOG-COVERAGE (count of catalog rows sharing a franchise/setTag string), NOT an authoritative set size. Completion is only meaningful for a finite, closed set. Fix: split reporting by axis.
- **Set axis (`setTag`)** = finite named sets (enrich.js derives ~13 clean set tags via SET_SUFFIXES, e.g. "Haunted Mansion Mini Vinyl Figures"). Completion is real here → ratio + bar.
- **Franchise axis** = open-ended (Twinkies, Disney) + user-invented groupings ("Disney - Christmas", "Disneyland Rides"). Catalog row count is coverage, not a target → COUNT ONLY, never a bar/%. This deleted the three defensive fields entirely.

### Four source files changed (built + installed, verified on-device; NOT committed)
1. **`FunkoItem.kt`** (SeriesSummary ~205): DELETED `isTracked` and `displayDenominator`. Replaced with `isCompletable = level == GroupLevel.SET && totalInCatalog > 0`. `completionPct` simplified — no `coerceAtMost` (a real set can't be exceeded).
2. **`ReportsScreen.kt`**: card branches on `isCompletable` — sets show ratio+bar+pill, everything else shows "N in collection" only, no bar/pill/"Not tracked" tag. Expander relabeled "Show want list" → **"Show gaps"**. Summary "Want List" tile label unchanged (it's correct).
3. **`FunkoRepository.kt`** — FOUR fixes in `getCollectionStats`:
   - `uniqueFranchises`: was `franchiseKeys.size` (catalog∪owned = ~2554). Now counts DISTINCT OWNED franchises only → **136**.
   - **UPC fallback in gaps diff**: added `ownedUpcs` set; a catalog row counts as owned if its handle OR its upc is owned. `CatalogGroupingRow` gained a `upc` field (populated from `CatalogMapper.FIELD_UPC`). Recovers ~79 catalog rows that were falsely showing as gaps (only 100 of 355 owned had a `catalogRef`; all 355 have a UPC).
   - **Default intent flipped**: `intentFor` now defaults unstored groups to **CHERRY_PICK**, not COMPLETE. Completion is opt-IN per group. (This is what stops "own 1 Star Wars → want all 317.")
   - **Want total = (Y−X)+Z**: `totalWanted` now sums `missingItems` (implied gaps) across all COMPLETE-intent groups, deduped by catalog handle across both axes, + manual wants + missing-original flags. Only COMPLETE groups carry missingItems (default cherry-pick), so this is exactly the opted-in gap set.
4. **`DetailScreen.kt`**: FIXED overlay bug — `SeriesIntentSection` (the "Collecting / Complete the set / Just this one" picker) was a Box SIBLING of `ViewContent`, so it painted ON TOP of the hero photo and stole taps. Moved it INTO ViewContent's scroll Column (after variants, before trailing Spacer); threaded the 4 intent params through ViewContent's signature. Group intent was ALREADY group-scoped (stored by franchise/setTag name, not record id) — flipping one record sets the whole group; that part was never broken.

### On-device verification (the clean-slate test, PASSED)
From want=0: tap Lilo & Stitch complete → 42 (card "Show gaps (42)"). Then tap Haunted Mansion complete → 60 (card "Show gaps (18)", 42+18, no overlap). Each opt-in adds its gap count, deduped, reconcilable against the card. Model works as designed.

### Data reset (baked into RESET_ZERO backup)
- Removed 3 stored `group_pref` docs: `Pop! Disney` (stale/inert — key is a category, matches no franchise), `Lilo & Stitch`, `Christmas`.
- **Harry Chitwood** (`funko::889698505390`): was `isOwned:false` (wrong — Chris owns it) → set `isOwned:true`. Was the 1 manual want.
- **Phineas, Ezra, Gus** (`funko::889698432337`): cleared `isMissingOriginal` (was flagged variant-of-standard; Chris owns the metallic/CHROME 3-pack and doesn't want the standard) → plain owned figure.

## OPEN / NEXT SESSION

1. **COMMIT/PUSH the four S20 files** (or re-apply them) — GitHub still has S19 report code. This is the #1 action.

2. **CATALOG COVERAGE is the root problem under everything (primary real workstream, funko_enrich side).** The gap counts (Stitch 42, Haunted Mansion 18) are INFLATED: owned figures don't UPC-match the catalog, so figures Chris owns show as gaps. Proven: owned Lilo & Stitch overlaps the catalog's 44 L&S rows on exactly **1 of 30** by UPC — and it's NOT a format problem (leading-zero normalization still yields 1). They're genuinely different figures: Chris owns mainline (Stitch with Frog #986, Stitch 626 #125) that the catalog lacks; catalog has figures (Summer Stitch #636, Stitch on Tricycle #784) Chris doesn't own. Of 255 owned-without-catalogRef, all 255 have UPCs but only 58 match a catalog UPC; 197 UPCs aren't in the catalog at all. **The fix is catalog GROWTH (harvest the mainline figures Chris owns), not re-running match on existing data.** Investigate why enrich.js's golden master omits mainline figures. This is on the Windows box; not startable from a repo read alone.

3. **Haunted Mansion set-axis mismatch.** Owned HM figures are `franchise="Haunted Mansion"`, `setTag=None`. The catalog's HM set is on the SET axis: `setTag="Haunted Mansion Mini Vinyl Figures"` (20 rows), plus a franchise "Haunted Mansion" (28 rows). So completing HM uses the franchise axis; the finite set-completion never fires because owned figures lack the setTag. If set-completion for HM is wanted, owned HM figures need the setTag applied (enrich/relink).

## DESIGN DECISIONS LOCKED THIS SESSION (carry forward)
- Want list = opt-in per group. Default cherry-pick. Want total = Σ(gaps over COMPLETE groups, both axes, deduped) + manual + missing-original.
- Completion/bars ONLY on the set axis (finite). Franchise axis is count-only.
- **Invented groupings ("Disney - Christmas", "*Rides", holiday sub-lines) are COUNT-ONLY BY DESIGN, forever.** Catalog has 0 rows under those names, so tapping Complete on them is a harmless no-op (0 gaps). Chris CONFIRMED this is desired — those groups "grow differently" (on his terms, no finite roster), so completion tracking would be meaningless. Do NOT try to map them to real catalog franchises to "enable" completion.
- Star Wars fragmentation (31 owned across 12 franchise strings — "Star Wars" 8, "The Mandalorian" 6, dupes like "Mandalorian"/"The Mandalorian" and two "Last Jedi" strings, junk "40th The Empire Strikes Back Star Wars") is UNRESOLVED. Chris was offered A (collapse all to "Star Wars") vs B (normalize dupes/junk, keep real sub-properties). No decision made. Not urgent.

## KEY CONSTRAINTS (carried)
- Catalog is GOLDEN SOURCE; fix collection to catalog. The gap-inflation is a catalog COVERAGE gap, not a collection error.
- UPC-match or human-confirm only; number-based matching is UNSAFE (Pop numbers not unique across lines).
- Doc `_id`s NEVER changed.
- Restore-size invariant: resize blobs to ≤400px JPEG q85 after every harvest (`resize_blobs.py`), BEFORE restore — CBL silently drops multi-MB records on individual save.
- Verify version-sensitive symbols against pinned versions + in-repo usage; never guess.
- thumbnailBlob export/import schema: `{_type:"blob", contentType:"image/jpeg|png|webp", data:"<base64 NO_WRAP>"}`.
