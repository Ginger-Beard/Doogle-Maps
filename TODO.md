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
- **Teleports are a list you own** — `Guided run > Teleport items`, comma separated by item name,
  defaulting to the generated table. Matched by name against the bank, which is what lets a list of
  words become item ids without an index of every item in the game.
- **The seed vault is highlighted too**, not just the bank. Both surfaces were bank-only, while
  the planner has always been willing to route to the vault. Filtering there is not possible via
  Bank Tags and is not planned — it would mean hiding widgets ourselves.
- **Bank filtering is now on by default**, having proved undiscoverable while off. See the
  `BankFilter` javadoc for what changed and what did not.
- **The bank layout is a map you draw** — `Guided run > Bank layout map`, one character per slot.
  Regions rather than a flow, so a group's position does not move with the run's size.
- **Tree seeds are found in the bank.** The loadout measured ownership with `getOwnedPlantable`
  (saplings only) and the highlight/filter sets held only the sapling id, so seeds in the bank read
  as MISSING, were never marked, and were actively hidden by the filter. Both now use either form.
- **The bank filter no longer kills the client on close.** `BankFilter.onWidgetClosed` called
  Bank Tags' `closeBankTag` synchronously, and `WidgetClosed` is posted from inside the client's
  own script execution — so it started a script within a script, tripped
  `AssertionError: scripts are not reentrant`, and killed the `Client` thread. Now deferred with
  `clientThread.invokeLater`. Reproducible before the fix: open a bank with filtering on, close it.
- **A `jstack` if the freeze recurs** — see the deadlock section below. **One freeze was captured
  on 2026-08-05 and it was _not_ this deadlock**: the dump had no `Client` thread and no plugin
  frames at all, because the thread had been killed by the reentrancy assertion above rather than
  blocked on a lock. The teleport deadlock remains unobserved, and that dump is not evidence about
  it. See `NOTES.md`.
- **Farming contracts**, whole. The contract tab and its single derived seed, the guild patch
  moving out of the herb group, the contract being planted first, and the hand-in / take-a-new
  steps at Guildmaster Jane. The three dialogue lines and the completion message are matched from
  the client sources rather than from play, so a single wording difference makes the whole capture
  silent — and silent looks exactly like "no contract". See the section below and `TESTING.md`.

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
> is one motive out of three. The loadout's full-patch-count-per-seed bug is **fixed**: it reads
> the allocation too, so the bank list, the guide and the estimate all divide the patches the same
> way. It allocates on either seed form rather than the plantable one, so an unpotted tree seed is
> still something to take.

| | Rule today | 4 patches, 2 seeds picked |
|---|---|---|
| `RunEstimate` / `SeedAllocation` | rank by expected **XP**, fill until seeds or payments run out, spill to the next | 4 patches of the higher-XP crop |
| Guided mode | ~~its own rule~~ **reads the allocation above** | agrees with the panel |
| `RunLoadout.addSeeds` (bank list) | ~~a full patch count each~~ **reads the allocation above** | agrees with the panel and the guide |

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

## Farmer contracts in guided runs — built, unverified in the client

Guildmaster Jane's contract asks for one crop, and the reward — seed packs, and the Farming Guild
reputation that unlocks the tiers — makes it the single highest-value thing in a run. The plugin
now knows about it, which closes both of the failures this started as: it never told you to plant
one, and it would happily fill the very patch the contract needed with something else.

### What the client sources settled, and what they overturned

Both questions this was blocked on are answered from `FarmingContractManager` and
`TimeTrackingPlugin` at 1.12.35. One came out as hoped; the other did not, and it is the reason
this is more machinery than the plan called for.

- **What completes a contract.** Nothing is written to config but the crop's item id, so it has to
  be derived — and `handleContractState` shows exactly how core derives it: a guild patch of the
  contract's implementation, growing the contract produce, past its done estimate. That is
  `PatchProjection.isReady()`, which we already had. Health-check types are special-cased there
  and dead herbs read as `ANYHERB`, both of which the derivation here allows for.
