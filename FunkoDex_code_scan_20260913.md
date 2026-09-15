# FunkoDex — full code scan

**Date:** 2026-09-13
**Scope:** every Kotlin file in `app/src/main` and `app/src/test` — 77 files, 18,899 lines,
pre-existing code included, not scoped down.
**Method:** static analysis against known Android/Kotlin/Couchbase defect classes, each hit
read in context before being reported. Findings that did not survive that read are listed
under "Checked and clean" so the negative results are visible too.

---

## Findings

| # | Finding | Where | Severity |
|---|---|---|---|
| 1 | OkHttp `Response` leaked on error-return paths | `CatalogRefreshWorker` ×3 | **Medium** |
| 2 | Live-query `ResultSet` created and discarded | 4 repositories | Low |
| 3 | Price parsing silently yields 0.0 on multi-separator values | 4 sites | Low |
| 4 | eBay `CLIENT_ID` is still a placeholder | `OAuthConfig` | Low (known) |

### 1. Unclosed OkHttp responses on failure paths (Medium)

`CatalogRefreshWorker` calls `.execute()` at three points and returns early without
closing the response when the request fails:

```kotlin
).execute()

if (!response.isSuccessful) {
    FunkoDexLogger.w(TAG, "Kenny Chan fetch failed: ${response.code}")
    return@withContext 0          // <- response never closed
}
val json = response.body?.string() ?: return@withContext 0   // <- null body: same
```

Three occurrences: the Kenny Chan fetch (~line 162), the community-UPC fetch (~line 221),
and the HobbyDB vaulted endpoint (~line 307). The success path is fine — `body.string()`
closes the body — but every failure path leaks the connection until GC.

This is exactly LESSON 34 in `LESSONS_LEARNED.md`: *"Close every OkHttp `Response` with
`.use {}` even on error-return paths."* That lesson was applied to `PriceService` and
`FunkoLookupService` in Session 12; `CatalogRefreshWorker` was missed. The worker runs
every 7 days, so the practical impact is small, but it is a documented invariant that the
codebase currently violates.

`TokenRefreshManager` and `OAuthCallbackActivity` were checked and both wrap correctly
in `.use { }`.

**Fix:** wrap each in `.use { resp -> ... }`, matching the pattern already used in
`PriceService`.

### 2. Live-query result sets created and thrown away (Low)

Four `callbackFlow` live queries do this:

```kotlin
val token = query.addChangeListener { change -> ... trySend(items) }
query.execute()               // result discarded
awaitClose { token.remove() }
```

`FunkoRepository` (two sites), `AlertRepository`, `CategoryPreferenceRepository`.

Two problems, both small. The returned `ResultSet` is never closed, so its query
enumerator is held until GC; and the call is redundant — `addChangeListener` delivers an
initial result on its own, which is what actually primes the flow. Either drop the
`query.execute()` line or wrap it in `.use { }`. `CatalogPreloader.countCatalogDocs`
already does the latter correctly and is the model to copy.

### 3. Price strings silently parse to zero (Low)

Four sites normalise a price with the same expression:

```kotlin
raw.replace(Regex("[^0-9.]"), "").toDoubleOrNull()
```

`FunkoRepository:442`, `CatalogImporter:208`, `CatalogImporter:381`,
`CollectionRelinkService:74`.

It handles `"$1,234.56"` correctly. It does not handle a value containing more than one
decimal separator: `"$10.00 - $15.00"` becomes `"10.0015.00"` and `"1.234,56"` becomes
`"1.23456"`. The first returns null from `toDoubleOrNull()` and the call sites then
substitute `0.0`.

Writing 0.0 for an unparseable value runs against DEC-025 ("blank a wrong value; never
guess a replacement") — a silent zero is a guess, and a zero market value is
indistinguishable from a genuinely free item. Current enricher output is clean, so this is
latent rather than live, but it is the same shape of hazard as DEC-031's blank titles:
it fires when externally-authored data arrives.

**Suggested fix:** keep the first numeric run only, and leave the field untouched rather
than writing 0.0 when parsing fails.

### 4. eBay `CLIENT_ID` placeholder (Low, already known)

`OAuthConfig.kt:42` still holds `"FunkoDex-FunkoDex-PRD-xxxxxxxx-xxxxxxxx"`. Documented in
`CLAUDE.md` under remaining limitations. Noted only for completeness — the eBay OAuth flow
cannot work until it is replaced.

---

## Checked and clean

These were tested and produced no finding. Listed so the scan's negative results are
visible rather than implied.

- **Non-null assertions:** zero `!!` in the entire tree.
- **Unstructured concurrency:** no `GlobalScope`, no ad-hoc `CoroutineScope(Dispatchers…)`.
- **PendingIntent mutability:** both sites (`TokenKeeperWorker:62`, `PriceAlertWorker:151`)
  use `FLAG_IMMUTABLE`.
- **Notification permission:** all five `notify()` call sites are gated by a
  `POST_NOTIFICATIONS` check on TIRAMISU+ — `TokenKeeperWorker`, `ConnectivityObserver`,
  `PriceAlertWorker`, and both `DriveBackupWorker` notifications. CLAUDE.md's claim holds.
- **Hardcoded credentials:** none. Keys come from `SecureKeyStore`; the HobbyDB client id
  is a public client identifier, correctly documented as such.
- **Barcode check digits:** UPC-A and EAN-13 weighting in `UpcValidation` are both correct.
  Verified by hand against known-good codes (`036000291452` sums to 60; `4006381333931`
  sums to 90 — both ≡ 0 mod 10).
- **Division by zero:** `FunkoItem.completionPct` is guarded by `isCompletable`
  (`totalInCatalog > 0`); `PriceService`'s median has an `if (prices.isEmpty()) return null`
  ahead of the `sorted[size/2 - 1]` access, so the empty-list crash is not reachable.
- **Thread-unsafe date formatting:** `CrashHandler` constructs its `SimpleDateFormat`
  per call, so it is safe. (`FunkoDexLogger` held shared instances across threads — fixed
  earlier today.)
- **Over-escaped string templates:** none remaining after the `SettingsViewModel` fix.
- **Cleartext image URLs:** `toHttpsImageUrl()` upgrades `http://` before Coil sees it,
  which is the correct response to the global `cleartextTrafficPermitted="false"`.

---

## Suggested order

1. Finding 1 — it violates a written lesson and is a five-line change.
2. Finding 2 — same sitting, same class of fix.
3. Finding 3 — worth a decision rather than a patch: it is a DEC-025 question
   (what to write when a price will not parse), not just a regex fix.
4. Finding 4 — blocked on eBay app approval, nothing to do in code.
