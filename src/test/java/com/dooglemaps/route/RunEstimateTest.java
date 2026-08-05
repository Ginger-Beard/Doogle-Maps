package com.dooglemaps.route;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropXp;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Seed;
import com.dooglemaps.timer.FarmingBonuses;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Covers the whole-run experience and yield summary.
 *
 * <p>The part worth testing is the seed allocation. A run is limited by what you own, not by
 * how many patches there are, so the estimate has to spread the picked seeds the way a player
 * would — best first, until they run out — rather than multiplying one seed by the patch count
 * and quietly assuming infinite stock.
 */
public class RunEstimateTest
{
	private static Map<PatchImplementation, Integer> patches(Object... pairs)
	{
		Map<PatchImplementation, Integer> map = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
		{
			map.put((PatchImplementation) pairs[i], (Integer) pairs[i + 1]);
		}
		return map;
	}

	private static Set<Seed> seeds(Seed... picked)
	{
		return new LinkedHashSet<>(java.util.Arrays.asList(picked));
	}

	private static Map<Seed, Integer> owned(Object... pairs)
	{
		Map<Seed, Integer> map = new EnumMap<>(Seed.class);
		for (int i = 0; i < pairs.length; i += 2)
		{
			map.put((Seed) pairs[i], (Integer) pairs[i + 1]);
		}
		return map;
	}

	@Test
	public void addsUpEveryPatchTheRunWillVisit()
	{
		RunEstimate estimate = RunEstimate.forRun(
			patches(PatchImplementation.HERB, 5),
			seeds(Seed.RANARR),
			owned(Seed.RANARR, 100),
			99, FarmingBonuses.NONE);

		assertEquals(1, estimate.getLines().size());
		assertEquals(5, estimate.getLines().get(0).getPatches());

		// Five patches of the same crop is five times one patch, and one patch is the figure
		// the row tooltip already shows.
		double onePatch = estimate.getTotalXp() / 5;
		assertTrue("a ranarr patch is worth more than planting alone",
			onePatch > CropXp.forSeed(Seed.RANARR).getPlantXp());
		assertEquals(estimate.getTotalXp(), onePatch * 5, 0.001);
	}

	/**
	 * Stock, not patch count, is what limits a run.
	 *
	 * <p>Four ranarr seeds across eight herb patches means four ranarr patches and four of
	 * something else — the case the whole allocation exists for.
	 */
	@Test
	public void spreadsTheBestSeedsFirstAndFallsBack()
	{
		RunEstimate estimate = RunEstimate.forRun(
			patches(PatchImplementation.HERB, 8),
			seeds(Seed.RANARR, Seed.GUAM),
			owned(Seed.RANARR, 4, Seed.GUAM, 100),
			99, FarmingBonuses.NONE);

		Map<Seed, Integer> filled = new EnumMap<>(Seed.class);
		estimate.getLines().forEach(l -> filled.merge(l.getSeed(), l.getPatches(), Integer::sum));

		assertEquals("only four ranarr seeds exist", Integer.valueOf(4), filled.get(Seed.RANARR));
		assertEquals("guam fills the rest", Integer.valueOf(4), filled.get(Seed.GUAM));
		assertEquals(0, estimate.getUnfilledPatches());
	}

	/** Ranarr is worth more than guam, so it must be the one that gets planted first. */
	@Test
	public void theBetterSeedGoesInFirst()
	{
		RunEstimate estimate = RunEstimate.forRun(
			patches(PatchImplementation.HERB, 3),
			seeds(Seed.GUAM, Seed.RANARR),
			owned(Seed.GUAM, 100, Seed.RANARR, 1),
			99, FarmingBonuses.NONE);

		Map<Seed, Integer> filled = new EnumMap<>(Seed.class);
		estimate.getLines().forEach(l -> filled.merge(l.getSeed(), l.getPatches(), Integer::sum));

		assertEquals("the single ranarr should be used, not skipped",
			Integer.valueOf(1), filled.get(Seed.RANARR));
		assertEquals(Integer.valueOf(2), filled.get(Seed.GUAM));
	}

