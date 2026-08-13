# Doogle Maps — code review, August 2026 (third pass)

Whole-repo review, 2026-08-13, aimed at the uncommitted working tree: ~20,400 added lines
across 114 files since `b3e16ee`, which is the compost-bin run, the underwater patches, the
route commitment, the seed-box rework and the contract errands. Baseline at review time:
build green, **1,064 tests, 0 failures**.

Findings already tracked in `docs/TODO.md` are excluded rather than re-reported — the
bottomless-bucket tier gap, `PatchRules.java` being unreviewed, `route/InventoryPlan.java`
being dead, `PatchInteractionTracker.isGrowthTick`'s unreachable branch, the missing tests
list, and the per-file licence headers. Follow-up to `docs/code-review-2026-08b.md`, whose
findings are all closed: JDWP is on loopback, the chathead fetcher validates, and no secrets,
network calls or unsafe file IO exist anywhere in `src/main`.

## Overall

The discipline that made the last two reviews easy is still here and still working: the
single-sourced capture path, the tick-cached snapshots, the causal javadoc, and a test suite
that has roughly doubled alongside the code. Two of the best things in this change set are
defensive rather than additive — `data/SeedBox` finally gives "can this item go in a box" one
home after four places disagreed about it, and `BankHighlightOverlay.refreshWanted` is a
textbook tick cache on a render path.

What went wrong went wrong in one specific way, and it is worth naming because it explains
three of the findings below. **Two methods carry javadoc that forbids exactly the call the
new code makes**, and the compiler cannot check a paragraph. `heldForRegrowth` and
`clusterHeld` both say "planning-time only, never in `isComplete`", both explain the failure
that would follow, and both are now called from `isComplete`. One of the two failures is
real, reproducible, and is the same run-stranding class as the headline findings of the
previous two reviews.

---

## Found in play after this review, from one run's log

Three more, none of which this review caught by reading. They came out of a single reported
symptom — *"guided to harvest limpwurts at the Farming Guild, then asked to teleport away
without planting a new one or getting compost from the leprechaun"* — traced through
`client.log` and the stored profile. The first two are **fixed**; the third is open.

### 0. FIXED — a Fill into a box the model thought was full discarded the seeds

**`SeedInventoryStore.applyPendingSeedBoxAction`.** The Fill derivation refused to credit a
seed to a box whose model already held six kinds, on the reasoning that a full box cannot take
a seventh. True of the **box**; false of the **model** of it. When the two disagree the game
has just said so, and the code discarded the message.

From the run's own log, 12:28:52:

```
A Fill cannot put seed 5100 in a box already holding 6 kinds - either the 681 that
left the pack went elsewhere, or this model of the box is wrong. Taking the next read of it.
```

Seed 5100 is limpwurt. 681 of them went into the box and were dropped from the model, which
went on reporting `Limpwurt x1 (inv 0, box 0, bank 0, vault 0)`. The flower run then had no
seed, the guild's flower patch produced no step, the stop completed and the run routed on.
The same refusal also ate seed 5321 (190, then 9), 5282 and 5298.

**The escape hatch was unreachable.** "Taking the next read of it" — and `boxSuspect`'s own
javadoc — rest on a `SEED_BOX` container arriving. `getItemContainer(573)` has returned null
on *every attempt of every session on record*: `box 0 read, 56 with no container` in the run
line, and not one `Seed box reconciled from the client` in any archived log. So the discard
was permanent.

Three defects, one root cause, and the third is why the other two shipped:
`relearnInventoryFromClient`'s javadoc asserted the box "is a container you carry,
`getItemContainer` answers for it exactly as it does for the pack." It does not, and two
repairs were built on the belief that it does. That paragraph is now corrected and states
what the logs actually show.

**Fix:** the observation outranks the model. The seeds are recorded, the model is allowed over
capacity, and `boxSuspect` is set. That over-capacity state is not a new hazard — it is
precisely the drift signal `SeedBox.KINDS` documents itself as being and that
`boxCannotBeRight` already acts on; refusing the merge is what stopped the signal ever being
recorded. Tests: `SeedBoxDriftTest.aFillIntoAModelledFullBoxIsBelievedOverTheModel` and
`aFillItCannotAccountForIsKeptAndMakesTheNextReadWin`, the latter rewritten — it previously
asserted the disappearance.

**The trade-off is real and chosen, not free.** Banking a whole stack within two ticks of a
Fill is still indistinguishable from filling, so it can now be credited to the box in the
seventh-kind case as it already could in the other six. That direction is the safe one: an
over-count makes the run plan a plant, arrive, find no seed and say where it thinks they are —
visible and correctable. An under-count skips a patch in silence, which is what happened here.
Same call `docs/TODO.md` makes about the bottomless bucket.

### 0b. FIXED — an unchanged read never persisted when it happened

**`SeedInventoryStore.store`.** `lastSeen` was refreshed on every read but `save()` was gated
on the **counts** changing. Opening a bank whose seed counts match what is stored is the
ordinary case, so the fresh stamp lived in memory and died at the next restart.

Reported as a run planned at 12:42 announcing `bank 2h ago` when `BankFilter` shows the bank
open at 12:31. The stamp on disk was from 10:03 — the last time the counts themselves moved.
It reads as cosmetic and is not: `whereSeedsAre` and the run-planned line quote this to say how
far an answer can be trusted, so a stale stamp discredits a good count. It also actively
misled this review's own diagnosis.

