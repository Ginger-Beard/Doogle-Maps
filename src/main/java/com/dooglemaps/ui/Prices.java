package com.dooglemaps.ui;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.ItemPrices;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.Seed;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * What things are worth, for the one section of the Stats tab that talks in coins.
 *
 * <h2>What this is and is not</h2>
 *
 * It describes what a crop is worth. It never suggests buying one — the plugin's compliance line
 * is that nothing anywhere tells you to go to the Grand Exchange, and "your snapdragon harvest
 * came to 3.1M" stays comfortably on the right side of that. A <i>recommendation</i> built on
 * prices would not, so this deliberately produces figures and no advice.
 *
 * <h2>Three things every number here carries</h2>
 *
 * <ul>
 *   <li><b>It is today's price on an old harvest.</b> Nothing ever recorded the price at the
 *       time and historical prices are not available offline, so this is "what that would be
 *       worth now" — the right question for deciding what to plant next, and the wrong one for a
 *       lifetime earnings claim.</li>
 *   <li><b>Costs are notional for an ironman</b>, who did not buy the seed and made the compost
 *       themselves. The arithmetic is the same and the word is not: for them it is value
 *       produced rather than profit.</li>
 *   <li><b>Protection payments are not in it.</b> Nothing has ever recorded whether a given
 *       patch was paid for, and the plant-out projection does not model disease at all — so
 *       charging for protection while crediting none of its benefit would be worse than
 *       omitting both.</li>
 * </ul>
 *
 * <h2>Prices come from a cache, never from {@code ItemManager}</h2>
 *
 * This class used to call {@code ItemManager.getItemPrice} directly, on the belief — stated in
 * the spec and never checked — that it was a cached lookup safe to make while repainting. It is
 * not: it resolves the canonical item through {@code getItemComposition}, which asserts it is on
 * the client thread. The failure was not a wrong figure. The {@code AssertionError} unwound the
 * panel's entire refresh, so every section after the one that asked went unbuilt and the tab read
 * "nothing here yet" over a full history.
 *
 * <p>So prices are read on the client thread into {@link ItemPrices} and this only ever reads
 * that. Same arrangement as {@code ItemNames}, which had already solved the identical problem for
 * item names.
 */
class Prices
{
	/** Produce by display name, because the harvest history stores the name and not the id. */
	private static final Map<String, Produce> BY_NAME = new LinkedHashMap<>();

	static
	{
		for (Produce produce : Produce.values())
		{
			BY_NAME.putIfAbsent(produce.getName(), produce);
		}
	}

	private final ItemPrices items;

	Prices(ItemPrices items)
	{
		this.items = items;
	}

	/** Whether prices are available at all. Off before the item cache has loaded. */
	boolean isKnown(Produce produce)
	{
		return produce != null && priceOf(produce.getItemID()) > 0;
	}

	@Nullable
	static Produce produceNamed(String name)
	{
		return name == null ? null : BY_NAME.get(name);
	}

	/** What a pile of one crop would fetch at today's price. */
	long valueOf(@Nullable Produce produce, double count)
	{
		return produce == null || count <= 0
			? 0
			: Math.round(priceOf(produce.getItemID()) * count);
	}

	/** What the seeds for this many patches cost, at today's price. */
	long seedCost(@Nullable Seed seed, int patches)
	{
		if (seed == null || patches <= 0)
		{
			return 0;
		}
		// The item that actually goes in the ground: a sapling for a tree, the seed otherwise.
		// Charging tree runs the seed price would understate them by an order of magnitude.
		return (long) priceOf(seed.getPlantedItemID()) * patches * seed.getSeedsPerPatch();
	}

	/** What treating this many patches costs, at today's price. Untreated is free. */
	long compostCost(@Nullable CompostTier tier, int patches)
	{
		return tier == null || tier == CompostTier.NONE || patches <= 0
			? 0
			: (long) priceOf(tier.getItemID()) * patches;
	}

	/** A price, or zero where there is not one. See {@link ItemPrices#get}. */
	private int priceOf(int itemId)
	{
		return itemId <= 0 ? 0 : items.get(itemId);
	}
}
