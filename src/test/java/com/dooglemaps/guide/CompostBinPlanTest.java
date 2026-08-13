package com.dooglemaps.guide;

import com.dooglemaps.data.CompostBin;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingTool;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.state.CompostRunStore;
import com.dooglemaps.state.LeprechaunStore;
import com.dooglemaps.state.PatchSnapshot;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * The clicks at a compost bin, in the order money and pack space dictate.
 *
 * <p>The one ordering that costs coins if it slips is pinned hardest: ash goes on a ready bin
 * of supercompost <b>before</b> any bucket comes out, because 25 ash upgrades the whole bin
 * where filled buckets cost 2 each. Everything else is the bucket dance the owner specified —
 * empties from the leprechaun, bin into buckets, full buckets back to him, fill, close.
 */
public class CompostBinPlanTest
{
	/** Decoded bin states, by the varbit values PatchRules names. */
	private static final int EMPTY_BIN = 0;
	private static final int SUPER_READY_FULL = 62;
	private static final int SUPER_FILLING_FULL = 47;
	private static final int SUPER_FILLING_EIGHT = 40;
	private static final int TOMATOES_READY = 144;
	/** Closed on supercompost and still composting - GROWING, whatever the clock says. */
	private static final int SUPER_COMPOSTING = 95;

	private FarmPatch bin;
	private CompostRunStore store;
	private CarriedItems carried;
	private LeprechaunStore leprechaun;

	@Before
	public void setUp()
	{
		bin = FarmingWorldData.getPatches(PatchImplementation.COMPOST).get(0);
		assertNotNull(bin);
		store = Mockito.mock(CompostRunStore.class);
		carried = Mockito.mock(CarriedItems.class);
		leprechaun = Mockito.mock(LeprechaunStore.class);
		// A pack with room in it, which is what every test below assumes unless it says
		// otherwise. A mock answers zero, and zero free slots now waives the fodder minimum -
		// see FODDER_MINIMUM_DIVISOR - so leaving it at the default would quietly turn the
		// floor off in the tests that exist to pin it.
		when(carried.getFreeSlots()).thenReturn(10);
	}

	@Test
	public void ashGoesOnBeforeAnyBucketComesOut()
	{
		when(store.isAshing()).thenReturn(true);
		when(carried.getInventoryCount(CompostBin.VOLCANIC_ASH)).thenReturn(25);
		// The leprechaun has no buckets to give, so he is not part of this bin's work at all.

		List<GuideStep> steps = steps(SUPER_READY_FULL);

		assertEquals("the ash is the whole first answer - emptying waits for the re-derive",
			1, steps.size());
		assertEquals(GuideAction.APPLY_ASH, steps.get(0).getAction());
		assertEquals(CompostBin.VOLCANIC_ASH, steps.get(0).getItemId());
	}

	/**
	 * The buckets the emptying will want are fetched on the way in, not on the way back.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"I've walked past the lep on my way to put volcanic ash in the compost bin, why didn't I
	 * grab empty buckets first."</i> The ash branch returned before the bucket logic, so the
	 * requirement was invisible until the ash was already in — and the answer arrived as a walk
	 * back to a leprechaun the player had just passed.
	 *
	 * <p>The follow-up was the interesting half: <i>"depending on where you tele in, you might not
	 * walk past the lep, then putting the ash on the bin first might make sense."</i> It does not,
	 * and this is the one ordering question at a bin that needs no geometry. Both orders have to
	 * END at the bin, so ash-first is always buckets-first plus the detour
	 * {@code d(entry,bin) + d(bin,lep) - d(entry,lep)}, which is never negative — there are no
	 * teleports inside a stop, so the distances are metric. Buckets-first is no worse from any
	 * tile the player can arrive on, which is what makes this fixable without the planner in
	 * {@code docs/stop-planner-spec.md}.
	 */
	@Test
	public void theBucketsAreFetchedBeforeTheAshRatherThanAfterIt()
	{
		when(store.isAshing()).thenReturn(true);
		when(carried.getInventoryCount(CompostBin.VOLCANIC_ASH)).thenReturn(25);
		when(leprechaun.has(FarmingTool.EMPTY_BUCKET)).thenReturn(true);

		List<GuideStep> steps = steps(SUPER_READY_FULL);

		assertEquals("the leprechaun trip is folded into the walk in", 2, steps.size());
		assertEquals(GuideAction.WITHDRAW_TOOL, steps.get(0).getAction());
		assertEquals(ItemID.BUCKET_EMPTY, steps.get(0).getItemId());
		assertEquals("and the ash still goes on before a single bucket leaves the bin",
			GuideAction.APPLY_ASH, steps.get(1).getAction());
	}

