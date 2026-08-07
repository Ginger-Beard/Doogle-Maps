# In-client test plan

Everything below is written but **has never run in the client**. Unit tests cover the
arithmetic and the layout; none of them can tell you whether an outline lands on the right
patch or whether the sidebar feels right.

Ordered by how likely each is to be wrong, so a short session still buys the most information.

---

## Before you start

1. **Commit first.** If a test session ends in a freeze and a kill, an uncommitted tree is the
   thing you actually lose. (The 77-file backlog this used to name is committed.)
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
- **Pass**: the patch is outlined in cyan; the **Farm run** panel on the game screen shows the
  same instruction in words ("Harvest the ranarr."). It used to be in the sidebar — see 1a-iii.
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
- ~~**The instruction follows where you stand.**~~ **Superseded by 1a-vi.** The original bug was
  real — regions were compared, and every patch at a stop shares one, so "nearest" meant "first
  in the list" and you could stand at the herb patch and be told about an allotment. The fix
  used real coordinates, and then went too far the other way: it re-chose every tick, so walking
  across the area mid-harvest moved the instruction to whatever you passed. Distance now decides
  only the **first** pick at a stop. Check 1a-vi instead of this.

### 1a-iii. The step is on the game screen now

- **Pass**: a small panel headed **Farm run** appears top-left when you are stood at a stop with
  something to do, giving the current instruction and up to three follow-ups. Right-click it to
  drag it somewhere better; it behaves like any other RuneLite overlay.
- **Also pass**: the Doogle Maps sidebar tab **no longer repeats the instruction**. It keeps the
  stop list, the destinations and the bank loadout — the things you read standing still.
- **Also pass**: it disappears entirely between stops and when everything here is growing. An
  empty panel parked on screen is a bug.
- **Fail signature**: nothing appears at all but the outline is on the patch → the overlay is
  not registered. Text appears but never changes → the tracker is not being re-read; that is the
  same cache the outline uses, so the outline would be stuck too.

### 1a-iv. Noting a full inventory highlights the crop — the bug you found

- **Do**: fill your pack while harvesting.
- **Pass**: the leprechaun is outlined **and** the crop is outlined in your inventory. Both, not
  just him.
- **Was**: only the leprechaun. Every step *at* the leprechaun looked its item up in his store,
  and a watermelon has no slot there, so the inventory highlight was silently skipped. It now
  asks where the item *is* rather than where the click happens.
- **Worth checking the other directions while you are there.** Three leprechaun steps, and they
  do not all point at the same screen:
  - **Withdrawing compost or a tool** → the **slot in his store**, not an inventory item.
  - **Returning empty buckets** → also the **bucket slot in his store**. His store opens over the
    inventory listing his own contents, and that is where the click happens. This was pointing at
    the bucket in your pack, which is the wrong object — reported from play.
  - **Noting a crop** → the item **in your pack**, since you use it on him directly.

  These now go three different ways off one flag, so a fix to any of them could break another.

### 1a-v. Tools you are not carrying

- **Do**: bank your rake, then go to a weedy patch on a run.
- **Pass**: if the leprechaun has a rake, the first instruction is *"Get your rake from the tool
  leprechaun"* with his store slot highlighted, and raking follows it.
- **Pass**: if he has none either, no such step appears — there is nothing useful to say at a
  patch about a rake in your bank. It should have been in the loadout before you set off.
- **Fail signature**: the step appears and will not clear after you withdraw → his store varbit
  is not what we think; say which tool.

### 1a-vi. The guidance stays on the patch you are working

- **Do**: start harvesting a patch, then walk across the area — past another patch, or to the
  leprechaun on the far side.
- **Pass**: the instruction and the outline **stay on the patch you were working** the whole way.
  They only move on once that patch has nothing left to ask for.
- **Also pass**: leaving the area entirely and arriving somewhere new picks the nearest thing
  there, as before. Stickiness is per stop, not for the whole run.
- **Was**: patches were ordered by distance every tick, so walking put you nearer the flower
  patch and the highlight jumped to it mid-harvest — usually while walking to the leprechaun
  *because of* the step you were on.
- **Fail signature**: it now sticks to a patch that is genuinely finished → the "no steps left"
  test is not firing, which would also mean the stop never completes.

### 1a-vii. The patch fill is solid, not a grid

- **Pass**: an allotment lights up as **one filled shape with a single outline round the
  outside**. No internal cyan lines between tiles.
- **Was**: every tile drew its own border, so a multi-tile patch read as a chessboard laid over
  the ground (see the screenshot).
- **Check with crops standing too**, not just on bare soil — the tiles are drawn under the crop
  models in both cases and only the empty patch was in the screenshot.

### 1a-viii. Barbarian Farming — no dibber asked for

- **Pass**: on an account with Barbarian Farming, the guide **stops asking for a seed dibber**
  after the first herb or allotment you plant, and the loadout stops listing one.
- **If it is still asking, just tick the setting.** Settings → *Barbarian farming*.
  That forces the answer on and is the quickest way past it — the detection below is a
  convenience, not something to be held up by.
- **The first plant is the catch.** There is no varbit for the farming section of the miniquest,
  and quest state answers about the whole thing — which is the wrong question, since the sections
  are independent. So it is learned by watching: a seed going in with no dibber carried can only
  mean the unlock. Expect **one** wrong "get your seed dibber" before the first planting, and
  never again after.
- **Confirm it landed**: `client.log` prints *"Seed planted with no dibber carried - this account
  has Barbarian Farming"* once, the first time it is seen.
- **If it keeps asking**, the log now distinguishes the two reasons for silence. A line saying
  *"Watched X planted with a dibber carried"* means the detection is running and you had a dibber
  on you. **No line at all** means the planting was never seen, which is the interesting failure —
  say which patch you planted and what state it was in beforehand.

### 1a-ix. The panel while travelling

- **Do**: finish a stop and set off for the next one.
- **Pass**: the **Farm run** panel keeps something on it — "Travel to the next patches", how many
  stops are left, and **"via …"** lines naming what Shortest Path says the route uses (fairy
  ring, spirit tree, a teleport). On the supply leg it reads "Collect your supplies" instead.
- **Was**: blank for the whole journey, which is the longest part of a run — the line was on the
  map and the words were in the sidebar, so following a route meant looking in a third place.
- **It should now name where you are going** — "Travel to Troll Stronghold." Shortest Path reports
  a destination alongside the transports, which was previously assumed unobtainable. It falls back
  to "Travel to the next patches" whenever that destination does not resolve to exactly one
  outstanding stop, because a confidently wrong place name sends you across the map.
  **Tell me which of the two you see**, and whether the named stop is where the path actually
  goes — that settles whether the destination is the router's choice or an echo of every target
  it was handed, which the API does not document.
- **No repeated hops.** "via Troll Stronghold Portal" should appear **once**. One edge of a path
  can produce several transport objects sharing a display string, which is why it read as
  "portal, portal" before; the list is de-duplicated and in path order.
- **Fail signature**: no "via" lines ever → either Shortest Path is not installed (fine, the rest
  still shows) or `postTransports` is not taking effect.

### 1a-x. The teleport is highlighted, everywhere you might use it

The travelling counterpart to the patch highlighting: the same idea applied to the half of a run
that is not standing at a patch. Four surfaces, checked in order of how specific each is — an
open menu wins over the inventory behind it, because that is what you are looking at.

- **In your inventory or worn**: the teleport that reaches the next stop is outlined, and the
  panel says *"Use your Catherby teleport tablet"*. **Worn items count** — an Ardougne cloak round
  your neck or a Construction cape on your back is the normal way to carry a teleport, and the
  highlight now looks in the equipment tab as well as the pack. Open either view; both are
  covered.
- **In the bank**: already covered by the loadout highlight, since teleports for every stop on
  the run are in it. **Pass**: the tablet you need is marked in the withdraw colour, and the panel
  reads *"Bank: …"* rather than "use your".
- **Once you are in the house, the tablet stops being suggested.** It was still outlined and the
  panel still said to use it, which is the plugin telling you to travel to where you already are.
  **Pass**: arriving in the POH drops the tablet highlight and the furniture takes over.
- **In your house, before opening anything**: the **portal nexus** and **jewellery box** objects
  are outlined in the room. The box is matched by object id as well as by name (all three tiers),
  after one outlined and the other did not. This was the hole — they were only marked once their interface was
  open, which is no help to someone who has just teleported in and is looking at the furniture.
  Matched by name, so all three tiers of each are covered.
