# FunkoDex — Google Drive Auth Migration Spec (Phase 1)

**Author session:** 2026-06-12
**Status:** Specification — for review before implementation (Phase 2)
**Scope:** Replace the deprecated `GoogleSignIn` API used for Google Drive backup auth.
**MIT License — Copyright (c) 2026 Chris Ahrendt**

---

## 1. The core finding (read this first)

The handoff frames this as "GoogleSignIn → Credential Manager migration." That framing is
half right and, taken literally, would lead to building the wrong thing. Verified against
current Google documentation (developer.android.com, last updated 2026-03-06 and
2025-10-27):

Google split the one deprecated API into **two** replacement APIs:

- **Credential Manager** (`androidx.credentials`) — handles **authentication** (who the
  user is: the Sign in with Google flow, ID token, email/name).
- **AuthorizationClient** (`com.google.android.gms.auth.api.identity`, part of
  `play-services-auth`) — handles **authorization** (scoped access to a Google service
  like Drive; returns the OAuth access token).

The old `GoogleSignIn` did both in one call. The new model treats them as distinct flows.

**What FunkoDex actually needs:** a Drive access token for `DRIVE_FILE` scope, used by the
existing Drive REST client in `DriveBackupWorker`. That is **authorization**, not
authentication. The app does not need to know the user's identity for backup — it needs a
token that can write to their Drive.

**Therefore the minimal correct migration uses `AuthorizationClient` only.**
Credential Manager is **not strictly required** for this feature.

Why include Credential Manager at all, then? One reason, optional:
- The current UI shows "Signed in as {email}" (`driveAccount!!.email`). `AuthorizationResult`
  does **not** contain the account identity — the authorization response only carries a
  token, by design. To keep the email label, you either (a) drop the email from the UI, or
  (b) add a Credential Manager sign-in step purely to obtain the email, or (c) request the
  `userinfo`/`profile`/`openid` scopes alongside `DRIVE_FILE` and call the OAuth userinfo
  endpoint. Option (a) is the least work and is recommended for v1.

**Recommendation:** Implement `AuthorizationClient`-only. Drop the email label (show
"Connected" instead). Defer Credential Manager unless a real authentication need appears.
This is smaller, has fewer moving parts, and removes the deprecated API — which is the
actual goal.

**DECIDED (Phase 1 close-out, 2026-06-12):** the AuthorizationClient-only path is locked
in. The "Signed in as {email}" label is dropped in favor of "Connected · Tap to back up
now". No Credential Manager dependency is added. All former §7 open questions are
resolved below — Phase 2 has no open decisions.

> Opinion (flagged as opinion): the handoff's "significant — own session" sizing was
> based on the assumption that this is a full auth-stack migration. Under the
> AuthorizationClient-only approach it is materially smaller — roughly a
> half-session of focused work plus device testing. The risk is not size; it is the
> token-lifecycle correctness in the background worker (see §6).

---

## 2. Current state (verified against repo @ 9cd550b)

### Files that touch Drive auth
```
data/backup/DriveBackupWorker.kt        — uses GoogleSignIn.getLastSignedInAccount() +
                                           GoogleAccountCredential for the Drive REST client
ui/screens/settings/SettingsScreen.kt   — GoogleSignInClient setup (lines ~73–98),
                                           Connect/Backup/Disconnect rows (lines ~370–393)
FunkoDexApp.kt                           — DriveBackupWorker.schedule(this) at startup
```

### Current auth mechanism
- `GoogleSignIn.getClient()` with `GoogleSignInOptions.DEFAULT_SIGN_IN`, `.requestEmail()`,
  `.requestScopes(Scope(DriveScopes.DRIVE_FILE))`.
- Sign-in launched via `googleSignInClient.signInIntent` through a
  `StartActivityForResult` launcher.
- Worker reads `GoogleSignIn.getLastSignedInAccount(context)`, wraps it in
  `GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE_FILE))`, and
  passes that credential to `Drive.Builder(...)`.
- Sign-out via `googleSignInClient.signOut()`.

