package com.dooglemaps.route;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropXp;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.Seed;
import com.dooglemaps.timer.CropYieldModel;
import com.dooglemaps.timer.FarmingBonuses;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Value;

/**
 * What a whole run is worth, before you set off.
 *
 * <p>Adds up the per-patch figures the rows already show, over the patch types picked for the
 * run and the seeds picked to fill them. The useful question is not "what is this ranarr patch
 * worth" but "is this run worth doing before I log out", and that needs the total.
 *
 * <p>Seeds are allocated the way a player allocates them: <b>best first, until you run out</b>.
 * With four ranarr seeds and eight herb patches you plant ranarr in four and fall back to
 * whatever else was picked for the rest — so the estimate has to model stock, not just
 * multiply one seed by the patch count. "Best" is taken as the highest experience per patch,
 * which for herbs and allotments is also the most valuable.
 *
 * <p>Compost is a parameter rather than a guess. It is applied during the run, so there is
 * nothing to read off a patch, and assuming ultracompost would inflate every figure by up to
 * double while assuming none understates it by as much. The panel prices several tiers and
 * shows them side by side instead; {@link #getCompost()} records which one a given estimate
 * used.
 */
@Value
public class RunEstimate
{
	/**
	 * How likely a crop is to reach harvest.
	 *
	 * <p>A function rather than a value because it depends on the patches themselves: disease
	 * risk varies by crop, by compost, by whether a farmer was paid, and by location — Weiss
	 * and Trollheim are immune outright, and a diary makes three more so.
	 */
	@FunctionalInterface
	public interface Survival
	{
		/** Always survives. For callers that do not model disease. */
		Survival CERTAIN = (seed, compost) -> 1;

		double chanceFor(Seed seed, CompostTier compost);
	}

	/** One patch type's contribution, so the panel can break the total down. */
	@Value
	public static class Line
	{
		PatchImplementation type;
		Seed seed;
		/** Patches this seed will fill, limited by stock and by how many patches there are. */
		int patches;
		/** Expected harvest, already discounted by the chance the crop dies first. */
		double expectedYield;
		double expectedXp;
		/** Chance one of these patches reaches harvest, 1.0 when nothing can go wrong. */
		double survivalChance;
	}

	List<Line> lines;
	double totalXp;
	double totalYield;

	/**
	 * Share of patches expected to survive to harvest, averaged across the run.
	 *
	 * <p>1.0 when nothing can go wrong. Well under it for untreated herbs, which is the whole
	 * reason this is modelled: an untreated herb patch is roughly a coin flip.
	 */
	double survivalChance;

	/** Patches in the run that no picked seed can fill. */
	int unfilledPatches;

	/** The compost tier the estimate assumed, which is none. */
	CompostTier compost;

	public boolean isEmpty()
	{
		return lines.isEmpty();
	}

	/**
	 * Estimates a run.
	 *
	 * @param patchesByType how many patches of each type the run will visit
	 * @param selected      the seeds picked for the run
	 * @param ownedSeeds    how many of each seed the player has, across every store
	 * @param farmingLevel  for the chance-to-save curve
	 */
	public static RunEstimate forRun(Map<PatchImplementation, Integer> patchesByType,
		Set<Seed> selected, Map<Seed, Integer> ownedSeeds, int farmingLevel,
		FarmingBonuses bonuses)
	{
		return forRun(patchesByType, selected, ownedSeeds, farmingLevel, bonuses, CompostTier.NONE);
	}

	/**
	 * Estimates a run under a particular treatment.
	 *
	 * @param compost the tier assumed on every patch, since none has been applied yet
	 */
	public static RunEstimate forRun(Map<PatchImplementation, Integer> patchesByType,
		Set<Seed> selected, Map<Seed, Integer> ownedSeeds, int farmingLevel,
		FarmingBonuses bonuses, CompostTier compost)
	{
		Map<PatchImplementation, CompostTier> uniform = new LinkedHashMap<>();
		patchesByType.keySet().forEach(type -> uniform.put(type, compost));
		return forRun(patchesByType, selected, ownedSeeds, farmingLevel, bonuses, uniform);
	}