	/**
	 * ...and no trip is invented where the leprechaun was never needed.
	 *
	 * <p>The three cases where he is not part of a bin's work: a bottomless bucket takes the
	 * whole bin into one slot, rotten tomatoes come out by hand, and buckets already in the pack
	 * need no fetching. Those are precisely where ash-first is right, and they cost nothing
	 * because the fetch declines rather than because anything special-cases them.
	 */
	@Test
	public void bucketsAlreadyHeldDoNotSendYouToTheLeprechaunAtAll()
	{
		when(store.isAshing()).thenReturn(true);
		when(carried.getInventoryCount(CompostBin.VOLCANIC_ASH)).thenReturn(25);
		when(leprechaun.has(FarmingTool.EMPTY_BUCKET)).thenReturn(true);
		when(carried.getInventoryCount(ItemID.BUCKET_EMPTY)).thenReturn(4);

		List<GuideStep> steps = steps(SUPER_READY_FULL);

		assertEquals("nothing to fetch, so the ash is the whole answer", 1, steps.size());
		assertEquals(GuideAction.APPLY_ASH, steps.get(0).getAction());
	}

	/** Without the ash in the pack there is no ash instruction - just the emptying. */
	@Test
	public void missingAshFallsThroughToTheBuckets()
	{
		when(store.isAshing()).thenReturn(true);
		when(carried.getInventoryCount(CompostBin.VOLCANIC_ASH)).thenReturn(3);
		when(leprechaun.has(FarmingTool.EMPTY_BUCKET)).thenReturn(true);

		List<GuideStep> steps = steps(SUPER_READY_FULL);

		assertEquals("fetch the buckets, then empty", 2, steps.size());
		assertEquals(GuideAction.WITHDRAW_TOOL, steps.get(0).getAction());
		assertEquals(ItemID.BUCKET_EMPTY, steps.get(0).getItemId());
		assertEquals(GuideAction.EMPTY_BIN, steps.get(1).getAction());
	}

	/** A bottomless compost bucket collapses the whole dance to one click. */
	@Test
	public void aBottomlessBucketSkipsTheLeprechaunEntirely()
	{
		when(carried.hasAny(ItemID.BOTTOMLESS_COMPOST_BUCKET,
			ItemID.BOTTOMLESS_COMPOST_BUCKET_FILLED)).thenReturn(true);

		List<GuideStep> steps = steps(SUPER_READY_FULL);

		assertEquals(1, steps.size());
		assertEquals(GuideAction.EMPTY_BIN, steps.get(0).getAction());
		assertTrue("the instruction names the bottomless bucket",
			steps.get(0).getText().contains("bottomless"));
	}

	/** Carried empties mean no withdrawal - straight to the emptying. */
	@Test
	public void carriedEmptiesSkipTheWithdrawal()
	{
		when(carried.getInventoryCount(ItemID.BUCKET_EMPTY)).thenReturn(15);

		List<GuideStep> steps = steps(SUPER_READY_FULL);

		assertEquals(1, steps.size());
		assertEquals(GuideAction.EMPTY_BIN, steps.get(0).getAction());
	}

	/**
	 * A banked fill still hands the spare buckets over first.
	 *
	 * <p>You arrived carrying both; the buckets are clutter from the bank trip and the fill is
	 * the errand you made it for.
	 */
	@Test
	public void fullBucketsAreDepositedBeforeABankedRefill()
	{
		atTheGuildBin();
		when(store.getFills())
			.thenReturn(java.util.Collections.singletonList(ItemID.PINEAPPLE));
		when(carried.getInventoryCount(ItemID.BUCKET_ULTRACOMPOST)).thenReturn(15);
		when(carried.getInventoryCount(ItemID.PINEAPPLE)).thenReturn(15);

		List<GuideStep> steps = steps(EMPTY_BIN);

		assertEquals(2, steps.size());
		assertEquals(GuideAction.DEPOSIT_COMPOST, steps.get(0).getAction());
		assertTrue("the deposit happens at the leprechaun", steps.get(0).isAtLeprechaun());
		assertEquals(GuideAction.FILL_BIN, steps.get(1).getAction());
		assertEquals(ItemID.PINEAPPLE, steps.get(1).getItemId());
	}

