# In-client test plan

Everything below is written but **has never run in the client**. Unit tests cover the
arithmetic and the layout; none of them can tell you whether an outline lands on the right
patch or whether the sidebar feels right.

Ordered by how likely each is to be wrong, so a short session still buys the most information.

---

## Before you start

1. **Commit first.** There are 77 changed or new files against the last commit. If a test
   session ends in a freeze and a kill, an uncommitted tree is the thing you actually lose.
2. **Clear the harvest history.** Settings → Maintenance → *Clear harvest history*. Everything
   currently stored was collected by the broken attribution and will drag every average it
   touches. New rows written after this are trustworthy; old ones are not.
3. Leave **Log harvests for validation** on. It is what makes the rest of this measurable.
4. Note that **guided mode defaults to on**, so it starts working the moment a run does.

---

## 1. Guided mode — highest risk, most new code

**The scene-scan defect is fixed.** `GuideOverlay` was walking 104×104 tiles with an
`getObjectDefinition` call per object on *every frame*; both that and the step derivation now
run once per game tick. Worth confirming the frame rate near a patch feels normal, but it is
no longer expected to be the problem.

Caching the step matters for a second reason: working it out walks the run planner and the
patch store, both synchronised, from the **client thread** — while the panel walks the same two
from the EDT. Fifty times a second was pointless cross-thread lock traffic on exactly the path
the freeze investigation keeps circling.

### 1a. The instruction appears and is right

- **Setup**: start a herb + allotment run with seeds picked and compost chosen.
- **Do**: teleport to a patch area and stand next to a ready patch.
- **Pass**: the patch is outlined in cyan; the Doogle Maps tab shows the same instruction in
  words above the stop list ("Harvest the ranarr.").
- **Fail signatures**:
  - Outline on the *wrong* patch → `findPatchObject` matches by varbit id, and two patches in
    one region can share one.
  - No outline but text present → the object scan found nothing; the patch object id may not
    carry the varbit we expect.
  - Text but no outline *and* no frame drop → overlay not registered.

### 1a-i. Start run no longer walks you away from work

- **Do**: stand at a patch area with something to do — ripe, dead or empty — and press Start
  run.
- **Pass**: the run begins **here**. No bank teleport while you are stood on the work.
- **Also pass**: finish that stop, and *then* the bank leg appears if supplies are genuinely
  needed. It is deferred, not cancelled.
- **Fail signature**: still routed to a bank → check whether any seed is picked at all for the
  types in the run. With none picked, a bank is still the deliberate opening leg, since we
  cannot know what the trip needs.

### 1a-ii. The two bugs you found, now fixed

- **The whole patch lights up, not one crop.** A patch is several objects sharing one varbit —
  an allotment is a scatter of one-tile crops, not one big object — so **every** match is now
  outlined. **Pass**: an allotment outlines across its whole area. **Fail**: still one melon →
  the objects are not all carrying the varbit, and I want to know which patch.
- **No path flicker on starting.** **Pass**: pressing Start run while stood at a patch draws no
  Shortest Path line at all, because there is nowhere to go. A line to another stop is correct
  and expected if the run has one.
- **No teleport-home instruction while standing on the work.** We were telling Shortest Path
  it could detour through a bank on *every* request, so it would happily route house → bank →
  teleport for a hop between patches. That permission is now only on the supply leg, where
  collecting is the point.
- **An emptied patch still highlights.** Bare soil is a `GroundObject`, not a `GameObject`, and
  only the latter was being scanned — so a patch went dark the moment its last crop was picked.
  **Pass**: after clearing an allotment it still outlines for the compost and seed steps.
- **The leprechaun's compost slot.** **Pass**: withdrawing compost outlines and tints the whole
  named slot in his store. **Fail**: a small bucket icon in the slot's top-left corner is the
  old behaviour and means this build did not take.
- **The instruction follows where you stand.** It compared regions, and every patch at a stop
  shares one, so "nearest" was really "first in the list" — you could stand at the herb patch
  and be told about an allotment. **Pass**: walking between patches at one stop changes the
  instruction to the one you are next to.

### 1b. The order is right

Work one patch through its whole cycle and check each instruction appears in turn:

