# FunkoDex — Consolidated Feature Spec

Version: v1.0 (2026-06-29)
License: MIT © 2026 Chris Ahrendt

Single authoritative spec for all designed-but-not-fully-built FunkoDex work.
Supersedes and replaces these files (now safe to delete):
`SERIES_COMPLETION_SPEC_v0.2.md`, `SERIES_COMPLETION_HANDOFF.md`,
`IDEA_browse_set_wantlist.md`, `SPEC_variant_hierarchy.md`,
`RELINK_FIELD_PROTECTION_SPEC.md`, `SPEC_regional_currency.md`,
`TODO_app_autofill_prices.md`, `TODO_remote_catalog_autoupdate.md`, `FUTURE.md`,
`docs/PlayStore_Readiness_Migration_SPEC.md` and `docs/CredentialManager_Migration_SPEC.md` (both completed migrations — hard rules preserved in §11).

Architecture, package layout, and current build state remain in `CLAUDE.md`.
Test coverage lives in `FUNKODEX_TEST_PLAN_v1.0.md`. Dated session log and next
focus remain in `HANDOFF.md`.

Verified against the on-device repaired backup (`funkodex_backup.json`,
2026-06-29): 21,989 catalog docs, 175 owned, `franchiseSuggestion` populated on
76% (16,604 / 2,562 distinct franchises), `setTag` on 107, 82% priced.

---

## Section index

1. Collection Completion + Want List (LEAD — grouped, build first)
2. Variant Hierarchy (folded into §1 as the grouping layer)
3. Re-link Field Protection
4. On-Add Live Price Fill
5. Regional Currency + Travel-Safe Pricing
6. Remote Catalog Auto-Update
7. Community Catalog Distribution (major initiative — design only)
8. FUTURE roadmap (F-XXX enhancement backlog)
9. Required data pre-work (blocks §1)
10. Cross-feature dependency map
11. Completed Migrations and Hard Rules (reference)

---

# 1. Collection Completion + Want List

Status: DESIGNED, verified against real data, approved UI (Option C drill-in
with want-all button). Build FIRST. This is the consolidation of the former
series-completion, browse-set want-list, and variant-hierarchy specs into one
feature, because the verified data proves they are one feature: a completion
view is unusable without variant grouping, and the want-list is the completion
view's missing set made actionable.

## 1.1 The grouping axis (RESOLVED against final data)

The keystone question — which field holds the set/property name — is settled:

- **Primary axis: `franchiseSuggestion`.** Populated on 76% of catalog docs,
  2,562 distinct franchises, already cleaned (retailer/event noise stripped from
  the raw `pcSeries`). Resolves small properties correctly (Hocus Pocus = 21,
  Dragon Ball = 18) that the umbrella console alone could not. USE THIS, not
  `pcSeries` (its noisy source) and not `series`/`category` (those hold the
  format/line — "Pop! Disney" — which is useless for property grouping).
