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
 * <p>Two lists, because the bin has two useful outcomes and the player picks between them.
 * Hand-written, like {@link NotableHarvests} and {@link SpadeClearedCrops}, from the wiki's
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

	/**
	 * Items that make a bin into ordinary compost.
	 *
	 * <p>The other half of the wiki's table, and worth offering rather than hiding: an account
	 * with no pineapples still has potatoes and grimy guams, and ordinary compost is what most
	 * of a farm run actually gets treated with. A bin filled with any mixture that is not all
	 * supercompostable produces this, so the list is only ever "what to fill with on purpose".
	 *
	 * <p>Ordered like the other list — the things people hold in piles first, the odds and ends
	 * last. Three of the entries are worth knowing about because they surprise people:
	 * <b>giant seaweed</b> and <b>potato cactus</b> are ordinary rather than super despite being
	 * high-level produce, and <b>tomatoes</b> are here but are a trap; see
	 * {@link #isRottenTomatoTrap}.
	 *
	 * <p>Deliberately not exhaustive at the silly end. The wiki's table also lists kebabs, apple
	 * mush and rotten apples, which nobody stockpiles to compost — listing them would pad a grid
	 * whose whole job is to be scanned at a glance.
	 */
	private static final Set<Integer> ORDINARY = ordinarySet();

	private static Set<Integer> ordinarySet()
	{
		Set<Integer> items = new LinkedHashSet<>();

		// Allotment crops, which is what most people have hundreds of.
		Collections.addAll(items,
			ItemID.POTATO,
			ItemID.ONION,
			ItemID.CABBAGE,
			ItemID.SWEETCORN,
			ItemID.STRAWBERRY,
			ItemID.WATERMELON_SLICE,
			ItemID.TOMATO);

		// Herbs below toadflax, clean and grimy alike - the line the supercompost list draws.
		Collections.addAll(items,
			ItemID.GUAM_LEAF, ItemID.UNIDENTIFIED_GUAM,
			ItemID.MARENTILL, ItemID.UNIDENTIFIED_MARENTILL,
			ItemID.TARROMIN, ItemID.UNIDENTIFIED_TARROMIN,
			ItemID.HARRALANDER, ItemID.UNIDENTIFIED_HARRALANDER,
			ItemID.RANARR_WEED, ItemID.UNIDENTIFIED_RANARR,
			ItemID.IRIT_LEAF, ItemID.UNIDENTIFIED_IRIT);

		// Hops and the rest of a hops patch's output.
		Collections.addAll(items,
			ItemID.BARLEY,
			ItemID.HAMMERSTONE_HOPS,
			ItemID.ASGARNIAN_HOPS,
			ItemID.YANILLIAN_HOPS,
			ItemID.KRANDORIAN_HOPS,
			ItemID.WILDBLOOD_HOPS,
			ItemID.JUTE_FIBRE,
			ItemID.FLAX);

		// Flowers, and the bush berries under jangerberry.
		Collections.addAll(items,
			ItemID.MARIGOLD,
			ItemID.ROSEMARY,
			ItemID.NASTURTIUM,
			ItemID.WOADLEAF,
			ItemID.LIMPWURT_ROOT,
			ItemID.REDBERRIES,
			ItemID.CADAVABERRIES,
			ItemID.DWELLBERRIES);

		// Fruit-tree fruit, which is the ordinary-compost counterpart of the papayas and
		// coconuts on the other list.
		Collections.addAll(items,
			ItemID.COOKING_APPLE,
			ItemID.BANANA,
			ItemID.ORANGE,
			ItemID.LEMON,
			ItemID.LIME,
			ItemID.PEACH,
			ItemID.CURRY_LEAF);

		// Forestry's leaves, a woodcutting by-product with no other use, and the odds and ends.
		Collections.addAll(items,
			ItemID.LEAVES,
			ItemID.LEAVES_OAK,
			ItemID.LEAVES_WILLOW,
			ItemID.LEAVES_MAPLE,
			ItemID.LEAVES_YEW,
			ItemID.LEAVES_MAGIC,
			ItemID.WILLOW_BRANCH,
			ItemID.WEEDS,
			ItemID.GRAIN,
			ItemID.SEAWEED,
			ItemID.EDIBLE_SEAWEED,
			// Both of these read as things that ought to be supercompostable and are not.
			ItemID.GIANT_SEAWEED,
			ItemID.CACTUS_POTATO);

		return Collections.unmodifiableSet(items);
	}

	private Compostables()
	{
	}

	/** Every item that makes a full bin into ordinary compost, in list order. */
	public static Set<Integer> ordinaryCompostables()
	{
		return ORDINARY;
	}

	/** Whether a bin filled with this alone comes out as ordinary compost. */
	public static boolean isOrdinaryCompostable(int itemId)
	{
		return ORDINARY.contains(itemId);
	}

	/** Whether this can go in a bin at all, at either tier. The store's validation. */
	public static boolean isCompostable(int itemId)
	{
		return isSuperCompostable(itemId) || isOrdinaryCompostable(itemId);
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
	 *
	 * <p>The tomato is still on {@link #ordinaryCompostables()} rather than struck off it,
	 * because the wiki's own escape is worth leaving open: "if filling a bin with tomatoes, at
	 * least one compostable item must be of a different type" — so a mostly-tomato bin is a
	 * perfectly good bin, and only an <i>entirely</i> tomato one is wasted.
	 */
	public static boolean isRottenTomatoTrap(int itemId)
	{
		return itemId == ItemID.TOMATO;
	}
}
