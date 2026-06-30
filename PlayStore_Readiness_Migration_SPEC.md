# FunkoDex — Google Play Readiness Migration Spec

**Author session:** 2026-06-12 (repo scanned @ commit `9cd550b`, all 66 Kotlin files + build config + manifest)
**Status:** Specification — for review before implementation
**Scope:** (1) the 16 KB page-size Play error, (2) deprecated API warnings, (3) other Play-readiness items surfaced by the full-repo scan.
**MIT License — Copyright (c) 2026 Chris Ahrendt**

---

## Priority map

| # | Item | Play impact | Effort |
|---|------|-------------|--------|
| P0 | 16 KB page size — Couchbase Lite 3.2.1 | **Hard reject** (Nov 1 2025 policy, app targets SDK 36) | Trivial (version bump) |
| P0 | 16 KB page size — CameraX 1.3.4 | **Hard reject** | Small (version bump + smoke test) |
| P0 | 16 KB page size — ML Kit barcode 17.3.0 | **Possible reject** — contested, must verify | Verification + possible swap |
| P1 | GoogleSignIn deprecated API | Build warnings now; removal later | Already specced (CredentialManager_Migration_SPEC.md) |
| P1 | READ_MEDIA_IMAGES permission | Play Photo & Video Permissions policy — declaration form or rejection | Small (Photo Picker swap) |
| P2 | Couchbase Lite database-level APIs (107 call sites) | None today; blocks CBL 4.x later | Medium, mechanical |
| P2 | security-crypto (deprecated lib, alpha pin) | None; unmaintained crypto in a shipping app | Medium |
| P3 | `kotlinOptions`, accompanist-flowlayout, `Icons.Default.ArrowBack`, misc | Warnings only | Trivial each |

P0 must be done before any Play submission. P1 should be. P2/P3 are debt with a documented clock.

---

## 1. The 16 KB page-size error (P0)

### What it is
Android 15+ supports devices with 16 KB memory pages. Native libraries (`.so`) built
assuming 4 KB pages have ELF LOAD segments aligned at 4 KB and fail to load on those
devices. **Since November 1, 2025, Google Play rejects new apps and updates that target
Android 15+ (API 35+) unless every native library is 16 KB-aligned.** FunkoDex has
`targetSdk = 36`, so the requirement applies in full. (Note: HANDOFF.md still says
targetSdk 35 — stale; the build file says 36.)

The error Android Studio / Play Console shows is exactly:
```
APK is not compatible with 16 KB devices. Some libraries have LOAD segments
not aligned at 16 KB boundaries:
  lib/arm64-v8a/libLiteCore.so
  lib/arm64-v8a/libLiteCoreJNI.so
```

Two halves to compliance:
- **Zip alignment of the APK** — handled automatically by AGP 8.5.1+. FunkoDex is on
  AGP 8.13.2. **Nothing to do.**
- **ELF segment alignment inside each `.so`** — the library vendor must rebuild with
  16 KB-aligned segments. This is the real work, and it is entirely a dependency-version
  problem for FunkoDex (the app has no first-party native code — verified, no `jni/`,
  no `externalNativeBuild`).

### Native-library inventory (verified from libs.versions.toml + scan)

| Dependency | Pinned | Native libs shipped | 16 KB status | Action |
|---|---|---|---|---|
| `couchbase-lite-android-ktx` | **3.2.1** | `libLiteCore.so`, `libLiteCoreJNI.so` | **NOT aligned at 3.2.1. Fixed in 3.2.3** — confirmed by Couchbase engineering on the official forum (Aug 2025), and the reporter confirmed it resolved the exact Play error above. | Bump to latest 3.2.x (≥3.2.3; 3.2.4 exists on the .NET side — **verify the latest Android 3.2.x on Maven Central at implementation time**). Do **not** jump to 4.0.x for this (see §3). |
| `androidx.camera:*` | **1.3.4** | `libimage_processing_util_jni.so`, `libsurface_util_jni.so` | **NOT aligned at 1.3.x. Fixed in 1.4.x** (Google issue 351313880, referenced in multiple 16 KB reports). | Bump camerax to latest stable 1.4.x+ (**verify exact version**). Smoke-test the scanner — 1.3 → 1.4 had minor behavioral changes around `ResolutionSelector`/preview. |
| `com.google.mlkit:barcode-scanning` | **17.3.0** | `libbarhopper_v3.so` | **Contested.** One GitHub report says 17.3.0 *is* the 16 KB-fixed version (vs 16.2.0 which is not); another (Nov 2025) claims 17.3.0 is still unaligned. 17.3.0 is also the latest bundled version (Aug 2024 — no newer release to bump to). | **Verify empirically** (gate below). If unaligned: switch to the **unbundled** `com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1` — the model and natives live in Play services, not the APK, which removes the `.so` from your APK entirely *and* cuts APK size. API surface is near-identical (`BarcodeScanning.getClient` same package shape; verify `BarcodeScannerOptions` imports). |
| Everything else (Hilt, Compose, OkHttp, Gson, POI, Coil, Glance, CBL-ktx Kotlin layer, security-crypto/Tink) | — | none | n/a | none |

