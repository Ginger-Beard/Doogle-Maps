package com.dooglemaps.guide;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.data.Seed;
import com.dooglemaps.route.RunPlanner;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.PlayerLocation;
import com.dooglemaps.timer.GrowthTimer;
import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * A stop the run skipped stays finished after the player walks away from it.
 *
 * <h2>The bug this pins down</h2>
 *
 * The guide's "nothing can be done here" report used to be a statement about the stop being
 * stood in: cleared every tick, populated only for the patches around the player. But the
 * planner asks {@code isComplete} of <i>every</i> stop, every tick — so a stop that finished
 * only because its patch was exempt became unfinished again the instant the player left, and
 * the run routed them straight back to a patch it had already announced it was skipping.
 * Forever, because arriving re-exempted it and leaving resurrected it.
 *
 * <p>The report is a pure function of the patch stores and the allocation — nothing about it
 * needs the player nearby — so it is now computed for every stop, every tick. These tests are
 * the two halves of that contract: exemptions hold at any distance, and a patch that becomes
 * workable again starts blocking completion on the very next tick.
 *
 * <p>The planner's own half — an exempted patch not holding its stop open — is pinned
 * separately by {@code RunPlannerTest.aPatchTheGuideCannotActOnDoesNotHoldTheRunUp}.
 */
public class SkippedStopStaysFinishedTest
{
	/** Two herb patches in two different regions, so the run has two stops. */
	private static final String FALADOR_HERB = "12083.4774";
	private static final String CATHERBY_HERB = "11062.4774";

	/** Varbit 3 is a raked, empty herb patch: actionable until something is planted in it. */
	private static final int RAKED_AND_EMPTY = 3;

	private Map<String, String> stored;
	private PatchStateStore stateStore;
	private AvailabilityProfile availability;
	private RunPlanner planner;
	private GuideTracker tracker;
	private com.dooglemaps.state.SeedSelectionStore selection;
	private com.dooglemaps.state.SeedInventoryStore seeds;
	private com.dooglemaps.state.PlantingGroups groups;

	@Before
	public void setUp() throws Exception
	{
		stored = new HashMap<>();
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

		stateStore = construct(PatchStateStore.class, configManager, gson);
		availability = construct(AvailabilityProfile.class, configManager, gson, stateStore);
		com.dooglemaps.route.PatchLocationStore locations =
			construct(com.dooglemaps.route.PatchLocationStore.class, configManager, gson);
		com.dooglemaps.route.BankLocationStore banks =
			construct(com.dooglemaps.route.BankLocationStore.class, configManager, gson);
		GrowthTimer timer = construct(GrowthTimer.class, configManager);
		com.dooglemaps.route.ShortestPathIntegration router =
			construct(com.dooglemaps.route.ShortestPathIntegration.class,
				Mockito.mock(EventBus.class), clientThread);

		stateStore.load();
		availability.load();
		locations.load();
		banks.load();

		net.runelite.api.Client client = Mockito.mock(net.runelite.api.Client.class);
		PlayerLocation playerLocation = construct(PlayerLocation.class, client);

		// Nothing selected and nothing owned, until a test says otherwise: the stuck-patch case
		// is precisely "empty patch, no seed to put in it".
		selection = Mockito.mock(com.dooglemaps.state.SeedSelectionStore.class);
		seeds = Mockito.mock(com.dooglemaps.state.SeedInventoryStore.class);

		groups = Mockito.mock(com.dooglemaps.state.PlantingGroups.class);
		com.dooglemaps.data.PlantingGroup herbs =
			Mockito.mock(com.dooglemaps.data.PlantingGroup.class);
		when(herbs.getKey()).thenReturn("herb");
		when(groups.groupFor(any())).thenReturn(herbs);

		com.dooglemaps.state.CompostSelectionStore compost =
			Mockito.mock(com.dooglemaps.state.CompostSelectionStore.class);
		when(compost.get(any(com.dooglemaps.data.PlantingGroup.class)))
			.thenReturn(com.dooglemaps.data.CompostTier.NONE);

		com.dooglemaps.bank.RunLoadout loadout =
			Mockito.mock(com.dooglemaps.bank.RunLoadout.class);

		// The herb group is ticked for the full run; harvest-only stays false, so the empty
		// patch is one the run genuinely wants to plant.
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
			(javax.inject.Provider<com.dooglemaps.bank.RunLoadout>) () -> loadout);

		tracker = trackerWith(planner, stateStore, timer, playerLocation, selection, seeds,
			groups, compost, runOptions, client);
	}

	/**
	 * The reported shape: every patch in the run is unworkable, the player is nowhere near any
	 * of them, and the run still finishes — and stays finished on the next tick, which is the
	 * half that used to fail.
	 */
	@Test
	public void aStopSkippedForWantOfASeedStaysFinishedFromAnyDistance()
	{
		startTwoStopRun();

		assertEquals("two empty patches are two stops worth visiting",
			2, planner.getRemaining().size());

		tracker.onGameTick(null);
		assertTrue("no seed anywhere, so nothing is worth waiting on",
			planner.getRemaining().isEmpty());

		// The tick after is the regression: the exemption used to be cleared the moment the
		// player was not standing at the stop, resurrecting it.
		tracker.onGameTick(null);
		assertTrue("and it stays that way on the next tick, wherever the player is",
			planner.getRemaining().isEmpty());
	}

	/** The other half of the contract: a patch that becomes workable blocks again at once. */
	@Test
	public void aPatchThatBecomesWorkableStartsBlockingAgain()
	{
		startTwoStopRun();

		tracker.onGameTick(null);
		assertTrue("unworkable, so skipped", planner.getRemaining().isEmpty());

		// Guam seeds turn up: selected for the group, owned, and plantable at this level.
		when(selection.getSelectedFor(any(com.dooglemaps.data.PlantingGroup.class)))
			.thenReturn(java.util.Collections.singleton(Seed.GUAM));
		when(seeds.getOwnedPlantable(Seed.GUAM)).thenReturn(10);
		when(seeds.getFarmingLevel()).thenReturn(99);
		when(groups.patchesIn(any())).thenReturn(java.util.Arrays.asList(
			patch(FALADOR_HERB), patch(CATHERBY_HERB)));

		tracker.onGameTick(null);
		assertEquals("a seed to plant makes both patches worth the trip again",
			2, planner.getRemaining().size());
	}

	// ------------------------------------------------------------------- helpers

	private void startTwoStopRun()
	{
		record(FALADOR_HERB, RAKED_AND_EMPTY);
		record(CATHERBY_HERB, RAKED_AND_EMPTY);
		availability.setAvailable(patch(FALADOR_HERB), true);
		availability.setAvailable(patch(CATHERBY_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB));
	}

	private void record(String key, int varbitValue)
	{
		FarmPatch p = patch(key);
		ProduceState decoded = p.getImplementation().forVarbitValue(varbitValue);
		assertNotNull("varbit " + varbitValue + " does not decode for " + key, decoded);
		stateStore.recordVarbit(p, varbitValue, decoded);
	}

	private static FarmPatch patch(String key)
	{
		FarmPatch patch = FarmingWorldData.getPatch(key);
		assertNotNull("fixture patch " + key + " no longer exists", patch);
		return patch;
	}

	/** The real collaborators injected by type; everything else a mock. */
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

	@SuppressWarnings("unchecked")
	private static <T> T construct(Class<T> type, Object... args) throws Exception
	{
		Constructor<?> constructor = type.getDeclaredConstructors()[0];
		constructor.setAccessible(true);
		return (T) constructor.newInstance(args);
	}
}
