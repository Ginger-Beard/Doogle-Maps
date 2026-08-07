package com.dooglemaps.capture;

import com.dooglemaps.route.BankLocationStore;
import com.dooglemaps.route.RunPlanner;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;

/**
 * Learns which banks the player can actually reach, by noticing where they bank.
 *
 * <p>The bank container arrives when the interface opens, and at that moment the player is
 * standing at the bank — so their position is the bank's position, near enough to route to.
 * That sidesteps having to know which banks are locked behind quests, skills or having
 * built them.
 */
@Slf4j
@Singleton
public class BankCapture
{
	private final Client client;
	private final BankLocationStore banks;
	private final RunPlanner runPlanner;
	private final com.dooglemaps.data.ItemNames itemNames;
	private final net.runelite.client.game.ItemManager itemManager;

	@Inject
	private BankCapture(Client client, BankLocationStore banks, RunPlanner runPlanner,
		com.dooglemaps.data.ItemNames itemNames,
		net.runelite.client.game.ItemManager itemManager)
	{
		this.itemNames = itemNames;
		this.itemManager = itemManager;
		this.client = client;
		this.banks = banks;
		this.runPlanner = runPlanner;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.BANK || client.getLocalPlayer() == null)
		{
			return;
		}

		banks.record(client.getLocalPlayer().getWorldLocation());
		recordNames(event.getItemContainer());

		// Offered, not asserted. leaveBank ends the leg only once nothing is left to collect —
		// opening a bank used to be enough on its own, which finished the shopping before any of
		// it had been done. Called from here as well as from the tick so a withdrawal is acted on
		// in the same tick it happens.
		runPlanner.leaveBank();
	}

	/**
	 * Learns the game's name for everything in the bank.
	 *
	 * <p>Done here because this is the client thread and the container is in hand — {@code
	 * getItemComposition} is a client-thread call, and the teleport list is matched by name from
	 * the Swing thread. See {@link com.dooglemaps.data.ItemNames}.
	 *
	 * <p>The whole bank rather than a shortlist, because which ids matter cannot be known until
	 * the names are read; that is the question being answered. It is only expensive once —
	 * {@code record} skips anything already cached, so the second bank open is nearly free.
	 */
	private void recordNames(net.runelite.api.ItemContainer container)
	{
		if (container == null)
		{
			return;
		}

		java.util.List<Integer> ids = new java.util.ArrayList<>();
		for (net.runelite.api.Item item : container.getItems())
		{
			if (item != null && item.getId() > 0)
			{
				ids.add(item.getId());
			}
		}
		itemNames.record(itemManager, ids);
	}
}
