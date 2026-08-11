package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.RunOption;
import com.dooglemaps.route.RunStop;
import com.dooglemaps.state.ContractState;
import com.dooglemaps.state.PlantingGroups;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The skip list stays out of the contract's business — {@code contractNote} owns that story.
 *
 * <p>Both used to fire at once. A fresh cadantine contract whose seed sat in the bank drew
 * "Skipping farming guild - the cadantine seeds are in your bank" from the skip list and "Your
 * cadantine contract seed is in your bank..." from the note, one above the other on the same
 * panel: the same fact twice, and the skip wording reading as skipping the whole guild because
 * the guild's patches carry no disambiguator of their own. Reported from play.
 */
public class ContractNoteOwnsTheSkipTest
{
	private GuideTracker tracker;
	private PlantingGroups groups;
	private GrowthTimer growthTimer;
	private com.dooglemaps.state.RunTypeStore runTypes;
	private FarmPatch herb;

	@Before
	public void setUp() throws Exception
	{
		groups = Mockito.mock(PlantingGroups.class);
		growthTimer = Mockito.mock(GrowthTimer.class);
		runTypes = Mockito.mock(com.dooglemaps.state.RunTypeStore.class);

		DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class);
		when(config.guideFarmingContracts()).thenReturn(true);
		when(groups.patchesIn(any())).thenReturn(Collections.emptyList());

		tracker = trackerWith(config);

		herb = guildPatch(PatchImplementation.HERB);
		assertNotNull("the Farming Guild has no herb patch in the data", herb);

		// Empty and unplanted: exactly the patch the skip list exists to explain.
		project(herb);
	}

	/** The contract's patch produces no skip line of its own. */
	@Test
	public void theContractPatchIsLeftToTheNote() throws Exception
	{
		PlantingGroup contract = PlantingGroup.contract(PatchImplementation.HERB);
		when(groups.groupFor(herb)).thenReturn(contract);
		when(runTypes.isSelected(RunOption.full(contract))).thenReturn(true);

		assertEquals("the note already says where the seed is",
			Collections.emptyList(), announceSkips(herb));
	}

	/** An ordinary patch in the same state still gets its explanation. */
	@Test
	public void anOrdinaryPatchIsStillExplained() throws Exception
	{
		PlantingGroup plain = PlantingGroup.of(PatchImplementation.HERB);
		when(groups.groupFor(herb)).thenReturn(plain);
		when(runTypes.isSelected(RunOption.full(plain))).thenReturn(true);

		List<String> skipped = announceSkips(herb);
		assertEquals("an empty patch with nothing to plant is the stranding case", 1,
			skipped.size());
		assertTrue(skipped.get(0), skipped.get(0).startsWith("Skipping"));
	}

	/** A group with no full line ticked was never going to be planted, so nothing is said. */
	@Test
	public void anUntickedGroupNeedsNoExplanation() throws Exception
	{
		PlantingGroup plain = PlantingGroup.of(PatchImplementation.HERB);
		when(groups.groupFor(herb)).thenReturn(plain);
		when(runTypes.isSelected(RunOption.full(plain))).thenReturn(false);

		assertEquals(Collections.emptyList(), announceSkips(herb));
	}

	// ------------------------------------------------------------------- helpers

	@SuppressWarnings("unchecked")
	private List<String> announceSkips(FarmPatch patch) throws Exception
	{
		RunStop stop = Mockito.mock(RunStop.class);
		when(stop.getRegion()).thenReturn(patch.getRegion());
		when(stop.getPatches()).thenReturn(Collections.singletonList(patch));
		when(stop.getServiced()).thenReturn(new java.util.HashSet<>());

		Method method = GuideTracker.class.getDeclaredMethod(
			"announceSkips", RunStop.class, List.class);
		method.setAccessible(true);
		try
		{
			method.invoke(tracker, stop, Collections.singletonList(patch));
		}
		catch (java.lang.reflect.InvocationTargetException e)
		{
			throw new AssertionError(e.getCause());
		}

		java.lang.reflect.Field field = GuideTracker.class.getDeclaredField("skipped");
		field.setAccessible(true);
		return (List<String>) field.get(tracker);
	}

	/** An empty patch: no produce, nothing growing. */
	private void project(FarmPatch patch) throws Exception
	{
		long now = java.time.Instant.now().getEpochSecond();

		Constructor<PatchProjection> ctor = PatchProjection.class.getDeclaredConstructor(
			FarmPatch.class, com.dooglemaps.data.Produce.class, CropState.class, int.class,
			int.class, long.class, int.class, long.class, Confidence.class, boolean.class,
			long.class, boolean.class, int.class);
		ctor.setAccessible(true);

		PatchProjection projection = ctor.newInstance(patch, null, CropState.EMPTY, 0, 0,
			0L, 0, 0L, Confidence.CERTAIN, false, now, false, -1);

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
			else if (type == DoogleMapsConfig.class)
			{
				args[i] = config;
			}
			else
			{
				args[i] = Mockito.mock(type);
				if (type == com.dooglemaps.state.SeedSelectionStore.class)
				{
					when(((com.dooglemaps.state.SeedSelectionStore) args[i])
						.getSelectedFor(any(PlantingGroup.class)))
						.thenReturn(new LinkedHashSet<>());
				}
				else if (type == com.dooglemaps.state.CompostSelectionStore.class)
				{
					when(((com.dooglemaps.state.CompostSelectionStore) args[i])
						.get(any(PlantingGroup.class)))
						.thenReturn(CompostTier.NONE);
				}
			}
		}
		return (GuideTracker) constructor.newInstance(args);
	}
}
