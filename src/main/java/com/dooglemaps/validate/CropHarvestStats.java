package com.dooglemaps.validate;

import lombok.Data;

/**
 * Everything harvested of one crop under one compost tier, rolled up.
 *
 * <p>Rolled up rather than kept as a list of harvests on purpose. A serious farmer does
 * thousands of patches, and neither the config store nor a stats panel wants that; an
 * aggregate is bounded at roughly fifty crops times four tiers whatever happens. The raw
 * per-patch rows still exist in the CSV for anyone who wants to go deeper.
 *
 * <p>Keeping the summed <b>prediction</b> alongside the summed actual is what lets this
 * serve two purposes at once. Comparing the totals stays valid even though the individual
 * harvests happened at different levels and with different gear, because each row's
 * prediction was computed under the conditions of that row. Averaging the predictions
 * afterwards would not have worked.
 */
@Data
public class CropHarvestStats
{
	/** Display name of the crop, e.g. "Ranarr". */
	private String crop;

	/** {@link com.dooglemaps.data.CompostTier} name, so tiers can be compared. */
	private String compost;

	/** Patches picked clean. Only these count towards the yield average. */
	private int harvests;

	/** Items from those completed patches. */
	private int items;

	/** Sum of what was predicted for those same patches. */
	private double predicted;

	/**
	 * Sum of the spread around those same predictions, as a variance.
	 *
	 * <p>Summed rather than averaged because that is the property that makes this work: the
	 * variances of independent patches add, exactly as their means do. So one running total is
	 * enough to say where a whole season's harvests landed, without keeping a single patch.
	 */
	private double predictedVariance;

	/**
	 * How many of {@link #harvests} contributed to {@link #predictedVariance}.
	 *
	 * <p>Kept because it is the only thing that can tell a complete total from a partial one.
	 * Patches recorded before this field existed added to {@code harvests} and {@code predicted}
	 * without adding to the variance, and so did any crop whose spread the plugin cannot name —
	 * either way the totals no longer describe the same set of patches, and a percentile drawn
	 * from them would be wrong rather than merely noisy. {@link #hasLuckPercentile} is that
	 * check, and it is why the figure stays hidden on an old history rather than lying.
	 */
	private int variancePatches;

	/** Farming experience seen while picking them. */
	private double xp;

	/** Biggest and smallest single-patch harvest, for a "best ever" line. */
	private int best;
	private int worst;

	/**
	 * Items from patches that were left standing.
	 *
	 * <p>Held apart rather than discarded. They are real items and belong in a lifetime
	 * total, but folding them into the average would drag it down with harvests that were
	 * never finished — which is the most likely way for a stats page to end up lying.
	 */
	private int partialItems;
	private double partialXp;

	private long firstHarvest;
	private long lastHarvest;

	/** Mean items per completed patch, or 0 before anything has been finished. */
	public double getAverageYield()
	{
		return harvests == 0 ? 0 : (double) items / harvests;
	}

	/** What the plugin expected to average over the same patches. */
	public double getAveragePredicted()
	{
		return harvests == 0 ? 0 : predicted / harvests;
	}

	/**
	 * Actual over predicted: 1.0 is a perfect model, above 1 means we are understating.
	 *
	 * <p>Noisy until a couple of dozen harvests are in — a single patch can swing it by a
	 * third — so treat it as a trend rather than a verdict.
	 */
	public double getAccuracy()
	{
		return predicted <= 0 ? 0 : items / predicted;
	}

	/** Every item of this crop ever seen, finished patches or not. */
	public int getTotalItems()
	{
		return items + partialItems;
	}

	public double getTotalXp()
	{
		return xp + partialXp;
	}

	// -------------------------------------------------------------------- luck

	/**
	 * Completed patches needed before a percentile is worth showing at all.
	 *
	 * <p>The spread grows like {@code √n} while the total grows like {@code n}, so a percentile
	 * over four patches is noise dressed as insight — and a stats page that invents patterns is
	 * worse than one that says nothing. Twenty is also comfortably where the normal
	 * approximation behind {@link #getLuckPercentile} becomes sound.
	 */
	public static final int MIN_PATCHES_FOR_LUCK = 20;

	/** Items over what was predicted for the same patches; negative is under. */
	public double getSurplus()
	{
		return predicted <= 0 ? 0 : items - predicted;
	}

	/**
	 * Whether there is enough here to say where the harvests landed.
	 *
	 * <p>Three conditions, and the middle one is the subtle one: the variance must cover
	 * <i>every</i> patch in the total, or it is being compared against a mean drawn from a
	 * larger set. See {@link #variancePatches}.
	 */
	public boolean hasLuckPercentile()
	{
		return harvests >= MIN_PATCHES_FOR_LUCK
			&& variancePatches == harvests
			&& predictedVariance > 0;
	}

	/**
	 * Where the actual total falls in the distribution of totals this many patches should give,
	 * from 0 to 100.
	 *
	 * <p>Not a comparison against other players, and deliberately not: the thing worth measuring
	 * against is the game's own RNG, which is fully specified. Each patch is a negative binomial
	 * with a mean and a variance the plugin already computes, those add over patches, and the
	 * central limit theorem makes the sum near enough normal by {@link #MIN_PATCHES_FOR_LUCK}.
	 * So a single account's own history answers it exactly, where "luckier than other people"
	 * would be a weaker claim even with the data to make it.
	 *
	 * <p>Meaningless unless {@link #hasLuckPercentile} holds; callers must ask first.
	 */
	public double getLuckPercentile()
	{
		return 100 * normalCdf(getSurplus() / Math.sqrt(predictedVariance));
	}

	/**
	 * The standard normal CDF, by Abramowitz and Stegun 7.1.26.
	 *
	 * <p>Accurate to about 1.5e-7, which is several orders of magnitude tighter than the
	 * sampling noise in anything that reaches it — the figure is rounded to a whole percentile
	 * before it is shown.
	 */
	private static double normalCdf(double z)
	{
		double sign = z < 0 ? -1 : 1;
		double x = Math.abs(z) / Math.sqrt(2);
		double t = 1 / (1 + 0.3275911 * x);
		double erf = 1 - ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t
			- 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x);
		return 0.5 * (1 + sign * erf);
	}
}
