# Doogle Maps — code review, August 2026 (second pass)

> **Status, written the same day.** Priorities 1–4 were fixed immediately after the review ran:
> §1 (the exemption set is now derived for every stop, every tick — see
> `GuideTracker.reportIdlePatches` and `SkippedStopStaysFinishedTest`), §2 and §3 (JDWP on
> loopback; the chathead fetcher validates every wiki-sourced string against an allow-list and
> checks PNG magic bytes), §4 and §5 (`HarvestHistory.load` runs on the executor;
> `harvests.csv` is per-profile via `HarvestFiles`, which adopts the old shared file once), and
> the `ProfileJsonStore` base now carries seven stores, with every `@Inject` constructor
> package-private. What remains open — the `RunScope`/`RunSnapshot` refactor, §6's latent race,
> and the small items — was moved into `docs/TODO.md`, which is where open work lives.

Whole-repo review, 2026-08-07. Follow-up to `docs/code-review-2026-08.md`, whose headline
finding (§0, derived stop completion) was fixed before this pass ran. Baseline at review
time: build green, 548 tests, 0 failures. Seven parallel review passes — four code-quality
slices by package, security, architecture, tests — plus a docs pass.

Findings already tracked in `docs/TODO.md` (quadratic `patchesWanting`, `PatchTypePanel.rows`,
oversized classes, deprecated APIs) are excluded rather than re-reported.

## Overall

An unusually healthy codebase for its size: a single-sourced write path (`capture/` is the
only place patch state mutates), a generated leaf `data/` package pinned by tests, a UI that
never touches game state directly, causal javadoc that explains the bug behind each design,
and a disciplined 548-test suite. What remains is concentrated, not diffuse: one confirmed
run-stranding bug, two security items in tooling/build config, and an architectural knot
between three coordinator classes that is the root cause of most of the rest.

## HIGH — one confirmed functional bug

### 1. Skipped-patch stop completion evaporates when you leave the stop

Multi-stop runs re-route to unworkable patches.

`RunPlanner.nothingToDo` (`RunPlanner.java:128`) is the mechanism that lets `isComplete()`
(`:616-630`) treat a patch with no step (no seed allocated, no axe, etc.) as not blocking.
But `GuideTracker.computeStepsHere()` clears it unconditionally every tick
(`GuideTracker.java:313`) and repopulates it only for the stop the player is physically
standing in (`:374`). Meanwhile `getRemaining()` (`RunPlanner.java:1237`) re-derives
completion for **all** stops on every poll.

Failure: a Falador stop "completes" because its seedless herb patch was reported
nothing-to-do; you travel to Catherby; the exemption is gone, `isComplete(Falador)` is false
again, Falador re-enters `getRemaining()`, and `retarget()` routes you back — indefinitely.
It also inflates the on-screen "stops remaining" count the moment you leave any
skip-completed stop. Verified directly in both files; the clearing comment at
`GuideTracker.java:307-311` names the trade-off it made without noticing this consequence.
This partially undoes the previous review's §0 fix for exactly the blocked-patch cases that
fix was meant to cover.

**Fix direction:** completion exemptions must live per-stop and survive absence — record
"blocked as of last visit" keyed by stop, rebuilding only the current stop's entry each
tick, and clearing a patch's exemption when its state changes or the player returns. Add
the missing regression test with it: a two-stop run where stop one contains an unworkable
patch stays completed after the player leaves. Nothing in the suite covers this today.

## MEDIUM

### 2. Debug JVM listens on all interfaces

`build.gradle:63`: `address=*:5005`. JDWP is unauthenticated remote code execution by
design, and `./run-client.sh --debug` attaches it to a client logged into a real account.
Change to `address=127.0.0.1:5005`.

### 3. Wiki-sourced text is compiled into the plugin unescaped

`tools/fetch_chatheads.py:231-232, 271-274`. MediaWiki page titles (publicly editable) are
interpolated into Java string literals in generated source with no escaping; the
`--from-tsv` path emits `NpcID.%s` with no quotes at all. Hard-fail on names not matching
`^[A-Za-z0-9_ '\-()]+$` (and `^[A-Z0-9_]+$` for constants). Related hardening: check the
image URL host and PNG magic bytes before writing to `src/main/resources` (`:177,
:337-340`).

### 4. Blocking file I/O on the client thread at login

Found independently by two review passes. `HarvestHistory.load()`
(`HarvestHistory.java:148-244`) reads up to 50k rows and, on the trim path, rewrites the
whole file, called from `DoogleMapsPlugin.load()` (`:689`) which runs on client-thread
event handlers (`onGameStateChanged`, `onProfileChanged`). The class's own javadoc says its
purpose is to avoid blocking on I/O — the guarantee covers repaints but not this path.
Dispatch load/trim to the executor and publish when ready.

### 5. `harvests.csv` is global, not per-RS-profile

`HarvestLog.java:703` and `HarvestHistory.java:248` resolve one shared
`RUNELITE_DIR/doogle-maps/harvests.csv`, while every sibling store correctly uses
`getRSProfileConfiguration`. Multi-account users get all accounts' harvest data pooled into
one Stats tab.

### 6. `GuideTracker.stepsFor(FarmPatch)` races the tick if ever used

`GuideTracker.java:1119` reads the unsynchronized `allocations` HashMap and its javadoc
documents a Swing caller. Dead code today, a `HashMap` race the day a panel is wired to it
as intended. Delete it or make it read a published snapshot.

## Architecture

The load-bearing findings, in the order to act on them:

