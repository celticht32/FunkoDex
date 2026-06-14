# FunkoDex — On-Device Test Plan
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

## Results Log

| Test | Result | Notes |
|------|--------|-------|
| 1. First launch / catalog preload | PASS (VM) | Verified on emulator — splash-gated preload completes, Collection loads, manual search returns catalog results. No physical-device dependency. |
| 2. UPC scan — add screen | PASS | 2026-06-13. Verified on device (Galaxy S23, SM-S911U). Scanned a physical box barcode from the add flow; camera opened, barcode read, catalog match found and item added (e.g. Tinker Bell scanned in successfully). |
| 3. UPC scan — edit screen | PASS | 2026-06-13. Verified on device (Galaxy S23, SM-S911U). Scanned a barcode from an existing item's edit screen; camera opened, barcode read, UPC set on the record. (UPC field is now scan-only — manual entry removed.) |
| 4. Manual search keyboard dismiss | PASS (VM) | Verified on emulator — keyboard dismisses on Done, results render, list selectable. No physical-device dependency. |
| 5. Check screen | | |
| 6. Fetch from catalog | | |
| 7. App performance | PASS | 2026-06-13. Verified on device (Galaxy S23, SM-S911U). Performance acceptable — responsive in normal use, no notable jank observed. |
| 8. Send to another phone | | |
| 9. Enriched catalog import | PARTIAL PASS | 2026-06-13. Import confirmed: 14,314 total in batches of 500; first run 13,585/725/4/0; re-import idempotent (0 added/~14,310 updated). Net-new enriched-only item confirmed via "perpetua" → "Papa V Perpetua" after fixing a catalog-search filter bug (enabled-categories key/name mismatch was silently dropping all results). Existing-item integrity: re-import left existing records unchanged (pass condition met). NOTE: the enriched catalog does NOT survive an app uninstall — `uninstallDebug` wipes it and reinstall re-preloads only the base Kenny Chan set, so enriched-only items (e.g. Perpetua) disappear until the enriched import is re-run manually. The "Twinkie the Kid" image spot-check surfaced a data issue, not a code issue: one duplicate Twinkie variant is linked to a dead/wrong HobbyDB URL (a "Shirts and Jackets" apparel image returning 404 NoSuchKey) — not fixable by the app; that record needs deletion or a manual photo. **Still not verified:** the "Peacemaker on Peacecycle" / `91991.html`→`peacemaker-on-peacecycle` handle-repair spot-check. |