- **Whether the config key clears on hand-in — and this is the one that overturned the plan.**
  It clears *earlier* than that. `TimeTrackingPlugin.onChatMessage` calls `setContract(null)` on
  **"You've completed a Farming Guild Contract. You should return to Guildmaster Jane."**, which
  the game sends when the crop finishes **growing**. So an absent key means either "nothing
  assigned" or "your reward is sitting unclaimed" — and the second is precisely the state the
  guide most needs to speak up in. The two-state machine the plan assumed does not exist.

**So the fallback capture was built after all**, though narrowed to what the config genuinely
cannot say. `ContractCapture` watches the same three lines Time Tracking does and writes them
under this plugin's own keys; `ContractState` then consults the two sources by what each actually
knows — Time Tracking's key first for *which crop is assigned*, ours alone for *whether it is grown
and unclaimed*. The two failure modes turn out to be complementary, which is why both are kept: a
contract that ripens while you are logged in wipes their key and only our capture sees it, and one
that ripens while you are logged out sends no message at all but leaves their key intact for the
patch-state derivation to work from.

### What is built

- **The contract is its own planting group, and the patch moves.** `PlantingGroup` carries a
  `Scope` enum — `ALL`, `PROTECTED`, `CONTRACT` — rather than the second boolean, which would have
  allowed a protected-and-contract group that means nothing. While a contract is assigned, the
  guild patches of its type leave their ordinary group and join `HERB#contract` (or
  `BUSH#contract`, and so on). The tab, the run line and the reservation all fall out of machinery
  that already existed: **the estimate cannot promise a snapdragon in a patch that is spoken for,
  because the ordinary group has stopped counting it.** No reservation logic anywhere.
- **The seed is derived, not stored.** `SeedSelectionStore.getSelectedFor` answers a contract group
  with the one crop asked for, and `toggle` refuses it. Nothing the player never chose is
  persisted, and it cannot go stale when the contract changes.
- **Compost and protection keep their dropdowns**, remembered per assigned patch type, and a
  brand-new `HERB#contract` inherits whatever herbs are treated with rather than starting at NONE.
  The type-wide write-through is suppressed for a contract, the same as for a protected split.
- **The contract patch is serviced first at the guild**, whatever the distance sort says. This is
  the one place in a run where order is load-bearing.
- **The guide closes the loop**: `HAND_IN_CONTRACT` and `TAKE_CONTRACT`, both at Guildmaster Jane
  (NPC 8628, region 4922), appended at the end of the guild stop.
- **It is pinned, and named for the job rather than the crop.** The tab sits first in the strip and
  the run line last in its list, because the contract is not a patch type — it moves from cactus to
  bushes as it is reassigned, and filing it under whichever type it currently wants had the tab and
  the line jumping around each week. The run line reads **Farming Contract**: "Cactus (contract)"
  invites the answer "no, I am not doing cactus today", when the decision is whether to do the
  contract at all. Last also fixes an alignment bug — inline, it landed between `Cactus` and
  `Cactus (H/O)` and split the pair the two-column layout works to keep side by side.
- **The tab icon is Guildmaster Jane's face**, fetched by `tools/fetch_chatheads.py` like the
  gardeners'. She is three NpcID constants for one person and the wiki declares only two of them,
  so the tool groups them, resolves the page from whichever id answers, and writes the sprite under
  all three — which is why the lookup works whichever id the game reports. A face among crop
  sprites needs no corner badge, so `ContractIcon`'s diamond is now only the fallback for a missing
  resource.
- **And the new contract can be planted on the same trip.** Nothing is stored, so the moment a new
  one lands in config its patch moves into the contract group, is pulled to the front, and gets its
  plant step — on the next tick, while you are still standing there. Where that cannot happen —
  the patch is still occupied, or the run was never routed past it — the on-screen panel says so
  rather than going quiet. That note is the only thing in the guide that is information rather than
  an instruction, deliberately: a step nobody can perform would leave the stop reading as unfinished
  for the rest of the run.
