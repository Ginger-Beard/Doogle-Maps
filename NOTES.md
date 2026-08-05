# Notes

Everything this project has worked out, kept because the reasoning has repeatedly paid for
itself — the seed-box container lag, the four yield mechanics and the wrong-object-model
sequence each cost real time to establish and would cost it again.

Open work lives in `TODO.md`; in-client checks live in `TESTING.md`. This file is reference.

---

## Early fixes and findings, in the order they were made

The first pass of work, kept as written. Most are struck through and done; the Geomancy entry
is the substantial one, and the only part of it still open is how a *diseased* patch renders.

- ~~White checkboxes are still almost black on the hide patches drop down~~ **done** — the
  look and feel hands out a *shared* checkbox UI, so a per-component style set after
  construction never sticks. Now drawn explicitly (`CheckIcon`): white tick, guaranteed.
- ~~Credit osrs user "Sitta mango" for the "Doogle Maps" name, from my clan~~ **done** —
  README and ATTRIBUTION.md.
- ~~Inspect patch seems to update the compost listing, but ... the icon gets removed from the
  south, and vice versa, should keep cached~~ **done** — the bug was not the inspect
  pairing. A raked, composted, not-yet-planted patch reads as *weeds*, and the store treated
  weeds as "empty, so forget the treatment" — wiping the compost a tick after it was
  applied. Covered by `PersistenceTest`.
  - *Also can we get this from time tracking instead so it's up to date without clicking
    inspect?* — **no**, and worth knowing why: core Time Tracking detects compost exactly the
    same way we do (the same chat messages, the same Inspect action). It has no extra source.
    We already read its stored value to seed patches we have never seen. Compost simply is
    not in the patch varbit, so something has to say it out loud.
- ~~**Protection icon should be the head of the NPC you actually paid** for that plot, not a
  shield~~ **done, wants your eyes in-client**. `FarmerIcon` draws the gardener's chathead on
  a paid patch; the blue shield still marks the immune ones, which have no farmer to show.
  The tooltip now names them too — "Protected - Elstan paid".

  Runtime rendering was checked and ruled out properly rather than assumed: the API does
  expose `NPCComposition.getChatheadModels()` and `Client.loadModel`, but nothing that
  rasterises a `Model`, so bundling was the only option. 48 PNGs, 204 KB.

  **Nothing is hand-maintained**, which was the thing worth getting right. `tools/
  fetch_chatheads.py` reads the farmer ids out of the generated `FarmingWorldData`, resolves
  their numbers from the runelite-api jar Gradle already downloads, finds each one's wiki page
  *by id*, and pulls the portrait and the name. It emits the sprites, `Farmers.java`, and
  `tools/chatheads.tsv` as provenance. `--from-tsv` rebuilds the Java without refetching,
  since a full pass is ~200 wiki requests.

  Three things about the wiki's search API, none of them documented, all of which cost a pass
  to find:
  - **CirrusSearch's regex dialect has no `\s`**, and matches nothing rather than erroring.
    That reported all 49 farmers as missing.
  - The **regex forms are scored and time-limited**, so a page that plainly matches can still
    fall outside the results — Ayesha, Imiago and Liliwen all did. The quoted phrase
    `insource:"id = N"` is an ordinary indexed search and finds them; it is tried first now,
    with three regex fallbacks behind it for versioned infoboxes (`id2 = ...`).
  - `action=ask` and `action=cargoquery` are **both disabled**, so there is no structured
    query for "the NPC with this id" — the search index is the only route.

  **One genuine gap:** `TORTUGAN_CORAL_FARMER` (15061) has no wiki page. `FarmerIcon.of`
  returns null and the caller keeps the shield, which is also what happens to any farmer a
  future RuneLite update introduces. `FarmerIconTest` names that id explicitly, so if the wiki
  ever documents them the test fails rather than accepting the gap for good — and a new
  gardener appearing in the world data fails the test too, with "rerun fetch_chatheads.py".

  Badges are **18px** rather than the other badges' 14: a drawn shield reads fine at 14, a
  face does not. `build/farmers.png` is a contact sheet of all 48 at that size, written by the
  test, for judging that without launching the client.
- ~~Hidden patches aren't persisting logout/login~~ **probably fixed, needs your eyes** —
  save/load round-trips correctly in isolation (`PersistenceTest` proves it), so the fault
  was *when* we loaded: config is scoped to a RuneScape profile that is not resolved until
  after login, and loading before then read nothing and wiped the in-memory toggles. Now
  deferred until the profile exists.
- ~~Hide patches drop down ... slow as hell~~ **done** — two causes. Every refresh rebuilt
  all 23 tabs (several hundred Swing components, three times a minute, even with the sidebar
  shut); now only the visible tab is rebuilt and the rest catch up when selected. And every
  refresh re-scaled every icon from scratch; those are cached now.
  - *scroll bar is blue/white, isn't this a standard runelite feature?* — it is: the look and
    feel sets `ScrollBarUI` globally. Yours not picking it up is the same symptom the light
    "Show patches" button had. Styled explicitly the way core's Time Tracking panel does its
    scroller. **Needs your eyes** — I could not reproduce the unstyled colours locally.
- ~~Seed listing should always be cached, and persist login, counts, inventory, seed vault,
  seedbox~~ **done** — all four are real item containers, so the seed box works like the
  rest. Cached per profile, refreshed whenever you open one, inventory read live.
- ~~Seed listing should be a drop down or check box?~~ **Moot, and answered by what got
  built.** The worry was that a dropdown implies *picking* one — but picking is exactly what
  the seed grid now does: click an icon and it joins the run, with a green border to say so.
  That is the behaviour the note wanted, in a form that also shows counts and staleness, which
  a dropdown cannot. The compost selector beside it is a dropdown, which is where the idiom
  genuinely fits.
- ~~Estimated yield per patch, with min/expected on hover, plus expected XP~~ **done, wants
  your eyes in-client**. The blocker in the old note — "chance-to-save is NOT on these
  pages" — was wrong about *where* to look, not about the wiki. It is published, one crop at
  a time, in the `{{Farming yield calculator|low=|high=}}` template on each seed's page.
  Scraped all 34 into `tools/crop-yield.tsv` -> `CropYield.java`.

  Three things the old note had subtly wrong, all of which change the answer:
  - Boosts scale the **constants**, before the level interpolation. Applying them to the
    interpolated chance instead gives a different number.
  - Secateurs and cape are **additive** with each other (1.15 together), then the diary is
    **added as a flat integer** (+10/+17/+25, not x1.10), then attas multiplies. Floor at
    each step.
  - The Farming cape is **herb patches only**, and secateurs do nothing underwater.

  `YieldEstimateTest` anchors on figures the wiki states in prose, reached by a different
  route than ours, so they are real cross-checks rather than restatements:
  - jute 47.7% at level 13 and 70.7% at 99, both from jute's own page
  - giant seaweed 59% -> 82% across levels 1-99, from Mod Ash
  - guam and torstol converging to exactly 81/256 at level 99, from Mod Kieren
  - a kitted ranarr patch expecting ~9 herbs, the wiki's headline figure

  In the UI: the row now reads **"ready ~9"** for lives-based crops (regrowing crops keep
  "ready x4", which is a real count of fruit on the plant, not an estimate). The tooltip
  gives the expectation, the guaranteed floor, and what the estimate assumed. There is
  deliberately **no max** — saved lives can run indefinitely, so a ceiling would be fiction.

  Bonuses are detected, not asked for: `FarmingBonusStore` reads secateurs from inventory or
  equipment and the cape by **name** (`starts with "farming cape"` / `ends with "max cape"`),
  since there are 50-odd cosmetic max capes and an id list would rot. Attas is read live
  from the anima patch the plugin already tracks.

  ~~**Still open, small:** the diary bonus is hardcoded to 0.~~ **done** — `DiaryBonus` reads
  the four varbits and applies +10/+17/+25 to Catherby (Kandarin medium/hard/elite) and +10 to
  Hosidius and the Farming Guild (Kourend hard). Only the best Kandarin tier counts; they do
  not stack with each other. Worth more than it looks: because the diary is *added* to the
  chance-to-save constants rather than multiplying them, elite Kandarin beats the magic
  secateurs on a herb patch, and the two stack. `PatchRow` now asks for bonuses **per patch**
  rather than per player, which is the only form that can be right for these.

  **Deliberately absent, recorded as tests:** celastrus (uses the lives mechanic but Jagex
  has never published its constants), flowers and bushes (do not use the mechanic at all).

  **XP is wired into the tooltip.** Reported per award rather than as a total: a tree's
  entire payout is one check-health click, so that is what it shows; a herb's arrives per
  pick, so it shows the rate. ~~Fruit trees are still absent — the wiki gives them one
  unlabelled figure that could be the check award or a total.~~ **Done, and it was neither:**
  see the fruit tree section below.

  **Validation is automated now — see `HarvestLog`.** Rather than eyeballing it, the plugin
  records every finished patch to the client log and to `~/.runelite/doogle-maps/harvests.csv`
  with the prediction beside the actual. Config toggle "Log harvests for validation",
  currently defaulted **on**; turn it off before release if it stays noisy. Nothing is sent
  anywhere.

  Columns: `time, patch, crop, level, compost, secateurs, cape, attas, lives, predicted,
  actual, predicted_xp, actual_xp, completed`. Rows with `completed=false` are patches you
  walked away from — **exclude those when averaging**, or partial picks will look like low
  yields. A couple of dozen completed herb rows settles whether the formula matches the game;
  the mean of `actual` should land on the mean of `predicted`.

  An XP mismatch logs at WARN rather than going quietly into the CSV, because that means a
  wrong number in `CropXp` — a data bug, not a modelling one, and fixable outright.

  **Stored as stats, not just as a debug trail.** `HarvestStatsStore` keeps a lifetime
  rollup per RuneScape profile, so the same data can be shown back to the player later.
  Nothing displays it yet — the store, the aggregation and the persistence are done and
  tested, the panel is not written.

  Design notes worth keeping:
  - **Rolled up, not a list of harvests.** A serious farmer does thousands of patches;
    aggregates are bounded at ~50 crops x 4 compost tiers however long you play. The raw
    per-patch rows stay in the CSV for anyone who wants to go deeper.
  - **Keyed by crop *and* compost tier**, because compost is the single biggest lever on
    yield and an average mixing untreated with ultracomposted describes neither.
    `getByCrop()` sums the tiers back together for a per-crop view.
  - **The summed prediction is stored beside the summed actual.** That is what makes the
    accuracy figure valid across mixed conditions: each row's prediction used that row's own
    level and gear, so totals compare like with like. Averaging predictions afterwards would
    not have worked.
  - **Abandoned patches are held apart** in `partialItems`/`partialXp`. They are real items
    and belong in a lifetime total, but folding them into the average would drag it down
    with harvests that were never finished — the most likely way for a stats page to lie.

  ~~Ready to display when wanted~~ — **displayed now.** `HarvestStatsPanel` sits under the run
  section, collapsed, headed "Show harvest history (n)" so the count is visible without
  opening it. Expanded it gives lifetime patches/items/xp, how the predictions are doing in
  words, and a table of `got / avg / pred` per crop. Hovering a crop gives best and worst,
  items from patches left standing, and **the per-compost split** — which is the part worth
  hovering for, since an average that mixes untreated with ultracomposted describes neither.

  Accuracy is reported as prose rather than a bare ratio, and anything within 5% either way
  is called right: the figure is noisy until a couple of dozen patches are in, and "1.03x"
  would invite chasing sampling noise.

  `clear()` is wired to **Clear harvest history** in the settings' Maintenance section, beside
  the profile reset but deliberately *not* part of it, and with its own `warning` prompt. The
  two are separate because they are different kinds of thing: a profile reset throws away what
  the plugin worked out, all of which returns by playing, while this throws away a record of
  things that already happened. Sharing one button would mean either losing the history to a
  click meant for stale patch state, or never being able to clear it.

  Both trigger keys are now `String` constants on the config interface (`RESET_PROFILE_KEY`,
  `CLEAR_HARVEST_STATS_KEY`) rather than literals repeated in the annotation and in
  `onConfigChanged`. A key spelt in two places can be renamed in one, and the failure is
  silent — the switch flips and nothing happens.

  The table is a shared `DataTable` now, extracted from `RewardTable`, because the two were
  about to be near-copies. Watch for one thing if you touch it: a table row declares its own
  preferred width, and it used to declare **zero** — harmless inside the reward table's
  stretching `BorderLayout`, and invisible until the stats panel put one in a `BoxLayout` and
  it rendered nothing at all. `build/harvest-stats.png` is written by the test.

