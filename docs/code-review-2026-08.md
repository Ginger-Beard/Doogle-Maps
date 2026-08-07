# Doogle Maps — code review, August 2026

> **Archived. Do not work from this file.**
>
> Its headline finding — §0, the run being unable to complete because a stop only finished when
> every patch was watched being planted — is **fixed**: completion is derived from patch state and
> polled once a tick. §1.2, fetching a mid-run contract's seed, is **fixed** too, though not for
> the reason this review supposed; see `docs/run-flow.md`.
>
> The three items that were still live — the quadratic `patchesWanting`, `PatchTypePanel.rows`
> never being pruned, and the oversized classes — were re-verified against the tree and moved into
> `docs/TODO.md`, which is where open work lives. All three were still real; two had got worse.
>
> Kept only because the reasoning in §0 and §6 is worth reading. Delete it when it stops being.

---


Whole-repo review. `src/main` 30,437 lines / 121 files, `src/test` 12,986 lines / 49 files,
421 tests. Java 11, Gradle, RuneLite plugin, unpublished.

Aimed at the stated problem — **no stable farm run through all patches yet** — rather than spread
evenly. That is a specific class of defect and it turns out to have a specific cause.

Companion document: [`docs/run-flow.md`](run-flow.md) traces the player's click-and-move loop
as two flowcharts, with the stall points marked. Read that first; this explains it.

---

## 0. Headline: the run cannot complete, and it is one bug

**Three symptoms, one root cause.** A stop completes only when every patch at it is marked
serviced, and a patch is marked serviced only by an *event* that fires on one specific transition.
Any patch the player does not fully plant is never crossed off, and the stop never ends.

```java
// RunStop.java:52
public boolean isComplete()
{
    return serviced.size() >= patches.size();
}
```

```java
// PatchInteractionTracker.java:212 — the only caller of markServiced
if (changed && previousValue != null && previousValue != varbitValue
    && !isActionable(decoded))
{
    runPlanner.markServiced(patch);
}

// :293 — HARVESTABLE, DISEASED, DEAD and EMPTY are all still "actionable",
// so the only non-actionable state is GROWING-a-crop.
```

Together: **the run advances only if you fully plant every patch at every stop.**

### The four ways that fails

| # | Situation | Why it never completes |
| --- | --- | --- |
| 1 | **Harvest-only run** | A picked-clean bush or fruit tree still decodes as `HARVESTABLE`. Never GROWING, never serviced. |
| 2 | **No seed for a patch** | `GuidePlan.forPatch` returns `[]` when `chosen == null` (`:158`). Patch stays `EMPTY`, nothing to click, never serviced. |
| 3 | **Cannot clear a patch** | Dead tree, no axe → stays `DEAD`. Same for anything deliberately skipped. |
| 4 | **Patch turns out to need nothing** | `RunPlanner.isActionable` returns `true` for a never-seen patch (`:464`, *"Worth a look: it may well be empty"*). Arrive, find it growing, do nothing — it is in the stop and can never leave it. |

**#1 is verified empirically, not inferred.** I decoded the varbit ranges:

```
BUSH / FRUIT_TREE: 19 states decode as HARVESTABLE with stock 0
  varbit 11 -> Redberry  HARVESTABLE stage=0
  varbit 22 -> Cadavaberry HARVESTABLE stage=0   … etc
```

`GrowthTimer` documents the same fact from the other side: *"A fruit tree holds at most six fruit
but has seven states, because 'no fruit on the tree' is one of them."* So **every harvest-only stop
stalls, always** — and harvest-only is one of the headline run types.

### Why it reads as a freeze rather than a stuck patch

`retarget()` is called only from `start()`, `leaveBank()` and `markServiced()`-on-stop-complete.
And `retarget` deliberately clears the router while you are standing in a region with outstanding
work (`RunPlanner.java:996`). So a stalled stop means **no route drawn and no next instruction** —
which looks like the plugin having died, not like a patch it is waiting on.

### The contradiction underneath

`GuidePlan`'s class comment states the design rule explicitly:

> deliberately a **pure function of the patch's current state** — no progress counter, no "which
> step am I on" stored anywhere. […] A stored step index has to be kept in step with a player who
> does things out of order, walks away, or gets the compost on before you told them to.

`RunStop` does exactly the opposite: it accumulates a `serviced` set, advanced by a single event.
The two halves of one feature are built on opposite principles, and the accumulating half is the
one that strands the run.

### Recommended fix

Make completion **derived**, matching the half that already works:

```java
/**
 * Whether anything here still wants doing.
 *
 * <p>Asked of the patches rather than counted. A serviced set is a progress counter and can only
 * be advanced by an event, so a patch the run never acts on is never crossed off and the stop
 * never completes however the player plays. Deriving it means a stop is done exactly when there
 * is nothing left to click — the rule GuidePlan already applies per patch.
 */
public boolean isComplete()
```

