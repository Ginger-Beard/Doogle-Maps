# TODO

Open work only. Everything learned and every closed post-mortem is in `NOTES.md`; the
in-client checks are in `TESTING.md`.

## Blocked on testing in the client

These are written and unverified. `TESTING.md` has the full plan, with a fail signature for
each so a wrong result points somewhere.

- **Guided mode** — the whole per-patch loop. Most recently fixed but unseen: patch tiles now
  filled so an emptied patch is visible, weeds asking to be raked, the leprechaun's compost
  slot no longer staying lit, bucket counts asking for what is actually missing, and the
  return-empty-buckets step.
- **The step is on the game screen now, not in the sidebar.** A draggable Quest Helper-style
  panel, top-left by default. The sidebar no longer repeats it.
- **Noting a full inventory highlights the crop again.** The reported bug: only the leprechaun
  lit up. Every step *at* the leprechaun searched his store for its item, and a watermelon has
  no slot in it, so nothing was drawn.
- **The leprechaun's store is read** (all varbits, no interface needed), so the loadout now says
  which tools and which compost tier he actually has — rather than asserting he has everything.
- **The bank loadout** — including the Farmer's outfit line and the herb sack variants, neither
  of which has been seen on screen.
- **The `Run planned:` diagnostic.** One INFO line prints every input to the bank-leg decision.
  If a run still starts at a bank while standing on work, that line settles which of the four
  possible causes it is.
- **The herb sack chat wording.** `HarvestLog` logs any sack/basket/box message during a
  harvest. Paste it and the experience-based estimate becomes an exact count.
- **A `jstack` if the freeze recurs** — see the deadlock section below.

## Actionable without the client

- **Per-file licence headers.** 8 of 93 files have one, and all 8 are generated files citing
  Abex. RuneLite core puts a BSD header on every file. Not confirmed to be a Hub *requirement*,
  and 85 new headers would bury real changes in a diff, so it wants doing right after a commit
  rather than before one.
- **Dead code sweep**, deliberately deferred until the features above are verified — several
  of the unused-looking methods are scaffolding for things half-built. See the list in
  `NOTES.md`. The two unused *imports* can go any time.
- **The deprecated-API note on every build.** Four files still use the old
  `net.runelite.api.ItemID`, `Varbits` and `widgets.ComponentID` rather than the `gameval`
  replacements: `CompostCapture`, `ProtectionCapture`, `CompostTier` and `FarmingBonusStore`.
  (`PatchInteractionTracker`'s was fixed in passing, and the unchecked note from
  `DoogleMapsPluginTest` is suppressed with a reason.)
  **Not a package swap** — the constant *names* differ between the two, which is the trap:
  `net.runelite.api.ItemID.COMPOST` is `gameval.ItemID.BUCKET_COMPOST`, and a rename that
  compiles can still be the wrong item. The underlying numbers do not change, so the right way
  is one file at a time, checking each constant's value against the old one, and it wants doing
  on its own rather than mixed into a diff you are about to test in-game. Also worth pairing
  with `CompostCapture`'s three other deprecations — `Client.getPlane`, `Client.getScene` and
  `WorldPoint.fromScene` — which are behavioural rather than cosmetic and want reading properly.
- **Guided mode: the stop is not sequenced.** Patches at a stop are ordered nearest-first,
  which is arbitrary where a position was never learned and only the region centre is known.
- **No arrow or navigation line.** Quest Helper has both, toggleable.
- **No menu swap for the seed box.** The spec asks for Empty as left-click. It is also the
  first thing that would modify input rather than describe it, so it wants deciding on rather
  than assuming.

## Decisions waiting on you

- **The rename apostrophe** — *Farmers Almanac*, *Farmer's Almanac* or *Farmers' Almanac*. It
  lands in the descriptor, the repo name and the Hub listing, and both real-world publications
  disagree, so there is no convention to inherit. Blocks the whole rename. Details below.

## Per-feature toggles for a run — wants thinking about, not just adding

Guided mode is close to all-or-nothing today. There is one switch for the whole thing
(`guidedMode`), one for the bank highlight (`highlightBankItems`), and a *style* setting whose
`NONE` doubles as an off switch for the world highlight. That is three switches for six things,
and the one people will actually want — "keep the text, lose the outlines" — is not among them.

