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

	@Inject
	private BankCapture(Client client, BankLocationStore banks, RunPlanner runPlanner)
	{
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

		// Reaching a bank is what ends a run's opening leg; from here it routes to patches.
		runPlanner.leaveBank();
	}
}
