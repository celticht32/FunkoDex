# Test Tracker — Functional Validation

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

---

## Part A — Core Collection Features

- [ ] A1. First launch, splash-gated preload, search proves catalog
- [ ] A2a. Scan → found → "Added!" flow
- [ ] A2b. Scan → want list (verify via Check badge / re-scan)
- [ ] A2c. Scan → "Already in your collection" (variant / variant-missing-original / update)
- [ ] A2d. Scan → "Barcode not in catalog" → match (silent USER_SCAN contribution)
- [ ] A2e. Offline scan → "Scan queued — no network" → auto-resolve + notification
- [ ] A3a. "Search Catalog" bulk add (incl. category-filtered results)
- [ ] A3b. Batch scan FAB → "Save all (N)"
- [ ] A4a. View mode (status card "Tap to move", chips, Market Price)
- [ ] A4b. Edit fields ("Edit Funko" / "Save")
- [ ] A4c. UPC scan dialog + "Share UPC with community?" prompt (USER_EDIT)
- [ ] A4d. Photos: camera / gallery (Photo Picker, no permission prompt) / "Fetch from catalog"; "Save photo as" Main/Variation/Both
- [ ] A4e. Variant edit: description, price, remove
- [ ] A4f. **Blob-preservation regression (CRITICAL — Session 7 risk)**
- [ ] A5. Delete via card kebab menu AND detail trash → "Remove from collection?"
- [ ] A6. Search / segmented sort (4 options) / "All" + franchise chips; confirm category prefs do NOT filter My Dex
- [ ] A7. Price alerts (want-list only; "Target price (USD)")
- [ ] A8. "My collection categories": toggles, genre toggle, Reset, restart persistence
- [ ] A9. Reports + export .xlsx (4 sheets) / .csv
- [ ] A10. **Check tab — Pre-Purchase Check (4 overlays, 4s auto-reset, re-scan cancel)**
- [ ] A11. App theme (6 options) + Diagnostics log share

## Part B — Integrations

- [ ] B1. Channel3 key set/persist
- [ ] B2. HobbyDB OAuth connect/persist/disconnect
- [ ] B3. eBay OAuth connect/persist/disconnect
- [ ] B4. Drive connect / back up now / **lapsed grant (notif id 3002)** / disconnect
- [ ] B5. Contributions: silent USER_SCAN + USER_EDIT prompt; toggle arms/cancels GitHubUploadWorker; WORKER_URL-unset skip log
- [ ] B6. Catalog refresh worker log sequence — "Refresh now" + scheduled run

## Part C — Backup/Restore (run LAST)

- [ ] C1. Backup: Downloads file + "Share backup via…" + JSON structure (no catalog/system docs, blob encoding correct)
- [ ] C2. Restore: "Replace your collection?" → exact state restored, catalog/cat-prefs intact
- [ ] C3. **Force restore: "Database rebuilt" → restart → re-preload → correct collection (HIGHEST PRIORITY — Session 7 reopen/Collection risk)**

## Part D — Automated

- [ ] D1a. Enriched import — 5-record test file, exact counts (1 or 0 enriched / 2 or 3 added / 2 skipped / 0 errors)
- [x] D1b. Enriched import — full 14,314-record file — PASS (see Result log)
- [ ] D2. `gradlew test` — 72 tests green (9+11+8+15+20+9)
- [ ] D3. SecureKeyStore v2 prefs format / no Cipher/KeyStore exceptions across restart

## Part E — 16 KB Regression

- [ ] E1. 16 KB emulator condensed smoke test (A1, A3a, A4d+A4f, C1)

---

## Result log

(Add one line per completed item: date · item · PASS/FAIL/BLOCKED · note)

- 2026-06-13 · D1b · PASS · Full 14,314-record `funko_data_enriched.json`, first run: 13,585 enriched / 725 added / 4 skipped / 0 errors, 51s. Matches HANDOFF.md dry-run estimate (~13,583/~725/~4).
- 2026-06-13 · D1b (re-import) · PASS · Same file, second run after category fix: 14,310 updated / 0 added / 4 skipped / 0 errors, 47s. Confirms idempotency + category repair (714 docs). Verified via Search Catalog → "perpetua" returning "Papa V Perpetua · Music" (was 0 results before fix).
