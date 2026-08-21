# Doogle Maps — architecture review, August 2026

Whole-repo **architecture** review, 2026-08-17, at `244bed4`. 278 Java files, ~52,000 lines of
main source across ten packages, 1,133 tests in 119 test classes.

This is a different pass from the three code reviews of 2026-08 (`code-review-2026-08.md`, `-b`,
`-c`, deleted from the tree and readable at `c108d27`). Those looked for defects. This one looks
at **shape**: where the boundaries are, which way the dependencies run, what is holding the
concurrency together, and which of the large classes have a seam worth cutting. Two findings
below are behavioural, and both are consequences of shape rather than of a mistake in a line.

Excluded rather than re-reported, per the standing rule: everything open in `docs/TODO.md`,
`todo.txt` and `open-issues.txt` — the resource-monitor dev wiring, the per-patch location
harvest, the teleport deadlock, the unverified in-client items, the seed-per-type question. All
three files were searched for each finding below. That search caught one: the implicit tick
ordering is already in `open-issues.txt`'s DEFERRED list, so §2 reports only the two specifics
that note does not cover and leaves the deferred decision alone.

Security architecture is not re-examined: review 08b closed it — no network calls, no secrets, no
unsafe file IO in `src/main`, JDWP bound to loopback — and nothing in the structure has moved
since. Scalability in the web sense does not apply to a client plugin; the resource question that
does apply is per-tick cost and lock traffic on the client thread, and that is §3.

---

## Overall

**The architecture is sound and the layering is real.** Ten packages, a genuine leaf (`data`), a
persistence base class that ended a dozen copies of the same forty lines, a snapshot boundary
between the client thread and the EDT, and an inter-plugin integration that is a message bus
rather than reflection. Nothing here is accidental architecture; almost every boundary has a
class note saying what it is for and what it replaced.

The pressure is in one place, and it is the same place twice. **`GuideTracker` (4,041 lines) and
`RunPlanner` (3,769) have each absorbed a second responsibility that has its own state, its own
lifecycle and no owner.** For `GuideTracker` that is the run's own lifecycle: the boundary at which run-scoped state is
cleared is an unlabelled branch inside `reportIdlePatches()`, while the method named `reset()` is
shutdown-only — a distinction that has already produced one shipped bug and that this review
itself got wrong on the first pass. For `RunPlanner` it is the run state machine sharing a monitor
with the pure planning queries. §4 names both.

The remaining structural hazard is **the tick's implicit ordering** — fifteen `GameTick`
subscribers, one declared priority, and an order that falls out of fully-qualified class names.
The owner has already seen this and deferred it deliberately. §2 therefore reports only what the
deferral does not cover: that `state.*` ticks *after* `GuideTracker`, so the player's own position
is a tick old at all five sites that read it, and that because the sort key is the FQN it is the
**package** that is load-bearing, not the class name — which is a trap sitting directly under §1's
recommendation.

---

## 1. Layering and package dependencies

Built from every `import com.dooglemaps.*` in `src/main`, collapsed to package pairs. Read down
the layers; the count is import sites, not call volume.

```
data      ← state 56 · ui 39 · guide 34 · route 29 · timer 26 · validate 22 · capture 20 · bank 14
state     ← ui 40 · guide 24 · route 10 · capture 10 · bank 10 · (root) 13 · validate 6 · timer 1
timer     ← ui 18 · validate 6 · route 5 · guide 4 · bank 2 · capture 1 · state 3
route     ← guide 6 · ui 7 · capture 4 · bank 4 · (root) 4 · state 2 · validate 1
guide     ← bank 5 · ui 3 · capture 2 · (root) 5
bank      ← ui 5 · guide 2 · (root) 3 · route 1
validate  ← ui 7 · (root) 5 · capture 1
ui        ← (root) 3
(root)    ← 28 sites, all of them DoogleMapsConfig
```

**`data` is a true leaf.** 240 inbound import sites, zero outbound. For a package that is largely
generated from `tools/generate_farming_data.py` that is exactly right, and it is what makes
regeneration safe.

**The intended layering holds:** `data` → `state`/`timer` → `route` → `guide`/`bank` → `ui`, with
`capture` writing into `state` from the side and `(root)` wiring it together. `ui` depends on
seven packages and nothing depends on `ui` except the plugin class. `validate` (the harvest
statistics line) is close to a sealed vertical — read by `ui` for its panel and by `(root)` for
wiring, and otherwise self-contained.

