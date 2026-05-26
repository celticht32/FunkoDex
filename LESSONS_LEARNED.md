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
Individual `database.save(doc)` calls inside a loop are extremely slow — each one
acquires and releases the write lock. Wrap any loop that writes multiple documents in
`database.inBatch { }`. For the 23,940-record Kenny Chan preload, this is the
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
- API keys → user-entered, stored in `EncryptedSharedPreferences` (AndroidX security-crypto)
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

### 20. Store the OAuth install ID in EncryptedSharedPreferences, not plain prefs
The community contribution rate-limit uses an anonymous install UUID sent as X-Device-ID.
Original implementation used `context.getSharedPreferences("funkodex_meta", MODE_PRIVATE)`
which writes a plaintext XML file readable via `adb shell`. Even though the UUID is not
a credential, consistency with the rest of the security model demands EncryptedSharedPreferences.
The docstring said "EncryptedSharedPreferences" but the code used plain prefs — a comment
mismatch that the security audit caught. Keep code and comments consistent.

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

*Document maintained by Celtic Heart Steamworks. Update after each significant change.*
