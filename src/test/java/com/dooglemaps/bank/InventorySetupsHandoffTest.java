package com.dooglemaps.bank;

import com.dooglemaps.route.RunPlanner;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The hespori gear handoff, driven against a fake Inventory Setups over a real
 * {@link EventBus} — the same bus, the same messages, the same synchronous fill of the
 * caller's collection that the installed plugin performs.
 *
 * <p>This conversation already failed once in the field: the integration was first written
 * against a message API absent from the Hub build actually installed, and every message was a
 * silent no-op — "no hespori setup found" over a setup that plainly existed. The API is in the
 * Hub build now and is the only transport, but hespori seeds are rare and the patch takes a
 * day, so the exchange gets almost no live rehearsals; this harness is where it rehearses.
 */
public class InventorySetupsHandoffTest
{
	private Client client;
	private RunPlanner planner;
	private ClientThread clientThread;
	private EventBus eventBus;
	private FakeInventorySetups setups;
	private InventorySetupsHandoff handoff;

	/** The bank widget the handoff watches; null means closed. */
	private Widget bankItems;

	@Before
	public void setUp()
	{
		client = mock(Client.class);
		planner = mock(RunPlanner.class);
		clientThread = mock(ClientThread.class);
		when(client.getWidget(InterfaceID.Bankmain.ITEMS)).thenAnswer(call -> bankItems);

		// Inventory Setups installed and listening, which is the ordinary case.
		eventBus = new EventBus();
		setups = new FakeInventorySetups();
		eventBus.register(setups);
		handoff = handoffOn(eventBus);
		eventBus.register(handoff);
	}

	private InventorySetupsHandoff handoffOn(EventBus bus)
	{
		return new InventorySetupsHandoff(client, clientThread, bus, planner);
	}

	private void bankOpen(boolean open)
	{
		if (open)
		{
			bankItems = mock(Widget.class);
			when(bankItems.isHidden()).thenReturn(false);
		}
		else
		{
			bankItems = null;
		}
	}

	@Test
	public void findsTheSetupCaseInsensitivelyAndSelectsIt()
	{
		setups.addSetup("Farm run");
		setups.addSetup("HESPORI");
		when(planner.isGearPhase()).thenReturn(true);

		handoff.onGameTick(new GameTick());

		assertEquals("selected in the panel, spelled the player's way", "HESPORI",
			setups.getCurrentSelectedSetup());
		assertTrue("and the supply lines name it",
			handoff.supplyLines().stream().anyMatch(line -> line.contains("\"HESPORI\"")));
	}

	@Test
	public void aMissingSetupIsSaidOutLoudAndNothingIsSelected()
	{
		setups.addSetup("Vorkath");
		when(planner.isGearPhase()).thenReturn(true);

		handoff.onGameTick(new GameTick());

		assertNull(setups.getCurrentSelectedSetup());
		assertTrue("the player is told to gear up by hand",
			handoff.supplyLines().stream()
				.anyMatch(line -> line.contains("No \"hespori\" setup")));
	}

	/**
	 * Inventory Setups not installed at all: nobody answers, and the leg says the same thing
	 * it says for a missing setup rather than throwing into the event bus.
	 */
	@Test
	public void anAbsentInventorySetupsIsJustAnUnansweredMessage()
	{
		InventorySetupsHandoff alone = handoffOn(new EventBus());
		when(planner.isGearPhase()).thenReturn(true);

		alone.onGameTick(new GameTick());

		assertTrue("the player is told to gear up by hand",
			alone.supplyLines().stream()
				.anyMatch(line -> line.contains("No \"hespori\" setup")));
	}

	/** A setup created after the run started is found on the retry clock, not never. */
	@Test
	public void aLateSetupIsFoundByTheRetry()
	{
		when(planner.isGearPhase()).thenReturn(true);
		handoff.onGameTick(new GameTick());
		assertNull("fixture: nothing to find yet", setups.getCurrentSelectedSetup());

		setups.addSetup("hespori");
		when(client.getTickCount()).thenReturn(150);
		handoff.onGameTick(new GameTick());

		assertEquals("hespori", setups.getCurrentSelectedSetup());
	}

	/**
	 * And found at once when Inventory Setups says the list changed, without waiting out the
	 * retry clock — the tick count never moves in this test.
	 */
	@Test
	public void aSetupsChangedBroadcastReMatchesImmediately()
	{
		when(planner.isGearPhase()).thenReturn(true);
		handoff.onGameTick(new GameTick());
		assertNull("fixture: nothing to find yet", setups.getCurrentSelectedSetup());

		setups.addSetup("hespori");
		Map<String, Object> data = new HashMap<>();
		data.put("setups", Arrays.asList("hespori"));
		data.put("version", 1);
		eventBus.post(new PluginMessage("inventory-setups", "setups-changed", data));

		handoff.onGameTick(new GameTick());

		assertEquals("hespori", setups.getCurrentSelectedSetup());
	}

	/** The gear stop's end is observable: the bank was open at the leg and then closed. */
	@Test
	public void theGearStopEndsWhenTheBankHasBeenAndGone()
	{
		setups.addSetup("hespori");
		when(planner.isGearPhase()).thenReturn(true);
		when(planner.isAtBankLeg()).thenReturn(true);

		handoff.onGameTick(new GameTick());
		assertTrue("nothing has happened yet", handoff.gearOutstanding());

		bankOpen(true);
		handoff.onGameTick(new GameTick());
		assertTrue("the bank being open is not the same as being done",
			handoff.gearOutstanding());

		bankOpen(false);
		handoff.onGameTick(new GameTick());
		assertFalse("open and closed again is the whole observable",
			handoff.gearOutstanding());
	}

	/** The phase ending puts the overview back — the setup selection was ours to undo. */
	@Test
	public void standingDownClearsOurOwnSelection()
	{
		setups.addSetup("hespori");
		when(planner.isGearPhase()).thenReturn(true);
		handoff.onGameTick(new GameTick());
		assertEquals("hespori", setups.getCurrentSelectedSetup());

		when(planner.isGearPhase()).thenReturn(false);
		handoff.onGameTick(new GameTick());

		assertNull("the overview is back for the swap-back bank visit",
			setups.getCurrentSelectedSetup());
	}

	/** But never a setup the player navigated to themselves. */
	@Test
	public void standingDownLeavesThePlayersOwnSelectionAlone()
	{
		setups.addSetup("hespori");
		setups.addSetup("Vorkath");
		when(planner.isGearPhase()).thenReturn(true);
		handoff.onGameTick(new GameTick());

		// The player clicks over to their own setup mid-run.
		setups.selectByHand("Vorkath");

		when(planner.isGearPhase()).thenReturn(false);
		handoff.onGameTick(new GameTick());

		assertEquals("a named clear only lands on our own selection", "Vorkath",
			setups.getCurrentSelectedSetup());
	}
}