	/**
	 * A fodder fill goes first, and the deposit follows it.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * The deposit used to come first unconditionally, and the reason given was that "the fill
	 * needs the slots they are sitting in". It does not — filling a bin takes items <b>out</b>
	 * of the pack and needs no room at all. The order was right for a banked fill for a
	 * different reason (the buckets really are clutter from the bank trip) and wrong here.
	 *
	 * <p>What it cost: at the Farming Guild you are always carrying ultracompost, because it is
	 * what you are treating the patches with. So the deposit always fired, always came first,
	 * and always pointed at the leprechaun — and the player harvesting watermelons into a full
	 * pack was told to store their compost while "put these in the bin" sat behind it and never
	 * became the current step. Reported three times as the bin not being offered; the bin
	 * decision log eventually showed it answering "fill with 5982" while the panel said
	 * something else entirely.
	 */
	@Test
	public void aFodderFillComesBeforeTheDeposit()
	{
		atTheGuildBin();
		fodder(ItemID.WATERMELON);
		when(carried.getInventoryCount(ItemID.BUCKET_ULTRACOMPOST)).thenReturn(4);
		when(carried.getInventoryCount(ItemID.WATERMELON)).thenReturn(14);
		when(carried.getFreeSlots()).thenReturn(0);

		List<GuideStep> steps = steps(EMPTY_BIN);

		assertEquals(2, steps.size());
		assertEquals("the crop with nowhere to go is the errand",
			GuideAction.FILL_BIN, steps.get(0).getAction());
		assertEquals(ItemID.WATERMELON, steps.get(0).getItemId());
		assertEquals("and the buckets still get handed over, right after",
			GuideAction.DEPOSIT_COMPOST, steps.get(1).getAction());
	}

	/**
	 * ...but never the buckets the patches at this stop are about to be treated with.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"when I'm foddering the bin I'm told to go deposit my compost back at the lep before I
	 * use them... we're wasting a lot of time going back and forth to the lep"</i>. The step
	 * order from the session log, with the bin at 4775 and the patches around it:
	 *
	 * <pre>
	 * 6192.4774=WITHDRAW_COMPOST; 6192.4774=APPLY_COMPOST; 6192.4772=HARVEST;
	 * 6192.4771=HARVEST; 6192.4775=DEPOSIT_COMPOST; 6192.4773=HARVEST;
	 * </pre>
	 *
	 * Withdraw four, use one, store the other three — with three allotments still to be picked
	 * that each want one. The test above has the right instinct in its own javadoc and stops one
	 * step short: reordering the deposit does not help when the deposit should not happen.
	 *
	 * <p>The deposit is for compost with nowhere to go, which is what comes <i>out of</i> a bin.
	 * Compost withdrawn a minute ago for the patches around it is not that.
	 */
	@Test
	public void compostThisStopIsAboutToUseIsNotDepositedFirst()
	{
		atTheGuildBin();
		fodder(ItemID.WATERMELON);
		when(carried.getInventoryCount(ItemID.BUCKET_ULTRACOMPOST)).thenReturn(4);
		when(carried.getInventoryCount(ItemID.WATERMELON)).thenReturn(14);
		when(carried.getFreeSlots()).thenReturn(0);

		// All four are spoken for by the patches at this stop.
		List<GuideStep> steps = steps(EMPTY_BIN, false,
			java.util.Collections.singletonMap(ItemID.BUCKET_ULTRACOMPOST, 4));

		assertEquals("the crop still goes in; nothing else is asked for", 1, steps.size());
		assertEquals(GuideAction.FILL_BIN, steps.get(0).getAction());
	}

