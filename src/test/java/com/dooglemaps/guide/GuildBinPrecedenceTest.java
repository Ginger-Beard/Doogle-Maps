package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.RunOption;
import com.dooglemaps.route.RunStop;
import com.dooglemaps.state.ContractState;
import com.dooglemaps.state.RunTypeStore;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * What the Farming Guild offers, and in what order.
 *
 * <h2>The hold-back was too broad in two directions</h2>
 *
 * {@code contractComesFirst} keeps the guild clear while a contract wants something, so an
 * ordinary crop cannot be planted in ground the contract needs. Two things were wrong with
 * where it applied:
 *
 * <ul>
 *   <li>it fired on runs that were <b>not doing the contract at all</b>, and with none assigned
 *       it withheld the entire guild — a compost-only run stood at the guild bank and was
 *       offered nothing, because the guild was being kept clear for a contract nobody asked
 *       for;</li>
 *   <li>it withheld the <b>compost bin</b>, which Jane can never assign, so it competes for no
 *       ground and has no reason to wait for anything.</li>
 * </ul>
 *
 * <p>And the bin is not merely allowed through but goes first: emptying it frees the pack and
 * restocks the compost every other patch at that stop is about to want.
 */
public class GuildBinPrecedenceTest
{
	private ContractState contracts;
	private RunTypeStore runTypes;
	private DoogleMapsConfig config;
	private GuideTracker tracker;

	/** Held so a bin can be given a state; the ordering now turns on what it is waiting for. */
	private com.dooglemaps.timer.GrowthTimer growthTimer;

	/** And on what is in the pack, since a full one changes where a hungry bin belongs. */
	private CarriedItems carried;
	private com.dooglemaps.state.CompostRunStore compostRun;

	/** Only so the tier lookup has an answer; a mock's null NPEs inside patchesWanting. */
	private com.dooglemaps.state.CompostSelectionStore compost;

	/** Likewise: the step builder wants a real group for the patch it is asked about. */
	private com.dooglemaps.state.PlantingGroups groups;

	/** The raw snapshots, which are what a bin's item count has to be read from. */
	private com.dooglemaps.state.PatchStateStore patchStore;

	@Before
	public void setUp() throws Exception
	{
		contracts = Mockito.mock(ContractState.class);
		runTypes = Mockito.mock(RunTypeStore.class);
		config = Mockito.mock(DoogleMapsConfig.class);
		when(config.guideFarmingContracts()).thenReturn(true);

		tracker = trackerWith();
		// Each patch in its own group, so the step builder has something real to ask about.
		// Null-tolerant: groupFor is asked about a null patch on some paths.
		when(groups.groupFor(Mockito.nullable(FarmPatch.class))).thenAnswer(invocation ->
		{
			FarmPatch asked = invocation.getArgument(0, FarmPatch.class);
			return asked == null
				? null : com.dooglemaps.data.PlantingGroup.of(asked.getImplementation());
		});
		// nullable, not any: any(Class) declines to match a null argument, and some lookups
		// here legitimately pass one.
		when(compost.get(Mockito.nullable(com.dooglemaps.data.PlantingGroup.class)))
			.thenReturn(com.dooglemaps.data.CompostTier.NONE);
	}

	/**
	 * The reported dead end: no contract assigned, and the whole guild withheld.
	 *
	 * <p>With the contract line unticked there is nothing to keep the ground clear for, so the
	 * guild is open — and a compost-only run is offered its bin.
	 */
	@Test
	public void aRunWithoutTheContractIsOfferedTheGuild() throws Exception
	{
		when(contracts.getActiveContractType()).thenReturn(null);

		List<FarmPatch> offered = offer(guildPatches());

		assertFalse("the guild was held clear for a contract nobody asked for",
			offered.isEmpty());
		assertTrue("including the bin", offered.stream().anyMatch(
			patch -> patch.getImplementation() == PatchImplementation.BIG_COMPOST));
	}

	/**
	 * With a contract assigned but its line unticked, the guild still opens.
	 *
	 * <p>"Not selected for the run" is the owner's rule, and it covers this as well as the case
	 * above: a player ignoring an assigned contract is not asking for its ground to be reserved.
	 */
	@Test
	public void anUntickedContractDoesNotHoldTheGuild() throws Exception
	{
		when(contracts.getActiveContractType()).thenReturn(PatchImplementation.HERB);
		when(runTypes.isSelected(RunOption.full(PlantingGroup.contract(
			PatchImplementation.HERB)))).thenReturn(false);

		assertFalse(offer(guildPatches()).isEmpty());
	}

