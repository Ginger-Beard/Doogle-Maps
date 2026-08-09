package com.dooglemaps.data;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.runelite.api.gameval.ItemID;

/**
 * Harvested items the leprechaun notes that {@link Produce} cannot name.
 *
 * <p>{@code Produce} is generated from RuneLite core's Time Tracking enum, which stores one
 * item per crop — enough for core, which only needs an identity for the patch contents. Two
 * kinds of harvest fall outside that one item, and both walked straight past the noting step
 * because nothing recognised them as produce at all. Reported from play, separately:
 *
 * <ul>
 *   <li><b>Tree roots</b> — the second harvest a tree patch hands over, on the dig-up. The
 *       enum's tree rows carry the logs.</li>
 *   <li><b>Grimy herbs</b> — the only harvest a herb patch hands over. The enum's herb rows
 *       carry the <i>clean</i> herb, so a pack of loose grimy ranarrs read as no crop.</li>
 * </ul>
 *
 * <p>Unnoted, none of these stack, so every one walked away with is a slot gone for the rest
 * of the run. The herb sack needs no special case here: an open sack swallows grimy herbs
 * before they reach the inventory, so a loose grimy herb in the pack already means the sack
 * is full, closed, or not owned — exactly the times noting is the right advice.
 */
public final class NotableHarvests
{
	/** Item id to display name, as the note step speaks it. */
	private static final Map<Integer, String> BY_ITEM_ID = new LinkedHashMap<>();

	static
	{
		BY_ITEM_ID.put(ItemID.OAK_ROOTS, "oak roots");
		BY_ITEM_ID.put(ItemID.WILLOW_ROOTS, "willow roots");
		BY_ITEM_ID.put(ItemID.MAPLE_ROOTS, "maple roots");
		BY_ITEM_ID.put(ItemID.YEW_ROOTS, "yew roots");
		BY_ITEM_ID.put(ItemID.MAGIC_ROOTS, "magic roots");

		// One per herb Produce row. Goutweed is absent because it has no grimy form, and the
		// raids and jungle herbs are absent because no farm patch grows them.
		BY_ITEM_ID.put(ItemID.UNIDENTIFIED_GUAM, "grimy guam");
		BY_ITEM_ID.put(ItemID.UNIDENTIFIED_MARENTILL, "grimy marrentill");
		BY_ITEM_ID.put(ItemID.UNIDENTIFIED_TARROMIN, "grimy tarromin");
		BY_ITEM_ID.put(ItemID.UNIDENTIFIED_HARRALANDER, "grimy harralander");
		BY_ITEM_ID.put(ItemID.UNIDENTIFIED_RANARR, "grimy ranarr");
		BY_ITEM_ID.put(ItemID.UNIDENTIFIED_TOADFLAX, "grimy toadflax");
		BY_ITEM_ID.put(ItemID.UNIDENTIFIED_IRIT, "grimy irit");
		BY_ITEM_ID.put(ItemID.UNIDENTIFIED_AVANTOE, "grimy avantoe");
		BY_ITEM_ID.put(ItemID.UNIDENTIFIED_KWUARM, "grimy kwuarm");
		BY_ITEM_ID.put(ItemID.UNIDENTIFIED_HUASCA, "grimy huasca");
		BY_ITEM_ID.put(ItemID.UNIDENTIFIED_SNAPDRAGON, "grimy snapdragon");
		BY_ITEM_ID.put(ItemID.UNIDENTIFIED_CADANTINE, "grimy cadantine");
		BY_ITEM_ID.put(ItemID.UNIDENTIFIED_LANTADYME, "grimy lantadyme");
		BY_ITEM_ID.put(ItemID.UNIDENTIFIED_DWARF_WEED, "grimy dwarf weed");
		BY_ITEM_ID.put(ItemID.UNIDENTIFIED_TORSTOL, "grimy torstol");
	}

	private NotableHarvests()
	{
	}

	/** Whether this item is a harvest the leprechaun notes but Produce cannot name. */
	public static boolean isNotable(int itemId)
	{
		return BY_ITEM_ID.containsKey(itemId);
	}

	/** The item's name as spoken in guide text, or null for an item not in the table. */
	@Nullable
	public static String nameOf(int itemId)
	{
		return BY_ITEM_ID.get(itemId);
	}

	/** Every item id in the table. */
	public static Iterable<Integer> ids()
	{
		return BY_ITEM_ID.keySet();
	}
}