### The verification gate (mandatory, after the bumps)
On the Windows dev box, build a release bundle and check alignment — this is the only
proof that matters:
1. Android Studio → Build → Analyze APK → open the arm64-v8a libs; Studio flags 16 KB
   misalignment directly (same banner as the original error).
2. Or CLI (these are bundled with build-tools / NDK; run from the project dir):
```cmd
gradlew :app:bundleRelease
```
   then inspect with APK Analyzer, or run Google's `check_elf_alignment` script against
   the extracted `lib/arm64-v8a/*.so` (script ships with NDK r28+; on Windows run it via
   WSL or use APK Analyzer instead).
3. Final confirmation: run the app on a 16 KB emulator image (Android Studio ships
   16 KB-page system images; Tools → Device Manager → create one) and exercise:
   catalog preload (CBL), barcode scan (ML Kit + CameraX), photo capture (CameraX).
   These three flows cover every native lib in the app.

### Spec'd changes
`gradle/libs.versions.toml`:
```toml
couchbase-lite     = "3.2.4"        # ≥3.2.3 required for 16 KB; VERIFY latest 3.2.x on Maven
camerax            = "1.4.2"        # ≥1.4.0 required for 16 KB; VERIFY latest stable
mlkit-barcode      = "17.3.0"       # keep; verify alignment empirically — swap to unbundled if it fails
```
No code changes are expected for the CBL bump (3.2.1 → 3.2.x is a maintenance train; the
ktx API surface is unchanged). CameraX 1.3 → 1.4: scan `ScannerScreen`/`BarcodeAnalyzer`/
`BatchScanScreen` for `ResolutionSelector` deprecation warnings after the bump and fix
mechanically.

---

## 2. Deprecated API warnings (P1–P3)

These are the sources of the "uses or overrides a deprecated API" / Kotlin deprecation
warnings in the build log, found by whole-repo scan. Listed by weight.

### 2.1 GoogleSignIn / GoogleAccountCredential (P1 — already specced)
`SettingsScreen.kt` (~lines 73–98, 370–393) and `DriveBackupWorker.kt`. The entire
migration is specified in `CredentialManager_Migration_SPEC.md` (AuthorizationClient-only
path). Executing that spec removes this whole warning family plus the
`play-services-auth` legacy surface. **No additional work specced here — do that spec.**

### 2.2 Couchbase Lite database-level APIs — 107 call sites across 11 files (P2)
CBL deprecated the database-level data APIs (`database.getDocument()`, `database.save()`,
`database.delete()`, `DataSource.database()`, `database.createQuery()`,
`database.createIndex()`, database-level change listeners) in favor of the **Collection**
API (`db.defaultCollection.getDocument()`, `DataSource.collection(...)`, etc.).
**CBL 4.0.0 (Oct 2025) removed the deprecated forms outright** (release notes: CBL-7291
"Removed Deprecated Database APIs", CBL-7299 "Removed Deprecated QueryBuilder APIs").

Verified usage: 107 call sites, 0 uses of `defaultCollection` anywhere. Affected files:
```
data/db/FunkoDexDatabase.kt          data/repository/FunkoRepository.kt
data/repository/AlertRepository.kt   data/repository/ContributionRepository.kt
data/repository/CategoryPreferenceRepository.kt
data/repository/ImageBlobRepository.kt
data/preload/CatalogPreloader.kt     data/preload/CatalogImporter.kt
data/preload/CatalogRefreshWorker.kt network/FunkoLookupService.kt
network/ConnectivityObserver.kt      ui/screens/settings/DatabaseTransferViewModel.kt
```

**Recommendation:** do NOT fold this into the 16 KB fix. The 3.2.x bump compiles
unchanged (warnings only). Schedule the Collection-API migration as its own mechanical
session: add a `FunkoDexDatabase.collection` accessor returning `defaultCollection`,
then convert file-by-file (`database.getDocument` → `collection.getDocument`,
`DataSource.database(db)` → `DataSource.collection(col)`, `database.save` →
`collection.save`, indexes → `collection.createIndex`). The backup/restore and
force-restore paths (`DatabaseTransferViewModel`) need the most care — they enumerate
and delete docs and reopen the database. This migration is the prerequisite for ever
moving to CBL 4.x; until then 3.2.x remains supported and 16 KB-safe.

