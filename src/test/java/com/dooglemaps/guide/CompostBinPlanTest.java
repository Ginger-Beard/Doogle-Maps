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
	}

	@Test
	public void ashGoesOnBeforeAnyBucketComesOut()
	{
		when(store.isAshing()).thenReturn(true);
		when(carried.getInventoryCount(CompostBin.VOLCANIC_ASH)).thenReturn(25);

		List<GuideStep> steps = steps(SUPER_READY_FULL);

		assertEquals("the ash is the whole first answer - emptying waits for the re-derive",
			1, steps.size());
		assertEquals(GuideAction.APPLY_ASH, steps.get(0).getAction());
		assertEquals(CompostBin.VOLCANIC_ASH, steps.get(0).getItemId());
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
	 * At an empty bin, the full buckets go to the leprechaun before the fill - the fill needs
	 * the slots they are sitting in.
	 */
	@Test
	public void fullBucketsAreDepositedBeforeTheBinIsRefilled()
	{
		when(store.getFills())
			.thenReturn(java.util.Collections.singletonList(ItemID.PINEAPPLE));
		when(carried.getInventoryCount(ItemID.BUCKET_ULTRACOMPOST)).thenReturn(15);
		when(carried.getInventoryCount(ItemID.PINEAPPLE)).thenReturn(15);

		List<GuideStep> steps = steps(EMPTY_BIN);

		assertEquals(2, steps.size());
		assertEquals("deposit first - see the pack-space note",
			GuideAction.DEPOSIT_COMPOST, steps.get(0).getAction());
		assertTrue("the deposit happens at the leprechaun", steps.get(0).isAtLeprechaun());
		assertEquals(GuideAction.FILL_BIN, steps.get(1).getAction());
		assertEquals(ItemID.PINEAPPLE, steps.get(1).getItemId());
	}

	/** Fill produce still in the bank gets silence, not an unperformable instruction. */
	@Test
	public void aFillYouAreNotCarryingGetsNoStep()
	{
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
		when(store.getFills())
			.thenReturn(java.util.Collections.singletonList(ItemID.PINEAPPLE));
		when(carried.getInventoryCount(ItemID.PINEAPPLE)).thenReturn(10);

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
		when(store.getFills())
			.thenReturn(java.util.Collections.singletonList(ItemID.PINEAPPLE));
		when(carried.getInventoryCount(ItemID.PINEAPPLE)).thenReturn(10);

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

	private List<GuideStep> steps(int varbitValue)
	{
		ProduceState decoded = bin.getImplementation().forVarbitValue(varbitValue);
		assertNotNull("varbit " + varbitValue + " decodes to nothing for a bin", decoded);

		PatchSnapshot snapshot = new PatchSnapshot();
		snapshot.setVarbitValue(varbitValue);
		snapshot.setProduce(decoded.getProduce());
		snapshot.setCropState(decoded.getCropState());
		snapshot.setStage(decoded.getStage());
		snapshot.setLastSeen(java.time.Instant.now().getEpochSecond());

		return CompostBinPlan.forBin(CompostBin.NORMAL, bin, snapshot, store, carried,
			leprechaun);
	}
}
