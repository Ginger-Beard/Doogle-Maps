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
- **Committing.** 80-odd files are outstanding; a freeze-and-kill loses the lot.

## Deferred deliberately

- **The bank tag tab** — held until the loadout's item list proves right in play. A filter
  built on a wrong list *hides* things you need, where a wrong highlight is merely ignorable.
- **Patch border highlighting** — the tile fill added for empty patches may already have
  answered this; worth looking before building anything.
- **Carrying compost between stops.** Normally pure loss, since every area has its own
  leprechaun with a thousand buckets. The exception is **Trollheim**, where the leprechaun is
  ~15 tiles from the patch. The non-rotting fix is to learn leprechaun positions the way patch
  positions are already learned, then compute the rule. Low priority: one patch, one walk.
- **Crowdsourced yield data**, post-Hub and opt-in. See `NOTES.md`.
- **Geomancy bulk refresh (§4b)** — decoded as far as it can be without a diseased patch. The
  probe's vocabulary catalogue announces the diseased rendering automatically the first time
  one is cast on.

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