	/** And the surplus above what the stop wants still goes back — this is a trim, not a veto. */
	@Test
	public void onlyTheCompostBeyondWhatTheStopWantsIsDeposited()
	{
		atTheGuildBin();
		fodder(ItemID.WATERMELON);
		when(carried.getInventoryCount(ItemID.BUCKET_ULTRACOMPOST)).thenReturn(9);
		when(carried.getInventoryCount(ItemID.WATERMELON)).thenReturn(14);
		when(carried.getFreeSlots()).thenReturn(0);

		List<GuideStep> steps = steps(EMPTY_BIN, false,
			java.util.Collections.singletonMap(ItemID.BUCKET_ULTRACOMPOST, 4));

		assertEquals(2, steps.size());
		assertEquals(GuideAction.FILL_BIN, steps.get(0).getAction());
		assertEquals(GuideAction.DEPOSIT_COMPOST, steps.get(1).getAction());
		assertTrue("the step should offer the five spare, not all nine: "
				+ steps.get(1).getText(),
			steps.get(1).getText().contains("5 buckets"));
	}

	/** Fill produce still in the bank gets silence, not an unperformable instruction. */
	@Test
	public void aFillYouAreNotCarryingGetsNoStep()
	{
		atTheGuildBin();
		when(store.getFills())
			.thenReturn(java.util.Collections.singletonList(ItemID.PINEAPPLE));
		when(carried.getInventoryCount(ItemID.PINEAPPLE)).thenReturn(0);

		assertTrue(steps(EMPTY_BIN).isEmpty());
	}

	/**
	 * Holding less than a bin wants gets an honest partial fill, not "fill the bin".
	 *
	 * <p>Nothing supercompostable stacks and a bin refuses notes, so fifteen items is fifteen
	 * slots — and the big bin's thirty cannot fit in a twenty-eight slot inventory at all.
	 * "Fill the bin" to someone holding ten ended halfway through with no word about why.
	 */
	@Test
	public void aShortFillSaysHowShortItIs()
	{
		fodder(ItemID.POTATO);
		when(carried.getInventoryCount(ItemID.POTATO)).thenReturn(10);

		List<GuideStep> steps = steps(EMPTY_BIN);

		assertEquals(1, steps.size());
		assertEquals(GuideAction.FILL_BIN, steps.get(0).getAction());
		assertTrue("names what can go in: " + steps.get(0).getText(),
			steps.get(0).getText().contains("Add your 10"));
		assertTrue("and how far short it leaves you: " + steps.get(0).getText(),
			steps.get(0).getText().contains("5 short"));
	}

	/**
	 * The queue spills at the next BIN, never within one.
	 *
	 * <p>A bin filled with anything not entirely supercompostable makes ordinary compost, so
	 * running out of pineapples half way through and topping up with potatoes would quietly
	 * cost the tier. With no pineapples carried the step reaches straight past them to the
	 * next fill and puts a whole bin of that in.
	 */
	@Test
	public void theQueueSpillsToTheNextFillWhenTheFirstIsNotCarried()
	{
		atTheGuildBin();
		when(store.getFills()).thenReturn(
			java.util.Arrays.asList(ItemID.PINEAPPLE, ItemID.WATERMELON));
		when(carried.getInventoryCount(ItemID.PINEAPPLE)).thenReturn(0);
		when(carried.getInventoryCount(ItemID.WATERMELON)).thenReturn(15);

		List<GuideStep> steps = steps(EMPTY_BIN);

		assertEquals(1, steps.size());
		assertEquals(GuideAction.FILL_BIN, steps.get(0).getAction());
		assertEquals("the second fill fills this bin whole",
			ItemID.WATERMELON, steps.get(0).getItemId());
	}

	/** ...and the first fill wins whenever it is actually in the pack. */
	@Test
	public void theFirstCarriedFillIsTheOneUsed()
	{
		atTheGuildBin();
		when(store.getFills()).thenReturn(
			java.util.Arrays.asList(ItemID.PINEAPPLE, ItemID.WATERMELON));
		when(carried.getInventoryCount(ItemID.PINEAPPLE)).thenReturn(15);
		when(carried.getInventoryCount(ItemID.WATERMELON)).thenReturn(15);

		List<GuideStep> steps = steps(EMPTY_BIN);

		assertEquals(ItemID.PINEAPPLE, steps.get(0).getItemId());
	}

	/** The big bin's thirty never fit one inventory, which is a fact worth having in the data. */
	@Test
	public void theBigBinCannotBeFilledInOneTrip()
	{
		assertTrue("a normal bin's fifteen fit fine", CompostBin.NORMAL.fitsOneInventory());
		assertFalse("thirty un-noted items against twenty-eight slots",
			CompostBin.BIG.fitsOneInventory());
	}

