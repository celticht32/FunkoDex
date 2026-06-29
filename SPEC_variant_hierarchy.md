# SPEC: Three-Level Variant Hierarchy (FunkoDex)

Status: PROPOSED — design against FINAL enriched data; get approval on the
grouping rule + UI before coding. Build AFTER current import is validated.

License: MIT © 2026 Chris Ahrendt

---

## The three levels (the core model)

Funko collecting has three distinct levels that the app must represent separately:

1. **Base figure** — the character + Pop number. E.g. "Spider-Man #1329".
   Groups its official variants.
2. **Official/catalog variant** — a distinct SKU Funko produced. E.g. Spider-Man
   #1329 (Wood Deco), (Hologram), (Gold Eyes). Each has its own market value and
   its own catalog identity. MANY share the same Pop number + base name.
3. **User copy** — a specific physical item the user owns of one official variant.
   Same SKU, but an individual copy with its own condition, price paid, photo.
   Example (real): a Stitch where the bad-level paper is faded vs. another where
   it is full red — same official SKU, two user copies, very different value
   (full red paid $50, faded much less).

The app's existing `FunkoItem.variants: List<FunkoVariant>` already serves LEVEL 3
(user copies of an owned item). What is MISSING is LEVEL 1 (grouping official
variants under a base figure).

## Current state (why this is needed)

The enriched catalog is FLAT: Spider-Man #1329 exists as 9 independent top-level
records with NO variant-link field (`isVariant`/`variantOf`/`baseHandle` all
absent). They share only `funkoNumber` + base name in the title parenthetical.
On import they come in as 9 separate entries, ungrouped.

For OWNERSHIP this flat structure is correct (you own a specific official variant).
For BROWSING/COMPLETION ("Spider-Man #1329 — you own 2 of 9 variants") the app must
group them.

## The grouping rule (the hard part — needs care + approval)

Group official variants by `funkoNumber` + normalized base name. BUT this is fuzzy
and must not over-merge:
- "Spider-Man" + "Spider-Man (Wood Deco)" → SAME base #1329. Group. ✓
- "Vegeta" + "Great Ape Vegeta" → DIFFERENT figures that may share a number. Do
  NOT group. ✗
So the base name must be derived carefully (strip trailing parenthetical variant
qualifiers, but NOT leading descriptive words that change the character). Getting
this wrong either splits a real group or merges distinct figures.

Two implementation options (decide against final data):
  (a) Catalog-side: derive a `variantGroup` / `baseFigure` key in enrich.js
      post-process and store it on each record. App groups by that key. Pro:
      grouping logic lives in one place, testable offline. Con: re-run to change.
  (b) App-side: group by `funkoNumber` + normalized base-name at display time.
      Pro: no catalog change. Con: grouping logic in the app, recomputed each view.

Recommendation leaning (a) — a derived key is testable and consistent — but
VALIDATE the rule against final data with a sample of known tricky cases
(Vegeta/Great Ape Vegeta, Goku forms, Spider-Man variants) before committing.

## UI (show rendered options for approval BEFORE spec'ing)

- Base figure view: "Spider-Man #1329" with its official variants listed; mark
  which the user owns (e.g. "2 of 9").
- Tapping an official variant → its detail, including the user's copies (level 3).
- Level-3 user copies: each with own condition, price paid, photo, market value.

Per Chris's preference, render UI options for approval before writing into the
final spec.

## Data model changes (sketch — finalize at build)

- Catalog `catalog::` doc: optional `variantGroup` key (if option a).
- `FunkoItem`: already has `variants: List<FunkoVariant>` for level-3 copies; may
  need a `baseFigureKey` to associate owned items with their base group.
- `FunkoVariant`: already carries per-copy condition/pricePaid/photo. Confirm it
  also carries a per-copy market value + currency (see SPEC_regional_currency).

## Dependencies / sequencing

- Same grouping-field question as relink mapping + want-list + set membership. One
  investigation against final data resolves all.
- Build after the current (flat) catalog import is validated on-device.

## Related

- IDEA_browse_set_wantlist.md (variant addendum) — same grouping need.
- SPEC_regional_currency.md — per-copy price needs a currency.
