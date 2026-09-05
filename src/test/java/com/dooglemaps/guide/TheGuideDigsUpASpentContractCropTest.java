package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.RunOption;
import com.dooglemaps.data.Seed;
import com.dooglemaps.route.RunPlanner;
import com.dooglemaps.state.ContractState;
import com.dooglemaps.state.PlantingGroups;
import com.dooglemaps.state.SeedSource;
import com.dooglemaps.timer.Confidence;
import com.dooglemaps.timer.GrowthTimer;
import com.dooglemaps.timer.PatchProjection;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A contract crop that can never satisfy its contract is work, not a finished job.
 *
 * <h2>Jane's stop stopped being held open for the one contract that needed it most</h2>
 *
 * {@code newContractStillToPlant} ended the contract claim on "the contract crop is alive in the
 * contract's patch", which reads as <i>sown and growing, nothing more to do here</i>. A bush
 * health-checked before the contract was taken looks exactly like that from there and is the
 * opposite state: nothing has been sown for this contract at all, and the trip owes a dig, a plant
 * and a wait. With the claim false the guild stop was no longer held open, and on the
 * contract-only run this was reported from there was nothing else at the guild to hold it.
 *
 * <p>The judgment itself — is this standing crop spent — belongs to {@code RunPlanner}, because the
 * same question decides whether the run allocates the seed; it is stubbed here and has its own
 * tests in {@code route.ADudContractStillWantsItsSeedTest}. What is asserted here is that the guide
 * asks it, and that the dig-and-replant the run already knows how to describe is offered.
 */
public class TheGuideDigsUpASpentContractCropTest
{
	private GuideTracker tracker;
	private PlantingGroups groups;
	private GrowthTimer growthTimer;
	private RunPlanner planner;
	private ContractState contracts;
	private com.dooglemaps.state.RunTypeStore runTypes;
	private com.dooglemaps.state.SeedSelectionStore selection;
	private com.dooglemaps.state.SeedInventoryStore seeds;
	private FarmPatch bush;

	private final PlantingGroup contractGroup = PlantingGroup.contract(PatchImplementation.BUSH);

	@Before
	public void setUp() throws Exception
	{
		groups = Mockito.mock(PlantingGroups.class);
		growthTimer = Mockito.mock(GrowthTimer.class);
		planner = Mockito.mock(RunPlanner.class);
		contracts = Mockito.mock(ContractState.class);
		runTypes = Mockito.mock(com.dooglemaps.state.RunTypeStore.class);
		selection = Mockito.mock(com.dooglemaps.state.SeedSelectionStore.class);
		seeds = Mockito.mock(com.dooglemaps.state.SeedInventoryStore.class);

		DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class);
		when(config.guideFarmingContracts()).thenReturn(true);

		when(selection.getSelectedFor(any(PlantingGroup.class)))
			.thenReturn(new LinkedHashSet<>());
		when(groups.patchesIn(any())).thenReturn(Collections.emptyList());

		tracker = trackerWith(config);

		bush = guildPatch(PatchImplementation.BUSH);
		assertNotNull("the Farming Guild has no bush patch in the data", bush);

		// A poison ivy contract assigned, its patch holding a poison ivy bush that was checked
		// before Jane named it: HARVESTABLE, picked clean, and spent as far as the contract goes.
		when(contracts.getContract()).thenReturn(Produce.POISON_IVY);
		when(contracts.hasContract()).thenReturn(true);
		when(contracts.getContractSeed()).thenReturn(Seed.POISON_IVY);
		when(contracts.getActiveContractType()).thenReturn(PatchImplementation.BUSH);
		when(groups.groupFor(bush)).thenReturn(contractGroup);
		when(groups.patchesIn(contractGroup)).thenReturn(Collections.singletonList(bush));
		when(planner.contractStandingIsSpent(bush)).thenReturn(true);
		project(bush, Produce.POISON_IVY, CropState.HARVESTABLE, 0);