### 2.3 READ_MEDIA_IMAGES + GetContent (P1 — this one is policy, not just a warning)
Manifest declares `READ_MEDIA_IMAGES` (API 33+) and `READ_EXTERNAL_STORAGE` (maxSdk 32);
`DetailScreen.kt:830` gates gallery access behind a runtime
`rememberPermissionState(READ_MEDIA_IMAGES)`; the actual pick uses
`ActivityResultContracts.GetContent()` (DetailScreen.kt:816, PhotoRepository contract).

Two problems:
1. **The permission is unnecessary.** `GetContent` (and better, `PickVisualMedia`) grants
   per-item URI access with **no** storage permission required.
2. **Play's Photo and Video Permissions policy** requires apps declaring
   `READ_MEDIA_IMAGES` to justify broad photo access in a Console declaration form or
   face rejection/removal — enforcement has been active since 2025. FunkoDex picks one
   photo at a time; it will not qualify for broad access.

**Spec'd change:** replace the gallery flow with the system Photo Picker
(`ActivityResultContracts.PickVisualMedia()` + `PickVisualMediaRequest(ImageOnly)`),
delete the permission gate at DetailScreen.kt:830, and remove both
`READ_MEDIA_IMAGES` and `READ_EXTERNAL_STORAGE` from the manifest. CAMERA stays
(core scanning feature; Play-acceptable). This also deletes one of the two
accompanist-permissions use sites. Smoke test: gallery pick on an API 33+ device and an
API 26–32 device (Photo Picker is backported via Play services to API 30+; on 26–29 the
contract falls back to the documents UI — acceptable, verify behavior on the min-SDK
emulator).

### 2.4 security-crypto / EncryptedSharedPreferences (P2)
`SecureKeyStore.kt` and `HmacKeyStore.kt` use
`androidx.security:security-crypto:1.1.0-alpha06`. **Verified: Google deprecated the
entire library in April 2025 (1.1.0-alpha07: "Deprecated all APIs in favour of existing
platform APIs and direct use of Android Keystore"; the 1.1.0 betas/rc carry the same
deprecation).** The alpha06 pin predates the annotations, which is why the build doesn't
warn — but this is an unmaintained crypto wrapper pinned at an alpha in a release app.

Not a Play blocker. **Recommendation:** own session, after launch is unblocked. Replace
`EncryptedSharedPreferences` with plain `SharedPreferences` values encrypted via Android
Keystore (AES/GCM, key in `AndroidKeyStore`) — the data is API tokens and keys, small
strings, so a thin Keystore wrapper (~80 lines) replaces the library. Must include a
one-time migration that reads existing encrypted prefs with the old library before
removal, or users lose their HobbyDB/eBay/Channel3 keys on update. Until then, pin
alpha06 deliberately and note why.

### 2.5 Small fry (P3 — fix opportunistically, each is minutes)
- **`kotlinOptions { jvmTarget = "17" }`** (app/build.gradle.kts:62) — deprecated in
  Kotlin 2.x Gradle plugin; emits a build warning. Replace with:
  ```kotlin
  kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
  ```
- **`accompanist-flowlayout`** — `CollectionScreen.kt:151` uses
  `com.google.accompanist.flowlayout.FlowRow`. The accompanist artifact is deprecated;
  Compose foundation has had `FlowRow` since 1.4 (the BOM in use includes it). Swap the
  import (`androidx.compose.foundation.layout.FlowRow`, `horizontalArrangement =
  Arrangement.spacedBy(8.dp)` replaces `mainAxisSpacing`), then drop the dependency.
- ~~**`Icons.Default.ArrowBack`** (DetailScreen.kt:235, CategoryFilterScreen.kt:37) and
  **`Icons.Default.Logout`** (SettingsScreen.kt:385) — deprecated in favor of
  `Icons.AutoMirrored.Filled.*` (RTL support). Mechanical rename.~~
  **DONE (Session 10).** `Logout` was fixed in Session 9. `ArrowBack` (both
  sites, plus `PreScanScreen.kt:260` `HelpOutline` found during the same pass)
  fixed in Session 10 — note each swap needs its own
  `import androidx.compose.material.icons.automirrored.filled.<IconName>`,
  the existing `filled.*` wildcard import does not cover `Icons.AutoMirrored`
  and the rename alone produces a new "receiver type mismatch" compile error.