**Fix:** save when the counts changed **or** when the stored stamp is more than
`SAVE_STAMP_SECONDS` (60) old. The original guard's reason survives — banking fires this with a
thousand items and writing config each time is what made banking stutter — and the expensive
half, `fireChanged()`, was always gated separately by the caller. Test:
`PersistenceTest.anUnchangedReadStillRecordsWhenItHappened`, which pins it through a reload,
because in memory the stamp was always right. **Pre-existing, not from this change set.**

### 0d. FIXED — a store that never read its blob wrote over it, every client start

**`ProfileJsonStore.save` / `load`.** `save()` serialises the whole in-memory map with **no
merge**, and `load()` tolerates having nothing to read: at the login screen there is no
RuneScape profile, `getRSProfileConfiguration` answers null, `applyJson` is skipped, and
`resetForLoad()` has already emptied the map. Anything writing in that window persisted the
emptiness over a full blob.

Found in the live profile: `patchLocations` held **six** entries against **107** observed
patches, and all six were in the region of the last login — a complete region, not a scatter.
`PatchLocationCapture` is the only writer driven by an event that can fire that early
(`GameObjectSpawned`, as the login scene builds) and it has no game-state guard, which is why
it was the store that lost its data. The control: `banks`, same base class but a post-login
writer, holds entries spread across the map.

Unlike `PatchStateStore` — whose `afterLoad()` backfills from core Time Tracking (318
`timetracking` keys in the same profile) — `patchLocations` has no regeneration path, so the
losses were permanent.

**Fix:** the base class now records *whether* and *for which RS profile* the blob was read, and
`save()` refuses (at WARN, naming the store) until it has. Keyed on the profile rather than a
bare flag, so it also closes writing account A's map into account B's blob. The flag is armed
**before** `afterLoad()`, or `PatchStateStore`'s backfill save would be silently discarded — a
fix for one store quietly breaking another. Test:
`PersistenceTest.aStoreThatNeverLoadedDoesNotOverwriteWhatIsStored`, verified to fail without
the guard.

Eleven existing tests failed on the guard, all constructing a store and writing without ever
calling `load()` — a lifecycle production never has, since `DoogleMapsPlugin` loads all thirteen
stores at start-up. Their fixtures now load, which makes them more faithful, not less.

**Cost while it was live:** within-stop patch ordering. An unlearned patch falls back to a
*per-location* wiki pin, so every patch in a region returns the identical point,
`GuideTracker.distance` ties for every pair, and `sortedByDistance` collapses to declaration
order — the exact failure its own javadoc says it exists to prevent. Travel was never affected:
`WikiPatchLocations` pins 42 of 43 regions, so the region-centre fallback is effectively
unreachable.

Visible in the log as a natural experiment: Ardougne (10548, unlearned) is emitted
`4771; 4772; 4773; 4774` in every `noteStopOrder` line and never reorders, while Falador (12083,
the one region with learned positions) reorders freely as the player moves. The owner's read is
that this is behind much of the inner-region routing complaint, and the log supports it. The
follow-on — harvesting the learned positions into a per-patch table so every account has them
from first launch — is written up in `docs/TODO.md` under *Blocked on testing in the client*.

### 0e. FIXED — `PatchLocationStore.record` posted ConfigChanged under its own monitor

`record()` called `save()` inside `synchronized (this)`. `ProfileJsonStore.save`'s javadoc
forbids exactly that, by name — *"do not invoke this while holding the store's monitor, or the
write happens under your lock and the hole is back"* — and documents the deadlock it caused,
found by a JVM thread dump on an Explorer's ring teleport.

A save posts `ConfigChanged` synchronously into every subscriber, i.e. arbitrary plugin code
reaching into other stores. The trigger here is `GameObjectSpawned`, and a teleport spawns a
whole scene at once — the same event `docs/TODO.md`'s open, highest-priority freeze report is
pinned to. Now mutates under the lock and saves outside it, matching
`PatchStateStore.recordVarbit`.

**Still open, same shape:** `ProfileJsonStore.load()` calls `afterLoad()` *inside* its monitor,
and `PatchStateStore.afterLoad()` saves. That posts `ConfigChanged` under the lock on every
load. Not touched here because moving it is a real behavioural change, but it is the same
hazard and worth its own pass.

### 0c. OPEN — `Need.MISSING` is unreachable for seeds, so the bank never warns

**`RunLoadout.addSeeds`, `RunLoadout.java:590-597`.** `if (patches == 0) { continue; }` drops
the row for any seed the allocator gave no patches — and the allocator caps patches at
`owned / perPatch`, so a seed you own **none** of always has zero and always vanishes. The
surviving classifier at `:661` then cannot return `MISSING` at all: that needs
`owned <= inPack < wanted <= owned`, a contradiction. `LoadoutSummary:118`'s
*"Skipping &lt;x&gt; — you have none."* is dead code for seeds.

The player set off with **five** picked-but-unowned seeds — limpwurt, avantoe, snape grass,
watermelon, seaweed — and `still to collect: []`. Introduced by `31950fc` for a good reason
(a seed with nothing left to plant into should not be listed) that swallowed the owned-zero
case with it. Still promised in three places: `docs/TESTING.md:1763-1766` pins it as a passing
owner-tested criterion, `RunPlanner.java:2181-2185` documents it as current, and
`SeedSelectorPanel:558-563` relies on it to justify leaving a zero-stock seed picked.

