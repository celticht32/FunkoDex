# Test Tracker — Functional Validation

<!-- Version: v2.0 (2026-06-20). Tracks COMPLETE_TEST_PLAN.md v2.0. Reconciled
     to Session 14 (catalog last-enricher-wins, re-link, field protection);
     prior reconcile Session 13 (commits 9a315bf, 294dc84, c9fa9c3, d38fd18). -->

Tracks execution of `COMPLETE_TEST_PLAN.md` (code-verified against the repo).
Update this file as each item is run — check the box and add a one-line
result/note in the log at the bottom.

## Known wiring gaps — RESOLVED 2026-06-12

Both gaps below were fixed and merged (commits `74c5616`, `6f2c523`). Build
compiles and runs clean on device. A9 and B1/B2/B3/B6 are no longer blocked —
they're untested like everything else in this tracker.

- **`ReportsScreen.kt`** — created at
  `ui/screens/reports/ReportsScreen.kt` + `ReportsViewModel.kt`. Wired into
  `FunkoDexNavHost.kt` (import/call site were already present). Was previously
  affecting **A9**.
- **`CatalogDataSection`** — now invoked from the "Catalog" section of
  `SettingsScreen.kt`, reusing the existing `catalogSettingsViewModel`
  instance. Was previously affecting **B1, B2, B3, B6**.

## Session 14 changes (2026-06-20)

Code-only; nothing tested on device this session. All five files verified
IDENTICAL against `origin/master` after push. New/changed test surface:

- **Enriched-import parser fix** (`CatalogImporter.toEnrichedRecord`) — 9 keys
  that were silently dropped are now read: `marketValueComplete` (PRIMARY in-box
  price), `releaseDate`, `ebayEpid`, `amazonAsin`, `printRun`, `publisher`,
  `pcSeries`, `pcDescription`. **After import, a catalog record sourced from a
  JSON entry that has these keys must now carry them.** Affects D1a/D1c and any
  market-value display test — `marketValueComplete` previously never landed.
- **Catalog merge → last-enricher-wins** (`CatalogImporter.mergeRecordInto`) —
  re-importing an enriched JSON now OVERWRITES enrichment fields on existing
  catalog docs and RECOMPUTES seriesList + category (+ primarySeries, exclusive,
  chase, seriesNumber) from the incoming tags. **New test: import file v1, then
  import file v2 whose record has a longer/corrected series list + different
  category for the same handle; confirm the existing catalog doc's category and
  seriesList UPDATE (not just new records). Adds D1d.** Preserved fields:
  handle/title/imageUrl — confirm those do NOT change on re-import.
- **Collection re-link** (`CollectionRelinkService`, Settings → Catalog →
  "Re-link collection to catalog") — refreshes owned funko:: items from the
  enriched catalog. **New tests (add as Part B/F):**
  - **R1 fill:** owned item missing UPC/price/image/franchise/category, with a
    valid catalogRef → after re-link, those fields are filled from the catalog.
  - **R2 refresh (marker present, not edited):** owned item added/edited after
    the S14 build (has the marker), enriched catalog has a corrected category →
    re-link overwrites the item's category + genre.
  - **R3 protect (marker present, edited):** set a custom category on an item via
    the edit screen + save, then re-link → the custom category is PRESERVED
    (userEditedFields contains "category").
  - **R4 migration (marker absent):** item owned before the S14 build (no marker)
    with a non-blank category → re-link does NOT overwrite it (fill-only fallback);
    a blank field IS filled.
  - **R5 unmatched:** owned item with no catalogRef and no UPC match → untouched,
    counted "unmatched".
  - **R6 idempotent:** run re-link twice with no catalog change between → second
    run reports 0 enriched.
  - **R7 manual market value:** item with `marketValueIsManual = true` → re-link
    never touches marketAvg.
  - **R8 sequencing:** re-link BEFORE importing the enriched JSON links against the
    asset/seed catalog only (documented constraint — verify the UI/flow guides
    import-first).