- **At the portal nexus**: the row matching the destination is outlined. This was finding nothing
  at all — the rows sit several containers deep and the search only looked one level down, so the
  jewellery box's flatter menu worked and the nexus silently did not.
- **Matched on the destination's name, never its position.** Players reorder their own nexus, so
  "the third row" and "option 4" mean different things on different accounts. **The one to watch
  is Troll Stronghold** — the nexus calls it *Trollheim*, and the two share no substring, so it
  goes through an alias table.
- **A miss now names itself.** If nothing on the menu matches, `client.log` gets one line per
  destination listing every row that *was* on screen — *"Nothing on this teleport menu matched
  "Catherby". Rows on screen: [...]"*. Paste it and the alias is a one-line addition. Guessing at
  the game's vocabulary is what produced the Trollheim alias; this makes the game supply the rest.
- **At a jewellery box**: the *category button* is outlined first — Skills for the Farming Guild,
  Glory for Draynor/Al Kharid/Karamja/Edgeville, Gaming for Troll Stronghold. Then, once past it,
  the box opens a **lettered option menu** and the single matching row is outlined:
  *"J: Farming Guild"*, not the whole panel. Marking the panel was the bug — the line is the thing
  you need to find.
- **Only one piece of furniture lights up.** Heading somewhere a jewellery box reaches marks the
  box and **not** the nexus. Two highlights means "one of these, you work it out", which is the
  question you came in with. With only one kind of furniture in the house, that one is marked
  whatever the destination.

- **Getting to the house**: the **Teleport to house** tablet (or a Construction cape) is now
  offered when nothing lands nearer the stop — it was missing, which broke the chain at its first
  step. **Pass**: travelling to a stop you have no direct teleport for, with a house tablet in
  your pack, outlines the tablet and the panel reads *"Use your teleport to house tablet"*. Then
  the nexus/jewellery box highlighting takes over once you are inside.
- **It must never outrank a direct teleport.** **Pass**: heading to Catherby with a Catherby
  tablet *and* a house tablet, the Catherby one is highlighted.

**Fail signatures**:
- Nothing highlighted while travelling → the teleport table has no entry for that stop and you
  have no house teleport either. The table is short on purpose. Tell me which item and which
  patch, it is one line.
- *"Use your Dramen staff"* → the universal fallback is not distinguishing a teleport from a
  thing you carry to enable one. That instruction cannot be followed.
- The nexus lights up the **wrong** row → the match is too loose. This is the failure worth
  reporting immediately, since it would send you the wrong way.
- The whole nexus panel lights up → an unlabelled widget is matching everything, which the empty
  guard is meant to prevent.
- Highlight appears while you are **at** a patch with work to do → the travel hint should only
  exist while travelling.
- The nexus object is not outlined in your house → the name match. Tell me what yours is called;
  it looks for "nexus" and "jewellery box".
- A **worn** item named in the panel but not outlined in the equipment tab → the slot list. Say
  which slot it was in.

### 1a-xi. The route stops naming a teleport you have already used

- **Do**: teleport to your house mid-run and watch the panel.
- **Pass**: the "via" lines **change**. They should no longer say "via Teleport to house tablet"
  once you are standing in the house.
- **Was**: reported from play — the panel kept listing the tablet that had just been used, and the
  hops named were the previous journey's. Shortest Path redraws its own line, but what it had
  reported to us was computed from where you used to be, and we kept showing it.
- **Two things changed**: the stored list is cleared when a new route is asked for, rather than
  being replaced only when a reply arrives; and a run now asks for a fresh route whenever the
  player changes region, which is what a teleport does.
- **Fail signature**: the list goes empty and never repopulates → the retarget is firing but
  Shortest Path is not replying. The stop count and destination should still be right.

### 1a-xii. One trip to the leprechaun, not three

- **Do**: with unnoted crops and empty buckets in your pack, have any leprechaun step anywhere in
  the stop's list — most easily by needing to withdraw compost.
- **It does not have to be the current step.** Mid-harvest with a compost withdrawal a couple of
  steps away, the visit is already certain and the errands appear at it. You are not interrupted:
  the patch in front of you finishes, then noting appears as you set off for him.
- **Pass**: the panel lists them in the order **note → deposit → withdraw**. Noting and handing
  back buckets both *free* slots and withdrawing compost *fills* them, so anything else can leave
  you taking four buckets into a pack still holding four limpwurts.
- **Was**: noting and buckets were only ever appended at the end of a stop, so a trip to him for
  compost did not mention them and you walked back later.
- **Also pass, unchanged**: with nothing sending you to him, the noting and buckets still come
  last, after the patch work. Interrupting a harvest to tidy up is still worse than the tidying.
- **Fail signatures**:
  - "note the herbs" twice → the de-duplication is not catching the note step a full inventory
    raises on its own.
  - **Not prompted to note at all.** `client.log` now prints one line per leprechaun visit —
    *"At the leprechaun in Falador: bundling [...]. Crops in the pack: 4 limpwurt."* Paste it. It
    records what was in the pack at the moment the decision was made, which is the thing that is
    gone by the time the problem is noticed, and it prints even when the answer was "nothing
    extra" — a prompt that was never generated and one that was generated and missed look
    identical from the outside otherwise.

### 1a-xiii. The seed box highlights

- **Do**: with seeds in your seed box and none in your pack, reach the planting step.
- **Pass**: the **seed box** is outlined in your inventory alongside "Empty your seed box…".
- **Was**: nothing was outlined. The step named the *seed*, which is inside the box and so not in
  the inventory at all — there was nothing on screen for the outline to find, and it drew nothing
  rather than failing loudly. Same root cause as the watermelon-noting bug.
- Works for both the open and closed box, which are different item ids.

### 1a-xiv. Protected herb patches as their own tab

**On by default.** Settings → Guided run → *Separate protected herb patches*.

- **Pass**: the herb tab strip shows **two** herb icons, and the protected one carries a small
  green **shield** in its bottom-right corner — the same shield the protected-patch rows use, so
  it needs no explaining. They share the base sprite because the game has one herb-patch icon;
  the badge is what tells them apart without hovering. Tooltips still read "Herb" and
  "Herb (protected)".
