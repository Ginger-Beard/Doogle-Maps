package com.dooglemaps.capture;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.route.PatchLocationStore;
import com.google.gson.Gson;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * A scene's objects arrive before the stores have read anything, so recording waits a tick.
 *
 * <h2>The reported dead end</h2>
 *
 * Patch objects spawn during {@code LOADING}; {@code DoogleMapsPlugin} loads the stores on the
 * first {@code LOGGED_IN}. So at a login the whole scene is seen <b>before</b>
 * {@code PatchLocationStore} has read its blob — and recording there wrote into an empty map,
 * which is how a store holding six positions overwrote one holding a hundred.
 *
 * <p>{@code ProfileJsonStore}'s write guard stopped the damage and exposed the other half: the
 * sightings were then simply lost, because {@code load()} clears the map on its way in. Logging
 * in at the Farming Guild learned all thirteen patches correctly and kept none — ten refusals in
 * one second, with the guild's tree and redwood still on the wrong plane afterwards.
 */
public class PatchLocationTimingTest
{
	private Client client;
	private PatchLocationStore locations;
	private PatchLocationCapture capture;
	private FarmPatch patch;

	@Before
	public void setUp()
	{
		client = Mockito.mock(Client.class);
		locations = construct(PatchLocationStore.class,
			Mockito.mock(ConfigManager.class), new Gson());
		capture = construct(PatchLocationCapture.class, client, locations);
		patch = FarmingWorldData.getAllPatches().iterator().next();
	}

	/**
	 * A spawn on its own records nothing; the tick after it does.
	 *
	 * <p>The tick is the whole point: {@code GameTick} only fires while logged in, so the load
	 * that happens on the way to {@code LOGGED_IN} is provably already done. Nothing here has to
	 * assume an ordering between two subscribers.
	 */
	@Test
	public void aSpawnIsRecordedOnTheFollowingTick()
	{
		WorldPoint where = locationOf(patch);
		capture.onGameObjectSpawned(spawn(where, 3, 3));

		// isLearned, not isExact: every patch ships with a measured position now, so
		// isExact answers true before this class has done anything at all and the test
		// would pass without the capture running.
		assertFalse("nothing observed yet", locations.isLearned(patch));

		capture.onGameTick(new GameTick());

		assertTrue("and the tick is late enough to be safe", locations.isLearned(patch));
	}

	/** Every object of the scene is kept, so a multi-tile patch still unions to its full extent. */
	@Test
	public void everySightingInTheSceneSurvivesTheWait()
	{
		WorldPoint corner = locationOf(patch);
		for (int x = 0; x < 6; x++)
		{
			for (int y = 0; y < 5; y++)
			{
				capture.onGameObjectSpawned(spawn(
					new WorldPoint(corner.getX() + x, corner.getY() + y, 0), 1, 1));
			}
		}

		capture.onGameTick(new GameTick());

		// The ring of a 6x5 extent, not of the one tile that happened to arrive last.
		assertTrue(locations.getRouteTargets(patch).contains(
			new WorldPoint(corner.getX() - 1, corner.getY() - 1, 0)));
		assertTrue(locations.getRouteTargets(patch).contains(
			new WorldPoint(corner.getX() + 6, corner.getY() + 5, 0)));
	}

	/** And a tick with nothing waiting does not touch the store. */
	@Test
	public void anIdleTickRecordsNothing()
	{
		capture.onGameTick(new GameTick());
		assertFalse(locations.isLearned(patch));
	}

	/**
	 * A point inside the patch's own region, so {@code getRegionsForLocation} resolves it.
	 *
	 * <p>Taken from the region rather than invented: the capture matches a spawn to a patch by
	 * looking up the region the object stands in, so a coordinate somewhere else matches nothing
	 * and the test would pass for the wrong reason.
	 */
	private static WorldPoint locationOf(FarmPatch patch)
	{
		int regionId = patch.getRegion().getRegionId();
		return new WorldPoint(((regionId >>> 8) << 6) + 20, ((regionId & 0xFF) << 6) + 20, 0);
	}

	private GameObjectSpawned spawn(WorldPoint where, int sizeX, int sizeY)
	{
		ObjectComposition definition = Mockito.mock(ObjectComposition.class);
		when(definition.getVarbitId()).thenReturn(patch.getVarbit());
		when(client.getObjectDefinition(1)).thenReturn(definition);

		GameObject object = Mockito.mock(GameObject.class);
		when(object.getId()).thenReturn(1);
		when(object.getWorldLocation()).thenReturn(where);
		when(object.sizeX()).thenReturn(sizeX);
		when(object.sizeY()).thenReturn(sizeY);

		GameObjectSpawned event = new GameObjectSpawned();
		event.setGameObject(object);
		return event;
	}
}