Independent of §0 — with the box fixed, a player who genuinely owns none of a selected seed is
still told nothing until they are standing at the patch. Note the guide *did* speak at the
patch: `announceSkipsAtFinishedStop` emitted "Skipping farming guild - no seed." The gap is the
bank leg, which is the moment it could still have been acted on.

### 0f. FIXED — one stale scene object blanked the rest of the overlay for that frame

**`GuideOverlay`, every `getClickbox()` call site; `PlayerHouse.onGameTick`.** A week of logs
carries 25 `Error during overlay rendering` traces naming `com.dooglemaps`, and every one
bottoms out the same way:

```
java.lang.NullPointerException: null
	at dy.eh(dy.java:58418)
	at dy.ae(dy.java:1430)
	at ee.qt(ee.java)
	at fb.aw(fb.java:64273)
	at fb.getClickbox(fb.java:64062)
	at com.dooglemaps.guide.GuideOverlay.highlightHouseTeleports(GuideOverlay.java:1086)
	at com.dooglemaps.guide.GuideOverlay.render(GuideOverlay.java:160)
	at net.runelite.client.ui.overlay.OverlayRenderer.safeRender(...)
```

Nothing of ours is null anywhere in it — the NPE is raised **inside** the client building a
model against scene data a reload has already freed. So the `clickbox != null` check all five
call sites carried was dead weight: the failure is a throw, not a null. (Most occurrences log
with no frames at all, which is HotSpot's fast-throw kicking in after the same site throws
repeatedly — the burst shape, not 25 distinct events.)

Two causes, both fixed:

- **The window.** Scans run once a `GameTick`; the overlay draws every frame. Between a scene
  reload and the next tick, up to ~30 frames hold freed objects. `PlayerHouse` now drops its
  furniture on `GameStateChanged` rather than waiting for the tick — that is 22 of the 25.
- **The blast radius.** `OverlayRenderer.safeRender` catches per *overlay*, not per object, so
  one bad house portal took `highlightRouteObject` and `highlightPatchesAhead` down with it for
  that frame. `drawObject`/`clickboxOf`/`outlineObject` now guard each object and log once, so a
  stale one costs its own highlight and nothing else. This is what covers the other three
  traces, which came from `highlightSupplyPoints` and `highlightPatch` — scene scans with the
  same staleness and no event to hang a fix on.

Pinned by `HouseFurnitureGoesWithTheSceneTest`, including the obvious wrong fix (clearing on
*every* state change, which would blank the furniture on the tick it was found).

### 0g. FIXED — every menu swap was made and then overwritten by core's

**`GuideMenuSwap.onPostMenuSort`.** Reported from play: the fairy ring still opened on
Last-destination with the route going through a ring, the setting on and the guard passing.
Nothing in the swap was wrong. `EventBus.register` orders subscribers by

```java
comparingDouble(Subscriber::getPriority).reversed().thenComparing(<class name>)
```

so equal priorities break the tie **alphabetically**, and `com.dooglemaps.guide.GuideMenuSwap`
sorts ahead of `net.runelite.client.plugins.menuentryswapper.MenuEntrySwapperPlugin`. Core's
swapper subscribes to the same event, does the same job, and ran second — so it won. This was
never scoped to the fairy ring: *any* core swap wanting the same entry silently beat ours. The
ring is only where the two disagreed loudly enough to notice, helped by
`swapFairyRing=LAST_DESTINATION` being core's default and set in all five of this account's
profiles.

Fixed with `@Subscribe(priority = -1f)` — negative is **later**, since the comparator is
reversed. Last is the right end for this class: every swap in it is opt-in and scoped to a run
or a step, which is a narrower claim than a standing preference and should be the one that
survives; outside those windows nothing is touched and core's answer stands.

Pinned by `SwapRunsAfterCoreTest`, which runs the real `EventBus` against a stand-in whose class
name sorts after ours, rather than asserting on the annotation — the failure was ordering, and
the sign of a priority is exactly the sort of detail that reads correct while being backwards.

Same family as the `GameTick` ordering hazard in `docs/NOTES.md`, one event over. Worth a
standing rule: **a plugin that shares an event with a core plugin has an ordering relationship
with it whether or not anyone chose one.**

### 0h. FIXED — the fairy ring swap read a hop list that is always empty in a house

**`GuideMenuSwap.onPostMenuSort`.** Reported from play right after §0g: Configure still was not
the default on the POH garden ring. Different cause, and visible in the asymmetry — the ring was
*outlined* correctly the whole time and only its left click was wrong.

A house is an instance and Shortest Path cannot path from inside one, so
`planner.getCurrentTransports()` empties the moment the teleport lands. That list is all
`GuideStatus` carries, so `routeUsesAFairyRing(status)` saw no hops and concluded the route did
not use a ring — while the player stood in front of the one their route had picked.

`GuideTracker.liveTransports()` exists precisely for this: it keeps the last hop list that
planned *through* the house and answers with it while the live list is empty.
`GuideOverlay.highlightHouseTeleports` already used it, which is why the highlight was right.
Two callers, one question, two answers; the swap now asks the same way.

No effect outside a house — `liveTransports()` returns the live list whenever it is non-empty.

Pinned by `GuideMenuSwapTest.theGardenRingSwapsFromTheHopsThatEnteredTheHouse`, which sets the
snapshot empty and the live list non-empty, exactly as the house leaves them.

### 0i. FIXED — compost was withdrawn for patches that would never get it, then handed back

**`GuideTracker.countWanting`, `CompostBinPlan.addDepositStep`.** Reported as
*"our getting compost logic is really wacky, sometimes i get 2, sometimes 1, sometimes 4…
we're wasting a lot of time going back and forth to the lep"*. Two causes:

