package com.dooglemaps.timer;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.Produce;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
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

	/**
	 * Chance per growth tick of catching something, in 128ths, before compost.
	 *
	 * <p>Only where Jagex has published it. Herbs are the one that matters most — 27/128 a tick
	 * over three vulnerable ticks is a coin flip on whether an untreated patch survives at all.
	 *
	 * <p>Crops absent from here are treated as safe rather than guessed at. That understates
	 * the risk for allotments and hops, which is the direction that at least does not invent a
	 * penalty; see {@link #isRiskKnown}.
	 */
	private static final Map<PatchImplementation, Integer> BASE_RISK =
		ImmutableMap.of(
			PatchImplementation.HERB, 27,
			PatchImplementation.FRUIT_TREE, 18,
			PatchImplementation.CORAL, 8);

	/** Trees differ by species rather than by patch, and only two are published. */
	private static final Map<Produce, Integer> TREE_RISK =
		ImmutableMap.of(Produce.MAPLE, 13, Produce.MAGIC, 9);

	private DiseaseRisk()
	{
	}

	/** Whether a published disease rate exists for this crop at all. */
	public static boolean isRiskKnown(Produce produce)
	{
		if (produce == null || produce.getPatchImplementation() == null)
		{
			return false;
		}
		return TREE_RISK.containsKey(produce)
			|| BASE_RISK.containsKey(produce.getPatchImplementation());
	}

	/**
	 * The chance a freshly planted crop reaches harvest.
	 *
	 * <p>Disease is rolled once per growth tick, but neither the first stage nor a fully grown
	 * crop can catch anything, so a five-stage herb rolls three times. Compost is the whole
	 * defence: it cuts the rate by half, four fifths or nine tenths, which turns an untreated
	 * herb's 49% survival into 95%.
	 *
	 * <p>Returns 1 where nothing can go wrong — an immune crop, a disease-free patch, or one a
	 * farmer has been paid to watch — and also where no rate is published, since inventing a
	 * penalty would be worse than omitting one.
	 */
	public static double survivalChance(FarmPatch patch, Produce produce, CompostTier compost,
		boolean protectedByFarmer)
	{
		return survivalChance(patch, produce, compost, protectedByFarmer, false);
	}

	/**
	 * As above, told separately whether this patch is disease-free for <i>this</i> account.
	 *
	 * <p>Needed because {@link #isInherentlySafe} can only cover the patches whose unlock is
	 * provable from the patch existing at all — Trollheim and Weiss. Hosidius and Harmony are
	 * disease-free on a diary you can stand in the region without having, so whether they are
	 * safe is a fact about the player rather than about the patch, and it has to come from
	 * outside. See {@code ProtectedPatches}.
	 *
	 * @param diseaseFreeForPlayer whether the account's unlocks make this patch immune
	 */
	public static double survivalChance(FarmPatch patch, Produce produce, CompostTier compost,
		boolean protectedByFarmer, boolean diseaseFreeForPlayer)
	{
		if (produce == null || isInherentlySafe(patch, produce) || protectedByFarmer
			|| diseaseFreeForPlayer)
		{
			return 1;
		}

		Integer base = TREE_RISK.containsKey(produce)
			? TREE_RISK.get(produce)
			: BASE_RISK.get(produce.getPatchImplementation());
		if (base == null)
		{
			return 1;
		}

		// Rounded down to the nearest 128th after the reduction, as the wiki states.
		int rate = (int) Math.floor(base * remainingRisk(compost));
		int rolls = Math.max(0, produce.getStages() - 2);
		return Math.pow(1 - (rate / 128.0), rolls);
	}

	/** What share of the base rate survives a tier of compost. */
	private static double remainingRisk(CompostTier compost)
	{
		if (compost == null)
		{
			return 1;
		}
		switch (compost)
		{
			case COMPOST: return 0.5;
			case SUPERCOMPOST: return 0.2;
			case ULTRACOMPOST: return 0.1;
			default: return 1;
		}
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
