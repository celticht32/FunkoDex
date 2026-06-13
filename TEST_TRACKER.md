# Test Tracker — Functional Validation

Tracks execution of `COMPLETE_TEST_PLAN.md` (code-verified against the repo).
Update this file as each item is run — check the box and add a one-line
result/note in the log at the bottom.

## Known wiring gaps (verified against the repo)

Two composables are referenced/defined but have no reachable call sites in the
pushed repo — they live in local-only/uncommitted files:

- **`ReportsScreen.kt`** — imported by `FunkoDexNavHost.kt` but absent from the
  repo. Affects **A9** (Reports + export).
- **`CatalogDataSection`** — defined in `SettingsScreen.kt` but never invoked.
  Provides the Channel3/HobbyDB/eBay "Lookup sources" rows and the "Refresh
  now" button. Affects **B1, B2, B3, B6**.

If either gap blocks a test, mark it `BLOCKED (gap)` rather than pass/fail and
note it — these are pre-existing local-file issues, not regressions from
Sessions 7/8.

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
- [ ] A9. Reports + export .xlsx (4 sheets) / .csv — BLOCKED (ReportsScreen.kt gap) unless local file present
- [ ] A10. **Check tab — Pre-Purchase Check (4 overlays, 4s auto-reset, re-scan cancel)**
- [ ] A11. App theme (6 options) + Diagnostics log share

## Part B — Integrations

- [ ] B1. Channel3 key set/persist — BLOCKED (CatalogDataSection gap) unless reachable
- [ ] B2. HobbyDB OAuth connect/persist/disconnect — BLOCKED (CatalogDataSection gap) unless reachable
- [ ] B3. eBay OAuth connect/persist/disconnect — BLOCKED (CatalogDataSection gap) unless reachable
- [ ] B4. Drive connect / back up now / **lapsed grant (notif id 3002)** / disconnect
- [ ] B5. Contributions: silent USER_SCAN + USER_EDIT prompt; toggle arms/cancels GitHubUploadWorker; WORKER_URL-unset skip log
- [ ] B6. Catalog refresh worker log sequence — "Refresh now" BLOCKED (CatalogDataSection gap), test via scheduled run

## Part C — Backup/Restore (run LAST)

- [ ] C1. Backup: Downloads file + "Share backup via…" + JSON structure (no catalog/system docs, blob encoding correct)
- [ ] C2. Restore: "Replace your collection?" → exact state restored, catalog/cat-prefs intact
- [ ] C3. **Force restore: "Database rebuilt" → restart → re-preload → correct collection (HIGHEST PRIORITY — Session 7 reopen/Collection risk)**

## Part D — Automated

- [ ] D1a. Enriched import — 5-record test file, exact counts (1 or 0 enriched / 2 or 3 added / 2 skipped / 0 errors)
- [ ] D1b. Enriched import — full ~17,500-record file (optional)
- [ ] D2. `gradlew test` — 72 tests green (9+11+8+15+20+9)
- [ ] D3. SecureKeyStore v2 prefs format / no Cipher/KeyStore exceptions across restart

## Part E — 16 KB Regression

- [ ] E1. 16 KB emulator condensed smoke test (A1, A3a, A4d+A4f, C1)

---

## Result log

(Add one line per completed item: date · item · PASS/FAIL/BLOCKED · note)