	/**
	 * Estimates a run where each patch type is treated differently.
	 *
	 * <p>Which is how people farm: ultra on the herbs, nothing on the hops. The per-type
	 * choice comes from the dropdowns beside each tab's seed list.
	 */
	public static RunEstimate forRun(Map<PatchImplementation, Integer> patchesByType,
		Set<Seed> selected, Map<Seed, Integer> ownedSeeds, int farmingLevel,
		FarmingBonuses bonuses, Map<PatchImplementation, CompostTier> compostByType)
	{
		return forRun(patchesByType, selected, ownedSeeds, farmingLevel, bonuses, compostByType,
			(seed, compost) -> 1);
	}

	/**
	 * Estimates a run, discounting each crop by its chance of reaching harvest.
	 *
	 * @param survival chance a patch of this crop, treated this way, survives. Supplied rather
	 *                 than computed because it depends on <i>where</i> the patches are — a
	 *                 Weiss herb patch cannot be diseased and a Falador one very much can — and
	 *                 this class only sees how many there are.
	 */
	public static RunEstimate forRun(Map<PatchImplementation, Integer> patchesByType,
		Set<Seed> selected, Map<Seed, Integer> ownedSeeds, int farmingLevel,
		FarmingBonuses bonuses, Map<PatchImplementation, CompostTier> compostByType,
		Survival survival)
	{
		return forRun(patchesByType, selected, ownedSeeds, farmingLevel, bonuses, compostByType,
			survival, ProtectionBudget.NONE);
	}

	/**
	 * Estimates a run, limited by how many patches the player can afford to protect.
	 *
	 * <p>The budget is what makes a mixed seed selection behave sensibly. Six tree patches with
	 * magic and yew both picked and 75 coconuts in the bank fills three patches with magic — all
	 * the coconuts cover — and the remaining three with yew, rather than promising six magics and
	 * leaving three of them unprotected.
	 */
	public static RunEstimate forRun(Map<PatchImplementation, Integer> patchesByType,
		Set<Seed> selected, Map<Seed, Integer> ownedSeeds, int farmingLevel,
		FarmingBonuses bonuses, Map<PatchImplementation, CompostTier> compostByType,
		Survival survival, ProtectionBudget protection)
	{
		List<Line> lines = new ArrayList<>();
		double totalXp = 0;
		double totalYield = 0;
		int unfilled = 0;
		double weightedSurvival = 0;
		int filledPatches = 0;

		for (Map.Entry<PatchImplementation, Integer> entry : patchesByType.entrySet())
		{
			PatchImplementation type = entry.getKey();
			int remaining = entry.getValue();
			CompostTier compost = compostByType.getOrDefault(type, CompostTier.NONE);

			for (Seed seed : bestFirst(selected, type, farmingLevel))
			{
				if (remaining <= 0)
				{
					break;
				}

				int owned = ownedSeeds.getOrDefault(seed, 0);
				int fillable = Math.min(remaining, owned / seed.getSeedsPerPatch());

				// Capped by what can be paid for, and only for crops the player asked to protect.
				// An unprotected crop is unconstrained — capping it to zero would stop the run
				// planting anything it could not afford to protect, which is backwards.
				//
				// Whatever is not affordable is left in `remaining` and offered to the next crop,
				// which is the whole mechanism: the coconuts run out and the yews take over.
				fillable = Math.min(fillable, protection.affordablePatches(seed));
				if (fillable <= 0)
				{
					continue;
				}
				protection.spend(seed, fillable);

				// A patch that dies gives nothing, so the harvest is worth what it yields times
				// the chance it survives. Without this an untreated herb run reads about twice
				// what it delivers.
				// A protected patch cannot die, and by construction every patch allocated above is
				// one the budget could cover — so this is not an assumption, it is what was just
				// paid for.
				double survives = protection.isProtecting(seed) ? 1 : survival.chanceFor(seed, compost);
				double perPatchYield = yieldFor(seed, farmingLevel, bonuses, compost) * survives;
				// The outfit multiplies experience and nothing else, so it lands here rather
				// than anywhere near the yield.
				double perPatchXp = bonuses.applyOutfit(xpFor(seed, perPatchYield));

				lines.add(new Line(type, seed, fillable,
					perPatchYield * fillable, perPatchXp * fillable, survives));
				totalYield += perPatchYield * fillable;
				totalXp += perPatchXp * fillable;
				weightedSurvival += survives * fillable;
				filledPatches += fillable;
				remaining -= fillable;
			}

			unfilled += Math.max(0, remaining);
		}

		// Grouped by patch type so a herb/allotment/flower run reads in the same order as the
		// tabs, then best crop first within each type.
		lines.sort(Comparator
			.comparingInt((Line line) -> line.getType().ordinal())
			.thenComparing(Comparator.comparingDouble(Line::getExpectedXp).reversed()));
		// A single tier only describes the run when every type shares one; otherwise the panel
		// reads the choice back per type.
		CompostTier uniform = null;
		for (PatchImplementation type : patchesByType.keySet())
		{
			CompostTier tier = compostByType.getOrDefault(type, CompostTier.NONE);
			if (uniform == null)
			{
				uniform = tier;
			}
			else if (uniform != tier)
			{
				uniform = CompostTier.NONE;
				break;
			}
		}
		return new RunEstimate(lines, totalXp, totalYield,
			filledPatches == 0 ? 1 : weightedSurvival / filledPatches,
			unfilled, uniform == null ? CompostTier.NONE : uniform);
	}

