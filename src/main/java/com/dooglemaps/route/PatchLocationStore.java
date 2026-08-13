package com.dooglemaps.route;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;

/**
 * Where each patch actually is, for handing to the router.
 *
 * <h2>Why this is learned rather than listed</h2>
 * RuneLite's farming data identifies a patch by region and varbit, not by position — it
 * only ever needs to read a varbit, never to walk anywhere. So there is no table of patch
 * coordinates to mirror, and hand-authoring 107 of them would be a lot of data to get
 * subtly wrong and then maintain.
 *
 * <p>So it comes from three places, best first: the exact position learned by watching the
 * patch's game object appear (patch objects carry the same varbit id we already match on,
 * so standing near one is enough); a coordinate scraped from the wiki for that location;
 * and finally the centre of the patch's region. Every patch is routable from the first
 * launch, and gets more precise as you play.
 */
@Slf4j
@Singleton
public class PatchLocationStore extends com.dooglemaps.state.ProfileJsonStore
{
	private static final String LOCATIONS_KEY = "patchLocations";

	private static final Type LOCATION_MAP_TYPE = new TypeToken<HashMap<String, int[]>>()
	{
	}.getType();

	/** Regions are 64x64 tiles, so their centre is 32 in from the corner. */
	private static final int REGION_SIZE = 64;

	/**
	 * The footprint assumed when only a point is known — a wiki seed, a region centre, or a
	 * position learned before footprints were recorded. Three tiles square covers the small
	 * patch families (herb, flower, bush); a bigger real footprint only means the ring lands
	 * inside the patch, which for the big families (allotments) is walkable anyway.
	 */
	private static final int ASSUMED_FOOTPRINT = 3;

	/**
	 * Patch key to a position, in one of three shapes.
	 *
	 * <ul>
	 *   <li><b>3</b> — {@code {x, y, plane}}. Learned before footprints were recorded.</li>
	 *   <li><b>5</b> — {@code {x, y, plane, sizeX, sizeY}}, the point being the middle of its
	 *       own extent. Correct only where the patch is a rectangle.</li>
	 *   <li><b>7</b> — {@code {x, y, plane, sizeX, sizeY, swX, swY}}: a point the patch
	 *       <b>occupies</b>, plus the extent's south-west corner. The two are separate because
	 *       patches are frequently not rectangles — both of the Farming Guild's allotments are
	 *       eighteen tiles inside a 6x5 box, and the middle of that box is off the patch.</li>
	 * </ul>
	 *
	 * <p>All three are read; only the last is written. See {@link #record}.
	 */
	private final Map<String, int[]> learned = new HashMap<>();

	@Inject
	PatchLocationStore(ConfigManager configManager, Gson gson)
	{
		super(configManager, gson, LOCATIONS_KEY);
	}

	/**
	 * Where to route for this patch.
	 *
	 * <p>Never null: falls back to the region centre so a patch you have never visited is
	 * still routable, just less precisely.
	 */
	public synchronized WorldPoint getLocation(FarmPatch patch)
	{
		int[] exact = exactFor(patch);
		if (exact != null)
		{
			return new WorldPoint(exact[0], exact[1], exact[2]);
		}

		// Three tiers, narrowest first: this patch's own pin, then its location's, then the
		// middle of the region. The last is arithmetic rather than a place and exists only so
		// this method never returns null - see isKnown, and the table on the Great Conch.
		WorldPoint own = WikiPatchLocations.forPatch(patch);
		if (own != null)
		{
			return own;
		}
		WorldPoint seeded = WikiPatchLocations.forRegion(patch.getRegion().getRegionId());
		return seeded != null ? seeded : regionCentre(patch.getRegion().getRegionId());
	}

	/**
	 * This patch's exact position and extent — learned first, shipped otherwise.
	 *
	 * <h2>Learned wins, and has to</h2>
	 *
	 * {@link MeasuredPatchLocations} is one account's observation of a world that Jagex can move.
	 * The learned store is <b>this</b> player's, taken from the client in front of them, so where
	 * the two disagree the live one is the one that has seen the game more recently. That also
	 * keeps the self-healing property: a patch that moves is corrected by walking past it, with
	 * nothing to ship.
	 *
	 * <p>The shipped table is what makes a first launch as precise as a hundredth. Before it, a
	 * fresh account had one wiki pin per <i>location</i>, so every patch in a region compared
	 * equal and the nearest-first ordering inside a stop collapsed to declaration order.
	 */
	@Nullable
	private int[] exactFor(FarmPatch patch)
	{
		int[] learnedHere = learned.get(patch.getKey());
		return learnedHere != null ? learnedHere : MeasuredPatchLocations.forPatch(patch);
	}

