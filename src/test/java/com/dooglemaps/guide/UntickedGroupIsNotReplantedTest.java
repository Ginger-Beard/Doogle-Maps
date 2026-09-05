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
import com.dooglemaps.state.ContractState;
import com.dooglemaps.state.PatchStateStore;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A patch whose group has no full-run line ticked is never offered destructive work.
 *
 * <h2>The poison ivy report</h2>
 *
 * Handing in a bush contract re-groups the guild's bush patch from the contract group back into
 * the plain bush group, mid-run, while the patch is still on the stop the run planned around the
 * old contract. If that plain group has no line ticked, the old rule — "full unless harvest-only
 * is ticked" — read it as a full run, and the freshly-harvested poison ivy got a "dig up the
 * picked-clean poison ivy" step the player never asked for, with seeds from their box lined up to
 * replace it. Digging up a regrowing crop is only ever wanted where the player has said "replant
 * these": the group's full line, or the contract's own ticked line.
 */
public class UntickedGroupIsNotReplantedTest
{
	private GuideTracker tracker;
	private PlantingGroups groups;
	private GrowthTimer growthTimer;
	private com.dooglemaps.state.RunTypeStore runTypes;
	private com.dooglemaps.state.SeedSelectionStore selection;
	private com.dooglemaps.state.SeedInventoryStore seeds;
	private com.dooglemaps.state.CompostSelectionStore compost;
	private FarmPatch bush;
	private final PlantingGroup bushGroup = PlantingGroup.of(PatchImplementation.BUSH);

	@Before
	public void setUp() throws Exception
	{
		groups = Mockito.mock(PlantingGroups.class);
		growthTimer = Mockito.mock(GrowthTimer.class);
		runTypes = Mockito.mock(com.dooglemaps.state.RunTypeStore.class);
		selection = Mockito.mock(com.dooglemaps.state.SeedSelectionStore.class);
		seeds = Mockito.mock(com.dooglemaps.state.SeedInventoryStore.class);
		compost = Mockito.mock(com.dooglemaps.state.CompostSelectionStore.class);

		DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class);
		when(config.guideFarmingContracts()).thenReturn(true);

		// Defaults that keep the allocation walk off null collections; tests override the
		// specific answers they are about.
		when(selection.getSelectedFor(any(PlantingGroup.class)))
			.thenReturn(new LinkedHashSet<>());
		when(groups.patchesIn(any())).thenReturn(Collections.emptyList());

		tracker = trackerWith(config);

		bush = guildPatch(PatchImplementation.BUSH);
		assertNotNull("the Farming Guild has no bush patch in the data", bush);

		// The world the report described: the contract handed in, the bush back in its plain
		// group, picked clean — HARVESTABLE with a stock of zero — and a poison ivy seed
		// sitting in the seed box, at hand for the replant nobody asked for.
		when(groups.groupFor(bush)).thenReturn(bushGroup);
		when(groups.patchesIn(bushGroup)).thenReturn(Collections.singletonList(bush));
		project(bush, Produce.POISON_IVY, CropState.HARVESTABLE, 0);