		// The player has ticked Farming contract, and holds seeds for the replant.
		when(runTypes.isSelected(RunOption.full(contractGroup))).thenReturn(true);
		when(seeds.getFarmingLevel()).thenReturn(99);
		when(seeds.getOwnedPlantable(Seed.POISON_IVY)).thenReturn(7);
		when(seeds.getPlantable(Seed.POISON_IVY, SeedSource.SEED_BOX)).thenReturn(7);
		when(seeds.getPlantableOnHand(Seed.POISON_IVY)).thenReturn(7);
		when(selection.getSelectedFor(contractGroup))
			.thenReturn(new LinkedHashSet<>(Collections.singletonList(Seed.POISON_IVY)));
	}

	/** The spade first: the ground has to be cleared before the contract's seed can go in. */
	@Test
	public void theSpentCropIsDugUp() throws Exception
	{
		List<GuideStep> steps = stepsFor(bush);

		assertTrue("the replant starts with the dig: " + steps, has(steps, GuideAction.CLEAR));
	}

	/** And the guild stays open, because the dig and the planting are both still owed. */
	@Test
	public void janeStillHasBusinessWithTheRun() throws Exception
	{
		assertTrue("a dud contract is a contract still to plant", contractBusinessOutstanding());
	}

	/** The same fixture with the crop genuinely sown and growing closes the claim, as before. */
	@Test
	public void aContractActuallySownEndsTheClaim() throws Exception
	{
		when(planner.contractStandingIsSpent(bush)).thenReturn(false);
		project(bush, Produce.POISON_IVY, CropState.GROWING, 0);

		assertFalse("the chain finishes when the seed is in the ground",
			contractBusinessOutstanding());
	}

	// ------------------------------------------------------------------- helpers

	private boolean contractBusinessOutstanding() throws Exception
	{
		Method method = GuideTracker.class.getDeclaredMethod("contractBusinessOutstanding");
		method.setAccessible(true);
		try
		{
			return (Boolean) method.invoke(tracker);
		}
		catch (java.lang.reflect.InvocationTargetException e)
		{
			throw new AssertionError(e.getCause());
		}
	}

	private List<GuideStep> stepsFor(FarmPatch patch) throws Exception
	{
		Method method = GuideTracker.class.getDeclaredMethod(
			"stepsFor", FarmPatch.class, int.class, java.util.Map.class);
		method.setAccessible(true);
		try
		{
			@SuppressWarnings("unchecked")
			List<GuideStep> steps = (List<GuideStep>) method.invoke(tracker, patch, 0,
				java.util.Collections.emptyMap());
			return steps;
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

	private void project(FarmPatch patch, Produce produce, CropState state, int lives)
		throws Exception
	{
		long now = java.time.Instant.now().getEpochSecond();

		Constructor<PatchProjection> ctor = PatchProjection.class.getDeclaredConstructor(
			FarmPatch.class, Produce.class, CropState.class, int.class, int.class,
			long.class, int.class, long.class, Confidence.class, boolean.class, long.class,
			boolean.class, int.class);
		ctor.setAccessible(true);

		PatchProjection projection = ctor.newInstance(patch, produce, state,
			produce.getStages() - 1, produce.getStages(), now - 60, lives,
			0L, Confidence.CERTAIN, false, now, false, -1);

		when(growthTimer.project(Mockito.eq(patch), any())).thenReturn(projection);
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

	/** A tracker whose collaborators are this test's mocks, everything else auto-mocked. */
	private GuideTracker trackerWith(DoogleMapsConfig config) throws Exception
	{
		Constructor<?> constructor = GuideTracker.class.getDeclaredConstructors()[0];
		constructor.setAccessible(true);

		Class<?>[] types = constructor.getParameterTypes();
		Object[] args = new Object[types.length];
		for (int i = 0; i < types.length; i++)
		{
			Class<?> type = types[i];
			if (type == PlantingGroups.class)
			{
				args[i] = groups;
			}
			else if (type == GrowthTimer.class)
			{
				args[i] = growthTimer;
			}
			else if (type == RunPlanner.class)
			{
				args[i] = planner;
			}
			else if (type == ContractState.class)
			{
				args[i] = contracts;
			}
			else if (type == com.dooglemaps.state.RunTypeStore.class)
			{
				args[i] = runTypes;
			}
			else if (type == com.dooglemaps.state.SeedSelectionStore.class)
			{
				args[i] = selection;
			}
			else if (type == com.dooglemaps.state.SeedInventoryStore.class)
			{
				args[i] = seeds;
			}
			else if (type == DoogleMapsConfig.class)
			{
				args[i] = config;
			}
			else
			{
				args[i] = Mockito.mock(type);
			}
		}
		return (GuideTracker) constructor.newInstance(args);
	}
}
