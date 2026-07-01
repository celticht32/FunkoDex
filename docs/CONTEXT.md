---
project: FunkoDex / funko_enrich
branch: master
updated: 2026-06-30
last_session: 17 (validated on-device)
---

## Current Focus
Session 17 app code is pushed (streaming full backup/restore + golden-source relink, validated on-device). Resume the build roadmap: lock the grouping field, add the priceSource reader, then build the designed-but-unbuilt features.

## Active Tasks
- [ ] Grouping-field investigation: sample FINAL enriched data for a named set (Haunted Mansion), a mainline line, and a Minis set to lock WHICH field holds the specific set/property name. Unblocks relink field-mapping, variant grouping, set membership, want-list. Design against final data with rendered UI options for approval.
- [ ] §4 priceSource reader: add a reader for the `priceSource` field in toEnrichedRecord/EnrichedRecord/CatalogMapper so on-add live-price fill (TODO_app_autofill_prices) can act on 'none'.
- [ ] Then build in spec-priority order: variant hierarchy, regional currency, on-add price fill, remote catalog auto-update, browse-set want-list (all designed, none built).

## Blockers
- Build roadmap items gated on the grouping-field lock + priceSource reader landing first.

## Context (max 5)
- Grouping: franchiseSuggestion = L1 (from pcSeries, not raw series tag); setTag = L2 named set. Intent in group_pref::{LEVEL}::{key} as COMPLETE/CHERRY_PICK (see DEC-001/002/003).
- HARD INVARIANT: collection items use funko::{UUID}/funko::{upc}, NEVER catalog:: — this was the S17 bug class (see DEC-007b).
- Backup: normal backup EXCLUDES catalog/system; only exportFullBackup dumps catalog. Both streaming (Gson JsonReader, 500-doc batches) — never refactor to load-all (DEC-007).
- Counts (verified): bundled asset 23,940; golden master ~25,806 (~76% PC-priced); on-device post-S17 21,989 (DEC-006b).
- Toolchain VERIFIED: AGP 8.13.2 / Gradle 8.13 / Kotlin 2.0.21 / CBL 3.2.4 / Compose BOM 2024.09.00 / minSdk 26 / targetSdk 36. material3 is BOM-managed, not a literal pin (DEC-010).

## Release-prep (from S17)
- 1,404 cleared catalog images show placeholders. Full re-enrichment with the fixed enricher (now filters non-figure images) is the realistic repopulation path before release; then full backup -> extract final golden-master enriched file.
- Carried: S15 series-completion verification; SERIES_COMPLETION_SPEC section-9 + RELINK_FIELD_PROTECTION_SPEC unit tests; HobbyDB tier-4 pricing never verified on-device.

## Next Session
Lock the grouping field (sample final enriched data, rendered UI options for approval), add the priceSource reader, then build in spec-priority order. Confirm any Compose/material3 symbol against project usage before writing — material3 is BOM-managed.