- Geomancy bulk-refresh scrape (§4b) — **prep done, needs one in-client capture**.

  The interface is `InterfaceID.FARMING_VIEW` (group **179**), and RuneLite already names all
  **329** of its components — three per patch: `_BACK`, `_PIC`, `_FRONT`. So none of the
  widget ids have to be discovered by hand.

  **The families line up exactly with ours — all 23, all 107 patches**, which settles the hard
  half: there is a clean one-to-one correspondence, and only the *ordering within* each family
  is still unknown. Three names give the mapping away:
  - Geomancy splits herbs into `HERB` (8), `HERB_MYARM` and `HERB_MY2ARM` — the two Trollheim
    patches from My Arm's Big Adventure — matching our 10.
  - `GRAPEVINE` is our `GRAPES`.
  - `COMPOST_GUILD` is the Hosidius big bin, our `BIG_COMPOST` — independent confirmation that
    folding it in with the other bins was right.

  `GeomancyProbeTest.theInterfaceHoldsExactlyThePatchesWeDo` pins this, so a RuneLite data
  update that adds a patch on one side only fails a test rather than silently misreading the
  interface.

  **What is still unknown, and how to find out.** `GeomancyProbe` (settings: "Dump the
  Geomancy interface", off by default) writes the whole widget tree to
  `~/.runelite/doogle-maps/geomancy-<time>.tsv` when you cast — component id, resolved name,
  sprite, item, model, text, actions — *and*, in the same file, every patch the plugin already
  knows with its region, varbit, crop and state. So the two halves sit side by side and
  decoding becomes reading rather than guessing.

  **First capture, 2026-08-04 — timing was wrong, now fixed.** The dump caught the interface
  as an empty shell: every patch widget `hidden=true`, no sprite/item/model, and the text
  `"Loading..."`. `WidgetLoaded` fires when the interface is *created*, and the server fills
  it in over the following ticks. The patch-cache half of the file was perfect (all 107 with
  varied states), so only the widget half needed redoing.

  Also learned from that dump, which was not wasted:
  - The interface has **six tabs**: Allotments, Herbs, Hops, Bushes, Trees, Special. The
    selected one uses sprite **296**, the rest **295**.
  - Contents look to be populated **per tab**, so one cast has to be followed by clicking
    through the tabs.
  - Every component was captured twice: the same widget is reachable through more than one
    of the child accessors and object identity did not dedupe it. Now keyed on component id
    plus child index.

  The probe now waits: it starts a file when the interface opens, then snapshots on each game
  tick once the contents settle on something new and no `"Loading..."` remains, up to 12
  snapshots per cast. Clicking through the tabs therefore yields one snapshot per tab in a
  single file.

  Best capture: cast Geomancy somewhere you have recently walked, with patches in **several
  different states** — one empty, one growing, one ready, ideally one diseased — then **click
  through all six tabs** before closing it. Identical states make the sprite-to-state mapping
  ambiguous.

  **Second capture, 2026-08-04 (cast, tabbed, scrolled) — most of it is decoded.**

  | Widget | Carries |
  |---|---|
  | `_PIC` | the produce as a real **item id** (5982 watermelon, 231 snape grass, 225 limpwurt, 6055 weeds for an empty patch) |
  | `_FRONT` child 0 | the **patch location** as text: "Falador (NW)", "Catherby (N)", "Harmony" |
  | `_FRONT` child 1 | the **crop name**: "Watermelons", "Ranarr weed", or a generic "Herb patch" when empty |
  | `_FRONT` child 5 or 6 | the **stock count** for regrowing crops |
  | `_BACK` | background only, sprite 1040 for every patch |

  Two consequences worth stating plainly:

  - **The ordering problem is gone.** The interface names each patch's location itself, so the
    widget-to-`FarmPatch` mapping can be matched on text rather than inferred from index
    order. That was the main open risk.
  - **The fruit-tree count reads 6 on a full palm**, which independently confirms the harvest
    count direction inferred earlier from `harvestStages`. That had been marked "still worth
    eyeballing once" — consider it eyeballed.

  **`TOOLTIP` child 2 is the richest thing in the interface**, and carries more than the
  varbit does:

      Teak tree (State: 8 / 8)<br>The patch has been treated with ultracompost.
      Grapevine patch (empty)<br>The people of Hosidius are protecting the patch.
      Hardwood tree patch (weeds)

  That is crop, growth stage, **compost tier and protection** — and compost is not in the
  patch varbit at all, which is why the plugin currently needs an Inspect per patch. But the
  tooltip is one patch at a time, on hover, so it is not a bulk read.

  **Third capture, 2026-08-04 (cast, tabbed, scrolled, hovered) — question settled.**

  **Text colour does not encode state.** Only two colours exist across the whole interface:
  `FF981F` on every label and `FFB82F` on the counts. That was the last candidate for
  carrying state in the bulk data, so the bulk data does not carry it.

  Proved directly, too: `HERB_MYARM` (Troll Stronghold, **harvestable** ranarr) and
  `HERB_MY2ARM` (Weiss, **growing** ranarr) are identical in every captured field — same item
  id, same crop name, no extra children.

  **What the bulk read does give, and it is not nothing:**

  | Signal | Meaning | Reliable? |
  |---|---|---|
  | generic crop name ("Bush patch") + weeds item | patch is empty | yes |
  | real crop name + `_PIC` item id | which crop is planted | yes |
  | count on `_FRONT` child 5/6 | crop is **grown**, and this is its stock | yes |
  | real crop name, no count | growing **or** dead | ambiguous |

  The count correlation is exact across the capture: all 6 palms read 6, poison ivy /
  jangerberry / whiteberry read 4, Al Kharid cactus reads 3 — every one of them harvestable in
  our cache. Every patch without a count was growing or dead. So for regrowing crops, Geomancy
  answers "is it ready, and how much is on it" outright, which is the most tedious thing to
  check on foot.

  **Full state exists only in the hover tooltip.** `TOOLTIP` child 2, one patch at a time:

      Ranarr weed (State: 5 / 5)<br>A gardener is protecting this patch.<br>The patch has been treated with ultracompost.
      Herb patch (weeds)<br>This patch is permanently undead and won't get diseased.
      Allotment (weeds)

  Crop, growth stage, protection, **compost tier**, disease immunity. Richer than the varbit.
  But it is populated on hover, and the plugin does not automate input, so it cannot be
  harvested for all patches. It **can** be read opportunistically: whatever the player hovers
  while the interface is open is free information, and compost in particular is otherwise only
  learnable by Inspecting each patch in person. Worth doing.

  **Core Time Tracking does not read Geomancy** — no reference to `FARMING_VIEW` anywhere in
  the client — so casting it does not refresh core's cache either, and our backfill from it
  inherits nothing. Checked because it would have been free if true.

  **Correction — state IS in the bulk data, in the progress bar.** The "partial refresh"
  conclusion below was wrong, and wrong because the probe was not capturing widget geometry.

  Each patch is drawn with a status bar, the same idea as this plugin's own rows, built from
  three `_BACK` children:

  - child 2, colour `474745` — border
  - child 1, colour `0E0E0C` — track
  - child 0 — the **fill**, and this is the one that talks:
    - normally sprite `1040` with colour `000000`
    - on a dead patch, **no sprite and colour `FF3F3F`** (red) — 36 rows in the third capture,
      matching the dead patches in our cache

  Colour therefore marks dead, and the fill's **width** is what carries how far along the crop
  is. Width was never captured, which is why three passes of analysis concluded state was
  absent: every field that *was* captured is genuinely identical between a growing and a ready
  patch. Confirmed independently in-client: a dead Catherby limpwurt shows a red bar where the
  others are green, with the tooltip `Limpwurt (State: 3 / 5)<br>The patch is dead.`

  **Diseased is still unseen, and cannot be arranged on demand.** Dead draws red; whether
  diseased draws red too, draws some other colour, or is only distinguishable from the tooltip
  is unknown. So the probe no longer relies on catching it in a deliberate capture: it keeps a
  running catalogue in `~/.runelite/doogle-maps/geomancy-vocabulary.tsv` of every distinct bar
  fill (`sprite=… colour=…`) and every distinct tooltip clause it has ever seen, carried across
  sessions, with stage numbers generalised to `n / m` so ordinary variation does not drown it.
  Anything new is logged at INFO as `Geomancy: new bar seen …` / `new tooltip seen …`.

  So the answer arrives on its own: leave the toggle on, cast Geomancy occasionally, and the
  first time a patch is diseased it announces itself. Known so far — bars: `sprite=1040
  colour=000000` (alive), `sprite=-1 colour=FF3F3F` (dead). Tooltips: `(weeds)`, `(empty)`,
  `State: n / m`, `The patch is dead.`, three protection phrasings, the ultracompost line, and
  the permanently-undead line.

  **Why diseased specifically matters**, beyond completeness: there are two spells that act on
  it, and both imply a spellbook swap that the gathering phase would have to plan for.

  - **Cure Plant** — Lunar, 66 Magic. Cures a diseased patch before it dies.
  - **Resurrect Crops** — Arceuus, 78 Magic. Revives a patch that has already died.

  Which means the dead/diseased distinction is not cosmetic: diseased is *recoverable and
  time-limited* (it dies at the next growth tick), dead is recoverable only by the other
  spellbook. A run planner that knows which it is can say "swap to Lunars and bring astrals"
  rather than "this patch is a write-off". Not building that now — this is only about being
  able to tell them apart.

  **Forcing a diseased patch, from the wiki's published rates.** Per growth tick, untreated:

  | Crop | Chance |
  |---|---|
  | **Herbs** | **27/128 (~21%)** |
  | Fruit trees | 18/128 (~14%) |
  | Maple | 13/128 (~10%) |
  | Magic | 9/128 (~7%) |
  | Coral | 8/128 (~6%) |

  Herbs are both the highest rate and quick (20 min/tick, 5 stages). Neither the first stage
  nor a fully grown crop can catch anything, so a herb gets ~3 rolls per 80-minute cycle —
  around a **50% chance per patch per cycle**, so a few patches at once makes it near certain.

  Conditions that matter: **no compost** (it cuts the chance by 50/80/90% for compost / super /
  ultra) and **do not pay the farmer**. Avoid the patches that cannot be diseased at all —
  Trollheim and Weiss (My Arm's), Hosidius with the easy Kourend diary, Harmony with elite
  Morytania, the Falador tree patch with elite Falador, and poison ivy.

  The window is short: a diseased crop dies at the next growth tick, so Geomancy has to be cast
  within about 20 minutes of it happening.

  The probe now also records `getWidth`, `getHeight`, `getOriginalWidth` and `getOriginalHeight`.
  Expect the fill width to be proportional to stage, so that `width / trackWidth` recovers the
  `State: n / m` the tooltip spells out — which would make Geomancy a **full** bulk refresh
  after all, tooltips needed only for compost and protection.

  **Superseded, kept for the reasoning:** it fills in what
  is planted where, flags empty patches, and gives ready-plus-stock for every regrowing crop,
  for the whole game in one cast. It cannot give growth progress for ordinary crops. Confidence
  on anything learned this way should be marked accordingly rather than presented as a sighting.

  ~~Loose end: empty patches show two different `_PIC` item ids, 6055 (weeds) and 6512.~~
  **Identified**: 6055 is `WEEDS`, 6512 is `BLANKOBJECT` — a generic placeholder, not anything
  farming-specific. Weeds on a weedy patch, a blank on a raked one, so treating either as
  "empty" is sound.

- ~~Off-by-one in the harvest count for regrowing crops~~ — **fixed**, wiki-verified.

  The wiki settles it: *"A fruit tree will continue to grow fruit over time, to a maximum of
  six"*. RuneLite's `harvestStages` for a fruit tree is **7**, so it counts *states*
  (0-6 fruit), not fruit. The two crop families genuinely differ:

  | family | states | meaning | lives/stock |
  |---|---|---|---|
  | Herb, allotment, hops | 3 | remaining lives, never 0 while harvestable | `stage + 1` |
  | Fruit tree, bush, cactus | 7 | fruit currently on the plant, can be 0 | `stage` |

  `GrowthTimer.project` now branches on `produce.getRegrowTickrate() > 0` and caps regrowing
  crops at `harvestStages - 1`. A full palm reads 6, a picked-from one reads its actual stock,
  and an empty one reads 0 — where the row shows the time to the next fruit rather than
  claiming to be "ready". Four tests cover full / partly picked / empty / non-regrowing.

  **Still worth eyeballing once**: whether the number matches the fruit actually hanging on
  the tree. The direction of the count is inferred, not proven — if it turns out inverted
  (stage counting down from full), it is a one-line change in the same place.
- ~~Repeatably harvestable patches should indicate when they're ready to be harvested
  again~~ **done** — rows now read "ready x4" for remaining harvests, and the tooltip gives
  the regrow countdown with a nudge to leave the patch rather than clear it. That timer uses
  the crop's regrow rate, which is a different cycle from its growth rate.

---

## Yield: four mechanics, not one

`YieldEstimate`'s chance-to-save maths only covers herbs, allotments, hops and giant seaweed.
Everything else was falling back to a flat one item, which was exactly right for a marigold and
out by a factor of seven for a limpwurt. `CropYieldModel` now dispatches per crop:

| Rule | Crops | Compost? |
|---|---|---|
| Harvest lives, from published CTS | herbs, allotments, hops, seaweed | yes |
| Base 3 + a level-scaled roll | limpwurt, belladonna | no |
| Whatever has regrown | bushes, fruit trees, calquat, grapes | no |
| Exactly one | ordinary flowers | no |
| Wiki's measured average | cactus 10, potato cactus 17.5, celastrus 9 | n/a |

The level roll is Mod Ash's description: a random number from 0 to your level minus one,
contributing one per ten. Its mean is ~4.5 at level 99, so a limpwurt patch is about **7.5
roots**, against the wiki's measured 8 — the gap being an attas plant, which is not modelled
for this rule.

`basisFor` reports which rule produced a figure, so the UI can distinguish a computed number
from a measured one. The empirical averages are the weakest link: they are measured at 99 with
secateurs, cape and ultracompost, so they neither scale with level nor respond to compost.
Anything better would need Jagex to publish the rules.

Still unmodelled, and worth revisiting: **attas** on the level-roll crops, and whether cactus
really uses harvest lives — a 2018 newspost describes it as "similar to herbs" with a 75% chance
to use a life at 55 and 30% at 99, but those two points do not fit the standard CTS curve, so
the measured average is used instead.


---

## Boosts: what is detected, and where each one acts

Settled after the run-estimate work. The point that kept catching me out is that these do not
all act at the same point in the sum, so they cannot share one flag.

| Bonus | Effect | Detected from |
|---|---|---|
| Magic secateurs | +10% yield | inventory **or** equipment — the game accepts either |
| Farming cape / max cape | +5% yield, **herbs only** | equipment, matched by item name |
| Attas | +5% yield, every patch | the anima patch the plugin already tracks |
| Farmer's outfit | up to +2.5% **experience only** | equipment |
| Kandarin diary | +10/+17/+25 to **Catherby's herb patch** | varbit |
| Kourend hard | +10 to **Hosidius and Farming Guild herb patches** | varbit |
| Compost | +1/+2/+3 harvest lives | **chosen by the player**, per patch type |

Two of these are shaped differently from the rest and are worth stating plainly:

- The **outfit multiplies experience and never yield**, so it is applied after the harvest is
  worked out rather than alongside secateurs. `FarmingBonuses.applyOutfit` exists so that
  passing a yield through it is obviously wrong at the call site.
- The **outfit's pieces are not worth the same** — jacket 0.8%, legs 0.6%, hat 0.4%, boots
  0.2%, plus 0.5% for the set. Most skilling outfits give a flat share each, so the usual
  assumption is wrong for three of the four pieces.
- The **diaries belong to a place**, not to a player, which is why bonuses are now looked up
  per patch.

**Compost is the one thing not detected**, because there is nothing to detect: it is applied
during the run. It is chosen instead, per patch type, from a dropdown beside that tab's seed
list (`CompostSelectionStore`, default ultracompost). That choice drives the run table, which
is consequently one honest row per crop rather than a grid of possibilities.

Not modelled: attas on the level-roll crops (limpwurt, belladonna), and the coral exception —
the Farmer's outfit does not boost coral experience without a medallion of the deep.


---

## Seed box accounting

The seed box cannot be read after a Fill or Empty: **the client's copy of that container lags
a step behind the action**, so asking it returns the contents from before the move. That one
fact produced three separate reported bugs.

- Filling made seeds **vanish** — the box read as still empty while the inventory was already
  emptied.
- Emptying made the count **double** — the box read as still full while the seeds were also
  back in the inventory.
- Before that, an earlier attempt to re-read sibling containers on every change was itself
  the cause of the first two. It has been removed from the capture path.

Fixed by not reading the box at all. Both actions have exact semantics and the inventory is
always live, which is enough to derive the box outright:

- **Fill** — whatever left the inventory went into the box, so add the delta to it.
- **Empty** — the box is provably empty afterwards, so no arithmetic is involved.

`SeedCapture.onMenuOptionClicked` supplies the action, matching all three seed box item ids.
Anything else that removes seeds from the inventory — planting, most obviously — has no
pending action and is left alone, which is covered by a test.

`relearnFromClient()` still reads every container directly, but only after a profile reset,
where there is no action in flight to lag behind.

### How other plugins do it, and the remaining gap

Checked, because guessing at this produced three bugs. **Nobody reads the seed box
continuously, because it is not possible.** The container (id 573) has its own interface,
`InterfaceID.HOSIDIUS_SEEDBOX`, and the server only pushes it to the client while that
interface is open. Everyone therefore does two things:

1. Read the container when the interface opens — the one authoritative sync. We do, via
   `ItemContainerChanged` for 573.
2. Infer in between, from actions or chat messages.

Core's loot tracker is the reference for (2) and uses chat messages. Those are now handled:
pickpocketing a Master Farmer drops seeds **straight into the box without touching the
inventory**, so no delta exists to derive them from — that was a real gap, now closed with
the same two patterns core uses. Note the near-miss while reading core: it has an
`ItemID.SEEDBOX` + "Take"/"Take-all" handler, but that is the *seed pack* from farming
contracts, a different item sharing a similar constant name.

**Known gap, accepted:** taking seeds out of the box **one at a time** rather than using
Empty. There is no chat message and no inventory-derivable signal for it, so the box count
stays stale until the box is next opened — at which point the container event corrects it.
Closing this would mean tracking interactions inside the seed box widget itself, which is a
lot of fragile machinery for a case that self-heals.


---

## Bank/vault open lag

Opening a bank stuttered slightly. Three causes, all on the container-changed path:

- `Seed.forItemId` was a **linear scan over 81 seeds**, called once per item in the container.
  A bank is ~1,000 items. Now an item-id map, in the generator so it stays generated.
- Every container event **rewrote the config** (gson serialise of the whole seed cache) even
  when the counts were identical, which they almost always are on reopening.
- Every container event **fired a change notification**, rebuilding the visible tab and its
  seed icons.

`store()` now returns whether anything actually differed, and only then saves and notifies.
The "seen just now" timestamp is still refreshed either way, so the staleness tooltip stays
honest. The **seed vault is the same shape and the same code path**, so it is fixed by the
same change — covered by a test that runs over both.


---

## Level-locked seeds sometimes rendered invisible

Reported as: seeds you cannot plant yet are "sometimes a nice grey, sometimes invisible,
can't figure out when" — with the tooltip still working, which is the clue that only the
icon was affected.

Swing paints a disabled label with `JLabel.getDisabledIcon()`, which derives a greyed copy of
the icon **once and caches it**. Item sprites arrive asynchronously, so whether that
derivation happened before or after the sprite loaded came down to whether that item had been
drawn before this session. Cold sprite -> Swing cached a greyed *blank placeholder*, and
nothing ever replaced it. Warm sprite -> greyed correctly. Hence "random".

Fixed in `Icons.setStack`: when the sprite arrives, `setDisabledIcon(null)` drops the cached
copy so Swing derives it again from the real image. Passing null also clears the flag saying
the caller supplied one, which is what permits the re-derivation.

`IconsTest` asserts the **user-visible** symptom — it renders the disabled icon and checks
some pixel is not transparent — rather than poking at Swing's cache, which modern JDKs block
reflection into anyway. Verified to fail with the fix reverted.

This is the same class of bug the file's other helper, `setScaled`, already guards against:
anything derived from an item sprite has to be redone when the sprite lands.


---

## Settings: patch types and reset

- **Patch type toggles** — new collapsed "Patch types" section, one per tab. Turning one off
  removes the tab and its icon outright. The keys the panel watches are read off the config
  interface by reflection (`PatchTabs.isTabVisibilityKey`), not listed a second time; a
  hand-written list would fail silently, with the toggle working but needing a restart.
- **Reset this account's data** — `ProfileReset`, in its own collapsed section.
  - Clears: patch states, seed counts, cached Farming level, carried-bonus flags, learned
    patch positions, learned banks. The line is **what the plugin worked out**, not what the
    player told it.
  - **Keeps: settings, harvest statistics, shown/hidden patch toggles, and the run seed
    selection.** All four live in the *same config group* as the cache, so wiping the group
    would have taken them with it — that is why the reset names each store's key instead.
  - `ProfileResetTest.holdsNoStoreItMustNotClear` reads `ProfileReset`'s own fields and fails
    if `HarvestStatsStore`, `AvailabilityProfile` or `SeedSelectionStore` is ever wired in.
    Different reasons, identical silent failure mode.
  - RuneLite's config annotations have **no button type** in 1.12.34.1, so this is a boolean
    that the plugin flips back off immediately after acting. `@ConfigItem(warning = ...)`
    supplies the confirmation prompt.
  - **A reset lands on the fresh-install state, not an empty one.** After clearing, the
    plugin reloads, which re-runs the core Time Tracking backfill — that data is still there
    and is how a first run populates itself. It then relearns, on the client thread, the
    Farming level and every seed container the client still holds.
  - The Farming level was the trap: it is otherwise only recorded from a `StatChanged`
    Farming XP drop, which might not come for hours. A level of 0 makes `PatchRow` hide
    every yield estimate, so the panel would have quietly lost a feature after a reset.
    `load()` now reads it outright on every load, which also fixes a fresh install where the
    player has not gained Farming XP yet.


---

## Three top-level tabs — done

The sidebar is now **Almanac / Doogle Maps / Stats** rather than one scrolling column, which
had the run controls sitting below a hundred-odd patch rows and the history below those.

- **Almanac** — everything up to the Start run button: the summary line, the patch-type strip,
  the rows, the seed list and compost dropdown, the hide-patches toggles. Unchanged.
- **Doogle Maps** — the run. The routing half, which is where the name always fitted best.
  With a page to itself the destination list is now **open by default**; it was collapsed only
  because it had a hundred rows above it.
- **Stats** — the harvest history, no longer a collapsible section.

This also settles the open question in the rename section below: **the tab is the heading**,
so no separate collapsible "Doogle Maps" heading is needed. What is left of that item is the
descriptor and the apostrophe.

Two Swing things worth keeping:

- **`MaterialTab.select()` does not switch the page.** It marks the tab; only
  `MaterialTabGroup.select(tab)` swaps the display panel — and calling the tab's own first
  makes the group's a no-op, because it returns early on an already-selected tab. The symptom
  is a strip that highlights the new tab while the old page stays on screen, which is exactly
  what the first render showed.
- **The strip does not fit at its natural size.** `MaterialTab` pads generously and a
  `GridLayout` hands every tab the widest one's width, so three tabs asked for 253px of a
  225px sidebar. Equal thirds fit but clip "Doogle Maps" to "Doogle M...". It is a horizontal
  box now, with `sizeSectionTabs` sharing the width in proportion to each name and only ever
  scaling down — which survives the rename below rather than being three hardcoded numbers.

`PanelRenderTest` renders **all three** tabs (`build/panel.png`, `-doogle-maps`, `-stats`),
because the run controls carry the widest thing in the panel and moving them behind a tab
would otherwise have quietly taken them out of the width guard.


---

## Saplings were missing from tree seed lists — fixed

`Seed.OAK` is `ItemID.ACORN`, and nothing knew that an oak *sapling* was the same crop. Trees,
fruit trees, hardwoods, calquats, celastrus, redwoods, spirit trees and the crystal tree are
all planted as saplings — the seed has to spend time in a filled plant pot first — so anyone
actually stocked up for a tree run owns saplings, quite possibly no seeds at all, and saw an
empty list.

`Seed` now carries a second item id, generated the same way as the first: the rule is
`ItemID.PLANTPOT_<PRODUCE>_SAPLING`, with three overrides where the item name says "TREE" and
the produce constant does not (magic, celastrus, redwood). All 24 resolved; a wrong guess would
have failed the build rather than gone quiet.

**Owning and being able to plant are kept apart**, which is the part worth having:

- `getCount`/`getOwned` count seeds and saplings together — you do own the tree either way.
- `getPlantable`/`getOwnedPlantable` count only what can go in the ground.
- `Plantable.needsPotting()` is the case in between, and the tooltip says so outright: *"4
  seeds still need potting into a sapling"*. Greying the icon out and leaving the player to
  work out why would have been the easy version and a worse one.

The grid draws `getPlantedItemID()` — the sapling for a tree — because that is the item you
carry to the patch and look for in the bank.

Free because the seed cache was already keyed by **item id** rather than by `Seed`, so the
split cost two accessors and no change to what is stored.


---

## Harvest stats looked wrong — what the CSV actually said

Reported: limpwurt showing 23 items against 1 harvest, snape grass likewise, watermelon
missing entirely, jangerberry at 0 harvests but 7 items. Four separate causes, and reading
`harvests.csv` beside the stored `harvestStats` settled all of them without guessing.

**1. The bad numbers are pre-fix records.** Every row in the CSV predates the nearest-patch
attribution fix — snape grass 68 against a predicted 15, watermelon 110 against 11. Those are
the funnel bug, already fixed, but the rollup still holds them. **The history wants clearing**
(Settings → Maintenance → Clear harvest history); nothing else removes them, because the
rollup is by design a sum rather than a list.

**2. The funnel could still happen, and now cannot.** The fix compared the player's region id
to the patch's, and fell back to "prefer a record that is already open" — which is the funnel.
That fallback fired whenever the region did not match, which is not rare: some farming regions
span more than one map square and the plugin mirrors only the canonical id. Now the fallback is
a real coordinate distance from `PatchLocationStore`, and there is a **maximum**: past 64 tiles
nothing is credited at all. You have to stand at a patch to pick it, so a crop appearing in the
inventory anywhere else was bought, traded, or picked somewhere untracked — better to lose the
observation than invent one.

**3. Regrowing crops never completed a record.** Real bug, and the jangerberry line was the
symptom: 0 harvests, 7 items. A record closed only when the patch *emptied*, which never
happens to a bush, a fruit tree or a cactus — pick the last berry and it goes straight back to
growing. So every one of them sat open until the idle timer abandoned it and every berry ever
picked was filed as "left standing". Now a record closes when the patch stops being
**harvestable**, which is the same moment for a herb and the right moment for a bush. Two tests
cover it, including that picking part of a stock keeps the record open.

**4. Watermelon was in the CSV but not in the stats — and that one is not ours.** The row is
there, completed, 110 items, timestamped 15:16. The rollup has no watermelon at all. The CSV is
appended to disk immediately; `ConfigManager` batches and flushes on a timer or at shutdown. The
session ended in the freeze and was killed, so the last config write never reached disk. **The
CSV is the durable trail and the stats can lose the tail on a kill** — worth knowing before
treating a disagreement between them as a bug.

**Presentation, which is what made it look worse than it was.** The row showed lifetime items
next to an average over completed patches only, so limpwurt read as "1 harvest of 23". The
columns are now **crop / n / got / avg**, with the patch count on screen — 17 patches, 145
items, 8.3 average reconciles at a glance where 145 and 8.3 alone do not. The predicted average
moved to the tooltip, which also now says how many items came from patches left standing and
that they are excluded from the average.

**Not a bug, but worth watching:** every logged row so far reads `compost=NONE`. The stored
snapshots do have ULTRACOMPOST on 12 patches, so the mechanism works — the harvests that
happened to be logged were at Falador and the Farming Guild, where no compost was recorded.
If rows keep coming back untreated for patches you know you composted, that is a real lead.


---

## The herb sack was eating the harvest log

Reported during testing, and it explains the shape of the data outright: the CSV had limpwurt,
snape grass, jangerberry and watermelon rows and **not one herb**, despite herb patches being
harvested all along.

An open herb sack takes a grimy herb the instant it is picked. It never reaches the inventory,
so there is no delta to count, so no record ever opened. Fruit baskets and vegetable sacks
would do the same.

**There is no container and no varbit.** Checked both, and then checked what core RuneLite does
— its loot tracker resorts to a regex over chat spam for the herbiboar version of this
(`LootTrackerPlugin.processHerbiboarHerbSackLoot`). Core would not do that if a container or a
varbit existed, so that is about as settled as it gets.

**Two signals, one exact and one approximate.**

- *Approximate, working now:* count the experience. It arrives per pick at a published rate, so
  each drop matching that rate is one pick. Counted drop by drop rather than by dividing a
  total — division compounds error over a long harvest and silently absorbs experience that was
  never a pick.
- *Exact, not yet written:* the chat message. `HarvestLog.onChatMessage` currently **logs** any
  message mentioning a sack, basket or box while a harvest is in flight, so the pattern gets
  written from an observation rather than a guess. Same approach as the Geomancy vocabulary
  catalogue.

**The modifier trap, flagged during review and worth recording.** The published rate is not what
arrives: the Farmer's outfit adds up to 2.5%, so a ranarr's 30.5 turns up as 31.26 for anyone
wearing the set — most farmers. Matching the unboosted figure would have failed for exactly the
people this is for. Matched against `applyOutfit(harvestXp)` instead, with a tolerance of half a
point for the integer-versus-fractional mismatch (`StatChanged` reports a whole-number total, so
30.5 surfaces as alternating 30 and 31) plus 5% because the outfit bonus is modelled rather than
measured. Still tight enough to reject the neighbouring award — planting a potato pays 8 against
its 9 to pick.

Blast radius is small on purpose: the count is `max(seen, fromXp)`, so an ordinary harvest where
items land in the inventory is completely unchanged. The XP-mismatch warning is skipped for
inferred records, since predicting from the same constant the count was derived from would be an
assertion that cannot fail.


---

## Real bugs the crash log revealed — all three fixed

Worth more than the crash itself, because these were reproducible:

1. ~~**Limpwurt experience is badly wrong**~~ **fixed**. `predicted 1200.0 for 10 picks, saw
   91.0`. The scraped 120 is the award for **clearing the patch**, not a per-root rate — and
   the same is true of every flower. `CropYieldModel.xpHarvestsFor` now returns 1 for flowers
   and the yield for everything else, applied in both the harvest log and the run estimate.
   Herbs and allotments were unaffected, which is why they had been matching.
2. ~~**`HarvestRecord` asked `CropYield` directly**~~ **fixed** — goes through
   `CropYieldModel`, so flowers report a real prediction instead of `n/a`. That was the
   difference between collecting usable validation data for them and collecting none.
3. ~~**Watermelon x110 from one patch**~~ **fixed**. `findPatchHolding` preferred an
   already-open record, so every watermelon picked on a run funnelled into whichever allotment
   was harvested first — one record claimed 110 against a predicted 11. Now attributed to the
   **nearest** ripe patch, by region. Patches sharing a region stay ambiguous, but they are a
   few steps apart, so a wrong guess costs one data point instead of corrupting a total.
4. Watermelon XP (`predicted 5995.0, saw 6646.0`) fell out of (3) — it was ten patches' worth
   of experience compared against one patch's prediction.

**Still worth watching**: the observed limpwurt figure was 91, not the 120 the wiki gives. The
per-patch model is clearly right in shape, but the exact number may still be off, or that
harvest may have been partial ("left standing"). One clean full harvest settles it.


---

## Small open questions, closed

**Fruit tree experience — done, and the old note had the wrong dichotomy.** The single figure
on the patch/Seeds table is neither the check-health award nor a per-fruit rate: it is the
*whole cycle summed*, assuming six fruit picked. Both candidates the note weighed up were
wrong, which is why no amount of staring at that table settled it.

The three components are stated separately on each seed's own page, and they reconcile:
`plant + check + 6 x harvest` lands exactly on the published total for **six of the eight**.
That is real corroboration rather than a restatement, because the totals and the components
come from different pages. `CropXpTest` pins all six.

The two that disagree do so by under an experience point, and in the check figure both times —
pineapple's page says 4,605 where its total implies 4,605.7; papaya's says 6,146.6 where the
total implies 6,146.4. The seed pages win, being the ones that state components rather than
deriving them, and the gap is pinned by its own test so it stays visible. **A single clean
check-health XP drop settles either outright** — no averaging needed — and `HarvestLog` already
warns on a mismatch.

Nothing downstream needed changing: `RunEstimate.xpFor` and the row tooltip both go through
`CropXp.totalFor`, which has always handled plant + check + per-harvest generically.

**Empty-patch `_PIC` item ids — answered.** 6055 is `WEEDS`; **6512 is `BLANKOBJECT`**, a
generic placeholder rather than anything farming-specific. So Geomancy draws weeds on a patch
with weeds in it and a blank on one that is genuinely bare, which makes "the produce item is
6055 or 6512" a sound empty test — and explains why two different ids show up for what looked
like one state.

**The seed dropdown-versus-list question is moot.** The old note asked whether the seed list
should become a dropdown, and worried that a dropdown implies *picking* one. Seeds are now
click-to-select icons that mark what is in the run, which is the picking behaviour the note
wanted, and the compost dropdown beside them is where a dropdown genuinely fits. Nothing left
to decide.

**Still open, and genuinely blocked on data:** attas on the level-roll crops (limpwurt,
belladonna), whether cactus really uses harvest lives, and the coral/medallion-of-the-deep
outfit exception. All three need observations rather than reading — which is what the harvest
log is for.


---

## Guided mode — built, wants your eyes in-client

Highlighting deliberately copies **Quest Helper**, on your instruction, because it is the
vocabulary OSRS players already read: outline or click box on the target object, the same on
the tool leprechaun, and a filled outline on the inventory item to use. Same config shape too —
style, colour, outline thickness and feathering, with Quest Helper's own defaults (OUTLINE,
thickness 4, feathering 4). The highlight colour defaults to cyan rather than Quest Helper's,
so running both at once still tells you which plugin is asking for what.

**The design decision worth keeping: there is no step counter.** `GuidePlan.forPatch` is a pure
function of the patch's current state, asked afresh every render. The obvious alternative — an
index into a script — has to be kept in step with a player who does things out of order, walks
off mid-patch, or composts before being told to, and every one of those is a chance to insist
on something already done. Deriving it means the guidance can be a tick behind but never wrong
about what has happened.

The order per patch is the spec's: empty it out, then fill it back up.

| State | Instruction |
|---|---|
| harvestable, room in the pack | harvest |
| harvestable, pack full | note with the leprechaun **first** |
| dead | clear it |
| growing | *nothing at all* |
| empty, compost chosen and not applied | withdraw compost if you lack it, then apply |
| empty, treated, seeds in the box | empty the seed box |
| empty, treated, seeds in hand | plant |

Pieces: `GuideAction` (seven verbs), `GuideStep` (one action, one object, at most one item),
`GuidePlan` (the judgement), `GuideTracker` (which patch you are at), `GuideOverlay` (the
drawing), `CarriedItems` (inventory contents and free slots — `SeedInventoryStore` only knows
seeds, and this needs compost and slot counts).

The panel shows the same instruction in words above the stop list: an outline says *where* and
the text says *what*, and neither is much use alone.

**Two bugs found in the first play test, both fixed:**

- **The wrong object was highlighted, seemingly at random** — a guam plant in one place, a
  single watermelon in another, the patch itself in a third. A farming patch is drawn as
  several game objects that all key off the same varbit, and taking the first match meant
  taking whichever the tile scan reached first.

  **Fixed twice.** The first attempt scored the matches and took the largest, assuming a patch
  is one big object with decorative crops sitting on it. It is not: an allotment is a scatter
  of one-tile crop objects, each carrying the varbit, with no large object to prefer — so
  "largest wins" went on picking an arbitrary watermelon and the melon highlighting continued.
  Now **every** match is marked, which lights up the whole patch. That was the ask in the first
  place, and it needs no special case for the patches that genuinely are a single object.
  Deduplicated by object hash, since a multi-tile object is reachable from each tile it covers
  and would otherwise be outlined once per tile. Still not quite the ask — see the border note
  below.
- **The inventory item never appeared, though the leprechaun did.** Draw order, and not obvious:
  world outlines sit on `ABOVE_SCENE` so they never paint over an open bank, but the inventory
  is a *widget*, and anything on `ABOVE_SCENE` is drawn underneath it. The watermelon highlight
  was being painted and then covered by the inventory panel. One overlay cannot be on two
  layers, so the item highlight moved to its own `GuideInventoryOverlay` on `ABOVE_WIDGETS`.
  That matters most for the noting step, where seeing which item to use on the leprechaun is
  the whole instruction.
- **The leprechaun step fired a pick early**, at 27 of 28. The threshold kept one slot spare
  where it should have waited for a genuinely full pack — a wasted trip, one herb short.
- **An emptied patch stopped highlighting entirely.** The scan only looked at
  {@code tile.getGameObjects()}. A crop standing up is a `GameObject`, but bare soil is a
  **`GroundObject`** — so the moment the last melon was picked, there was nothing left for the
  scan to find. All four kinds are checked now (`GameObject`, `GroundObject`,
  `DecorativeObject`, `WallObject`); they share the `TileObject` interface so nothing
  downstream cares which is which. Third distinct cause behind "the highlighting is wrong",
  and the one a screenshot found rather than reasoning.
- **The leprechaun's store slot drew a stray bucket in its corner.** That interface is not an
  item list — each thing it holds has its own named widget (`FarmingTools.ULTRACOMPOST` and
  so on), so there is nothing to scan for an item id and the slot has to be looked up. Then
  the panels turned out to be several times the size of an inventory square, so painting a
  32px item sprite into one marked nothing useful. The slot is outlined and tinted instead,
  which is also the better answer: the whole panel is the click target.
- **The instruction did not follow where you stood**, which read as "highlighting stops after
  the first harvest". `distance()` compared *regions* — but every patch at a stop shares a
  region by construction, so they all tied and "nearest" was simply first-in-list. You could
  stand at the herb patch and be given the allotment's instruction. Now uses real coordinates
  from `PatchLocationStore`.


---

## Run strategy — the open question

Raised in testing, and correctly identified as the interesting problem: *"do you harvest
everything then lep, do you plant one after harvest, do you pick up compost before the next
patch, if you do you have less inventory space"*. Guided mode currently has **no strategy at
all** — it answers per patch, nearest first, and the ordering that falls out is incidental.

Three things were fixed straight away because they are not really strategy, just correctness:

- The leprechaun's compost slot **stayed lit after withdrawing**, because withdrawing and
  applying name the same bucket and the overlay chose where to draw from the item id alone.
  Where a step happens is now a property of the step (`GuideStep.isAtLeprechaun`).
- The withdrawal **names a quantity** — one per patch at this stop still wanting that tier,
  counted per tier because a stop can mix them.
- **Note before leaving a stop.** Harvested crops do not stack, so walking off with 23
  watermelons is 23 slots gone for the rest of the run. Appended after the patch work, so it
  becomes the current instruction only once there is nothing else to do here.

**Settled, and worth stating because it removes a whole option:** there is no reason to carry
compost between stops. Every farming area has its own leprechaun holding the same thousand
buckets, so a "pick up for the next location" step buys nothing and costs an inventory slot at
the exact moment the next harvest needs it. Withdraw at each stop, for that stop.

**The ordering question is answered, and the wiki answers it.** Rather than pick between
harvest-everything-first and finish-each-patch, both the *Farm run* article and the herb
money-making guide give the same shape:

> Clear the patch → compost → plant → *(return later)* → harvest → compost → plant new seed →
> "Use a herb on the nearby Tool Leprechaun to note them"

So: **finish each patch before moving to the next**, and **note once per location** rather than
per patch. That is what guided mode already did, and the note-before-leaving step above
completes it. No strategy engine needed — the per-patch answer was the right one.

The compost-timing question resolves too: **withdraw after harvesting, never before.** Buckets
carried through a harvest cost slots exactly when they are scarcest. The code does this because
the compost step only appears once a patch is clear, which is worth stating so nobody "fixes"
it later.

**And the wiki found a bug.** The money-making guide sows *then* composts — "plant a new Huasca
seed and use Ultracompost on the patch" — which the game allows and this plugin did not. The
compost step only existed while a patch was empty, so anyone following that order got silence
and an untreated patch, precisely because they did it the other way round. Now a patch in its
**first growth stage** with compost chosen and not applied still offers it. Limited to stage 0,
which lasts one growth tick, so it cannot become nagging about a crop planted an hour ago.

Two orderings, both valid, both supported — which is the point of deriving steps from state
rather than driving a script.

**Still not settled, and cheap to leave open:** whether to carry compost for several patches in
one withdrawal or fetch per patch. The instruction now names a quantity for the whole stop,
which suits one trip; someone who prefers a trip each simply ignores the number.

### The one exception to "never carry compost between stops" — not started

Reported from play: at **Trollheim** the leprechaun is about 15 tiles from the herb patch, far
enough that walking back for a bucket is a real cost rather than a step sideways. There, taking
compost *before* leaving the previous stop genuinely wins.

The general rule holds — every farming area has a leprechaun with the same thousand buckets, so
carrying compost onward is normally pure loss. This is an exception created by **distance**, not
by scarcity.

Worth noting that the fix does not have to be a hardcoded list, which is the tempting version
and the one that rots. The plugin already learns patch positions by watching game objects
(`PatchLocationCapture` → `PatchLocationStore`); leprechaun positions could be learned exactly
the same way, from the NPC. Then the rule computes itself:

> If the next stop's leprechaun is more than N tiles from its patches, add a "take compost with
> you" step before leaving this one.

That also picks up any other awkward patch without anyone having to notice it first — the lone
herb patches (Trollheim, Weiss, Harmony) are the obvious suspects, but nothing about the rule
depends on knowing which they are. Nothing to draw until a leprechaun has been seen, same as
patch positions, which is the usual trade.

Low priority: one patch, one extra walk, and only for people who do the Trollheim herb run.

### Highlighting: a tile fallback, because guessing the object model kept failing

Three separate wrong models of how a patch is drawn — scan order, then "one big object with
crops on it", then "GameObject only" — and an empty Ardougne allotment still would not mark.
So the marker no longer depends on getting that right: when the varbit scan finds nothing,
the patch's **learned tile** is outlined instead, from the position `PatchLocationCapture`
already records. Never as pretty as a model outline, never silently absent.

The diagnostic is still there and logs at INFO, so the next miss says which patch it was.
Worth knowing: plugin logs go to the **terminal**, not `~/.runelite/logs/client.log`, which is
why the first attempt to diagnose this from the log file found nothing at all.

**Maybe: outline the patch border rather than every crop in it.** Highlighting all the matching
objects is an improvement on one arbitrary melon, but it still traces each crop model — so an
allotment reads as a dozen lit watermelons rather than one lit patch. What was actually asked
for is the patch's *area*.

Not obvious how, which is why it is a maybe. The game gives no "patch" object to outline for an
allotment; the crops are what exist. Options, none tried:

- Take the union of the matching objects' tiles and stroke the outside edge of that region.
  Closest to the intent, and wrong whenever a patch is only partly planted — the border would
  shrink to fit the surviving crops.
- Store the patch's tile extent alongside its position in `PatchLocationStore`, learned the
  same way positions are, and draw that. Correct regardless of contents, at the cost of a
  second thing to learn per patch and nothing to draw until it has been.
- Fill the tiles rather than outlining them, which sidesteps the border problem entirely and
  looks less like Quest Helper.

**Not done, and next if this proves out:**
- **The stop is not sequenced.** Patches at a stop are ordered nearest-first, which is honest
  but still arbitrary where the plugin has never learned a patch's position and falls back to
  the region centre.
- **No arrow or navigation line.** Quest Helper has both, and both are toggleable there.
- **No menu swap for the seed box.** The spec asks for Empty as left-click; the step names it
  instead, which is honest but slower. A menu swap is also the first thing here that would
  modify input rather than describe it, so it wants deciding on rather than assuming.
- Completion is inferred from patch state, so a step lingers for the tick between clicking and
  the varbit arriving.


---

## Bank loadout and filtering — highlighting built, tag tab not started

When the bank is open, show and filter to what this run actually needs. `BankTagsService` is
public API in 1.12.34.1 (`openBankTag(String, int)`, with `OPTION_HIDE_TAG_NAME` and
`OPTION_NO_LAYOUT`), so Quest Helper's approach is available rather than needing reinvention.

### The design point that matters

The earlier note rejected a static region-to-teleport table as *"exactly the kind of
hand-maintained data the rest of this plugin has avoided, and it would be wrong for anyone whose
unlocks differ."* That objection still stands — but it only applies to the **prescriptive**
form. Invert it:

- A table of **item → where it teleports** is a fact about the item. It does not vary by player
  and cannot be wrong about them.
- Intersect it with the run's destinations **and with what is actually in their bank**.
- Result: *"you own an Ardougne cloak 2 and this run visits Ardougne"* — personal, correct, and
  it never tells an ironman to go and buy something.

That is the same trick already used for secateurs, cape and outfit: **detect, do not
prescribe**. The bank container is already read on every open (`SeedInventoryStore`), it just
discards everything that is not a seed, so a `BankContents` alongside `CarriedItems` is cheap.

### Most of the list already exists

| Item group | Where it already comes from |
|---|---|
| Seeds for the run | `SeedSelectionStore` |
| Compost per patch type | `CompostSelectionStore` |
| **Protection payments** | `ProtectionPayment` — generated, and the biggest thing missing from the brainstorm |
| Secateurs, cape, outfit | `FarmingBonusStore` already detects all three |
| Where the run goes | `RunPlanner.previewStops` |

Genuinely new data: a teleport table and a short list of storage items. That is all.

### What the brainstorm missed

- **Protection payments.** Every tree, fruit tree and hardwood patch wants one, the data is
  already generated, and noted payments cost one slot per type. The single biggest omission.
- **The Farmer's outfit**, four pieces, +2.5% experience. The cape was listed; the outfit was
  not, and it is already detected.
- **Produce storage beyond the herb sack** — fruit basket (5 of each fruit), vegetable sack
  (10 potatoes/onions/cabbages). These matter *more* than the herb sack for their runs, because
  `InventoryPlan` already records that the leprechaun **cannot note** fruit baskets, vegetable
  sacks, Falador cabbages or logs.
- **Herb sack specifics**, worth surfacing rather than assuming: 30 of each grimy herb (450
  total), 58 Herblore *unboostable*, 750 Slayer points or 250 Tithe points. Silklined upgrade
  holds 100 each. So it is not something every account can be told to bring.
- **Spellbook consumables**: Fertile Soil runes (Lunar), Cure Plant (Lunar, 66 Magic),
  Resurrect Crops (Arceuus, 78 Magic). The disease work already flagged that these imply a
  spellbook swap the gathering phase would have to plan for.
- **Stamina potions**, for the walking between patches.
- More teleports than listed — see the table below.

### And the inverse, which is the more useful half

**The tool leprechaun stores far more than a "bring everything" list would assume**, so the
interesting output is as much what to *leave* as what to take:

- 100 rakes, spades, seed dibbers, trowels
- 100 secateurs **or magic secateurs** — it stores those specifically
- 1 watering can or Gricoller's can, 1 bottomless compost bucket
- 1,000 buckets each of compost, supercompost and ultracompost
- **1,000 plant cures** — so the cure for a diseased patch is already on site
- Noted versions count, space permitting

So compost and every tool are on-site items, not bank items. `InventoryPlan` already models
this as `usesLeprechaunStorage`. A loadout that told you to withdraw ultracompost you have a
thousand of at the patch would be actively worse than saying nothing.

The one nuance: magic secateurs only give their +10% while **carried or worn**, so they are
still worth taking even though the leprechaun holds them — the storage is a safety net, not a
substitute.

### Teleports, from the wiki's own per-patch recommendations

Confirmed for herb patches:

| Patch | Item / spell |
|---|---|
| Ardougne | Ardougne cloak 2+ |
| Catherby | Catherby Teleport |
| Falador | Explorer's ring 2+ |
| Farming Guild | Farming cape (doubles as the +5% herb bonus) |
| Harmony | Harmony Island Teleport |
| Hosidius | Xeric's talisman → Xeric's Glade |
| Troll Stronghold | Stony basalt |
| Weiss | Icy basalt |
| Civitas illa Fortis | Civitas illa Fortis Teleport |

Not yet verified per patch, and needed for the non-herb runs: skills necklace (Farming Guild),
ectophial (Morytania), digsite pendant (Fossil Island), teleport crystal (Prifddinas/Lletya),
royal seed pod (Gnome Stronghold), spirit tree network, fairy rings plus a dramen or lunar
staff, and house teleport tabs for a jewellery box or portal nexus.

### Built

1. `TeleportItems` — item id → region served. Facts about items, not advice.
2. `BankContents` — everything in the bank, off the container event already being handled.
   Not persisted, deliberately: seed counts are cached because the panel shows them when you
   are nowhere near a bank, but this only matters while you are standing at one, and a
   remembered bank is a bank that can be wrong.
3. `RunLoadout` — folds the six existing sources plus the two new tables into one list, each
   entry marked `WITHDRAW` / `HAVE` / `AT_LEPRECHAUN` / `MISSING`.
4. `BankHighlightOverlay` — the same filled-outline treatment guided mode uses on inventory
   items, in **two colours that mean opposite things**. Cyan says take this; amber says the
   leprechaun already has it, so leave it and ask when you reach the patch. Marking the
   leprechaun's items in the withdraw colour would have you banking compost you have a thousand
   of on site; leaving them unmarked reads as the plugin having forgotten about compost. Hover
   explains which is which, because a second colour with no explanation is a puzzle.
   Anything already carried stays unmarked — a busy bank tells you less than a sparse one.
5. **The best axe you can actually swing**, for runs with anything that needs chopping. A grown
   tree has to be cut and its stump dug before the patch can be replanted, and the leprechaun
   stores every farming tool *except* this one. Level-gated as well as tier-ordered: a dragon
   axe from a drop is dead weight at 30 Woodcutting. Woodcutting level is cached the same way
   the Farming one is. When nothing usable is owned it says so outright, because turning up to
   a tree patch without an axe means the trip achieves nothing there.

   **All ten felling axes are in**, and the naming is the trap worth recording: RuneLite calls
   them `*_AXE_2H` with no mention of "felling" anywhere, so they read as some separate
   two-handed family. `BRONZE_AXE_2H` is item 28196, which the wiki confirms is the bronze
   felling axe. They were in the table under the wrong names while the class comment claimed
   they had been excluded — wrong twice over. `AxesTest` pins the family by literal id, so a
   renamed constant fails rather than quietly dropping one.

   Also present: infernal (charged and not — an empty one still cuts at dragon speed), 3rd age
   in both forms, gilded, and both Trailblazer axes. Left out: noted forms and uncharged
   crystal axes, neither of which can be swung.
6. The run summary names what is left to withdraw, and separately anything **missing
   everywhere** — that last one matters because an item you own none of cannot be highlighted,
   so silence would read as "nothing else needed".

Config: *Highlight run items in the bank*, on by default, in the Guided run section.

`RunLoadoutTest` covers what it declines to say as much as what it says — compost reported as
on-site rather than as a withdrawal, teleports you do not own never suggested, a teleport for
somewhere this run does not go never suggested, and storage only offered when you have it.

### Still to do

- **The `doogle-maps` bank tag** via `BankTagsService.openBankTag`, so the bank can be
  *filtered* rather than only marked. Left until the item list proves right in play — a filter
  built on a wrong list hides things you need, where a wrong highlight is merely ignorable.
- **The teleport table is deliberately short.** Herb patches are covered from the wiki's own
  per-patch recommendations; the other families have only the obvious entries. Missing ones
  cost a suggestion, wrong ones send someone across the map, so it grows by verification
  rather than by memory.
- Fruit basket and vegetable sack are matched on the **empty** item id only. A part-filled
  basket is a different id, so someone holding a basket with two apples in it reads as not
  having one.
- No inventory-space arithmetic in the loadout. `InventoryPlan` already does that for the run
  summary; the two want joining up so the loadout can say "this will not fit".

All display-only, so none of it touches the read-only rule.


---

## Routing — landed, untested in-client

Shortest Path turned out to have a documented `PluginMessage` API, richer than the spec
assumed. Notably it takes a *set* of targets and routes to the cheapest reachable one, so
the plugin does no route ordering at all — it posts the outstanding stops and lets the
router choose. It also posts back which transports a path uses, and it already models
banking for teleport items itself (`includeBankPath`, which we switch on per request).

- Stops are regions, so allotment/flower/herb and Gnome Stronghold's tree + fruit tree
  group for free — 15 of 43 regions hold more than one patch type
- Patch positions: learned from game objects, seeded from 31 wiki coordinates, falling back
  to region centre. All 31 verified by region id against RuneLite's data
- Banks: 49 unrestricted ones seeded from Shortest Path's data, plus every bank you open
- Runs cover actionable patches only (ready, empty, diseased, dead), bank leg first
- `InventoryPlan` counts item *types* at both ends; the return leg is what binds

Still to do: the seed choice per run is "best usable seed we can see" rather than a saved
loadout (§13.4), and there is no highlighting of what to click at each stop (§13.7).

### Start run sent you to a bank while you stood on the work

Reported from play: standing at the Ardougne patches with dead limpwurt, dead and ripe
watermelons and part-picked guams, carrying everything needed, Start run asked for a teleport
to a bank. Two causes.

- **The supply check ignored what the run covered.** `getSupplySources()` walked *every*
  selected seed, so one bush seed picked at some point could send a herb run to the bank for
  something the trip never touches. Now scoped to the run's patch types, and to types that
  actually have an actionable patch.
- **Nothing considered where you were standing.** Now `start()` checks whether you are already
  in a stop with outstanding work, and if so the run begins there. The trip is not cancelled —
  `supplyOwed` remembers it, and the bank leg is entered when that stop finishes. Being routed
  away from crops you are stood next to is wrong however much needs collecting later.

Deliberately kept: with **no** seeds picked for the run, a bank is still the opening leg. We
cannot know what the trip needs, so that is the useful default — and it no longer fires while
you are stood on work, which is what made it objectionable.

**The first version of that threw on the Swing thread.** `Player.getWorldLocation()` asserts it
is on the client thread — and the assertion is on the *Player*, not on `getLocalPlayer()`, so
holding a player reference proves nothing about where you may ask it questions. Pressing Start
run from the sidebar hit it immediately.

Worth recording *why* it was written that way: the reasoning was "GuideTracker already does
this, so it must be fine". It was not fine — `GuideTracker` had the same latent fault and would
have thrown the moment guided mode refreshed from the panel. **Inferring safety from existing
code is inferring it from an untested assumption.**

Both now read `PlayerLocation`, which samples the position once a tick on the client thread and
hands it out to anyone. A tick-old tile is no worse than a fresh one for deciding which farming
region you are stood in, and it removes the whole "which thread am I on" question from every
caller.

Audited the rest afterwards rather than assuming: every other client read reachable from the
panel — the diary varbits, both skill levels, `relearnFromClient` — is already inside
`clientThread.invokeLater`.

**And the route flickered on top of that.** `retarget()` handed the router every remaining stop
including the one underfoot, so Shortest Path drew a path to the player's own feet for a moment
before working out it had arrived. The subtlety is that an empty target list no longer means the
run is over — when the only stop left is the one you are standing in there is nothing to draw
but the run is still very much running, so it clears the path without clearing `active`.

**Then it turned out excluding the current stop was not enough**, because a route to the *next*
stop kept a teleport-and-bank instruction on screen the entire time the player worked the patch
in front of them. Now **nothing is routed at all while there is work where you stand** — the
path returns once the stop is finished. That matches the wiki's shape (finish a location, then
travel) and stops the router competing with the guide for the player's attention. A test
asserting the opposite was replaced; it had encoded an assumption rather than a requirement.

