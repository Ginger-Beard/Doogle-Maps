package com.dooglemaps.timer;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropYield;
import com.dooglemaps.data.PatchImplementation;

/**
 * How much a patch will actually give you.
 *
 * <p>Herbs, allotments, hops and giant seaweed are not harvested a fixed number of times.
 * A fully grown patch starts with three "lives", plus one per tier of compost, and every
 * pick has a chance to take none of them. So the three lives an ultracomposted ranarr patch
 * turns into six are worth around nine herbs at a high Farming level, and the gap between
 * the floor and the expectation is the whole reason to bother with compost and secateurs.
 *
 * <p>The arithmetic is Jagex's, relayed through the OSRS Wiki, and the fiddly parts are the
 * ones that are easy to get subtly wrong:
 *
 * <ul>
 *   <li>Boosts scale the two <b>constants</b>, not the interpolated chance, so applying them
 *       afterwards gives a different and wrong answer.</li>
 *   <li>Secateurs and the cape are <b>additive</b> with each other (1.15 together), then the
 *       diary is added, then attas multiplies. Each step floors.</li>
 *   <li>The Farming cape only counts on herbs, and secateurs do not work underwater.</li>
 * </ul>
 *
 * <p>There is no maximum. Saving a life can in principle go on forever, so the honest pair
 * to show is the guaranteed floor and the expectation, never a range with a top end.
 */
public final class YieldEstimate
{
	/** Every lives-based crop starts fully grown with three, before compost. */
	public static final int BASE_LIVES = 3;

	private static final double SECATEURS_BONUS = 0.10;
	private static final double CAPE_BONUS = 0.05;
	private static final double ATTAS_BONUS = 0.05;

	private YieldEstimate()
	{
	}

	/**
	 * Lives a freshly grown patch holds, given how it was treated.
	 *
	 * <p>This is also the guaranteed minimum harvest: you cannot get fewer items than you
	 * have lives, because a pick either yields an item and keeps the life or yields an item
	 * and spends it.
	 */
	public static int lives(CompostTier compost)
	{
		return BASE_LIVES + (compost == null ? 0 : compost.getLivesBonus());
	}

	/**
	 * The chance a single pick costs no life, as a fraction between 0 and 1.
	 *
	 * @param level Farming level, clamped to 1-99; boosts above 99 do nothing here
	 */
	public static double chanceToSave(CropYield yield, int level, FarmingBonuses bonuses)
	{
		int clamped = Math.max(1, Math.min(99, level));
		boolean herb = yield.getSeed().getPatchType() == PatchImplementation.HERB;
		boolean underwater = yield.getSeed().getPatchType() == PatchImplementation.SEAWEED;

		// Mod Ash: "the percentages are added for the secateurs and cape, then applied, then
		// the Kandarin effect is multiplied on afterwards."
		double itemBonus = 0;
		if (bonuses.isMagicSecateurs() && !underwater)
		{
			itemBonus += SECATEURS_BONUS;
		}
		if (bonuses.isFarmingCape() && herb)
		{
			itemBonus += CAPE_BONUS;
		}

		int low = boost(yield.getCtsLow(), itemBonus, bonuses);
		int high = boost(yield.getCtsHigh(), itemBonus, bonuses);

		double interpolated = (low * (99.0 - clamped) / 98.0) + (high * (clamped - 1.0) / 98.0);
		return (1 + Math.floor(interpolated + 0.5)) / 256.0;
	}

	/** Applies the three classes of boost to one constant, flooring at each step. */
	private static int boost(int cts, double itemBonus, FarmingBonuses bonuses)
	{
		int boosted = (int) Math.floor(cts * (1 + itemBonus));
		boosted += bonuses.getDiaryBonus();
		if (bonuses.isAttas())
		{
			boosted = (int) Math.floor(boosted * (1 + ATTAS_BONUS));
		}
		return boosted;
	}

	/**
	 * Expected number of items from a patch with the given lives.
	 *
	 * <p>Picks that save a life follow a negative binomial distribution, whose mean works out
	 * as simply {@code lives / (1 - chanceToSave)}.
	 */
	public static double expectedHarvest(CropYield yield, int level, int lives, FarmingBonuses bonuses)
	{
		double save = chanceToSave(yield, level, bonuses);
		if (save >= 1.0)
		{
			// Unreachable with real constants, but a 100% save would be an infinite harvest
			// and it is better to return the floor than an infinity that formats as "Infinity".
			return lives;
		}
		return lives / (1.0 - save);
	}

	/** Convenience for the common case: a patch treated with this compost, at this level. */
	public static double expectedHarvest(CropYield yield, int level, CompostTier compost,
		FarmingBonuses bonuses)
	{
		return expectedHarvest(yield, level, lives(compost), bonuses);
	}

	/**
	 * How widely the item count scatters around {@link #expectedHarvest}, as a variance.
	 *
	 * <p>The same negative binomial the mean comes from, so it costs the same two numbers: the
	 * lives are the {@code r} failures, a saved life is a success at probability {@code p}, and
	 * that distribution's variance is {@code r·p/(1-p)²}.
	 *
	 * <p>Worth carrying because a mean alone cannot say whether a harvest was <i>unusual</i>.
	 * Twelve herbs off a patch predicted nine is either luck or a modelling error, and only the
	 * spread separates them. Variances of independent patches add exactly as their means do, so
	 * a running total of this is the whole of what a "where did I land" figure needs — no
	 * per-patch history, and no comparison against other players.
	 */
	public static double harvestVariance(CropYield yield, int level, int lives,
		FarmingBonuses bonuses)
	{
		double save = chanceToSave(yield, level, bonuses);
		if (save >= 1.0)
		{
			// Matching expectedHarvest: a certain save is unreachable, and a zero spread beside
			// its floor is the honest pair rather than an infinity.
			return 0;
		}
		double spread = 1.0 - save;
		return lives * save / (spread * spread);
	}

	/** Convenience for the common case: a patch treated with this compost, at this level. */
	public static double harvestVariance(CropYield yield, int level, CompostTier compost,
		FarmingBonuses bonuses)
	{
		return harvestVariance(yield, level, lives(compost), bonuses);
	}
}