**What should be individually switchable:**

- On-screen step text
- Shortest Path routing
- Highlighting, split: inventory items · the leprechaun · crops · objects in the world
- Bank filtering

**Why this is not simply five new booleans.** Bank filtering is the one that goes wrong. A
*highlight* that is wrong is ignorable — you see a marked item, you disagree, you move on. A
**filter** that is wrong actively hides things you need, and you cannot see what is missing
because that is what filtering does. So the interaction to think through is what happens when
someone's seed selection does not match what they are about to plant: with highlighting they
notice, with filtering they arrive short and have no idea why. That is the same reasoning that
has kept the bank tag tab deferred, and it applies to any toggle that turns highlighting *into*
filtering rather than alongside it.

**Also worth settling before building:** whether these are one section of independent checkboxes
or a hierarchy under `guidedMode`. Independent is simpler to implement and easy to get into a
confusing state — text off, highlighting off, guided mode nominally on. A hierarchy needs a
decision about what the parent switch means when its children disagree.

## Watering — worth deciding whether it is worth modelling at all

Raised from play: *"I never water. Do people?"* That is the actual question, and it is worth
settling before any of this is built, because watering touches the yield model, the disease
model, the timers and guided mode — and if nobody does it, all of that is cost for nothing.

**What is not in doubt** is that one kind of watering is already unavoidable and already
half-modelled: a tree, fruit tree or bush **sapling** is a seed in a plant pot of soil that has
to be watered *once* to become a sapling. The plugin already knows about potting — the seed
list greys an unpotted tree seed with a "needs potting" tooltip — so the watering can belongs in
that sentence too. That part is a bank-side chore, not a farm-run one, and it is cheap.

**The expensive kind is watering a growing patch**, and that is what needs deciding:

- **What it actually buys.** The mechanic to confirm is that watering a growing allotment, hops
  or bush patch prevents disease for that growth stage, and that it does nothing for growth
  speed. If it is per-*stage*, protecting a crop fully means being there at every stage — which
  is the objection: farm runs are a thing you do twice a day precisely because you are not
  standing over the patch, and this turns it into a full-time activity. Whether that is a bad
  thing is a separate question; there is an argument for a bank-to-patch AFK loop, and this
  plugin would be well placed to guide one. It is just not the same feature as a farm run.
- **What it would do to the numbers.** `DiseaseRisk` currently discounts yield by a per-crop
  rate that assumes nobody waters. Someone who does water would be quietly under-promised, and
  the harvest log would keep scoring them as beating prediction with no idea why. That is the
  half of this that is *already* wrong for waterers rather than merely unbuilt.
- **The can, which changes the arithmetic.** An ordinary watering can holds 8 doses and
  **Gricoller's can** (Tithe Farm) holds far more — the reward page says 1,000. Eight doses is
  one patch area and a return trip; a thousand is not a constraint at all. So "is watering
  practical" has two different answers depending on a reward most accounts do not have, and any
  advice that ignores which can you own would be wrong for one group or the other.
  The leprechaun's watering-can varbit is already being read (`FarmingTool.WATERING_CAN`), but
  whether that number is doses or cans is unknown — see the note in that file.

**Suggested order if it is taken up**: confirm the mechanic; make `DiseaseRisk` aware that a
watered stage is protected; only then consider guiding it. The first two are worth doing even if
nobody ever waters on purpose, because they are what stop the estimates being wrong for people
who do.

## Coral and seaweed — tracked, but not runnable

> **Superseded in scope.** The decision is now that *every* patch type should be runnable, not
> just these two — see "Every patch type should be runnable" below for the data gap that covers.
> This section is kept because coral and seaweed carry a problem none of the others do: the dive
> gear and whether Shortest Path can even route there.


Both are in the almanac already: `SEAWEED` (Fossil Island underwater, two patches) and `CORAL`
(two patches, `TORTUGAN_CORAL_FARMER`, growing elkhorn, pillar and umbral coral). Growth
timings, states and yields all decode. What they are not is *runnable* — `RunPanel.RUNNABLE`
lists eight patch types and neither is among them, so they never appear in a run, a loadout or
guided mode.

That was a reasonable default and it is now the wrong one: people do run these.

**What makes them different from every other patch, and why it is not a one-line change:**

