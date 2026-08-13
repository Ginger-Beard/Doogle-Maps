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
			// The tile must belong to the patch's own place — usually that means its own region
			// id, but a few farm regions cover more than one. The Great Conch is thirteen
			// regions of ship with every varbit on 12581, and its calquat physically stands in
			// 12325, so an id-equality check would reject a coordinate that is simply right.
			// Asking the world data which regions the tile belongs to is the same evidence, and
			// it is the check the multi-region cases can actually pass.
			assertTrue("seeded coordinate for " + patch + " is not anywhere in "
					+ patch.getRegion().getName() + " (it is in region "
					+ seeded.getRegionID() + ")",
				FarmingWorldData.getRegionsForLocation(seeded).contains(patch.getRegion()));
			checked++;
		}
		assertTrue("no seeded coordinates were checked at all", checked > 0);
	}

	/**
	 * Every per-patch pin lands somewhere its patch's own place covers.
	 *
	 * <p>Same evidence as the region pins above, applied one level down. A typo in a coordinate
	 * almost certainly moves it out of the location it belongs to and fails here.
	 */
	@Test
	public void everyPerPatchPinLandsWhereItBelongs()
	{
		int checked = 0;
		for (FarmPatch patch : FarmingWorldData.getAllPatches())
		{
			WorldPoint own = WikiPatchLocations.forPatch(patch);
			if (own == null)
			{
				continue;
			}
			assertTrue("pin for " + patch + " is not anywhere in "
					+ patch.getRegion().getName() + " (it is in region "
					+ own.getRegionID() + ")",
				FarmingWorldData.getRegionsForLocation(own).contains(patch.getRegion()));
			checked++;
		}
		assertEquals("every per-patch pin should belong to a real patch",
			WikiPatchLocations.perPatchSize(), checked);
	}

	/**
	 * The Farming Guild's patches are not all in the same spot, and no longer pretend to be.
	 *
	 * <h2>Why the guild specifically</h2>
	 *
	 * Fourteen patches from the redwood at x 1229 to the cactus at x 1265, and from the anima
	 * at y 3723 to the fruit tree at y 3759 — a good forty tiles across, all of it behind one
	 * pin on the herb patch. Everywhere else the patches really are one little farm and one pin
	 * is the honest answer; this is the place where it was not.
	 */
	@Test
	public void theGuildsPatchesAreToldApart()
	{
		java.util.Set<WorldPoint> distinct = new java.util.HashSet<>();
		int guildPatches = 0;
		for (FarmPatch patch : FarmingWorldData.getAllPatches())
		{
			if (patch.getRegion().getRegionId() != 4922)
			{
				continue;
			}
			guildPatches++;
			assertNotNull("the guild's " + patch.getImplementation() + " has no pin of its own",
				WikiPatchLocations.forPatch(patch));
			distinct.add(WikiPatchLocations.forPatch(patch));
		}
		assertTrue("fixture: the guild should have a crowd of patches", guildPatches >= 13);
		assertTrue("one pin for fourteen patches is what this replaced: " + distinct,
			distinct.size() >= 9);
	}

	@Test
	public void theSeededTableCoversTheRunPatches()
	{
		assertEquals("seeded location count changed - re-scrape or update this", 42, WikiPatchLocations.size());
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

	/**
	 * A patch is many objects, and its position is all of them rather than the last one.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * {@code record} took whatever it was last handed. Read off the client at the Farming Guild:
	 * an allotment is eighteen separate 1x1 objects sharing one varbit, so eighteen calls landed
	 * and the survivor was an arbitrary corner tile carrying a 1x1 footprint — several tiles out,
	 * with nothing for {@code getRouteTargets} to ring.
	 *
	 * <p>The fixture is that real data: the guild's south allotment, whose tiles run x 1267-1272
	 * and y 3723-3727.
	 */
	@Test
	public void everyObjectOfAPatchIsUnionedIntoItsExtent()
	{
		PatchLocationStore store = freshStore();
		FarmPatch patch = FarmingWorldData.getAllPatches().iterator().next();

		for (int x = 1267; x <= 1272; x++)
		{
			for (int y = 3723; y <= 3727; y++)
			{
				store.record(patch, new WorldPoint(x, y, 0), 1, 1);
			}
		}

		// The whole 6x5 block, not the last tile fed in.
		java.util.List<WorldPoint> targets = store.getRouteTargets(patch);
		assertTrue("ring hugs the south-west corner",
			targets.contains(new WorldPoint(1266, 3722, 0)));
		assertTrue("ring hugs the north-east corner",
			targets.contains(new WorldPoint(1273, 3728, 0)));
		assertTrue("and does not cut through the patch",
			!targets.contains(new WorldPoint(1270, 3725, 0)));
	}

	/**
	 * A patch is often not a rectangle, so its point has to be a tile it actually occupies.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * The stored point was the middle of the bounding box, and both of the Farming Guild's
	 * allotments are <b>L-shaped</b> — eighteen tiles inside a 6x5 box, 60% filled — so the
	 * middle landed in the notch, off the patch entirely. That point is what the patch marker is
	 * drawn on and what the nearest-patch ordering measures from. Reported from play, and the
	 * shapes vary by farm, so nothing may assume a rectangle.
	 *
	 * <p>The fixture is the guild's south allotment exactly as the client reported it: two full
	 * columns at x 1267-1268, and a four-wide foot along y 3726-3727.
	 */
	@Test
	public void theStoredPointIsATileThePatchOccupies()
	{
		PatchLocationStore store = freshStore();
		FarmPatch patch = FarmingWorldData.getAllPatches().iterator().next();

		java.util.Set<WorldPoint> tiles = new java.util.LinkedHashSet<>();
		for (int y = 3723; y <= 3727; y++)
		{
			tiles.add(new WorldPoint(1267, y, 0));
			tiles.add(new WorldPoint(1268, y, 0));
		}
		for (int x = 1269; x <= 1272; x++)
		{
			tiles.add(new WorldPoint(x, 3726, 0));
			tiles.add(new WorldPoint(x, 3727, 0));
		}
		for (WorldPoint tile : tiles)
		{
			store.record(patch, tile, 1, 1);
		}

		assertTrue("the point must be on the patch, not in the notch of the L",
			tiles.contains(store.getLocation(patch)));

		// And the extent is still the whole block, so the ring is unchanged by the point moving.
		java.util.List<WorldPoint> targets = store.getRouteTargets(patch);
		assertTrue("ring still hugs the extent's south-west corner",
			targets.contains(new WorldPoint(1266, 3722, 0)));
		assertTrue("ring still hugs the extent's north-east corner",
			targets.contains(new WorldPoint(1273, 3728, 0)));
	}

	/**
	 * A tall tree is drawn on the storeys above it, and those are not the patch.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * The guild's redwood spawned twenty-two objects across planes 0, 1 and 2, and the last one
	 * won — so it was stored on plane 2. {@code GuideTracker.distance} answers
	 * {@code Integer.MAX_VALUE} whenever the stored plane differs from the player's, so the patch
	 * sorted last for ever and its route targets pointed upstairs.
	 *
	 * <p>Order-independent, which is the point: spawn order is not guaranteed, and the guild's
	 * tree arrived plane 0 first while its redwood arrived plane 0 last.
	 */
	@Test
	public void theStoreyAboveAPatchIsNotThePatch()
	{
		FarmPatch patch = FarmingWorldData.getAllPatches().iterator().next();

		PatchLocationStore groundFirst = freshStore();
		groundFirst.record(patch, new WorldPoint(1226, 3752, 0), 3, 3);
		groundFirst.record(patch, new WorldPoint(1230, 3756, 2), 4, 4);
		assertEquals("canopy seen second is ignored", 0,
			groundFirst.getLocation(patch).getPlane());

		PatchLocationStore canopyFirst = freshStore();
		canopyFirst.record(patch, new WorldPoint(1230, 3756, 2), 4, 4);
		canopyFirst.record(patch, new WorldPoint(1226, 3752, 0), 3, 3);
		assertEquals("canopy seen first is replaced", 0,
			canopyFirst.getLocation(patch).getPlane());
		assertEquals("and the ground sighting is what is kept",
			new WorldPoint(1226, 3752, 0), canopyFirst.getLocation(patch));
	}

	/**
	 * A store already holding a canopy sighting corrects itself, with nothing to migrate.
	 *
	 * <p>Why the rule lives in the store rather than in the capture: profiles in the wild already
	 * hold the plane-2 redwood, and the repair has to be "stand near it again" rather than "reset
	 * your patch locations".
	 */
	@Test
	public void aStoredCanopySightingHealsOnTheNextVisit()
	{
		PatchLocationStore store = freshStore();
		FarmPatch patch = FarmingWorldData.getAllPatches().iterator().next();
		store.record(patch, new WorldPoint(1230, 3756, 2), 4, 4);
		assertEquals("fixture: the bad state a live profile is in", 2,
			store.getLocation(patch).getPlane());

		store.record(patch, new WorldPoint(1226, 3752, 0), 3, 3);

		assertEquals(0, store.getLocation(patch).getPlane());
	}

	/** A patch never seen still gets a ring, around its seeded or region-centre point. */
	@Test
	public void routeTargetsExistBeforeThePatchIsEverSeen()
	{
		PatchLocationStore store = freshStore();
		FarmPatch patch = FarmingWorldData.getAllPatches().iterator().next();

		java.util.List<WorldPoint> targets = store.getRouteTargets(patch);

		// A ring plus the centre, around the patch's own measured footprint rather than the
		// assumed 3x3 - every patch ships with a real one now.
		assertTrue("a ring exists", targets.size() > 1);
		assertTrue("centred on the patch", targets.contains(store.getLocation(patch)));
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

	/**
	 * A seabed patch has no location worth routing to, which is why the approach exists.
	 *
	 * <p>Nothing on the seabed carries the patch varbit, so the store can never learn where a
	 * coral patch is and falls through to the middle of its region — for the Great Conch, a
	 * table on the deck; for the seaweed patches, a tile out at sea that nothing can reach.
	 * Pinned here because it is the fact {@code RunPlanner.routeTargetsFor} is built on, and
	 * because the day one of them <i>does</i> learn a location is the day that reasoning wants
	 * revisiting rather than silently ignoring.
	 */
	@org.junit.Test
	public void everySeabedPatchIsMeasuredToo()
	{
		for (com.dooglemaps.data.PatchImplementation type : new com.dooglemaps.data.
			PatchImplementation[]{com.dooglemaps.data.PatchImplementation.CORAL,
				com.dooglemaps.data.PatchImplementation.SEAWEED})
		{
			for (com.dooglemaps.data.FarmPatch patch
				: com.dooglemaps.data.FarmingWorldData.getPatches(type))
			{
				org.junit.Assert.assertTrue(type + " was walked up to like everything else",
					freshStore().isExact(patch));
				org.junit.Assert.assertEquals(type + " stands on the seabed, not the surface",
					1, freshStore().getLocation(patch).getPlane());
			}
		}
	}

	/**
	 * And routing to one still goes through its landward approach, not its position.
	 *
	 * <p>The half that actually matters, and the reason knowing where a seabed patch is changes
	 * nothing about getting to one: no path exists to the seabed, so
	 * {@code RunPlanner.routeTargetsFor} diverts to the steps or the rowboat regardless. This
	 * test used to assert the position was <i>unknown</i> and stood in for that; the position is
	 * known now, so the real rule is worth pinning on its own.
	 */
	@Test
	public void aSeabedPatchIsStillReachedByItsApproach()
	{
		for (com.dooglemaps.data.FarmPatch patch : com.dooglemaps.data.FarmingWorldData
			.getPatches(com.dooglemaps.data.PatchImplementation.CORAL))
		{
			assertNotNull("the seabed is reached on foot only via its approach",
				com.dooglemaps.data.UnderwaterApproach.forPatch(patch));
		}
	}

	@Test
	public void everyPatchIsExactFromAFreshInstall()
	{
		PatchLocationStore fresh = freshStore();
		for (com.dooglemaps.data.FarmPatch patch
			: com.dooglemaps.data.FarmingWorldData.getAllPatches())
		{
			org.junit.Assert.assertTrue(patch.getKey() + " ships with a measured position",
				fresh.isExact(patch));
			org.junit.Assert.assertTrue(patch.getKey() + " is therefore known, not assumed",
				fresh.isKnown(patch));
		}
		assertEquals("and the table covers the world data exactly",
			com.dooglemaps.data.FarmingWorldData.getAllPatches().size(),
			MeasuredPatchLocations.size());
	}

	private static com.dooglemaps.data.FarmPatch patchIn(int regionId)
	{
		for (com.dooglemaps.data.FarmPatch patch
			: com.dooglemaps.data.FarmingWorldData.getAllPatches())
		{
			if (patch.getRegion().getRegionId() == regionId)
			{
				return patch;
			}
		}
		throw new AssertionError("no patch in region " + regionId);
	}

	/**
	 * Every measured coordinate lands in the region its patch belongs to.
	 *
	 * <h2>The check that makes a baked table trustworthy</h2>
	 *
	 * The same evidence {@code everySeededCoordinateLandsInTheRegionItBelongsTo} applies to the
	 * wiki pins, applied to the measured ones. The two were derived independently — one from the
	 * wiki's map templates, one by standing in the game and watching objects spawn — so agreement
	 * on the region is real evidence rather than a tautology. A bad bake almost certainly moves a
	 * coordinate out of its region and fails here.
	 *
	 * <p><b>The hespori is exempt and that is not a fudge.</b> Its patch is in an instanced cave,
	 * so the coordinate read there is instance-space and belongs to no world region at all. It is
	 * in the table so the set is complete rather than complete-minus-one-unexplained-gap, and it
	 * is named here so nobody later reads its exemption as an oversight. Nothing routes to it —
	 * it is not in {@code PlantingGroups.RUNNABLE} — and anything that ever does has to replace
	 * the coordinate first. See {@code docs/TODO.md}.
	 */
	@Test
	public void everyMeasuredCoordinateLandsWhereItsPatchBelongs()
	{
		int checked = 0;
		for (FarmPatch patch : FarmingWorldData.getAllPatches())
		{
			int[] measured = MeasuredPatchLocations.forPatch(patch);
			assertNotNull(patch.getKey() + " is missing from the measured table", measured);
			if (patch.getImplementation() == com.dooglemaps.data.PatchImplementation.HESPORI)
			{
				continue;
			}

			WorldPoint at = new WorldPoint(measured[0], measured[1], measured[2]);
			assertTrue("measured coordinate for " + patch + " is not anywhere in "
					+ patch.getRegion().getName() + " (it is in region " + at.getRegionID() + ")",
				FarmingWorldData.getRegionsForLocation(at).contains(patch.getRegion()));
			checked++;
		}
		assertTrue("no measured coordinates were checked at all", checked > 100);
	}

	/** And every entry carries a real extent, so the router's ring is never guessed. */
	@Test
	public void everyMeasuredEntryCarriesItsFootprint()
	{
		for (FarmPatch patch : FarmingWorldData.getAllPatches())
		{
			int[] measured = MeasuredPatchLocations.forPatch(patch);
			assertEquals(patch.getKey() + " is not in the point-plus-extent shape",
				7, measured.length);
			assertTrue(patch.getKey() + " has a nonsense footprint",
				measured[3] >= 1 && measured[4] >= 1);
		}
	}
}