**Four cycles exist, all of them one or two classes wide and all of them benign:**

| Cycle | The edge that closes it | Verdict |
|---|---|---|
| `state` ↔ `route` | `ProfileReset` → `BankLocationStore`, `PatchLocationStore` | A profile-wide reset legitimately reaches every store. Not a layering claim. |
| `state` ↔ `timer` | `FarmingBonusStore` → `DiaryBonus`, `FarmingBonuses`, `FarmingOutfit` | The store persists bonus values whose *types* live in `timer`. Value types in the wrong package, not a dependency. |
| `timer` → `state` | `GrowthTimer` → `PatchSnapshot` | Same shape, other direction. |
| `guide` ↔ `bank` | `GuideTracker` → `BankContents`/`TeleportItems`; `RunLoadout`/`RouteItem`/`ToolNeeds` → `CarriedItems` | The real one. `CarriedItems` is what makes it — a `guide` class that is genuinely a *state* concept. |
| `route` ↔ `bank` | `RunPlanner` → `ToolNeeds` | One import. |

**Recommendation (low effort, real payoff): move `guide/CarriedItems` to `state`.** It is
inventory-and-equipment state read by four `bank` classes, `GuideTracker` and `RunPlanner`'s
neighbourhood; it subscribes to `GameTick`, holds `synchronized` state and answers "what are you
carrying". Nothing about it is guide-specific. That single move deletes three of the five
`bank → guide` edges and leaves `bank`'s remaining dependency on `guide` as
`BankHighlightOverlay → ItemHighlight` and `RouteItem → TeleportSpell`, which are drawing
concerns and defensible. Cost: a package statement and six imports.

**With one caveat that must travel with it.** `CarriedItems` subscribes to `GameTick`, and the
EventBus orders subscribers by fully-qualified name (§2), so today it ticks *before*
`GuideTracker` (`guide.C` < `guide.G`) and after the move would tick *after* it
(`guide.G` < `state.C`). `GuideTracker` reads it — the free-slot count is what decides when you
are sent to the leprechaun — so the move on its own introduces exactly the one-tick staleness
§2a describes. `open-issues.txt` already flags this class by name for the same reason. Carry an
`@Subscribe(priority = 1f)` on `CarriedItems.onGameTick` as part of the move, or do not make the
move.

**The `Provider<RunLoadout>` cycle is already fixed, and correctly.** `RunPlanner`'s note at
line 207 records that the planner used to ask the loadout back and that the fix was to *push*
the answer in (`withdrawOutstanding`, set by `GuideTracker.reportIdlePatches` every tick) rather
than to postpone construction with a `Provider`. That is the right resolution and worth keeping
as the house pattern: when Guice will not construct a cycle, invert the data flow, do not defer
the injection.

**`DoogleMapsConfig` as the 28-site inbound dependency is fine and should stay.** It is an
interface with no implementation of ours, so it is a boundary, not coupling — but see §7 on its
size.

---

## 2. Tick ordering — the general finding is already deferred; two specifics are not

**The mechanism, verified rather than assumed.** `EventBus.register` orders subscribers with
`comparingDouble(priority).reversed().thenComparing(s -> s.object.getClass().getName())` —
confirmed from the bytecode of `client-1.12.35.jar`. That is `getName()`, the **fully-qualified**
name, which is why `com.dooglemaps.*` always precedes `net.runelite.*` and why
`GuideMenuSwap`'s deliberate `-1f` is the only way to get behind core. Within this plugin it
means the tick order is by *package then class*, and `DoogleMapsPlugin` leads because uppercase
`D` sorts ahead of every lowercase package letter:

```
DoogleMapsPlugin · bank.BankFilter · capture.ContractCapture ·
capture.PatchInteractionTracker · capture.PatchLocationCapture · capture.ProtectionCapture ·
capture.SeedCapture · guide.CarriedItems · guide.DroppedProduce · guide.GuideTracker ·
state.LeprechaunStore · state.PlayerHouse · state.PlayerLocation ·
validate.GeomancyProbe · validate.HarvestLog
```