	/**
	 * Combines estimates priced separately, as one run.
	 *
	 * <p>Exists because a patch type can be split into planting groups — protected herbs get a
	 * different seed and a different compost from ordinary ones — and each group has to be priced
	 * against <i>its own</i> selection. Pricing them together would let the expensive seed fill
	 * every patch of the type, which is exactly the arrangement the split exists to prevent.
	 *
	 * <p>Merging rather than teaching this class about groups keeps the arithmetic in one place
	 * and untouched: every part was computed by the same method under the same rules, so summing
	 * them cannot disagree with pricing them.
	 */
	public static RunEstimate merge(List<RunEstimate> parts)
	{
		List<Line> lines = new ArrayList<>();
		double totalXp = 0;
		double totalYield = 0;
		int unfilled = 0;
		double weightedSurvival = 0;
		int filledPatches = 0;
		CompostTier uniform = null;
		boolean mixed = false;

		for (RunEstimate part : parts)
		{
			lines.addAll(part.getLines());
			totalXp += part.getTotalXp();
			totalYield += part.getTotalYield();
			unfilled += part.getUnfilledPatches();

			// Re-weighted from the lines rather than averaging the averages, which would give a
			// one-patch group the same say as a seven-patch one.
			for (Line line : part.getLines())
			{
				weightedSurvival += line.getSurvivalChance() * line.getPatches();
				filledPatches += line.getPatches();
			}

			if (part.getLines().isEmpty())
			{
				continue;
			}
			if (uniform == null)
			{
				uniform = part.getCompost();
			}
			else if (uniform != part.getCompost())
			{
				mixed = true;
			}
		}

		lines.sort(Comparator
			.comparingInt((Line line) -> line.getType().ordinal())
			.thenComparing(Comparator.comparingDouble(Line::getExpectedXp).reversed()));

		return new RunEstimate(lines, totalXp, totalYield,
			filledPatches == 0 ? 1 : weightedSurvival / filledPatches,
			unfilled, mixed || uniform == null ? CompostTier.NONE : uniform);
	}

	/**
	 * The picked seeds for one patch type, most experience per patch first.
	 *
	 * <p>Seeds the player cannot yet plant are left out entirely: including them would have
	 * the estimate quietly assume a patch gets filled with something unplantable.
	 */
	static List<Seed> bestFirst(Set<Seed> selected, PatchImplementation type, int level)
	{
		List<Seed> usable = new ArrayList<>();
		for (Seed seed : selected)
		{
			if (seed.getPatchType() == type && level >= seed.getLevelRequirement())
			{
				usable.add(seed);
			}
		}
		// Ranked without boosts on purpose: which seed is worth most does not change with
		// compost or secateurs, and re-ranking per tier would make the table's rows disagree
		// about what got planted where.
		usable.sort(Comparator.comparingDouble((Seed seed) ->
			xpFor(seed, yieldFor(seed, level, FarmingBonuses.NONE, CompostTier.NONE))).reversed());
		return usable;
	}

