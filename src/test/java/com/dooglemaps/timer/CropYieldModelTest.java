package com.dooglemaps.timer;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Seed;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Covers the four different rules the game uses to decide a harvest.
 *
 * <p>Everything used to go through the chance-to-save formula and fall back to a single item
 * when a crop had no published constants. That was exactly right for a marigold and out by a
 * factor of seven for a limpwurt, which is the kind of error that makes a whole run look not
 * worth doing.
 */
public class CropYieldModelTest
{
	private static final FarmingBonuses BARE = FarmingBonuses.NONE;

	/** Herbs and allotments still go through the chance-to-save maths. */
	@Test
	public void livesBasedCropsAreStillComputed()
	{
		assertEquals(CropYieldModel.Basis.COMPUTED, CropYieldModel.basisFor(Seed.RANARR));

		double ranarr = CropYieldModel.expected(Seed.RANARR, 99, CompostTier.ULTRACOMPOST,
			new FarmingBonuses(true, true, false, 0));
		assertEquals("the wiki's headline herb figure", 9.0, ranarr, 0.5);
	}

	/**
	 * Limpwurt does not use harvest lives at all.
	 *
	 * <p>Mod Ash: "That one doesn't have the 'life' mechanic... That's 3 + a random number."
	 * The wiki's measured average is about 8; the rule works out just under that before an
	 * attas plant, which is not modelled.
	 */
	@Test
	public void limpwurtRollsALevelScaledBonus()
	{
		assertEquals(CropYieldModel.Basis.LEVEL_ROLL, CropYieldModel.basisFor(Seed.LIMPWURT));

		double atNinetyNine = CropYieldModel.expected(Seed.LIMPWURT, 99, CompostTier.NONE, BARE);
		assertEquals(7.45, atNinetyNine, 0.1);

		assertTrue("and it must beat the old flat answer of one", atNinetyNine > 7);
		assertTrue("with the roll growing as you level",
			atNinetyNine > CropYieldModel.expected(Seed.LIMPWURT, 30, CompostTier.NONE, BARE));
		assertTrue("but never below its base of three",
			CropYieldModel.expected(Seed.LIMPWURT, 1, CompostTier.NONE, BARE) >= 3);
	}

	/** Belladonna works the same way, per Mod Ash: "Belladonna has something similar." */
	@Test
	public void belladonnaSharesTheRule()
	{
		assertEquals(CropYieldModel.Basis.LEVEL_ROLL, CropYieldModel.basisFor(Seed.BELLADONNA));
		assertEquals(CropYieldModel.expected(Seed.LIMPWURT, 99, CompostTier.NONE, BARE),
			CropYieldModel.expected(Seed.BELLADONNA, 99, CompostTier.NONE, BARE), 0.001);
	}

	/** Compost buys harvest lives, and only the lives mechanic has any. */
	@Test
	public void compostOnlyHelpsCropsWithLives()
	{
		assertTrue(CropYieldModel.respondsToCompost(Seed.RANARR));
		assertTrue(CropYieldModel.expected(Seed.RANARR, 99, CompostTier.ULTRACOMPOST, BARE)
			> CropYieldModel.expected(Seed.RANARR, 99, CompostTier.NONE, BARE));

		assertFalse(CropYieldModel.respondsToCompost(Seed.LIMPWURT));
		assertEquals("ultracompost cannot help a crop that does not roll for lives",
			CropYieldModel.expected(Seed.LIMPWURT, 99, CompostTier.NONE, BARE),
			CropYieldModel.expected(Seed.LIMPWURT, 99, CompostTier.ULTRACOMPOST, BARE), 0.001);
	}

	/** An ordinary flower really does give one, and should say so. */
	@Test
	public void ordinaryFlowersGiveExactlyOne()
	{
		for (Seed flower : new Seed[]{Seed.MARIGOLD, Seed.WOAD, Seed.WHITE_LILY, Seed.ROSEMARY})
		{
			assertEquals(flower + " gives one", 1.0,
				CropYieldModel.expected(flower, 99, CompostTier.ULTRACOMPOST, BARE), 0.001);
			assertEquals(CropYieldModel.Basis.FIXED, CropYieldModel.basisFor(flower));
		}
	}

	/**
	 * A visit to a regrowing plant collects what has grown back.
	 *
	 * <p>Not a rolled amount: a full palm holds six coconuts, and six is what you get.
	 */
	@Test
	public void regrowingCropsGiveTheirStock()
	{
		assertEquals(CropYieldModel.Basis.STOCK, CropYieldModel.basisFor(Seed.PALM));
		assertEquals(6.0, CropYieldModel.expected(Seed.PALM, 99, CompostTier.NONE, BARE), 0.001);

		assertEquals(CropYieldModel.Basis.STOCK, CropYieldModel.basisFor(Seed.POISON_IVY));
		assertTrue(CropYieldModel.expected(Seed.POISON_IVY, 99, CompostTier.NONE, BARE) >= 4);
	}

	/** Where Jagex published nothing, the wiki's measured average is used and labelled. */
	@Test
	public void unpublishedCropsUseMeasuredAverages()
	{
		assertEquals(CropYieldModel.Basis.EMPIRICAL, CropYieldModel.basisFor(Seed.CELASTRUS));
		assertEquals(9.0, CropYieldModel.expected(Seed.CELASTRUS, 99, CompostTier.NONE, BARE), 0.001);

		assertEquals(CropYieldModel.Basis.EMPIRICAL, CropYieldModel.basisFor(Seed.CACTUS));
		assertEquals(10.0, CropYieldModel.expected(Seed.CACTUS, 99, CompostTier.NONE, BARE), 0.001);
	}

	/**
	 * A flower pays its harvest experience once, however many items come out.
	 *
	 * <p>Observed in a real harvest: limpwurt predicted 1,200 experience for 10 roots and paid
	 * 91. The 120 figure is the award for clearing the patch, not a per-root rate.
	 */
	@Test
	public void flowersPayTheirHarvestExperienceOnce()
	{
		assertEquals(1.0, CropYieldModel.xpHarvestsFor(Seed.LIMPWURT, 10), 1e-9);
		assertEquals(1.0, CropYieldModel.xpHarvestsFor(Seed.MARIGOLD, 1), 1e-9);

		// Everything else really is per item, which is why herbs have matched all along.
		assertEquals(9.0, CropYieldModel.xpHarvestsFor(Seed.RANARR, 9), 1e-9);
		assertEquals(30.0, CropYieldModel.xpHarvestsFor(Seed.WATERMELON, 30), 1e-9);
	}

	/** Nothing should ever come back as zero, or a run would price a patch at nothing. */
	@Test
	public void everySeedYieldsSomething()
	{
		for (Seed seed : Seed.values())
		{
			if (seed.getPatchType() == PatchImplementation.TREE)
			{
				continue;   // a tree's logs are Woodcutting, not a farming harvest
			}
			assertTrue(seed + " yields nothing",
				CropYieldModel.expected(seed, 99, CompostTier.NONE, BARE) >= 1);
		}
	}

	private static void assertFalse(boolean condition)
	{
		assertTrue(!condition);
	}
}
