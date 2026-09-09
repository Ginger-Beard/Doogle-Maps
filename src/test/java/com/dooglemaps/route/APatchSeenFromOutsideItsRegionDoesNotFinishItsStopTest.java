package com.dooglemaps.route;

import com.dooglemaps.capture.PatchInteractionTracker;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.timer.GrowthTimer;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.HashTable;
import net.runelite.api.Player;
import net.runelite.api.WidgetNode;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * A tree seen from the next region over is not the tree whose varbit the client is holding.
 *
 * <h2>The reported dead end</h2>
 *
 * A tree run with two stops left — Auburnvale and Taverley — walking north out of Falador. At
 * 23:03:06 the player crossed y=3392 into region 11829, the strip between Falador's north wall
 * and Taverley, and one tick later the log said
 * {@code Stop at Taverley finished with nothing left to do} with the patch fifty tiles away and
 * untouched. The run dropped Taverley, re-chose its leg, and sent the player to a fairy ring for
 * Auburnvale while the infobox still said "Taverley".
 *
 * <h2>Why the run believed it</h2>
 *
 * Every tree patch answers on the same transmitted varbit, and the server sends it for the zone
 * the player is in. {@code FarmingWorldData} registers Taverley under 11573 <b>and</b> 11829, so
 * the first scan taken in 11829 reads that one varbit and files it under Taverley's key — while
 * it still holds Falador's tree. The session's config writes show the swap exactly:
 * {@code 11828.4771} recorded 26 (a maple two stages in) at 23:03:05, {@code 11573.4771} recorded
 * the same 26 at 23:03:06, and the real Taverley value, 32, arrived at 23:03:07. A growing maple
 * is nothing to do, so the stop was finished by a patch the client had never described.
 *
 * <p>Modelled here with the two values from that log, because the numbers are the evidence: 26 is
 * Falador's crop and 32 is Taverley's, and the bug is the moment they share a key.
 */
public class APatchSeenFromOutsideItsRegionDoesNotFinishItsStopTest
{
	/** The Taverley tree patch, and the stop that must survive the walk. */
	private static final String TAVERLEY_TREE = "11573.4771";

	/** Falador's tree patch: same varbit id, one region south, holding a different crop. */
	private static final String FALADOR_TREE = "11828.4771";

	/** The other stop left on the run, standing in for Auburnvale. */
	private static final String VARROCK_TREE = "12854.4771";

	/** Taverley's own crop that day: a maple at its last growth stage, waiting to be cleared. */
	private static final int MAPLE_GROWN = 32;

	/** Falador's crop that day: the same maple two stages in, and nothing to do about it. */
	private static final int MAPLE_STAGE_TWO = 26;

	/**
	 * A tile in region 11829, north of Falador's wall and south of Taverley — the strip the
	 * player was crossing. The Taverley patch is at (2936,3438), fifty tiles away.
	 */
	private static final WorldPoint BETWEEN_FALADOR_AND_TAVERLEY = new WorldPoint(2966, 3393, 0);

	private Map<String, String> stored;
	private ConfigManager configManager;
	private PatchStateStore stateStore;
	private AvailabilityProfile availability;
	private GrowthTimer timer;
	private RunPlanner planner;
	private PatchInteractionTracker tracker;
	private Client client;
	private com.dooglemaps.state.PlayerLocation playerLocation;

	/** Varbit id to the value the client will report; anything unlisted reads 0. */
	private Map<Integer, Integer> varbits;

	/** Every PluginMessage the planner caused, in order. */
	private List<PluginMessage> posted;

