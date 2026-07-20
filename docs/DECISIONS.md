# DECISIONS.md — FunkoDex / funko_enrich Architectural Decision Registry

Single source of truth for locked architectural decisions across FunkoDex (Android/Kotlin) and the funko_enrich pipeline (Node.js). Search with `grep -i "keyword" docs/DECISIONS.md`.

Rules:
- One heading per decision (`### DEC-NNN: title (YYYY-MM-DD)`). Active decisions on top.
- A reversed/replaced decision is NOT deleted — set Status to "Superseded by DEC-NNN", move it to the Superseded section, add the replacement up top with "Supersedes DEC-NNN".
- Detail lives here once. Chronicle/handoff notes reference "see DEC-NNN" rather than restating rationale.

> VERIFICATION NOTE: Validated 2026-06-30 against the live repo (master branch — NOTE default branch is `master`, not `main`): libs.versions.toml, app/build.gradle.kts, gradle/wrapper/gradle-wrapper.properties, and HANDOFF.md (dated 2026-06-29). Toolchain pins (DEC-010) confirmed against build files. Data-model decisions confirmed against HANDOFF.md Sessions 14-17. Corrections from the first draft are folded in below.

---

## ACTIVE DECISIONS

### DEC-001: franchiseSuggestion is the primary grouping axis (locked; verified S15)
**Status:** Active
**Context:** FunkoDex needs a consistent top-level grouping for browsing and the series/collection-completion features. The raw series tag gave bad results (the umbrella console alone resolved small properties like Hocus Pocus / Hangover to "disney").
**Decision:** `franchiseSuggestion` is the PRIMARY grouping axis, seeded from PriceCharting's `pcSeries` property (cleaned; console fallback) — user-authoritative, NO longer derived from the raw series tag. It is level 1 of a two-level grouping (franchise/property + named set).
**Verified:** Session 15 — switching to pcSeries raised franchise coverage 57 -> 630 and correctly resolves small properties. (The earlier "~76% populated" figure is NOT in the repo; removed.)
**Consequences:** Grouping UI, completion logic, and group_pref intent key off franchiseSuggestion (level 1). Do not revert grouping to the raw series tag.

### DEC-002: setTag is the named-set level (level 2 of two-level grouping; verified S15)
**Status:** Active
**Context:** Within a franchise/property, users want the specific named set (e.g. Haunted Mansion within Disney).
**Decision:** `setTag` = the most-specific named set. It is LEVEL 2 of the deliberate two-level grouping (franchise/property at L1, named set at L2) — not a fallback or a lesser axis. Emitted by enricher POST-PROCESS 5 (S15).
**Verified:** Session 15 — Haunted Mansion resolves to its own set, 19/19.
**Consequences:** Set membership, set badges, and per-set completion read setTag. Changing what populates setTag is a grouping change, not a cosmetic one.

### DEC-003: group_pref intent stored in group_pref::{LEVEL}::{key} docs (verified S15)
**Status:** Active
**Context:** Per-group completion intent must persist as durable, backed-up user data.
**Decision:** Intent is stored in docs keyed `group_pref::{LEVEL}::{key}` — LEVEL = grouping tier (franchise / set), key = the franchise/set identifier. Intent value is COMPLETE or CHERRY_PICK. These docs back up via the existing denylist (treated as user data, not catalog).
**Verified:** Session 15. `getCollectionStats` reads the catalog for true X-of-Y denominators; want list = COMPLETE groups only (manual wants kept).
**Consequences:** Key format and the COMPLETE/CHERRY_PICK enum are load-bearing — changing either is a migration. Completion + Want List read/write these docs.

### DEC-004: Collection Completion + Want List feature design
**Status:** Active
**Context:** Users want to track which items in a group they own and which they still want.
**Decision:** Collection Completion and Want List are designed against the franchiseSuggestion grouping (DEC-001) with intent in group_pref docs (DEC-003). Next actionable build work: §9 data pre-work + the §4 priceSource reader to unblock the Collection Completion build.
**Consequences:** Build sequencing depends on the priceSource reader landing first.

### DEC-005: funko_enrich produces funko_data_enriched.json, consumed via catalog import
**Status:** Active
**Context:** Enrichment (Node.js) and the app (Android) are separate codebases; the app must not run enrichment at runtime.
**Decision:** funko_enrich emits `funko_data_enriched.json` as its product; FunkoDex consumes it via catalog import. The boundary is the file, not a live API.
**Consequences:** App ships against a snapshot. Re-enrichment = new JSON + re-import, not a hot update.

### DEC-006: funko_enrich is a four-pass pipeline (corrected; verified against HANDOFF + enrich.js usage)
**Status:** Active
**Context:** No single source has complete catalog + pricing + reference-number coverage.
**Decision (CORRECTED from first draft):** enrich.js runs FOUR passes: Pass 1 = Kenny Chan GitHub dataset (always `--skip-kenny`, same as bundled JSON); Pass 2 = funko.com scrape (Puppeteer + stealth, `--max-pages 160`); Pass 3 = PriceCharting (free, no key — adds marketValueLoose/New); Pass 4 = HobbyDB Reference Numbers (Puppeteer — adds upc, funkoNumber, retailer SKUs). The "Pass 3b catalog crawl" and "Pass 3 pricing" language from the first draft conflated later PriceCharting session work with the base pipeline structure — it is NOT a separate numbered pass.
**Standing rule:** MAXIMIZE golden-master completeness — prefer expanded passes and higher limits over partial enrichment.
**Consequences:** Re-enrichment follows the four-pass order. HobbyDB runs in resumable batches (`--hdb-limit`).

