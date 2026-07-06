# CLAUDE_STATE — FunkoDex — Session 19

STATUS: COMPLETE (session closed for length/stability). Data cleanup done and restored; report-code fixes staged but NOT confirmed compiled-in. Next workstream: enrichment run.

Repo: FunkoDex (branch `master`, `C:\Downloads\Development\FunkoDex\`)
Toolchain (pinned): AGP 8.13.2, Gradle 8.13, Kotlin 2.0.21, material3 1.3.0 (BOM 2024.09.00), CBL 3.2.4. package com.funkodex, minSdk 26, targetSdk 36.
Companion repo: funko_enrich (branch `main`, `C:\Downloads\Development\funko_enrich\`), Node v24, Python/Pillow.

## Current authoritative backup
`FunkoDex_REPAIRED_20260706_050617.zip` — restore via Settings > Restore full (wipe + import).
State: 356 owned, 350 blobs, 0 blank franchise, 0 junk "Funko" franchise, 0 blank category, 0 dup ids, 0 catalog:: on owned, 0 records >500KB (restore-safe).
Zip the inner `.json` as `funkodex_backup.json` inside a `.zip` to restore.

## S19 WORK COMPLETED

### 1. Image harvest restore bug — ROOT-CAUSED and FIXED
- Symptom: 66 harvested records restored with BOTH imageUrl AND thumbnailBlob empty/null; all other records fine.
- Root cause (proven via device backup diff): harvested images were full-resolution — one was 8.9MB, several 1-2MB. CBL's individual-save path (LARGE_DOC_BYTES=64KB threshold in DatabaseTransferViewModel) fails on multi-MB docs and drops the whole record's fields. NOT a format/schema/priority issue (those were ruled out).
- Fix: resized all oversized blobs to max 400px JPEG q85. 40.6MB -> 2.0MB. Largest record now 106KB.
- Prevention tool: `resize_blobs.py` (standalone, Pillow). Run after every harvest, before restore. Workflow is now harvest -> blob -> RESIZE -> restore.
- App display priority (CollectionScreen.kt ~189): imageUrl first, thumbnailBlob fallback ONLY if imageUrl empty. (This is why a dead imageUrl blocks the blob — relevant if future images misbehave.)

### 2. Franchise cleanup — 51 junk "Funko" + blanks -> 0
Title-inference passes (NOT catalog-sourced; the 61 affected records have NO catalog UPC match). Key decisions:
- Holiday sub-line convention: format `Property - Holiday` (spaces around dash), e.g. "Looney Tunes - Halloween", "Lilo & Stitch - Christmas". Rationale: holiday figures are not mainline; grouped by holiday deliberately. This is Chris's ORGANIZING choice.
- All NBC (incl. Santa Jack, Santa Claus) -> "Disney - Christmas". A Christmas Carol ghosts -> "Christmas". Universal Monsters / Patchwork set -> left as "Universal Monsters". Dave'Acula + Mummy Stuart (#966/#967, both Minions) -> "Minions - Halloween". Hanukkah Stitch -> "Lilo & Stitch - Hanukkah". Jasmine -> "Disney - Christmas". Donald #1128 + Daisy #1127 -> "Disney - Christmas" (siblings of Eeyore #1129). 3 Matterhorn -> "Disneyland Rides" (unified). WDW50 ride -> "Walt Disney World Rides". Disneyland 70th -> "Disneyland 70th Anniversary". Casey Jr. Mickey -> "Disneyland Rides".
- 3 Pride & Prejudice & Zombies (Featherstone, Catherine, Collins) -> "Pride and Prejudice and Zombies" (name-list, no franchise in title). Red One Nick -> "Red One". Lilo & Stitch cluster (~16), Star Wars (5), Elvira, DC, Marvel, Guardians, Devil Wears Prada, Grinch, etc. from title.
- Non-alphanumeric junk scan: stripped `__Disneyland 65th Anniversary` prefix (3 fields on Donald Casey Jr.), 1 trailing space, 6 double-spaces. 0 residual.
- FLAGGED, UNRESOLVED: Casey Jr. pair mismatch — Mickey Casey Jr = "Disneyland Rides" but sibling Donald Casey Jr = "Disneyland 65th Anniversary" (same attraction, two franchises). Chris has not decided to pair them.

### 3. Category fill — 61 blank categories -> 0
Inference (catalog can't fill; 0 of 61 have catalog UPC match). Decisions:
- Rudolph -> "Pop! Television" (TV special). Elvira -> "Pop! Icons". Grinch -> "Pop! Movies". NBC -> "Pop! Disney".
- Disney park attraction figures (character-on-vehicle: Peoplemover, Matterhorn, Casey Jr., carousel, fire engine) -> "Pop! Rides" (real Funko line per Chris's research; franchise does the grouping, category stays real Funko taxonomy — Option A). WDW Ride Super Deluxe -> "Pop! Rides" (bug fix). Looney Tunes Marvin -> "Pop! Animation" (bug fix).
- Property rules: Lilo & Stitch -> Pop! Disney, Star Wars -> Pop! Star Wars, Marvel/Guardians -> Pop! Marvel, DC -> Pop! Heroes.

### 4. Report code fixes — STAGED, NOT CONFIRMED COMPILED-IN
Three files in outputs/funkodex/. Chris compiled and "updates did not seem to go in" — PARTIAL: the FunkoItem/ReportsScreen changes rendered (screenshots show "Not tracked" tags working), but FunkoRepository category fix did NOT apply (Disney 100 still showed "Pop! Rides" not most-common). Suspect FunkoRepository.kt not copied to correct path or build didn't pick it up.
- `FunkoItem.kt` (SeriesSummary ~line 205): added `isTracked` (totalInCatalog > 0), `displayDenominator` (maxOf(totalInCatalog, ownedCount)), capped `completionPct` at 100 (.coerceAtMost(100)). Fixes "12/0 0%" and "400% complete".
- `ReportsScreen.kt` (series card ~275-335): branches on isTracked — tracked shows ratio+bar+"N% complete"+Completing pill; untracked (invented franchises) shows owned count only, no bar, "N in collection" + muted "Not tracked" tag (Option B).
- `FunkoRepository.kt` (summaryFor ~line 210): category derivation changed from `ownedInGroup.firstOrNull()?.category` to MOST-COMMON non-blank category (groupingBy/eachCount/maxByOrNull). Fixes Universal Monsters showing "Pop! Animation", Disney 100 showing "Pop! Trains".
- Symbols verified against repo/stdlib (Surface, LinearProgressIndicator via material3.* wildcard; coerceAtMost, maxByOrNull, groupingBy/eachCount). Braces/parens balanced. NOT compiled in this env (pinned toolchain absent).

## OPEN / NEXT SESSION

1. **ENRICHMENT RUN (primary next workstream).** Chris's decision: stop manual patching; run enrichment and let it overwrite the invented "Disney - Christmas"-style franchises/categories with real catalog data. CAVEAT: enrichment matches by UPC; the 61 problem records have NO catalog UPC match, so enrichment only fixes them IF catalog coverage has grown to include their UPCs. If not, they retain the inferred values (which are reasonable). Verify coverage after the run. After enrichment gives real franchises, Chris can decide whether to re-apply the holiday sub-line scheme on top (knowing it breaks catalog completion by design) or track holidays via a separate tag.

2. **Report code fixes didn't fully land.** If continuing: debug why FunkoRepository.kt category fix didn't compile in (check file landed at `app/src/main/java/com/funkodex/data/repository/FunkoRepository.kt`, clean rebuild). Files are in outputs/funkodex/.

3. **Cost Breakdown clarity (APPROVED by Chris, NOT written).** Label changes: "Total Retail Value" -> "Total Retail (MSRP)", "Above Retail" -> "Paid Above Retail". Add single info popup (ⓘ on "Cost Breakdown" card header) with all 4 definitions. Verified defs from code: Total Paid = sum(pricePaid + variants' pricePaid); Total Retail Value = sum(effectiveRetail = retailPrice if>0 else resolvedRetail); Above Retail = totalPaid - totalRetailValue; Est. Market Value = sum(marketAvg = PriceCharting "Complete"/pcComplete, or manual). Card composable at ReportsScreen ~195-225.

4. **Manual-fix edge records (Chris handling on-device):** Easter Stitch #1533 (junk name, no blob), Elvira (on Red Sofa) (Deluxe), Mad Sweeney. Also Wedge Antilles ($120 signed — LEAVE, accurate as "Most Expensive Paid"; Chris confirmed don't manipulate pricePaid). "Stitch with Mood Chart" has variants:127 (possible data bug, unresolved).

5. Casey Jr. Mickey/Donald franchise mismatch (see #2 above) — Chris hasn't decided to pair.

## S19 TOOLING (in outputs/, PENDING save to repo tools folder)
- `resize_blobs.py` — post-harvest resize pass (Pillow, max 400px JPEG q85, only touches blobs >90KB). CRITICAL — prevents the restore bug.
- `blob_images.js` — harvest blobber. KNOWN ISSUE: stores full-res, does NOT resize (caused the S19 restore bug). Should get resize built in.
- `serp_proxy.js` — local SerpAPI proxy (port 3000, needs SERPAPI_KEY env).
- `FunkoDex_Image_Collector.html` — browser image picker.
- `rpm_harvest.js` — Real Pop Mania catalog harvester (title-only match, coverage-test only).

## CHANGE LOGS (in outputs/)
golden_fix_changelog.md, retailer_strip_changelog.md, holiday_changelog.md, franchise_final_changelog.md, category_fill_changelog.md

## KEY CONSTRAINTS (carried)
- Catalog is GOLDEN SOURCE; fix collection to catalog. If catalog wrong, fix catalog upstream. The 61 records here had NO catalog match, so inference was used (documented as inference, not golden-source).
- Number-based catalog matching is UNSAFE (Pop numbers not unique across lines). UPC-match or human-confirm only.
- Doc `_id`s NEVER changed.
- thumbnailBlob schema (export/import format, NOT native CBL Blob): `{_type:"blob", contentType:"image/jpeg|png|webp", data:"<base64 NO_WRAP>"}`.
- Verify version-sensitive symbols against pinned versions + in-repo usage; never guess.