- **Field-protection marker roundtrip** (`FunkoMapper` ↔ `userEditedFields`) —
  **D2 unit-test additions (see `RELINK_FIELD_PROTECTION_SPEC.md`):** mapper
  roundtrip with marker present / present-empty / absent (null); edit-screen
  `markEdited` stamps the right FIELD_ key with no duplicates.
- **Backup/restore unchanged but newly relevant** — the new enriched fields and
  the `userEditedFields` marker must survive Part C (backup → restore →
  force-restore). Serializer is field-agnostic; **add a C-part assertion that a
  re-linked item's refreshed fields + marker round-trip through a backup.**
- **No production test files changed** — `gradlew test` count unchanged; the new
  R-series and marker tests above are NOT yet written. **D2 carries an expanded
  coverage-gap note for S14.**

## Session 13 changes (2026-06-20)

Code-only; nothing tested on device this session. Reconciled into the plan as
**v2.0** from source commits `9a315bf`, `294dc84`, `c9fa9c3`, `d38fd18` (all on
`origin/master` @ `938a5f0`). New/changed test surface:

- **Scan reads the Couchbase catalog first** (`c9fa9c3`,
  `FunkoLookupService.lookupCatalogByUpc`) — bundled `funko_data.json` is only a
  seed; the live catalog is the source of truth, queried first, then bundled
  JSON, then network. UPC matching normalizes leading zeros both ways, and seeds
  `marketAvg` from the catalog's PriceCharting Complete price. **Changes
  preconditions for A2a/A2c/A2d; adds A2g.** Run A1 (preload) before A2.
- **Live PriceCharting refresh tier** (`d38fd18`, `PriceService.fetchPriceCharting`
  + `PriceSource.PRICECHARTING`) — a refresh on an item with a catalog-stored
  `pricechartingUrl` re-scrapes that page via plain OkHttp GET (Android UA, no
  headless browser) and reads `#used_price`/`#complete_price`/`#new_price`. Runs
  **before** the retail short-circuit. **Adds A4l; affects A4g/A9/B3.**
- **Channel3 rewritten to the real API** (`9a315bf`) — `POST /v1/search`,
  `x-api-key` header, JSON body; **a key is now required for every call (no free
  tier).** Plus a **key-import-from-file** path (`importKeysFromFile`,
  `funkodex_keys.json` in Downloads). **B1 rewritten; adds B1b.**
- **Channel3 key-entry UI hidden by default** (`d38fd18`,
  `SHOW_CHANNEL3_KEY_UI = false`) — manual row + dialog suppressed; import path
  still functions. **B1 reachability changed; B1b may be BLOCKED if no UI entry
  point exists in the default build — verify and flag.**
- **UPC-based import de-dup + merge-on-collision** (`294dc84`,
  `buildUpcIndex`/`mergeRecordInto`) — imports match handle → UPC → title; an
  insert that collides now **merges (fill-only) instead of skipping**.
  `marketValueComplete` + full PriceCharting metadata now flow through import.
  **D1a path coverage updated (its 2-skipped count is unchanged — no collisions
  in that file); adds D1c.**
- **No test files changed** — `gradlew test` is still 72, but `FunkoMapperTest`
  doesn't assert the new `pricechartingUrl` field and `FunkoLookupServiceTest`
  predates the catalog-first path. **D2 carries a coverage-gap note.**
- **Known stale code comment (not a test target):** `SettingsScreen.kt` says
  "the free Channel3 tier still works" — it doesn't (`9a315bf` removed it).
  Flagged for code cleanup.

## Session 12 changes (2026-06-19)

Code-only; nothing tested on device this session. New/changed test surface:

- **Manual-UPC validation** — the editable UPC field on manual-add now rejects
  malformed entries (bad check digit / wrong length) and shows "Valid UPC" when
  good; Add is blocked on a non-blank invalid UPC. New `util/UpcValidation.kt`.
- **"Enter details manually" button** on the Add start screen → blank manual-add.
- **"Add another"** after a save now goes straight to the live camera, not the
  start chooser.
