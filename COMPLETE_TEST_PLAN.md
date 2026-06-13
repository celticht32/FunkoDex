# FunkoDex — Complete Functional Test Plan

Covers every feature built across all sessions to date: catalog & data layer
(Sessions 1–4, 7, 8), variant/photo/backup system (Session 3), Play-readiness
changes (Sessions 5–6), CBL Collection API migration (Session 7), and
Keystore migration (Session 8).

Recommended order: run **Part A** (core collection features) first on a
normal emulator/device, then **Part B** (integrations — Drive, OAuth,
community), then **Part C** (backup/restore — do this last since
force-restore wipes the database), then **Part D** (automated/regression).

A condensed subset for the 16 KB emulator is in Part E.

---

# PART A — Core Collection Features

## A1. First launch & catalog preload

1. Fresh install on a clean emulator/device.
2. Launch the app — should open to **My Dex** (Collection tab), empty.
3. No crash, no error toast.
4. Go to the **Check** tab and search for a common franchise (e.g. "Star
   Wars"). **Expected:** results appear — confirms the ~23,000-item Kenny
   Chan catalog preloaded successfully (`CatalogPreloader`, `system::catalog_loaded`
   marker written).
5. Check logcat for `CatalogPreloader` — should show `Loaded(count)` with
   count ≈ 23,000, no `AssetMissing`/`ParseError`.

**Pass:** App launches clean, catalog preloads, search returns results.

---

## A2. Add item — UPC scan (live camera)

1. Go to the **Add** tab (bottom nav, barcode icon — "Scanner").
2. Tap **Start scanning**.
3. Point the camera at a real Funko Pop barcode.
4. **Expected:** "Looking up…" overlay appears briefly, then the
   **FunkoPreviewSheet** bottom sheet shows the matched item (name, franchise,
   series number, image, exclusive-retailer chip if applicable).
5. Optionally enter a **price paid**.
6. Tap **Add to collection**.
7. **Expected:** "Saved!" confirmation, item appears in **My Dex** with
   `isOwned = true`, `dateAdded = today`.
8. Repeat, but this time tap **Want list** instead — item should appear in
   the want list (isOwned = false) rather than the owned collection.

### A2b. Unknown UPC → pending queue

9. Scan a barcode that the catalog doesn't recognize (or disconnect network
   first, then scan any code).
10. **Expected:** "Error" sheet appears with **Retry** / **Manual** options,
    OR (if offline) the scan is queued as a `PendingUpcScan` document
    (`pending_scan::...`).
11. If queued offline: reconnect network. **Expected:** `ConnectivityObserver`
    fires, processes the pending queue, and either resolves the item (adds it
    as a want-list item with a notification "Funko scan identified") or
    increments its retry count (up to 5 attempts before giving up).

**Pass:** Live scan → preview → add to collection/want list works; offline
scans queue and resolve once connectivity returns.

---

## A3. Add item — manual search + bulk add

1. Go to the **Check** tab ("PreScan").
2. Type a franchise name (e.g. "Marvel").
3. **Expected:** catalog matches appear within ~1 second
   (`FunkoLookupService.searchLocalByName`).
4. Select 2–3 items.
5. Tap the bulk-add/confirm action.
6. **Expected:** all selected items appear in **My Dex** with default values.

### A3b. Batch scan sheet

7. From the **Scanner** screen, open the **Batch scan** sheet (if accessible
   via a button/icon — check for a "batch" icon near the scan button).
8. Scan multiple items in sequence without leaving the sheet.
9. **Expected:** each scanned item appears as a row in the batch list with a
   running count (`Save all (N)`).
10. Use **Want list** toggle in the batch sheet if present — confirm it
    changes the target for newly-scanned items.
11. Tap **Save all** — **expected:** all batched items saved to the
    collection/want list as appropriate, sheet closes, items appear in
    **My Dex**/want list.

**Pass:** Manual search returns results and bulk-adds correctly; batch scan
accumulates multiple items and saves them all at once.

