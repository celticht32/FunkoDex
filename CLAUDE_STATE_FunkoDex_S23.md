# CLAUDE_STATE — FunkoDex — Session 23

STATUS: COMPLETE for catalog work; **the enrich run itself has NOT been done**. S23
was meant to be "one enrich run over ~25 blanked records." It never got there: the
first thing checked — the 3 "image-blanked" records — turned out to be misfiled in
the S22 handoff, and unpicking that consumed the session. The catalog is now
correct and the guards are in place, so the run is ready to go and is the first
thing S24 should do.

Repo: FunkoDex (branch `master`, `C:\Downloads\Development\FunkoDex\`)
Toolchain (pinned, unchanged): AGP 8.13.2, Gradle 8.13, Kotlin 2.0.21, material3 1.3.0 (BOM 2024.09.00), CBL 3.2.4. package com.funkodex, minSdk 26, targetSdk 36.
Companion repo: funko_enrich (branch `main`), Node v24, Python/Pillow.

## Current authoritative catalog
`C:\Downloads\Development\funko_enrich\funkodex_base_catalog.json` — **20,565 records**
(was 20,580 at S22 end; net −15: 15 rows removed, 15 modified, 0 added).
Shipped asset `app\src\main\assets\funkodex_base_catalog.json.gz` is **STALE** — it still
holds the 20,580 S22 catalog. Nothing has been rebuilt or shipped. `CATALOG_VER` is still 2.

## Current authoritative backup
Unchanged from S22: `FunkoDex_RESTORE.zip` (12.2 MB, 21,211 docs) in
`C:\Downloads\funkodex_upc_verify\`. **Not touched this session** — `merge_backup.py` has
not been re-run, so the backup still carries the S22 catalog.

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
   → bump `CATALOG_VER` 2→3 → ship → **fresh-install test** (a restore bypasses the preloader
   entirely — see the S22 testing note, it still bites).
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
6. **APP DEFECT — `saveItem` does not enforce the owned-id invariant.** Move the
   `catalog::` → `funko::` re-home out of `toggleOwned()` and into
   `FunkoRepository.saveItem()`, so all 15 call sites are covered rather than one. Until then
   the 2-record defect recurs on any edit-then-save of a catalog-backed owned figure. Requires
   a compile; not attempted in S23.
7. Carried unchanged from S22: `CatalogImporter.kt` still expects the old shape;
   `FunkoDexApp.kt` ~line 134 logs the wrong filename; `KennyRecord` +
   `refreshKennyChanDISABLED()` retained for compilation (2 expected warnings); plus the S21
   carry-overs (Josh w/Piano link, relink-on-refresh, Option-B tappable rows,
   image-vector-search for the 6,360 filtered rows, S19 report-code re-verification).

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

FunkoDex: **no code changed.** `docs/DECISIONS.md` gains DEC-026.