- **Getting there is gear, not a teleport.** The underwater area needs diving equipment worn,
  which nothing else in the plugin models. Every other stop is "be in this region"; this one is
  "be in this region *and* be wearing the right two items", and arriving without them is a
  wasted trip of exactly the kind the loadout exists to prevent. The loadout has no concept of
  *required equipment* today — the axe is the nearest thing, and it is an inventory item.
- **Routing may not reach it.** Shortest Path is handed a region and asked for the cheapest way
  there. Whether it models the dive is unknown and needs checking before anything is promised;
  if it does not, the honest answer is to name the destination and leave the travel alone.
- **Coral is recent content and I do not know it.** The patches, the varbits and the produce are
  in the data because they were generated from RuneLite's own tables, so the *tracking* is as
  trustworthy as everything else. What is missing is the play knowledge: what clearing and
  planting one involves, whether a leprechaun is even present, what the run looks like. Worth
  writing down from a real trip before guessing at it.

**Cheapest useful first step**: add both to `RUNNABLE` and see what breaks. Tracking, timers and
yields already work, so the likely gaps are the loadout (no dive gear) and the route (no path).
Both fail visibly rather than silently, which makes this a good thing to try in a session where
you are going anyway.

## Deferred deliberately

- ~~**The bank tag tab**~~ — **built.** The loadout proved right in play, which was the gate. It
  is a *virtual* tag via `TagManager.registerTag`, so membership is asked live rather than saved
  and nothing is left in the player's own tag list. Off by default: the original reasoning — a
  wrong filter hides things you cannot see are missing — is why it is opt-in rather than why it
  is absent.
- **Patch border highlighting** — the tile fill added for empty patches may already have
  answered this; worth looking before building anything.
- **Carrying compost between stops.** Normally pure loss, since every area has its own
  leprechaun with a thousand buckets. The exception is **Trollheim**, where the leprechaun is
  ~15 tiles from the patch. The non-rotting fix is to learn leprechaun positions the way patch
  positions are already learned, then compute the rule. Low priority: one patch, one walk.
- **Crowdsourced yield data**, post-Hub and opt-in. See `NOTES.md`.

## Open data questions, blocked on observations

Not code problems — they need harvests, and `HarvestLog` is already collecting them.

- **Attas on the level-roll crops** (limpwurt, belladonna). Limpwurt computes to ~7.45 against
  a measured ~8; if the gap is attas, harvests split by whether an anima patch was growing
  would show it.
- **Whether cactus really uses harvest lives.** A 2018 newspost gives two points that do not
  fit the standard curve, so the wiki's measured average is used instead.
- **Celastrus**, which has no published constants at all — only "8-10 bark".
- **Pineapple and papaya check-health experience**, where the seed page and the summary table
  disagree by under a point. One clean observation settles either.
- **Limpwurt's per-patch experience** — observed 91 against a wiki-implied 120. The per-patch
  shape is right; the number may not be.


## Picking more than one seed for a patch type — currently three answers, all guesses

Selecting two seeds for one patch type is stored as nothing more than two entries in a flat set.
Three components then interpret that differently, and none of them asks the player what they
meant.

> **Largely resolved.** The three-way disagreement is gone: `SeedAllocation` is now the single
> answer, the guide reads it rather than applying a rule of its own, and `AllocationAgreementTest`
> compares the two so they cannot drift apart again. `GuidePlan.seedFor` has been deleted.
> Allocation is also capped by protection payments, so a mixed selection spills sensibly.
>
> **Still open below**: which *order* the seeds rank in — the ranking is still expected XP, which
> is one motive out of three. And `RunLoadout.addSeeds` still asks for a full patch count per
> selected seed rather than for its allocated share.

| | Rule today | 4 patches, 2 seeds picked |
|---|---|---|
| `RunEstimate` / `SeedAllocation` | rank by expected **XP**, fill until seeds or payments run out, spill to the next | 4 patches of the higher-XP crop |
| Guided mode | ~~its own rule~~ **reads the allocation above** | agrees with the panel |
| `RunLoadout.addSeeds` (bank list) | every selected seed, at a **full patch count each** | "bring 4 of each" — still wrong |

### The loadout one is a plain bug