	@Test
	public void aFullOpenBinGetsTheCloseInstruction()
	{
		List<GuideStep> steps = steps(SUPER_FILLING_FULL);

		assertEquals(1, steps.size());
		assertEquals("a full open bin composts nothing, forever",
			GuideAction.CLOSE_BIN, steps.get(0).getAction());
	}

	@Test
	public void aPartFilledBinAsksForTheTopUp()
	{
		fodder(ItemID.POTATO);
		when(carried.getInventoryCount(ItemID.POTATO)).thenReturn(10);

		List<GuideStep> steps = steps(SUPER_FILLING_EIGHT);

		assertEquals(1, steps.size());
		assertEquals(GuideAction.FILL_BIN, steps.get(0).getAction());
		assertTrue("says how many more it takes", steps.get(0).getText().contains("7 more"));
	}

	/** Rotten tomatoes come out by hand, and the step says what this fill was worth. */
	@Test
	public void rottenTomatoesNeedNoBuckets()
	{
		List<GuideStep> steps = steps(TOMATOES_READY);

		assertEquals(1, steps.size());
		assertEquals(GuideAction.EMPTY_BIN, steps.get(0).getAction());
		assertTrue(steps.get(0).getText().contains("rotten tomatoes"));
	}

	/** A closed bin still composting wants nothing at all. */
	@Test
	public void aCompostingBinIsLeftAlone()
	{
		assertTrue(steps(31).isEmpty());
	}

	/**
	 * The same closed bin once the clock says it is done.
	 *
	 * <p>The state the whole {@code clockReady} parameter exists for. A bin is marked
	 * health-check-required, so {@code GrowthTimer} never promotes it out of {@code GROWING} —
	 * and {@code RunPlanner} routes the player to it anyway, on the same {@code isReady()} this
	 * passes in. Silence here was the run stopping at a bin with nothing to say.
	 */
	@Test
	public void aFinishedBinIsEmptiedEvenWhileItsVarbitSaysGrowing()
	{
		when(leprechaun.has(FarmingTool.EMPTY_BUCKET)).thenReturn(true);

		List<GuideStep> steps = steps(SUPER_COMPOSTING, true);

		assertEquals("fetch the buckets, then empty", 2, steps.size());
		assertEquals(GuideAction.WITHDRAW_TOOL, steps.get(0).getAction());
		assertEquals(GuideAction.EMPTY_BIN, steps.get(1).getAction());
	}

	/**
	 * And it is priced as a whole bin, not off the composting stage.
	 *
	 * <p>While {@code GROWING} the decoded stage counts composting progress, so reading it as a
	 * bucket count would promise one bucket out of a bin holding fifteen.
	 */
	@Test
	public void aFinishedBinCountsAWholeBinRatherThanItsCompostingStage()
	{
		List<GuideStep> steps = steps(SUPER_COMPOSTING, true);

		assertEquals(1, steps.size());
		assertTrue("says a full bin, not the stage: " + steps.get(0).getText(),
			steps.get(0).getText().contains(String.valueOf(CompostBin.NORMAL.getCapacity())));
	}

	/** The ash still goes on first, which is the whole reason the order is pinned. */
	@Test
	public void aFinishedBinStillTakesItsAshBeforeAnyBucket()
	{
		when(store.isAshing()).thenReturn(true);
		when(carried.getInventoryCount(CompostBin.VOLCANIC_ASH)).thenReturn(25);

		List<GuideStep> steps = steps(SUPER_COMPOSTING, true);

		assertEquals(1, steps.size());
		assertEquals(GuideAction.APPLY_ASH, steps.get(0).getAction());
	}

	/**
	 * A pack with no room hands the full buckets over rather than asking for more empties.
	 *
	 * <p>The bin has to be emptiable from one free slot - fetch a bucket, fill it, hand it over,
	 * repeat. Without this the guide asked for another empty bucket with nowhere to put it and
	 * the bin deadlocked, since the only thing that would free a slot was the step it never gave.
	 */
	@Test
	public void aFullPackDepositsTheCompostRatherThanAskingForMoreBuckets()
	{
		when(leprechaun.has(FarmingTool.EMPTY_BUCKET)).thenReturn(true);
		when(carried.getFreeSlots()).thenReturn(0);
		when(carried.getInventoryCount(ItemID.BUCKET_SUPERCOMPOST)).thenReturn(1);

		List<GuideStep> steps = steps(SUPER_READY_FULL);

		assertEquals("the deposit is the whole answer - emptying waits for the re-derive",
			1, steps.size());
		assertEquals(GuideAction.DEPOSIT_COMPOST, steps.get(0).getAction());
	}