	/** Ticked, with nothing claimed yet, the hold-back still does its job — bar the bin. */
	@Test
	public void atickedContractStillHoldsTheGuildButNotTheBin() throws Exception
	{
		when(contracts.getActiveContractType()).thenReturn(PatchImplementation.HERB);
		when(runTypes.isSelected(RunOption.full(PlantingGroup.contract(
			PatchImplementation.HERB)))).thenReturn(true);
		when(contracts.hasContract()).thenReturn(false);
		when(contracts.claimsUntilHandedIn(Mockito.any())).thenReturn(false);

		List<FarmPatch> offered = offer(guildPatches());

		assertEquals("only the bin survives the hold-back", 1, offered.size());
		assertEquals(PatchImplementation.BIG_COMPOST, offered.get(0).getImplementation());
	}

	/**
	 * A bin with compost in it is offered first, which is what the ordering was always for.
	 *
	 * <p>Emptying frees the pack and restocks the compost every other patch at that stop is
	 * about to want, so doing it last means arriving at the herbs with fifteen slots of produce
	 * in hand and no ultracompost.
	 */
	@Test
	public void aReadyBinSortsToTheFrontOfItsStop() throws Exception
	{
		binIs(com.dooglemaps.data.CropState.HARVESTABLE);

		List<FarmPatch> ordered = binLast();
		sort(ordered);

		assertEquals("emptying it frees the pack for everything else here",
			PatchImplementation.BIG_COMPOST, ordered.get(0).getImplementation());
	}

	/**
	 * A bin waiting to be filled is offered <b>last</b>, and has to be.
	 *
	 * <p>It is fed from the harvest standing next to it, so putting it first offers a fill the
	 * player is not holding — which produces no step, and through the idle report quietly
	 * completes the stop. Split on what the bin waits for rather than on which bin it is, so the
	 * guild's own bin lands correctly both ways.
	 */
	@Test
	public void aBinWaitingToBeFilledSortsToTheBack() throws Exception
	{
		binIs(com.dooglemaps.data.CropState.EMPTY);

		List<FarmPatch> ordered = binLast();
		// Shuffled to the front first, so passing cannot be an accident of the input order.
		ordered.sort((a, b) -> Boolean.compare(
			b.getImplementation() == PatchImplementation.BIG_COMPOST,
			a.getImplementation() == PatchImplementation.BIG_COMPOST));
		sort(ordered);

		assertEquals("you cannot fill it from a harvest that has not happened",
			PatchImplementation.BIG_COMPOST,
			ordered.get(ordered.size() - 1).getImplementation());
	}

	/**
	 * A full pack turns "the bin goes last" from an ordering into a dead end.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * A bin waiting to be fed goes last, which is right: you cannot fill it from a harvest that
	 * has not happened. But the pack fills up <i>during</i> that harvest, and at that moment
	 * three things are true at once — the harvest cannot continue, the leprechaun's note is
	 * suppressed precisely because a bin here wants the crop, and the bin's own step is queued
	 * behind the harvest that is stuck. Reported from play: told to pick more watermelons with
	 * nowhere to put them, and never once offered the bin standing beside the patch.
	 */
	@Test
	public void aFullPackSendsYouToTheBinYouCanFeed() throws Exception
	{
		binIs(com.dooglemaps.data.CropState.EMPTY);
		holding(0, com.dooglemaps.data.CompostBin.BIG.getCapacity());

		List<FarmPatch> ordered = binLast();
		sort(ordered);

		assertEquals("the bin is the only thing you can do with a full pack",
			PatchImplementation.BIG_COMPOST, ordered.get(0).getImplementation());
	}

