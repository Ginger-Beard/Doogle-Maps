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
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * A patch whose varbit has changed once this run is still guided through the rest of its work.
 *
 * <h2>The bug this pins down</h2>
 *
 * {@code RunStop.markServiced} once meant "planted": the capture layer reported a varbit change
 * only when it decoded to a growing crop. When it was widened to report <i>every</i> change — so
 * harvest-only stops could complete — the guide's step builder kept filtering serviced patches
 * out, and the first harvest crossed the patch off with the compost and replant still undone.
 * The step went straight from harvesting to travel; the leprechaun errands were the only voice
 * left; a compost withdrawal came back as "deposit it". Reported from play, at Troll Stronghold
 * and the protected herb patches both.
 *
 * <p>The guide derives per-patch doneness from state — {@code outstandingFor} — and the serviced
 * set is an ordering hint only. This test walks the exact reported shape: empty patch (the
 * moment after a harvest), the change already reported to the planner, and the guide must still
 * ask for the planting.
 */
public class HarvestedPatchStillGuidedTest
{
	private static final String FALADOR_HERB = "12083.4774";

	/** Varbit 3 is a raked, empty herb patch: what a herb patch looks like just after digging. */
	private static final int RAKED_AND_EMPTY = 3;

	/** A tile inside Falador's region (12083), so the guide is standing at the stop. */
	private static final WorldPoint AT_FALADOR = new WorldPoint(3010, 3266, 0);

	private Map<String, String> stored;
	private PatchStateStore stateStore;
	private AvailabilityProfile availability;
	private RunPlanner planner;
	private GuideTracker tracker;
	private PlayerLocation playerLocation;

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
		net.runelite.api.Player player = Mockito.mock(net.runelite.api.Player.class);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getWorldLocation()).thenReturn(AT_FALADOR);
		playerLocation = construct(PlayerLocation.class, client);

		// A guam seed is selected, owned and plantable, so the empty patch has work behind it.
		com.dooglemaps.state.SeedSelectionStore selection =
			Mockito.mock(com.dooglemaps.state.SeedSelectionStore.class);
		com.dooglemaps.state.SeedInventoryStore seeds =
			Mockito.mock(com.dooglemaps.state.SeedInventoryStore.class);
		when(selection.getSelectedFor(any(com.dooglemaps.data.PlantingGroup.class)))
			.thenReturn(java.util.Collections.singleton(Seed.GUAM));
		when(seeds.getOwnedPlantable(Seed.GUAM)).thenReturn(10);
		// In the pack, not merely owned: the guide only offers a plant step for seeds that
		// are actually on the trip.
		when(seeds.getPlantable(Seed.GUAM, com.dooglemaps.state.SeedSource.INVENTORY))
			.thenReturn(10);
		when(seeds.getFarmingLevel()).thenReturn(99);

		com.dooglemaps.state.PlantingGroups groups =
			Mockito.mock(com.dooglemaps.state.PlantingGroups.class);
		com.dooglemaps.data.PlantingGroup herbs =
			Mockito.mock(com.dooglemaps.data.PlantingGroup.class);
		when(herbs.getKey()).thenReturn("herb");
		when(groups.groupFor(any())).thenReturn(herbs);
		when(groups.patchesIn(any()))
			.thenReturn(java.util.Collections.singletonList(patch(FALADOR_HERB)));

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
			Mockito.mock(com.dooglemaps.DoogleMapsConfig.class));

		tracker = trackerWith(planner, stateStore, timer, playerLocation, selection, seeds,
			groups, compost, runOptions, client);
	}

	@Test
	public void aPatchReportedChangedStillGetsItsPlantingStep()
	{
		record(FALADOR_HERB, RAKED_AND_EMPTY);
		availability.setAvailable(patch(FALADOR_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB));
		playerLocation.onGameTick(null);

		// The harvest's varbit change, as the capture layer reports it: the patch is marked
		// serviced on the stop. It is a hint, not a completion.
		planner.onPatchChanged(patch(FALADOR_HERB));

		tracker.onGameTick(null);

		List<GuideStep> steps = tracker.stepsHere();
		assertFalse("an empty patch with a seed to plant is still work, "
			+ "whatever the serviced hint says", steps.isEmpty());
		assertTrue("and the work named is at the patch the harvest just emptied",
			steps.stream().anyMatch(step ->
				FALADOR_HERB.equals(step.getPatch().getKey())));
	}

	// ------------------------------------------------------------------- helpers

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
}
