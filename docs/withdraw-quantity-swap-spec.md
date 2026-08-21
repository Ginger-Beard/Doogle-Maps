# Spec: swapping the withdraw quantity to the one the run actually wants

Drafted 2026-08-18 from the owner's proposal.

**Scope ruled in by the owner, 2026-08-18**: this is a menu-reordering feature and sits in the
same category as the six swaps `GuideMenuSwap` already carries. Recorded here so it is not
re-opened; the mechanics below stay inside that category by construction — see §10.

---

## 1. The problem

`Withdraw-X` cannot be set by a plugin. That was established previously and is not revisited
here: the amount behind X is the player's, stored client-side, and nothing offers a way to write
it.

So the run knows exactly how many of something it wants and has no way to ask for that number.
What it has instead is a fixed menu:

| Where | Options offered |
|---|---|
| Bank | Withdraw-1, Withdraw-5, Withdraw-10, Withdraw-X, Withdraw-All |
| Leprechaun's store | **Remove**-1, Remove-5, Remove-X, Remove-All — **no 10** |

The player's answer to that gap is Withdraw-All, because it is one click and always enough. It is
also the wrong click most of the time, and the cost is specific: **fifteen empty buckets when the
bin run needed four leaves eleven slots of a harvest in the ground.** The plugin already computes
how many were wanted — `LoadoutItem.outstanding` is exactly that number — and then watches the
player take a full stack anyway, because taking the right number is three clicks and taking all
of them is one.

## 2. The idea

Do not try to set X. **Choose among the quantities the game already offers**, and put the best one
under the left click, updating as the count comes down.

The owner's example: fifteen watermelons for a bin. Swap to `Withdraw-10`; after that click five
remain, so swap to `Withdraw-5`; after that the item is done and the swap moves to the next thing
on the list. Two clicks instead of a stack, with no arithmetic asked of the player.

The same machinery answers a second question — **noting** — in §6, and the deposit side is
deliberately much simpler: see §7.

## 3. What it needs to know, and already has

Nothing new has to be modelled. The three inputs exist:

| Input | Source |
|---|---|
| how many of this item are still wanted | `LoadoutItem.outstanding` — already "what the run wants, less what is on you" |
| how much room is left | `CarriedItems.getFreeSlots()` |
| whether a run is on, and which leg | `GuideTracker` / `RunPlanner.isAtBankLeg` |

The swap is therefore a *presentation* of the withdraw list the bank panel already draws, which is
the property worth keeping: if the highlighted row says "4 buckets", the click under the mouse
should take four, and the two must not be able to disagree.

## 4. Choosing the quantity

Greedy, largest offered option that does not exceed what is still wanted:

```
remaining = min(outstanding, what free space allows)
pick the largest offered option <= remaining
pick All only where §5 says the item is taken wholesale
```

Worked examples, at a bank:

| Still wanted | Swap sequence | Clicks |
|---|---|---|
| 15 | 10, then 5 | 2 |
| 7 | 5, then 1, 1 | 3 |
| 4 | 1, 1, 1, 1 | 4 |
| 23 | 10, 10, 1, 1, 1 | 5 |

**Never overshoot. Settled with the owner, 2026-08-18.** Taking five for a wanted four was
considered and rejected: a few extra clicks are fine, and the spare item is not. Four buckets as
four clicks is accepted as the cost of the rule rather than treated as a hole in it.

That decision buys the invariant §3 asks for and would otherwise be impossible: **the click never
takes more than the panel says.** The highlighted row and the option under the mouse cannot
disagree, in any case, ever — which also means the feature never has to explain itself.

**At the leprechaun the ladder is 1 and 5 only, and his verb is "Remove" rather than "Withdraw"**
— confirmed in play. The amount is read as the number after the first dash, so the verb never
mattered; it is written down because the wording is the thing a reader would assume.

Counts land worse there and the click count goes up; that is the same accepted cost, not a special
case.

## 5. What it applies to, and what it must not

Settled with the owner:

| Item | Behaviour | Why |
|---|---|---|
| **Bin fills** (watermelons and the rest) | quantity-swapped | the case that prompted this; 15 or 30 un-noted items is most of a pack |
| **Empty buckets** | quantity-swapped | the case the owner named — extra buckets crowd out a harvest |
| **Volcanic ash** | quantity-swapped | 25 or 50, and one slot, so this is about not over-taking rather than space |
| **Protection payments** | quantity-swapped | a known count per patch |
| **Saplings** | quantity-swapped | you want exactly as many as there are patches |
| **Seeds** | **Withdraw-All, always** | owner's call. Stock runs out mid-run, a spare costs one slot, and being short costs a patch |