- **Time Tracking being switched off is said, not guessed at.** `ContractState.logState()` puts one
  line in the log on load naming which of the three silent causes applies.
- **A toggle, on by default**, in the guided-run section.

### Two things play caught that the tests had not

- **"No seed picked for: cactus"**, with the contract tab above it showing the cactus seed already
  selected. `RunPanel.updateNoSeeds` read the *type-wide* selection, and a contract's seed is never
  in it — the crop is derived from the assignment rather than picked, deliberately, so that nothing
  the player never chose gets persisted. It asks each ticked group what it will plant now, which is
  the question the line always meant.
- **Protection was off on the contract group** even for someone who protects that crop everywhere.
  `ProtectionSelectionStore` had no fallback to the type's answer, where `CompostSelectionStore` has
  had one since the protected-herb split — so a brand-new group started at "no". It now inherits,
  and an explicit no on the contract tab still sticks (which needed the explicit *offs* persisting,
  since absence from the set used to mean both "off" and "never asked"). **Not** made automatic:
  protection costs items, and turning it on for someone is not a decision this plugin makes.

### What is left

- **All of it wants seeing in the client.** See `TESTING.md`. Nothing here has been in front of
  Guildmaster Jane; the three dialogue lines and the completion message are matched from the
  client sources rather than from play, and a single wording difference makes the capture silent.
- **`Produce.getByItemID` is first-wins**, matching core's linear scan, because item ids are not
  unique in that enum — `ANYHERB` carries a guam leaf. That is right for every contract crop, but
  it is an assumption about core's behaviour rather than a documented promise.
- **Both guild allotments move together.** The guild has one patch of every type except allotments,
  and a contract for one moves both — so an allotment contract will suggest the contract crop in
  both. The alternative, picking one of the two, would have a patch changing groups as its state
  changed, which is exactly what `PlantingGroups` must not do while a run is planned around it.
  Worth revisiting only if it proves annoying in play.
- **NPC highlighting by id was a bug fix in passing.** `GuideOverlay` sent every step with an NPC
  on it to the leprechaun search, so `PAY_FARMER` outlined the leprechaun rather than the farmer.
  It now outlines by id when the step names one. Unverified, like the rest.
- **The guide section's config positions collide** — several items share a position and always
  have. The new toggle sits at 1 alongside `highlightBankItems`. Renumbering the section is a
  tidy-up of its own.

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

### The shape it should take: sections, not one table

Asked for outright, so this is the frame the rest of the list hangs off. Four sections down the
tab, each answering a different question, in this order:

1. **Lifetime** — what you have actually got, per crop, with a total.
2. **Luck** — where you landed against expectation, as a distribution rather than a mean.
3. **Expected** — what your remaining seeds are worth, level-ups included.
4. **Validation** — the existing crop / n / got / avg table, which is the developer's view and
   belongs at the bottom now rather than being the whole tab.

The tab's tooltip is **"Nerd."** and that is the design brief. This is the one place in the
plugin where the answer being long is the point — everything else is a run you are in the middle
of; this is the thing you read when you are not farming.

### Section 1 — Lifetime totals, per crop

Everything you have harvested since the plugin was installed, one row per crop, totalled at the
bottom.

- Columns: **crop, patches, items, xp**. Sorted by xp, because that is what the reader is
  scanning for.
- `getTotalItems`, `getTotalXp` and `getTotalHarvests` already exist for the footer, and
  `CropHarvestStats` is already per crop — so the rows are a regroup of what is stored, not new
  capture. **The one wrinkle**: the store keys on crop *and compost tier*, so a crop farmed with
  two tiers is two entries and has to be summed for this view.
