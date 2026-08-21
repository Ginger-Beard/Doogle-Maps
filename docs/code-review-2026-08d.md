# Code review, 2026-08-20 (round d)

Scope: the full uncommitted changeset against `244bed4` — ~1,700 main-source lines across 25
files, plus the new `WithdrawQuantity`, `RunPresetStore` and their tests. Rounds a–c covered the
older strata of this tree and have been absorbed and deleted; this round weights the newest
material (withdraw-quantity swap, run presets, the compost phase gate, the run-boundary
extraction, seed-box write coalescing) and re-checks the cross-cutting hazards the repo's notes
flag (EventBus ordering, EDT discipline, lock traffic).

Suite state at review time: 1213 tests, all passing.

---

## HIGH

### 1. The withdraw-amount swap promotes **Deposit-N**, sized by the run's withdraw need

`GuideMenuSwap.swapWithdrawAmount` (line 265) scans every menu entry for a ladder amount and
deliberately ignores the verb — `WithdrawQuantity.amountNamed` reads "the number after the first
dash, whatever verb precedes it", which is what makes the leprechaun's *Remove-5* work. But the
bank's **inventory side** builds its menus from the same convention: hovering an item while the
bank is open offers `Deposit-1 / Deposit-5 / Deposit-10 / Deposit-X / Deposit-All`. Those parse
as amounts, `itemIdOf` resolves the item, and `stillWantedNow` is non-zero for **exactly the
items the run is mid-collecting** — so the swap promotes, say, `Deposit-10` to the left click on
the very buckets the run just told the player to withdraw.

Concrete replay: run wants 15 buckets, player has withdrawn 4. Row still says WITHDRAW, so
`stillWantedNow` = 11. Player hovers the 4 buckets in the pack → menu is Deposit-N →
`choose({1,5,10}, 11)` = 10 → `Deposit-10` becomes the left click. One misclick puts the
supplies back, and the click was re-aimed *by the feature that exists to make clicks safe*. The
setting defaults on, so every guided-mode user at a bank is exposed.

The spec saw this shape and settled it the other way — §7: "the deposit side swaps to
**Deposit-All** and nothing else — no ladder, no arithmetic" — but no deposit handling was ever
implemented, so deposit menus fall through into the withdraw ladder instead. The class comment
on `amountNamed` ("the caller has already established it is a withdraw entry") is not true of
the only caller.

**Fix:** gate the scan on the entry's verb — accept only options beginning `Withdraw` /
`Remove` (case-insensitive), the two confirmed collection verbs — and pin it with a
deposit-menu test in `WithdrawAmountSwapTest` (the suite currently has none). Whether to then
implement §7's Deposit-All swap is a separate, smaller decision; the misfire is the part that
cannot wait.

---

## MEDIUM

### 2. Unidentified entries weaken the swap's "one item, or nothing" guard

Same method, lines 278–281: the first parsed entry with no resolvable item (`itemIdOf` = −1)
sets `itemId = -1`, and the *next* entry with a real id silently replaces it via the
`if (itemId < 0)` arm — so a menu mixing one unidentifiable amount-bearing entry with
identifiable ones passes the same-item check, and the stray entry's amount is attributed to the
identified item via `offered`. The guard's own javadoc ("if two ever do not name the same item,
the menu is left exactly as the game built it") promises stricter than the code delivers.
Low practical exposure today, but the guard exists for menus this code has not seen — make −1 a
bail (`return`) rather than a wildcard, or track "saw an unidentified entry" and stand down.

### 3. `ReadyInfoBox.toWithdraw` still walks the planner from the EDT

`ReadyInfoBox.update()` is called from Swing-thread store listeners, and `toWithdraw()` calls
`loadout.forRun(planner.coveredTypes())` — RunLoadout's monitor plus a full planner walk on a
cache miss. This changeset just removed the last EDT query of the planner's monitor from
`RunPanel` (`RunSnapshot.fillableBins`, with a note calling that traffic out by name), while the
infobox keeps doing the same thing one class over. The new `isActive()` gate narrows it to
run-time only, which helps; the consistent end-state is the withdraw count riding the snapshot
like `fillableBins` now does. Pre-existing rather than introduced here — flagged because the
changeset's own direction points at it.

---

## LOW

### 4. Dangling double-javadoc blocks (three)

Adjacent `/** … */ /** … */` pairs where the first block is silently dropped by javadoc:

