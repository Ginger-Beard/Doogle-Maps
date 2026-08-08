package com.dooglemaps.timer;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropXp;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Seed;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.Value;
import net.runelite.api.Experience;

/**
 * The path from where you are to where your banked seeds would take you.
 *
 * <p>Distinct from {@link com.dooglemaps.route.RunEstimate}, which prices <i>one trip</i> with
 * the patches you have and the seeds you picked. This plants the whole bank: not "what will this
 * run give me" but "where does everything I own get me", which is the question you ask when
 * deciding what to plant rather than when planting it. Both go through
 * {@link CropYieldModel#expected} and {@link CropXp#totalFor}, so the two cannot disagree about a
 * single patch — they disagree only about how many patches are in view.
 *
 * <h2>Why this is a simulation and not a sum</h2>
 *
 * Chance-to-save moves with the Farming level, and so does <i>what you are allowed to plant</i>.
 * A bank planted from level 1 does not stay at level 1: guam takes you to where ranarr unlocks,
 * ranarr takes you to where snapdragon does, and each unlock changes what the next cycle should
 * be planting. Pricing each stack separately misses all of that, and misses it by more the
 * further you have to go.
 *
 * <p>So this runs the whole thing forward. Each cycle fills every patch type with the best
 * experience per patch it can currently plant, banks what that pays, recomputes the level, and
 * goes again. Crops crossing their requirement mid-run are picked up as {@link Unlock}s, which
 * is the path itself: <i>at 32 you unlock ranarr, and you are holding two hundred</i>.
 *
 * <h2>The policy, stated because it is an assumption</h2>
 *
 * <b>Best experience per patch, first.</b> Patches are the scarce resource — a run visits what
 * you have and no more — so filling them with the most valuable thing you can plant is what
 * maximises experience, and it is what someone chasing 99 would actually do. Every seed is
 * planted eventually; the policy decides the <i>order</i>, and best-first is the order that gets
 * the most out of the levelling.
 *
 * <p><b>Nothing about time.</b> Growth is real but so is logging off, and "you will have this by
 * Tuesday" would be the first dishonest number on the tab. This is a valuation and a route
 * through the levels, not a schedule.
 */
public final class PlantOutEstimate
{
	/**
	 * Hard bound on simulation steps.
	 *
	 * <p>Not expected to bind. Each step either crosses a level or empties a seed stack, so the
	 * real bound is 99 plus the number of crops — a hundred and fifty or so. This is here
	 * because the loop runs on the Swing thread during a panel repaint, and a bound that cannot
	 * be reasoned about is worse than one that is never reached.
	 */
	private static final int MAX_STEPS = 10_000;

	/** One crop's stack, and what planting all of it contributes. */
	@Value
	public static class Line
	{
		Seed seed;

		/** Seeds or saplings held, across every source. */
		int seeds;

		/**
		 * Patches those seeds fill.
		 *
		 * <p>Not the same as the seed count: an allotment takes three seeds and a hops patch
		 * four, so a hundred potato seeds is thirty-three patches and one seed left over.
		 */
		int patches;

		/** Items expected from those patches, at the levels they are actually planted at. */
		double items;

		/** Farming experience from them, likewise. */
		double xp;

		/** The same, computed as though you never left the level you are on now. */
		double xpAtStartLevel;

		/** Experience per seed held, which is what makes this a planting guide. */
		public double getXpPerSeed()
		{
			return seeds == 0 ? 0 : xp / seeds;
		}
	}

	/** A crop crossing its Farming requirement partway through the run. */
	@Value
	public static class Unlock
	{
		/**
		 * The crop's Farming requirement, which is the level it genuinely unlocks at.
		 *
		 * <p>Not the level the simulation happened to notice at. One cycle low down can carry
		 * you several levels at once, so a crop requiring 75 can first be seen at 85 — and
		 * reporting 85 would be describing the step size rather than the game.
		 */
		int level;
		Seed seed;
		int seeds;
	}

	/** Every stack, the route through the levels, and where it all ends. */
	@Value
	public static class Projection
	{
		List<Line> lines;

		/** Crops that become plantable along the way, in the order they do. */
		List<Unlock> unlocks;

		/** Experience from planting the lot. */
		double xp;

		/** The same if the level never moved, which is the assumption being made visible. */
		double xpAtStartLevel;

