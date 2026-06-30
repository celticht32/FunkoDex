# FunkoDex — Consolidated Test Plan & Tracker

Version: v1.0 (2026-06-29)
License: MIT © 2026 Chris Ahrendt

Single test document for FunkoDex. Supersedes and replaces (now safe to delete):
`COMPLETE_TEST_PLAN_v2.0.md`, `COMPLETE_TEST_PLAN.md` (v1), `TEST_TRACKER_v2.0.md`,
`DEVICE_TEST_PLAN.md`, `BACKEND_SETUP.md`, `SESSION_D_TRACKER.md`.

Composed from those sources verbatim (cases unchanged) under one roof:
- PART 1 — Functional test plan (code-verified, Parts A–E)
- PART 2 — Execution tracker (check items here as run)
- PART 3 — On-device test plan
- PART 4 — Backend setup (stand up everything the app talks to)
- PART 5 — NEW test surface: Collection Completion + Want List (FUNKODEX_SPEC_v1.0 §1)

New feature specs live in `FUNKODEX_SPEC_v1.0.md`. Architecture in `CLAUDE.md`.

---

# PART 1 — Functional Test Plan (code-verified)


<!-- Version: v2.0 (2026-06-20). Reconciled against Session 13 source commits
     9a315bf, 294dc84, c9fa9c3, d38fd18 (all on origin/master @ 938a5f0). -->

Every UI label, dialog title, and behavior below was verified against the
repository source (master, verified in sync with GitHub). As of Session 9
(commit `d69a4ec`), `ReportsScreen.kt`/`ReportsViewModel.kt` and
`CatalogDataSection` are present and wired — the "local-only file" caveats
that previously applied to A9/B1–B3/B6 no longer apply.

**Session 13 additions (2026-06-20)** — reconciled from source diffs, all on
`origin/master`:

- **Scan now reads the Couchbase catalog first** (`c9fa9c3`,
  `FunkoLookupService.lookupCatalogByUpc`). The bundled `funko_data.json` is
  only a preload seed; the live catalog (which holds every imported/enriched
  record) is the source of truth and is queried first, bundled JSON second,
  network APIs last. UPC matching normalizes leading zeros both ways. **Changes
  the preconditions for A2a/A2c/A2d** and adds A2g.
- **Live PriceCharting refresh tier** (`d38fd18`, `PriceService.fetchPriceCharting`
  + new `PriceSource.PRICECHARTING`). When an item carries a catalog-stored
  `pricechartingUrl`, a price refresh re-scrapes that exact page via a plain
  OkHttp GET (Android UA, no headless browser) and reads the three grade prices
  from `#used_price`/`#complete_price`/`#new_price`. This tier runs **before**
  the retail short-circuit because MSRP is not a market value. **Adds A4l**;
  affects A4g/A9/B3 (market totals can now move on refresh for catalog items
  with a PC URL).
- **Channel3 tier rewritten** (`9a315bf`) to the real API: `POST /v1/search`,
  `x-api-key` header, JSON body. **A key is now required for every Channel3
  call — the old free/keyless tier no longer exists.** **B1 is rewritten.**
- **Channel3 key import from file** (`9a315bf`,
  `CatalogSettingsViewModel.importKeysFromFile`): an "Import from file" button
  in the Channel3 key dialog loads `funkodex_keys.json` from Downloads
  (recognizes `channel3_api_key`; `ebay_client_id`/`hobbyDB` are accepted but
  not yet wired). **Adds B1b.**
- **Channel3 key-entry UI hidden by default** (`d38fd18`,
  `SHOW_CHANNEL3_KEY_UI = false`). The manual key-entry row + dialog are
  suppressed in Settings; the import-keys path still functions. **B1 reachability
  changed — see B1.**
- **UPC-based import de-dup + merge-on-collision** (`294dc84`,
  `CatalogImporter.buildUpcIndex`/`mergeRecordInto`). Imports now match on
  handle → UPC → title, and an insert that collides with an existing doc
  **merges (fill-only) instead of skipping**, so a colliding record contributes
  its price/metadata rather than being dropped. `marketValueComplete` and the
  full PriceCharting metadata set (releaseDate, ebayEpid, amazonAsin, printRun,
  publisher, pcSeries, pcDescription) now flow through import. **Changes D1a
  path coverage; adds D1c.**

> **One source inconsistency to be aware of (not a test target):** a code
> comment in `d38fd18`/`SettingsScreen.kt` claims "the free Channel3 tier still
> works." It does not — `9a315bf` removed the keyless tier. Treat Channel3 as
> fully dormant without a key (see B1). Flagged for the next code cleanup.

**Session 12 additions (2026-06-19):** manual-UPC check-digit validation (the
manual-add UPC field rejects malformed entries and shows "Valid UPC" / error —
test A2d-3), a third "Enter details manually" button on the Add start screen
(A2e), "Add another" now returns to the live camera not the chooser (A2f),
variant-aware pricing for chase/exclusive items (A4k — verify a chase prices
differently from the common version where data exists), and a camera-executor
leak fix (no user-visible change; the camera still rebinds on resume — A4j).

**eBay pricing (Tier 2a) status — corrected Session 12:** the HTML-scrape parser
is *not* broken. It was verified against a live sold-listings page and parses
current eBay markup correctly. The 403s seen earlier are a fetch-time bot
challenge from datacenter IPs, not a parse failure — on a real device's
residential connection the fetch may well succeed, so eBay *may* contribute
prices during device testing. Do not assume eBay returns nothing; check the logs.
See CHANGELOG Session 12.

Recommended order: **Part A** (core) → **Part B** (integrations) → **Part C**
(backup/restore — LAST, force-restore wipes the database) → **Part D**
(automated) → **Part E** (16 KB regression).

Bottom nav tabs (left to right): **My Dex** · **Add** · **Check** · **Reports**
· **Settings**.

---

# PART A — Core Collection Features

## A1. First launch & catalog preload

1. Fresh install on a clean emulator/device.
2. Launch. **Expected:** branded splash (navy background, spinning brass ring,
   Celtic heart logo). On first launch the splash stays up until the catalog
   preload finishes, then navigates to **My Dex**.
3. **My Dex** shows the empty-collection state (no crash, no error toast).
4. Verify preload worked: **Add** tab → **Search by name** → search "Star
   Wars" in the **Search Catalog** sheet. **Expected:** results appear.
5. Logcat check (filter `CatalogPreloader`): preload result `Loaded(count)`
   with count ≈ 23,000; on subsequent launches `AlreadyLoaded`. No
   `CouchbaseLiteException` from `ensureIndexes()`.

**Pass:** splash → preload → My Dex, search returns catalog results.

---

## A2. Add tab — UPC scan paths

The **Add** tab start screen shows: header **"Add to collection"**, subtext
**"Scan a Funko UPC barcode or search manually"**, a **"Start scanning"**
button and a **"Search by name"** button.

> **Precondition (Session 13, `c9fa9c3`):** UPC scans resolve against the
> **Couchbase catalog first**, then bundled `funko_data.json`, then network.
> So a "found in catalog" result (A2a) means the catalog held the UPC — which
> requires the catalog to have been preloaded/imported. On a fresh install
> before any enriched import, only UPCs present in the bundled seed will hit
> locally; everything else falls to network. Run A1 (preload) before A2, and
> if you've run an enriched import, expect imported UPCs to resolve offline.

### A2a. Scan → found in catalog → add

1. Tap **Start scanning**, point at a Funko Pop barcode.
2. **Expected:** overlay **"Looking up on Funko.com…"**, then the preview
   bottom sheet: item image, name, franchise + series number,
   "<Retailer> Exclusive" chip if applicable, **"Price paid (optional)"**
   field (with "$" prefix; placeholder shows "Retail: $X" when known), and two
   buttons: **"Want list"** and **"Add to collection"**.
3. Tap **Add to collection**.
4. **Expected:** confirmation screen: **"Added!"** + item name, with buttons
   **"Add another"**, **"I only have the variant — want the original"**, and
   **"Done"**.
5. Tap **Done** (or **Add another** to keep scanning). Item appears in
   **My Dex** (isOwned = true, sorted to top under "Recently Added").

### A2b. Scan → add to want list

6. Scan another item, tap **"Want list"** instead.
7. **Expected:** item saved with isOwned = false. It will NOT appear in
   **My Dex** (My Dex shows owned items only — verified: `collectionFlow`
   filters `isOwned = true`). Verify it saved by either:
   - **Check** tab: scan the same barcode → blue **"NOT IN YOUR COLLECTION"**
     overlay with the orange **"★ On your want list"** badge, or
   - **Add** tab: scan the same barcode again → preview sheet reopens
     (want-list items re-present as Preview so you can confirm you bought it).

   > Note: there is no dedicated want-list browsing screen, but
   > `ReportsScreen.kt`'s per-series "Show want list (N)" expandable section
   > (added Session 9) lists missing/wanted items per series — verify there.

### A2c. Scan → already owned

8. Scan the barcode of an item you already own.
9. **Expected:** sheet **"Already in your collection"** with options:
   - **"I have a variant of this"** — "Adds a variant copy to the existing
     record — same Funko, different version"
   - **"I have a variant but NOT the original"** — "Adds as a variant and
     flags the original on your want list"
   - **"Update existing record"** — "Edit condition, price, or notes on the
     existing item"
   - **Cancel**