**This lands better than it might have.** All five `capture` classes and `CarriedItems` tick
*before* `GuideTracker`, so the patch-state scan, the seed and protection captures and the
free-slot count are all this tick's when the step list is derived. The layering and the package
names happen to agree.

**The general finding is tracked and deliberately deferred**, in `open-issues.txt` under the
DEFERRED list: *"GuideTracker's tick handler runs after the plugin's (class-name event ordering),
so reviewProgress reads last tick's exemptions — one-tick lag, benign, but the ordering is
implicit; an explicit @Subscribe priority would document it (and changing it would also reorder
CarriedItems, so not done casually)."* That is the same observation, it is the owner's call, and
it is not re-opened here.

Two things sit outside what that note covers, and they are the only reason this section exists.

**2a. `state.*` ticks after `GuideTracker`, and `PlayerLocation` is read five times.**
`PlayerLocation.onGameTick` is a three-line assignment of the player's tile.
`GuideTracker.onGameTick` calls `playerLocation()` for `retargetIfMoved`, `noteTravelProgress`,
`stopAt(player)`, `insertPickUpDrops` and `announceSkipsAtFinishedStop` — all reading the
**previous** tick's tile, because `guide` sorts before `state`. `LeprechaunStore` (tool varbits)
and `PlayerHouse` are behind it for the same reason.