| Patch state | Expected instruction |
|---|---|
| ready | Harvest |
| ready, inventory full | Note with the tool leprechaun **first** |
| dead | Clear the dead crop |
| growing | *nothing at all* |
| empty, compost not applied | Withdraw compost (if you lack it) → Apply compost |
| empty, treated, seeds in box | Empty your seed box |
| empty, treated, seeds in hand | Plant |

- **Pass**: each step disappears within a tick or two of doing it, and the next appears.
- **Fail signature**: a step that will not go away after you have done it. That means the
  state it keys on is not updating — most likely compost, which is chat-message-only.

### 1c. Out-of-order play

- **Do**: apply compost *before* the plugin asks for it. Then look at the instruction.
- **Pass**: it skips straight to Plant. There is no step counter, so it should simply agree
  with reality.
- **Fail signature**: it insists on compost you have already applied — the compost capture
  missed it (see §4).

### 1d. The leprechaun

- **Do**: fill your inventory while harvesting.
- **Pass**: instruction changes to noting **only when the pack is genuinely full** — 27 of 28
  should still say harvest. The leprechaun is outlined, the crop is filled-outlined **in your
  inventory**, and the patch is not outlined.
- The inventory highlight is the part that was broken: it was drawn under the inventory panel
  and so invisible. Seeing which item to use on the leprechaun is most of that instruction, so
  if the leprechaun lights up and the crop does not, say so.
- **Fail signature**: leprechaun not highlighted → matched by name containing "leprechaun";
  check what yours is actually called.

---

## 2. The three tabs

Never seen outside a unit-test PNG.

- **Pass**: three tabs across the top — Almanac, Doogle Maps, Stats — all three labels fully
  readable, nothing clipped at 225px.
- **Fail signature**: "Doogle M..." means the proportional sizing did not take; the strip
  wants 253px at its natural size and is scaled down to fit.
- Check switching tabs actually **changes the page**. `MaterialTab.select()` alone does not
  swap it — that bug appeared in the render tests and is fixed, but it is worth one click.

---

## 3. Harvest log — the four fixes

These are the ones with real evidence behind them, so they are worth confirming.

### 3z. Herbs reach the log at all — the herb sack fix

- **Do**: harvest a herb patch **with your herb sack open**, then check `client.log` and the CSV.
- **Pass**: a herb row appears at all. There has never been one, which is the bug — an open
  sack takes the herb before the inventory sees it.
- **Also watch for**: a line reading `Harvest storage message seen: "..."`. That is the plugin
  learning the wording the game uses. **Please paste it to me** — it turns the current
  experience-based estimate into an exact count.
- **Fail signature**: still no herb row → the experience match is not firing. The likely cause
  is an XP modifier beyond the Farmer's outfit; tell me what you were wearing.

### 3a. Regrowing crops complete

- **Do**: pick a bush or fruit tree clean.
- **Pass**: `client.log` shows `Harvest: ... (no "left standing" suffix)`, and the Stats tab
  shows **n ≥ 1** for that crop rather than 0 items-with-no-harvests.
- **Was**: every berry ever picked filed as abandoned; jangerberry read 0 harvests / 7 items.

### 3b. Flower XP is no longer 0

- **Do**: harvest a limpwurt patch clean.
- **Pass**: CSV `actual_xp` is non-zero and close to `predicted_xp`.
- **Was**: `predicted 120.5, actual 0.0` — the record was written before the tick's experience
  arrived.
- **Still worth watching**: the observed limpwurt figure was 91 where the wiki implies 120.
  One clean full harvest settles whether the per-patch number is right.

### 3c. Attribution stays local

- **Do**: a full run touching several patches of the same crop, then bank the produce.
- **Pass**: one CSV row per patch, each with a plausible count. Banking produces **no** row.
- **Was**: 110 watermelons on one row against a predicted 11.

### 3d. The compost warning fires

- **Do**: compost a patch, harvest it, and check `client.log`.
- **Pass**: if the row says `compost=NONE` you get a WARN saying the patch was probably
  composted and we missed it. If compost was captured, no warning and the row names the tier.
- Either outcome is informative: the warning existing means capture failed, its absence means
  capture worked.

---

## 3e. Bank loadout and highlighting — new, never run

**Setup**: tick some patch types, pick seeds, choose compost, then open any bank.

- **Pass**: the items the run still needs are outlined in the bank — seeds, protection
  payments, any teleport you own that reaches a stop on this run, your herb sack or seed box.
  The Doogle Maps tab says "From the bank: …" listing the same things.
