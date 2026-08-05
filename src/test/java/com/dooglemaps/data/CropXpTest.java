package com.dooglemaps.data;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Guards the scraped experience table.
 *
 * <p>The interesting property is not any single number but the shape per family: trees are
 * paid almost entirely for one click and nothing for their logs, while herbs are paid a
 * little up front and the rest per pick. Getting those the wrong way round would produce
 * plausible-looking totals that are badly wrong.
 */
public class CropXpTest
{
	@Test
	public void treesArePaidForCheckingNotForHarvesting()
	{
		CropXp magic = CropXp.forSeed(Seed.MAGIC);
		assertNotNull(magic);
		assertEquals(145.5, magic.getPlantXp(), 0.01);
		assertEquals(13768.3, magic.getCheckXp(), 0.01);
		assertEquals("a tree's logs are Woodcutting experience, not Farming",
			0.0, magic.getHarvestXp(), 0.001);

		// So the harvest count cannot change a tree's total.
		assertEquals(magic.totalFor(0), magic.totalFor(50), 0.001);
	}

	@Test
	public void herbsArePaidPerPick()
	{
		CropXp guam = CropXp.forSeed(Seed.GUAM);
		assertNotNull(guam);
		assertEquals(11.0, guam.getPlantXp(), 0.01);
		assertEquals("herbs are never checked for health", 0.0, guam.getCheckXp(), 0.001);
		assertEquals(12.5, guam.getHarvestXp(), 0.01);

		// The wiki's own "assumes 8 herbs are picked" total for guam is 111.
		assertEquals(111.0, guam.totalFor(8), 0.01);
	}

	@Test
	public void bushesArePaidBothWays()
	{
		CropXp redberry = CropXp.forSeed(Seed.REDBERRIES);
		assertNotNull(redberry);
		assertTrue("bushes are checked for health", redberry.getCheckXp() > 0);
		assertTrue("and picked from", redberry.getHarvestXp() > 0);
	}

	@Test
	public void everyEntryPaysSomethingForPlanting()
	{
		for (CropXp xp : CropXp.values())
		{
			assertTrue(xp + " has no planting experience", xp.getPlantXp() > 0);
			assertNotNull(xp + " has no seed", xp.getSeed());
		}
	}

	/**
	 * The panel only ever knows the produce, so that lookup has to work for everything.
	 *
	 * <p>A patch varbit says "a ranarr is growing here"; it cannot say which seed went in.
	 * If a produce could belong to two seeds the map would silently keep one of them, so
	 * that is checked rather than assumed.
	 */
	@Test
	public void everyEntryIsReachableFromItsProduce()
	{
		for (CropXp xp : CropXp.values())
		{
			assertEquals("produce lookup disagrees with seed lookup for " + xp,
				xp, CropXp.forProduce(xp.getSeed().getProduce()));
		}

		assertEquals(CropXp.forSeed(Seed.MAGIC), CropXp.forProduce(Produce.MAGIC));
		assertNull("weeds are not a crop", CropXp.forProduce(Produce.WEEDS));
		assertNull(CropXp.forProduce(null));
	}

	/**
	 * Fruit tree experience reconciles against the figure the wiki publishes for it.
	 *
	 * <p>They were absent for a long time because the patch/Seeds table gives one unlabelled
	 * number, and it turned out to be neither of the two candidates considered — not the
	 * check-health award and not a per-fruit rate, but the whole cycle summed. The components
	 * come from each seed's own page, and this is the check that they were read correctly:
	 * {@code plant + check + 6 x harvest} has to land on the published total, six times over.
	 *
	 * <p>Not a restatement of the same source. The totals come from a different page than the
	 * components, so agreement between them is real corroboration.
	 */
	@Test
	public void fruitTreeExperienceAddsUpToThePublishedTotal()
	{
		// From the Fruit tree patch/Seeds table, which assumes six fruit picked.
		assertCycleTotal(Seed.APPLE, 1272.5);
		assertCycleTotal(Seed.BANANA, 1841.5);
		assertCycleTotal(Seed.ORANGE, 2586.7);
		assertCycleTotal(Seed.CURRY, 3036.9);
		assertCycleTotal(Seed.PALM, 10509.6);
		assertCycleTotal(Seed.DRAGONFRUIT, 17895);
	}

	/**
	 * The two fruit trees whose sources disagree, pinned to what was actually chosen.
	 *
	 * <p>Pineapple's seed page gives a check award of 4,605 where its total implies 4,605.7;
	 * papaya's gives 6,146.6 where the total implies 6,146.4. Sub-experience-point gaps, and
	 * the seed pages win because they state the components rather than deriving them. Pinned
	 * so the discrepancy stays visible: one clean check-health observation settles either.
	 */
	@Test
	public void theTwoDisputedFruitTreesUseTheirSeedPageFigures()
	{
		assertEquals(4605.0, CropXp.forSeed(Seed.PINEAPPLE).getCheckXp(), 0.001);
		assertEquals(6146.6, CropXp.forSeed(Seed.PAPAYA).getCheckXp(), 0.001);

		// Off by less than an experience point either way, so nothing user-visible turns on it.
		assertEquals(4791.7, cycleTotal(Seed.PINEAPPLE), 1.0);
		assertEquals(6380.4, cycleTotal(Seed.PAPAYA), 1.0);
	}

	private static void assertCycleTotal(Seed seed, double published)
	{
		assertEquals(seed + " should reconcile with the wiki's own total",
			published, cycleTotal(seed), 0.001);
	}

	/** Plant, check and six fruit — the cycle the published total describes. */
	private static double cycleTotal(Seed seed)
	{
		CropXp xp = CropXp.forSeed(seed);
		assertNotNull(seed + " has no experience data", xp);
		return xp.getPlantXp() + xp.getCheckXp() + (6 * xp.getHarvestXp());
	}
}