The consequence worth naming is the sort. Nearest-first patch ordering within a stop is a locked
decision (design-principles #9) and its input is `playerLocation()`; while the player walks
between patches five tiles apart, that input is a tile they have already left. This is a
*different* cause from the shared-pin problem tracked in `docs/TODO.md` — that one is about
patches having no distinct coordinate, this one is about the player having a stale one — and it
would still be there after the per-patch location harvest lands. Both would need to be right for
the ordering to be.

**2b. The load-bearing thing is the package, not the class name — and the one comment that says
so gets it wrong.** Because the key is the FQN, moving a tick subscriber between packages silently
changes when it runs. `CarriedItems.onGameTick` is the only place in the codebase that documents
its own ordering, and it documents it as a live contract:

```java
 * <p>Runs before {@code GuideTracker}'s tick handler — the event bus orders same-priority
 * subscribers by class name — so the steps computed this tick see this tick's pack.
```

The contract is true. **"by class name" is not** — it is the fully-qualified name, and that
difference is exactly what would make moving this class to `state` (§1's recommendation) look
free. Correct that phrase whether or not the move ever happens; it is the sentence someone will
check before making the change.

**Recommendation, narrow, and stopping short of the deferred item:** a class note on
`GuideTracker.onGameTick` stating that `playerLocation()`, the leprechaun's tools and the house
are one tick behind by construction, and that the tick key is the fully-qualified name. Not the
priority annotations — that is the deferred decision, and re-litigating it here would be exactly
the noise the exclusion rule exists to prevent.

If the deferral is ever revisited, one thing the note does not mention is worth recording first:
`@Subscribe(priority = 1f)` on the sensor classes puts them ahead of **core's** GameTick
subscribers too, not only this plugin's. For pure varbit and position reads that is harmless —
the client has set both before the event is posted — but it is a cross-plugin change, and
`GuideMenuSwap`'s `-1f` shows the ordering-against-core question already gets thought about here.

## 3. The snapshot boundary is right, and one call goes round it

**The pattern is good.** `RunSnapshot` and `GuideStatus` are the same idea applied twice: sample
everything on the client thread where the monitor already lives, publish one immutable value, let
the EDT read it lock-free. `RunSnapshot`'s own note is explicit that this replaced five
synchronized queries from the EDT — including `previewStops`, which replans from scratch — and
that removing that cross-thread lock traffic "structurally rather than carefully" was the point.
`RunPlanner` has 48 `synchronized` occurrences; the snapshot is what keeps the EDT out of them.

**One call was never brought inside it — now fixed.** `RunPanel.binsWantAFill` called
`planner.binWork(...)` — a `synchronized` method that walks every bin of every type — directly on
the EDT, on every panel refresh, and it was the only such call left in `ui`. `binWork`
post-dates `RunSnapshot`, which is why: the snapshot's field list was fixed before the
compost-bin line existed, and the new query had nowhere to go.

**Fixed 2026-08-17.** `fillableBins` is now the snapshot's sixth field, computed in
`snapshotFor` on the client thread with the rest. The bin-type fold the panel was doing for
itself moved into `RunPlanner.fillableBinsIn` alongside the call it guards.

The documented EDT fallback at `RunPanel:759` is a different thing and is fine — `RunSnapshot`'s
note sanctions it explicitly, for the one tick after a checkbox changes. `binWork` is not
sanctioned anywhere and runs on every refresh.

**This is the shape named in the open deadlock investigation** (`docs/TODO.md`, *DEADLOCK on
teleport*) — client thread and EDT walking the same monitors — and it is not among the paths that
section lists. Reported here as a structural gap rather than as a cause; the deadlock itself stays
tracked where it is.

**One residual, stated rather than fixed.** `RunLoadout.addCompostBinSupplies` also calls
`binWork`, and `RunLoadout.forRun` is called from the panel as well as from the overlays — so
whichever thread asks first in a tick pays for the build, and that can be the EDT. It is a much
weaker version of the same shape: `forRun` is tick-cached on `(tick, types)`, so it is at most
one acquisition per tick rather than one per refresh. Left alone deliberately; noted so it is not
mistaken for having been covered by the fix above.

---

## 4. The two large classes — where the seams actually are

Both are large. Neither is a ball of mud: the javadoc density is extraordinary (29 `<h2>` sections
in `GuideTracker`, 30 in `RunPlanner`, most of them naming a specific failure the code exists to
prevent), and the method names describe one thing each. The question is not "are these too big"
in the abstract; it is whether either contains a *second thing with its own state*. Both do, and
the two answers are different.

### 4a. `GuideTracker` — the run boundary has no name, and `reset()` is not it

> **Corrected 2026-08-17, after first issue.** This section originally reported
> `announcedResurrect` as never cleared, on the strength of it being absent from `reset()`. That
> was wrong: it *is* cleared, at line 1228. What follows is what the evidence actually supports,
> and the misreading is now part of the finding rather than the finding itself.

Step derivation is genuinely one job and belongs together: `computeStepsHere` and its dozen
helpers are a pure function of world state, re-derived every tick, which is the design.

**There are two reset methods in this class, they have different scopes, and only one is called
`reset`.**

- `reset()` (line 411) is wired to **plugin shutdown** — `DoogleMapsPlugin:459`, alongside every
  other capture's `reset()`. It is not a run boundary.
- The **run boundary** is an early-return branch inside `reportIdlePatches()` (line 1222), taken
  every tick the planner is inactive. That is where `announcedResurrect`, `skippedSteps`,
  `announcedBlock`, `announcedDud`, `loggedErrandsAt` and `lastNamedStop` are cleared.

The comment on that branch says why it exists, and it is worth quoting because it is the whole
finding:

```
// Everything else that is scoped to "this run" is cleared here too — this branch
// runs every tick the planner is inactive, which makes it the run boundary that
// reset() never was. reset() is wired to plugin shutdown only, so these used to be
// session-scoped in practice while their docs claimed run scope; a skipped
// "pay the farmer" then silently planted that patch unprotected on every later
// run of the session, and a skipped pick-up never offered again.
```

So this has already produced one shipped bug — a skipped pay-step silently planting unprotected
on every later run of the session — and it was found and fixed. The fix put the clearing in the
right place. **It did not give the place a name.** The run boundary is still an unlabelled branch
in a method called `reportIdlePatches`, which is why the mistake stays available: reading this
class for "what happens when a run ends" leads you to `reset()`, which is the wrong method, and
`reset()` gives a confident-looking answer. That is exactly the reading this review made on its
first pass, with the code open.

**The ten say-once fields, correctly classified:**

| Field | Cleared | Verdict |
|---|---|---|
| `announcedResurrect` · `skippedSteps` · `announcedBlock` · `announcedDud` · `loggedErrandsAt` | run boundary (1228–1241) | Correct. |
| `announcedMissingAsh` | never | Correct — its comment says "this session" and means it. |
| `loggedStopOrder` · `lastContractDiagnostic` · `loggedBinDecision` | never | Correct — `log.info` dedup, session scope is what noise-suppression wants. |
| `announcedDowngrade` | never | **Unsettled.** The only player-facing chat announcer outside the run-boundary block. |

`announcedDowngrade`'s comment says only "so it is said once", which does not settle run or
session. Keyed on `group#wanted#using`, so only an *identical* repeat is swallowed. Both readings
are defensible and the owner should pick — see the note in §9.

**Recommendation: name the boundary, do not extract a class.** The earlier draft of this section
proposed a `GuideAnnouncer` holding all ten fields; two things killed it. The announce methods
read roughly ten of `GuideTracker`'s collaborators between them (`patches`, `growthTimer`,
`config`, `client`, `carried`, `bank`, `groups`, `runTypes`, `planner`, `loadout`), so the class
would arrive with ten constructor arguments — and the lifetime problem it was meant to solve is
*already* centralised, because the owner centralised it after the pay-step bug.

What is left is a naming fix: **extract the inactive branch's clear list into a `runEnded()`
method**, comment intact, called from where it is now. Then "what is cleared when a run ends" is
a method you can find by name, `reset()` can say in one line that it is shutdown-only and point
at it, and the next person asking this question does not have to read `reportIdlePatches` to
find out. Perhaps twenty lines moved. The `GuideAnnouncer` class stays available as the answer if
this recurs, not as the recommendation now.

### 4b. `RunPlanner` — planner and run state machine on one monitor

Two things live here:

- **Planning queries**, pure functions of stores: `planStops`, `countActionable`,
  `actionableByGroup`, `ripeProduceIn`, `survivalAcross`, `binWork`, `isActionable`. These are
  what `RunSnapshot` samples and what `RunLoadout` reads.
- **The run's own mutable state**: `active`, `atBankLeg`, `supplyOwed`, `bankLegWaived`,
  `withdrawOutstanding`, `toolOutstanding`, `committedRegion`, `runCompletePending`,
  `exemptionPushes`, `skippedRegions`, `announced`, `postedSources` — plus `start`, `stop`,
  `leaveBank`, `waiveBankLeg`, `commitDestination`, `followSupplyProgress`.

They share one monitor, which is why the planning queries are `synchronized` at all, which is why
§3 exists.

**But do not split this one yet.** The pure/mutable line is real, but the state machine's
transitions read the planning queries constantly (`needsSupplyTrip` → `suppliesOutstanding` →
`seedsWantedThisRun`; `isComplete` → `stillWanted` → `isActionable`), so a split hands you a new
cross-object call graph and a fresh chance at the `Provider` cycle the class already escaped once.
Worse, `docs/stop-planner-spec.md` proposes replacing the per-stop ordering with a
constraint-and-cost solver, and §7 of that spec is a migration plan through this same code. A
refactor now would be re-done by that work.

**Recommendation:** hold the split until the stop-planner spec is decided either way, and in the
meantime take the cheap half — move the run-state fields into one contiguous, commented block
(they are currently interleaved with the query fields from line 109 to line 2430) and state on the
class which methods are pure and which mutate. That makes the seam visible for whoever cuts it,
costs nothing, and does not pre-empt the spec.

### 4c. A smaller thing, seen in both

Fully-qualified names appear inline where an import would do — `com.dooglemaps.state.RunTypeStore`,
`com.dooglemaps.bank.RunLoadout`, `com.dooglemaps.route.BankLocationStore`, `java.util.Set`,
`net.runelite.client.chat.QueuedMessage` — including in constructor parameter lists. It reads as
accretion: each was added without touching the import block. Harmless individually; collectively
it is why the dependency matrix in §1 needed care to build, and it hides which packages a class
actually depends on from anyone reading the top of the file. Worth a tidy pass when either class
is next opened, not worth a commit of its own.

---

## 5. Persistence — the strongest boundary in the codebase

Twenty-five classes touch `ConfigManager`. **Exactly two of them call `setConfiguration` or
`getConfiguration` directly** (`ui/PanelLayoutStore`, `state/ContractState`). Everything else goes
through `ProfileJsonStore`.

That base class is the right answer to the right problem, and its note says so precisely: the
forty lines of plumbing had been copied a dozen times and **the copies had drifted on locking** —
which of `load` and `save` held the monitor varied by which copy was the template that day. Not an
observed bug, because every caller happened to hold the monitor; a rule living in the callers'
heads. Now it is one decision: `load` and `save` both hold the monitor, subclasses only run
inside it, and `loaded()` runs *outside* it because calling listeners under a lock is how
deadlocks start.

The write guard is the other half — a store that has never read cannot write, because `save()`
serialises the whole map with no merge. That defect (`patchLocations` holding six entries against
107 observed patches) is exactly the kind that a copied-plumbing design produces once per copy and
a base class fixes once.

**File IO is confined to `validate` plus one call in `DoogleMapsPlugin`** — the harvest CSV, the
stats blobs and the Geomancy probe. Nothing in `state`, `route`, `guide` or `bank` touches the
filesystem. That is a clean split and it is what makes the config-vs-file question answerable.

**No recommendation. This is the pattern the rest of the codebase should be measured against**,
and §4a is essentially the observation that the announcer never got its `ProfileJsonStore`
moment.

---

## 6. Extension boundaries

**Shortest Path — the soft dependency is done properly.** `ShortestPathIntegration` talks over
RuneLite's `PluginMessage` bus under the `shortestpath` namespace. No reflection, no compile
dependency, no version check, no `Class.forName`. Posting a message nobody listens for does
nothing, so absence needs no handling at all — which is precisely why locked decision #6 (routing
is a soft dependency, never required) is *structurally* satisfied rather than carefully
maintained. Handing it a set of targets and letting its cost model pick the cheapest is also what
lets the plugin have no tour-ordering heuristic of its own, and the class note says so.

