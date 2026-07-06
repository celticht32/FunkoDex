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

## Where things stand (end of Session 21)
Latest good backup: `FunkoDex_LINKED_20260706_linked.zip` (26,878 docs; 356 owned, 234 linked to catalog, 122 standalone). Prior: `FunkoDex_REPAIRED_20260706_050617.zip` (S19, 100 linked).

Session 21 did (all built + installed, Chris-confirmed): (a) **corrected owned↔catalog linking 100→234** via a deterministic unique-UPC-then-unique-name pass (134 links); UPC-verified that only 1 more of the remaining 122 is genuinely linkable, so 235 is this catalog's ceiling; (b) added a **manual-search junk filter** — `searchByName` now drops identify-only rows (kept 19,891 / dropped 6,360 / 0 UPC-rows lost), nothing deleted from the DB; (c) added a **name-based pre-purchase check** on the `prescan` screen for loose figures (badged OWNED/WANTED/NOT_IN_COLLECTION); (d) **closed the enrichment workstream** as a measured dead end; (e) added unit tests for (b) and (c).

## THE MAIN THING TO KNOW
The owned-match problem was never catalog completeness — it was **linking** and, structurally, **architecture**. Locked in DEC-020: an owned figure is self-sufficient (carries its own UPC/name/franchise/series) and the `catalogRef` link is *opportunistic* context, not a dependency. "Owned but unlinked" is a normal, permanent state — 122 owned figures have no catalog row because none exists, and that is fine. **Do NOT run enrich.js expecting owned-match improvement** (a full run added 8 UPCs / 10 prices — retired). Do NOT delete the no-UPC catalog rows (DEC-021 — filtered, not deleted; they are the future image-search coverage set).

## Open items (see CLAUDE_STATE_FunkoDex_S21.md for full detail)
1. Fold the +1 "Josh w/Piano Outfit" → `catalog::josh-baskin-piano-outfit` link into the shipped backup (found after the 234-link zip; not yet applied).
2. Wire/verify opportunistic relink-on-refresh (DEC-020) in `CollectionRelinkService` / `CatalogRefreshWorker`.
3. Optional Option-B: make a name-check result row tappable (open detail / add). Inert by design now (DEC-022).
4. Future/big: image-vector-search for loose oddball figures (the 6,360 filtered rows are its coverage set).
5. Carried from S19: Cost Breakdown label changes + info popup (approved, not written); re-verify the three S19 report-code fixes actually landed (FunkoRepository category fix was suspect); Casey Jr. Mickey/Donald franchise mismatch; manual edge records.

## Files in this handoff
- `CLAUDE_STATE_FunkoDex_S21.md` — full session state / checkpoint (read this first). `CLAUDE_STATE_FunkoDex_S19.md` retained for the data-cleanup detail.
- `HANDOFF.md` — this file. `docs/DECISIONS.md` — DEC-020/021/022 added. `CHANGELOG.md` — S21 entry.
- Changed code (destination paths in the state file): `FunkoLookupService.kt`, `FunkoRepository.kt`, `PreScanViewModel.kt`, `PreScanScreen.kt`. Tests: `FunkoLookupServiceTest.kt`, `PreScanBadgeLogicTest.kt`.
- Backup: `FunkoDex_LINKED_20260706_linked.zip`. Reference: `LINK_REPORT.json`, `FunkoDex_Unlinked_Review.xlsx`.
- S19 tooling still applies: `resize_blobs.py` (critical post-harvest), `blob_images.js`, `serp_proxy.js`, `FunkoDex_Image_Collector.html`, `rpm_harvest.js`.