- **The planner/guide/loadout three-way knot is the root problem.** `RunLoadout` calls into
  `RunPlanner`; `RunPlanner` holds `Provider<RunLoadout>` purely to dodge a Guice cycle
  (`:187`); `GuideTracker` pushes conclusions back via `setNothingToDo`. Three classes, one
  component, all past 1,300 lines — and finding §1 lives precisely in that back-channel.
  Extracting a per-tick `RunScope` ("which patches are actionable / blocked for this run"),
  computed once by a coordinator and consumed by all three, removes the Provider, the
  setter, and the bug's habitat at once.
- **`RunPlanner` needs the snapshot pattern `GuideTracker` already has.** Nine
  `synchronized` methods, five called from the EDT on every panel refresh, contending with
  the client thread on one monitor whose discipline is maintained by javadoc alone. Publish
  an immutable `RunSnapshot` per tick, like `GuideStatus`.
- **Fourteen stores hand-roll the same ConfigManager+Gson persistence with *inconsistent*
  locking** — `PatchStateStore` syncs save but not load, `HarvestStatsStore` /
  `DiseaseStatsStore` the reverse, `AvailabilityProfile` neither. Copy-drift, not
  decisions, and load runs on profile-switch while save runs from Swing. Extract a
  `ProfileJsonStore<T>` base; the locking becomes one decision made once.
- **Make `@Inject` constructors package-private instead of private.** 29 of 59 test files
  build subjects by reflection, and a shared ~15-line `construct()` helper is copy-pasted
  into 18 of them. Package-private constructors turn signature drift into compile errors,
  delete the reflection, and lower the cost barrier that has left the 1,517-line
  `GuideTracker` with no direct test.
- Smaller but worthwhile: typed `PatchKey`/`GroupKey` wrappers (three string key-spaces
  currently meet with no compiler help); a `PluginComponent` multibinding to replace
  `DoogleMapsPlugin`'s four hand-synced register/unregister/reset/load lists; injecting
  sub-panels so `DoogleMapsPanel` drops its 24-parameter conduit constructor; moving the
  statistical-significance thresholds out of `HarvestStatsPanel` into the `validate` types
  so the policy is testable without Swing.
- **Dead code:** `route/InventoryPlan.java` — 188 lines of seed-box/slot accounting
  referenced only by its own test. Wire it up or delete it.

### What the architecture does well

Single-sourced writes (only `capture/` mutates patch state, verified by grep); `data/` a
true generated leaf pinned by `FarmingDataTest`; the UI/game-state boundary backed by
`PanelRenderTest`; javadoc that records causation, not description.

## Testing

The suite is genuinely high quality — behavior-organized, message-rich assertions,
disciplined mocking, and `PanelRenderTest`'s render-and-assert approach is a standout. The
gaps that matter, in value order:

1. The regression test for §1 (stop stays complete after leaving).
2. `PatchInteractionTracker.isGrowthTick` (`:352`) — pure, branchy, the core of the capture
   pipeline, zero tests, zero mocking needed.
3. `HarvestCsv` round-trip with reordered/subset headers — the exact regression its own doc
   comment warns about.
4. `ui/Prices` arithmetic — pure, user-facing numbers, untested.
5. `BankFilter` state machine — 592 lines, two production bugs already on record, no
   regression coverage.

Also: the reflection `construct()` helper duplicated across 18 test files should be one
shared class, and `ContractHandInOrderTest` / `LeprechaunErrandOrderTest` reach private
methods by name via reflection — promote those seams or extract the logic.

## Low / hygiene

- `README.md:7` references `docs/images/in-game.png`, which doesn't exist yet — the hero
  image renders broken. Known (`DEVELOPMENT.md` has the capture plan), but live.
- `latest.release` RuneLite dependency with `mavenLocal()` first — non-reproducible builds;
  pin and demote `mavenLocal`.
- `tools/__pycache__/*.pyc` committed; no `__pycache__` entry in `.gitignore`. Likewise
  `.claude/settings.local.json` is untracked but not ignored.
- `HarvestCsv.clean()` (`:225-228`) only strips commas — not exploitable today (all string
  columns are compile-time enum names, verified), but strip newlines and neutralize
  `=`/`+`/`-`/`@` prefixes so a future game-text column doesn't become injectable silently.
- Cosmetics: `DataTable.shortNumber` renders 999,500–999,999 as `"1000k"` (the width
  problem the M branch was added to fix); `describeExpectedValue` doesn't handle negative
  profit; `SeedInventoryStore.java:585` indentation; level 1–9 band labeled `"1"` against
  the N-to-N+9 convention; `BankHighlightOverlay.withdrawCounts()` should gate on
  `Need == WITHDRAW` explicitly rather than incidentally.

## Docs

Causal javadoc, `design-principles.md`, and the "roads not taken" section are exemplary.
Two gaps: the broken README image above, and `docs/TESTING.md` is purely the manual
in-client QA script — nothing documents the *unit* suite's conventions (the `construct()`
pattern, real-objects-over-mocks, render guards). A short conventions preamble would pay
for itself at onboarding.

## Priorities

1. Fix §1 (per-stop `nothingToDo` persistence) + its regression test.
2. The two one-line-ish security fixes: JDWP to `127.0.0.1` (§2), input validation in
   `fetch_chatheads.py` (§3).
3. Move `HarvestHistory.load()` off the client thread (§4) and scope `harvests.csv` per
   profile (§5).
4. `ProfileJsonStore<T>` extraction + package-private constructors.
5. The `RunScope` / `RunSnapshot` refactor — it's what makes the big three classes safe to
   keep growing.
