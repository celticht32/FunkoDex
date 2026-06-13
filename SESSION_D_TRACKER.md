# Session D — CBL Collection API Migration — Progress & Test Tracker

> **Note:** The "End-of-session test checklist" below is superseded by
> `TEST_TRACKER.md` (Parts A–E), which is the actively-maintained functional
> test tracker as of Session 9. This file's checklist is kept for historical
> reference to the Session D migration scope; do not update it further —
> update `TEST_TRACKER.md` instead.

Migration: `database.X()` / `DataSource.database(db)` / database-level change
listeners → `collection.X()` / `DataSource.collection(col)` / collection-level
listeners, via `db.getCollection()` (new accessor → `database.defaultCollection`,
non-null).

## File status

| File | Status | Notes |
|---|---|---|
| `data/db/FunkoDexDatabase.kt` | DONE | Added `getCollection()`; `ensureIndexes()` converted (12 sites) |
| `data/repository/FunkoRepository.kt` | DONE | 21 sites converted; removed unused `database` property |
| `data/repository/AlertRepository.kt` | DONE | 10 sites converted |
| `data/repository/ContributionRepository.kt` | DONE | 8 sites converted |
| `data/repository/CategoryPreferenceRepository.kt` | DONE | added `collection` property; ~16 sites converted; `inBatch` correctly stays on `database` (3 sites) |
| `data/repository/ImageBlobRepository.kt` | DONE | 3 sites converted |
| `data/preload/CatalogPreloader.kt` | DONE | 8 sites; `ensureCatalogIndexes` now takes `Collection` param |
| `data/preload/CatalogImporter.kt` | DONE | 8 sites; `buildTitleIndex` now takes `Collection` param |
| `data/preload/CatalogRefreshWorker.kt` | DONE | 12 sites across 3 functions; `inBatch` stays on `database` in all 3 |
| `network/FunkoLookupService.kt` | DONE | 2 DataSource.database → collection. **Session 9:** found+fixed a 3rd missed site — `db.getDatabase().getDocument(docId)` → `db.getCollection().getDocument(docId)` (was using the deprecated `Database.getDocument`, only surfaced as a compiler warning, not caught by this migration's original sweep) |
| `network/ConnectivityObserver.kt` | DONE | 7 sites: getDocument/save/delete/DataSource all converted |
| `ui/screens/settings/DatabaseTransferViewModel.kt` | DONE | export/import/forceRestore all converted; `liveCollection` obtained AFTER `db.reopen()` in force-restore so it reflects the fresh DB instance; `inBatch` stays on `liveDb` |

## Conversion rules

- `db.getDocument(id)` → `col.getDocument(id)` where `col = db.getCollection()`
- `db.save(doc)` → `col.save(doc)`
- `db.delete(doc)` → `col.delete(doc)`
- `db.createQuery(...)` → `col.createQuery(...)` (verify signature parity)
- `DataSource.database(db)` → `DataSource.collection(col)`
- `db.addChangeListener(...)` → `col.addChangeListener(...)` (DatabaseChangeListener → CollectionChangeListener type change — verify)
- `db.addDocumentChangeListener(id, ...)` → `col.addDocumentChangeListener(id, ...)`
- `db.count` / `db.getCount()` → verify Collection equivalent (`col.count`)
- Index creation already handled in `FunkoDexDatabase.ensureIndexes()`

## End-of-session test checklist (functional/device — deferred until all coding done)

- [ ] Catalog preload (23,940 items) on fresh install
- [ ] Add item via UPC scan (Scanner tab)
- [ ] Add item via manual search + bulk add
- [ ] Edit item — save, verify blob preservation (thumbnailBlob, userPhoto, variants)
- [ ] Delete item
- [ ] Collection screen — series filter, sort, live category filter (combine())
- [ ] Reports screen
- [ ] Price alerts — create, trigger (PriceAlertWorker)
- [ ] Category preference toggles (CategoryFilterScreen)
- [ ] Community contribution flow (ContributionRepository → GitHubUploadWorker)
- [ ] Enriched catalog import (CatalogImporter)
- [ ] Catalog refresh worker (CatalogRefreshWorker — weekly update)
- [ ] **Backup** — export, verify zip contents
- [ ] **Restore** (normal) — verify user data restored, catalog/system docs preserved
- [ ] **Force restore** — verify full wipe + reopen + reinsert + catalog re-preload on next start
- [ ] FunkoMapperTest (9 tests) — Couchbase document roundtrip
- [ ] CollectionStatsTest (11 tests)
- [ ] FunkoLookupServiceTest (8 tests)
- [ ] All existing unit tests still pass (`./gradlew test`)
- [ ] Connectivity observer — verify network state detection still works (if touched)
- [ ] 16 KB emulator regression — re-run smoke test (catalog preload, scan, photo) since CBL access patterns changed