### Fixed after the first real run

- ~~Gather step routed to a bank for seeds that were only in the seed vault~~ **fixed**. Two
  causes. `getSupplySources` asked "is this seed in the bank *at all*", so a single stray seed
  outranked a full patch's worth in the vault; it now compares against what a patch actually
  takes. And `getSupplyTargets` offered the vault *alongside* every bank — but Shortest Path
  routes to the cheapest reachable member of a target set, so the nearest bank always won and
  the vault was never visited. The vault now wins outright whenever it is needed, which costs
  nothing when the bank is needed too: the Farming Guild has a bank chest beside the vault, and
  anyone with seeds in the vault necessarily has guild access. The panel already claimed this
  ("the Farming Guild has both") — the routing just did not do it. Covered by
  `SupplyRoutingTest`.
- ~~"Start run" patch-type checkboxes were not remembered~~ **fixed**. They were pure Swing
  state. Now persisted per profile in `RunTypeStore`, and deliberately **not** cleared by a
  profile reset, on the same footing as the seed selection and the shown/hidden patch toggles —
  it is a choice about how you farm, not cached data.

### Partly fixed: the run now names every destination up front

~~It only says what is next, and only once you reach the bank.~~ The run panel now has a
**Show destinations (n)** section, listing every stop and its patch count before you set off,
with the total on the button so the size of the trip is visible without expanding it. That is
option three of the three below — the cheap, honest one — and it hands over the thing you
cannot work out for yourself while leaving the teleport choice, which you know better than we
do, alone.