`int wanted = patches * seed.getSeedsPerPatch()` sits inside the per-seed loop, so each selected
seed is asked for as if it were filling every patch on its own. Independent of anything below —
whatever the run ends up planting, telling someone to bank two runs' worth of seed for a one-run
trip is wrong. Fixable without settling the design question.

### The ranking is an assumption about intent

Ranking by XP treats farming as an XP activity. It is often not. People plant for one of three
reasons and the plugin cannot tell which:

- **Experience** — the only motive the current code models.
- **Grand Exchange value** — a different order, and one that moves with the market.
- **Resources for something else**, which is most of ironman farming and follows neither: herbs
  for potions, watermelons for the compost bin, limpwurts for potions, coconuts to pay for magic
  tree saplings — where the coconuts are worth nothing themselves and unlock the highest-XP
  planting there is.

A crop can be bottom of every ranking and still be the whole point of the run.

### Where it actually gets placed is a per-patch question

And people already do this by hand: **rare seeds go in the disease-free patches** — Weiss,
Trollheim, Hosidius at full favour — because a ranarr that dies is gone, while **cheap plentiful
seeds go in the ordinary patches** where a loss costs nothing and the experience is the same. So
allocation is not per patch *type* at all. It is per patch, and it depends on which patch can be
diseased.

### The trap: every one of these is real, and together they are unusable

Motive, fallback-versus-split, and per-patch placement are each true observations. Solved
separately they become a priority list, a goal setting and a per-patch assignment screen — and
planning a run turns into filling in a form. **That is a worse plugin than one that guesses
slightly wrong**, because the whole point of the run panel is to tick two boxes and go.

So the design rule for this: **no new configuration.** If it cannot be derived, it does not get
built.

### Decided: a settings toggle, herbs and Kourend

Confirmed from play — **break the protected patches out as their own category, behind a settings
toggle**, covering the herb patches and the Kourend ones.

A toggle rather than automatic detection because that is exactly where the evidence runs out, and
`DiseaseRisk` already draws the line in the right place:

- **Trollheim and Weiss** are provable. Reaching either needs the quest that makes it
  disease-free, so having the patch switched on proves the unlock. These could be automatic.
- **Kourend/Hosidius** is disease-free only at full favour, and you can stand in it without
  that — so access proves nothing and the plugin cannot honestly infer it. The player saying so
  is the only reliable source, which is what the toggle is for.

Same reasoning as the Barbarian Farming setting: where a fact is unobservable, ask once rather
than guess every time.

**It has to move the disease maths too, not just the tab.** `DiseaseRisk.DISEASE_FREE_REGIONS`
is what `survivalAcross` averages over, so a toggle that only regrouped the UI would leave the
yield estimate still discounting those patches for a disease that cannot happen.

### The middle ground: make disease-free herbs their own category

Rather than inventing an allocation mechanism, **split the herb patches into two categories** —
ordinary herbs, and the disease-free ones — and let the existing per-category machinery do the
rest. Pick ranarr for the safe category and guam for the risky one, exactly the way seeds are
picked today.

This is the version worth building, because it adds no new *kind* of thing to configure. It is
one more category in a panel that already has twenty-two, and everything downstream already
knows how to handle a category: the seed list, the compost dropdown, the reward table rows, the
loadout counts. Nothing learns a new concept.

**It also fixes a real inaccuracy for free.** `RunEstimate` works on counts per patch type and
takes an *averaged* survival chance, because — in its own words — survival "depends on where the
patches are, and this class only sees how many there are". Split the group and survival becomes
genuinely uniform within each one, so the average stops being a fudge. That is the per-patch
rework from the previous section, obtained by grouping rather than by rebuilding.

**And the compost dropdown starts earning its keep.** Ultracompost on a disease-free patch is
buying only the yield, never the protection, which is a different value proposition — and today
there is no way to say "ultra on the risky herbs, super on the safe ones" because they share one
setting.

**Which patches qualify**, and this is already settled in `DiseaseRisk`:

- **Provable today**: Troll Stronghold (11321) and Weiss (11325). Reaching either requires the
  quest that makes it disease-free, so having the patch switched on proves the unlock.
- **Deliberately excluded**: Harmony, Hosidius, Falador Park and Civitas illa Fortis. Those need a
  *diary*, and you can stand in all four without having done it, so access proves nothing. If they
  are ever to join the group it has to be off the diary varbits — which `FarmingBonusStore`
  already reads for Kandarin and Kourend, so it is plausible rather than speculative.

