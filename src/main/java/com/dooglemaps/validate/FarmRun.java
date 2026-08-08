package com.dooglemaps.validate;

import lombok.Data;

/**
 * One sitting: the patches you harvested before you went and did something else.
 *
 * <p>Nothing records a "run" — the plugin has a guided mode, but most harvesting happens outside
 * it and a run that was never started still happened. What the log does record is <i>when</i>
 * each patch was picked, and a farm run has an unmistakable signature in that: a dozen patches
 * inside a quarter of an hour, then nothing for eighty minutes while the next lot grows. So runs
 * are reconstructed from the gaps rather than tracked, which means they cover the whole history
 * including everything harvested before anyone thought to count.
 */
@Data
public class FarmRun
{
	/** Epoch seconds of the first and last patch in the sitting. */
	private long startedAt;
	private long endedAt;

	private int patches;
	private int items;
	private double xp;

	/**
	 * How long the sitting itself took, in seconds.
	 *
	 * <p>First patch to last, so a run of one patch is zero seconds long rather than one patch
	 * long. That is the honest reading and it is why {@code activeXpPerHour} has to exclude
	 * them — a single-patch run divides a real number by nothing.
	 */
	public long getDuration()
	{
		return Math.max(0, endedAt - startedAt);
	}

	void add(HarvestRow row)
	{
		if (patches == 0)
		{
			startedAt = row.getAt();
		}
		endedAt = Math.max(endedAt, row.getAt());
		patches++;
		items += row.getActual();
		xp += row.getXp();
	}
}