---

## A4. Edit item — fields, blob, variants, photos

### A4a. Basic field edit

1. Open any item in **My Dex** → tap **Edit**.
2. Change condition, notes, and price paid.
3. Save.
4. Re-open — **expected:** all changed fields persisted correctly.

### A4b. Photo — main photo via camera

5. On an item without a photo yet, tap the photo/camera icon.
6. **Expected:** a "Save photo as" dialog appears with three options:
   **Main photo**, **Variation photo**, **Both**.
7. Take a photo via camera (tests `createCameraUri` → `EXIF` rotation
   correction via `androidx.exifinterface`).
8. Choose **Main photo**.
9. **Expected:** photo appears as the item's main image in both the detail
   screen and **My Dex** grid (`userPhoto` field set, distinct from
   `thumbnailBlob`).

### A4c. Photo — gallery picker (Session 6 Photo Picker)

10. On another item, tap the photo icon → **Choose from gallery**.
11. **Expected:** the system **Photo Picker** UI opens (NOT a permission
    dialog — Session 6 removed `READ_MEDIA_IMAGES`/`READ_EXTERNAL_STORAGE`).
    No "Allow access to photos" prompt should appear.
12. Pick an image.
13. **Expected:** "Save photo as" dialog appears (same as A4b), choose
    **Main photo** — image saved correctly.

### A4d. Variation photo & variant system

14. On an item, tap the photo icon → take/pick a photo → choose
    **Variation photo**.
