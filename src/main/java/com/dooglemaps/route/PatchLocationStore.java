package com.dooglemaps.route;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
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

	/** Patch key to {x, y, plane} or {x, y, plane, sizeX, sizeY}; the point is the centre. */
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
		int[] exact = learned.get(patch.getKey());
		if (exact != null)
		{
			return new WorldPoint(exact[0], exact[1], exact[2]);
		}

		// Wiki coordinates cover the patches a farm run visits; the rest fall back to the
		// middle of their region, which is within about half a region of the truth.
		WorldPoint seeded = WikiPatchLocations.forRegion(patch.getRegion().getRegionId());
		return seeded != null ? seeded : regionCentre(patch.getRegion().getRegionId());
	}

	/** Whether we know exactly where this patch is, rather than roughly. */
	public synchronized boolean isExact(FarmPatch patch)
	{
		return learned.containsKey(patch.getKey());
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

		int[] exact = learned.get(patch.getKey());
		WorldPoint centre = getLocation(patch);
		int sizeX = exact != null && exact.length >= 5 ? exact[3] : ASSUMED_FOOTPRINT;
		int sizeY = exact != null && exact.length >= 5 ? exact[4] : ASSUMED_FOOTPRINT;

		// The footprint's south-west corner, from its centre the way RuneLite derives the
		// centre from the corner: minus half the size, rounding down.
		int swX = centre.getX() - (sizeX - 1) / 2;
		int swY = centre.getY() - (sizeY - 1) / 2;

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
	 * Records where a patch really is, having seen its game object.
	 *
	 * @param sizeX the object's footprint width in tiles, for {@link #getRouteTargets};
	 *              anything below 1 records the position alone
	 * @param sizeY the footprint height, same rule
	 */
	public void record(FarmPatch patch, WorldPoint location, int sizeX, int sizeY)
	{
		if (location == null)
		{
			return;
		}

		boolean sized = sizeX >= 1 && sizeY >= 1;
		int[] value = sized
			? new int[]{location.getX(), location.getY(), location.getPlane(), sizeX, sizeY}
			: new int[]{location.getX(), location.getY(), location.getPlane()};

		synchronized (this)
		{
			if (java.util.Arrays.equals(learned.get(patch.getKey()), value))
			{
				return;
			}

			learned.put(patch.getKey(), value);
			save();
		}
		log.debug("Learned location {} ({}x{}) for {}", location, sizeX, sizeY, patch);
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
				if (value != null && (value.length == 3 || value.length == 5)
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
