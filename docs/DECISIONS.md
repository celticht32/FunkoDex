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
**Consequences:** Catalog updates now ship with the app. Verified on a clean emulator: fresh install with no backup loads 20,580 records and name-search works. Note the fresh-install path is the ONLY way to exercise this — restoring a backup bypasses the preloader entirely, because the backup carries its own catalog. Test it that way after any preloader change. `CatalogImporter.kt` was not updated and is now inconsistent with this shape.

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

---

## SUPERSEDED / DEPRECATED (kept for reference — never deleted)

### (none yet)
When a decision above is reversed, set its Status to "Superseded by DEC-NNN", move it here, and add the replacement to ACTIVE DECISIONS.