	/**
	 * Whether we know exactly where this patch is, rather than roughly.
	 *
	 * <p>True of every patch now that {@link MeasuredPatchLocations} ships the lot, which is the
	 * point of shipping it — a first launch is as precise as a hundredth. Kept as a question
	 * rather than deleted because the answer is a fact about the data rather than a constant, and
	 * a future patch the table has not caught up with would answer false.
	 */
	public synchronized boolean isExact(FarmPatch patch)
	{
		return exactFor(patch) != null;
	}

	/**
	 * Whether <b>this player</b> has stood next to it, as opposed to it merely being shipped.
	 *
	 * <p>The distinction {@link #isExact} used to carry on its own and cannot any more. Only the
	 * learned half is evidence about the capture pipeline working — a test that watches a spawn
	 * turn into a position has to ask this, or the shipped table answers for it and the test
	 * passes without the code under it running at all.
	 */
	public synchronized boolean isLearned(FarmPatch patch)
	{
		return learned.containsKey(patch.getKey());
	}

	/**
	 * Whether this patch's position is something known, rather than something assumed.
	 *
	 * <h2>Known, not exact</h2>
	 *
	 * {@link #getLocation} never returns null: a patch nobody has stood beside falls back to a
	 * wiki pin, and failing that to the middle of its region. Both are honest as rough guesses
	 * and both are fine when they are the only thing a router is given.
	 *
	 * <p>The region centre stops being fine the moment it shares a target set with something
	 * precise. A router takes the cheapest of what it is handed and cannot tell which entries
	 * were invented, so a centre that happens to lie nearer than the real destination wins — see
	 * {@code RunPlanner.routeTargetsFor}, and the table on the Great Conch it walked people to.
	 * A wiki pin is a real place somebody wrote down, so it belongs in such a set; a region
	 * centre is arithmetic, and does not.
	 */
	public synchronized boolean isKnown(FarmPatch patch)
	{
		return exactFor(patch) != null
			|| WikiPatchLocations.forPatch(patch) != null
			|| WikiPatchLocations.forRegion(patch.getRegion().getRegionId()) != null;
	}

	/**
	 * Where to send the router for this patch: the tiles <b>around</b> it, plus the centre.
	 *
	 * <h2>Why a ring, when one point was routable</h2>
	 * Shortest Path only ever finishes a search by stepping <i>onto</i> a target tile — there
	 * is no "adjacent counts" — and the one point this store used to hand out is the patch
	 * object's own tile. For the walkable families (allotments, herbs) that is fine; a bush or
	 * a tree <b>is</b> the blockage, so its path could never terminate. The search then runs
	 * to Shortest Path's no-progress cutoff — three seconds by default — before a line appears,
	 * and every recalculation (the player straying {@code recalculateDistance} tiles from the
	 * drawn path, or a re-post of ours) starts the grind over with nothing drawn in between.
	 * Reported from play as the route line barely ever existing on a bush run until the
	 * patches were nearly in sight.
	 *
	 * <p>The ring sits one tile outside the learned footprint, so the first walkable tile
	 * beside the patch ends the search instantly — which is also exactly where the player
	 * wants to be stood. The centre is kept in the set for the walkable families, and a
	 * blocked centre is harmless: an unreachable member of a target set merely never wins.
	 */
	public synchronized java.util.List<WorldPoint> getRouteTargets(FarmPatch patch)
	{

		int[] exact = exactFor(patch);
		WorldPoint centre = getLocation(patch);
		int sizeX = exact != null && exact.length >= 5 ? exact[3] : ASSUMED_FOOTPRINT;
		int sizeY = exact != null && exact.length >= 5 ? exact[4] : ASSUMED_FOOTPRINT;

		// The extent's own south-west corner where it is recorded, and derived from the point
		// otherwise. The two are only the same thing for a rectangular patch: a multi-tile patch
		// stores a point it actually occupies rather than the middle of its bounding box, so
		// deriving the corner from it would slide the ring off by however lopsided the patch is.
		// See record() for why the point cannot be the middle.
		int swX;
		int swY;
		if (exact != null && exact.length >= 7)
		{
			swX = exact[5];
			swY = exact[6];
		}
		else
		{
			swX = centre.getX() - (sizeX - 1) / 2;
			swY = centre.getY() - (sizeY - 1) / 2;
		}

		java.util.List<WorldPoint> targets = new java.util.ArrayList<>();
		targets.add(centre);
		for (int x = swX - 1; x <= swX + sizeX; x++)
		{
			for (int y = swY - 1; y <= swY + sizeY; y++)
			{
				boolean onRing = x == swX - 1 || x == swX + sizeX
					|| y == swY - 1 || y == swY + sizeY;
				if (onRing)
				{
					targets.add(new WorldPoint(x, y, centre.getPlane()));
				}
			}
		}
		return targets;
	}