		int startLevel;
		int endLevel;

		/** Whether the bank is enough to get there at all. */
		boolean reachesMaxLevel;

		/** Seeds needed to reach 99, and what they pay. Zero where it is never reached. */
		int seedsToMaxLevel;
		double xpToMaxLevel;

		/** What is left once 99 is behind you, and what it would add anyway. */
		int seedsBeyondMaxLevel;
		double xpBeyondMaxLevel;

		/**
		 * Crops held that never become plantable, even at the level this ends on.
		 *
		 * <p>Counted rather than projected. There is no honest number for a crop you cannot
		 * reach — saying what it would be worth at a level you do not get to is a number about
		 * a different account.
		 */
		int lockedCrops;

		public boolean isEmpty()
		{
			return lines.isEmpty();
		}
	}

	private PlantOutEstimate()
	{
	}

	/**
	 * Plants the whole bank and reports where it gets you.
	 *
	 * @param owned         seeds held per crop, as {@code SeedInventoryStore.getOwned} reports
	 * @param patchesByType available patches of each type — the cycle size, and the reason a crop
	 *                      whose patches are all switched off is left out entirely
	 * @param compostByType what the player has chosen to treat each type with
	 * @param farmingXp     total Farming experience, not the level: the simulation adds to it, and
	 *                      starting from the level alone throws away up to a whole level of
	 *                      progress before the first patch is planted
	 */
	public static Projection of(Map<Seed, Integer> owned,
		Map<PatchImplementation, Integer> patchesByType,
		Map<PatchImplementation, CompostTier> compostByType,
		FarmingBonuses bonuses, int farmingXp)
	{
		int startLevel = levelAt(farmingXp);

		Map<Seed, Integer> held = new EnumMap<>(Seed.class);
		Map<Seed, Integer> remaining = new EnumMap<>(Seed.class);
		stock(owned, patchesByType, held, remaining);

		Map<Seed, Integer> planted = new EnumMap<>(Seed.class);
		Map<Seed, Double> xpBySeed = new EnumMap<>(Seed.class);
		Map<Seed, Double> itemsBySeed = new EnumMap<>(Seed.class);

		List<Unlock> unlocks = new ArrayList<>();
		Set<Seed> announced = EnumSet.noneOf(Seed.class);
		for (Seed seed : remaining.keySet())
		{
			if (seed.getLevelRequirement() <= startLevel)
			{
				announced.add(seed);
			}
		}

		double xp = farmingXp;
		double gained = 0;
		int seedsPlanted = 0;

		// Captured the moment 99 is crossed, so the bank can be split into what got you there
		// and what was surplus to it.
		double xpAtMaxLevel = -1;
		int seedsAtMaxLevel = 0;

		for (int step = 0; step < MAX_STEPS && !remaining.isEmpty(); step++)
		{
			int level = levelAt(xp);

			for (Seed seed : remaining.keySet())
			{
				if (!announced.contains(seed) && seed.getLevelRequirement() <= level)
				{
					announced.add(seed);
					unlocks.add(new Unlock(seed.getLevelRequirement(), seed, held.get(seed)));
				}
			}

			if (xpAtMaxLevel < 0 && level >= Experience.MAX_REAL_LEVEL)
			{
				xpAtMaxLevel = gained;
				seedsAtMaxLevel = seedsPlanted;
			}

			Map<PatchImplementation, Seed> choice = new EnumMap<>(PatchImplementation.class);
			double perCycleXp = 0;
			for (Map.Entry<PatchImplementation, Integer> type : patchesByType.entrySet())
			{
				int patches = type.getValue() == null ? 0 : type.getValue();
				if (patches <= 0)
				{
					continue;
				}
				Seed best = bestFor(type.getKey(), level, remaining, compostByType, bonuses);
				if (best == null)
				{
					continue;
				}
				choice.put(type.getKey(), best);
				perCycleXp += Math.min(patches, remaining.get(best))
					* xpPerPatch(best, level, compostByType, bonuses);
			}

			if (choice.isEmpty())
			{
				// Everything left is above the level, and nothing plantable remains to raise it.
				break;
			}

			int jump = cyclesToRun(choice, patchesByType, remaining, perCycleXp, level, xp);

			double stepXp = 0;
			for (Map.Entry<PatchImplementation, Seed> entry : choice.entrySet())
			{
				Seed seed = entry.getValue();
				int patches = patchesByType.get(entry.getKey());
				int plant = Math.min(patches * jump, remaining.get(seed));

				double perPatchItems = CropYieldModel.expected(seed, level,
					tierFor(entry.getKey(), compostByType), bonuses);
				double perPatchXp = xpFor(seed, perPatchItems, bonuses);

				stepXp += plant * perPatchXp;
				planted.merge(seed, plant, Integer::sum);
				xpBySeed.merge(seed, plant * perPatchXp, Double::sum);
				itemsBySeed.merge(seed, plant * perPatchItems, Double::sum);
				seedsPlanted += plant * seed.getSeedsPerPatch();

				int left = remaining.get(seed) - plant;
				if (left <= 0)
				{
					remaining.remove(seed);
				}
				else
				{
					remaining.put(seed, left);
				}
			}

			xp += stepXp;
			gained += stepXp;
		}

		return assemble(held, planted, xpBySeed, itemsBySeed, unlocks, patchesByType,
			compostByType, bonuses, farmingXp, startLevel, gained, seedsPlanted,
			xpAtMaxLevel, seedsAtMaxLevel, remaining);
	}

