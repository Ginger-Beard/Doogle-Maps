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

	/**
	 * Route targets ring the patch rather than standing on it.
	 *
	 * <p>Shortest Path only finishes a search on a tile it can step onto, and a bush or tree
	 * patch's own tile is blocked — so the targets have to be the tiles beside it. The ring
	 * sits exactly one tile outside the learned footprint.
	 */
	@Test
	public void routeTargetsRingTheLearnedFootprint()
	{
		PatchLocationStore store = freshStore();
		FarmPatch patch = FarmingWorldData.getAllPatches().iterator().next();
		store.record(patch, new WorldPoint(3004, 3308, 0), 3, 3);

		java.util.List<WorldPoint> targets = store.getRouteTargets(patch);

		// A 3x3 footprint centred on (3004,3308) spans 3003-3005 x 3307-3309; the ring is the
		// 16-tile perimeter one step outside that, and the centre rides along for the
		// families whose patch is walkable ground.
		assertEquals(17, targets.size());
		assertTrue("centre kept", targets.contains(new WorldPoint(3004, 3308, 0)));
		assertTrue("ring corner", targets.contains(new WorldPoint(3002, 3306, 0)));
		assertTrue("ring corner", targets.contains(new WorldPoint(3006, 3310, 0)));
		assertTrue("no tile of the footprint itself except the centre",
			!targets.contains(new WorldPoint(3003, 3307, 0)));
	}

	/** A patch never seen still gets a ring, around its seeded or region-centre point. */
	@Test
	public void routeTargetsExistBeforeThePatchIsEverSeen()
	{
		PatchLocationStore store = freshStore();
		FarmPatch patch = FarmingWorldData.getAllPatches().iterator().next();

		java.util.List<WorldPoint> targets = store.getRouteTargets(patch);

		assertEquals("assumed 3x3 footprint: 16-tile ring plus the centre", 17, targets.size());
		assertTrue(targets.contains(store.getLocation(patch)));
	}

	private static PatchLocationStore freshStore()
	{
		try
		{
			return com.dooglemaps.Construct.construct(PatchLocationStore.class,
				org.mockito.Mockito.mock(net.runelite.client.config.ConfigManager.class),
				new com.google.gson.Gson());
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}

	/**
	 * The store still answers with the patch's own footprint, coral included.
	 *
	 * <p>The landward divert lives in {@code RunPlanner.routeTargetsFor}, not here, because it
	 * depends on where the player is standing — on the dock it applies and underwater it must
	 * not, or the router plots a course back up the steps.
	 */
	@org.junit.Test
	public void theStoreAlwaysAnswersWithThePatchesOwnTiles()
	{
		com.dooglemaps.data.FarmPatch nursery = com.dooglemaps.data.FarmingWorldData
			.getPatches(com.dooglemaps.data.PatchImplementation.CORAL).get(0);
		org.junit.Assert.assertNotNull(nursery);

		org.junit.Assert.assertTrue("a ring of tiles, the same as any other patch",
			freshStore().getRouteTargets(nursery).size() > 1);
	}

	/**
	 * The gear list names the medallion first, because it replaces the pair.
	 *
	 * <p>Someone who owns one needs neither other piece, and an instruction naming all three
	 * would be telling them to wear a fishbowl helmet they replaced long ago.
	 */
	@org.junit.Test
	public void theDivingGearListLeadsWithTheMedallion()
	{
		int[] gear = com.dooglemaps.data.UnderwaterApproach.gearToWear();

		org.junit.Assert.assertEquals(3, gear.length);
		org.junit.Assert.assertEquals(net.runelite.api.gameval.ItemID.MEDALLION_OF_THE_DEEP,
			gear[0]);
	}

	/**
	 * Both underwater families have an approach now; nothing on dry land does.
	 *
	 * <p>Seaweed was deliberately absent at first, on the grounds that nobody had reported the
	 * router refusing it — which held until somebody did, with the rowboat's id and position.
	 * The rule that kept it out is still the rule: an approach goes in when the router has
	 * actually refused the patch and the object is known, never because a patch looks wet.
	 */
	@org.junit.Test
	public void bothUnderwaterFamiliesHaveALandwardApproach()
	{
		com.dooglemaps.data.UnderwaterApproach.Approach steps =
			com.dooglemaps.data.UnderwaterApproach.forType(
				com.dooglemaps.data.PatchImplementation.CORAL);
		com.dooglemaps.data.UnderwaterApproach.Approach boat =
			com.dooglemaps.data.UnderwaterApproach.forType(
				com.dooglemaps.data.PatchImplementation.SEAWEED);

		org.junit.Assert.assertNotNull(steps);
		org.junit.Assert.assertNotNull(boat);
		org.junit.Assert.assertNotEquals("two different shores, two different regions",
			steps.getRegionId(), boat.getRegionId());
		org.junit.Assert.assertNull("nothing on dry land is diverted",
			com.dooglemaps.data.UnderwaterApproach.forType(
				com.dooglemaps.data.PatchImplementation.HERB));
	}

	/** Each approach is judged against its OWN shore, not a shared one. */
	@org.junit.Test
	public void eachApproachIsJudgedAgainstItsOwnShore()
	{
		com.dooglemaps.data.UnderwaterApproach.Approach steps =
			com.dooglemaps.data.UnderwaterApproach.forType(
				com.dooglemaps.data.PatchImplementation.CORAL);
		com.dooglemaps.data.UnderwaterApproach.Approach boat =
			com.dooglemaps.data.UnderwaterApproach.forType(
				com.dooglemaps.data.PatchImplementation.SEAWEED);

		org.junit.Assert.assertTrue(com.dooglemaps.data.UnderwaterApproach.stillWanted(
			boat, boat.getRegionId()));
		org.junit.Assert.assertFalse("standing on the coral dock is not standing at the boat",
			com.dooglemaps.data.UnderwaterApproach.stillWanted(boat, steps.getRegionId()));
	}
}