Reflection in `src/main` appears in exactly two places, neither of them cross-plugin:
`ui/Locations` and `validate/GeomancyProbe`.

**Generated data.** Eight classes in `data` are generated by `tools/generate_farming_data.py` from
TSVs, and `data` being a leaf is what makes regeneration a safe operation. The known hazard — that
regenerating wipes hand-written additions to `Produce.java` and `PatchImplementation.java` — is a
process risk rather than an architectural one, and `DEVELOPMENT.md` covers the hand-check.

**`GeomancyProbe` is kept as a tool, with its setting hidden.** Keeping the code that decoded a
dropped feature, while removing the switch from the panel, is the right call and is reasoned in
`DEVELOPMENT.md` under *Roads not taken*. Noted here only because a reviewer coming to `validate`
cold will find a class with no callers and no panel entry and wonder.

---

## 7. Config as a surface

`DoogleMapsConfig` is 1,446 lines: **92 `@ConfigItem`s in 6 `@ConfigSection`s**, averaging fifteen
settings a section. There is a `ConfigLayoutTest`, which is more than most plugins have.

This is not an architecture defect — it is an interface, it has no logic, and the 28 classes
reading it are reading a boundary. It is worth naming as a **product** risk with an architectural
tell: ninety-two switches is a lot of surface for a Hub plugin to present, and the count grows
monotonically because adding a setting is the cheapest way to resolve any "should it do X or Y".
Several of the six menu swaps, the resurrect reminder, the compost downgrade and the bank filter
each carry their own toggle, correctly and for compliance reasons — but the pattern generalises
past the cases that need it.

