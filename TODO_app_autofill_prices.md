# TODO: Fill prices for PriceCharting-unpriced items, ON ADD (FunkoDex)

Status: PROPOSED — pipeline side DONE (enrich.js stamps `priceSource`), app side
not started.

License: MIT © 2026 Chris Ahrendt

---

## Why

PriceCharting can't price ~24% of the catalog — not a matcher weakness, but items
PriceCharting genuinely doesn't carry: non-figure merch (advent calendars,
collector boxes, socks, plush, passports), convention/exclusive variants, multi-
character packs, and obscure variants. Verified: PriceCharting UPC search returns
"No results" for these (e.g. Freddy Frostbear UPC 889698538435).

The app already has live price tiers (eBay sold, UPCitemdb, HobbyDB) that can often
price these on demand — fresher than a baked catalog value anyway. So instead of
baking noisy eBay snapshots into the golden master, the pipeline MARKS the gap and
the app fills it live — but ONLY when the user deliberately adds the item.

## TRIGGER — important: user-initiated only

The live price fetch fires ONLY when the user **scans or searches for a Funko and
ADDS it to their collection**. It is tied to the add-to-collection action on a
single item the person actually wants.

It must NOT fire:
- on catalog import (never fire thousands of eBay lookups when the catalog loads),
- automatically on merely VIEWING a catalog/detail item (browsing must stay cheap),
- as any background or bulk pass over the catalog.

Rationale: the eBay/live tiers are rate-limited and fragile (challenge pages,
markup changes). Tying the call to a deliberate single-item add keeps volume tiny,
relevant, and user-intended — one lookup for one item the person chose to own.

## Pipeline side (DONE)

enrich.js stamps every record with `priceSource`:
- `priceSource: 'pricecharting'` — a real PC price is present.
- `priceSource: 'none'` — PC could not price it; the app MAY fill via live tiers
  WHEN the user adds the item (see trigger above).

Coverage at last run: ~19,725 `pricecharting`, ~6,081 `none`. Of the `none`:
~2,078 have a usable UPC (eBay-by-UPC, precise), ~4,003 have no UPC (eBay-by-title,
fuzzier — the obscure-variant tail).

## App side (TODO)

1. **Read the flag on import.** `CatalogImporter.toEnrichedRecord()` reads fields
   explicitly and ignores unknowns, so `priceSource` is currently DROPPED on
   import. Add a reader for it (+ to `EnrichedRecord` + `CatalogMapper` + the
   catalog doc field) so the flag survives into app data. Without this step the
   flag does nothing. (This step does NOT trigger any network call — it only
   stores the flag.)

2. **On ADD to collection, if needed, pull a live price.** In the scan/search ->
   add flow, AFTER the item is added to the collection (`funko::` doc created):
   if the item has no market value AND `priceSource == "none"`, fire the existing
   `PriceService` waterfall ONCE for that single item. Use UPC when present
   (precise), title otherwise (fuzzy — same caution as PriceCharting title
   matching). Items already priced by PriceCharting need no live call.

3. **Persist the live result on the user's item.** Save the fetched price as a
   snapshot on the `funko::` doc with a timestamp + which tier answered (e.g.
   `ebay_sold`), via the existing `savePriceSnapshot` mechanism. This is the
   owned item's price; it is not written back to the shared `catalog::` doc.

4. **Manual refresh still available.** The existing detail-screen Refresh button
   stays as the way to re-pull later (respecting a sane TTL so eBay's fast-staling
   sold prices can be refreshed on demand). Auto-pull happens once on add; further
   refreshes are user-tapped.

## Why not bake eBay prices into the pipeline, and why not on import/view

- eBay sold prices are noisy and stale fast; baking them lowers golden-master
  quality vs PriceCharting's curated averages.
- Bulk eBay calls (on import, or background) are heavy and fragile — rate limits,
  challenge pages, markup changes.
- Firing on view makes browsing expensive and still over-fetches items the user
  never adds.
- Firing on ADD is the minimal, user-intended trigger: one lookup, one chosen
  item, at the moment the person commits to owning it.

So: pipeline carries the reliable PriceCharting baseline + flags the gap; the app
fills the gap for a single item only when the user adds it.

## The hard floor (set expectations)

Even with live eBay fill, some items won't price anywhere (truly obscure, no sold
history). `priceSource: 'none'` + no live result on add = genuinely unpriceable;
show "no price available" rather than implying a failure. The catalog is ~76%
priced by PriceCharting; the on-add live tier closes part of the rest for items the
user owns; a tail remains unpriceable. That tail is a data-availability limit, not
a bug.

## Related

- IDEA_browse_set_wantlist.md — the want-list's "available on eBay" row reuses the
  eBay-ACTIVE tier (distinct from eBay-SOLD used for pricing).
- PriceService waterfall (network/PriceService.kt) — the live tiers to trigger.