	/** Patches with nothing to put in them are reported rather than silently dropped. */
	@Test
	public void countsPatchesNoSeedCanFill()
	{
		RunEstimate estimate = RunEstimate.forRun(
			patches(PatchImplementation.HERB, 6),
			seeds(Seed.RANARR),
			owned(Seed.RANARR, 2),
			99, FarmingBonuses.NONE);

		assertEquals(2, estimate.getLines().get(0).getPatches());
		assertEquals(4, estimate.getUnfilledPatches());
	}

	/** An allotment takes three seeds, so stock does not map one-to-one onto patches. */
	@Test
	public void respectsHowManySeedsAPatchTakes()
	{
		RunEstimate estimate = RunEstimate.forRun(
			patches(PatchImplementation.ALLOTMENT, 4),
			seeds(Seed.WATERMELON),
			owned(Seed.WATERMELON, 7),
			99, FarmingBonuses.NONE);

		assertEquals("seven seeds fill two allotments, not seven",
			2, estimate.getLines().get(0).getPatches());
		assertEquals(2, estimate.getUnfilledPatches());
	}

	/** A seed you cannot plant yet must not be counted as filling anything. */
	@Test
	public void ignoresSeedsAboveYourLevel()
	{
		RunEstimate estimate = RunEstimate.forRun(
			patches(PatchImplementation.HERB, 3),
			seeds(Seed.TORSTOL),
			owned(Seed.TORSTOL, 100),
			50, FarmingBonuses.NONE);

		assertTrue("torstol needs 85", estimate.isEmpty());
		assertEquals(3, estimate.getUnfilledPatches());
	}

	/** Compost is applied during the run, so it is not assumed - and the estimate says so. */
	@Test
	public void doesNotAssumeCompost()
	{
		RunEstimate estimate = RunEstimate.forRun(
			patches(PatchImplementation.HERB, 1),
			seeds(Seed.RANARR),
			owned(Seed.RANARR, 100),
			99, FarmingBonuses.NONE);

		assertEquals(CompostTier.NONE, estimate.getCompost());
		assertTrue("three lives is the untreated floor", estimate.getTotalYield() >= 3);
		assertTrue("but it should beat the floor", estimate.getTotalYield() > 3);
	}

	/**
	 * The breakdown names each crop, because that is what the summary shows.
	 *
	 * <p>A single "84 crops" total says nothing about whether a run is worth doing. Lines are
	 * grouped by patch type so a herb/allotment/flower run reads in tab order.
	 */
	@Test
	public void breaksTheYieldDownByCrop()
	{
		RunEstimate estimate = RunEstimate.forRun(
			patches(PatchImplementation.ALLOTMENT, 4,
				PatchImplementation.HERB, 3,
				PatchImplementation.FLOWER, 2),
			seeds(Seed.WATERMELON, Seed.RANARR, Seed.LIMPWURT),
			owned(Seed.WATERMELON, 100, Seed.RANARR, 100, Seed.LIMPWURT, 100),
			99, FarmingBonuses.NONE);

		assertEquals("one line per crop", 3, estimate.getLines().size());

		// Tab order: herbs, then allotments, then flowers.
		assertEquals(Seed.RANARR, estimate.getLines().get(0).getSeed());
		assertEquals(Seed.WATERMELON, estimate.getLines().get(1).getSeed());
		assertEquals(Seed.LIMPWURT, estimate.getLines().get(2).getSeed());

		for (RunEstimate.Line line : estimate.getLines())
		{
			assertTrue(line.getSeed() + " should yield something",
				line.getExpectedYield() > 0);
		}

		// Each line is that crop's whole contribution, so they must add up to the total.
		double summed = estimate.getLines().stream()
			.mapToDouble(RunEstimate.Line::getExpectedYield).sum();
		assertEquals(estimate.getTotalYield(), summed, 0.001);
	}

	@Test
	public void nothingPickedIsNotAnError()
	{
		RunEstimate estimate = RunEstimate.forRun(
			patches(PatchImplementation.HERB, 5), seeds(), owned(), 99, FarmingBonuses.NONE);

		assertTrue(estimate.isEmpty());
		assertEquals(0.0, estimate.getTotalXp(), 0.001);
		assertEquals(5, estimate.getUnfilledPatches());
	}
}
