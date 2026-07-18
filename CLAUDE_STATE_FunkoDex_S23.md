# CLAUDE_STATE — FunkoDex — Session 23

STATUS: Catalog work COMPLETE and, for the first time in this app's history, ACTUALLY LOADED
ON A DEVICE. **The enrich run itself has still NOT been done.** S23 was meant to be "one enrich
run over ~25 blanked records." It never got there twice over: first the 3 "image-blanked"
records turned out to be misfiled in the S22 handoff, and unpicking that consumed the middle of
the session; then shipping the cleaned catalog exposed that the preloader had NEVER worked —
the shipped catalog had never reached any device, and S22's sign-off had passed on network
fallback results. Both are now fixed and verified on device. The enrich run is ready and is the
first thing S24 should do.

**S24: read THE ASSET BUG (DEC-027) and THE PRELOAD RACE (DEC-028) before shipping any
catalog.** They contain a verification rule that exists because two sessions signed off on a
catalog that was never loaded. New decisions this session: **DEC-026** (dupes need a product
page), **DEC-027** (the `.gz_` extension is load-bearing), **DEC-028** (nothing writes
`catalog::` until the preload finishes). **DEC-023 is amended** — its "verified on a clean
emulator" claim was false.

Repo: FunkoDex (branch `master`, `C:\Downloads\Development\FunkoDex\`)
Toolchain (pinned, unchanged): AGP 8.13.2, Gradle 8.13, Kotlin 2.0.21, material3 1.3.0 (BOM 2024.09.00), CBL 3.2.4. package com.funkodex, minSdk 26, targetSdk 36.
Companion repo: funko_enrich (branch `main`), Node v24, Python/Pillow.

## Current authoritative catalog
`C:\Downloads\Development\funko_enrich\funkodex_base_catalog.json` — **20,565 records**
(was 20,580 at S22 end; net −15: 15 rows removed, 15 modified, 0 added).
Shipped asset: `app\src\main\assets\funkodex_base_catalog.json.gz_` — **note the trailing
underscore, it is deliberate and load-bearing** (see THE ASSET BUG below). `CATALOG_VER` is now
"3". Built, shipped, and VERIFIED ON DEVICE: searching "Bash" returns the Fortnite Pop from the
local catalog.

## Current authoritative backup
`C:\Downloads\funkodex_upc_verify\` holds three:
- `FunkoDex_RESTORE_S22.zip` — the original (12.2 MB, 21,211 docs)
- `FunkoDex_RESTORE_S23.zip` — **id-fixed** (the 2 owned-on-catalog::-id records re-homed; see
  FULL DATA SCAN). Output of `fix_backup_ids.py` as `funkodex_backup_idfix_S23.json`.
- `FunkoDex_FULL_20260717_212053.zip` — a phone export taken during S23

**None has been restored, deliberately.** All still carry the STALE 20,580 S22 catalog, so
`merge_backup.py` should fold the current catalog in *after* the enrich run. Note a restore
BYPASSES the preloader entirely (the backup brings its own catalog docs) — which is exactly why
the asset bug hid for two sessions. Never verify a catalog ship via a restore.

## THE ONE-LINE VERSION
S22's handoff said "3 image-blanked records (Bash #623, Kurogiri #789, Android 17 #529)
await one enrich run to repopulate their images." Every part of that was wrong:
enrich never refetches images (S22's own data-safety rule, one line further down the
same doc); the records were not damaged base figures; and one of them was not a Pop.

## WHAT S23 ACTUALLY DID

### 1. Verified the `coreNameCovered` fix (the thing the run depended on)
Tested the S22 hardening against the documented cases before trusting it: **15/15 pass**.
Blocks all four substring collisions (`Ram`→bram-stoker, `Poe`→poet-anderson,
`Will`→chilly-willy, `Venom`→venomized-doctor-doom) plus `Harry`→hermione-granger.
Still allows the six fuller-name matches S22 deliberately spared (Erza→erza-scarlet,
Woody→sheriff-woody, etc.). Confirms both documented regressions are real and intended:
`H.E.R.B.I.E.`→herbie and `Mbappé`→kylian-mbappe now reject. The fix is sound.

### 2. The 3 "image-blanked" records were 2 signed editions + 1 shirt
- **Kurogiri #789** and **Android 17 #529** are real **Toyzilla / Chuck Huber certified
  signed editions** (Huber voices Dragon Ball / MHA roles). Each has a populated base-figure
  twin in the catalog holding the same UPC, number and image. The shared UPC is **correct** —
  a signed Pop is the base figure in its original box with a signature and COA added, not a
  new SKU. Do not let a future UPC-mis-staple pass blank these barcodes.
- **`catalog::summer-bbq-bash` is APPAREL** — hobbyDB 339696, filed under *Shirts and Jackets*,
  35 mentions of "Shirt", zero of Fortnite or Funko Pop. It carried the real Bash Pop's #623
  and UPC 889698506939, mis-stapled. See the correction below; this one is on the assistant.

`fix_signed_editions.py` (run, verified): harvested identity-neutral fields (image, franchise,
pcSeries) from each base twin, renamed titles to `(Toyzilla Signed Edition)`, blanked
inherited base-figure pricing, set `source: MANUAL_VERIFIED`.
**The harvest is field-classed, not gap-driven, and that distinction is the whole point:**
Android 17's twin carries loose $6.33 / complete $9.00 / new $10.55 and pcId 7468564. Those
fields are *blank* on the signed record, so a "fill anything empty from the twin" rule would
have stamped $6.33 onto a piece that sells for $70–100 — DEC-025's exact failure mode,
populated and plausible and silently wrong.

### 3. Kenny/PriceCharting dupe hunt — 531 → 18 → 14 different decisions
A `title + funkoNumber + UPC` scan reported **531** pairs. That number was an artefact of
stripping parentheticals in the normaliser (it collapsed Groot Holiday / Glow / Prototype
into one bucket, and three Spider-Man variants into another) — the same mistake S22 recorded
under "403 collision groups are a normalisation artefact," reproduced from scratch. Keeping
the parentheticals gave **18**. Those 18 needed 14 separate decisions, each settled by a
product page. Full detail in the judgement-calls section below. See **DEC-026**.

`merge_kenny_dupes.py` (run, verified): 12 pairs merged + 1 pure duplicate deleted →
20,579 → 20,566.

### 4. The Bash misidentification — corrected, record restored
`fix_bash_misidentification.py` (run, verified): deleted the shirt, restored
`catalog::bash-pop!-vinyl` **verbatim** from its pre-delete state (recovered from the
session-start tarball, not reconstructed), merged `catalog::pc-7516024` into it, deleted
that stub. 20,566 → 20,565. UPC 889698506939 now belongs to exactly one record.

### 5. enrich.js — `PC_SKIP_IDS` added (3 hunks, +64 lines, purely additive)
Guards the 2 genuinely-signed records in **both** places that can corrupt them:
- `passPriceCharting` candidate filter (~line 1155) — never priced.
- `dedupeAndMerge` canonicalByNum index (~line 2365) — never a merge **target**.
`node --check` clean. Verified by extracting the shipped constant and functions from the
patched file and exercising them, rather than testing a hand-copied mirror.
Back up `enrich.js` before replacing; the whole run depends on it.

## CORRECTION TO THIS SESSION'S OWN WORK — the invented signed Bash
The assistant fabricated "Bash (Toyzilla Signed Edition)". The chain:
1. The dupe scan matched `summer-bbq-bash` to `bash-pop!-vinyl` on title+number+UPC — which
   matched *only because of the mis-staple*.
2. The assistant read the slug "summer-bbq-bash" as a Toyzilla "Summer BBQ Bash" event.
   "Bash" is the Fortnite character's name; the shirt is unrelated to any event.
3. It then asked Chris to confirm a premise it had already built — "is the twin a dupe of the
   signed Pop?" — rather than asking what the record *was*. Chris's "yes" was a reasonable
   answer inside a wrong frame. **A confirmation of a wrong framing reads exactly like a fact.**
4. `fix_signed_editions.py` renamed the shirt, stamped it `MANUAL_VERIFIED`, skip-listed it,
   and deleted `bash-pop!-vinyl` — a real Pop — as its duplicate.
The Chuck Huber sourcing only ever covered Kurogiri and Android 17; Huber has no Fortnite
connection. Recovery was possible only because the pristine tarball was still in the sandbox.
**If a rename or a delete rests on an inference about a slug, it is not verified.**

## KNOWN OPEN ITEMS
1. **THE ENRICH RUN — still not done.** 24 blanked records await it (22 mis-match-blanked
   from S22 + Black Star's conflicting pricing from S23). It is now safe to run: the
   `coreNameCovered` fix is verified and `PC_SKIP_IDS` protects the 2 signed editions.
   Sequence: run enrich → `check_mismatches.py` → `merge_backup.py` → `build_catalog_asset.py`
   (now writes `.gz_`) → bump `CATALOG_VER` 3→4 → ship → **verify the asset is in the APK AND
   logcat says `Catalog loaded: <n>` on a fresh install** (a restore bypasses the preloader
   entirely, and a network fallback makes a failed load look fine — see THE ASSET BUG).
2. **The id-fixed backup is NOT restored, on purpose.** `funkodex_backup_idfix_S23.json` fixes
   the 2 malformed ids but still carries the **20,580-record S22 catalog**. A restore is wipe +
   import and bypasses the preloader entirely, so restoring it now would revert the device's
   catalog to pre-S23. Correct sequence: run enrich → `merge_backup.py` against the **20,565**
   catalog, feeding it the id-fixed backup → one restore carries both. Source
   `funkodex_backup.json` (34,890,191 bytes, the S22 build) is untouched.
3. **The 2 signed editions will never be priced automatically.** They are skip-listed, so
   their blank pricing only fills from a real signed-market value sourced off a listing or
   packaging. Intended (blank beats wrong), but it means those records stay unpriced
   indefinitely. Toyzilla listings put Android 17 around $70–100 vs its base figure's $6.33.
4. **~~Corruption-marker scan~~ — RUN, and the marker was WRONG.** `_id` ≠ `pricechartingId`
   fires on **560 stubs (6.7%)** and is a normal crawl artefact, not corruption. PC URLs are
   name-slugs and carry no numeric id, so they cannot arbitrate; adjacent ids mean siblings
   (Mr. Toad lesson). The two cases that inspired the marker were found by product pages, not
   by it. **DEC-026 corollary 2 is struck.** Do not use it as a scan signal.
5. **Variant-level matching remains unfixed** — S22's open item #2, now understood as an
   information problem rather than a matcher-tuning one (DEC-026). Parenthetical stripping in
   `coreNameTokens()` and `coreNoParens()` means no title convention can encode a variant
   distinction the matcher will respect. A real fix needs an `isVariant`/`variantOf` field —
   a schema change, deliberately not attempted this session.
6. **~~Device missing 1,559 records~~ — FIXED and re-verified.** The race fix landed; a fresh
   install now loads all 20,565 and a relaunch reports `Catalog already present: 20565 items`.
   Kept here only as a reminder of the failure mode: a short load that writes its marker becomes
   PERMANENT. The `MIN_EXPECTED_ROWS` guard now refuses the marker below 20,000 so a bad load
   retries instead of freezing.
7. **`##623` display bug** — the UI prepends "#" to a `seriesNumber` that already carries one,
   so numbers render as `##623`. Cosmetic, pre-existing, only visible now that real catalog data
   reaches the screen. Fix in the UI, not the data: the catalog's `#623` is correct.
8. **`FunkoLookupService.loadLocalDb()` is dead code** — it reads `funko_data.json`, deleted
   from assets in S22, and is the fallback at searchLocalByName's line ~309. It catches, logs,
   caches emptyList, and never retries. Harmless (the CBL query is primary) but it is the
   fallback that would have masked a preload failure. Delete it with `KennyRecord`.
9. **APP DEFECT — `saveItem` does not enforce the owned-id invariant.** Move the
   `catalog::` → `funko::` re-home out of `toggleOwned()` and into
   `FunkoRepository.saveItem()`, so all 15 call sites are covered rather than one. Until then
   the 2-record defect recurs on any edit-then-save of a catalog-backed owned figure. Requires
   a compile; not attempted in S23.
10. **The benign CBL `conflict [1, 8]` warning at the marker save** — cause unknown, outcome
   correct, three theories about it were wrong. See the note at the end of THE PRELOAD RACE.
   Do not spend a session on it unless it becomes an E.
11. Carried unchanged from S22: `CatalogImporter.kt` still expects the old shape; `KennyRecord`
   + `refreshKennyChanDISABLED()` retained for compilation (2 expected warnings); plus the S21
   carry-overs (Josh w/Piano link, relink-on-refresh, Option-B tappable rows,
   image-vector-search for the 6,360 filtered rows, S19 report-code re-verification).
   (S22's "FunkoDexApp logs the wrong filename" item is DONE — the message now names
   `CatalogPreloader.ASSET_NAME` and says explicitly that AssetMissing is not cosmetic.)

## THE ASSET BUG — the catalog had NEVER loaded on any device
The single most important finding of S23, and it invalidates S22's sign-off.
**Filed as DEC-027; DEC-023's false verification claim is amended in place.**

**AGP's asset merger DECOMPRESSES any `.gz` file under `src/main/assets` and STRIPS the
extension**, during `mergeXxxAssets` — before AAPT2, and `gradlew clean` does not stop it. So
the 2.0 MB `funkodex_base_catalog.json.gz` DEC-023 shipped arrived in the APK as an 18.1 MB
*decompressed* `funkodex_base_catalog.json`. `CatalogPreloader` called
`assets.open("funkodex_base_catalog.json.gz")`, got FileNotFoundException, returned
`AssetMissing`, and loaded **zero records**. Every launch. On every device. Since S22.

**Why nobody noticed for two sessions:** the failure is silent by design.
`FunkoLookupService.searchByName()` falls back to a network search when the local Couchbase
query returns nothing. So the app kept working and search kept returning results — from the
network, not the catalog. S22's fresh-install verification ("searched Stitch, got results,
added one") passed while the device held **0** catalog records. The one honest signal was a
warning S22 recorded as open item #4 and dismissed as *cosmetic*:
`funko_data.json not found — catalog lookup will use network only`. It was not cosmetic; it
was the whole bug, wearing a stale filename.

**How it was found:** pulled the CBL SQLite off the emulator (`run-as ... cat` piped through
.NET — PowerShell's `>` corrupts binary with a UTF-16 BOM) and counted. 8,219 catalog docs,
ALL of them `source: USER_SCAN` — i.e. written by CatalogRefreshWorker from the network, none
by the preloader. Then listed the APK's asset entries and found the 18 MB plain `.json`.

**The fix (4 parts — all must agree):**
| where | change |
|---|---|
| `app/src/main/assets/` | asset renamed to `funkodex_base_catalog.json.gz_` |
| `app/build.gradle.kts` | `androidResources { noCompress += "gz_" }` |
| `CatalogPreloader.kt` | `ASSET_NAME = "funkodex_base_catalog.json.gz_"` |
| `build_catalog_asset.py` | `DEF_OUT` writes `.gz_` |
`.gz_` is not an extension AGP acts on, so the file survives; `noCompress` stops AAPT2
pointlessly deflating an already-gzipped file. The bytes are unchanged — still gzip, still read
by `GZIPInputStream`.

**VERIFIED on device**: APK now contains `assets/funkodex_base_catalog.json.gz_` at 2,012,079
(byte-identical to source), logcat shows `Catalog loaded`, and searching "Bash" returns the
Fortnite Pop #623 with its llama art from the LOCAL catalog. First time the catalog has ever
reached a device.

**THE VERIFICATION RULE THIS BUYS (do not skip it again):** after any catalog ship, confirm
BOTH:
1. the asset is really in the APK —
   `[IO.Compression.ZipFile]::OpenRead("app-debug.apk").Entries | ? { $_.FullName -like "assets/funkodex*" }`
   (call `.Dispose()` — an open handle locks the file and breaks the next `gradlew clean`)
2. logcat on a FRESH install says `Catalog loaded: <n> items` with n ≈ 20,565.
"It searched and found something" proves nothing — that is the network answering.

## THE PRELOAD RACE — found immediately after the asset fix
**Filed as DEC-028.**
With the asset finally readable, the first load reported `Catalog loaded: 14856 items` and the
device ended up with 19,006 of 20,565 asset records (1,559 missing).

**Cause:** `FunkoDexApp` scheduled `CatalogRefreshWorker` at step 4a and started the preload at
5c. On a fresh install the worker runs immediately, and its `refreshCommunityUpcFile()` writes
`catalog::` docs (logcat: "Community UPC file: 8204 UPCs merged into catalog" at 22:42:31,
while the preload was still streaming and did not finish until 22:42:45).
`CatalogPreloader.writeChunk()` **skips any doc id that already exists** — a deliberate,
correct behaviour (it makes a partial re-run idempotent and stops the preloader clobbering
user-scanned records) — so every id the worker got to first was dropped by the preloader.

**Made permanent by a second bug:** the version marker was written unconditionally after the
stream, `count = imported`. So a SHORT load got marked complete, and `preloadIfNeeded()`
short-circuits forever after — the next launch logged `Catalog already present: 0 items` and
the 1,559 records were gone for good.

**Fix (2 parts):**
- `FunkoDexApp`: `CatalogRefreshWorker.schedule()` moved INSIDE the preload coroutine, after
  `preloadIfNeeded()` returns. Ordering only — do not move it back to 4a.
- `CatalogPreloader`: the marker is now written only if `countCatalogDocs()` >= 20,000
  (`MIN_EXPECTED_ROWS`). A short load logs a warning, returns `ParseError`, and RETRIES next
  launch rather than becoming permanent. The marker/`Loaded` count now reports actual DB rows,
  not `imported` (which under-counts by design, since writeChunk skips existing ids).
**BUILT AND VERIFIED ON DEVICE** (23:23 / 23:27 S23):
- fresh `pm clear` install → `Catalog loaded: 20565 items` (was 14856 with the race)
- worker now scheduled AFTER the load: `Scheduled: every 7d` at 23:14:10.481, its UPC merge at
  23:14:19 — the preload finished at 23:14:10.476
- relaunch → `Catalog already present: 20565 items` (was `0 items` when the marker was written
  mid-race), so the marker carries the true count and the short-circuit works

**Two Kotlin errors the sandbox could not catch, for future reference** — both were fixed by
copying the pattern already working elsewhere in this repo rather than reasoning about the API:
- `Function.count(...)` → *Unresolved reference 'count'*. `import com.couchbase.lite.*` is NOT
  enough: Kotlin's own `kotlin.Function` is in the default preamble and shadows it. Needs an
  explicit `import com.couchbase.lite.Function` alongside the wildcard. `FunkoRepository.kt:4`
  already did this.
- `rs.next()?.getInt("n")` was invented and does not exist. The working form is
  `rs.allResults().firstOrNull()?.getInt("n")` — see `FunkoRepository.kt:606`.

**A BENIGN WARNING THAT LOOKS ALARMING — do not chase it.** The marker save logs:
`W CouchbaseLite/DATABASE: com.couchbase.lite.LiteCoreException: conflict [1, 8]` with a stack
frame at `CatalogPreloader.preloadIfNeeded` on the `collection.save(markerDoc)` line. It is
logged at W by CBL's internal logger, which then handles the retry itself; the save lands. Proof
it is benign: the very next log line is `Catalog loaded: 20565 items` (a count read from the DB
by `countCatalogDocs()`, not from `imported`), and the NEXT LAUNCH reads the marker back
successfully — `Catalog already present: 20565 items`. Both would be impossible if the save had
failed. The save was changed to `getDocument(MARKER_DOC)?.toMutable() ?: MutableDocument(...)`
(the pattern from `GroupPrefRepository.kt:61`) to carry the revision; the warning persists even
on a `pm clear` install where no marker can pre-exist, so that was NOT the cause. Cause still
unknown. Three separate theories about it were wrong. It is a W, the outcome is correct — leave
it unless it ever becomes an E.


## FULL DATA SCAN — catalog + backup (run at Chris's request, end of S23)
Scanned the live 20,565-record catalog and a **fresh device backup** (exported from the phone
2026-07-17 21:20, 21,211 docs) plus the S22 build (`FunkoDex_RESTORE.zip`). Both backups carry
the same defects byte-identical.

**Clean — no action needed:**
| check | result |
|---|---|
| Orphaned `catalogRef`s (owned → live catalog) | **0** — S23's 15 deletions broke nothing |
| Catalog shape violations (the rules `build_catalog_asset.py` enforces) | **0** |
| Duplicate `_id`s | **0** |
| `type=funko` leaks in the catalog FILE | **0** |
| Doc counts | 21,211 = catalog 20,580 + owned 358 + app-state 273 ✓ |

**ONE REAL DEFECT — 2 owned figures on `catalog::` ids (FIXED in the backup):**
```
catalog::pc-10854506  "Hondo And Pikk"  Pop! Star Wars #808, UPC 889698869287, paid $9
catalog::pc-7506433   "Val"             Pop! Star Wars #243, UPC 889698269896, paid $10
```
Both added 2026-07-07, `source: pricecharting`, `userEditedFields: ['imageUrl']` (Chris set the
images by hand — they resolve to Google Shopping thumbnails), thumbnail blobs present, and
**Chris confirms he owns both** (scanned them in). They carry `type: "funko"` and the full owned
schema but sit on `catalog::` ids — which is why the doc split reads 20,582 `catalog::` ids /
356 `funko::` ids against types of 20,580 / 358.

`fix_backup_ids.py` (new) re-homes them to `funko::889698869287` / `funko::889698269896`,
preserving every other field byte-for-byte (verified: only `_id` differs; thumbnail blobs
identical; 0 other docs touched). Output: `funkodex_backup_idfix_S23.json` in
`C:\Downloads\funkodex_upc_verify\`. **NOT restored** — deliberately, see open item 2.
`catalogRef` stays empty: no catalog row exists for either UPC, and per DEC-020 unlinked-owned
is a normal permanent state, not an error.

**ROOT CAUSE — an app defect that WILL RECUR:**
The invariant "an owned item must never live under a `catalog::` doc id" is enforced in exactly
**one** of 15 `saveItem` call sites: `DetailViewModel.toggleOwned()` (~line 257), which re-homes
to `funko::{upc|uuid}` and sets `catalogRef`. `FunkoRepository.saveItem()` (~line 37) only mints
an id when one is **empty** — it never checks the prefix — so every other caller writes
`item.id` straight through.

`DetailViewModel.saveEdit()` (~line 360) is the path that produced these: it calls
`saveItem(editing.draft)` with whatever id the record was loaded under. Open a catalog-backed
record in Detail → edit a field → save → the owned doc lands on the `catalog::` id. **The
give-away that it was `saveEdit` and not `toggleOwned`: `catalogRef` is EMPTY on both.**
`toggleOwned` would have populated it (`catalogRef.ifBlank { item.id }`); `saveEdit` never
touches it. Note `DetailViewModel:190` also auto-saves on a price refresh — so merely *viewing*
a catalog record can write it back at its `catalog::` id (that path doesn't set `isOwned`, so it
didn't cause this, but it is the same missing invariant).

**The fix belongs in `saveItem`, not in 15 callers.** Not attempted this session — it is a real
Kotlin change and the sandbox cannot compile. See open item 6.

NOT an overwrite: neither id exists in the S22 base catalog file, so no catalog row was
destroyed. The ids came from a device-local PriceCharting lookup. 2 malformed docs, 0 lost
records. (An earlier claim in this session that they "overwrote two catalog rows" was wrong and
was retracted after checking the pristine file.)

**`seriesNumberInt` is DEAD — do not "fix" it:**
All **358/358** owned figures carry `seriesNumberInt: -1`, including 289 with a perfectly
parseable `seriesNumber` (`'#986'` etc). This looks like a parser bug and is not one: **nothing
in the app ever parses `seriesNumber` into `seriesNumberInt`** (grep for `toIntOrNull` /
`removePrefix("#")` returns nothing), the field has a `-1` default, every construction site takes
that default, `FunkoMapper` faithfully persists it, and **no sort, query or UI ever reads it
back**. It is an unfinished field, not corrupt data. Backfilling a parse into the backup would
write data no code consumes. Wire it up in code or delete the field; do not touch the data.

## DATA-SAFETY RULES CARRIED FORWARD (S22's, all reconfirmed, plus new)
- **Never blank `imageUrl` on a re-resolve.** Enrich does not refetch images. S23 nearly lost
  three images by deleting Kenny records that were the sole image source — harvest first.
- **Blank beats wrong.** Reconfirmed for Black Star, Kurogiri's inherited pricing, the signed editions.
- **Presence is not correctness.** S23 walked into this while writing the script meant to
  avoid it: a boolean has-it/doesn't-have-it column said "13 complementary image+price pairs";
  the actual *values* said 4 of those were different products or wrong data.
- **NEW — a blanked field is a magnet, not a resting state.** `passPriceCharting` selects on
  `!hasPrice`. Blanking a wrong value under DEC-025 *guarantees* the record is re-processed
  next run. Blank + skip-list, or it comes back.
- **NEW — verify tools against the shipped code, not a copy of it.** The `coreNameCovered` and
  `PC_SKIP_IDS` checks extract the real definitions out of `enrich.js` and exercise those. A
  hand-retyped mirror tests the assistant's reading, not the file.
- **Scripts read the LIVE original and write a new file.** Rename over the original BEFORE the
  next runs, or they all read the same input and none chain.
- **PowerShell, not cmd.** `move /Y` fails — `Move-Item -Force`. Ship downloadable `.py` files,
  never one-liners with nested quotes. Chris asked for full files, not paste-in snippets.

## JUDGEMENT CALLS — the 18 dupe candidates, and why each went the way it did
These only existed in the S23 conversation. Written down so a future session doesn't
re-litigate them, or quietly reverse them.

### Kept, NOT merged — real distinct products
- **Hello Kitty (8-Bit) #31** — `catalog::hello-kitty-8-bit` (common, pcId 7531588, $3.00) and
  `catalog::pc-7531588` (**CHASE**, pcId 7531589, $13.00, `isChase` already True). Sanrio 45th
  anniversary. A chase shares the common's UPC and number **by design** — 1-in-6 in the same
  case. The "$3 vs $13 conflict" was never a conflict; it was two products. Note `pc-7531588`'s
  `_id` is off-by-one from its own pcId — misleading, but `_id`s are never changed (fix content,
  keep the key).
- **Kurogiri #789 / Android 17 #529 Toyzilla signed editions** — see above.

### Merged, KENNY dominant (stub was the corrupt row)
- **Katniss (Wedding Dress) #230** — stub `pc-7489644` carried pcId **10805742**, which appears
  **zero times** on the live page (`funko-pop-movies/katniss-wedding-dress-230`; Kenny's 7489644
  appears 8 times). Live prices $19.99/$23.49/$29.99 match Kenny, not the stub's $19.00/$40.35.
  Stub's `pcSeries` "The Hunger Games. Hot Topic" is also wrong — the page shows no exclusive.
- **Great White Shark (Bloody) #758** — live page lists $15.00/$21.00/$25.00 = Kenny's values;
  the stub's `marketValueComplete` $24.78 is wrong. Every "Exclusive" mention on the page is an
  eBay listing for this figure as a **Target exclusive**, so Kenny's `isExclusive=True` is right
  and the stub's False is wrong. Kenny was also 8 days fresher.

### Merged, STUB dominant (Kenny was the corrupt row)
- **Santa Freddy #936** — Kenny titled it "Santa Freddy **Funko**", which is the Funko *mascot*
  in a holiday sweater — a different figure that already exists separately as
  `catalog::freddy-funko-santa` #9. This record's own UPC (889698724883), number (#936) and PC
  url (`funko-pop-GAMES/santa-fre...`) all say **Five Nights at Freddy's** Santa Freddy. Kenny's
  `isExclusive=True` + "Funko Shop" belonged to that mascot web-exclusive and were dropped.
  **This is the only record where the stub wins the title. Do not "restore" the Kenny title.**

### Merged, pricing BLANKED (unverifiable conflict)
- **Black Star #778** — same pcId 7468817 and same URL on both rows (so, same product), but
  $35.00/$63.64/$66.65 vs $66.89/$72.09/$72.09. One scrape is stale or wrong; no page was
  pulled. Per Chris's ruling: blank, re-resolve on a future enrich or eBay pull. pcId kept so
  it can. **Chris explicitly rejected "go with the higher value" as a rule** — higher is not
  evidence of correctness, and encoding it would have a future session apply it blindly.

### Merged, clean (9) — genuinely complementary halves
Pixie (Hanna-Barbera), Toshi (Funko), The Dapper Dans (4-Pack), Sam "Mayday" Malone,
More Cowbell!, John "Soap" MacTavish, Eleventh Doctor/Mr. Clever, Samuel "Screech" Powers,
and Santa Freddy (title override above). Kenny had the image and the punctuated title; the
stub had `series`/franchise/publisher/releaseDate/pricing. No conflicts.

### Deleted (1) — genuine duplicate
`catalog::deadpool-venom` (Kenny) vs `catalog::deadpool-/-venom` (ENRICHED). Same UPC
889698151801, #237, both with images, neither with pricing; differ only by slug punctuation.
Survivor is the ENRICHED row.

### Why "stub wins" and "Kenny wins" are BOTH wrong
Tested, not assumed. PriceCharting **strips punctuation**, and the punctuation *is* the product
name — a blanket stub-wins rule would have produced `Sam Mayday Malone`, `Pixie Hanna-Barbera`,
`More Cowbell`, `The Dapper Dans 4-Pack`, plus 3 wrong prices. A blanket Kenny-wins rule would
have kept the Santa Freddy mis-title and left every `series` as the placeholder "Pop! Vinyl".
Merge by field class, with per-record overrides justified by pages. See DEC-026 corollary 3.

### Dapper Dans — investigated, deliberately NOT "fixed"
Its `series` is "D23 Expo" (an exclusive) where `category` holds the line ("Pop! Disney"). This
looked inconsistent and the assistant was about to move the fields. Measurement stopped it:
**4,151 catalog records** (a fifth of the catalog) put a non-`Pop!` value in `series` and the
line in `category` — "Special Edition (Funko Pop!s)", "New York Comic Con 2020", "Funko
GameStop Exclusives", "EMP", "Funko Prototypes". `series` = release context, `category` = line.
Dapper Dans is **already conventional**. Its `isExclusive=False` may be inconsistent with
`series='D23 Expo'`, but if so it is inconsistent catalog-wide and belongs in the derivation
rule, not a one-record hand-edit. Left alone.

## FILES CHANGED THIS SESSION
funko_enrich (all run and verified by Chris):
- `fix_backup_ids.py` (new) — backup id re-home; output `funkodex_backup_idfix_S23.json`
  in `C:\Downloads\funkodex_upc_verify\` (NOT restored — see open item 2)
- `fix_signed_editions.py` (new) — 20,580 → 20,579
- `merge_kenny_dupes.py` (new) — 20,579 → 20,566
- `fix_bash_misidentification.py` (new) — 20,566 → 20,565
- `enrich.js` (modified, +64 lines, 3 additive hunks) — `PC_SKIP_IDS`
- Changelogs: `fix_signed_editions_changelog.txt`, `merge_kenny_dupes_changelog.txt`,
  `fix_bash_misidentification_changelog.txt`
- `funkodex_base_catalog.json` — 20,565 records

FunkoDex (all built; asset verified in APK and on device):
- `app/src/main/assets/funkodex_base_catalog.json.gz_` — renamed from `.gz` (2,012,079 bytes),
  the 20,565-record S23 catalog
- `app/build.gradle.kts` — `androidResources { noCompress += "gz_" }`
- `app/src/main/java/com/funkodex/data/preload/CatalogPreloader.kt` — `ASSET_NAME` → `.gz_`,
  `CATALOG_VER` 2→3, `MIN_EXPECTED_ROWS` guard + `countCatalogDocs()`, marker save via
  `toMutable()`, explicit `import com.couchbase.lite.Function`. BUILT + VERIFIED ON DEVICE.
- `app/src/main/java/com/funkodex/FunkoDexApp.kt` — `CatalogRefreshWorker.schedule()` moved
  inside the preload coroutine (kills the race); AssetMissing message now names the real asset
  and states it is not cosmetic. BUILT + VERIFIED ON DEVICE.
- `docs/DECISIONS.md` — DEC-026 added (corollary 2 struck; see the retraction)


## HOW THE ASSET BUG WAS ACTUALLY FOUND — method worth repeating
Recorded because the reasoning was wrong five times and the measurements were right every time.

Wrong theories, in order, each plausible and each killed by one command:
1. *"Name search reads the deleted `funko_data.json`"* — no: that is only the FALLBACK at
   `searchLocalByName` ~line 309; the CBL query is primary.
2. *"The category filter hides Pop! Games"* — no: `getEnabledCategories()` reads `cat_pref` docs,
   a wiped device has none, and the filter is skipped entirely when the enabled set is empty.
3. *"The preloader writes `series` but the query reads `primarySeries`"* — no:
   `CatalogMapper.FIELD_PRIMARY_SERIES` **is** the string `"series"`. They agree.
4. *"Stale build artifact in `app/build/`"* — no: survived `gradlew clean`.
5. *"The marker doc's revision causes the conflict"* — no: it persists on a `pm clear` install
   where no marker can exist.

What actually worked, every time:
- **Pull the database and count.** `run-as com.funkodex cat files/funkodex.cblite2/db.sqlite3`
  piped to disk. 8,219 docs, ALL `source: USER_SCAN` → the preloader had written *nothing*, which
  no amount of code-reading had revealed.
- **List the APK's assets.** One command showed an 18 MB plain `.json` where a 2 MB `.gz` was
  expected. That is the whole bug, visible in one line.
- **Ask for a falsifiable test.** "Rename it and see if it survives" would have settled it in five
  minutes at any point.

Two Windows gotchas that cost real time:
- PowerShell's `>` and `Set-Content` **corrupt binary** (UTF-16 BOM, null-padded). Pull binaries
  with `adb pull`, or stream via .NET:
  `$p=[Diagnostics.Process]::Start(...); $p.StandardOutput.BaseStream.CopyTo($out)`.
  `run-as ... cat > /sdcard/x` fails too — the redirect runs as the *shell* user, which the app
  uid cannot write; you get a 0-byte file and adb cheerfully pulls it.
- `[IO.Compression.ZipFile]::OpenRead()` **holds the APK open** for the session's life and breaks
  the next `gradlew clean` with "Unable to delete directory". Always `.Dispose()`.

**The rule this session earns:** a green test that could pass for the wrong reason is not a test.
S22's "fresh install, searched Stitch, got results" was true, repeatable, and meaningless — the
network answered. Verify the mechanism (asset in APK, `Catalog loaded: N` in logcat, doc count in
the DB), not the symptom.
