package com.dooglemaps.data;

import lombok.Value;

/**
 * The decoded contents of a patch: what is in it, what it is doing, and how far along.
 *
 * @see PatchImplementation#forVarbitValue(int)
 */
@Value
public class ProduceState
{
	Produce produce;
	CropState cropState;
	int stage;

	/** Total number of stages in whichever phase this crop is currently in. */
	public int getStages()
	{
		return cropState == CropState.HARVESTABLE || cropState == CropState.FILLING
			? produce.getHarvestStages()
			: produce.getStages();
	}

	/**
	 * Minutes per growth tick in the current phase, or 0 when the patch is not advancing
	 * on its own (dead, diseased, empty).
	 */
	public int getTickRate()
	{
		switch (cropState)
		{
			case HARVESTABLE:
				return produce.getRegrowTickrate();
			case GROWING:
				return produce.getTickrate();
			default:
				return 0;
		}
	}
}
