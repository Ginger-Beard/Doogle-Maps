package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.Produce;
import com.dooglemaps.route.RunStop;
import com.dooglemaps.state.ContractState;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.PlantingGroups;
import com.dooglemaps.timer.Confidence;
import com.dooglemaps.timer.GrowthTimer;
import com.dooglemaps.timer.PatchProjection;
import javax.annotation.Nullable;
import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * What Jane is asked for, and when, once the contract crop has been checked.
 *
 * <h2>The state these are about</h2>
 *
 * A cactus, bush or fruit tree finishes growing into a state the game still calls {@code GROWING};
 * checking it moves it to {@code HARVESTABLE} <b>with a stock of zero</b>, and the produce then
 * comes back one at a time. Read off the varbit table: a potato cactus reads
 * {@code HARVESTABLE stage 0} the moment it is checked, {@code stage 1..6} as its cacti return.
 *
 * <p>Two separate things went wrong in that gap, and they look identical from the player's side —
 * "I checked the cactus and it sent me straight to Jane".
 */
public class ContractHandInOrderTest
{
	private GuideTracker tracker;
	private ContractState contracts;
	private PlantingGroups groups;
	private GrowthTimer growthTimer;
	private PatchStateStore patches;
	private ChatMessageManager chat;
	private com.dooglemaps.state.SeedInventoryStore seeds;
	private FarmPatch cactus;

	@Before
	public void setUp() throws Exception
	{
		contracts = Mockito.mock(ContractState.class);
		groups = Mockito.mock(PlantingGroups.class);
		growthTimer = Mockito.mock(GrowthTimer.class);
		patches = Mockito.mock(PatchStateStore.class);
		chat = Mockito.mock(ChatMessageManager.class);
		seeds = Mockito.mock(com.dooglemaps.state.SeedInventoryStore.class);

		DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class);
		when(config.guideFarmingContracts()).thenReturn(true);
		when(config.contractSeedAdvice())
			.thenReturn(DoogleMapsConfig.ContractSeedAdvice.ASK_FOR_EASIER);

		tracker = trackerWith(contracts, groups, growthTimer, patches, config, chat, seeds);