**The one implementation constraint that matters**: `PatchImplementation` is generated from
RuneLite's sources, so the split cannot be a new enum member — the generator would drop it on the
next regeneration. It has to be a grouping layered over the generated type, which also means
`SeedSelectionStore` and `CompostSelectionStore` key on that group rather than on
`Seed.getPatchType()`.

**Two patches is a small category**, and worth sanity-checking before building: it only pays off
because those two are precisely where the ranarr and torstol go. If it feels thin in play, that is
the signal that this wants the diary patches too rather than that the idea is wrong.

**Still deliberately not building**: ordered priority lists, sort-by-motive helpers, per-seed patch
counts, goal settings. Each was on this list, and the category split makes every one of them
unnecessary — which is the argument for it.

## Every patch type should be runnable

`RunPanel.RUNNABLE` offers eight types and calls the rest "one-offs you visit deliberately". That
is wrong: they all grant experience on some combination of plant, check and harvest, and people
run them. The only genuine exceptions are the two **compost bins**, which have no seed and no
planting experience — they are storage, not a crop.

**What it costs, measured rather than guessed.** Every currently-runnable type has complete XP
data; the gap is entirely in the types that are not yet offered:

| Patch type | Seeds | Missing XP |
|---|---|---|
| Anima (attas, iasor, kronos) | 3 | 3 |
| Coral (elkhorn, pillar, umbral) | 3 | 3 |
| Cactus (cactus, potato cactus) | 2 | 2 |
| Calquat, Celastrus, Crystal tree, Redwood, Spirit tree | 1 each | 5 |
| Belladonna, Grape, Hespori, Mushroom, Seaweed | 1 each | 5 |

**18 seeds needing a wiki lookup each** — planting XP, check XP where the type has a health
check, and harvest XP where it pays per item. That is the whole job; the code already handles any
type generically once the data exists, as adding the hardwoods showed.

**Two things to get right while doing it:**

- **Yield data is a separate gap.** Five runnable types already have no `CropYield` entry — bush,
  flower, fruit tree, hardwood and tree — because yield either is not a chance-to-save roll or is
  not a count at all. Worth confirming per type whether that is correct or merely absent before
  adding twenty more.
- **A grown tree only counts if the run notices it.** Fixed for `isActionable` via `isReady()`,
  and every long-grow type added here inherits that — so it is worth re-checking after the change
  rather than assuming, since these are precisely the types that sit finished-but-unchecked for
  days.

**And the panel gets crowded.** Eight checkboxes in two columns becomes about twenty. That is a
layout question rather than a reason not to do it, but it wants an answer before the change lands
rather than after.

## Stats tab — what else the data can answer

The tab shows lifetime totals and a crop / n / got / avg table with a per-compost tooltip. That
is the validation view: it exists to check the estimates. Everything below is the other reading —
things a player would actually want to know about their own farming.

**What there is to work with**, because it bounds all of this:

- **`HarvestStatsStore`**, in memory and in config. Rolled up per crop *and compost tier*:
  harvests, items, summed prediction, xp, best and worst single patch, items and xp from patches
  left standing, first and last harvest time. Bounded at ~50 crops x 4 tiers, so it is free to
  read as often as you like.
- **`harvests.csv`**, one row per patch: time, patch, crop, level, compost, secateurs, cape,
  attas, lives, predicted, actual, predicted_xp, actual_xp, completed. Richer, unbounded, and not
  currently read back by anything.

### Tier 1 — from the rolled-up store, no new capture

- **What compost is actually worth, measured.** The one worth building first. The store already
  splits every crop by tier, so it can say *"ultracompost gave you 8.4 ranarr a patch against 6.1
  untreated, over 31 patches"* — the player's own numbers, not the wiki's, answering the only
  question anyone asks about compost. **Caveat that shapes it:** most players use one tier
  forever, so for many accounts one column is empty and the comparison cannot be drawn. It has to
  degrade to "you have only ever used ultracompost" rather than to a misleading half-answer.
- **Luck, cumulative.** Actual minus predicted, summed. *"47 herbs ahead of expectation."* Free —
  both halves are already stored, and it is the single most fun number here because it is the
  thing a farmer feels and cannot otherwise check.
