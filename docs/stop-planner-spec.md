# Spec: a constraint-and-cost planner for the work at one stop

**Status:** proposed, not started. Written 2026-08-14 from two reports and a read of the code
that exists today. Intended to be picked up on its own branch.

---

> **The motivating case is already fixed.** Working the geometry out (see §5) showed that
> buckets-before-ash is provably optimal from any arrival tile, so it needed no planner and no
> positions — `CompostBinPlan.addBucketFetch` now runs ahead of the ash step. That is the *only*
> ordering question at a stop that settles without geometry; everything else in this spec still
> stands.

## 1. The problem

Two reports, a turn apart, that contradict each other — which is the whole point:

> *"I've walked past the lep on my way to put volcanic ash in the compost bin, why didn't I grab
> empty buckets first."*

> *"Well hold on, it's not necessarily, because depending on where you tele in, you might not walk
> past the lep, then putting the ash on the bin first might make sense."*

Both are right. There is **no correct static order** for those two actions. It is a function of
where the player entered the stop from, and every ordering rule in the code today is
position-blind.

That is why this class of report keeps arriving looking like a one-line reorder, and why fixing
each one breaks the opposite case. `GuideTracker:760` records the same disease from the other
end — seven rounds of fixes to the bin ordering, each overwritten a few lines later:

> *"`binsFirst` moves a hungry bin to the front of the stop's list, and `chooseWorkingPatch` runs
> **afterwards** and hoists whatever patch you are part way through back to the top. So every fix
> aimed at the ordering was overwritten a few lines later, and the log said 'bin first' while the
> panel said 'harvest'."*

## 2. Why the current shape cannot do it

Two structural reasons, both deliberate decisions that have outlived their justification.

**Ordering is eight mutation passes over one list.** In `GuideTracker.computeStepsHere`:

```
sortedByDistance(stop, player)        order patches by distance
contractComesFirst(stop, ordered)     reorder
flowersAfterAllotments(offered)       reorder
chooseWorkingPatch(offered, stop)     hoist one to the front
[per-patch stepsFor, concatenated]    expand to steps
binsFirst                             reorder
collapseDuplicateNotes(steps)         dedupe
removeIf(skippedSteps)                filter
appendLeprechaunErrands(steps, stop)  splice at a computed index
```

Each pass is individually justified and documented. The **composition** is undefined — no one
chose the order globally, so every conflict is discovered in play rather than at the keyboard.

**Precedence is enforced by early return.** `CompostBinPlan.addEmptyingSteps` emits one action
and returns; its javadoc says why: *"Each return hands back one action and re-derives after it
happens, which is what keeps the ash ahead of the first bucket by construction."* `GuidePlan.forPatch`
is the same shape — roughly a dozen `return steps;` branches in priority order.

This is a correct way to enforce precedence and it has one fatal consequence for this problem:
**the planner never holds the whole remaining errand list at once, so it can never order it
spatially.** You cannot sort a list you cannot see.

## 3. The model

Replace both with one evaluation:

- **Actions** — the unit is what it is today (`GuideStep`), plus a **location**.
- **Precedence** — hard directed edges between actions. Never costs, never weights. These are the
  early returns, declared as data.
- **Resources** — inventory slots and stock, as preconditions and effects per action.
- **Cost** — walking distance from the player's actual position, through the ordered actions,
  plus a fixed charge per distinct leprechaun visit.
- **Solve on arrival**, when the entry position is known. No prediction of where you will land.

Then "grab buckets while passing the lep" is not a rule. It is what the cheapest valid tour
happens to be when the lep lies between you and the bin — and "ash first" is what it happens to
be when it does not. One computation, both answers, no case analysis.

### What this is not: Quest Helper's model

