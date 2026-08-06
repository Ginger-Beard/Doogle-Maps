# Developing Doogle Maps

Everything about building, running and regenerating the plugin. For what it does, see
[README.md](README.md).

## Layout: Windows filesystem, WSL symlink

The source has to live on the Windows filesystem, with a symlink into WSL for editing:

```bash
/mnt/c/Users/$USER/projects/Doogle-Maps   # the real thing
~/projects/Doogle-Maps                    # symlink, for editing
```

That split matters and is not a preference. The client has to run Windows-side for the GPU and
the Windows `~/.runelite` profile, and `cmd.exe` cannot take a `\\wsl.localhost` path as its
working directory — so Gradle's `runClient` cannot start a Windows JVM from the WSL side.
`run-client.sh` checks for this and tells you how to fix it if the repo is in the wrong place.

## Building and running

**Compiling** works from either side; WSL is the faster inner loop:

```bash
./gradlew build     # from WSL, through the symlink
```

**Launching the client** goes through Windows:

```bash
./run-client.sh            # build and launch RuneLite with the plugin side-loaded
./run-client.sh --refresh  # also re-resolve dependencies
./run-client.sh --debug    # also listen on port 5005 for a debugger
```

That wraps `cmd.exe /c "pushd <windows path> && gradlew.bat runClient"`, which starts RuneLite in
developer mode via `src/test/java/com/dooglemaps/DoogleMapsPluginTest.java`. It needs a Windows
JDK 11 on `PATH` (Temurin at `C:\Program Files\Eclipse Adoptium\jdk-11.0.29.7-hotspot`).

The jar lands in `build/libs/` (WSL) or `build-windows/libs/` (Windows).

## Iterating without relaunching

Relaunching for every change is slow, and most of the time it is avoidable. In rough order of how
much time each saves:

**1. Do not launch the client at all.** Most panel work does not need it. `PanelRenderTest` renders
the whole sidebar headlessly to `build/panel.png`, so layout, colours, wording and sizing can be
checked in a few seconds:

```bash
./gradlew test --tests '*PanelRenderTest*'   # writes build/panel.png
```

It also fails the build if anything wants more than the 225px the sidebar has, which is the mistake
that is otherwise only visible in-client. `FarmerIconTest` does the same for gardener portraits,
writing a contact sheet to `build/farmers.png`.

**2. Hot-swap into the running client.** Launch with `--debug` and attach a debugger to
`localhost:5005`; in IntelliJ that is a "Remote JVM Debug" configuration. Recompile, then "Reload
Changed Classes", and the running client picks the change up.

The limit is worth knowing before relying on it: stock HotSwap replaces **method bodies only**.
Adding or removing a field or a method, changing a signature, or editing an enum needs a relaunch —
which, in a codebase with this much generated data, is a lot of changes. The JetBrains Runtime
supports rather more than the stock JVM if you want to push it.

**3. Toggle the plugin off and on.** In RuneLite's plugin list. This re-runs `shutDown` and
`startUp`, which rebuilds the panel, re-registers the event subscribers and reloads every store. It
does *not* reload classes, so on its own it changes nothing — but after a hot-swap it is what makes
constructor and `startUp` changes actually take effect.

**4. Use the client's own tools.** `--developer-mode` is already on, so the Widget Inspector and Var
Inspector are available. For anything to do with widgets or varbits, they answer questions live that
would otherwise mean adding logging and relaunching.

## Why the two sides have separate build directories

Gradle state cannot be shared across the WSL/Windows boundary. The Windows daemon locks its project
cache, and a running client holds its class files open; WSL cannot then delete either, and the next
WSL build dies with:

```
Could not create service of type FileHasher using BuildSessionServices.createFileHasher().
> java.io.IOException: Input/output error
```

So `run-client.sh` passes `--project-cache-dir .gradle-windows -PbuildSuffix=windows`, and
`build.gradle` redirects the build directory when `buildSuffix` is set. WSL keeps the plain
`.gradle` and `build/`; Windows keeps `.gradle-windows` and `build-windows/`. Neither touches the
other, so you can rebuild in WSL while the client is still running.

If you hit that error on a repo that predates the split, clear the shared state once — close the
client first, since its daemon holds the locks:

```bash
cmd.exe /c "pushd $(wslpath -w .) && gradlew.bat --stop"
rm -rf .gradle build
```

## Regenerating the farming data

`com.dooglemaps.data`'s `Produce`, `PatchImplementation`, `PatchRules` and `FarmingWorldData` are
generated from RuneLite's client sources — those classes are package-private in core, so they cannot
be called from an external plugin and the data has to be carried here. To pick up a RuneLite update:

```bash
# Which client version the build actually resolves
./gradlew dependencies --configuration compileClasspath | grep net.runelite:client

# Gradle only caches the binary jar, so fetch the sources one directly
VER=1.12.35
mkdir -p /tmp/rl-src && cd /tmp/rl-src
curl -fLO "https://repo.runelite.net/net/runelite/client/$VER/client-$VER-sources.jar"
jar xf "client-$VER-sources.jar"

cd ~/projects/Doogle-Maps
python3 tools/generate_farming_data.py /tmp/rl-src
./gradlew test
```

The generator prints the counts it parsed; they should match the client sources:

```bash
grep -c 'new FarmingPatch(' /tmp/rl-src/net/runelite/client/plugins/timetracking/farming/FarmingWorld.java
```