Implement as "no patch here still produces steps". That subsumes all four cases at once. Keep
`serviced` if it is useful for ordering, but stop making completion depend on it.

**Watch the second-order effect:** a patch produces no steps when you are not there either, so this
must be gated on being in the region — which `computeStepsHere` already does.

**This is the single highest-value change in the repo right now.** Everything below is smaller.

---

## 1. Other run-pipeline findings

### 1.1 `getCurrentStep()` is the first list element, and contract errands now jump the queue

`GuideTracker.getCurrentStep` returns `steps.get(0)`, and I moved the contract hand-in / take-next
to `steps.add(0, …)` earlier today so Jane comes before the guild's other patches.

That is right for the instruction, but `GuideOverlay` highlights the *working patch* chosen by
`chooseWorkingPatch`, which is unaware of the insertion. So the panel can say "hand your cactus to
Jane" while the world outline sits on a herb patch. Worth checking in-client; the fix is to have
the overlay follow `getCurrentStep().getPatch()` rather than the working patch.

### 1.2 A contract taken mid-run has no way to fetch its seed

Documented in `TODO.md` today. Taking the next contract moves its patch into the contract group
immediately, so the seed appears in the loadout — but the supply leg has already finished and
nothing reopens it. You are told to plant something you are not carrying.

### 1.3 `markServiced` ignores the first reading of a patch

`previousValue != null` means the very first varbit seen for a patch this session cannot mark it
serviced. Narrow — you would have to plant a patch on the same tick the plugin first sees it — but
it is another way the counter can miss, and it disappears entirely under the §0 fix.

---

## 2. Security

**Nothing to report, and little surface to have anything on.** No credentials, no network calls of
its own, no shell execution, no deserialisation of untrusted input. Swept for `password|api.key|
secret|token|Runtime.getRuntime|ProcessBuilder|exec(` — the only matches were Gson `TypeToken`
declarations.

Two things worth naming as *correctly* handled rather than absent:

- **File writes are bounded and opt-in.** `HarvestLog.appendCsv` and `GeomancyProbe` write under
  `RuneLite.RUNELITE_DIR`, both behind config flags, both failing silently rather than interfering
  with the game. `GeomancyProbe` caps itself at `MAX_SNAPSHOTS`.
- **Another plugin's config is read, never written.** Time Tracking's keys are read defensively
  with try/catch and parse guards (`ContractState.fromTimeTracking`, `PatchStateStore.backfillFrom`).
  Reading a sibling plugin's storage is the risky pattern here and it is done read-only.

The one thing I would add before publishing: `Tooltips.text()` escapes `&<>` for item names that
come from the game. Nothing else interpolates game strings into HTML — I checked the other tooltip
builders and they interpolate our own text or numbers.

---

## 3. Architecture

### 3.1 What is genuinely good

- **The derived-not-stored principle** in `GuidePlan`, and the reasoning written next to it. §0 is
  a failure to apply it consistently, not a failure to have it.
- **`PanelRenderTest`** paints the real component tree under RuneLite's LAF and asserts on pixels.
  Uncommon and correct.
- **Deriving data rather than listing it** — `REGROWS` from `getRegrowTickrate()`,
  `Locations.isEnabled` from the region name, and now the disease list cross-checked against
  `ProtectionPayment`. Each one records the hand-written version that was wrong first.
- **`BankFilter`'s injector comment** — why constructor injection fails loudly and optional field
  injection fails *silently*. The highest-value comment in the repo.
- **One-way dependencies with a documented lock order** (`RunPlanner:80-87`).

### 3.2 Four classes past readable size

`RunLoadout` 1,186 · `DoogleMapsConfig` 1,172 · `GuideTracker` 1,078 · `RunPlanner` 1,057.

`RunLoadout` splits cleanly — eight `addX` builders that barely interact, each taking the run's
types and appending to a list. `DoogleMapsConfig` is ~700 lines of mechanical toggles and cannot
shrink without RuneLite gaining a dynamic config API.

Not urgent, and I would not do it before §0. But `addTeleports` is where a performance bug hid for
a while, and that class of thing is hard to see in a thousand-line file.

### 3.3 `PatchRules.java` (705 lines) — not examined

Flagging honestly: I have not read it in any of these passes.

---

## 4. Performance

Largely addressed earlier today — `GrowthTimer` was reaching config 7–14 times *per patch* with no
caching, inside loops that run per tick. Fixed with an invalidatable cache.

Remaining, in order:

1. **`GuideTracker.computeStepsHere` is O(patches²) per tick.** `patchesWanting` loops the stop's
   patches inside a loop over the same patches. At the Farming Guild that is 121 projections a
   tick. Now much cheaper per projection, but the shape is still quadratic.
