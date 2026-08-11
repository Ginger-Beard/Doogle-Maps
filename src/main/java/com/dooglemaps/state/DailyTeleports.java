package com.dooglemaps.state;

import com.dooglemaps.DoogleMapsConfig;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;

/**
 * Which of the diary teleports that only work a few times a day have been used up today.
 *
 * <h2>The one thing no plugin in the client can see</h2>
 *
 * The Ardougne cloak teleports to the Ardougne farm patch three times a day on the medium
 * cloak and five on the hard one; the elite cloak and the max cape are unlimited, as is the
 * Monastery teleport on every tier. Shortest Path knows all of that and <b>tries</b> to
 * withhold the hop once the day's teleports are gone — its transport data gates the medium
 * and hard rows on {@code 6069<3} and {@code 6069<5}.
 *
 * <p>That gate can never fire. Varbit 6069 is {@code ARDOUGNE_CLOAK_LOWBITS} — bit 18 of
 * varplayer 635, one single bit, so its value is 0 or 1 and it is always under three. The
 * rest of the counter is not in that varp at all (bits 19 upward are the Ardougne ring
 * respawn point, the free essence, the Yanille teleport and Bert's daily hint), and the
 * server does not send it: the transport data says so itself, <i>"varbit 6069 is only
 * lowbits, highbits is not transmitted"</i>. The count genuinely is not readable. So the
 * router keeps offering a teleport that is spent, and the guide keeps telling you to click
 * a cloak that will not teleport you. Reported from play.
 *
 * <p>(The explorer's ring is in the same family and needs nothing here: its counter, varbit
 * 4552, is bits 25-26 of the same varp — a real 0-3 number that does arrive — so Shortest
 * Path's own gate on the ring works, and rings 3 and 4 are unlimited anyway.)
 *
 * <h2>So the game is asked instead of the client</h2>
 *
 * The one thing that <i>is</i> unambiguous is what the game says when you try it:
 * <i>"You have already used all of your available teleports for today. Try again tomorrow
 * when the cape has recharged."</i> That line is proof, not inference, and it is what
 * {@code TeleportChargeCapture} latches on.
 *
 * <p>The honest cost of that design, stated plainly: this cannot know until you have tried.
 * The first cloak teleport of the day that fails is still offered, still clicked, and still
 * wasted — and every one after it, for the rest of the day, is not. Counting successful
 * teleports instead would predict it, and would also be wrong in the one direction that
 * matters: a count that drifts high (teleports taken on another client, or with the plugin
 * off) withholds a teleport the player really does have, with no way to say otherwise until
 * the day rolls over. A signal that cannot produce a false positive is worth one wasted
 * click a day.
 *
 * <h2>What "today" means</h2>
 *
 * The game's daily reset, which is midnight UTC — so the latch is stored with the UTC date
 * it was seen on and simply stops matching when that date rolls over. Nothing has to notice
 * the reset happening, which matters for a client that may well be logged in through it.
 *
 * <p>Stored per RuneScape profile, like every other observation: two accounts have their own
 * cloaks and their own days.
 */
@Slf4j
@Singleton
public class DailyTeleports
{
	/**
	 * A teleport with a daily allowance that nothing in the client can count.
	 *
	 * <p>Deliberately only the ones that are <b>both</b> limited and invisible. Anything
	 * Shortest Path can already gate on a varbit belongs to Shortest Path, and listing it
	 * here as well would be two plugins guessing at one fact.
	 */
	public enum Teleport
	{
		/**
		 * The Ardougne cloak's teleport to the farm patch — three a day on the cloak 2, five
		 * on the cloak 3.
		 *
		 * <p>Only those two tiers: the cloak 4 and the max cape have it unlimited, so a
		 * player holding one can never see the message this is latched on, and the item list
		 * says as much rather than relying on that.
		 *
		 * <p>The words are matched against Shortest Path's own display string for the hop,
		 * <i>"Ardougne cloak: Ardougne Farm"</i>. Its Monastery hop shares the first word and
		 * not the second, which is the point of matching on both: the Monastery teleport is
		 * unlimited and must go on being offered.
		 */
		ARDOUGNE_CLOAK_FARM(
			"ardougneCloakFarm",
			"Ardougne cloak: Ardougne Farm",
			new int[]{ItemID.ARDY_CAPE_MEDIUM, ItemID.ARDY_CAPE_HARD},
			new String[]{"ardougne cloak", "farm"});

