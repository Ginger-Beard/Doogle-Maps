// GENERATED FILE - DO NOT EDIT BY HAND.
// Regenerate with: python3 tools/generate_farming_data.py <runelite-client-sources>
//
// Mirrored from RuneLite core's net.runelite.client.plugins.timetracking.farming
// package (Copyright (c) 2018 Abex and the RuneLite contributors, BSD 2-clause).
// Those classes are package-private, so external plugins must carry their own copy.
// See ATTRIBUTION.md.
package com.dooglemaps.data;


import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ItemID;

/**
 * Everything that can be planted, and what it takes to plant it.
 *
 * <p>Not derived from RuneLite: its data describes what a patch is <i>doing</i>, keyed
 * on the harvested item, and says nothing about the seed that started it or the level
 * needed to sow it. Level requirements come from the OSRS Wiki's seed tables.
 */
@Getter
@RequiredArgsConstructor
public enum Seed
{
	POTATO(ItemID.POTATO_SEED, -1, Produce.POTATO, 1),
	ONION(ItemID.ONION_SEED, -1, Produce.ONION, 5),
	CABBAGE(ItemID.CABBAGE_SEED, -1, Produce.CABBAGE, 7),
	TOMATO(ItemID.TOMATO_SEED, -1, Produce.TOMATO, 12),
	SWEETCORN(ItemID.SWEETCORN_SEED, -1, Produce.SWEETCORN, 20),
	STRAWBERRY(ItemID.STRAWBERRY_SEED, -1, Produce.STRAWBERRY, 31),
	WATERMELON(ItemID.WATERMELON_SEED, -1, Produce.WATERMELON, 47),
	SNAPE_GRASS(ItemID.SNAPE_GRASS_SEED, -1, Produce.SNAPE_GRASS, 61),
	MARIGOLD(ItemID.MARIGOLD_SEED, -1, Produce.MARIGOLD, 2),
	ROSEMARY(ItemID.ROSEMARY_SEED, -1, Produce.ROSEMARY, 11),
	NASTURTIUM(ItemID.NASTURTIUM_SEED, -1, Produce.NASTURTIUM, 24),
	WOAD(ItemID.WOAD_SEED, -1, Produce.WOAD, 25),
	LIMPWURT(ItemID.LIMPWURT_SEED, -1, Produce.LIMPWURT, 26),
	WHITE_LILY(ItemID.WHITE_LILY_SEED, -1, Produce.WHITE_LILY, 58),
	REDBERRIES(ItemID.REDBERRY_BUSH_SEED, -1, Produce.REDBERRIES, 10),
	CADAVABERRIES(ItemID.CADAVABERRY_BUSH_SEED, -1, Produce.CADAVABERRIES, 22),
	DWELLBERRIES(ItemID.DWELLBERRY_BUSH_SEED, -1, Produce.DWELLBERRIES, 36),
	JANGERBERRIES(ItemID.JANGERBERRY_BUSH_SEED, -1, Produce.JANGERBERRIES, 48),
	WHITEBERRIES(ItemID.WHITEBERRY_BUSH_SEED, -1, Produce.WHITEBERRIES, 59),
	POISON_IVY(ItemID.POISONIVY_BUSH_SEED, -1, Produce.POISON_IVY, 70),
	BARLEY(ItemID.BARLEY_SEED, -1, Produce.BARLEY, 3),
	HAMMERSTONE(ItemID.HAMMERSTONE_HOP_SEED, -1, Produce.HAMMERSTONE, 4),
	ASGARNIAN(ItemID.ASGARNIAN_HOP_SEED, -1, Produce.ASGARNIAN, 8),
	JUTE(ItemID.JUTE_SEED, -1, Produce.JUTE, 13),
	YANILLIAN(ItemID.YANILLIAN_HOP_SEED, -1, Produce.YANILLIAN, 16),
	FLAX(ItemID.FLAX_SEED, -1, Produce.FLAX, 18),
	KRANDORIAN(ItemID.KRANDORIAN_HOP_SEED, -1, Produce.KRANDORIAN, 21),
	WILDBLOOD(ItemID.WILDBLOOD_HOP_SEED, -1, Produce.WILDBLOOD, 28),
	HEMP(ItemID.HEMP_SEED, -1, Produce.HEMP, 37),
	COTTON(ItemID.COTTON_SEED, -1, Produce.COTTON, 71),
	GUAM(ItemID.GUAM_SEED, -1, Produce.GUAM, 9),
	MARRENTILL(ItemID.MARRENTILL_SEED, -1, Produce.MARRENTILL, 14),
	TARROMIN(ItemID.TARROMIN_SEED, -1, Produce.TARROMIN, 19),
	HARRALANDER(ItemID.HARRALANDER_SEED, -1, Produce.HARRALANDER, 26),
	RANARR(ItemID.RANARR_SEED, -1, Produce.RANARR, 32),
	TOADFLAX(ItemID.TOADFLAX_SEED, -1, Produce.TOADFLAX, 38),
	IRIT(ItemID.IRIT_SEED, -1, Produce.IRIT, 44),
	AVANTOE(ItemID.AVANTOE_SEED, -1, Produce.AVANTOE, 50),
	KWUARM(ItemID.KWUARM_SEED, -1, Produce.KWUARM, 56),
	HUASCA(ItemID.HUASCA_SEED, -1, Produce.HUASCA, 65),
	SNAPDRAGON(ItemID.SNAPDRAGON_SEED, -1, Produce.SNAPDRAGON, 62),
	CADANTINE(ItemID.CADANTINE_SEED, -1, Produce.CADANTINE, 67),
	LANTADYME(ItemID.LANTADYME_SEED, -1, Produce.LANTADYME, 73),
	DWARF_WEED(ItemID.DWARF_WEED_SEED, -1, Produce.DWARF_WEED, 79),
	TORSTOL(ItemID.TORSTOL_SEED, -1, Produce.TORSTOL, 85),
	OAK(ItemID.ACORN, ItemID.PLANTPOT_OAK_SAPLING, Produce.OAK, 15),
	WILLOW(ItemID.WILLOW_SEED, ItemID.PLANTPOT_WILLOW_SAPLING, Produce.WILLOW, 30),
	MAPLE(ItemID.MAPLE_SEED, ItemID.PLANTPOT_MAPLE_SAPLING, Produce.MAPLE, 45),
	YEW(ItemID.YEW_SEED, ItemID.PLANTPOT_YEW_SAPLING, Produce.YEW, 60),
	MAGIC(ItemID.MAGIC_TREE_SEED, ItemID.PLANTPOT_MAGIC_TREE_SAPLING, Produce.MAGIC, 75),
	APPLE(ItemID.APPLE_TREE_SEED, ItemID.PLANTPOT_APPLE_SAPLING, Produce.APPLE, 27),
	BANANA(ItemID.BANANA_TREE_SEED, ItemID.PLANTPOT_BANANA_SAPLING, Produce.BANANA, 33),
	ORANGE(ItemID.ORANGE_TREE_SEED, ItemID.PLANTPOT_ORANGE_SAPLING, Produce.ORANGE, 39),
	CURRY(ItemID.CURRY_TREE_SEED, ItemID.PLANTPOT_CURRY_SAPLING, Produce.CURRY, 42),
	PINEAPPLE(ItemID.PINEAPPLE_TREE_SEED, ItemID.PLANTPOT_PINEAPPLE_SAPLING, Produce.PINEAPPLE, 51),
	PAPAYA(ItemID.PAPAYA_TREE_SEED, ItemID.PLANTPOT_PAPAYA_SAPLING, Produce.PAPAYA, 57),
	PALM(ItemID.PALM_TREE_SEED, ItemID.PLANTPOT_PALM_SAPLING, Produce.PALM, 68),
	DRAGONFRUIT(ItemID.DRAGONFRUIT_TREE_SEED, ItemID.PLANTPOT_DRAGONFRUIT_SAPLING, Produce.DRAGONFRUIT, 81),
	CACTUS(ItemID.CACTUS_SEED, -1, Produce.CACTUS, 55),
	POTATO_CACTUS(ItemID.POTATO_CACTUS_SEED, -1, Produce.POTATO_CACTUS, 64),
	TEAK(ItemID.TEAK_SEED, ItemID.PLANTPOT_TEAK_SAPLING, Produce.TEAK, 35),
	MAHOGANY(ItemID.MAHOGANY_SEED, ItemID.PLANTPOT_MAHOGANY_SAPLING, Produce.MAHOGANY, 55),
	CAMPHOR(ItemID.CAMPHOR_SEED, ItemID.PLANTPOT_CAMPHOR_SAPLING, Produce.CAMPHOR, 66),
	IRONWOOD(ItemID.IRONWOOD_SEED, ItemID.PLANTPOT_IRONWOOD_SAPLING, Produce.IRONWOOD, 80),
	ROSEWOOD(ItemID.ROSEWOOD_SEED, ItemID.PLANTPOT_ROSEWOOD_SAPLING, Produce.ROSEWOOD, 92),
	ATTAS(ItemID.ATTAS_SEED, -1, Produce.ATTAS, 76),
	IASOR(ItemID.IASOR_SEED, -1, Produce.IASOR, 76),
	KRONOS(ItemID.KRONOS_SEED, -1, Produce.KRONOS, 76),
	ELKHORN_CORAL(ItemID.CORAL_ELKHORN_FRAG, -1, Produce.ELKHORN_CORAL, 28),
	PILLAR_CORAL(ItemID.CORAL_PILLAR_FRAG, -1, Produce.PILLAR_CORAL, 52),
	UMBRAL_CORAL(ItemID.CORAL_UMBRAL_FRAG, -1, Produce.UMBRAL_CORAL, 77),
	SEAWEED(ItemID.SEAWEED_SEED, -1, Produce.SEAWEED, 23),
	GRAPE(ItemID.GRAPE_SEED, -1, Produce.GRAPE, 36),
	MUSHROOM(ItemID.MUSHROOM_SEED, -1, Produce.MUSHROOM, 53),
	BELLADONNA(ItemID.BELLADONNA_SEED, -1, Produce.BELLADONNA, 63),
	CALQUAT(ItemID.CALQUAT_TREE_SEED, ItemID.PLANTPOT_CALQUAT_SAPLING, Produce.CALQUAT, 72),
	SPIRIT_TREE(ItemID.SPIRIT_TREE_SEED, ItemID.PLANTPOT_SPIRIT_TREE_SAPLING, Produce.SPIRIT_TREE, 83),
	CELASTRUS(ItemID.CELASTRUS_TREE_SEED, ItemID.PLANTPOT_CELASTRUS_TREE_SAPLING, Produce.CELASTRUS, 85),
	REDWOOD(ItemID.REDWOOD_TREE_SEED, ItemID.PLANTPOT_REDWOOD_TREE_SAPLING, Produce.REDWOOD, 90),
	HESPORI(ItemID.HESPORI_SEED, -1, Produce.HESPORI, 65),
	CRYSTAL_TREE(ItemID.CRYSTAL_TREE_SEED, ItemID.PLANTPOT_CRYSTAL_TREE_SAPLING, Produce.CRYSTAL_TREE, 74);