2. **`PlantingGroups.isSplit` walks the type's patch list**, called per patch from `groupFor`. I
   tried caching it and reverted — see §6. `hasAnyProtected` now short-circuits, which is most of
   the win with none of the staleness risk.
3. **`PatchTypePanel.rows`** is never pruned; toggling a location filter leaves orphaned `PatchRow`
   instances. Bounded and small, but it is a leak.

---

## 5. Tests

421 tests, and the suite is better than its size suggests because it is organised **by behaviour**
rather than by class — `SupplyRoutingTest`, `AllocationAgreementTest`, `ProtectedTabTest`,
`LeprechaunErrandOrderTest`. A file-per-class audit would report most classes as untested and would
be wrong.

The suite has also earned its keep repeatedly today: it caught a bad `PlantingGroups` cache, a
`needsSupplyTrip`-vs-`suppliesOutstanding` confusion that would have stranded runs at the bank, and
two stale design assertions.

**The gap that matters:** nothing tests **stop completion**. There is no test that a stop ends, or
that a run advances from one stop to the next under realistic play. That is precisely the area
§0 breaks in, and it is why a bug this fundamental survived to this point.

Worth adding alongside the §0 fix:

- a harvest-only stop completes after the crop is picked;
- a stop with an unplantable patch still completes;
- a two-stop run reaches the second stop.

---

## 6. What I got wrong in this review cycle, recorded deliberately

Two entries, because both say something about where to be careful.

**A `PlantingGroups` cache I wrote and reverted.** `groupFor` looked structurally identical to the
`GrowthTimer` hot path, so it got the same treatment, wired to all three change signals. A test
failed: `structureChanged()` is public and rebuilds the strip directly, and no signal reaches the
cache on that path. The failure would have been *the protected herb tab silently not appearing
after an unlock*. Reverted; `hasAnyProtected` short-circuits instead.

> **Rule:** cache a computation when each input has exactly one writer that can invalidate. Weigh
> staleness by how badly it reads, not how often it happens.

**A hand-written disease list, wrong twice.** I transcribed it from a wiki summary and it missed
calquat and redwood — both of which the plugin's own `ProtectionPayment` table already knew about,
because you pay those gardeners. *Nobody pays to prevent something that cannot happen.*

> **Rule:** when a hand-written list must agree with a fact, find the table that already encodes
> that fact for another purpose and assert against it. `RunOptionCoverageTest` now does, and I
> checked the assertion actually fails when calquat is removed rather than assuming it bites.

---

## 7. Documentation

`NOTES.md` (2,470 lines) is doing two jobs: **API traps and design invariants**, which a
contributor could not derive and needs; and **fix narratives**, which are what `git log` is for and
are the half that rots. Suggested split test: *would this still be worth reading if the bug had
never happened?*

`TESTING.md` (1,295 lines) is a manual plan for a project with 421 automated tests. Much of it now
duplicates a unit test. Rule of thumb: appearance is genuinely manual, logic probably is not.

The class-level Javadoc is consistently stronger than the markdown, because it sits next to what it
describes. When trimming, protect that.

---

## 8. Priorities

**Before anything else**

1. **§0 — derive `RunStop.isComplete`.** This is what is stopping a stable run. Everything else can
   wait behind it.
2. **§5 — tests for stop completion and run advancement**, written with the fix.

**Next**

3. §1.1 — check the overlay follows the current step after the contract reorder *(in-client)*
4. §1.2 — fetching a mid-run contract's seed
5. §4.1 — the quadratic `patchesWanting`

**When convenient**

6. §3.2 split `RunLoadout` · §4.3 prune `PatchTypePanel.rows` · §7 split `NOTES.md`
7. Read `PatchRules.java`, which no review pass has covered

---

## 9. Confidence

**Verified by execution or by reading the relevant source:** §0 entirely — the varbit decode was
run, `RunStop`, `PatchInteractionTracker` and `RunPlanner.retarget` were read directly. §2's sweep
was run. §5's counts are from the build.

**Read but not executed:** §1.1 and §1.2 — the reasoning follows from the code, but both want
in-client confirmation, and §1.1 in particular is a claim about drawing that I cannot see.

**Not examined:** `PatchRules.java` (705 lines), `GeomancyProbe` beyond its file-writing path, the
`tools/` Python generators, and the farming *domain* arithmetic — yield curves, XP tables, disease
formulae. `CropYieldModelTest` and `HarvestStatsPanelTest` suggest you have that ground better
covered than I would.

**A bias to declare:** I went looking for a systemic cause of run instability and found one, which
is the outcome I was predisposed to reach. The evidence is strong — a decoded varbit table and two
methods that plainly cannot agree — but if fixing §0 does not give you a stable run, the next place
I would look is `SeedAllocation` and the seed/patch pairing, which this review does not cover.
