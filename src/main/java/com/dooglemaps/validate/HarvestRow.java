package com.dooglemaps.validate;

import lombok.Value;

/**
 * One patch, as {@code harvests.csv} recorded it.
 *
 * <p>The per-patch grain {@link CropHarvestStats} deliberately does not keep. Rolled-up totals
 * answer anything that is a <i>sum</i> over patches — and that turned out to include the luck
 * percentile, which is why the rolled-up store got so far. What a running total cannot
 * reconstruct is <b>shape</b> or <b>sequence</b>: where your patches actually clustered, which
 * ones happened in the same sitting, and how yield moved as the level did. All three need the
 * rows back, and this is what one looks like.
 *
 * <p>Only the fields the analyses use. The file carries the gear flags too, and they are left
 * out on purpose: the contribution of secateurs is only computable for an account that has
 * farmed both with and without them, which is nearly nobody, and parsing a column to answer
 * nothing is how a reader grows.
 */
@Value
class HarvestRow
{
	/** Epoch seconds. What runs are clustered on. */
	long at;

	String crop;
	String patch;

	/** Farming level at the time, which is what makes yield-against-level answerable. */
	int level;

	String compost;

	double predicted;
	int actual;
	double xp;

	/** False where the patch was left standing, in which case it is not a yield observation. */
	boolean completed;

	/** How far this patch landed from what was predicted for it. */
	double getSurplus()
	{
		return actual - predicted;
	}
}