- **The count was too big.** A compost tier belongs to the *group* and survives that group's line
  being unticked or set to harvest-only, so `countWanting` counted every ripe patch at the stop
  whose group named the same tier — including ones this run will only pick. The withdrawal was
  sized from one question and the steps it supplies from another. It now applies the same
  `RunOption.full` test `stepsFor` uses, so the two cannot drift.
- **The surplus test did not exist.** `addDepositStep` handed over *every* filled bucket before
  refilling a bin, on the reasoning that "carrying compost onward buys nothing". True of compost
  that came out of the bin; false of compost withdrawn a minute ago for the patches around it.
  The session log shows the whole round trip:

  ```
  6192.4774=WITHDRAW_COMPOST; 6192.4774=APPLY_COMPOST; 6192.4772=HARVEST;
  6192.4771=HARVEST; 6192.4775=DEPOSIT_COMPOST; 6192.4773=HARVEST;
  ```

  Withdraw four, use one, store the other three — with three allotments still to pick that each
  want one. The deposit now keeps back, per tier, what the stop still has a use for. The
  out-of-slots deadlock guard deliberately keeps nothing back: with no free slot the run cannot
  proceed at all.

`CompostBinPlan`'s own javadoc had already diagnosed half of this — *"the buckets are not clutter
in that case, they are the compost for the patches being replanted at this same stop"* — but the
remedy was to move the deposit after the fill rather than to stop depositing what is about to be
used. Right insight, one step short.

Note that the count legitimately counts *down* as a stop is worked through; that part was never a
bug and still happens.

### 0j. FIXED — both coral nurseries lit, and a bin kept a held plot's stop alive

**`GuideOverlay.findPatchObjects`.** Both nurseries were outlined whenever either was the step's
patch. `UnderwaterApproach`'s own note said why — they "cannot be told apart from the objects
alone", since nothing on the seabed carries a patch varbit and the two object ids do not map
one-to-one onto the two patches. That was true when written and is not any more:
`MeasuredPatchLocations` ships `12581.4771` at (3299, 8860) and `.4772` at (3294, 8860), five
tiles apart. The by-id scan is now narrowed by position, falling back to marking all of them when
the position is unknown — a nursery lit twice being a smaller failure than one never lit at all.

**`RunPlanner.clusterHeld`.** *"I was just sent to ardougne farm… with watermelons still in
progress, per our combined allotment/herb/flower patches rule, we shouldn't have even gone
here."* The hold was working: the run's own `Shared plots` line has Ardougne **[free to visit]**
at plan time with all four patches ripe, and they were serviced. `Stop complete at Ardougne`
never appears in the log — the **bin** kept the stop alive. `binActionable` reports a closed bin
as actionable once its clock elapses, and `clusterHeld` had no opinion because a bin is not one
of `CLUSTER_TYPES`. So the trip the setting exists to prevent was made for the one patch on that
ground the setting could not see.

A bin is the best possible thing to make wait — nothing in it spoils, it has no disease clock,
and it stands among the patches that will want its compost. It is now held on exactly the plot's
terms, so an unticked or harvest-only plot never holds it and it cannot wait for ever.

Note the first version of this test passed *without* the fix: `planStops` already drops a stop
that would exist only for one of the seven small bins, so the bin's real effect is on
`isComplete`, not on stop creation. The test drives completion instead.

### 0k. FIXED — a lost protection payment was never re-checked, and a lost ash upgrade was silent

**`PatchStateStore.backfillFrom`, wired into `PatchInteractionTracker.scan`.** Reported as being
asked to pay for a coral patch that had not been harvested, on both nurseries at once. The
session's records settle who was wrong: Time Tracking held
`timetracking.…12581.4771.protected=true` and `.4772.protected=true` throughout, while this store
flipped both to false in one write at 12:15:56 — varbit unchanged at 4 (GROWING), no coral
harvest anywhere in the log, and the player standing in Ardougne at the time.

The reconciliation that answers this already existed and ran **once, at login**, on the reasoning
that it "answers a question about the past, and the past does not change". The premise is right
and the conclusion does not follow: what changes is *our* answer. It now also runs on region
entry, which is where a wrong answer starts costing money. It can only ever restore a payment and
is structurally incapable of revoking one — it never sets false.

**Still open:** how the flag was lost. Nothing in this plugin sets protection false except
`applyVarbit`'s spent-payment rule — which needs the crop to leave or turn `HARVESTABLE`, and
neither happened — and `applyProtected`, which only ever receives `true` from `ProtectionCapture`
and logs at DEBUG on every change (the line is absent). The fix stops it costing anything; it does
not explain it.

**`CompostBinPlan.upgradeWouldBeLost` + `GuideTracker.noteMissingAsh`.** The `APPLY_ASH` step is
gated on the ash being in the pack, because "an instruction that cannot be followed is the
loadout's failure to prevent". That holds for a run that stays inside the plan it was stocked for,
and a run that keeps absorbing newly-ready stops outgrows that plan by design — the loadout is
computed once, at the bank. So the guide went quiet and the bin came out as plain supercompost.
Now announced once per bin. Still not a step: there is no ash to fetch at a bin, and the choice —
bank for it, or accept supercompost — is the player's.

### 0l. FIXED — the other half of §0f: every NPC and the player flickering with our colour