	/**
	 * With room to carry on picking, and not enough to close it, the bin waits its turn.
	 *
	 * <p>Holding <b>less than the bin wants</b>, which is the point and used to be incidental:
	 * this held a whole bin's worth against an empty bin, so it also satisfied "you could finish
	 * it right now" — the case {@link #aBinYouCanFinishNowIsOfferedNow} is for. Two rules met in
	 * one fixture and the test only named one of them, so it was pinning a boundary it was not
	 * describing. Six of a thirty-item bin is unambiguously "keep picking".
	 */
	@Test
	public void aBinStillWaitsWhileThereIsRoomToHarvest() throws Exception
	{
		binIs(com.dooglemaps.data.CropState.EMPTY);
		holding(6, 6);

		List<FarmPatch> ordered = binLast();
		sort(ordered);

		assertEquals("six free slots is not a dead end",
			PatchImplementation.BIG_COMPOST,
			ordered.get(ordered.size() - 1).getImplementation());
	}

	/** A full pack of something the bin will not take is the leprechaun's problem, not the bin's. */
	@Test
	public void aFullPackOfSomethingTheBinRefusesDoesNotMoveIt() throws Exception
	{
		binIs(com.dooglemaps.data.CropState.EMPTY);
		holding(0, 0);

		List<FarmPatch> ordered = binLast();
		sort(ordered);

		assertEquals("nothing on board it would accept",
			PatchImplementation.BIG_COMPOST,
			ordered.get(ordered.size() - 1).getImplementation());
	}

	/**
	 * Sets the pack up: this many free slots, and this many watermelons in it.
	 *
	 * <p>Watermelon because it is on the allotment fodder list and is supercompostable, so
	 * {@code CompostBinPlan} treats a bin-sized stack of them as the best possible fill.
	 */
	private void holding(int freeSlots, int watermelons)
	{
		when(carried.getFreeSlots()).thenReturn(freeSlots);
		when(carried.getInventoryCount(net.runelite.api.gameval.ItemID.WATERMELON))
			.thenReturn(watermelons);
		when(compostRun.isFodderEnabled()).thenReturn(true);
		when(compostRun.getFodderCrops()).thenReturn(java.util.Collections.singleton(
			net.runelite.api.gameval.ItemID.WATERMELON));
	}

	/**
	 * The working-patch latch lets go when the pack is full and its patch wants a harvest.
	 *
	 * <h2>The reported dead end, seven rounds of it</h2>
	 *
	 * {@code binsFirst} moves a hungry bin to the front of the stop's list, and
	 * {@code chooseWorkingPatch} runs <b>afterwards</b> and hoists whatever patch you are part
	 * way through back to the top. So every fix aimed at the ordering was overwritten a few
	 * lines later, and the log said "bin first" while the panel said "harvest" — because the log
	 * was reading the list before this ran.
	 *
	 * <p>The latch is right in general: it stops the guide hopping between patches mid-job. It is
	 * wrong when the job cannot be done. A full pack and a harvest is exactly that — there is no
	 * next click — and the thing that unblocks it is the bin the latch is hiding.
	 */
	@Test
	public void afullPackReleasesTheWorkingPatch() throws Exception
	{
		everyPatchOffersAHarvest();
		List<FarmPatch> ordered = guildPatches();
		FarmPatch firstInLine = ordered.get(0);
		FarmPatch beingWorked = ordered.get(ordered.size() - 1);
		assertFalse("fixture: the worked patch must not already be first",
			firstInLine.getKey().equals(beingWorked.getKey()));

		setWorking(beingWorked);
		when(carried.getFreeSlots()).thenReturn(6);
		assertEquals("with room to pick, the latch holds",
			beingWorked.getKey(), chooseWorkingPatch(ordered).getKey());

		when(carried.getFreeSlots()).thenReturn(0);
		assertEquals("with none, the ordering decides again",
			firstInLine.getKey(), chooseWorkingPatch(ordered).getKey());
	}