	/**
	 * Turns seeds held into patches fillable, dropping anything with nowhere to go.
	 *
	 * <p>A crop whose patches are all switched off is left out entirely — the availability
	 * invariant, and also what stops a cycle of zero patches from never finishing the stack.
	 */
	private static void stock(Map<Seed, Integer> owned,
		Map<PatchImplementation, Integer> patchesByType,
		Map<Seed, Integer> held, Map<Seed, Integer> remaining)
	{
		for (Map.Entry<Seed, Integer> entry : owned.entrySet())
		{
			Seed seed = entry.getKey();
			int count = entry.getValue() == null ? 0 : entry.getValue();
			if (count <= 0 || patchesByType.getOrDefault(seed.getPatchType(), 0) <= 0)
			{
				continue;
			}

			int fillable = count / seed.getSeedsPerPatch();
			if (fillable > 0)
			{
				held.put(seed, count);
				remaining.put(seed, fillable);
			}
		}
	}

	/**
	 * How many cycles can be run before anything about them changes.
	 *
	 * <p>The reason this is not one cycle at a time. A bank of ten thousand seeds through five
	 * patches is two thousand cycles, and re-picking the best crop for each of them would put a
	 * seven-figure loop on the Swing thread. Nothing changes within a stretch except the level
	 * and the stock, so the step runs until one of those two does: whichever of "cycles until
	 * the next level" and "cycles until a stack empties" comes first.
	 *
	 * <p>That also bounds the simulation. Every step ends on a level-up or an empty stack, so
	 * there can be at most 99 of the first and one per crop of the second.
	 */
	private static int cyclesToRun(Map<PatchImplementation, Seed> choice,
		Map<PatchImplementation, Integer> patchesByType, Map<Seed, Integer> remaining,
		double perCycleXp, int level, double xp)
	{
		int jump = Integer.MAX_VALUE;
		for (Map.Entry<PatchImplementation, Seed> entry : choice.entrySet())
		{
			int patches = patchesByType.get(entry.getKey());
			int stock = remaining.get(entry.getValue());
			// Rounded up: the final cycle of a stack may be a partial one, and it still runs.
			jump = Math.min(jump, (stock + patches - 1) / patches);
		}

		if (perCycleXp > 0 && level < Experience.MAX_REAL_LEVEL)
		{
			double toNext = Experience.getXpForLevel(level + 1) - xp;
			jump = Math.min(jump, (int) Math.min(Integer.MAX_VALUE,
				Math.ceil(toNext / perCycleXp)));
		}

		return Math.max(1, jump);
	}

	/** The best experience per patch this type can currently be planted with, or null. */
	@Nullable
	private static Seed bestFor(PatchImplementation type, int level, Map<Seed, Integer> remaining,
		Map<PatchImplementation, CompostTier> compostByType, FarmingBonuses bonuses)
	{
		Seed best = null;
		double bestXp = -1;

		for (Seed seed : Seed.forPatchType(type))
		{
			if (!remaining.containsKey(seed) || seed.getLevelRequirement() > level)
			{
				continue;
			}
			double xp = xpPerPatch(seed, level, compostByType, bonuses);
			if (xp > bestXp)
			{
				bestXp = xp;
				best = seed;
			}
		}
		return best;
	}