`RunPlanner.previewStops` is the same computation `start` does, sharing a private
`planStops`, so what the panel promises cannot drift from what the run turns out to be. The
test compares the two rather than a fixed list, which is the only version of that assertion
that would catch a filter changing on one side only. Sorted by name and labelled "visited
cheapest-first, so this is not the order" — numbering them would imply a tour that does not
exist.

**Still not done: naming the teleport items.** That remains a real limitation of how the
transports are obtained rather than an oversight.

Shortest Path reports the transports for **the path it is currently drawing** — one leg. The
plugin hands it the whole outstanding stop set and lets it choose the cheapest next one, which
is what removes the need for any tour ordering, but it also means no future leg has been
costed yet, so there is nothing to report about it.

Options, none free:
- Ask Shortest Path to path to each remaining stop individually up front and collect the
  transports. Accurate, but N extra requests that would fight with the displayed path.
- Keep a static region-to-teleport table. No extra requests, but it is exactly the kind of
  hand-maintained data the rest of this plugin has avoided, and it would be wrong for anyone
  whose unlocks differ.
- ~~Show the run's **destinations** up front without naming items, leaving the teleport choice
  to the player. Cheap and honest, and probably the right first step.~~ **done, see above.**


---

## Reflection audit (Hub review)

The Plugin Hub review page lists reflection among the things it restricts, so worth knowing
where we stand. Audited 2026-08-04:

