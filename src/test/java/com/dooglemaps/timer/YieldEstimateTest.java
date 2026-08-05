package com.dooglemaps.timer;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropYield;
import com.dooglemaps.data.Seed;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the yield arithmetic against figures the wiki publishes independently.
 *
 * <p>The formula has several places where a plausible-looking mistake changes the answer by
 * only a percent or two - rounding rather than flooring the interpolation, applying boosts
 * after it instead of before, multiplying the diary rather than adding it. So the tests
 * anchor on numbers the wiki states in prose, arrived at by a different route than ours.
 */
public class YieldEstimateTest
{
	private static final FarmingBonuses BARE = FarmingBonuses.NONE;

	/**
	 * Jute's own page states both ends of its range, which nails the formula at once.
	 *
	 * <p>"The chance to successfully harvest a jute fibre without losing a harvest life
	 * ... ranges from 47.7% at level 13 up to a 70.7% chance at level 99."
	 */
	@Test
	public void reproducesTheRangeJutePublishes()
	{
		CropYield jute = CropYield.forSeed(Seed.JUTE);
		assertNotNull(jute);

		assertEquals(0.477, YieldEstimate.chanceToSave(jute, 13, BARE), 0.0005);
		assertEquals(0.707, YieldEstimate.chanceToSave(jute, 99, BARE), 0.0005);
	}

	/**
	 * At 99 every herb converges, which is the single most quotable fact about the mechanic.
	 *
	 * <p>Mod Kieren: "if you're 99 farming, your average yield from guam and torstol will be
	 * exactly the same." Guam and torstol sit at opposite ends of the low constant, so if the
	 * interpolation leaned on it at all at 99 this would fail.
	 */
	@Test
	public void everyHerbConvergesAtNinetyNine()
	{
		double guam = YieldEstimate.chanceToSave(CropYield.forSeed(Seed.GUAM), 99, BARE);
		double torstol = YieldEstimate.chanceToSave(CropYield.forSeed(Seed.TORSTOL), 99, BARE);

		assertEquals(guam, torstol, 1e-9);
		assertEquals("81/256", 81 / 256.0, guam, 1e-9);

		// And they very much do not converge lower down, which is the other half of the claim.
		assertTrue(YieldEstimate.chanceToSave(CropYield.forSeed(Seed.TORSTOL), 50, BARE)
			> YieldEstimate.chanceToSave(CropYield.forSeed(Seed.GUAM), 50, BARE));
	}

	/**
	 * The wiki's headline herb figure: "Herbs give an average of 9 herbs per patch",
	 * assuming magic secateurs, the Farming cape, level 99 and ultracompost.
	 */
	@Test
	public void aFullyKittedHerbPatchGivesAboutNine()
	{
		FarmingBonuses kitted = new FarmingBonuses(true, true, false, 0);
		double expected = YieldEstimate.expectedHarvest(
			CropYield.forSeed(Seed.RANARR), 99, CompostTier.ULTRACOMPOST, kitted);

		assertEquals(9.0, expected, 0.5);
	}

	/** Ultracompost doubles the lives, and so doubles the expected harvest exactly. */
	@Test
	public void compostBuysLivesNotChance()
	{
		CropYield ranarr = CropYield.forSeed(Seed.RANARR);

		assertEquals(3, YieldEstimate.lives(CompostTier.NONE));
		assertEquals(6, YieldEstimate.lives(CompostTier.ULTRACOMPOST));
		assertEquals("compost cannot change the odds of a pick",
			YieldEstimate.chanceToSave(ranarr, 75, BARE),
			YieldEstimate.chanceToSave(ranarr, 75, BARE), 1e-9);

		assertEquals(2.0,
			YieldEstimate.expectedHarvest(ranarr, 75, CompostTier.ULTRACOMPOST, BARE)
				/ YieldEstimate.expectedHarvest(ranarr, 75, CompostTier.NONE, BARE),
			1e-9);
	}