		private final String key;
		private final String displayName;
		private final int[] itemIds;
		private final String[] words;

		Teleport(String key, String displayName, int[] itemIds, String[] words)
		{
			this.key = key;
			this.displayName = displayName;
			this.itemIds = itemIds;
			this.words = words;
		}

		public String getDisplayName()
		{
			return displayName;
		}

		public int[] getItemIds()
		{
			return itemIds.clone();
		}

		/** Whether this is one of the items the teleport comes off. */
		public boolean isItem(int itemId)
		{
			for (int id : itemIds)
			{
				if (id == itemId)
				{
					return true;
				}
			}
			return false;
		}

		/**
		 * Whether some text — a route hop, a menu target — names this teleport.
		 *
		 * <p>Every word has to appear, which is what keeps the unlimited Monastery hop out of
		 * it. A menu target carries only the item's name and never a destination, so it is
		 * read with {@link #namedByItemName} instead.
		 */
		public boolean matches(@Nullable String text)
		{
			if (text == null)
			{
				return false;
			}
			String haystack = text.toLowerCase(Locale.ROOT);
			for (String word : words)
			{
				if (!haystack.contains(word))
				{
					return false;
				}
			}
			return true;
		}

		/**
		 * Whether some text names the <i>item</i> this teleport comes off — the first word
		 * only, since a menu target reads "Ardougne cloak 3" and says nothing about where it
		 * is being pointed.
		 */
		public boolean namedByItemName(@Nullable String text)
		{
			return text != null && text.toLowerCase(Locale.ROOT).contains(words[0]);
		}
	}

	/** One key per teleport, so a new one can be added without migrating anything. */
	private static final String KEY_PREFIX = "dailyTeleportSpent.";

	private final ConfigManager configManager;

	@Inject
	DailyTeleports(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	/** Whether this teleport has been proved spent for the current game day. */
	public boolean isSpent(Teleport teleport)
	{
		String stored = configManager.getRSProfileConfiguration(
			DoogleMapsConfig.GROUP, KEY_PREFIX + teleport.key, String.class);
		return stored != null && stored.equals(today());
	}

	/** Whether any teleport off this item is spent — for the travel hint's item table. */
	public boolean isItemSpent(int itemId)
	{
		for (Teleport teleport : Teleport.values())
		{
			if (teleport.isItem(itemId) && isSpent(teleport))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a route hop, in Shortest Path's own wording, is one that is spent today.
	 *
	 * <p>Matched on words rather than resolved to an item because that is all the hop is: the
	 * router posts display text, and this is the same best-effort reading {@code RouteItem}
	 * already does with it. A rewording upstream makes this quietly stop firing, which is the
	 * failure the whole feature is allowed to have — it is back to today's behaviour, not
	 * something worse.
	 */
	public boolean isHopSpent(@Nullable String hop)
	{
		for (Teleport teleport : Teleport.values())
		{
			if (teleport.matches(hop) && isSpent(teleport))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Records the game having said there are none left, having just watched it say so.
	 *
	 * <p>Idempotent within a day, and logged once so the reason a cloak stopped being offered
	 * is in the log rather than being a mystery.
	 */
	public void observeExhausted(Teleport teleport)
	{
		if (isSpent(teleport))
		{
			return;
		}

		configManager.setRSProfileConfiguration(
			DoogleMapsConfig.GROUP, KEY_PREFIX + teleport.key, today());
		log.info("{} is out of teleports for today - it will not be offered again until the "
			+ "daily reset.", teleport.getDisplayName());
	}

	/** Forgets every latch, for a profile reset. Rebuilt by the game saying so again. */
	public void clear()
	{
		for (Teleport teleport : Teleport.values())
		{
			configManager.unsetRSProfileConfiguration(
				DoogleMapsConfig.GROUP, KEY_PREFIX + teleport.key);
		}
	}

	/** The game's day, which turns over at midnight UTC wherever the player is sitting. */
	private static String today()
	{
		return LocalDate.now(ZoneOffset.UTC).toString();
	}
}