- Include `partialItems` and `partialXp` in the totals here, and **only** here.
  {@code totalItems()} and {@code totalXp()} on `CropHarvestStats` already do this. They are
  deliberately excluded from the averages — a part-picked patch is not a fair sample of a full
  one — but for "what have I actually got", a watermelon you picked three of is three
  watermelons.
- **Honest about its start date.** `firstHarvest` is stored, so the section can say *"since 4
  August"* rather than implying it covers an account's whole history. Without that line the
  numbers read as a lifetime total and are not one.

### Section 2 — Luck, as a distribution

The nerdy one, and the reason to build the tab at all. Not "you are 47 ahead" — *where you fell*.

- Per crop: **actual against expected, as a percentile**. The store holds `items` and `predicted`
  summed, so the ratio is free; the interesting version needs the spread, not just the mean.
- **This needs per-patch rows, so it is Tier 2** — `harvests.csv` has one line per patch with
  both predicted and actual. From those: a histogram of actual-minus-expected per crop, and a
  statement like *"your ranarr patches land above expectation 54% of the time"*.
#### No aggregate data is needed, and that is the whole point

The obvious assumption is that "luck" needs a population to compare against — how did *other*
players do. It does not, because the thing to compare against is not other players. It is the
game's own RNG, which is fully specified.

A patch is harvested by rolling chance-to-save until the lives run out, so the item count is a
**negative binomial**: trials until `r` failures, where `r` is the lives and `p` is the save
chance. {@code YieldEstimate.expectedHarvest} already computes `lives / (1 - save)`, which is
exactly that distribution's mean — so the variance is `r·p / (1-p)²`, free, from the two numbers
already in hand.

That gives the null model outright:

- Per patch, from the level, compost and gear stored on its `harvests.csv` row: mean `μᵢ` and
  variance `σᵢ²`.
- Over `n` patches of a crop, all independent: total mean `Σμᵢ`, total variance `Σσᵢ²`. Different
  levels and tiers across the rows are fine — they simply have different parameters.
- The player's actual total lands at `z = (actual − Σμᵢ) / √(Σσᵢ²)`, which is a percentile.
  Normal approximation is sound by the central limit theorem once `n` is a few dozen, which is
  the same floor the sample-size rule already imposes.

So a single account's own history is sufficient, and the answer is exact rather than relative:
*"you are at the 71st percentile of where this many patches should land"* — not "luckier than
other players", which would be a much weaker claim even with the data to make it.

**What a population would actually be for** is the other question, and it is worth not confusing
the two: aggregate data would test whether the *published constants* are right — whether the
wiki's chance-to-save figures and the disease rates match reality. That is validating the model,
not measuring the player against it. It is also the one thing this plugin will not do: it is
read-only and sends nothing anywhere, and collecting play data from users to answer a curiosity
would trade that for very little.

- **The sample-size rule is doing the real work**, then, and is not a caveat. `√(Σσᵢ²)` grows
  like `√n` while the total grows like `n`, so the percentile is meaningless at small `n` and
  tightens quickly — which is exactly why a figure over four patches must not be shown at all.

#### Storage: one more accumulator beats a better file format

The instinct is that per-patch analysis needs a queryable store rather than an append-only CSV.
For the headline number it does not, and the reason is the same property the maths above turns
on: **variance is additive over independent patches**, exactly as the mean already is.

`CropHarvestStats` already accumulates `items` and `predicted` per crop and tier. Adding a third
running total — `predictedVariance`, summing `r·p/(1-p)²` at the moment each harvest is recorded,
where the level, compost and gear are all in hand — makes the percentile computable from the
rolled-up store alone. No file is read, nothing is parsed, and it stays bounded at ~50 crops x 4
tiers like the rest of it. **Build this first**: it is a few lines, and it delivers the whole of
"where did I land" without touching the storage question.

**What genuinely needs the per-patch rows** is anything about *shape* or *sequence*, which no
running total can reconstruct:

- the histogram, as opposed to the percentile — where your patches actually clustered;
- runs, which are found by clustering timestamps;
- yield against level, which needs each row's level, not the aggregate.

**For those, the CSV is adequate and the format is not the problem.** A heavy farmer writes
perhaps a hundred rows a day — roughly 4MB a year, parsed once in milliseconds. What is wrong
with it today is smaller and more specific than "it should be a database":

- **It is read by column position**, so inserting a column silently reinterprets every historic
  row. Read the header and map by name; the header is already written.
- **It has no version marker.** One `version` column costs nothing and makes a future format
  change survivable rather than a migration.
- **It is unbounded**, which is fine for size and not fine forever. Rotating at some row count is
  the cheap answer, and losing the oldest rows costs little because the aggregates already hold
  the lifetime totals.
- **It must be read once on load, never on a panel refresh.** That is the mistake to avoid, and
  it is a caller mistake rather than a format one.

**A real database is not worth it here.** SQLite means a native dependency in a plugin that
currently has none, for a dataset that fits comfortably in memory; and the plugin's own constraint
— read-only, nothing leaves the machine — means there is never a bigger corpus to scale to. If
the CSV is ever genuinely outgrown, JSON Lines is the honest next step: still append-only, still
a text file, but self-describing per row, so schema changes stop being a positional puzzle.
- **Sample size is the whole risk.** Thirty patches cannot distinguish luck from a modelling
  error, and a percentile computed over four patches is noise presented as insight. Every figure
  here needs an n beside it, and below some floor — 20 patches, say — it should say *"not enough
  yet"* and nothing else. Getting this wrong turns the section into a machine for inventing
  patterns.
- Cheap wins that belong in this section and need no CSV: **cumulative luck** (actual minus
  predicted, summed), **best and worst single patch** per crop, and **patches walked away from**
  (`partialItems`, real lost yield and the only actionable line on the tab).

### Section 3 — Expected yield from the seeds you hold

What your bank is worth if you plant all of it through this plugin.

- Every seed you own, run through `CropYieldModel.expected` against your patch count, compost
  choice and detected gear — the same arithmetic the run projection uses, so the two cannot
  disagree.
- **Accounting for level-ups mid-cycle is the actual problem here**, and it is not a detail. A
  full bank of guam planted at level 30 finishes somewhere north of 40, and chance-to-save moves
  with the level, so a single-level calculation understates the total. The honest version
  iterates: plant a cycle at the current level, add the xp, recompute the level, repeat until the
  seeds run out. That is a loop over `CropXp` and the xp table, and it is the difference between
  a number that is roughly right and one that is right.
- **Two figures, not one**: the naive "at your current level" and the iterated "planting it all
  out". The gap between them is itself interesting, and showing both makes the assumption
  visible instead of buried.
- **What it must not do** is imply a schedule. This is "what these seeds are worth", not "you
  will have this by Tuesday" — growth time is real but so is logging off, and a time estimate
  would be the tab's first dishonest number.
- Ordering by xp per seed makes it a planting guide as a side effect: it answers *"which of these
  should I actually be planting"* without being asked.

### Tier 1 — from the rolled-up store, no new capture

- **What compost is actually worth, measured.** The one worth building first. The store already
  splits every crop by tier, so it can say *"ultracompost gave you 8.4 ranarr a patch against 6.1
  untreated, over 31 patches"* — the player's own numbers, not the wiki's, answering the only
  question anyone asks about compost. **Caveat that shapes it:** most players use one tier
  forever, so for many accounts one column is empty and the comparison cannot be drawn. It has to
  degrade to "you have only ever used ultracompost" rather than to a misleading half-answer.
- **Luck, cumulative**, **best and worst single patch**, and **patches walked away from** are all
  stored and unsurfaced, and all belong in Section 2 above. Listed here because they need nothing
  new — they are the part of that section that can ship before the CSV is ever read.
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