	/**
	 * Expected items from one patch.
	 *
	 * <p>Through {@link CropYieldModel} rather than the chance-to-save formula directly,
	 * because only some crops use it. Limpwurt rolls a level-scaled bonus, a bush hands over
	 * whatever has regrown, and a marigold gives exactly one — treating all three as "one"
	 * made a flower run read as barely worth leaving the bank for.
	 */
	private static double yieldFor(Seed seed, int level, FarmingBonuses bonuses, CompostTier compost)
	{
		return CropYieldModel.expected(seed, level, compost, bonuses);
	}

	/** Experience from one patch, planting through to the last harvest. */
	private static double xpFor(Seed seed, double expectedYield)
	{
		CropXp xp = CropXp.forSeed(seed);
		// Flowers pay their harvest award once for the patch, not per item picked.
		return xp == null ? 0 : xp.totalFor(CropYieldModel.xpHarvestsFor(seed, expectedYield));
	}

	/**
	 * Prices a harvest-only trip, against what is already growing.
	 *
	 * <h2>Why this cannot go through {@link #forRun}</h2>
	 *
	 * That method starts from the seeds the player picked and fills empty patches with them. A
	 * harvest-only run has no seed by definition — you are going to pick fruit off trees that are
	 * already there — so it produced no lines at all, and the panel, seeing an empty selection,
	 * hid the table. The run was priced at nothing because it was being asked the wrong question.
	 *
	 * <p>Two differences beyond the starting point, and both matter:
	 *
	 * <ul>
	 *   <li><b>Harvest experience only.</b> Nothing is planted and nothing is checked, so the
	 *       plant and check awards are not paid. For a fruit tree those are most of the total —
	 *       counting them would roughly quadruple the figure.</li>
	 *   <li><b>Nothing can die.</b> The crop is grown and in the ground; disease is a risk while
	 *       growing, and this trip does not involve any growing.</li>
	 * </ul>
	 */
	public static RunEstimate forHarvest(Map<Produce, Integer> ripeByProduce, int farmingLevel,
		FarmingBonuses bonuses)
	{
		List<Line> lines = new ArrayList<>();
		double totalXp = 0;
		double totalYield = 0;
		for (Map.Entry<Produce, Integer> entry : ripeByProduce.entrySet())
		{
			Seed seed = Seed.forProduce(entry.getKey());
			CropXp xp = CropXp.forSeed(seed);
			if (seed == null || xp == null)
			{
				continue;
			}

			int count = entry.getValue();
			// No compost: a grown bush or fruit tree holds a fixed stock, and compost only ever
			// acted on the roll that filled it — which happened when it was planted, not now.
			double perPatchYield = CropYieldModel.expected(seed, farmingLevel, CompostTier.NONE,
				bonuses);
			double perPatchXp = bonuses.applyOutfit(
				xp.getHarvestXp() * CropYieldModel.xpHarvestsFor(seed, perPatchYield));

			lines.add(new Line(seed.getPatchType(), seed, count,
				perPatchYield * count, perPatchXp * count, 1));
			totalYield += perPatchYield * count;
			totalXp += perPatchXp * count;
		}

		lines.sort(Comparator.comparingDouble(Line::getExpectedXp).reversed());
		return new RunEstimate(lines, totalXp, totalYield, 1, 0, CompostTier.NONE);
	}

	/** Somewhere to start from when nothing is picked. */
	public static RunEstimate empty()
	{
		return new RunEstimate(new ArrayList<>(), 0, 0, 1, 0, CompostTier.NONE);
	}

	/** The lines keyed by type, for a panel that wants to group them. */
	public Map<PatchImplementation, List<Line>> byType()
	{
		Map<PatchImplementation, List<Line>> grouped = new LinkedHashMap<>();
		for (Line line : lines)
		{
			grouped.computeIfAbsent(line.getType(), k -> new ArrayList<>()).add(line);
		}
		return grouped;
	}
}