The seed exception is the interesting one and is worth keeping written down: seeds are the item
where **over-withdrawing is nearly free and under-withdrawing costs a patch**, which is the
opposite of the bucket case. Saplings are excluded from the exception because a sapling is one
patch, one item — there is no stack to be generous with.

## 6. Noting

The second half of the proposal, and the half with a correctness trap in it.

The leprechaun's note toggle is a state, not a click-through: turn it on and *everything*
withdrawn is noted until it is turned off. So a run that wants noted protection payments and
un-noted bin fill has to switch it twice, and the plugin knows which is wanted at each moment.

The proposal is to **highlight the note toggle when it is set the wrong way for what is being
fetched next** — on for the notable things, and, critically, **off again before anything that
cannot be noted.**

**A bin refuses noted items.** That is already load-bearing elsewhere: the full-pack step stands
down at a bin so the leprechaun does not note away the crop the bin is waiting for. A highlight
that only ever said "turn noting on" would walk the player straight into a pack of noted
watermelons and a bin that will not take them.

So the highlight is two-directional, and the wrong-direction case is the one to build first
because it is the one that breaks a run.

## 7. Deposit is always All

Settled with the owner, 2026-08-18, and deliberately not symmetrical with §4.

Depositing has no count worth computing: whatever is being put back is being put back. So the
deposit side swaps to **Deposit-All** and nothing else — no ladder, no arithmetic.

Where a few are wanted back — a bucket kept for the ultracompost — that is not a smaller deposit,
it is **a deposit of everything followed by a withdraw step**, which §4 already knows how to
choose the quantity for. One rule instead of two, and the withdraw path is the one that has been
thought about.

## 8. Rapid, which is a harder constraint than it sounds

The owner's requirement: the whole process has to be quick. That rules out one obvious
implementation and is the main thing to get right.

**`RunLoadout.forRun` is tick-cached, and a tick is 600ms.** The cache is keyed on
`(client.getTickCount(), types)` and exists for a good reason — four callers wanted the list every
tick while a bank was open, and each build walks the planner. But a player clicking through
`Withdraw-10` then `Withdraw-5` does both inside one tick, and a swap reading that cache would
still believe fifteen were outstanding on the second click. It would offer `Withdraw-10` again and
take twenty-five.

So the count driving the swap **must not come from the tick-cached list**. The options:

- read the live containers instead — `CarriedItems` updates from `ItemContainerChanged`, which
  fires on the withdrawal itself rather than on the tick, and is already the class the guide uses
  when it needs to know what is in the pack *now*;
- or keep the wanted total from the loadout (which genuinely changes slowly) and subtract what is
  carried live, so only the fast-moving half is read fast.

The second is the better shape: the run's *intent* is tick-stable, the player's *pack* is not, and
they are already separate numbers.

**Where the swap runs is not the problem.** `PostMenuSort` fires on every menu rebuild, so the
entry order is recomputed as fast as the menu is opened. The staleness risk is entirely in the
count, not in the hook.

## 9. Settled details

All four decided with the owner, 2026-08-18. Kept as a section rather than folded away because
each one is a thing a later reader would otherwise re-propose.

**9.1 One setting**, not one per item class. The existing six swaps each carry their own, but six
more would be a lot of switches for what the player experiences as a single behaviour: *take the
number the run actually wants*. Named for that, not for the mechanism.

**9.2 While a run is on, only.** Consistent with `seedBoxLeftClick` and `dropEmptyBuckets`, and
mostly self-answering — outside a run there is no outstanding count to work from, so there is
nothing to swap to.

**9.3 `UNKNOWN` needs no handling, because it cannot happen here.** Checked rather than assumed:
every `UNKNOWN` in `RunLoadout` is gated on `bank.hasBeenSeen()`, so it means *no bank has ever
been read* — which is why a fresh login says "open a bank and this will say whether you have one"
rather than claiming you own no axe. This swap only acts at a bank that is open in front of the
player, and at the leprechaun, whose stock `LeprechaunStore` reads from varbits with no interface
needed. Neither can be `UNKNOWN` at the moment the menu is built. Nothing to code; recorded so the
absence is deliberate rather than an oversight.

**9.4 Withdraw-X is never touched.** The owner's word, and the reasoning is sound: X is the
player's own setting, its value is invisible to the swap's arithmetic until it is rendered, and a
swap that put an unknown quantity under the left click is the exact failure this feature exists to
prevent. It is left exactly where the game puts it.

## 9a. Considered and dropped

Kept short, so the reasoning is not re-derived later.

- **Overshooting** — taking 5 for a wanted 4. Rejected: a few extra clicks are fine, a spare item
  is not, and dropping it is what makes §4's invariant hold in every case.