Worth stating, because Quest Helper is the acknowledged vocabulary model here (see
`GuideOverlay`'s class javadoc) and it is visibly good at exactly the thing this spec is about —
knowing where you are and recovering when you do something unexpected.

Its core is a `ConditionalStep`: a list of requirement→step pairs, re-evaluated every tick,
showing the first step whose requirements hold. There is **no plan and no memory**. Its positional
robustness comes from holding nothing — walk somewhere unexpected, drop an item, do a step out of
order, and it all resolves next tick because nothing was ever committed.

That property is already ours and this spec keeps it: §6 re-solves on arrival and on every
completion, precisely so a wandering player is never fighting a stale plan.

**What cannot be borrowed is the ordering, because Quest Helper never faces it.** A quest is a
*chain* — the game gates it, so "what is next" is fully determined by state and there is only ever
one legal next thing. A farm stop is a *set*: the bin, the patches and the leprechaun have no
game-imposed order, every sequence is legal, and most are wasteful. A conditional machine has
nothing to say there.

The gap in one line: **Quest Helper answers "what is available now"; farming also needs "of the
things available now, which first".** Keep the statelessness; add the ordering it never needed.

### Why this is tractable

It needs **no Shortest Path**. Everything is inside one region, so cost is straight-line distance
over a handful of tiles — no transports, no collision map, no cross-plugin round trips. (This is
the opposite of the inter-stop ordering problem, where the absence of a cost-only SP query is the
blocker.)

Positions are already known. `MeasuredPatchLocations` ships all 107 patches. The leprechaun, the
bin and the farmer are fixed per farm area and learnable the same way — see
`PatchLocationCapture` for the pattern that produced the patch table.

A stop has 3–6 distinct locations and rarely more than ~12 pending actions, most of them
precedence-chained. Exhaustive enumeration with constraint pruning is microseconds. **Nothing
here wants a heuristic, let alone a learned one.**

---

## 4. The constraint catalogue

The substance of this branch. Every rule below exists today as control flow or a reorder pass;
the work is to restate it as data without losing the reasoning. **Each one keeps its javadoc** —
they are the record of the play session that produced the rule, and they are the most valuable
thing in this codebase.

### 4.1 Per-patch precedence — from `GuidePlan.forPatch` branch order

Listed in current priority order. Branch numbers are the comments in the file.

| # | Rule | Source |
|---|---|---|
| 0.2 | Vinery empty states decode as weeds; treat as empty | `GuidePlan:123` |
| 0.5 | **CHECK before anything** — some crops refuse interaction until checked | `GuidePlan:139` |
| 0.6 | A felled stump is cleared, not picked | `GuidePlan:160` |
| 0.7 | A checked tree still standing is chopped, not harvested | `GuidePlan:183` |
| 0.8 | A spent anima plant | `GuidePlan:207` |
| — | **NOTE → fetch SPADE → HARVEST** for spade-harvested families. Note first: noting frees the slots and the fetch is the same trip to him | `GuidePlan:280–296` |
| — | `harvestOnly` stops here — nothing clears, treats or replants | `GuidePlan:302` |
| 2 | **CLEAR before anything goes in** (dead crop or weeds). Redwood is the exception: pay Alexandra 2,000gp | `GuidePlan:308` |
| 2.5 | DISEASED → cure. Never for DEAD, never for a mature crop | `GuidePlan:327` |
| 2.7 | Spade-cleared family, picked clean, replant possible → **fetch SPADE → CLEAR** | `GuidePlan:341` |
| — | Crop in the ground → PAY_FARMER (protection), and the compost-after-planting case | `GuidePlan:365` |
| 3 | **COMPOST before PLANT.** Preferred, *not required* — the wiki's herb guide sows first | `GuidePlan:428` |
| 4 | PLANT. Needs SPADE if sapling; SEED_DIBBER unless Barbarian Farming, or coral (placed, not dibbed) | `GuidePlan:437–470` |

Note the one **soft** edge in the table: compost-before-plant is a preference, not a game rule.
It should become a cost term, not a constraint — which is exactly what lets a player who sows
first still be guided correctly.

### 4.2 Compost bin precedence — from `CompostBinPlan`

| Rule | Why | Source |
|---|---|---|
| **APPLY_ASH before the first bucket leaves the bin** | 25 ash (50 big) upgrades the whole bin; 2 ash per bucket once out. Hard | `CompostBinPlan:130` |
| Bottomless bucket → EMPTY_BIN directly, no leprechaun trip | Whole bin into one slot | `CompostBinPlan:141` |
| Rotten tomatoes need no bucket | The one bin product taken by hand | `CompostBinPlan:154` |
| **Empty buckets in hand before EMPTY_BIN** | Nothing to empty into otherwise | `CompostBinPlan:161` |
| No free slots → DEPOSIT_COMPOST first | Deadlock break: fetch, fill, be told to fetch again, forever | `CompostBinPlan:171` |
| Fodder fill **before** deposit; bank fill **after** | The crop in the pack has nowhere else to go; the compost does | `CompostBinPlan:~440` |
| CLOSE_BIN when full | A full open bin composts nothing | `CompostBinPlan:427` |

### 4.3 Stop-level ordering — from `GuideTracker`

These are the eight passes. Most become **cost terms**, not constraints — which is the point:
they are preferences that currently masquerade as ordering.

| Pass | Becomes | Rule |
|---|---|---|
| `contractComesFirst` | **Constraint** | While contract business is outstanding the guild offers only the contract's patch. Held back, not cancelled |
| `binsFirst` / `binRank` | **Cost** | A bin you can *finish* ranks early; a bin waiting to be *filled* ranks last — you cannot fill from a harvest that has not happened |
| `chooseWorkingPatch` | **Cost, strong** | Stickiness: hold to the patch part way through. Currently outranks `binsFirst` by running after it. Must release when `cannotCarryOn` — a full pack and a harvest is an instruction with no next click |
| allotment twin handoff | **Cost** | A finished allotment hands to its twin before anything nearer, so one leprechaun trip notes both |
| `flowersAfterAllotments` | **Cost** | A grown flower is the allotments' disease guard; its *pick* goes last. A flower needing *planting* keeps its place |
| `sortedByDistance` | **Subsumed by the tour cost** | Nearest-first is what a walking cost produces anyway |
| `appendLeprechaunErrands` | **Cost** | Bundle note + bucket return into a committed leprechaun visit. Errands go before the step that brought you there, *unless* that step is itself a note |
| `collapseDuplicateNotes` | **Constraint** | One note per visit |

### 4.4 Resources

The recurring coupling, and the one that has produced the most bugs. Model as preconditions and
effects, not as ad-hoc checks:

| Action | Slots | Other |
|---|---|---|
| HARVEST | needs ≥1 free | |
| NOTE_AT_LEPRECHAUN | **frees a stack** | |
| RETURN_BUCKETS | frees 1 per bucket | |
| WITHDRAW_COMPOST | **consumes** 1 per bucket | needs stock at the lep |
| DEPOSIT_COMPOST | frees 1 per bucket | |
| FILL_BIN | needs 15 (30 big) items in pack | un-noted only |
| EMPTY_BIN | needs empty buckets | |
| APPLY_ASH | | needs 25 (50) ash in pack |
| PLANT | | needs seed + dibber/spade |
| PAY_FARMER | | needs payment, noted counts |

Three shipped fixes are all the same slot-ordering problem solved separately by hand: the
compost round-trip (§0i in `code-review-2026-08c.md`), the bucket-return-before-note ordering
(`GuideTracker:2174`), and the bin deadlock guard (`CompostBinPlan:171`). One slot-aware cost
term subsumes all three.

---

## 5. Cost function

1. **Distinct leprechaun visits** — a fixed charge per visit. **The dominant term.**
2. **Walk** — sum of distances along the tour from the player's actual tile.
3. **Stickiness** — penalty for leaving a patch part way through.
4. **Soft precedence** — small penalty for compost-after-plant, flower-picked-before-allotments.

Ticks, not tiles, if the conversion is ever worth it. Everything at a stop is walking, so tiles
are a faithful proxy today.

### Why visits outrank walking, though it looks the other way round

An earlier draft of this spec had walking as the dominant term. Working the motivating example
through says otherwise, and the reasoning is worth keeping because it also disposes of the
example itself.

Take the ash/bucket case in isolation: locations are the entry tile `P`, the leprechaun `L` and
the bin `B`; the actions are `ash@B`, `buckets@L`, `empty@B`. Both valid orders must **end** at
`B`, so ash-first is always buckets-first plus a detour of `d(P,B) + d(B,L) − d(P,L)`, which is
never negative — there are no teleports inside a stop, so the metric is honest. **Buckets-first
is provably never worse on walking, from any entry point.**

So the reported case does not turn on geometry at all. Ash-first wins only when the leprechaun
is not in the tour — when you already hold buckets, and he drops out entirely.

Where the real money is, and what a walking cost cannot see:

```
LEP(buckets) → patches → LEP(note) → BIN(ash) → BIN(empty)   two visits
patches → LEP(note + buckets) → BIN(ash) → BIN(empty)        one visit
```

Same actions, same precedence, similar distance, one fewer trip. Whether the second is available
depends on when the pack fills — which is the resource model in §4.4, not the geometry. Hence:
visits dominate, walking breaks ties.

This also explains why `appendLeprechaunErrands` exists and why it keeps producing reports. It is
a hand-rolled version of exactly this term, applied after the ordering rather than as part of it.

## 6. Solver

- Enumerate valid orderings under precedence + resource constraints; take the minimum cost.
- Prune hard: most actions are precedence-chained, so the branching factor is small.
- Re-solve on arrival, on any action completing, and on inventory change. It is cheap enough to
  run per tick, but per-event is tidier and the existing per-tick caches (`allocations`,
  `compostWanting`) show the shape.
- **Determinism matters.** Tie-break on patch key, as `SeedAllocation.forPatches` already does,
  so the guide never appears to change its mind about a step it has already given.

## 7. Migration

Do not rewrite in one pass. The value in the current code is the accumulated case knowledge, and
it is recoverable step by step:

1. **Give actions locations.** `GuideStep` gains a `WorldPoint`. Learn the leprechaun and bin
   positions the way patch positions were learned. No behaviour change.
2. **Declare precedence as data.** Extract the `GuidePlan` branch order and the `CompostBinPlan`
   returns into an explicit edge set. Keep the existing code path; assert the two agree — the
   same technique `AllocationAgreementTest` uses to hold `RunEstimate` and `SeedAllocation`
   together.
3. **Emit the full pending set** for a stop instead of one action at a time. Precedence is now
   carried by the edges, so the early returns are no longer load-bearing.
4. **Order by cost.** The eight passes become terms. Delete them one at a time, each with its
   test still passing.
5. **Property-based verification.** Enumerate reachable stop states and assert: no state emits an
   instruction that cannot be followed; no state emits a withdraw immediately followed by a
   deposit of the same item; every state makes progress. This would have caught the compost
   round-trip mechanically.

## 8. Must not regress

Each of these pins a bug reported from play. They are the acceptance suite:

- `GuildBinPrecedenceTest` — the full-pack bin latch, seven rounds of it
- `CompostBinPlanTest` — fodder-before-deposit, keep-back, the ash loss notice
- `UntickedGroupIsNotReplantedTest` — no destructive work on an unticked group
- `LeprechaunErrandOrderTest` — errands ahead of the step that brought you, behind a note
- `HarvestedPatchStillGuidedTest`, `EmptyBucketsWantedTest`, `ContractHandInOrderTest`
- `AllocationAgreementTest` — the guide and the panel must keep agreeing on counts

## 9. Open questions

- **Leprechaun position.** Fixed per farm area, but unmeasured. Capture like patches, or hardcode
  from the wiki? Capture is consistent with everything else here.
- **Does the player walk the tour we cost?** They click where they like. The plan should be
  robust to that — re-solving on arrival and on each completion mostly handles it, but a player
  who wanders will get a re-ordered list, which is the jitter noted below.
- **Panel jitter.** `sortedByDistance` already re-sorts every tick as the player moves; the
  Ardougne logs show the step order churning almost per tick. A tour cost has the same hazard.
  Hysteresis, or re-solve only on events rather than position.
- **Weights.** Start with walk-dominant and everything else small. Tune against the harvest log's
  replay data if it turns out to matter, which it may not.