- **No `setAccessible` anywhere in `src/main`.** The only uses are in tests, which are not
  submitted.
- The many `java.lang.reflect.Type` imports are Gson `TypeToken`s, not reflection.
- Two genuine uses remain, both enumerating public members of public classes:
  - `PatchTabs.visibilityKeys()` reads `@ConfigItem` off our own config interface, so the
    patch-type toggles cannot be listed twice and drift apart.
  - `GeomancyProbe.componentNames()` enumerates `InterfaceID.FarmingView`'s 329 constants.
    Changed from `Class.forName("...")` to a class literal — a string lookup would have failed
    *silently* if RuneLite moved the class, and it is the shape of reflection that attracts
    questions at review for no benefit.

Neither reads a private member or reaches into the client. If review objects anyway, both have
an easy out: hand-written lists, at the cost of a second place to forget.


---

## Crowdsourcing the yield answers (post-Hub idea)

The open yield questions are all empirical, and `HarvestLog` already records exactly the
observations that would settle them. One player generates a trickle; a Hub audience generates
enough to answer them outright.

**What the data would actually settle**, in rough order of value:

1. **Cactus.** The 2018 newspost's two points (75% chance to use a life at 55, 30% at 99) do
   not fit the standard CTS curve. A few hundred harvests at spread levels would either
   recover a low/high pair or show it uses a different rule. Same for potato cactus, where the
   wiki's own figures are already given as a range.