	@Before
	public void setUp()
	{
		stored = new HashMap<>();
		varbits = new HashMap<>();
		posted = new ArrayList<>();
		Gson gson = new Gson();

		configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer(i -> stored.get(key(i)));
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(int.class)))
			.thenAnswer(i -> null);
		when(configManager.getRSProfileConfiguration(anyString(), eq("farmingLevel"),
			eq(int.class))).thenReturn(99);
		doAnswer(i ->
		{
			Object value = i.getArgument(2);
			stored.put(key(i), String.valueOf(value));
			return null;
		}).when(configManager).setRSProfileConfiguration(anyString(), anyString(), any());

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

		net.runelite.client.callback.ClientThread clientThread =
			Mockito.mock(net.runelite.client.callback.ClientThread.class);
		doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		stateStore = construct(PatchStateStore.class, configManager, gson);
		availability = construct(AvailabilityProfile.class, configManager, gson, stateStore);
		PatchLocationStore locations = construct(PatchLocationStore.class, configManager, gson);
		BankLocationStore banks = construct(BankLocationStore.class, configManager, gson);
		timer = construct(GrowthTimer.class, configManager);
		ShortestPathIntegration router =
			construct(ShortestPathIntegration.class, eventBus, clientThread);

		stateStore.load();
		availability.load();
		locations.load();
		banks.load();

		com.dooglemaps.state.ContractState contracts =
			construct(com.dooglemaps.state.ContractState.class, configManager);
		com.dooglemaps.state.SeedSelectionStore selection = construct(
			com.dooglemaps.state.SeedSelectionStore.class, configManager, gson, contracts);
		com.dooglemaps.state.SeedInventoryStore seedInventory =
			construct(com.dooglemaps.state.SeedInventoryStore.class,
				Mockito.mock(Client.class), configManager, gson);
		selection.load();
		seedInventory.load();

		client = client();
		playerLocation = construct(com.dooglemaps.state.PlayerLocation.class, client);

		planner = construct(RunPlanner.class, availability, locations, banks, selection,
			seedInventory, stateStore, timer, router,
			playerLocation, Mockito.mock(com.dooglemaps.bank.ToolNeeds.class),
			Mockito.mock(com.dooglemaps.state.ProtectedPatches.class),
			Mockito.mock(com.dooglemaps.state.PlantingGroups.class),
			Mockito.mock(com.dooglemaps.state.ProtectionSelectionStore.class),
			Mockito.mock(com.dooglemaps.state.RunTypeStore.class),
			Mockito.mock(com.dooglemaps.state.CompostRunStore.class),
			Mockito.mock(com.dooglemaps.DoogleMapsConfig.class),
			Mockito.mock(com.dooglemaps.bank.BankContents.class),
			Mockito.mock(com.dooglemaps.guide.CarriedItems.class));

		tracker = construct(PatchInteractionTracker.class,
			client,
			stateStore,
			timer,
			planner,
			Mockito.mock(com.dooglemaps.validate.HarvestLog.class),
			Mockito.mock(com.dooglemaps.state.BarbarianFarming.class),
			Mockito.mock(com.dooglemaps.guide.CarriedItems.class),
			Mockito.mock(com.dooglemaps.validate.DiseaseStatsStore.class),
			Mockito.mock(com.dooglemaps.state.ProtectedPatches.class),
			Mockito.mock(com.dooglemaps.state.TimeTrackingState.class));
	}

	/**
	 * Walking into 11829 must not finish Taverley, and must not drop it from the next route.
	 *
	 * <p>The two assertions are the two halves the player saw: the stop quietly leaving the run,
	 * and the route swinging round to the other side of the map with the name unchanged.
	 */
	@Test
	public void aTreeReadFromTheNextRegionDoesNotFinishTaverley()
	{
		FarmPatch taverley = patch(TAVERLEY_TREE);
		FarmPatch falador = patch(FALADOR_TREE);
		FarmPatch varrock = patch(VARROCK_TREE);

		assertEquals("fixture: the two trees answer on the same transmitted varbit",
			falador.getVarbit(), taverley.getVarbit());
		assertMaple(taverley, MAPLE_GROWN, "a maple that has finished growing");
		assertMaple(falador, MAPLE_STAGE_TWO, "a maple still growing");

		// Taverley holds a grown maple waiting to be cleared, Falador one two stages in, and
		// the far stop is an empty patch. Only the two stops are switched on.
		record(taverley, MAPLE_GROWN);
		record(falador, MAPLE_STAGE_TWO);
		record(varrock, 3);
		availability.setAvailable(taverley, true);
		availability.setAvailable(varrock, true);
		availability.setAvailable(falador, true);

		planner.start(EnumSet.of(PatchImplementation.TREE));
		planner.leaveBank();
		assertEquals("fixture: two stops left, the way the run was planned",
			2, planner.getRemaining().size());
		assertTrue("fixture: Taverley is one of them", remainingRegions().contains(11573));

		// Standing at the Falador tree, where the client is being sent Falador's varbit.
		standAt(new WorldPoint(3004, 3373, 0));
		varbits.put(taverley.getVarbit(), MAPLE_STAGE_TWO);
		tick();

		// One step north over y=3392 into 11829. The server has not started sending Taverley's
		// tree yet, so the varbit still describes Falador's.
		standAt(BETWEEN_FALADOR_AND_TAVERLEY);
		tick();

		assertTrue("a patch read from outside its own region cannot finish its stop",
			remainingRegions().contains(11573));
		assertTrue("and the next route still has to go there",
			regionsTargeted().contains(11573));
	}

	// ------------------------------------------------------------------- helpers

	/** One client tick: the position store, the capture scan, then the planner's review. */
	private void tick()
	{
		playerLocation.onGameTick(new GameTick());
		tracker.onGameTick(new GameTick());
		planner.reviewProgress();
	}

	private void standAt(WorldPoint where)
	{
		Player player = Mockito.mock(Player.class);
		when(player.getWorldLocation()).thenReturn(where);
		when(client.getLocalPlayer()).thenReturn(player);
	}

	private Set<Integer> remainingRegions()
	{
		Set<Integer> regions = new java.util.HashSet<>();
		for (RunStop stop : planner.getRemaining())
		{
			regions.add(stop.getRegion().getRegionId());
		}
		return regions;
	}

	private Set<Integer> regionsTargeted()
	{
		Set<Integer> regions = new java.util.HashSet<>();
		for (WorldPoint target : lastTargets())
		{
			regions.add(target.getRegionID());
		}
		return regions;
	}

	@SuppressWarnings("unchecked")
	private Set<WorldPoint> lastTargets()
	{
		for (int i = posted.size() - 1; i >= 0; i--)
		{
			PluginMessage message = posted.get(i);
			if ("clear".equals(message.getName()))
			{
				return Collections.emptySet();
			}
			Object target = message.getData().get("target");
			if (target instanceof Set)
			{
				return (Set<WorldPoint>) target;
			}
		}
		return Collections.emptySet();
	}

	private void record(FarmPatch patch, int varbitValue)
	{
		ProduceState decoded = patch.getImplementation().forVarbitValue(varbitValue);
		assertNotNull("varbit " + varbitValue + " does not decode for " + patch.getKey(), decoded);
		stateStore.recordVarbit(patch, varbitValue, decoded);
	}

	private static void assertMaple(FarmPatch patch, int varbitValue, String what)
	{
		ProduceState decoded = patch.getImplementation().forVarbitValue(varbitValue);
		assertNotNull("fixture: " + varbitValue + " no longer decodes for " + patch.getKey(),
			decoded);
		assertEquals("fixture: " + varbitValue + " is " + what,
			Produce.MAPLE, decoded.getProduce());
	}

	private static FarmPatch patch(String key)
	{
		FarmPatch patch = FarmingWorldData.getPatch(key);
		assertNotNull("fixture patch " + key + " no longer exists", patch);
		return patch;
	}

	private static String key(InvocationOnMock invocation)
	{
		Object group = invocation.getArgument(0);
		Object name = invocation.getArgument(1);
		return group + "." + name;
	}

	/** A logged-in client with no interface open, answering from the varbit table. */
	@SuppressWarnings("unchecked")
	private Client client()
	{
		Client mock = Mockito.mock(Client.class);
		when(mock.getGameState()).thenReturn(GameState.LOGGED_IN);

		HashTable<WidgetNode> components = Mockito.mock(HashTable.class);
		when(components.iterator()).thenAnswer(i -> Collections.<WidgetNode>emptyIterator());
		when(mock.getComponentTable()).thenReturn(components);

		when(mock.getVarbitValue(anyInt()))
			.thenAnswer(i -> varbits.getOrDefault((Integer) i.getArgument(0), 0));
		return mock;
	}
}