**Recommendation:** no change now, but before Hub submission do one pass asking of each item
*"has this ever been switched?"* — and treat any new setting proposed as a tie-breaker between two
behaviours as a design question that has not been answered yet. `docs/TODO.md` already has a
section wanting exactly this thinking for per-feature run toggles; this is the same question at
config scale.

---

## 8. Testability

1,133 tests across 119 classes, and the distribution follows the risk sensibly:

| Package | Main classes | Test classes |
|---|---|---|
| `guide` | 17 | **35** |
| `route` | 13 | 12 |
| `state` | 26 | 17 |
| `ui` | 29 | 17 |
| `bank` | 12 | 10 |
| `data` | 29 | 8 |
| `timer` | 10 | 6 |
| `validate` | 12 | 6 |
| `capture` | 8 | 5 |

`guide` at two test classes per main class is right — it is the largest package, the most
stateful, and where the run stalls lived. `data` at 8/29 is fine because most of it is generated.

**What the structure makes hard to test is the tick as a whole.** Because the order between the
fifteen subscribers is a property of the EventBus rather than of any code here (§2), there is no
seam at which "the tick, in order" can be exercised — each subscriber is tested against a
constructed state rather than against what the previous subscriber actually left. That is a
consequence of the deferred decision rather than a separate finding, and it is worth knowing as
one more cost on that side of the ledger if it is ever reconsidered. A cheaper partial: a test
that asserts the FQN sort puts every `capture` class ahead of `guide.GuideTracker`, which is the
property the current arrangement quietly relies on and which a package move would break
silently.