**`GuideOverlay.standsInTheScene`.** Reported as *"when a patch/tree/idk gets highlighted, ALL of
the NPCs in the area start flickering with the same colour highlighting, rapidly, extremely
rapidly. Including my own player character."*

Same stale object as §0f, on the frames where it does not have the decency to throw. Scans run on
a **tick**, the overlay draws every **frame**, so after a scene reload the caches hold objects
whose scene data the client has freed and reused. `getClickbox` walks that memory and raises an
NPE — loud, and the half that got noticed first. `ModelOutlineRenderer` walks it too and does
*not* throw: it draws an outline from whatever now occupies those slots, which is the NPCs
standing around you and your own player. The flicker is per-frame because the reused memory
changes per frame.

Worth recording as a lesson: §0f's try/catch was never going to help this one. Nothing was
throwing. It needed the question nobody was asking — *is this object still here?* —
which `LocalPoint.isInScene()` answers for free. Every draw path now asks it, and `render()`
additionally drops all five scan caches whenever the game state is not `LOGGED_IN`, the same
guard `PlayerHouse` carries for the same reason.

**The pattern, now three deep.** §0f, §0g and §0h are all one shape: *two places answering the
same question from different sources.* The clickbox guard existed at five call sites and was
wrong at all five; the swap and core's swapper both reordered the same menu; the overlay and the
swap both asked whether the route uses a ring. Worth watching for — when a second caller needs an
answer the first already computes, take the first one's route to it rather than a fresh one.

---

## HIGH

### 1. FIXED — a harvest-only stop completes under the player's feet at the first berry

> **Fixed after it was reported from play**, in exactly the shape predicted below: on a
> harvest-only bush run at the Ardougne monastery, picking one white berry ended the stop, so
> `stopAt` went null and the only thing left to say was the leaving errand — *"told to note each
> white berry as I collected it, instead of finishing harvesting"*. `heldForRegrowth` now carries
> the same standing-here guard `clusterHeld` has. Regression test:
> `RunPlannerTest.standingAtAPartlyPickedPatchTheStopStaysOpen`, which sits beside the shipped
> `aPartlyRegrownCactusDoesNotFetchYouBack` so both halves are pinned — away from the patch one
> spine is still not a trip. §2's shared-predicate fix is still the better repair and still open.


**`RunPlanner.java:897`** adds `!heldForRegrowth(patch)` to `isComplete`. The method's own
javadoc (`RunPlanner.java:1425-1434`) says why that must not happen:

> *Planning-time only, like `clusterHeld` and for the same reason: once the player is standing
> there picking, the stock falls and more starts regrowing — a completion filter would read
> "no longer full" as "not worth visiting" and finish the stop under their feet at the first
> berry.*

`clusterHeld` is protected from this by a standing-in-this-region guard
(`RunPlanner.java:1499`). **`heldForRegrowth` has no such guard**, so the warning lands.

**Mechanism.** `isRegrowing()` is `regrowEstimate > 0`, and `GrowthTimer.project`
(`GrowthTimer.java:394-397`) sets `regrowEstimate` to a real timestamp whenever
`livesRemaining < maximum` — i.e. the instant the plant is not full. So picking one unit
flips `heldForRegrowth` from false to true:

1. Harvest-only bush/cactus/fruit-tree run. Player arrives at a full patch — `livesRemaining
   == maximum`, `regrowEstimate == 0`, not held, step shown. Correct.
2. Player picks **one**. Now `regrows() && hasProduceToPick() && isRegrowing()` are all true
   → `heldForRegrowth` true → `isComplete` skips the patch → the stop is complete.
3. `getRemaining()` (`:2319`) drops it. `GuideTracker.stopAt` (`GuideTracker.java:3367`)
   walks only `getRemaining()`, so it returns null.
4. `computeStepsHere` returns at its `stop == null` branch (`GuideTracker.java:577-588`) with
   no steps — that branch only re-attaches leaving errands, never a harvest.

**Result: the guide goes silent and routes you onward with most of the crop still on the plant
in front of you.**

**Scope — this is the whole feature, not an edge.** Three patch types regrow
(`getRegrowTickrate() > 0`): **BUSH** (6 crops, holds 4), **FRUIT_TREE** (8 crops, holds 6) and
**CACTUS** (2 crops). A one-patch stop completes entirely on the first pick, and almost every
patch of these types is alone in its region:

| Type | Patches alone in their region |
|---|---|
| BUSH | Ardougne 10290 · Rimmington 12596 · Etceteria 10300 · Champions' Guild 11570 |
| FRUIT_TREE | Gnome Stronghold 11058 · Tree Gnome Village 11317 · Brimhaven 9781 · Catherby 9265 · Tai Bwo Wannai 9777 · Lletya 5423 |
| CACTUS | Al Kharid 13106 |

So on a harvest-only bush run **every stop is a one-patch stop**, and picking one berry of four
ends it. A harvest-only fruit-tree run abandons five fruit of six. Only the Farming Guild
(4922), which holds all three types among nine others, degrades gracefully — and only because
its other patches keep the stop alive.