	private final int itemID;
	/**
	 * The sapling this seed becomes, or -1 where the seed goes straight in the ground.
	 *
	 * <p>Trees, fruit trees, hardwoods, calquats, celastrus, redwoods, spirit trees and
	 * the crystal tree are all planted as saplings: the seed has to spend time in a
	 * filled plant pot first. Anyone stocked up for a tree run therefore owns saplings
	 * and quite possibly no seeds at all, so both count as having the crop.
	 */
	private final int saplingItemID;
	private final Produce produce;
	/** Farming level needed to plant it. */
	private final int levelRequirement;

	public PatchImplementation getPatchType()
	{
		return produce.getPatchImplementation();
	}

	/**
	 * How many seeds one patch takes.
	 *
	 * <p>Allotments take three and hops four, except jute at three; everything else is
	 * a single seed or sapling.
	 */
	public int getSeedsPerPatch()
	{
		if (this == JUTE)
		{
			return 3;
		}
		switch (getPatchType())
		{
			case ALLOTMENT:
				return 3;
			case HOPS:
				return 4;
			default:
				return 1;
		}
	}

	public String getName()
	{
		return produce.getName();
	}

	/** Every seed that goes in a given kind of patch, easiest first. */
	public static List<Seed> forPatchType(PatchImplementation type)
	{
		List<Seed> seeds = new ArrayList<>();
		for (Seed seed : values())
		{
			if (seed.getPatchType() == type)
			{
				seeds.add(seed);
			}
		}
		seeds.sort((a, b) -> Integer.compare(a.levelRequirement, b.levelRequirement));
		return Collections.unmodifiableList(seeds);
	}