	/**
	 * The Farming cape is a herb-patch reward and does nothing to an allotment.
	 *
	 * <p>Easy to miss, because secateurs help both and the two are usually worn together.
	 */
	@Test
	public void theFarmingCapeCountsOnHerbsOnly()
	{
		FarmingBonuses cape = new FarmingBonuses(false, true, false, 0);

		assertTrue(YieldEstimate.chanceToSave(CropYield.forSeed(Seed.RANARR), 60, cape)
			> YieldEstimate.chanceToSave(CropYield.forSeed(Seed.RANARR), 60, BARE));
		assertEquals("an allotment does not care about the cape",
			YieldEstimate.chanceToSave(CropYield.forSeed(Seed.WATERMELON), 60, BARE),
			YieldEstimate.chanceToSave(CropYield.forSeed(Seed.WATERMELON), 60, cape), 1e-9);
	}

	/** Secateurs are no use underwater, so giant seaweed ignores them. */
	@Test
	public void secateursDoNotWorkUnderwater()
	{
		CropYield seaweed = CropYield.forSeed(Seed.SEAWEED);
		assertNotNull(seaweed);

		assertEquals(
			YieldEstimate.chanceToSave(seaweed, 80, BARE),
			YieldEstimate.chanceToSave(seaweed, 80, new FarmingBonuses(true, true, false, 0)),
			1e-9);

		// Mod Ash: "The chance scales from 59% - 82% of NOT taking a life ... as your level
		// goes from 1-99."
		assertEquals(0.59, YieldEstimate.chanceToSave(seaweed, 1, BARE), 0.005);
		assertEquals(0.82, YieldEstimate.chanceToSave(seaweed, 99, BARE), 0.005);
	}

	/**
	 * The diary is a flat addition to the constants, not a multiplier.
	 *
	 * <p>Getting this wrong is invisible at low constants and badly wrong at high ones, so it
	 * is checked at 99 where the constant is largest.
	 */
	@Test
	public void theDiaryBonusIsAddedNotMultiplied()
	{
		CropYield ranarr = CropYield.forSeed(Seed.RANARR);
		FarmingBonuses elite = BARE.withDiaryBonus(25);

		// 80 + 25 = 105, so 106/256 at level 99.
		assertEquals(106 / 256.0, YieldEstimate.chanceToSave(ranarr, 99, elite), 1e-9);
	}

	/** Level is clamped: a Farming potion cannot push a herb past its level-99 ceiling. */
	@Test
	public void boostsAboveNinetyNineDoNothing()
	{
		CropYield ranarr = CropYield.forSeed(Seed.RANARR);
		assertEquals(YieldEstimate.chanceToSave(ranarr, 99, BARE),
			YieldEstimate.chanceToSave(ranarr, 112, BARE), 1e-9);
		assertEquals(YieldEstimate.chanceToSave(ranarr, 1, BARE),
			YieldEstimate.chanceToSave(ranarr, 0, BARE), 1e-9);
	}

	/** The expectation can only ever be above the floor, for every crop at every level. */
	@Test
	public void theExpectationNeverFallsBelowTheGuaranteedLives()
	{
		for (CropYield yield : CropYield.values())
		{
			for (int level = 1; level <= 99; level += 7)
			{
				double expected = YieldEstimate.expectedHarvest(
					yield, level, CompostTier.NONE, BARE);
				assertTrue(yield + " at level " + level + " expects " + expected,
					expected >= YieldEstimate.BASE_LIVES);
			}
		}
	}

	/**
	 * Crops whose constants Jagex has never published are absent rather than guessed at.
	 *
	 * <p>Celastrus uses the lives mechanic but has no numbers anywhere; flowers and bushes do
	 * not use it at all. Recorded as a test so the gap stays a decision.
	 */
	@Test
	public void unpublishedCropsAreLeftOut()
	{
		assertNull(CropYield.forSeed(Seed.CELASTRUS));
		assertNull(CropYield.forSeed(Seed.LIMPWURT));
		assertNull(CropYield.forSeed(Seed.REDBERRIES));
	}
}