	/**
	 * With room again it goes straight back to fetching, and takes only what fits.
	 *
	 * <p>The other half of the same rule: the deposit is a way out of a full pack, not a step
	 * that displaces the buckets whenever any compost is held.
	 */
	@Test
	public void roomForOneBucketFetchesOne()
	{
		when(leprechaun.has(FarmingTool.EMPTY_BUCKET)).thenReturn(true);
		when(carried.getFreeSlots()).thenReturn(1);
		when(carried.getInventoryCount(ItemID.BUCKET_SUPERCOMPOST)).thenReturn(1);

		List<GuideStep> steps = steps(SUPER_READY_FULL);

		assertEquals(2, steps.size());
		assertEquals(GuideAction.WITHDRAW_TOOL, steps.get(0).getAction());
		assertTrue("one slot, one bucket: " + steps.get(0).getText(),
			steps.get(0).getText().contains("1 empty bucket"));
		assertEquals(GuideAction.EMPTY_BIN, steps.get(1).getAction());
	}

	private List<GuideStep> steps(int varbitValue)
	{
		return steps(varbitValue, false);
	}

	private List<GuideStep> steps(int varbitValue, boolean clockReady)
	{
		return steps(varbitValue, clockReady, java.util.Collections.emptyMap());
	}

	/**
	 * @param compostToKeep buckets this stop is about to use, which the deposit step leaves
	 *                      alone. Empty for every test that predates it — the old behaviour is
	 *                      exactly "keep nothing back".
	 */
	private List<GuideStep> steps(int varbitValue, boolean clockReady,
		java.util.Map<Integer, Integer> compostToKeep)
	{
		ProduceState decoded = bin.getImplementation().forVarbitValue(varbitValue);
		assertNotNull("varbit " + varbitValue + " decodes to nothing for a bin", decoded);

		PatchSnapshot snapshot = new PatchSnapshot();
		snapshot.setVarbitValue(varbitValue);
		snapshot.setProduce(decoded.getProduce());
		snapshot.setCropState(decoded.getCropState());
		snapshot.setStage(decoded.getStage());
		snapshot.setLastSeen(java.time.Instant.now().getEpochSecond());

		return CompostBinPlan.forBin(size, bin, snapshot, store, carried, leprechaun, clockReady,
			compostToKeep);
	}

	// ------------------------------------------- fed from the harvest

	/**
	 * A bin beside the allotments is filled from what you just picked.
	 *
	 * <p>The whole point of the split. There is no bank near any of the seven — Falador ~65
	 * tiles, Ardougne ~96, and three of them none the plugin ships at all — so carrying fifteen
	 * un-noted items to one was a farm run spent with a full pack. The allotments that feed it
	 * are a few tiles away and share its stop.
	 */
	@Test
	public void anAllotmentBinTakesTheHarvestInYourPack()
	{
		fodder(ItemID.WATERMELON);
		when(carried.getInventoryCount(ItemID.WATERMELON)).thenReturn(15);

		List<GuideStep> steps = steps(EMPTY_BIN);

		assertEquals(1, steps.size());
		assertEquals(GuideAction.FILL_BIN, steps.get(0).getAction());
		assertEquals(ItemID.WATERMELON, steps.get(0).getItemId());
	}

	/** And it has no bank queue to fall back on, however much is picked for the guild's bin. */
	@Test
	public void anAllotmentBinIgnoresTheBankQueue()
	{
		when(store.getFills())
			.thenReturn(java.util.Collections.singletonList(ItemID.PINEAPPLE));
		when(carried.getInventoryCount(ItemID.PINEAPPLE)).thenReturn(15);

		assertTrue("the queue is the guild bin's, and this is not it",
			steps(EMPTY_BIN).isEmpty());
	}