	/**
	 * And it takes you back when the pack has room again.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * Releasing the latch was only half the job. The detour <b>re-pointed</b> it — the bin became
	 * the patch being worked — so the fact that an allotment was half picked was thrown away. Fill
	 * the bin and close it and the bin has no steps left, so the next tick fell through to "first
	 * patch in the ordering" and picked something else entirely.
	 *
	 * <p>Reported from play at Ardougne: mid watermelon harvest, pack fills, fill and close the
	 * bin beside it, and be sent to the herb patch with the watermelons still standing.
	 *
	 * <p>A blocked patch is not a finished one. The bin is somewhere you are sent, not something
	 * you are working, so the latch stays where it was and the moment there is room to pick it
	 * hands you straight back.
	 */
	@Test
	public void roomToPickAgainReturnsYouToTheHalfWorkedPatch() throws Exception
	{
		everyPatchOffersAHarvest();
		List<FarmPatch> ordered = guildPatches();
		FarmPatch firstInLine = ordered.get(0);
		FarmPatch beingWorked = ordered.get(ordered.size() - 1);
		assertFalse("fixture: the worked patch must not already be first",
			firstInLine.getKey().equals(beingWorked.getKey()));

		setWorking(beingWorked);
		when(carried.getFreeSlots()).thenReturn(0);
		assertEquals("fixture: a full pack sends you off to the bin",
			firstInLine.getKey(), chooseWorkingPatch(ordered).getKey());

		// The detour finishes: nothing outstanding at it any more.
		when(carried.getFreeSlots()).thenReturn(6);
		nothingOutstandingAt(firstInLine);

		assertEquals("the half-picked patch is the one you go back to",
			beingWorked.getKey(), chooseWorkingPatch(ordered).getKey());
	}

	/**
	 * And the detour is allowed to finish before you are handed back.
	 *
	 * <h2>The regression this is here to stop repeating</h2>
	 *
	 * The first fix for the handoff above declined to move the latch at all while the pack was
	 * full, on the reasoning that a blocked patch is interrupted rather than finished. That is
	 * true, and it returned you to the allotment the instant the bin gave back a <b>single</b>
	 * slot — bin still open, fodder still in the pack. Reported from play at Falador: told to
	 * carry on harvesting before the fodder was in the bin.
	 *
	 * <p>The two halves are one rule: the detour keeps the latch while it has work of its own,
	 * and hands back only when it runs out.
	 */
	@Test
	public void theDetourKeepsTheLatchWhileItStillHasWork() throws Exception
	{
		everyPatchOffersAHarvest();
		List<FarmPatch> ordered = guildPatches();
		FarmPatch firstInLine = ordered.get(0);
		FarmPatch beingWorked = ordered.get(ordered.size() - 1);

		setWorking(beingWorked);
		when(carried.getFreeSlots()).thenReturn(0);
		assertEquals("fixture: a full pack sends you off to the bin",
			firstInLine.getKey(), chooseWorkingPatch(ordered).getKey());

		// A few items went in, so there is room to pick again - but the bin wants more.
		when(carried.getFreeSlots()).thenReturn(3);

		assertEquals("the bin is not finished, so it keeps you",
			firstInLine.getKey(), chooseWorkingPatch(ordered).getKey());
	}

	/** A full pack does not release it for work you can still do. */
	@Test
	public void afullPackKeepsTheLatchForNonHarvestWork() throws Exception
	{
		everyPatchOffersAHarvest();
		List<FarmPatch> ordered = guildPatches();
		FarmPatch beingWorked = ordered.get(ordered.size() - 1);
		setWorking(beingWorked);

		// Built before the stub: growingProjection mocks, and Mockito reads stubbing inside an
		// open when() as an unfinished one.
		com.dooglemaps.timer.PatchProjection growing = growingProjection(beingWorked);
		when(growthTimer.project(Mockito.eq(beingWorked), Mockito.any())).thenReturn(growing);
		when(carried.getFreeSlots()).thenReturn(0);

		FarmPatch chosen = chooseWorkingPatch(ordered);
		assertTrue("a growing patch offers nothing, so the latch falls through by the old rule",
			chosen == null || !chosen.getKey().equals(beingWorked.getKey()));
	}

	private FarmPatch chooseWorkingPatch(List<FarmPatch> ordered) throws Exception
	{
		RunStop stop = Mockito.mock(RunStop.class);
		when(stop.getRegion()).thenReturn(ordered.get(0).getRegion());
		when(stop.getPatches()).thenReturn(ordered);
		when(stop.getServiced()).thenReturn(new java.util.HashSet<>());

		Method method = GuideTracker.class.getDeclaredMethod(
			"chooseWorkingPatch", List.class, RunStop.class);
		method.setAccessible(true);
		try
		{
			return (FarmPatch) method.invoke(tracker, ordered, stop);
		}
		catch (java.lang.reflect.InvocationTargetException e)
		{
			throw new AssertionError(e.getCause());
		}
	}

