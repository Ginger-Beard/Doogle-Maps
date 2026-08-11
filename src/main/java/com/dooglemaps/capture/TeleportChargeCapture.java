package com.dooglemaps.capture;

import com.dooglemaps.guide.CarriedItems;
import com.dooglemaps.state.DailyTeleports;
import java.util.Locale;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Watches for the game refusing a diary teleport because the day's are gone.
 *
 * <p>The one moment the daily allowance becomes visible to the client at all — see
 * {@link DailyTeleports} for why nothing else in the client can see it, varbit and all. The
 * message is proof; everything here is about pinning it to the right item.
 *
 * <h2>Attributing the message</h2>
 *
 * The line names no item, so it is credited to whatever the player was pointing at:
 *
 * <ul>
 *   <li><b>The click</b>, when the menu target names one of the items — which it does for an
 *       inventory op and for an equipment-tab op alike, since the target is the item's own
 *       name. Nothing else can produce that pairing, so this cannot misfire.</li>
 *   <li><b>What is worn</b>, otherwise. A fallback rather than the rule: it is the same
 *       message from an item this table does not list, arriving while the player happens to
 *       be wearing one it does, that would be credited wrongly. The message says "the cape",
 *       and the only limited-teleport cape in the table is the Ardougne cloak, so that is a
 *       narrow risk — and it costs a teleport withheld until the daily reset, not anything
 *       permanent.</li>
 * </ul>
 *
 * <p>Both routes still require the message itself. Nothing here counts teleports or infers
 * one from a click that worked, deliberately: a wrong count withholds a teleport the player
 * really has, and there would be no way for them to say otherwise.
 */
@Slf4j
@Singleton
public class TeleportChargeCapture
{
	/**
	 * What the game says when they are gone.
	 *
	 * <p>The full line is <i>"You have already used all of your available teleports for
	 * today. Try again tomorrow when the cape has recharged."</i> Matched on the first
	 * sentence, lowercased: the tail names the item's kind and would have to be one pattern
	 * per family, while the head is the part that means the thing this cares about.
	 */
	private static final String EXHAUSTED =
		"you have already used all of your available teleports for today";

	/**
	 * How long a click stays creditable. Generous on purpose — the message follows the click
	 * within a tick or two, and being late here only falls through to the worn-item reading.
	 */
	private static final int CLICK_WINDOW_TICKS = 5;

	private final Client client;
	private final CarriedItems carried;
	private final DailyTeleports dailyTeleports;

	@Nullable
	private DailyTeleports.Teleport clicked;
	private int clickedTick = -1;

	@Inject
	TeleportChargeCapture(Client client, CarriedItems carried, DailyTeleports dailyTeleports)
	{
		this.client = client;
		this.carried = carried;
		this.dailyTeleports = dailyTeleports;
	}

	/** Forgotten on logout, like every other in-flight observation. */
	public void reset()
	{
		clicked = null;
		clickedTick = -1;
	}

	/**
	 * Remembers a click on something with a daily allowance.
	 *
	 * <p>Matched on the menu target rather than on {@code getItemId}, which is not populated
	 * for every way of using a worn cape, and not on the menu option either — the option is
	 * the destination's name and would need a table of its own, while the refusal message can
	 * only ever have come from the limited teleport in the first place.
	 */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		String target = Text.removeTags(event.getMenuTarget()).toLowerCase(Locale.ROOT);
		for (DailyTeleports.Teleport teleport : DailyTeleports.Teleport.values())
		{
			if (teleport.namedByItemName(target))
			{
				clicked = teleport;
				clickedTick = client.getTickCount();
				return;
			}
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		// Both types: an item's refusal is a game message, and a filtered chat setting can put
		// the same line through as spam.
		if (event.getType() != ChatMessageType.GAMEMESSAGE
			&& event.getType() != ChatMessageType.SPAM)
		{
			return;
		}

		String message = Text.removeTags(event.getMessage()).toLowerCase(Locale.ROOT);
		if (!message.contains(EXHAUSTED))
		{
			return;
		}

		DailyTeleports.Teleport spent = credit();
		if (spent == null)
		{
			// Worth a line rather than a silent drop: it means the game refused a teleport
			// this plugin could not name, which is either a new item worth listing or a menu
			// target that does not read the way this assumes.
			log.debug("A teleport was refused for the day, but nothing clicked or carried "
				+ "names one this build knows about.");
			return;
		}

		dailyTeleports.observeExhausted(spent);
	}

	/** Which teleport the refusal belongs to; see the class note on attribution. */
	@Nullable
	private DailyTeleports.Teleport credit()
	{
		if (clicked != null && client.getTickCount() - clickedTick <= CLICK_WINDOW_TICKS)
		{
			return clicked;
		}

		for (DailyTeleports.Teleport teleport : DailyTeleports.Teleport.values())
		{
			if (carried.hasAny(teleport.getItemIds()))
			{
				return teleport;
			}
		}
		return null;
	}
}