- **Variant-aware pricing** (eBay/HobbyDB/Channel3 name queries) — a chase or
  exclusive should price against its own listings where data exists.
- **eBay parser confirmed working** against a live page; earlier 403s are a
  fetch-time bot block, not a parse bug. eBay may contribute prices on a real
  device.
- **Channel3 is dormant** (no key) — that tier won't fire in testing.
- **Leak fixes** (HTTP responses, camera executor) — no user-visible behavior
  change expected; the camera-rebind-on-resume path (A4j) is the one to spot-check.

## Session 9 fixes (2026-06-13, commit `d69a4ec`)

- Enriched catalog import (D1b) was broken (`ArrayList cannot be cast to
  java.lang.Void`) — fixed via tree-based JSON parsing. Now PASS, see Result log.
- Catalog `category` field could be stored as `"Pop! Vinyl"` (a format
  descriptor, not a real category), making 714 records unsearchable via
  `FunkoLookupService.searchByName`'s category filter (separate bug: broken
  slug-vs-name comparison, also fixed). Both fixed; merge-path repair applied
  on re-import. **A3a should be re-tested** — the search-filter fix affects
  the whole catalog, not just the 714 affected records, so it may change
  results for other categories too.
- `db.getDatabase().getDocument()` → `db.getCollection().getDocument()` in
  `FunkoLookupService` (deprecation cleanup, Session 7 Collection API pattern).
- `Icons.Default.Logout` → `Icons.AutoMirrored.Filled.Logout`,
  `Icons.Default.TrendingUp` → `Icons.Default.AttachMoney` (deprecation
  cleanup in new `ReportsScreen`/`SettingsScreen` code).
- "Import Enriched Catalog" picker now defaults to Downloads
  (`OpenDocumentInDownloads`, API 26+).

See `CHANGELOG.md` Session 9 entry and `LESSONS_LEARNED.md` #26–27 for full detail.

## Session 10 fixes (2026-06-13)

- Reports "Est. Market Value" and "Total Retail Value" always showed $0.00 —
  `DetailViewModel.refreshPrices` now persists `marketAvg`/`resolvedRetail`
  onto the item; `ReportsScreen` now refreshes `CollectionStats` on
  `ON_RESUME`. **A9 and B3 should be re-tested** with a price refresh +
  navigation round trip to confirm Reports reflects Detail-screen values.
- New `FunkoItem.resolvedRetail` field + `effectiveRetail` computed property
  — "Total Retail Value" and all per-item "Retail" displays/exports now use
  `effectiveRetail` (catalog `retailPrice` if set, else `resolvedRetail`).
  Catalog `retailPrice` / Tier 1 price-waterfall behavior is unchanged.
- DetailScreen's "I only have the variant — want the original" control was a
  `TextButton` with no visible chrome (looked like static text) — now an
  `OutlinedButton`.
- Deprecation cleanup: `Icons.Default.ArrowBack` (DetailScreen.kt,
  CategoryFilterScreen.kt) and `Icons.Default.HelpOutline` (PreScanScreen.kt)
  → `Icons.AutoMirrored.Filled.*` (+ required imports);
  `db.getDatabase().getDocument/save` → `db.getCollection().getDocument/save`
  in `DetailViewModel.kt`; `query.removeChangeListener(token)` →
  `token.remove()` in `FunkoRepository.kt` (both live-query flows); removed
  no-op `@OptIn(ExperimentalGetImage::class)` in `ScannerScreen.kt`; fixed
  `@Suppress("DEPRECATION")` placement for the legacy `vibrate(50)` fallback
  (was only suppressing the declaration, not the call).

See `CHANGELOG.md` Session 10 entry and `LESSONS_LEARNED.md` #28–30 for full
detail.

## Session 11 fixes (2026-06-14)

New/changed surface that affects existing items and adds new ones. See
`CHANGELOG.md` Session 11 and `LESSONS_LEARNED.md` #31–33 for full detail.