- ~~**`vibrate(50)`** (ScannerScreen.kt:72) — already correctly version-gated with
  `@Suppress("DEPRECATION")` for pre-API-31. **No change needed**; this is the right
  pattern. Noting it so nobody "fixes" it.~~
  **CORRECTION (Session 10):** this assessment was wrong — the
  `@Suppress("DEPRECATION")` was attached only to the `val v = ...`
  declaration, not the separate `v?.vibrate(50)` call on the next line, so
  the warning still fired. Fixed by wrapping both statements in one `run { }`
  block under a single `@Suppress("DEPRECATION")`.
- **`accompanist-permissions`** — still maintained (unlike flowlayout) and used for the
  CAMERA flow in 3 screens. Keep for now; if §2.3 removes the DetailScreen use, the
  remaining uses are scanner/prescan CAMERA gates which are fine.
- **`compose-bom 2024.09.00` / `navigation 2.8.0` / `lifecycle 2.8.5`** — not deprecated,
  but ~20 months old; a BOM refresh will surface (and usually auto-fix) accumulated
  Compose deprecations. Optional, do after P0/P1 land, in its own commit.

---

## 3. What NOT to do

- **Do NOT migrate to Couchbase Lite 4.0.x as part of the 16 KB fix.** 4.0 removed the
  database-level APIs FunkoDex calls 107 times — that bump would break every repository
  in the app at once. 3.2.3+ is the 16 KB fix; 4.x is a separate migration gated on §2.2.
- **Do NOT add `android:extractNativeLibs` / `useLegacyPackaging` workarounds.** They do
  not fix ELF alignment and legacy packaging is itself on the deprecation path.
- **Do NOT bump Kotlin/AGP/Gradle in the same change set.** The toolchain
  (AGP 8.13.2 / Gradle 8.13 / Kotlin 2.0.21) already satisfies every requirement here.
- **Do NOT remove the CAMERA permission or touch the OAuthCallbackActivity
  intent-filters** — both are correct as-is.

---

## 4. Execution plan (suggested order)

1. **Session A — 16 KB (P0):** bump couchbase-lite + camerax in libs.versions.toml
   (verify exact latest versions on Maven first), build, run the §1 verification gate,
   device-smoke the three native-lib flows. If ML Kit fails the gate, swap to the
   unbundled artifact and re-verify. Small session.
2. **Session B — Drive auth (P1):** execute CredentialManager_Migration_SPEC.md.
3. **Session C — Photo Picker (P1):** §2.3. Small session; pairs well with the P3 items
   as a "warnings cleanup" commit.
4. **Session D — CBL Collection API (P2):** §2.2, 107 call sites, mechanical but touch-
   everything; full regression on backup/restore/force-restore required.
5. **Session E — Keystore migration (P2):** §2.4, with the pref-migration shim.

After A–C the app is submission-clean for Play: 16 KB compliant, no policy-flagged
permissions, no deprecated-API families with removal dates attached. D and E are debt
with documented clocks (CBL 4.x adoption; unmaintained crypto lib).

---

## 5. Facts verified this session (sources current as of 2026-06-12)

- Play 16 KB requirement and effective date (Nov 1, 2025, apps targeting Android 15+) —
  Android developer docs / Play policy, quoted verbatim in the Android Studio error.
- CBL 16 KB fix shipped in **3.2.3** — Couchbase engineering on the official forum;
  reporter confirmed it cleared the exact error.
- CBL 4.0.0 removed deprecated Database/QueryBuilder APIs — official 4.0.0 release notes
  (CBL-7291, CBL-7295, CBL-7299, CBL-7569). 4.0.x current is 4.0.4 (May 2026).
- CameraX 16 KB fix in 1.4.x (`libimage_processing_util_jni.so`) — Google issue 351313880.
- ML Kit barcode 17.3.0 16 KB status contested (conflicting reports, Sept vs Nov 2025);
  17.3.0 is the latest bundled release; unbundled `play-services-mlkit-barcode-scanning`
  18.3.1 is the documented alternative.
- security-crypto deprecated entirely at 1.1.0-alpha07 (April 2025), repo pins alpha06.
- Repo facts (107 CBL call sites, 0 defaultCollection uses, permission gates, file/line
  refs, AGP/Kotlin/SDK levels) — verified directly against the clone at `9cd550b`.

Items deliberately left as verify-at-implementation: exact latest CBL 3.2.x and CameraX
1.4.x version strings on Maven, ML Kit alignment (empirical gate), Photo Picker fallback
behavior on API 26–29.
