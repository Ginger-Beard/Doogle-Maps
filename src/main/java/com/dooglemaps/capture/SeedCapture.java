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

	/**
	 * The game's own word for seeds going into and out of the box.
	 *
	 * <h2>Why these are worth reading when a derivation already exists</h2>
	 *
	 * Because they are exact and the derivation is a guess. {@code SeedInventoryStore} works the
	 * box out by watching what left the inventory in the two ticks after a Fill, which cannot
	 * tell a Fill from a bank deposit or a planting in the same window — where <i>"Stored 6 x
	 * Ranarr seed in your seed box."</i> names the seed and the count outright.
	 *
	 * <p>The plugin has been watching one of these lines go past for months without acting on
	 * it: {@code HarvestLog} logs "Stored 1 x Avantoe seed in your seed box." with the note
	 * <i>"if items are going somewhere the inventory cannot see, this is the wording to
	 * match"</i>. It was the wording to match.
	 *
	 * <p>Found by reading how Dude Where's My Stuff tracks the box, which reads these same lines
	 * alongside the interface itself; the wordings are the game's own. Credited in
	 * {@code ATTRIBUTION.md}.
	 */
	private static final Pattern[] SEED_BOX_ADDED = {
		// A Fill, and a seed used on a closed box.
		Pattern.compile("Stored (?<quantity>\\d+) x (?<item>.+?) in your seed box\\."),
		// A seed clicked while the box's own interface is open.
		Pattern.compile(
			"You put (?<quantity>\\d+) x (?<item>.+?) straight into your open seed box\\."),
	};

	/** The other direction: an Empty, which names what came back. */
	private static final Pattern[] SEED_BOX_REMOVED = {
		Pattern.compile("Emptied (?<quantity>\\d+) x (?<item>.+?) to your inventory\\."),
	};

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

	/** The once-a-tick inventory reconcile; see {@code SeedInventoryStore.relearnInventoryFromClient}. */
	@Subscribe
	public void onGameTick(net.runelite.api.events.GameTick event)
	{
		// First, and before the relearn below reads anything. A fill states itself once per seed
		// kind in the chat box, so six kinds arrive as six messages inside one tick; holding the
		// write until here is what stops that being six config writes and six sidebar rebuilds.
		// See SeedInventoryStore.flushSeedBoxWrites, which explains why this is coalesced to the
		// tick rather than batched round a block the way the patch scan is.
		seeds.flushSeedBoxWrites();
		seeds.relearnInventoryFromClient();
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
		// The bank's empty-containers button, which tips the box straight into the bank with
		// no click on the box itself — the one emptying route the pending-action model never
		// saw. "All of your seeds and saplings were deposited." landed in the bank's counts
		// with no debit against the box, and a day later the run double-counted two snape
		// grass seeds into an allotment it could not plant. Reported from play. The click
		// arms the same EMPTY the box's own option arms; the container deltas do the rest,
		// including the partial and no-op cases, exactly as they do for an ordinary Empty.
		if (event.getParam1() == net.runelite.api.gameval.InterfaceID.Bankmain.DEPOSITCONTAINERS)
		{
			seeds.noteSeedBoxAction(SeedBoxAction.EMPTY);
			return;
		}

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

	/**
	 * Both forms of the seed box: closed and open.
	 *
	 * <p>NOT {@code ItemID.SEEDBOX}. The gameval names are the cache's internal ones, and
	 * 22993 "SEEDBOX" is the <b>Seed pack</b> — the farming contract reward — not a box form.
	 * It sat in this list as "the Farming Guild one" and made every seed-box treatment apply
	 * to seed packs; reported from play as packs lighting up with the box highlight.
	 */
	private static boolean isSeedBox(int itemId)
	{
		return itemId == ItemID.SEED_BOX
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
			return;
		}

		if (applyStated(event.getMessage(), SEED_BOX_ADDED, true))
		{
			return;
		}
		applyStated(event.getMessage(), SEED_BOX_REMOVED, false);
	}

	/**
	 * Applies the first of these patterns the message matches.
	 *
	 * @param adding true for seeds going in, false for seeds coming out
	 * @return whether one matched, so the caller can stop looking
	 */
	private boolean applyStated(String message, Pattern[] patterns, boolean adding)
	{
		for (Pattern pattern : patterns)
		{
			Matcher matcher = pattern.matcher(message);
			if (!matcher.matches())
			{
				continue;
			}

			int quantity;
			try
			{
				quantity = Integer.parseInt(matcher.group("quantity"));
			}
			catch (NumberFormatException ignored)
			{
				// Better to miss the seeds than to invent some - same call as the bulk line.
				return true;
			}

			// Matched, and that is worth reporting even when the name resolves to nothing: a
			// wording that fits but names a seed we cannot place is the one shape of failure
			// this cannot notice by itself.
			if (!move(matcher.group("item"), quantity, adding))
			{
				log.info("The game said \"{}\" and \"{}\" is not a seed this build knows - the "
					+ "box model will drift by {} until it is next opened.",
					message, matcher.group("item"), quantity);
			}
			return true;
		}
		return false;
	}

	/** Resolves the named seed and moves it, answering whether the name was placed at all. */
	private boolean move(String itemName, int quantity, boolean adding)
	{
		for (Seed seed : Seed.values())
		{
			ItemComposition composition = itemManager.getItemComposition(seed.getItemID());
			if (composition != null && itemName.equalsIgnoreCase(composition.getName()))
			{
				if (adding)
				{
					seeds.addToSeedBox(seed.getItemID(), quantity);
				}
				else
				{
					seeds.removeFromSeedBox(seed.getItemID(), quantity);
				}
				return true;
			}
		}
		return false;
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
