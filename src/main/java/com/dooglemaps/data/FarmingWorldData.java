// GENERATED FILE - DO NOT EDIT BY HAND.
// Regenerate with: python3 tools/generate_farming_data.py <runelite-client-sources>
//
// Mirrored from RuneLite core's net.runelite.client.plugins.timetracking.farming
// package (Copyright (c) 2018 Abex and the RuneLite contributors, BSD 2-clause).
// Those classes are package-private, so external plugins must carry their own copy.
// See ATTRIBUTION.md.
package com.dooglemaps.data;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.coords.WorldPoint;

/**
 * Every farming patch in the game, grouped by the map region whose varbits carry it.
 *
 * <p>A patch is identified by (region id, varbit): the same varbit number is reused
 * across regions, so neither half is unique on its own.
 */
public final class FarmingWorldData
{
	private static final List<FarmRegion> REGIONS = new ArrayList<>();
	private static final Map<Integer, List<FarmRegion>> BY_REGION_ID = new LinkedHashMap<>();
	private static final Map<PatchImplementation, List<FarmPatch>> BY_TYPE = new EnumMap<>(PatchImplementation.class);
	private static final Map<String, FarmPatch> BY_KEY = new LinkedHashMap<>();

	static
	{
		add(new FarmRegion("Al Kharid", 13106, false, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.CACTUS, NpcID.FARMING_GARDENER_CACTUS, -1)
		), 13362, 13105);