	/** A crop left off the list is never binned, however much of it is in the pack. */
	@Test
	public void aCropYouDidNotAllowIsNeverBinned()
	{
		fodder(ItemID.WATERMELON);
		when(carried.getInventoryCount(ItemID.SNAPE_GRASS)).thenReturn(30);

		assertTrue("snape grass is worth more than compost", steps(EMPTY_BIN).isEmpty());
	}

	/** Switching fodder off keeps the picks and stops acting on them. */
	@Test
	public void fodderOffMeansNoHarvestFill()
	{
		when(store.isFodderEnabled()).thenReturn(false);
		when(store.getFodderCrops())
			.thenReturn(java.util.Collections.singleton(ItemID.WATERMELON));
		when(carried.getInventoryCount(ItemID.WATERMELON)).thenReturn(15);

		assertTrue(steps(EMPTY_BIN).isEmpty());
	}

	/**
	 * The tier beats the bigger stack.
	 *
	 * <p>A bin filled entirely with supercompostables makes supercompost; one grain of anything
	 * else and the whole bin is ordinary. So twenty potatoes against fifteen watermelons is not
	 * a close call — the four items of headroom are worth nothing beside the tier.
	 */
	@Test
	public void aSupercompostableBeatsALargerOrdinaryStack()
	{
		fodder(ItemID.POTATO, ItemID.WATERMELON);
		when(carried.getInventoryCount(ItemID.POTATO)).thenReturn(20);
		when(carried.getInventoryCount(ItemID.WATERMELON)).thenReturn(15);

		List<GuideStep> steps = steps(EMPTY_BIN);

		assertEquals(1, steps.size());
		assertEquals(ItemID.WATERMELON, steps.get(0).getItemId());
	}

	/** With no supercompostable able to fill it alone, the biggest stack wins instead. */
	@Test
	public void otherwiseTheLargestStackWins()
	{
		fodder(ItemID.POTATO, ItemID.WATERMELON);
		when(carried.getInventoryCount(ItemID.POTATO)).thenReturn(12);
		when(carried.getInventoryCount(ItemID.WATERMELON)).thenReturn(6);

		List<GuideStep> steps = steps(EMPTY_BIN);

		assertEquals(1, steps.size());
		assertEquals("the tier is lost either way, so take the fuller one",
			ItemID.POTATO, steps.get(0).getItemId());
	}

	/**
	 * A handful is not worth a step.
	 *
	 * <p>A patch can give you two of something. "Put 2 watermelons in the bin" costs a click and
	 * a line to move a bin a fifteenth of the way, and a bin keeps its contents between runs, so
	 * there is no urgency. A third of the bin is the floor.
	 */
	@Test
	public void aTokenAmountOfFodderIsNotOffered()
	{
		fodder(ItemID.WATERMELON);
		when(carried.getInventoryCount(ItemID.WATERMELON)).thenReturn(4);

		assertTrue(steps(EMPTY_BIN).isEmpty());
	}

	/**
	 * A full pack waives the floor, because then the bin is the only way to carry on.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * The floor exists so a stray two watermelons do not produce a step, and that argument is
	 * about a pack with room in it. With no room the bin is not a tidy-up: eight watermelons
	 * into it is eight slots back and the harvest continues.
	 *
	 * <p>Reported from play at the Farming Guild — pack full mid watermelon harvest, an empty big
	 * bin standing beside the allotment, and the guide asking for more watermelons. The big bin
	 * makes it sharper than the small ones: a third of thirty is ten, and one allotment rarely
	 * clears that.
	 */
	@Test
	public void aFullPackIsOfferedTheBinHoweverLittleItHolds()
	{
		atTheGuildBin();
		fodder(ItemID.WATERMELON);
		when(carried.getInventoryCount(ItemID.WATERMELON)).thenReturn(4);
		when(carried.getFreeSlots()).thenReturn(0);

		List<GuideStep> steps = steps(EMPTY_BIN);

		assertEquals(1, steps.size());
		assertEquals(GuideAction.FILL_BIN, steps.get(0).getAction());
		assertEquals(ItemID.WATERMELON, steps.get(0).getItemId());
	}