**The `ConfigLayoutTest` and `GeomancyProbeTest.theInterfaceHoldsExactlyThePatchesWeDo` are both
good instincts** — pinning a data set so a RuneLite update fails a test rather than going
unnoticed. That pattern would be worth extending to the generated `data` classes if a
regeneration ever silently drops a row.

---

## 9. Roadmap

Ordered by ratio of payoff to risk. Nothing here touches a locked decision or the Hub compliance
line.

> **Status, 2026-08-17.** Items 1, 2 and 5 below are **done** — `runEnded()` is extracted and
> `reset()` delegates to it, `announcedDowngrade` is run-scoped and says so, and the tick's
> staleness is stated on `GuideTracker.onGameTick` with `CarriedItems`' "by class name" corrected
> to the qualified name. Item 3 is **done** — `fillableBins` is on `RunSnapshot`, the bin-type
> fold moved into `RunPlanner.fillableBinsIn`, and the panel no longer takes the planner's
> monitor from the EDT at all. Item 4 (`CarriedItems` → `state`) is **dropped** by the owner.
> Build green, **1,142 tests, 0 failures**.

**Now — small, self-contained, each an hour or less**

1. ~~**Settle `announcedDowngrade`'s lifetime**~~ (§4a) — **done: run-scoped.** The owner's call,
   and the reasoning is on the field: the reminder is meant to nag, and `downgradeCompost` is the
   switch for anyone who has heard enough. Cleared in `runEnded()` with its four siblings.
2. ~~**Name the run boundary**~~ (§4a) — **done.** `runEnded()` extracted; `reset()` now keeps
   only the two plugin-scoped clears and delegates the rest, so the two lists cannot drift apart
   again.
3. ~~**Move `binWork` into `RunSnapshot`**~~ (§3) — **done.** `fillableBins` is the snapshot's
   sixth field; the fold the panel did for itself is now `RunPlanner.fillableBinsIn`, package-
   private so its test could move with it. `CompostBinWarningTest` verifies `binWork` is
   `never()` called from the panel, so a future edit reaching for the planner fails there rather
   than in a freeze report.
4. ~~**Move `guide/CarriedItems` to `state`**~~ (§1) — **considered and declined by the owner**,
   on the reasoning in §2b and `open-issues.txt`. It would have deleted three of the five
   `bank → guide` edges, but the class states a live ordering contract that the move breaks, and
   `state.CarriedItems` sorts after `guide.GuideTracker`. Recorded so it is not re-proposed
   without the `@Subscribe(priority = 1f)` that would have to come with it.

**Next — declares something currently unwritten**

5. ~~**Note the tick's staleness on `GuideTracker.onGameTick`**~~ (§2a) — **done.** The handler now
   states which packages tick before it and which after, that `playerLocation()` and the
   leprechaun's tools are a tick behind, and that the sort key is the fully-qualified name;
   `CarriedItems`' "by class name" is corrected to match. A paragraph, not a refactor — the
   priority-annotation version stays deferred where `open-issues.txt` put it.
6. **Extract `GuideAnnouncer`** (§4a) — **not recommended now**, recorded as the answer if the
   lifetime confusion recurs. The announce methods read ten collaborators between them, so the
   class arrives with ten constructor arguments to solve a problem the run boundary already
   solves.
7. **Group `RunPlanner`'s run-state fields and mark pure vs mutating** (§4b). Preparation, not a
   split.

**Before the Hub**

8. Remove the resource-monitor dev wiring — already tracked in `docs/TODO.md`, listed here only
   because it is the one architectural item on the publish path. `grep -rn "REMOVE BEFORE
   PUBLISHING" build.gradle src/`.
9. The config surface pass (§7).

**Deferred, deliberately**

10. **Splitting `RunPlanner` into planner and run state machine.** The seam is real and §4b names
    it, but `docs/stop-planner-spec.md` §7 migrates through the same code. Decide the spec first;
    doing both is doing one of them twice.
