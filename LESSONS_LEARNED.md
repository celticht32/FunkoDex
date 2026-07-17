# FunkoDex — Lessons Learned

Practical lessons from building a production Android app entirely in Claude.
These apply to any medium-to-large Android project developed in an AI-assisted workflow.

---

## Architecture

### 1. Split global metadata from personal data from day one
We retrofitted the `catalog::` / `funko::` document split in Phase A. Had we designed
it upfront, every phase would have been cleaner. The rule: if a field is true for all
users (product name, retail price, image URL), it belongs in the catalog doc. If it's
about what *this user* has done (owned/wanted, price paid, notes), it belongs in the
personal doc. Mixing them creates both privacy and upload safety problems.

### 2. WorkManager workers need @HiltWorker from the start
If you add Hilt injection to a Worker later, you must also add `HiltWorkerFactory` to
`FunkoDexApp` and implement `Configuration.Provider`. Missing this causes a silent
injection failure at runtime. Add it when you write the first Worker.

### 3. Couchbase inBatch{} for all bulk writes
Individual `collection.save(doc)` calls inside a loop are extremely slow — each one
acquires and releases the write lock. Wrap any loop that writes multiple documents in
`database.inBatch { }` (the batch wrapper stays on `database` even after the Session 7
Collection API migration — only per-document calls like `save`/`getDocument`/`delete`
moved to `collection`). For the 23,940-record Kenny Chan preload, this is the
difference between 3 seconds and 3 minutes on first launch.

### 4. Phase ordering matters
We built: Security → Schema split → Data services → UI features → Community upload.
This order was right. Trying to do community upload before the schema split would have
meant uploading personal data accidentally. Trying to do the price service before
fixing the refresh worker stub meant testing against stale catalog data.

---

## Security

