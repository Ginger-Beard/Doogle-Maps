package com.dooglemaps.timer;

import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
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

	Confidence confidence;

	/** Whether the patch was still advancing when we last had real information. */
	boolean stale;

	/** Epoch seconds when this patch's state was last actually confirmed. */
	long lastSeen;

	public boolean isReady()
	{
		return cropState == CropState.HARVESTABLE
			|| (doneEstimate > 0 && doneEstimate <= System.currentTimeMillis() / 1000L);
	}

	public boolean isEmpty()
	{
		return produce == null || !produce.isCrop();
	}
}