2. **Celastrus**, which has no published constants at all — only "8-10 bark".
3. **Attas on the level-roll crops.** Limpwurt computes to ~7.45 against a measured ~8; if the
   gap really is attas, harvests split by whether an anima patch was growing would show it.
4. **Confirming the herb and allotment curves.** Cheap to check and the thing everything else
   is calibrated against.
5. **Fruit tree experience**, still absent from `CropXp` because the wiki gives one unlabelled
   figure. A single check-health XP drop observed cleanly answers it — no aggregation needed,
   just one honest observation.

**What a submission would need to contain**, and nothing else: crop, farming level, compost
tier, the bonus flags, patch type, items harvested, XP gained. No account name, no login,
no position, no timestamps finer than a date. That is already the shape of `CropHarvestStats`,
which is a point in favour of the rollup format — it is aggregate by construction.

**Constraints to settle before building any of it:**

- The Plugin Hub review page documents no explicit telemetry policy — it covers malicious
  code, reflection, native code and dependencies. Absence of a rule is not permission, so this
  needs asking about directly rather than assumed. Note that RuneLite core ships its own
  `crowdsourcing` plugin, so the concept is not foreign to the project; worth reading how that
  one asks and what it sends.
- **Opt-in, off by default, and obvious.** Anything else is indefensible regardless of what the
  rules technically allow.
