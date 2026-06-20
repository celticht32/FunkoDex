# Session Bootstrap — read this first

This file is the entry point for any new Claude session working on FunkoDex. It is
**evergreen**: it describes process, project layout, and access mechanics that do not
change session to session. It deliberately contains **no** session numbers, file
counts, or version pins — those live in the state files below and would rot here.

If anything in this file conflicts with reality (an access method changed, a repo
moved), trust reality and update this file.

## 1. Read these, in order, before doing anything

1. **`CLAUDE.md`** — architecture, package layout, key design decisions, and the
   current state summary ("What this is"). The single source of truth for how the
   codebase is built and why.
2. **`HANDOFF.md`** — dated session log, current state, **Next session focus**, and
   the repo/local-path/toolchain facts (see its "Project" block — do not duplicate
   those here; read them there so there is one copy).
3. **`TEST_TRACKER_v2.0.md`** + **`COMPLETE_TEST_PLAN_v2.0.md`** — what is tested,
   what is only specified, and the per-session changed test surface.
4. Any `*_SPEC.md` relevant to the task (e.g. `RELINK_FIELD_PROTECTION_SPEC.md`,
   `docs/*_Migration_SPEC.md`) — specs for work that is designed but may not be
   fully built or tested.

The code's true state is whatever the repo says. Treat your training data as
possibly stale for anything version- or symbol-specific.

## 2. The two repos

This project spans **two** separate GitHub repos under `github.com/celticht32`:

- **`FunkoDex`** — the Android app (this repo). Branch: `master`.
- **`funko_enrich`** — the Node.js enrichment pipeline (`enrich.js`) that scrapes
  HobbyDB / funko.com / PriceCharting and produces `funko_data_enriched.json`,
  the enriched catalog the app imports. Branch: `main`. MIT, © 2026 Chris Ahrendt.

The enriched JSON is **produced** in `funko_enrich` and **consumed** in `FunkoDex`
(Settings → Catalog → Import Enriched Catalog). The bundled `app/.../assets/
funko_data.json` is only a seed; the live Couchbase catalog is the source of truth.

Golden-master build path: run `enrich.js` → import the enriched JSON (catalog import
is last-enricher-wins) → catalog is at max quality. The master ships catalog-only
with an empty user collection; re-link and field-protection are on-device runtime
features, not part of the master.

## 3. Reading repo state reliably (access mechanics)

GitHub's normal access paths are unreliable from this environment:

- `raw.githubusercontent.com` — **blocked** (robots/automation disallowed).
- GitHub REST API (`api.github.com/.../contents`) — **rate-limited** unauthenticated;
  fails fast.
- The GitHub **web file view** (`github.com/.../blob/...`) — fetchable, but can serve
  a **stale cache** (observed: a CLAUDE.md cached dozens of commits behind). Do not
  trust it for "is this current."

**Use the codeload tarball — it is the reliable, current source:**

```
curl -sL "https://codeload.github.com/celticht32/FunkoDex/tar.gz/refs/heads/master" -o live.tar.gz
tar xzf live.tar.gz   # extracts to FunkoDex-master/
```

For `funko_enrich`, swap the repo name and use `refs/heads/main`.

This is what you diff against to confirm what is actually on the remote.

## 4. Verify-against-live workflow (do this, every time)

This project's working rhythm depends on never claiming parity from a snapshot:

- **Before editing**, re-fetch the file you are about to change from a fresh codeload
  tarball. Do not edit against a copy pulled earlier in the session.
- **After the user pushes**, re-fetch a fresh tarball and `diff` your generated files
  against live to confirm they match byte-for-byte. Report IDENTICAL / DIFFERS.
- **For version-pinned symbols** (Compose/material3, Kotlin, Android libs, CBL), do
  not infer API names/signatures from training data. Verify each against the
  project's existing usage first, then versioned docs. If a symbol can't be
  verified, flag the line rather than guessing.
- **Brace/structure sanity** on any non-trivial Kotlin edit: strip comments/strings
  and confirm `{`/`}` balance before declaring done (the environment can't run the
  real compiler — the user confirms "compiles" separately).

## 5. Build / environment facts that don't change

- Platform is **Windows**; give shell commands in cmd/PowerShell syntax
  (`copy`, `del`, `C:\path\...`), not bash.
- The Gradle toolchain (AGP/Gradle/Kotlin/CBL/CameraX versions, min/target SDK) is
  pinned — exact values are in `HANDOFF.md`'s Project block. This environment cannot
  run that toolchain, so "compiles" is always the user's local confirmation, never a
  claim made here.
- Code deliverables: present individual files with their destination paths under
  `app\src\main\java\com\funkodex\...`. Package multi-file handoffs as `tar.gz`.

## 6. End-of-session ritual

When work is done and the user has pushed, update the state files so the *next*
session inherits everything:

- `CLAUDE.md` — bump the state summary + package tree if structure changed.
- `HANDOFF.md` — add a dated session entry, update **Next session focus**, append the
  session to the sessions-completed line.
- `TEST_TRACKER_v2.0.md` — add a "Session N changes" block describing the new/changed
  test surface.
- `CHANGELOG.md` — add an entry (Keep a Changelog format) if user-facing.
- `README.md` — only if a user-facing feature changed.

Then have the user push and re-diff against live to confirm parity. This bootstrap
file itself should only change if the *process* changes — not each session.
