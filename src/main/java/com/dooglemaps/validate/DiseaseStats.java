package com.dooglemaps.validate;

import lombok.Data;

/**
 * How one crop under one compost tier has actually fared against disease.
 *
 * <p>The gap the harvest log structurally cannot fill. Every disease and death figure in the
 * plugin comes from the published constants, and there was no way to check any of it — because
 * <b>a dead patch produces no harvest</b>, so it never reaches {@link HarvestLog} at all. A
 * validation built only on harvests is blind to exactly the outcome it most needs to see.
 *
 * <p>Kept in the same shape as {@link CropHarvestStats} and for the same reason: the summed
 * prediction sits beside the summed actual, so totals compare like with like even though each
 * patch was predicted under its own location, compost and protection. Averaging the predictions
 * afterwards would not work — a ranarr in Weiss cannot be diseased at all, and folding its
 * certainty into a mean with a Falador patch describes neither.
 */
@Data
public class DiseaseStats
{
	/** Display name of the crop, e.g. "Ranarr". */
	private String crop;

	/** {@link com.dooglemaps.data.CompostTier} name, so the tiers can be compared. */
	private String compost;

	/**
	 * Growth cycles seen through to an outcome — harvestable or dead.
	 *
	 * <p>The denominator, and it counts <i>observed</i> cycles rather than patches planted. A
	 * cycle that began and ended while you were away from the patch was never seen and is not
	 * counted, which is right: it is missing data, not a survival.
	 */
	private int cycles;

	/**
	 * Cycles that caught a disease at any point, cured or not.
	 *
	 * <p>Curing one does not make it a survival — the roll still went against you, and it is the
	 * roll the published rate is about.
	 */
	private int diseased;

	/** Cycles that went all the way to dead, which is the one that costs you the crop. */
	private int died;

	/**
	 * Sum of the survival chance predicted for those same cycles.
	 *
	 * <p>So the comparison is against what was expected for these particular patches, protection
	 * and locations included, rather than against a crop-wide constant.
	 */
	private double predictedSurvivals;

	private long firstSeen;
	private long lastSeen;

	/** Cycles that came through without ever being diseased. */
	public int getSurvived()
	{
		return cycles - diseased;
	}

	/** Observed survival rate, 0 to 1, or 0 before anything has been seen. */
	public double getSurvivalRate()
	{
		return cycles == 0 ? 0 : (double) getSurvived() / cycles;
	}

	/** What the plugin expected to survive, as a rate over the same cycles. */
	public double getPredictedSurvivalRate()
	{
		return cycles == 0 ? 0 : predictedSurvivals / cycles;
	}

	/** Share of cycles that ended dead rather than merely diseased. */
	public double getDeathRate()
	{
		return cycles == 0 ? 0 : (double) died / cycles;
	}
}