- Needs somewhere to send it and someone to run that, which is a maintenance commitment rather
  than a feature.

**Worth doing first, and free:** the local CSV already accumulates. Playing normally for a few
weeks produces a dataset that answers (4) and probably (5) single-handed, and it costs nothing
to look at what one account's data settles before building anything that talks to a server.


---

## Original spec (§13.7), kept for the wording

The shape the run should take, per patch area. This is the feature the routing exists to serve;
everything so far is the planning half.

Per stop, in order:

1. Teleport to the spot (already routed).
2. **Highlight the patch to harvest.** Harvest until the inventory fills.
3. **Highlight the crop in the inventory**, then **highlight the leprechaun** to note it.
4. If the patch still has produce, highlight it again and repeat from (2).
5. When it is time to plant:
   - **Highlight the chosen compost and the patch** to apply it to.
   - **Highlight the seed and the patch.**
6. Where the compost or seeds are in the seed box or with the leprechaun, highlight those to
   withdraw first — seed box wants a menu swap so **Empty is left-click** — then the leprechaun
   to exchange, then the compost to withdraw.
7. Only when the whole area is finished, teleport to the next stop.

Notes worth keeping:

- **The allotment/flower/herb/compost cluster is one place.** They are always adjacent, so the
  loop runs across all of them before moving on. The lone herb patches (Trollheim, Weiss,
  Harmony) are the exception and are a stop on their own — which the region-as-stop model
  already gets right.
- Highlighting only. The plugin never clicks: this is quest-helper-style guidance, and the
  read-only rule in the spec is not negotiable.
- The **order within a stop matters** and is not currently modelled at all. `RunStop` holds a
  set of patches; this needs a sequence, plus a notion of what the player is expected to do
  next and how to tell when they have done it.
- Detecting "harvest until full" needs the inventory watch that `HarvestLog` already has.
  Detecting "noted at the leprechaun" is the same signal in reverse. Both exist; neither is
  wired to anything that instructs.


---
