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

	/**
	 * The grimy herbs alone, because they are the one entry here with a hostile left-click.
	 *
	 * <p>Everything else in the table defaults to Use, which is exactly what handing it to the
	 * leprechaun wants. A grimy herb's left-click is <b>Clean</b> — so following the note step
	 * by clicking the highlighted herb quietly cleaned one instead, and cleaned herbs are ones
	 * he will not note. {@code GuideMenuSwap} promotes Use over Clean while the note step is
	 * current, and this set is how it knows which items that applies to.
	 */
	private static final java.util.Set<Integer> GRIMY_HERBS = new java.util.LinkedHashSet<>();

	static
	{
		BY_ITEM_ID.put(ItemID.OAK_ROOTS, "oak roots");
		BY_ITEM_ID.put(ItemID.WILLOW_ROOTS, "willow roots");
		BY_ITEM_ID.put(ItemID.MAPLE_ROOTS, "maple roots");
		BY_ITEM_ID.put(ItemID.YEW_ROOTS, "yew roots");
		BY_ITEM_ID.put(ItemID.MAGIC_ROOTS, "magic roots");

		// Celastrus bark, the third harvest with no Produce row of its own: core's enum stores
		// celastrus as a BATTLESTAFF — what the bark is eventually made into — so the bark itself
		// (22935, named CELASTRUS_WOOD in the cache) was as invisible to the note step as the
		// grimy herbs were. Found while removing logs from the step, which is the same fault read
		// the other way round: the enum's item is not always the thing that comes off the patch.
		BY_ITEM_ID.put(ItemID.CELASTRUS_WOOD, "celastrus bark");

		// One per herb Produce row. Goutweed is absent because it has no grimy form, and the
		// raids and jungle herbs are absent because no farm patch grows them.
		herb(ItemID.UNIDENTIFIED_GUAM, "grimy guam");
		herb(ItemID.UNIDENTIFIED_MARENTILL, "grimy marrentill");
		herb(ItemID.UNIDENTIFIED_TARROMIN, "grimy tarromin");
		herb(ItemID.UNIDENTIFIED_HARRALANDER, "grimy harralander");
		herb(ItemID.UNIDENTIFIED_RANARR, "grimy ranarr");
		herb(ItemID.UNIDENTIFIED_TOADFLAX, "grimy toadflax");
		herb(ItemID.UNIDENTIFIED_IRIT, "grimy irit");
		herb(ItemID.UNIDENTIFIED_AVANTOE, "grimy avantoe");
		herb(ItemID.UNIDENTIFIED_KWUARM, "grimy kwuarm");
		herb(ItemID.UNIDENTIFIED_HUASCA, "grimy huasca");
		herb(ItemID.UNIDENTIFIED_SNAPDRAGON, "grimy snapdragon");
		herb(ItemID.UNIDENTIFIED_CADANTINE, "grimy cadantine");
		herb(ItemID.UNIDENTIFIED_LANTADYME, "grimy lantadyme");
		herb(ItemID.UNIDENTIFIED_DWARF_WEED, "grimy dwarf weed");
		herb(ItemID.UNIDENTIFIED_TORSTOL, "grimy torstol");
	}

	private static void herb(int itemId, String name)
	{
		BY_ITEM_ID.put(itemId, name);
		GRIMY_HERBS.add(itemId);
	}

	private NotableHarvests()
	{
	}

	/** Whether this item is a harvest the leprechaun notes but Produce cannot name. */
	public static boolean isNotable(int itemId)
	{
		return BY_ITEM_ID.containsKey(itemId);
	}

	/** Whether this item is a grimy herb, whose left-click is Clean rather than Use. */
	public static boolean isGrimyHerb(int itemId)
	{
		return GRIMY_HERBS.contains(itemId);
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
