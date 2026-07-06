# S21 Upload Manifest

Copy this tree over your local repo root (`C:\Downloads\Development\FunkoDex\`).
All paths are repo-relative. Then commit and push to `master`.

## Code (changed — already built & installed on device)
- app/src/main/java/com/funkodex/network/FunkoLookupService.kt      (searchByName junk filter — DEC-021)
- app/src/main/java/com/funkodex/data/repository/FunkoRepository.kt  (+ findCollectionItemForCatalog — DEC-022)
- app/src/main/java/com/funkodex/ui/screens/prescan/PreScanViewModel.kt (+ NameSearch state/actions — DEC-022)
- app/src/main/java/com/funkodex/ui/screens/prescan/PreScanScreen.kt (+ NameSearchPanel / PreScanMatchRow — DEC-022)

## Tests (new/updated — pure-logic unit tests, no CBL/Android runtime)
- app/src/test/java/com/funkodex/network/FunkoLookupServiceTest.kt   (+ 6 actionability-filter cases)
- app/src/test/java/com/funkodex/ui/screens/prescan/PreScanBadgeLogicTest.kt (new — OwnStatus mapping)

Run: `gradlew testDebugUnitTest`

## Docs / state
- CLAUDE_STATE_FunkoDex_S21.md   (new — current checkpoint; supersedes S19 "enrichment next")
- HANDOFF.md                     (updated — standing state + next focus)
- CHANGELOG.md                   (updated — S21 [Unreleased] entry)
- docs/DECISIONS.md              (updated — DEC-020, DEC-021, DEC-022 added)
- FUNKODEX_TEST_PLAN_v1.0.md      (updated — A10b name-check verified; A3a filter note)

## Data backup (NOT in this archive — already delivered separately)
- FunkoDex_LINKED_20260706_linked.zip  (restore via Settings > Restore full)

## Not compiler-verified here
Gradle can't fetch its distribution in the build sandbox, so these files were
verified by symbol/brace inspection against the live repo, not compiled here.
Build + install already succeeded on-device for the four code files. The two
test files are new since that build — run testDebugUnitTest to confirm green.

## Suggested commit message
    S21: owned↔catalog linking, manual-search junk filter, name-based
    pre-purchase check; close enrichment workstream (DEC-020/021/022)
