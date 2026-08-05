package com.dooglemaps.bank;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;

/**
 * What is in the bank, so a loadout can say "you own this" rather than "you should get this".
 *
 * <p>The bank container is already read on every open by {@code SeedInventoryStore}, which
 * throws away everything that is not a seed. This keeps the rest — one pass over the same
 * event, no extra work when the bank opens.
 *
 * <p>Not persisted, deliberately. Seed counts are cached across sessions because the panel
 * shows them when you are nowhere near a bank; this only matters while you are standing at
 * one, and a remembered bank is a bank that can be wrong.
 */
@Singleton
public class BankContents
{
	private final Map<Integer, Integer> counts = new HashMap<>();
	private boolean seen;

	@Inject
	private BankContents()
	{
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.BANK)
		{
			return;
		}
		record(event.getItemContainer());
	}

	synchronized void record(@Nullable ItemContainer container)
	{
		counts.clear();
		seen = true;

		if (container == null)
		{
			return;
		}

		for (Item item : container.getItems())
		{
			if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			counts.merge(item.getId(), item.getQuantity(), Integer::sum);
		}
	}

	public synchronized int getCount(int itemId)
	{
		return counts.getOrDefault(itemId, 0);
	}

	public synchronized boolean has(int itemId)
	{
		return getCount(itemId) > 0;
	}

	/**
	 * Whether a bank has been read this session.
	 *
	 * <p>Worth asking before drawing conclusions from an empty one: "your bank has no Ardougne
	 * cloak" and "we have not looked in your bank" deserve different answers.
	 */
	public synchronized boolean hasBeenSeen()
	{
		return seen;
	}

	public synchronized void reset()
	{
		counts.clear();
		seen = false;
	}
}
