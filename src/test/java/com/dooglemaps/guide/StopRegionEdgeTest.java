package com.dooglemaps.guide;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.route.PatchLocationStore;
import com.dooglemaps.route.RunStop;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * A stop still claims a player standing just over its region's edge.
 *
 * <h2>The cactus patch and the invisible line</h2>
 *
 * A stop is a 64x64 map region and its patches can sit near the edge — the Farming Guild's
 * cactus patch is the reported case. Standing on the patch's far side put the player's
 * region id one column over, every region-keyed question answered "between stops" at once,
 * and the leprechaun stopped highlighting for the note step until the player walked ~6
 * tiles back over a line nothing on screen shows. {@code standingAt} is the tolerance: the
 * stop's own region always, a touching region only within a few tiles of a patch whose
 * location has actually been learned.
 */
public class StopRegionEdgeTest
{
	private GuideTracker tracker;
	private PatchLocationStore locations;
	private FarmPatch patch;
	private WorldPoint patchTile;
	private RunStop stop;

	@Before
	public void setUp() throws Exception
	{
		locations = Mockito.mock(PatchLocationStore.class);
		tracker = trackerWith(locations);

		patch = FarmingWorldData.getPatches(PatchImplementation.CACTUS).get(0);
		assertNotNull(patch);

		// The patch sits one tile inside the west edge of its region, so the player can
		// stand beside it with a different region id. Region x = 20 -> tiles 1280..1343.
		patchTile = new WorldPoint(1281, 3730, 0);
		when(locations.getLocation(patch)).thenReturn(patchTile);

		stop = Mockito.mock(RunStop.class);
		com.dooglemaps.data.FarmRegion region =
			Mockito.mock(com.dooglemaps.data.FarmRegion.class);
		when(region.getRegionId()).thenReturn(patchTile.getRegionID());
		when(stop.getRegion()).thenReturn(region);
		when(stop.getPatches()).thenReturn(Collections.singletonList(patch));
		// Behaving like the real thing: a dry stop claims its own region and nothing else.
		// standingAt asks claimsRegion rather than the bare id, so a mock that answers false
		// to everything would make the stop's own ground read as somewhere else.
		when(stop.claimsRegion(Mockito.anyInt())).thenAnswer(invocation ->
			invocation.getArgument(0, Integer.class) == patchTile.getRegionID());
	}

	/**
	 * A stop that stands in more ground than it is filed under still counts as being at.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * The Great Conch's stop is region 12581 - the ship - while two of its patches are coral
	 * nurseries on the seabed at 13194. {@code RunStop.claimsRegion} exists so every caller
	 * answers "is the player at this stop" the same way, and its own comment says so; this one
	 * was added afterwards and compared the bare id instead.
	 *
	 * <p>What it cost: the stop completes when the last nursery is planted or paid for, and
	 * {@code appendLeavingErrandsAtFinishedStop} then asks this before re-attaching the leaving
	 * errands. Underwater it got no, so the note that turns a pack of coral into one stack was
	 * never offered. Reported from play as not being prompted to note at the nurseries.
	 */
	@Test
	public void aStopIsStoodInWhereverItClaimsToReach() throws Exception
	{
		int seabed = 13194;
		when(stop.claimsRegion(seabed)).thenReturn(true);

		// A tile on the seabed - a different region id, and nowhere near the stop's own.
		WorldPoint player = seabedTile(seabed);
		assertTrue("fixture: the seabed is not the stop's own region",
			player.getRegionID() != patchTile.getRegionID());
		assertTrue("the stop says it reaches here, so the player is at it",
			standingAt(player));
	}

	/** Any tile inside the given region, for a stop whose patches have no learnable position. */
	private static WorldPoint seabedTile(int regionId)
	{
		return new WorldPoint(((regionId >> 8) & 0xFF) * 64 + 32,
			(regionId & 0xFF) * 64 + 32, 0);
	}

	@Test
	public void theStopsOwnRegionAlwaysCounts() throws Exception
	{
		assertTrue(standingAt(new WorldPoint(1340, 3730, 0)));
	}

	@Test
	public void aTouchingRegionCountsBesideThePatch() throws Exception
	{
		// One region column west of the patch, four tiles away - the reported spot.
		WorldPoint player = new WorldPoint(1277, 3730, 0);
		assertTrue("the region id changed but the player did not leave the stop",
			player.getRegionID() != patchTile.getRegionID() && standingAt(player));
	}

	@Test
	public void aTouchingRegionFarFromEveryPatchDoesNotCount() throws Exception
	{
		assertFalse("thirty tiles out is travelling, not working",
			standingAt(new WorldPoint(1250, 3730, 0)));
	}

	@Test
	public void aPatchWithNoLearnedLocationClaimsNothingPastItsRegion() throws Exception
	{
		when(locations.getLocation(patch)).thenReturn(null);
		assertFalse("nothing to measure from means the old region rule stands",
			standingAt(new WorldPoint(1277, 3730, 0)));
	}

	// ------------------------------------------------------------------- helpers

	private boolean standingAt(WorldPoint player) throws Exception
	{
		Method method = GuideTracker.class.getDeclaredMethod(
			"standingAt", RunStop.class, WorldPoint.class);
		method.setAccessible(true);
		try
		{
			return (Boolean) method.invoke(tracker, stop, player);
		}
		catch (java.lang.reflect.InvocationTargetException e)
		{
			throw new AssertionError(e.getCause());
		}
	}

	private static GuideTracker trackerWith(PatchLocationStore locations) throws Exception
	{
		Constructor<?> constructor = GuideTracker.class.getDeclaredConstructors()[0];
		constructor.setAccessible(true);

		Class<?>[] types = constructor.getParameterTypes();
		Object[] args = new Object[types.length];
		for (int i = 0; i < types.length; i++)
		{
			args[i] = types[i] == PatchLocationStore.class
				? locations
				: Mockito.mock(types[i]);
		}
		return (GuideTracker) constructor.newInstance(args);
	}
}