	private void setWorking(FarmPatch patch) throws Exception
	{
		java.lang.reflect.Field field = GuideTracker.class.getDeclaredField("working");
		field.setAccessible(true);
		field.set(tracker, patch.getKey());
	}

	/**
	 * Makes one patch answer nothing, the way a finished detour does.
	 *
	 * <p>A growing patch offers no steps, which is what {@code outstandingFor} reads — the same
	 * shape a bin that has been filled and closed presents to {@code chooseWorkingPatch}.
	 */
	private void nothingOutstandingAt(FarmPatch patch)
	{
		com.dooglemaps.timer.PatchProjection growing = growingProjection(patch);
		when(growthTimer.project(Mockito.eq(patch), Mockito.any())).thenReturn(growing);
	}

	/**
	 * Every patch ripe, so every one of them answers a harvest and the order is what decides.
	 *
	 * <p>Built per patch rather than once: {@code GuidePlan.forPatch} takes the patch off the
	 * projection, so one shared mock hands it a null and the whole thing falls over.
	 */
	private void everyPatchOffersAHarvest()
	{
		when(growthTimer.project(Mockito.nullable(FarmPatch.class), Mockito.any()))
			.thenAnswer(invocation ->
			{
				FarmPatch asked = invocation.getArgument(0, FarmPatch.class);
				return asked == null ? null : ripeProjection(asked);
			});
	}

	private static com.dooglemaps.timer.PatchProjection ripeProjection(FarmPatch patch)
	{
		com.dooglemaps.timer.PatchProjection ripe =
			Mockito.mock(com.dooglemaps.timer.PatchProjection.class);
		when(ripe.getPatch()).thenReturn(patch);
		when(ripe.getCropState()).thenReturn(com.dooglemaps.data.CropState.HARVESTABLE);
		when(ripe.getProduce()).thenReturn(com.dooglemaps.data.Produce.WATERMELON);
		when(ripe.hasProduceToPick()).thenReturn(true);
		return ripe;
	}

	private static com.dooglemaps.timer.PatchProjection growingProjection(FarmPatch patch)
	{
		com.dooglemaps.timer.PatchProjection growing =
			Mockito.mock(com.dooglemaps.timer.PatchProjection.class);
		when(growing.getPatch()).thenReturn(patch);
		when(growing.getCropState()).thenReturn(com.dooglemaps.data.CropState.GROWING);
		when(growing.getProduce()).thenReturn(com.dooglemaps.data.Produce.WATERMELON);
		return growing;
	}

	/** Puts every bin into a given state, whatever the tracker asks the timer for. */
	private void binIs(com.dooglemaps.data.CropState state)
	{
		com.dooglemaps.timer.PatchProjection projection =
			Mockito.mock(com.dooglemaps.timer.PatchProjection.class);
		when(projection.getCropState()).thenReturn(state);
		when(growthTimer.project(Mockito.any(), Mockito.any())).thenReturn(projection);
	}

	/** The guild's patches with the bin at the back, as distance ordering could easily leave it. */
	private List<FarmPatch> binLast()
	{
		List<FarmPatch> ordered = new ArrayList<>(guildPatches());
		ordered.sort((a, b) -> Boolean.compare(
			a.getImplementation() == PatchImplementation.BIG_COMPOST,
			b.getImplementation() == PatchImplementation.BIG_COMPOST));
		assertFalse("fixture should not start with the bin",
			ordered.get(0).getImplementation() == PatchImplementation.BIG_COMPOST);
		return ordered;
	}

	private void sort(List<FarmPatch> ordered) throws Exception
	{
		Method binsFirst = GuideTracker.class.getDeclaredMethod("binsFirst", List.class);
		binsFirst.setAccessible(true);
		binsFirst.invoke(tracker, ordered);
	}

	/** Every guild patch this account uses, bin included. */
	private static List<FarmPatch> guildPatches()
	{
		List<FarmPatch> patches = new ArrayList<>();
		for (FarmPatch patch : FarmingWorldData.getAllPatches())
		{
			if (patch.getRegion().getRegionId() == ContractState.FARMING_GUILD_REGION)
			{
				patches.add(patch);
			}
		}
		assertFalse("no guild patches in the world data", patches.isEmpty());
		return patches;
	}

