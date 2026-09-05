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

	/**
	 * The planner, because it owns one answer this class asks for.
	 *
	 * <p>Whether a standing contract crop is <b>spent</b> — checked before the contract was taken,
	 * so it can never satisfy it — is {@code RunPlanner.contractStandingIsSpent}, and it lives
	 * there because the same question decides whether the run allocates a seed for the replant.
	 * The guide asks rather than re-deriving, so a test about the dud has to say what the planner
	 * would answer. The judgment itself is tested in
	 * {@code route.ADudContractStillWantsItsSeedTest}.
	 */
	private com.dooglemaps.route.RunPlanner planner;

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

		planner = Mockito.mock(com.dooglemaps.route.RunPlanner.class);

		tracker = trackerWith(contracts, groups, growthTimer, patches, config, chat, seeds,
			planner);

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
	/**
	 * Claiming the reward is a conversation, not a delivery, and the step must not imply otherwise.
	 *
	 * <h2>The correction</h2>
	 *
	 * <i>"you don't 'hand in' your harvest for a contract, you can still note it on the lep and
	 * keep it, you just go talk to jane"</i>.
	 *
	 * <p>The step said "Hand your cactus to Guildmaster Jane for the contract reward" and carried
	 * the produce's item id, which outlined it in the pack — together they read as the crop being
	 * the price. It is not: the contract completes when the crop is harvested, and the produce is
	 * the player's to note and keep.
	 *
	 * <p>The item assertion is the load-bearing one. The wording could be reworded again by anyone;
	 * an outlined crop beside a hand-in step is the part that actively misleads.
	 */
	@Test
	public void theRewardIsClaimedWithoutGivingTheProduceUp() throws Exception
	{
		when(contracts.getAwaitingHandIn()).thenReturn(Produce.POTATO_CACTUS);
		when(contracts.getContract()).thenReturn(null);
		when(contracts.hasContract()).thenReturn(false);
		project(cactus, Produce.POTATO_CACTUS, CropState.HARVESTABLE, 0);

		GuideStep handIn = null;
		for (GuideStep step : errands())
		{
			if (step.getAction() == GuideAction.HAND_IN_CONTRACT)
			{
				handIn = step;
				break;
			}
		}
		assertNotNull("the finished contract should be claimable", handIn);

		assertFalse("nothing of the player's is outlined for a reward they do not pay for",
			handIn.hasItem());
		assertFalse("and the wording must not say the crop changes hands: " + handIn.getText(),
			handIn.getText().toLowerCase().contains("hand your"));
	}

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

	/**
	 * A crop health-checked <b>before</b> the contract was assigned is not read as a completion.
	 *
	 * <h2>The poison ivy report</h2>
	 *
	 * Wiki-checked: a check-health crop completes its contract at the check, the check fires once
	 * per planting, and one made before the assignment can never satisfy it — "they will need to
	 * plant a new seed and wait for it to grow". The patch-evidence fallback read the standing
	 * {@code HARVESTABLE} bush as a completion nothing had recorded, wrote it to config, and
	 * marched the player to Jane with berries she would not take. {@code HARVESTABLE} only exists
	 * <i>after</i> the check for these families, and a check made during the contract announces
	 * itself in the chatbox — so this state with nothing awaiting is exactly the pre-checked dud.
	 */
	@Test
	public void aPreCheckedCropIsNotMistakenForACompletedContract() throws Exception
	{
		when(contracts.getContract()).thenReturn(Produce.POTATO_CACTUS);
		when(contracts.getAwaitingHandIn()).thenReturn(null);
		when(contracts.hasContract()).thenReturn(true);
		// Post-check and picked clean - the state the report ended in after the harvest.
		project(cactus, Produce.POTATO_CACTUS, CropState.HARVESTABLE, 0);

		List<GuideStep> steps = errands();

		assertFalse("Jane will not take it, so the guide must not send you",
			has(steps, GuideAction.HAND_IN_CONTRACT));
		assertFalse("and the contract is still assigned, so there is nothing to ask for",
			has(steps, GuideAction.TAKE_CONTRACT));
		Mockito.verify(contracts, Mockito.never()).recordCompleted();
	}

	/** The logged-out case the fallback exists for still works: grown, unchecked, detected. */
	@Test
	public void aGrownUncheckedContractIsStillDetected() throws Exception
	{
		when(contracts.getContract()).thenReturn(Produce.POTATO_CACTUS);
		when(contracts.getAwaitingHandIn()).thenReturn(null);
		when(contracts.hasContract()).thenReturn(true);
		project(cactus, Produce.POTATO_CACTUS, CropState.GROWING,
			Produce.POTATO_CACTUS.getStages() - 1, 0);

		errands();

		Mockito.verify(contracts).recordCompleted();
	}

	/** A harvest-class crop cannot be a dud - standing grown is still completion evidence. */
	@Test
	public void aGrownHerbStillReadsAsACompletion() throws Exception
	{
		FarmPatch herb = guildPatch(PatchImplementation.HERB);
		assertNotNull("the Farming Guild has no herb patch in the data", herb);
		when(groups.patchesIn(PlantingGroup.contract(PatchImplementation.HERB)))
			.thenReturn(Collections.singletonList(herb));

		when(contracts.getContract()).thenReturn(Produce.RANARR);
		when(contracts.getAwaitingHandIn()).thenReturn(null);
		when(contracts.hasContract()).thenReturn(true);
		project(herb, Produce.RANARR, CropState.HARVESTABLE, 3);

		errands();

		Mockito.verify(contracts).recordCompleted();
	}

	/** The dud is explained - in the panel note and once in the chatbox - not just refused. */
	@Test
	public void aPreCheckedCropSaysWhyItIsBeingReplanted() throws Exception
	{
		when(contracts.getContract()).thenReturn(Produce.POTATO_CACTUS);
		when(contracts.getAwaitingHandIn()).thenReturn(null);
		when(contracts.hasContract()).thenReturn(true);
		project(cactus, Produce.POTATO_CACTUS, CropState.HARVESTABLE, 0);
		// The planner's verdict on that projection; see the field note on why it is asked for
		// rather than worked out here.
		when(planner.contractStandingIsSpent(cactus)).thenReturn(true);

		String note = note();
		assertNotNull("digging up a healthy crop needs its why", note);
		assertTrue("it says the check came first: " + note,
			note.contains("health-checked before the contract"));
		assertTrue("and what to do about it: " + note, note.contains("Dig it up"));

		note();
		note();
		java.util.List<QueuedMessage> said = queued();
		assertEquals("said once, not once a tick", 1, said.size());
		assertTrue("the chat line carries the same explanation",
			said.get(0).getRuneLiteFormattedMessage().contains("checked before the contract"));
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
			boolean.class, int.class);
		ctor.setAccessible(true);

		// A done estimate already in the past, which is what makes a last-stage GROWING patch read
		// as grown-but-unchecked rather than still on its way. Never a stump: nothing here is a
		// tree, and TreeStumpTest covers the patches that can be one.
		PatchProjection projection = ctor.newInstance(patch, produce, state, stage,
			produce.getStages(), done ? now - 60 : now + 3600, lives,
			0L, Confidence.CERTAIN, false, now, false, -1);

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
		ChatMessageManager chat, com.dooglemaps.state.SeedInventoryStore seeds,
		com.dooglemaps.route.RunPlanner planner)
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
			else if (type == com.dooglemaps.route.RunPlanner.class)
			{
				args[i] = planner;
			}
			else
			{
				args[i] = Mockito.mock(type);
			}
		}
		return (GuideTracker) constructor.newInstance(args);
	}
}