- **Patches you walked away from.** `partialItems` is already tracked and deliberately kept out
  of the averages, but never shown. *"You have left 14 patches part-picked"* is real lost yield
  and the only actionable line in this list.
- **Best and worst single patch**, per crop. Stored, unsurfaced.
- **How long you have been at it, and how often.** First and last harvest are stored, so patches
  per week falls out. Cheap, and it makes the tab feel like a record rather than a table.

### Tier 2 — needs reading `harvests.csv` back

- **Runs, reconstructed.** Cluster rows by timestamp gap — anything over ~30 minutes starts a new
  run — and the tab can show *last run*, *best run* and *average run* in patches, items and xp.
  Probably the most satisfying thing on this list, and it needs no new data at all.
- **Yield against farming level**, since every row stores the level it happened at. Shows the
  curve flattening, which is a real effect most players never see.
- **Per-location performance.** Cheap to compute and probably worth *not* showing: disease is
  random and thirty patches is not enough to separate a bad location from bad luck. Listing it
  would invent a pattern.
- **What your gear earned you.** Every row stores secateurs/cape/attas, so the contribution is
  computable — but only for accounts that have farmed both with and without, which is nearly
  nobody. Likely an empty answer dressed as a feature.

**The cost to weigh first:** the CSV is append-only and unbounded. Reading it on every panel
refresh is wrong; it wants reading once on load, or capping, or a rolled-up "runs" store written
alongside the CSV the way `HarvestStatsStore` already is. That decision is the actual work here,
not the arithmetic.

### Tier 3 — needs a small new capture

- **Observed disease and death rate.** The most valuable gap, and the one thing this data
  genuinely cannot answer: a dead patch produces no harvest, so it never appears in the log at
  all. Every rate shown today comes from the published constants. `PatchInteractionTracker`
  already watches the DEAD transition, so counting it per crop and per compost tier is a small
  change — and it would let the Stats tab validate `DiseaseRisk` the way it already validates
  yield. It would also settle whether ultracompost's protection is worth its price on the
  player's own numbers.

### Rates, and why farming needs its own unit

**"XP per hour" is the wrong question for this skill, and getting that right is most of the
design.** Farming is not continuous: a run is ten or fifteen minutes and then the crops grow for
eighty while you do something else. So the two obvious rates are both lies in opposite
directions — XP/hr measured over the run alone is a huge flattering number that describes nothing
you can sustain, and XP/hr measured over elapsed time is a tiny one dominated by sleep.

The units that actually mean something:

- **XP per run.** The natural unit, because it is what a farmer plans around. Falls straight out
  of the run clustering in Tier 2.
- **XP per day.** The honest throughput number for a skill gated by a growth timer rather than by
  your attention. Arguably *the* farming rate, and nobody displays it.
- **XP per patch**, per crop. What makes one seed choice better than another.
- **Active XP/hr** — first to last harvest within a run cluster. Worth showing because it is what
  people mean when they ask, but it has to be labelled *active* rather than left to imply it is
  sustainable.

- **Runs to the next level.** `Experience.getXpForLevel` is in the API and the current farming XP
  is one call away — `SeedInventoryStore` already caches the level from the same place, so this is
  a field alongside it. *"About 4 more herb runs to 85"* is the most useful single line the tab
  could carry, and it costs almost nothing.

### Coin value — yes, and the interesting version is profit

`ItemManager.getItemPrice(int)` is public, cached in memory, and safe to call from the panel. So
value is available per item and therefore per patch, per run and lifetime.

The version worth building is not "your harvest was worth 240k" but **net profit**: harvest value
minus the seed, the compost and the protection payment. That is the number that actually decides
whether snapdragon beats ranarr this month, and it moves with the market in a way no guide can
keep up with.

Three caveats that belong in the display rather than in a footnote:

- **It is today's price applied to old harvests.** We never recorded the price at the time, and
  historical prices are not available offline. So it is "what that would be worth now", which is
  the right thing for deciding what to plant next and the wrong thing for a lifetime earnings
  claim.
- **Costs are notional for an ironman**, who did not buy the seed and made the compost. Profit
  for them is really "value produced". Same arithmetic, different word — and the word matters.
