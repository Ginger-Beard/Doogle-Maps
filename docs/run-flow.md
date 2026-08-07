# What the player actually does during a run

The click-and-move loop guided mode drives, end to end, with the points where it can **stall**
marked. Built by tracing `RunPlanner` → `GuideTracker` → `GuidePlan` → `PatchInteractionTracker`.

Read the red nodes first. They were the states a run could enter and not leave.

> **Status: all four fixed.** Completion is derived from patch state and polled once a tick, so a
> stop ends when nothing at it is actionable rather than when every patch has been watched being
> planted — and a patch the guide has no step for is skipped rather than waited on, with the panel
> saying which and why.

---

## The whole run

```mermaid
flowchart TD
    Start([Player presses Start run]) --> Plan

    Plan["<b>Plan stops</b><br/>every actionable patch of the ticked types,<br/>grouped by region — RunPlanner.planStops"]
    Plan --> Owed{"Anything to collect?<br/><i>needsSupplyTrip</i>"}

    Owed -- no --> Travel
    Owed -- yes --> Standing{"Already standing<br/>on work?"}
    Standing -- "yes, and no supply point here" --> Work
    Standing -- "no, or a bank/vault is in this region" --> SupplyLeg

    SupplyLeg["<b>Supply leg</b><br/>route to bank, or to the seed vault<br/>if that is where the seeds are"]
    SupplyLeg --> Withdraw["Withdraw: seeds, payments,<br/>tools only in the bank, teleports"]
    Withdraw --> Outstanding{"Anything still<br/>outstanding and<br/><i>reachable</i>?"}
    Outstanding -- yes --> Withdraw
    Outstanding -- "no — including items you own nowhere" --> Travel

    Travel["<b>Travel</b><br/>Shortest Path routes to whichever<br/>remaining stop is cheapest"]
    Travel --> Work

    Work["<b>At a stop</b><br/>order patches: contract first,<br/>then nearest — GuideTracker.sortedByDistance"]
    Work --> Jane{"Farming Guild,<br/>and contract business?"}
    Jane -- yes --> JaneStep["Hand in / take next<br/><i>inserted at the front</i>"]
    Jane -- no --> Patch
    JaneStep --> Patch

    Patch["<b>Work one patch</b><br/>see the patch loop below"]
    Patch --> More{"Anything here still<br/><b>actionable</b>?<br/><i>RunPlanner.isComplete</i>"}

    More -- yes --> Blocked{"Does the guide have<br/>a step for it?"}
    Blocked -- yes --> Patch
    Blocked -- "no — skipped, and said so" --> Lep

    More -- no --> Lep["Leprechaun errands:<br/>note crops, return empty buckets"]
    Lep --> Remaining{"Stops left?"}
    Remaining -- yes --> Travel
    Remaining -- no --> Done([Run ends])

    Tick["<i>reviewProgress, every tick</i><br/>re-checks completion in case<br/>no varbit change announced it"] -.-> More

    classDef normal fill:#1e293b,stroke:#64748b,color:#e2e8f0
    class Start,Done,Plan,SupplyLeg,Withdraw,Travel,Work,Patch,Lep,JaneStep,Tick normal
```

## One patch, click by click

`GuidePlan.forPatch` — a pure function of the patch's current state, re-derived every tick.
The first matching branch wins and returns; there is no progress counter anywhere.

```mermaid
flowchart TD
    P([Patch in front of you]) --> Weeds{"Weeds, and<br/>stage > 0?"}
    Weeds -- yes --> Rake["Get rake from leprechaun<br/>(only if he has one)<br/>→ <b>Rake the weeds</b>"] --> P

    Weeds -- no --> Ripe{"Anything to pick?<br/><i>hasProduceToPick</i>"}
    Ripe -- yes --> Full{"Inventory<br/>full?"}
    Full -- yes --> Note["<b>Note with the leprechaun</b>"] --> Harvest
    Full -- no --> Harvest["<b>Harvest</b>"]
    Harvest --> Ripe

    Ripe -- no --> HO{"Harvest-only<br/>run?"}
    HO -- yes --> HODone["Nothing more here — by design.<br/>The patch is no longer actionable,<br/>so the stop can finish."]

    HO -- no --> Dead{"DEAD?"}
    Dead -- yes --> Clear["Get spade if needed<br/>→ <b>Clear the dead crop</b>"] --> P

    Dead -- no --> Growing{"Still growing?"}
    Growing -- yes --> Pay["<b>Pay the farmer</b><br/>if protecting &amp; payment carried"]
    Pay --> LateCompost["<b>Late compost</b><br/>if just planted and untreated"]

    Growing -- "no, patch is empty" --> Seed{"Seed allocated<br/>for this patch?"}
    Seed -- no --> NoSeed
    Seed -- yes --> Compost{"Compost wanted<br/>and not applied?"}

    Compost -- yes --> DoCompost["Withdraw compost from leprechaun<br/>(enough for every patch here)<br/>→ <b>Treat the patch</b>"] --> P
    Compost -- no --> Box{"Seeds in the<br/>seed box?"}
    Box -- yes --> Empty["<b>Empty the seed box</b>"] --> Dibber
    Box -- no --> Dibber{"Needs a dibber?"}
    Dibber -- "yes, and not carried" --> GetDibber["Get dibber from leprechaun"] --> Plant
    Dibber -- no --> Plant["<b>Plant</b> → patch becomes GROWING<br/>→ no longer actionable"]

    NoSeed["No step to give.<br/><b>Skipped, and the panel says so:</b><br/><i>Skipping falador herb - no seed.</i>"]

    classDef ok fill:#14532d,stroke:#22c55e,color:#fff
    class HODone,NoSeed ok
```