- **Pass**: the protected tab lists only the patches you have actually unlocked — Trollheim (My
  Arm's Big Adventure), Weiss (Making Friends with My Arm), Hosidius (Kourend **easy** diary),
  Harmony (Morytania **elite** diary). All four are detected, none is asked for.
- **Civitas illa Fortis is a separate tick** — *Colosseum Champion*. It is disease-free with
  Champion status and the client exposes no varbit for it, so it is the one thing here taken on
  trust rather than observed.
- **Pass**: with no unlocks at all, there is **no** second tab. An empty one would imply you were
  missing something.

**The run list must agree with the tabs.** *Herb (protected)* is its own line under Start run,
appearing and disappearing with the same setting. It is built from the same list as the strip but
was built only once, at startup — so the tab could appear when the unlocks arrived while the run
list below still had no line for it, giving you a category you could see and could not run. Both
are now rebuilt together.

- **Pass**: ticking *Herb (protected)* and *Herb* separately runs them separately, and the
  estimate below counts each group's own patches.
- **The reversibility check again**: tick *Herb (protected)*, turn the setting **off**, tick
  something else, turn it back **on**. Protected herbs should still be ticked. The checkboxes can
  only report on lines they are showing, so a plain "replace everything they say" discarded it.

**The two tabs are independent**: pick ranarr on the protected one and guam on the other, and
they stay that way. The reward table should price them separately and add up — protected patches
at 100% survival, ordinary ones discounted.

**The reversibility check, which is the one worth doing.** Turn the setting **off** again:

- **Pass**: back to one herb tab, with the seeds and compost you had before splitting.
- **Fail signature**: an empty seed list or compost reset to untreated means the fallback is not
  working, and that is the failure that matters — an unnoticed reset to NONE is a whole run
  planted untreated.

**Also worth confirming**: the yield estimate for Hosidius and Harmony should no longer be
discounted for disease once the diary is done. That was wrong before this change regardless of
the tab split — `survivalAcross` only knew about Trollheim and Weiss.

**If the tab is missing, the log says why.** Two places:

```
Protected herb patches: 1 of 4 unlocked
  no   Trollheim (My Arm's Big Adventure)
  no   Weiss (Making Friends with My Arm)
  yes  Hosidius (Kourend easy diary)
  no   Harmony (Morytania elite diary)
Built 23 patch tabs; protected herbs split=true (setting=true, qualifying patches=1)
```

Both halves of the split condition are named, because from the sidebar they look identical:
`setting=false` is the toggle, `qualifying patches=0` means nothing was detected.

**This is what went wrong the first time.** The unlocks were sampled once, from the plugin's
load, which runs the instant `LOGGED_IN` fires — and the quest and diary varbits are not all
synced by then. It read zero, latched, and the tab was missing for the whole session; toggling
the setting could not help, because the setting was never the thing that was false. It is now
re-read every tick, leaving early when nothing has changed, so a late varbit is picked up and the
sidebar is told. Expect the "0 of 4" line **not** to appear at all on a normal login now.

**Fail signature**: an unlock you know you have reading `no` after you have been logged in for a
minute. That is a wrong varbit or quest constant, not a timing problem, and the named line says
which one to go and look at.

### 1a-xiv-b. One patch list, and the Locations section — new

**The separate "Patches (n/m)" dropdown is gone.** The status rows are the control now.

- **Pass**: clicking a row switches that patch off. It stays where you can see it, washed
  translucent red, and moves to the bottom of the list. Clicking it again puts it back.
- **Pass**: the count moved into the heading — *Patch status (14/18)*. That was the one thing the
  old list said which the rows do not say for themselves.
- **Pass, and this is the one that used to be impossible**: a patch you have never visited can be
  switched off and on. Under the old arrangement `Hide empty patches` could hide the row while the
  checkbox list still listed it, which is how the same patch could be in two states at once.
- **Pass**: click through the patch tabs, then click back through them again. No gap opens between
  the tab strip and the *Patch status* heading. Two of the three places setting the "nothing here
  yet" line's visibility asked `isEnabled()` — a property nothing sets, so always true — and one of
  them runs on every tab select. The empty line has an 8px border top and bottom, so it read as
  padding appearing from nowhere. **The second lap is the one to watch**: a tab is refreshed on
  select only while it is stale, so the first visit corrected itself and revisiting did not.
- **Fail signature**: a switched-off patch disappearing rather than going red. That is the old
  behaviour, and it is the reason for the change: the list you were reading and the list you
  edited were different lists, so switching something off looked like losing it.

**Settings → Locations** is a coarser cut: 36 places, all on by default.

- **Pass**: turning off somewhere you never farm removes its patches from **every** tab at once,
  and from the heading counts.
- **Display only, and worth being clear about**: this hides rows. It does not change what a run
  does — that is the per-patch switch on the row. A location you hide while its patches are still
  switched on is still routed to. The two answer different questions: whether you can reach a
  patch, and how much of the sidebar you want it taking.
- **Fail signature**: a place with no setting. `LocationsTest` fails the build if the generated
  region list and the hand-written settings ever drift, which they will the next time Jagex adds a
  farming area.

### 1a-xiv-c. Patch rows, run options and guild tiers — new

**Clicking a row now works.** It did not, and the cause was worth finding: `setToolTipText`
quietly registers `ToolTipManager` as a mouse listener, and the row puts tooltips on the produce
icon, the progress bar and the badges. Swing delivers a click to the deepest component that is
listening, so every click landed on one of those and stopped. The row's own listener fired only
on the bare pixels between them.

- **Pass**: clicking anywhere on a row — the icon, the name, the bar, the shield — switches the
  patch off, washes it red, and drops it to the bottom of the list. The projection below changes
  with it.
- **Fail signature**: clicking the name working but the progress bar doing nothing. That is the
  same bug with a narrower blast radius.

**Cactus is a run type, with a harvest-only line.** It regrows exactly like a bush and had
neither. The harvest-only set is derived from the produce data now rather than hand-listed, so it
cannot fall behind again.

- **Pass**: *Cactus* and *Cactus (H/O)* both appear, and the projection is non-zero — cactus
  experience data was missing entirely, which is why it was left out before.

**Pairs sit side by side.** *Bush* and *Bush (H/O)* share a row, as do the fruit tree and cactus
pairs. A two-column grid fills row by row, so where a pair landed depended on how many single
options happened to precede it — one shared a row by luck while another straddled the break.

- **Pass**: every *(H/O)* line is immediately to the right of its full run, on the same row.
- **Pass, and this is the one that used to drift**: switch patch types off in the settings and the
  pairs stay aligned. Hiding a type removes its line and shifts everything after it by a cell.
- **Pass**: a patch type switched off is **no longer offered as a run at all**. It was, which
  meant a run you could start and not configure — the tab is the only place to pick that type's
  seed or compost. Switching it back on brings its ticks back; nothing is lost by hiding one.

**Full and harvest-only are mutually exclusive.** Ticking one unticks the other.

- **Pass**: tick *Bush*, then *Bush (H/O)* — the first clears. And back the other way.
- **Why**: replanting means harvesting first, so the full run already includes the harvest;
  choosing harvest-only is choosing not to replant. Both was previously expressible and silently
  resolved as "full wins".

**"Treat with" only appears where compost changes a number the plugin produces.** It was offered
on sixteen types where every value gave the same projection. Two reasons it can matter:

- **Yield**, via the lives mechanic — herbs, allotments, hops, giant seaweed. No note.
- **Disease chance** — fruit trees, trees and coral, wherever Jagex has published a rate. The
  dropdown stays, and an amber line appears under it once you pick anything but untreated:
  *"Only lowers disease chance here, not yield. Not needed if you are paying for protection."*
  The second sentence is not a general hint — a protected crop survives outright, so the discount
  compost buys is one the payment has already bought.

- **Pass**: no dropdown at all on bush, flower, cactus, hardwood — nothing there responds either
  way, so it was a control that could not move anything.
- **Pass**: set a fruit tree to ultracompost and the projection's survival improves. That is the
  whole reason the control stayed, so if the number does not move the choice is not reaching the
  estimate.
- **Pass**: the note is absent on herbs, where compost raises the yield too.
- **Known gap, not a UI decision**: bushes, cactus and hardwood can be diseased in game, but no
  published rate exists for them, so our model treats them as certain to survive. If a rate turns
  up, adding it to `DiseaseRisk` brings the dropdown back automatically — the rule is derived,
  not listed.

**Farming Guild tiers are enforced.** Not a setting — it is a locked door, so it is treated like
availability.

- **45** entry and eastern wing: cactus, both allotments, flower, bush, compost bin.
- **65** western wing: herb, tree, anima, and the Hespori cave.
- **85** northern wing: fruit tree, spirit tree, celastrus, redwood.
- **Pass**: below 85, the guild's redwood and fruit tree patches do not appear anywhere — not in
  the tabs, not in a run, not in the counts.
- **Boosts are deliberately not modelled**: a boost lasts minutes and a run is planned ahead, so
  treating a boostable tier as open would route you somewhere you can only reach holding a pie.
- **Fail signature**: a guild patch you can genuinely reach going missing. The tier table is in
  `PatchRequirements` and the levels came from the wiki — tell me which patch and which tier.

### 1a-xiv-d. The projection tooltip, and per-group disease — new

**Hover the projection table.** It should open with what the figures are:

> Estimated yield and XP, from your Farming level, gear, diaries, compost and protection.
> Harvest-only runs count the harvest award alone.

then the detected gear, then the disease discount.

- **Fail signature**: *"Paying farmers is not assumed, and would raise this."* That line was
  written before protection existed and was simply false — the estimate has been reading the
  Protect boxes all along. If you still see it, the build did not take.
- **Pass**: it now reads *"Protected patches are already counted as surviving."*

**Disease is discounted per planting group, not per patch type.** This was a real defect and it
only showed with the protected herb split on.

- **The bug**: survival was averaged across *every* herb patch and the one blended figure applied
  to both groups. Trollheim and Weiss cannot be diseased; Ardougne very much can. So the
  protected group was discounted for a risk it does not carry, and the ordinary group was
  credited with safety it does not have.
- **Pass**: with the split on, the protected herb group's projection shows **no** disease
  discount at all, and the ordinary herb group's shows the full untreated discount.
- **Pass**: all three defences move the number — better compost raises it, ticking Protect on a
  crop takes it to 100% for that crop, and a disease-free patch is at 100% regardless.

### 1a-xiv-e. The run anchored to the foot of the sidebar — new

- **Pass**: on a patch tab with only a few rows, the run controls sit at the **bottom** of the
  sidebar rather than directly under the last row. Clicking between a three-patch tab and a
  twenty-patch one no longer moves them up and down the page.
- **Pass**: on a tab long enough to scroll, the run follows the rows as before — with a small gap
  above **Start run** so the controls are not jammed against the last patch row. That gap exists
  only while scrolling; pinned to the foot there is already space above them.
- **How it works, since it is not obvious**: `PluginPanel` adds itself to its scroll wrapper at
  `BorderLayout.NORTH`, which gives it exactly its content height — so the panel had no foot to
  anchor anything to. It is moved to CENTER, and `PageLayout` puts glue between the two sections
  to absorb the spare height.
- **Fail signature**: a scrollbar that never goes away on a short tab. That means the page is
  claiming the viewport's height as its preferred height rather than its content's.

### 1a-xiv-f. Compost and protection backfilled from Time Tracking — new

The plugin learns compost and protection by watching you apply them, which cannot know what
happened before it was installed. RuneLite's Time Tracking has been recording both for years, per
profile, in plain config — so on load they are read and used to fill gaps.

- **Pass, and this is the visible one**: on an account that farmed before installing this plugin,
  patches you have paid for should show the farmer's chathead badge and the compost badge without
  your having done anything this session. The log says
  *"Filled in N compost and protection facts from Time Tracking"*.
- **Pass**: it only ever fills gaps. Anything observed this session wins, because ours is live and
  theirs is whatever was last written. Applying compost should never be undone by a stale record.
- **Fail signature**: a patch showing protection you know you have not paid for. That would mean
  the key is being read for the wrong patch — both use `regionId.varbit`, so a mismatch would show
  up on every patch rather than one.
- **Switching Time Tracking off** simply means no backfill, and nothing else changes.

### 1a-xv. Protecting a crop — new

**In the seed selector**, under the compost dropdown, a **Protect** checkbox appears for any
group whose picked seeds can be protected. Hidden entirely for herbs, which cannot be.

- **The payment item is named, not the crop.** "Protect magic (75 coconuts)", not "(75 magic)" —
  the payment enum exposes both halves through similarly named accessors and the wrong one was
  read. Names come from the game itself rather than from a hand-written list, so a basket reads
  "Basket of tomatoes" rather than "basket tomato 5".
- **Per crop, not per patch type** — one row per picked seed that has a payment. A magic tree
  wants 25 coconuts and a yew wants 10 cactus spines, so one switch for "trees" could not say
  what it would cost. Herbs have no payment, so no row ever appears for them.
- **Pass**: each row names the total **for the whole run**, not for one patch — three plantable
  magic trees reads "Protect magic (75 coconuts)". Hover for the breakdown.
- **The guide agrees with the panel.** Walk to those six trees: the guide should say "plant
  magic" at three of them and "plant yew" at the other three, matching the reward table exactly.
  Before this it said magic at all six. **Fail signature**: the guide naming a crop the table did
  not budget for — say which patch and what each claimed.
- **The allocation follows the payments, which is the point.** Six tree patches, magic and yew
  both picked, plenty of both seeds and only 75 coconuts → the reward table should show
  **3 magic and 3 yew**, not 6 magic. The coconuts cover three trees and the rest fall to the next
  crop you picked. The tooltip says "Covers 3 of 6 patches".
- **Pass, and this is the case to check**: with fewer than that, the label turns **red** and the
  tooltip says "You have 40 of the 75 this run needs". Setting off short means paying for some
  patches and discovering the rest at the last tree, which is the trip this is meant to save.
- **Pass**: before you have opened a bank it says so rather than reporting zero.
- **The loadout agrees.** It asks for the same total and reports the shortfall as *missing*
  rather than as a withdrawal — withdrawing 40 of 75 coconuts is not a run that can be protected.
- **Pass, and this is the point of it**: ticking it stops the reward table discounting that crop
  for disease. A protected magic tree cannot die, so its expected yield should jump.
- **Pass**: the payment appears in the bank loadout **only** when the box is ticked. Unticked, it
  is not asked for — before this, every possible payment was listed whether you wanted it or not.
- **Pass**: at the patch, once something is growing, the guide says *"Pay the farmer 1 coconut to
  protect the magic tree."* with the farmer outlined and the coconut marked in your inventory.
- **Fail signature**: the step never appears → it is silent unless you are carrying the payment,
  deliberately, since telling you to pay with fruit you did not bring is an instruction you
  cannot follow. If the loadout also failed to ask for it, that is the bug.

### 1a-xvi. Bank filtering — now ON by default

Settings → Guided run → *Filter the bank to this run*.

- **Pass**: with it on, opening a bank shows only what the run touches. Turning it **off while
  the bank is open** restores everything immediately, without needing to reopen.
- **Pass**: it shows everything the run touches, not only what is missing — including items the
  leprechaun holds, so you can see they are there and leave them.
- **Pass, and check this one carefully**: every item in the filtered bank is an item you actually
  own. **No dulled or faded entries.** A Bank Tags layout slot is a reservation and Bank Tags
  fills an unfillable one with a faded stand-in, so laying out the whole loadout put a ghost of
  every seed the run wanted and you did not have into the grid — a dulled poison ivy seed for
  someone with none, which reads as the filter claiming they have one. The layout now places only
  what the bank holds. What is missing is missing, and the panel is where you see that.
- **Pass**: with filtering on, the bank items are **not** also highlighted. Both features answer
  the same question, so running both marks the entire visible bank — a wall of colour over a bank
  that had already narrowed itself. Turn filtering off with the bank open and the highlighting
  comes straight back.
- **Pass**: the **seed vault** is still highlighted with filtering on. Nothing filters the vault,
  so its one-at-a-time step sequence is the only thing pointing at the next seed. Fail signature:
  the vault goes dark when you switch filtering on — the stand-down is meant to be per container.
- **Pass**: nothing in the bank is marked in a second colour. Compost and buckets the leprechaun
  is holding used to be marked amber for "leave this"; they are now not marked at all, because a
  highlight over an item reads as *take it* whatever colour it is. He still shows up in the panel
  and in the guide at the patch, which is where the errand actually is.
- **On by default now**, having spent a long time off. The reason it was off still describes the
  risk — a wrong highlight is ignorable, a wrong filter *hides* things — but being off proved
  undiscoverable, and overflow no longer disappears. Highlighting is unaffected either way.
- **Pass**: turning it off leaves the bank completely alone, including the layout.
- **Pass**: press **Stop run** with the bank open. Highlighting stops immediately and stays
  stopped through closing and reopening the bank. It used to persist — the overlay asked which
  patch types were ticked and never whether a run was under way, so there was no way to make it
  stop short of clearing the ticks. Filtering follows the same rule now: no run, no filter.
- **Fail signature**: nothing filters → Bank Tags is unavailable. `client.log` says so once at
  startup: *"Bank Tags is unavailable, so run filtering is off. Highlighting is unaffected."*
  **With Bank Tags on, that line must not appear.** It did, for the whole first attempt at this
  feature, and it is why filtering never worked once: `BankTagsPlugin` binds `BankTagsService`
  inside its own injector, so no form of injection can reach it from here. Constructor injection
  fails loudly — the plugin does not load at all — and optional field injection fails silently,
  which is worse, because the feature looks present and does nothing. Both are now gone: the
  service comes off the Bank Tags plugin instance, and the tag manager out of its injector.
- **If it still does not filter, the log now names which gate stopped it**, including the one that
  cannot be seen from here: after asking Bank Tags to open the tag we ask back with
  `getActiveTag()`, because `openBankTag` returns void and declines quietly in several places.
  A mismatch logs *"Bank Tags did not take the filter: asked for X, active tag is Y"*. Calling it
  and it taking are different facts and only the second is the feature working.
- **It cannot leave anything behind.** The tag is virtual — membership is asked live rather than
  saved — so it never appears in your own bank tag list and nothing survives the plugin stopping.
- **Close the bank and check the chatbox.** It must read *"Press Enter to Chat..."* again.
  Opening a bank tag puts the client into bank-search input mode, and that input **is** the
  chatbox; leaving the tag open when the bank closes strands it showing a bare `*` cursor in
  every scene, with no bank on screen to suggest what caused it. The filter now closes on the
  bank interface closing, not only on the setting being switched off.

### 1a-xvii. Harvest-only runs — new

**No seed is needed, and the projection still works.** Both were wrong: a harvest-only run asked
for a seed it has no use for, and priced itself at zero when you did not give it one.

- **Pass**: tick *Bush (H/O)* with no bush seed picked. No red "No seed picked for: bush" under
  the boxes, and the projection below shows the berries and experience the trip is actually
  worth.
- **Pass, and this is the number to sanity-check**: the figure counts the **harvest** award only.
  A banana tree pays 1,750 for the health check against 10.5 per banana; if a trip to pick fruit
  reads in the thousands, the plant and check awards have crept back in.
- **Pass**: ticking the full *Bush* line as well as *Bush (H/O)* does still want a seed — the full
  run plants, so the warning is right there.
- **Fail signature**: the table disappearing entirely when only harvest-only lines are ticked.
  That is the old behaviour, where an empty seed selection hid it.

The run list now offers more lines than there are patch types: **Herb (protected)** when you have
any, plus **Bush (harvest only)** and **Fruit tree (harvest only)**.

- **Pass**: ticking *Fruit tree (harvest only)* builds a run that visits **only trees with fruit
  on them**. An empty fruit tree patch is not a reason to travel when you have said you are not
  replanting.
- **Pass, and this is the one that matters**: at the patch the guide says "Harvest the papaya"
  and then **stops**. No clear, no compost, no plant. On a fruit tree those steps would mean
  digging up something that took two days to grow.
- **Pass**: ticking the ordinary *Fruit tree* line as well gives the full cycle again. Ticking
  both is a contradiction, and the full run wins — it does everything the harvest-only run does
  and more, so nothing asked for is skipped.
- **Pass**: the list is two options per row, and the variants read as **(H/O)** — spelled out,
  "Fruit tree (harvest only)" made two columns want 290px of a 225px sidebar. What it means is on
  the hover. The render test refuses to let a too-wide label through.
- **Your existing selection survives.** A full run over an unsplit type is stored under the same
  key it always was, so previously ticked types stay ticked.

### 1a-xviii. Farming contracts — new, and the largest single untested piece

Everything here is written from the client sources rather than from play. The three lines the
capture matches on are quoted below; if any of them differs by a character in game, the capture is
silent — and silent is indistinguishable from "no contract", which is why the first check is a log
line rather than anything on screen.

**Start here, before anything else.** With Time Tracking on and a contract assigned, the log on
login carries one line: `Farming contract: <crop>; awaiting hand-in: <crop or nothing>.` If it
reads `Time Tracking is switched off, ...`, that is the other branch and it is telling the truth.

- **Pass**: with a contract assigned, the sidebar grows a tab showing **Guildmaster Jane's face**,
  pinned **first** in the strip whatever crop the contract wants. Hovering names the crop. A blank
  square there means her portrait did not load — `FarmerIconTest` should have caught that, and the
  fallback is the crop sprite with an amber diamond on it.
- **Pass, and this is the reservation working**: the ordinary **Herb** tab **no longer lists the
  Farming Guild's herb patch**. It has moved, not been copied — seeing it on both tabs is the
  failure this whole design exists to prevent, and it would show up later as the estimate promising
  a snapdragon in a patch the contract had already claimed.
- **Pass**: the contract tab shows **exactly one seed**, already highlighted as picked, and
  **clicking it does nothing**. If you do not own the seed it is still shown, greyed.
- **Pass**: the compost and protection dropdowns are still there on the contract tab, and a
  brand-new one **inherits whatever you treat herbs with** rather than resetting to untreated.
  Changing it must **not** change the ordinary Herb tab's compost.
- **Pass**: the run list has a **Farming Contract** line, **last**, on a row of its own — not
  "Cactus (contract)", and not sitting with its type. Check the pairs above it are still side by
  side: inline, the contract line split `Cactus` from `Cactus (H/O)`.
- **Pass**: ticking it and opening the bank shows the contract seed in the loadout, reasoned as
  **"Guildmaster Jane's contract"** — and marked missing, in words, if you do not own one.
- **Pass, both reported from play**: no *"No seed picked for: cactus"* under the boxes when the
  contract tab is showing a cactus seed; and if you protect that crop on its ordinary tab, the
  contract tab's protect box is **already ticked**. Unticking it there must stick across a relog
  without unticking the ordinary tab's.
- **Pass, and this is the ordering that matters**: arriving at the Farming Guild, the **first**
  instruction concerns the contract's patch, whatever else is nearer. Any other patch being
  serviced first takes the only patch of that type in the guild and costs a full growth cycle.
- **Pass**: with everything at the guild dealt with and a contract grown, the last step is
  **"Hand your ranarr to Guildmaster Jane for the contract reward."**, with **Jane** outlined —
  not the leprechaun, and not the patch.
- **Pass**: once handed in, the next step is **"Ask Guildmaster Jane for a new farming contract
  before you leave."** No difficulty is suggested, deliberately — easy, medium and hard draw from
  different pools and choosing for you is not this plugin's job.
- **Pass, and this is the loop the design turns on**: take a new contract *while still standing in
  the guild*. If its patch is free, the guide should within a tick be telling you to plant it —
  no run restart, no re-entering the stop. If it is not free, the panel says so in grey:
  *"...cannot be planted on this run - the patch it wants is not free."*
- **Pass**: turning **Include the farming contract** off in the guided-run settings returns the
  guild's patches to the ordinary run, removes the tab and the run line, and stops both Jane steps.

**The four lines the capture depends on**, all from `FarmingContractManager` and
`TimeTrackingPlugin`:

| Moment | Line |
|---|---|
| Assigned | `We need you to grow` / `Please could you grow ... for us?` |
| Grown | `You've completed a Farming Guild Contract. You should return to Guildmaster Jane.` |
| Handed in | `You'll be wanting a reward then. Here you go.` |
| Config | group `timetracking`, key `contract`, the harvested item's id as a string |

- **Fail signature — no tab, no run line, nothing in the log.** The config key is not being read.
  Check `timetracking.contract` in the profile config; if it holds a number and the plugin says
  nothing, `Produce.getByItemID` did not resolve it.
- **Fail signature — the tab appears but the guild's herb patch is on *both* tabs.** `groupFor` is
  answering two things for one patch, and the estimate will be double-counting it.
- **Fail signature — the contract stays assigned after the crop finishes growing.** Expected, and
  handled: Time Tracking clears its key on the completion message and our capture records the
  hand-in. If instead the tab lingers and the guide keeps saying to plant it, the completion
  message did not match and the derivation from patch state did not fire either.
- **Fail signature — the hand-in step never appears for a contract that ripened while you were
  logged out.** That case has no message at all and relies entirely on the patch-state derivation:
  a guild patch holding the contract crop, `isReady()`. Check the patch is actually cached.
- **Fail signature — a warning in the log**: `Guildmaster Jane asked for "x", which is not a crop
  this build knows.` A game update added a contract crop; regenerate the farming data.

### 1a-xix. Seeds in your pack stay in the seed list — new, a fix

Reported from play: ranarr seeds in the inventory were missing from the herb seed list, and banking
them and taking them back out fixed it. `load()` was clearing the inventory — which is deliberately
not persisted — and restoring only what config held.

- **Pass**: log in holding seeds you have not touched, open the sidebar, and they are in the seed
  list without banking anything.
- **Pass**: switch the plugin off and on again while logged in and holding seeds. They should still
  be listed — this is the case `relearnFromClient` covers, where no container event ever fires.
- **Fail signature**: an empty or short seed list that fills in the moment you open a bank. That is
  the same bug, and it is timing-dependent, so try it a few times.

### 1a-xx. Three fixes from the contract session — new

- **Pass**: the contract tab's tooltip reads **"Farming Contract - Potato cactus"**, not
  "Cactus (contract) - ...". The type was saying twice what the crop already says.
- **Pass, reported from play**: stand at the Farming Guild bank with a contract run ticked and no
  seed or payment withdrawn. Start run should say **"Collect your supplies"**, not send you to
  clear the patch. The rule it used to hit — *standing on work beats going shopping* — is about
  travel, and the guild's bank, vault and patches are all one region. The `Run planned:` line now
  says `... which is a stop on this run and has a supply point in it`.
- **Pass**: the same rule must still hold where it was right. Stand at Ardougne's patches with
  everything already carried and Start run should **not** route you to a bank.
- **Pass, reported from play**: with a protection row showing *"Open a bank to see whether you
  have 24 coconuts"*, open a bank. The row should update **without** switching tabs. Opening a
  bank that changes nothing should not make the panel flicker.

### 1a-xxi. Closing a bank with filtering on — new, a crash fix

This killed the client. `WidgetClosed` is posted from inside the client's own script execution, and
closing the Bank Tags filter from there runs a script within a script — which trips
`AssertionError: scripts are not reentrant`, kills the `Client` thread, and leaves the window up
with a dead game behind it. It read as a crash once and as a hang once; same bug both times.

- **Pass**: with *Guided run → Filter the bank to this run* **on** and a run under way, open a bank
  and close it. Repeat a few times, including closing by walking away and by pressing Escape.
  Nothing in the log, and the game keeps running.
- **Pass**: the chatbox still says *"Press Enter to Chat..."* afterwards. The whole reason the
  close exists is that an open bank tag strands the chatbox in bank-search input mode, and the fix
  defers that close by a tick — so this is the thing most likely to have been broken by it.
- **Pass**: the bank comes back unfiltered next time it is opened.
- **Fail signature**: `java.lang.AssertionError: scripts are not reentrant` in the log with
  `BankFilter.onWidgetClosed` in the stack. That is the original bug, unfixed.
- **Fail signature**: the game freezes with the window still up and **nothing** in the log after
  the assertion. Same bug — the client thread is dead, not blocked. A `jstack` will show no
  `Client` thread at all, which is how to tell it apart from the teleport deadlock.

### 1a-xxii. Tree seeds in the bank — new, a fix

A tree crop is two items, the seed and the sapling, and the bank-facing code only ever named the
sapling. With a magic/yew/palm/teak run ticked and the seeds (not saplings) in your bank:

- **Pass**: the loadout lists the seed as **withdraw**, not missing.
- **Pass**: with highlighting on, the seed is marked in the bank.
- **Pass, and this is the one that was worst**: with *Filter the bank to this run* on, the seeds
  are **visible**. They used to be hidden — the filter removing the exact item it was there to
  show.
- **Pass**: if you own the seed but no sapling, the row's hover says it needs potting first.
- **Pass**: a sapling you already potted is still shown and marked. Both forms count.

### 1a-xxiii. The seed vault — new

Reported from play as "not working at all, just the normal interface", and it was never built
rather than broken: both the highlight and the filter were written against the bank interface
only. The run does route you to the vault — `getSupplyTargets` sends you there rather than to a
bank when that is where the seeds are — so marking them in one and not the other was half a
feature.

The vault is marked **one step at a time**, unlike the bank. It is divided by seed type and a
category hides the rest, so lighting up everything the run needs would mostly light up things that
are not on screen.

- **Pass**: with a run ticked whose seeds are in the vault, open it. **Exactly one thing** is
  outlined — the next seed if it is on screen, otherwise the category holding it.
- **Pass, and this is the sequence**: click the outlined category, and the seed inside it lights
  up. Withdraw it, and the outline moves to the next seed's category. Group, seed, next group,
  next seed, with nothing to keep your place in.
- **Pass**: nothing is tracked, so it cannot get out of step. Click around the categories at
  random and the outline is always either the next seed or the way to it.
- **Pass**: the outlines stay inside the vault's list when it is scrolled — nothing floating over
  the chat box, which is the bug the clipping in the bank version exists for.
- **Pass**: no run under way means no marks, the same rule the bank follows.
- **Pass**: the category is drawn as a **box around the tab**, not an item outline. It is a label
  with no item id, so the item-outline path drew nothing at all — which is how "the tabs aren't
  highlighting" looked.
- **Pass**: scroll the vault. Nothing paints outside the list — highlights are clipped to the
  list **intersected with its parent**, because a scrolling container reports bounds for all its
  contents rather than for the part on screen, and clipping to the list alone let a scrolled-off
  row paint over the chat box.
- **Fail signature — the seed lights up but the category never does.** The label match failed. It
  matches the vault's own wording against the patch type's name ("Herb", "Fruit tree"), loosely
  enough for "Allotments" vs "Allotment" — but if the vault words them differently, the Widget
  Inspector on `CATEGORY_LIST` and `LEFT_LIST` will say what they actually read.
- **Filtering is bank-only and will stay that way.** Bank Tags is a bank feature and has no notion
  of the vault; there is no plugin-facing equivalent anywhere in the client, and filtering it
  ourselves would mean hiding widgets, which this plugin deliberately does not do. The vault also
  needs it least: it holds only seeds, and the game already groups them by category with its own
  search, favourites and a built-in **contract seeds** view.
- **Fail signature — nothing is outlined at all.** The item container is
  `InterfaceID.SeedVault.OBJ_LIST`, not `LIST`. The vault has several list-shaped children and only
  that one holds item widgets; the Widget Inspector will confirm which is which in seconds.

### 1a-xxiv. The bank and the vault are highlighted on the supply leg — new, a fix

Reported from play: during a guided run the panel says *"Collect your supplies"* and lists what to
take, but nothing on screen was marked. `GuideOverlay` treated "no step" as "travelling", and the
supply leg produces no steps — so it only ever highlighted house teleport furniture.

- **Pass**: start a run that needs a bank trip. At the bank, the **booth or chest is outlined**.
- **Pass**: in the Farming Guild, the **seed vault is outlined too**, alongside the bank — a single
  trip can genuinely want both, and `getSupplySources` says so with `[BANK, SEED_VAULT]`.
- **Pass**: the outlines stop the moment the bank leg ends, i.e. as soon as you open the bank.
- **Pass**: deposit boxes are **not** marked. They only take things; this leg is about getting
  things out.
- **Fail signature**: nothing outlined at a bank that clearly has booths. Matching is on the
  object's "Bank" action via its impostor composition — a booth's base id carries neither name nor
  actions, so a missing impostor lookup would show exactly this.

### 1a-xxv. The teleport list — new

Settings → Guided run → *Teleport items*. A comma-separated list of item names, rendered as a text
area the same way Ground Items' lists are, defaulting to every teleport the plugin already knows.

- **Pass**: the setting arrives pre-filled and sorted, naming things like *Ardougne cloak 2* and
  *Explorer's ring 2*. It is derived from `TeleportItems`, so it cannot drift out of step with the
  table.
- **Pass**: add something the table has never known — a games necklace, a house tab — and with it
  in your bank it appears in the loadout under Teleports, reasoned **"On your teleport list"**, and
  is grouped with the teleports in the filter and the layout's `T` region.
- **Pass**: an item on the list you do **not** own says nothing at all. No advice to go and buy it.
- **Pass**: emptying the list entirely is fine — you get only the teleports the plugin already
  knows reach a farming region.
- **Pass**: an item the region table already offers keeps its own reason ("Reaches Catherby"),
  not the generic one. The table knows where it goes, which is the better answer.
- **Fail signature — a listed item you own never appears.** Matching is on the game's own item
  name, read off your bank when it is opened, so the spelling has to match what the game calls it,
  brackets and charges included: `Games necklace(8)`, not `Games necklace`. Open a bank once first,
  since that is when the names are learned.

### 1a-xxvi. Bank responsiveness — new, a performance fix

The loadout was rebuilt four times a tick with a bank open, and each rebuild replanned the run once
per stop. On a 28-stop run that was around a hundred synchronised replans a tick.

- **Pass**: open a bank mid-run with filtering on and a large run ticked. It should not stutter,
  and moving items should not lag.
- **Pass**: the filter and the highlights still update when the run changes — the cache is keyed on
  the tick, so a change is visible on the next one.
- **Fail signature**: the bank feels sluggish in proportion to how many stops the run has. That is
  the shape of the old bug and means the caching is not being hit.

### 1a-xxvii. Two seeds for one patch type — new, a fix

Reported from play as ending up with far too many of the second seed. Every picked seed asked the
bank for a **full** patch count, so two herbs over eight patches wanted eight of each — two runs'
worth for a one-run trip. `docs/TODO.md` had it listed as a known open bug.

- **Pass**: pick two herb seeds with, say, four actionable herb patches. The loadout asks for
  **four seeds in total** across the two, not four of each.
- **Pass**: the split matches what the guide plants and what the projection prices — all three now
  read the same `SeedAllocation`.
- **Pass**: a seed with nothing left to plant in is left off the list rather than listed as zero.
- **Pass**: tree seeds still appear even when you own none potted. The bank list allocates on
  either form, deliberately unlike the guide, which can only plant a sapling — you pot on the way.

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

## 3f. The leprechaun's store — new, and the most checkable thing here

His store turns out to be **varbits**, not an interface, so the plugin can read it any time —
standing in a bank, before the run starts, without clicking him. That makes this quick to check
and quick to disprove.

**Setup**: open the tool leprechaun's store once and note what is actually in it. Then open a
bank and look at the loadout.

- **Pass**: tools he is holding read *on site* (amber), tools only in the bank read as
  withdrawals (cyan), and a tool you own nowhere is called **missing** with a tooltip pointing
  at a farming shop rather than a bank.
- **Pass**: the compost tier you chose is amber only if **he has that tier**. Ordinary compost in
  his store does not satisfy a choice of ultra — they are separate slots.
- **Pass**: magic secateurs he is holding read as on-site rather than as a bank withdrawal, with
  a tooltip saying to take them out at the first patch (the +10% only counts while carried).
- **The number to check**: `client.log` may print a line about a slot summing to more than its
  base varbit can express, asking what the store screen reads. **If you see it, tell me the
  number on screen.** Each tool has a base varbit and one or more `EXTRA` ones, and whether those
  are extra storage or high bits of one number is not documented. Nothing today depends on it —
  everything asks "is there at least one" — but a count is only worth showing once it is known.
- **Fail signatures**:
  - Everything reads missing on a stocked account → the varbits are not being read at all; the
    store fills on the first tick after login, so this would be every session.
  - A tool reads on-site that his store does not have → wrong varbit for that tool, and knowing
    which one narrows it to a single line.

**The case this exists for** is the one you cannot test without breaking something on purpose:
bank every rake you own and start a herb run. The run should open with a **bank leg** for it,
where before it would have set off and found the first weedy patch unworkable.

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

- ~~**A diseased patch, with Geomancy cast within ~20 minutes.**~~ **Done, 2026-08-05.** Caught
  on a cast with a dead patch and a healthy one of the same crop in the same snapshot, which is
  what made it decodable rather than guessable. The whole rendering is in `docs/NOTES.md`.
  The probe is switched off again — it is a development aid, not a feature, and it writes a
  ~1MB dump per cast. Turning it off now takes effect immediately rather than at the end of the
  cast in flight.
- **The rename decision**: Farmers Almanac / Farmer's Almanac / Farmers' Almanac. It lands in
  the descriptor, the repo name and the Hub listing. Never rename the config group
  `dooglemaps` — that orphans the harvest history, the one thing that does not come back.

### 1a-xviii. After the code review — what to look at in the client

Mostly things automated tests cannot see. The correctness fixes are covered by the suite; these are
the ones that need eyes.

- **Settings → Guided run reads in a sensible order.** Pass: *Guide me through a run*, *Include the
  farming contract*, then the four bank settings together, then the four highlight settings
  together (style, colour, thickness, feathering), then the three "things the client cannot detect"
  at the bottom. It used to render with the highlight settings split across four positions and the
  bank layout map separated from the filter it depends on — thirteen settings shared seven
  positions and RuneLite breaks ties alphabetically. `ConfigLayoutTest` fails the build on a repeat.
- **Hover anything.** Pass: every tooltip wraps to a readable column. **Fail signature**: one long
  line running off the side of the client. The worst were the reward table's gear breakdown, the
  Stats headline and a patch's progress bar. Nothing sets its own width now — see `Tooltips`.
- **The reward table has a caption under it**: *"Counts the seeds going in, not what you pick on the
  way round."* That sentence used to be paragraph two of a thousand-character hover, which is to say
  invisible. It appears and disappears with the table.
- **A patch tooltip's bonus list is bullets, not a sentence.** Pass: *"At level 80, with:"* then one
  line per bonus. The line that matters is *"no diary here yet"* — it explains why two identical
  patches differ, and as the fourth clause of a run-on sentence nobody read it.
- **The on-screen step panel.**
  - Pass: with exactly one step over the limit it shows **the step**, not *"+ 1 more here"*. The
    collapse line costs the same height as the step it hides, so it only earns its place from two
    onward. Same for *"via …"* transports, which also used to read *"+ 1 more hops"*.
  - Pass: follow-ups read *"then rake the patch"* and then bare lines, not *"then"* four times.
  - Pass: the title reads *"Catherby - farm run"*, place first.
  - Pass: at the bank the heading is *"Withdraw:"* above the list, not *"Collect your supplies."*
    followed immediately by the supplies.
- **Almanac rows still show the right compost badge and state while a patch changes.** Pass: nothing
  flickers or shows a stale badge as a crop advances. `PatchStateStore` now hands out copies rather
  than the live object every reader was sharing with the capture thread — if anything looks
  *different* here, that difference was the race.
- **Stop a run with the bank open.** Pass: highlighting stops immediately, every time. The run flags
  are `volatile` now; before, a Swing-thread write had no guaranteed visibility to the client thread
  that reads them, so this was intermittent by construction.
- **Hide a location, then check another tab.** Pass: its patches are gone from every tab and from
  the "Patch status (n/m)" counts. The 36-case region-to-setting switch is now derived from the
  region name, so a region added by Jagex cannot get a toggle that silently does nothing.

### 1a-xix. Seed vault, withdraw counts, and the leprechaun's buckets

Four reports from play, all needing in-client eyes.

- **The vault only points at seeds that are in the vault.** Pass: standing at the seed vault with,
  say, ranarr in your *bank* and nothing relevant in the vault, **nothing is highlighted**. Was:
  the step sequence picked any seed marked "withdraw" — which mostly means the bank — failed to
  find it among the vault's contents, and fell through to outlining the Herb category. That points
  at a drawer to fetch something not in it, and then stays there forever, because the step can
  never be satisfied from the vault.
- **The category is outlined when the seed is in another one.** Pass: with a run seed genuinely in
  the vault but its category not on screen, that category's label is boxed.
  - **This is the one that most needs checking**, because the fix was made without being able to
    see the widget tree. Only `CATEGORY_LIST` and `LEFT_LIST` were searched, and the labels are not
    in them; the search now covers `CATEGORY_HEADERS`, `TEXT_LIST` and `CATEGORY_LINES` too, and
    walks four levels deep rather than one, because a list row usually holds its text in a child.
  - **Fail signature**: still nothing. `client.log` at debug now says *"No widget in the seed vault
    is labelled X"*, once per seed, which distinguishes "we looked and the label is not there" from
    "we never looked". If that line appears, the label lives somewhere else again and the fix is
    another id in `CATEGORY_LISTS` — not more depth.
- **Seeds and payments carry a number.** Pass: a marked seed or protection payment shows how many
  to take, in the game's stack-count yellow, under the slot's own quantity. Only those two, and
  only above one — everything else on the list is "bring the thing you own", and a "1" over each
  would be four more numbers and no decisions. The number that earns this is the payment: four
  magic trees is a hundred coconuts, and getting it wrong is discovered at the fourth tree.
- **Empty buckets highlight *your* inventory, not his store.** Pass: at the leprechaun with empties
  in your pack, the buckets **in your inventory** are outlined. Was: his store's bucket slot, which
  lights up the thousand he is already holding — reading as "take these" at the exact moment the
  instruction is "give him yours".
  - Worth knowing this assertion has now been wrong twice in opposite directions, and the reason:
    the first fix reasoned from *which interface opens*. His store does open, and it does list his
    contents, but the click is on your own inventory underneath it. The interface being open and
    the grid being clicked are different questions.
  - `LeprechaunErrandOrderTest` pins all three leprechaun steps now — note, buckets, withdraw —
    rather than only the two that were in dispute.

### 1a-xx. Harvest-only loadouts, and the supply leg as two independent jobs

- **A harvest-only run asks for nothing to plant.** Pass: tick *Fruit tree (H/O)* and the loadout
  offers **no saplings, no compost, no protection payments** — only the things you still want on a
  picking trip, which is the axe for a dead tree, the teleports, and the seed box and herb sack for
  what you bring home. Was: palm saplings, because the planner correctly narrows a harvest-only
  stop to ripe patches and the loadout then read "four fruit tree patches" as four patches to plant
  in. Nothing in the loadout had ever been told the difference.
- **The bank and the seed vault are separate jobs, in either order.** Pass: the step panel lists
  *"From the bank: …"* and *"From the seed vault: …"* as two lines. Was: everything under
  *"From the bank:"* regardless — which names the wrong container, and reads as one task, so
  opening the bank looked like the whole of it.
- **Opening a bank no longer ends the leg.** Pass, and this is the one worth testing carefully:
  with your seeds in the **vault**, standing in the Farming Guild, open the bank chest and withdraw
  the payments. The run must **stay** on the supply leg until the seeds come out of the vault too.
  Was: the first bank container event ended the leg outright, so the vault three steps away never
  got its turn and the run walked to the patches with nothing to plant.
  - It completes on withdrawal from **either** container, and the tick drives the check, because
    nothing about the vault fires a bank event.
  - **Fail signature to watch for**: the run *never* leaving the bank. That is the failure mode of
    this fix and it was caught in review — "no seed picked for anything" makes the trip needed but
    is not a state banking resolves, so the leg's condition is deliberately narrower than the one
    that decides whether to open at a bank at all. `RunPlannerTest` pins both directions.
- **Something you own nowhere is announced, not waited for.** Pass: with a picked seed you have
  none of, the panel says *"Skipping ranarr seed - you have none."* and the run proceeds. Was
  *"Not found anywhere: …"*, which left you to work out what the run would do about it — and the
  answer, carrying on without it, is the part worth stating.

### 1a-xxi. Protected patches, finished contracts, and the teleport list

- **Switching every protected patch off does not delete the tab.** Pass: on the protected herb
  tab, switch off every row. It reads **0/N**, the rows stay visible washed red, and clicking one
  puts it back. Was: **0/0** with nothing to click, and the patches reappeared under ordinary
  herbs — because the split's existence was counted from *available* patches, so switching them
  all off made the group stop existing, which moved them to the plain group, which is why they
  "popped back up in the regular herbs". A switched-off patch is a choice within a group and must
  never be able to delete the group.
- **A finished contract asks for no seed.** Pass: with a grown cactus contract, start a run with
  only the contract ticked. The loadout asks for **no cactus seed** — the trip is harvest, hand in,
  take the next one, and only then is there anything to plant. Was: withdraw a cactus seed, for a
  cactus standing there finished.
  - The check is *what is actually growing in the guild patch*, not the hand-in flag. A contract
    that ripened while you were logged out sends no chat message so nothing captured it, but the
    patch is full either way.
  - **Pass, the loop**: hand in, take the next contract, and its seed appears on the same trip
    without restarting the run — taking the contract moves its patch into the contract group
    immediately, so the loadout picks it up on the next tick.
- **The teleport list is a filter, not a supplement.** Pass: with a short list, the bank offers
  **only** those teleports even for teleports the plugin knows reach your stops. Was: the table
  offered per-region teleports regardless, so cutting the list down changed nothing — while the
  setting's own description said "cut it down to the ones you actually use".
  - The travel hint follows the same list. A hint naming something the loadout never told you to
    bring is an instruction you cannot follow.
  - An empty list still means "no opinion" and falls back to everything the table knows.
- **New defaults.** Bank layout is three rows of seeds and three of gear, using all eight rows;
  the teleport list is thirteen items rather than all twenty-eight. **Existing installs keep what
  they have** — a default only applies where nothing is stored, so anyone who has edited either
  setting is unaffected.

### 1a-xxii. Layout separators, the contract chain, and compost on flowers

- **The bank layout map accepts what you were shown.** Pass: paste
  `TTT.SSSS\nTTT.SSSS\n...` (with literal backslash-n) into the layout setting and it lays out
  identically to the real-newline form. Also `/` between rows, and a stray trailing `\` on a row.
  - The cause is worth knowing: RuneLite stores settings in a `.properties` file, where
    `Properties.store` writes a real newline as the two characters `\n`. That round-trips
    correctly — so the setting *worked* — but the escaped form is what you see, and typing it back
    failed silently: eight rows became one 71-character row, validation rejected it, and it fell
    back to the default with only a log line. Confirmed by reading the stored value directly.
- **A finished contract is harvested before it is handed in.** Pass: with a grown cactus contract,
  the guide says **pick the cactus first**; the hand-in appears only once the patch is empty. Was:
  "hand your cactus spine to Guildmaster Jane" while the cactus was still in the ground — an
  instruction that walks you past the thing she wants.
- **The contract patch stays first even after it finishes.** Pass: with herb, herb (protected) and
  the contract all ticked, arriving at the guild puts the **contract patch first**, not the herbs.
  Was: herbs first, because the completion message clears the contract key and the ordering rule
  asked `hasContract()` — so the patch lost its priority at exactly the moment it mattered.
- **Jane comes before the rest of the guild.** Pass: once the contract patch is clear, the hand-in
  and "ask for a new contract" jump to the **front** of the step list, ahead of the guild's other
  patches. That is what makes the chain fit in one trip: hand in, take the next, plant it. The old
  rule put both last, so every herb done first was a step further from starting a chain whose last
  link expires when you leave.
  - **Now fixed**: the run does divert back for the new contract's seed. See 1a-xxiii.
- **Flower patches offer compost.** Pass: the flower tab has a *Treat with* dropdown, the run banks
  the buckets, and the guide applies them. Flowers can be diseased and compost cuts the chance by
  50/80/90% like anywhere else — the control was hidden because the plugin gated it on whether a
  *published rate* exists, which is a different question. Same fix reaches bushes, cacti, hardwood,
  celastrus, spirit trees, mushrooms and belladonna, all of which had the same gap.
  - **Pass**: the note under it reads *"Lowers disease chance, which is the only protection these
    get - no farmer will watch them. The projection cannot show it: the rate is unpublished."* The
    projection deliberately does not move: Jagex publishes rates only for herbs, fruit trees, maple,
    magic and coral, and inventing one would be a made-up number.

### 1a-xxiii. The tree contract chain, end to end

Six defects, all found from one reported session: a yew contract taken in the Farming Guild with a
grown, unchecked magic tree standing in the patch it wanted. Worth running as one sequence, because
that is how they were found and each one hid the next.

**Setup**: hand in a contract, take one for a tree crop, with a grown but **unchecked** tree of a
different crop in the guild's tree patch. Tick Farming contract. Keep the sapling in the seed vault
and the axe and payments in the bank.

- **The bank and the vault are both outlined, and the line goes to one of them.** Pass: whichever
  the path is drawn to is highlighted; if the trip needs both, both are lit.
  - Was: the seed vault outlined and every bank booth dark, while Shortest Path drew the line to
    the guild's bank chest. `GuideOverlay.marks` enforced "the vault or the banks, never both" —
    true when written, and untrue since `supplyTargetsFor` was changed to hand over both. The unit
    test asserted the broken rule, so the suite agreed.
  - **Fail signature**: two lit places when the run only wants one → `getSupplySources` is
    reporting a source nothing needs; check the `Run planned:` line for what it thinks is short.
- **The contract's sapling is on the withdraw list under the right container.** Pass: *"From the
  seed vault: yew."* Was: *"From the bank: yew"*, with the vault outlined — the loadout and the
  planner resolving the same question two different ways.
- **The run will not leave the bank without the axe or the payments.** Pass: the supply leg stays
  open until the axe and the cactus spine are in the pack. Teleports and yield gear must **not**
  hold it — leaving those behind is a choice, and being pinned at a bank for one would be the
  plugin refusing to let you play.
  - Was: the leg ended on seeds and bank-only tools alone. `ToolNeeds` has never known about axes,
    so a tree contract could close the leg and send you to check a grown tree bare-handed.
- **The tree gives three instructions, not one repeated forever.** Pass, in order:
  *"Check the health of the magic."* → *"Chop down the magic."* → *"Dig up the magic stump."* →
  compost → plant the yew.
  - Was: *"Harvest the magic."* at the checked tree, *"Harvest the magic."* again at the stump, and
    for ever after. The game gives those two states different varbit values (61 and 62 for magic)
    and `PatchRules` decodes them identically — same crop, same `HARVESTABLE`, same stage. Nothing
    the player clicked changed the state being tested, so the patch never came out and the stop
    never finished.
  - The table is **generated** from RuneLite core and could not be edited, so the difference is
    derived from its shape instead: a stump is a stage-0 harvestable value whose two predecessors
    are the checked tree and the growing one. `TreeStumpTest` walks every value of every patch,
    including the six-wide willow block at 192–197 that broke the first version of the rule.
  - **Fail signature**: told to dig a bush or a fruit tree you have picked once → the stump rule has
    escaped its guard onto a family with counting harvest states.
  - **Also check**: a *harvest-only* tree run stops after the chop and leaves the stump standing. It
    should not offer to dig, and the stop should still finish.
- **Nothing you are already carrying is on the withdraw list.** Pass: teleports, cloaks and rings in
  your pack read as held, on a fresh login **and** after enabling the plugin mid-session without
  touching anything.
  - Was: `CarriedItems` was fed only by container events, and the client re-sends a container when
    it *changes* — so a pack nobody had touched was never described, and everything in it read as
    missing. It cleared as soon as any item moved, which is what made it look like a display glitch.
  - **This is the one to test by toggling the plugin off and on while standing still**, since that
    is the case that was broken and the one a normal session never reproduces.
- **The house tablet is offered at all.** Pass: with the default teleport setting, a banked
  *Teleport to house* appears on the list.
  - Was: never, for anyone who had not edited the setting. The default shipped the name
    *"Teleport to house tablet"*; the game calls the item *"Teleport to house"*, and a name matching
    nothing fails silently by design. Settings entries are matched against our own labels as well as
    the game's now, which also fixes *"Skills necklace"* against `Skills necklace(6)` and
    *"Rune pouch"* against `Divine rune pouch`. Locked (trouver) pouches are in the table too.