	private List<FarmPatch> offer(List<FarmPatch> ordered) throws Exception
	{
		RunStop stop = Mockito.mock(RunStop.class);
		com.dooglemaps.data.FarmRegion region = ordered.get(0).getRegion();
		when(stop.getRegion()).thenReturn(region);
		when(stop.getPatches()).thenReturn(ordered);

		Method method = GuideTracker.class.getDeclaredMethod(
			"contractComesFirst", RunStop.class, List.class);
		method.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<FarmPatch> offered = (List<FarmPatch>) method.invoke(tracker, stop, ordered);
		return offered;
	}

	/**
	 * A bin that is full stops asking to be fed, even while the projection lags behind.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * {@code fodderWantedBy} read the room from {@code projection.getStage()}.
	 * {@code CompostBinPlan}'s class note says why that cannot work — <i>"the projection exists to
	 * move a crop forward through time, and in doing so it flattens the two numbers a bin turns
	 * on: a FILLING bin's item count is not carried at all"</i> — so the number was frozen wherever
	 * the projection left it.
	 *
	 * <p>Caught in the log at the Farming Guild's big bin: <b>"room for 27"</b> on every line while
	 * the player filled it from 25 to 30, and still <b>"room for 27 -&gt; fill with 5982"</b> on
	 * the tick it was full and {@code CompostBinPlan} was already saying to close it. The fill step
	 * itself was always right; what was wrong is everything gated on <i>does this bin still want
	 * feeding</i> — {@code binHereWants} suppresses the leprechaun's note for a crop a bin waits
	 * on, and {@code binsFirst} hoists a hungry bin to the front of the stop. A full bin went on
	 * claiming both.
	 *
	 * <p>The fixture is that disagreement made explicit: a snapshot saying full, a projection
	 * saying nearly empty.
	 */
	@Test
	public void aFullBinStopsAskingToBeFed() throws Exception
	{
		FarmPatch bin = FarmingWorldData.getPatches(PatchImplementation.BIG_COMPOST).get(0);

		com.dooglemaps.timer.PatchProjection filling =
			Mockito.mock(com.dooglemaps.timer.PatchProjection.class);
		when(filling.getCropState()).thenReturn(com.dooglemaps.data.CropState.FILLING);
		// The projection's own stage, which does not carry a filling bin's item count.
		when(filling.getStage()).thenReturn(2);
		when(growthTimer.project(Mockito.eq(bin), Mockito.any())).thenReturn(filling);

		com.dooglemaps.state.PatchSnapshot snapshot =
			Mockito.mock(com.dooglemaps.state.PatchSnapshot.class);
		when(patchStore.get(bin)).thenReturn(snapshot);

		int watermelon = net.runelite.api.gameval.ItemID.WATERMELON;
		when(compostRun.isFodderEnabled()).thenReturn(true);
		when(compostRun.getFodderCrops()).thenReturn(
			new java.util.LinkedHashSet<>(java.util.Collections.singletonList(watermelon)));
		when(carried.getInventoryCount(watermelon)).thenReturn(6);
		when(carried.getFreeSlots()).thenReturn(10);

		// Thirty of thirty in it: nothing more will go in, whatever the projection thinks.
		when(snapshot.getStage()).thenReturn(29);
		assertEquals("a full bin wants nothing, even holding six watermelons",
			com.dooglemaps.state.CompostRunStore.NO_FILL, fodderWantedBy(bin));

		// And one short of full still does, so this has not simply switched the feature off.
		when(snapshot.getStage()).thenReturn(28);
		assertEquals("one short, and the watermelons are what it wants",
			watermelon, fodderWantedBy(bin));
	}