### What is NOT affected (verified)
- The HobbyDB/eBay OAuth stack in `auth/` (`OAuthLauncher`, `PkceHelper`,
  `TokenRefreshManager`, `TokenKeeperWorker`, `OAuthCallbackActivity`) is a **separate,
  custom PKCE flow** with its own `funkodex://oauth/{provider}` redirect. It shares
  nothing with Google Drive auth. `TokenRefreshManager`/`TokenKeeperWorker` contain no
  Drive/Google references. **Do not touch these files.**
- `SecureKeyStore` Drive surface is only `getLastBackup()` / `setLastBackup()` (a
  timestamp). No Google token is currently persisted by the app — `GoogleSignIn` cached
  the account. This changes under the new model (see §6).

### Dependencies (verified in gradle/libs.versions.toml)
```
play-services-auth        = "21.2.0"
google-api-drive          → google-api-services-drive (version.ref google-drive-api)
google-api-client-android = "2.7.0"   (GoogleAccountCredential lives here)
```

---

## 3. Target architecture

```
┌─────────────────────────────────────────────────────────────────┐
│ Settings UI (Connect Google Drive)                              │
│   → AuthorizationClient.authorize(DRIVE_FILE)                   │
│   → if hasResolution(): launch PendingIntent (consent screen)   │
│   → else: already granted, no UI                                │
│   → on result: AuthorizationResult.accessToken                  │
│   → persist a "drive connected" flag (NOT the token)            │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ DriveBackupWorker (daily, background, no UI possible)           │
│   → AuthorizationClient.authorize(DRIVE_FILE)                   │
│   → if hasResolution(): user interaction needed → exit cleanly  │
│        (cannot show consent from a worker) → notify user        │
│   → else: AuthorizationResult.accessToken → build Drive client  │
│        with a GoogleCredential/Bearer token, run backup         │
└─────────────────────────────────────────────────────────────────┘
```

Key behavioural fact (verified): on the **first** call `authorize()` may return a
`PendingIntent` (`hasResolution() == true`) requiring user consent. On **subsequent** calls,
as long as the grant has not been revoked, `authorize()` returns the access token
**without user interaction** (`hasResolution() == false`). This is exactly what the daily
worker needs — it is the replacement for the old `silentSignIn` / cached-account path.

---

## 4. Drive REST client wiring change

The Drive REST client (`com.google.api.services.drive.Drive`) currently takes a
`GoogleAccountCredential`. Under the new model there is no `GoogleAccountCredential` —
there is a raw OAuth access token string from `AuthorizationResult.getAccessToken()`.

Two options to feed the token to `Drive.Builder`:

**Option A (RECOMMENDED) — `HttpRequestInitializer` that sets the Bearer header directly:**
```kotlin
val initializer = HttpRequestInitializer { request ->
    request.headers.authorization = "Bearer ${authorizationResult.accessToken}"
}
val drive = Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), initializer)
    .setApplicationName("FunkoDex").build()
```
Zero new dependencies, zero deprecated classes. `HttpRequestInitializer` is a stable
core interface of `google-http-client` (already on the classpath via the Drive REST
client). This is the recommendation.

**Option B (fallback only) — `GoogleCredential` with the access token:**
```kotlin
val credential = com.google.api.client.googleapis.auth.oauth2.GoogleCredential()
    .setAccessToken(authorizationResult.accessToken)
```
**Verified deprecated:** `GoogleCredential` is marked deprecated in google-api-client 2.x
("Please use google-auth-library…"). It still functions in access-token-only mode, but
using a deprecated class in a deprecation-removal migration defeats the purpose. The
google-auth-library replacement (`GoogleCredentials` + `AccessToken` +
`HttpCredentialsAdapter`) would add a new dependency for no benefit over Option A. Use
Option A.

The rest of `DriveBackupWorker` (zip, folder ensure, upload, prune) is unchanged — it
operates on the `Drive` object, which is identical regardless of how it was authenticated.

---

## 5. Files to change (Phase 2 work list)