- **A click-count floor**, below which the swap stands down. Went with the overshoot rule; four
  clicks for four buckets is the accepted cost.
- **A setting per item class.** One setting instead (§9.1).
- **Using Withdraw-X when the player's X happens to match.** Rejected outright (§9.4).

## 9b. Proposed, unsettled: swapping to Examine once the count is met

Owner's suggestion, 2026-08-18, offered as an experiment and assessed by them as *"kind of hacky,
but it might work well for the game play loop"* — which is the right frame and the reason this is
written up rather than argued down. The objections below are what to watch for while trying it,
not a case against trying it. Whether a thing feels good over a hundred farm runs is not
answerable from the code, and the owner is the one doing the runs.

**The idea.** Once the run has enough of an item, put `Examine` under the left click instead of a
withdraw option, so a lazy click cannot take more.

**Why it is attractive.** It closes the loop on the motivating case. The quantity swap makes taking
the right number easy; this makes taking the wrong number hard, and the wrong number — eleven
spare buckets — is the whole reason the feature exists.

**Three things to watch, in the order they would be felt.**

1. **It does not prevent anything, it charges a click.** `Examine` under the left button still
   leaves every withdraw option one right-click away, which the compliance line requires. So a
   player who genuinely wants more buckets gets friction rather than a wall — and a player who
   does not want more was not going to click anyway once the highlight went out.
2. **Being wrong costs more here than anywhere else.** A mis-sized quantity swap takes five instead
   of four. A wrong "you have enough" blocks the click entirely, on an item the run may have
   mis-counted — and the plugin's own model of *enough* is exactly what the herb-withdraw report
   is currently open about. This is the "plugin refusing to let you play" shape the loadout is
   otherwise careful to avoid (see `RunLoadout.toolsLeftToWithdraw` on why a seed is not treated
   as unarguable).
3. **It is a different mechanic from the rest of this document.** Every other swap here, and the
   six in `GuideMenuSwap`, put a *useful* option under the click. This one puts a deliberate no-op
   there to stop an action. `design-principles.md` records the rulings as permitting reordering and
   forbidding *conditional menu entry removing*; this removes nothing, but blocking-by-swap is
   nearer that boundary than anything else in the plugin. Worth a look at the rejected-features
   page before building, which is the house rule for anything sitting near a category edge.

**The cheaper alternative, which may get most of the benefit.** Once the count is met, **stop
swapping** — let the game's own order come back. The assistance disappearing *is* the signal, and
it needs no new mechanic:

- the bank highlight already goes out on the item the moment it is satisfied — *"withdrawing an
  item un-highlights it the same tick"*, which is same-tick rather than same-token behaviour;
- so the player sees an unhighlighted row whose left click is whatever it normally is, which reads
  as "done with this" without anything having been taken away.

That gets the "stop me over-withdrawing" benefit through absence rather than obstruction, and has
no boundary question attached to it at all.

**If it is tried, the narrow version is the one to try first**: only for items where the run can be
*certain* the count is met — a count read live from containers rather than from a tick-cached plan
(§8) — and never for seeds, which are Withdraw-All by design (§5). That keeps the blast radius to
the case that motivated it (buckets, ash, bin fill) and leaves the items where being wrong is
expensive alone.

**How to tell whether it worked.** Not by whether it feels tidy, but by whether the pack arrives at
the first bin with room for a harvest in it. That is the outcome the whole document exists for, and
it is observable in a single run.

## 10. Must not regress

- **Reordered, never renamed, never removed.** `SeedCapture` recognises Fill and Empty by their
  option *strings*, and the same is true of anything reading withdraw options. Moving entries is
  the whole mechanism; rewriting their text would break the accounting that decides the swap.
- **Every other quantity stays one right-click away**, and switching the setting off restores the
  game's own order exactly.
- **`Withdraw-X` is never moved, chosen, or read as a target** (§9.4). It is the one entry whose
  quantity the swap cannot know before the click lands.
- **Seeds stay Withdraw-All** (§5).
- **The note toggle must end up OFF before a bin fill.** §6. This is the one that can strand a run
  rather than merely annoy.
- **The click never takes more than the panel says.** Guaranteed by the no-overshoot rule (§4)
  rather than merely intended, which is the reason that rule is worth its extra clicks.
- **The count must be live, not tick-cached** (§8). A stale count does not merely mis-swap, it
  over-withdraws — the failure this feature exists to prevent.
- **Runs last of every `PostMenuSort` subscriber.** `GuideMenuSwap` already takes
  `@Subscribe(priority = -1f)` for this reason: core's own swapper runs first, and anything
  reordering after it wins.
