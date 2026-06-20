# Series Completion, Franchise Grouping & Auto Want-List — Specification

- **Project:** FunkoDex (github.com/celticht32/FunkoDex, branch `master`)
- **Document:** SERIES_COMPLETION_SPEC_v0.2.md (supersedes v0.1)
- **Status:** DRAFT for review — not yet built
- **Author:** Chris Ahrendt (Celtic Heart Steamworks). License: MIT, © 2026 Chris Ahrendt.
- **Date:** 2026-06-20
- **Depends on:** Session 14 (catalog data-quality / re-link / field-protection — schema-touching, not yet device-verified)
- **Companion repo:** funko_enrich (branch `main`) — produces the enriched catalog the app imports

---

## 0. What changed since v0.1

- Grouping is now **two-level**: a coarse **franchise/property** level (the level
  the user actually collects by — "Hocus Pocus", "Snow White") and a fine
  **named-set** level ("Haunted Mansion Mini Vinyl Figures"). v0.1 only modelled
  the set level.
- **Franchise sourcing is resolved** (was the last open question). Verified
  against the live enricher code and three real PriceCharting pages plus the
  fresh 12,176-record enriched output: **no scrape source reliably carries
  property-level franchise.** Franchise is therefore **user-assigned and
  authoritative**, with the enricher console used only as a first-scan suggestion
  where it is property-specific. Evidence in §4.3.
- O-1 (variants) resolved: **each variant is its own slot.**
- O-3 resolved: first-scan intent prompt **re-asks until set.**
- O-4 resolved: a manual want in a later-cherry-picked series is **kept.**
- Set-tag grouping rule sharpened to "most-specific named set tag", with the
  failure modes of a naive frequency rule documented (§4.2).

---

## 1. Problem

`FunkoRepository.getCollectionStats()` computes "series completion" from the
user's own `funko::` docs only:

```
totalInCatalog = ownedInFranchise.size + wantedInFranchise.size
```

The denominator is "what I own plus what I manually marked wanted", never the true
size of a set. `completionPct` can only read ~100%. The report is an inventory
count wearing a progress bar.

User need, in their words: own 3 of the 6 figures in a set (or a property like
Hocus Pocus), have the report say "3 of 6", and have the 3 missing auto-populate
a want list — UNLESS only one figure from that group was wanted, in which case the
rest must NOT be auto-wanted. The grouping the user thinks in is the **property /
movie** ("Hocus Pocus has 6 Pops, I have 3, I want the other 3"), not the Pop!
product line.

## 2. Goals

- Report shows true "owned of total" for both franchise/property groups and named
  sets, sourced from the catalog.
- Missing figures in a group the user is completing auto-populate a want list, no
  scanning or manual entry required.
- Per-group intent: completing vs. cherry-picking. Cherry-picked groups show their
  fraction for information but contribute nothing to the want list.
- All grouping + intent is user data: survives backup/restore and does not depend
  on the catalog being present (golden master ships catalog-only / empty user
  collection).

## 3. Non-goals

- No change to the price waterfall, scanner pipeline, OAuth, or workers.
- No network calls in the app for this feature. Everything computes from on-device
  catalog + collection.
- The Community Catalog Distribution architecture is untouched.

---

## 4. Design

### 4.1 Two grouping levels

| Level | Example | Source | Drives |
|---|---|---|---|
| **Franchise / property** | "Hocus Pocus", "Harry Potter" | user-assigned (authoritative); enricher console as suggestion only | the user's primary "X of Y" + want list |
| **Named set** | "Haunted Mansion Mini Vinyl Figures" | catalog `series` tag (most-specific), derived | secondary "X of Y" for branded sets |

Both can show completion. They are independent: a figure has both a franchise
("Hocus Pocus") and possibly a named set. A figure may have a franchise but no
named set (most Pop! figures), or rarely a named set whose members span franchises.

### 4.2 Named-set grouping — the most-specific set tag

Verified: the catalog `series` array mixes three tiers per figure — format
descriptors (`Pop! Vinyl`), Pop! product lines (`Pop! Disney`, `Pop! Movies`),
and occasionally a real **named set** (`Haunted Mansion Mini Vinyl Figures`).
`CatalogMapper.deriveSeriesFields()` currently picks the *first* non-`Pop!` tag as
`primarySeries`, which mis-selects: it buckets Haunted Mansion minis under the
broad "Disney Mini Vinyl Figures" (which appears first) instead of the specific
"Haunted Mansion Mini Vinyl Figures" (which appears second).