### 5.1 `gradle/libs.versions.toml` + `app/build.gradle.kts`
Concrete changes (versions verified this session; re-check Maven only if months pass
before implementation):
```toml
# [versions]
play-services-auth = "21.6.0"   # was 21.2.0 — 21.6.0 is the version Google's
                                 # authorization guide cites (page updated 2025-10-27);
                                 # AuthorizationClient/Identity APIs present in 21.x.
                                 # If Android Studio suggests a newer 21.x, take it.

# [libraries] — ADD (verified ABSENT from the repo):
coroutines-play-services = { group = "org.jetbrains.kotlinx",
    name = "kotlinx-coroutines-play-services", version.ref = "coroutines" }
# kotlinx-coroutines-play-services releases in lockstep with kotlinx.coroutines;
# the existing `coroutines = "1.9.0"` ref is correct for it.
```
```kotlin
// app/build.gradle.kts dependencies — ADD:
implementation(libs.coroutines.play.services)
```
- **No Credential Manager deps** (`androidx.credentials:*`) — decided out (§1).
- `google-api-client-android` (2.7.0) and `google-api-services-drive` stay unchanged —
  they provide the Drive REST client. `GoogleAccountCredential` simply stops being
  imported; the artifact remains for the Drive client itself.

### 5.2 New file: `data/backup/DriveAuthManager.kt`
A small wrapper that owns the `AuthorizationClient` interaction so both the UI and the
worker share one code path. Complete implementation (Phase 2 may adjust formatting only):
```kotlin
package com.funkodex.data.backup

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DriveAuthManager
 *
 * Single owner of the AuthorizationClient interaction for Google Drive backup.
 * Replaces the deprecated GoogleSignIn / GoogleAccountCredential path.
 *
 * Authorization model (verified against Google identity docs, 2025-10 / 2026-03):
 *  - First authorize(): may return hasResolution()==true with a PendingIntent the
 *    CALLER must launch from an Activity (consent screen). Workers cannot do this.
 *  - Subsequent authorize(): returns a fresh ~1h access token with NO user
 *    interaction while the grant stands.
 *
 * MIT License — Copyright (c) 2026 Chris Ahrendt
 */
@Singleton
class DriveAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client = Identity.getAuthorizationClient(context)

    private val request: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DriveScopes.DRIVE_FILE)))
        .build()

    /** Result of an authorization attempt, normalized for callers. */
    sealed class DriveAuth {
        /** Token in hand — proceed. */
        data class Authorized(val accessToken: String) : DriveAuth()
        /** User consent required — only an Activity can launch this. */
        data class NeedsConsent(val pendingIntent: PendingIntent) : DriveAuth()
        /** Authorization failed (API error, no token, etc.). */
        data class Failed(val reason: String) : DriveAuth()
    }

    /**
     * Attempt authorization. Safe to call from a worker: never shows UI itself.
     * Null/blank token with no resolution is treated as Failed (see spec §6 item 3).
     */
    suspend fun authorize(): DriveAuth = try {
        val result: AuthorizationResult = client.authorize(request).await()
        when {
            result.hasResolution() -> {
                val pi = result.pendingIntent
                if (pi != null) DriveAuth.NeedsConsent(pi)
                else DriveAuth.Failed("Resolution required but no PendingIntent")
            }
            !result.accessToken.isNullOrBlank() ->
                DriveAuth.Authorized(result.accessToken!!)
            else -> DriveAuth.Failed("No access token and no resolution")
        }
    } catch (e: Exception) {
        DriveAuth.Failed(e.message ?: e.javaClass.simpleName)
    }

    /** Extract the result after the consent PendingIntent returns to the launcher. */
    fun resultFromConsentIntent(data: android.content.Intent?): DriveAuth = try {
        val result = client.getAuthorizationResultFromIntent(data)
        if (!result.accessToken.isNullOrBlank()) DriveAuth.Authorized(result.accessToken!!)
        else DriveAuth.Failed("Consent completed but no access token")
    } catch (e: Exception) {
        DriveAuth.Failed(e.message ?: e.javaClass.simpleName)
    }

    /** Drop a stale token from the local cache (call on Drive 401/403). Best-effort. */
    suspend fun clearToken(token: String) {
        try {
            client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
        } catch (_: Exception) { /* best-effort */ }
    }
}
```
Notes:
- `kotlinx.coroutines.tasks.await()` requires `kotlinx-coroutines-play-services` —
  **NOT currently in the repo (verified)**. Add it (§5.1).