- `RunLoadout.stillWantedNow` — the long design doc (caching, eligibility) sits orphaned above
  the short "As below" doc on the one-arg overload (line ~295); the real doc should sit on the
  two-arg method it describes.
- `GuideTracker.plural` (line ~930) — the old sibilant-rule doc stacked on the new "Moved to
  GuidePlan.plural" doc.
- `RunPanel.shownPresets` (line 609) — `refreshPresets`' method doc lands on the field declared
  between them.

Cosmetic, but this codebase's comments are load-bearing; a dropped block is a lost argument.

### 5. `RunPresetStore.serialized()` reads the map outside the monitor

`save()` is (correctly) called outside the lock, and `serialized()` copies `presets`
unsynchronized. Writers are EDT-only today so it cannot race in practice, but every other store
that follows the write-outside-the-lock rule snapshots its state *inside* the mutating block or
synchronizes `serialized()`. One `synchronized` keyword buys consistency with the pattern the
class is imitating.

---

## What the changeset gets right (kept short, but real)

- **The run boundary extraction** (`GuideTracker.runEnded()` vs `reset()`) turns a
  twice-made mistake into a named method — the right fix for a class this size.
- **Seed-box write coalescing** (`SeedInventoryStore.flushSeedBoxWrites`) bounds a chat burst at
  one config write and one rebuild per tick, with the defer flag cleared in `finally` and both
  callbacks fired outside the monitor. Careful work, well covered by `SeedBoxDriftTest`.
- **The compost phase gate** (`RunPlanner.count`): a ready bank-fed bin now counts as fillable
  but contributes no fill items until empty — the sequencing bug from play, fixed at the level
  the redesign spec (§9) says it belongs.
- **Ledgered allocation** (`RunLoadout.addSeeds`/`addPayments` `unspent` maps) closes the
  two-groups-one-stock double-count, and the payments loop reproducing the seeds loop's
  allocation order is stated as the invariant it is.
- **The tests document their own history** — `aReadyBinBanksNoFillUntilItIsEmptied` carrying
  both dead ends' stories is the pattern at its best.

## Coverage gaps worth closing

- `WithdrawAmountSwapTest`: no deposit-side menu, no mixed-item menu (findings 1 and 2 are both
  one test away from being pinned).
- `GuideMenuSwapTest`/`WithdrawAmountSwapTest`: nothing asserts the swap stands down on menus
  with no ladder verb at all (shop `Buy 5`-style spacing currently passes only because shops
  use spaces, not dashes — an accident worth converting into an assertion on `amountNamed`).

## Resolution, 2026-08-20 (same day)

All five findings fixed; suite green at 1217 tests.

- **1 (deposit misfire):** `isCollectionOption` whitelists Withdraw/Remove ahead of the amount
  parse; pinned by `aDepositMenuIsNeverSized`. §7's Deposit-All swap remains unimplemented on
  purpose — the misfire was the urgent half.
- **2 (unidentifiable entries):** any candidate with neither id nor name stands the whole swap
  down; pinned by `anUnidentifiableEntryStandsTheWholeSwapDown`.
- **3 (EDT lock traffic):** the withdraw list now rides `GuideStatus.toWithdraw`, built by
  `GuideTracker.withdrawLines()` on the tick where the loadout build is already paid for;
  `ReadyInfoBox` lost its `RunLoadout` dependency entirely.
- **4 (dangling javadoc):** all three folded.
- **5 (`serialized()`):** synchronized, with a note that it is pattern-consistency rather than a
  live race.

And the reason round d existed at all bore fruit sideways: the owner reported the swap "never
worked at the lep anyways", and the review's finding 2 was the trailhead — identification fails
there because *neither the entry nor the widget* carries an item id at his store (the earlier
widget fix held only in its mock). The swap now falls back to the item's printed name in the
entry target, sized via `RunLoadout.stillWantedNow(String)`; a debug stand-down line
(`GuideMenuSwap.logStandDown`) records the guard that fired, so a third round of this cannot
cost another play session of guessing.

## Next steps, in order (as originally written)

1. Verb-gate `swapWithdrawAmount` + deposit-menu regression test (finding 1).
2. Tighten the unidentified-entry bail (finding 2) in the same edit.
3. Fold the three dangling javadocs into their methods (finding 4) — five minutes.
4. Decide whether §7's Deposit-All swap is still wanted now that the misfire is closed.
5. When the snapshot grows its next field, move the infobox withdraw count onto it (finding 3).
