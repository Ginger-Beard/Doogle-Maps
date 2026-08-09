package com.dooglemaps.state;

import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.Produce;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The spirit tree simultaneous-plant cap, which the game enforces and the plugin must too.
 *
 * <p>Wiki-checked: one tree at 83 Farming, two at 88, three at 93, four at 96, unlimited at
 * 99. Without this the allocation cheerfully assigned a seed to every empty spirit patch on
 * the account and the guide instructed a planting the game refuses.
 *
 * <h2>What counts as occupying a slot</h2>
 *
 * A tree that is growing, diseased or grown — the states the game charges the cap for. A dead
 * tree and an empty patch do not hold one. The dead case is the least certain reading of the
 * wiki and errs permissive: wrongly allowing a plant shows up as the game refusing one click,
 * where wrongly forbidding it silently plants fewer trees than the account could.
 *
 * <h2>Trimmed at the allocation, in both builders</h2>
 *
 * The guide and the loadout each build their own plantable list — deliberately, one asks
 * "plantable now" and the other "worth banking for" — so the cap trims both through this one
 * class, sorted by patch key first so the two keep choosing the same trees. The planner still
 * routes to an over-cap patch it happens to pass; the allocation gives it no seed, the guide
 * reports it skipped, and the nothing-to-do exemption keeps the run moving.
 */
public final class SpiritTrees
{
	private SpiritTrees()
	{
	}

	/** How many spirit trees this level may have planted at once. */
	public static int capFor(int farmingLevel)
	{
		if (farmingLevel >= 99)
		{
			return Integer.MAX_VALUE;
		}
		if (farmingLevel >= 96)
		{
			return 4;
		}
		if (farmingLevel >= 93)
		{
			return 3;
		}
		if (farmingLevel >= 88)
		{
			return 2;
		}
		return 1;
	}

	/** How many more could be planted right now, given what is already in the ground. */
	public static int plantableNow(PatchStateStore patches, int farmingLevel)
	{
		int cap = capFor(farmingLevel);
		if (cap == Integer.MAX_VALUE)
		{
			return Integer.MAX_VALUE;
		}

		int occupied = 0;
		for (FarmPatch patch : com.dooglemaps.data.FarmingWorldData.getPatches(
			PatchImplementation.SPIRIT_TREE))
		{
			PatchSnapshot snapshot = patches.get(patch);
			if (snapshot == null || snapshot.getProduce() == null
				|| snapshot.getProduce() == Produce.WEEDS)
			{
				continue;
			}
			CropState state = snapshot.getCropState();
			if (state == CropState.GROWING || state == CropState.DISEASED
				|| state == CropState.HARVESTABLE)
			{
				occupied++;
			}
		}
		return Math.max(0, cap - occupied);
	}

	/**
	 * The plantable list, cut down to what the cap allows. A no-op for every other group.
	 *
	 * <p>Sorted by patch key before the cut, so the guide's list and the loadout's list —
	 * built from different sources, in different orders — keep the same trees and the two
	 * cannot disagree about which patch gets the seed.
	 */
	public static List<FarmPatch> trimToCap(PatchStateStore patches, int farmingLevel,
		PlantingGroup group, List<FarmPatch> plantable)
	{
		if (group.getType() != PatchImplementation.SPIRIT_TREE)
		{
			return plantable;
		}

		int allowed = plantableNow(patches, farmingLevel);
		if (plantable.size() <= allowed)
		{
			return plantable;
		}

		List<FarmPatch> sorted = new ArrayList<>(plantable);
		sorted.sort(Comparator.comparing(FarmPatch::getKey));
		return sorted.subList(0, allowed);
	}
}
