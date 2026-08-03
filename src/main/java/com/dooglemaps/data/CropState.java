package com.dooglemaps.data;

/**
 * What a patch's contents are currently doing.
 *
 * <p>Mirrors RuneLite core's {@code CropState} so the generated varbit tables decode to
 * the same vocabulary.
 */
public enum CropState
{
	/** Growing towards being harvestable. */
	GROWING,
	/** Fully grown and pickable. For regrowing crops, counting down its remaining lives. */
	HARVESTABLE,
	/** Stopped growing; dies at the end of this cycle unless cured. */
	DISEASED,
	/** Dead; needs digging up. */
	DEAD,
	/** Nothing planted. */
	EMPTY,
	/** Compost bins only: accumulating items. */
	FILLING
}
