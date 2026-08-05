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
}
