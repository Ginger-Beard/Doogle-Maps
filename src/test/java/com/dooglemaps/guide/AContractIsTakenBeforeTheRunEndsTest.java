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
import com.dooglemaps.route.RunStop;
import com.dooglemaps.state.ContractState;
import com.dooglemaps.state.PlantingGroups;
import com.dooglemaps.timer.Confidence;
import com.dooglemaps.timer.GrowthTimer;
import com.dooglemaps.timer.PatchProjection;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The next contract is taken before the run is allowed to end.
 *
 * <h2>The step that had never once fired</h2>
 *
 * TAKE_CONTRACT is offered only while nothing is assigned, and "assigned" was read straight off
 * Time Tracking's key. That key is cleared by the <i>completion message</i> — and a contract that
 * ripens while you are logged out never sends one, so the key goes on naming the crop through the
 * harvest, through the hand-in, and for as long afterwards as it takes Jane to name another. Which
 * she will not do unasked. The guide therefore believed a cadantine contract was assigned to a
 * player who had just given Jane the cadantine, said nothing, and let the run end; the player
 * asked for the next contract themselves, unguided. No session log on disk contains the step.
 *
 * <p>So a hand-in writes down what it settled, and that outranks the stale key for exactly as long
 * as the key names the same crop. The real {@code ContractState} is used here rather than a mock,
 * because the whole bug lives in the disagreement between two config keys and a mock would simply
 * be told the answer.
 */
public class AContractIsTakenBeforeTheRunEndsTest
{
	private static final String TIME_TRACKING = "timetracking";

	/** Config keyed as {@code group + "." + key}, so the two groups cannot collide. */
	private final Map<String, Object> stored = new HashMap<>();

	private GuideTracker tracker;
	private ContractState contracts;
	private PlantingGroups groups;
	private GrowthTimer growthTimer;
	private com.dooglemaps.state.RunTypeStore runTypes;
	private com.dooglemaps.state.SeedInventoryStore seeds;
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
		// The one line the player ticked: Farming contract, for herbs.
		when(runTypes.isSelected(RunOption.full(contractGroup))).thenReturn(true);

		// A cadantine contract that ripened while logged out, so Time Tracking's key was never
		// cleared, and has now been harvested: the patch is bare and the reward is owed.
		assignInTimeTracking(Produce.CADANTINE);
		contracts.recordCompleted();
		assignInTimeTracking(Produce.CADANTINE);
		empty(herb);
	}

	/** The state the run ended in: bare patch, reward owed, and Jane still to be visited. */
	@Test
	public void aHandInThatIsOwedKeepsJanesBusinessOpen() throws Exception
	{
		assertEquals(GuideAction.HAND_IN_CONTRACT, errands().get(0).getAction());
		assertTrue("the run cannot be over with the reward uncollected",
			tracker.contractBusinessOutstanding());
	}

	/** The step the whole chain hangs on, over a key that is still naming the settled crop. */
	@Test
	public void theNextContractIsAskedForOverAStaleKey() throws Exception
	{
		contracts.recordHandedIn();

		assertEquals("Time Tracking still says cadantine, and it is wrong", Produce.CADANTINE,
			timeTrackingSays());
		assertNull("what it says is a contract we have already given back",
			contracts.getContract());

		List<GuideStep> steps = errands();
		assertFalse("there is one thing left to do here", steps.isEmpty());
		assertEquals(GuideAction.TAKE_CONTRACT, steps.get(0).getAction());
		assertTrue("and the run stays open until it is done",
			tracker.contractBusinessOutstanding());
	}

	/** And the moment Jane names a new crop the marker is spent and the seed is named. */
	@Test
	public void aNewAssignmentClosesTheWindowAndNamesItsSeed() throws Exception
	{
		contracts.recordHandedIn();
		assignInTimeTracking(Produce.SNAPDRAGON);

		assertEquals("the new contract is the one that stands", Produce.SNAPDRAGON,
			contracts.getContract());
		assertNull("the window between contracts is closed", contracts.getSettledContract());
		assertEquals("and its seed is what the run now wants", Seed.SNAPDRAGON,
			contracts.getContractSeed());
		assertFalse("nothing left to ask her for", has(errands(), GuideAction.TAKE_CONTRACT));
	}

	/**
	 * Waving the errand past is the way out, so a player who wants no more contracts is not
	 * held at the guild by one.
	 */
	@Test
	public void skippingTheErrandGivesTheRunItsEndingBack() throws Exception
	{
		contracts.recordHandedIn();
		skip(errands().get(0));

		assertFalse("skipped is skipped, and the stop may finish",
			tracker.contractBusinessOutstanding());
	}

	// ------------------------------------------------------------------- helpers

	private void assignInTimeTracking(Produce produce)
	{
		stored.put(TIME_TRACKING + ".contract", String.valueOf(produce.getItemID()));
	}

	@Nullable
	private Produce timeTrackingSays()
	{
		Object stored = this.stored.get(TIME_TRACKING + ".contract");
		return stored == null
			? null
			: Produce.getByItemID(Integer.parseInt(String.valueOf(stored)));
	}

	/** What Skip step records, written straight into the set it records it in. */
	@SuppressWarnings("unchecked")
	private void skip(GuideStep step) throws Exception
	{
		java.lang.reflect.Field field = GuideTracker.class.getDeclaredField("skippedSteps");
		field.setAccessible(true);
		((java.util.Set<String>) field.get(tracker))
			.add(step.getPatch().getKey() + "#" + step.getAction().name());
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
		long now = java.time.Instant.now().getEpochSecond();
		Constructor<PatchProjection> ctor = PatchProjection.class.getDeclaredConstructor(
			FarmPatch.class, Produce.class, CropState.class, int.class, int.class,
			long.class, int.class, long.class, Confidence.class, boolean.class, long.class,
			boolean.class, int.class);
		ctor.setAccessible(true);

		when(growthTimer.project(Mockito.eq(patch), any())).thenReturn(ctor.newInstance(patch,
			null, CropState.EMPTY, 0, 0, now + 3600, 0, 0L, Confidence.CERTAIN, false, now,
			false, -1));
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
			else if (type == DoogleMapsConfig.class)
			{
				args[i] = config;
			}
			else
			{
				args[i] = Mockito.mock(type);
				// The two stores whose null answers are read as values rather than as absences:
				// a null tier is dereferenced for the per-stop count, and a null seed set for
				// the allocation. Neither is what this test is about.
				if (type == com.dooglemaps.state.SeedSelectionStore.class)
				{
					when(((com.dooglemaps.state.SeedSelectionStore) args[i])
						.getSelectedFor(any(PlantingGroup.class)))
						.thenReturn(new java.util.LinkedHashSet<>());
				}
				else if (type == com.dooglemaps.state.CompostSelectionStore.class)
				{
					when(((com.dooglemaps.state.CompostSelectionStore) args[i])
						.get(any(PlantingGroup.class)))
						.thenReturn(com.dooglemaps.data.CompostTier.NONE);
				}
			}
		}
		return (GuideTracker) constructor.newInstance(args);
	}
}