- **Manual add of catalog-missing items** — new `ManualAddSheet` reachable from
  the "Barcode not in catalog" sheet AND the toolbar manual-search sheet. Creates
  a `FunkoItem` (name required; UPC carried/locked from scan or editable) and
  optionally queues a `USER_MANUAL` `CatalogContribution`. **Affects A2d** (the
  not-found path now offers manual add, not only catalog match) and adds new
  coverage — see DEVICE_TEST_PLAN §10.
- **Punctuation-tolerant name search** — `FunkoLookupService` now token-matches
  (normalize + all-tokens). "mr toad" matches "Mr. Toad". **Re-test A3a and A6** —
  search behavior changed catalog-wide, not just for punctuated names.
- **Manual market value** — editable `marketAvg` in detail edit; a manual value is
  a fallback that a real feed (`snapshot.avg>0`) overwrites; retail-only hits do
  not. **Affects A4b and A9/B3** (Reports market totals now include manual values;
  a failed refresh must NOT blank a manual value — regression to verify).
- **Image URL entry + http→https** — editable Image URL on manual add and detail
  edit; detail edit auto-re-downloads on URL change. All image loads upgrade
  `http://`→`https://`. **Affects A4d and DEVICE_TEST_PLAN §6** (the
  "Image not available / CLEARTEXT" case should now load over https).
- **Scanner frame-confirmation + retry** — `BarcodeAnalyzer` needs 3 consecutive
  identical reads; NotFound sheet gained "Scan again" + empty-state. **Affects
  A2a/A2d.**
- **Camera black after screen-saver — FIXED** — scanner now rebinds the camera on
  `ON_RESUME`. New device test, DEVICE_TEST_PLAN §3 addendum / §11.
- **eBay pricing** — RSS→HTML scrape; parser **verified working** Session 12
  against a live page. Earlier 403s are a fetch-time bot block (datacenter IP),
  not a parse failure. **B3 / price tests:** eBay *may* contribute prices on a
  real device — check the logs rather than assuming it returns nothing. Variant-
  aware for chase/exclusive items.
- **Manual market value wipe-on-refresh — FIXED** (staleDays `Int.MAX_VALUE`
  overflow). Regression: enter a manual market value, hit refresh on an item with
  no feed data, confirm the value survives.

---

## Part A — Core Collection Features

- [ ] A1. First launch, splash-gated preload, search proves catalog
- [ ] A2a. Scan → found → "Added!" flow
- [ ] A2b. Scan → want list (verify via Check badge / re-scan)
- [ ] A2c. Scan → "Already in your collection" (variant / variant-missing-original / update)
- [ ] A2d. Scan → "Barcode not in catalog" → match (silent USER_SCAN contribution)
- [ ] A2d-2. Scan → "Barcode not in catalog" → **Add manually** (UPC locked; name required; saves; USER_MANUAL contribution queued if shared) — Session 11
- [ ] A2e. Offline scan → "Scan queued — no network" → auto-resolve + notification
- [ ] A2g. **Catalog-first UPC resolution + leading-zero normalization** — Session 13
- [ ] A3a. "Search Catalog" bulk add (incl. category-filtered results)
- [ ] A3b. Batch scan FAB → "Save all (N)"
- [ ] A4a. View mode (status card "Tap to move", chips, Market Price)
- [ ] A4b. Edit fields ("Edit Funko" / "Save")
- [ ] A4c. UPC scan dialog + "Share UPC with community?" prompt (USER_EDIT)
- [ ] A4d. Photos: camera / gallery (Photo Picker, no permission prompt) / "Fetch from catalog"; "Save photo as" Main/Variation/Both
- [ ] A4e. Variant edit: description, price, remove
- [ ] A4g. **Manual market value** — enter value in edit; shows on card ("Manually set"); refresh with no feed data does NOT blank it (Session 11 regression); a real market feed overwrites it — Session 11
- [ ] A4h. **Image URL entry** — paste image URL in edit; saves; auto-re-downloads thumbnail on URL change; http URL loads over https — Session 11
- [ ] A4i. **Manual search → Add manually** — toolbar manual search "No results" → Add manually (UPC editable) → saves — Session 11
- [ ] A4f. **Blob-preservation regression (CRITICAL — Session 7 risk)**
- [ ] A4l. **Live PriceCharting refresh tier** — PC-URL items refresh from PriceCharting (Complete grade primary), runs before retail short-circuit — Session 13
- [ ] A5. Delete via card kebab menu AND detail trash → "Remove from collection?"
- [ ] A6. Search / segmented sort (4 options) / "All" + franchise chips; confirm category prefs do NOT filter My Dex
- [ ] A7. Price alerts (want-list only; "Target price (USD)")
- [ ] A8. "My collection categories": toggles, genre toggle, Reset, restart persistence
- [ ] A9. Reports + export .xlsx (4 sheets) / .csv
- [ ] A10. **Check tab — Pre-Purchase Check (4 overlays, 4s auto-reset, re-scan cancel)**
- [ ] A11. App theme (6 options) + Diagnostics log share

