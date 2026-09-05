package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.RunOption;
import com.dooglemaps.data.Seed;
import com.dooglemaps.route.BankLocationStore;
import com.dooglemaps.route.RunStop;
import com.dooglemaps.state.ContractState;
import com.dooglemaps.state.PlantingGroups;
import com.dooglemaps.state.SeedSource;
import com.dooglemaps.timer.Confidence;
import com.dooglemaps.timer.GrowthTimer;
import com.dooglemaps.timer.PatchProjection;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The contract Jane hands out is planted on the trip it was taken on.
 *
 * <h2>The loop the whole design promises</h2>
 *
 * <i>"nothing here is stored, so the moment the new contract lands in config the patch it wants
 * moves into the contract group, the patch is pulled to the front, and GuidePlan produces the plant
 * step for it — all on the next tick, while you are still standing in the guild."</i> That is what
 * {@code appendContractErrands} says it does, and every part of it was true except that the run had
 * already ended: the guild stop finished on the harvest that started the chain, so there was no
 * stop to be standing in and no tick that would look.
 *
 * <p>These are the two ends of that loop with the run held open. A fresh assignment landing while
 * the player stands at the guild is a fetch and a planting on this trip, and once the seed is in
 * the ground nothing is outstanding and the stop is free to finish — a claim that could not end
 * would be a run that could not.
 */
public class TheNewContractIsPlantedOnTheSameTripTest
{
	private static final String TIME_TRACKING = "timetracking";

	/** In the Farming Guild's region, beside Jane: the guild's bank chest and seed vault. */
	private static final WorldPoint GUILD_STORAGE = new WorldPoint(1253, 3741, 0);

	/** Config keyed as {@code group + "." + key}, so the two groups cannot collide. */
	private final Map<String, Object> stored = new HashMap<>();

	private GuideTracker tracker;
	private ContractState contracts;
	private PlantingGroups groups;
	private GrowthTimer growthTimer;
	private com.dooglemaps.state.RunTypeStore runTypes;
	private com.dooglemaps.state.SeedInventoryStore seeds;
	private com.dooglemaps.state.SeedSelectionStore selection;
	private BankLocationStore bankLocations;
	private FarmPatch herb;

	private final PlantingGroup contractGroup = PlantingGroup.contract(PatchImplementation.HERB);

	@Before
	public void setUp() throws Exception
	{
		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer(i -> stored.get(i.getArgument(0) + "." + i.getArgument(1)));
		Mockito.doAnswer(i -> stored.put(i.getArgument(0) + "." + i.getArgument(1),
				i.getArgument(2)))
			.when(configManager)
			.setRSProfileConfiguration(anyString(), anyString(), Mockito.<Object>any());
		Mockito.doAnswer(i -> stored.remove(i.getArgument(0) + "." + i.getArgument(1)))
			.when(configManager)
			.unsetRSProfileConfiguration(anyString(), anyString());

		contracts = construct(ContractState.class, configManager);
		groups = Mockito.mock(PlantingGroups.class);
		growthTimer = Mockito.mock(GrowthTimer.class);
		runTypes = Mockito.mock(com.dooglemaps.state.RunTypeStore.class);
		seeds = Mockito.mock(com.dooglemaps.state.SeedInventoryStore.class);
		selection = Mockito.mock(com.dooglemaps.state.SeedSelectionStore.class);
		bankLocations = Mockito.mock(BankLocationStore.class);

		DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class);
		when(config.guideFarmingContracts()).thenReturn(true);
		when(config.contractSeedAdvice())
			.thenReturn(DoogleMapsConfig.ContractSeedAdvice.ASK_FOR_EASIER);

		tracker = trackerWith(config);

		herb = guildPatch(PatchImplementation.HERB);
		assertNotNull("the Farming Guild has no herb patch in the data", herb);
		when(groups.patchesIn(any())).thenReturn(Collections.emptyList());
		when(groups.patchesIn(contractGroup)).thenReturn(Collections.singletonList(herb));
		when(groups.groupFor(herb)).thenReturn(contractGroup);
		when(runTypes.isSelected(RunOption.full(contractGroup))).thenReturn(true);
		when(selection.getSelectedFor(contractGroup))
			.thenReturn(new LinkedHashSet<>(Collections.singletonList(Seed.SNAPDRAGON)));
		when(seeds.getFarmingLevel()).thenReturn(99);

