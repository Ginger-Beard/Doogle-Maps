package com.dooglemaps.route;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropXp;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.Seed;
import com.dooglemaps.timer.FarmingBonuses;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A harvest-only run is worth something, and it is worth the right thing.
 *
 * <p>It used to be worth nothing. The estimate started from the seeds picked for the run and
 * filled empty patches with them, and a harvest-only trip has no seed by definition — you are
 * going to pick fruit off trees that are already there. So it produced no lines, the panel saw an
 * empty selection and hid the table, and the run that had prompted the question was priced at
 * zero.
 *
 * <p>Nothing threw and no existing test noticed, because every one of them supplies a seed.
 */
public class HarvestEstimateTest
{
	private static final FarmingBonuses NONE = FarmingBonuses.NONE;

	@Test
	public void aHarvestOnlyRunIsPricedWithoutAnySeedSelection()
	{
		Map<Produce, Integer> ripe = Collections.singletonMap(Produce.BANANA, 3);

		RunEstimate estimate = RunEstimate.forHarvest(ripe, 75, NONE);

		assertEquals(1, estimate.getLines().size());
		assertTrue("three banana trees are worth more than nothing", estimate.getTotalXp() > 0);
		assertTrue(estimate.getTotalYield() > 0);
		assertEquals(3, estimate.getLines().get(0).getPatches());
	}

	/**
	 * Harvest experience only — no plant award, no check award.
	 *
	 * <p>This is most of the difference and the easy thing to get wrong. A banana tree's check is
	 * 1,750 experience against 10.5 per banana picked; counting the full cycle would have made a
	 * trip to pick fruit read as roughly twenty times what it pays.
	 */
	@Test
	public void onlyTheHarvestAwardIsCounted()
	{
		RunEstimate estimate = RunEstimate.forHarvest(
			Collections.singletonMap(Produce.BANANA, 1), 75, NONE);

		CropXp banana = CropXp.forSeed(Seed.forProduce(Produce.BANANA));
		double full = banana.totalFor(6);
		double harvestOnly = estimate.getTotalXp();

		assertTrue("the plant and check awards are still in there: " + harvestOnly + " of " + full,
			harvestOnly < full / 2);
		assertTrue(harvestOnly > 0);
	}

	/**
	 * And a full run over the same patches is worth much more, which is the whole distinction.
	 *
	 * <p>Comparing the two rather than asserting a number: the yield model moves with the level
	 * curve and the bonuses, and a hardcoded figure here would be testing the arithmetic against
	 * a copy of itself.
	 */
	@Test
	public void aFullRunOverTheSamePatchesIsWorthMore()
	{
		Seed banana = Seed.forProduce(Produce.BANANA);
		Map<PatchImplementation, Integer> patches =
			Collections.singletonMap(PatchImplementation.FRUIT_TREE, 3);
		Map<Seed, Integer> owned = new LinkedHashMap<>();
		owned.put(banana, 10);

		RunEstimate full = RunEstimate.forRun(patches, Collections.singleton(banana), owned, 75,
			NONE, CompostTier.NONE);
		RunEstimate harvest = RunEstimate.forHarvest(
			Collections.singletonMap(Produce.BANANA, 3), 75, NONE);

		assertTrue("planting and checking is where a fruit tree's experience is: "
			+ harvest.getTotalXp() + " vs " + full.getTotalXp(),
			full.getTotalXp() > harvest.getTotalXp());
	}

	/** Nothing growing means nothing to price, not a crash. */
	@Test
	public void nothingRipeIsAnEmptyEstimate()
	{
		RunEstimate estimate = RunEstimate.forHarvest(Collections.emptyMap(), 75, NONE);
		assertTrue(estimate.isEmpty());
		assertEquals(0, estimate.getTotalXp(), 0.0001);
	}

	/** A grown patch cannot be diseased, so nothing is discounted for it. */
	@Test
	public void nothingIsDiscountedForDisease()
	{
		RunEstimate estimate = RunEstimate.forHarvest(
			Collections.singletonMap(Produce.BANANA, 2), 75, NONE);
		assertEquals(1, estimate.getSurvivalChance(), 0.0001);
		assertEquals(1, estimate.getLines().get(0).getSurvivalChance(), 0.0001);
	}
}
