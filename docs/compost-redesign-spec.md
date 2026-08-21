# Spec: one compost model, and bank legs planned per bin

Drafted 2026-08-18 from the owner's proposal, **unsettled on purpose**. The open questions in §7
are the point of the document; nothing here is decided until they are answered.

Companion to `docs/stop-planner-spec.md`, which solves the adjacent problem one scope down. See
§6 — whether these are one piece of work or two is the first thing to settle, and it changes
everything below it.

---

## 1. The problem

The compost tab is clunky, and the clunkiness is not in the panel. It is that **three different
models of a compost bin are on screen at once**, and the player is asked to hold all three:

- the **guild's big bin**, which is banked for;
- the **seven allotment bins**, which are fed from the harvest you are standing in;
- and a **fodder toggle** that switches the second group on and off from a section that looks
  like a sibling of the first rather than a mode of it.

That split was deliberate and is defensible — there is no bank near any of the seven, so
carrying their fill across the map costs a farm run's worth of inventory. But it is expressed as
*two kinds of bin* when it is really *one kind of bin with two sources of fill*, and every
consequence of that leaks: a separate section, a separate toggle, a run tick that means one bin
and covers another (`CompostBin.coveredByTheBinTick`), and a loadout that withholds fill for the
seven while still packing their ash.

## 2. The arithmetic nobody chose

This is the constraint the redesign is really about, and it is the game's, not the plugin's.

| | fill items | ash | pack |
|---|---|---|---|
| Normal bin | **15**, un-noted | 25 | 28 slots |
| Guild's big bin | **30**, un-noted | 50 | 28 slots |

A bin's fill cannot be noted. So:

- **One bin per trip is forced, not preferred.** Fifteen items is most of an inventory; two bins'
  worth does not fit at all.
- **The big bin cannot be filled in one trip even starting empty.** Thirty items, twenty-eight
  slots. It has always needed two.

The code already half-knows this. `divertForSupplies` exists because *"more than one trip is the
normal case for bins rather than an edge"*, and `RunLoadout.fillReason` carries short-of-the-job
wording for a stop that asks for more than a pack holds. What it does **not** do is plan for it:
the trip is a rescue at the point the run would otherwise end, rather than the unit the run is
built from.

**The whole redesign is making the game's own constraint the plan's explicit unit.**

## 3. What the owner proposed

Verbatim in substance, 2026-08-18:

1. Stop treating the Farming Guild bin as different at all.
2. Move the fodder / fill-from-bank choice out of its own section and onto the bin's row in the
   patch status list, as a highlight colour.
3. Revise the banking logic to match: carry the fill for **one** bin before travelling, and plan
   a bank trip to collect the next bin's fill before moving on.
4. The guild bin works the same way, except the bank is adjacent so the "trip" is a walk rather
   than a teleport.

## 4. Why the current shape cannot do it

Three things, in order of how much they hurt.

**4.1 The loadout is one list per run.** `RunLoadout.forRun(types)` answers *"what does this whole
trip need"*, tick-cached on `(tick, types)`, and every consumer — the bank highlight, the filter,
the guide's supply lines, `anythingLeftToWithdraw` — asks it that question. The proposal needs
*"what do I take for the bin I am going to next"*, which is a **per-leg** question the class has
no concept of. This is the real architectural delta; points 1 and 2 are presentation on top of it.

**4.2 Bank legs are not route elements.** A run has one supply leg at the front, and
`divertForSupplies` can append another when the run would otherwise finish. There is no way to say
"bank, bin, bank, bin" as a plan. The latching machinery is closer than it looks —
`commitDestination` already decides one leg at a time from where the player stands — but it
chooses among *stops*, and a bank leg is not one.

**4.3 The guild's specialness is hard-coded in the routing, not just the panel.**
`banksItsFill(bin)` is `bin.getImplementation() == BIG_COMPOST`, and
`allotmentBinsInTheRun` exists to collect the other seven "for their ash and their buckets and
nothing else". Removing the distinction means those two go away and something per-bin replaces
them.

## 5. The model this points at

Sketched, not settled.

**5.1 A bin has a fill source, per bin.** Three states rather than a global toggle:

| State | Meaning |
|---|---|
| not in the run | the bin is not being serviced this trip |
| **harvest-fed** | filled from what you are holding when you finish the patches beside it; never banked for |
| **bank-fed** | its fill is collected from a bank before you travel to it |

Today's behaviour is this model with the state forced by patch type: big bin → bank-fed, the
seven → harvest-fed. Dropping the special case means letting the player say. The seven *default*
to harvest-fed for the reason the split was built — no bank within 65 tiles — and the guild's
defaults to bank-fed because its bank is adjacent, but neither is a rule any more.