		// Jane has just taken the last contract back and named the next one, with the player
		// still standing in front of her. Its seed is in the vault a few steps away.
		contracts.recordHandedIn();
		assignInTimeTracking(Produce.SNAPDRAGON);
		when(seeds.getOwned(Seed.SNAPDRAGON)).thenReturn(1);
		when(seeds.getOwnedPlantable(Seed.SNAPDRAGON)).thenReturn(1);
		when(seeds.getPlantable(Seed.SNAPDRAGON, SeedSource.SEED_VAULT)).thenReturn(1);
		when(bankLocations.getUsableBanks()).thenReturn(Collections.emptySet());
		when(bankLocations.getSeedVault()).thenReturn(GUILD_STORAGE);
		empty(herb);
	}

	/** The near end of the loop: the seed is fetched here rather than next week. */
	@Test
	public void theSeedIsFetchedOnTheSameTrip() throws Exception
	{
		List<GuideStep> steps = errands();

		assertFalse("a contract taken here is a contract sown here", steps.isEmpty());
		assertEquals(GuideAction.FETCH_SEED, steps.get(0).getAction());
		assertTrue(steps.get(0).getText(), steps.get(0).getText().contains("seed vault"));
		assertTrue("and the run may not end while it is still in the vault",
			tracker.contractBusinessOutstanding());
	}

	/** With it in hand, the patch's own plan asks for it to go in the ground. */
	@Test
	public void theSeedInHandIsPlantedInTheContractPatch() throws Exception
	{
		inHand(Seed.SNAPDRAGON);

		assertTrue("the fetch is done, so it stands down",
			errands().stream().noneMatch(step -> step.getAction() == GuideAction.FETCH_SEED));
		assertTrue("the patch it wants is empty and the seed is on you",
			has(outstandingFor(herb), GuideAction.PLANT));
		assertTrue("still the run's business until it is sown",
			tracker.contractBusinessOutstanding());
	}

	/** And once it is growing there, nothing is outstanding and the run is free to end. */
	@Test
	public void theRunEndsOnceItIsInTheGround() throws Exception
	{
		inHand(Seed.SNAPDRAGON);
		growing(herb, Produce.SNAPDRAGON);

		assertTrue(errands().isEmpty());
		assertFalse("a claim that cannot end is a run that cannot end",
			tracker.contractBusinessOutstanding());
	}

	/**
	 * A seed the player does not own anywhere is not a reason to hold the guild open.
	 *
	 * <p>The stop would never finish and the run would never end — the same freeze the
	 * exemption set exists to prevent, arrived at from the contract's side.
	 */
	@Test
	public void aSeedTheyDoNotHaveDoesNotStrandTheRun() throws Exception
	{
		when(seeds.getOwned(Seed.SNAPDRAGON)).thenReturn(0);
		when(seeds.getOwnedPlantable(Seed.SNAPDRAGON)).thenReturn(0);
		when(seeds.getPlantable(Seed.SNAPDRAGON, SeedSource.SEED_VAULT)).thenReturn(0);

		assertFalse(tracker.contractBusinessOutstanding());
	}

	// ------------------------------------------------------------------- helpers

	private void assignInTimeTracking(Produce produce)
	{
		stored.put(TIME_TRACKING + ".contract", String.valueOf(produce.getItemID()));
	}

	private void inHand(Seed seed)
	{
		when(seeds.getPlantable(seed, SeedSource.INVENTORY)).thenReturn(seed.getSeedsPerPatch());
		when(seeds.getPlantableOnHand(seed)).thenReturn(seed.getSeedsPerPatch());
	}

	private List<GuideStep> errands() throws Exception
	{
		Method method = GuideTracker.class.getDeclaredMethod(
			"appendContractErrands", List.class, RunStop.class);
		method.setAccessible(true);

		List<GuideStep> steps = new ArrayList<>();
		try
		{
			method.invoke(tracker, steps, guildStop());
		}
		catch (java.lang.reflect.InvocationTargetException e)
		{
			throw new AssertionError(e.getCause());
		}
		return steps;
	}

	@SuppressWarnings("unchecked")
	private List<GuideStep> outstandingFor(FarmPatch patch) throws Exception
	{
		Method method = GuideTracker.class.getDeclaredMethod(
			"outstandingFor", FarmPatch.class, RunStop.class);
		method.setAccessible(true);
		try
		{
			return (List<GuideStep>) method.invoke(tracker, patch, guildStop());
		}
		catch (java.lang.reflect.InvocationTargetException e)
		{
			throw new AssertionError(e.getCause());
		}
	}

	private static boolean has(List<GuideStep> steps, GuideAction action)
	{
		return steps.stream().anyMatch(step -> step.getAction() == action);
	}

	private RunStop guildStop()
	{
		RunStop stop = Mockito.mock(RunStop.class);
		when(stop.getName()).thenReturn("Farming Guild");
		when(stop.getRegion()).thenReturn(herb.getRegion());
		when(stop.getPatches()).thenReturn(Collections.singletonList(herb));
		when(stop.getServiced()).thenReturn(new java.util.HashSet<>());
		return stop;
	}

	private void empty(FarmPatch patch) throws Exception
	{
		when(growthTimer.project(Mockito.eq(patch), any()))
			.thenReturn(projection(patch, null, CropState.EMPTY, 0, 0));
	}

	private void growing(FarmPatch patch, Produce produce) throws Exception
	{
		when(growthTimer.project(Mockito.eq(patch), any()))
			.thenReturn(projection(patch, produce, CropState.GROWING, 1, produce.getStages()));
	}

	private static PatchProjection projection(FarmPatch patch, @Nullable Produce produce,
		CropState state, int stage, int stages) throws Exception
	{
		long now = java.time.Instant.now().getEpochSecond();

		Constructor<PatchProjection> ctor = PatchProjection.class.getDeclaredConstructor(
			FarmPatch.class, Produce.class, CropState.class, int.class, int.class,
			long.class, int.class, long.class, Confidence.class, boolean.class, long.class,
			boolean.class, int.class);
		ctor.setAccessible(true);

		return ctor.newInstance(patch, produce, state, stage, stages, now + 3600, 0, 0L,
			Confidence.CERTAIN, false, now, false, -1);
	}

	private static FarmPatch guildPatch(PatchImplementation type)
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(type))
		{
			if (patch.getRegion().getRegionId() == ContractState.FARMING_GUILD_REGION)
			{
				return patch;
			}
		}
		return null;
	}

	/** A tracker whose collaborators are this test's, everything else auto-mocked. */
	private GuideTracker trackerWith(DoogleMapsConfig config) throws Exception
	{
		Constructor<?> constructor = GuideTracker.class.getDeclaredConstructors()[0];
		constructor.setAccessible(true);

		Class<?>[] types = constructor.getParameterTypes();
		Object[] args = new Object[types.length];
		for (int i = 0; i < types.length; i++)
		{
			Class<?> type = types[i];
			if (type == ContractState.class)
			{
				args[i] = contracts;
			}
			else if (type == PlantingGroups.class)
			{
				args[i] = groups;
			}
			else if (type == GrowthTimer.class)
			{
				args[i] = growthTimer;
			}
			else if (type == com.dooglemaps.state.RunTypeStore.class)
			{
				args[i] = runTypes;
			}
			else if (type == com.dooglemaps.state.SeedInventoryStore.class)
			{
				args[i] = seeds;
			}
			else if (type == com.dooglemaps.state.SeedSelectionStore.class)
			{
				args[i] = selection;
			}
			else if (type == BankLocationStore.class)
			{
				args[i] = bankLocations;
			}
			else if (type == DoogleMapsConfig.class)
			{
				args[i] = config;
			}
			else
			{
				args[i] = Mockito.mock(type);
				if (type == com.dooglemaps.state.CompostSelectionStore.class)
				{
					// A null tier is dereferenced for the per-stop count; none is what this
					// test's patch is being treated with.
					when(((com.dooglemaps.state.CompostSelectionStore) args[i])
						.get(any(PlantingGroup.class)))
						.thenReturn(CompostTier.NONE);
				}
			}
		}
		return (GuideTracker) constructor.newInstance(args);
	}
}
