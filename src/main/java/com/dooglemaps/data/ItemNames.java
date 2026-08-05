package com.dooglemaps.data;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;

/**
 * The game's own name for an item, readable from any thread.
 *
 * <p>Exists because {@code ItemManager.getItemComposition} is a client-thread call — the one
 * other place this plugin uses it says so outright — and the sidebar needs item names while
 * running on Swing. So the names are read once where that is legal and handed out afterwards.
 *
 * <h2>Why not just use the constant name</h2>
 *
 * Because it is wrong in exactly the cases that matter. A protection payment is often a basket or
 * a sack, and {@code ItemID.BASKET_TOMATO_5} would render as "basket tomato 5" where the game
 * says "Basket of tomatoes". Asking the game removes a whole class of hand-written labels that
 * would drift the moment an item was renamed.
 */
@Singleton
public class ItemNames
{
	private final Map<Integer, String> names = new ConcurrentHashMap<>();

	@Inject
	private ItemNames()
	{
	}

	/**
	 * Reads and caches the names of these items. <b>Client thread only.</b>
	 *
	 * <p>Called with a fixed, small set rather than on demand, because on-demand would mean
	 * asking from wherever the name was wanted — which is the thread problem this exists to
	 * solve.
	 */
	public void record(ItemManager itemManager, Iterable<Integer> itemIds)
	{
		for (Integer itemId : itemIds)
		{
			if (itemId == null || itemId <= 0 || names.containsKey(itemId))
			{
				continue;
			}

			ItemComposition composition = itemManager.getItemComposition(itemId);
			if (composition != null && composition.getName() != null)
			{
				names.put(itemId, composition.getName());
			}
		}
	}

	/**
	 * The item's name, or the fallback when it has not been read yet.
	 *
	 * <p>A fallback rather than null because every caller is building a label, and a label with a
	 * hole in it is worse than one that is briefly generic.
	 */
	public String get(int itemId, String fallback)
	{
		String name = names.get(itemId);
		return name == null ? fallback : name;
	}

	@Nullable
	public String get(int itemId)
	{
		return names.get(itemId);
	}

	public void reset()
	{
		names.clear();
	}
}
