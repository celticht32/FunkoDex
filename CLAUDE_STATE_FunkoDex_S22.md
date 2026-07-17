# CLAUDE_STATE — FunkoDex — Session 22

STATUS: COMPLETE. Catalog data-quality cleanup executed end-to-end; the app now SHIPS the cleaned catalog for the first time (was still seeding from the deprecated Kenny Chan `funko_data.json`). Verified on device AND on a clean emulator (fresh-install path). Partially supersedes S21's "enrichment is a dead end" call — see the correction below, it was right about *owned-match improvement* and wrong about *catalog growth*.

Repo: FunkoDex (branch `master`, `C:\Downloads\Development\FunkoDex\`)
Toolchain (pinned, unchanged): AGP 8.13.2, Gradle 8.13, Kotlin 2.0.21, material3 1.3.0 (BOM 2024.09.00), CBL 3.2.4. package com.funkodex, minSdk 26, targetSdk 36.
Companion repo: funko_enrich (branch `main`), Node v24, Python/Pillow.

## Current authoritative backup
`FunkoDex_RESTORE.zip` (12.2 MB) in `C:\Downloads\funkodex_upc_verify\` — restore via Settings > Restore full (wipe + import). Inner file `funkodex_backup.json`, **21,211 docs**: catalog 20,580 + owned 358 + app-state 273 (system 2, cat_pref 65, price 130, contrib 74, group_pref 2).
CONFIRMED restored on device (logcat: "Force restore: streamed 21211 documents") and the collection renders correctly.
Fallback (stale but known-good): `FunkoDex_RESTORE_cleaned.zip` — PRE-cleanup (19,831 catalog, old catalogRefs). Keep it; do not merge into it.

## Current authoritative catalog
`C:\Downloads\Development\funko_enrich\funkodex_base_catalog.json` — **20,580 records**, cleaned + enriched.
Shipped asset: `app\src\main\assets\funkodex_base_catalog.json.gz` (2.0 MB, 11% of 18.1 MB raw).
The old `funko_data.json` (Kenny Chan seed) has been DELETED from assets.

## S22 WORK COMPLETED

### 1. Catalog data-quality cleanup (the bulk of the session)
Started from "some UPCs look wrong", ended with a systematically cleaned catalog. All applied to live files and verified:
- **537 non-Pops removed** — pins, shirts, glasses, magnets, FunkO's cereal, Dorbz, ReAction figures, action figures, prototypes, ornaments. Many (Dorbz/ReAction/prototypes) carry `Vinyl_Art_Toys` images identical to real Pops and were only identifiable by eye, not by rule.
- **361 UPC mis-staples blanked** (266 automatic + 95 from review). Tiered rule: blank when owner-agreement <0.20, keep at >=0.50, review the 0.20–0.50 band. A naive "one owner per UPC" rule would have blanked **619**, destroying ~258 legitimate variant/multipack shared UPCs.
- **53 collection names cleaned** (18 auto + 35 review).
- **13 duplicate clusters merged**, survivors healed from their dupes (e.g. Zombie She-Hulk gained the correct UPC 889698491280 + #792 + pcId).
- **~1,400 new Pops added** by the enrich run's funko.com pass.

### 2. Known-issue fixes (`fix_known_issues.py`)
- **Evil Queen (Snow White Stained Glass)** — existed as TWO half-records: `catalog::81681.html` (funko.com: good title + image, no identity) and `catalog::pc-10118182` (PriceCharting: #1609 + pcId, bare title, no image). Merged into 81681.html, stub removed, UPC 889698816816 set (sourced by Chris from retail listings).
- **Castiel** — restored (30 fields recovered verbatim). Real Pop! Television Supernatural #95, Hot Topic 2014; enrich's FunkO's title rule had wrongly deleted it. UPC left blank deliberately (never resolved; a guessed UPC is worse than none).
- **Mr. Toad (65th Anniversary)** — CREATED. Did not exist in the catalog at all. #814 and UPC 889698511728 both read off Chris's physical box; series inherited from its sibling in the same Disneyland 65th Anniversary attraction line.
- **Belle** and **Evil Queen on Throne** — collection items re-linked to real catalog rows.
- Result: **0 orphans** across all 358 owned items (was 3).

### 3. Enrich mis-match audit (`check_mismatches.py` / `blank_mismatches.py`)
The enrich run stamped a *different Pop's* identity onto records it mis-matched — record keeps its own correct title/image but gains the wrong funkoNumber/pcId/series/pricing. Silent: every field looks populated.
- Audited all 451 funko.com records that got a PriceCharting match. Found ~6 blatant + ~15 variant-level wrong.
- **22 records blanked** — identity fields cleared, title/image/handle preserved, so a future enrich re-resolves them from their own (correct) title.
- Deliberately spared ~9 that tripped the detector but are correct (Mbappé accent, De'Aaron Fox apostrophe, Erza→erza-scarlet, Plo Koon→plo-koon-glow-in-the-dark, H.E.R.B.I.E.→herbie, etc.).

### 4. enrich.js hardened (companion repo `funko_enrich`)
Every failure this session surfaced is now a rule:
- **`coreNameCovered` substring bug** (the root cause of the mis-matches) — used raw `rowLc.includes(w)`, so "ram" matched "**bram** stoker", "poe" matched "**poe**t anderson", "will" matched "chilly **will**y". Now tokenises the row name and compares whole words. Tested 11/11: blocks all four substring collisions, still allows legitimate fuller-name matches.
- **`isNonPop` series crash** — did `.map()` on `rec.series` assuming an array; base-catalog shape has it as a string. Crashed the final post-process. Normalised.
- **Castiel guard** — a figure image (`Vinyl_Art_Toys`/`Action_Figures`) + a real funkoNumber now overrides a garbage title, so mis-titled real Pops survive. Narrow by design: tested to rescue Castiel while still deleting all 15 FunkO's cereal records (which carry `Whatever_Else` images).
- Greedy bare apparel words (hat/cap/mug/bag/dress) REMOVED from `NON_POP_TITLE_WORDS` — a Pop can *wear* a hat. Real apparel is caught by image category instead.

### 5. App now ships the cleaned catalog (DEC-023, DEC-024)
The app was still preloading `funko_data.json` — none of the cleanup had ever reached it.
- `CatalogPreloader.kt` rewritten: streams `funkodex_base_catalog.json.gz` via `GZIPInputStream` + `JsonReader` (the old `readText()` + whole-tree Gson on an 18 MB file was an OOM risk), reads the enriched `BaseRecord` shape, `CATALOG_VER` 1→2.
- `CatalogRefreshWorker.kt`: the Kenny Chan re-fetch is DISABLED (see DEC-024). Community UPC merge + HobbyDB vaulted refresh still run.
- `build_catalog_asset.py` (funko_enrich) validates the catalog against exactly what the Kotlin expects before gzipping.
- **BUILD SUCCESSFUL**, 2 warnings (both the intentional `@Deprecated KennyRecord`).
- **Fresh-install path VERIFIED on a clean emulator**: searched "Stitch", got results, added one. That exercises gzip read → stream parse → 20,580 CBL inserts → index build → UI read.

## CORRECTION to S21: "enrichment is a dead end"
S21 stated **"DO NOT run enrich.js again expecting owned-match improvement."** That framing was *half right* and this session proves the boundary:
- **Right about owned-match improvement.** None of the ~1,400 new records fixed an unmatched owned figure. DEC-020 still stands.
- **Wrong as a blanket "enrichment is done."** The run added ~1,400 real Pops (current funko.com releases the catalog had never seen) and is what surfaced the Evil Queen / Stained Glass line at all. Catalog *growth* and owned-*matching* are different goals; S21 measured the second and generalised to the first.
- **The real cost S21 didn't measure:** the run also introduced 22 silent identity corruptions (mis-matches). The fix is the `coreNameCovered` word-boundary correction, not abstinence.
- Revised guidance: run enrich for catalog currency; ALWAYS follow with `check_mismatches.py`; never expect it to close the owned-link gap.

## KNOWN OPEN ITEMS
1. **25 blanked records await one enrich run** to re-resolve: 3 image-blanked (Bash #623, Kurogiri #789, Android 17 #529) + the 22 mis-match-blanked. Copy the fixed `enrich.js` first. Then re-run `merge_backup.py` and `build_catalog_asset.py`. Purely additive — nothing is broken without it.
2. **Variant-level mis-matching is UNFIXED and probably unfixable from text.** The Evil Queen case ("Evil Queen (Snow White Stained Glass)" vs slug "snow-white-&-evil-queen") shares every word; only knowing a 2-pack from a Deluxe distinguishes them. Character-level collisions are now blocked; this class will recur on future runs. `check_mismatches.py` reports it as "partial" for human review — it is a worklist, not a verdict.
3. `CatalogImporter.kt` untouched — user-triggered import path, still expects the old enriched shape. Now inconsistent with the preloader. Not blocking.
4. `FunkoDexApp.kt` ~line 134 still logs "funko_data.json not found" on `AssetMissing`. Cosmetic.
5. `KennyRecord` + `refreshKennyChanDISABLED()` are retained only so the disabled code compiles. Delete together when removed for good.

## DATA-SAFETY RULES CARRIED FORWARD
- **Never blank `imageUrl` on a re-resolve.** `reresolve_records.py` did; enrich does NOT refetch images, so Bash/Kurogiri/Android 17 ended with correct data and no picture. Only blank fields a pass actually repopulates.
- **Blank beats wrong.** A blank re-resolves cleanly; a wrong value corrupts silently and passes every "is it populated?" check.
- **Presence is not correctness.** A verification that checks whether a field is filled will happily green-light garbage — the Evil Queen record reported "funkoNumber present: YES" while holding a different Pop's number. Compare VALUES.
- **Scripts read the LIVE original.** Each writes a new file (`.kfix`/`.blank`/`.merged`); rename over the original BEFORE running the next, or they all read the same input and none chain.
- **PowerShell one-liners with nested quotes fail.** Always ship a downloadable `.py`.

## JUDGEMENT CALLS — the reasoning behind the numbers
These only existed in the S22 conversation. Written down so a future session doesn't
re-litigate them from scratch, or worse, quietly reverse them.

### The 22 blanked mis-matches: what was spared and why
`check_mismatches.py` flagged 6 "no overlap" + 61 "partial" out of 451 matched records.
22 were blanked. The other ~45 were judged CORRECT and deliberately left alone — they
trip the detector because PriceCharting names things differently, not because the match
is wrong:
- **Accents/punctuation:** `Kylian Mbappé`→`kylian-mbappe`, `De'Aaron Fox`→`de%27aaron-fox`, `Peni Parker's SP//dr`→`peni-parker%27s-spdr`. URL-encoding artefacts, same figure.
- **Fuller names:** `Erza`→`erza-scarlet`, `Alligator`→`alligator-loki`, `Woody`→`sheriff-woody`, `Rebecca`→`rebecca-cunningham`, `Chopper`→`tony-tony-chopper-flocked`. PriceCharting uses the full character name; ours is the short form. Legitimately "covered".
- **Expanded qualifier:** `Plo Koon (Glow)`→`plo-koon-glow-in-the-dark`.
- **`H.E.R.B.I.E.`→`herbie`** — almost certainly a correct match. It now FAILS the fixed
  word-boundary check (punctuation tokenises to `h,e,r,b,i,e`, which won't match
  "herbie"), so it will be rejected as uncertain on the next run rather than
  mis-matched. That is an accepted minor regression: rejecting is the safe failure, and
  a punctuation-collapsing rule risked opening new holes. Don't "fix" it without
  testing against the substring cases in lesson 40.

The 22 that WERE blanked split into two kinds — worth knowing, because the second kind
is the one the fixed code still can't catch:
- **Substring/character collisions (now fixed in code):** `Ram`→`bram-stoker`, `Poe`→`poet-anderson`, `Will`→`chilly-willy-frozen`, `Venom`→`venomized-doctor-doom`, `Harry (Beanie on Fire)`→`hermione-granger`.
- **Variant-level (still possible):** `Spider-Man (No Way Home Suit)`→`spider-man-homemade-suit`, `Hulk (Brand New Day)`→`hulk-holiday`, `Maul`→`darth-maul-on-bloodfin-speeder`, `Miles Morales (Vibranium Suit)`→`miles-morales-programmable-matter-suit`, `King Ghidorah`→`mecha-king-ghidorah`, `Batman (DC New Classics)`→`batman-sdcc`, `The Mandalorian with Grogu (On Bantha)`→`the-mandalorian-on-speeder-with-grogu`, and others. Right character, wrong product.
- **`The Creature`→`creature-from-the-black-lagoon`** was blanked while flagged
  "possibly right" — over-blanking accepted deliberately (a blank costs pricing until
  the next run; a wrong value corrupts forever).

### Mr. Toad #814 — nearly blanked on a bad inference
The assistant reasoned that #814 must be wrong because its Disneyland 65th Anniversary
siblings are numbered in the 80s-90s (the sibling attraction Pop is #89) and because
#814 collides with Vegeta, Feyd Rautha, Cousin Itt, Boba Fett and Captain America in
this catalog. **All of that was wrong.** Chris photographed the box front: #814 is
correct. Funko reuses numbers across series; a number collision is normal and is NOT
evidence of bad data. The near-miss is why the record now carries `source:
MANUAL_VERIFIED` — its number and UPC came off physical packaging, which outranks any
inference.

Related: the sibling record `catalog::mr.-toad-at-the-mr.-toad's-wild-ride-attraction`
(#89, UPC 889698511926) is a DIFFERENT figure in the same line — adjacent barcode, not
a duplicate. Do not merge them.

### Evil Queen — the collection item's UPC is the cereal's, and that is correct
`funko::889698502702` ("Evil Queen on Throne") scanned UPC 889698502702, which
genuinely belongs to the FunkO's cereal record. Chris owns the *Pop*, not the cereal —
the scan hit the wrong barcode at add-time. The cereal's UPC was never mis-stapled, so
it was NOT corrected; the collection item was re-linked to the real Pop
(`catalog::81681.html`) instead. The Pop's own UPC (889698816816) was sourced from
retail listings. Don't "fix" the cereal's UPC on a future pass — it is right.

### Dedup was deliberately NOT extended
`check_funko_dupes.py` reported 403 normalized-title collision groups. That number is
almost entirely a normalisation artefact — stripping parentheticals collapses every
Spider-Man variant into one `spider man` bucket (~80 records), every Batman into
`batman` (~90). Real duplicates in there are maybe dozens, and separating them needs
product knowledge (the Evil Queen pair was found by Chris recognising the product, not
by an algorithm). A fuzzy matcher would either over-merge — it nearly merged the four
records titled exactly "Winter Soldier" (#838, #44, #701, and one unresolved) into one,
and likewise the Twinkie variants —
or generate a 400-item review queue that is ~95% false positives. **Decision: stop at
the 13 strict-rule merges.** Don't build the fuzzy matcher without a much better signal
than title normalisation.

### The pc-XXXXX stub records are NOT a problem
They look alarming (a "pc-10118182"-style id instead of a name slug) but the large
majority are legitimate PriceCharting-crawl Pops with real data — they are simply keyed
by PC number rather than name. (Measured on the pre-enrich base: 8,377 stubs, of which
7,936 (94%) carry a real funkoNumber.) Only the ones that duplicate a funko.com record are an issue,
and that is the Evil Queen case, handled individually. Don't bulk-delete or bulk-merge
them.

### Why the enriched base was salvaged rather than re-run
The crashed run's output (`funkodex_base_catalog.enriched.json`, 20,680 records) was
mixed-shape: 19,281 in base-catalog shape plus 1,399 still in raw funko.com working
shape (the crash hit `removeNonPops`, which runs BEFORE `toBaseCatalogShape`).
`salvage_enriched.js` finishes the run by calling the pipeline's own `removeNonPops`
and `toBaseCatalogShape` **verbatim** — extracted from enrich.js, not reimplemented —
so the result is byte-identical to what a clean run would have written, without
re-scraping for an hour. If a future run crashes in post-processing, salvage rather
than re-run, and keep the "verbatim, not reimplemented" property.
