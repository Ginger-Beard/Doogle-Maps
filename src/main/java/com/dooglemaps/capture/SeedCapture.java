package com.dooglemaps.capture;

import com.dooglemaps.data.Seed;
import com.dooglemaps.state.FarmingBonusStore;
import com.dooglemaps.state.SeedBoxAction;
import com.dooglemaps.state.SeedInventoryStore;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.ItemComposition;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Reads seed storage whenever the game hands it to us.
 *
 * <p>The client fires a container-changed event when a bank, vault or seed box is opened
 * as well as when its contents change, so simply visiting one refreshes the cache — no
 * polling and nothing for the player to remember to do.
 */
@Slf4j
@Singleton
public class SeedCapture
{
	/** "You put the stolen Ranarr seed into your seed box." */
	private static final Pattern SEED_BOX_SINGLE = Pattern.compile(
		"You put the stolen (?<item>.+?) into your seed box\\.");

	/** "The following stolen loot gets added to your seed box: Ranarr seed x 3." */
	private static final Pattern SEED_BOX_BULK = Pattern.compile(
		"The following stolen loot gets added to your seed box: (?<item>.+?) x (?<quantity>\\d+)\\.");

	private final SeedInventoryStore seeds;
	private final FarmingBonusStore bonuses;
	private final ItemManager itemManager;

	@Inject
	SeedCapture(SeedInventoryStore seeds, FarmingBonusStore bonuses, ItemManager itemManager)
	{
		this.seeds = seeds;
		this.bonuses = bonuses;
		this.itemManager = itemManager;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		seeds.record(event.getContainerId(), event.getItemContainer());
		// The same events carry the secateurs and cape that decide what a harvest is worth.
		bonuses.record(event.getContainerId(), event.getItemContainer());
	}

	/**
	 * Watches for the seed box being filled or emptied.
	 *
	 * <p>Needed because the client's copy of the seed box container lags a step behind these
	 * two actions, so reading it afterwards reports the state from before. The click says
	 * what happened; {@link SeedInventoryStore} works out the rest from the inventory.
	 */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!isSeedBox(event.getItemId()))
		{
			return;
		}

		if ("Fill".equals(event.getMenuOption()))
		{
			seeds.noteSeedBoxAction(SeedBoxAction.FILL);
		}
		else if ("Empty".equals(event.getMenuOption()))
		{
			seeds.noteSeedBoxAction(SeedBoxAction.EMPTY);
		}
	}

	/** Every form of the seed box: the plain one, the open one, and the Farming Guild one. */
	private static boolean isSeedBox(int itemId)
	{
		return itemId == ItemID.SEED_BOX
			|| itemId == ItemID.SEEDBOX
			|| itemId == ItemID.SEED_BOX_OPEN;
	}

	/**
	 * Credits seeds the game says went straight into the seed box.
	 *
	 * <p>Pickpocketing a Master Farmer with a seed box drops the seeds directly into it. They
	 * never touch the inventory, so nothing can be derived from an inventory delta, and the
	 * box is not open to report itself. The chat message is the only evidence, which is why
	 * core's own loot tracker reads these same two lines.
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}

		Matcher single = SEED_BOX_SINGLE.matcher(event.getMessage());
		if (single.matches())
		{
			creditToSeedBox(single.group("item"), 1);
			return;
		}

		Matcher bulk = SEED_BOX_BULK.matcher(event.getMessage());
		if (bulk.matches())
		{
			try
			{
				creditToSeedBox(bulk.group("item"), Integer.parseInt(bulk.group("quantity")));
			}
			catch (NumberFormatException ignored)
			{
				// Not a number we can use; better to miss the seeds than to invent some.
			}
		}
	}

	/**
	 * Resolves an item name from the chat box to a seed.
	 *
	 * <p>Matched by name because that is all the message carries. Safe on the client thread,
	 * which is where chat events arrive.
	 */
	private void creditToSeedBox(String itemName, int quantity)
	{
		for (Seed seed : Seed.values())
		{
			ItemComposition composition = itemManager.getItemComposition(seed.getItemID());
			if (composition != null && itemName.equalsIgnoreCase(composition.getName()))
			{
				seeds.addToSeedBox(seed.getItemID(), quantity);
				return;
			}
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() == Skill.FARMING)
		{
			seeds.recordFarmingLevel();
		}
	}
}