## Part B — Integrations

- [ ] B1. Channel3 — row hidden by default (`SHOW_CHANNEL3_KEY_UI=false`); no-key ⇒ tier dormant (no crash); imported key persists — **rewritten Session 13**
- [ ] B1b. Channel3 key import from `funkodex_keys.json` (toast confirms; unwired keys reported skipped; bad-file errors) — or **BLOCKED** if no UI entry point in default build — Session 13
- [ ] B2. HobbyDB OAuth connect/persist/disconnect
- [ ] B3. eBay OAuth connect/persist/disconnect — re-confirm subtitle copy ("RSS feed" string is stale)
- [ ] B4. Drive connect / back up now / **lapsed grant (notif id 3002)** / disconnect
- [ ] B5. Contributions: silent USER_SCAN + USER_EDIT prompt; toggle arms/cancels GitHubUploadWorker; WORKER_URL-unset skip log
- [ ] B6. Catalog refresh worker log sequence — "Refresh now" + scheduled run

## Part C — Backup/Restore (run LAST)

- [ ] C1. Backup: Downloads file + "Share backup via…" + JSON structure (no catalog/system docs, blob encoding correct)
- [ ] C2. Restore: "Replace your collection?" → exact state restored, catalog/cat-prefs intact
- [ ] C3. **Force restore: "Database rebuilt" → restart → re-preload → correct collection (HIGHEST PRIORITY — Session 7 reopen/Collection risk)**

## Part D — Automated

- [ ] D1a. Enriched import — 5-record test file, exact counts (1 or 0 updated / 2 or 3 added / 2 skipped / 0 errors). NB Session 13: 2-skipped unchanged (no UPC/handle collisions in this file); path-coverage note updated
- [x] D1b. Enriched import — full 14,314-record file — PASS (see Result log)
- [ ] D1c. **UPC-collision merge** — same-UPC/diff-handle records merge to one doc (1 added / 1 updated / 0 skipped); colliding record's price + PC metadata present; re-import idempotent — Session 13
- [ ] D2. `gradlew test` — 72 tests green (9+11+8+15+20+9). NB Session 13: count unchanged but `pricechartingUrl` round-trip and catalog-first lookup are not asserted (covered by device A2g/A4l)
- [ ] D3. SecureKeyStore v2 prefs format / no Cipher/KeyStore exceptions across restart

## Part E — 16 KB Regression

- [ ] E1. 16 KB emulator condensed smoke test (A1, A3a, A4d+A4f, C1)

---

## Result log

(Add one line per completed item: date · item · PASS/FAIL/BLOCKED · note)

- 2026-06-13 · D1b · PASS · Full 14,314-record `funko_data_enriched.json`, first run: 13,585 enriched / 725 added / 4 skipped / 0 errors, 51s. Matches HANDOFF.md dry-run estimate (~13,583/~725/~4).
- 2026-06-13 · D1b (re-import) · PASS · Same file, second run after category fix: 14,310 updated / 0 added / 4 skipped / 0 errors, 47s. Confirms idempotency + category repair (714 docs). Verified via Search Catalog → "perpetua" returning "Papa V Perpetua · Music" (was 0 results before fix).
