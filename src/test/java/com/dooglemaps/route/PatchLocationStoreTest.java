package com.dooglemaps.route;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Checks the region-centre fallback, which is what routing uses for a patch the player has
 * never stood next to.
 */
public class PatchLocationStoreTest
{
	@Test
	public void regionCentreLandsInsideItsOwnRegion()
	{
		// The centre derived from a region id must belong to that same region. Getting the
		// bit packing backwards would put every unvisited patch on the wrong side of the
		// map, and nothing else would notice.
		for (FarmPatch patch : FarmingWorldData.getAllPatches())
		{
			int regionId = patch.getRegion().getRegionId();
			WorldPoint centre = PatchLocationStore.regionCentre(regionId);
			assertEquals("centre of region " + regionId + " fell outside it, for " + patch,
				regionId, centre.getRegionID());
		}
	}

	@Test
	public void regionCentreMatchesAKnownPlace()
	{
		// Falador's allotments sit in region 12083, covering x 3008-3071, y 3328-3391.
		WorldPoint centre = PatchLocationStore.regionCentre(12083);
		assertEquals(12083, centre.getRegionID());
	}

	/**
	 * Every scraped coordinate must land in the region RuneLite associates with that patch.
	 *
	 * <p>This is what makes the wiki data trustworthy without hand-checking 31 map pins: the
	 * two sources were derived independently, so agreement on the region is real evidence.
	 * A typo in a coordinate almost certainly moves it out of its region and fails here.
	 */
	@Test
	public void everySeededCoordinateLandsInTheRegionItBelongsTo()
	{
		int checked = 0;
		for (FarmPatch patch : FarmingWorldData.getAllPatches())
		{
			int regionId = patch.getRegion().getRegionId();
			WorldPoint seeded = WikiPatchLocations.forRegion(regionId);
			if (seeded == null)
			{
				continue;
			}
			assertEquals("seeded coordinate for " + patch + " is not in region " + regionId,
				regionId, seeded.getRegionID());
			checked++;
		}
		assertTrue("no seeded coordinates were checked at all", checked > 0);
	}

	@Test
	public void theSeededTableCoversTheRunPatches()
	{
		assertEquals("seeded location count changed - re-scrape or update this", 31, WikiPatchLocations.size());
	}

	@Test
	public void everyPatchIsRoutableBeforeItIsEverVisited()
	{
		for (FarmPatch patch : FarmingWorldData.getAllPatches())
		{
			WorldPoint fallback = PatchLocationStore.regionCentre(patch.getRegion().getRegionId());
			assertNotNull(patch + " has no routable location", fallback);
			assertTrue(patch + " has a nonsense location", fallback.getX() > 0 && fallback.getY() > 0);
		}
	}
}
