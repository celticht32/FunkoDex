# FunkoDex — Complete Functional Test Plan (code-verified)

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
