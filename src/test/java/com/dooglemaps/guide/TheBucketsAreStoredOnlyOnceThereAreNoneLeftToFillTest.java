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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Emptying a bin is not interrupted to hand the buckets over half way through.
 *
 * <h2>The reported dead end</h2>
 *
 * <i>"Shouldn't be prompted to drop buckets while I'm filling them for a compost run."</i> At the
 * guild's big bin the player withdrew a pack of empty buckets, put the ash on, and started
 * emptying. The first bucket came out full, and on that tick the step stopped being <b>empty the
 * bin</b> and became <b>store your buckets of compost with the tool leprechaun</b> — with
 * twenty-seven empties still in hand and twenty-nine buckets still in the bin.
 *
 * <p>The step came from {@code addBucketFetch}'s full-pack branch, which exists so a bin can be
 * emptied from a single free slot: fetch, fill, hand over, repeat. It read a pack with no room as
 * that deadlock. It is not one while empty buckets remain — an empty bucket becomes a bucket of
 * compost in the slot it already occupies — so the emptying runs to the last bucket without a
 * slot ever being freed.
 *
 * <p>The deposit the owner's order does want is the one that follows the emptying: full buckets to
 * the leprechaun before the bin is refilled. That step is raised by {@code addFillAndDeposit} off
 * an {@code EMPTY} bin, and the tests below pin that it is untouched.
 */
public class TheBucketsAreStoredOnlyOnceThereAreNoneLeftToFillTest
{
	/** Decoded bin states, by the varbit values PatchRules names. */
	private static final int EMPTY_BIN = 0;
	private static final int SUPER_READY_FULL = 62;
	private static final int SUPER_FILLING_EIGHT = 40;

	private FarmPatch bin;
	private CompostBin size;
	private CompostRunStore store;
	private CarriedItems carried;
	private LeprechaunStore leprechaun;

	@Before
	public void setUp()
	{
		bin = FarmingWorldData.getPatches(PatchImplementation.COMPOST).get(0);
		assertNotNull(bin);
		size = CompostBin.NORMAL;
		store = Mockito.mock(CompostRunStore.class);
		carried = Mockito.mock(CarriedItems.class);
		leprechaun = Mockito.mock(LeprechaunStore.class);
		when(leprechaun.has(FarmingTool.EMPTY_BUCKET)).thenReturn(true);
		// Every test here is about a pack with no room in it, which is the state the whole
		// emptying is done in: the empties fill the pack, and filling one changes nothing.
		when(carried.getFreeSlots()).thenReturn(0);
	}

	/**
	 * Mid emptying, with empties still in hand, the answer is still "empty the bin".
	 *
	 * <p>The reported case, one bucket in: fourteen empties left against fifteen still in the
	 * bin, and one filled bucket in the pack.
	 */
	@Test
	public void aBinStillBeingEmptiedIsNotAskedToStoreItsBuckets()
	{
		when(carried.getInventoryCount(ItemID.BUCKET_EMPTY)).thenReturn(14);
		when(carried.getInventoryCount(ItemID.BUCKET_SUPERCOMPOST)).thenReturn(1);

		List<GuideStep> steps = steps(SUPER_READY_FULL);

		assertEquals("emptying is the whole answer while a bucket is left to fill",
			1, steps.size());
		assertEquals(GuideAction.EMPTY_BIN, steps.get(0).getAction());
	}

	/**
	 * ...and it comes back the moment there is nothing left to fill.
	 *
	 * <p>Which is also the original deadlock this branch was written for: a full pack, no empty
	 * bucket in it, and a bin still holding compost. The only move is the leprechaun.
	 */
	@Test
	public void theStoreStepComesBackWhenNoEmptyBucketIsLeftToFill()
	{
		when(carried.getInventoryCount(ItemID.BUCKET_EMPTY)).thenReturn(0);
		when(carried.getInventoryCount(ItemID.BUCKET_SUPERCOMPOST)).thenReturn(15);

		List<GuideStep> steps = steps(SUPER_READY_FULL);

		assertEquals("the deposit is the whole answer - emptying waits for the re-derive",
			1, steps.size());
		assertEquals(GuideAction.DEPOSIT_COMPOST, steps.get(0).getAction());
	}

	/**
	 * An emptied bin hands the full buckets over, empties in the pack or not.
	 *
	 * <p>The wait is a property of the emptying, not of the buckets: once the bin is open and
	 * bare, the deposit is the owner's own next line — full buckets to the leprechaun, then the
	 * refill — and a few unused empties left over from the emptying do not hold it up.
	 */
	@Test
	public void anEmptiedBinStillHandsTheFullBucketsOver()
	{
		when(carried.getInventoryCount(ItemID.BUCKET_EMPTY)).thenReturn(3);
		when(carried.getInventoryCount(ItemID.BUCKET_SUPERCOMPOST)).thenReturn(15);

		assertTrue("an empty bin's deposit is untouched: " + steps(EMPTY_BIN),
			has(steps(EMPTY_BIN), GuideAction.DEPOSIT_COMPOST));
	}

	/** And so does a part-filled one, for the same reason: nothing is being emptied. */
	@Test
	public void aPartFilledBinIsUnaffectedByTheEmptiesInThePack()
	{
		when(carried.getInventoryCount(ItemID.BUCKET_EMPTY)).thenReturn(3);
		when(carried.getInventoryCount(ItemID.BUCKET_SUPERCOMPOST)).thenReturn(15);

		assertTrue("a filling bin's deposit is untouched: " + steps(SUPER_FILLING_EIGHT),
			has(steps(SUPER_FILLING_EIGHT), GuideAction.DEPOSIT_COMPOST));
	}

	private static boolean has(List<GuideStep> steps, GuideAction action)
	{
		for (GuideStep step : steps)
		{
			if (step.getAction() == action)
			{
				return true;
			}
		}
		return false;
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

		return CompostBinPlan.forBin(size, bin, snapshot, store, carried, leprechaun, false,
			java.util.Collections.emptyMap());
	}
}
