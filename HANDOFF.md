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

## Where things stand (end of Session 19)
Latest good backup: `FunkoDex_REPAIRED_20260706_050617.zip`. Clean state: 356 owned, 350 images, 0 blank/junk franchise, 0 blank category, restore-safe (no oversized blobs).

Session 19 did: (a) found + fixed an image restore bug (oversized blobs broke CBL's large-doc save, dropping fields — fixed by resizing to 400px thumbnails; `resize_blobs.py` now prevents recurrence), (b) cleaned all junk/blank franchises to 0 via title inference incl. a holiday sub-line scheme "Property - Holiday", (c) filled all 61 blank categories via inference, (d) staged three report-code fixes (completion math + category derivation).

## THE MAIN THING TO KNOW
All the messy records (61 of them) are the ones with NO catalog UPC match — retail-dump imports. Everything we did to them (franchise, category) is INFERENCE, not catalog-sourced. **Chris's decision at session end: stop hand-patching, run ENRICHMENT, let it overwrite these with real catalog data.** So the next session's likely job is the enrichment run — NOT more manual data patching.

Caveat: enrichment matches by UPC. If these 61 records' UPCs still aren't in the catalog, enrichment won't touch them and the inferred values stand (that's fine). Check catalog coverage after running.

## Open items (see CLAUDE_STATE_FunkoDex_S19.md for full detail)
1. Enrichment run (primary).
2. Report code fixes staged in outputs/funkodex/ (FunkoItem.kt, ReportsScreen.kt, FunkoRepository.kt) — the FunkoRepository category fix did NOT land when Chris compiled; debug if continuing.
3. Cost Breakdown label changes + info popup — APPROVED, not yet written.
4. Manual edge records Chris handles on-device (Easter Stitch #1533, Elvira Red Sofa, Mad Sweeney; leave Wedge Antilles $120 signed as-is).
5. Casey Jr. Mickey/Donald franchise mismatch — undecided.

## Files in this handoff
- `CLAUDE_STATE_FunkoDex_S19.md` — full session state / checkpoint (read this first).
- `HANDOFF.md` — this file.
- Report-code fixes: `funkodex/FunkoItem.kt`, `funkodex/ReportsScreen.kt`, `funkodex/FunkoRepository.kt`.
- Backup: `FunkoDex_REPAIRED_20260706_050617.zip`.
- Tooling: `resize_blobs.py` (critical), `blob_images.js`, `serp_proxy.js`, `FunkoDex_Image_Collector.html`, `rpm_harvest.js`.
- Change logs: golden_fix, retailer_strip, holiday, franchise_final, category_fill (.md).