	private static double xpPerPatch(Seed seed, int level,
		Map<PatchImplementation, CompostTier> compostByType, FarmingBonuses bonuses)
	{
		CompostTier tier = tierFor(seed.getPatchType(), compostByType);
		return xpFor(seed, CropYieldModel.expected(seed, level, tier, bonuses), bonuses);
	}

	private static CompostTier tierFor(PatchImplementation type,
		Map<PatchImplementation, CompostTier> compostByType)
	{
		CompostTier tier = compostByType.get(type);
		return tier == null ? CompostTier.NONE : tier;
	}

	/**
	 * What one patch pays, experience-wise.
	 *
	 * <p>The same route {@code RunEstimate} takes — {@link CropXp#totalFor} over
	 * {@link CropYieldModel#xpHarvestsFor}, with the outfit applied last — so planting a patch is
	 * worth the same number here as it is in a run projection.
	 */
	private static double xpFor(Seed seed, double perPatchItems, FarmingBonuses bonuses)
	{
		CropXp rates = CropXp.forSeed(seed);
		return rates == null
			? 0
			: bonuses.applyOutfit(rates.totalFor(CropYieldModel.xpHarvestsFor(seed, perPatchItems)));
	}

	/** Builds the rows and the totals once the simulation has finished. */
	private static Projection assemble(Map<Seed, Integer> held, Map<Seed, Integer> planted,
		Map<Seed, Double> xpBySeed, Map<Seed, Double> itemsBySeed, List<Unlock> unlocks,
		Map<PatchImplementation, Integer> patchesByType,
		Map<PatchImplementation, CompostTier> compostByType, FarmingBonuses bonuses,
		int farmingXp, int startLevel, double gained, int seedsPlanted,
		double xpAtMaxLevel, int seedsAtMaxLevel, Map<Seed, Integer> unplanted)
	{
		List<Line> lines = new ArrayList<>();
		double naive = 0;

		for (Map.Entry<Seed, Integer> entry : planted.entrySet())
		{
			Seed seed = entry.getKey();
			// Only what you could plant today counts towards the flat figure. A crop you unlock
			// on the way has no honest "at your current level" value - you cannot plant it.
			double atStart = seed.getLevelRequirement() <= startLevel
				? entry.getValue() * xpPerPatch(seed, startLevel, compostByType, bonuses)
				: 0;
			naive += atStart;

			lines.add(new Line(seed, held.get(seed), entry.getValue(),
				itemsBySeed.getOrDefault(seed, 0.0), xpBySeed.getOrDefault(seed, 0.0), atStart));
		}

		// Descending by experience per seed, so the list answers "which of these should I
		// actually be planting" without being asked the question.
		lines.sort(Comparator.comparingDouble(Line::getXpPerSeed).reversed());

		// Discovery is already in level order across steps, but several crops can come into
		// range on the same one - and within a step they arrive in whatever order the map holds
		// them, which is the enum's rather than the game's.
		unlocks.sort(Comparator.comparingInt(Unlock::getLevel));

		boolean reached = xpAtMaxLevel >= 0;
		return new Projection(lines, unlocks, gained, naive, startLevel,
			levelAt(farmingXp + gained), reached,
			reached ? seedsAtMaxLevel : 0,
			reached ? xpAtMaxLevel : 0,
			reached ? seedsPlanted - seedsAtMaxLevel : 0,
			reached ? gained - xpAtMaxLevel : 0,
			unplanted.size());
	}

	/**
	 * Farming level at a given total experience, capped at 99.
	 *
	 * <p>Capped because {@code getLevelForXp} keeps counting to 126 and the yield arithmetic does
	 * not: chance-to-save clamps at 99, but the level roll behind limpwurt does not, and a virtual
	 * level would quietly inflate it.
	 */
	private static int levelAt(double xp)
	{
		int clamped = (int) Math.min(Math.max(xp, 0), Experience.MAX_SKILL_XP);
		return Math.min(Experience.MAX_REAL_LEVEL, Experience.getLevelForXp(clamped));
	}
}