**5.2 The unit of planning is one bin's fill.** A bank-fed bin contributes a **leg**: collect
exactly its capacity (15 or 30, capped by what the pack can hold), travel, fill, and if the bin
is not yet full, come back. A harvest-fed bin contributes nothing to any bank leg.

**5.3 The guild is not special, it is just cheap.** Its bank being adjacent makes the return trip
a walk instead of a teleport. That is a **cost difference the router already understands**, not a
category difference the plugin has to model. If the routing is cost-driven, the guild bin's
two-trip fill falls out for free rather than needing a rule.

**5.4 What the panel becomes.** The compost tab keeps the *fill item* picker — which crops go in
a bin is a shopping decision and belongs with the other shopping decisions. What moves to the
patch status row is the *per-bin mode*, as a highlight colour, because that is a fact about that
bin in that place.

## 6. The collision to settle first

`docs/stop-planner-spec.md` already carries the resource vocabulary this needs, in §4.4:

```
| FILL_BIN   | needs 15 (30 big) items in pack | un-noted only |
| APPLY_ASH  |                                 | needs 25 (50) ash in pack |
```

modelled exactly as this document wants them — preconditions and effects rather than ad-hoc
checks.

**But that spec is scoped to the work at one stop, and this is between stops.** Same vocabulary,
one scope up. The two readings:

- **One piece of work.** The stop planner grows a trip level, and compost is its first real
  consumer. Slower to start, and the resource model gets written once.
- **Two.** Compost gets its per-leg loadout now, and the stop planner later subsumes it. Faster,
  and risks designing the same thing twice — which is precisely why the architecture review
  deferred splitting `RunPlanner` rather than doing it ahead of the spec.

**This is question zero.** Everything in §5 is cheap to change on paper and expensive to change
once built against the wrong scope.

## 7. Open questions

**7.1 Is the per-bin mode three-state, as in §5.1?** If yes, fodder stops being a feature toggle
and becomes a mode — which is what makes dropping the guild special-case coherent, and what
removes the "Compost these" section's odd status as a sibling rather than a child.

**7.2 What happens to the Compost run tick?** One `RunOption` line covering all eight bins, or a
line per group? Today the tick names the ordinary bin's type and covers the big one via
`coveredByTheBinTick`, which exists only because those two are different things. If they stop
being different, does that fold go away entirely?

**7.3 How does a mode colour coexist with availability on the same row?** Locked decision #11
makes availability a **global invariant** expressed in exactly that UI — a patch switched off must
not be routed to, planted into, or highlighted. The row click already owns that toggle. A second
meaning on the same row must not make "switched off" and "not compost-selected" confusable, and
`PatchRow` currently has two badge slots (`shieldBadge`, `compostBadge`) and one click.

**7.4 Does the fill-item picker stay on the compost tab?** §5.4 assumes yes. The alternative —
fills chosen per bin — is more expressive and probably more clicking than anyone wants.

**7.5 What does the player see when a bank-fed bin is unreachable?** A bin whose fill is in the
bank, on a run with no bank leg left, is the case that currently ends a run early. Under the new
model it should say so on the row rather than silently drop the bin.

**7.6 Does a partly-filled bin remember what went in it?** The big bin's two trips mean the plan
has to know "15 of 30 done". `PatchProjection` reads a `FILLING` state; whether it can say *how
full* has not been checked.

## 8. Must not regress

- **No bank trip for a harvest-fed bin, ever.** The reason the split exists. Ardougne is 96 tiles
  from its bin.
- **Ash still reaches the seven.** Ash is one inventory slot however much you carry, so it is
  packed for the allotment bins today even though their fill is not. Losing that would remove
  ultracompost from those bins entirely.
- **A run with no fill picked still routes to an empty bin.** Settled with the owner: an empty bin
  with room in it is worth the trip, and the guide simply stays quiet there.
- **The full-pack step still stands down at a bin.** A bin refuses noted items, so the leprechaun
  must not note away the crop a bin beside you is waiting for.
- **Nothing unobtainable holds a bank leg open.** A row goes `MISSING` rather than `WITHDRAW` when
  the bank has none, which is what stops a supply leg that never ends.

---

## 9. Review, 2026-08-20

Prompted by a report from play that is this spec's thesis happening in the wild, at the one bin
where it hurts: *"I'm getting prompted to take watermelons out for the big bin before emptying it
first... after emptying it, I then need to bank to get the watermelons from the bank and continue
the run."*

### 9.1 The unit in §5.2 is right but incomplete: a bin is two phases, not one load

§5.2 makes "one bin's fill" the planning unit. The report shows the unit is really **one bin
phase**, because a ready bin's two phases have *opposite pack profiles*:

