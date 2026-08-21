package com.dooglemaps.guide;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * When the "drop empty buckets" arrangement has to stand down.
 *
 * <p>The setting rests on a bucket being spent the moment it empties — composting a patch leaves
 * one behind and it does nothing for the rest of the run, so it is lit red with Drop under the
 * left click from the moment the run starts. A compost bin is the one place that is false: the
 * empties are what the compost is poured into, and marking them as litter put Drop on the very
 * items the next click consumes. Reported from play.
 *
 * <p>Tested on {@link GuideStatus} rather than on either caller, because both the inventory
 * overlay's red and {@code GuideMenuSwap}'s left-click ask this one question — a bucket lit red
 * whose left-click is not Drop, or the reverse, is worse than either behaviour alone.
 */
public class EmptyBucketsWantedTest
{
	private static final FarmPatch BIN =
		FarmingWorldData.getPatches(PatchImplementation.COMPOST).get(0);

	/** The ordinary case: nothing about buckets, so the standing arrangement holds. */
	@Test
	public void anOrdinaryStepLeavesTheArrangementAlone()
	{
		assertFalse(statusWith(GuideStep.of(GuideAction.HARVEST, BIN, "Pick the herbs."))
			.wantsEmptyBuckets());
	}

	/** Travelling between stops: no steps at all, and nothing to stand down for. */
	@Test
	public void anEmptyStepListLeavesTheArrangementAlone()
	{
		assertFalse(statusWith().wantsEmptyBuckets());
	}

	/** Pouring a bin into buckets is the case the whole test exists for. */
	@Test
	public void emptyingABinWantsTheBuckets()
	{
		assertTrue(statusWith(GuideStep.of(GuideAction.EMPTY_BIN, BIN, "Empty the bin."))
			.wantsEmptyBuckets());
	}

	/** The ash sits immediately before the first bucket, so it counts as the same moment. */
	@Test
	public void theAshStepWantsThemToo()
	{
		assertTrue(statusWith(GuideStep.withItem(GuideAction.APPLY_ASH, BIN,
			com.dooglemaps.data.CompostBin.VOLCANIC_ASH, "Use ash on the bin."))
			.wantsEmptyBuckets());
	}

	/** The guide fetching the buckets itself is the least ambiguous case of wanting them. */
	@Test
	public void fetchingBucketsWantsThem()
	{
		assertTrue(statusWith(GuideStep.atLeprechaun(GuideAction.WITHDRAW_TOOL, BIN,
			ItemID.BUCKET_EMPTY, null, "Withdraw 15 empty buckets."))
			.wantsEmptyBuckets());
	}

	/**
	 * Fetching something else does not.
	 *
	 * <p>The test that stops the rule being "any withdrawal at a bin stop" — a watering can or a
	 * spade collected from the same leprechaun says nothing about buckets.
	 */
	@Test
	public void fetchingAnotherToolDoesNot()
	{
		assertFalse(statusWith(GuideStep.atLeprechaun(GuideAction.WITHDRAW_TOOL, BIN,
			ItemID.SPADE, null, "Withdraw a spade."))
			.wantsEmptyBuckets());
	}

	/**
	 * Filling the bin back up does not, which is what puts the arrangement back.
	 *
	 * <p>By then the buckets really are spent: the compost is with the leprechaun and the bin
	 * wants produce. Standing down for the whole visit would have left them unmarked for the
	 * rest of the run.
	 */
	@Test
	public void fillingTheBinBackUpDoesNot()
	{
		assertFalse(statusWith(
			GuideStep.withItem(GuideAction.FILL_BIN, BIN, ItemID.PINEAPPLE, "Fill the bin."),
			GuideStep.of(GuideAction.CLOSE_BIN, BIN, "Close the bin."))
			.wantsEmptyBuckets());
	}

	/** One bin emptying anywhere in the list is enough - it need not be the current step. */
	@Test
	public void oneEmptyingStepAnywhereInTheListIsEnough()
	{
		assertTrue(statusWith(
			GuideStep.of(GuideAction.HARVEST, BIN, "Pick the herbs."),
			GuideStep.of(GuideAction.EMPTY_BIN, BIN, "Empty the bin."))
			.wantsEmptyBuckets());
	}

	private static GuideStatus statusWith(GuideStep... steps)
	{
		List<GuideStep> list = steps.length == 0
			? Collections.emptyList()
			: Arrays.asList(steps);
		return new GuideStatus(list, true, false, 1, Collections.emptyList(), null, null,
			Collections.emptyList(), null, null, Collections.emptyList(),
			Collections.emptyList(), null, Collections.emptyList(), Collections.emptySet(), null);
	}
}
