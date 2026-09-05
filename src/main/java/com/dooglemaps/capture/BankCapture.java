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

	/** For the withdraw list's answer, refreshed ahead of {@code leaveBank}. */
	private final com.dooglemaps.bank.RunLoadout loadout;

	@Inject
	BankCapture(Client client, BankLocationStore banks, RunPlanner runPlanner,
		com.dooglemaps.data.ItemNames itemNames,
		net.runelite.client.game.ItemManager itemManager,
		com.dooglemaps.bank.RunLoadout loadout)
	{
		this.loadout = loadout;
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

		// Never from inside an instance. getWorldLocation() there returns the instance
		// TEMPLATE tile - a real map coordinate in unreachable void - and the store is
		// deliberately permanent, so one chest banked inside an instance would hand
		// Shortest Path a phantom bank target on every supply leg forever after.
		if (!client.getTopLevelWorldView().isInstance())
		{
			banks.record(client.getLocalPlayer().getWorldLocation());
		}
		recordNames(event.getItemContainer());

		// Offered, not asserted. leaveBank ends the leg only once nothing is left to collect —
		// opening a bank used to be enough on its own, which finished the shopping before any of
		// it had been done. Called from here as well as from the tick so a withdrawal is acted on
		// in the same tick it happens — which is why the flag is refreshed first rather than
		// trusting the guide's last per-tick push.
		// The flag only, never the leg's containers. Those are the guide's push, because it is the
		// guide that knows the gear phase narrows them to the hespori's own kit — see
		// GuideTracker.supplyLegSources. Asking the loadout here for the whole run's types and
		// pushing that would point a gear trip at the vault holding next week's saplings for as
		// long as it took the next tick to narrow it back. Ending the leg early is the one thing
		// this event is better placed to answer than the tick, and it is all it answers.
		if (runPlanner.isActive())
		{
			runPlanner.setWithdrawOutstanding(
				loadout.anythingLeftToWithdraw(runPlanner.coveredTypes()));
		}
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
