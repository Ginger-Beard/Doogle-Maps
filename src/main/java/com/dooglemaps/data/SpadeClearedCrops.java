package com.dooglemaps.data;

import java.util.EnumSet;
import java.util.Set;

/**
 * The regrowing patch families a spade digs straight out, with no chop first.
 *
 * <p>Wiki-checked: a picked-clean bush or cactus has a Clear option that takes a spade and
 * empties the patch in one go. Fruit trees, calquats and celastrus regrow (or deplete) too,
 * but go through chop-and-stump — a flow the guide does not model — so they are deliberately
 * absent, and a stripped one still reads as finished.
 *
 * <p>Hand-written, like {@link NotableHarvests}: {@link PatchImplementation} is generated from
 * RuneLite core and cannot carry facts core has no use for. Shared by {@code GuidePlan} (the
 * dig-it-up step) and {@code RunPlanner.isActionable} (keeping the stop alive for the run);
 * the two must agree, or the run routes to a patch the guide will not speak about.
 */
public final class SpadeClearedCrops
{
	private static final Set<PatchImplementation> FAMILIES =
		EnumSet.of(PatchImplementation.BUSH, PatchImplementation.CACTUS);

	private SpadeClearedCrops()
	{
	}

	/** Whether a picked-clean crop of this family is cleared for replanting with a spade. */
	public static boolean isSpadeCleared(PatchImplementation implementation)
	{
		return FAMILIES.contains(implementation);
	}
}