**Verified, not inferred.** A probe added to `RunPlannerTest` (player standing in the
cactus's own region, varbit 18 → 17) printed `remaining=1` before the pick and `remaining=0`
after. The probe was removed again; the suite is green as found.

The shipped test `aPartlyRegrownCactusDoesNotFetchYouBack`
(`RunPlannerTest.java:1258-1281`) does not catch this because it calls
`standingIn(VARROCK_REGION)` — it only ever exercises the away-from-the-stop case, which is
the half that works.

**Fix direction.** Give `heldForRegrowth` the same standing-here guard `clusterHeld` has, and
add the missing regression test: a harvest-only bush run, player standing at the bush, one
berry picked, stop still in `getRemaining()`. The deeper repair is that "do not travel back
here" and "there is nothing to do here" are different statements — the first belongs with
`announced`/`skippedRegions`, which govern re-entry, not with `isComplete`, which governs
whether work exists.

---

## MEDIUM

### 2. `clusterHeld`'s standing guard uses a narrower "here" than the guide does

**`RunPlanner.java:897`** also adds `!clusterHeld(patch, types)`, and that method's javadoc
(`:1483-1486`) says "never in `isComplete`" just as plainly. Here a guard at `:1499` is
*meant* to cover it:

```java
if (patch.getRegion().getRegionId() == playerLocation.getRegionId())
{
    return false;   // never hold the plot being stood on
}
```

But that is not the test the guide uses to decide the player is at a stop. **There are two
incompatible definitions of "here" in the codebase**, and the guard has the weaker one:

| | Definition of "the player is at this stop" |
|---|---|
| `GuideTracker.standingAt` (`:3405-3431`) | `stop.claimsRegion(playerRegion)` **or** (regions touch **and** within `STOP_EDGE_TILES` = 10 of a patch) |
| `RunPlanner.clusterHeld` (`:1499`) | `patch.getRegion().getRegionId() == playerRegion` — exact, and not even `claimsRegion` |

Any state where the first is true and the second false lets the hold apply to a player who is
standing at the stop — the same failure as #1, through a narrower door. That state is not
hypothetical: `STOP_EDGE_TILES`' own javadoc (`GuideTracker.java:3384-3394`) records it as a
reported bug —

> *the Farming Guild's cactus patch works out to sit close enough to the edge that standing on
> its far side put the player's region id one column over.*

— and Farming Guild region 4922 holds two allotments, a flower and a herb, all three
`CLUSTER_TYPES`. So standing on the far side of the guild with `holdClustersUntilReady` on,
`stopAt` says you are at the stop while `clusterHeld` says you are not, and the stop can
complete under you.

**Fix direction: one predicate, shared.** Both #1 and #2 are the hold rules disagreeing with
the guide about where the player is. Give `RunStop` (or `RunPlanner`) a single
`playerIsHere(stop)` built on `claimsRegion` plus the tile tolerance, and have `clusterHeld`,
`heldForRegrowth` and `GuideTracker.standingAt` all call it. Correct the two javadoc
paragraphs in the same change, saying that `isComplete` is now a legal caller and naming the
guard that makes it safe — because a method whose doc forbids its only new caller is exactly
how #1 shipped.

### 3. New lock nesting on the hottest path, in the file whose top open issue is a freeze

`RunPlanner.java:331-335` states the rule this class is built on:

> *nothing outside this class is ever called with the lock held.*

Two new paths break it, both reachable every tick or from the EDT:

| Path | Called out of the lock |
|---|---|
| `getRemaining()` (`:2319`, synchronized) → `isComplete` → `clusterHeld` | `config.holdClustersUntilReady()` → RuneLite `ConfigManager` |
| `binWork()` (`:1170`, synchronized), `previewStops()` (`:350`), `start()` (`:275-285`) → `planStops` → `inTheRun` / `addOpportunisticBins` / `guildWillFeed` | `compostRun.isFodderEnabled()` / `getFodderCrops()` → `ConfigManager` |

`getRemaining()` runs on every game tick from `GuideTracker.onGameTick` and from the panel;
`previewStops` is an EDT call. So `ConfigManager` is now entered under this class's monitor
from both threads.

**This is a discipline violation, not a proven deadlock, and the difference was checked
rather than assumed.** Three things have to be true for it to bite, and the third is false:

1. A config *read* takes `ConfigManager`'s internal lock — plausible.
2. Something else takes that lock and then wants `RunPlanner`'s. A config *write* posts
   `ConfigChanged` synchronously, and `DoogleMapsPlugin.onConfigChanged` (`:561`) is the only
   subscriber that could reach the planner.
3. …but it returns early at `:598` unless the key is in `SETTING_KEYS`, which is built by
   reflection from `DoogleMapsConfig`'s `@ConfigItem` annotations (`:536-549`). The stores'
   own keys are not `@ConfigItem`s, so a store write never reaches `panel.configChanged` /
   `refresh()`. **No inversion exists.**

The other new nesting — `heldForRegrowth` (`:1440`) → `runOptions.isHarvestOnly` taking
`RunTypeStore`'s monitor under `RunPlanner`'s — is **safe and worth recording as safe**:
`isSelected`/`isHarvestOnly` read an in-memory `LinkedHashSet` and touch no config, and
`RunTypeStore` holds no listener list and no reference back, so it is a leaf exactly like
`PatchStateStore`. `ProfileJsonStore` also documents and follows the "mutate under the lock,
`save()` outside it" rule (`:91-96`, and `RunTypeStore:221`/`:232` obeys it), which is what
keeps writes off this graph too.

So what is left is that the rule the file states about itself is no longer true, in the class
whose top open issue is an unexplained freeze — and the next person to audit it will have to
redo the three steps above to find that out. Note the care taken a few lines away: `start()`
deliberately calls `config.holdClustersUntilReady()` at `:326` **outside** the lock, and
`reviewBins()` reads the fodder toggle outside its `synchronized` block. The pattern is
understood; these sites just missed it.

**Fix direction.** Hoist the config reads to the top of the public entry points and pass them
down as parameters, exactly as `start` already does for `withdrawOutstanding` and
`clusterHeld` logging. `isComplete` should take a small immutable "run rules" value rather
than reaching for two stores per patch per call.

### 4. Twenty-three javadoc blocks have come loose from the methods they document

An edit pattern in this change set has left `/** … */` blocks stacked directly on top of
other `/** … */` blocks, so the first documents nothing and the second appears to be preceded
by an explanation of something else. Two of them are load-bearing:

- **`RunPlanner.java:609`** — `isActionable`'s doc ("Whether this patch is worth walking to
  right now") now floats above `countActionable`. One of the two core predicates of the class
  has lost its documentation, and a reader of `countActionable` gets a stale one.
- **`GuideTracker.java:2399`** — `chooseWorkingPatch`'s "The one piece of state in guided
  mode, and why it is allowed" — the justification for the single mutable field in an
  otherwise pure design — now sits above `flowersAfterAllotments`.

The full list, machine-detectable as "a javadoc block whose next non-blank line opens another
javadoc block":

```
DoogleMapsConfig.java:198        guide/GuideTracker.java:2399, 2913, 3463
bank/RunLoadout.java:51,408,1579 guide/HouseTeleports.java:200
guide/CompostBinPlan.java:325    route/RunPlanner.java:609, 2160, 2350
guide/GuideAction.java:166       ui/CompostBinPanel.java:373
guide/GuideInventoryOverlay.java:554  ui/Controls.java:33
guide/GuideOverlay.java:797      ui/PatchTypePanel.java:247
guide/GuidePlan.java:635         ui/RunPanel.java:383, 736
                                 ui/SeedSelectorPanel.java:162, 489
```

Mechanical to fix and worth doing in one pass, because this is the repository's primary
design record and the damage compounds silently.

### 5. `CompostRunStore`'s listener API is never registered with

`CompostRunStore.java:364-380` implements `addChangeListener`, `removeChangeListener` and
`changed()`, and the field comment says *"Swing listeners, like every other store the sidebar
draws from."* Nothing anywhere calls `compostRun.addChangeListener`. `DoogleMapsPlugin.java:361-371`
wires up eight sibling stores and omits this one, so every `changed()` iterates an empty list.

The visible consequence is small but real: `RunPanel.binsWantAFill` (`RunPanel.java:913`)
reads `compostRun.hasFill()` to decide whether to warn that no fill is picked, and
`CompostBinPanel` refreshes only itself after a toggle (`:176`, `:244`, `:517`). The Run tab's
warning is therefore stale until some other signal repaints it.

Related, same file: `ashBox.addActionListener` (`CompostBinPanel.java:183`) does not call
`refresh()` where `fodderBox` (`:175-176`) does. Harmless today — nothing rendered depends on
`isAshing()` beyond the checkbox itself — but it is the asymmetry that makes the next
ash-dependent label wrong.

**Either wire it up in `DoogleMapsPlugin` beside the other eight, or delete the three methods.**

### 6. Config is re-read and re-parsed on per-tick and per-item paths

`CompostRunStore` reads live from `ConfigManager` on every call by design, and its class doc
justifies that with *"two scalar keys are not worth a load step"*. It is now four keys, and
two of them (`getFills`, `getFodderCrops`) `split(",")` a string and rebuild a collection on
every invocation. The justification no longer describes the class.

Where that lands:

- `CompostBinPlan.addFillAndDeposit` (`:445`) calls `bestFodder`, which calls
  `isFodderEnabled()` + `getFodderCrops()`; it then calls `addFillStep`, which calls
  `bestFodder` **again**. Twice per bin per tick, and steps are re-derived every tick.
- `CompostBinPanel.fillWarning` is invoked twice per refresh (`:292-293`, once for the text
  and once to test emptiness) and calls `store.getFodderCrops()` **inside its own loop**
  (`:317-319`) — an O(n) sequence of config reads and string parses where one suffices.
- `RunPlanner` asks `isFodderEnabled()` at five sites, three of them under the monitor (see #3).

None of this is visible at farming tick rates today. It is flagged because `docs/TODO.md`
already records that `addTeleports` is where a per-tick performance bug hid, and this is the
same shape in a newer file.

### 7. The oversized classes have more than doubled, and the tests now reach through them

`docs/TODO.md` records "four classes past readable size, and the gap is widening" at
`RunPlanner` 1,613 · `GuideTracker` 1,517 · `RunLoadout` 1,258. Today:

| Class | Then | Now |
|---|---:|---:|
| `guide/GuideTracker` | 1,517 | **3,598** |
| `route/RunPlanner` | 1,613 | **3,340** |
| `bank/RunLoadout` | 1,258 | **1,940** |
| `DoogleMapsConfig` | 1,213 | 1,421 *(excluded — mechanical toggles)* |

`GuideTracker` now carries 90+ methods. The new symptom is in the tests: roughly fifteen
`getDeclaredMethod(...).setAccessible(true).invoke(...)` calls now reach private methods —
`travelHint`, `contractNote`, `binsFirst`, `findPatchForNpc`, `getSupplyTargets` — because
there is no public seam to test them through. (`Construct.java` is not part of this; a shared
helper for package-private `@Inject` constructors is the right call and is well documented.)

Finding #1 is the argument for doing something about it: an invariant stated in a javadoc
paragraph 550 lines away from its violation is invisible in a 3,300-line file. `RunLoadout`
is still the one that splits cleanly.

---

## LOW

- **Dead code introduced by this change set.** `GuideMenuSwap.anyBoxedSeeds`
  (`:233`) lost its only caller when `wantedBoxOption` was simplified — it survives only as a
  `{@code}` reference in the replacement's javadoc. `GuideTracker.destinationName` (`:3254`)
  lost its callers to `destinationStop`. Both are private, so nothing warns.
- **`CompostBinPlan.addEmptyingSteps` can emit an unfollowable instruction.** With no empty
  buckets, no free slots and no filled buckets to deposit, `addDepositStep` adds nothing, the
  guard at `:171-179` falls through, and `Math.max(1, Math.min(remaining, 0))` produces
  "Withdraw 1 empty bucket" followed by "Empty the bin into your buckets" against a full pack.
  Rare, and self-clearing, but the file's own doctrine is that an instruction that cannot be
  followed is a bug.
- **`divertForSupplies` (`RunPlanner.java:2660`) can re-arm indefinitely.** Its doc addresses
  the empty-bank case (rows go `MISSING`, not `WITHDRAW`), but a player who reaches the bank
  and leaves without withdrawing still has `suppliesOutstanding()` true, so the run diverts
  again rather than ending. The escape hatch exists — `bankLegWaived` via skip — but the run
  cannot terminate on its own. Worth one sentence in the javadoc, or a diversion counter.
- **`CompostBin.coveredByTheBinTick` (`:114-116`)** returns the caller's own set when
  `COMPOST` is absent, and a defensive copy when it is present. Callers get an aliased set
  half the time. Return a copy on both paths.
- **`SeedBox.kindsIn(Collection<Integer>)`** counts entries, not distinct ids, so a caller
  passing a `List` with duplicates over-counts against `KINDS`. Every current caller passes a
  map's key set, so it is correct today; the parameter type invites the bug.
- **Duplicated work in `SeedInventoryStore.record`** — `countSeeds(container)` is called at
  `:186` and again at `:208` on the seed-box path inside the action window.

---

## What is healthy, and worth not regressing

- **Security: clean.** No secrets, no credentials, no network calls, no `ProcessBuilder`, no
  reflection into other plugins. The only file IO in `src/main` is `HarvestHistory`'s
  temp-file-then-move CSV write. JDWP is bound to `127.0.0.1` (`build.gradle:91`), closing
  the previous review's §2.
- **Plugin Hub compliance holds.** The new `payLeftClick` swap reorders only, matches on the
  patch's own name appearing in the game's own option string, never renames or removes, has
  its own setting, and is scoped to a current step — inside the ruling and inside the table in
  `docs/design-principles.md`. The `dropEmptyBuckets` bin exception is a genuine improvement:
  the swap no longer argues with the instruction.
- **Tick-caching discipline.** `GuideStatus`, `RunSnapshot` and
  `BankHighlightOverlay.refreshWanted` all resolve once per tick and are read lock-free. This
  is the right answer to the render-thread lock traffic and it is applied consistently.
- **`data/SeedBox` and `data/FarmerVariants`** are model refactors, not features: each takes a
  question four files were answering separately and gives it one home, with the disagreement
  that motivated it written down. This is the pattern that keeps paying.
- **1,064 tests, and the new ones test behaviour.** `GuildBinPrecedenceTest`,
  `ContractSeedFetchStepTest` and `SkippedStopStaysFinishedTest` all pin reported bugs by
  their symptom rather than their implementation.

---

## Suggested order

1. **One shared `playerIsHere(stop)` predicate**, used by `clusterHeld`, `heldForRegrowth` and
   `GuideTracker.standingAt`, built on `claimsRegion` plus the 10-tile tolerance. This is the
   single fix for both #1 and #2, and it removes the class of bug rather than the instance.
2. **The regression tests that were missing**, both of the standing-there shape: a harvest-only
   bush run with one berry picked, and a cluster stop entered from a touching region. The
   shipped `aPartlyRegrownCactusDoesNotFetchYouBack` covers only the away case.
3. **Correct the two javadoc paragraphs** to name `isComplete` as a legal caller and the guard
   that makes it one. (#1, #2)
4. **Hoist the two config reads out of the `RunPlanner` monitor**, and re-state the file's own
   rule with the `RunTypeStore` edge recorded as a checked exception. (#3)
5. **One mechanical pass over the 23 detached javadoc blocks.** (#4)
6. Wire or delete `CompostRunStore`'s listeners; add `refresh()` to the ash box. (#5)
7. Cache the two parsed lists in `CompostRunStore`, invalidated on write. (#6)
8. Fold the dead methods into the tracked sweep. (LOW)

Items 1–5 are the ones that would have been cheaper to catch than to have found.

## A note on the pattern behind #1, #2 and #4

All three are the same failure of a technique this repository otherwise uses better than most:
**invariants written as prose and enforced by nobody.** `heldForRegrowth` and `clusterHeld`
each state their constraint clearly, in the right place, with the consequence spelled out —
and a caller 550 lines away violated both without anything objecting. The 23 orphaned javadoc
blocks are the same weakness in its passive form: prose that has quietly stopped describing
the thing beneath it.

The cheap countermeasure is not less prose. It is to give the load-bearing sentences a
mechanical shadow — a shared predicate instead of a rule about which methods may call what
(#1/#2), a test whose name is the sentence, or a private method that simply cannot be reached
from the forbidden caller. Where a rule matters enough to write a paragraph about, it matters
enough to make unsayable in code.