- No `revoke()` method: disconnect is flag-clear + clearToken by design (§5.4).
- `result.accessToken` nullability handled per §6 item 3.

### 5.3 `DriveBackupWorker.kt`
- Remove imports: `GoogleSignIn`, `GoogleAccountCredential`. Add:
  `com.google.api.client.http.HttpRequestInitializer`, `DriveAuthManager`.
- Constructor: add `private val driveAuthManager: DriveAuthManager` to the existing
  `@AssistedInject` params (Hilt provides the `@Singleton` automatically; `db` and
  `secureKeyStore` are already injected the same way).
- Replace the account/credential block at the top of `doWork()` with:
```kotlin
CouchbaseLite.init(applicationContext)

if (!secureKeyStore.isDriveConnected())
    return@withContext Result.success(workDataOf("skipped" to "not_connected"))

val token = when (val auth = driveAuthManager.authorize()) {
    is DriveAuthManager.DriveAuth.Authorized -> auth.accessToken
    is DriveAuthManager.DriveAuth.NeedsConsent -> {
        // Grant lapsed/revoked — a worker cannot show consent UI. Not an error;
        // do NOT retry (it would spin). Tell the user to reconnect.
        sendReconnectNotification()
        return@withContext Result.success(workDataOf("skipped" to "needs_consent"))
    }
    is DriveAuthManager.DriveAuth.Failed -> {
        FunkoDexLogger.w(TAG, "Drive authorize failed: ${auth.reason}")
        return@withContext Result.retry()   // transient API failure — backoff applies
    }
}

val drive = Drive.Builder(
    NetHttpTransport(),
    GsonFactory.getDefaultInstance(),
    HttpRequestInitializer { req -> req.headers.authorization = "Bearer $token" },
).setApplicationName("FunkoDex").build()
```
- `sendReconnectNotification()`: clone of the existing `sendBackupNotification` pattern
  (same `backup_status` channel, same POST_NOTIFICATIONS guard), text:
  "Google Drive backup paused — reconnect in Settings to resume." Use a distinct
  notification id (e.g. 3002).
- Mid-flight stale token (§6 item 4): wrap the three Drive calls (`ensureFolder`,
  `files().create`, `pruneOldBackups`) so a
  `GoogleJsonResponseException` with status 401/403 does
  `driveAuthManager.clearToken(token)` then `return@withContext Result.retry()` —
  the retry re-runs `authorize()` and gets a fresh token.
- Everything else in the worker (zip, folder ensure, upload, prune, lastBackup,
  notification) is **unchanged**.

### 5.4 `SettingsScreen.kt`
- Delete the `googleSignInClient` `remember{}` block and the `driveSignInLauncher`
  `StartActivityForResult` block (~lines 73–98).
- Replace with:
  - A `rememberLauncherForActivityResult(StartIntentSenderForResult())` to resolve the
    consent `PendingIntent`.
  - "Connect Google Drive" → calls `viewModel.connectDrive()` which calls
    `DriveAuthManager.authorize()`; if `hasResolution()`, launch the `IntentSender`; on
    the launcher result, call `getAuthorizationResultFromIntent()` and mark connected.
  - Connection state: replace `driveAccount: GoogleSignInAccount?` with a simple boolean
    persisted via `SecureKeyStore` (e.g. `isDriveConnected()`), since there is no account
    object anymore. **Drop the "Signed in as {email}" subtitle** → "Connected · Tap to
    back up now" (decided — see §1).
  - **"Disconnect" (decided design — do NOT use revokeAccess):** the recommended path
    holds no `Account` object, and `revokeAccess` semantics revoke ALL scopes which is
    heavier than needed. Disconnect = (1) clear the `isDriveConnected` flag, (2) call
    `clearToken` on the last-used access token if one is in hand (best-effort, ignore
    failure), (3) `DriveBackupWorker.cancel(context)` is NOT needed — the worker
    self-guards on the flag, but cancelling the periodic work is tidy and matches the
    "Stop automatic backups" subtitle, so do cancel and re-`schedule` on reconnect.
    Update the Disconnect row subtitle to also mention: "To fully revoke FunkoDex's
    Drive access, visit your Google Account → Connections." That page
    (myaccount.google.com/connections) is the user-controlled revocation path and is
    always accurate regardless of app state.
