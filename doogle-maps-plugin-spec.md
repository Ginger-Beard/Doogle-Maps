# Doogle Maps — RuneLite Plugin Spec

*(Plugin Hub name: **Doogle Maps** — a play on the OSRS "doogle leaves" item. The name is
a meme, so the Hub **description** must do the discoverability work: lead with what it is,
e.g. "Farming overview + guided farm-run helper — see every patch, plan your run, and get
walked through it." Ships the punny name, stays findable under "farming".)*

An all-in-one RuneLite Plugin Hub farming plugin. It caches the state of every farming
patch as you interact with it — plant, compost, protect, harvest, check-health — and
presents it in a Geomancy-style sidebar, so you always have a full overview of your farm
without visiting each patch. For players with the Lunar spell **Geomancy** (65 Magic) it
also captures a full bulk snapshot on cast; but the plugin is fully useful *without*
Geomancy — that's just icing that fills in everything at once (yields, compost, timers,
what's planted) instead of patch-by-patch.

**End goal — the experience we're building toward.** Log in at any random moment, cast
Geomancy, and immediately drop into a Quest-Helper-style guided farm run: the bank filters
to exactly the items you need, if you're missing something you get on-screen directions to
the nearest bank, and then at every patch the plugin highlights the next thing to click —
the patch, the compost bucket, the seeds, the farmer for payment, the leprechaun. Full
lazy mode: no thinking, just click → click → click, then sign off until the next round.
Every feature in this spec exists to serve that loop; §13.7 is where it all comes together.
The hard compliance line (§11): **the plugin highlights and instructs, the human clicks** —
it never automates input. That's exactly how Quest Helper (the most-installed Hub plugin)
works, so the pattern is proven; crossing into auto-clicking/auto-walking would get it
removed.

> This is a design/implementation spec written to be fed into an agent (e.g. Claude
> Code). Anything marked **TODO(verify)** needs to be confirmed against the current
> RuneLite API/source before coding — do not trust hardcoded IDs from memory.

---

## 0. Design decisions (locked)

Settled with the owner — treat as constraints, don't re-open:

1. **Works identically for ironman and main.** Never suggest buying anything on the GE,
   anywhere. All "get this" guidance is drops/shops/crafting/leprechaun-storage only.
2. **MVP = the Geomancy-style UI backed by our own patch-state cache**, populated by
   *interactions* (plant/compost/protect/harvest/check-health), not by Geomancy. Geomancy
   access is optional bulk-refresh, not a requirement. Everything else is built on top.
3. **Capture is sequential and mirrors Geomancy's own state machine:** harvest → empty;
   compost → empty + composted; plant → planted + composted; protect → + protected; then
   growth stages. Each interaction updates the cached state the same way the Geomancy
   interface would show it.
4. **Loadouts are per patch *type*** ("all herb patches → ranarr"), not per location.
   Per-location is a possible later refinement, deprioritised (people plant for XP or a
   specific resource across all patches, not 1–2 at a time).
5. **UI: reuse the real Geomancy interface if the Plugin Hub allows; else recreate from
   the same game assets; else mimic** a sidebar pull-out with the same presentation (see
   §9 for the exact layout the owner specified).
6. **Offline progression is accounted for** — patches grow while logged out; recompute
   state from elapsed wall-clock against the global tick schedule on login/reopen. Want an
   in-game hoverable icon showing which patches are ready. Timer colouring TBD.
7. **Routing:** phase-1 uses the wiki's recommended run orders. Shortest Path is a **soft**
   dependency; fallback is a "show on map" button that opens the world map pinned to the
   patch.
8. **Multi-account: nothing to build** — RuneLite's per-profile config handles it.
9. **This replaces** the farming role of Time Tracking / Lazy Farming — it's the one
   all-in-one farming plugin, not a companion to them.
10. **Name: "Doogle Maps"** (Plugin Hub display name) — a meme play on the OSRS doogle
    leaves item. No collision with Speaax's Farming-Helper/Lazy Farming. Because the name
    doesn't say "farming," the Hub **description** and tags must carry discoverability:
    lead with "farming overview / guided farm-run helper" so it surfaces in a "farming"
    search. Config group / package can be `dooglemaps`.
11. **Availability is a global invariant, driven by manual per-patch toggles.** Each patch
    has an on/off toggle in the §9 side panel; the player enables the ones they can use
    (persists per profile — a one-time setup). Auto-detection of unlocks is an **optional
    later convenience** that pre-fills those toggles, never a requirement. Either way, no
    feature (overview, plantable, gather, routing, guided mode) may surface, plant into,
    route to, or highlight a patch that's toggled off — e.g. leave Weiss off and nothing
    ever routes or highlights it. See §13.5.

---

## 1. Problem / goal

Keeping track of a big farm is tedious: you have 20+ patches across the map, and short of
running to each one (or casting Geomancy, which needs 65 Magic on the Lunar spellbook) you
can't see their state at a glance. Even mid-run you lose track of which patches you've
already serviced.

**Goal:** maintain a live, cached overview of every farming patch — what's planted, its
compost/protection, growth stage and estimated time left, whether it's ready/diseased/dead
— updated automatically as you interact with patches, and presented in the familiar
Geomancy layout. Geomancy, if the player has it, bulk-fills that overview in one cast; if
not, it fills in as they visit patches, exactly like RuneLite's core Time Tracking but with
a richer, Geomancy-style presentation and everything in §13 layered on top.

The closest existing plugins: **Lazy Farming** (Speaax's Farming-Helper) is a route/planting
*guide*; RuneLite's core **Time Tracking** estimates state from the last time you were near
each patch but presents it as a plain timer list. Nothing combines the full Geomancy-style
overview, interaction-driven caching, loadouts, gather lists, and routing in one plugin.

---

## 2. Feature scope

### MVP — the Geomancy-style overview, cached from interactions
- **Patch-state cache** covering the full sequence (empty → composted → planted →
  protected → growing stages → ready/diseased/dead), updated from near-patch varbit
  changes and interactions (§4/§7). Works with no Geomancy access.
- **Geomancy-style sidebar UI** (§9): patch-type tabs, per-location rows with plant icon,
  protection shield, compost-bucket icon, and a staged progress bar.
- **Geomancy bulk-refresh (optional):** on cast, capture every patch at once to fill/
  correct the cache — pure bonus for Lunar users.
- **Persistence** across restarts (RuneLite per-profile config).

### On top of the MVP
- Estimated time-remaining on the progress bar (hover), with offline catch-up (§5/§6).
- Confidence tiers / disease handling (§6); in-game "ready" hover icon.
- Everything in §13: seed cache, plantable filter, loadouts + gather list, availability
  filtering, route planning.

### Explicit non-goals
- No automation of clicks/movement (Plugin Hub rules — read-only + UI only).
- Not predicting disease/death; timers are estimates that self-correct on visit/recast (§6).
- No "buy on the GE" guidance anywhere (decision 1).

---

## 3. Architecture

Standard RuneLite Plugin Hub layout (Gradle, `net.runelite.client.plugins.*` style).

| Component | Responsibility |
|---|---|
| `DoogleMapsPlugin` | Wiring, event subscriptions, lifecycle. |
| `DoogleMapsConfig` | Toggles: show timers, ready-icon, include tools/compost in gather list, overlay/UI options, etc. |
| `PatchState` (model) | One patch: id, display name, crop, stage, total stages, protected?, compost tier, status enum, `lastSeen` timestamp. |
| `PatchStateStore` | The cached state of every patch (the "SnapshotStore" of earlier sections); save/load via `ConfigManager` (per-profile); JSON (Gson). |
| `PatchInteractionTracker` | **Primary capture.** Watches per-patch varbits + menu interactions near a patch (plant/compost/protect/harvest/check-health) and updates `PatchStateStore` sequentially (§4/§7). |
| `GeomancyCapture` | **Optional bulk refresh.** On Geomancy cast, reads the interface and fills/corrects every patch at once (§4). |
| `GrowthTimer` | Projects "ready at" times from crop data onto the global growth-tick grid, incl. offline catch-up. |
| `DoogleMapsPanel` | `PluginPanel` — the Geomancy-style sidebar UI (§9). |
| `ReadyOverlay` | In-game hoverable "patches ready" icon/infobox (decision 6). |

State model per patch (no `done` flag — the panel reflects live state, decision 3; a
per-run "serviced" marker lives on the loadout/run, §13):

```
status ∈ { EMPTY, COMPOSTED_EMPTY, GROWING, READY_TO_HARVEST, DISEASED, DEAD, ... }
protected : boolean       // farmer/immune protection active
compost   : { NONE, COMPOST, SUPER, ULTRA }
lastSeen  : instant       // when this patch's state was last confirmed (interaction or cast)
```

---

## 4. Capturing patch state

Two sources feed `PatchStateStore`. The **primary** one works for everyone; the Geomancy
one is a bonus for Lunar users (decision 2).

### 4a. Interaction capture (primary — no Geomancy needed)
Whenever the player is at a patch, the client has that patch's real state, and it changes
as they act on it. Track it via `onVarbitChanged` on the per-patch state varbits, plus
`onMenuOptionClicked` for the action that caused it, and update the cache **sequentially**
to match how Geomancy would show it (decision 3): harvest → `EMPTY`; add compost →
`COMPOSTED_EMPTY` + compost tier; plant → `GROWING` stage 1 (+ compost carried); pay
farmer → `protected = true`; then stage advances over time. Also capture the passive
"check health" / walking-past reads so simply visiting a patch refreshes it. Stamp
`lastSeen` on every update, persist.

- **TODO(verify):** per-patch state varbits + compost/protection varbits (from RuneLite's
  `timetracking.farming` `FarmingPatch`/`FarmingRegion`/`CropState`/`PatchImplementation`).
- This is the same signal §7 uses; §7 and §4a are one tracker.

### 4b. Geomancy bulk refresh (optional bonus)
For players with Geomancy, one cast fills/corrects **every** patch at once. The game sends
all patch data to render the interface, so scrape the widget:
- Subscribe to `onWidgetLoaded` / `onScriptPostFired` for the Geomancy interface group.
  **TODO(verify):** Geomancy widget group id + build script id.
- Walk the child widgets (crop, growth text, diseased/dead/grown, protected/compost) into
  `PatchState` objects; map each row to the same internal patch id used in 4a.
- Merge into `PatchStateStore` (Geomancy is authoritative at cast time), stamp `lastSeen`,
  persist, refresh the panel.

Because capture is continuous, there's no "reset on cast" concern — every source just
writes the latest known state per patch, newest `lastSeen` wins.

---

## 5. Growth data + timers

Timers derive from: **current stage (from snapshot)** + **stages remaining × stage
interval**, projected onto the global growth-tick grid.

### Global tick grid (important)
Crops do **not** grow on a per-plant countdown. They advance only when a global growth
tick lands inside the crop's stage window; ticks occur ~every 5 minutes (500 game
ticks). So the first stage after planting is usually shorter than the nominal interval,
and completion times align to the shared grid rather than to plant time. Each cycle
length has a fixed base schedule (GMT), e.g. 20-min crops tick at :00/:20/:40, 40-min
crops at :00/:40/1:20/…, and every 640 minutes all cycle lengths line up and tick
together.

**Per-player offset:** the schedule is shifted by a fixed per-account offset of up to 30
minutes, always *negative* (ticks happen up to 30 min earlier than the base table), and
it persists across logins. To timer accurately you must derive this offset per user
(observe when a known slow crop actually advances and diff against the base schedule),
then apply it to all projections. RuneLite's time-tracking code already computes the
farming tick — reuse it rather than reimplementing the clock. **TODO(verify):** confirm
how to read the current farming-tick offset from the API.

Implication: you don't need plant time. From the snapshot you know current stage `N` of
total `S`; remaining time ≈ `(S − N) × stageInterval`, snapped to the next qualifying
(offset-adjusted) growth tick(s). Growth times in the table below are **minimums** —
disease repeats a cycle, so a diseased/rechecked crop can exceed them.

**Edge case:** the yew tree's first growth stage is 5 minutes and does not follow normal
40-min timing; special-case it or accept a small error on freshly-planted yews.

### Per-crop data table

`stages` = number of growth ticks from planting to fully grown. `interval` = minutes per
growth tick. `total = stages × interval` = **minimum** grow time (disease repeats a
cycle and adds time). Sourced from the OSRS Wiki (Farming / Seeds / Special patches),
current as of the fetch. Cross-check against RuneLite core's
`net.runelite.client.plugins.timetracking.farming.PatchImplementation` when wiring up —
that enum ties these numbers to the actual crop varbit values you'll read from the
snapshot.

**Allotment** (10-min ticks):

| Crop | Stages | Interval | Total |
|---|---|---|---|
| Potato | 4 | 10 | 40m |
| Onion | 4 | 10 | 40m |
| Cabbage | 4 | 10 | 40m |
| Tomato | 4 | 10 | 40m |
| Sweetcorn | 6 | 10 | 60m |
| Strawberry | 6 | 10 | 60m |
| Snape grass | 7 | 10 | 70m |
| Watermelon | 8 | 10 | 80m |

**Flower** (5-min ticks) — all 4 stages / 20m total: Marigold, Rosemary, Nasturtium,
Woad, Limpwurt, White lily.

**Herb** (20-min ticks) — **all herbs are 4 stages / 80m total**, identical regardless
of type (Guam → Torstol, incl. Huasca, Gout tuber/goutweed).

**Hops** (mixed intervals — note the exceptions):

| Crop | Stages | Interval | Total |
|---|---|---|---|
| Barley | 4 | 10 | 40m |
| Hammerstone | 4 | 10 | 40m |
| Asgarnian | 5 | 10 | 50m |
| Jute | 5 | 10 | 50m |
| Yanillian | 6 | 10 | 60m |
| Flax | 3 | 20 | 60m |
| Krandorian | 7 | 10 | 70m |
| Wildblood | 8 | 10 | 80m |
| Hemp | 4 | 20 | 80m |
| Cotton | 5 | 20 | 100m |

**Bush** (20-min ticks):

| Crop | Stages | Interval | Total |
|---|---|---|---|
| Redberry | 5 | 20 | 100m |
| Cadavaberry | 6 | 20 | 120m |
| Dwellberry | 7 | 20 | 140m |
| Jangerberry | 8 | 20 | 160m |
| Whiteberry | 8 | 20 | 160m |
| Poison ivy | 8 | 20 | 160m (**immune to disease**) |

**Tree** (40-min ticks; sapling from seedling is a separate 5-min-tick step):

| Crop | Stages | Interval | Total |
|---|---|---|---|
| Oak | 4 | 40 | 2h40m |
| Willow | 6 | 40 | 4h |
| Maple | 8 | 40 | 5h20m |
| Yew | 10 | 40 | 6h40m (first stage 5m — see edge case) |
| Magic | 12 | 40 | 8h |

**Fruit tree** (160-min ticks) — **all 6 stages / 16h total**: Apple, Banana, Orange,
Curry, Pineapple, Papaya, Palm, Dragonfruit.

**Special / other patches:**

| Crop | Patch | Stages | Interval | Total | Notes |
|---|---|---|---|---|---|
| Giant seaweed | Underwater | 4 | 10 | 40m | |
| Grapes | Vinery | 7 | 5 | 35m | protected free by vinery gardener |
| Bittercap mushroom | Mushroom | 6 | 40 | 4h | **unprotectable** |
| Belladonna | Belladonna | 4 | 80 | 5h20m | **unprotectable** |
| Cactus | Cactus | 7 | 80 | 9h20m | |
| Potato cactus | Cactus | 7 | 10 | 70m | |
| Calquat | Calquat | 8 | 160 | 21h20m | |
| Celastrus | Celastrus | 5 | 160 | 13h20m | |
| Crystal tree | Crystal | 6 | 80 | 8h | **immune** |
| Spirit tree | Spirit | 12 | 320 | 64h | |
| Redwood | Redwood | 10 | 640 | 106h40m | |
| Hespori | Hespori | 3 | 640 | 32h | **immune** |
| Elkhorn/Pillar/Umbral coral | Coral nursery | 4 | 40 | 2h40m | each tier same timing |
| Teak | Hardwood | 7 | 640 | 74h40m | |
| Mahogany | Hardwood | 8 | 640 | 85h20m | |
| Camphor | Hardwood | 8 | 640 | 85h20m | |
| Ironwood | Hardwood | 8 | 640 | 85h20m | |
| Rosewood | Hardwood | 10 | 640 | 106h40m | |
| Kronos / Iasor / Attas (anima) | Anima | 8 | 640 | ~85h lifespan | **immune**; special lifecycle (seedling → withering → death), effect active from planting |

Render: if `status == READY_TO_HARVEST` show "ready"; if `GROWING` show estimated ready
time; if `DISEASED`/`DEAD` show the flag, not a timer.

---

## 6. Disease uncertainty → confidence tiers

A cached timer is really "expected ready time *assuming it doesn't get diseased*." A
healthy crop can become diseased at the end of a growth cycle; a diseased crop stops
advancing and dies at the end of that cycle if uncured, and never recovers on its own.
Two mechanical facts that bound the risk: **a crop cannot become diseased in its first
growth stage** (immediately after planting), and **a fully grown crop cannot become
diseased**. So only the intermediate cycles are at risk. The snapshot can't see disease
that happens after the cast. Handle it honestly rather than hiding it:

- **Immune / protected / disease-free patch → high-confidence timer.** Cannot be
  diseased, so the countdown is effectively guaranteed. Render solid/green.
- **Unprotected & at-risk → estimate.** Show a tinted/amber "est. ready ~HH:MM
  (unverified)" and optionally a "recheck by" nudge at the next disease-check window.
- **Already diseased/dead at snapshot → red flag, no timer.** Surface these first;
  they're the ones needing action.

### What counts as disease-free (verified)

**Inherently immune (any location, no action needed):** Hespori, Crystal tree, Poison
ivy bush, and the anima plants (Kronos / Iasor / Attas). Grapes in the Vinery are
protected for free by the gardener.

**Location/unlock-based disease-free patches:**

| Patch | How it becomes disease-free |
|---|---|
| Trollheim herb | Complete *My Arm's Big Adventure* |
| Weiss herb | Complete *Making Friends with My Arm* (+ build Fire of Nourishment) |
| Harmony Island herb | Complete Elite Morytania Diary |
| Hosidius allotment / herb / flower | Complete Easy Kourend & Kebos Diary |
| Falador Park tree | Complete Elite Falador Diary |
| Civitas illa Fortis (Varlamore) herb | Reach 16,000 glory / Champion rank at Fortis Colosseum |

**Protectable (disease eliminated by paying a farmer):** allotment, hops, bush, tree,
fruit tree, calquat, cactus, hardwood, celastrus, redwood, giant seaweed, coral. Flowers
can't be farmer-protected but a fully-grown flower/scarecrow protects adjacent
allotments (white lily protects all allotment crops); a live Iasor cuts disease risk.

**Unprotectable & always at risk (no farmer option, no immunity):** herbs (outside the
disease-free patches above), flowers themselves, mushrooms, belladonna. These are the
crops whose cached timers are least trustworthy — flag them hardest.

**Self-healing + offline catch-up:** the estimate only matters for patches you haven't
touched recently. Every interaction or walk-past refreshes real state (§4a), and a
Geomancy cast refreshes everything. Crucially, patches keep growing while logged out, so
on login/reopen the `GrowthTimer` recomputes each patch's current stage from wall-clock
time elapsed since `lastSeen` against the offset-adjusted tick schedule (decision 6) — a
patch that would have finished offline shows "ready ~now / overdue," with the caveat that
an unprotected one *may* have diseased instead. Confidence improves the moment you visit.

Per-patch protection/compost comes from capture (interaction or Geomancy both expose it).
Fall back to the immunity table above plus the §13.5 unlock-based disease-free list where
capture doesn't distinguish.

---

## 7. Interaction tracker (= §4a, detailed) + patch mapping

This is the primary capture path, not a "checklist tick." One `PatchInteractionTracker`
watches patch state and writes it to `PatchStateStore`; the per-run "serviced this stop"
marker used by the run planner (§13.3) is derived from the same signal but stored on the
run, not on the patch.

- **`onVarbitChanged`:** each patch's state varbit changes when you compost/plant/protect/
  harvest it; update `status`/`compost`/`protected`/`stage`, stamp `lastSeen`.
- **`onMenuOptionClicked`:** catch Harvest/Clear/Plant/Rake/Pick/Compost/Pay on the patch
  `GameObject` as an immediate hint before the varbit settles.
- **Run servicing:** if a run (§13.3) is active, mark the corresponding stop serviced when
  its patch transitions (e.g. to freshly planted), and advance the Shortest Path target to
  the next stop.

### Patch mapping table
`internalPatchId → { displayName, worldPoint(s)/regionId, stateVarbit, patchType,
protectionDefault }`. RuneLite core's `net.runelite.client.plugins.timetracking.farming`
package (`FarmingPatch`, `FarmingRegion`, `Tab`, `CropState`, `PatchImplementation`)
already encodes locations, varbits, and crop states — mirror it. This table is the main
piece of manual data entry, and it's shared by capture (§4), the UI (§9), routing (§13.3),
and availability (§13.5).

---

## 8. Persistence

- Serialize `PatchStateStore` to a `ConfigManager` key as JSON. RuneLite scopes config
  per account profile automatically (decision 8), so no manual multi-account keying.
- Save on every capture/state update.
- Load on startup so the overview is populated before you visit anything.
- Each patch carries `lastSeen`; the UI can show relative age and the timer uses it for
  offline catch-up (§6).

---

## 9. UI

Presentation preference order (decision 5): **(a)** reuse the real Geomancy interface if
Plugin Hub review allows driving/redrawing it; **(b)** else recreate it from the same game
sprites/fonts; **(c)** else mimic it as a `PluginPanel` sidebar pull-out. All three share
the same layout the owner specified:

- **Patch-type tabs** across the top (herb, allotment, flower, hops, bush, tree, fruit
  tree, special…) — the Geomancy tab grouping.
- **Location rows** under the active tab, one per patch. Each row shows:
  - the **plant-type icon** (what's growing there),
  - a **protection shield** overlaid when the patch is protected/immune,
  - a **compost-bucket icon** matching the compost tier used (none / compost / super /
    ultra),
  - a **staged progress bar** (segments = growth stages, filled = current stage).
    **Hovering the bar shows the estimated time remaining** (§5/§6), and its
    confidence/colour follows §6.
  - Diseased/dead patches flagged distinctly and sorted to the top.
- **Available-seed selector** at the bottom of the tab: the seeds you own (§13.1) filtered
  to this patch type and to your Farming level (§13.2), each checkable to set the loadout
  choice for this patch type (decision 4). *(If per-location is added later, this selector
  moves inline under each location row instead — decision 4.)*

- **In-game ready icon** (`ReadyOverlay`): a small overlay/infobox you can hover to see
  which patches are ready to harvest (decision 6). Colour scheme for timers/states is TBD
  and meant to be iterated on.

No "done/checkbox" column — the row *is* the live patch state (decision 3); run-progress
striking-through happens only in the active run view (§13.3), not the overview.

---

## 10. Suggested build order

1. Project scaffold: `DoogleMapsPlugin` + `Config` + empty `PluginPanel` with sidebar
   icon; `PatchState` model + `PatchStateStore` with per-profile `ConfigManager` persistence.
2. **Patch mapping table** (§7) from `timetracking.farming` — the data spine everything
   else needs.
3. **Interaction capture (§4a/§7):** varbit + menu tracking → sequential state updates.
   At this point the plugin already works with no Geomancy.
4. **Geomancy-style UI (§9):** tabs, location rows with plant/shield/compost icons + staged
   progress bar. This is the MVP.
5. **Geomancy bulk refresh (§4b):** widget scrape → merge into the cache. Bonus path.
6. `GrowthTimer`: per-crop table (§5) + offset-adjusted global-tick projection + offline
   catch-up; hover time-remaining on the progress bar; confidence tiers (§6); ready icon.
7. Everything in §13, in the order given by §13.6.

---

## 10a. Attribution / prior art

RuneLite core's Time Tracking (and its farming package) is the reference for varbits,
locations, and crop timing. Respect its licence when mirroring data/approach, and credit
it plus the Shortest Path plugin (§13.3) in the repo README.

---

## 11. Compliance notes

- Plugin Hub rules: **read-only + UI only.** No input automation, no click/movement
  scripting. This plugin only reads game state and renders an overview — keep it that way
  so it passes review. The bank/vault highlight+filter (§13.4) and any Geomancy-interface
  reuse (§9) must stay display-only.
- Third-party plugin: expect human + AI review on submission and updates.

---

## 12. Open questions / TODO(verify) checklist

- [ ] **Per-patch state varbits** (compost/protection/crop/stage) from `FarmingPatch`/
      region defs — the spine of interaction capture (§4a/§7). Highest priority.
- [ ] Which patch interactions emit which varbit/menu events, to drive the sequential
      state machine (harvest→empty→compost→plant→protect) (§4a, decision 3).
- [ ] Geomancy interface group id + build script id, and per-row child structure — for the
      optional bulk-refresh path (§4b).
- [x] Per-crop stages + tick interval — filled in §5 from the wiki; cross-check crop→
      varbit mapping against `PatchImplementation`.
- [ ] How to read the current farming-tick offset from the API (for offset-adjusted +
      offline timers, §5/§6).
- [ ] Whether §9 can drive/redraw the real Geomancy interface within Plugin Hub rules,
      or whether to recreate from sprites / use a plain panel (decision 5).
- [ ] In-game "ready" overlay icon: infobox vs custom overlay wiring (decision 6).
- [ ] Seed vault container / widget id, and bank container read hook (§13.1).
- [ ] Watering-can dose + filled-plant-pot item ids, for the seed-vs-sapling kit math (§13.4).
- [ ] Farming-enhancer item ids to detect (magic secateurs, seed box, herb sack, bottomless
      bucket, etc.) for the "grab if owned" kit line (§13.4).
- [ ] Shortest Path integration: how it exposes target-setting (copy from Shortest Clue /
      Quest Helper), single- vs multi-target; soft-dep fallback is a show-on-map button
      (decision 7). Patch coordinates from `FarmingWorld`/`FarmingRegion` (shared with §7).
- [ ] Bank-search / filter API entry point Quest Helper uses to auto-filter the bank
      (confirm class/method); seed-vault item-slot ids for highlighting (§13.4).
- [ ] Availability = **manual per-patch toggles** in the §9 panel (decision 11) — this is
      the default and needs no requirement data. *Optional later:* auto-detect pre-fill
      needs quest-state/varbit ids, diary varbits, region checks, and the
      `patch → unlockRequirement` table (§13.5 seeds it). Teleports are Shortest Path's job.
- [ ] Confirm Shortest Path exposes no readable/writable teleport-requirement API beyond
      target-setting; if not, don't surface teleports in our UI.
- [x] Plugin Hub name = **Doogle Maps** (decision 10). Remaining: write a farming-forward
      Hub description/tags so it's discoverable despite the punny name.

---

## 13. Roadmap — all-in-one farming plugin

Post-MVP direction: grow the Geomancy-style overview into a single plugin that plans the
run, knows what seeds you own, and tells you what you can plant where. All of it stays
**read-only + UI** to keep Plugin Hub compliance (see §11) — it informs, it never acts.

**North star — how the pieces compose.** Everything downstream is a function of three
cached facts about the account, plus static reference data. `PatchStateStore` holds live
patch state, captured continuously from interactions (§4a) and topped up by Geomancy casts
(§4b). `SeedInventoryStore` (§13.1) holds seeds + payment items, captured when bank/vault
are open. `AvailabilityProfile` (§13.5) holds which patches the account has unlocked, from
quest/diary/skill/region state. Those feed a set of pure resolvers backed by the static
tables (`seed → patchType/levelReq/protection`, `patchType → seedsPerPatch`,
`patch → unlockRequirement`, plus §5 growth data): `PlantableResolver` (§13.2) answers
"what can I plant in each patch" from state ∧ owned ∧ level; `LoadoutStore` + `GatherList`
(§13.4) turn a saved per-patch-type seed plan into a bank/vault withdrawal list with
protection payments; `RunPlanner` (§13.3) orders the stops and delegates navigation to the
Shortest Path plugin. `AvailabilityProfile` is a filter every one of them reads through, so
an un-unlocked patch never appears. The §9 sidebar is that shared state rendered as the
Geomancy-style overview. In short: **capture state → filter by availability → resolve
against static data → render**, each feature a projection over one shared model, not a silo.

### 13.1 Seed inventory cache (bank + seed vault + inventory)

Goal: always know how many of each seed you own, even away from a bank.

- **New component `SeedInventoryStore`** — a cached `{ seedId → count }` per source
  (bank, seed vault, inventory), merged on read into an "owned" total.
- **Bank:** the client only holds bank contents while the bank is open (same constraint
  as Geomancy). Read the bank `ItemContainer` on the bank-open/container-changed event,
  filter to seed item ids, cache to `ConfigManager`, stamp `lastSeen`. **TODO(verify):**
  bank container id + the right event to read it cleanly.
- **Seed vault:** the Farming Guild vault is a separate storage with its own interface;
  read + cache the same way when it's open. **TODO(verify):** seed vault container /
  widget group id (confirm against the API — don't guess).
- **Inventory:** live and always available (`InventoryID.INVENTORY`); no caching needed,
  just union it in.
- **Staleness:** show each source's `lastSeen` so "you have 40 ranarr seeds" can be
  flagged as "(bank last seen 2h ago)".
- Reuse the same seed-id set to also track **protection payment items** (the payment
  column in §5's sources), enabling a "you're missing 3 baskets of apples for this run"
  readiness check.

### 13.2 Plantable resolver (available-seed selector)

Goal: for each patch, show the seeds you actually own and can plant there right now — this
is what populates the available-seed selector in each §9 tab.

Inputs: patch type + empty/harvestable status (from `PatchStateStore`, §4), owned seeds
(13.1), seed → patch-type mapping, seed → level requirement, and the player's Farming level.

**Seed → patch → level requirement** (verified; powers the filter — a seed is
plantable only if you own it *and* meet the level):

- **Allotment:** Potato 1, Onion 5, Cabbage 7, Tomato 12, Sweetcorn 20, Strawberry 31,
  Watermelon 47, Snape grass 61
- **Flower:** Marigold 2, Rosemary 11, Nasturtium 24, Woad 25, Limpwurt 26, White lily 58
- **Herb:** Guam 9, Marrentill 14, Tarromin 19, Harralander 26, Gout tuber 29, Ranarr 32,
  Toadflax 38, Irit 44, Avantoe 50, Kwuarm 56, Snapdragon 62, Huasca 65, Cadantine 67,
  Lantadyme 73, Dwarf weed 79, Torstol 85
- **Hops:** Barley 3, Hammerstone 4, Asgarnian 8, Jute 13, Yanillian 16, Flax 18,
  Krandorian 21, Wildblood 28, Hemp 37, Cotton 71
- **Bush:** Redberry 10, Cadavaberry 22, Dwellberry 36, Jangerberry 48, Whiteberry 59,
  Poison ivy 70
- **Tree:** Oak (acorn) 15, Willow 30, Maple 45, Yew 60, Magic 75
- **Fruit tree:** Apple 27, Banana 33, Orange 39, Curry 42, Pineapple 51, Papaya 57,
  Palm 68, Dragonfruit 81
- **Special:** Seaweed 23, Grapes 36, Mushroom 53, Belladonna 63, Hespori 65,
  Calquat 72, Crystal 74, Spirit 83, Celastrus 85, Redwood 90, Cactus 55,
  Potato cactus 64; Coral Elkhorn 28 / Pillar 52 / Umbral 77;
  Hardwood Teak 35 / Mahogany 55 / Camphor 66 / Ironwood 80 / Rosewood 92;
  Anima (Kronos/Iasor/Attas) 76

(Levels pair with the stages/interval data in §5 keyed on the same crop names.)

- **Component `PlantableResolver`** produces, per patch, the list of owned + level-eligible
  seeds for that patch type (optionally sorted by XP or "highest you can plant").
- **UI:** this is exactly the **available-seed selector at the bottom of each §9 tab** —
  the resolver supplies its contents (owned counts + eligibility), filtered to the tab's
  patch type. No separate Geomancy-widget overlay needed; our own sidebar is the surface,
  so it works with or without Geomancy access.
- Nice extension: flag "you have seeds but no empty patch" and "empty patch but no seeds".

### 13.3 Route / run planning (piggyback on Shortest Path)

Don't build a pathfinder. The **Shortest Path** plugin (Runemoro/shortest-path) already
solves cross-map navigation: the user selects which teleports/transports they're willing
to use, and it draws the route to a destination on the world map and minimap. **Quest
Helper** and **Shortest Clue** (KeiranY/clue-pathing-runelite-plugin) already drive it
programmatically — Quest Helper sets a path to each quest step, Shortest Clue sets a path
to the solved clue location. We do the same for farm patches.

**Division of labour:**
- *Shortest Path owns:* the teleport/transport graph and the actual travel leg from
  where you are to the next patch. We inherit all of it for free, including whatever
  teleports the user has configured.
- *Our planner owns:* the **set** of destinations (patch coordinates) and the **order**
  to visit them. Shortest Path routes point-to-point; it doesn't decide a tour.

**Approach:**
- **Phase 1 (curated):** ship per-run-type ordered stop lists (herb, tree, fruit tree,
  bush, hops, special) mirroring the wiki's Farming-run guides. As the player services a
  stop (marked serviced via §7), set the *next* stop as the Shortest Path target so they
  just follow the line. Integrate with 13.1/13.2 for fillability + payment readiness per stop.
- **Phase 2 (ordering):** for an ad-hoc set of patches the player picks, order the stops
  with a nearest-neighbour heuristic. If Shortest Path's API exposes path *cost/length*,
  use that as the edge weight so ordering reflects real travel time (incl. teleports)
  rather than raw tile distance; otherwise fall back to straight-line distance. Small
  fixed node set — no need for anything heavier than greedy NN.

**Integration mechanics — TODO(verify):** confirm how Shortest Path exposes target-
setting to other plugins in its current version (published API artifact / event / config
key / reflection) and whether it supports multiple waypoints or one target at a time.
Copy the pattern from Shortest Clue (KeiranY/clue-pathing-runelite-plugin) and Quest
Helper's Shortest Path integration rather than inventing one. It's a **soft** dependency
(decision 7): if the user doesn't have Shortest Path, fall back to a **"show on map"
button** on each stop that opens the world map pinned to that patch's location — no custom
pathfinding of our own.

**Data still needed from us:** patch coordinates + region ids to hand Shortest Path as
targets — pull from RuneLite's `FarmingWorld`/`FarmingRegion` (already structured) rather
than the wiki. This is the same patch-location layer the §7 mapping needs, so it's shared,
not new work.

### 13.4 Run loadouts, pre-run gather list & bank/vault highlighting

Goal: save a run as a per-patch seed plan, and before you leave the bank, tell you
exactly what to withdraw from bank + seed vault — and highlight it in place.

- **`LoadoutStore`** — named, saved runs persisted to `ConfigManager`. A loadout maps
  each **patch type** to a chosen seed plus its protection choice (decision 4: per-type,
  not per-location). Multiple loadouts ("full herb run", "herb + tree", etc.).
- **`GatherList`** — from a loadout, compute required seed totals:
  `Σ (patches of type × seeds-per-patch)`, subtract what's already in inventory, then
  split the remainder across **bank** and **seed vault** using the §13.1 cache (flag any
  seed neither source has enough of). Also compute **protection-payment items** for every
  patch the loadout marks as protected (`payment × protected-patch-count`), from the table
  below. Output a pre-run list: "Bank: 9 ranarr, 96 potato (3×32), 250 coconut (10× magic-
  tree protection)…  Vault: 20 snapdragon…  Missing: 4 magic saplings." **Never suggests
  the GE** (decision 1) — only what to withdraw/where it's obtained.

  **What's in the kit (decision 3):**
  - **Seeds** — quantities from **seeds per patch:** allotment **3**, hops **4** (jute
    **3**), herb / flower / bush **1**, tree / fruit tree / most specials **1**.
  - **Saplings vs seeds for trees/fruit trees:** if the player owns **saplings**, list
    those directly. If only **seeds**, list the seeds **plus 1 filled plant pot and 1
    watering-can dose per seed** (seed → filled pot → water → sapling). If they own both,
    list both and let them pick.
  - **Farming enhancers** — include ones the player **owns** (e.g. magic secateurs, seed
    box, herb sack, bottomless bucket, produce-boosting gear) so they remember to grab
    them; never tell them to acquire ones they don't have.
  - **Tools & plain compost — excluded by default.** Most players keep rake/spade/dibber/
    secateurs/watering can and compost in the **leprechaun** at each patch, so don't clutter
    the withdrawal list with them. Offer a config toggle "I don't use leprechaun storage"
    that adds tools + compost back in.

- **Protection payments** — per-seed, so the gather list knows what to add and the UI can
  show it on hover. `seed → protection[]` (some patches accept alternatives; list them
  all). Verified from the wiki:

  *Allotment* (farmer payment **or** an adjacent fully-grown flower / white lily):
  Potato 2 compost · Onion 1 sack potatoes · Cabbage 1 sack onions · Tomato 2 sacks
  cabbages · Sweetcorn 10 jute fibre (or scarecrow) · Strawberry 1 basket apples ·
  Watermelon 10 curry leaf (or nasturtiums) · Snape grass 5 jangerberries. White lily
  protects any allotment.

  *Hops:* Barley 3 compost · Hammerstone 1 marigold · Asgarnian 1 sack onions · Jute 6
  barley malt · Yanillian 1 basket tomatoes · Flax 6 grain · Krandorian 3 sacks cabbages ·
  Wildblood 1 nasturtiums · Hemp 6 flax · Cotton 6 hemp.

  *Bush:* Redberry 4 sacks cabbages · Cadavaberry 3 baskets tomatoes · Dwellberry 3
  baskets strawberries · Jangerberry 6 watermelons · Whiteberry 8 bittercap mushrooms ·
  Poison ivy — immune, none.

  *Tree:* Oak 1 basket tomatoes · Willow 1 basket apples · Maple 1 basket oranges · Yew 10
  cactus spines · **Magic 25 coconuts**.

  *Fruit tree:* Apple 9 sweetcorn · Banana 4 baskets apples · Orange 3 baskets strawberries
  · Curry 5 baskets bananas · Pineapple 10 watermelons · Papaya 10 pineapples · Palm 15
  papaya fruit · Dragonfruit 15 coconuts.

  *Hardwood:* Teak 15 limpwurt roots · Mahogany 25 yanillian hops · Camphor 10 white
  berries · Ironwood 10 curry leaf · Rosewood 8 dragonfruit.

  *Other special:* Giant seaweed 200 numulite · Calquat 8 poison ivy berries · Celastrus 8
  potato cactus · Redwood 6 dragonfruit · Cactus 6 cadava berries · Potato cactus 8 snape
  grass · Spirit tree 5 monkey nuts + 1 monkey bar + 1 ground tooth · Coral Elkhorn 5 giant
  seaweed / Pillar 5 elkhorn coral / Umbral 5 pillar coral. **Grapes** protected free.
  **Immune / unprotectable (no payment):** Hespori, Crystal tree, anima plants, Poison
  ivy; and herbs / flowers / mushroom / belladonna can't be farmer-protected at all.

- **Hover tooltips:** on any seed/sapling icon in our UI (loadout editor, gather list, and
  the §9 sidebar seed selector), show its protection cost on hover — e.g. hovering a magic
  seed/sapling shows "Protection: 25 coconuts", potato shows "2 compost, or adjacent
  marigold / white lily". Use RuneLite's `TooltipManager` + `Tooltip`.
  **TODO(verify):** exact tooltip-on-hover wiring for our panel/overlay.

- **Live swap (the ran-out-of-ranarr flow):** editing a loadout's seed for a patch type
  instantly recomputes the gather list *and* the §13.2 plantable view. Because §13.1
  already knows owned counts, flag any chosen seed where `owned < needed` in red and offer
  one-click swap to another owned + level-eligible seed for that patch type ("you have 40
  guam, 12 harralander available"). That's the whole "click a different herb and go"
  interaction.

- **Bank / seed vault highlight + filter** (both are proven patterns):
  - *Highlight:* colour the needed seeds' item-slot widgets in the bank and seed vault —
    same approach as the Bank Highlighter plugin (built on RuneLite's Inventory Tags) and
    Quest Helper highlighting required items. **TODO(verify):** seed-vault widget / item-
    slot ids for drawing highlights (bank slots are well-trodden; the vault less so).
  - *Filter:* narrow the bank to just the needed seeds, the way Quest Helper auto-filters
    the bank to a quest's required items. **TODO(verify):** the bank-search API entry
    point Quest Helper calls (confirm the class/method). The seed vault has no built-in
    search, so fall back to highlight-only there.
  - *Compliance:* highlight/filter are display only — they never withdraw for you. Keep it
    that way (§11).

### 13.5 Availability filtering (teleports & patches)

The full patch set includes patches the account hasn't unlocked yet, and showing them is
noise — it produces impossible plans (gathering seeds for a patch you can't reach, adding
an unreachable stop to a run). Teleport availability is Shortest Path's job (see below);
**patch** availability is ours.

**`AvailabilityProfile`** — one source of truth for which patches this account uses,
persisted per profile. Per decision 11 it's a **global invariant** every consumer reads
through (§9 overview, plantable selector, gather list, run planner, guided mode §13.7): a
patch that's off is never shown, planted into, gathered for, routed to, or highlighted.

**Default mechanism: manual per-patch toggles in the §9 side panel.** The player switches
on the patches they can use — a one-time setup that persists. This deliberately avoids the
most brittle part of the plugin: a hardcoded `patch → unlockRequirement` table plus quest/
diary/region varbit detection would need maintenance every time Jagex adds a patch or
changes a requirement. Letting the user decide is simpler, always correct for their account,
and zero-maintenance. Concretely: someone without *Making Friends with My Arm* just leaves
Weiss toggled off, and it disappears everywhere.

**Optional later layer: auto-detect.** As a convenience we can *pre-fill* the toggles from
game state (quest states, diary varbits, Farming level for Guild tiers, region access),
with the user still free to override. This is a stretch feature, not a dependency — the
plugin is fully functional on manual toggles alone, so auto-detect can land whenever, patch
by patch, without blocking anything.

Teleports remain Shortest Path's job (below) either way.

**Teleports — Shortest Path already does this; don't duplicate it.** Shortest Path's
transport definitions carry their own requirement metadata (quests, items, skill levels),
so with its requirement-aware routing it already excludes teleports the account can't use.
That means the "unlocked" filtering happens *inside* Shortest Path at routing time — when
we hand it a patch target, we get it for free. We do **not** need to compute teleport
availability ourselves.

The open question is only whether we can *piggyback* on that data — e.g. read its
requirement-filtered transport set to show "this run uses fairy ring X, spirit tree Y" in
our own panel, or toggle its requirement mode on. Based on how Quest Helper and Shortest
Clue integrate (they only ever *set a target*), the exposed surface is likely
target-setting only, with no readable/writable config API. **TODO(verify):** inspect
Shortest Path's source for any public API or config keys beyond target-setting; if none,
accept that teleport capability is handled internally by Shortest Path and simply don't
surface it in our UI. Either way, no teleport data duplication on our side.

**Patches — we fully own this** (Shortest Path knows nothing about farming patches). A
patch that's toggled off drops out of the overview (§9), gather list (§13.4), plantable
view (§13.2), run planner (§13.3), and guided mode (§13.7) in one move.

The patch requirement data below is **only needed for the optional auto-detect pre-fill** —
the plugin works without it, on manual toggles. If/when auto-detect is built, seed it from
this (verified from the wiki) and complete the rest from RuneLite's `FarmingWorld`/
`FarmingRegion` region gating + wiki patch pages:

| Patch | Requirement |
|---|---|
| Farming Guild (all its patches) | Farming 45 enter / 65 intermediate / 85 advanced |
| Hespori | Farming 65 (Guild) |
| Anima (Kronos/Iasor/Attas) | Farming 76 (Guild advanced) |
| Celastrus | Farming 85 (Guild) |
| Redwood | Farming 90 (Guild) |
| Spirit tree | Farming 83 (patch count scales with level) |
| Trollheim herb | My Arm's Big Adventure |
| Weiss herb | Making Friends with My Arm (+ Fire of Nourishment) |
| Harmony Island allotment | The Great Brain Robbery |
| Harmony Island herb | Elite Morytania Diary |
| Coral nursery | Troubled Tortugans |
| Locus Oasis hardwood | The Ribbiting Tale of a Lily Pad Labour Dispute |
| Anglers' Retreat hardwood | Sailing 51 |
| The Summer Shore calquat | Troubled Tortugans (partial) |
| Kastori / Auburnvale / Aldarin / Civitas illa Fortis | Varlamore region access |
| Lletya fruit tree / Prifddinas crystal tree | Tirannwn access (confirm exact quest) |
| Fossil Island seaweed + hardwood | Fossil Island access (confirm exact quest) |
| Mushroom (Canifis) | Morytania access (confirm exact quest) |

**Settings UX:** the §9 side panel carries a per-patch on/off toggle (grouped by tab). A
first-run prompt nudges the player to enable their patches. If auto-detect ships later, add
a "detect my unlocks" button that pre-fills the toggles (user can still override) — but it's
never required. Teleport preferences stay in Shortest Path's own config; we don't mirror
them. Everything here is read-only.

### 13.6 Where this lands in the architecture

New components on top of §3: `SeedInventoryStore`, `PlantableResolver`, `LoadoutStore`,
`GatherList`, `AvailabilityProfile`, `RunPlanner` (owns stop sets + ordering, delegates
navigation to Shortest Path as a soft dependency), `RunDirector` (§13.7 — the guided-mode
step machine + nearest-bank lookup), plus the static `seed → {patchType, levelReq}`,
`patchType → seedsPerPatch`, `seed → protectionPayment[]`, and `patch → unlockRequirement`
tables, and the reused patch-location data.
`AvailabilityProfile` is a filter every other feature reads through: the §9 overview,
gather list, plantable view, and run planner all show only available patches, and routing
uses only unlocked teleports. UI additions: the plantable seed selector inside each §9 tab
(13.2), a loadout editor + gather-list panel with bank/vault highlighting (13.4), an
Availability settings section (13.5), and a run-plan view (13.3), alongside the §9
overview panel.

Suggested sequencing after the §10 MVP: 13.1 → 13.5 *(now just per-patch toggles — trivial,
and everything downstream filters through it)* → 13.2 → 13.4 → 13.3 → **13.7 (the capstone
that ties it together)**. Teleport availability needs no work of ours; it falls out of
Shortest Path's routing in 13.3. Auto-detect pre-fill (§13.5) is optional and can land any
time later.

### 13.7 Guided "lazy" mode — the capstone

This is the end-goal experience (see top of doc): cast Geomancy, then be walked through the
entire run click-by-click, Quest-Helper style. It introduces **no new data** — it's a
`RunDirector` that sequences the existing pieces and drives highlights, advancing each step
when §7 detects you performed the action.

**The loop it orchestrates:**
1. **On cast** (or on opening the panel), build the run: current states (§4) ∧ active
   loadout (§13.4) ∧ owned items (§13.1) ∧ availability (§13.5) → the ordered stop list
   (§13.3) + the gather list (§13.4). The availability filter (decision 11) is applied
   *before* anything is ordered, so the run only ever contains patches this account can
   reach — no unreachable stop, highlight, or route (e.g. Weiss is simply absent for anyone
   who hasn't unlocked it).
2. **Restock if short.** If the gather list has a shortfall, the first step is "get items":
   route to the **nearest bank** (Shortest Path target, or show-on-map fallback), and on
   bank open, **filter + highlight** the exact seeds / payments / enhancers to withdraw
   (§13.4). Advance when inventory holds them.
3. **Per stop, in order:** set the Shortest Path target to the patch; on arrival, highlight
   the next object to click in sequence and advance as each is done (via §7):
   patch (rake if weeds / dig if dead) → **compost bucket** (inventory highlight) → **seeds
   or sapling** (inventory highlight) → **farmer NPC + payment items** for protection →
   **leprechaun** (tools/produce/compost storage) → collect. Each is a highlight + one-line
   hint, exactly like a Quest Helper step.
4. **Finish:** when every stop is planted, show "done — next round in ~HH:MM" (soonest
   patch ready, from §5/§6) and you log off.

**Highlight kit** (all read-only overlays): game-object highlight (patch, bank booth,
farmer, leprechaun), inventory-item highlight (compost, seeds, payment items, enhancers),
bank-widget highlight + filter (§13.4), and Shortest Path / show-on-map for travel. RuneLite
exposes object/NPC/inventory highlighting the way Quest Helper and Bank Highlighter do.

**New bits to build:** `RunDirector` (the step state machine — each step = a highlight
target + a completion predicate over §7 events) and a small **nearest-bank** lookup.
Everything else is a call into an existing component.

**TODO(verify):** object ids for patch/bank-booth/farmer/leprechaun highlights; compost/
seed/sapling/payment/enhancer item ids for inventory highlights (many shared with §13.4);
nearest-bank location data (maintain a list or reuse a RuneLite source); the object/NPC/
inventory highlight APIs (mirror Quest Helper).

**Compliance (non-negotiable, §11):** every step is *highlight + instruct*; the player
performs every click, withdrawal, and step. No auto-withdraw, no auto-walk, no auto-click.
This is precisely the Quest Helper model — approved and hugely popular — so "lazy mode" is
achievable *because* the laziness is "don't think," not "don't click." Any input automation
would get the plugin pulled from the Hub.
