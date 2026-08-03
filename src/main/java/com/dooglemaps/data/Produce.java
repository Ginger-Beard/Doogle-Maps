// GENERATED FILE - DO NOT EDIT BY HAND.
// Regenerate with: python3 tools/generate_farming_data.py <runelite-client-sources>
//
// Mirrored from RuneLite core's net.runelite.client.plugins.timetracking.farming
// package (Copyright (c) 2018 Abex and the RuneLite contributors, BSD 2-clause).
// Those classes are package-private, so external plugins must carry their own copy.
// See ATTRIBUTION.md.
package com.dooglemaps.data;


import javax.annotation.Nullable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ItemID;

/**
 * Everything that can occupy a farming patch, with its growth timing.
 *
 * <p>{@code tickrate} is minutes per growth tick and {@code stages} the number of
 * growth states; {@code regrowTickrate}/{@code harvestStages} cover crops that
 * regrow after picking ("lives").
 */
@Getter
@RequiredArgsConstructor
public enum Produce
{
	WEEDS("Weeds", "Weeds", null, ItemID.WEEDS, 5, 4, 0, 1),
	SCARECROW("Scarecrow", "Scarecrow", null, ItemID.SCARECROW_COMPLETE, 5, 4, 0, 1),
	POTATO("Potato", "Potatoes", PatchImplementation.ALLOTMENT, ItemID.POTATO, 10, 5, 0, 3),
	ONION("Onion", "Onions", PatchImplementation.ALLOTMENT, ItemID.ONION, 10, 5, 0, 3),
	CABBAGE("Cabbage", "Cabbages", PatchImplementation.ALLOTMENT, ItemID.CABBAGE, 10, 5, 0, 3),
	TOMATO("Tomato", "Tomatoes", PatchImplementation.ALLOTMENT, ItemID.TOMATO, 10, 5, 0, 3),
	SWEETCORN("Sweetcorn", "Sweetcorn", PatchImplementation.ALLOTMENT, ItemID.SWEETCORN, 10, 7, 0, 3),
	STRAWBERRY("Strawberry", "Strawberries", PatchImplementation.ALLOTMENT, ItemID.STRAWBERRY, 10, 7, 0, 3),
	WATERMELON("Watermelon", "Watermelons", PatchImplementation.ALLOTMENT, ItemID.WATERMELON, 10, 9, 0, 3),
	SNAPE_GRASS("Snape grass", "Snape grass", PatchImplementation.ALLOTMENT, ItemID.SNAPE_GRASS, 10, 8, 0, 3),
	MARIGOLD("Marigold", "Marigolds", PatchImplementation.FLOWER, ItemID.MARIGOLD, 5, 5, 0, 1),
	ROSEMARY("Rosemary", "Rosemary", PatchImplementation.FLOWER, ItemID.ROSEMARY, 5, 5, 0, 1),
	NASTURTIUM("Nasturtium", "Nasturtiums", PatchImplementation.FLOWER, ItemID.NASTURTIUM, 5, 5, 0, 1),
	WOAD("Woad", "Woad", PatchImplementation.FLOWER, ItemID.WOADLEAF, 5, 5, 0, 1),
	LIMPWURT("Limpwurt", "Limpwurt roots", PatchImplementation.FLOWER, ItemID.LIMPWURT_ROOT, 5, 5, 0, 1),
	WHITE_LILY("White lily", "White lillies", PatchImplementation.FLOWER, ItemID.WHITELILLY, 5, 5, 0, 1),
	REDBERRIES("Redberry", "Redberries", PatchImplementation.BUSH, ItemID.REDBERRIES, 20, 6, 20, 5),
	CADAVABERRIES("Cadavaberry", "Cadava berries", PatchImplementation.BUSH, ItemID.CADAVABERRIES, 20, 7, 20, 5),
	DWELLBERRIES("Dwellberry", "Dwellberries", PatchImplementation.BUSH, ItemID.DWELLBERRIES, 20, 8, 20, 5),
	JANGERBERRIES("Jangerberry", "Jangerberries", PatchImplementation.BUSH, ItemID.JANGERBERRIES, 20, 9, 20, 5),
	WHITEBERRIES("Whiteberry", "White berries", PatchImplementation.BUSH, ItemID.WHITE_BERRIES, 20, 9, 20, 5),
	POISON_IVY("Poison ivy", "Poison ivy berries", PatchImplementation.BUSH, ItemID.POISONIVY_BERRIES, 20, 9, 20, 5),
	BARLEY("Barley", "Barley", PatchImplementation.HOPS, ItemID.BARLEY, 10, 5, 0, 3),
	HAMMERSTONE("Hammerstone", "Hammerstone", PatchImplementation.HOPS, ItemID.HAMMERSTONE_HOPS, 10, 5, 0, 3),
	ASGARNIAN("Asgarnian", "Asgarnian", PatchImplementation.HOPS, ItemID.ASGARNIAN_HOPS, 10, 6, 0, 3),
	JUTE("Jute", "Jute", PatchImplementation.HOPS, ItemID.JUTE_FIBRE, 10, 6, 0, 3),
	YANILLIAN("Yanillian", "Yanillian", PatchImplementation.HOPS, ItemID.YANILLIAN_HOPS, 10, 7, 0, 3),
	FLAX("Flax", "Flax", PatchImplementation.HOPS, ItemID.FLAX, 20, 4, 0, 3),
	KRANDORIAN("Krandorian", "Krandorian", PatchImplementation.HOPS, ItemID.KRANDORIAN_HOPS, 10, 8, 0, 3),
	WILDBLOOD("Wildblood", "Wildblood", PatchImplementation.HOPS, ItemID.WILDBLOOD_HOPS, 10, 9, 0, 3),
	HEMP("Hemp", "Hemp", PatchImplementation.HOPS, ItemID.HEMP, 20, 5, 0, 3),
	COTTON("Cotton", "Cotton", PatchImplementation.HOPS, ItemID.COTTON_BOLL, 20, 6, 0, 3),
	GUAM("Guam", "Guam", PatchImplementation.HERB, ItemID.GUAM_LEAF, 20, 5, 0, 3),
	MARRENTILL("Marrentill", "Marrentill", PatchImplementation.HERB, ItemID.MARENTILL, 20, 5, 0, 3),
	TARROMIN("Tarromin", "Tarromin", PatchImplementation.HERB, ItemID.TARROMIN, 20, 5, 0, 3),
	HARRALANDER("Harralander", "Harralander", PatchImplementation.HERB, ItemID.HARRALANDER, 20, 5, 0, 3),
	RANARR("Ranarr", "Ranarr", PatchImplementation.HERB, ItemID.RANARR_WEED, 20, 5, 0, 3),
	TOADFLAX("Toadflax", "Toadflax", PatchImplementation.HERB, ItemID.TOADFLAX, 20, 5, 0, 3),
	IRIT("Irit", "Irit", PatchImplementation.HERB, ItemID.IRIT_LEAF, 20, 5, 0, 3),
	AVANTOE("Avantoe", "Avantoe", PatchImplementation.HERB, ItemID.AVANTOE, 20, 5, 0, 3),
	KWUARM("Kwuarm", "Kwuarm", PatchImplementation.HERB, ItemID.KWUARM, 20, 5, 0, 3),
	HUASCA("Huasca", "Huasca", PatchImplementation.HERB, ItemID.HUASCA, 20, 5, 0, 3),
	SNAPDRAGON("Snapdragon", "Snapdragon", PatchImplementation.HERB, ItemID.SNAPDRAGON, 20, 5, 0, 3),
	CADANTINE("Cadantine", "Cadantine", PatchImplementation.HERB, ItemID.CADANTINE, 20, 5, 0, 3),
	LANTADYME("Lantadyme", "Lantadyme", PatchImplementation.HERB, ItemID.LANTADYME, 20, 5, 0, 3),
	DWARF_WEED("Dwarf weed", "Dwarf weed", PatchImplementation.HERB, ItemID.DWARF_WEED, 20, 5, 0, 3),
	TORSTOL("Torstol", "Torstol", PatchImplementation.HERB, ItemID.TORSTOL, 20, 5, 0, 3),
	GOUTWEED("Goutweed", "Goutweed", PatchImplementation.HERB, ItemID.EADGAR_GOUTWEED_HERB, 20, 5, 0, 2),
	ANYHERB("Any herb", "Any herb", PatchImplementation.HERB, ItemID.GUAM_LEAF, 20, 5, 0, 3),
	OAK("Oak", "Oak tree", PatchImplementation.TREE, ItemID.OAK_LOGS, 40, 5, 0, 1),
	WILLOW("Willow", "Willow tree", PatchImplementation.TREE, ItemID.WILLOW_LOGS, 40, 7, 0, 1),
	MAPLE("Maple", "Maple tree", PatchImplementation.TREE, ItemID.MAPLE_LOGS, 40, 9, 0, 1),
	YEW("Yew", "Yew tree", PatchImplementation.TREE, ItemID.YEW_LOGS, 40, 11, 0, 1),
	MAGIC("Magic", "Magic tree", PatchImplementation.TREE, ItemID.MAGIC_LOGS, 40, 13, 0, 1),
	APPLE("Apple", "Apple tree", PatchImplementation.FRUIT_TREE, ItemID.COOKING_APPLE, 160, 7, 45, 7),
	BANANA("Banana", "Banana tree", PatchImplementation.FRUIT_TREE, ItemID.BANANA, 160, 7, 45, 7),
	ORANGE("Orange", "Orange tree", PatchImplementation.FRUIT_TREE, ItemID.ORANGE, 160, 7, 45, 7),
	CURRY("Curry", "Curry tree", PatchImplementation.FRUIT_TREE, ItemID.CURRY_LEAF, 160, 7, 45, 7),
	PINEAPPLE("Pineapple", "Pineapple plant", PatchImplementation.FRUIT_TREE, ItemID.PINEAPPLE, 160, 7, 45, 7),
	PAPAYA("Papaya", "Papaya tree", PatchImplementation.FRUIT_TREE, ItemID.PAPAYA, 160, 7, 45, 7),
	PALM("Palm", "Palm tree", PatchImplementation.FRUIT_TREE, ItemID.COCONUT, 160, 7, 45, 7),
	DRAGONFRUIT("Dragonfruit", "Dragonfruit tree", PatchImplementation.FRUIT_TREE, ItemID.DRAGONFRUIT, 160, 7, 45, 7),
	CACTUS("Cactus", "Cactus", PatchImplementation.CACTUS, ItemID.CACTUS_SPINE, 80, 8, 20, 4),
	POTATO_CACTUS("Potato cactus", "Potato cacti", PatchImplementation.CACTUS, ItemID.CACTUS_POTATO, 10, 8, 5, 7),
	TEAK("Teak", "Teak", PatchImplementation.HARDWOOD_TREE, ItemID.TEAK_LOGS, 640, 8, 0, 1),
	MAHOGANY("Mahogany", "Mahogany", PatchImplementation.HARDWOOD_TREE, ItemID.MAHOGANY_LOGS, 640, 9, 0, 1),
	CAMPHOR("Camphor", "Camphor", PatchImplementation.HARDWOOD_TREE, ItemID.CAMPHOR_LOGS, 640, 9, 0, 1),
	IRONWOOD("Ironwood", "Ironwood", PatchImplementation.HARDWOOD_TREE, ItemID.IRONWOOD_LOGS, 640, 9, 0, 1),
	ROSEWOOD("Rosewood", "Rosewood", PatchImplementation.HARDWOOD_TREE, ItemID.ROSEWOOD_LOGS, 640, 10, 0, 1),
	ATTAS("Attas", "Attas", PatchImplementation.ANIMA, ItemID.ANIMA_ATTAS, 640, 9, 0, 1),
	IASOR("Iasor", "Iasor", PatchImplementation.ANIMA, ItemID.ANIMA_IASOR, 640, 9, 0, 1),
	KRONOS("Kronos", "Kronos", PatchImplementation.ANIMA, ItemID.ANIMA_KRONOS, 640, 9, 0, 1),
	ELKHORN_CORAL("Elkhorn", "Elkhorn", PatchImplementation.CORAL, ItemID.CORAL_ELKHORN, 40, 5, 0, 1),
	PILLAR_CORAL("Pillar", "Pillar", PatchImplementation.CORAL, ItemID.CORAL_PILLAR, 40, 5, 0, 1),
	UMBRAL_CORAL("Umbral", "Umbral", PatchImplementation.CORAL, ItemID.CORAL_UMBRAL, 40, 5, 0, 1),
	SEAWEED("Seaweed", "Seaweed", PatchImplementation.SEAWEED, ItemID.GIANT_SEAWEED, 10, 5, 0, 4),
	GRAPE("Grape", "Grape", PatchImplementation.GRAPES, ItemID.GRAPES, 5, 8, 0, 5),
	MUSHROOM("Mushroom", "Mushroom", PatchImplementation.MUSHROOM, ItemID.BITTERCAP_MUSHROOM, 40, 7, 0, 7),
	BELLADONNA("Belladonna", "Belladonna", PatchImplementation.BELLADONNA, ItemID.NIGHTSHADE, 80, 5, 0, 1),
	CALQUAT("Calquat", "Calquat", PatchImplementation.CALQUAT, ItemID.CALQUAT_FRUIT, 160, 9, 0, 7),
	SPIRIT_TREE("Spirit tree", "Spirit tree", PatchImplementation.SPIRIT_TREE, ItemID.SPIRIT_TREE_DUMMY, 320, 13, 0, 1),
	CELASTRUS("Celastrus", "Celastrus tree", PatchImplementation.CELASTRUS, ItemID.BATTLESTAFF, 160, 6, 0, 4),
	REDWOOD("Redwood", "Redwood tree", PatchImplementation.REDWOOD, ItemID.REDWOOD_LOGS, 640, 11, 0, 1),
	HESPORI("Hespori", "Hespori", PatchImplementation.HESPORI, ItemID.HESPORI, 640, 4, 0, 2),
	CRYSTAL_TREE("Crystal tree", "Crystal tree", PatchImplementation.CRYSTAL_TREE, ItemID.GAUNTLET_CRYSTAL_SHARD, 80, 7, 0, 1),
	EMPTY_COMPOST_BIN("Compost Bin", "Compost Bin", PatchImplementation.COMPOST, ItemID.EADGAR_FADE_TO_BLACK_INV, 0, 1, 0, 0),
	COMPOST("Compost", "Compost", PatchImplementation.COMPOST, ItemID.BUCKET_COMPOST, 40, 3, 0, 15),
	SUPERCOMPOST("Supercompost", "Supercompost", PatchImplementation.COMPOST, ItemID.BUCKET_SUPERCOMPOST, 40, 3, 0, 15),
	ULTRACOMPOST("Ultracompost", "Ultracompost", PatchImplementation.COMPOST, ItemID.BUCKET_ULTRACOMPOST, 0, 3, 0, 15),
	ROTTEN_TOMATO("Rotten Tomato", "Rotten Tomato", PatchImplementation.COMPOST, ItemID.ROTTEN_TOMATO, 40, 3, 0, 15),
	EMPTY_BIG_COMPOST_BIN("Big Compost Bin", "Big Compost Bin", PatchImplementation.COMPOST, ItemID.EADGAR_FADE_TO_BLACK_INV, 0, 1, 0, 0),
	BIG_COMPOST("Compost", "Compost", PatchImplementation.BIG_COMPOST, ItemID.BUCKET_COMPOST, 40, 3, 0, 30),
	BIG_SUPERCOMPOST("Supercompost", "Supercompost", PatchImplementation.BIG_COMPOST, ItemID.BUCKET_SUPERCOMPOST, 40, 3, 0, 30),
	BIG_ULTRACOMPOST("Ultracompost", "Ultracompost", PatchImplementation.BIG_COMPOST, ItemID.BUCKET_ULTRACOMPOST, 0, 3, 0, 30),
	BIG_ROTTEN_TOMATO("Rotten Tomato", "Rotten Tomato", PatchImplementation.BIG_COMPOST, ItemID.ROTTEN_TOMATO, 40, 3, 0, 30);

	private final String name;
	private final String contractName;
	@Nullable
	private final PatchImplementation patchImplementation;
	private final int itemID;
	/** Minutes per growth tick. */
	private final int tickrate;
	/** Number of growth states, typically tick count + 1. */
	private final int stages;
	/** Minutes to regrow after harvesting, or 0 if it does not regrow. */
	private final int regrowTickrate;
	/** Number of harvest states, often called lives. */
	private final int harvestStages;

	/** True for the filler entries that are not a real crop. */
	public boolean isCrop()
	{
		return this != WEEDS && this != SCARECROW;
	}

	/** Minimum minutes from planting to fully grown, ignoring disease setbacks. */
	public int getMinutesToGrow()
	{
		return tickrate * (stages - 1);
	}
}