---

## The four stalls, and why they were one bug

**As it was:** a stop completed only when every patch at it had been marked serviced
(`serviced.size() >= patches.size()`), and a patch was marked serviced only when its varbit changed
into a state holding a growing crop. Put together — **the run only advanced if you fully planted
every patch at every stop.**

**As it is now:** a stop is complete when nothing at it is actionable, asked of the patches rather
than counted, and re-asked once a tick so it cannot depend on an event arriving.

| # | Situation | What happens |
| --- | --- | --- |
| 1 | **Harvest-only run** — **FIXED** | A picked-clean bush or fruit tree still decodes as `HARVESTABLE` — verified: 19 such varbit states, stock 0. `PatchProjection.hasProduceToPick()` now separates "grown" from "laden", so the stop ends when the fruit is gone. |
| 2 | **No seed for a patch** — **FIXED** | The guide reports every patch it has no step for; the planner stops waiting on those. The panel says *"Skipping falador herb - no seed."* rather than skipping silently. |
| 3 | **Cannot clear a patch** — **FIXED** | Same mechanism: no step means nothing to wait for. Only the no-seed case gets a worded reason, because that is the one the player can act on. |
| 4 | **Patch turns out to want nothing** — **FIXED** | Included optimistically when never seen. Now caught by `reviewProgress()`, polled each tick, because arriving at such a patch fires no varbit *transition* to report. |

When a stop stalls, nothing reports a completed stop, so `retarget()` is never called. And
`retarget` clears the router whenever you are standing in a region with outstanding work — so the
visible symptom is **no route drawn and no next instruction**, which reads as the plugin freezing
rather than as a patch it is waiting on. No stop can now reach that state.

### The underlying contradiction

`GuidePlan`'s own class comment states the design rule:

> The whole of guided mode's judgement lives here, and it is deliberately a **pure function of the
> patch's current state** — no progress counter, no "which step am I on" stored anywhere. […] A
> stored step index has to be kept in step with a player who does things out of order, walks away,
> or gets the compost on before you told them to.

`RunStop` does the opposite. It accumulates a `serviced` set — a progress counter, kept in step by
one event that fires on one transition. The two halves of the same feature are built on opposite
principles, and the accumulating half is the one that strands the run.

### What was done

Completion is **derived**, matching the half that already worked:

- `PatchProjection.hasProduceToPick()` separates *grown* from *laden*, so a picked-clean regrowing
  crop stops being worth visiting. Fixes stall 1.
- `RunPlanner.isComplete(RunStop)` asks whether any patch is still actionable — the same test the
  stop was built from, so a stop ends exactly when it would no longer be created.
- `RunStop.isComplete` became `isFullyServiced` and nothing depends on it to end a run.
- `markServiced` became `onPatchChanged`: the capture layer reports the *event*, the planner
  decides the *meaning*. It no longer needs an opinion about what a run considers finished.
- `RunPlanner.reviewProgress()`, polled each tick, catches a stop that finished without any varbit
  transition to announce it. Fixes stall 4.

### What is still open

Nothing in this loop, as far as I can tell.

The gap this section used to name — a contract taken mid-run having no way to fetch its seed — is
closed. The cause was not the missing machinery it looked like: `RunPlanner.selectedForThisRun`
resolved seeds by patch **type**, and a contract's seed is derived from the assignment rather than
picked, so it was never in the set that overload filters. The planner could not see it while
`RunLoadout`, which resolves by planting **group**, could — so the withdraw list asked for a yew
that no routing decision knew about. Both resolve by group now.

The supply leg also ends on the withdraw list rather than on its own partial copy of it, which is
what lets the axe and the protection payments hold it open. See `docs/TODO.md` for what remains.