		cactus = guildPatch(PatchImplementation.CACTUS);
		assertNotNull("the Farming Guild has no cactus patch in the data", cactus);
		when(groups.patchesIn(PlantingGroup.contract(PatchImplementation.CACTUS)))
			.thenReturn(Collections.singletonList(cactus));
	}

	/**
	 * The reported bug: checking the contract cactus sends you to Jane for a <i>new</i> contract.
	 *
	 * <p>It reads as the hand-in firing early and is not. Completion clears the assignment — that
	 * is what {@code getAwaitingHandIn} exists to remember — so {@code hasContract()} says no, and
	 * the "ask Jane for a new one" step went in at the front of the list while the finished crop
	 * was still standing in the patch behind you. Jane will not give one out until the last is
	 * settled, so the step could not even be followed.
	 */
	@Test
	public void aFinishedContractIsNotSweptAsideByAskingForTheNextOne() throws Exception
	{
		when(contracts.getAwaitingHandIn()).thenReturn(Produce.POTATO_CACTUS);
		when(contracts.getContract()).thenReturn(null);
		when(contracts.hasContract()).thenReturn(false);
		project(cactus, Produce.POTATO_CACTUS, CropState.HARVESTABLE, 0);

		List<GuideStep> steps = errands();

		assertFalse("nothing to ask for while one is unsettled",
			has(steps, GuideAction.TAKE_CONTRACT));
		assertEquals("the finished one is what Jane is for", GuideAction.HAND_IN_CONTRACT,
			steps.get(0).getAction());
	}

	/**
	 * And while there is produce on it, the harvest comes first.
	 *
	 * <p>"We should at least harvest and finish that patch while we're at it" — the hand-in defers
	 * itself so the harvest step {@code GuidePlan} is already producing stays in front of it.
	 */
	@Test
	public void theHandInWaitsWhileThereIsStillSomethingToPick() throws Exception
	{
		when(contracts.getAwaitingHandIn()).thenReturn(Produce.POTATO_CACTUS);
		when(contracts.getContract()).thenReturn(null);
		when(contracts.hasContract()).thenReturn(false);
		project(cactus, Produce.POTATO_CACTUS, CropState.HARVESTABLE, 4);

		List<GuideStep> steps = errands();

		assertFalse("Jane waits until the cacti are picked",
			has(steps, GuideAction.HAND_IN_CONTRACT));
		assertFalse("and there is still nothing to ask her for",
			has(steps, GuideAction.TAKE_CONTRACT));
	}

	/**
	 * A regrowing contract crop can be handed in at all, which it could not.
	 *
	 * <p>The deferral used to ask whether the crop was still standing. For a herb that is the same
	 * as "still has something on it" — pick it and the patch empties. A cactus never empties: it
	 * stays {@code HARVESTABLE} for the rest of its life, stock or no stock, so the hand-in was
	 * deferred forever and a cactus contract could never be completed.
	 */
	@Test
	public void aPickedCleanCactusStopsBlockingTheHandIn() throws Exception
	{
		when(contracts.getAwaitingHandIn()).thenReturn(Produce.POTATO_CACTUS);
		when(contracts.getContract()).thenReturn(null);
		when(contracts.hasContract()).thenReturn(false);
		project(cactus, Produce.POTATO_CACTUS, CropState.HARVESTABLE, 0);

		assertTrue("the plant is still standing and always will be",
			has(errands(), GuideAction.HAND_IN_CONTRACT));
	}

	/**
	 * An unchecked one still holds the hand-in back, which is the older fix and still wanted.
	 *
	 * <p>Walking to Jane past the patch holding the thing she wants is the bug that started this.
	 * Checking it is the step, and the hand-in reappears the moment it is done.
	 */
	@Test
	public void anUncheckedCropStillHoldsTheHandInBack() throws Exception
	{
		when(contracts.getAwaitingHandIn()).thenReturn(Produce.POTATO_CACTUS);
		when(contracts.getContract()).thenReturn(null);
		when(contracts.hasContract()).thenReturn(false);
		// Grown but unchecked: the game still calls this GROWING, at the last stage.
		project(cactus, Produce.POTATO_CACTUS, CropState.GROWING,
			Produce.POTATO_CACTUS.getStages() - 1, 0);

		assertFalse("check it before walking off to her",
			has(errands(), GuideAction.HAND_IN_CONTRACT));
	}

	/** With nothing outstanding at all, asking for the next one is exactly right. */
	@Test
	public void withNothingWaitingSheIsAskedForTheNextContract() throws Exception
	{
		when(contracts.getAwaitingHandIn()).thenReturn(null);
		when(contracts.getContract()).thenReturn(null);
		when(contracts.hasContract()).thenReturn(false);

		assertTrue(has(errands(), GuideAction.TAKE_CONTRACT));
	}

	/**
	 * A contract whose patch has something still growing in it says so, in the chatbox.
	 *
	 * <h2>Why this is a message and not a step</h2>
	 *
	 * The guild has one patch of each type, so an occupied one genuinely blocks the contract — and
	 * the only way past it is to dig up a crop that is days into growing. That is a trade the
	 * player may well refuse, so the guide does not make it for them: the run walks past, and the
	 * chatbox says what is in the way and what the two options are.
	 *
	 * <p>The panel note alone was not enough. It is only read by someone already looking at the
	 * sidebar, and this is a decision rather than a status.
	 */
	@Test
	public void agrowingCropInTheContractsPatchIsAnnounced() throws Exception
	{
		blockedBy(Produce.CACTUS);

		String note = note();
		assertNotNull("the panel says nothing about it", note);
		assertTrue("it names what is in the way: " + note, note.contains("cactus"));

		java.util.List<QueuedMessage> said = queued();
		assertEquals("one message, not one a tick", 1, said.size());
		assertEquals(ChatMessageType.GAMEMESSAGE, said.get(0).getType());

		String text = said.get(0).getRuneLiteFormattedMessage();
		assertTrue("names the contract: " + text, text.contains("potato cactus contract"));
		assertTrue("names what is in the way: " + text, text.contains("cactus still growing"));
		assertTrue("and gives both options: " + text,
			text.contains("remove it") && text.contains("wait"));
	}

	/** And it is said once, however long you stand there. */
	@Test
	public void theWarningIsNotRepeatedEveryTick() throws Exception
	{
		blockedBy(Produce.CACTUS);

		note();
		note();
		note();

		assertEquals("the tick loop must not turn this into spam", 1, queued().size());
	}

	/** Nothing is said while the contract's own crop is the thing growing there. */
	@Test
	public void aContractGrowingWhereItBelongsIsNotAWarning() throws Exception
	{
		blockedBy(Produce.POTATO_CACTUS);

		assertNull("it is doing exactly what was asked of it", note());
		assertTrue("and nothing to say about it", queued().isEmpty());
	}

	// ------------------------------------------------------------------- helpers

	/** A run standing in the guild, with {@code occupant} mid-growth in the contract's patch. */
	private void blockedBy(Produce occupant) throws Exception
	{
		when(contracts.getContract()).thenReturn(Produce.POTATO_CACTUS);
		when(contracts.getAwaitingHandIn()).thenReturn(null);
		when(contracts.getContractSeed()).thenReturn(com.dooglemaps.data.Seed.POTATO_CACTUS);
		// Owning the seed, so the "no seed for the contract" note does not fire first and mask
		// the one under test.
		when(seeds.getOwned(Mockito.any())).thenReturn(99);

		// Mid-growth: not empty, and nowhere near ready.
		project(cactus, occupant, CropState.GROWING, 1, 0, false);
	}

	/** The note the panel would show, for a stop that is not carrying the contract's patch. */
	@Nullable
	private String note() throws Exception
	{
		Method method = GuideTracker.class.getDeclaredMethod("contractNote", RunStop.class);
		method.setAccessible(true);

		RunStop stop = Mockito.mock(RunStop.class);
		when(stop.getRegion()).thenReturn(cactus.getRegion());
		// The patch is not in the stop — which is the situation the note exists for.
		when(stop.getPatches()).thenReturn(Collections.emptyList());
		when(stop.getServiced()).thenReturn(new java.util.HashSet<>());

		try
		{
			return (String) method.invoke(tracker, stop);
		}
		catch (java.lang.reflect.InvocationTargetException e)
		{
			// Unwrapped, so a failure inside the tracker reads as itself rather than as
			// "InvocationTargetException" with the real cause buried.
			throw new AssertionError(e.getCause());
		}
	}

	private java.util.List<QueuedMessage> queued()
	{
		org.mockito.ArgumentCaptor<QueuedMessage> captor =
			org.mockito.ArgumentCaptor.forClass(QueuedMessage.class);
		Mockito.verify(chat, Mockito.atLeast(0)).queue(captor.capture());
		return captor.getAllValues();
	}

	private static boolean has(List<GuideStep> steps, GuideAction action)
	{
		return steps.stream().anyMatch(step -> step.getAction() == action);
	}

	private List<GuideStep> errands() throws Exception
	{
		Method method = GuideTracker.class.getDeclaredMethod(
			"appendContractErrands", List.class, RunStop.class);
		method.setAccessible(true);

		List<GuideStep> steps = new ArrayList<>();
		method.invoke(tracker, steps, guildStop());
		return steps;
	}

	/** A projection for the patch, in the harvestable phase with the given stock. */
	private void project(FarmPatch patch, Produce produce, CropState state, int lives)
		throws Exception
	{
		project(patch, produce, state, produce.getStages() - 1, lives);
	}

	private void project(FarmPatch patch, Produce produce, CropState state, int stage, int lives)
		throws Exception
	{
		project(patch, produce, state, stage, lives, true);
	}

	private void project(FarmPatch patch, Produce produce, CropState state, int stage, int lives,
		boolean done) throws Exception
	{
		long now = java.time.Instant.now().getEpochSecond();

		Constructor<PatchProjection> ctor = PatchProjection.class.getDeclaredConstructor(
			FarmPatch.class, Produce.class, CropState.class, int.class, int.class,
			long.class, int.class, long.class, Confidence.class, boolean.class, long.class,
			boolean.class);
		ctor.setAccessible(true);

		// A done estimate already in the past, which is what makes a last-stage GROWING patch read
		// as grown-but-unchecked rather than still on its way. Never a stump: nothing here is a
		// tree, and TreeStumpTest covers the patches that can be one.
		PatchProjection projection = ctor.newInstance(patch, produce, state, stage,
			produce.getStages(), done ? now - 60 : now + 3600, lives,
			0L, Confidence.CERTAIN, false, now, false);

		when(growthTimer.project(Mockito.eq(patch), any())).thenReturn(projection);
	}

	private RunStop guildStop()
	{
		RunStop stop = Mockito.mock(RunStop.class);
		when(stop.getName()).thenReturn("Farming Guild");
		when(stop.getRegion()).thenReturn(cactus.getRegion());
		when(stop.getPatches()).thenReturn(Collections.singletonList(cactus));
		when(stop.getServiced()).thenReturn(new java.util.HashSet<>());
		return stop;
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

	/** A tracker with the four collaborators the contract errands actually read. */
	private static GuideTracker trackerWith(ContractState contracts, PlantingGroups groups,
		GrowthTimer growthTimer, PatchStateStore patches, DoogleMapsConfig config,
		ChatMessageManager chat, com.dooglemaps.state.SeedInventoryStore seeds)
		throws Exception
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
			else if (type == PatchStateStore.class)
			{
				args[i] = patches;
			}
			else if (type == DoogleMapsConfig.class)
			{
				args[i] = config;
			}
			else if (type == ChatMessageManager.class)
			{
				args[i] = chat;
			}
			else if (type == com.dooglemaps.state.SeedInventoryStore.class)
			{
				args[i] = seeds;
			}
			else
			{
				args[i] = Mockito.mock(type);
			}
		}
		return (GuideTracker) constructor.newInstance(args);
	}
}