	/** Squared tile distance, for comparing two candidates without a square root. */
	private static long squaredGap(int x, int y, int toX, int toY)
	{
		long dx = x - toX;
		long dy = y - toY;
		return dx * dx + dy * dy;
	}

	/**
	 * The middle of a map region.
	 *
	 * <p>A region id packs its own coordinates: the top byte is the region's x in units of
	 * 64 tiles, the bottom byte its y.
	 */
	static WorldPoint regionCentre(int regionId)
	{
		int x = ((regionId >> 8) & 0xFF) * REGION_SIZE;
		int y = (regionId & 0xFF) * REGION_SIZE;
		return new WorldPoint(x + (REGION_SIZE / 2), y + (REGION_SIZE / 2), 0);
	}

	/**
	 * Records where a patch really is, having seen one of its game objects.
	 *
	 * <h2>One patch is many objects, and the last one is not the answer</h2>
	 *
	 * This took whatever it was last handed, and a patch is rarely one object. Read off the
	 * client at the Farming Guild: an allotment is <b>eighteen</b> separate 1x1 objects sharing
	 * one varbit, and the redwood is twenty-two objects spread over three planes. Every one of
	 * them matched, every one overwrote the last, and what survived was an arbitrary corner tile
	 * carrying a sub-object's 1x1 footprint.
	 *
	 * <p>Two consequences, and the second is the sharp one:
	 *
	 * <ul>
	 *   <li>The stored point was a corner rather than the patch, several tiles out, with a
	 *       footprint far too small for {@link #getRouteTargets} to ring anything useful.</li>
	 *   <li><b>Tall trees are drawn on the storeys above them.</b> The guild's redwood was last
	 *       seen at plane 2 and its tree at plane 1, so both were stored on a floor the player
	 *       is never on — and {@code GuideTracker.distance} answers {@code Integer.MAX_VALUE}
	 *       whenever the planes differ. Those two patches sorted last, permanently, and their
	 *       route targets pointed upstairs.</li>
	 * </ul>
	 *
	 * <h2>So: lowest plane wins, and same-plane sightings accumulate</h2>
	 *
	 * An object on a <b>higher</b> plane than what is known is canopy and is ignored. One on a
	 * <b>lower</b> plane replaces what is there outright, because it is the storey the patch is
	 * worked from. One on the <b>same</b> plane is another piece of the same patch, so its tiles
	 * are unioned in and the stored value becomes the whole extent.
	 *
	 * <p>Order-independent by construction, which matters because spawn order is not: the guild's
	 * tree arrived at plane 0 and then plane 1, and the rule gives the same answer either way.
	 *
	 * <p>Self-healing, which is the reason this lives here rather than in the capture. A store
	 * already holding the plane-2 redwood corrects itself the next time the player stands near
	 * it, with nothing to migrate and no reset to ask anyone for.
	 *
	 * <p>The centre is derived so that {@link #getRouteTargets} reconstructs the south-west
	 * corner exactly: it takes {@code centre - (size - 1) / 2}, so the centre is stored as
	 * {@code corner + (size - 1) / 2} and the two round the same way.
	 *
	 * @param sizeX the object's footprint width in tiles, for {@link #getRouteTargets};
	 *              anything below 1 is treated as a single tile
	 * @param sizeY the footprint height, same rule
	 */
	public void record(FarmPatch patch, WorldPoint location, int sizeX, int sizeY)
	{
		if (location == null)
		{
			return;
		}

		int width = Math.max(1, sizeX);
		int height = Math.max(1, sizeY);
		int plane = location.getPlane();

		// This object's own tile extent, from the same centre-to-corner rule getRouteTargets uses.
		int swX = location.getX() - (width - 1) / 2;
		int swY = location.getY() - (height - 1) / 2;
		int neX = swX + width - 1;
		int neY = swY + height - 1;

		// Read, merge and write in ONE critical section. Splitting the merge from the store would
		// let a second sighting land between them and be unioned away.
		//
		// The save is outside it, though. ProfileJsonStore.save's own javadoc forbids the other
		// order by name — "do not invoke this while holding the store's monitor, or the write
		// happens under your lock and the hole is back" — and this method used to do exactly
		// that. A save posts ConfigChanged synchronously into every subscriber, which is
		// arbitrary plugin code reaching into other stores, so holding this monitor across it is
		// the shape of the deadlock that javadoc describes from a JVM thread dump.
		//
		// It matters here more than most: the trigger is GameObjectSpawned, and a teleport spawns
		// a whole scene at once. That is the same event the open freeze report is pinned to; see
		// docs/TODO.md, "DEADLOCK on teleport during a farm run".
		boolean changed;
		int[] value;
		synchronized (this)
		{
			int[] known = learned.get(patch.getKey());
			if (known != null && known.length >= 3)
			{
				if (plane > known[2])
				{
					// Canopy. The storey below is the patch.
					return;
				}
				if (plane == known[2] && known.length >= 5)
				{
					// The corner where it is recorded, derived from the point only for the older
					// five-int shape where the point IS the middle. Deriving it from a seven-int
					// entry's point would slide the box by however far that point sits from the
					// middle, and since the result is stored and merged again next time, the box
					// walks away from the patch a little further on every sighting.
					int knownSwX = known.length >= 7 ? known[5] : known[0] - (known[3] - 1) / 2;
					int knownSwY = known.length >= 7 ? known[6] : known[1] - (known[4] - 1) / 2;
					swX = Math.min(swX, knownSwX);
					swY = Math.min(swY, knownSwY);
					neX = Math.max(neX, knownSwX + known[3] - 1);
					neY = Math.max(neY, knownSwY + known[4] - 1);
				}
				// A lower plane, or a legacy entry with no footprint to union with: replaced.
			}

			int spanX = neX - swX + 1;
			int spanY = neY - swY + 1;

			// The point is a tile the patch actually occupies, which the middle of the extent
			// is not. Allotments are L-shaped far more often than they are rectangles - both of
			// the Farming Guild's are eighteen tiles inside a 6x5 box, 60% filled - and the
			// centre of that box lands in the notch, off the patch entirely. That is what gets
			// drawn as the patch marker and measured from for the nearest-patch ordering.
			//
			// So the extent and the point are stored separately, and the point is whichever
			// observed object sits closest to the middle. Approximated incrementally, because
			// the middle moves as more of the patch is seen: each sighting is compared against
			// the extent as it stands, which converges on a central tile without keeping the
			// whole tile set. A corner would be on the patch too, and this is better than a
			// corner for the same cost.
			int midX = swX + (spanX - 1) / 2;
			int midY = swY + (spanY - 1) / 2;
			int pointX = location.getX();
			int pointY = location.getY();
			if (known != null && known.length >= 7 && plane == known[2])
			{
				long kept = squaredGap(known[0], known[1], midX, midY);
				if (kept <= squaredGap(pointX, pointY, midX, midY))
				{
					pointX = known[0];
					pointY = known[1];
				}
			}

			value = new int[]{pointX, pointY, plane, spanX, spanY, swX, swY};

			changed = !java.util.Arrays.equals(known, value);
			if (changed)
			{
				learned.put(patch.getKey(), value);
			}
		}

		if (!changed)
		{
			return;
		}

		save();
		// The accumulated extent, not the one object that triggered it - so a line per patch
		// reads as the patch growing to its real size rather than as the same patch being
		// "learned" eighteen times at eighteen different corners.
		log.debug("Learned location ({},{}) plane {} ({}x{}) for {}, from an object at {}",
			value[0], value[1], value[2], value[3], value[4], patch, location);
	}

	/** Forgets every learned patch position, falling back to the seeded coordinates. */
	public synchronized void clear()
	{
		learned.clear();
		unsetStored();
	}

	@Override
	protected void resetForLoad()
	{
		learned.clear();
	}

	@Override
	protected void applyJson(String json)
	{
		Map<String, int[]> loaded = gson.fromJson(json, LOCATION_MAP_TYPE);
		if (loaded != null)
		{
			loaded.forEach((key, value) ->
			{
				// Three values is a position learned before footprints were recorded; it
				// still routes, through the assumed footprint.
				// Three: a position learned before footprints were recorded. Five: a point that
				// is the middle of its own extent. Seven: a point the patch occupies plus the
				// extent's corner, which is the only form that can describe a patch that is not
				// a rectangle. All three still route; see getRouteTargets.
				if (value != null && (value.length == 3 || value.length == 5 || value.length == 7)
					&& FarmingWorldData.getPatch(key) != null)
				{
					learned.put(key, value);
				}
			});
		}
	}

	@Override
	protected Object serialized()
	{
		return learned;
	}
}
