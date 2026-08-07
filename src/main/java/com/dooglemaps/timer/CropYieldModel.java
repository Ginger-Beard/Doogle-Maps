package com.dooglemaps.timer;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropYield;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.Seed;
import java.util.EnumMap;
import java.util.Map;

/**
 * How many items a patch gives, whichever way the game happens to decide it.
 *
 * <p>The "harvest lives" mechanic in {@link YieldEstimate} is the best understood but it is not
 * the only one, and treating everything else as a single item was wrong in both directions —
 * it made a limpwurt patch look worth a tenth of what it is, while being exactly right for a
 * marigold. There are four genuinely different rules:
 *
 * <ul>
 *   <li><b>Harvest lives</b> — herbs, allotments, hops, giant seaweed. Computed from published
 *       chance-to-save constants, and the only family where compost and secateurs apply.</li>
 *   <li><b>A base plus a level roll</b> — limpwurt and belladonna. Mod Ash: <i>"That one
 *       doesn't have the 'life' mechanic... it just picks a number of roots and gives them to
 *       you. That's 3 + a random number."</i> Compost does nothing here.</li>
 *   <li><b>Whatever is on the plant</b> — bushes, fruit trees, cacti and the like regrow, so a
 *       visit collects the current stock rather than rolling for it.</li>
 *   <li><b>Exactly one</b> — the ordinary flowers. A marigold patch gives one marigold, and
 *       every estimate for it should say one.</li>
 * </ul>
 *
 * <p>Where Jagex has published nothing, the wiki's empirical averages are used and labelled as
 * such through {@link #basisFor}. Those are measured rather than derived, so they carry the
 * measurement's assumptions with them; the alternative was a number that was simply wrong.
 */
public final class CropYieldModel
{
	/** How a figure was arrived at, so the panel can be honest about it. */
	public enum Basis
	{
		/** Computed from published chance-to-save constants. */
		COMPUTED,
		/** Computed from Jagex's described base-plus-roll rule. */
		LEVEL_ROLL,
		/** The produce currently on a regrowing plant. */
		STOCK,
		/** Always this many. */
		FIXED,
		/** The wiki's measured average, because nothing better is published. */
		EMPIRICAL
	}

	/** Crops that roll a level-scaled bonus on top of a base of three. */
	private static final Map<Seed, Integer> LEVEL_ROLL_BASE = new EnumMap<>(Seed.class);

	/**
	 * Measured averages, for crops whose rules Jagex has never described.
	 *
	 * <p>From the OSRS Wiki's "Average yield per crop type", which measures with magic
	 * secateurs, the Farming cape and ultracompost at level 99 — so these are optimistic for
	 * anyone below that, and they do not scale. Better than a placeholder, worse than a
	 * formula, and marked {@link Basis#EMPIRICAL} so it can be said out loud.
	 */
	private static final Map<Seed, Double> EMPIRICAL = new EnumMap<>(Seed.class);

	static
	{
		LEVEL_ROLL_BASE.put(Seed.LIMPWURT, 3);
		LEVEL_ROLL_BASE.put(Seed.BELLADONNA, 3);

		EMPIRICAL.put(Seed.CACTUS, 10.0);          // "Cacti give an average of 10 spines"
		EMPIRICAL.put(Seed.POTATO_CACTUS, 17.5);   // "15-20 potato cacti"
		EMPIRICAL.put(Seed.CELASTRUS, 9.0);        // "8-10 bark"
	}

	private CropYieldModel()
	{
	}

	/**
	 * Expected items from one fully grown patch.
	 *
	 * @param compost what the patch was treated with; ignored by every rule but harvest lives
	 */
	public static double expected(Seed seed, int level, CompostTier compost,
		FarmingBonuses bonuses)
	{
		if (seed == null)
		{
			return 0;
		}

		CropYield yield = CropYield.forSeed(seed);
		if (yield != null)
		{
			return YieldEstimate.expectedHarvest(yield, level, compost, bonuses);
		}

		Integer base = LEVEL_ROLL_BASE.get(seed);
		if (base != null)
		{
			return base + expectedLevelRoll(level);
		}

		Double measured = EMPIRICAL.get(seed);
		if (measured != null)
		{
			return measured;
		}

		return fullStock(seed);
	}

	/** How the figure {@link #expected} returns was arrived at. */
	public static Basis basisFor(Seed seed)
	{
		if (seed == null)
		{
			return Basis.FIXED;
		}
		if (CropYield.forSeed(seed) != null)
		{
			return Basis.COMPUTED;
		}
		if (LEVEL_ROLL_BASE.containsKey(seed))
		{
			return Basis.LEVEL_ROLL;
		}
		if (EMPIRICAL.containsKey(seed))
		{
			return Basis.EMPIRICAL;
		}
		return regrows(seed) ? Basis.STOCK : Basis.FIXED;
	}

