package com.dooglemaps.state;

import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Produce;
import java.util.EnumSet;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Whether a grown flower is standing guard over an allotment crop.
 *
 * <p>The third protection, and the one the plugin was blind to: an allotment beside a fully
 * grown flower of the right kind cannot be diseased at all — the same guarantee a payment
 * buys, for the cost of a flower seed. Wiki-checked: marigolds guard potatoes, onions and
 * tomatoes; rosemary guards cabbages; nasturtiums guard watermelons; a <b>white lily guards
 * every allotment crop</b>; woad and limpwurt guard nothing.
 *
 * <p>Only a <i>fully grown</i> flower counts, which is why this reads live patch state rather
 * than intent: a growing marigold protects nothing yet, and a picked one has stopped. That
 * fact is also why the guide holds a flower's pick back until the allotments beside it are
 * done — see {@code GuideTracker.flowersAfterAllotments}.
 */
public final class FlowerGuard
{
	private static final Set<Produce> MARIGOLD_WARDS = EnumSet.of(
		Produce.POTATO, Produce.ONION, Produce.TOMATO);

	private FlowerGuard()
	{
	}

	/**
	 * Whether this allotment crop is guarded by a grown flower in the same location.
	 *
	 * <p>False for anything that is not an allotment — the flower mechanic exists for
	 * allotments alone — and false when the flower patch has never been seen, which is the
	 * honest reading: an unknown guard is not a guard.
	 */
	public static boolean guarding(PatchStateStore patches, FarmPatch patch,
		@Nullable Produce crop)
	{
		if (crop == null || patch.getImplementation() != PatchImplementation.ALLOTMENT)
		{
			return false;
		}

		for (FarmPatch flower : FarmingWorldData.getPatches(PatchImplementation.FLOWER))
		{
			if (flower.getRegion().getRegionId() != patch.getRegion().getRegionId())
			{
				continue;
			}
			PatchSnapshot snapshot = patches.get(flower);
			if (snapshot != null && snapshot.getCropState() == CropState.HARVESTABLE
				&& guards(snapshot.getProduce(), crop))
			{
				return true;
			}
		}
		return false;
	}

	/** The wiki's table, as a predicate. */
	static boolean guards(@Nullable Produce flower, Produce crop)
	{
		if (flower == null)
		{
			return false;
		}
		switch (flower)
		{
			case WHITE_LILY:
				return true;
			case MARIGOLD:
				return MARIGOLD_WARDS.contains(crop);
			case ROSEMARY:
				return crop == Produce.CABBAGE;
			case NASTURTIUM:
				return crop == Produce.WATERMELON;
			default:
				return false;
		}
	}
}