- **Also pass**: things you are *already carrying* are **not** highlighted. Compost **is**
  marked, but in **amber rather than cyan** — meaning "leave it, the leprechaun has a thousand,
  ask when you get there". Hovering either colour says which it is. If compost lights up in the
  same colour as the seeds, that logic is inverted.
- **Tree runs**: tick Tree or Fruit tree and your best usable axe should be marked. **Pass**:
  the best one your Woodcutting level allows, not simply the best you own — a dragon axe should
  not be named at 45 Woodcutting. With no usable axe at all it says so in the panel, since
  arriving without one means the tree patch cannot be cleared.
- **Fail signatures**:
  - A teleport highlighted for somewhere this run does not go → the region match in
    `TeleportItems` is wrong for that patch.
  - Nothing at all highlighted → check the bank has actually been read; `BankContents` fills
    on the container event, so a bank never opened this session is empty by definition.
  - A fruit basket you own not suggested → only the **empty** basket id is matched today, so a
    part-filled one reads as absent. Known gap, recorded.

- **Before you open a bank**, the panel should say *"Open a bank and this will say what to
  take"* — **not** list your secateurs and payments as missing. The bank is only readable while
  open, so on a fresh login everything looks absent; claiming that would be a false alarm every
  session.

**The teleport table is deliberately short.** Herb patches come from the wiki's own per-patch
recommendations; other families have only obvious entries. If a teleport you own for a stop on
your run is not offered, that is a missing table row rather than a bug — tell me which item and
which patch and it is a one-line addition.

## 4. Compost capture — the open question

Your records match core Time Tracking's exactly, so nothing is lost once seen. The question is
whether it is seen at all during normal play.

- **Do**: compost several patches normally over a run. Afterwards, check the Almanac rows for
  the compost badge, and check new CSV rows for the tier.
- **Pass**: tier recorded on every patch you treated.
- **Fail signature**: `compost=NONE` on a patch you definitely treated, *plus* the §3d warning.
  If that happens, tell me which patch and whether you used a bucket, the bottomless bucket, or
  Fertile Soil — the three take different capture paths and only one of them would explain it.

---

## 5. Smaller things, one look each

| What | Where | Pass |
|---|---|---|
| **Farmer chatheads** | Almanac, a protected patch | A recognisable face, not a green shield. Tooltip names them ("Protected - Elstan paid"). Coral patch keeps a shield — that one has no wiki page. |
| **Saplings** | Tree / fruit tree tab seed list | Saplings you own appear. A tree *seed* also appears, greyed, tooltip saying it needs potting. |
| **Stats tab** | Stats | Columns read crop / n / got / avg. Hovering a crop gives the per-compost split. |
| **Run destinations** | Doogle Maps, before starting | "Show destinations (n)" lists every stop with its patch count. |
| **Stats reset** | Settings → Maintenance | *Clear harvest history* prompts, then empties the Stats tab. Patch states and settings survive. |
| **Fruit tree XP** | Check-health a fruit tree | The tooltip now gives a figure. One clean drop settles pineapple (4,605 vs 4,605.7) and papaya (6,146.6 vs 6,146.4). |

---

## 6. The freeze

Unresolved. One real fault was found and fixed — `RunPlanner.start`/`stop` called into Shortest
Path's event bus while holding a lock, and that bus delivers synchronously. Whether it was
*the* cause is unproven.

**If it happens again, before killing it:**

```
jps -l                 # find the RuneLite pid
jstack <pid> > C:\hang.txt
```

That names both threads and both monitors and turns this into a one-line fix. A kill without it
leaves us exactly where we are.

Workaround if it recurs often: turn off the ready infobox. It renders on the client thread and
walks the same stores the panel does.

---

## 7. Things only you can trigger

- **A diseased patch, with Geomancy cast within ~20 minutes.** Herbs untreated and unprotected
  are the fastest route — ~50% per patch per cycle. The probe writes any new bar or tooltip it
  has never seen to `~/.runelite/doogle-maps/geomancy-vocabulary.tsv` and logs it at INFO, so it
  announces itself; nothing to watch for.
- **The rename decision**: Farmers Almanac / Farmer's Almanac / Farmers' Almanac. It lands in
  the descriptor, the repo name and the Hub listing. Never rename the config group
  `dooglemaps` — that orphans the harvest history, the one thing that does not come back.