`FarmingDataTest` asserts those counts and the shape of the tables, so a parsing regression fails
the build rather than shipping. When a RuneLite update genuinely adds patches, that test fails on
purpose — check the counts, then update the expected numbers.

The generator reads its `ItemID` / `NpcID` / `VarbitID` imports out of the client sources rather than
assuming a package, because RuneLite moves them (`Varbits` became `VarbitID`, and `ItemID` and
`NpcID` moved into `net.runelite.api.gameval`). Constant *names* change freely between versions; the
underlying varbit numbers do not, which is why cached patch state survives a regeneration.

The generator is deterministic, so it is worth running it against the *current* version before
changing it — a clean `git status` afterwards proves the tool reproduces what is checked in, and any
diff you then see is genuinely yours.

### One thing to hand-check after regenerating

`RegionBounds` is hand-ported, not generated. A few regions overlap or leak varbits from an upper
floor, and core handles them with anonymous `isInBounds` overrides. The generator emits a
`RegionBounds.forRegion(id)` call wherever core had one, so compare the two:

```bash
grep -c 'public boolean isInBounds' /tmp/rl-src/net/runelite/client/plugins/timetracking/farming/FarmingWorld.java
grep -c 'RegionBounds.forRegion' src/main/java/com/dooglemaps/data/FarmingWorldData.java
```

If those disagree, a new override appeared and needs porting into `RegionBounds`. If they agree,
also confirm the existing predicates did not change:

```bash
diff <(grep -A25 'public boolean isInBounds' <old sources>/FarmingWorld.java | grep -E 'loc\.|return ') \
     <(grep -A25 'public boolean isInBounds' /tmp/rl-src/.../FarmingWorld.java | grep -E 'loc\.|return ')
```

## Regenerating the chathead sprites

`src/main/resources/com/dooglemaps/chatheads/` holds one portrait per gardener who protects a patch,
plus Guildmaster Jane for the farming contract's tab. Chatheads are 3D model renders, so there is
nothing to ask `ItemManager` for and no way to rasterise one at runtime — they have to be bundled.

```bash
python3 tools/fetch_chatheads.py --dry-run   # resolve and report, write nothing
python3 tools/fetch_chatheads.py             # ~200 wiki requests, rate-limited
python3 tools/fetch_chatheads.py --from-tsv  # rebuild Farmers.java without refetching
```

Nothing is hand-maintained: the NPC ids come out of the generated `FarmingWorldData`, their numeric
values out of the runelite-api jar, and their names and portraits off the wiki by searching for the
id. A farmer who moves ids shows up as a miss in the report rather than as a silently wrong face.
`tools/chatheads.tsv` records what each one resolved to.

One gardener has no portrait — the Tortugan who tends the coral patch has no wiki page — and
`FarmerIconTest` asserts that gap is still a gap, so it fails on purpose if one appears.

## Screenshots

`README.md` links three images out of `docs/images/`. They have to be captured **in the client** —
`PanelRenderTest` cannot stand in for them, because it mocks `ItemManager` and answers every
`getImage` call with a flat green swatch, so every seed, tab icon and compost bucket renders as a
blob. Those `build/*.png` renders are a layout-regression tool; these are the shop window.

Filenames are what the README references, so they have to match exactly.

| File | What it shows |
|---|---|
| `in-game.png` | The hero. Whole client window, a run under way. |
| `sidebar-almanac.png` | The Almanac tab, cropped to the patch rows. |
| `sidebar-run.png` | The run section, cropped. |

**`in-game.png`** wants four things visible at once: the **patch outlined** on the ground, the
**seed lit** in the inventory, the **on-screen step panel** with its instruction, and the
**ready-count infobox**. Falador and Catherby both frame well, having several patch types close
together. Crop to the client window — no desktop, no taskbar.

**`sidebar-almanac.png`** wants genuine variety in the rows, or it undersells what the tab is for:
something growing with a part-filled bar, something ready, something diseased or dead, and at least
one shield. Crop from the Almanac/Stats strip down through the rows, stopping before the run
section.

**`sidebar-run.png`** wants two or three seeds picked in the grid, the compost dropdown, ticked run
options and the projected yield/XP table, with Start run visible. Leave Destinations collapsed — it
makes the crop much taller for very little.

One thing to check before committing them: `in-game.png` will include the chat box. Clear it, or
crop below it.

**A fourth, once the bank highlighting is working:** `bank-loadout.png`, an open bank with a run
ticked so the seeds, payments and teleports are marked, cropped to the bank interface. The README's
*What to take* section is written and waiting for it — it just has no image yet. That shot will show
the contents of your bank, so use a bank tab rather than the full view if you would rather not
publish the lot.

Sidebar shots are rendered at `width="260"` in the README. The sidebar is 225px wide, so a
full-column capture is very tall — cropping each shot to the section it illustrates matters more
than the capture resolution does.

## The docs

- **`TODO.md`** — open work only, including what is written but unverified in the client.
- **`TESTING.md`** — the in-client check plan, with a fail signature for each item so a wrong
  result points somewhere.
- **`NOTES.md`** — everything learned and every closed post-mortem.
- **`doogle-maps-plugin-spec.md`** — the original spec.
- **`ATTRIBUTION.md`** — what is mirrored from RuneLite core, the wiki and other plugins.