- **Still not a purchase suggestion.** Describing what a crop is worth does not break the stated
  compliance line, which is about never telling anyone to go and buy something. Worth keeping
  clearly on that side of the line as this grows: a "you should plant X" recommendation built on
  prices would cross it.

### Decisions before building

- ~~**Coin value: yes or no?**~~ **Yes** — speced above. What still wants deciding is whether the
  headline is value or *profit*, since profit needs seed and compost costs that an ironman never
  paid.
- **Old rows are not trustworthy.** Everything logged before the attribution fixes was collected
  by the broken version, and `TESTING.md` still tells you to clear the history. Any headline stat
  built on it inherits that. Worth considering whether the store should record a "trustworthy
  from" timestamp rather than relying on someone having cleared it.

## Backlog

Not being worked on, and not blocked either — parked because the value stopped justifying the
work. Kept because the reasoning is worth having if that changes.

- **Geomancy bulk refresh (§4b).** Fully decoded, nothing left to research: `NOTES.md` has the
  whole rendering, and the probe that produced it is switched off.
  Parked because what it can actually deliver turned out to be narrower than it looked. The
  **growth stage is not readable in bulk** — it exists only in the hover tooltip — so a cast can
  never produce a timer for a patch the plugin has not seen, which was the original appeal. What
  is left is filling in dead / diseased / empty / what is growing across the map in one cast:
  useful, but the ordinary walk-past capture already gets there, and it needs a Lunar spellbook
  and 65 Magic to be worth anything at all.
  If it is picked up, the decode is done and it is an afternoon's work rather than a research
  problem.

## DEADLOCK on teleport during a farm run, 2026-08-04 ~11:17 — open, highest priority

Confirmed by the user as a **freeze requiring a kill**, not a crash. That matches the log
exactly: it ends mid-session with no exception and no `hs_err_pid`, which is what a hung JVM
looks like. Reproduced once, on the build *before* the disease work, so `survivalAcross` is not
the cause.

**Concrete suspect, unproven.** `ReadyInfoBox.getText()` calls `panel.projectAvailable()`, and
RuneLite renders infoboxes on the **client thread**. So two threads walk the same locks:

- client thread, infobox render: panel -> `AvailabilityProfile` -> `PatchStateStore`
- EDT, panel refresh: `RunPlanner` -> `AvailabilityProfile` -> `PatchStateStore`

Both take Availability before State, which is consistent. The inversion, if there is one, will
be some path that takes them the other way round — and a teleport is when everything fires at
once, which is why it showed up there.

**One cause found and fixed, and it may be a contributor.** `markServiced` called `retarget()`
on **every serviced patch**, not just on a completed stop. Falador's allotments, flower, herb
and compost bin are all one stop, so finishing a herb patch fired four or five full pathfinding
requests while the player stood still — each one a cross-thread post through
`clientThread.invokeLater` into Shortest Path's event bus. That is exactly the reported
"takes a second to pop up the next location", and the re-navigation seen while standing among
the Falador patches. Now retargets only when the stop completes; the answer cannot improve
while you are still standing in the region being routed to.

Whether that also caused the freeze is unproven, but repeated cross-thread posting under
contention is the right shape for it, and it was happening several times a minute during a run.

**Get the evidence anyway.** Next freeze, before killing it:

    jps -l                 # find the RuneLite pid
    jstack <pid> > C:\hang.txt

`jstack` names both threads and both monitors outright. That turns this from a reading exercise
into a one-line fix.

**The cross-store audit is now done properly.** The earlier scan was wrong twice over — broken
brace counting, and it only looked at *direct* calls inside a synchronized method, so a
synchronized method calling a private helper that calls a store was invisible. That is exactly
how it missed `RunPlanner.start`. The scanner now builds a per-class call graph and follows it
transitively. (It also produced a confident "0 findings" run that was pure artefact: the shell
had a stale working directory and it scanned nothing. A zero from a scanner is worth one check
that it looked at any files at all.)

**Result: there is no lock-order inversion.** Every edge runs one way —

    RunPlanner -> AvailabilityProfile -> PatchStateStore
              \-> GrowthTimer, SeedInventoryStore, SeedSelectionStore   (leaves)

The leaves hold no references to other stores, so nothing can take them in the opposite order,
and listeners are still never fired under a lock (re-verified). A two-store deadlock in this
plugin's own code is therefore ruled out.

