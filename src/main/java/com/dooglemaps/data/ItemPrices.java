package com.dooglemaps.data;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.game.ItemManager;

/**
 * What an item is worth, readable from any thread.
 *
 * <p>The same arrangement as {@link ItemNames} and for the same reason.
 * {@code ItemManager.getItemPrice} <b>is a client-thread call</b> — it goes through
 * {@code getItemComposition} to find the canonical id, and that asserts. So prices are read once
 * where that is legal and handed out afterwards.
 *
 * <h2>This was learned the hard way</h2>
 *
 * The Stats tab called {@code getItemPrice} straight from the panel on the assumption that a
 * cached lookup was thread-safe. It is not, and the failure was not a wrong number: the
 * {@code AssertionError} unwound the panel's whole refresh, so every section after the one that
 * asked went unbuilt and the tab sat on "nothing here yet" while holding a full history. An
 * {@code Error} rather than an exception, so a {@code catch (RuntimeException)} around the call
 * did not stop it either.
 *
 * <p>Read for a fixed set — every crop, every seed and sapling, and the compost buckets — rather
 * than on demand, because on demand means asking from wherever the price was wanted, which is
 * the thread problem this exists to solve.
 */
@Singleton
public class ItemPrices
{
	private final Map<Integer, Integer> prices = new ConcurrentHashMap<>();

	@Inject
	ItemPrices()
	{
	}

	/**
	 * Reads and caches the price of everything the plugin can put a figure on.
	 *
	 * <p><b>Client thread only.</b> Re-read on each load rather than once per session, because a
	 * price is a live number and a stale one is the whole point of the feature going wrong.
	 */
	public void record(ItemManager itemManager)
	{
		for (Produce produce : Produce.values())
		{
			put(itemManager, produce.getItemID());
		}
		for (Seed seed : Seed.values())
		{
			put(itemManager, seed.getPlantedItemID());
		}
		for (CompostTier tier : CompostTier.values())
		{
			put(itemManager, tier.getItemID());
		}
	}

	private void put(ItemManager itemManager, int itemId)
	{
		if (itemId <= 0)
		{
			return;
		}
		int price = itemManager.getItemPrice(itemId);
		if (price > 0)
		{
			prices.put(itemId, price);
		}
	}

	/**
	 * The item's price, or zero where there is not one.
	 *
	 * <p>Zero rather than a guess. An item with no price is untradeable or has not been read
	 * yet, and both mean "we do not know" — which callers show as a gap rather than as something
	 * worth nothing.
	 */
	public int get(int itemId)
	{
		Integer price = prices.get(itemId);
		return price == null ? 0 : price;
	}

	/** Whether anything has been priced yet, so a caller can tell empty from unloaded. */
	public boolean isLoaded()
	{
		return !prices.isEmpty();
	}

	public void reset()
	{
		prices.clear();
	}
}