- This logic should move into `SettingsViewModel` (or a small `DriveSettingsViewModel`)
  rather than living in the composable, to keep the `Task`/coroutine handling testable.

### 5.5 `SecureKeyStore.kt`
- Add `isDriveConnected()/setDriveConnected(Boolean)/clearDriveConnected()` — a simple
  boolean flag replacing the implicit "is there a signed-in account" check.
- **Do not** persist the Drive access token. It is short-lived (1 hour) and
  `AuthorizationClient` caches it internally; re-call `authorize()` to get a fresh one.
  Storing it buys nothing and adds attack surface.

### 5.6 `FunkoDexApp.kt`
- `DriveBackupWorker.schedule(this)` stays. The worker self-guards: if not connected /
  consent needed, it exits cleanly. Optionally gate the schedule call on
  `secureKeyStore.isDriveConnected()` to avoid scheduling a no-op worker — minor.

### 5.7 Manifest / Cloud Console
- No manifest changes for Drive (no new intent-filter; the consent flow uses a system
  `PendingIntent`, not a custom redirect like the HobbyDB/eBay flow).
- **Cloud Console (external, must be done before testing):** an OAuth **Android client ID**
  with the app package name + SHA-1 must exist (this already exists if the current
  GoogleSignIn flow ever worked). The recommended path needs **no** Web client ID, because
  no `requestOfflineAccess()`/server auth code is used (the app holds the token directly).
  If offline/server access is ever added, a Web client ID is required.

---

## 6. The actual risk: token lifecycle in the worker

This is where a careless migration breaks silently. The old `GoogleAccountCredential`
refreshed tokens transparently. The new model does not — the worker holds a raw 1-hour
access token.

Correctness requirements:
1. The worker must call `authorize()` **every run** to get a fresh token. Never cache the
   token across runs.
2. A `hasResolution()` result inside the worker is **not an error** — it means the grant
   lapsed (revoked, or never completed). Handle it as "skip + notify," not retry.
3. **Guard a null/blank `accessToken`** even when `hasResolution()` is false.
   `AuthorizationResult.getAccessToken()` is nullable; treat null the same as the
   needs-consent path (skip + notify) rather than letting it NPE into `Result.retry()`.
4. A 401/403 from a Drive call mid-backup means the token went stale mid-flight (rare at
   1-hour lifetime, but possible if the device slept). Clear the token and retry once.
5. `revokeAccess()` revokes **all** scopes for the app and clears cached tokens — so
   "Disconnect" genuinely disconnects. Confirmed in docs.

Test this path explicitly (see device test additions, §9).

---

## 7. Decisions — all former open questions RESOLVED (2026-06-12)

1. **Email retention: NO.** UI shows "Connected · Tap to back up now". Decided.
2. **Revoke: not used.** Disconnect = clear local flag + best-effort `clearToken` +
   cancel the periodic worker; subtitle points users to Google Account → Connections
   for full server-side revocation. Removes the Account-object dependency entirely.
3. **`play-services-auth` = 21.6.0** — the version Google's authorization guide cites
   (page updated 2025-10-27); take a newer 21.x if Android Studio offers one.
4. **`kotlinx-coroutines-play-services`: ADD** — verified absent from the repo; pin to
   the existing `coroutines` ref (1.9.0), same release train.
5. **Drive client auth = `HttpRequestInitializer` Bearer header** — `GoogleCredential`
   is deprecated (verified); do not use it.
6. **Multidex: no action.** `multiDexEnabled = true` stays; removing GoogleSignIn only
   reduces method count.

---

## 8. Phase 2 execution checklist (do in order)

