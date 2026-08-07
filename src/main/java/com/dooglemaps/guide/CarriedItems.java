package com.dooglemaps.guide;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;

/**
 * What is in the inventory right now, and how much room is left.
 *
 * <p>Separate from {@code SeedInventoryStore}, which tracks seeds across four containers and
 * persists them. Guided mode needs something that store deliberately is not: every item, not
 * just seeds — compost buckets, secateurs, the crop being picked — and the free slot count,
 * which is what decides when to send you to the leprechaun.
 *
 * <p>Read from the event rather than from the client, so the panel and the overlay can ask
 * from any thread. {@code Client.getItemContainer} asserts it is on the client thread.
 *
 * <p>That is true of the <i>reads</i>, and it is why {@link #relearnFromClient()} exists as a
 * separate, explicitly client-threaded way in. Being told only by events is correct once the
 * plugin is running and wrong at the moment it starts — see that method.
 */
@Singleton
public class CarriedItems
{
	public static final int INVENTORY_SIZE = 28;

	/**
	 * Inventory and equipment, kept apart.
	 *
	 * <p>Both matter and they answer different questions. "Do you have magic secateurs" has to
	 * count the pair you are wielding; "how many free slots" must not. Reading only the
	 * inventory — which is what this did — made the plugin blind to every worn item, so a
	 * Farming cape on your back, an axe in your hand and an Ardougne cloak round your neck all
	 * read as things you did not own and were told to fetch from the bank.
	 */
	private final Map<Integer, Integer> inventory = new HashMap<>();
	private final Map<Integer, Integer> equipment = new HashMap<>();
	private int usedSlots;

	private final Client client;

	@Inject
	private CarriedItems(Client client)
	{
		this.client = client;
	}

	/**
	 * Reads the pack and the worn items straight out of the client.
	 *
	 * <h2>Because an inventory nobody has touched is never sent</h2>
	 *
	 * Everything here arrived by {@link ItemContainerChanged} and nothing else, which covers every
	 * moment except the one that matters most: the first. A plugin switched on mid-session was
	 * never told what you are holding, and a profile change hands over a different account's pack
	 * without anything having moved in it. The client re-sends a container when it <i>changes</i>,
	 * so in both cases the maps stay empty until the player happens to disturb something.
	 *
	 * <p>Everything that asks {@link #has} then answers no, and the consequences are loud rather
	 * than subtle: every teleport, every piece of diary gear, the herb sack and the seed box read
	 * as things you do not own and are put on the withdraw list — with the tablets sitting in your
	 * pack. Moving any item at all fixes it, which is what made this look like a display glitch
	 * rather than a missing read.
	 *
	 * <p>{@code SeedInventoryStore.relearnFromClient} has covered exactly this for seeds since it
	 * was written, and the reasoning transfers unchanged; only the containers differ.
	 *
	 * <p><b>Must be called on the client thread.</b> That is the whole reason it is a separate
	 * method rather than something the reads do for themselves — see the class note.
	 */
	public void relearnFromClient()
	{
		record(client.getItemContainer(InventoryID.INV));
		recordEquipment(client.getItemContainer(InventoryID.WORN));
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.INV)
		{
			record(event.getItemContainer());
		}
		else if (event.getContainerId() == InventoryID.WORN)
		{
			recordEquipment(event.getItemContainer());
		}
	}

	/**
	 * Replaces what we think is in the inventory.
	 *
	 * <p>Public only so a test in another package can hand it a container; nothing in the
	 * plugin calls it except the event above.
	 */
	public synchronized void record(@Nullable ItemContainer container)
	{
		inventory.clear();
		usedSlots = 0;

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
			inventory.merge(item.getId(), item.getQuantity(), Integer::sum);
			// One slot per item entry, whatever the quantity: a stack of a thousand seeds is
			// still one slot, which is the whole reason noting is worth doing.
			usedSlots++;
		}
	}

	/** Replaces what we think is worn. Does not touch the slot count — equipment is not carried. */
	public synchronized void recordEquipment(@Nullable ItemContainer container)
	{
		equipment.clear();

		if (container == null)
		{
			return;
		}

		for (Item item : container.getItems())
		{
			if (item != null && item.getId() > 0 && item.getQuantity() > 0)
			{
				equipment.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
	}

	/** How many are on the player, worn or in the pack. */
	public synchronized int getCount(int itemId)
	{
		return inventory.getOrDefault(itemId, 0) + equipment.getOrDefault(itemId, 0);
	}

	/** How many are in the inventory specifically, for things that must be held rather than worn. */
	public synchronized int getInventoryCount(int itemId)
	{
		return inventory.getOrDefault(itemId, 0);
	}

	/** Whether any of these are on the player. For items with several forms — see ItemFamilies. */
	public synchronized boolean hasAny(int... itemIds)
	{
		for (int itemId : itemIds)
		{
			if (getCount(itemId) > 0)
			{
				return true;
			}
		}
		return false;
	}

	public synchronized boolean has(int itemId)
	{
		return getCount(itemId) > 0;
	}

	public synchronized int getFreeSlots()
	{
		return Math.max(0, INVENTORY_SIZE - usedSlots);
	}

	/** Forgotten on logout, so a stale pack is never used to decide anything. */
	public synchronized void reset()
	{
		inventory.clear();
		equipment.clear();
		usedSlots = 0;
	}
}