**One real thing was found and fixed.** `start()` and `stop()` were `synchronized` methods that
called the router *inside* the lock — `router.setTargets` / `router.clear`, which post through
`clientThread.invokeLater` into Shortest Path's event bus, and `EventBus.post` delivers
synchronously. So another plugin's subscriber code could run while this planner's monitor was
held. `markServiced` and `leaveBank` already did the right thing; these two did not. Both now
mutate under the lock and call the router outside it, and the audit confirms
`ShortestPathIntegration` no longer appears under any lock.

That is not proof it caused the freeze — from the EDT `invokeLater` queues rather than running
inline — but it was the one place the code did the thing the freeze looks like. No
`invokeAndWait`, `join`, `CountDownLatch` or `sleep` exists anywhere in `src/main`, so there is
no blocking cross-thread wait to pair it with either.

## Original write-up, kept for the log evidence

`client.log` ends cleanly at 11:17:49 on an unrelated GotR line. **No exception, no stack
trace, no OOM, and no `hs_err_pid` file.** A Java-level fault in this plugin would have logged
a trace, so there is nothing here implicating it — that pattern points at a native fault (GPU
or driver) or the JVM being killed. Not proven either way, and not reproducible on demand.

If it recurs, what would actually distinguish the cases:
- an `hs_err_pid*.log` in the working directory means a native crash, nothing to do with us
- a frozen client with no CPU use means a deadlock, and a `jstack` while it is hung would name
  the two locks
- an exception in `client.log` means it is ours

**Lock-ordering note, since a freeze is the plausible way this plugin could do it.** Two locks
are taken in sequence in several places and the order must stay `RunPlanner -> Availability ->
PatchStateStore`. `PatchStateStore` already documents why `fireChanged` must never run under
its own lock. `RunPlanner.survivalAcross` returns a lambda that is invoked later, on the EDT,
and takes Availability then State — consistent with that order, but it is the kind of thing to
re-check rather than assume.

## Rename: Farmers Almanac, with Doogle Maps as the run section

The plugin becomes **Farmers Almanac**. "Doogle Maps" survives as the heading of the run
section, which is where it always fitted best — it is the routing half, and the joke is a
mapping joke.

- **Spelling to confirm before anything is renamed**: "Farmers Almanac", "Farmer's Almanac" or
  "Farmers' Almanac". Worth settling first because it ends up in the descriptor, the repo name
  and the Hub listing, and changing it afterwards is far more annoying than choosing now.
  There is no convention to inherit either: the real-world publications are *Farmers' Almanac*
  and *The Old Farmer's Almanac*, so both apostrophe placements have a precedent and neither
  is the obvious one.
- ~~The **Start run** section becomes **collapsible**, headed "Doogle Maps".~~ **Superseded and
  done better** — it is a top-level tab now, so the heading is the tab and the overview gets
  the whole panel back rather than just most of it. See the section above.
- Sitta mango's credit still stands and does not move — the name is still in use, just for the
  section rather than the whole plugin. `ATTRIBUTION.md` and the README both need rewording
  rather than removing.

**What the rename actually touches**, in rough order of risk:

1. `DoogleMapsConfig.GROUP = "dooglemaps"`. **Leave it alone**, or every stored patch state,
   seed cache, availability toggle and harvest statistic is orphaned. It is an internal key
   nobody ever sees; renaming it buys nothing and costs a migration. If it ever must change, it
   needs a one-off copy-across on first run, not a rename.
   - The **harvest statistics** are the part that makes this more than an inconvenience.
     Patch states, seed counts and learned locations all come back by playing — that is the
     whole basis of the profile reset. Nothing rebuilds the harvest history, so orphaning it
     is the one genuinely irreversible thing a rename could do.
2. `@PluginDescriptor` name, description and tags — the only part users actually read, and the
   only part that has to change for the rename to be real.
3. The `com.dooglemaps` package and the class prefixes. Cosmetic, wide, and best done in one
   mechanical pass rather than drifting half-renamed.
4. The repo directory, which is referenced by `run-client.sh` and the WSL/Windows symlink, so
   renaming it means re-pointing both.

Doing 1 and 2 gets the rename; 3 and 4 are tidying and can wait.
