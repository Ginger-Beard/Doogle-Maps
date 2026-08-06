package com.dooglemaps.bank;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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

	/**
	 * Told when the bank changes, so what is drawn from it can be redrawn.
	 *
	 * <p>There were none, and the gap showed on the protection rows. Before a bank has been
	 * opened they say "Open a bank to see whether you have 8 coconuts" — correct, and it stayed
	 * there after opening one, because nothing asked the panel to look again. Switching to another
	 * tab and back fixed it, which is the tell: the answer was right and only the paint was stale.
	 *
	 * <p>{@code SeedInventoryStore} fires on its own capture from the same event, so a bank open
	 * usually did repaint — but only when a <i>seed</i> count changed. Opening a bank to check
	 * whether you have the coconuts moves no seeds, which is exactly the case that stayed stale.
	 */
	private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

	@Inject
	private BankContents()
	{
	}

	public void addChangeListener(Runnable listener)
	{
		changeListeners.add(listener);
	}

	public void removeChangeListener(Runnable listener)
	{
		changeListeners.remove(listener);
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

	void record(@Nullable ItemContainer container)
	{
		boolean changed;
		synchronized (this)
		{
			Map<Integer, Integer> incoming = new HashMap<>();
			if (container != null)
			{
				for (Item item : container.getItems())
				{
					if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
					{
						continue;
					}
					incoming.merge(item.getId(), item.getQuantity(), Integer::sum);
				}
			}

			// The first read counts as a change even when the bank is empty, because "we have not
			// looked" and "you own none" are the two answers this class exists to tell apart —
			// and everything drawn from it says something different for each.
			changed = !seen || !incoming.equals(counts);

			counts.clear();
			counts.putAll(incoming);
			seen = true;
		}

		// Only on a real change. A bank fires this event for every deposit and withdrawal, and a
		// notification rebuilds the visible tab — the same reason SeedInventoryStore compares
		// before telling anyone.
		if (changed)
		{
			for (Runnable listener : changeListeners)
			{
				listener.run();
			}
		}
	}

	/**
	 * Every item id the bank was last seen holding.
	 *
	 * <p>Exists so the teleport list can be matched by name: there is no index of every item in
	 * the game to resolve names against, but the only ids that can be filtered or laid out are
	 * the ones in here — so for that question, this is the index. See
	 * {@code RunLoadout.addListedTeleports}.
	 */
	public synchronized java.util.Set<Integer> getItemIds()
	{
		return new java.util.LinkedHashSet<>(counts.keySet());
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

	public void reset()
	{
		synchronized (this)
		{
			counts.clear();
			seen = false;
		}
		for (Runnable listener : changeListeners)
		{
			listener.run();
		}
	}
}
