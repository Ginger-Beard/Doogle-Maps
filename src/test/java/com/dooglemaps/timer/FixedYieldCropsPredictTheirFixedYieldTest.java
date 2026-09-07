package com.dooglemaps.timer;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Seed;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Three crops give the same number every time, and the model was guessing at all three.
 *
 * <h2>The reported dead end</h2>
 *
 * Each of these was falling through to a rule borrowed from a crop that looks similar, and the
 * account's own harvest log contradicts every one of them without a single exception:
 *
 * <ul>
 *   <li><b>Belladonna</b> sat in {@code LEVEL_ROLL_BASE} beside limpwurt, on the strength of a Mod
 *       Ash quote — <i>"it just picks a number of roots and gives them to you, that's 3 + a random
 *       number"</i> — which is about limpwurt. That predicted 6.77 nightshade a patch. Observed:
 *       <b>exactly 1 on all fifteen patches</b>, each paying 521 experience, which is one
 *       nightshade's harvest award and not six of them. It also put ~575k of imaginary experience
 *       into the "planting it all out" figure, off 173 banked seeds.</li>
 *   <li><b>Calquat</b> does not regrow, so {@code fullStock} gave it 1. Observed: exactly 6 on all
 *       twenty-four.</li>
 *   <li><b>Mushroom</b> the same, and observed exactly 6 on all six.</li>
 * </ul>
 *
 * <p>Limpwurt's own level roll is deliberately left alone and pinned here, because for limpwurt it
 * is a good model: predicted 6.77 against 6.91 observed over 89 patches, with a maximum of 11 at
 * level 87 exactly as {@code 3 + ⌊U(0,level−1)/10⌋} says.
 */
public class FixedYieldCropsPredictTheirFixedYieldTest
{
	@Test
	public void belladonnaGivesOneNightshade()
	{
		assertEquals(1.0, expected(Seed.BELLADONNA, 85), 1e-9);
		assertEquals("and it is a count, not an estimate",
			CropYieldModel.Basis.FIXED, CropYieldModel.basisFor(Seed.BELLADONNA));
	}

	@Test
	public void calquatGivesSixFruit()
	{
		assertEquals(6.0, expected(Seed.CALQUAT, 85), 1e-9);
		assertEquals(CropYieldModel.Basis.FIXED, CropYieldModel.basisFor(Seed.CALQUAT));
	}

	@Test
	public void aMushroomPatchGivesSixMushrooms()
	{
		assertEquals(6.0, expected(Seed.MUSHROOM, 85), 1e-9);
		assertEquals(CropYieldModel.Basis.FIXED, CropYieldModel.basisFor(Seed.MUSHROOM));
	}

	/** Fixed means fixed: neither the level nor the bucket moves any of them. */
	@Test
	public void neitherLevelNorCompostMovesAFixedYield()
	{
		for (Seed seed : new Seed[]{Seed.BELLADONNA, Seed.CALQUAT, Seed.MUSHROOM})
		{
			double low = CropYieldModel.expected(seed, 1, CompostTier.NONE, FarmingBonuses.NONE);
			double high = CropYieldModel.expected(seed, 99, CompostTier.ULTRACOMPOST,
				FarmingBonuses.NONE);
			assertEquals(seed + " gives the same number whatever the patch was through", low, high,
				1e-9);
			assertFalse(seed + " has no lives for a bucket to add to",
				CropYieldModel.respondsToCompost(seed));
		}
	}

	/**
	 * Limpwurt keeps the level roll, which is the model belladonna was borrowing.
	 *
	 * <p>The quote is about flowers with a root count, and for the crop it was said of it is
	 * excellent. Removing belladonna from that map must not take limpwurt with it.
	 */
	@Test
	public void limpwurtKeepsItsLevelRoll()
	{
		assertEquals(CropYieldModel.Basis.LEVEL_ROLL, CropYieldModel.basisFor(Seed.LIMPWURT));

		double atThirty = expected(Seed.LIMPWURT, 30);
		double atNinety = expected(Seed.LIMPWURT, 90);
		assertTrue("the roll scales with the level, which is the whole of the rule",
			atNinety > atThirty);
		assertEquals("and it is a base of three plus the roll", 3.0 + CropYieldModel
			.expectedLevelRoll(90), atNinety, 1e-9);
	}

	/**
	 * A farmed tree has no per-patch yield, and never had.
	 *
	 * <p>Teak, maple, yew, magic and camphor rows reached the harvest store carrying woodcutting
	 * logs and zero farming experience, and were scored against a predicted 1 — worth +144 of the
	 * account's "items over expectation". This has always been the right answer; nothing asked it.
	 * The refusal itself is pinned in {@code AWithdrawalIsNotAHarvestTest}.
	 */
	@Test
	public void aTreeCarriesNoHarvestExpectation()
	{
		for (Seed seed : Seed.values())
		{
			if (seed.getPatchType() == PatchImplementation.TREE)
			{
				assertFalse(seed + " is chopped for logs, not picked",
					CropYieldModel.hasMeaningfulYield(seed));
			}
		}
	}

	private static double expected(Seed seed, int level)
	{
		return CropYieldModel.expected(seed, level, CompostTier.ULTRACOMPOST, FarmingBonuses.NONE);
	}
}