	/**
	 * The average of the roll limpwurt and belladonna add to their base.
	 *
	 * <p>Mod Ash describes it as a random number from 0 to your level minus one, contributing
	 * one per ten — <i>"if you boost to 101, it has a small chance to roll 100, which would
	 * give the +10"</i>. So the bonus is the roll's tens digit, and its mean works out at
	 * roughly 4.5 by level 99, giving about seven and a half roots.
	 */
	static double expectedLevelRoll(int level)
	{
		int clamped = Math.max(1, level);
		int tens = clamped / 10;
		int remainder = clamped % 10;
		// Sum of floor(u / 10) for u in 0..level-1, in closed form.
		double sum = 5.0 * tens * (tens - 1) + (double) tens * remainder;
		return sum / clamped;
	}

	/**
	 * What a regrowing plant holds when full, or one for anything else.
	 *
	 * <p>A visit to a bush or a fruit tree collects what has grown back, not a rolled amount,
	 * so its full stock is the honest estimate for a run. Everything left over — the ordinary
	 * flowers, the single-item crops — gives exactly one.
	 */
	private static double fullStock(Seed seed)
	{
		Produce produce = seed.getProduce();
		if (produce == null)
		{
			return 1;
		}
		// Regrowing crops count states, one of which is "nothing on the plant".
		return regrows(seed) ? Math.max(1, produce.getHarvestStages() - 1) : 1;
	}

	private static boolean regrows(Seed seed)
	{
		Produce produce = seed.getProduce();
		return produce != null && produce.getRegrowTickrate() > 0;
	}

	/** Whether compost changes this crop's yield at all. Only the lives mechanic cares. */
	public static boolean respondsToCompost(Seed seed)
	{
		return seed != null && CropYield.forSeed(seed) != null;
	}

	/**
	 * Whether treating this patch type changes anything the plugin can actually tell you.
	 *
	 * <p>Two separate ways it can: the <b>yield</b>, through the lives mechanic, which only herbs,
	 * allotments, hops and giant seaweed have; and the <b>disease</b> chance, which compost cuts
	 * on every patch that can catch one. The second is why the dropdown belongs on trees despite a
	 * tree giving one log however it was treated.
	 *
	 * <p>Both halves are asked of the data rather than listed. Disease is asked of
	 * {@link DiseaseRisk#isRiskKnown}, not of "can this be diseased in game" — Jagex has published
	 * a rate for herbs, fruit trees, coral and two of the trees, and for everything else the model
	 * returns certain survival. Offering the control where no rate exists would put a dropdown in
	 * front of the player that provably cannot move a number, which is the thing being fixed.
	 */
	public static boolean compostMatters(PatchImplementation type)
	{
		// Whether it does something in the game, not whether the projection can show it. Asking
		// isRiskKnown alone hid the dropdown on flowers — a patch that can be diseased and that
		// compost genuinely protects — because Jagex has never published a flower rate. The player
		// could then not ask for their flowers to be treated at all, so the run banked no buckets
		// and the guide applied none. See DiseaseRisk.canCatchDisease.
		if (DiseaseRisk.canCatchDisease(type))
		{
			return true;
		}

		for (Seed seed : Seed.forPatchType(type))
		{
			if (respondsToCompost(seed) || DiseaseRisk.isRiskKnown(seed.getProduce()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether compost is worth applying here for disease alone.
	 *
	 * <p>True exactly where the dropdown is offered but the yield will not move, which is the
	 * case worth saying out loud — someone treating a fruit tree with ultracompost is buying
	 * survival, not fruit, and the projection moving only a little would otherwise look wrong.
	 */
	public static boolean compostOnlyHelpsDisease(PatchImplementation type)
	{
		if (!compostMatters(type))
		{
			return false;
		}
		for (Seed seed : Seed.forPatchType(type))
		{
			if (respondsToCompost(seed))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * How many times the harvest award is actually paid for one patch.
	 *
	 * <p>Not the same as the yield. A flower patch pays its harvest experience <b>once</b>
	 * however many roots come out — limpwurt's 120 is the award for clearing the patch, not
	 * per root. Multiplying it by the yield claimed 1,200 experience for a level-26 flower,
	 * against 91 actually observed.
	 *
	 * <p>Everywhere else the award really is per item, which is why the herb and allotment
	 * figures have been matching all along.
	 */
	public static double xpHarvestsFor(Seed seed, double expectedYield)
	{
		if (seed == null)
		{
			return 0;
		}
		return seed.getPatchType() == PatchImplementation.FLOWER ? 1 : expectedYield;
	}

	/** Whether this is a crop where a per-patch yield figure means anything. */
	public static boolean hasMeaningfulYield(Seed seed)
	{
		return seed != null && seed.getPatchType() != PatchImplementation.TREE;
	}
}