### DEC-006b: enriched-record counts (verified S16/S17 — these are the real numbers)
**Status:** Active
**Context:** Several count figures drifted in handoff memory.
**Decision (authoritative numbers):** Bundled asset `funko_data.json` = 23,940 records. Golden master after S16 rebuild = ~25,806 records, ~76% PriceCharting-priced (unpriced tail = items PC doesn't carry, a data ceiling not a matcher gap). On-device catalog after S17 repair/prune = 21,989 (pruned from 28,008 stale-drift). (First-draft figures "~25,731 / 77-80%" were close but not exact — use these.)
**Consequences:** Quote these numbers, not remembered approximations.

### DEC-007: Streaming exportFullBackup() / forceRestoreDatabase() to avoid OOM (verified S16/S17)
**Status:** Active
**Context:** Full catalog is ~22-26k docs. The naive in-memory `exportFullBackup` OOM'd at 150 MB.
**Decision:** Backup/restore are STREAMING. `exportFullBackup` dumps EVERY doc incl. catalog, streamed to zip. `forceRestoreDatabase` uses Gson `JsonReader`, 500-doc batches. Force restore = close DB -> wipe directory -> reopen fresh -> insert user data -> catalog re-preloads on next start.
**Critical related fact:** the NORMAL backup EXCLUDES catalog + system docs (only user data) — that is why on-device catalog state was previously invisible and why `exportFullBackup` was needed for full-state dumps. system-type marker docs are preserved through backup/restore (not exported, not deleted).
**Verified:** Session 17 on-device — import 16,149 updated + 9,546 added in 14 s, no OOM.
**Consequences:** Never refactor backup/restore to load-all-then-write. Preserve the catalog-excluded/full-included distinction between normal and full backup.

### DEC-007b: funko::UUID for collection items — NEVER catalog:: (hard invariant; root cause of S17 bug class)
**Status:** Active
**Context:** A long-chased bug class (wrong/pin images, "not matched", junk names) was root-caused in S17: 8 owned items carried `catalog::` document IDs, squatting on the slots the catalog's own records need, so those catalog records were never created and the relink's UPC index (queries type=="catalog") never found them.
**Decision:** Collection items MUST use `funko::{UUID}` (or `funko::{upc}`) IDs. `catalog::` IDs are reserved for catalog records. The S17 fix re-homed the 8 offenders to `funko::{upc}`.
**Consequences:** Any code path that could assign a `catalog::` ID to an owned item is a corruption bug. After the fix, relink reported 0 remaining bugs (75 unmatched are legitimate: 64 not-in-catalog gaps + 11 shared-UPC variants relink correctly won't guess).

### DEC-008: GitHub access pattern for catalog/data files
**Status:** Active
**Context:** Bulk tarball fetches (codeload.github.com) can serve stale cache.
**Decision:** Use raw.githubusercontent.com for individual files; codeload.github.com tarballs for bulk — but VERIFY against the GitHub web tree when correctness matters, because tarballs may serve stale cache.
**Consequences:** Don't trust a tarball snapshot as current without a web-tree check.

### DEC-009: On-device DB repaired (S17) — known-good baseline with exact numbers
**Status:** Active
**Context:** The on-device DB had ID-collisions, corrupted franchises, junk names, stale drift, and non-figure images.
**Decision/result (verified S17, delivered as a repaired full backup, restore via "Restore full"):** re-homed 8 `catalog::`-squatting items to `funko::{upc}` (see DEC-007b); cleaned 21 corrupted franchises; normalized ~30 franchises to canonical spellings; cleaned 15 junk retail names with metadata extraction; pruned catalog 28,008 -> 21,989 (stale drift dropped, owned-refs preserved); cleared 1,404 catalog + 4 owned non-figure images (pins/keychains) to placeholders. Final verification: 175/175 items preserved with prices/conditions/photos, 0 junk names, 0 Funko/unknown franchises, 0 dup IDs, 0 orphaned refs.
**Consequences:** This is the known-good baseline. Relink/import regressions validate against it; relink-integrity regressions are release-blockers. Release-prep open item: the 1,404 cleared images need a full re-enrichment (fixed enricher now filters non-figure HobbyDB media via `isFigureImage()`) to repopulate correct figure images before release.

### DEC-010: FunkoDex toolchain pins (VERIFIED against build files 2026-06-30)
**Status:** Active — VERIFIED
**Context:** Reproducible builds; version-sensitive API symbols must match pinned libs exactly.
**Decision (confirmed against gradle/libs.versions.toml, app/build.gradle.kts, gradle-wrapper.properties):** AGP 8.13.2, Gradle 8.13, Kotlin 2.0.21 (KSP 2.0.21-1.0.28), Couchbase Lite 3.2.4, Compose BOM 2024.09.00, minSdk 26, targetSdk 36, compileSdk 36. Also pinned: Hilt 2.51.1, CameraX 1.6.1, ML Kit barcode 17.3.0, Coil 2.7.0, Navigation 2.8.0, coroutines 1.9.0, OkHttp 4.12.0, Gson 2.11.0, Apache POI 5.3.0, WorkManager 2.10.1, DataStore 1.1.1, Glance 1.1.0.
**CORRECTION:** material3 has NO explicit version in the catalog — `compose-material3` is declared without a version and resolved transitively by the Compose BOM (2024.09.00). State it as "material3 via compose-bom 2024.09.00 (BOM-managed)", NOT as a literal pin like "1.3.0".
**Consequences:** For any Compose/material3/Kotlin/Android code, never infer API symbol names/signatures from training data — verify every version-sensitive symbol against the project's existing usage first, then versioned docs; flag any symbol that can't be verified against the pinned/BOM-managed version rather than guessing.

### DEC-011: FunkoDex documentation consolidated 24 -> 11 files
**Status:** Active
**Context:** Documentation had sprawled to 24 files.
**Decision:** Consolidated to 11 files. (This handoff/docs tracking structure should not re-sprawl it — keep hot state in CONTEXT.md, decisions here, narrative in chronicles.)
**Consequences:** New docs justify their existence against the 11-file baseline.

### DEC-012: Enriched import uses explicit JSON-tree extraction, NOT reflective Gson (FAILED PATH — do not retry)
**Status:** Active
**Context:** The enriched-catalog importer must deserialize `funko_data_enriched.json` into `EnrichedRecord`.
**Decision:** Parse via `JsonParser.parseString(json)` -> `JsonArray` -> explicit per-field `JsonObject` extraction (`optString`/`optBoolean`/`optStringList`). DO NOT use `gson.fromJson(json, TypeToken<List<EnrichedRecord>>)`.
**Why (the dead-end to not repeat):** the reflective Gson path threw `ArrayList cannot be cast to java.lang.Void` on-device (Session 9). Kotlin-bytecode-specific; root cause never fully isolated, but the tree-parse approach bypasses it entirely. Unknown JSON keys (hdbid, hdbChecked, franchise, funkoSection, funkoNumberFromTitle) are simply not read — harmless.
**Consequences:** Anyone "cleaning up" the importer to reflective binding will reintroduce an on-device crash. Leave the tree-parse in place.

### DEC-013: Accepted import behaviors — do NOT "fix" without discussion
**Status:** Active
**Context:** The importer's spec-verbatim rules produce a few known, deliberately-accepted edge cases.
**Decision (accepted, by choice, to stay close to spec):**
 - `NON_POP_TITLE` regex is verbatim from spec and false-positives on real Pops whose titles contain shirt/soda/bag (e.g. "Hulk Hogan (Tearing Shirt)", "Jinu (Soda Pop)", "Bilbo Baggins in Bag-End"). These ~4 are skipped by decision.
 - `isStandardPop()` series-tag list omits "pocket pop" — Pocket Pops lacking the phrase in-title pass the filter. No impact on current file; a future raw dataset could insert them as standard.
 - funko.com Pass-2 emits `NNNNN.html` page-filename handles for unmatched records; importer repairs them with a title slug (729/729 clean on the 2026-06-12 file). Proper upstream fix is to slugify in enrich.js.
 - Shared UPCs (e.g. 889698491181 on two records) and shared funkoNumbers (e.g. #157 Vader variants) are expected; user is the safety net (wrong name shows in Preview).
**Consequences:** These are not bugs. Reference DEC-013 before "fixing" any of them.

### DEC-014: CollectionRelinkService is golden-source; field protection via userEditedFields (verified S14/S16/S17)
**Status:** Active
**Context:** Re-importing an improved enrich.js run must upgrade owned items without clobbering user edits.
**Decision:** Catalog merge is last-enricher-wins and recomputes series-derived fields via shared `CatalogMapper.deriveSeriesFields` (insert and merge can't drift). `CollectionRelinkService` is golden-source: enriched catalog OVERWRITES franchise/category (re-derives genre) when non-blank; UPC is fill-only; ownership data untouched. User-editable fields (upc, franchise, category, imageUrl) refresh only when the `userEditedFields` marker is absent or doesn't list them; absent marker -> fill-only (migration guard). Run AFTER the enriched import.
**Consequences:** The `userEditedFields` marker is load-bearing for edit protection. Backup/restore is field-agnostic (walks doc.keys) — verified, no schema change needed when fields are added.

---

## LICENSING / COPYRIGHT / BRAND (personal work — git-resident, kept separate from any Couchbase registry)

### DEC-015: License + copyright — MIT, © 2026 Chris Ahrendt (personal default)
**Status:** Active
**Context:** FunkoDex is personal/hobbyist work under the Celtic Heart Steamworks brand (GitHub celticht32), distinct from Couchbase employer work. No top-level LICENSE file existed in the repo when this was recorded (2026-06-30).
**Decision:** Code Chris produces here is MIT licensed, Copyright (c) 2026 Chris Ahrendt, unless a specific file states otherwise. Personal default — applies ONLY to personal repos, NEVER to Couchbase work (Couchbase code carries Couchbase/licensor terms; that separation lives in the Couchbase ops DECISIONS.md, not here).
**Action:** No top-level `LICENSE` file is present — add one (MIT, © 2026 Chris Ahrendt) to make the license explicit rather than implied.
**Consequences:** Clear personal-vs-employer IP boundary. A FunkoDex artifact carrying Couchbase terms, or a Couchbase artifact carrying this MIT/© Chris Ahrendt notice, is an error.

### DEC-016: Brand — Celtic Heart Steamworks (personal)
**Status:** Active
**Context:** Personal deliverables use a consistent brand identity.
**Decision:** Celtic Heart Steamworks brand: palette navy #0B1929, steel blue #4A8FD4, brass #8B6914, cream text #D4B896, muted steel blue #5580A0, dark steel #263F56; Georgia serif headings. Logo is the Celtic heart knot SVG — original file `celticht.svg`, path data used VERBATIM, never approximated or redrawn; if referenced and not present in session, ask for re-upload rather than substituting.
**Scope note:** This palette/Georgia rule governs Chris's personal *documents and deliverables*. FunkoDex's in-app UI has its own type set (Cinzel Decorative) and uses `celticht.svg` as the launcher icon — the app's UI theme is not bound by the document brand rule.
**Consequences:** Personal-work brand consistency. Personal-only — never appears on Couchbase material (see Couchbase ops DECISIONS.md DEC-010).

---

## METHOD / QUALITY DISCIPLINE (harvested as PATTERNS from davidegreenwald/claude-greenfield — not the tool)

### DEC-017: Unified `verify` gate = the Definition of Done
**Status:** Active (target — pieces exist, not yet unified)
**Context:** FunkoDex already has the quality pieces — `tsc`/Kotlin compile clean, the test suite (138+ passing incl. ScannerViewModelStateTest), and build output tracked in `C:\build_output.txt` — but they run separately, not as one gate. Greenfield's factor 6: one fast command (lint + types + tests + arch) is the Definition of Done, ideally hook-enforced, "the faster the gate, the more often it runs."
**Decision:** Treat a single pass of {Kotlin compile + lint + unit tests + the catalog:: invariant check (DEC-018)} as the Definition of Done for a change. "Done" means that gate is green — not "the build seemed to work." Where practical, bind it so it runs at commit rather than at discretion. Keep slow/instrumented device tests OUT of the fast gate; run them as a separate pre-merge step (Greenfield's "keep slow suites out of the fast gate").
**Consequences:** A change isn't done until the gate passes. Prevents "compiles for me" landing a regression. Not yet wired as a literal hook — this records the intent and the gate's contents; wiring is a follow-up.

### DEC-018: catalog:: invariant enforced as a fitness function, not just prose
**Status:** Active (target — invariant is real, machine-check not yet built)
**Context:** DEC-007b is the hard invariant (collection items use funko::{upc|uuid}, NEVER catalog::) and was the root cause of the S17 corruption (8 squatting items). Today it lives as a CRITICAL prose warning in CLAUDE.md. Greenfield's factor 7: "documented boundaries decay; checked contracts do not" — encode the rule as a build-failing check, and only fall back to a rule+review when no machine check is possible.
**Decision:** Add a machine check that FAILS when a collection-item (`type=="funko"` / owned item) is written or found with a `catalog::` `_id` prefix — as a unit test over the mint/import/edit paths and/or a lightweight assertion in the ID-minting code. The prose warning stays, but the test is the enforcement. This is the build-failing guard for the exact bug class DEC-007b describes.
**Consequences:** The S17 corruption class can't silently recur — a violating ID fails the gate (DEC-017) instead of shipping. Until the check exists, DEC-007b remains prose-only; this entry is the standing instruction to build it.

### DEC-019: Decision-complete tickets — no "TBD" reaches the keyboard
**Status:** Active
**Context:** Greenfield's factor 1: a ticket/spec resolves every decision (files touched, schema, flags, blast radius, test scenarios) before implementation, so "execution becomes mechanical — the agent transcribes a resolved plan instead of designing at the keyboard, where it has the least context and the most room to drift." This sharpens the existing BRD gap-scan discipline.
**Decision:** Before implementing a FunkoDex feature/change of any size, resolve every open decision in the spec first — no "TBD" carried into code. Files affected, data-model/schema impact, the catalog::/funko:: ID path, and the test scenarios are all named before writing. This is the existing gap-scan rule, stated as an absolute: design is finished in the spec, not improvised mid-build.
**Consequences:** Less mid-implementation drift and rework. Specs carry the decisions; the build transcribes them. (Complements the standing BRD gap-scan-before-finalize rule.)

### DEC-020: Owned records are self-sufficient; the catalog link is opportunistic, not a dependency (S21)
**Status:** Active
**Context:** The catalog is a snapshot; the world is not. A figure can enter a user's collection before it enters the catalog (new releases, prototypes, signed variants, regional/park exclusives). Treating "owned but not linked to a catalog row" as a defect leads to an unwinnable enrichment chase — S20/S21 measured that the remaining unmatched owned figures need a *bigger catalog*, not more scraping (see the enrichment dead-end below). An owned record already carries its own UPC, name, franchise, and series.
**Decision:** An owned `funko` record must render and count correctly on its own fields, whether or not a catalog row exists for it. The `catalogRef` link is OPPORTUNISTIC enrichment (a UPC/name join that happens when a catalog row exists and is simply absent when it does not) — never a precondition for the figure to be a first-class citizen. "Unmatched owned" is a normal, permanent, well-rendered state, not an error to eliminate. Set-completion math runs only where a real denominator exists (a named set), so unlinked figures never break it (they count as owned in their franchise; open franchises never had a completion denominator — see DEC-001/002).
**Consequences:** Stop patching the catalog to chase 100% owned-link coverage. Existing `CollectionRelinkService` / `CatalogRefreshWorker` machinery is the correct home for opportunistic link-on-refresh (link by UPC when a matching row appears). Do not gate display, pricing, or grouping on presence of `catalogRef`.

### DEC-021: No-UPC-no-identity catalog rows are FILTERED from action surfaces, never deleted (S21)
**Status:** Active
**Context:** ~6,360 catalog rows (24%) have no UPC, no Pop number, no PriceCharting link, and no franchise — overwhelmingly Pocket Pops, prototypes, box sets, and exclusives. Measured facts: 0 are currently linked by any owned figure; 6,344 are the only row for their name (not duplicates of good rows); all carry a title and ~6,065 carry an image. They cannot serve the manual-search goal (a user who picks one gets no UPC to attach and no price to look up), but they are the sole visual reference for those figures and would be first-class inputs to a future image-vector-search feature.
**Decision:** Do NOT delete these rows (irreversible; forecloses future image-search coverage and any later UPC enrichment). Instead FILTER them out of user-facing action surfaces where an unactionable result is a dead end. Concretely, `searchByName` drops any result lacking ALL of {upc, seriesNumber, pricechartingUrl, franchise}. Deletion is only ever considered if storage genuinely bites — on a ~26k-row CBL set it does not.
**Consequences:** Manual/name searches return only actionable figures. The rows remain in the DB, dormant, available to a later image-search or enrichment pass. Any new user-facing catalog surface should apply the same actionability filter rather than re-litigating deletion.

### DEC-022: Name-based ownership check on the pre-purchase screen (loose figures) (S21)
**Status:** Active
**Context:** The pre-purchase ("do I already own this?") flow is the `prescan` screen and is UPC-only. A loose figure with no box has no scannable barcode. A name, unlike a UPC, is one-to-many, so a name check cannot answer owned/not-owned directly — it must show the matching figures, each badged with ownership.
**Decision:** Add a name-search fallback to `prescan` (Option A: reuse `searchByName`, show results badged by ownership; read-only, no add flow — matching the screen's existing purpose). Ownership badge join: a picked catalog figure (id = `catalog::x`) is OWNED if a collection item's `catalogRef == catalog::x`, with a UPC fallback for scan-added items that predate linking. This is the same catalogRef==catalog-doc-id relationship established for linking (see FunkoRepository line ~187). Badge states: OWNED / WANTED / NOT_IN_COLLECTION.
**Consequences:** `PreScanState.NameSearch` + `PreScanMatch`/`OwnStatus` added; `FunkoRepository.findCollectionItemForCatalog(catalogId, upc)` is the join. Tapping a result is intentionally inert for now (Option A); making it open detail/add is the Option-B upgrade if wanted.

### DEC-023: The app ships the enriched catalog as a gzipped asset, streamed (S22)
**Status:** Active
**Context:** The app was still preloading `funko_data.json` — the raw Kenny Chan seed — while every cleanup and enrichment pass since had been landing in `funkodex_base_catalog.json`. None of it had ever reached a device: the catalog users searched was the un-cleaned one, complete with the merch, mis-stapled UPCs and duplicates that had been removed months earlier. The two datasets also differ structurally: Kenny records carry a series LIST and no UPC/number/pricing; the enriched shape carries a single flattened `series` string plus UPC, PriceCharting values, HobbyDB imagery and Funko numbers, and — critically — the series-derived fields (`isExclusive`/`isChase`/`seriesNumber`/`category`) ALREADY COMPUTED from the full list. At 18.1 MB raw the enriched catalog is also too large for the old `readText()` + whole-tree-Gson approach on a low-end device.
**Decision:** Ship `funkodex_base_catalog.json.gz` (2.0 MB) in `assets/`, delete `funko_data.json`, and rewrite `CatalogPreloader` to STREAM it (`GZIPInputStream` + `JsonReader`, 500-record batches) rather than materialise the whole document tree. The preloader reads the enriched `BaseRecord` shape and **trusts the enricher's derived fields instead of recomputing them via `CatalogMapper.deriveSeriesFields`** — recomputing from a single flattened series string would be strictly lossy, since the enricher had the full series list plus funko.com/PriceCharting context when it derived them. `CATALOG_VER` 1→2 forces a reload on existing installs; bump it on every future catalog ship or installs keep their loaded copy. `build_catalog_asset.py` validates the JSON against exactly what the Kotlin expects (real booleans not "True"/"False" strings, string `series` not a list, no `type=funko` records leaked, every record has an id and title) BEFORE writing the asset — a bad asset fails *silently* at startup, skipping records without crashing.
**Consequences:** Catalog updates now ship with the app. `CatalogImporter.kt` was not updated and is now inconsistent with this shape.

**AMENDED S23 — the verification in the original text was false.** It read: *"Verified on a clean emulator: fresh install with no backup loads 20,580 records and name-search works."* The fresh install did happen and name search did return results, but **zero catalog records were on the device**: the asset was named `.gz`, AGP's merger silently decompressed and renamed it, `assets.open()` threw FileNotFoundException, and `FunkoLookupService.searchByName()` fell back to a network lookup that answered the query. The test passed for the wrong reason. This decision's design (gzip + streaming) is sound and unchanged; only the asset NAME changed — see DEC-027. The fresh-install-only note is correct and now doubly so: a restore bypasses the preloader, AND a network fallback makes a total preload failure look like success. Verify the mechanism, not the symptom.

### DEC-024: The Kenny Chan re-fetch is disabled, not deleted (S22)
**Status:** Active
**Context:** `CatalogRefreshWorker.refreshKennyChan()` periodically pulled the raw upstream dataset from GitHub and inserted any handle not already in the DB. Against a cleaned catalog this is actively destructive: 537 non-Pops (cereal, pins, apparel, Dorbz, ReAction, prototypes), 361 mis-stapled UPCs and 13 duplicate clusters were deliberately removed, and Kenny handles do not match the enriched catalog's ids — so the worker's "not already in the DB" test passes for records that DO exist under a different key. It would re-import exactly what the cleanup removed, plus Kenny's missing/incorrect UPCs, and map it through `deriveSeriesFields`, recomputing fields the enricher had already resolved more accurately.
**Decision:** Disable it (renamed `refreshKennyChanDISABLED()`, `newCount = 0` at the call site) rather than delete it, with the reasoning inline so no future session re-enables it casually. `KennyRecord` is retained `@Deprecated` solely so the disabled body still compiles. The community UPC merge and HobbyDB vaulted-status refresh still run — both are additive and keyed on handles that already exist.
**Consequences:** Catalog currency now comes from shipping a new asset (bump `CATALOG_VER`), not from background re-fetch. The 2 build warnings about deprecated `KennyRecord` are expected — they are the annotation doing its job. When the disabled function is finally deleted, delete `KennyRecord` with it and the warnings go.

### DEC-025: Blank a wrong value; never guess a replacement (S22)
**Status:** Active
**Context:** The enrich run matched funko.com records against PriceCharting and copied the matched Pop's identity onto them. Where the match was wrong, the record kept its own correct title and image but gained a *different* Pop's funkoNumber, pcId, series and pricing — e.g. "Evil Queen (Snow White Stained Glass)" carrying "Snow White & Evil Queen" #6 (a Pop! Minis 2-pack), and "Hulk (Brand New Day)" carrying Holiday Hulk's number and price. This is silent: every field is populated, so any "is it filled in?" check passes. It was only caught because Chris owned one of the affected figures and knew the product. 22 such records were found across 451 matches (~5%). Correcting them would have meant hand-sourcing 22 real numbers/pcIds — verification the sandbox cannot do — and a wrong correction is indistinguishable from the original corruption.
**Decision:** When a field is known-wrong but the right value cannot be VERIFIED, blank it. Never substitute a plausible guess. Blanking preserves everything that was correct (title, image, handle — the funko.com data was never wrong) and lets a later enrich re-resolve from those; a guessed value corrupts silently and permanently. Applied throughout S22: the 22 mis-matches blanked, Castiel restored with a blank UPC (no barcode ever resolved), Bash/Kurogiri/Android 17 left image-less rather than showing the wrong figure's picture. Corollary for verification: check VALUES, not presence — the first Evil Queen verification reported "funkoNumber present: YES" while the field held a different Pop's number.
**Consequences:** ~25 records currently carry blanks awaiting one enrich run. That is the intended, recoverable state. The exception is a value read off physical packaging or a retail listing — Mr. Toad's #814 and UPC 889698511728, Evil Queen's UPC 889698816816 — which is verified data, not a guess, and IS written.

### DEC-026: Identity fields cannot distinguish duplicate from variant from non-Pop; only a product page can (S23)
**Status:** Active
**Context:** S23 set out to run one enrich pass over ~25 blanked records and instead spent the session on a dupe hunt that was wrong four times, each time for the same reason. A scan matching catalog rows on `title + funkoNumber + UPC` reported **531** Kenny/PriceCharting duplicate pairs. Removing the title normalisation that stripped parentheticals — which had collapsed three distinct Groots (Holiday / Glow / Prototype) and three Spider-Men (Wood Deco / Gray Skull / Hologram) into single buckets — dropped that to **18**. Of those 18, individual investigation found: 9 genuine complementary merges, 1 chase variant, 2 records whose "conflict" was a stub carrying a wrong pcId, 3 signed editions, 1 clean duplicate, and 1 non-Pop. The scan's own signature — shared title, number and UPC — was **evidence of nothing**, because those fields are shared in three unrelated situations:
- **By design (variants):** a chase, signed edition, or retailer exclusive ships in the base figure's box under the base figure's barcode. `catalog::pc-7531588` holds Hello Kitty #31 **Chase** (pcId 7531589) and legitimately shares UPC 889698434645 with the common. Kurogiri and Android 17 Toyzilla/Chuck Huber signed editions legitimately share their base figures' barcodes — a signature and COA are added to the original box, not a new SKU.
- **By defect (mis-staples):** `catalog::summer-bbq-bash` is **apparel** (hobbyDB 339696, filed under Shirts and Jackets) carrying the real Bash Pop's #623 and UPC 889698506939, mis-stapled. It matched the real `catalog::bash-pop!-vinyl` on every identity field.
- **By corruption (bad crawl rows):** `catalog::pc-7489644` (Katniss) held pcId 10805742, a value that appears **nowhere** on the live PC page for that figure, alongside prices that page does not list.

Every one of these was resolved only by reading the actual product page (PriceCharting, hobbyDB, funko.com), and several were resolved *against* the assistant's stated reasoning. This is S22's open item #2 ("variant-level mis-matching, probably unfixable from text") generalised: it is not a matcher-tuning problem, it is an information problem. The catalog does not contain the fact that distinguishes these cases.

**Decision:** A `title + funkoNumber + UPC` match is a **worklist entry, never a verdict**. No merge, delete, or identity write may be made on identity-field agreement alone. Each candidate requires a product page (or physical packaging, per DEC-025) before action. Corollaries:
1. **Never bulk-apply a dupe rule.** The 18 candidates needed 14 different decisions. A rule that handled the 9 clean pairs correctly would have deleted a chase, corrupted two records with wrong pricing, and destroyed three signed editions.
2. **~~`_id` ≠ `pricechartingId` on a `pc-NNNN` stub is a corruption marker.~~ RETRACTED — see the note below.** This corollary was written from a sample of two and is **wrong**. A catalog-wide scan found the condition on **560 stub records (6.7%)**, and it is a normal crawl artefact, not corruption: the crawler keys the doc by the search-result row it arrived from and stores the pcId of the product page it landed on — two different but legitimate numbers. PriceCharting URLs are name-slugs (`.../funko-pop-star-wars/darth-revan-gamestop-396`) and contain no numeric id, so the URL cannot arbitrate between them; and adjacent ids (e.g. `pc-7506679` holding 7506681) indicate **sibling products**, per the Mr. Toad lesson, not a defect. The two cases that prompted this corollary (Hello Kitty, Katniss) were identified by **product pages**, not by the marker — the marker found neither. **Do not use this as a scan signal.** It is retained here, struck through, as a record of the error: a two-case sample is not a pattern, and "this marker caught the two things I already knew about" is circular.
3. **Neither source is authoritative — merge by field class.** "PriceCharting is the pricing source of record" would have written 3 wrong prices; "Kenny is bad data" would have slugified 9 titles (PC strips punctuation: `Sam "Mayday" Malone` → `Sam Mayday Malone`) and kept a mis-titled record. Kenny holds `_id`/image/punctuated titles; the stub holds `series`/franchise/publisher/pricing. Overrides are per-record, each justified by a page.
4. **Variant naming in the title is cosmetic, not protective.** `coreNameTokens()` and `dedupeAndMerge`'s `coreNoParens()` both strip parentheticals, so "Kurogiri (Toyzilla Signed Edition)" tokenises to exactly `["kurogiri"]` — identical to the bare title. Renaming a record does **not** shield it from a matcher. Only `PC_SKIP_IDS` does.
5. **The blank state is what attracts corruption.** `passPriceCharting`'s candidate filter is `if (!hasPrice) return true;` — so a record blanked under DEC-025 is *guaranteed* to be re-processed. Blanking a wrong value and leaving the record matchable is not a resting state; it needs a skip-list entry or it will be re-corrupted on the next run.
6. **Ask about the artifact, not the interpretation.** The invented "Bash (Toyzilla Signed Edition)" survived because the user was asked to confirm a premise the assistant had already built ("is the twin a dupe of the signed Pop?") rather than asked what the record was. A confirmation of a wrong framing reads exactly like a fact.

**Consequences:** Dedup work is inherently manual and page-by-page; budget for it accordingly and do not accept a scan's headline number. `check_funko_dupes.py`'s 403 collision groups and this session's 531 are the same artefact — both are normalisation noise, and S22's "stop at strict-rule merges" decision is reaffirmed and strengthened: even strict-rule matches need a page. The 15 records S23 removed and 15 it modified are each documented individually in `CLAUDE_STATE_FunkoDex_S23.md` with the page that justified them.

### DEC-027: The catalog asset is named `.gz_`, and that trailing underscore is load-bearing (S23)
**Status:** Active
**Context:** DEC-023 shipped the catalog as `assets/funkodex_base_catalog.json.gz`. It never loaded — not once, on any device, for two sessions. **AGP's asset merger decompresses any `.gz` file under `src/main/assets` and strips the extension** during `mergeXxxAssets`, before AAPT2 runs; `gradlew clean` does not prevent it. The 2.0 MB gzip arrived in the APK as an 18.1 MB plain `funkodex_base_catalog.json`, so `assets.open("funkodex_base_catalog.json.gz")` threw `FileNotFoundException`, `preloadIfNeeded()` returned `AssetMissing`, and the preloader wrote **zero** records. Proven by listing the APK's asset entries (18 MB `.json` where a 2 MB `.gz` was expected) and by pulling the device's CBL SQLite: 8,219 catalog docs, every one `source: USER_SCAN` — i.e. written by `CatalogRefreshWorker` off the network, none by the preloader.

The failure was invisible because `FunkoLookupService.searchByName()` falls back to a network search when the local Couchbase query returns nothing. The app worked. Search returned results. S22 signed off on a fresh-install test that passed entirely on network answers. The one true signal — a startup warning `funko_data.json not found — catalog lookup will use network only` — was recorded as an open item and dismissed as *cosmetic*. It was the whole bug wearing a stale filename.

**Decision:** Ship the asset as `funkodex_base_catalog.json.gz_`. It is ordinary gzip (magic `1f 8b`); only the extension is odd, and it is odd on purpose — AGP does not recognise `.gz_` and passes the file through untouched. Four things must agree, and changing any one alone silently breaks the catalog:
| where | value |
|---|---|
| `app/src/main/assets/` | `funkodex_base_catalog.json.gz_` |
| `app/build.gradle.kts` | `androidResources { noCompress += "gz_" }` |
| `CatalogPreloader.ASSET_NAME` | `"funkodex_base_catalog.json.gz_"` |
| `build_catalog_asset.py` `DEF_OUT` | `funkodex_base_catalog.json.gz_` |
`noCompress` stops AAPT2 deflating an already-gzipped file (no size win, wasted CPU). All four carry an inline comment saying why; **do not "tidy" the extension back to `.gz`.**

**Consequences:** Verified on device: APK contains `assets/funkodex_base_catalog.json.gz_` at 2,012,079 bytes (byte-identical to source), logcat reports `Catalog loaded: 20565 items`, and searching "Bash" returns the Fortnite Pop #623 from the LOCAL catalog. First time the shipped catalog has ever reached a device. **The verification rule this buys, mandatory after any catalog ship:** (1) confirm the asset is really in the APK — `[IO.Compression.ZipFile]::OpenRead("app-debug.apk").Entries | ? { $_.FullName -like "assets/funkodex*" }`, and call `.Dispose()` or the open handle breaks the next `gradlew clean`; (2) confirm logcat on a FRESH install says `Catalog loaded: <n>` with n ≈ the asset's record count. "It searched and found something" proves nothing.

---

### DEC-028: Nothing else may write `catalog::` documents until the preload completes (S23)
**Status:** Active
**Context:** With the asset finally readable, the first load reported `Catalog loaded: 14856 items` and the device held 19,006 of 20,565 records. `FunkoDexApp` scheduled `CatalogRefreshWorker` at startup step 4a and began the preload at 5c; on a fresh install the worker ran immediately and its `refreshCommunityUpcFile()` wrote `catalog::` docs while the preloader was still streaming (logcat: UPC merge at 22:42:31, preload finished 22:42:45). `CatalogPreloader.writeChunk()` **skips any doc id that already exists** — deliberate and correct, since it makes a partial re-run idempotent and stops the preloader clobbering user-scanned records — so every id the worker reached first was dropped, permanently.

Permanently, because the version marker was then written unconditionally with `count = imported`. A short load marked itself complete, `preloadIfNeeded()` short-circuits on that marker forever, and the next launch logged `Catalog already present: 0 items`. The 1,559 missing records could never self-heal.

**Decision:** Two guards, both required.
1. **Ordering:** `CatalogRefreshWorker.schedule()` moved *inside* the preload coroutine, after `preloadIfNeeded()` returns. The worker is the only known early writer; this removes the race at its source. Do not move it back to step 4a "for startup parallelism" — that is what caused this.
2. **Completeness gate:** the marker is written only if `countCatalogDocs()` >= `MIN_EXPECTED_ROWS` (20,000). A short load logs a warning, returns `ParseError`, and **retries on the next launch** rather than freezing. The marker and the `Loaded` result now report actual DB rows, not `imported` — which under-counts by design, since `writeChunk` skips ids another writer created.

The gate is deliberately a floor (20,000) not an equality check: `writeChunk` legitimately skips pre-existing ids, and the catalog grows with each ship.

**Consequences:** Verified on device — fresh install loads 20,565/20,565; the worker is scheduled after the load completes; relaunch reports `Catalog already present: 20565 items`. Any future feature that writes `catalog::` docs at startup (a sync, an import, a migration) must be scheduled after the preload or it will silently eat records. The `MIN_EXPECTED_ROWS` floor must be raised if the catalog ever shrinks below 20,000 legitimately, or the preload will retry forever.

**A known benign warning, so no one else chases it:** the marker save logs `W CouchbaseLite/DATABASE: com.couchbase.lite.LiteCoreException: conflict [1, 8]`. It is logged at W by CBL's internal logger, which handles the retry itself; the save lands. Proof: the load reports the true DB count on that same run, and the next launch reads the marker back. Changing the save to `getDocument(MARKER_DOC)?.toMutable() ?: MutableDocument(...)` (the pattern from `GroupPrefRepository.kt:61`) did NOT silence it, and it fires on `pm clear` installs where no marker can pre-exist — so the obvious explanation is wrong. Root cause unknown. Leave it unless it becomes an E.

### DEC-029: Enrich fixes must go to SOURCE and survive re-derivation, not just the output (S23)
**Status:** Active
**Context:** The S23 enrich run made 21 wrong PriceCharting matches (short/ambiguous titles
matched to unrelated Pops — Rowdy→Roddy Piper, Edison→Community, Mark→Iron Man) and its dedup
removed 6 owned Pop! Disney figures (orphaning the user's collection). Fixing these in the
enriched OUTPUT is worthless: the next `node enrich.js` regenerates the output and re-breaks
everything. Three separate mechanisms have to agree or a fix silently reverts.

**Decision:** A durable enrich fix touches all of these:
1. **The base** (`funkodex_base_catalog.json`) — the corrected numbers/franchises/UPCs live
   here, because a fresh `--input` build reads the base. Applied via `apply_resolutions.py`
   from `S23_mismatch_resolutions.json`.
2. **PC_SKIP_IDS** in enrich.js — the corrected ids, so the PC matcher never re-matches them
   and dedup never removes them. NOTE the skip-list needs TWO code guards: the index guard
   (never a merge TARGET) AND a source-loop guard added S23 (never merged AWAY). The second was
   missing and is why the 6 owned figures were removed despite intent.
3. **Blank the derivation SOURCES, not just the derived field.** `deriveGroupingFields`
   unconditionally overwrites `franchiseSuggestion` from `franchiseFromPcSeries(pcSeries) ||
   franchiseSuggestionFromUrl(pricechartingUrl)`. Repointing the franchise alone is clobbered on
   the next run. Blank `pcSeries` AND `pricechartingUrl` so the derive finds no source and keeps
   the hand-set value. 14 of 21 needed this; without it they reverted (Rowdy showed WWE not NFL
   until both sources were blanked).

**Consequences:** Proven durable — re-ran enrich from the fixed base with `--skip-*` and every
one of the 21 mismatch fixes, 6 owned figures, and 14 franchise corrections survived. A fix that
only touches the enriched output is not done. Corollary for triage: the enricher OVERWRITES
`franchiseSuggestion` to match its (possibly wrong) PC guess, so franchise-agreement between a
record and its matched slug is NOT evidence the match is correct — it is circular. Only a
product page decides (DEC-026). Corollary for scripts: BOTH `build_catalog_asset.py` and
`merge_backup.py` default `--base` to the raw `funkodex_base_catalog.json`; pass the enriched
file explicitly or you ship the un-deduped base.

**Known unfixed:** the matcher root cause. Short one-word titles still mis-match to longer
unrelated slugs on every crawl; PC_SKIP_IDS only protects known-bad ids, not new records. The
matcher itself (`shareAllShortTokens` and the PC candidate filter) needs tightening — deferred.

### DEC-030: The PriceCharting console crawl (Pass 3b) under-captures large consoles — root cause of "owned but not in catalog" (S23)
**Status:** RESOLVED S24 — see RESOLUTION below. Original root-cause hypothesis (accepts partial lazy-load) was CORRECT in mechanism but WRONG in detail; the real defect was the scroll give-up logic, and fixing it exposed a second, worse bug (DEC-031).
**Context:** ~154 owned, scanned figures have no catalog record to link to (owned items
show catalogRef=""). They are NOT vaulted-vintage oddities — median Pop# ~1286, 148/154 use
the current 889698 UPC prefix — and they demonstrably EXIST on funko.com, PriceCharting, and
eBay (e.g. Stitch 626 #125, funko.com item 4671, PC id 7473576, upc 849803046712). The scan
found them live at scan time; the enrich crawl never added them.

**Investigation (done, not assumed):**
- Base catalog has 53 Stitch figures but NOT Stitch 626 #125 — so the base seed never had it.
- Pass 3b (PriceCharting discovery crawl, `--pc-crawl`, ON by default) DOES add net-new records
  (`enriched.push({handle: pc-<id>, ...})`, ~L1789) — that is how ~8,359 pc- records exist.
- Stitch's pcId 7473576 / upc 849803046712 appear in NEITHER the base NOR the pre-dedup
  enriched file (enr.json, 20,680). So dedup did NOT remove it (cf. DEC-029's 6 owned figures,
  which dedup DID remove) — **discovery never captured it in the first place.**
- Pass 3b crawls `/console/funko-pop-disney` (it's in the console list). Disney is one of the
  largest consoles (~2500+ figures per PC's own "Prices for all N" count). We captured only
  **654 Disney pc- records** (range #4–#2038, sparse — 75 under #200). Not a clean truncation
  cutoff: sparse incompleteness.
- The crawl loads a console page then SCROLL-scrapes a lazy-loaded table until row count
  reaches the stated target (`targetCount`), with MAX_SCROLLS=200 and a 12-try stall tolerance.
  On a stall it logs `[warn] loaded X of target — set may be incomplete` and **continues with
  the partial DOM** rather than failing. Large consoles stall before fully rendering, so figures
  past the loaded rows are silently absent from the catalog.
- The 154 absences cluster in exactly the biggest consoles: ~half are Disney-family (21 Lilo &
  Stitch, 9 Disney-Christmas, 9 Disney, plus Sleeping Beauty/Snow White/WDW50/Hocus Pocus),
  with Star Wars and Marvel also present. This is the fingerprint of large-console under-capture.

**Decision / fix direction (DEFERRED — needs its own session + a multi-hour re-crawl to prove):**
The scroll-scrape must not accept a partial lazy-load. Options, best first:
1. Replace DOM-scroll with PriceCharting's paginated data or CSV/collection-tracker export for
   each console (deterministic completeness, no lazy-load race).
2. Make the completeness gate REJECT an incomplete set (loaded < target − margin) and force a
   real retry / harder scroll, instead of warning-and-continuing.
Validation: re-crawl, confirm Disney jumps from ~654 toward ~2500 and Stitch 626 #125 appears
as a pc- record, then it links to the owned figure automatically.

**Do NOT** "fix" this by hand-building the 154 catalog records — that patches symptoms while the
crawl stays broken and re-misses on the next run. Per-figure creation is only a last resort for
a figure PriceCharting genuinely lacks (rare). The systemic fix recovers most of the 154 at once.

**RESOLUTION (S24):**
Diagnosed against live pages and real data (not assumption), the chain was:
- The completeness gate (loaded < target ⇒ leave set unmarked, retry next run) already existed —
  DEC-030's "warns and continues, marks done anyway" was stale on that point.
- The parser (`parsePriceChartingListing`), dedup seed (`havePcId`), and gate were all verified
  CORRECT against the real Disney console HTML (1681/1681 rows parsed, every pcId present,
  Stitch 626 = 7473576 extracted). Discovery was not the failure.
- Real defect: the scroll-to-target loop **gave up after a fixed 12 stalls**, accepting a partial
  load as terminal when the next lazy-load batch was merely slow/rate-limited. Non-deterministic:
  the SAME Disney page loaded 1681 rows on one run and stalled at 900 on another.
- Attempted fix via the `js-next-page` "more results" form FAILED — that form is a no-JS fallback
  (`method=POST action=""`); submitting it navigates the page and destroys the Puppeteer context.
  The page accumulates rows via scroll-triggered AJAX; scroll is the correct trigger.
**Fix (in enrich.js, verified):** replaced the give-up-on-stall logic with keep-scrolling while
short of target, a scroll "nudge" (jump up then back to bottom) to re-arm debounced lazy-loaders,
and a generous no-progress budget (~40 idle tries ≈ 30–40s) before accepting a stall. Result:
Disney loads 1681/1681 in a single pass; Stitch 626 #125 (7473576, upc 849803046712) captured.
**Also added:** `--pc-crawl-only <slug,slug>` flag to crawl specific consoles (testing/targeted re-runs).
**Validation:** full all-console crawl produced a clean catalog; base 20,552 → 21,672 (+1,120),
the recovered records being the previously-missing figures across Disney/Marvel/Star Wars/etc.
Promoted to funkodex_base_catalog.json S24 (backup: funkodex_base_catalog.BACKUP_preS24_20260719.json).

---

### DEC-031: Dedup over-merges distinct variants — the pcId guard (S24)
**Status:** Active — fix applied and validated offline; exposed by the DEC-030 scroll fix.
**Context:** Fixing DEC-030 (loading full consoles for the first time) fed `dedupeAndMerge` its
full diet of variant figures and revealed a pre-existing, silent data-destroying bug. On a clean
single-run Disney crawl, dedup removed 274 records — and **229 of them (84%) were WRONGFUL**:
distinct products with DIFFERENT pcIds being merged into each other and deleted. Examples:
"Ariel [Diamond]" (7488113) merged into "Ariel" (7488112); "Belle [Gold]" (7473821) into
"Belle (Dancing)" (7473820); "Jack Skellington [Blacklight]" into "Jack Skellington (Prototype)".
**Root cause:** `coreName()` strips `[brackets]` and `coreNoParens()` strips `(parens)` before
comparing titles, so every variant (Diamond / Blacklight / GITD / Chase / Gold / SDCC …) reduces
to the same core as its base figure. Since PriceCharting numbers variants close together, the
funkoNumber bucket also matches, and the base/variant pair merges — the bracketed variant being
the one deleted. Why it didn't surface before: past catalogs were built with `--skip` finishing
partial data, so few variants were ever newly crawled together in one pass. 200 of the 229
wrongful merges had a `[variant]` tag in the removed title.
**Decision / fix:** in the PriceCharting merge loop, add a **pcId guard** — if a candidate record
and its potential merge target BOTH carry a pricechartingId and those ids DIFFER, they are distinct
products and must never merge (a PriceCharting id uniquely identifies a product). Records with no
pcId on one side fall through to the existing name test unchanged (e.g. matching a PC row to a
HobbyDB canonical). This supersedes the "matcher needs tightening" known-unfixed note in DEC-029.
**Validated (offline, against the real Disney removal log before any code change):** the guard
blocks exactly the 229 wrongful merges (records saved) and preserves exactly the 45 legitimate
same-pcId dedups — 0 wrongful merges slip through, 0 legit merges wrongly blocked.
**Note on shared pcIds:** ~765 pcIds are shared across multiple records in the base catalog
(pre-existing) — these are PriceCharting's own data shape (prototype clusters, box+redeco families
filed under one id), NOT duplicates, and are correctly preserved. The guard only blocks merges
between records with DIFFERENT pcIds.

---

### DEC-032: Two data-quality classes logged for a focused session (S24)
**Status:** Active — quantified, fixes deferred (each needs care, not a rushed pass).
**Context:** Diagnosing a mis-scan (Peter Pan with Flute #1344 scanned as a plain "Peter Pan")
surfaced two distinct, pre-existing data-quality classes, both separate from the DEC-030/031 crawl bugs.
**Class A — genuine UPC collisions (66 real errors):** 1,418 UPCs are shared across 3,210 records,
but 1,352 are legitimate variant families (prototypes/re-decos that physically share a UPC — Funko
reused UPCs across variants; correct data, must NOT be touched). Only **66 are genuinely-unrelated
figures wrongly sharing a UPC** (e.g. Nick Fury/Franken Berry on 830395025421; Winter Soldier/Rhino/
Red Hulk on 746775305086; Wichita/Witchita typo-dupe on 889698491020; the Toyzilla pair on
889698451376). Fixing requires per-record UPC verification against source — guessing would invent
bad data (cf. the S23 invented-Toyzilla-record failure). The better structural fix may be app-side:
a scan hitting a multi-record UPC should disambiguate (chooser) rather than silently pick one.
**Class B — under-titled PC records:** PriceCharting's terse titles drop the box's variant descriptor.
#1344 IS in the catalog (upc 889698706971, pc 7488433, franchise "Peter Pan 70th Anniversary") but
titled just "Peter Pan" instead of "Peter Pan with Flute" — so it scanned to a bare Peter Pan and
looked wrong. Two sub-classes: (1) **133 self-fixable** — a richer title already exists in-catalog
from a non-PC source at the same UPC, just needs applying to the PC record (no external lookup);
(2) **PC-only under-titled** (like #1344 itself) — PriceCharting is the only source, terse title,
NOT detectable by cross-source comparison and only fixable via funko.com enrichment (this is what
Pass 5 / funko.com detail pages are meant to supply). Count of sub-class (2) is open-ended.
**Decision:** deferred to a focused data-quality session. Do NOT bulk-edit titles or UPCs by
heuristic — both classes conflate legitimate variant families with real errors, and a naive pass
would destroy correct data (the recurring lesson of DEC-029/030/031).

---

## SUPERSEDED / DEPRECATED (kept for reference — never deleted)

### (none yet)
When a decision above is reversed, set its Status to "Superseded by DEC-NNN", move it here, and add the replacement to ACTIVE DECISIONS.