		when(seeds.getFarmingLevel()).thenReturn(99);
		when(seeds.getOwnedPlantable(Seed.POISON_IVY)).thenReturn(94);
		when(seeds.getPlantable(Seed.POISON_IVY, SeedSource.SEED_BOX)).thenReturn(94);
		when(seeds.getPlantableOnHand(Seed.POISON_IVY)).thenReturn(94);
		when(selection.getSelectedFor(bushGroup))
			.thenReturn(new LinkedHashSet<>(Collections.singletonList(Seed.POISON_IVY)));
	}

	/** No line ticked for bushes: the picked-clean crop is left alone, seeds at hand or not. */
	@Test
	public void anUntickedGroupsPickedCleanCropIsNotDugUp() throws Exception
	{
		when(runTypes.isSelected(RunOption.full(bushGroup))).thenReturn(false);

		List<GuideStep> steps = stepsFor(bush);

		assertFalse("nothing ticked asked for a replant: " + steps,
			has(steps, GuideAction.CLEAR));
	}

	/** The same tick read as harvest-only behaves the same way — this held before the fix. */
	@Test
	public void aHarvestOnlyGroupsPickedCleanCropIsNotDugUp() throws Exception
	{
		when(runTypes.isSelected(RunOption.full(bushGroup))).thenReturn(false);
		when(runTypes.isHarvestOnly(bushGroup)).thenReturn(true);

		assertFalse(has(stepsFor(bush), GuideAction.CLEAR));
	}

	/** With the full line ticked the replant is asked for, so the dig-up stays. */
	@Test
	public void aFullRunStillClearsThePickedCleanCropForItsReplant() throws Exception
	{
		when(runTypes.isSelected(RunOption.full(bushGroup))).thenReturn(true);

		List<GuideStep> steps = stepsFor(bush);

		assertTrue("the full run replants, and the replant starts with the spade: " + steps,
			has(steps, GuideAction.CLEAR));
	}

	/**
	 * A patch this run will not replant does not add to the compost withdrawal either.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"at allotment/herb/flower patch combos our getting compost logic is really wacky,
	 * sometimes i get 2, sometimes 1, sometimes 4"</i>. The compost <b>tier</b> is a property of
	 * the group and survives the line being unticked or set to harvest-only, so
	 * {@code countWanting} counted every ripe patch at the stop whose group happened to name the
	 * same tier — including the ones this run will only pick. The withdrawal was sized from one
	 * question and the steps it supplies from another, so a bucket or two came back unused, and
	 * how many depended entirely on which lines were ticked and what was ripe.
	 *
	 * <p>Reached by reflection because the count is deliberately private and cached per tick; the
	 * whole point of the fix is that it now asks the same {@code RunOption.full} question
	 * {@code stepsFor} does, so the two cannot drift apart again.
	 */
	@Test
	public void aGroupThatWillNotBeReplantedIsNotCountedForCompost() throws Exception
	{
		when(compost.get(bushGroup)).thenReturn(com.dooglemaps.data.CompostTier.ULTRACOMPOST);

		com.dooglemaps.route.RunStop stop = Mockito.mock(com.dooglemaps.route.RunStop.class);
		when(stop.getPatches()).thenReturn(Collections.singletonList(bush));

		when(runTypes.isSelected(RunOption.full(bushGroup))).thenReturn(true);
		assertEquals("a patch being replanted wants a bucket", 1,
			countWanting(stop, com.dooglemaps.data.CompostTier.ULTRACOMPOST));

		when(runTypes.isSelected(RunOption.full(bushGroup))).thenReturn(false);
		when(runTypes.isHarvestOnly(bushGroup)).thenReturn(true);
		assertEquals("one being picked and left does not", 0,
			countWanting(stop, com.dooglemaps.data.CompostTier.ULTRACOMPOST));
	}

	private int countWanting(com.dooglemaps.route.RunStop stop,
		com.dooglemaps.data.CompostTier tier) throws Exception
	{
		Method method = GuideTracker.class.getDeclaredMethod("countWanting",
			com.dooglemaps.route.RunStop.class, com.dooglemaps.data.CompostTier.class);
		method.setAccessible(true);
		return (Integer) method.invoke(tracker, stop, tier);
	}

	// ------------------------------------------------------------------- helpers

	private List<GuideStep> stepsFor(FarmPatch patch) throws Exception
	{
		// The map is what the deposit step keeps back for the patches at this stop; empty here,
		// because these tests are about whether a picked-clean crop is dug up and no bin is
		// involved. See GuideTracker.compostThisStopWillUse.
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
			else if (type == com.dooglemaps.state.CompostSelectionStore.class)
			{
				args[i] = compost;
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
