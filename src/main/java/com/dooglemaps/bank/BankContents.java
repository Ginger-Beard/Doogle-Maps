package com.dooglemaps.bank;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
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
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;

/**
 * What is in the bank, so a loadout can say "you own this" rather than "you should get this".
 *
 * <p>The bank container is already read on every open by {@code SeedInventoryStore}, which
 * throws away everything that is not a seed. This keeps the rest — one pass over the same
 * event, no extra work when the bank opens.
 *
 * <h2>Persisted per profile, which reverses an earlier decision</h2>
 *
 * This class used to keep nothing across sessions, on the argument that a remembered bank is a
 * bank that can be wrong. What that argument missed is who reads it and <b>when</b>: the
 * loadout, the withdraw list, the slot counts and the bank filter are all consumed at the start
 * of a session, before any bank has been opened. With nothing remembered, every payment and
 * tool read as unknown, the protection budget was zero, and a protected seed was allocated
 * <b>no patches at all</b> — so the run's own yew vanished from the list, "to withdraw" was
 * empty, and the filter narrowed the bank to a set missing exactly the things the trip was
 * for. All of it sprang to life the moment a bank was opened, which is the reported shape:
 * <i>"doesn't work on first load; the list doesn't show up until I open the bank."</i>
 *
 * <p>The seed store crossed this same bridge long ago and for the same reason — counts are
 * shown when you are nowhere near a bank. The staleness risk is the one already accepted
 * there: a remembered bank is corrected wholesale the moment the real one is read, and
 * {@code Need.UNKNOWN} still covers an account whose bank has genuinely never been seen.
 */
@Singleton
public class BankContents extends com.dooglemaps.state.ProfileJsonStore
{
	private static final String CONTENTS_KEY = "bankContents";

	private static final Type COUNT_MAP_TYPE = new TypeToken<HashMap<Integer, Integer>>()
	{
	}.getType();

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
	BankContents(ConfigManager configManager, Gson gson)
	{
		super(configManager, gson, CONTENTS_KEY);
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

			if (changed)
			{
				// On a real change only, like the notification: a bank fires this event for
				// every deposit, and the write is what makes next session start informed.
				save();
			}
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
	 * Whether this profile's bank has ever been read — this session, or a previous one.
	 *
	 * <p>Worth asking before drawing conclusions from an empty one: "your bank has no Ardougne
	 * cloak" and "we have not looked in your bank" deserve different answers.
	 */
	public synchronized boolean hasBeenSeen()
	{
		return seen;
	}

	/** Empties the in-memory side, for a profile switch. The stored copy is load()'s to restore. */
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

	@Override
	protected void resetForLoad()
	{
		counts.clear();
		seen = false;
	}

	@Override
	protected void applyJson(String json)
	{
		Map<Integer, Integer> loaded = gson.fromJson(json, COUNT_MAP_TYPE);
		if (loaded != null)
		{
			loaded.forEach((id, quantity) ->
			{
				if (id != null && quantity != null && id > 0 && quantity > 0)
				{
					counts.put(id, quantity);
				}
			});
		}
		// A stored blob at all — even an empty bank's "{}" — means a bank was read at some
		// point, and that fact is half of what this class answers.
		seen = true;
	}

	@Override
	protected Object serialized()
	{
		return counts;
	}

	@Override
	protected void loaded()
	{
		for (Runnable listener : changeListeners)
		{
			listener.run();
		}
	}
}
