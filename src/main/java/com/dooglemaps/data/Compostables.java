package com.dooglemaps.data;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import net.runelite.api.gameval.ItemID;

/**
 * What goes into a compost bin, and what comes out when it does.
 *
 * <h2>The bin has three outcomes and only one of them is chosen</h2>
 *
 * Fill it with fifteen of anything compostable and you get compost. Fill it with fifteen items
 * from {@link #superCompostables()} — all fifteen, no mixing — and you get supercompost, which
 * is the only reason anyone fills a bin by hand. Fill it entirely with tomatoes and you get
 * <b>rotten tomatoes</b>, which is not a mistake the game warns you about and not one worth
 * making: fifteen tomatoes in, fifteen rotten tomatoes out, no compost at all.
 *
 * <p>Hand-written, like {@link NotableHarvests} and {@link SpadeClearedCrops}, from the wiki's
 * "Suitable items" table. {@link Produce} is generated from RuneLite core and cannot carry a
 * fact core has no use for.
 *
 * <h2>Grimy counts</h2>
 *
 * Both forms of every listed herb are on the wiki's table, and the gameval names for the grimy
 * ones are {@code UNIDENTIFIED_*} rather than anything containing "grimy" — worth stating,
 * because a search for the obvious name finds nothing and the natural conclusion is that they
 * are absent from the table rather than named oddly.
 */
public final class Compostables
{
	/**
	 * Items that make a whole bin into supercompost.
	 *
	 * <p>Insertion-ordered so a selector built from this reads in a sensible order rather than
	 * by item id: the fruit people actually farm bins with first, then the berries, then the
	 * herbs, then the leftovers that have no other use.
	 *
	 * <p>Two items on the wiki's table are deliberately absent. <b>White lily</b> has no
	 * harvested item at all — the flower cannot be picked, only planted, so there is nothing to
	 * put in a bin and nothing to name in a selector. <b>Ent branch</b> is here, because it is
	 * a real dropped item, but it is worth knowing it comes from Forestry rather than farming
	 * and nobody stockpiles it.
	 */
	private static final Set<Integer> SUPER = superSet();

	private static Set<Integer> superSet()
	{
		Set<Integer> items = new LinkedHashSet<>();

		// The fruit that bins are actually run on: a fruit tree run comes home with enough
		// pineapples or papayas to fill several.
		Collections.addAll(items,
			ItemID.PINEAPPLE,
			ItemID.TENTIPINEAPPLE,
			ItemID.WATERMELON,
			ItemID.PAPAYA,
			ItemID.DRAGONFRUIT,
			ItemID.COCONUT,
			ItemID.COCONUT_HALF,
			ItemID.COCONUT_SHELL,
			ItemID.CALQUAT_FRUIT,
			ItemID.GARDEN_WHITE_TREE_FRUIT);

		// Bush produce, which is the other thing a farm run comes home with in fifteens.
		Collections.addAll(items,
			ItemID.JANGERBERRIES,
			ItemID.WHITE_BERRIES,
			ItemID.POISONIVY_BERRIES);

		// Toadflax and up, clean and grimy alike. Nothing below toadflax qualifies, which is
		// the line the wiki's table draws and the reason a guam run's leftovers make ordinary
		// compost however many go in.
		Collections.addAll(items,
			ItemID.TOADFLAX, ItemID.UNIDENTIFIED_TOADFLAX,
			ItemID.AVANTOE, ItemID.UNIDENTIFIED_AVANTOE,
			ItemID.KWUARM, ItemID.UNIDENTIFIED_KWUARM,
			ItemID.SNAPDRAGON, ItemID.UNIDENTIFIED_SNAPDRAGON,
			ItemID.CADANTINE, ItemID.UNIDENTIFIED_CADANTINE,
			ItemID.LANTADYME, ItemID.UNIDENTIFIED_LANTADYME,
			ItemID.DWARF_WEED, ItemID.UNIDENTIFIED_DWARF_WEED,
			ItemID.TORSTOL, ItemID.UNIDENTIFIED_TORSTOL);

		// And the rest: tree roots from a chopped farmed tree, snape grass, the mushroom patch's
		// crop, celastrus bark, and Forestry's ent branch.
		Collections.addAll(items,
			ItemID.OAK_ROOTS,
			ItemID.WILLOW_ROOTS,
			ItemID.MAPLE_ROOTS,
			ItemID.YEW_ROOTS,
			ItemID.MAGIC_ROOTS,
			ItemID.SNAPE_GRASS,
			ItemID.BITTERCAP_MUSHROOM,
			ItemID.CELASTRUS_WOOD,
			ItemID.ENT_BRANCH);

		return Collections.unmodifiableSet(items);
	}

	private Compostables()
	{
	}

	/** Every item that turns a full bin into supercompost, in the order a list should show them. */
	public static Set<Integer> superCompostables()
	{
		return SUPER;
	}

	/** Whether a bin filled with this alone comes out as supercompost. */
	public static boolean isSuperCompostable(int itemId)
	{
		return SUPER.contains(itemId);
	}

	/**
	 * The one filling that produces no compost whatsoever.
	 *
	 * <p>Its own method rather than a line in a comment because it is the only way to waste a
	 * bin: fifteen tomatoes give fifteen rotten tomatoes, and the bin's own state varbit has a
	 * {@code ROTTEN_TOMATO} family for exactly this. Anything offering a fill should refuse it
	 * or say what it does.
	 */
	public static boolean isRottenTomatoTrap(int itemId)
	{
		return itemId == ItemID.TOMATO;
	}
}
