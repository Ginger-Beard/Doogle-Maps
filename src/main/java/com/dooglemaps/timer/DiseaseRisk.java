package com.dooglemaps.timer;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Produce;
import com.google.common.collect.ImmutableSet;
import java.util.Set;

/** Which crops and patches disease can touch, for the confidence tiers in {@link Confidence}. */
public final class DiseaseRisk
{
	/** Crops that can never be diseased, wherever they are planted. */
	private static final Set<Produce> IMMUNE_PRODUCE = ImmutableSet.of(
		Produce.HESPORI,
		Produce.CRYSTAL_TREE,
		Produce.POISON_IVY,
		Produce.KRONOS,
		Produce.IASOR,
		Produce.ATTAS
	);

	/**
	 * Patch kinds no farmer will ever protect and that have no immunity: herbs (outside
	 * the disease-free locations below), flowers, mushrooms and belladonna. These are the
	 * crops whose cached timers deserve the least trust.
	 */
	private static final Set<PatchImplementation> UNPROTECTABLE = ImmutableSet.of(
		PatchImplementation.HERB,
		PatchImplementation.FLOWER,
		PatchImplementation.MUSHROOM,
		PatchImplementation.BELLADONNA
	);

	/**
	 * Regions where reaching the patch at all proves the unlock that makes it
	 * disease-free.
	 *
	 * <p>Trollheim's herb patch is disease-free after <i>My Arm's Big Adventure</i> and
	 * Weiss's after <i>Making Friends with My Arm</i>, and neither patch is reachable
	 * without that quest — so if the player has the patch switched on, the crop is safe.
	 * Grapes are free-protected by the Vinery gardener.
	 *
	 * <p>Deliberately excluded: Harmony Island, Hosidius, Falador Park and Civitas illa
	 * Fortis. Those are disease-free only once a <i>diary</i> is done, and you can stand
	 * in all four without having done it, so access proves nothing.
	 */
	private static final Set<Integer> DISEASE_FREE_REGIONS = ImmutableSet.of(
		11321,  // Troll Stronghold herb, after My Arm's Big Adventure
		11325,  // Weiss herb, after Making Friends with My Arm
		7223    // Kourend vinery, gardener protects grapes for free
	);

	private DiseaseRisk()
	{
	}

	/** Whether disease is impossible here, ignoring farmer payment. */
	public static boolean isInherentlySafe(FarmPatch patch, Produce produce)
	{
		return (produce != null && IMMUNE_PRODUCE.contains(produce))
			|| DISEASE_FREE_REGIONS.contains(patch.getRegion().getRegionId());
	}

	/** Whether paying a farmer is even an option for this patch. */
	public static boolean isProtectable(FarmPatch patch)
	{
		return patch.isProtectable() && !UNPROTECTABLE.contains(patch.getImplementation());
	}

	/**
	 * Whether a crop in this patch, in this state, could have diseased since we last saw
	 * it.
	 *
	 * @param stage 0-based current growth stage; disease cannot strike during stage 0
	 */
	public static boolean isAtRisk(FarmPatch patch, Produce produce, boolean isProtected, int stage)
	{
		if (isProtected || isInherentlySafe(patch, produce))
		{
			return false;
		}
		// A crop cannot be diseased in its first growth stage.
		return stage > 0;
	}
}
