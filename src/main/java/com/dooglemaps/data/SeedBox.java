package com.dooglemaps.data;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What a seed box holds, and how much of it.
 *
 * <h2>Why this exists as a class of its own</h2>
 *
 * The seed box has produced more repeat bug reports than anything else in the plugin, and they
 * were all the same bug: <b>"can this item go in a box"</b> had no single home, so four places
 * worked it out separately and disagreed.
 *
 * <ul>
 *   <li>{@code countSeeds} accepted anything in the {@link Seed} table — which includes sapling
 *       item ids, because {@code Seed.forItemId} maps both forms to the crop.</li>
 *   <li>The kind counter and the fill highlight used {@code Seed.isSapling()}, which is a fact
 *       about the <b>crop</b> and not about the item in front of you. A calquat <i>seed</i> goes
 *       in a box; the plant pot it becomes does not. Skipping the crop lost a whole slot, the
 *       box read as having room when it was full, and it lit to fill with a seventh kind loose
 *       in the pack.</li>
 *   <li>The Fill derivation counted raw map entries, and the overlay walked the seed table, and
 *       the log caught the two disagreeing about the same box in the same second.</li>
 * </ul>
 *
 * <p>The rule itself is simple and was never in doubt: <b>every seed goes in, no sapling does</b>.
 * Frags and mushroom spores are seeds for this purpose — the wiki says so outright, and they are
 * in {@link Seed} with a seed item of their own. So the set is derived from the table rather than
 * hand-listed, and cannot fall behind it.
 *
 * <p>The capacity lived in three files that each read from the next. One definition now, and the
 * others are gone rather than aliased — an alias is how three of them happened.
 */
public final class SeedBox
{
	/**
	 * How many kinds the box holds — six, one stack each, no practical limit per stack.
	 *
	 * <p>The wiki's own figure for a stack is 2,147,483,647, so "full" can only ever mean six
	 * kinds. That makes this an <b>invariant</b> rather than a layout number: a model holding a
	 * seventh kind is proof its arithmetic has drifted, which is what
	 * {@code SeedInventoryStore.boxCannotBeRight} acts on.
	 */
	public static final int KINDS = 6;

	/** Every seed item, and no sapling. Derived from {@link Seed} so it cannot fall behind it. */
	private static final Set<Integer> ACCEPTED;

	static
	{
		Set<Integer> accepted = new HashSet<>();
		for (Seed seed : Seed.values())
		{
			// getItemID, never getPlantedItemID: for a tree crop the planted form is the
			// sapling, which is the one thing the box refuses.
			accepted.add(seed.getItemID());
		}
		ACCEPTED = Collections.unmodifiableSet(accepted);
	}

	private SeedBox()
	{
	}

	/** Whether the box will hold this item. */
	public static boolean accepts(int itemId)
	{
		return ACCEPTED.contains(itemId);
	}

	/**
	 * How many of the six kinds these contents use.
	 *
	 * <p>Ids the box cannot hold are not counted. They should never be in a box's contents at
	 * all — {@link #onlyWhatItHolds} is what keeps them out — and counting one would close the
	 * box off for a reason nobody could look up.
	 */
	public static int kindsIn(Collection<Integer> itemIds)
	{
		int kinds = 0;
		for (Integer itemId : itemIds)
		{
			if (itemId != null && accepts(itemId))
			{
				kinds++;
			}
		}
		return kinds;
	}

	/**
	 * These contents with anything the box cannot hold removed.
	 *
	 * <p>Applied where a box's contents are recorded, so the rest of the plugin can treat "in
	 * the box" as meaning something the box can actually hold. Without it a sapling read into
	 * the box's model would spend one of the six kinds forever.
	 */
	public static Map<Integer, Integer> onlyWhatItHolds(Map<Integer, Integer> contents)
	{
		Map<Integer, Integer> kept = new LinkedHashMap<>();
		for (Map.Entry<Integer, Integer> entry : contents.entrySet())
		{
			if (entry.getKey() != null && accepts(entry.getKey())
				&& entry.getValue() != null && entry.getValue() > 0)
			{
				kept.put(entry.getKey(), entry.getValue());
			}
		}
		return kept;
	}
}
