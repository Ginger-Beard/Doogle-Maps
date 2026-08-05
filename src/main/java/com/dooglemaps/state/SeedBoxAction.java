package com.dooglemaps.state;

/**
 * The two seed box actions that move seeds without the box being readable.
 *
 * <p>Opening the box is not here, and does not need to be: that syncs the container and the
 * game tells us its contents outright. These two do not, which is the whole problem.
 */
public enum SeedBoxAction
{
	/** Everything plantable in the inventory goes into the box. */
	FILL,
	/** The box tips its whole contents back into the inventory, leaving it empty. */
	EMPTY
}