- **Sub-band: `setTag`.** Only 107 records carry it (Mini Vinyl Figures, Mystery
  Boxes, Vinyl Sets). It is a real but narrow secondary grouping — mainline Pop!
  lines have no `setTag`. v1 shows it as an optional sub-group inside a franchise
  (e.g. Haunted Mansion's "Mini Vinyl Figures") with its own want-all button.

Design consequence: every completion/want feature keys on `franchiseSuggestion`
as the primary group, with `setTag` as an optional nested sub-group. The original
specs assumed `setTag` would be the workhorse; the data says `franchiseSuggestion`
is. A "browse every named sub-set" promise cannot be made for mainline lines —
that data does not exist in the catalog.

## 1.2 Variant grouping (the grouping layer — validated)

The enriched catalog is FLAT for variants: a base figure exists as N independent
records sharing only `funkoNumber` + base name. Verified examples from the backup:
Madame Leota #575 is three records (Glitter / plain / Glow in the Dark); Sarah
Sanderson appears across #558, #434, #771 with variant tags. A flat want-list is
noisy with these duplicates — which is why grouping ships in v1, not v1.1.

**Grouping key (validated against real data):**
`funkoNumber` + normalized base name, where the base name is derived by:
- decode HTML entities (`&amp;` → `&`),
- strip trailing `(...)` and `[...]` qualifier groups,
- strip `#NN` tokens,
- collapse whitespace, lowercase,
- DO NOT strip leading descriptive words.

**Validation results (run against the backup):**
- Collapses correctly: `[575] Madame Leota` groups Glitter / plain / Glow;
  `[154] Super Saiyan Vegeta` groups all 7 chrome convention variants;
  `[111] Majin Buu` groups plain / Chocolate / Pink Chrome.
- PASSES the danger test: `Vegeta` (#10) and `Great Ape Vegeta` do NOT merge —
  leading-word preservation keeps the base names distinct. `Super Saiyan Vegeta`
  (#154) stays separate from plain `Vegeta` (#10) — different number AND base.
- Grouping is at the BASE-SKU level (number + name), NOT character level:
  `[578] Constance Hatchaway` and `[803] Constance Hatchaway` group SEPARATELY —
  correct, they are two distinct collectible releases. State this so the count is
  not mistaken for "unique characters."

**Known residual imprecision (accept, do not hide):**
The rule cannot distinguish a finish variant ("(Metallic)") from a sculpt variant
("(with Destructo Disc)") — both are trailing parentheticals. Verified case:
`[706] Krillin` groups "(Metallic)" with "(with Destructo Disc)", which are
arguably different figures. Rule is ~95% correct. MITIGATION: treat a group as a
DISPLAY CONVENIENCE — variants stay individually visible and ownable under the
collapsed group — so a wrong group is visible and harmless, never an authoritative
"these are identical" claim that hides a figure.

**Implementation choice:** derive the `variantGroupKey` in the enricher
(`enrich.js` post-process) and store it on each catalog record, OR compute it
app-side at display time. RECOMMENDATION (opinion): app-side compute, because the
rule is a display convenience and computing it in the app avoids an enricher
re-run to tune it. Revisit if grouping needs to be queryable.

## 1.3 The want model (scan = owned, heart = want)

Three per-figure states, driven by TWO different actions:

- **Owned** — arrives by SCAN or other add paths. Read-only on the completion
  screen; you never toggle it here. Shown with a checkmark.
- **Wanted** — not owned, you tapped the heart. Goes on the want list / buy feed.
- **Not wanted** — not owned, no heart. Stays quiet, never clutters the want list.

The loop: scanning a wanted figure satisfies the want — it moves Wanted → Owned
automatically, no manual cleanup. The heart is purely "things I am hunting for";
scanning is "I got it."

## 1.4 Want-all vs cherry-pick (two entry points, not two modes)

- **Want all (whole series):** ONE button at the top of a franchise screen. One
  tap adds every currently-missing figure to the want list. The bulk path.
- **Cherry-pick (individuals):** the per-figure hearts. Leave the button alone,
  heart only the specific figures you want (e.g. "a Stitch wherever he appears,
  not the whole series"). The granular path.

They compose: hit want-all then untick the few you will never chase, OR ignore the
button and heart only what you want.

## 1.5 Standing intent — the self-maintaining want list (KEY BEHAVIOR)

Pressing "Want all" does TWO things: wants the current missing figures, AND writes
`COMPLETE` intent to a `group_pref::FRANCHISE::{key}` doc. This makes the want list
a STANDING SUBSCRIPTION, not a one-time action: when a new figure in that franchise
later enters the catalog (Funko releases a new Haunted Mansion after the golden
master is set), the reconciliation pass (§1.7) auto-adds it to the want list — the
user never has to know it came out.

Intent states stored per franchise (and per `setTag` sub-group):
- `COMPLETE` — want all current + all future figures in the group.
- `CHERRY_PICK` — want only individually-hearted figures; future figures stay quiet.
- (absent) — franchise never engaged.

This reuses the existing `group_pref::` storage (already built in the
series-completion work; backs up via the existing type-denylist).

## 1.6 The untick-exception (suppress flag — load-bearing)

Under a `COMPLETE` franchise, unticking a figure must store a per-figure
"explicitly not wanted" exception (a suppress flag on the figure / a
`suppressed` list on the group_pref), DISTINCT from "never evaluated." Without it,
the reconciliation pass (§1.7) re-adds the unticked figure on every import because
the franchise is COMPLETE and the figure is unowned. The suppress flag is what lets
a standing COMPLETE intent coexist with manual exclusions (the Hatbox Ghost
prototype you removed stays removed).

## 1.7 How future releases reach the want list (import / enrichment trigger)

The trigger is ANY catalog-growing event: a manual enriched-catalog import
(today's path) or a remote catalog auto-update (§6, once built). Both end by
calling the same reconciliation pass, so behavior is identical regardless of how
new records arrived.

**Ordering (must hold):** import/update -> `CollectionRelinkService` -> want
reconciliation. Relink must run first so newly-imported data attaches to owned
items; otherwise a figure you own but have not yet relinked could be mis-detected
as "unowned, new" and wrongly wanted.

**Detection is set-difference based, NOT date-based.** Verified constraint: the
importer stamps a UNIFORM `lastUpdated` on every record (all 21,989 = import date),
so "new since last sync" cannot be detected by date. `releaseDate` (63% coverage)
is the real-world release date, not catalog-entry date — also wrong. The pass
instead diffs membership:

For each `group_pref` with `groupIntent == COMPLETE`:
1. Load the group's full current membership (catalog docs where
   `franchiseSuggestion == groupKey`, grouped by `variantGroupKey` per §1.2).
2. Diff against three exclusion sets for the group: OWNED (`funko::` joined by
   `catalogRef`/UPC), already-WANTED, and SUPPRESSED (§1.6).
3. Anything in membership but in none of the three exclusion sets is a
   newly-appeared figure -> add to the want list automatically.
4. Repeat for `setTag` sub-groups carrying their own COMPLETE intent.

**Guarantees:** auto-wants genuinely new releases; preserves untick exceptions
(suppressed set); idempotent (a no-change re-run adds nothing); no date dependency.

**Honest dependency:** a new figure is only auto-wanted if enrichment assigned it
the correct `franchiseSuggestion`. A pc-crawl figure that arrives with a blank or
umbrella franchise will not match the COMPLETE group until enrichment tags it. The
auto-want is only as good as franchise tagging — bounded by the 76% coverage
ceiling. This is a dependency on enrichment quality, not a feature defect; state it
so the behavior is not oversold. Full "auto-want new releases" only works once §6
ships; until then it fires on manual catalog re-import.

## 1.8 Completion counts

Show BOTH, because the meaningful number differs by intent:
- `COMPLETE` group: "owned of total" (e.g. Haunted Mansion 10 of 28) — full-set
  progress.
- `CHERRY_PICK` group: "owned of wanted" (e.g. 3 owned, 2 wanted) — personal target.
  The "of total" is de-emphasized for cherry-pick (the total is not the goal).

Counts use the GROUPED denominator (distinct `variantGroupKey`), so #575 Madame
Leota counts once with three variants under it, not three times.

## 1.9 UI (APPROVED — Option C drill-in)

- **Franchise grid (entry):** compact progress cards (franchise name, owned/total,
  progress bar). Umbrella franchises suppressed (§9).
- **Franchise screen (drill-in):** back arrow + franchise title + count; a
  "Want all N missing" button (flips to "All N on want list — tap to clear" after
  press); an OWNED section (checkmarks, grouped); a MISSING section (empty-heart
  rows, eBay buy row per row); optional `setTag` sub-groups each with their own
  want-all button.
- **Buy row:** eBay-active "from $X" + tap-through link per unowned figure
  (reuses the price tier in §1.10). "no listing" when none.
- Verified real example for the spec (Haunted Mansion, from the backup): 10 owned
  / 28 total / 18 missing; owned incl. Alexander Nitrokoff #804, Haunted Mansion
  with Butler #19, Madame Leota (Glow in the Dark) #575; missing incl. Madame Leota
  (Glitter) #575 $14.47, Ezra (10-Inch) #579 $11.99, Hatbox Ghost (Blue Glow
  Glitter) #165 $100.00.

## 1.10 eBay-active buy row

Reuse `PriceService`. Today it queries eBay SOLD listings for pricing
(`LH_Complete=1&LH_Sold=1`). Dropping those flags returns ACTIVE listings = "buy
it now for $X." New method `fetchEbayActive()` mirroring `fetchEbaySold()`, reusing
the existing query construction (incl. variant-specific query for chase), browser-UA
fetch, and HTML parse. Returns asking price + item URL. Caveat: HTML scraping is
fragile; fine at personal volume; durable upgrade path is the eBay Browse API
(official, needs key + OAuth).

## 1.11 Data model summary (§1)

- `group_pref::{LEVEL}::{groupKey}` — REUSED. `{level, groupIntent, groupKey}`.
  Add `suppressed: List<String>` (variantGroupKeys explicitly unticked under a
  COMPLETE group). LEVEL ∈ {FRANCHISE, SET}.
- Want state: a per-figure `isWanted` flag on a want record OR a `wanted` list keyed
  by variantGroupKey. (Finalize storage at build; must back up via the existing
  non-catalog denylist.)
- `variantGroupKey` — `funkoNumber||normalizedBaseName` (§1.2); app-side computed.
- Owned join: `funko::` -> `catalog::` by `catalogRef`, then UPC. Verified clean
  (175/175 owned items resolve to a franchise in the backup).

---

# 2. Variant Hierarchy

Folded into §1.2 as the grouping layer (the verified data proved completion and
variant grouping are inseparable). No separate build. The three-level model
(base figure / official variant / user copy) maps as: base figure =
`variantGroupKey`; official variant = each catalog record under it; user copy =
the existing `FunkoItem.variants: List<FunkoVariant>` (per-copy condition,
pricePaid, photo — already built, unchanged). LEVEL 3 (user copies) already works;
§1.2 adds LEVEL 1 (grouping official variants), which was the missing piece.

---

# 3. Re-link Field Protection (Option B)

Status: scoped, not implemented. Runtime (on-device) feature; NOT part of the
golden master (the master ships catalog-only with an empty collection).

## Problem
Several `funko::` fields are BOTH enricher-derived AND user-editable
(franchise, category, upc overlap with catalog enrichment). With no per-field
edit signal, the current re-link keeps franchise/category/upc fill-only (write only
when blank) to avoid clobbering user edits — so a corrected catalog category never
reaches an owned item that already has one.

## Solution
Track which fields the user explicitly edited; let re-link overwrite any field NOT
in that set.
1. **Schema:** add `userEditedFields: List<String>` to `FunkoItem` + `FunkoMapper`
   (read+write), default empty. NOTE: the backup confirms this field already exists
   on owned docs — partially in place.
2. **Stamp on edit (`DetailViewModel`):** each `updateX` appends its FIELD_ key
   (deduped) before save. Map: updateName→name, updateFranchise→franchise,
   updateNumber→seriesNumber, updateCondition→condition, updateNotes→notes,
   updateCategory→category (+implies genre locked), updateUpc→upc,
   updateImageUrl→imageUrl, market value already covered by `marketValueIsManual`.
3. **Re-link honors the set (`CollectionRelinkService`):** change franchise,
   category(+genre), upc, retailPrice, pricechartingUrl, funkoId, market values
   from "fill when blank" to "overwrite WHEN key NOT in userEditedFields." Keep
   imageUrl protected when user-edited OR non-blank.
4. **Migration (do not skip):** an ABSENT `userEditedFields` (vs present-empty)
   must fall back to fill-only for franchise/category/upc, so a pre-marker edit is
   never clobbered. Distinguish absent vs empty by checking the raw doc before
   mapping.

## Test surface
FunkoMapper roundtrip (present/empty/absent); re-link (edited preserved,
non-edited refreshed, pre-marker fill-only); edit-screen stamps correct key, no dup.

---

# 4. On-Add Live Price Fill

Status: pipeline side DONE (`enrich.js` stamps `priceSource`); APP side not started.

## Why
PriceCharting cannot price ~18-24% of the catalog (verified: 82% priced in the
backup) — not a matcher weakness, but items PC does not carry (boxes, prototypes,
exclusives, multi-packs). The app already has live tiers (eBay sold, UPCitemdb,
HobbyDB) that can price these on demand. So the pipeline MARKS the gap
(`priceSource: 'none'`) and the app fills it live — but ONLY on deliberate add.

## Trigger — user-initiated only
Fires ONLY when the user scans/searches a Funko and ADDS it to the collection.
MUST NOT fire on catalog import, on merely viewing a catalog/detail item, or as any
background/bulk pass. Rationale: live tiers are rate-limited and fragile; tie the
call to a single deliberate add.

## App-side steps (TODO)
1. **Read the flag on import.** `CatalogImporter.toEnrichedRecord()` currently
   DROPS `priceSource` (reads fields explicitly, ignores unknowns). Add a reader to
   `EnrichedRecord` + `CatalogMapper` + the catalog doc field so it survives import.
   This step fires NO network call — it only stores the flag. (This is the small
   prerequisite that unblocks the rest.)
2. **On add, if needed, pull live.** After the `funko::` doc is created: if the
   item has no market value AND `priceSource == 'none'`, fire the `PriceService`
   waterfall ONCE. UPC when present (precise), else title (fuzzy).
3. **Persist on the owned item.** Save as a snapshot on the `funko::` doc with
   timestamp + answering tier via `savePriceSnapshot`. NOT written back to the
   shared `catalog::` doc.
4. **Manual refresh stays.** The detail-screen Refresh button remains for re-pulls
   (sane TTL). Auto-pull happens once on add.

## Hard floor
Some items price nowhere (truly obscure, no sold history). `priceSource: 'none'` +
no live result on add = genuinely unpriceable; show "no price available," not a
failure.

---

# 5. Regional Currency + Travel-Safe Pricing

Status: PROPOSED. Build after §1 import path is validated.

## Verified facts
- PriceCharting is USD by default; currency is a stored browser/account preference,
  NOT request-origin based -> the PC tier is TRAVEL-SAFE (always USD).
- eBay localizes by domain (ebay.co.uk -> GBP, ebay.de -> EUR) -> the eBay fallback
  is NOT travel-safe; a lookup abroad can return a localized price in the wrong
  currency.

## Design decisions
1. **Store a currency code with every price** — never bare numbers. PC market value
   = USD; user `pricePaid` (per copy) = user's chosen currency; any live-tier price
   = the currency that tier returned.
2. **Do NOT auto-convert via live FX.** Show USD as USD; let the user record their
   purchase in their own currency. (Optional display-only "approx in my currency"
   must be clearly labeled and never the stored value.)
3. **User picks a HOME currency** (Settings) — drives default symbol + the travel
   guard baseline.

## Travel-safe guard (two layers)
- **Layer 1 — pin eBay to a fixed domain.** Fallback queries a FIXED eBay domain
  (default ebay.com / USD) regardless of physical location, so returned currency
  matches the PC baseline. Removes the corruption risk for the common case.
- **Layer 2 — mismatch warning.** If device locale currency != home currency AND a
  price action would record a local-currency value, show a non-blocking warning and
  let the user proceed consciously.

## Data model
Every stored price -> `{amount, currency}` (or a parallel `*Currency` field):
user pricePaid, any live-tier snapshot. PC market values are USD by definition.
Settings: `homeCurrency` (default from locale, overridable). eBay tier:
`pinnedEbayDomain` (default ebay.com). Migration: default existing pricePaid to the
user's home currency rather than leaving it ambiguous.

---

# 6. Remote Catalog Auto-Update

Status: PROPOSED, not started. Build AFTER the manual import/relink path (§1) is
validated end-to-end on-device (the auto-updater reuses the streaming importer —
prove that path manually first).

## Goal
Keep the APK small (ship only the Kenny base) and deliver the large enriched
catalog over the network, automatically, no app release per catalog update. Reuse
the streaming `CatalogImporter`.

## Architecture
Small bundled base for instant usability + background hydrate from a remote
manifest-pointed asset. On launch:
1. Seed from the bundled Kenny base (`assets/funko_data.json`) — usable offline,
   no blank-catalog first impression.
2. Fetch a tiny `catalog-manifest.json` (a few hundred bytes).
3. Compare manifest `version` to the local `CATALOG_VER` marker.
4. If newer: download the gzipped enriched catalog into PRIVATE app storage
   (`filesDir`/`cacheDir`), NOT Downloads.
5. Verify `sha256` against the manifest before importing; reject on mismatch.
6. Stream the gzip through the existing importer
   (`GZIPInputStream` -> `JsonReader` -> existing per-record path).
7. Update the local version marker; later launches skip the download.

## Settled decisions
- Download to private storage (no permissions, no Downloads scan — that is the
  separate manual path).
- Version via a small manifest, not file-presence.
- GZIP the catalog (JSON compresses ~5-10x; ~30 MB -> ~3-5 MB). Composes directly
  with the streaming importer.
- Host behind the manifest (host can change without an app update).

## Host choice
- raw.githubusercontent.com works at low scale TODAY but is rate-limited/throttled
  and not built for repeated serving.
- PREFER GitHub Releases assets (built for file distribution, keeps the big file
  out of git history).
- Scale path: Cloudflare R2 (free tier, zero egress) — manifest indirection makes
  this a config change, not an app change.

## Components
- `CatalogUpdateService` (new): manifest fetch, version compare, download+retry,
  sha256 verify, gzip-stream into the importer; graceful no-network path (open on
  base catalog, retry later, never hard-fail on first launch).
- Launch wiring + progress UI (reuse import progress; add download progress).
- `catalog-manifest.json` (new remote file). Proposed schema (DESIGN + approve
  before coding): `{version, url, sha256, records, minAppVersion}`.
- Build/release step: enriched JSON -> gzip -> sha256 -> release asset -> update
  manifest (candidate GitHub Action).

## Robustness
No-network first launch -> base catalog, retry. Corrupt download -> sha256 verify,
retry. Metered connection -> consider Wi-Fi-only or prompt. `CATALOG_VER` tracks the
LOADED version so re-import only happens on a real version change.

## COUPLING TO §1
This is the delivery mechanism for the §1.7 "auto-want new releases" behavior. The
auto-updater brings new figures; §1.5 COMPLETE intent decides they get wanted. Until
this ships, §1.7 fires on manual re-import only.

---

# 7. Community Catalog Distribution

Status: DESIGN ONLY (Session 11), nothing implemented. Major initiative. Full
design in `FunkoDex_Catalog_Distribution_Architecture_v1.2.docx` (repo root/docs).

Summary:
- Golden-master base bundled as `funko_data_golden.json`, loaded on first install;
  deprecates the Enriched Catalog Import feature. NOTE: current `CatalogPreloader`
  reads only handle/title/imageName/series (thin format) — bundling enriched data
  requires teaching it the richer schema.
- Core/user field split: core (shared, syncable) vs user (private, never synced).
- Community hub (`funko-upc-community` repo) emitting dated core-only update packets;
  builds on the existing `CatalogContribution` (`source = USER_MANUAL`).
- Monthly client scan pulls packets newer than last imported.
- Per-field conflict resolution: empty->fill; never-update flag->keep; else field
  policy "always update" or "ask me."
- Five open decisions (doc §7); five-phase build (doc §8), Phase 1 = golden-master
  base + preloader.

Relationship to §6: §6 (remote auto-update) is the lighter-weight delivery of the
enriched catalog; §7 is the full community/sync vision. §6 can ship first as the
foundation; §7 is the larger program. Do not build §7 until its architecture
decisions are resolved end-to-end.

---

# 8. FUTURE roadmap (F-XXX backlog)

Lower-priority enhancements, each implementable in one session. Completed items
removed. Format preserved from the former `FUTURE.md`.

## Auth & OAuth
- **F-AUTH-1: eBay Browse API (active listings).** Once `isEbayTokenValid()`, try a
  Browse API call before fallback; `PriceSource.EBAY_BROWSE`. Browse returns ACTIVE
  listings only, not sold comps — not a drop-in for sold-price data. Files:
  `PriceService.kt` (`fetchEbayBrowseApi`). Needs a real eBay CLIENT_ID. ~1 session.
  (Note: §1.10's `fetchEbayActive` HTML scrape and this share the active-listings
  goal; Browse API is the durable upgrade for both.)

## Prices & Market Data
- **F-PRICE-1: Pop Price Guide (free tier).** `GET popriceinfo.com/api/v1/search?q=`.
  Add as a tier between Channel3 premium and HobbyDB. ~1 session.
- **F-PRICE-2: Price history chart.** Persist snapshots; Compose Canvas line chart of
  last 12 fetches in Detail. ~2 sessions.
- **F-PRICE-3: Bulk price refresh.** "Refresh all prices" in Reports; rate-limited
  Flow (100ms). ~1 session.

## Collection Features
- **F-COLL-1: Tablet two-pane.** `WindowSizeClass` list+detail at >=840dp. ~2 sessions.
- **F-COLL-2: Custom tags/labels.** `tags: List<String>` on `funko::`; filter by tag.
  ~1 session.
- **F-COLL-3: Duplicate/variant detection on scan.** On owned-UPC match, check
  `isChase` differs; offer to record both with a shared `catalogRef`. ~1 session.
  (Overlaps §1.2 variant grouping — coordinate.)
- **F-COLL-4: Want-list restock alerts.** On `isVaulted` true->false in
  `CatalogRefreshWorker`, notify if the item is wanted. ~1 session. (Now meaningful
  with §1's real want list.)

## Platform & Integration
- **F-PLAT-1: Wear OS tile.** owned count / today's value / most-wanted. New `wear/`
  module. ~3 sessions.
- **F-PLAT-2: Android Auto want list.** Read-only top 5. ~2 sessions. Low priority.
- **F-PLAT-3: Assistant/App Actions.** "scan a Funko" opens scanner. ~1 session.

## Data & Sync
- **F-DATA-1: Dropbox backup.** `BackupProvider` interface + `DropboxBackupProvider`.
  ~2 sessions.
- **F-DATA-2: CBL -> Capella sync.** Swap to CBL EE; `Replicator` to Capella. Schema
  unchanged. ~1 session + account.
- **F-DATA-3: CSV import from other apps.** Column-mapping screen. ~2 sessions.

## Quality & Testing
- **F-QA-2: Instrumented Couchbase tests.** In-memory CBL; test save/delete/flow/stats.
  ~1 session.
- **F-QA-3: Crashlytics.** `recordException` in `CrashHandler`. ~30 min + Firebase.

## UI & UX
- **F-UI-1: Collection value over time.** Daily `value_history::` snapshots; 90-day
  Canvas chart. ~1.5 sessions.
- **F-UI-3: Animated scan line.** Cosmetic `infiniteTransition`. ~45 min.
- **F-UI-4: Item sharing card.** Stylised PNG via Canvas+Picture; `ACTION_SEND`.
  ~1.5 sessions.
- **F-UI-5: Scheduled dark/light.** Sunset/sunrise switch. ~1 session.

## Security
- **F-SEC-1: Play Integrity in Worker.** Integrity token as `X-Integrity`; validate
  in the Worker. ~1 session + Worker.
- **F-SEC-2: Biometric lock.** `androidx.biometric`; 5-min grace. ~1 session.
- **F-SEC-3: Certificate pinning.** OkHttp `CertificatePinner` for hobby-db/ebay.
  Risks breakage on cert rotation. ~30 min.

## Performance
- **F-PERF-2: Stats query cache.** `MutableStateFlow<CollectionStats?>`; invalidate on
  save/delete. ~30 min.
- **F-PERF-3: Paging for large collections.** `paging-compose` + LIMIT/OFFSET. Only
  valuable >2,000 items. ~2 sessions.

## i18n
- **F-I18N-1: Multi-language.** Extract strings (incl. `HelpContent.kt`, already
  constants) to `values/`; add locale folders. ~2 sessions + 1/language.

(Completed and removed: F-QA-1, F-PERF-1, F-PLAT-4, F-AUTH-2, F-UI-2.)

---

# 9. Required data pre-work (BLOCKS §1)

These are data defects in the current catalog/state that make §1 misbehave. Fix
before or as part of the §1 build.

1. **Umbrella-franchise suppression.** `franchiseSuggestion == "Disney"` catches
   165 records; it is not a completable target. Suppress umbrella franchises from
   completion (reuse the enricher's umbrella-console list:
   disney/animation/movies/marvel/...), or let the user hide a franchise. Without
   this, the grid shows meaningless "complete Disney" rows.
2. **Franchise-name normalization.** Verified bug: `Lilo and Stitch` vs
   `Lilo & Stitch` (tied to an undecoded `&amp;` entity) splits one franchise into
   two, producing counts that exceed 100% (owned 6 / total 2). Normalize franchise
   names (decode entities, canonical spelling) in the enricher or the join.
3. **Stale `group_pref` cleanup.** The backup contains
   `group_pref::FRANCHISE::Pop! Disney` with COMPLETE intent — "Pop! Disney" is a
   format/line, NOT a franchise (a pre-O5 artifact when franchise was seeded from the
   series tag). Delete `group_pref` intents keyed on format/line values rather than
   real franchises.
4. **Title entity decode.** Verified: `"Scooby-Doo &amp; Haunted Mansion"` — undecoded
   `&amp;` in a stored title. The enricher's `cleanTitles` decodes entities on build;
   any record predating that needs a re-import, or an app-side decode on display.
5. **`seriesList` is a stringified `Array{...}` blob on-device**, not real JSON.
   Any app-side logic reading individual series tags must parse that string format,
   not `JSON.parse`.

---

# 10. Cross-feature dependency map

```
§9 data pre-work ──► §1 Completion/Want (LEAD)
                       │
                       ├─ §1.2 variant grouping (folded in; validated rule)
                       ├─ §3 re-link field protection (shares franchise mapping)
                       └─ needs §4 step-1 priceSource reader for buy-state logic
§4 On-add price fill ──► step 1 (priceSource reader) is also the smallest unblock
§1 validated on-device ──► §6 remote auto-update ──► completes §1.7 auto-want
§5 regional currency ──► protects §4's eBay fallback path
§6 ──► (foundation for) §7 community distribution
```

Build order (recommended):
1. §9 pre-work items 1-4 (data correctness — without these §1 shows glitches).
2. §4 step 1 (priceSource reader — 30 min, unblocks buy-state + on-add fill).
3. §1 Collection Completion + Want (grouped), with §1.2 grouping and §3 field
   protection.
4. §4 steps 2-4 (on-add live fill).
5. §1 on-device validation -> §6 remote auto-update (completes the auto-want loop).
6. §5 regional currency.
7. §8 F-XXX backlog as desired; §7 when its architecture is resolved.

---

# 11. Completed Migrations & Hard Rules (REFERENCE — no action)

Folded in from the two former `docs/*_Migration_SPEC.md` files
(`PlayStore_Readiness_Migration_SPEC.md` and `CredentialManager_Migration_SPEC.md`,
both authored 2026-06-12). The execution plans in both are **DONE** — Sessions 5–10
completed every item. This section preserves the parts that retain forward value:
the HARD RULES that stop a future session from breaking things, and the verified
facts behind them. Nothing here is open work. (Note: the Credential Manager file's
own header still read "Specification / Phase 2" but the work shipped in Session 5 —
the header was never updated; §11.4 explains.)

## 11.1 Completion status (all done)
- 16 KB page-size (P0): CBL 3.2.1 → 3.2.4, CameraX 1.3.4 → 1.6.1 — Session 5.
- GoogleSignIn → AuthorizationClient (P1): per the CredentialManager migration — Session 5.
- Photo Picker (P1): `PickVisualMedia`, removed `READ_MEDIA_IMAGES`/`READ_EXTERNAL_STORAGE` — Session 6.
- P3 cleanup: `kotlinOptions` → `compilerOptions`, accompanist-flowlayout → Compose
  `FlowRow` — Session 6; `Icons.Default.ArrowBack`/`Logout` → `AutoMirrored`,
  `vibrate(50)` suppress fix — Sessions 9/10.
- CBL Collection API (P2): 107 call sites → `defaultCollection` across 12 files — Session 7.
- Keystore (P2): `security-crypto` EncryptedSharedPreferences → direct AES-256-GCM
  `AndroidKeyStore` (`SecureKeyStore`) — Session 8.

## 11.2 HARD RULES (do not violate — these prevent regressions)
- **Do NOT migrate to Couchbase Lite 4.0.x.** 4.0 (Oct 2025, CBL-7291/7299) REMOVED
  the database-level APIs and changes semantics beyond the 3.2.x Collection API this
  codebase now uses. CBL must stay on the 3.2.x train (≥3.2.3 for 16 KB). 4.x is a
  separate future migration, not a version bump.
- **CBL must stay ≥3.2.3 and CameraX ≥1.4.x** — below those, native libs are not
  16 KB-aligned and Play hard-rejects (policy effective Nov 1 2025 for apps targeting
  Android 15+; FunkoDex targets SDK 36).
- **Do NOT add `android:extractNativeLibs` / `useLegacyPackaging` workarounds.** They
  do not fix ELF alignment and legacy packaging is itself deprecated.
- **Do NOT bump Kotlin/AGP/Gradle to "fix" 16 KB.** The toolchain (AGP 8.13.2 /
  Gradle 8.13 / Kotlin 2.0.21) already satisfies every requirement; APK zip-alignment
  is automatic on AGP 8.5.1+.
- **Do NOT re-add `READ_MEDIA_IMAGES`/`READ_EXTERNAL_STORAGE`.** Photo Picker
  (`PickVisualMedia`) grants per-item access with no storage permission; re-adding the
  permission triggers Play's Photo & Video Permissions policy review. CAMERA stays
  (core scanning, Play-acceptable).
- **`inBatch()` correctly remains database-level** — it is a transaction wrapper, not
  deprecated, not part of the Collection API migration. Do not "migrate" it.

## 11.3 Verified facts (do not re-derive from memory)
- CBL 16 KB fix shipped in 3.2.3 (Couchbase engineering, official forum; reporter
  confirmed it cleared the exact `libLiteCore.so`/`libLiteCoreJNI.so` Play error).
- CameraX 16 KB fix in 1.4.x (`libimage_processing_util_jni.so`) — Google issue 351313880.
- ML Kit barcode 17.3.0 16 KB status was CONTESTED (conflicting Sept/Nov 2025 reports);
  17.3.0 is the latest bundled release; the documented escape hatch if it fails the
  alignment gate is the UNBUNDLED `com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1`
  (natives move to Play services, out of the APK).
- security-crypto deprecated entirely at 1.1.0-alpha07 (April 2025).
- The app has NO first-party native code (no `jni/`, no `externalNativeBuild`) — every
  16 KB concern is purely a dependency-version problem.
- CBL 4.0.x current is 4.0.4 (May 2026) — noted only to confirm it exists and is the
  thing to avoid, not adopt.

## 11.4 Drive Auth Migration (GoogleSignIn → AuthorizationClient) — DONE Session 5
Folded in from the former `docs/CredentialManager_Migration_SPEC.md` (authored
2026-06-12; that file's header still read "Specification / Phase 2 / unchecked
boxes" but the work SHIPPED in Session 5 — the header was simply never updated.
`DriveAuthManager.kt` exists and `DriveBackupWorker` uses it). Preserved here for
its hard rules and the worker token-lifecycle correctness notes, which remain the
authoritative guidance if that code is ever touched.

**The core finding (the framing trap):** the old `GoogleSignIn` API split into TWO
replacements — Credential Manager (`androidx.credentials`, AUTHENTICATION: who the
user is) and AuthorizationClient (`com.google.android.gms.auth.api.identity`,
AUTHORIZATION: scoped access + OAuth token). FunkoDex needs a Drive token for
`DRIVE_FILE`, which is AUTHORIZATION. **The migration uses `AuthorizationClient`
ONLY; Credential Manager was deliberately NOT added.** Anyone "fixing" this by
adding Credential Manager is rebuilding the thing that was correctly avoided.

**HARD RULES (Drive auth):**
- Use `AuthorizationClient` (authorization), NOT Credential Manager (authentication).
  Read this before assuming otherwise.
- The "Signed in as {email}" label was intentionally DROPPED ("Connected · Tap to
  back up now") — `AuthorizationResult` carries no account identity by design.
  Do not re-add an email label by bolting on Credential Manager.
- **Do NOT persist the Drive access token.** It is short-lived (1 hour);
  `AuthorizationClient` caches it internally. The worker calls `authorize()` fresh
  EVERY run. Storing the token buys nothing and adds attack surface.
- Drive REST client auth = `HttpRequestInitializer` Bearer header. `GoogleCredential`
  is deprecated — do not use it.
- No manifest change for Drive (consent uses a system `PendingIntent`, not a custom
  redirect like the HobbyDB/eBay PKCE flow). The HobbyDB/eBay `auth/` stack is
  SEPARATE and shares nothing with Drive — do not touch it during Drive work.

**Worker token-lifecycle correctness (the actual risk, where a careless change
breaks silently):** the old `GoogleAccountCredential` refreshed transparently; the
new model holds a raw 1-hour token. (1) call `authorize()` every run, never cache
across runs; (2) a `hasResolution()` result in the worker is NOT an error — the
grant lapsed; skip + notify, do not retry; (3) guard null/blank `accessToken` even
when `hasResolution()` is false (it is nullable — treat null as skip+notify, not an
NPE into retry); (4) a mid-backup 401/403 means the token went stale in flight —
clear and retry once; (5) `revokeAccess()` revokes ALL scopes and clears cached
tokens.

**Verified facts:** `play-services-auth` = 21.6.0 (Google authorization guide,
2025-10-27); `kotlinx-coroutines-play-services` added (pinned to coroutines 1.9.0);
on first `authorize()` `hasResolution()` may be true (consent `PendingIntent`), on
subsequent calls it returns the token with no UI (the silent path the daily worker
needs); Cloud Console needs only an Android OAuth client ID (package + SHA-1), no
Web client ID (no offline/server auth code used).

---

## Gap scan (v1.0)

- Want-state STORAGE (per-figure flag vs group `wanted` list) is left "finalize at
  build" — flagged, not yet decided. Decide before coding §1.11.
- `variantGroupKey` app-side vs enricher-side is a recommendation (app-side), not
  locked. Decide at build.
- Cherry-pick header (show "of total" or not) resolved in §1.8 (de-emphasize, not
  remove).
- `setTag` sub-band confirmed IN for v1 (§1.1).
- Krillin-class sculpt-vs-finish mis-grouping accepted as display-only (§1.2).
- Open: exact suppress-flag storage shape (per-figure field vs group list) — §1.6
  names both options; pick at build.
- Not covered here (intentionally): architecture/package layout (CLAUDE.md), test
  cases (FUNKODEX_TEST_PLAN_v1.0.md), session log (HANDOFF.md).