	/**
	 * A bin you are holding enough to <b>finish</b> is offered now, not after two more harvests.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * A bin awaiting fill sorts last, which is right in general — it is fed from the harvest, so
	 * offering it before the harvest happens produces no step. The exception was a full pack.
	 *
	 * <p>That missed the case reported from play at the Farming Guild: the big bin four short of
	 * full, six watermelons in the pack, seven free slots, and {@code FILL_BIN} sitting
	 * <b>fourth</b> behind planting an allotment and two more harvests. Every one of those is
	 * individually right and the sum is a bin left open with the crop that would close it in hand.
	 *
	 * <p>Finishing it specifically, not merely holding something it would take — "some fodder" is
	 * true almost continuously once a harvest starts, and hoisting on that would put the bin ahead
	 * of the harvest meant to feed it.
	 */
	@Test
	public void aBinYouCanFinishNowIsOfferedNow() throws Exception
	{
		FarmPatch bin = FarmingWorldData.getPatches(PatchImplementation.BIG_COMPOST).get(0);
		int watermelon = net.runelite.api.gameval.ItemID.WATERMELON;

		com.dooglemaps.timer.PatchProjection filling =
			Mockito.mock(com.dooglemaps.timer.PatchProjection.class);
		when(filling.getCropState()).thenReturn(com.dooglemaps.data.CropState.FILLING);
		when(growthTimer.project(Mockito.eq(bin), Mockito.any())).thenReturn(filling);
		com.dooglemaps.state.PatchSnapshot snapshot =
			Mockito.mock(com.dooglemaps.state.PatchSnapshot.class);
		when(patchStore.get(bin)).thenReturn(snapshot);

		when(compostRun.isFodderEnabled()).thenReturn(true);
		when(compostRun.getFodderCrops()).thenReturn(
			new java.util.LinkedHashSet<>(java.util.Collections.singletonList(watermelon)));
		// Room to keep harvesting, so the full-pack exception cannot be what carries this.
		when(carried.getFreeSlots()).thenReturn(7);
		// Twenty-six in it, so it wants four.
		when(snapshot.getStage()).thenReturn(25);

		when(carried.getInventoryCount(watermelon)).thenReturn(6);
		assertEquals("six will close a bin that wants four, so close it", -1, binRank(bin));

		when(carried.getInventoryCount(watermelon)).thenReturn(2);
		assertEquals("two will not, so it waits for the harvest that feeds it", 1, binRank(bin));
	}

	private int binRank(FarmPatch bin) throws Exception
	{
		Method method = GuideTracker.class.getDeclaredMethod("binRank", FarmPatch.class);
		method.setAccessible(true);
		return (int) method.invoke(tracker, bin);
	}

	private int fodderWantedBy(FarmPatch bin) throws Exception
	{
		Method method = GuideTracker.class.getDeclaredMethod("fodderWantedBy", FarmPatch.class);
		method.setAccessible(true);
		return (int) method.invoke(tracker, bin);
	}

	private GuideTracker trackerWith() throws Exception
	{
		Constructor<?> constructor = GuideTracker.class.getDeclaredConstructors()[0];
		constructor.setAccessible(true);

		Class<?>[] types = constructor.getParameterTypes();
		Object[] args = new Object[types.length];
		for (int i = 0; i < types.length; i++)
		{
			if (types[i] == ContractState.class)
			{
				args[i] = contracts;
			}
			else if (types[i] == RunTypeStore.class)
			{
				args[i] = runTypes;
			}
			else if (types[i] == DoogleMapsConfig.class)
			{
				args[i] = config;
			}
			else if (types[i] == com.dooglemaps.timer.GrowthTimer.class)
			{
				args[i] = growthTimer =
					Mockito.mock(com.dooglemaps.timer.GrowthTimer.class);
			}
			else if (types[i] == CarriedItems.class)
			{
				args[i] = carried = Mockito.mock(CarriedItems.class);
			}
			else if (types[i] == com.dooglemaps.state.CompostRunStore.class)
			{
				args[i] = compostRun =
					Mockito.mock(com.dooglemaps.state.CompostRunStore.class);
			}
			else if (types[i] == com.dooglemaps.state.CompostSelectionStore.class)
			{
				args[i] = compost =
					Mockito.mock(com.dooglemaps.state.CompostSelectionStore.class);
			}
			else if (types[i] == com.dooglemaps.state.PlantingGroups.class)
			{
				args[i] = groups = Mockito.mock(com.dooglemaps.state.PlantingGroups.class);
			}
			else if (types[i] == com.dooglemaps.state.PatchStateStore.class)
			{
				args[i] = patchStore =
					Mockito.mock(com.dooglemaps.state.PatchStateStore.class);
			}
			else
			{
				args[i] = Mockito.mock(types[i]);
			}
		}
		GuideTracker built = (GuideTracker) constructor.newInstance(args);
		assertNotNull(built);
		return built;
	}
}
