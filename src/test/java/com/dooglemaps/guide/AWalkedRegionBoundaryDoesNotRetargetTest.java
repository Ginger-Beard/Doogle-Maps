package com.dooglemaps.guide;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.route.RunPlanner;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.timer.GrowthTimer;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.PlayerLocation;
import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * {@code GuideTracker.retargetIfMoved} used to fire on a region change alone. Walking across a
 * region boundary is a region change with nothing behind it worth re-asking Shortest Path about,
 * and re-asking is not free: the router answers afresh from the new tile, and its
 * walk-versus-teleport cost comparison can flip on a one-tile difference. Reported from play at
 * Falador — standing on one tile the guide had drawn as a walk to Taverley, stepping onto the
 * next tile flipped the instruction to "Cast Teleport to House ... via Grand Exchange Portal".
 *
 * <p>The fix keys the retarget on the move looking like a jump, not merely on the region number
 * changing. These tests drive {@link GuideTracker#onGameTick} across the four kinds of move a
 * region change can ride on: a walked step, a plane change, a same-region jump, and an
 * across-region teleport (with and without an intervening LOADING tick) — plus the first tick of
 * a run, which still has to retarget the way it always did.
 */
public class AWalkedRegionBoundaryDoesNotRetargetTest
{
	private static final String FALADOR_HERB = "12083.4774";
	private static final String CATHERBY_HERB = "11062.4774";
	private static final int RAKED_AND_EMPTY = 3;

	// Adjacent tiles either side of a region boundary (x=3136 is a multiple of 64, so x=3135
	// and x=3136 land in different regions despite being one tile apart) — a single walked step.
	private static final WorldPoint BOUNDARY_A = new WorldPoint(3135, 3200, 0);
	private static final WorldPoint BOUNDARY_B = new WorldPoint(3136, 3200, 0);

	// Same tiles, but one plane apart, so the region changes and the plane changes together.
	private static final WorldPoint PLANE_A = new WorldPoint(3135, 3200, 0);
	private static final WorldPoint PLANE_B = new WorldPoint(3136, 3200, 1);

	// Both land in region (50,50); twenty tiles apart is a jump no walk could make, but it never
	// leaves the region, so today's "region changed" test would not have retargeted either.
	private static final WorldPoint SAME_REGION_A = new WorldPoint(3200, 3200, 0);
	private static final WorldPoint SAME_REGION_FAR = new WorldPoint(3220, 3200, 0);

	// A hundred tiles apart and in different regions — a teleport with no loading tick observed
	// (some teleports do not blank the position store the way a loading screen does).
	private static final WorldPoint JUMP_START = new WorldPoint(3200, 3200, 0);
	private static final WorldPoint JUMP_FAR = new WorldPoint(3300, 3200, 0);

	private static final WorldPoint LUMBRIDGE = new WorldPoint(3222, 3218, 0);
	private static final WorldPoint VARROCK = new WorldPoint(3210, 3424, 0);

	private final List<PluginMessage> posted = new ArrayList<>();
	private RunPlanner planner;
	private GuideTracker tracker;
	private PlayerLocation playerLocation;
	private Player player;
	private PatchStateStore stateStore;
	private AvailabilityProfile availability;

	@Before
	public void setUp() throws Exception
	{
		Map<String, String> stored = new HashMap<>();
		Gson gson = new Gson();
		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer(i -> stored.get(i.getArgument(0) + "." + i.getArgument(1)));
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(int.class)))
			.thenAnswer(i -> null);
		doAnswer(i ->
		{
			stored.put(i.getArgument(0) + "." + i.getArgument(1),
				String.valueOf((Object) i.getArgument(2)));
			return null;
		}).when(configManager).setRSProfileConfiguration(anyString(), anyString(), any());

		net.runelite.client.callback.ClientThread clientThread =
			Mockito.mock(net.runelite.client.callback.ClientThread.class);
		doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		EventBus eventBus = Mockito.mock(EventBus.class);
		doAnswer(i ->
		{
			Object event = i.getArgument(0);
			if (event instanceof PluginMessage)
			{
				posted.add((PluginMessage) event);
			}
			return null;
		}).when(eventBus).post(any());

		stateStore = construct(PatchStateStore.class, configManager, gson);
		availability = construct(AvailabilityProfile.class, configManager, gson, stateStore);
		com.dooglemaps.route.PatchLocationStore locations =
			construct(com.dooglemaps.route.PatchLocationStore.class, configManager, gson);
		com.dooglemaps.route.BankLocationStore banks =
			construct(com.dooglemaps.route.BankLocationStore.class, configManager, gson);
		GrowthTimer timer = construct(GrowthTimer.class, configManager);
		com.dooglemaps.route.ShortestPathIntegration router =
			construct(com.dooglemaps.route.ShortestPathIntegration.class, eventBus, clientThread);
		stateStore.load();
		availability.load();
		locations.load();
		banks.load();

		net.runelite.api.Client client = Mockito.mock(net.runelite.api.Client.class);
		player = Mockito.mock(Player.class);
		when(client.getLocalPlayer()).thenReturn(player);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		playerLocation = construct(PlayerLocation.class, client);

		com.dooglemaps.state.SeedSelectionStore selection =
			Mockito.mock(com.dooglemaps.state.SeedSelectionStore.class);
		com.dooglemaps.state.SeedInventoryStore seeds =
			Mockito.mock(com.dooglemaps.state.SeedInventoryStore.class);
		com.dooglemaps.state.PlantingGroups groups =
			Mockito.mock(com.dooglemaps.state.PlantingGroups.class);
		com.dooglemaps.data.PlantingGroup herbs =
			Mockito.mock(com.dooglemaps.data.PlantingGroup.class);
		when(herbs.getKey()).thenReturn("herb");
		when(groups.groupFor(any())).thenReturn(herbs);
		// A seed to plant, so the stops are worth visiting and stay in the run.
		when(selection.getSelectedFor(any(com.dooglemaps.data.PlantingGroup.class)))
			.thenReturn(java.util.Collections.singleton(com.dooglemaps.data.Seed.GUAM));
		when(seeds.getOwnedPlantable(com.dooglemaps.data.Seed.GUAM)).thenReturn(10);
		when(seeds.getPlantable(com.dooglemaps.data.Seed.GUAM,
			com.dooglemaps.state.SeedSource.INVENTORY)).thenReturn(10);
		when(seeds.getPlantableOnHand(com.dooglemaps.data.Seed.GUAM)).thenReturn(10);
		when(seeds.getFarmingLevel()).thenReturn(99);
		when(groups.patchesIn(any())).thenReturn(java.util.Arrays.asList(
			patch(FALADOR_HERB), patch(CATHERBY_HERB)));
		com.dooglemaps.state.CompostSelectionStore compost =
			Mockito.mock(com.dooglemaps.state.CompostSelectionStore.class);
		when(compost.get(any(com.dooglemaps.data.PlantingGroup.class)))
			.thenReturn(com.dooglemaps.data.CompostTier.NONE);
		com.dooglemaps.state.RunTypeStore runOptions =
			Mockito.mock(com.dooglemaps.state.RunTypeStore.class);
		when(runOptions.isSelected(any())).thenReturn(true);

		planner = construct(RunPlanner.class, availability, locations, banks, selection,
			seeds, stateStore, timer, router, playerLocation,
			Mockito.mock(com.dooglemaps.bank.ToolNeeds.class),
			Mockito.mock(com.dooglemaps.state.ProtectedPatches.class),
			groups,
			Mockito.mock(com.dooglemaps.state.ProtectionSelectionStore.class),
			runOptions,
			Mockito.mock(com.dooglemaps.state.CompostRunStore.class),
			Mockito.mock(com.dooglemaps.DoogleMapsConfig.class),
			Mockito.mock(com.dooglemaps.bank.BankContents.class),
			Mockito.mock(com.dooglemaps.guide.CarriedItems.class));

		tracker = trackerWith(planner, stateStore, timer, playerLocation, selection, seeds,
			groups, compost, runOptions, client);

		record(FALADOR_HERB, RAKED_AND_EMPTY);
		record(CATHERBY_HERB, RAKED_AND_EMPTY);
		availability.setAvailable(patch(FALADOR_HERB), true);
		availability.setAvailable(patch(CATHERBY_HERB), true);
	}

	@Test
	public void walkingAcrossARegionBoundaryDoesNotRetarget()
	{
		standAt(BOUNDARY_A);
		planner.start(EnumSet.of(PatchImplementation.HERB));
		tracker.onGameTick(null);
		int before = pathRequests();

		standAt(BOUNDARY_B);
		tracker.onGameTick(null);

		assertEquals("a walked step, even across a region boundary, is not a jump",
			before, pathRequests());
	}

	@Test
	public void aPlaneChangeIntoANewRegionRetargets()
	{
		standAt(PLANE_A);
		planner.start(EnumSet.of(PatchImplementation.HERB));
		tracker.onGameTick(null);
		int before = pathRequests();

		standAt(PLANE_B);
		tracker.onGameTick(null);

		assertEquals("a plane change is never something walking or running does",
			before + 1, pathRequests());
	}

	@Test
	public void aJumpWithinTheSameRegionDoesNotRetarget()
	{
		standAt(SAME_REGION_A);
		planner.start(EnumSet.of(PatchImplementation.HERB));
		tracker.onGameTick(null);
		int before = pathRequests();

		standAt(SAME_REGION_FAR);
		tracker.onGameTick(null);

		assertEquals("today's behaviour, unchanged: nothing retargets without a region change",
			before, pathRequests());
	}

	@Test
	public void aTeleportWithoutAnObservedLoadingTickStillRetargets()
	{
		standAt(JUMP_START);
		planner.start(EnumSet.of(PatchImplementation.HERB));
		tracker.onGameTick(null);
		int before = pathRequests();

		// No LOADING observed in between - just a hundred-tile move into a new region, further
		// than a single tick of running could ever cover.
		standAt(JUMP_FAR);
		tracker.onGameTick(null);

		assertEquals("distance alone is enough to call this a jump",
			before + 1, pathRequests());
	}

	@Test
	public void aTeleportWithALoadingTickRetargets()
	{
		standAt(LUMBRIDGE);
		planner.start(EnumSet.of(PatchImplementation.HERB));
		tracker.onGameTick(null);
		int before = pathRequests();

		// LOADING blanks the position store; the guide ticks before it re-samples, so this tick
		// sees no tile at all.
		playerLocation.onGameStateChanged(stateChange(GameState.LOADING));
		tracker.onGameTick(null);
		assertEquals("no tile is not a move; nothing is asked from nowhere",
			before, pathRequests());

		playerLocation.onGameStateChanged(stateChange(GameState.LOGGED_IN));
		standAt(VARROCK);
		tracker.onGameTick(null);

		assertEquals("an unknown previous tile reads the next known tile as a jump",
			before + 1, pathRequests());
	}

	@Test
	public void theFirstKnownTileOfARunStillRetargets()
	{
		standAt(LUMBRIDGE);
		// start() asks for a route of its own accord (see RunPlanner.startPlanned); captured here
		// so the assertion below isolates retargetIfMoved's own contribution from it.
		planner.start(EnumSet.of(PatchImplementation.HERB));
		int before = pathRequests();

		tracker.onGameTick(null);

		assertEquals("the very first tick has no previous tile, so it reads as a jump too",
			before + 1, pathRequests());
	}

	private int pathRequests()
	{
		int count = 0;
		for (PluginMessage message : posted)
		{
			if ("path".equals(message.getName()))
			{
				count++;
			}
		}
		return count;
	}

	private void standAt(WorldPoint tile)
	{
		when(player.getWorldLocation()).thenReturn(tile);
		playerLocation.onGameTick(null);
	}

	private static GameStateChanged stateChange(GameState state)
	{
		GameStateChanged event = new GameStateChanged();
		event.setGameState(state);
		return event;
	}

	private void record(String key, int varbitValue)
	{
		FarmPatch p = patch(key);
		ProduceState decoded = p.getImplementation().forVarbitValue(varbitValue);
		assertNotNull(decoded);
		stateStore.recordVarbit(p, varbitValue, decoded);
	}

	private static FarmPatch patch(String key)
	{
		FarmPatch patch = FarmingWorldData.getPatch(key);
		assertNotNull("fixture patch " + key + " no longer exists", patch);
		return patch;
	}

	private static GuideTracker trackerWith(Object... real) throws Exception
	{
		Constructor<?> constructor = GuideTracker.class.getDeclaredConstructors()[0];
		constructor.setAccessible(true);
		Class<?>[] types = constructor.getParameterTypes();
		Object[] args = new Object[types.length];
		for (int i = 0; i < types.length; i++)
		{
			for (Object candidate : real)
			{
				if (types[i].isInstance(candidate))
				{
					args[i] = candidate;
					break;
				}
			}
			if (args[i] == null)
			{
				args[i] = Mockito.mock(types[i]);
			}
		}
		return (GuideTracker) constructor.newInstance(args);
	}
}
