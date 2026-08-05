package com.dooglemaps.route;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.SeedSource;
import com.dooglemaps.timer.GrowthTimer;
import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Covers the two decisions the planner actually makes: which patches are worth visiting,
 * and what gets handed to the router.
 *
 * <p>Ordering is deliberately absent, because the planner does not do any — it posts the
 * whole outstanding set and Shortest Path picks. What is worth pinning down is that the set
 * contains the right things at the right time.
 */
public class RunPlannerTest
{
	private static final String FALADOR_HERB = "12083.4774";
	private static final String CATHERBY_HERB = "11062.4774";
	private static final String ARDOUGNE_HERB = "10548.4774";

	private Map<String, String> stored;
	private ConfigManager configManager;
	private PatchStateStore stateStore;
	private AvailabilityProfile availability;
	private com.dooglemaps.state.SeedSelectionStore selection;
	private com.dooglemaps.state.SeedInventoryStore seedInventory;
	private BankLocationStore banks;
	private RunPlanner planner;
	private net.runelite.api.Client client;
	private com.dooglemaps.state.PlayerLocation playerLocation;

	/** Every PluginMessage the planner caused, in order. */
	private List<PluginMessage> posted;

	@Before
	public void setUp() throws Exception
	{
		stored = new HashMap<>();
		posted = new ArrayList<>();
		Gson gson = new Gson();

		configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer(i -> stored.get(key(i)));
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(int.class)))
			.thenAnswer(i -> null);
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

		stateStore = construct(PatchStateStore.class, configManager, gson);
		availability = construct(AvailabilityProfile.class, configManager, gson, stateStore);
		PatchLocationStore locations = construct(PatchLocationStore.class, configManager, gson);
		banks = construct(BankLocationStore.class, configManager, gson);
		GrowthTimer timer = construct(GrowthTimer.class, configManager);
		// Run queued client-thread work immediately, so the test sees what was posted.
		net.runelite.client.callback.ClientThread clientThread =
			Mockito.mock(net.runelite.client.callback.ClientThread.class);
		doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		ShortestPathIntegration router = construct(ShortestPathIntegration.class, eventBus, clientThread);

		stateStore.load();
		availability.load();
		locations.load();
		banks.load();

		selection = construct(com.dooglemaps.state.SeedSelectionStore.class, configManager, gson,
			construct(com.dooglemaps.state.ContractState.class, configManager));
		seedInventory = construct(com.dooglemaps.state.SeedInventoryStore.class,
			Mockito.mock(net.runelite.api.Client.class), configManager, gson);
		selection.load();
		seedInventory.load();

		client = Mockito.mock(net.runelite.api.Client.class);
		playerLocation = construct(com.dooglemaps.state.PlayerLocation.class, client);
		planner = construct(RunPlanner.class, availability, locations, banks, selection,
			seedInventory, stateStore, timer, router,
			playerLocation, Mockito.mock(com.dooglemaps.bank.ToolNeeds.class),
			Mockito.mock(com.dooglemaps.state.ProtectedPatches.class),
			Mockito.mock(com.dooglemaps.state.PlantingGroups.class),
			Mockito.mock(com.dooglemaps.state.ProtectionSelectionStore.class),
			Mockito.mock(com.dooglemaps.state.RunTypeStore.class));
	}

	/** Puts the local player somewhere in the given map region. */
	private void standingIn(int regionId)
	{
		WorldPoint where = new WorldPoint(
			((regionId >>> 8) << 6) + 32, ((regionId & 0xFF) << 6) + 32, 0);
		net.runelite.api.Player player = Mockito.mock(net.runelite.api.Player.class);
		when(player.getWorldLocation()).thenReturn(where);
		when(client.getLocalPlayer()).thenReturn(player);

		// The position is sampled once a tick rather than read on demand, so the tick has to
		// happen for anything to see it - exactly as in the client.
		playerLocation.onGameTick(new net.runelite.api.events.GameTick());
	}

	private static String key(InvocationOnMock invocation)
	{
		Object group = invocation.getArgument(0);
		Object name = invocation.getArgument(1);
		return group + "." + name;
	}

	@SuppressWarnings("unchecked")
	private static <T> T construct(Class<T> type, Object... args) throws Exception
	{
		for (Constructor<?> candidate : type.getDeclaredConstructors())
		{
			if (candidate.getParameterCount() == args.length)
			{
				candidate.setAccessible(true);
				return (T) candidate.newInstance(args);
			}
		}
		throw new IllegalStateException("no constructor of arity " + args.length + " on " + type);
	}

	private static FarmPatch patch(String key)
	{
		FarmPatch patch = FarmingWorldData.getPatch(key);
		assertNotNull("fixture patch " + key + " no longer exists", patch);
		return patch;
	}

	private void stockVault(com.dooglemaps.data.Seed seed, int quantity)
	{
		stock(SeedSource.SEED_VAULT, seed, quantity);
	}

	private void stockBank(com.dooglemaps.data.Seed seed, int quantity)
	{
		stock(SeedSource.BANK, seed, quantity);
	}

	private void stockInventory(com.dooglemaps.data.Seed seed, int quantity)
	{
		stock(SeedSource.INVENTORY, seed, quantity);
	}

	private void stock(SeedSource source, com.dooglemaps.data.Seed seed, int quantity)
	{
		net.runelite.api.ItemContainer container = Mockito.mock(net.runelite.api.ItemContainer.class);
		when(container.getItems()).thenReturn(new net.runelite.api.Item[]{
			new net.runelite.api.Item(seed.getItemID(), quantity),
		});
		seedInventory.record(source.getContainerId(), container);
	}

	private void record(String key, int varbitValue)
	{
		FarmPatch p = patch(key);
		ProduceState decoded = p.getImplementation().forVarbitValue(varbitValue);
		assertNotNull("varbit " + varbitValue + " does not decode for " + key, decoded);
		stateStore.recordVarbit(p, varbitValue, decoded);
	}

	/** Targets in the most recent path message, or empty if the last message was a clear. */
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

	// ------------------------------------------------------- what goes in a run

	@Test
	public void aGrowingCropIsNotWorthVisiting()
	{
		record(FALADOR_HERB, 33);   // ranarr, growing
		availability.setAvailable(patch(FALADOR_HERB), true);

		List<RunStop> stops = planner.start(EnumSet.of(PatchImplementation.HERB));

		assertTrue("a growing crop should be left alone", stops.isEmpty());
		assertFalse(planner.isActive());
	}

	@Test
	public void readyEmptyAndDeadPatchesAllGoInTheRun()
	{
		record(CATHERBY_HERB, 43);    // toadflax, harvestable
		record(FALADOR_HERB, 3);      // raked, empty
		record(ARDOUGNE_HERB, 171);   // dead
		for (String key : new String[]{CATHERBY_HERB, FALADOR_HERB, ARDOUGNE_HERB})
		{
			availability.setAvailable(patch(key), true);
		}

		List<RunStop> stops = planner.start(EnumSet.of(PatchImplementation.HERB));

		assertEquals("ready, empty and dead all want attention", 3, stops.size());
		assertTrue(planner.isActive());
	}

	@Test
	public void patchesSwitchedOffAreNeverInARun()
	{
		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), false);

		assertTrue("availability is a global invariant",
			planner.start(EnumSet.of(PatchImplementation.HERB)).isEmpty());
	}

	@Test
	public void oneRegionIsOneStopHoweverManyPatchTypes()
	{
		// Falador's allotments, flower and herb patches share a region, so servicing all of
		// them is a single stop and costs no extra travel.
		record("12083.4771", 3);
		record("12083.4772", 3);
		record("12083.4773", 3);
		record(FALADOR_HERB, 3);
		for (String key : new String[]{"12083.4771", "12083.4772", "12083.4773", FALADOR_HERB})
		{
			availability.setAvailable(patch(key), true);
		}

		List<RunStop> stops = planner.start(EnumSet.of(
			PatchImplementation.ALLOTMENT, PatchImplementation.FLOWER, PatchImplementation.HERB));

		assertEquals("four patches, one place to stand", 1, stops.size());
		assertEquals(4, stops.get(0).getPatches().size());
	}

	// --------------------------------------------------- starting where you stand

	/**
	 * Standing on work, the run starts there rather than at a bank.
	 *
	 * <p>Reported from play: stood at the Ardougne patches with dead limpwurt, dead and ripe
	 * watermelons and part-picked guams, with everything needed already carried, and Start run
	 * asked for a teleport to a bank. Being routed away from crops you are stood next to is
	 * wrong whatever the trip needs collecting later.
	 */
	@Test
	public void aRunStartsWhereYouAreStandingWhenThereIsWorkThere()
	{
		record(ARDOUGNE_HERB, 43);   // toadflax, ready to pick
		availability.setAvailable(patch(ARDOUGNE_HERB), true);
		standingIn(patch(ARDOUGNE_HERB).getRegion().getRegionId());

		planner.start(EnumSet.of(PatchImplementation.HERB));

		assertTrue(planner.isActive());
		assertFalse("there is work underfoot; the bank can wait", planner.isAtBankLeg());
	}

	/**
	 * No path is drawn to the ground you are standing on.
	 *
	 * <p>Handing the router the stop you are already at made it plot a route to your own feet
	 * for a moment before working out it had arrived — the flicker seen when a run starts on
	 * top of some work.
	 */
	@Test
	public void theStopYouAreStandingInIsNotRoutedTo()
	{
		record(ARDOUGNE_HERB, 43);
		availability.setAvailable(patch(ARDOUGNE_HERB), true);
		standingIn(patch(ARDOUGNE_HERB).getRegion().getRegionId());

		planner.start(EnumSet.of(PatchImplementation.HERB));

		assertTrue("nowhere to navigate to, so no path", lastTargets().isEmpty());
		assertTrue("but the run is very much still running", planner.isActive());
	}

	/**
	 * Nothing is routed at all while there is work where you stand.
	 *
	 * <p>This deliberately replaced the opposite behaviour. Routing to the next stop while
	 * standing on ripe crops is a second instruction competing with the one guided mode is
	 * giving — and in practice Shortest Path drew a teleport-and-bank route across the screen
	 * the whole time the player was working the patch in front of them.
	 *
	 * <p>Finishing a location before travelling is also the shape the wiki's own farm-run guide
	 * describes, so the route has nothing useful to say until this stop is done.
	 */
	@Test
	public void nothingIsRoutedWhileThereIsWorkWhereYouStand()
	{
		record(ARDOUGNE_HERB, 43);
		record(CATHERBY_HERB, 43);
		availability.setAvailable(patch(ARDOUGNE_HERB), true);
		availability.setAvailable(patch(CATHERBY_HERB), true);
		standingIn(patch(ARDOUGNE_HERB).getRegion().getRegionId());

		planner.start(EnumSet.of(PatchImplementation.HERB));

		assertTrue("Catherby can wait until Ardougne is finished", lastTargets().isEmpty());
	}

	/** Once the stop underfoot is done, the rest of the run is routed again. */
	@Test
	public void routingResumesOnceTheStopUnderfootIsFinished()
	{
		record(ARDOUGNE_HERB, 43);
		record(CATHERBY_HERB, 43);
		availability.setAvailable(patch(ARDOUGNE_HERB), true);
		availability.setAvailable(patch(CATHERBY_HERB), true);

		// Seeds in hand, or finishing the stop would correctly trigger the supply trip that was
		// deferred at the start and route to every bank instead.
		selection.toggle(com.dooglemaps.data.Seed.TOADFLAX);
		stockInventory(com.dooglemaps.data.Seed.TOADFLAX, 5);

		standingIn(patch(ARDOUGNE_HERB).getRegion().getRegionId());

		planner.start(EnumSet.of(PatchImplementation.HERB));
		planner.markServiced(patch(ARDOUGNE_HERB));

		assertEquals("Catherby is the only thing left, so route to it",
			1, lastTargets().size());
	}

	/** The deferred trip is not forgotten — it happens once the work here is done. */
	@Test
	public void theDeferredSupplyTripHappensAfterTheStopIsFinished()
	{
		record(ARDOUGNE_HERB, 43);
		availability.setAvailable(patch(ARDOUGNE_HERB), true);
		standingIn(patch(ARDOUGNE_HERB).getRegion().getRegionId());

		planner.start(EnumSet.of(PatchImplementation.HERB));
		assertFalse(planner.isAtBankLeg());

		planner.markServiced(patch(ARDOUGNE_HERB));

		assertTrue("the supplies were owed, not cancelled", planner.isAtBankLeg());
	}

	/**
	 * A seed picked for a patch type the run does not cover cannot send it to a bank.
	 *
	 * <p>Every selected seed used to be considered whatever the run was, so one bush seed
	 * picked at some point in the past made every herb run start at a bank.
	 */
	@Test
	public void aSeedForAnotherPatchTypeDoesNotForceASupplyTrip()
	{
		record(ARDOUGNE_HERB, 43);
		availability.setAvailable(patch(ARDOUGNE_HERB), true);

		// Picked, owned nowhere, and irrelevant: this run has no bush in it.
		selection.toggle(com.dooglemaps.data.Seed.REDBERRIES);
		// And the herb the run does cover is already in the pack.
		selection.toggle(com.dooglemaps.data.Seed.TOADFLAX);
		stockInventory(com.dooglemaps.data.Seed.TOADFLAX, 5);

		planner.start(EnumSet.of(PatchImplementation.HERB));

		assertTrue("the bush seed is nothing to do with this run",
			planner.getSupplySources().isEmpty());
	}

	// ------------------------------------------------------- previewing a run

	/**
	 * The panel promises a set of destinations before the run starts, so the promise has to be
	 * the run. Comparing against {@code start} rather than against a hand-written expectation
	 * is the point: a filter that changed on one side only would pass a fixed list.
	 */
	@Test
	public void previewingARunGivesExactlyTheStopsItWouldHave()
	{
		record(CATHERBY_HERB, 43);
		record(FALADOR_HERB, 3);
		record(ARDOUGNE_HERB, 171);
		record("12083.4771", 3);
		for (String key : new String[]{CATHERBY_HERB, FALADOR_HERB, ARDOUGNE_HERB, "12083.4771"})
		{
			availability.setAvailable(patch(key), true);
		}

		Set<PatchImplementation> types =
			EnumSet.of(PatchImplementation.HERB, PatchImplementation.ALLOTMENT);

		List<String> previewed = new ArrayList<>();
		for (RunStop stop : planner.previewStops(types))
		{
			previewed.add(stop.getName() + "/" + stop.getPatches().size());
		}

		List<String> actual = new ArrayList<>();
		for (RunStop stop : planner.start(types))
		{
			actual.add(stop.getName() + "/" + stop.getPatches().size());
		}

		assertEquals(actual, previewed);
	}

	@Test
	public void previewingARunDoesNotStartOne()
	{
		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);

		assertFalse("a preview should be free of side effects",
			planner.previewStops(EnumSet.of(PatchImplementation.HERB)).isEmpty());

		assertFalse("looking at a run must not begin one", planner.isActive());
		assertTrue("and must not route anywhere", lastTargets().isEmpty());
	}

	// ------------------------------------------------------------ what is routed

	@Test
	public void aRunStartsByRoutingToABank()
	{
		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);

		planner.start(EnumSet.of(PatchImplementation.HERB));

		assertTrue(planner.isAtBankLeg());
		assertTrue("the opening leg should target banks, not patches", lastTargets().size() > 1);
	}

	@Test
	public void seedsInTheVaultSendYouToTheFarmingGuildNotTheNearestBank()
	{
		// There is one seed vault and it is in the Farming Guild, so routing to "a bank" for
		// vault seeds is precisely the wrong side of the map.
		stockVault(com.dooglemaps.data.Seed.RANARR, 40);
		selection.toggle(com.dooglemaps.data.Seed.RANARR);

		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB));

		assertEquals(java.util.Collections.singleton(SeedSource.SEED_VAULT),
			planner.getSupplySources());
		assertEquals("one target: the vault", 1, lastTargets().size());
		assertEquals(banks.getSeedVault(), lastTargets().iterator().next());
	}

	@Test
	public void bankedSeedsStillRouteToAnyBank()
	{
		stockBank(com.dooglemaps.data.Seed.RANARR, 40);
		selection.toggle(com.dooglemaps.data.Seed.RANARR);

		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB));

		assertEquals(java.util.Collections.singleton(SeedSource.BANK), planner.getSupplySources());
		assertTrue("every usable bank is a candidate", lastTargets().size() > 1);
	}

	@Test
	public void seedsAlreadyCarriedNeedNoSupplyTrip()
	{
		stockInventory(com.dooglemaps.data.Seed.RANARR, 10);
		selection.toggle(com.dooglemaps.data.Seed.RANARR);

		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB));

		assertTrue(planner.getSupplySources().isEmpty());
		assertFalse("nothing to collect, so go straight to the patches", planner.isAtBankLeg());
		assertEquals("one outstanding stop", 1, lastTargets().size());
	}

	@Test
	public void reachingABankSwitchesRoutingToThePatches()
	{
		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB));

		planner.leaveBank();

		assertFalse(planner.isAtBankLeg());
		assertEquals("one outstanding stop, one target", 1, lastTargets().size());
	}

	@Test
	public void servicingAPatchDropsItFromTheTargets()
	{
		record(CATHERBY_HERB, 3);
		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(CATHERBY_HERB), true);
		availability.setAvailable(patch(FALADOR_HERB), true);

		planner.start(EnumSet.of(PatchImplementation.HERB));
		planner.leaveBank();
		assertEquals(2, lastTargets().size());

		planner.markServiced(patch(FALADOR_HERB));

		assertEquals("the serviced stop should stop being a target", 1, lastTargets().size());
		assertEquals(1, planner.getRemaining().size());
	}

	@Test
	public void finishingTheLastStopEndsTheRun()
	{
		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB));
		planner.leaveBank();

		planner.markServiced(patch(FALADOR_HERB));

		assertFalse(planner.isActive());
		assertTrue("nothing left to route to", planner.getRemaining().isEmpty());
		assertEquals("the path should be cleared", Collections.emptySet(), lastTargets());
	}
}