| Phase | Pack wants | Comes from | Precondition |
|---|---|---|---|
| **Empty** | free slots (buckets batch in/out via the leprechaun, batch size = free slots) | leprechaun beside the bin | bin holds compost |
| **Fill** | a pack of un-noted items | bank (or harvest) | bin reads empty |

They cannot share a trip at any bin, and empty strictly precedes fill — the game refuses fill
into a bin holding compost. So the leg model in §5.2 needs **precedence**, not just capacity:
a ready bank-fed bin contributes an empty-leg and then a fill-leg, and the fill-leg's bank
collection must be sequenced *after* the empty. §6's resource vocabulary has `FILL_BIN` and
`APPLY_ASH` but no `EMPTY_BIN` row; it needs one ("needs free slots + empties at leprechaun,
produces N compost into leprechaun/bank").

The player-state trace that makes it concrete, at the guild: arrive with ash (1 slot) and a free
pack → ash the bin → batch-empty against whatever room is going → compost to the leprechaun →
bin reads empty → *now* the chest fourteen tiles away has a reason to exist → one pack-load of
fill → top up on a second if short. Every leg of that is already a concept the code has; what was
missing was refusing to collect the fill before the empty.

### 9.2 Fixed ahead of the redesign (2026-08-20)

The proximate bug did not need question zero answered. `RunPlanner.count()` counted a ready
bin's fill "at the same stop, on the same visit", so the pre-run supply leg withdrew it — into
the pack the emptying needed. Now a ready bank-fed bin counts as *fillable* (the pick-a-fill
warning and worth-visiting answer still see it) but contributes **no fill items until its varbit
reads empty**; from there the fill row goes WITHDRAW and `divertForSupplies` makes the collection
the run's next leg. At the guild — the only place a bank-fed bin exists — that is a walk to the
in-region chest.

History worth keeping: the up-front fill was itself the fix for an older dead end (all-ready runs
packed no produce; the run ended with the bin standing open —
`RunLoadoutTest.aReadyBinBanksNoFillUntilItIsEmptied` carries both stories). That dead end stays
fixed by `divertForSupplies`, which postdates it: a run out of work with a withdrawal outstanding
is sent back for another load rather than ended.

Known remaining coarseness, which is the redesign's job, not a bug: the divert fires when the run
*would otherwise end*, so on a multi-stop run the guild fill is collected on a return trip at the
end rather than immediately after the empty. The immediate version exists in miniature already —
the contract-collection path re-enters the bank leg mid-stop via `canBankHere`
(`atBankLeg || !here || canBankHere` + retarget) — so "bin just read empty && fill outstanding &&
supply point in-region" could re-use it. That is §4.2's "bank legs are route elements" arriving
one special case at a time; whether to do it now or wait for the redesign is a scope call.

> Called, 2026-08-20 evening: two reports in one play session ("never got routed back to the
> bank for watermelons", "sending me to ardougne instead of to get the rest of my melons").
> `RunPlanner.reviewNearbySupplies` now re-enters the bank leg when a COMPLETE stop has supplies
> outstanding and a supply point in its own region — the canBankHere shape, generalised, gated
> per tick on complete-here (the anti-yank rule) rather than on the completion edge, because at
> the edge the pack is still full of harvest and the fill row asks for nothing. The end-of-run
> divert keeps every other case.

### 9.3 Open questions this settles or adds

- **§7.6 — answered, from code.** A part-filled bin does say how full: `FILLING`'s stage is the
  item count less one, and `count()` already budgets the remainder as
  `capacity - (stage + 1)`. The two-trip big-bin plan can rely on it.
- **§7.7 (new) — compost produced upstream meets compost consumed downstream.** Emptying sends
  compost to the leprechaun, and `RunLoadout.addCompost` already answers patch-compost from the
  leprechaun's store (`AT_LEPRECHAUN`), so the loop closes today. But the *pre-run* loadout
  prices patch compost against the store as it stands, before the emptying tops it up — a run
  that will scoop 30 ultras may still be told to withdraw ultracompost. The redesign's resource
  model should let a planned `EMPTY_BIN`'s output satisfy a later `APPLY_COMPOST`'s input.
- **§7.8 (new) — an unknown bin is assumed fillable.** A bin never seen (no projection) is
  budgeted at full capacity on the first visit's supply leg. If it turns out ready, the old bug
  replays once. Acceptable — the first visit teaches it — but the redesign should decide it on
  purpose.

### 9.4 Additions to §8 (must not regress)

- **Fill is never asked for while its bin still holds compost.** The phase boundary of 9.1;
  `RunPlannerTest.aReadyBigBinsFillWaitsForTheEmptying` pins it.
- **An emptied bin's fill is asked for the moment it reads empty.** The other half, and the old
  dead end's guard; `RunPlannerTest.anEmptiedBigBinAsksForItsFill` pins it.