### 5. Never put secrets in BuildConfig — enforce this from day one
`buildConfigField` values end up as plaintext string constants in `classes.dex`.
JADX finds them in under 60 seconds. The correct approach from the first commit:
- API keys → user-entered, stored via `SecureKeyStore` (direct AES-256-GCM
  AndroidKeyStore wrapper — see lesson 20 below for why this replaced
  AndroidX security-crypto's `EncryptedSharedPreferences`)
- Tokens → Android Keystore for hardware-backed storage
- `allowBackup="false"` in manifest from day one (prevents adb backup extraction)
- `network_security_config.xml` from day one (HTTPS-only, no cleartext traffic)

### 6. The Cloudflare Worker proxy is the right pattern for community uploads
Any token embedded in an APK can be extracted. The correct model for community data
contributions: Phone → HMAC-signed HTTPS → Cloudflare Worker → GitHub API. The
Worker holds the GitHub PAT as a Cloudflare Secret. The phone holds an HMAC key in
the hardware-backed Android Keystore. Even if the HMAC key were extracted (not possible
from hardware Keystore), the Worker enforces schema validation and rate limiting
(50 contributions/device/day), so the damage ceiling is minimal.

---

## Data

### 7. Kenny Chan data has zero UPC codes
This is a critical architectural fact. The Kenny Chan dataset has names and images but
no UPCs. Every first-time UPC scan requires a network call to Channel3. The local
dataset is for **name search only**. Design the scanner to expect network on first scan.

### 8. GS1 UPC check digit algorithm
Even-indexed digit positions (0, 2, 4, 6, 8, 10) are multiplied by 3.
Odd-indexed (1, 3, 5, 7, 9) are multiplied by 1.
Sum modulo 10, subtract from 10, modulo 10 again = check digit (last digit).
The inverse (even×1, odd×3) is wrong and passes most test cases by coincidence
because common UPC ranges happen to satisfy it — but fails on real Funko UPCs.

### 9. HobbyDB image URLs end in _large
All 23,940 Kenny Chan image URLs end in `_large.jpg`. The `_small` and `_medium`
variants may not exist on the CDN (HEAD requests return 403). Store `_large` as the
Couchbase Blob — it's 150–300KB per item and gives full-quality offline images.

---

## SVG / Splash screen

### 10. Inkscape SVG viewBox pattern
For an Inkscape SVG with `viewBox="0 0 116.99 108.79"` and a layer
`transform="translate(-46.3,-94.2)"`, the correct HTML/Android canvas rendering is:
```
viewBox="0 0 116.99583 108.79025"   ← 0,0 origin matching the natural bounding box
<g transform="translate(-46.302082,-94.191666)">   ← Inkscape layer translate
  <path d="..." />   ← original path coordinates unchanged
</g>
```
Do NOT use the raw path coordinates (starting at ~129, 174) as the viewBox origin —
this puts the content outside the viewport. The `viewBox` must start at `0 0`.

---

## Development workflow

### 11. Scan for errors after every phase, not just at the end
We caught `refreshCommunityUpcFile()` being placed outside the class body during the
final cleanup scan. Had we scanned after Phase F, it would have been a one-file fix.
Run the brace-balance check and stale-field scan after every phase.

### 12. One method extraction unblocks everything (CatalogMapper)
The `CatalogRefreshWorker` was a stub for the entire development cycle because
`mapRecord()` was private inside `CatalogPreloader`. Extracting it to a shared
`CatalogMapper` object in Phase A1 took 150 lines and unblocked the refresh worker,
the community file download, and the contribution upload — all in one change.
Identify private method bottlenecks early.

### 13. Document all stubs immediately with a phase label
Every stubbed method had a comment like `// Phase B1 — write real implementation`.
This made the status board accurate and prevented accidentally shipping stubs.
The pattern: write the method signature + a realistic return value + the comment,
never `TODO()` which crashes at runtime.

### 14. Keep help text in a central file
`HelpContent.kt` has all in-app help strings as constants. This means:
- Easy to update copy without touching screen logic
- Easy to extract for localisation later
- Easy for a copywriter to review in one place
Never inline help strings directly in composables.

---

## Dependency management

### 15. Add `multiDexEnabled = true` before the Drive API
The Google Drive API client has a large transitive dependency graph that can exceed
the 64K DEX method limit. Add `multiDexEnabled = true` to `defaultConfig` in
`build.gradle.kts` before adding the Drive API dep, not after hitting the build error.

### 16. `hilt-work` + `hilt-work-compiler` must both be added together
`@HiltWorker` requires `androidx.hilt:hilt-work` at runtime AND
`androidx.hilt:hilt-compiler` as a KSP processor. Adding one without the other
produces a cryptic injection failure at Worker creation time.

### 17. ExifInterface is not in AndroidX Core
`androidx.exifinterface:exifinterface` is a separate dependency. Without it, user
photos taken in portrait mode display sideways. Add it alongside any camera feature work.

### 30. Pin version-sensitive API symbol names against the pinned dependency — never infer from memory
Compose/Material 3 rename and re-signature public symbols across releases, so a name
that is correct in one version fails to resolve in another. Real example from this
project: the `Modifier.menuAnchor()` no-arg overload was deprecated and replaced by a
typed overload. In **material3 1.3.0** (this project's pin, via Compose BOM 2024.09.00)
the type is **`MenuAnchorType.PrimaryNotEditable`**; the symbol was later renamed to
`ExposedDropdownMenuAnchorType` in 1.4.0+. Writing the 1.4.0 name against the 1.3.0 pin
produced `Unresolved reference 'ExposedDropdownMenuAnchorType'` at compile time.

Rule: before writing any version-sensitive symbol (Compose APIs, enum names, method
signatures, library classes), pin the exact name against the version actually in use.
Check the project's own existing usage first — it is ground truth for these versions —
then the versioned API docs. If a symbol can't be verified against the pin, flag that
line rather than guessing. Pinned stack: material3 1.3.0 (BOM 2024.09.00), Kotlin 2.0.21,
AGP 8.13.2, Gradle 8.13.

Corollary — deprecated APIs: don't leave a deprecated call in place "because it still
compiles." This project does not set `allWarningsAsErrors`, so a deprecation is only a
warning today — but the deprecated symbol gets *removed* in a later version, turning that
warning into a hard `Unresolved reference` on the next dependency bump. Migrate to the
current replacement when you touch the code. The catch is that the migration itself is
where versions bite: the no-arg `Modifier.menuAnchor()` is deprecated, and its typed
replacement is `MenuAnchorType.PrimaryNotEditable` in **1.3.0** but renamed to
`ExposedDropdownMenuAnchorType` in 1.4.0+ — so fixing the deprecation requires pinning the
replacement name to the same version (per the rule above), not just adopting the newest
name you've seen. Net: clear the deprecation, but verify the replacement against the pin.

---

## Testing

### 18. GS1 check digit is worth a unit test
It's easy to invert the multipliers (×1 and ×3 swapped) in a way that passes most
test cases because common UPC prefixes happen to be forgiving. Write a unit test
against at least 5 known real Funko UPCs before shipping the quarterly rebase tool.
Real Funko UPCs to test: 889698115810, 889698130653, 849803052188.

---


---

## Security (continued)

### 19. PKCE OAuth for mobile — no client secret in the APK
For OAuth on mobile, always use PKCE (RFC 7636). The standard OAuth flow requires a
client_secret which would be embedded in the APK and extractable by JADX. PKCE replaces
this with a one-time code_verifier generated on-device and a SHA-256 challenge. The
provider verifies the challenge without needing a static secret. Implementation:
`PkceHelper.generateVerifier()` → `PkceHelper.challenge(verifier)` → include challenge
in auth URL → receive auth code → exchange code+verifier for token.

### 20. Store the OAuth install ID via Keystore-backed encryption, not plain prefs
The community contribution rate-limit uses an anonymous install UUID sent as X-Device-ID.
Original implementation used `context.getSharedPreferences("funkodex_meta", MODE_PRIVATE)`
which writes a plaintext XML file readable via `adb shell`. Even though the UUID is not
a credential, consistency with the rest of the security model demands encrypted storage.
At the time this was written, that meant `EncryptedSharedPreferences` (AndroidX
security-crypto) — the docstring said "EncryptedSharedPreferences" but the code used
plain prefs, a comment mismatch that the security audit caught. Session 8 later
replaced `security-crypto` entirely with `SecureKeyStore` (direct AES-256-GCM
AndroidKeyStore wrapper); the install ID now lives there
(`SecureKeyStore.getInstallId()`, key `community_install_id`). The underlying lesson
stands regardless of mechanism: keep code and comments consistent, and never store
even non-credential identifiers in plaintext SharedPreferences.

### 21. Validate deep-link extras before navigating
`intent?.getStringExtra("NAVIGATE_TO_ITEM")` can be sent by any app. Without validation,
a malicious app could cause FunkoDex to navigate to arbitrary routes. A simple prefix
check (`it.startsWith("funko::") && it.length in 14..60`) eliminates the attack surface
at zero cost. Apply this pattern to any Intent extra that drives UI state.

### 22. Guard every nm.notify() on Android 13+ (POST_NOTIFICATIONS)
Three separate places in the codebase called `nm.notify()` without first checking
`POST_NOTIFICATIONS` permission. On Android 13+ the call silently fails — users never
see price alerts or backup confirmations. Pattern to follow everywhere:
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
}
```

---

## Logging

### 23. Replace all Log.x() calls with a central logger from day one
`android.util.Log` has no level gating for file output — everything or nothing. By wrapping
in `FunkoDexLogger` from day one you get: configurable verbosity persisted in DataStore,
async rotating file output (`filesDir/logs/`), and a single share button in Settings for
support. The refactor across 57 call sites late in the project was mechanical but
preventable. Set up the logger in Phase 1.

### 24. CrashHandler must be the absolute first thing in Application.onCreate()
Exceptions thrown during Hilt injection, CouchbaseLite init, or DataStore reading crash
the app silently in production with no log trail. `Thread.setDefaultUncaughtExceptionHandler`
must be called before any of those subsystems are initialised. Call `CrashHandler.install(this)`
as line 1 of `onCreate()` — before `super.onCreate()` if possible. The handler writes to
`filesDir/logs/crash_TIMESTAMP.log` and then delegates to the previous handler so the
system crash dialog still appears.

---

### 25. Refresh tokens need proactive keep-alive, not just on-demand refresh
OAuth access tokens (2 hours for eBay) are handled fine by on-demand refresh before each API call.
But refresh tokens have their own expiry — 18 months for eBay, similar for HobbyDB. If a user
installs the app, signs in, then barely uses it for months, the refresh token itself expires.
The next API call fails even though `TokenRefreshManager` works correctly, because there is nothing
left to refresh with. Solution: a weekly `@HiltWorker` (`TokenKeeperWorker`) that proactively
calls `getValidHobbyDbToken()` and `getValidEbayToken()`. It runs in the background whether or
not the user opens the app, keeping both provider sessions alive indefinitely. Uses KEEP policy
so the weekly interval is not reset on every app launch. This is a lesson in thinking beyond
the happy path: the token refresh code is correct, but the system needs a heartbeat to stay alive.

---

## Data Parsing

### 26. Gson reflective `TypeToken<List<DataClass>>` can fail on Kotlin data classes for reasons unrelated to field nullability
Importing `funko_data_enriched.json` (14,314 records) via
`gson.fromJson(json, TypeToken<List<EnrichedRecord>>)` threw `java.util.ArrayList
cannot be cast to java.lang.Void` on every attempt, on-device. The natural
suspect is a nullable generic field (`series: List<String>?`), matching a
known Gson/Kotlin issue (KT-41176). That theory was tested and falsified: both
the nullable and a non-nullable (`List<String> = emptyList()`) variant of the
field parsed correctly under a standalone Gson 2.11.0 build (compiled from
source, plain Java reflection) against the same JSON. The minified-build R8
explanation was also ruled out — the failure occurred on a `debug` build
(`isMinifyEnabled = false`).

The actual trigger remained Kotlin-bytecode-specific and unreproducible
without `kotlinc` (not obtainable through the project's network allowlist).
Rather than continue isolating it, the fix bypassed Gson's reflective
`TypeAdapter` entirely: parse the JSON tree (`JsonParser` → `JsonArray` →
`JsonObject`) and map each object to the data class via explicit
`optString`/`optBoolean`/`optStringList` extension functions. This is more
verbose but removes an entire class of Gson-reflection-vs-Kotlin-metadata
failure modes for any future enriched-data field changes. **Lesson:** when a
reflective JSON-binding error doesn't reproduce in isolated tests of the
"obvious" field, don't keep tuning field types — switch to tree parsing for
data-import paths where the schema is externally controlled (here, by
`enrich.js`) and may drift.

### 27. A derived display field (`category`) must only ever hold values from its own enum/taxonomy
`CatalogMapper.mapRecord` derived `category` as "the first series tag starting
with `Pop!`". For HobbyDB-sourced records this is almost always a real
category ("Pop! Disney", "Pop! Music"). But funko.com-sourced records
(729 of them, from the enriched-import net-new path) carry series like
`["Pop! Vinyl", "Music"]` — `"Pop! Vinyl"` is a *format* descriptor (every
standard Pop is "Pop! Vinyl"), not a category, and doesn't appear anywhere in
`FunkoCategories.ALL`. 714 records got `category = "Pop! Vinyl"` stored.

Separately, `FunkoLookupService.searchByName`'s category filter compared
`item.category.contains(key)` where `key` is a normalized slug (`pop_music`)
and `item.category` is the display string (`"Pop! Music"`) —
`"Pop! Music".contains("pop_music")` is always `false`. This silently dropped
**every** search result whose category was non-empty, masked because most
manual testing queries happened to hit items with empty categories or fall
through other paths. Combined, the 714 mis-categorized records were
unsearchable and invisible to the bug until a specific net-new record
("Papa V Perpetua") was searched and returned zero results.

**Lessons:** (1) when a derived field's value space should be a closed set
(here, `FunkoCategories.ALL`), exclude known-non-member values explicitly at
the point of derivation — mirror any existing exclusion list for a sibling
field (`primarySeries` already excluded `"Pop! Vinyl"`; `category` did not).
(2) Any comparison between a normalized key (`pop_music`) and a display string
(`"Pop! Music"`) must go through the same canonical normalizer
(`FunkoCategories.toKey()`) on both sides — never raw `contains()`. (3) A
merge/upsert path that already has access to "existing doc + freshly-parsed
record" is the cheapest place to add a self-healing repair for previously
mis-written derived fields — no migration script or catalog wipe needed, the
fix applies the next time the import runs.

### 28. A resolved/display value and a persisted/aggregated value are not the same write
`DetailViewModel.refreshPrices` fetched and resolved a price, updated
`_priceState` so the Detail screen's "Market Price" card showed Market avg
$37.94 / Retail $26.93 — but never called `repository.saveItem(...)`. The
item's persisted `marketAvg`/`retailPrice` stayed `0.0`, so
`CollectionStats.totalMarketValue`/`totalRetailValue` (Reports) were always
$0.00 even while the Detail screen displayed correct numbers. **Lesson:**
when a value is both shown on one screen *and* summed/aggregated on another,
trace both the display write (UI state) and the persistence write (saved
document) as separate steps — a refresh flow that updates only the former
will pass a visual check on the screen it was tested on while silently
leaving every aggregate wrong.

### 29. A "best resolved" fallback value must not feed back into the tier/source field that gates it
`PriceService.fetchPrice` checks `item.retailPrice > 0` as Tier 1
("Funko retail (catalog)", `staleDays = 30`) and short-circuits the entire
waterfall — eBay/Channel3/HobbyDB are never tried again once `retailPrice` is
set. The natural-seeming fix for "Total Retail Value is $0.00" — write the
UPCitemdb-resolved retail into `retailPrice` — would have permanently
disabled the price waterfall for that item and mislabeled a marketplace
figure as catalog MSRP. **Lesson:** before writing a "resolved" value back
into a field that also acts as a *gate* or *source-of-truth flag* elsewhere,
check every read site of that field, not just the one you're trying to fix.
Here the fix was a new field (`resolvedRetail`) + a derived
`effectiveRetail` getter, keeping the gate field (`retailPrice`) reserved for
its original catalog-only meaning.

### 30. `Icons.AutoMirrored.*` requires its own import, not just the `filled.*` wildcard
Replacing `Icons.Default.ArrowBack`/`HelpOutline` with the
`Icons.AutoMirrored.Filled.*` equivalents (to fix the "use the AutoMirrored
version" deprecation warning) produced a *new* compile error — "Unresolved
reference... receiver type mismatch: val Icons.Filled.ArrowBack: ImageVector"
— because `Icons.AutoMirrored` lives in package
`androidx.compose.material.icons.automirrored.filled` and is not covered by
the existing `import androidx.compose.material.icons.filled.*`. Each call
site needs its own
`import androidx.compose.material.icons.automirrored.filled.<IconName>`.
**Lesson:** an AutoMirrored icon swap is a two-line change (usage + import),
not one — verify the import before declaring the deprecation fixed.

## Pricing & lifecycle (Session 11)

### 31. `Int.MAX_VALUE` as a "never" duration overflows `LocalDate.plusDays()` and throws
A `MANUAL`/`USER_PAID` price source was given `staleDays = Int.MAX_VALUE` to
mean "never stale." But `PriceSnapshot.isStale` computes
`fetchedAt.plusDays(staleDays.toLong())`, and `LocalDate.plusDays(2147483647)`
exceeds the max supported year and throws `DateTimeException`. That made
`isStale` throw, `getResolvedPrice`'s `.filter { !it.isStale }` discard the
snapshot, the price resolve to 0, and a refresh overwrite the user's manually
entered market value with nothing. **Lesson:** never use `Int.MAX_VALUE` (or
any value near it) as a day/month/year count fed into `java.time` arithmetic.
Use a large *finite* value (e.g. 36,500 days ≈ 100y) AND defensively cap the
horizon at the computation site so no future source can re-trigger it.

### 32. CameraX preview goes black after screen-off; bind once is not enough
The scanner bound the camera once inside `AndroidView`'s `factory` block. When
the screen sleeps (lifecycle ON_STOP) the `PreviewView` surface is torn down,
and on resume the existing binding does not re-attach → black preview until the
user leaves and re-enters the screen. **Lesson:** for a CameraX preview in
Compose, hold the `PreviewView` in `remember` and add a
`DisposableEffect(lifecycleOwner)` that re-runs the bind on
`Lifecycle.Event.ON_RESUME` (and removes the observer in `onDispose`).
`bindToLifecycle` alone does not survive a surface teardown.

### 33. eBay retired the price RSS feed; the HTML scrape works, the *fetch* is the risk
The completed-listings RSS feed (`_rss=1`) is retired; the app scrapes the
sold-listings HTML (`s-card__price` spans). **Session 12 correction to the earlier
"treat as unreliable" verdict:** the *parser* is not the problem. Verified against a
real captured sold-listings page (Pepe #1678) — 67 price spans matched, 57 valid
after filters, sensible median. The 403s seen in logs are a *fetch-time* bot
challenge against datacenter/test IPs, not a parse failure; from a real device on a
residential connection the request may well succeed. **Lesson:** when a scrape
"fails," separate the fetch from the parse before concluding it's broken — capture
the raw response and run the parser against it offline. A saved-page test is the
cheapest way to tell "they changed the markup" (parser bug, fix it) from "they
blocked my IP" (fetch bug, different fix). The official Browse API returns only
*active* listings (not sold comps), so it's not a drop-in replacement.

### 34. Close every OkHttp `Response` — especially on the error-return path
`PriceService` did `.execute()` then `response.body?.string()` and returned early on
`!isSuccessful` without ever closing the response, leaking the connection. The
success path happened to close the body (`.string()` reads to completion) but the
error path didn't. **Lesson:** wrap the response in `.use { response -> ... }` so it
closes on every exit, including early returns and exceptions. Reading `.string()` is
not a substitute for closing. `FunkoLookupService` already did this — match it.

### 35. An executor created per lifecycle event with no shutdown leaks a thread
The camera screens created `Executors.newSingleThreadExecutor()` for the barcode
analyzer inside the camera-start path — which in `ScannerScreen` ran on every
`ON_RESUME` — and never called `shutdown()`. Each background/foreground cycle
leaked a live thread. **Lesson:** a resource with a lifecycle (executor, listener,
scope) created in a composable must be owned by that composable: create it once in
`remember`, release it in `onDispose`. Don't create it inside an effect that re-runs.

### 36. Price a variant against its own listings, not the common version
A name search like "funko pop Pepe 1678" returns the common *and* the chase/exclusive
in one bucket; the median is dominated by whichever is more common (usually the common
one), badly under-pricing a valuable variant — and no after-the-fact statistical trim
can separate them. **Lesson:** fix it upstream in the query, not downstream in the
stats. Append the variant's distinguishing terms (chase, retailer + "exclusive") to
the *name* query so the result set is the right population. UPC-keyed lookups don't
need this (a UPC is already variant-specific) — unless the variant shares the common
UPC, in which case no query change helps and it's a data-model limit.

---

## Data quality & pipelines (Session 22)

### 37. Verify values, not presence
A check that asks "is this field populated?" will pass on garbage. A merge script
reported `funkoNumber present: YES` on a record whose number belonged to a completely
different Pop — the enricher had stamped a wrong match's identity onto it, so every
field *was* filled, just wrong. The check was worse than useless: it produced
confidence. **Lesson:** compare the actual value against something independent (the
record's own title, a URL slug, a physical box) or don't claim verification.

### 38. Fill-if-blank silently preserves wrong data
`if (blank(field)) field = source.field` looks reasonable and is exactly wrong when the
target holds *corrupt* data rather than *missing* data — the guard sees "populated" and
skips, keeping the bad value. **Lesson:** when a known-good source exists, decide
per-field whether it is authoritative (overwrite) or supplementary (fill-if-blank), and
print every overwrite so nothing changes silently.

### 39. Blank beats wrong; a guess is worse than both
When a value is known-wrong but the right one can't be *verified*, blank it. A blank
re-resolves on the next pipeline run and is visibly absent in the UI; a plausible guess
is indistinguishable from real data forever. 22 mis-matched records were blanked rather
than hand-corrected because correcting meant sourcing 22 numbers that couldn't be
verified. **Lesson:** the exception is data read off physical packaging or a retail
listing — that's verified, not guessed, and should be written. (DEC-025)

### 40. Substring matching silently matches the wrong thing
`rowName.includes("ram")` matched **"Bram Stoker"**. `includes("poe")` matched **"Poet
Anderson"**. `includes("will")` matched **"Chilly Willy"**. The gate around it was
well-designed — variant tokens, exclusion rules, an approximate fallback — and one
`includes()` at the bottom undermined all of it. It shipped for months and surfaced only
because the user recognised a figure he owned. **Lesson:** when comparing names,
tokenise and compare whole words.

### 41. Corruption from a bad match is self-consistent
The first mis-match detector compared a record's `funkoNumber` against the number in its
`pricechartingUrl`, expecting disagreement on a bad match. They never disagree — the
enricher writes *both* from the same wrong match. **Lesson:** when hunting bad data,
compare against something the corrupting process didn't touch (here: the record's own
title, which enrich never overwrites).

### 42. Some errors are only visible to a human who knows the domain
"Evil Queen (Snow White Stained Glass)" was matched to "Snow White & Evil Queen" — every
meaningful word overlaps. No string comparison distinguishes them; you have to know one
is a Deluxe single and the other a Pop! Minis 2-pack. Character-level collisions are
fixable in code; variant-level ones aren't. **Lesson:** build the detector, state
explicitly that it produces a *worklist not a verdict*, and never let a clean run imply
clean data.

### 43. Test a rescue rule against what it must NOT rescue
"Castiel FunkO's" was deleted by a title rule matching `funko's` — but it's a real Pop!
Television Supernatural #95, mis-titled at source. Series and Funko number couldn't save
it: FunkO's *cereal* boxes legitimately carry a `Pop! Disney` tag and the box number of
the Pocket Pop inside. Only the HobbyDB image category separated them (`Vinyl_Art_Toys`
vs `Whatever_Else`). **Lesson:** the first version of that guard rescued Castiel *and*
nine cereal records. Always test the negative cases.

### 44. Post-processing crashes lose the whole run
`enrich.js` scraped for an hour then crashed in the final `removeNonPops` because
`rec.series` was a string where the code assumed an array — after all the network work,
before the write. **Lesson:** post-process steps should be re-runnable against their own
output (a salvage script reusing the pipeline's functions *verbatim*, not a
reimplementation), and the expensive result should hit disk before the cheap transforms.

### 45. Automated rules need a human review band, not a threshold
The naive UPC rule ("one owner per UPC, blank the rest") would have blanked 619 records,
destroying ~258 legitimate variant/multipack shared UPCs. The tiered version (blank
below 0.20 agreement, keep above 0.50, *review* between) blanked 266 automatically and
put 267 in front of a human, of which 95 needed action. **Lesson:** the middle band is
where the value is — it's the difference between a rule that works and one that quietly
deletes good data.

### 46. The user's eyes are ground truth, and they'll catch you
Every significant error in S22 was caught by the user, not the tooling: Glamrock FNAF are
action figures not Pops; a "hat" filter was eating real Pops *wearing* hats; Dorbz are
invisible to any rule; a verification was reporting YES on garbage; a box number was
correct when the assistant was about to blank it as suspicious. **Lesson:** when the user
contradicts the data, the data is usually wrong — and the fix belongs in the *rule*, not
the record.

### 47. A restore bypasses the preloader — test the fresh-install path separately
`CatalogPreloader.preloadIfNeeded()` checks a marker doc and returns early if the catalog
is loaded. A restored backup carries its own catalog, so restoring means the asset is
never read, the gzip never opened, the parse never exercised. Shipping a new preloader and
confirming "it works after a restore" tests *nothing* about the preloader. **Lesson:** wipe
data and launch clean (emulator: Device Manager → ⋮ → Wipe Data) — that's the path every
new user takes and the only one that runs the code.

### 48. A bad asset fails silently, not loudly
The preloader skips records missing an id or title rather than crashing. Ship a malformed
catalog and the app starts fine with a partial one — no exception, no log line, just
missing figures nobody notices. **Lesson:** validate the asset against exactly what the
loader expects *before* packaging (real booleans vs `"True"` strings, string vs list
`series`, leaked personal records, missing ids). A successful build proves nothing about
the data.

### 49. Stream large assets; don't materialise the tree
`assets.open(f).bufferedReader().use { it.readText() }` + `gson.fromJson` holds the whole
document tree in memory — fine at 8 MB, an OOM risk at 18 MB. `GZIPInputStream` +
`JsonReader` + batched writes keeps one record in memory at a time, and gzip took the
asset 18.1 MB → 2.0 MB. **Lesson:** don't gzip a gzip, though — Android already compresses
assets at packaging.

### 50. Deprecate rather than delete when disabling code
The Kenny Chan re-fetch had to stop running (it would re-import exactly the merch and
duplicates the cleanup removed) but deleting it would have deleted the *reasoning*.
Renaming it `refreshKennyChanDISABLED()` with the why inline, and marking the unused
`KennyRecord` `@Deprecated`, keeps the explanation next to the code for two build
warnings. **Lesson:** the warnings are the annotation doing its job. (DEC-024)

### 51. State the limits of a tool you just built
`check_mismatches.py` catches blatant mis-matches and cannot catch variant-level ones — it
missed the very record that motivated it. Handing it over as "run this to validate" would
imply assurance it doesn't provide. **Lesson:** a zero result means "no obvious problems",
not "no problems", and the difference matters when someone acts on it.

*Document maintained by Celtic Heart Steamworks. Update after each significant change.*