		add(new FarmRegion("Aldarin", 5421, false, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.HOPS, NpcID.FARMING_GARDENER_HOPS_5, -1)
		), 5165, 5166, 5422, 5677, 5678);

		add(new FarmRegion("Anglers' Retreat", 9770, false, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.HARDWOOD_TREE, NpcID.FARMING_GARDENER_HARDWOOD_TREE_5, -1)
		));

		add(new FarmRegion("Ardougne", 10290, false, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.BUSH, NpcID.FARMING_GARDENER_BUSH_4, -1)
		), 10546);

		add(new FarmRegion("Ardougne", 10548, false, RegionBounds.ALWAYS,
			new FarmPatch("North", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.ALLOTMENT, NpcID.KRAGEN, 0),
			new FarmPatch("South", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.ALLOTMENT, NpcID.KRAGEN, 1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_C, PatchImplementation.FLOWER, -1, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_D, PatchImplementation.HERB, -1, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_E, PatchImplementation.COMPOST, -1, -1)
		));

		add(new FarmRegion("Auburnvale", 5427, false, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.TREE, NpcID.FARMING_GARDENER_TREE_7, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.BELLADONNA, -1, -1)
		), 5428, 5684);

		add(new FarmRegion("Avium Savannah", 6702, true, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.HARDWOOD_TREE, NpcID.FROG_QUEST_MARCELLUS, -1)
		), 6446);

		add(new FarmRegion("Brimhaven", 11058, false, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.FRUIT_TREE, NpcID.GARTH, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.SPIRIT_TREE, NpcID.FARMING_GARDENER_SPIRIT_TREE_3, -1)
		), 11057);

		add(new FarmRegion("Catherby", 11062, false, RegionBounds.forRegion(11062),
			new FarmPatch("North", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.ALLOTMENT, NpcID.DANTAERA, 0),
			new FarmPatch("South", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.ALLOTMENT, NpcID.DANTAERA, 1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_C, PatchImplementation.FLOWER, -1, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_D, PatchImplementation.HERB, -1, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_E, PatchImplementation.COMPOST, -1, -1)
		), 11061, 11318, 11317);

		add(new FarmRegion("Catherby", 11317, false, RegionBounds.forRegion(11317),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.FRUIT_TREE, NpcID.FARMING_GARDENER_FRUIT_4, -1)
		));

		add(new FarmRegion("Civitas illa Fortis", 6192, false, RegionBounds.ALWAYS,
			new FarmPatch("North", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.ALLOTMENT, NpcID.FORTIS_GARDENER, 0),
			new FarmPatch("South", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.ALLOTMENT, NpcID.FORTIS_GARDENER, 1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_C, PatchImplementation.FLOWER, -1, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_D, PatchImplementation.HERB, -1, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_E, PatchImplementation.COMPOST, -1, -1)
		), 6447, 6448, 6449, 6191, 6193);

		add(new FarmRegion("Champions' Guild", 12596, true, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.BUSH, NpcID.FARMING_GARDENER_BUSH_1, -1)
		));

		add(new FarmRegion("Draynor Manor", 12340, false, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.BELLADONNA, -1, -1)
		));

		add(new FarmRegion("Entrana", 11060, false, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.HOPS, NpcID.FRANCIS, -1)
		), 11316);

		add(new FarmRegion("Etceteria", 10300, false, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.BUSH, NpcID.FARMING_GARDENER_BUSH_3, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.SPIRIT_TREE, NpcID.FARMING_GARDENER_SPIRIT_TREE_2, -1)
		));

		add(new FarmRegion("Falador", 11828, false, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.TREE, NpcID.FARMING_GARDENER_TREE_2, -1)
		), 12084);

		add(new FarmRegion("Falador", 12083, false, RegionBounds.forRegion(12083),
			new FarmPatch("North West", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.ALLOTMENT, NpcID.ELSTAN, 0),
			new FarmPatch("South East", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.ALLOTMENT, NpcID.ELSTAN, 1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_C, PatchImplementation.FLOWER, -1, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_D, PatchImplementation.HERB, -1, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_E, PatchImplementation.COMPOST, -1, -1)
		));

		add(new FarmRegion("Fossil Island", 14651, false, RegionBounds.forRegion(14651),
			new FarmPatch("East", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.HARDWOOD_TREE, NpcID.FOSSIL_SQUIRREL_GARDENER1, -1),
			new FarmPatch("Middle", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.HARDWOOD_TREE, NpcID.FOSSIL_SQUIRREL_GARDENER2, -1),
			new FarmPatch("West", VarbitID.FARMING_TRANSMIT_C, PatchImplementation.HARDWOOD_TREE, NpcID.FOSSIL_SQUIRREL_GARDENER3, -1)
		), 14907, 14908, 15164, 14652, 14906, 14650, 15162, 15163);

		add(new FarmRegion("Seaweed", 15008, false, RegionBounds.ALWAYS,
			new FarmPatch("North", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.SEAWEED, NpcID.FOSSIL_GARDENER_UNDERWATER, 0),
			new FarmPatch("South", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.SEAWEED, NpcID.FOSSIL_GARDENER_UNDERWATER, 1)
		));

		add(new FarmRegion("Gnome Stronghold", 9781, true, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.TREE, NpcID.FARMING_GARDENER_TREE_GNOME, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.FRUIT_TREE, NpcID.FARMING_GARDENER_FRUIT_1, -1)
		), 9782, 9526, 9525);

		add(new FarmRegion("Great Conch", 12581, true, RegionBounds.ALWAYS,
			new FarmPatch("East", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.CORAL, NpcID.TORTUGAN_CORAL_FARMER, 0),
			new FarmPatch("West", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.CORAL, NpcID.TORTUGAN_CORAL_FARMER, 1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_C, PatchImplementation.CALQUAT, NpcID.FARMING_GARDENER_CALQUAT_3, -1)
		), 12325, 12326, 12327, 12580, 12581, 12582, 12583, 12836, 12837, 12838, 12839, 13092, 13093, 13194);

		add(new FarmRegion("Harmony", 15148, false, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.ALLOTMENT, -1, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.HERB, -1, -1)
		));

		add(new FarmRegion("Kastori", 5423, false, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.CALQUAT, NpcID.FARMING_GARDENER_CALQUAT_2, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.FRUIT_TREE, NpcID.FARMING_GARDENER_FRUIT_7, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_C, PatchImplementation.FLOWER, -1, -1)
		), 5167, 5424);

		add(new FarmRegion("Kourend", 6967, false, RegionBounds.ALWAYS,
			new FarmPatch("North East", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.ALLOTMENT, NpcID.HOSIDIUS_ALLOTMENT_GARDENER, 0),
			new FarmPatch("South West", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.ALLOTMENT, NpcID.HOSIDIUS_ALLOTMENT_GARDENER, 1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_C, PatchImplementation.FLOWER, -1, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_D, PatchImplementation.HERB, -1, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_E, PatchImplementation.COMPOST, -1, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_F, PatchImplementation.SPIRIT_TREE, NpcID.FARMING_GARDENER_SPIRIT_TREE_4, -1)
		), 6711);

		add(new FarmRegion("Kourend", 7223, false, RegionBounds.ALWAYS,
			new FarmPatch("East 1", VarbitID.FARMING_TRANSMIT_A1, PatchImplementation.GRAPES, -1, -1),
			new FarmPatch("East 2", VarbitID.FARMING_TRANSMIT_A2, PatchImplementation.GRAPES, -1, -1),
			new FarmPatch("East 3", VarbitID.FARMING_TRANSMIT_B1, PatchImplementation.GRAPES, -1, -1),
			new FarmPatch("East 4", VarbitID.FARMING_TRANSMIT_B2, PatchImplementation.GRAPES, -1, -1),
			new FarmPatch("East 5", VarbitID.FARMING_TRANSMIT_C1, PatchImplementation.GRAPES, -1, -1),
			new FarmPatch("East 6", VarbitID.FARMING_TRANSMIT_C2, PatchImplementation.GRAPES, -1, -1),
			new FarmPatch("West 1", VarbitID.FARMING_TRANSMIT_D1, PatchImplementation.GRAPES, -1, -1),
			new FarmPatch("West 2", VarbitID.FARMING_TRANSMIT_D2, PatchImplementation.GRAPES, -1, -1),
			new FarmPatch("West 3", VarbitID.FARMING_TRANSMIT_E1, PatchImplementation.GRAPES, -1, -1),
			new FarmPatch("West 4", VarbitID.FARMING_TRANSMIT_E2, PatchImplementation.GRAPES, -1, -1),
			new FarmPatch("West 5", VarbitID.FARMING_TRANSMIT_F1, PatchImplementation.GRAPES, -1, -1),
			new FarmPatch("West 6", VarbitID.FARMING_TRANSMIT_F2, PatchImplementation.GRAPES, -1, -1)
		));

		add(new FarmRegion("Lletya", 9265, false, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.FRUIT_TREE, NpcID.FARMING_GARDENER_FRUIT_TREE_5, -1)
		), 11103);

		add(new FarmRegion("Lumbridge", 12851, false, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.HOPS, NpcID.FARMING_GARDENER_HOPS_3, -1)
		));

		add(new FarmRegion("Lumbridge", 12594, false, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.TREE, NpcID.FARMING_GARDENER_TREE_4, -1)
		), 12850);

		add(new FarmRegion("Morytania", 13622, false, RegionBounds.ALWAYS,
			new FarmPatch("Mushroom", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.MUSHROOM, -1, -1)
		), 13878);

		add(new FarmRegion("Morytania", 14391, false, RegionBounds.ALWAYS,
			new FarmPatch("North West", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.ALLOTMENT, NpcID.LYRA, 0),
			new FarmPatch("South East", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.ALLOTMENT, NpcID.LYRA, 1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_C, PatchImplementation.FLOWER, -1, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_D, PatchImplementation.HERB, -1, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_E, PatchImplementation.COMPOST, -1, -1)
		), 14390);

		add(new FarmRegion("Port Sarim", 12082, false, RegionBounds.forRegion(12082),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.SPIRIT_TREE, NpcID.FARMING_GARDENER_SPIRIT_TREE_1, -1)
		), 12083);

		add(new FarmRegion("Rimmington", 11570, false, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.BUSH, NpcID.FARMING_GARDENER_BUSH_2, -1)
		), 11826);

		add(new FarmRegion("Seers' Village", 10551, false, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.HOPS, NpcID.FARMING_GARDENER_HOPS_4, -1)
		), 10550);

		add(new FarmRegion("Tai Bwo Wannai", 11056, false, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.CALQUAT, NpcID.FARMING_GARDENER_CALQUAT, -1)
		));

		add(new FarmRegion("Taverley", 11573, false, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.TREE, NpcID.FARMING_GARDENER_TREE_1, -1)
		), 11829);

		add(new FarmRegion("Tree Gnome Village", 9777, true, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.FRUIT_TREE, NpcID.FARMING_GARDENER_FRUIT_2, -1)
		), 10033);

		add(new FarmRegion("Troll Stronghold", 11321, true, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.HERB, -1, -1)
		));

		add(new FarmRegion("Varrock", 12854, false, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.TREE, NpcID.FARMING_GARDENER_TREE_3_02, -1)
		), 12853);

		add(new FarmRegion("Yanille", 10288, false, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.HOPS, NpcID.FARMING_GARDENER_HOPS_1, -1)
		));

		add(new FarmRegion("Weiss", 11325, false, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.HERB, -1, -1)
		));

		add(new FarmRegion("Farming Guild", 5021, true, RegionBounds.ALWAYS,
			new FarmPatch("Hespori", VarbitID.FARMING_TRANSMIT_J, PatchImplementation.HESPORI, -1, -1)
		));

		add(new FarmRegion("Farming Guild", 4922, true, RegionBounds.ALWAYS,
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_G, PatchImplementation.TREE, NpcID.FARMING_GARDENER_FARMGUILD_T2, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_E, PatchImplementation.HERB, -1, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.BUSH, NpcID.FARMING_GARDENER_FARMGUILD_T1, 3),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_H, PatchImplementation.FLOWER, -1, -1),
			new FarmPatch("North", VarbitID.FARMING_TRANSMIT_C, PatchImplementation.ALLOTMENT, NpcID.FARMING_GARDENER_FARMGUILD_T1, 1),
			new FarmPatch("South", VarbitID.FARMING_TRANSMIT_D, PatchImplementation.ALLOTMENT, NpcID.FARMING_GARDENER_FARMGUILD_T1, 2),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_N, PatchImplementation.BIG_COMPOST, -1, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_F, PatchImplementation.CACTUS, NpcID.FARMING_GARDENER_FARMGUILD_T1, 0),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.SPIRIT_TREE, NpcID.FARMING_GARDENER_SPIRIT_TREE_5, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_K, PatchImplementation.FRUIT_TREE, NpcID.FARMING_GARDENER_FARMGUILD_T3, -1),
			new FarmPatch("Anima", VarbitID.FARMING_TRANSMIT_M, PatchImplementation.ANIMA, -1, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_L, PatchImplementation.CELASTRUS, NpcID.FARMING_GARDENER_FARMGUILD_CELASTRUS, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_I, PatchImplementation.REDWOOD, NpcID.FARMING_GARDENER_FARMGUILD_REDWOOD, -1)
		), 5177, 5178, 5179, 4921, 4923, 4665, 4666, 4667);

		add(new FarmRegion("Prifddinas", 13151, false, RegionBounds.ALWAYS,
			new FarmPatch("North", VarbitID.FARMING_TRANSMIT_A, PatchImplementation.ALLOTMENT, NpcID.PRIF_GARDENER, 0),
			new FarmPatch("South", VarbitID.FARMING_TRANSMIT_B, PatchImplementation.ALLOTMENT, NpcID.PRIF_GARDENER, 1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_C, PatchImplementation.FLOWER, -1, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_E, PatchImplementation.CRYSTAL_TREE, -1, -1),
			new FarmPatch("", VarbitID.FARMING_TRANSMIT_D, PatchImplementation.COMPOST, -1, -1)
		), 12895, 12894, 13150, 12994, 12993, 12737, 12738, 12126, 12127, 13250);

		for (Map.Entry<PatchImplementation, List<FarmPatch>> e : BY_TYPE.entrySet())
		{
			e.setValue(Collections.unmodifiableList(e.getValue()));
		}
	}

	private FarmingWorldData()
	{
	}

	private static void add(FarmRegion region, int... extraRegionIds)
	{
		REGIONS.add(region);
		BY_REGION_ID.computeIfAbsent(region.getRegionId(), k -> new ArrayList<>()).add(region);
		for (int extra : extraRegionIds)
		{
			BY_REGION_ID.computeIfAbsent(extra, k -> new ArrayList<>()).add(region);
		}
		for (FarmPatch patch : region.getPatches())
		{
			BY_TYPE.computeIfAbsent(patch.getImplementation(), k -> new ArrayList<>()).add(patch);
			BY_KEY.put(patch.getKey(), patch);
		}
	}

	public static List<FarmRegion> getRegions()
	{
		return Collections.unmodifiableList(REGIONS);
	}

	/** Regions whose varbits are live at the given location. */
	public static Collection<FarmRegion> getRegionsForLocation(WorldPoint location)
	{
		List<FarmRegion> candidates = BY_REGION_ID.get(location.getRegionID());
		if (candidates == null)
		{
			return Collections.emptyList();
		}

		List<FarmRegion> result = new ArrayList<>(candidates.size());
		for (FarmRegion region : candidates)
		{
			if (region.isInBounds(location))
			{
				result.add(region);
			}
		}
		return result;
	}

	public static List<FarmPatch> getPatches(PatchImplementation type)
	{
		return BY_TYPE.getOrDefault(type, Collections.emptyList());
	}

	public static Collection<FarmPatch> getAllPatches()
	{
		return Collections.unmodifiableCollection(BY_KEY.values());
	}

	/** Looks a patch up by its {@code regionId.varbit} key. */
	public static FarmPatch getPatch(String key)
	{
		return BY_KEY.get(key);
	}
}