	/**
	 * A fill already under way is never abandoned, however little is left in the pack.
	 *
	 * <h2>The reported dead end, one release after the last one</h2>
	 *
	 * Waiving the floor for a full pack got the bin offered. Then putting the first melon in
	 * <b>frees a slot</b> — which re-imposed the floor, took the fill step away mid fill, and
	 * sent the player off to another patch with the bin standing open. Reported as "I'm being
	 * routed to a different patch mid compost bin fill up".
	 *
	 * <p>The floor is about whether a fill is worth <i>starting</i>. A part-filled bin is not
	 * that question: stopping halfway is worse than a token fill ever was.
	 */
	@Test
	public void aFillAlreadyUnderWayIsNotAbandoned()
	{
		atTheGuildBin();
		fodder(ItemID.WATERMELON);
		// Four melons left and room to hold them: below the floor of ten for a thirty-item bin.
		when(carried.getInventoryCount(ItemID.WATERMELON)).thenReturn(4);
		when(carried.getFreeSlots()).thenReturn(6);

		// Varbit 3 on the big bin: part filled, which is what the player is standing over.
		List<GuideStep> steps = steps(3);

		assertFalse("the bin is mid fill and still wants them", steps.isEmpty());
		assertEquals(GuideAction.FILL_BIN, steps.get(0).getAction());
		assertEquals(ItemID.WATERMELON, steps.get(0).getItemId());
	}

	/** With one slot left the floor is back, so a token fill is still not worth the click. */
	@Test
	public void oneFreeSlotIsNotAFullPack()
	{
		atTheGuildBin();
		fodder(ItemID.WATERMELON);
		when(carried.getInventoryCount(ItemID.WATERMELON)).thenReturn(4);
		when(carried.getFreeSlots()).thenReturn(1);

		assertTrue("four of a thirty-item bin, with somewhere to put the next pick",
			steps(EMPTY_BIN).isEmpty());
	}

	/** The floor applies to fodder only - a banked fill was withdrawn on purpose. */
	@Test
	public void aBankedFillHasNoMinimum()
	{
		atTheGuildBin();
		when(store.getFills())
			.thenReturn(java.util.Collections.singletonList(ItemID.PINEAPPLE));
		when(carried.getInventoryCount(ItemID.PINEAPPLE)).thenReturn(4);

		List<GuideStep> steps = steps(EMPTY_BIN);

		assertEquals(1, steps.size());
		assertEquals(ItemID.PINEAPPLE, steps.get(0).getItemId());
	}

	/** The guild's bin prefers the harvest and keeps the bank as the fallback. */
	@Test
	public void theGuildBinPrefersFodderOverItsQueue()
	{
		atTheGuildBin();
		fodder(ItemID.WATERMELON);
		when(store.getFills())
			.thenReturn(java.util.Collections.singletonList(ItemID.PINEAPPLE));
		when(carried.getInventoryCount(ItemID.WATERMELON)).thenReturn(30);
		when(carried.getInventoryCount(ItemID.PINEAPPLE)).thenReturn(30);

		List<GuideStep> steps = steps(EMPTY_BIN);

		assertEquals(1, steps.size());
		assertEquals("free beats banked", ItemID.WATERMELON, steps.get(0).getItemId());
	}

	/**
	 * Which bin the next {@code steps(...)} call is about, and it moves with {@link #bin}.
	 *
	 * <p>They have to: the capacity and the ash price come from this, and the varbit decode comes
	 * from the patch, so a big {@code CompostBin} against a small patch reads a fifteen-item bin
	 * as holding thirty. Default is the ordinary one beside the allotments.
	 */
	private CompostBin size = CompostBin.NORMAL;

	/** Moves both to the Farming Guild's bin, the only one with a bank queue behind it. */
	private void atTheGuildBin()
	{
		bin = FarmingWorldData.getPatches(PatchImplementation.BIG_COMPOST).get(0);
		assertNotNull(bin);
		size = CompostBin.BIG;
	}

	/**
	 * Allows these crops to be fed to a bin out of the pack.
	 *
	 * <p>The ordinary way a bin is filled now: the seven beside the allotments have no other, and
	 * the guild's prefers it. See {@code CompostRunStore} for why this is a permission set rather
	 * than the queue.
	 */
	private void fodder(int... itemIds)
	{
		java.util.Set<Integer> crops = new java.util.LinkedHashSet<>();
		for (int itemId : itemIds)
		{
			crops.add(itemId);
		}
		when(store.isFodderEnabled()).thenReturn(true);
		when(store.getFodderCrops()).thenReturn(crops);
	}
}