10. Test each path once: variant add (check Variants section on the detail
    screen afterward), variant-missing-original (check the **"Got it!
    Variant only — no original"** chip appears on the detail screen), and
    update (lands you in the edit flow).

### A2d. Scan → unknown UPC, network available

11. Scan a barcode not in any source (a non-Funko product barcode works).
12. **Expected:** sheet titled **"Barcode not in catalog"** with:
    - **"Search by name (e.g. Batman)"** field with a trailing search icon
      (Session 11: icon moved to trailing; live search as you type, ≥2 chars)
    - results list when matches are found
    - a **"No catalog matches for …"** line when a search returns nothing
      (Session 11 empty-state)
    - a **"Scan again"** button (Session 11 — returns to live scanning, clears
      the last-scanned UPC so the same barcode can be re-read)
    - an **"Add manually"** button (Session 11 — see A2d-2)
13. Search and tap a catalog match. **Expected:** transitions to the normal
    preview sheet with the scanned UPC merged onto the matched item. **A
    community contribution (source USER_SCAN) is saved silently — there is no
    prompt for this path** (see B5).

### A2d-2. Scan → unknown UPC → Add manually (Session 11)

11b. From the "Barcode not in catalog" sheet (A2d), tap **"Add manually"**.
12b. **Expected:** **"Add item manually"** sheet with the UPC shown **locked**
     (from the scan, lock icon), a required **"Name"** field, an
     **Owned / Want list** toggle, a **"More details"** expander, and a
     **"Share with community UPC database"** checkbox (on by default).
13b. Expand **More details**. **Expected:** Pop! number (box number),
     Franchise, Category (dropdown), Exclusive toggle → retailer/event,
     Price paid, Condition, Image URL. Confirm the sheet scrolls so
     **"Add to collection"** stays reachable.
14b. Enter only a Name, tap **"Add to collection"**. **Expected:** item saved
     to My Dex with id `funko::{upc}`; sheet dismisses to scanning. With share
     left on and a UPC present, a `USER_MANUAL` `CatalogContribution` is queued
     (verify via B5 worker/contribution log).
15b. Re-scan the same barcode. **Expected:** now resolves instantly to the
     manually-added item (UPC is linked).

**Pass:** locked UPC carries through; name-only save works; Save reachable with
More details open; future scan of the same UPC resolves; contribution queued
when shared.

### A2e. Scan offline → pending queue

14. Enable airplane mode. Scan an unknown-to-local-catalog barcode.
15. **Expected:** sheet **"Scan queued — no network"** showing "UPC: <upc>"
    and a **"Scan another"** button. A `pending_scan::` doc is saved.
16. Disable airplane mode (app can be foreground or background).
17. **Expected:** `ConnectivityObserver` processes the queue. If resolved, the
    item is added **to the want list** ("user can confirm ownership later")
    and a notification posts: **"Funko scan identified"** (or "N Funko scans
    identified"), body "<names> added to want list". Requires
    POST_NOTIFICATIONS granted (API 33+); without it the resolution still
    happens, just silently (logcat confirms). Unresolvable scans increment a
    retry count and are abandoned after 5 attempts.

**Pass:** all five scan outcomes (found / want-list / already-owned / unknown
/ offline-queued) behave exactly as above.

### A2g. Catalog-first UPC resolution + leading-zero normalization (Session 13)

Verifies `c9fa9c3`: the scan path queries the Couchbase catalog before the
bundled JSON, and matches UPCs regardless of leading-zero encoding.

1. **Catalog-only UPC:** import an enriched record whose UPC is **not** in the
   bundled `funko_data.json` (any PriceCharting/HobbyDB-sourced UPC the seed
   lacks works). Scan that UPC.
2. **Expected:** the item resolves to the **imported catalog** record (correct
   name/image/market value seeded from `marketValueComplete`), not a network
   round-trip. Logcat (`FunkoLookup`) shows the catalog hit, no external API call.
3. **Leading-zero case:** find a catalog item whose stored UPC has a leading
   zero (or scan a UPC-A barcode that the scanner emits with/without the leading
   zero). Scan it.
4. **Expected:** it still matches — `lookupCatalogByUpc` normalizes leading
   zeros both ways, so `0889698…` and `889698…` resolve to the same doc. No
   "Barcode not in catalog" sheet for a UPC the catalog actually holds.
5. **Fallback intact:** scan a UPC that's in the bundled seed but not the
   imported catalog (e.g. on a fresh install with no import). **Expected:** it
   still resolves from the bundled JSON — catalog-first does not break the seed
   fallback.

**Pass:** catalog UPCs resolve locally before network; leading-zero variants
match; bundled-seed fallback still works when the catalog lacks the UPC.

---

## A3. Add tab — manual search + batch scan

### A3a. "Search by name" bulk add

1. **Add** tab → **Search by name**.
2. **Expected:** bottom sheet **"Search Catalog"**, text field placeholder
   **"e.g. Stitch, Batman, Mandalorian"**. Keyboard auto-hides on open.
3. Type "Stitch", submit (keyboard Done or the search icon).
4. **Expected:** "N results" count and a list with checkbox + image + name +
   franchise/#. **Note:** results are filtered by your enabled categories
   (`searchByName` applies the category filter) — if a category is disabled in
   **Settings → Collection categories**, matching items won't appear here.
   This is the correct place to observe the category filter working (see A6).
   **Session 11:** matching is now token-based and punctuation-tolerant —
   "mr toad", "mr. toad", and "toad mr" all return "Mr. Toad". Spot-check a
   punctuated title to confirm.
5. Select 2–3 rows. **Expected:** rows highlight, "N selected" shows, bottom
   button changes from **"Select items to add"** (disabled) to
   **"Add N to collection"** (enabled).
6. Tap **"Add N to collection"**. **Expected:** all selected items land in
   **My Dex** as owned.
7. Search gibberish ("zzzznonexistent") — **expected:** **"No results found"**
   plus an **"Add manually"** button (Session 11). Tap it. **Expected:** the
   **"Add item manually"** sheet opens with the **UPC field editable/blank**
   (not locked — there was no scan); enter a Name and **Add to collection**;
   item lands in My Dex (id `funko::{uuid}` when no UPC entered). This is the
   manual-search entry point to manual add (A2d-2 is the scan entry point).

### A3b. Batch scan

8. **Add** tab → **Start scanning** → tap the **"Batch scan"** FAB (bottom
   right of the camera view).
9. **Expected:** **"Batch scan"** sheet opens. Scan several items in
   sequence; each appears as a row. A **"Want list"** toggle chooses the
   destination; **"Save all (N)"** shows the found count.
10. Tap **"Save all (N)"**. **Expected:** all items saved (owned, or want
    list if toggled), sheet closes.

**Pass:** search sheet (incl. category filtering and exact button states) and
batch scan both work.

---

## A4. Detail screen — view, edit, photos, variants

Open an item from **My Dex** (tap its card).

### A4a. View mode

1. **Expected layout:** hero photo card; name; franchise · series number row;
   chips as applicable ("<Retailer> Exclusive", "Vaulted", **"Got it!
   Variant only — no original"**); an **"In collection"** (or **"On want
   list"**) status card with **"Tap to move"**; a **Details** card (Category,
   UPC — shows "—" when empty, etc.); **Notes** if set; a **Pricing** card and
   a **"Market Price"** card showing **"Tap refresh to load prices"** until
   refreshed; a **Variants** section if variants exist ("Variants", "N
   total", per-variant photo or "No photo", note, "Paid: $X", and "Edit this
   item to add a photo" hint).
2. Top bar: back arrow, **Edit** (pencil) and **Delete** (trash) icons.
3. Tap the status card — **expected:** item toggles owned ↔ want list
   immediately ("Tap to move"). Toggle it back.
4. For an **owned** item not variant-flagged: a text button **"I only have
   the variant — want the original"** is visible. Tapping it flags the item
   (`isMissingOriginal`); the **"Got it!  Variant only — no original"** chip
   appears. Tapping that chip opens **"Do you now own the original?"** with
   **"Yes — I have the original"** / **"No — still looking"**.

### A4b. Edit mode — fields

5. Tap the pencil. **Expected:** title becomes **"Edit Funko"**; top-bar
   actions become **"Save"** (text button) and a Close (X) icon that cancels.
6. Fields: **Name**, **Series**, **#**, **Price paid** ($ prefix),
   **Market value** ($ prefix — Session 11), **Condition** chips, **Notes**,
   **Image URL** (Session 11), **UPC** (with a camera icon, read-only/scan-only).
7. Edit Notes and Price paid → **Save** → re-open → values persisted.

### A4c. Edit mode — UPC scan + community prompt

8. In edit mode, tap the UPC field's camera icon.
9. **Expected:** camera permission prompt if not yet granted, then a
   full-screen scan dialog: **"Point at the UPC barcode on the box"**. Scans
   one barcode and closes, filling the UPC field.
10. **Save** with a new/changed UPC. **Expected:** dialog **"Share UPC with
    community?"** ("You added UPC <upc> for "<name>"… No personal data is
    shared…") with **"Share"** / **"No thanks"**.
    - **Share** → contribution saved (source USER_EDIT).
    - Clearing a UPC and saving deletes any pending contribution for the old
      UPC (verify via B5 if desired).

### A4d. Photos — camera, gallery, catalog fetch

11. In edit mode, tap the camera FAB on the photo card. **Expected:** options:
    - **"Take a photo"** — "Use your camera to photograph this item"
    - **"Choose from gallery"** — "Pick an existing photo from your phone"
    - **"Fetch from catalog"** — "Download the official image from the Funko
      catalog"
12. **Take a photo** → camera permission if needed → shoot. **Expected:**
    the **"Save photo as"** dialog appears:
    - **"Main photo"** — "Replaces the primary image in your collection"
    - **"Variation photo"** — "Stores as a variant — same item, different
      version"
    - **"Both"** — "Saves as main photo and adds a variation record"
    - **Cancel**
13. Choose **Main photo** — photo becomes the hero image and the My Dex
    thumbnail.
14. **Choose from gallery** — **expected:** the system **Photo Picker** opens
    with **no storage-permission prompt** (Session 6 removed
    READ_MEDIA_IMAGES/READ_EXTERNAL_STORAGE). Pick an image → same "Save
    photo as" dialog → choose **Variation photo** — **expected:** a new
    variant appears in the Variants section with that photo.
15. **Both** on a third image — sets main photo AND adds a variant.
16. **Fetch from catalog** — **expected:** dialog **"Fetching image"**
    ("Downloading image from the Funko catalog…") then **"Image downloaded"**
    ("The catalog image has been saved to this item.") — or **"Image not
    available"** if the catalog has none for this item. **Session 11:** image
    URLs that start with `http://` are upgraded to `https://` before loading,
    so a previously-failing **"CLEARTEXT communication not permitted"** URL
    (e.g. media.aent-m.com) should now succeed. A redirect-loop / "too many
    follow-up requests" URL is dead data — fix by setting a real Image URL
    (A4h), not an app bug.

### A4g. Manual market value (Session 11)

18. In edit mode, set **Market value** to a dollar amount on an item the price
    tiers can't price (e.g. a manually-added exclusive). **Save.**
19. **Expected:** the Market Price card shows the value with source
    **"Manually set"**. The value persists across navigation.
20. Tap **Refresh prices** (refresh icon on the Market Price card) for that
    item. **Expected:** the manual value is **NOT** wiped — the card keeps
    showing it and adds a small **"No new market data found — showing last
    known value."** note. (Regression guard for the staleDays-overflow bug.)
21. On an item the tiers CAN price, set a manual value, then **Refresh**.
    **Expected:** a real market feed **overwrites** the manual value and the
    "Manually set" source is replaced by the feed's source.
22. Open **Reports**. **Expected:** "Est. Market Value" includes manually-set
    values.

**Pass:** manual value displays and persists; no-data refresh keeps it; real
feed overwrites it; Reports totals include it.

### A4l. Live PriceCharting refresh tier (Session 13)

Verifies `d38fd18`: a price refresh on an item that carries a catalog-stored
`pricechartingUrl` re-scrapes that exact page and reads the three grade prices.
This tier runs **before** the retail short-circuit (MSRP is not a market value).

1. **Precondition:** use an item whose catalog doc has a `pricechartingUrl`
   (any record enriched by funko_enrich Pass 3 — confirm via the item's detail
   or by checking the imported record). Add it to your collection.
2. Open the item, tap **Refresh prices**.
3. **Expected:** the Market Price card updates from PriceCharting. Source shows
   PriceCharting; the displayed value is the **Complete (in-box)** grade (the
   primary), with loose/mint available as low/high. Logcat (`PriceService`)
   shows **"Tier 1m (PriceCharting) hit for <name>"**.
4. **Retail-doesn't-mask-market:** on an item that has BOTH a catalog
   `retailPrice` (MSRP) and a `pricechartingUrl`, refresh. **Expected:** the
   PriceCharting market value is shown, **not** the retail price — the PC tier
   runs ahead of the retail short-circuit. (Retail/Tier-1 catalog behavior for
   items *without* a PC URL is unchanged.)
5. **No-URL item:** on an item with no `pricechartingUrl`, refresh. **Expected:**
   the PC tier is skipped silently (returns null) and the normal waterfall
   (eBay/Channel3/HobbyDB/retail) runs as before — no crash, no error.
6. **Parser sanity:** if a PC page returns no parseable grade prices, the tier
   returns null and the waterfall continues. (No manual value is blanked —
   cross-check with A4g step 20.)

**Pass:** PC-URL items refresh from PriceCharting (Complete grade primary, log
line present); the PC tier precedes retail; no-URL items fall through cleanly.

### A4h. Image URL entry + http→https (Session 11)

23. In edit mode, paste a **direct image URL** (HobbyDB/funko.com, ending in an
    image extension or a CDN image path) into **Image URL**. **Save.**
24. **Expected:** image appears on the detail card and the My Dex thumbnail.
25. Change the Image URL to a different valid image and **Save**. **Expected:**
    the thumbnail re-downloads — the new image replaces the old cached one
    (auto re-download on URL change).
26. (Negative) Paste a **page** URL (e.g. `…/78901.html`). **Expected:** it does
    NOT render — a page URL is not an image; this is expected, not a bug.

**Pass:** valid image URL loads and persists; URL change re-downloads; http
hosts load via https; page URLs correctly fail to render.

### A4e. Variants editing

17. In edit mode with variants present: each shows **"Variant N"**, a remove
    icon, **"Description (e.g. Metallic paint)"**, and **"Price paid"** ($).
18. Edit a description and price → Save → re-open → persisted.
19. Remove a variant → Save. **Expected:** that variant (and its photo) gone;
    main item and other variants untouched.

### A4f. CRITICAL blob-preservation regression (Session 7)

20. On an item with a main photo AND variants: edit ONLY the Notes field →
    **Save**.
21. **Expected:** hero photo, My Dex thumbnail, and ALL variants with their
    photos still intact. (`FunkoMapper.toDocument` must reuse
    `existing?.toMutable()` through `collection.getDocument`/`collection.save`.)

**Pass:** every flow above with exact dialogs/labels; A4f shows zero blob or
variant loss.

---

## A5. Delete item

Two delete paths — test both:

1. **My Dex card:** tap the **⋮** (kebab) icon at the top-left of a grid card
   → **"Delete"** menu item. **Expected:** item disappears immediately (live
   query listener). There is **no swipe-to-delete**.
2. **Detail screen:** trash icon → dialog **"Remove from collection?"**
   ("This will permanently delete <name>.") → **"Delete"**. **Expected:**
   navigates back, item gone from My Dex.

**Pass:** both paths delete cleanly, no crash.

---

## A6. My Dex — search, sort, filter

1. **Search field** at top, placeholder **"Search collection…"**; matches
   name OR franchise, live. A clear (X) icon appears when non-empty.
2. Tap the **filter icon** at the right of the search field (tinted when a
   franchise filter is active). **Expected:** reveals a filter row.
3. **Sort:** a segmented button row with the four options — **"Recently
   Added"**, **"Name A–Z"**, **"Series"**, **"Price Paid"**. Cycle through
   all; verify ordering (Recently Added = newest first; Price Paid =
   highest first; Series = franchise then series number).
4. **Franchise chips:** **"All"** plus one chip per franchise in your
   collection. Select one → only that franchise shows; **All** → everything.
5. Combine: search text + franchise chip together — both filters apply.
6. Header shows **"N items"** and **"Paid: $X"** (when > 0). "EXCL" badge on
   exclusive items' cards.

> **Category preferences do NOT filter My Dex** — verified in code: "Always
> show owned items — category filter applies to browsing only." The
> observable effect of disabling categories is in **Add → Search by name**
> results (A3a step 4) instead. Do not expect owned items to disappear from
> My Dex when toggling categories.

**Pass:** search/sort/filter behave exactly as above.

---

## A7. Price alerts (want-list items only)

The alert bell is shown **only for want-list items** (verified: rendered only
when `!item.isOwned`).

1. Open a want-list item's detail screen (e.g. via toggling an item with
   "Tap to move", or after A2b).
2. **Expected:** a **"Price alert"** row with a bell icon and **"Set alert"**.
3. Tap it. **Expected:** bottom sheet with **"Target price (USD)"** field
   ($ prefix), **"Save alert"**, and — when an alert exists — **"Remove
   alert"**.
4. Save a target. **Expected:** bell row reflects the enabled alert
   immediately and persists across navigation.
5. **Remove alert** → reverts to "Set alert".
6. **Worker (optional):** `PriceAlertWorker` is scheduled daily at app start.
   On trigger it posts **"Price drop: <item name>"** (requires
   POST_NOTIFICATIONS on API 33+) and updates the alert's `lastTriggeredAt`.
   No manual trigger exists in the UI; verify on schedule or skip with a note.

**Pass:** alert create/persist/remove on a want-list item; bell absent on
owned items.

---

## A8. Category preferences

1. **Settings → Database → "Collection categories"** ("Choose which Funko
   categories you collect").
2. **Expected screen:** title **"My collection categories"**, a **"Reset"**
   text button in the top bar, and categories grouped under expandable genre
   headers (Entertainment/Music/Sports/Icons/etc., each with an icon).
3. Expand a genre; toggle a single category off/on — persists across
   navigation.
4. Use the **genre-level toggle** on a header — all categories in the genre
   flip together.
5. Tap **Reset** — all categories return to enabled.
6. Force-close + relaunch — settings persist (no silent re-seed).
7. Verify the filter's actual effect: disable a category, then **Add →
   Search by name** for an item in it — **expected:** filtered out of
   results (see A3a/A6 notes).

**Pass:** toggles, genre toggle, Reset, restart persistence, and the
search-results effect.

---

## A9. Reports tab + export

> **Session 9 update:** `ReportsScreen.kt`/`ReportsViewModel.kt` were created
> and wired into `FunkoDexNavHost.kt`; `ExportButton` and `CatalogDataSection`
> are also wired (commits `74c5616`, `6f2c523`). The data-layer expectations
> below are verified against `CollectionStats` and the built screen.

1. **Reports** tab loads without crash.
2. Data available to it (`CollectionStats`): totalOwned, totalWanted,
   totalPaid, totalRetailValue, totalMarketValue, uniqueFranchises,
   mostExpensivePaid, highestMarketValue, recentlyAdded, byGenre, and
   per-series `SeriesSummary` (ownedCount/totalInCatalog, completionPct,
   missingItems, totalCostPaid, marketValue).
3. Add/remove an item → return → numbers update.

### A9b. Export

4. **"Export collection"** button → sheet with **"Excel workbook (.xlsx)"**
   and **"CSV spreadsheet (.csv)"**, states "Building spreadsheet…" /
   "Opening share sheet…", **Cancel**.
5. **.xlsx** → share sheet with `FunkoDex_<date>.xlsx` containing **4
   sheets**: Collection, Series Report, Want List, Summary.
6. **.csv** → `FunkoDex_<date>.csv`, one row per owned item, columns: Name,
   Series, #, Category, Retail Price, Exclusive, Retailer, Vaulted.

**Pass:** Reports loads with correct live stats; export produces both files
correctly.

---

## A10. Check tab — Pre-Purchase Check

A read-only, camera-only "do I already own this?" screen for use in stores.

1. Go to the **Check** tab (cart icon). First time: **"Camera permission
   needed"** + **"Grant permission"** button.
2. **Expected when scanning-ready:** live camera, white corner-bracket scan
   frame, top pill label **"Pre-Purchase Check"**, bottom hint **"Scan the
   barcode on a Funko box"**.
3. Scan an **owned** item. **Expected:** green overlay — big check, **"YOU
   HAVE THIS ONE"**, item image/name/franchise, **"You paid: $X"** if a price
   was recorded, and "auto-resetting…". Returns to scanning after **4
   seconds** automatically.
4. Scan a **want-list** item. **Expected:** blue overlay — cart icon, **"NOT
   IN YOUR COLLECTION"**, item details, **"Retail: $X"** if known, and the
   orange **"★ On your want list"** badge.
5. Scan a catalog item you neither own nor want. **Expected:** blue "NOT IN
   YOUR COLLECTION" without the want-list badge. (Lookup falls back through
   local catalog → Channel3 → UPCItemDB → BarcodeSpider, so this can hit the
   network; **"Checking your collection…"** shows while looking up.)
6. Scan a completely unknown barcode. **Expected:** grey **"Unknown Funko"**
   / "Not in local database".
7. Scan a second item while a result is showing. **Expected:** the previous
   result is replaced immediately (re-scan cancels the pending auto-reset).
8. Confirm this screen never adds anything — it is read-only.

**Pass:** all four result overlays, the 4-second auto-reset, and immediate
re-scan all work.

---

## A11. Appearance + Diagnostics (Settings, quick checks)

1. **Settings → Appearance → "App theme"** → dialog with six radio options:
   **Follow system / Light / Dark / Funko Orange / Cool Blue / Gold
   Edition**. Pick each of Light/Dark/one Funko theme — applies immediately
   and persists across restart.
2. **Settings → Diagnostics** row ("Log level · share logs") → dialog
   **"Diagnostics"** with **Log level** selection and **Log file** section
   with a **"Share log"** button (opens a share sheet with the log file).

**Pass:** theme switching persists; log share sheet opens.

---

# PART B — Integrations

> **Session 9 update:** the connection UI for B1–B3 and the "Refresh now"
> button in B6 live in `CatalogDataSection` (SettingsScreen.kt, "Catalog"
> section), which is now invoked from `SettingsScreen` (commit `6f2c523`) —
> reachable but untested. The descriptions below match the composable's
> verified content.

## B1. Channel3 API key

> **Rewritten Session 13.** The Channel3 tier now calls the real API
> (`POST /v1/search`, `x-api-key` header, JSON body — `9a315bf`) and **requires
> a key for every call**; there is no longer a keyless/free tier. Separately,
> the manual key-entry row + dialog are **hidden by default** in Settings
> (`SHOW_CHANNEL3_KEY_UI = false`, `d38fd18`). So the primary way to set a key
> is the file-import path (B1b). The steps below assume the key-entry UI is
> hidden; if a build flips `SHOW_CHANNEL3_KEY_UI` to true, the manual row
> reappears and the original tap-to-enter flow applies.

1. Locate the **"Lookup sources"** card (Settings → Catalog →
   CatalogDataSection). Rows: **"Kenny Chan dataset"** (locked on),
   **"HobbyDB / Pop Price Guide"**, **"eBay sold listings"**. **Expected
   (default build):** the **"Channel3 API"** row is **absent** (hidden by
   `SHOW_CHANNEL3_KEY_UI`). Confirm it does not appear.
2. Set a key via the import path (B1b). After import, Channel3 calls in the
   price waterfall send `x-api-key` with your key.
3. **Dormant-without-key check:** with no key set, trigger a price lookup that
   would reach the Channel3 tier. **Expected:** the tier returns null/no
   contribution (it requires a key) — no crash, the waterfall continues to the
   next tier. Channel3 contributes nothing until a key is imported.
4. Force-close + relaunch after a key is set → key persists (Session 8 AES/GCM
   round-trip through `SecureKeyStore.getChannel3Key()`).

**Pass:** Channel3 row hidden by default; no key ⇒ tier dormant (no crash);
imported key persists across process death; no `Cipher`/`KeyStore` exceptions.

## B1b. Channel3 key import from file (Session 13)

Verifies `9a315bf` `importKeysFromFile`. (If `SHOW_CHANNEL3_KEY_UI` is false,
this is the only in-app way to set a Channel3 key.)

1. Create `funkodex_keys.json` with at least:
   `{ "channel3_api_key": "<your key>", "ebay_client_id": "", "hobbyDB": "" }`
   and push to Downloads:
   `adb push funkodex_keys.json /sdcard/Download/funkodex_keys.json`.
2. Open the key-import entry point — the **"Import from file"** button in the
   Channel3 key dialog. (In a default build with the row hidden, reach it via
   whatever surface exposes the dialog; if none is exposed, note B1b as
   **BLOCKED — no UI entry point in this build** and flag it. This is worth
   confirming during the run — the import code exists but its only known caller
   is inside the dialog gated by `SHOW_CHANNEL3_KEY_UI`.)
3. Pick the file. **Expected:** a toast summarizing the result, e.g.
   **"Imported: Channel3 key"**. If `ebay_client_id`/`hobbyDB` were non-blank,
   the toast also notes them as **skipped (not yet wired)**.
4. **Negative cases:** a file with no recognized keys → **"No keys found in
   file"**; malformed JSON → **"File isn't valid JSON"**; unreadable file →
   **"Could not open file"**.
5. Force-close + relaunch → the imported Channel3 key persists.

**Pass:** a valid file sets the Channel3 key (toast confirms), unwired keys are
reported as skipped, bad files produce the correct error toasts, key persists.
**If no UI entry point exists in the default build, record B1b as BLOCKED and
flag the dead-feature risk.**

## B2. HobbyDB OAuth

1. Tap **"HobbyDB / Pop Price Guide"** ("Not connected · tap to sign in with
   your HobbyDB account") → browser OAuth flow → complete sign-in.
2. **Expected:** `OAuthCallbackActivity` handles the redirect; row becomes
   "Connected · market pricing · vaulted status enabled".
3. Force-close + relaunch → still connected (`isHobbyDbTokenValid()`
   decrypts `"accessToken|expireAtMs|refreshToken"` via the new Keystore
   wrapper).
4. Tap again to disconnect → "Not connected".
5. Token refresh (`TokenRefreshManager`/`TokenKeeperWorker`, scheduled at app
   start) should be transparent — watch logcat for crypto exceptions during
   any refresh; there should be none.

**Pass:** connect / persist / disconnect; no crypto exceptions.

## B3. eBay OAuth

Same as B2 for **"eBay sold listings"** ("Not connected · optional · tap to
sign in with eBay" → "Connected · real sold prices"). Note: eBay tokens are
~2 h with a 5-minute refresh buffer (per `SecureKeyStoreTokenTest`).

> The eBay price tier no longer uses the retired RSS feed — it scrapes the
> sold-listings HTML (corrected S11/12). If the on-screen subtitle still reads
> "…than RSS feed," that string is stale copy, not a functional issue; note it
> for cleanup. The HTML parser is verified working; 403s during testing are a
> fetch-time bot block on datacenter IPs, not a parse failure (see plan header
> and A-series price notes).

## B4. Google Drive backup (Session 5)

All rows verified in **Settings → Database**:

1. **"Connect Google Drive"** ("Automatic daily backups of your collection")
   → AuthorizationClient flow (authorization-only — no account-picker
   sign-in screen; a consent sheet may appear). Grant.
2. **Expected:** row becomes **"Backup to Google Drive"** / subtitle
   **"Connected · Tap to back up now"**, and the periodic
   `DriveBackupWorker` is (re)armed (`ExistingPeriodicWorkPolicy.UPDATE`).
3. Tap the row → immediate backup. Verify a FunkoDex backup file appears in
   your Drive (drive.google.com).
4. Force-close + relaunch → still "Connected" (plain boolean in
   `funkodex_secure_prefs_v2`).
5. **Lapsed grant (T-D3, critical):** revoke FunkoDex's access in Google
   Account → Security → Third-party access, then trigger a backup.
   **Expected:** worker catches the auth failure, does NOT retry-spin, and
   posts notification id 3002 — text "Reconnect in Settings to resume
   automatic backups." Reconnect and confirm backups resume.
6. **"Disconnect Google Drive"** ("Stop automatic backups · To fully revoke
   access, visit Google Account → Connections") → worker cancelled, row
   reverts to "Connect Google Drive".

**Pass:** connect / back-up-now / restart persistence / lapsed-grant
notification / disconnect.

## B5. Community contributions

Verified mechanics — read before testing:
- **Two creation paths:** (a) **Scanner**: unknown UPC → "Barcode not in
  catalog" sheet → pick a match → contribution saved **silently**, source
  USER_SCAN (no prompt); (b) **Detail edit**: UPC added or changed → **"Share
  UPC with community?"** prompt, source USER_EDIT; clearing a UPC deletes the
  pending contribution.
- The **"Contribute to community database"** switch (Settings → Database)
  gates the **daily `GitHubUploadWorker` schedule** (on = schedule, off =
  cancel). It does **not** prevent local contribution saves.
- If `WORKER_URL` is blank in the build config, the worker logs "WORKER_URL
  not configured — skipping upload" and succeeds without uploading (the
  Cloudflare Worker is not yet deployed, so this is the expected state).

1. Ensure the toggle is **on** ("Anonymously share UPC data you scan. No
   personal data is ever uploaded.").
2. Run path (a) via A2d, and path (b) via A4c. **Expected:** both create
   `contrib::<upc>` docs without error; (b) shows the prompt, (a) doesn't.
3. Logcat: `HmacKeyStore`/`SecureKeyStore` show no crypto exceptions when the
   upload worker runs (HMAC signing uses `getInstallId()` — Session 8 path).
4. Worker behavior: with WORKER_URL unset, expect the "skipping upload" log.
   On HTTP 400 it marks contributions uploaded to avoid loops; on 429 it
   retries with backoff.

**Pass:** both creation paths behave as specified; toggle arms/cancels the
worker; no crypto errors.

## B6. Catalog refresh worker

`CatalogRefreshWorker` is scheduled at app start (KEEP policy) and runs
periodically. The manual **"Refresh now"** button is in `CatalogDataSection`
(Settings → Catalog).

1. Trigger via "Refresh now"; alternatively rely on the scheduled run (note
   it in results rather than skipping silently).
2. Logcat (filter `CatalogRefreshWorker`) expected sequence:
   - "Starting catalog refresh…"
   - "Refresh complete: N new catalog records added" (N may be 0)
   - "Community UPC file: N UPCs merged into catalog"
   - With HobbyDB connected: "Vaulted status updated: N items"
3. **Expected:** no `CouchbaseLiteException`/type errors (worker builds its
   own `FunkoDexDatabase` and calls `db.getCollection()` in each of its three
   functions — Session 7 conversion).

**Pass:** clean run (or graceful early-return offline) with the log sequence.

---

# PART C — Backup & Restore (run LAST)

## C1. Backup (export)

Two entry points, both verified to call the same `exportDatabase()`:
**Settings → Database → "Send to another phone"** and **Settings → Backup →
"Backup database"** ("Saves a .zip to your phone's Downloads folder and lets
you share to another device").

1. With a populated collection (photos, variants, an alert):
   tap **Backup database**.
2. **Expected:** the zip is written to **Downloads** via MediaStore AND a
   share chooser opens titled **"Share backup via…"**. Filename:
   `FunkoDex_backup_YYYYMMDD_HHmmss.zip`.
3. Verify on-device: `adb shell ls /sdcard/Download/ | findstr FunkoDex_backup`
4. Pull and inspect: `adb pull /sdcard/Download/FunkoDex_backup_<ts>.zip .`
   **Expected zip contents:** single `funkodex_backup.json`, a JSON array
   where every entry has `"_id"` (`funko::<uuid>` etc.); **no** entries of
   type `catalog` or `system`; blobs encoded as
   `{"_type":"blob","contentType":…,"data":"<base64>"}`.

**Pass:** Downloads file + share sheet + correct JSON structure.

## C2. Restore (normal)

1. Add one throwaway item (so current state differs from the C1 backup).
2. **Settings → Backup → "Restore backup"** ("Restore from a FunkoDex .zip
   backup file"). **Expected dialog:** **"Replace your collection?"** —
   "This will permanently replace everything… cannot be undone." + note that
   backups are in Downloads named `FunkoDex_backup_YYYYMMDD_HHmmss.zip`.
   Confirm with **"Replace collection"**.
3. Pick the C1 zip. **Expected:** "Importing…" subtitle → "Import
   successful!".
4. **Verify:** throwaway item gone; all C1 items back **including photos,
   variants, and the price alert**; catalog intact (Add → Search by name
   still returns results); category preferences unchanged.
5. Error path (optional): pick a non-backup zip → **"Restore failed"** dialog
   with "Your existing collection data has not been changed."

**Pass:** exact restore to backup state; catalog/cat-prefs untouched.

## C3. Force restore — HIGHEST PRIORITY (Session 7 risk)

Wipes the entire database **including catalog**, then rebuilds. The core
Session 7 risk: `forceRestoreDatabase` must obtain its `Collection` AFTER
`db.reopen()` — stale-reference bugs appear here first.

1. **Settings → Backup → "Force restore (corrupt database)"** ("Wipes
   everything and rebuilds from backup — use if the app is behaving
   incorrectly"). **Expected dialog:** **"Wipe and rebuild from backup?"** —
   notes the catalog will re-download on next start. Confirm with **"Wipe
   and restore"**.
2. Pick the C1 zip. **Expected:** success dialog **"Database rebuilt"** —
   "Your collection has been restored. The catalog (23,000+ items) will
   reload in the background on next start… Restart the app now for best
   results." Logcat: `Force restore: inserted N user documents. Catalog will
   reload on next start.` (N = C1 item count).
3. **Force-close and relaunch.**
4. **Expected on restart:**
   - Splash stays up longer (full catalog re-preload — the
     `system::catalog_loaded` marker was wiped).
   - **My Dex** shows exactly the C1 items with photos/variants intact.
   - **Add → Search by name** works (post-reopen Collection queryable).
   - **Settings → Collection categories**: all categories enabled
     (cat-prefs were wiped and re-seeded to defaults).
   - No empty, duplicated, or stale items.

**Pass:** the single most important test in this plan — full wipe, rebuild,
re-preload, and a correct collection afterward.

---

# PART D — Automated / Code-Level

## D1. Enriched catalog import

### D1a. Small test file (exercises five code paths)

Create `test_enriched.json`:

```json
[
  {
    "handle": "spider-man-no-way-home-pop",
    "title": "Spider-Man (No Way Home)",
    "series": ["Marvel"],
    "upc": "889698123456",
    "pid": "12345",
    "price": "$12.99",
    "available": true,
    "productUrl": "https://funko.com/products/spider-man-nwh",
    "funkoPrimaryImage": "https://example.com/spiderman.jpg",
    "funkoSource": "FUNKO_COM",
    "funkoNumber": "1118",
    "popType": "Pop!",
    "marketValueLoose": "$15.00",
    "marketValueNew": "$20.00",
    "pricechartingId": "98765",
    "pricechartingUrl": "https://pricecharting.com/game/spiderman"
  },
  {
    "handle": "totally-new-test-item-2026",
    "title": "Totally New Test Item",
    "series": ["Test Series"],
    "upc": "111222333444",
    "price": "$10.00",
    "available": true,
    "funkoSource": "FUNKO_COM",
    "popType": "Pop!"
  },
  {
    "handle": "92345.html",
    "title": "Page-Handle Repair Test Item",
    "series": ["Test Series"],
    "price": "$9.99",
    "funkoSource": "FUNKO_COM",
    "popType": "Pop!"
  },
  {
    "handle": "skip-me-merch",
    "title": "Test Branded T-Shirt",
    "series": ["Apparel"],
    "price": "$25.00",
    "funkoSource": "FUNKO_COM"
  },
  {
    "handle": "no-title-record",
    "title": "",
    "series": ["Test Series"]
  }
]
```

Path coverage (all verified against `CatalogImporter`):
- **1** — merge if `spider-man-no-way-home-pop` exists in the catalog
  (enrich-only: pricing/image/popType/funkoNumber; identity fields
  title/handle/imageUrl/seriesList never overwritten; UPC only set when
  previously blank); otherwise inserted as new.
- **2** — guaranteed insert → `catalog::totally-new-test-item-2026`.
- **3** — `92345.html` matches `FUNKO_PAGE_HANDLE` (`^\d+\.html$`) → slug
  repair → `catalog::page-handle-repair-test-item`.
- **4** — "T-Shirt" matches `NON_POP_TITLE` (`\bshirt\b`, case-insensitive)
  → **skipped**.
- **5** — handle present but blank title → **skipped**.

> **Session 13 note (`294dc84`):** the "2 records skipped" expectation below is
> **unchanged for this file** — records 4 and 5 are skipped for non-Pop title
> and blank title, neither of which the merge-on-collision change touches. What
> *did* change: an insert whose target docId **collides** with an existing doc
> now **merges (fill-only) instead of skipping**, and imports now match on
> handle → **UPC** → title. This 5-record file has all-distinct handles/UPCs
> and so does **not** exercise the new UPC-merge or insert-collision-merge path.
> **D1c** below adds that coverage. Do not infer the merge path is tested from a
> green D1a.

Push: `adb push test_enriched.json /sdcard/Download/test_enriched.json`

1. **Settings → Catalog → "Import Enriched Catalog"** ("Load enriched
   funko.com and pricing data from a JSON file") → pick the file (JSON file
   picker).
2. **Expected:** non-dismissable **"Importing catalog…"** dialog ("Reading
   file…" then a progress bar "N / M records"); near-instant for 5 records.
3. **Expected result dialog "Import complete":**
   - "X existing records updated" — 1 if record 1 matched, else 0
   - "X new records added" — 2 if record 1 matched, else 3
   - "2 records skipped (non-Pop or missing handle/title)"
   - errors line only if errors > 0 (expect absent)
   - "Completed in Xs" → **Done**
4. **Verify via Add → Search by name:** "Totally New Test Item" found;
   "Page-Handle Repair Test Item" found; "Test Branded T-Shirt" **not**
   found. If record 1 merged: its detail shows enriched pricing fields and
   the original title/image are unchanged.

### D1b. Full enriched file (optional)

Push the real `funko_data_enriched.json` (14,314 records) the same way.
**Expected:** progress advances in batches of 500 (verified chunk size).

**Actual (2026-06-13, on-device, PASS):** first run 13,585 enriched / 725
added / 4 skipped / 0 errors, 51s — matches the ~13,583/~725/~4 estimate from
HANDOFF.md's 2026-06-12 dry-run. Re-import (idempotency check, after the
Session 9 category fix) gave 14,310 updated / 0 added / 4 skipped / 0 errors,
47s.

**Pass:** exact counts on the 5-record file (D1a — not yet run); full-file
run PASS (above).

### D1c. UPC-collision merge (Session 13, `294dc84`)

Exercises `buildUpcIndex` + `mergeRecordInto` + the insert-collision-merge path
that D1a does not cover. Goal: prove a record that collides by **UPC** (not
handle) merges into the existing doc instead of inserting a duplicate or being
skipped, and that the colliding record's price/metadata is contributed.

Create `test_upc_merge.json` — two records, **different handles, same UPC**, the
second carrying pricing the first lacks:

```json
[
  {
    "handle": "upc-merge-base-2026",
    "title": "UPC Merge Base Item",
    "series": ["Marvel"],
    "upc": "889698555001",
    "popType": "Pop!"
  },
  {
    "handle": "upc-merge-pricecharting-2026",
    "title": "UPC Merge Base Item",
    "series": ["Marvel"],
    "upc": "889698555001",
    "marketValueComplete": "$42.00",
    "pricechartingUrl": "https://pricecharting.com/game/funko-pop/upc-merge",
    "pricechartingId": "555001",
    "releaseDate": "2024-01-15",
    "popType": "Pop!"
  }
]
```

1. Import on a catalog where this UPC isn't already present.
2. **Expected counts:** **1 new added** (the base) and **1 existing updated**
   (the second record merges into the first by UPC) — **0 skipped**. The second
   record must **not** appear as a separate added record, and must **not** be
   skipped.
3. **Verify the merge filled, didn't clobber:** search the catalog for "UPC
   Merge Base Item" → exactly **one** result. Its detail shows the
   `marketValueComplete` ($42.00 → seeds market value), `pricechartingUrl`,
   `pricechartingId`, and `releaseDate` from the second record, while
   title/handle/series from the first are unchanged.
4. **Re-import idempotency:** import the same file again → **0 added / 2 (or the
   merged 1) updated / 0 skipped**, no duplicate doc created.
5. **Ambiguity guard (optional):** add a third record with the **same UPC** but
   a genuinely different figure; confirm a UPC mapping to >1 existing doc is
   **dropped from the index** (ambiguous UPCs must not drive a merge) — that
   record should fall back to handle/title matching or insert, not merge into an
   arbitrary twin.

**Pass:** same-UPC/different-handle records merge into one doc (1 added / 1
updated / 0 skipped); the colliding record's price + PC metadata is present on
the surviving doc; identity fields are unchanged; re-import is idempotent.

> Pair this with **A4l**: after D1c, the surviving doc carries a
> `pricechartingUrl`, so adding it to your collection and refreshing prices
> exercises the live PriceCharting tier end-to-end (import → catalog → scan/add
> → refresh).

## D2. Unit tests

```
gradlew test
```

All under `app/src/test` (JVM — no device needed). Verified counts:
- `FunkoMapperTest` — **9** tests (document round-trip; would catch
  Session 7 type regressions)
- `CollectionStatsTest` — **11** tests
- `FunkoLookupServiceTest` — **8** tests
- `SecureKeyStoreTokenTest` — **15** tests (token string parsing — storage
  change in Session 8 does not affect these)
- `ScannerViewModelStateTest` — **20** tests (scan state machine)
- `PkceHelperTest` — **9** tests (OAuth PKCE)

**Pass:** `BUILD SUCCESSFUL`, 72 tests green.

> **Session 13 coverage gap (no new tests were added this session).** The 72
> count is unchanged, but production code under two existing suites changed and
> is **not** covered by new assertions:
> - `FunkoMapperTest` round-trip does not assert the new `FunkoItem.pricechartingUrl`
>   field (`d38fd18`). A green run does not prove the field survives save/load.
> - `FunkoLookupServiceTest` (8 tests) predates the catalog-first UPC path
>   (`c9fa9c3`) and the `marketValueComplete` seeding — the new `lookupCatalogByUpc`
>   branch and leading-zero normalization are untested at the JVM level (covered
>   only by device test A2g).
> Optional follow-up (not required to pass D2): extend `FunkoMapperTest` for
> `pricechartingUrl` and add a `FunkoLookupServiceTest` case for catalog-first /
> leading-zero matching. Until then, lean on A2g and A4l for that coverage.

## D3. SecureKeyStore file-level check (Session 8)

1. After B1 (or any secret saved): force-close + relaunch → secret still
   readable; logcat free of `AEADBadTagException` /
   `KeyPermanentlyInvalidatedException` / `KeyStoreException`.
2. Optional (debug build / root): `/data/data/com.funkodex/shared_prefs/`
   contains `funkodex_secure_prefs_v2.xml` with values in
   `base64(iv):base64(ciphertext)` form, and the old
   `funkodex_secure_prefs.xml` still present but untouched (abandoned by
   design — no migration).

**Pass:** new-format file in use; old file inert; no crypto exceptions.

---

# PART E — 16 KB Emulator Regression

On the **16 KB Page Size** emulator (Pixel 10, API 37.0) from Session A:

1. Install the current build.
2. Run: A1 (preload) → A3a (search + add) → A4d step 12–13 + A4f (photo +
   blob preservation) → C1 (one backup export only).
3. **Expected:** no crashes, no `SIGSEGV`/`SIGBUS` in logcat.

**Pass:** condensed smoke test clean vs. Session A baseline.

---

# Summary Checklist

> **Session 13 gap scan (v2.0).** New items added: **A2g** (catalog-first scan),
> **A4l** (live PriceCharting refresh), **B1b** (Channel3 key import), **D1c**
> (UPC-collision merge). Items whose prior pass is invalidated by Session 13 and
> reset to unchecked: **B1** (Channel3 rewritten + UI hidden), **B3** (stale
> subtitle), **D1a** (path coverage changed), **D2** (code under test changed,
> no new tests). Every Session 13 source change (commits `9a315bf`, `294dc84`,
> `c9fa9c3`, `d38fd18`) maps to at least one item above. Pre-existing `[x]/[ ]`
> marks on untouched items are left as-found and reflect the tracker's run log,
> not a fresh run.

## Part A — Core
- [x] A1. First launch, splash-gated preload, search proves catalog
- [ ] A2a. Scan → found → "Added!" flow
- [ ] A2b. Scan → want list (verify via Check badge / re-scan)
- [ ] A2c. Scan → "Already in your collection" (all 3 options)
- [ ] A2d. Scan → "Barcode not in catalog" → match (silent contribution)
- [ ] A2d-2. Scan → "Barcode not in catalog" → **Add manually** (UPC locked) — Session 11
- [ ] A2e. Offline scan → "Scan queued — no network" → auto-resolve + notification
- [ ] A2g. **Catalog-first UPC resolution + leading-zero normalization** — Session 13
- [x] A3a. "Search Catalog" bulk add (incl. category-filtered + token search) — re-test Session 11
- [ ] A3b. Batch scan FAB → "Save all (N)"
- [x] A4a. View mode (status card "Tap to move", chips, Market Price)
- [x] A4b. Edit fields ("Edit Funko" / "Save") — re-test: Market value + Image URL added (S11)
- [ ] A4c. UPC scan dialog + "Share UPC with community?" prompt
- [ ] A4d. Photos: camera / gallery (Photo Picker, no permission) / "Fetch
      from catalog" (http→https — S11); "Save photo as" Main/Variation/Both
- [x] A4e. Variant edit: description, price, remove
- [x] A4f. **Blob-preservation regression (critical)**
- [ ] A4g. **Manual market value** — display, no-data-refresh keeps it, feed overwrites — Session 11
- [ ] A4h. **Image URL entry** — load, re-download on change, http→https — Session 11
- [ ] A4i. **Manual search → Add manually** (UPC editable) — Session 11 (see A3a step 7)
- [ ] A4j. **Camera survives screen-saver** (rebind on resume) — Session 11; device only, see DEVICE_TEST_PLAN §11
- [ ] A4l. **Live PriceCharting refresh tier** (PC-URL items refresh from PriceCharting, runs before retail) — Session 13
- [x] A5. Delete via card kebab menu AND detail trash → "Remove from collection?"
- [x] A6. Search / segmented sort (4 options) / "All"+franchise chips
- [x] A7. Price alerts (want-list only; "Target price (USD)")
- [x] A8. "My collection categories": toggles, genre toggle, Reset, restart
- [x] A9. Reports + export .xlsx (4 sheets) / .csv
- [ ] A10. **Check tab — Pre-Purchase Check (all 4 overlays, 4 s auto-reset)**
- [x] A11. App theme (6 options) + Diagnostics log share

## Part B — Integrations
- [ ] B1. Channel3 — row hidden by default; no-key ⇒ tier dormant; imported key persists — **rewritten Session 13**
- [ ] B1b. Channel3 key import from `funkodex_keys.json` (or BLOCKED if no UI entry point) — Session 13
- [x] B2. HobbyDB OAuth connect/persist/disconnect
- [ ] B3. eBay OAuth connect/persist/disconnect — re-confirm subtitle copy (RSS string stale)
- [x] B4. Drive connect / back up now / **lapsed grant (notif 3002)** / disconnect
- [ ] B5. Contributions: silent USER_SCAN path + USER_EDIT prompt path;
      toggle arms/cancels upload worker; WORKER_URL-unset skip
- [x] B6. Catalog refresh worker log sequence

## Part C — Backup/Restore (LAST)
- [x] C1. Backup: Downloads file + "Share backup via…" + JSON structure
- [x] C2. Restore: "Replace your collection?" → exact state, catalog intact
- [x] C3. **Force restore: "Database rebuilt" → restart → re-preload →
      correct collection (HIGHEST PRIORITY)**

## Part D — Automated
- [ ] D1a. Enriched import 5-record file (exact counts; path-coverage note updated — Session 13)
- [x] D1b. Full enriched file — PASS (2026-06-13, see above)
- [ ] D1c. **UPC-collision merge** (same-UPC/diff-handle merges to one doc; price/metadata contributed) — Session 13
- [ ] D2. `gradlew test` — 72 tests green (coverage gap noted: pricechartingUrl, catalog-first lookup untested) — Session 13
- [x] D3. SecureKeyStore v2 prefs format / no crypto exceptions

## Part E
- [x] E1. 16 KB emulator condensed smoke test

---

# PART 2 — Execution Tracker


<!-- Version: v2.0 (2026-06-20). Tracks COMPLETE_TEST_PLAN.md v2.0. Reconciled
     to Session 14 (catalog last-enricher-wins, re-link, field protection);
     prior reconcile Session 13 (commits 9a315bf, 294dc84, c9fa9c3, d38fd18). -->

Tracks execution of `COMPLETE_TEST_PLAN.md` (code-verified against the repo).
Update this file as each item is run — check the box and add a one-line
result/note in the log at the bottom.

## Known wiring gaps — RESOLVED 2026-06-12

Both gaps below were fixed and merged (commits `74c5616`, `6f2c523`). Build
compiles and runs clean on device. A9 and B1/B2/B3/B6 are no longer blocked —
they're untested like everything else in this tracker.

- **`ReportsScreen.kt`** — created at
  `ui/screens/reports/ReportsScreen.kt` + `ReportsViewModel.kt`. Wired into
  `FunkoDexNavHost.kt` (import/call site were already present). Was previously
  affecting **A9**.
- **`CatalogDataSection`** — now invoked from the "Catalog" section of
  `SettingsScreen.kt`, reusing the existing `catalogSettingsViewModel`
  instance. Was previously affecting **B1, B2, B3, B6**.

## Session 14 changes (2026-06-20)

Code-only; nothing tested on device this session. All five files verified
IDENTICAL against `origin/master` after push. New/changed test surface:

- **Enriched-import parser fix** (`CatalogImporter.toEnrichedRecord`) — 9 keys
  that were silently dropped are now read: `marketValueComplete` (PRIMARY in-box
  price), `releaseDate`, `ebayEpid`, `amazonAsin`, `printRun`, `publisher`,
  `pcSeries`, `pcDescription`. **After import, a catalog record sourced from a
  JSON entry that has these keys must now carry them.** Affects D1a/D1c and any
  market-value display test — `marketValueComplete` previously never landed.
- **Catalog merge → last-enricher-wins** (`CatalogImporter.mergeRecordInto`) —
  re-importing an enriched JSON now OVERWRITES enrichment fields on existing
  catalog docs and RECOMPUTES seriesList + category (+ primarySeries, exclusive,
  chase, seriesNumber) from the incoming tags. **New test: import file v1, then
  import file v2 whose record has a longer/corrected series list + different
  category for the same handle; confirm the existing catalog doc's category and
  seriesList UPDATE (not just new records). Adds D1d.** Preserved fields:
  handle/title/imageUrl — confirm those do NOT change on re-import.
- **Collection re-link** (`CollectionRelinkService`, Settings → Catalog →
  "Re-link collection to catalog") — refreshes owned funko:: items from the
  enriched catalog. **New tests (add as Part B/F):**
  - **R1 fill:** owned item missing UPC/price/image/franchise/category, with a
    valid catalogRef → after re-link, those fields are filled from the catalog.
  - **R2 refresh (marker present, not edited):** owned item added/edited after
    the S14 build (has the marker), enriched catalog has a corrected category →
    re-link overwrites the item's category + genre.
  - **R3 protect (marker present, edited):** set a custom category on an item via
    the edit screen + save, then re-link → the custom category is PRESERVED
    (userEditedFields contains "category").
  - **R4 migration (marker absent):** item owned before the S14 build (no marker)
    with a non-blank category → re-link does NOT overwrite it (fill-only fallback);
    a blank field IS filled.
  - **R5 unmatched:** owned item with no catalogRef and no UPC match → untouched,
    counted "unmatched".
  - **R6 idempotent:** run re-link twice with no catalog change between → second
    run reports 0 enriched.
  - **R7 manual market value:** item with `marketValueIsManual = true` → re-link
    never touches marketAvg.
  - **R8 sequencing:** re-link BEFORE importing the enriched JSON links against the
    asset/seed catalog only (documented constraint — verify the UI/flow guides
    import-first).
- **Field-protection marker roundtrip** (`FunkoMapper` ↔ `userEditedFields`) —
  **D2 unit-test additions (see `RELINK_FIELD_PROTECTION_SPEC.md`):** mapper
  roundtrip with marker present / present-empty / absent (null); edit-screen
  `markEdited` stamps the right FIELD_ key with no duplicates.
- **Backup/restore unchanged but newly relevant** — the new enriched fields and
  the `userEditedFields` marker must survive Part C (backup → restore →
  force-restore). Serializer is field-agnostic; **add a C-part assertion that a
  re-linked item's refreshed fields + marker round-trip through a backup.**
- **No production test files changed** — `gradlew test` count unchanged; the new
  R-series and marker tests above are NOT yet written. **D2 carries an expanded
  coverage-gap note for S14.**

## Session 13 changes (2026-06-20)

Code-only; nothing tested on device this session. Reconciled into the plan as
**v2.0** from source commits `9a315bf`, `294dc84`, `c9fa9c3`, `d38fd18` (all on
`origin/master` @ `938a5f0`). New/changed test surface:

- **Scan reads the Couchbase catalog first** (`c9fa9c3`,
  `FunkoLookupService.lookupCatalogByUpc`) — bundled `funko_data.json` is only a
  seed; the live catalog is the source of truth, queried first, then bundled
  JSON, then network. UPC matching normalizes leading zeros both ways, and seeds
  `marketAvg` from the catalog's PriceCharting Complete price. **Changes
  preconditions for A2a/A2c/A2d; adds A2g.** Run A1 (preload) before A2.
- **Live PriceCharting refresh tier** (`d38fd18`, `PriceService.fetchPriceCharting`
  + `PriceSource.PRICECHARTING`) — a refresh on an item with a catalog-stored
  `pricechartingUrl` re-scrapes that page via plain OkHttp GET (Android UA, no
  headless browser) and reads `#used_price`/`#complete_price`/`#new_price`. Runs
  **before** the retail short-circuit. **Adds A4l; affects A4g/A9/B3.**
- **Channel3 rewritten to the real API** (`9a315bf`) — `POST /v1/search`,
  `x-api-key` header, JSON body; **a key is now required for every call (no free
  tier).** Plus a **key-import-from-file** path (`importKeysFromFile`,
  `funkodex_keys.json` in Downloads). **B1 rewritten; adds B1b.**
- **Channel3 key-entry UI hidden by default** (`d38fd18`,
  `SHOW_CHANNEL3_KEY_UI = false`) — manual row + dialog suppressed; import path
  still functions. **B1 reachability changed; B1b may be BLOCKED if no UI entry
  point exists in the default build — verify and flag.**
- **UPC-based import de-dup + merge-on-collision** (`294dc84`,
  `buildUpcIndex`/`mergeRecordInto`) — imports match handle → UPC → title; an
  insert that collides now **merges (fill-only) instead of skipping**.
  `marketValueComplete` + full PriceCharting metadata now flow through import.
  **D1a path coverage updated (its 2-skipped count is unchanged — no collisions
  in that file); adds D1c.**
- **No test files changed** — `gradlew test` is still 72, but `FunkoMapperTest`
  doesn't assert the new `pricechartingUrl` field and `FunkoLookupServiceTest`
  predates the catalog-first path. **D2 carries a coverage-gap note.**
- **Known stale code comment (not a test target):** `SettingsScreen.kt` says
  "the free Channel3 tier still works" — it doesn't (`9a315bf` removed it).
  Flagged for code cleanup.

## Session 12 changes (2026-06-19)

Code-only; nothing tested on device this session. New/changed test surface:

- **Manual-UPC validation** — the editable UPC field on manual-add now rejects
  malformed entries (bad check digit / wrong length) and shows "Valid UPC" when
  good; Add is blocked on a non-blank invalid UPC. New `util/UpcValidation.kt`.
- **"Enter details manually" button** on the Add start screen → blank manual-add.
- **"Add another"** after a save now goes straight to the live camera, not the
  start chooser.
- **Variant-aware pricing** (eBay/HobbyDB/Channel3 name queries) — a chase or
  exclusive should price against its own listings where data exists.
- **eBay parser confirmed working** against a live page; earlier 403s are a
  fetch-time bot block, not a parse bug. eBay may contribute prices on a real
  device.
- **Channel3 is dormant** (no key) — that tier won't fire in testing.
- **Leak fixes** (HTTP responses, camera executor) — no user-visible behavior
  change expected; the camera-rebind-on-resume path (A4j) is the one to spot-check.

## Session 9 fixes (2026-06-13, commit `d69a4ec`)

- Enriched catalog import (D1b) was broken (`ArrayList cannot be cast to
  java.lang.Void`) — fixed via tree-based JSON parsing. Now PASS, see Result log.
- Catalog `category` field could be stored as `"Pop! Vinyl"` (a format
  descriptor, not a real category), making 714 records unsearchable via
  `FunkoLookupService.searchByName`'s category filter (separate bug: broken
  slug-vs-name comparison, also fixed). Both fixed; merge-path repair applied
  on re-import. **A3a should be re-tested** — the search-filter fix affects
  the whole catalog, not just the 714 affected records, so it may change
  results for other categories too.
- `db.getDatabase().getDocument()` → `db.getCollection().getDocument()` in
  `FunkoLookupService` (deprecation cleanup, Session 7 Collection API pattern).
- `Icons.Default.Logout` → `Icons.AutoMirrored.Filled.Logout`,
  `Icons.Default.TrendingUp` → `Icons.Default.AttachMoney` (deprecation
  cleanup in new `ReportsScreen`/`SettingsScreen` code).
- "Import Enriched Catalog" picker now defaults to Downloads
  (`OpenDocumentInDownloads`, API 26+).

See `CHANGELOG.md` Session 9 entry and `LESSONS_LEARNED.md` #26–27 for full detail.

## Session 10 fixes (2026-06-13)

- Reports "Est. Market Value" and "Total Retail Value" always showed $0.00 —
  `DetailViewModel.refreshPrices` now persists `marketAvg`/`resolvedRetail`
  onto the item; `ReportsScreen` now refreshes `CollectionStats` on
  `ON_RESUME`. **A9 and B3 should be re-tested** with a price refresh +
  navigation round trip to confirm Reports reflects Detail-screen values.
- New `FunkoItem.resolvedRetail` field + `effectiveRetail` computed property
  — "Total Retail Value" and all per-item "Retail" displays/exports now use
  `effectiveRetail` (catalog `retailPrice` if set, else `resolvedRetail`).
  Catalog `retailPrice` / Tier 1 price-waterfall behavior is unchanged.
- DetailScreen's "I only have the variant — want the original" control was a
  `TextButton` with no visible chrome (looked like static text) — now an
  `OutlinedButton`.
- Deprecation cleanup: `Icons.Default.ArrowBack` (DetailScreen.kt,
  CategoryFilterScreen.kt) and `Icons.Default.HelpOutline` (PreScanScreen.kt)
  → `Icons.AutoMirrored.Filled.*` (+ required imports);
  `db.getDatabase().getDocument/save` → `db.getCollection().getDocument/save`
  in `DetailViewModel.kt`; `query.removeChangeListener(token)` →
  `token.remove()` in `FunkoRepository.kt` (both live-query flows); removed
  no-op `@OptIn(ExperimentalGetImage::class)` in `ScannerScreen.kt`; fixed
  `@Suppress("DEPRECATION")` placement for the legacy `vibrate(50)` fallback
  (was only suppressing the declaration, not the call).

See `CHANGELOG.md` Session 10 entry and `LESSONS_LEARNED.md` #28–30 for full
detail.

## Session 11 fixes (2026-06-14)

New/changed surface that affects existing items and adds new ones. See
`CHANGELOG.md` Session 11 and `LESSONS_LEARNED.md` #31–33 for full detail.

- **Manual add of catalog-missing items** — new `ManualAddSheet` reachable from
  the "Barcode not in catalog" sheet AND the toolbar manual-search sheet. Creates
  a `FunkoItem` (name required; UPC carried/locked from scan or editable) and
  optionally queues a `USER_MANUAL` `CatalogContribution`. **Affects A2d** (the
  not-found path now offers manual add, not only catalog match) and adds new
  coverage — see DEVICE_TEST_PLAN §10.
- **Punctuation-tolerant name search** — `FunkoLookupService` now token-matches
  (normalize + all-tokens). "mr toad" matches "Mr. Toad". **Re-test A3a and A6** —
  search behavior changed catalog-wide, not just for punctuated names.
- **Manual market value** — editable `marketAvg` in detail edit; a manual value is
  a fallback that a real feed (`snapshot.avg>0`) overwrites; retail-only hits do
  not. **Affects A4b and A9/B3** (Reports market totals now include manual values;
  a failed refresh must NOT blank a manual value — regression to verify).
- **Image URL entry + http→https** — editable Image URL on manual add and detail
  edit; detail edit auto-re-downloads on URL change. All image loads upgrade
  `http://`→`https://`. **Affects A4d and DEVICE_TEST_PLAN §6** (the
  "Image not available / CLEARTEXT" case should now load over https).
- **Scanner frame-confirmation + retry** — `BarcodeAnalyzer` needs 3 consecutive
  identical reads; NotFound sheet gained "Scan again" + empty-state. **Affects
  A2a/A2d.**
- **Camera black after screen-saver — FIXED** — scanner now rebinds the camera on
  `ON_RESUME`. New device test, DEVICE_TEST_PLAN §3 addendum / §11.
- **eBay pricing** — RSS→HTML scrape; parser **verified working** Session 12
  against a live page. Earlier 403s are a fetch-time bot block (datacenter IP),
  not a parse failure. **B3 / price tests:** eBay *may* contribute prices on a
  real device — check the logs rather than assuming it returns nothing. Variant-
  aware for chase/exclusive items.
- **Manual market value wipe-on-refresh — FIXED** (staleDays `Int.MAX_VALUE`
  overflow). Regression: enter a manual market value, hit refresh on an item with
  no feed data, confirm the value survives.

---

## Part A — Core Collection Features

- [ ] A1. First launch, splash-gated preload, search proves catalog
- [ ] A2a. Scan → found → "Added!" flow
- [ ] A2b. Scan → want list (verify via Check badge / re-scan)
- [ ] A2c. Scan → "Already in your collection" (variant / variant-missing-original / update)
- [ ] A2d. Scan → "Barcode not in catalog" → match (silent USER_SCAN contribution)
- [ ] A2d-2. Scan → "Barcode not in catalog" → **Add manually** (UPC locked; name required; saves; USER_MANUAL contribution queued if shared) — Session 11
- [ ] A2e. Offline scan → "Scan queued — no network" → auto-resolve + notification
- [ ] A2g. **Catalog-first UPC resolution + leading-zero normalization** — Session 13
- [ ] A3a. "Search Catalog" bulk add (incl. category-filtered results)
- [ ] A3b. Batch scan FAB → "Save all (N)"
- [ ] A4a. View mode (status card "Tap to move", chips, Market Price)
- [ ] A4b. Edit fields ("Edit Funko" / "Save")
- [ ] A4c. UPC scan dialog + "Share UPC with community?" prompt (USER_EDIT)
- [ ] A4d. Photos: camera / gallery (Photo Picker, no permission prompt) / "Fetch from catalog"; "Save photo as" Main/Variation/Both
- [ ] A4e. Variant edit: description, price, remove
- [ ] A4g. **Manual market value** — enter value in edit; shows on card ("Manually set"); refresh with no feed data does NOT blank it (Session 11 regression); a real market feed overwrites it — Session 11
- [ ] A4h. **Image URL entry** — paste image URL in edit; saves; auto-re-downloads thumbnail on URL change; http URL loads over https — Session 11
- [ ] A4i. **Manual search → Add manually** — toolbar manual search "No results" → Add manually (UPC editable) → saves — Session 11
- [ ] A4f. **Blob-preservation regression (CRITICAL — Session 7 risk)**
- [ ] A4l. **Live PriceCharting refresh tier** — PC-URL items refresh from PriceCharting (Complete grade primary), runs before retail short-circuit — Session 13
- [ ] A5. Delete via card kebab menu AND detail trash → "Remove from collection?"
- [ ] A6. Search / segmented sort (4 options) / "All" + franchise chips; confirm category prefs do NOT filter My Dex
- [ ] A7. Price alerts (want-list only; "Target price (USD)")
- [ ] A8. "My collection categories": toggles, genre toggle, Reset, restart persistence
- [ ] A9. Reports + export .xlsx (4 sheets) / .csv
- [ ] A10. **Check tab — Pre-Purchase Check (4 overlays, 4s auto-reset, re-scan cancel)**
- [ ] A11. App theme (6 options) + Diagnostics log share

## Part B — Integrations

- [ ] B1. Channel3 — row hidden by default (`SHOW_CHANNEL3_KEY_UI=false`); no-key ⇒ tier dormant (no crash); imported key persists — **rewritten Session 13**
- [ ] B1b. Channel3 key import from `funkodex_keys.json` (toast confirms; unwired keys reported skipped; bad-file errors) — or **BLOCKED** if no UI entry point in default build — Session 13
- [ ] B2. HobbyDB OAuth connect/persist/disconnect
- [ ] B3. eBay OAuth connect/persist/disconnect — re-confirm subtitle copy ("RSS feed" string is stale)
- [ ] B4. Drive connect / back up now / **lapsed grant (notif id 3002)** / disconnect
- [ ] B5. Contributions: silent USER_SCAN + USER_EDIT prompt; toggle arms/cancels GitHubUploadWorker; WORKER_URL-unset skip log
- [ ] B6. Catalog refresh worker log sequence — "Refresh now" + scheduled run

## Part C — Backup/Restore (run LAST)

- [ ] C1. Backup: Downloads file + "Share backup via…" + JSON structure (no catalog/system docs, blob encoding correct)
- [ ] C2. Restore: "Replace your collection?" → exact state restored, catalog/cat-prefs intact
- [ ] C3. **Force restore: "Database rebuilt" → restart → re-preload → correct collection (HIGHEST PRIORITY — Session 7 reopen/Collection risk)**

## Part D — Automated

- [ ] D1a. Enriched import — 5-record test file, exact counts (1 or 0 updated / 2 or 3 added / 2 skipped / 0 errors). NB Session 13: 2-skipped unchanged (no UPC/handle collisions in this file); path-coverage note updated
- [x] D1b. Enriched import — full 14,314-record file — PASS (see Result log)
- [ ] D1c. **UPC-collision merge** — same-UPC/diff-handle records merge to one doc (1 added / 1 updated / 0 skipped); colliding record's price + PC metadata present; re-import idempotent — Session 13
- [ ] D2. `gradlew test` — 72 tests green (9+11+8+15+20+9). NB Session 13: count unchanged but `pricechartingUrl` round-trip and catalog-first lookup are not asserted (covered by device A2g/A4l)
- [ ] D3. SecureKeyStore v2 prefs format / no Cipher/KeyStore exceptions across restart

## Part E — 16 KB Regression

- [ ] E1. 16 KB emulator condensed smoke test (A1, A3a, A4d+A4f, C1)

---

## Result log

(Add one line per completed item: date · item · PASS/FAIL/BLOCKED · note)

- 2026-06-13 · D1b · PASS · Full 14,314-record `funko_data_enriched.json`, first run: 13,585 enriched / 725 added / 4 skipped / 0 errors, 51s. Matches HANDOFF.md dry-run estimate (~13,583/~725/~4).
- 2026-06-13 · D1b (re-import) · PASS · Same file, second run after category fix: 14,310 updated / 0 added / 4 skipped / 0 errors, 47s. Confirms idempotency + category repair (714 docs). Verified via Search Catalog → "perpetua" returning "Papa V Perpetua · Music" (was 0 results before fix).

---

# PART 3 — On-Device Test Plan

**Device required:** Physical Android device (API 26+)
**Build:** Debug APK from current branch
**Prerequisites:** Fresh install OR clear app data before starting

---

## 1. First Launch — Catalog Preload

**What to test:** On a fresh install the splash screen must wait for the 23,940-item catalog to load before showing the collection screen.

**Steps:**
1. Install APK on a device that has never had FunkoDex installed
2. Launch the app
3. Observe the splash screen — the Celtic heart animation should display
4. Wait — do NOT tap anything
5. The app should transition to the Collection screen automatically

**Pass criteria:**
- Splash screen shows the Celtic heart animation on a navy background
- App does NOT navigate to Collection before the catalog is ready
- Collection screen loads without errors
- Adding an item via manual search finds items immediately (catalog is ready)

**Fail indicators:**
- App crashes on launch
- Collection screen appears but search returns no results
- Splash screen hangs indefinitely (>30 seconds on a real device)

---

## 2. UPC Barcode Scan — Add Screen

**What to test:** Scanning a Funko barcode in the Add screen finds the item in the catalog.

**Steps:**
1. Tap Add in the bottom nav
2. The idle screen shows "Start Scanning" and "Search Manually"
3. Tap "Start Scanning"
4. Grant camera permission if prompted
5. Point the camera at a Funko Pop box barcode
6. The app should detect the barcode and show a preview of the item

**Pass criteria:**
- Camera opens without crashing
- Barcode is detected within a few seconds
- Item preview shows correct name, franchise, and image
- Tapping Add saves the item to the collection

**Fail indicators:**
- Camera permission dialog appears but camera doesn't open after granting
- Camera opens but never detects the barcode
- Wrong item shown for the scanned barcode

**Note:** If the item isn't in the catalog, the NotFound sheet appears — this is expected. Try a different barcode.

---

## 3. UPC Barcode Scan — Edit Screen

**What to test:** The inline UPC scanner in the edit screen correctly reads a barcode and populates the UPC field.

**Steps:**
1. Open any item in your collection
2. Tap the edit (pencil) icon
3. Scroll to the UPC field near the bottom of the edit screen
4. Tap the barcode icon on the right of the UPC field
5. Grant camera permission if prompted
6. Point the camera at any barcode (Funko box or any product)
7. The UPC field should populate automatically and the camera dialog should close

**Pass criteria:**
- Camera dialog opens inside the edit screen (compact 320dp view, not full navigation)
- Barcode is detected and populates the UPC field
- Camera dialog closes automatically after scan
- Save works correctly with the new UPC
- If UPC is new, contribution prompt appears after saving

**Fail indicators:**
- Tapping the barcode icon does nothing
- Camera opens but never detects the barcode
- App navigates away from edit screen instead of showing inline camera

---

## 4. Manual Search — Keyboard Dismiss

**What to test:** The keyboard dismisses correctly when the search checkmark is tapped.

**Steps:**
1. Tap Add → Search Manually
2. The manual search sheet appears with a text field
3. Type a search term (e.g. "batman")
4. Tap the checkmark (Done) on the keyboard
5. Results should appear and the keyboard should dismiss

**Pass criteria:**
- Keyboard dismisses after tapping the checkmark
- Search results appear in a scrollable list
- Results are filterable and selectable

**Fail indicators:**
- Keyboard stays visible after tapping the checkmark
- Keyboard dismisses but no results appear
- App crashes or freezes on search

---

## 5. Check Screen (PreScan Tab)

**What to test:** The Check/PreScan tab functions correctly.

**Steps:**
1. Tap Check in the bottom nav (shopping cart icon)
2. Observe the screen layout
3. Test any available functionality (scanning items to check against want list, etc.)

**Pass criteria:**
- Screen loads without crashing
- UI elements are visible and functional

**Note:** This screen has not been tested in any session. Document exactly what you see and what functionality is available.

---

## 6. Fetch from Catalog — Photo

**What to test:** Fetching an official image for an item that has no image (HobbyDB URLs are blocked on the emulator but should work on device).

**Steps:**
1. Find an item in your collection that shows no image (blank card)
2. Open the detail screen
3. Tap the edit (pencil) icon
4. Tap the camera FAB (bottom-right of the image area)
5. The photo sheet appears — tap "Fetch from catalog"
6. A "Fetching image…" spinner dialog should appear
7. On success: "Image downloaded" dialog appears and image shows in edit

**Pass criteria:**
- Fetching dialog appears (was broken on emulator due to network block)
- Image downloads and shows in the edit screen
- Saving the item preserves the image
- Image shows on the collection card after saving

**Fail indicators:**
- "Image not available" dialog — note the URL shown for debugging
- Spinner dialog never appears (regression from earlier fix)

**Session 11 note:** `http://` image hosts (e.g. media.aent-m.com) are now upgraded to
`https://` before loading, so a previously-failing "CLEARTEXT communication not permitted"
URL should now succeed. A "too many follow-up requests" / redirect-loop URL (e.g. a
UPC-guessed booksamillion cover) is dead data, not an app bug — fix by setting a real
image URL (see test 13). A `.html` page URL will not render; it must be a direct image URL.

---

## 7. App Performance

**What to test:** General app performance and frame rate on a real device.

**Areas to check:**
- Collection screen scrolling — should be smooth with 50+ items
- Switching between tabs — should be instant
- Opening detail screen — should be near-instant
- Search results appearing — should be <1 second for local catalog search
- Backup creation — should complete in <5 seconds for a typical collection

**Pass criteria:**
- No visible frame drops (jank) during normal navigation
- No ANR (Application Not Responding) dialogs
- Memory usage stable (not growing continuously)

**Fail indicators:**
- Visible stuttering during list scrolling
- ANR dialog appears during any operation
- App becomes unresponsive after extended use

---

## 8. Send to Another Phone

**What to test:** Full backup transfer to a second device.

**Steps — Device 1 (source):**
1. Go to Settings → Backup database
2. Confirm the backup saved dialog
3. Tap "Share to…"
4. Choose a transfer method (Quick Share, Bluetooth, Gmail, etc.)
5. Send to Device 2

**Steps — Device 2 (target):**
1. Receive the backup zip file
2. Open FunkoDex (fresh install or clear data first)
3. Wait for catalog preload to complete
4. Go to Settings → Restore backup
5. Navigate to the received zip file
6. Confirm the restore
7. Verify collection matches Device 1

**Pass criteria:**
- Backup file transfers successfully
- Restore completes without error
- All items appear on Device 2 with correct data
- Photos, variants, and flags are all preserved

**Fail indicators:**
- Transfer fails at the share step
- Restore fails with permission error
- Items missing or data incorrect after restore

---

## 9. Enriched Catalog Import

**What to test:** Settings → Import Enriched Catalog merges `funko_data_enriched.json` into the live catalog.

**Steps:**
1. Copy `funko_data_enriched.json` onto the device (e.g. Downloads)
2. Go to Settings → Catalog → Import Enriched Catalog
3. Pick the file from the file picker
4. Wait for the progress dialog (record counter) to complete
5. Review the result summary dialog

**Pass criteria:**
- Progress dialog shows live `processed / total` counts (14,314 total)
- Result summary shows approximately 13,583 enriched, ~725 added, ~4 skipped,
  0 errors — **confirmed 2026-06-13: 13,585/725/4/0** on first run
- Spot-check a known existing item (e.g. search "Twinkie the Kid") — detail screen should
  now show enriched data without `imageUrl`, `title`, or `seriesList` having changed
- Spot-check a net-new item (e.g. search "Peacemaker on Peacecycle") — should appear as
  a new catalog entry with handle `peacemaker-on-peacecycle`, not `91991.html`
- Re-running the import on the same file should produce 0 added (no duplicates), all
  records re-enriched or skipped

**Fail indicators:**
- App crash or ANR during import
- Result summary shows nonzero `errors`
- Existing item's `imageUrl`/`title`/`seriesList` changed
- Any catalog entry with a `.html` handle
- Re-running creates duplicate entries

---

## 10. Manual Add — Item Not in Catalog (Session 11)

**What to test:** Adding an item the catalog doesn't have (e.g. a convention exclusive), from both entry points.

**Steps (from scan):**
1. Scan a barcode not in the catalog (or a new exclusive) → "Barcode not in catalog" sheet
2. Tap **Add manually**
3. UPC field is shown locked (from the scan); enter a Name (required)
4. Expand **More details**, optionally set Pop! number, franchise, category, exclusive + retailer, price paid, condition, image URL
5. Leave **Share with community** on; tap **Add to collection**

**Steps (from manual search):**
6. Add → Search Manually → type a term with no results → **Add manually** (UPC field editable/blank)

**Pass criteria:**
- Form scrolls so **Add to collection** is reachable with More details expanded
- Item saves; appears in My Dex with `funko::{upc}` id when a UPC was present
- Re-scanning the same barcode later resolves instantly (UPC now linked)
- If shared, a `USER_MANUAL` contribution is queued (verify via contribution/worker log)

**Fail indicators:**
- Save button unreachable when More details expanded
- Crash on save; item not in My Dex; future scan of same UPC still "not in catalog"

---

## 11. Scanner — Camera Survives Screen-Saver (Session 11)

**What to test:** The scanner camera recovers after the screen turns off and on (was a black-screen bug).

**Steps:**
1. Open the scanner (Add tab) and let the camera preview start
2. Let the screen time out to screen-saver / lock (or press power to sleep)
3. Wake the device and return to the still-open scanner

**Pass criteria:**
- Live camera preview resumes automatically; barcodes scan normally
- No need to exit the scanner and pick another tab to recover

**Fail indicators:**
- Black preview after wake; must leave/re-enter the scanner to fix (the original bug)

---

## 12. Manual Market Value (Session 11)

**What to test:** Setting a market value by hand and its interaction with price refresh.

**Steps:**
1. Open an item with no market price (e.g. a manually added exclusive) → Edit
2. Enter a **Market value**, save → price card shows it ("Manually set")
3. Tap **Refresh prices** on an item the tiers can't price
4. Separately, on an item the tiers CAN price, set a manual value then refresh

**Pass criteria:**
- Manual value persists and displays after save
- Refresh that finds nothing keeps the manual value (shows "No new market data found" note) — does NOT blank it
- Refresh that finds real market data overwrites the manual value and clears the manual flag
- Reports "Est. Market Value" includes manually-set values

**Fail indicators:**
- Manual value wiped to $0 on a no-data refresh (the staleDays-overflow regression)
- Card shows "No price data" and drops the value until the screen is re-entered

---

## 13. Image URL Entry + http→https (Session 11)

**What to test:** Pasting an image URL and the http-to-https upgrade.

**Steps:**
1. Edit an item with no image; paste a direct image URL (HobbyDB/funko.com) in **Image URL**; save
2. Confirm the image appears; change the URL and save again
3. Try an item whose catalog image URL is `http://` (e.g. a media.aent-m.com URL)

**Pass criteria:**
- Pasted image URL loads and shows on card after save
- Changing the URL re-downloads the thumbnail (old cached image replaced)
- `http://` image hosts load (upgraded to https) instead of failing with "CLEARTEXT communication not permitted"

**Fail indicators:**
- Image stays blank after a valid image URL; stale image after URL change
- "CLEARTEXT ... not permitted" error on an http host that supports https
- (Expected, not a fail) a page URL like `…/78901.html` won't render — must be a direct image URL

---

## Results Log

| Test | Result | Notes |
|------|--------|-------|
| 1. First launch / catalog preload | PASS (VM) | Verified on emulator — splash-gated preload completes, Collection loads, manual search returns catalog results. No physical-device dependency. |
| 2. UPC scan — add screen | PASS | 2026-06-13. Verified on device (Galaxy S23, SM-S911U). Scanned a physical box barcode from the add flow; camera opened, barcode read, catalog match found and item added (e.g. Tinker Bell scanned in successfully). |
| 3. UPC scan — edit screen | PASS | 2026-06-13. Verified on device (Galaxy S23, SM-S911U). Scanned a barcode from an existing item's edit screen; camera opened, barcode read, UPC set on the record. (UPC field is now scan-only — manual entry removed.) |
| 4. Manual search keyboard dismiss | PASS (VM) | Verified on emulator — keyboard dismisses on Done, results render, list selectable. No physical-device dependency. |
| 5. Check screen | PASS | 2026-06-13. Verified on device (Galaxy S23, SM-S911U). Camera scan on the Check screen works; correctly detected an already-owned Funko (duplicate detection fired). |
| 6. Fetch from catalog | | |
| 7. App performance | PASS | 2026-06-13. Verified on device (Galaxy S23, SM-S911U). Performance acceptable — responsive in normal use, no notable jank observed. |
| 8. Send to another phone | | |
| 9. Enriched catalog import | PASS | 2026-06-13. Import confirmed: 14,314 total in batches of 500; first run 13,585/725/4/0; re-import idempotent (0 added/~14,310 updated). Net-new enriched-only item confirmed via "perpetua" → "Papa V Perpetua" after fixing a catalog-search filter bug (enabled-categories key/name mismatch was silently dropping all results). Existing-item integrity: re-import left existing records unchanged (pass condition met). Handle-repair spot-check validated: "Peacemaker on Peacecycle" found via search (`91991.html` → `peacemaker-on-peacecycle`). NOTE: the enriched catalog does NOT survive an app uninstall — `uninstallDebug` wipes it and reinstall re-preloads only the base Kenny Chan set, so enriched-only items disappear until the enriched import is re-run manually. The "Twinkie the Kid" image spot-check surfaced a data issue, not a code issue: one duplicate Twinkie variant is linked to a dead/wrong HobbyDB URL (a "Shirts and Jackets" apparel image returning 404 NoSuchKey) — not fixable by the app; that record needs deletion or a manual photo. |
| 10. Manual add (catalog-missing) | | Session 11 — new |
| 11. Camera survives screen-saver | | Session 11 — new (was black-screen bug; fix applied) |
| 12. Manual market value | | Session 11 — new (incl. staleDays-overflow regression check) |
| 13. Image URL entry + http→https | | Session 11 — new |
| 14. Image URL clear (✕) on detail edit | | Session 12 — new |
| 15. "Enter details manually" button on Add screen | | Session 12 — new |
| 16. "Add another" returns to live camera | | Session 12 — new |
| 17. Manual-UPC validation (valid/error states) | | Session 12 — new; try a known-good UPC and a transposed one |
| 18. Variant-aware pricing (chase vs common) | | Session 12 — new; needs a flagged chase/exclusive with sold comps |
| 19. eBay price tier fires on-device | | Session 12 — confirm whether the HTML scrape returns prices on real-device IP (logs) |
| 20. Camera rebind after leave/return (executor leak fix) | | Session 12 — leave scanner + return repeatedly; camera must still bind |

---

# PART 4 — Backend Setup


This guide stands up every backend the app talks to, so that all functionality
in `DEVICE_TEST_PLAN.md` can be exercised on a physical phone. It is written for
a reader with **zero prior context** — a fresh Claude session or a second person
could follow it without the conversation that produced it.

All shell commands are **Windows** (`cmd`/PowerShell) syntax. Where a step needs
PowerShell specifically, it says so.

## What you need before starting

- Android Studio installed and able to build the project (this guide does not
  cover toolchain install — pinned toolchain is AGP 8.13.2, Gradle 8.13,
  Kotlin 2.0.21, JDK 17, compileSdk/targetSdk 36, minSdk 26).
- `git` 2.x and the GitHub CLI (`gh`) authenticated (`gh auth status`).
- A Node.js install (for `wrangler` and the community-repo workflows; the
  workflows themselves run Node 20 / Python 3.12 on GitHub's runners, not on
  your machine).
- Accounts you will sign into during setup: GitHub, Cloudflare, eBay developer,
  Channel3, HobbyDB, and a Google account for Drive.

## Verified facts worth knowing up front

These were checked against the repository source and live vendor docs, not
assumed:

- The app's hardcoded help text says Channel3 is "100 lookups/day free." That is
  stale. As of 2026, Channel3's free tier is **1,000 lifetime query credits,
  then $7 per 1,000 queries** (`api.trychannel3.com/v1`, `x-api-key` header).
  The key still works; only the quota expectation changes.
- The Cloudflare Worker exposes exactly two routes: `GET /health` and
  `POST /contribute`. `/contribute` requires `X-Device-ID`, `X-Timestamp`, and
  `X-Signature` headers, enforces a 5-minute replay window, and rate-limits to
  50 contributions per device per day. The HMAC signature is generated on the
  device, which is why **no shared signing secret** is configured between the
  app and the Worker.
- `workerUrl` is the **only** value that belongs in `local.properties`. Every
  other credential is entered in-app and stored encrypted via `SecureKeyStore`
  (AES-256-GCM, AndroidKeyStore).
- The community repo content ships in this project under `community-repo\`. It
  is a complete, pushable tree: both GitHub Actions workflows
  (`merge-deltas.yml` → "Weekly delta merge"; `quarterly-rebase.yml` →
  "Quarterly rebase"), `README.md`, the seed `funko_upc_community.json`, schema
  docs, and `deltas\.gitkeep`.

## Order of operations (dependency chain)

The community-upload pipeline has hard ordering: the Worker writes into the
community GitHub repo, so that repo must exist first; the Worker's
`wrangler.toml` requires a KV namespace ID before it will deploy; and the app
can't point at the Worker until the Worker has a URL.

1. Community GitHub repo
2. GitHub PAT for the Worker
3. Cloudflare Worker (KV namespace → secrets → deploy)
4. Point the app at the Worker (`local.properties`)
5. eBay developer registration
6. Channel3 key (obtained now, entered in-app later)
7. HobbyDB account (no pre-setup)
8. Build and install on the phone
9. In-app backend connections
10. Verification checklist

---

## 1. Community GitHub repo (`funko-upc-community`)

Must be **public** so the app can download the merged community UPC file
anonymously, and must exist before the Worker is deployed.

Create the repo:

```
gh repo create celticht32/funko-upc-community --public --description "Community UPC database for FunkoDex"
```

Push the contents that already exist under `community-repo\`. **Note:** this
folder lives inside the parent FunkoDex git tree, so initializing a nested git
repo here is intentional — you are publishing this subtree as its own
standalone repository.

```
cd C:\Downloads\Development\FunkoDex\community-repo
git init
git add -A
git commit -m "Initial commit — community UPC database v1.0"
git branch -M main
git remote add origin https://github.com/celticht32/funko-upc-community.git
git push -u origin main
```

Use `main` (the workflows reference it).

**Verify:** open `github.com/celticht32/funko-upc-community/actions`. You should
see two workflows — **Weekly delta merge** and **Quarterly rebase**. Trigger
"Weekly delta merge" manually (Run workflow); it will succeed doing nothing
because there are no delta files yet. That confirms the workflow and its
permissions are wired.

## 2. GitHub PAT for the Worker

The Worker authenticates to GitHub with a fine-grained token scoped to the
community repo only.

GitHub → Settings → Developer settings → Personal access tokens → Fine-grained
tokens → Generate new token.

- Repository access: **Only select repositories** → `funko-upc-community`
- Permissions: **Contents → Read and write**

Copy the token string. You will paste it in step 3c. Treat it like a password.

## 3. Cloudflare Worker (community upload proxy)

The Worker code is `cloudflare-worker\worker.js`. Its `wrangler.toml` binds a KV
namespace named `RATE_LIMIT` and reads three secrets. The repo's older
`GITHUB_SETUP.md` omits the KV-namespace step — without it, `wrangler deploy`
fails. Do these in order.

### 3a. Install and authenticate Wrangler

```
npm install -g wrangler
wrangler login
```

### 3b. Create the KV namespace and wire its ID

```
cd C:\Downloads\Development\FunkoDex\cloudflare-worker
wrangler kv namespace create RATE_LIMIT
```

Copy the `id` value from the output, then open `wrangler.toml` and replace
`REPLACE_WITH_KV_NAMESPACE_ID` with that id.

### 3c. Set the three required secrets

These names are read directly by `worker.js`:

```
echo YOUR_GITHUB_PAT| wrangler secret put GITHUB_PAT
wrangler secret put WORKER_SECRET
echo celticht32/funko-upc-community| wrangler secret put GITHUB_REPO
```

For `WORKER_SECRET`, paste any 32-byte random hex string when prompted. To
generate one in PowerShell (no OpenSSL needed):

```
powershell -Command "-join ((1..32) | ForEach-Object { '{0:x2}' -f (Get-Random -Max 256) })"
```

(`WORKER_SECRET` is used for Worker-side bookkeeping; it does **not** need to
match anything on the device — the device HMAC is self-generated.)

### 3d. Deploy and health-check

```
wrangler deploy
curl https://funkodex-contrib.YOUR_ACCOUNT.workers.dev/health
```

Expected response:

```
{"status":"ok","version":"1.0"}
```

A `200` on `/health` is your confirmation the Worker is live. Note the full URL
— you need it in step 4.

## 4. Point the app at the Worker

Edit `local.properties` in the project root and add the one line:

```
workerUrl=https://funkodex-contrib.YOUR_ACCOUNT.workers.dev
```

Without this line the app logs "WORKER_URL not configured — skipping upload" and
community uploads silently no-op (the app still runs normally). With it, the
daily `GitHubUploadWorker` posts HMAC-signed contributions to the Worker's
`POST /contribute` route.

## 5. eBay developer registration

Required before the in-app eBay sign-in (price tier 2a RSS plus the Browse API)
works. The app calls live **production** eBay endpoints
(`api.ebay.com/identity/v1/oauth2/token`,
`api.ebay.com/buy/browse/v1/...`), so use Production keys, not Sandbox.

- Go to `developer.ebay.com` and create a developer account.
- Create an app and obtain **Production** keys.
- Under User Tokens, add `funkodex://oauth/ebay` as an accepted redirect URI.
- Note your **RuName** (format `YourName-YourApp-PRD-xxxxxxxx-xxxxxxxx`).
- Edit `app\src\main\java\com\funkodex\auth\OAuthConfig.kt`:

```kotlin
const val CLIENT_ID = "YourName-YourApp-PRD-xxxxxxxx-xxxxxxxx"  // your RuName
```

- Commit and push the change to the private app repo.

## 6. Channel3 key

Sign up at `trychannel3.com/sign-up` and obtain an API key. You do **not** put
it in any config file — it is entered in-app in step 9 and stored encrypted.
(Quota note: 1,000 lifetime credits then paid, despite the app's "100/day"
text.)

## 7. HobbyDB

No pre-registration on your side. The app performs a standard OAuth sign-in
in-app (step 9) against `hobby-db.com/oauth/authorize` and `/oauth/token`. You
only need a HobbyDB account to sign in with. This is what unblocks the
catalog-photo fetch test (device test 6), since HobbyDB image URLs were the
piece blocked on the emulator.

## 8. Build and install on the phone

- Enable Developer Options and USB debugging on the device.
- Connect over USB and confirm it is recognized:

```
adb devices
```

- Install the debug build (or press Run in Android Studio):

```
gradlew installDebug
```

- Optional pre-flight: run the JVM unit suite (no device needed), which the
  test plan tracks as 72 green tests:

```
gradlew test
```

## 9. In-app backend connections (on the phone)

After install, open the app and connect each integration. These light up the
flows the device test plan exercises.

- **Settings → Data Sources → Channel3** → enter the key from step 6. Row should
  read "Connected · UPC lookup · pricing."
- **Settings → Data Sources → HobbyDB** → sign in (browser OAuth). Row reads
  "Connected · market pricing · vaulted status enabled."
- **Settings → Data Sources → eBay** → sign in (requires step 5 done first).
- **Settings → Database → Connect Google Drive** → sign in. Arms
  `DriveBackupWorker`; needed for backup/transfer and the lapsed-grant test.
- **Settings → Database → Contribute to community database** → toggle on. With
  step 4 done, this arms the daily upload worker that talks to your Worker.

## 10. Verification checklist

Backends:

- [ ] Community repo public, two workflows visible, "Weekly delta merge" ran clean
- [ ] Worker `/health` returns `{"status":"ok","version":"1.0"}`
- [ ] `local.properties` has the `workerUrl` line
- [ ] eBay `CLIENT_ID` set in `OAuthConfig.kt` and pushed
- [ ] App installed on the device (`adb devices` lists it)

In-app rows after step 9:

- [ ] Channel3 — "Connected · UPC lookup · pricing"
- [ ] HobbyDB — "Connected · market pricing · vaulted status enabled"
- [ ] eBay — connected
- [ ] Google Drive — connected
- [ ] Community contribution toggle on

Once all of the above are green, every backend referenced by
`DEVICE_TEST_PLAN.md` is live: live UPC scan, Check/PreScan, catalog photo fetch
(HobbyDB), all three price-source sign-ins, Drive backup, and the community
contribution round-trip through Cloudflare to GitHub. The only test that needs a
**second** physical device is the receive side of "Send to another phone."

## Known gaps to confirm yourself

These could not be verified without your accounts/machine and should be checked
at their steps:

- The community-repo push (step 1) assumes `community-repo\` is intact in your
  working tree; confirm the workflow files are present before pushing.
- `wrangler deploy` (step 3d) has not been run against your Cloudflare account;
  the KV-namespace ID and secrets must be correct or it will fail.
- The eBay portal UI changes over time; if the redirect-URI or key-generation
  screens differ from the description, the portal is authoritative.

---

*Maintained by Celtic Heart Steamworks. Companion to `DEVICE_TEST_PLAN.md`,
`COMPLETE_TEST_PLAN.md`, and `GITHUB_SETUP.md`.*

---

# PART 5 — Collection Completion + Want List (NEW, from FUNKODEX_SPEC_v1.0 §1)

Status: SPEC stage — these are the test cases to write/run when §1 is built.
None passing yet (feature not built). Grouped by the spec sub-section they verify.

## P5-A. Grouping axis (§1.1, §1.2)
- [ ] **CG1 franchise grouping:** catalog franchises group on `franchiseSuggestion`;
      Hocus Pocus shows 21 records, Dragon Ball ~18 — NOT collapsed under an umbrella.
- [ ] **CG2 variant collapse:** Madame Leota #575 (Glitter / plain / Glow) shows as ONE
      grouped row with three variants under it, not three top-level rows.
- [ ] **CG3 danger test — no over-merge:** Vegeta #10 and Great Ape Vegeta do NOT group;
      Super Saiyan Vegeta #154 stays separate from Vegeta #10.
- [ ] **CG4 base-SKU level:** Constance Hatchaway #578 and #803 group SEPARATELY (two
      distinct SKUs, not merged to one character).
- [ ] **CG5 accepted imprecision visible:** a sculpt-vs-finish mis-group (e.g. Krillin
      #706 Metallic + Destructo Disc) keeps BOTH variants individually visible/ownable —
      no figure hidden by the group.

## P5-B. Want model (§1.3)
- [ ] **CW1 heart = want:** tapping the heart on a missing figure adds it to the want
      list; it appears in the buy feed. Owned items show a checkmark and NO heart.
- [ ] **CW2 scan = owned, read-only:** owned state is set by scan/add, not toggled on
      this screen.
- [ ] **CW3 want satisfied by scan:** scanning a currently-wanted figure moves it
      Wanted → Owned automatically; it leaves the want list with no manual step.

## P5-C. Want-all vs cherry-pick (§1.4, §1.5)
- [ ] **CW4 want-all bulk:** "Want all N missing" adds every current missing figure to
      the want list in one tap; button flips to the clear/undo state.
- [ ] **CW5 cherry-pick:** with no want-all press, only individually-hearted figures are
      wanted; the rest stay quiet.
- [ ] **CW6 intent stored:** want-all writes COMPLETE to
      `group_pref::FRANCHISE::{key}`; cherry-pick path leaves CHERRY_PICK / absent.
      Intent backs up and restores via the non-catalog denylist.

## P5-D. Suppress exception (§1.6)
- [ ] **CW7 untick stores suppression:** under a COMPLETE franchise, unticking a figure
      records a per-figure suppress flag (distinct from never-evaluated).
- [ ] **CW8 suppression survives re-import:** after CW7, run a catalog re-import +
      reconciliation; the suppressed figure is NOT re-added to the want list.

## P5-E. Auto-want on import/enrichment (§1.7) — the standing-subscription behavior
- [ ] **CW9 new release auto-wanted:** with Haunted Mansion = COMPLETE, import a catalog
      that adds a NEW Haunted Mansion figure (unowned, not suppressed) → after
      reconciliation it appears on the want list automatically.
- [ ] **CW10 ordering:** reconciliation runs AFTER `CollectionRelinkService`; a newly
      imported figure the user OWNS is not mis-flagged as new/wanted.
- [ ] **CW11 idempotent:** running import + reconciliation twice with no catalog change
      adds nothing the second time.
- [ ] **CW12 set-difference, not date:** verify detection works despite uniform
      `lastUpdated` on all records (no reliance on a per-record date).
- [ ] **CW13 franchise-tagging dependency:** a new figure arriving with BLANK/umbrella
      `franchiseSuggestion` is NOT auto-wanted (correctly) until enrichment tags it.

## P5-F. Counts (§1.8)
- [ ] **CW14 grouped denominator:** completion count uses distinct `variantGroupKey`
      (Madame Leota counts once, not three times).
- [ ] **CW15 both counts:** COMPLETE shows "owned of total"; CHERRY_PICK emphasizes
      "owned of wanted." Verified franchise: Haunted Mansion 10 / 28.

## P5-G. UI (§1.9) + buy row (§1.10)
- [ ] **CW16 drill-in (Option C):** franchise grid → franchise screen with want-all
      button, OWNED section, MISSING section, optional `setTag` sub-group.
- [ ] **CW17 eBay-active buy row:** missing figure shows "from $X" + working tap-through
      link via `fetchEbayActive()`; "no listing" when none. Distinct from eBay-SOLD
      (pricing) tier.
- [ ] **CW18 setTag sub-band:** Haunted Mansion shows its "Mini Vinyl Figures" sub-group
      with its own want-all button.

## P5-H. Data pre-work (§9) — verify the fixes landed
- [ ] **CD1 umbrella suppressed:** "Disney" (165 records) does NOT appear as a completable
      franchise row.
- [ ] **CD2 name normalization:** `Lilo and Stitch` / `Lilo & Stitch` resolve to ONE
      franchise; no count exceeds 100% (the owned 6 / total 2 bug is gone).
- [ ] **CD3 stale group_pref removed:** `group_pref::FRANCHISE::Pop! Disney` (a format,
      not a franchise) is deleted.
- [ ] **CD4 title entity decode:** `Scooby-Doo & Haunted Mansion` displays decoded (no
      `&amp;`).

---

## Document log

- v1.0 (2026-06-29): consolidated COMPLETE_TEST_PLAN_v2.0 + TEST_TRACKER_v2.0 +
  DEVICE_TEST_PLAN + BACKEND_SETUP into one file; retired v1 plan + SESSION_D_TRACKER;
  added PART 5 (Collection Completion + Want List) from FUNKODEX_SPEC_v1.0 §1.
