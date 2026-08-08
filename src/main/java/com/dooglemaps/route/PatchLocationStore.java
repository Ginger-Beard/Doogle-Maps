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

	/** Patch key to {x, y, plane}. */
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

	/** Records where a patch really is, having seen its game object. */
	public void record(FarmPatch patch, WorldPoint location)
	{
		if (location == null)
		{
			return;
		}

		synchronized (this)
		{
			int[] existing = learned.get(patch.getKey());
			if (existing != null && existing[0] == location.getX()
				&& existing[1] == location.getY() && existing[2] == location.getPlane())
			{
				return;
			}

			learned.put(patch.getKey(), new int[]{location.getX(), location.getY(), location.getPlane()});
			save();
		}
		log.debug("Learned location {} for {}", location, patch);
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
				if (value != null && value.length == 3 && FarmingWorldData.getPatch(key) != null)
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