	/**
	 * Whether this crop is planted as a sapling rather than as a seed.
	 */
	public boolean isSapling()
	{
		return saplingItemID != -1;
	}

	/**
	 * The item that actually goes in the ground.
	 *
	 * <p>The sapling for a tree, the seed for everything else. This is what a seed list
	 * should draw and count, because it is what the player carries to the patch.
	 */
	public int getPlantedItemID()
	{
		return isSapling() ? saplingItemID : itemID;
	}

	private static final Map<Integer, Seed> BY_ITEM_ID = new HashMap<>();

	static
	{
		for (Seed seed : values())
		{
			BY_ITEM_ID.put(seed.itemID, seed);
			// Both forms count as owning the crop: the seed you have not potted yet and the
			// sapling it becomes. Only the sapling can be planted, but someone holding the
			// seed still has the tree, and a list that ignored either would be wrong.
			if (seed.isSapling())
			{
				BY_ITEM_ID.put(seed.saplingItemID, seed);
			}
		}
	}

	private static final Map<Produce, Seed> BY_PRODUCE = new HashMap<>();

	static
	{
		for (Seed seed : values())
		{
			BY_PRODUCE.put(seed.produce, seed);
		}
	}

	/**
	 * The seed that grows a given crop, or null if nothing plants it.
	 *
	 * <p>A patch varbit says what is <i>growing</i>, never which seed went in, so this is
	 * how anything reading a patch gets back to the seed's level and yield data.
	 */
	@Nullable
	public static Seed forProduce(@Nullable Produce produce)
	{
		return produce == null ? null : BY_PRODUCE.get(produce);
	}

	/**
	 * The seed an item id refers to, or null if it is not a seed.
	 *
	 * <p>A map rather than a scan because this is called once per item in whatever
	 * container just changed. Opening a bank means a thousand-odd lookups at once, and a
	 * linear walk of every seed for each of them was measurable when the bank opened.
	 */
	@Nullable
	public static Seed forItemId(int itemId)
	{
		return BY_ITEM_ID.get(itemId);
	}
}
