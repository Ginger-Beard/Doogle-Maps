# Doogle Maps

A RuneLite plugin that keeps a live overview of every farming patch you use, so you can
see your whole farm without running around it.

The name is a play on the OSRS *doogle leaves*. What it actually does is farming: a
patch-state overview and, in time, a guided farm-run helper.

## What it does today

- **Caches every patch's state as you interact with it.** Rake, compost, plant, pay the
  farmer, check health, harvest — the plugin follows the patch through each step and
  remembers it. Walking past a patch is enough to refresh it.
- **Shows it in a Geomancy-style sidebar.** Patch-type tabs across the top, one row per
  location: what is growing, a shield when the patch is protected or immune, the compost
  bucket it was treated with, and a staged progress bar. Hover the bar for the time
  remaining.
- **Works without Geomancy.** The Lunar spell fills everything in at once for players who
  have it, but it is a shortcut, not a requirement — the cache fills in as you go.
- **Grows while you are logged out.** Patches keep advancing offline, so on login every
  timer is recomputed from elapsed time against the game's growth-tick grid.
- **Tells you when it is guessing.** An unprotected crop can catch a disease you were not
  there to see, so its timer is shown as an estimate rather than a promise. Protected and
  immune crops get a solid one.
- **Per-patch on/off switches.** Turn on the patches your account can actually reach and
  the rest disappear everywhere — nothing you cannot get to is ever shown or counted.
- **In-game infobox** counting patches ready to harvest, hover for the list.

Multiple accounts need no setup: RuneLite scopes the cache per profile.

### Existing accounts start populated

RuneLite's built-in Time Tracking has quietly been recording the same varbits for as long
as your account has existed. On first run this plugin reads that cache to seed patches it
has never seen itself, so the overview is useful immediately rather than after a lap of
the map. It only ever fills gaps, and never writes to Time Tracking's data.

## Planned

Seed inventory cache, plantable-seed selector, run loadouts with a pre-run gather list and
bank highlighting, route planning via Shortest Path, and a guided "lazy mode" that walks
you through a run step by step. See `doogle-maps-plugin-spec.md`.

## Compliance

Read-only and display-only. The plugin highlights and instructs; you perform every click.
No input automation, no auto-walking, no auto-withdrawing — the same model Quest Helper
uses. It also never suggests buying anything on the Grand Exchange, so it works the same
for an ironman as for a main.

## Building and running

The source lives on the Windows filesystem at `C:\Users\Josh\projects\Doogle-Maps`, with a
symlink at `~/projects/Doogle-Maps` in WSL for editing — the same arrangement as the other
plugins. That split matters: the client has to run Windows-side for the GPU and the
Windows `~/.runelite` profile, and `cmd.exe` cannot take a `\\wsl.localhost` path as its
working directory, so Gradle's `runClient` cannot start a Windows JVM from the WSL side.

**Compiling** works from either side; WSL is the faster inner loop:

```bash
./gradlew build     # from WSL, through the symlink
```

**Launching the client** goes through Windows:

```bash
./run-client.sh            # build and launch RuneLite with the plugin side-loaded
./run-client.sh --refresh  # also re-resolve dependencies
```

That wraps `cmd.exe /c "pushd <windows path> && gradlew.bat runClient"`, which starts
RuneLite in developer mode via `src/test/java/com/dooglemaps/DoogleMapsPluginTest.java`.
It needs a Windows JDK 11 on `PATH` (Temurin at
`C:\Program Files\Eclipse Adoptium\jdk-11.0.29.7-hotspot`).

The jar lands in `build/libs/` (WSL) or `build-windows/libs/` (Windows).

### Why the two sides have separate build directories

Gradle state cannot be shared across the WSL/Windows boundary. The Windows daemon locks
its project cache, and a running client holds its class files open; WSL cannot then delete
either, and the next WSL build dies with:

```
Could not create service of type FileHasher using BuildSessionServices.createFileHasher().
> java.io.IOException: Input/output error
```

So `run-client.sh` passes `--project-cache-dir .gradle-windows -PbuildSuffix=windows`, and
`build.gradle` redirects the build directory when `buildSuffix` is set. WSL keeps the plain
`.gradle` and `build/`; Windows keeps `.gradle-windows` and `build-windows/`. Neither
touches the other, so you can rebuild in WSL while the client is still running.

If you hit that error on a repo that predates the split, clear the shared state once —
close the client first, since its daemon holds the locks:

```bash
cmd.exe /c "pushd C:\Users\Josh\projects\Doogle-Maps && gradlew.bat --stop"
rm -rf .gradle build
```

## Regenerating the farming data

`com.dooglemaps.data`'s `Produce`, `PatchImplementation`, `PatchRules` and
`FarmingWorldData` are generated from RuneLite's client sources — those classes are
package-private in core, so they cannot be called from an external plugin and the data has
to be carried here. To pick up a RuneLite update:

```bash
# Which client version the build actually resolves
./gradlew dependencies --configuration compileClasspath | grep net.runelite:client

# Gradle only caches the binary jar, so fetch the sources one directly
VER=1.12.34.1
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

`FarmingDataTest` asserts those counts and the shape of the tables, so a parsing regression
fails the build rather than shipping. When a RuneLite update genuinely adds patches, that
test fails on purpose — check the counts, then update the expected numbers.

The generator reads its `ItemID` / `NpcID` / `VarbitID` imports out of the client sources
rather than assuming a package, because RuneLite moves them (`Varbits` became `VarbitID`,
and `ItemID` and `NpcID` moved into `net.runelite.api.gameval`). Constant *names* change
freely between versions; the underlying varbit numbers do not, which is why cached patch
state survives a regeneration.

### One thing to hand-check after regenerating

`RegionBounds` is hand-ported, not generated. A few regions overlap or leak varbits from an
upper floor, and core handles them with anonymous `isInBounds` overrides. The generator
emits a `RegionBounds.forRegion(id)` call wherever core had one, so compare the two:

```bash
grep -c 'public boolean isInBounds' /tmp/rl-src/net/runelite/client/plugins/timetracking/farming/FarmingWorld.java
grep -c 'RegionBounds.forRegion' src/main/java/com/dooglemaps/data/FarmingWorldData.java
```

If those disagree, a new override appeared and needs porting into `RegionBounds`. If they
agree, also confirm the existing predicates did not change:

```bash
diff <(grep -A25 'public boolean isInBounds' <old sources>/FarmingWorld.java | grep -E 'loc\.|return ') \
     <(grep -A25 'public boolean isInBounds' /tmp/rl-src/.../FarmingWorld.java | grep -E 'loc\.|return ')
```

## Attribution

Patch locations, varbits, crop decoding and growth timings are mirrored from RuneLite
core's Time Tracking plugin. See [ATTRIBUTION.md](ATTRIBUTION.md).