1. [ ] Pull latest master; confirm `SettingsScreen.kt` GoogleSignIn block still at
       ~lines 73–98 / 370–393 (re-locate if the file moved since `9cd550b`).
2. [ ] `libs.versions.toml`: bump `play-services-auth` → 21.6.0; add
       `coroutines-play-services` library entry. `app/build.gradle.kts`: add
       `implementation(libs.coroutines.play.services)`. Sync.
3. [ ] Create `data/backup/DriveAuthManager.kt` (§5.2 — complete implementation given).
4. [ ] `SecureKeyStore.kt`: add `isDriveConnected()` / `setDriveConnected(Boolean)` /
       `clearDriveConnected()` (plain boolean pref, key `"drive_connected"`).
       Do NOT store the access token (§5.5).
5. [ ] `DriveBackupWorker.kt`: apply §5.3 — constructor param, doWork block,
       `sendReconnectNotification`, 401/403 wrap. Delete the GoogleSignIn imports.
6. [ ] `SettingsViewModel.kt`: add `driveConnected: StateFlow<Boolean>` (seeded from
       SecureKeyStore), `connectDrive()` (calls manager.authorize(); Authorized →
       set flag; NeedsConsent → expose the PendingIntent via a StateFlow for the UI to
       launch), `onConsentResult(Intent?)` (manager.resultFromConsentIntent → set flag),
       `disconnectDrive()` (clear flag). Worker schedule/cancel calls stay in the
       composable via `LocalContext` (matches existing `DriveBackupWorker.runNow` usage).
7. [ ] `SettingsScreen.kt`: delete the `googleSignInClient` remember block and
       `driveSignInLauncher`; add a
       `rememberLauncherForActivityResult(StartIntentSenderForResult())` that calls
       `viewModel.onConsentResult(result.data)`; a `LaunchedEffect` on the consent
       PendingIntent StateFlow launches
       `IntentSenderRequest.Builder(pi.intentSender).build()`; rewire the three rows
       per §5.4 (Connect / Connected · back up now / Disconnect with the
       Connections-page note).
8. [ ] Build. Zero remaining references to `GoogleSignIn` or `GoogleAccountCredential`
       (`grep` the source tree). The deprecation warnings for that family are gone.
9. [ ] Cloud Console: confirm the Android OAuth client (package `com.funkodex` + the
       signing SHA-1) exists — required before any device test passes (Chris).
10. [ ] Device tests T-D1 through T-D5 (§9 below). T-D3 (lapsed grant) is the one that
        catches worker-lifecycle mistakes — do not skip it.
11. [ ] Update HANDOFF.md (move Credential Manager item to resolved) and CHANGELOG.md
        (Session entry).

---

## 9. Device test additions (append to DEVICE_TEST_PLAN.md in Phase 2)

- **T-D1 First connect:** fresh app, Settings → Connect Google Drive → consent screen
  appears → grant → row shows "Connected." Tap "Back up now" → backup appears in Drive
  "FunkoDex Backups" folder.
- **T-D2 Silent daily run:** with Drive connected, trigger the periodic worker (or
  `runNow`) → backup succeeds with **no** consent UI (proves the silent authorize path).
- **T-D3 Lapsed grant:** revoke access from the Google account's Connections page, then
  trigger the worker → worker exits cleanly, posts the "reconnect" notification, does not
  crash or infinite-retry.
- **T-D4 Disconnect:** Settings → Disconnect → confirm subsequent worker run does not
  back up and the row returns to "Connect Google Drive."
- **T-D5 Reconnect after disconnect:** Connect again → consent may or may not re-appear
  (depending on prior revoke) → backup works.

---

## 10. Phase 2 handoff requirements (what the coding session needs)

- This spec (all decisions resolved — §7; execution order — §8).
- Repo @ current master.
- Cloud Console access (to confirm/create the Android OAuth client ID + SHA-1) — Chris.
- A real Google account on the test device for T-D1..T-D5.

**Estimated Phase 2 size (opinion):** AuthorizationClient-only path ≈ one focused coding
session including the worker-lifecycle care in §6, excluding device testing. The email
path, if required, adds meaningfully more.