**Rule: the set tag is the most-specific named-set tag present**, where:
- Skip format descriptors (`Pop!*`, `Pop! Vinyl`, `Pocket Pop!`), exclusives
  (anything matching the exclusive-keyword set), and `Chase Pieces`.
- Skip broad-line/genre tags (a curated list: `Disney Mini Vinyl Figures`,
  `Mini Vinyl Figures`, `Plushies`, `Soda Figures`, `ReAction Figures`,
  `Movies & TV`, `Animation & Cartoons`, `Anime & Manga`, `Vinyl Art Toys`,
  `Action Figures`, etc.).
- Among the remaining tags, prefer one that *is* a named set. A pure lowest-
  frequency heuristic is INSUFFICIENT — verified failure: the "Mummy" record
  tagged `[…Haunted Mansion Mini Vinyl Figures, Plushies, Hello Kitty]` wrongly
  picked "Hello Kitty" (rarer in catalog) over the real set. The selection must
  prefer set-suffixed tags (e.g. ending "… Mini Vinyl Figures", "… Set",
  "… Advent Calendar") and use frequency only as a tiebreak within those.
- If no named-set tag remains → the figure has **no named set** (set-level
  completion simply doesn't apply to it; it still has a franchise).

`seriesNumber` is NOT a grouping key (regex digit-pull from title; per-figure, not
a set boundary). Display/sort only.

The set-tag derivation belongs in **the enricher** (`funko_enrich`), computed once
and written as a clean field (e.g. `setTag`), so the app reads it directly rather
than re-deriving at import. Final tuning of the broad-line blocklist and the set-
suffix list is done against live enricher output.

### 4.3 Franchise grouping — user-assigned, authoritative (verified)

**Finding (verified, not assumed):** no enricher scrape source reliably carries
property-level franchise. Evidence:

- **funko.com breadcrumb** (`enrich.js` Pass 5) resolves franchise to breadcrumb
  position 3, which is a *genre* ("Animation & Cartoons", "Movies & TV"), not a
  property. Runs only on records missing series; 0 records this batch.
- **HobbyDB tags** (`parseHobbyDbSeries`) are explicitly "NOT … the franchise"
  per the code's own doc comment — a raw mix of format/event/line tags. Many
  pages carry no franchise tag at all.
- **PriceCharting `console-name`** is the closest thing and IS already parsed
  (used to reject non-Funko rows in `searchPriceCharting`), but:
  - It is **discarded** — not stored in any output field (confirmed against the
    fresh 12,176-record output: no `franchise`/`console` field exists; the
    console survives only inside the `pricechartingUrl` slug).
  - It is **mostly umbrella tier.** Real console distribution this run:
    animation 188, star-wars 166, comics 69, disney 61, marvel 41, television 31
    — i.e. genre/line, not property. Only a few are property-specific
    (harry-potter 3, wwe, nascar).
  - It **collapses small properties.** Verified: all Hocus Pocus figures sit
    under `funko-pop-disney` (42) / `funko-pop-movies` (2); there is no
    `funko-pop-hocus-pocus` console. Harry Potter *does* get its own console.
    So console-as-franchise resolves big properties and silently mis-files small
    ones under their umbrella.
  - Coverage is 5% regardless (676 / 12,176 had any PriceCharting data this run,
    capped by `--pc-limit`).

**Conclusion:** franchise must be **user-assigned** to be usable across the whole
collection. It is the only consistent source. Stored on the `funko::` doc as user
data (backs up; survives empty-catalog restore; wins over any derived value).

**Franchise resolution order (for grouping):**
1. **User-assigned franchise** — authoritative. `userEditedFields`-protected so
   re-link never clobbers it.
2. **Enricher console as a first-scan SUGGESTION only when property-specific** —
   i.e. NOT one of the umbrella consoles. Umbrella blocklist (initial, from this
   run's distribution): `animation, star-wars, disney, marvel, television,
   comics, movies, games, heroes, icons, rocks, ad-icons, retro-toys`. A console
   that maps 1:1 to a property (e.g. `harry-potter`) is offered as the suggested
   franchise to confirm. An umbrella console is treated as no-signal.
3. Otherwise **prompt the user to assign** on first scan; until assigned, the
   figure is **ungrouped at the franchise level** (counted in totals, not shown
   as a completable franchise).

The umbrella blocklist is tuned against live output over time (a data-quality
refinement, not a design hole): a console holding many figures across many
properties is umbrella; one holding a single property is usable.

### 4.4 Intent (complete vs. cherry-pick) — per group, its own doc

Intent is per-group (locked: editing one figure sets intent for the whole group).
Stored as a new doc type mirroring `cat_pref::`:

```
id:   group_pref::{level}::{groupKey}
type: "group_pref"
level: "FRANCHISE" | "SET"
groupKey: "Hocus Pocus"   (or the set tag)
intent: "COMPLETE" | "CHERRY_PICK"
```

`level` distinguishes a franchise group from a named-set group so the two
namespaces can't collide.

**Default when no `group_pref` doc exists:** `COMPLETE` for franchise and named-set
groups. A group the user has never set intent for is treated as one they want to
finish, so its missing figures appear on the want list. Pre-existing collections
default every group to COMPLETE until the user opts one out.

**Backup:** the export query (`DatabaseTransferViewModel`, verified) is a
denylist — exports every doc whose `type` is NOT `catalog` and NOT `system`.
`group_pref` is neither, so it is included automatically with no backup-code
change. (Confirmed `cat_pref` already rides this same path.)

### 4.5 First-scan / first-add intent prompt

When the user adds or scans a figure whose **franchise** group has no
`group_pref::FRANCHISE::{franchise}` doc yet:
1. If a franchise is resolved (user-set, or a property-specific console
   suggestion), ask once: **"Complete this {franchise} set, or just this one?"**
   The answer creates the franchise `group_pref` doc.
2. If franchise is unresolved (umbrella console / nothing), first prompt the user
   to assign a franchise (free-text, with the console as a pre-fill only when
   property-specific), THEN ask the complete/cherry-pick question.

**O-3 (resolved): re-ask until set.** If the user dismisses without choosing, no
doc is written and the prompt re-appears on the next add for that group. Never
silently assume an intent from a dismissal. (Cap: ask once per add action, not in
a loop.)

Named-set intent is not prompted at scan time (it would double-prompt); it is set
from the detail screen or defaults to COMPLETE.

### 4.6 Editing intent + franchise later (detail screen)

The detail screen gains:
- A **franchise field** (free-text / pick), stamped into `userEditedFields` via
  the existing `markEdited()` when the user changes it. This is the authoritative
  franchise.
- A per-group **Complete / Cherry-pick** toggle for the figure's franchise (and,
  when present, its named set). Writes the relevant `group_pref` doc. Because
  intent lives in its own doc, it does not use the `userEditedFields` marker.

### 4.7 Want list = computed, not stored

The want list stops being "`funko::` docs with `isOwned == false`." Computed at
report time:

> For each group (franchise or named set) with intent `COMPLETE`: the `catalog::`
> figures in that group not owned by the user → wanted.

A figure is "owned" by exact catalog-handle match (each variant is its own slot —
O-1 resolved — so a glow/glitter/chase variant the user lacks is a distinct want).

Plus explicit manual wants (existing `isOwned == false` items) are preserved and
shown. **O-4 (resolved):** a manual want in a group later marked CHERRY_PICK is
KEPT — explicit entry is a stronger signal than the group default.

`CHERRY_PICK` groups contribute nothing auto to the want list but still display
their "owned of total" fraction.

De-dup: a figure missing from both a COMPLETE franchise and a COMPLETE named set
appears once on the want list, attributed to its most-specific group (the set if
present, else the franchise).

---

## 5. Data model changes

### 5.1 `FunkoItem` (`data/model/FunkoItem.kt`)
- **`franchise` already exists and is already the right home (O-5 RESOLVED).**
  Verified: the field is documented as "IP/character universe"
  (`FunkoDexDatabase:27`), is already user-editable (`DetailViewModel:229`,
  manual-add `ScannerScreen:1230`), but is currently **seeded badly** — scan/
  lookup populates it from the catalog's first `series` tag
  (`FunkoLookupService:161,407`), which is usually a format/line/genre tag, not
  a property. No new field or rename is needed. Changes:
  - Stop seeding `franchise` from the first series tag. Seed it from the
    property-specific console suggestion (§4.3) when available; otherwise leave
    blank so the first-scan prompt asks for it.
  - Add `franchise` to `markEdited()` protection so re-link/import never clobbers
    a user-set property (it is now user-authoritative).
- Add `setTag: String = ""` (named-set membership; "" = no named set).

### 5.2 `FunkoDexDatabase` (`data/db/FunkoDexDatabase.kt`)
- Add `const val TYPE_GROUP_PREF = "group_pref"`.
- Add `const val FIELD_SET_TAG = "setTag"`.
- Add `const val FIELD_GROUP_LEVEL = "level"`, `FIELD_GROUP_KEY = "groupKey"`,
  `FIELD_GROUP_INTENT = "groupIntent"`.

### 5.3 `FunkoMapper` (`data/db/FunkoMapper.kt`)
- Serialize `setTag` (write when non-blank; read back, missing → "").
- Field-agnostic backup serializer already carries new fields (verified S14).

### 5.4 New: `GroupIntent` enum + `GroupLevel` enum (`data/model/`)
- `enum class GroupIntent { COMPLETE, CHERRY_PICK }`
- `enum class GroupLevel { FRANCHISE, SET }`

### 5.5 New: `GroupPref` model + `GroupPrefRepository` (`data/repository/`)
- Mirror `CategoryPreferenceRepository`.
- `suspend fun getIntent(level, key): GroupIntent` — absent → COMPLETE.
- `suspend fun setIntent(level, key, intent)` — upsert `group_pref::{level}::{key}`.
- `suspend fun getAllIntents(): Map<Pair<GroupLevel,String>, GroupIntent>` — one
  query for the report.

### 5.6 `SeriesSummary` (`data/model/FunkoItem.kt`)
Already has `totalInCatalog`, `ownedCount`, `wantedCount`, `missingItems`,
`completionPct`. Add:
- `val groupKey: String` and `val level: GroupLevel` (what this summary groups by).
- `val intent: GroupIntent`.
- `missingItems` = catalog-derived missing figures for COMPLETE groups.

### 5.7 Franchise suggestion helper
- A small `ConsoleFranchise` util: given a `pricechartingUrl` (or console slug),
  return a property-specific franchise suggestion or null when umbrella. Holds the
  umbrella blocklist (§4.3).

---

## 6. Repository / report changes

### 6.1 `FunkoRepository.getCollectionStats()` rewrite
- Load owned items (`isOwned == true`).
- Resolve each owned item's franchise (user field) and `setTag`.
- Query `catalog::` docs; build two grouping maps: by franchise (excluding blank/
  umbrella-only) and by `setTag` (excluding blank). Count → real `totalInCatalog`.
- `missingItems` (COMPLETE groups) = catalog figures in the group not owned.
- `wantedCount` = `missingItems.size` (COMPLETE) / `0` (CHERRY_PICK).
- Add an "ungrouped" bucket for figures with no resolved franchise and no set.
- `completionPct` formula unchanged (now correct — real denominator).

### 6.2 Want-list assembly
- Aggregate `missingItems` across COMPLETE groups; de-dup to most-specific group.
- Append explicit manual wants (kept regardless of group intent).
- Group by franchise (then named set) for display.

---

## 7. UI (Reports + detail)

### 7.1 Per-group row — **Option A (locked)**: progress bar + fraction
- Group name (left) · "owned of total" fraction (right).
- Horizontal fill bar = `completionPct`.
- Fill color: green (`#1D9E75`) for COMPLETE; neutral gray (`#888780`) for
  CHERRY_PICK so an opted-out group doesn't read as alarmingly "incomplete".
- Footer: left = short status; right = intent pill ("completing" teal /
  "cherry-pick" gray).
- Franchise groups listed first (primary); named sets as a secondary section.

### 7.2 Want-list section
- Header "Want list" + total count.
- Grouped by franchise → named set.
- Each row: thumbnail (or placeholder icon), figure name, and `seriesNumber`
  ONLY when present (name-only when the catalog has no clean number — never
  fabricate one).
- Only COMPLETE groups appear here; explicit manual wants always appear.

### 7.3 Detail screen
- Franchise field (authoritative, `markEdited`-stamped).
- Complete / Cherry-pick toggle(s) for the figure's franchise and named set.

### 7.4 First-scan prompt
- Per §4.5: resolve/assign franchise, then ask complete vs. just-this-one.

---

## 8. Open questions (remaining)

- **O-5 (RESOLVED) — existing `franchise` field.** Verified: `FunkoItem.franchise`
  is already a user-editable "IP/character universe" field, merely seeded badly
  from the first `series` tag. No split/rename needed — keep it as the
  authoritative user franchise, stop seeding it from series tags, seed from the
  console suggestion when property-specific, add to `markEdited()`. See §5.1.
- **O-6 — umbrella blocklist maintenance.** The §4.3 blocklist is seeded from one
  run's console distribution. Define where it lives (enricher constant vs. app
  constant) and how it's updated. Leaning: enricher-side, emitted into output so
  the app reads a resolved `franchiseSuggestion` field rather than re-judging.
- **O-7 — set-tag derivation location.** Confirm the enricher will emit `setTag`
  (preferred) vs. the app deriving it at import. Leaning enricher.

(O-1, O-3, O-4 resolved this revision; O-2 superseded — density is fine, grouping
rule was the real issue, now specified.)

---

## 9. Testing (add before ship — schema-touching change)

Unit:
- `FunkoMapper` `setTag` roundtrip (present / blank / absent → "").
- `FunkoMapper` `franchise` user-field roundtrip + `userEditedFields` protection.
- `GroupPrefRepository`: absent → COMPLETE; set/read both intents; upsert
  overwrites; FRANCHISE and SET keys don't collide.
- `ConsoleFranchise`: property-specific console → suggestion; umbrella console →
  null (test harry-potter→"Harry Potter", disney→null).
- `getCollectionStats`: denominator from catalog count per franchise and per set;
  owned-of-total correct; CHERRY_PICK contributes 0 wants; blank groups go
  ungrouped; de-dup to most-specific group; `completionPct` math.
- Want-list assembly: COMPLETE-only auto; manual wants preserved even in a
  cherry-picked group (O-4); grouped + de-duped.

Backup/restore:
- A `group_pref` doc and an item with `franchise`+`setTag` survive
  export → import → force-restore (denylist covers it — verify, don't assume).

Device (carried into the existing device pass):
- First-scan prompt: franchise resolve/assign then intent, re-asks on dismissal.
- Detail franchise edit + toggle flips a whole group; want list updates.
- Restore onto fresh install (empty catalog) preserves franchise/setTag/intent
  before catalog re-import; fractions re-populate after import.

---

## 10. Files touched (summary)

New:
- `data/model/GroupIntent.kt`, `data/model/GroupLevel.kt`, `data/model/GroupPref.kt`
- `data/repository/GroupPrefRepository.kt`
- `data/util/ConsoleFranchise.kt`

Changed:
- `data/model/FunkoItem.kt` (+`setTag`; franchise semantics per O-5; `SeriesSummary` +fields)
- `data/db/FunkoDexDatabase.kt` (+constants/type)
- `data/db/FunkoMapper.kt` (+serialize `setTag`)
- `data/preload/CollectionRelinkService.kt` (`setTag` pure-enrichment refresh; franchise fill-only/protected)
- `data/preload/CatalogImporter.kt` / catalog→item builders (stamp `setTag`; franchise suggestion only)
- `data/repository/FunkoRepository.kt` (`getCollectionStats` rewrite + want-list)
- `di/AppModule.kt` (provide new repo if needed)
- `ui/screens/reports/*` (Option A rows: franchise + set sections, want list)
- `ui/screens/detail/*` (franchise field + intent toggle)
- add/scan flow (first-scan franchise+intent prompt)

Enricher (`funko_enrich`, separate change, after current batch):
- Emit `setTag` (most-specific named-set tag, §4.2).
- Emit `franchiseSuggestion` (property-specific console only, umbrella → omitted).
- Both are suggestions/enrichment; the app's user franchise always wins.

---

## 11. Gap scan

- [x] Two grouping levels defined (franchise/property + named set) — the user
      collects by property; v0.1 missed this.
- [x] Franchise sourcing resolved against VERIFIED data (live enricher code +
      3 real PriceCharting pages + fresh 12,176-record output), not assumed.
- [x] Console-as-franchise rejected with evidence (umbrella tier, 5% coverage,
      Hocus Pocus collapses to disney); user-assigned franchise is authoritative.
- [x] Named-set rule sharpened; naive frequency heuristic's failure documented
      (Hello Kitty mis-pick).
- [x] `seriesNumber` rejected as grouping key with reason.
- [x] Backup inclusion verified (denylist), not assumed.
- [x] Field-protection interaction specified (franchise = user/protected;
      setTag = pure-enrichment refresh; intent = own doc).
- [x] Default intent (COMPLETE) defined for pre-existing collections.
- [x] Empty-catalog / restore path addressed (stored fields, not read-time join).
- [x] Variants = separate slots (O-1).
- [x] First-scan dismissal = re-ask, never silent (O-3).
- [x] Manual want kept in cherry-picked group (O-4).
- [x] O-5 existing `franchise` semantics — RESOLVED (already a user field, §5.1).
- [ ] O-6 umbrella blocklist home/maintenance — leaning enricher-emitted.
- [ ] O-7 set-tag derivation location — leaning enricher.

Two open items remain (O-6, O-7); both are placement decisions about the enricher
vs. app boundary — where the set-tag and franchise-suggestion derivations live —
not unresolved design. Neither blocks app-side coding against the spec; they only
decide whether two derivations run in `enrich.js` or at import time.
