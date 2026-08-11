package com.dooglemaps.capture;

import com.dooglemaps.guide.CarriedItems;
import com.dooglemaps.state.DailyTeleports;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Catching the game's refusal, and crediting it to the right item.
 *
 * <p>The whole feature turns on one line of chat — nothing else in the client can see a diary
 * teleport's daily allowance, see {@code DailyTeleports} — so what matters here is that the
 * line is recognised, and that it is never credited to something the player was not using.
 */
public class TeleportChargeCaptureTest
{
	private static final String REFUSED = "You have already used all of your available "
		+ "teleports for today. Try again tomorrow when the cape has recharged.";

	private Client client;
	private CarriedItems carried;
	private DailyTeleports dailyTeleports;
	private TeleportChargeCapture capture;

	@Before
	public void setUp()
	{
		client = Mockito.mock(Client.class);
		carried = Mockito.mock(CarriedItems.class);
		dailyTeleports = Mockito.mock(DailyTeleports.class);
		capture = construct(TeleportChargeCapture.class, client, carried, dailyTeleports);
	}

	@Test
	public void theRefusalAfterClickingTheCloakSpendsIt()
	{
		click("<col=ff9040>Ardougne cloak 3</col>");
		say(REFUSED);

		verify(dailyTeleports).observeExhausted(DailyTeleports.Teleport.ARDOUGNE_CLOAK_FARM);
	}

	/**
	 * With no click to credit it to — the click was long ago, or the target did not read the
	 * way this expects — what is worn answers instead.
	 */
	@Test
	public void aWornCloakAnswersForAnUncreditedRefusal()
	{
		when(carried.hasAny(ItemID.ARDY_CAPE_MEDIUM, ItemID.ARDY_CAPE_HARD)).thenReturn(true);
		say(REFUSED);

		verify(dailyTeleports).observeExhausted(DailyTeleports.Teleport.ARDOUGNE_CLOAK_FARM);
	}

	/** ...and with neither, nothing is guessed at. */
	@Test
	public void aRefusalFromSomethingUnknownSpendsNothing()
	{
		say(REFUSED);

		verify(dailyTeleports, never()).observeExhausted(Mockito.any());
	}

	/** Clicking the cloak is not itself the signal: the teleport that worked spends nothing. */
	@Test
	public void clickingTheCloakAloneSpendsNothing()
	{
		click("<col=ff9040>Ardougne cloak 3</col>");

		verify(dailyTeleports, never()).observeExhausted(Mockito.any());
	}

	@Test
	public void anUnrelatedGameMessageSpendsNothing()
	{
		when(carried.hasAny(ItemID.ARDY_CAPE_MEDIUM, ItemID.ARDY_CAPE_HARD)).thenReturn(true);
		say("You have used all of your Fossil Island teleports for today.");

		verify(dailyTeleports, never()).observeExhausted(Mockito.any());
	}

	private void click(String target)
	{
		MenuEntry entry = Mockito.mock(MenuEntry.class);
		when(entry.getTarget()).thenReturn(target);
		when(entry.getOption()).thenReturn("Farm");
		capture.onMenuOptionClicked(new MenuOptionClicked(entry));
	}

	private void say(String message)
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage(message);
		capture.onChatMessage(event);
	}
}
