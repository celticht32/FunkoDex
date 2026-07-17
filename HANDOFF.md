# HANDOFF — FunkoDex — for a session with zero prior context

You are picking up FunkoDex, an Android Funko Pop collection tracker. This brief gets you oriented cold.

## Who / what
- User: Chris Ahrendt (github.com/celticht32). Windows, cmd/PowerShell syntax only (copy/del/C:\paths, not cp/rm/~). MIT (c) 2026 Chris Ahrendt.
- App: FunkoDex — Android/Kotlin/Compose, Couchbase Lite (CBL) local DB. Repo branch `master` at `C:\Downloads\Development\FunkoDex\`.
- Toolchain is PINNED — verify every API symbol against these + existing in-repo usage, never guess from training: AGP 8.13.2, Gradle 8.13, Kotlin 2.0.21, Compose material3 1.3.0 (BOM 2024.09.00), CBL 3.2.4. package com.funkodex, minSdk 26, targetSdk 36.
- Companion: funko_enrich (Node v24 + Python/Pillow), branch `main` at `C:\Downloads\Development\funko_enrich\` — the metadata/pricing enrichment pipeline.

## How Claude works here (firm user rules)
- You CANNOT compile Kotlin, run the pipeline, or fetch retail image CDNs in your sandbox (allowlist is github/npm/pypi only). "Compiles"/"works"/"restores" = Chris's on-device confirmation.
- To read live repo state: pull the codeload tarball `https://codeload.github.com/celticht32/FunkoDex/tar.gz/refs/heads/master` (github IS allowlisted).
- Present individual code files WITH destination paths (not archives) unless told otherwise. Concise diffs, not full-file rewrites.
- Show RENDERED options for any report/UI/layout decision before writing it into code.
- Verify, don't fabricate. Say "I don't know" and check. Flag opinions vs facts.
- Catalog is golden source; fix collection to match catalog. But records with NO catalog UPC match require inference — document it AS inference.
- Doc `_id`s are NEVER changed. Backups restore via Settings > Restore full (wipe+import); zip the inner json as `funkodex_backup.json` inside a `.zip`.

