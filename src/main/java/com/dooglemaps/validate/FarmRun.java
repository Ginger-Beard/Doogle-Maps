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

	/**
	 * Experience the harvest records in this sitting claimed.
	 *
	 * <p>Kept under its own name because it is <b>not</b> the run's experience and reporting it
	 * as such was the worst number on the Stats tab. See {@link #getXp}.
	 */
	private double harvestXp;

	/**
	 * Every Farming experience drop seen while this sitting was in progress.
	 *
	 * <p>Zero for a run reconstructed from the CSV, which records a row per patch and nothing
	 * about what else was earned beside it.
	 */
	private double skillXp;

	/**
	 * The experience this sitting actually earned.
	 *
	 * <h2>The harvest records hold a fifth of it</h2>
	 *
	 * A {@link HarvestRecord} opens when a patch produces an item, and the great majority of
	 * farming experience is not paid that way: <b>check-health on a tree or a fruit tree opens no
	 * record at all</b>, and a yew check is 7,069 experience against a ranarr pick's 30.5. Over
	 * the audited month the account gained about 3.35M farming experience and the harvest log
	 * accounts for 710k of it — 20%.
	 *
	 * <p>Everything downstream inherited the factor of five. "11.3k xp a run" was really 53k;
	 * "21.2k a day" was 100k; and {@code describeRunsToNextLevel} divided a <i>real</i>
	 * experience requirement by that partial rate and told the player 34 more runs to 91 when the
	 * honest answer was about seven. A five-times-wrong estimate is worse than no estimate.
	 *
	 * <p>So the larger of the two is taken rather than the sum. Every experience drop a harvest
	 * record saw is also a drop {@link #skillXp} saw, so adding them would double-count the
	 * harvests; and where only one source has anything — a historical run read back from the
	 * file, a tree run that picked nothing — the max is that one. It is a strict improvement on
	 * either alone and it cannot exceed the farming experience actually gained.
	 *
	 * <p>A history spanning the change reads mixed, and unavoidably: runs from before it carry
	 * only what their rows recorded, because nothing else was written down. The average converges
	 * as you play.
	 */
	public double getXp()
	{
		return Math.max(harvestXp, skillXp);
	}

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
		// On the timestamp rather than the patch count, because a run can now be opened by an
		// experience drop before any patch is picked - a tree run checks health and harvests
		// nothing at all.
		if (startedAt == 0)
		{
			startedAt = row.getAt();
		}
		endedAt = Math.max(endedAt, row.getAt());
		patches++;
		items += row.getActual();
		harvestXp += row.getXp();
	}

	/** Folds in a Farming experience drop that landed during this sitting. */
	void addSkillXp(long at, double gained)
	{
		if (startedAt == 0)
		{
			startedAt = at;
		}
		endedAt = Math.max(endedAt, at);
		skillXp += gained;
	}
}
