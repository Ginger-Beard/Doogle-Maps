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

### 1a-xvi. Bank filtering — new, and off by default

Settings → Guided run → *Filter the bank to this run*.

- **Pass**: with it on, opening a bank shows only what the run touches. Turning it **off while
  the bank is open** restores everything immediately, without needing to reopen.
- **Pass**: it shows everything the run touches, not only what is missing — including items the
  leprechaun holds, so you can see they are there and leave them.
- **Off by default on purpose**, and worth understanding before judging it: a wrong highlight is
  ignorable, but a wrong filter *hides* things and you cannot see what is missing. Highlighting
  works either way and is unaffected.
- **Fail signature**: nothing filters → Bank Tags is unavailable. `client.log` says so once at
  startup: *"Bank Tags is unavailable, so run filtering is off. Highlighting is unaffected."*
  **With Bank Tags on, that line must not appear.** It did, for the whole first attempt at this
  feature, and it is why filtering never worked once: `BankTagsPlugin` binds `BankTagsService`
  inside its own injector, so no form of injection can reach it from here. Constructor injection
  fails loudly — the plugin does not load at all — and optional field injection fails silently,
  which is worse, because the feature looks present and does nothing. Both are now gone: the
  service comes off the Bank Tags plugin instance, and the tag manager out of its injector.
- **It cannot leave anything behind.** The tag is virtual — membership is asked live rather than
  saved — so it never appears in your own bank tag list and nothing survives the plugin stopping.
- **Close the bank and check the chatbox.** It must read *"Press Enter to Chat..."* again.
  Opening a bank tag puts the client into bank-search input mode, and that input **is** the
  chatbox; leaving the tag open when the bank closes strands it showing a bare `*` cursor in
  every scene, with no bank on screen to suggest what caused it. The filter now closes on the
  bank interface closing, not only on the setting being switched off.

### 1a-xvii. Harvest-only runs — new

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
- **Pass**: the list is one option per row now. Two columns clipped "Fruit tree (harvest only)"
  at the sidebar's 225px, which the render test refuses to let through.
- **Your existing selection survives.** A full run over an unsplit type is stored under the same
  key it always was, so previously ticked types stay ticked.

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
  what made it decodable rather than guessable. The whole rendering is in `NOTES.md`.
  The probe is switched off again — it is a development aid, not a feature, and it writes a
  ~1MB dump per cast. Turning it off now takes effect immediately rather than at the end of the
  cast in flight.
- **The rename decision**: Farmers Almanac / Farmer's Almanac / Farmers' Almanac. It lands in
  the descriptor, the repo name and the Hub listing. Never rename the config group
  `dooglemaps` — that orphans the harvest history, the one thing that does not come back.
