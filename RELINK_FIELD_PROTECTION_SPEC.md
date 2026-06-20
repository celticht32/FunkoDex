# Re-link Field Protection (Option B) — Follow-up Spec

Status: scoped, not yet implemented. This is the runtime (on-device) feature that lets
a shipped app push catalog improvements onto items a user already owns, without
overwriting fields the user edited by hand. It is NOT part of the golden-master build —
the master ships catalog-only with an empty user collection, so nothing here affects it.

## Problem

On `funko::` (owned-item) docs, several fields are BOTH enricher-derived AND user-editable
in the detail/edit screen. Confirmed user-editable set (from DetailViewModel):
name, franchise, seriesNumber, pricePaid, condition, notes, category (+genre), upc,
imageUrl, marketValue, dateAcquired, variants, isMissingOriginal.

Of these, the ones that overlap with catalog enrichment are: franchise, category, upc.
There is no per-field signal (except marketValueIsManual for market value) to tell a
user-corrected value from a stale auto-derived one. So the current re-link keeps
franchise/category/upc as fill-only (write only when blank) to avoid clobbering edits.
That means a corrected catalog category will not reach an owned item that already has any
category value.

## Solution

Track which fields the user explicitly edited, then let re-link overwrite any field NOT in
that set.

### 1. Schema addition (funko:: docs)
Add `userEditedFields: List<String>` to FunkoItem and FunkoMapper (read + write). Stores the
FIELD_ keys the user has touched, e.g. ["category", "franchise"]. Default empty.

### 2. Stamp on edit (DetailViewModel)
Each `updateX` that corresponds to a user-editable field appends that field's FIELD_ key to
the draft's `userEditedFields` (deduped) before save. Map:
- updateName → "name"
- updateFranchise → "franchise"
- updateNumber → "seriesNumber"
- updateCondition → "condition"
- updateNotes → "notes"
- updateCategory → "category" (also implies genre is locked)
- updateUpc → "upc"
- updateImageUrl → "imageUrl"
- updateMarketValue → already covered by marketValueIsManual; optionally also stamp "marketAvg"
- pricePaid, dateAcquired, variants, isMissingOriginal → stamp likewise if re-link ever touches them (it currently does not)

### 3. Re-link honours the set (CollectionRelinkService)
For each enrichment field, change the rule from "fill when blank" to:
  overwrite from catalog WHEN field key is NOT in item.userEditedFields.
Apply to: franchise, category(+genre), upc, retailPrice, pricechartingUrl, funkoId,
and market values (still also gated by marketValueIsManual). Keep imageUrl protected when
either user-edited OR non-blank (image quality not monotonic). catalogRef backfill and
one-way isVaulted unchanged.

### 4. Migration (critical — do not skip)
Docs created before this field exists have no `userEditedFields`. They must NOT be treated as
"nothing edited" (that would let re-link clobber values a user set before the marker existed).
Rule: when `userEditedFields` is ABSENT from the doc (vs present-but-empty), fall back to
fill-only behaviour for franchise/category/upc — i.e. the current safe behaviour. Only docs
saved after the upgrade (which will have the key, possibly empty) get full refresh semantics.
Distinguish absent vs empty by checking the raw doc for the key before mapping, or store a
schema-version marker.

## Test surface
- New unit tests: FunkoMapper roundtrip of userEditedFields (present/empty/absent).
- Re-link tests: edited field preserved; non-edited field refreshed; pre-marker doc falls back
  to fill-only.
- Edit-screen test: each updateX stamps the right key, no duplicates.

## Why this is separate from the master build
The golden master is the bundled enriched catalog + empty user collection. Re-link only runs
on a user's device against their own owned items. This feature changes runtime behaviour for
app updates; it has its own migration and test surface and should ship on its own, not folded
into the catalog-quality work that builds the master.