## Where things stand (end of Session 22)
Latest good backup: **`FunkoDex_RESTORE.zip`** (12.2 MB) in `C:\Downloads\funkodex_upc_verify\` — inner file `funkodex_backup.json`, **21,211 docs** (catalog 20,580 + owned 358 + app-state 273). Confirmed restored on device; collection renders correctly. Fallback (stale, PRE-cleanup, keep it): `FunkoDex_RESTORE_cleaned.zip`.

Authoritative catalog: `C:\Downloads\Development\funko_enrich\funkodex_base_catalog.json` — **20,580 records**. Shipped to the app as `app\src\main\assets\funkodex_base_catalog.json.gz` (2.0 MB).

Session 22 did (built + installed + emulator-verified): (a) **catalog data-quality cleanup** — 537 non-Pops removed, 361 UPC mis-staples blanked, 53 collection names cleaned, 13 dupe clusters merged, ~1,400 new Pops added; (b) **fixed known issues** — Evil Queen consolidated from two half-records, Castiel restored, Mr. Toad created from his physical box, 0 orphans remaining (was 3); (c) **audited enrich's PriceCharting matches** — 22 records carrying a different Pop's identity blanked; (d) **hardened `enrich.js`** — the `coreNameCovered` substring bug (root cause of the mis-matches), the `isNonPop` string-series crash, the Castiel guard; (e) **shipped the cleaned catalog into the app** — streaming gzip preloader, Kenny re-fetch disabled (DEC-023/024).

## THE MAIN THING TO KNOW
**The app had never shipped any of the catalog cleanup.** For months it preloaded the deprecated Kenny Chan `funko_data.json` while every enrichment and cleanup pass landed in `funkodex_base_catalog.json`. Nothing connected the two. That is now fixed (DEC-023) — but the lesson generalises: *check that data work actually reaches the device.* A clean file on disk is not a clean catalog in the app.

Second thing: **presence is not correctness.** The Evil Queen record reported `funkoNumber present: YES` while holding a completely different Pop's number. A verification that checks whether a field is *filled* will green-light garbage. Compare VALUES (DEC-025).

Third: **blank beats wrong.** ~25 records currently hold deliberate blanks awaiting one enrich run. That is the intended state — a blank re-resolves cleanly, a guessed value corrupts silently and forever (DEC-025). The exception is data read off physical packaging (Mr. Toad's #814, UPC 889698511728) — that is verified, not guessed, and IS written.

Still true from S21: an owned figure is self-sufficient; `catalogRef` is opportunistic, not a dependency (DEC-020). Do NOT delete the no-UPC catalog rows (DEC-021).

**Revised from S21:** S21 said "do not run enrich.js again." Half right. It is right that enrich will not close the owned-link gap (none of the ~1,400 new records fixed an unmatched owned figure). It is wrong as a blanket retirement — the run added ~1,400 current funko.com releases the catalog had never seen. Run it for catalog *currency*, ALWAYS follow with `check_mismatches.py`, never expect owned-match improvement.

## Open items (see CLAUDE_STATE_FunkoDex_S22.md for full detail)
1. **One enrich run** to re-resolve 25 deliberately-blanked records (3 image-blanked: Bash #623, Kurogiri #789, Android 17 #529; plus the 22 mis-match-blanked). Copy the fixed `enrich.js` first, then re-run `merge_backup.py` and `build_catalog_asset.py`. Purely additive.
2. **Variant-level mis-matching is unfixed** and likely unfixable from text alone — "Evil Queen (Snow White Stained Glass)" vs slug "snow-white-&-evil-queen" shares every word; only product knowledge separates a Deluxe from a 2-pack. Character-level collisions are now blocked. Expect this class to recur; `check_mismatches.py` flags them as "partial" for human review.
3. `CatalogImporter.kt` untouched — user-triggered import, still expects the old enriched shape, now inconsistent with the preloader.
4. `FunkoDexApp.kt` ~line 134 logs "funko_data.json not found" on `AssetMissing` — cosmetic, wrong filename now.
5. `KennyRecord` + `refreshKennyChanDISABLED()` retained only so the disabled body compiles (2 expected build warnings). Delete together.
6. Carried from S21, not touched in S22: fold the +1 "Josh w/Piano Outfit" link into the backup; wire/verify opportunistic relink-on-refresh; Option-B tappable name-check rows; image-vector-search for the 6,360 filtered rows; the S19 report-code re-verification and Cost Breakdown label changes.

## Testing note that will bite you
**Restoring a backup BYPASSES the preloader entirely** — the backup carries its own catalog, so `preloadIfNeeded()` finds the marker satisfied and does nothing. The only way to exercise the asset/gzip/stream path is a fresh install with no restore (emulator: Device Manager → ⋮ → Wipe Data; device: `adb shell pm clear com.funkodex` — which wipes the collection, so have the backup zip to hand). Verified working in S22 that way: searched "Stitch", got results, added one.

## Files in this handoff
- `CLAUDE_STATE_FunkoDex_S22.md` — full session state (read first). S21/S19 retained for their detail.
- `HANDOFF.md` — this file. `docs/DECISIONS.md` — DEC-023/024/025 added. `CHANGELOG.md` — S22 entry.
- Changed code (FunkoDex): `app/src/main/java/com/funkodex/data/preload/CatalogPreloader.kt`, `CatalogRefreshWorker.kt`. Asset: `app/src/main/assets/funkodex_base_catalog.json.gz` (added), `funko_data.json` (deleted).
- Changed code (funko_enrich): `enrich.js`. New tooling: `build_catalog_asset.py`, `check_mismatches.py`, `blank_mismatches.py`, `merge_backup.py`, `check_backup.py`, `fix_known_issues.py`, `salvage_enriched.js`, `check_funko_dupes.py`, `extract_subset.py`, `verify_kfix.py`.
- Backup: `FunkoDex_RESTORE.zip` (21,211 docs).
- S19 tooling still applies: `resize_blobs.py` (critical post-harvest), `blob_images.js`, `serp_proxy.js`, `FunkoDex_Image_Collector.html`, `rpm_harvest.js`.
