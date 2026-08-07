package com.dooglemaps.timer;

import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Produce;
import lombok.Value;

/**
 * A patch's state brought up to the present.
 *
 * <p>Where a {@code PatchSnapshot} says "this is what it looked like at 14:02", a
 * projection says "so right now it must be at stage 3 of 5, ready around 15:20".
 */
@Value
public class PatchProjection
{
	FarmPatch patch;
	Produce produce;
	CropState cropState;

	/** Current stage after projecting elapsed time forward, 0-based. */
	int stage;
	/** Total stages in the current phase. */
	int stages;

	/**
	 * Epoch seconds when the crop finishes, or 0 when there is nothing to wait for
	 * (empty, dead, diseased, or already harvestable and not regrowing).
	 */
	long doneEstimate;

	/**
	 * Harvests left before the patch is used up, for crops that give several. 0 when the
	 * crop is not harvestable or gives only one.
	 */
	int livesRemaining;

	/**
	 * Epoch seconds when the next harvest grows back, for crops that regrow — a fruit tree
	 * you want to keep rather than clear. 0 when nothing is regrowing.
	 */
	long regrowEstimate;

	Confidence confidence;

	/** Whether the patch was still advancing when we last had real information. */
	boolean stale;

	/** Epoch seconds when this patch's state was last actually confirmed. */
	long lastSeen;

	/**
	 * Whether what is standing there is a stump rather than a tree.
	 *
	 * <p>Carried on the projection rather than re-derived by each caller because it needs the raw
	 * varbit value, which stops at {@code GrowthTimer}. See
	 * {@link com.dooglemaps.data.PatchImplementation#isStumpVarbitValue}.
	 */
	boolean stump;

	public boolean isReady()
	{
		return cropState == CropState.HARVESTABLE
			|| (doneEstimate > 0 && doneEstimate <= System.currentTimeMillis() / 1000L);
	}

	public boolean isEmpty()
	{
		return produce == null || !produce.isCrop();
	}

	/**
	 * Whether this kind of crop grows more produce back rather than being used up.
	 *
	 * <p>A property of the crop, not of its current state — a fruit tree with a full load
	 * still regrows, it just has no room to right now. Distinct from {@link #isRegrowing()},
	 * which is about whether something is on its way.
	 */
	public boolean regrows()
	{
		return produce != null && produce.getRegrowTickrate() > 0;
	}

	/** Whether more produce is currently on its way. False when the plant is already full. */
	public boolean isRegrowing()
	{
		return regrowEstimate > 0;
	}

	/**
	 * Whether there is actually something on the patch to pick right now.
	 *
	 * <h2>Not the same as {@link #isReady()}, and the gap between them stalled runs</h2>
	 *
	 * For a crop that is used up — a herb, an allotment — {@code HARVESTABLE} and "has produce" are
	 * the same thing: you pick until the patch empties, and then it stops being harvestable.
	 *
	 * <p>A crop that <b>regrows</b> does not work that way. "No fruit on the tree" is one of its
	 * harvestable states, not a different state — a fruit tree has seven of them for a maximum of
	 * six fruit, and a picked-clean bush reads as {@code HARVESTABLE} with a stock of zero.
	 * Verified by decoding the varbit ranges: nineteen such states across bushes and fruit trees.
	 *
	 * <p>So asking {@code cropState == HARVESTABLE} answers "is this plant grown", and every caller
	 * that meant "is there anything to pick" got a permanent yes. The guide told you to harvest a
	 * tree you had just stripped, and — because a stop only ended when nothing was left actionable
	 * — a harvest-only run could never finish a stop at all.
	 */
	public boolean hasProduceToPick()
	{
		if (cropState != CropState.HARVESTABLE)
		{
			return false;
		}
		if (stump)
		{
			// A stump is harvestable by the varbit's reckoning and empty by any other. Saying so
			// here rather than only in the guide is what lets a harvest-only tree run finish: the
			// stop stays actionable while anything is left to pick, and "the logs are already in
			// your pack" has to be able to end that.
			return false;
		}
		return !regrows() || livesRemaining > 0;
	}

	/**
	 * Whether there is a tree standing here that has been checked and can be cut down.
	 *
	 * <p>The middle of the three clicks a tree patch wants — check, chop, dig — and the one the
	 * plugin had no word for, because chopping and picking are the same {@code HARVESTABLE} state.
	 * See {@link com.dooglemaps.data.PatchImplementation#isStumpVarbitValue} for how the tree and
	 * the stump are told apart at all.
	 */
	public boolean isChoppable()
	{
		return cropState == CropState.HARVESTABLE
			&& !stump
			&& !isEmpty()
			&& (patch.getImplementation() == PatchImplementation.TREE
				|| patch.getImplementation() == PatchImplementation.HARDWOOD_TREE);
	}

	/**
	 * Whether this crop has finished growing but has not been checked yet.
	 *
	 * <h2>The state the guide had no word for</h2>
	 *
	 * A tree, bush, cactus or calquat is not harvestable when it finishes growing — it is grown and
	 * <i>unchecked</i>, which the game encodes as still {@code GROWING}. {@code GrowthTimer}
	 * deliberately declines to promote these to {@code HARVESTABLE}, because only the player can
	 * make that transition, and everything downstream then read the patch as "still growing, leave
	 * it alone".
	 *
	 * <p>So the almanac said <i>ready</i>, the planner correctly routed to it, and the guide had
	 * nothing to say — no step, no highlight, and the run walked on to another patch. A finished
	 * farming contract could be skipped entirely this way.
	 */
	public boolean needsHealthCheck()
	{
		return cropState == CropState.GROWING
			&& !isEmpty()
			&& patch.getImplementation().isHealthCheckRequired()
			&& isReady();
	}
}