15. **Expected:** a new variant entry is created (stored as base64 JSON on
    the parent doc's `variants` field), with its own photo, separate from the
    main item photo.
16. Open the variants list/editor on the detail screen.
17. **Expected:** the new variant shows with its photo, an editable **note**
    field, and an editable **price paid** field.
18. Edit the variant's note and price — save — re-open — **expected:**
    changes persisted.
19. Choose **Both** on a third photo — **expected:** sets the main photo AND
    creates a variant in one action.
20. **Mark variant only:** if there's a "mark as variant only" action
    (`markVariantOnly`), use it on the base item — confirm it correctly
    reclassifies without data loss (check what this actually does in your UI
    — likely marks the base item as not separately owned, only its variants
    are).
21. **Remove a variant** (`removeVariant`) — **expected:** variant disappears
    from the list, its photo is gone, but the **main item and other variants
    are unaffected**.

### A4e. Delete photo

22. On an item with a main photo, use **delete photo**
    (`viewModel::deletePhoto`).
23. **Expected:** photo removed, item reverts to placeholder/no-image state
    in My Dex; other fields (variants, text fields) unaffected.

### A4f. Critical regression check — edit doesn't wipe blobs

24. **This is the key Session 7 regression check** (Test 3 from the prior
    Session-D-specific plan): on an item WITH a thumbnail/photo/variants from
    steps above, edit only a text field (e.g. notes) and save.
25. **Expected:** thumbnail, user photo, AND all variants (with their photos)
    are still present after the save — `FunkoMapper.toDocument` using
    `existing?.toMutable()` correctly preserves blobs through
    `collection.getDocument`/`collection.save`.

**Pass:** All photo/variant operations (camera, gallery picker, main/variation/both,
variant note/price edit, remove variant, delete photo) work correctly, and an
unrelated field edit never wipes existing blobs or variants.

---

## A5. Delete item

1. Pick a test item in **My Dex**.
2. Delete it (swipe or detail-screen delete action).
3. **Expected:** disappears immediately from My Dex (live query listener).
4. If it had a price alert (Part A7) or variants, confirm no crash on
   Reports/alerts screens afterward.

**Pass:** Delete removes the item and all its data with no crash.

---

## A6. Collection screen — sort, filter, category filter

1. In **My Dex**, cycle through all **sort options**:
   - **Recently Added** (date added, descending)
   - **Name A–Z**
   - **Series** (franchise, ascending)
   - **Price Paid** (descending)
2. **Expected:** list re-orders correctly for each, no crash.
3. Use the **series/franchise filter chips** — select a specific franchise,
   confirm only matching items show; tap **All** to clear.
4. **Live category filter:** go to **Settings → Collection categories**
   (Part A8), disable a category containing one of your items, return to
   **My Dex** — **expected:** items in that category disappear immediately
   (live `combine()` of `enabledCategoryKeysFlow` + collection flow). Re-enable
   — items reappear.
5. Use the **search box** (if present on this screen) — type a partial item
   name, confirm filtering works alongside the franchise filter
   (`state.searchQuery` + `state.filterFranchise` combined filter logic).

**Pass:** All four sort options, franchise filtering, search, and live
category filtering all work correctly together.

---

## A7. Price alerts

1. Open a **want-list** item.
2. Tap the price-alert bell icon, set a target price, save.
3. **Expected:** bell shows "enabled" immediately; persists across
   navigation.
4. Disable the alert — bell flips back to disabled immediately.
5. **Worker check (optional):** set an alert with a target above the item's
   cached market price (should trigger). If there's a manual "run worker"
   debug option, use it; otherwise note this for the next scheduled run.
   **Expected on trigger:** notification appears, and re-opening the alert
   shows `lastTriggeredAt` = today.

**Pass:** Alerts save/load/toggle correctly with live UI updates; trigger
path (if exercised) updates `lastTriggeredAt` and posts a notification.

---

## A8. Category preference toggles

1. Go to **Settings → Collection categories**.
2. Toggle a single category off, then on — state updates immediately and
   persists across navigation.
3. If a "toggle whole genre" control exists, use it — all categories in that
   genre flip together.
4. If a "reset to defaults" option exists, use it — all categories return to
   enabled, no crash.
5. **Restart the app entirely** (force-close + relaunch).
6. **Expected:** preferences from steps 2–4 persist correctly — no re-seed
   silently overwrites user choices.

**Pass:** Individual/genre toggles and reset all work and survive a full
restart.

---

## A9. Reports screen

> Note: `ReportsScreen.kt` exists locally (referenced by
> `FunkoDexNavHost.kt`) but is **not present in the GitHub repo** — likely an
> uncommitted local file. Test against your actual local screen; the items
> below describe what the underlying data (`CollectionStats`/`SeriesSummary`)
> should produce regardless of exact UI layout.

1. Go to the **Reports** tab.
2. **Expected, derived from `CollectionStats`:**
   - Total owned count (including variant counts)
   - Total wanted count
   - Total paid (sum of `pricePaid` across owned items + variants)
   - Total retail value
   - Total market value
   - Number of unique franchises
   - "Most expensive item paid" and "highest market value item"
   - "Recently added" list (last 10 by date)
   - Breakdown by genre (`byGenre` map)
3. **Series completion:** per-franchise/category summaries showing
   `ownedCount` / `totalInCatalog` and a completion percentage
   (`completionPct`). Missing items (want-list + "missing original" flagged
   items) should be listed per series.
4. Add/remove an item, return to Reports — numbers should update (recomputed
   each time, not cached).

### A9b. Export from Reports (if `ExportButton` is wired in)

5. If there's an **Export collection** button, tap it.
6. **Expected:** a sheet with two options — **Excel workbook (.xlsx)** and
   **CSV spreadsheet (.csv)**.
7. Choose **.xlsx** — **expected:** "Building spreadsheet…" then "Opening
   share sheet…", and the share sheet opens with `FunkoDex_<date>.xlsx`
   containing **4 sheets**: Collection, Series Report, Want List, and Summary.
8. Choose **.csv** — **expected:** share sheet opens with
   `FunkoDex_<date>.csv` containing one row per owned item with columns:
   Name, Series, #, Category, Retail Price, Exclusive, Retailer, Vaulted.
9. Open both files (e.g. in Excel/Sheets after AirDrop/email to yourself) and
   spot-check that the data matches your collection.

**Pass:** Reports show correct, live-updating stats and series completion;
export (if present) produces correct .xlsx (4 sheets) and .csv files.

---

# PART B — Integrations

## B1. Channel3 API key

1. Go to **Settings → Diagnostics → Channel3 API** (in the "Lookup sources"
   list).
2. Tap it — dialog opens for entering the API key.
3. Enter a test key, save.
4. **Expected:** row now shows "Connected · UPC lookup · pricing".
5. **Force-close + relaunch** — confirm it still shows connected (Session 8
   AES/GCM round-trip via `SecureKeyStore.getChannel3Key()`/`hasChannel3Key()`).
6. Clear the key (if a clear option exists) — row reverts to "Not configured".

**Pass:** Channel3 key saves, persists across restart, and clears correctly.

---

## B2. HobbyDB OAuth link

1. Go to **Settings → Diagnostics → HobbyDB / Pop Price Guide**.
2. Tap to sign in — `OAuthLauncher.launch(context, OAuthProvider.HOBBYDB)`
   opens a browser/webview for HobbyDB OAuth.
3. Complete sign-in.
4. **Expected:** `OAuthCallbackActivity` receives the redirect, exchanges the
   code via `TokenRefreshManager`/`PkceHelper`, and `SecureKeyStore.setHobbyDbToken`
   stores `"accessToken|expireAtMs|refreshToken"`. Row updates to "Connected ·
   market pricing · vaulted status enabled".
5. **Force-close + relaunch** — confirm still connected
   (`isHobbyDbTokenValid()`/`getHobbyDbAccessToken()` decrypt correctly via
   the new AES/GCM `SecureKeyStore`).
6. Tap to disconnect (`viewModel.disconnectHobbyDb()`).
7. **Expected:** row reverts to "Not connected".

### B2b. Token refresh

8. If the access token is short-lived, wait for (or simulate) expiry and
   trigger a lookup that needs HobbyDB pricing.
9. **Expected:** `TokenRefreshManager` transparently refreshes the token
   (re-encrypts and re-saves via `setHobbyDbToken`) — no user-visible error,
   no `Cipher`/`KeyStore` exception in logcat.

**Pass:** HobbyDB OAuth connect/disconnect/persist-across-restart all work;
token refresh is transparent.

---

## B3. eBay OAuth link

1. Repeat B2 steps 1–7 for **eBay sold listings**
   (`OAuthProvider.EBAY`, `setEbayOAuthToken`/`getEbayOAuthToken`/
   `isEbayTokenValid`/`getEbayAccessToken`/`disconnectEbay`).
2. **Token timing note:** per `SecureKeyStoreTokenTest`, eBay tokens are
   ~2-hour lifetime with a 5-minute refresh buffer — if you can keep a session
   open that long, verify a lookup near the 5-minute mark triggers a refresh
   rather than failing.

**Pass:** eBay OAuth connect/disconnect/persist-across-restart all work.

---

## B4. Google Drive backup connection (Session 5)

1. Go to **Settings → Database → Connect Google Drive**.
2. Tap — `DriveAuthManager.authorize()` requests `DRIVE_FILE` scope via
   `AuthorizationClient` (NOT a sign-in screen — per the
   `CredentialManager_Migration_SPEC.md` decision, this is
   authorization-only).
3. **Expected:** either an immediate "Authorized" result, or a consent screen
   (`NeedsConsent` → launches a `PendingIntent` via
   `ActivityResultContracts.StartIntentSenderForResult`). Grant access.
4. **Expected:** row updates to "Connected · Tap to back up now", and
   `DriveBackupWorker.schedule(context)` is called (periodic worker armed via
   `LaunchedEffect(driveConnected)`, `ExistingPeriodicWorkPolicy.UPDATE`).
5. Tap the row again ("back up now") — **expected:** triggers an immediate
   backup-and-upload cycle. Check Google Drive (drive.google.com) for a new
   FunkoDex backup file in the app's Drive folder.
6. **Force-close + relaunch** — confirm still shows "Connected" (`isDriveConnected()`
   reads a plain boolean from `funkodex_secure_prefs_v2`, Session 8's new file).

### B4b. Lapsed grant (T-D3 — critical, from CredentialManager spec §9)

7. Revoke FunkoDex's access via your Google Account → Security → Third-party
   access (or wait for a natural token lapse).
8. Trigger a backup (manually or via the worker).
9. **Expected:** `DriveBackupWorker` catches the
   `GoogleJsonResponseException` 401/403, calls `clearToken()`, and posts a
   **"Reconnect" notification** (notification id 3002). Tapping it should lead
   back to the Drive connection flow in Settings.
10. **Expected:** the backup is retried after reconnection, OR skipped with
    no-retry if `NeedsConsent` and the user hasn't re-consented yet (per the
    worker's auth-state handling).

### B4c. Disconnect

11. Go to **Settings → Disconnect Google Drive**.
12. **Expected:** `DriveBackupWorker.cancel(context)` runs, `disconnectDrive()`
    clears the connected flag (no token to clear per spec §5.5 — Drive auth
    is authorization-only, no persisted token). Row reverts to "Connect Google
    Drive".

**Pass:** Drive connect/backup-now/disconnect work; lapsed-grant produces a
reconnect notification and graceful retry/skip; connection state persists
across restart via the new Session 8 prefs file.

---

## B5. Community contribution flow

1. Go to **Settings → Contribute to community database**, ensure toggle is
   **on**.
2. Scan or look up a UPC **not** in the local catalog.
3. If prompted to contribute, accept.
4. **Expected:** `ContributionRepository.saveContribution` creates a
   `contrib::<upc>` document (local save via Collection API, no error).
5. **HMAC signing check:** `HmacKeyStore.sign()` is called when
   `GitHubUploadWorker` runs — this depends on `getInstallId()`
   (Session 8 AES/GCM). Check logcat for `HmacKeyStore`/`SecureKeyStore` — no
   crypto exceptions, `getInstallId()` returns a stable UUID.
6. **Upload note:** per `HANDOFF.md`, the Cloudflare Worker isn't deployed
   yet — `GitHubUploadWorker`'s network call may fail. That's expected; what
   matters is the **local save** and **HMAC signing** both succeed without
   exceptions.
7. **Mark uploaded / delete pending:** if there's a manual "retry upload" or
   "discard" action for pending contributions, test
   `markUploaded`/`deletePendingContribution` — confirm `hasPendingContribution`
   correctly reflects the state afterward.

**Pass:** Contribution saves locally, HMAC signs without error using the new
Keystore-backed install ID; upload failure (Worker not deployed) doesn't crash
the app.

---

## B6. Catalog refresh worker (background)

1. Look for a manual "Check for updates"/"Refresh now" action in
   **Settings → Diagnostics** (the "Refresh now" button seen near the lookup
   sources section).
2. Tap it.
3. Watch logcat filtered on `CatalogRefreshWorker`:
   - `"Starting catalog refresh…"`
   - `"Refresh complete: N new catalog records added"` (N may be 0)
   - `"Community UPC file: N UPCs merged into catalog"`
   - If HobbyDB connected: `"Vaulted status updated: N items"`
4. **Expected:** no exceptions, especially no `Collection`/`Database` type
   errors (this worker creates its own `FunkoDexDatabase` instance per
   Session 7's conversion — 3 separate `db.getCollection()` calls across
   `refreshKennyChan`, `refreshCommunityUpcFile`, `refreshVaultedStatus`).
5. After it completes, search for a known recently-added Pop in **Check** to
   confirm new catalog data is queryable.

**Pass:** Refresh worker runs to completion (or graceful early-return on
network failure) with no Collection-API exceptions.

---

# PART C — Backup & Restore (do this LAST)

These tests modify/wipe your collection. Complete Parts A and B first so you
have meaningful data to back up.

## C1. Backup (export) — two paths

FunkoDex has **two** export entry points that both call
`dbTransferViewModel.exportDatabase()`:
- **Settings → Database → "Send to another phone"**
- **Settings → Backup → "Backup database"**

Both produce the same `FunkoDex_backup_<timestamp>.zip`.

1. Ensure My Dex has several items including ones with photos, variants, and
   a price alert.
2. Tap **Backup database**.
3. **Expected:** share sheet opens with `FunkoDex_backup_YYYYMMDD_HHmmss.zip`.
4. Verify the file landed in Downloads:
   ```
   adb shell ls /sdcard/Download/ | findstr FunkoDex_backup
   ```
5. Pull and inspect:
   ```
   adb pull /sdcard/Download/FunkoDex_backup_<timestamp>.zip .
   ```
   Open the zip — **expected:** single file `funkodex_backup.json`, a JSON
   array where:
   - Every entry has `"_id"` like `"funko::<uuid>"`.
   - No entries have `type: "catalog"` or `type: "system"`.
   - Items with thumbnails/photos have nested `{"_type":"blob", "contentType":
     ..., "data": "<base64>"}` objects.
   - Items with variants have the variants JSON string embedded as a field
     value.

**Pass:** Both backup entry points produce a correctly-structured zip with
all user data, correctly excluding catalog/system docs.

---

## C2. Restore (normal)

1. Add one throwaway item to My Dex (so post-restore state differs from the
   backup in C1).
2. Go to **Settings → Backup → Restore backup**.
3. Confirm the "Replace your collection?" warning.
4. Select the C1 backup zip.
5. **Expected:** "Importing…" → "Import successful!"
6. **My Dex now matches the C1 backup exactly** — the throwaway item from
   step 1 is gone; all C1 items (with photos/variants/alerts) are back.
7. **Catalog untouched:** search **Check** tab — catalog results still
   present (type="catalog" excluded from delete/restore).
8. **Category preferences untouched:** Settings → Collection categories — your
   A8 settings are unchanged (type="cat_pref" excluded).
9. **Price alerts restored:** re-open the item from A7 — alert state should
   match what was in the C1 backup.

**Pass:** Restore replaces user data exactly per backup; catalog and
category-preference docs untouched; alerts restored correctly.

---

## C3. Force restore — CRITICAL, highest priority

This wipes the **entire database including catalog**, then rebuilds from the
backup. Do this only after C1/C2 pass — you have the C1 backup as a safety
net.

1. Ensure the C1 backup zip is still available.
2. Go to **Settings → Backup → Force restore (corrupt database)**.
3. Read and confirm the "Wipe and rebuild from backup?" warning.
4. Select the C1 backup zip.
5. **Expected:** "Importing…" → success state. Logcat shows:
   `"Force restore: inserted N user documents. Catalog will reload on next
   start."` (N = item count in C1 backup).
6. **Force-close and relaunch the app** (not just background).
7. **Expected on restart:**
   - Catalog re-preloads from scratch (may take noticeably longer than
     normal — the `system::catalog_loaded` marker was wiped).
   - **My Dex shows exactly the items from the C1 backup** — same items,
     thumbnails, user photos, and variants as in C2's verification.
   - **Check** tab catalog search works again (post-reopen `Collection` is
     queryable).
   - **Settings → Collection categories** — all categories show **enabled**
     (re-seeded to defaults; `cat_pref` docs were wiped too).
   - Price alerts from C1 are restored (alert docs are user data, included
     in the backup).
8. **This is the core Session 7 risk check:** confirm no items are empty,
   duplicated, or showing stale pre-restore data — this validates that
   `liveCollection = db.getCollection()` obtained *after* `db.reopen()`
   correctly derives from the new `Database` instance
   (`getDatabase().defaultCollection`), not a stale reference.

**Pass:** Force restore wipes and rebuilds successfully; on restart, catalog
re-preloads, all user data from the backup is present and correct, category
preferences re-seed to defaults. **This is the single most important test in
the entire plan.**

---

# PART D — Automated / Code-Level Tests

## D1. Enriched catalog import

### D1a. Small test file (all code paths in one file)

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

This exercises five paths in one import:
- **Record 1**: if `spider-man-no-way-home-pop` exists in the catalog →
  **merge path** (enriches with pricing/image/popType fields, never overwrites
  title/handle/imageUrl/seriesList). If not found → inserted as new.
- **Record 2**: guaranteed-new handle → **insert path**, new
  `catalog::totally-new-test-item-2026` doc.
- **Record 3**: funko.com page-filename handle (`92345.html`) → exercises
  `FUNKO_PAGE_HANDLE` regex repair, slugified to
  `catalog::page-handle-repair-test-item`.
- **Record 4**: title contains "shirt" → `isStandardPop`/`NON_POP_TITLE`
  filter should **skip** it.
- **Record 5**: blank title → skipped.

Push to device:
```
adb push test_enriched.json /sdcard/Download/test_enriched.json
```

1. Go to **Settings → Catalog → Import Enriched Catalog**.
2. Select `test_enriched.json` from Downloads.
3. **Expected:** "Importing catalog…" progress dialog, finishes almost
   instantly for 5 records.
4. **Expected result dialog ("Import complete"):**
   - "**X** existing records updated" — **1** if record 1 matched an existing
     catalog doc, else **0**.
   - "**X** new records added" — **2** (records 2 + 3) if record 1 matched,
     or **3** (records 1 + 2 + 3) if record 1 didn't match.
   - "**2** records skipped (non-Pop or missing handle/title)" — records 4 + 5.
   - **0 errors**.

5. **Verify in app:**
   - Search "Totally New Test Item" in **Check** — found (insert path,
     `collection.save`).
   - Search "Page-Handle Repair Test Item" — found (slug repair worked).
   - Search "Test Branded T-Shirt" — **not found** (non-Pop filter worked).
   - If record 1 matched: open "Spider-Man (No Way Home)" — confirm enriched
     fields (funkoNumber 1118, popType, market values, PriceCharting IDs)
     appear, and **title/image were NOT overwritten**.

### D1b. Full enriched file (optional)

6. If you have the real `funko_data_enriched.json` (~17,500 records) from the
   enricher pipeline, push and import it the same way.
7. **Expected:** progress bar advances in batches of 500, completes with a
   plausible enriched/added/skipped breakdown and **0 errors**.
8. Spot-check a few well-known Pops afterward for enriched pricing data.

**Pass:** Small test file produces exactly the expected counts; merge/insert/
slug-repair/non-Pop-filter/blank-title paths all behave correctly. Large file
(if tested) completes with 0 errors.

---

## D2. Unit test suites

```
gradlew test
```

**Expected — `BUILD SUCCESSFUL`, all green:**

- **`FunkoMapperTest`** (9 tests) — `FunkoMapper.toDocument`/`fromDocument`
  round-trip. A regression here would indicate a type mismatch from the
  Collection API migration (Session 7).
- **`CollectionStatsTest`** (11 tests) — `CollectionStats`/`SeriesSummary`
  computation. Confirms `getCollectionStats()`'s output shape is unchanged.
- **`FunkoLookupServiceTest`** (8 tests) — catalog search logic
  (`searchLocalByName`, `DataSource.collection` conversion).
- **`SecureKeyStoreTokenTest`** — pure string-parsing for
  `"accessToken|expireAtMs|refreshToken"`. Unaffected by Session 8's storage
  change (parsing-only), should still pass.
- **`ScannerViewModelStateTest`** — scanner state machine (manual selection,
  bulk add confirm flow, `ScanState.Saved`).
- **`PkceHelperTest`** — PKCE code-verifier/challenge generation for OAuth.

If any of these require an actual Couchbase Lite instance (instrumented test
under `app/src/androidTest` rather than `app/src/test`), run:
```
gradlew connectedAndroidTest
```
on a connected device. Per the current file layout, all six are under
`app/src/test`, so `gradlew test` (JVM) should cover everything.

**Pass:** `gradlew test` → `BUILD SUCCESSFUL`, all tests green.

---

## D3. SecureKeyStore — direct round-trip verification (Session 8)

This is covered indirectly by B1–B3, but if you want an isolated check
without going through full OAuth flows:

1. After completing B1 (Channel3 key set), force-close and relaunch.
2. Confirm `hasChannel3Key()` still returns true (UI shows "Connected").
3. Check logcat for any `javax.crypto.AEADBadTagException`,
   `KeyPermanentlyInvalidatedException`, or `KeyStoreException` — none should
   appear.
4. **Optional adb-level check** (requires root or a debug build with
   file access): confirm `funkodex_secure_prefs_v2.xml` exists under
   `/data/data/com.funkodex/shared_prefs/` and contains values in
   `base64:base64` format (not plaintext, not the old EncryptedSharedPreferences
   format). The old `funkodex_secure_prefs.xml` should also still exist
   (abandoned, untouched, still encrypted with the old scheme) — confirming
   no migration/deletion occurred, per the Session 8 design decision.

**Pass:** New prefs file uses the `iv:ciphertext` base64 format; old prefs
file is present but unused; no crypto exceptions across restart.

---

# PART E — 16 KB Emulator Regression

Using the **16 KB Page Size Google Play Intel x86_64 Atom** emulator (Pixel
10, API 37.0) from Session A:

1. Install the current build.
2. **A1** — first launch / catalog preload.
3. **A3** — manual search + add an item.
4. **A4b/A4f** — add a photo, edit an unrelated field, confirm photo
   preserved.
5. **C1** — one backup export (don't need the full restore cycle here).
6. **Expected:** no crashes, no `SIGSEGV`/`SIGBUS` in logcat, performance
   acceptable (may be slightly slower per Session A's notes).

**Pass:** Condensed smoke test passes with no new crashes vs. Session A's
baseline.

---

# Summary Checklist

## Part A — Core
- [ ] A1. First launch & catalog preload
- [ ] A2. UPC scan add (collection + want list) + offline pending queue
- [ ] A3. Manual search bulk-add + batch scan sheet
- [ ] A4. Edit: fields, main photo (camera), gallery picker, variation/both
      photo, variant note/price edit, remove variant, delete photo, and the
      critical "edit doesn't wipe blobs" regression check
- [ ] A5. Delete item
- [ ] A6. Sort (4 options) + franchise filter + search + live category filter
- [ ] A7. Price alerts (create/disable/persist; trigger optional)
- [ ] A8. Category preferences (toggle/genre/reset, restart-persist)
- [ ] A9. Reports screen stats + series completion + export (.xlsx 4-sheet, .csv)

## Part B — Integrations
- [ ] B1. Channel3 API key (set/persist/clear)
- [ ] B2. HobbyDB OAuth (connect/disconnect/persist/refresh)
- [ ] B3. eBay OAuth (connect/disconnect/persist)
- [ ] B4. Google Drive (connect/backup-now/disconnect, lapsed-grant T-D3,
      persist across restart)
- [ ] B5. Community contribution (local save + HMAC sign)
- [ ] B6. Catalog refresh worker (manual trigger, 3-function log sequence)

## Part C — Backup/Restore (run last)
- [ ] C1. Backup export (both entry points) + zip content verification
- [ ] C2. Restore (normal)
- [ ] C3. **Force restore — highest priority overall**

## Part D — Automated
- [ ] D1a. Enriched import — small test file (all 5 paths)
- [ ] D1b. Enriched import — full file (optional)
- [ ] D2. `gradlew test` — all unit suites green
- [ ] D3. SecureKeyStore format/exception check

## Part E — 16 KB regression
- [ ] E1. Condensed smoke test on 16 KB emulator
