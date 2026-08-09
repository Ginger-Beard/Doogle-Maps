package com.dooglemaps.bank;

import com.dooglemaps.data.ItemNames;
import com.dooglemaps.guide.CarriedItems;
import com.dooglemaps.guide.TeleportSpell;
import com.dooglemaps.route.ShortestPathIntegration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;

/**
 * The item Shortest Path's current route actually uses, when that can be said.
 *
 * <h2>The router's recommendation, surfaced — never required</h2>
 *
 * Shortest Path picks the route with the player's own transport settings, so the first
 * item-shaped transport on its path is the best travel advice available anywhere in the
 * client. This resolves it to an item the player owns, so the bank filter can show it in the
 * first slot, the highlight can mark it in cyan, and the step panel can name it.
 *
 * <p>Deliberately <b>soft</b>. It never joins the loadout's needs and never holds the supply
 * leg: some transports are not items at all, some items cannot be resolved from a display
 * string, and some — diary items with daily charges, for one — have constraints no plugin can
 * see. When nothing resolves, nothing appears, and the route line on the map is still there.
 *
 * <h2>Matched from display strings, best-effort by design</h2>
 *
 * The transports come back as Shortest Path's own display text, not item ids — that is all
 * its API posts. Matching text against the names of items the player owns (the bank and the
 * pack; the only ids that could be shown anyway) is the same trick the teleport list uses,
 * with the charge suffix stripped so <i>"Games necklace"</i> in a route finds the
 * <i>"Games necklace(8)"</i> in the bank. It breaks if Shortest Path rewords a transport,
 * and the cost of that break is the feature quietly not appearing — which is the failure
 * mode a convenience is allowed to have.
 *
 * <h2>Spells resolve too</h2>
 *
 * A route's best hop is often not an item at all but a teleport spell, and those used to fall
 * through this entirely: "Camelot Teleport" matched nothing owned, so the route line named a
 * teleport while nothing on screen showed where to click. A hop that matches no owned item is
 * now tried against {@link TeleportSpell}'s table, in the same path order — the first hop that
 * resolves either way is the next thing the player will actually click, and the overlay
 * points at the spell (or the magic tab that reveals it) instead of an inventory slot.
 * An owned item wins over the spell reading of the same words, deliberately: a
 * "Camelot teleport" tablet in the pack is one click where the spell is two.
 */
@Singleton
public class RouteItem
{
	private final ShortestPathIntegration router;
	private final ItemNames itemNames;
	private final BankContents bank;
	private final CarriedItems carried;
	private final Client client;

	private int resolvedId = -1;
	private String resolvedName;
	private TeleportSpell resolvedSpell;
	private int resolvedTick = -1;

	@Inject
	RouteItem(ShortestPathIntegration router, ItemNames itemNames, BankContents bank,
		CarriedItems carried, Client client)
	{
		this.router = router;
		this.itemNames = itemNames;
		this.bank = bank;
		this.carried = carried;
		this.client = client;
	}

	/** The item id the route's first item-shaped hop resolves to, or -1 for none. */
	public synchronized int currentItemId()
	{
		resolve();
		return resolvedId;
	}

	/** The game's name for whatever resolved — item or spell — or null for none. */
	public synchronized String currentName()
	{
		resolve();
		return resolvedName;
	}

	/** The teleport spell the route's first spell-shaped hop names, or null for none. */
	@Nullable
	public synchronized TeleportSpell currentSpell()
	{
		resolve();
		return resolvedSpell;
	}

	/** Once a tick, like every other per-tick answer around the bank. */
	private void resolve()
	{
		int tick = client.getTickCount();
		if (tick == resolvedTick)
		{
			return;
		}
		resolvedTick = tick;
		resolvedId = -1;
		resolvedName = null;
		resolvedSpell = null;

		List<String> transports = router.getCurrentTransports();
		if (transports.isEmpty())
		{
			return;
		}

		Set<Integer> owned = new LinkedHashSet<>(bank.getItemIds());
		owned.addAll(carried.getItemIds());

		// Path order, so the first hop that is an item wins - it is the next thing the
		// player will actually click.
		for (String transport : transports)
		{
			// A carried item first, the spell second, a banked item last. The order is how
			// many clicks each costs from wherever the player is standing: an item on you is
			// one click, a castable spell is two, and a banked item is a detour. Trying the
			// item unconditionally before the spell meant "Teleport to House" with tablets
			// *in the bank* resolved to the tablets, the spell was never considered, and the
			// travel hint — rightly refusing to highlight something not carried — showed
			// nothing at all. Reported from play, on the way to Weiss.
			int match = match(transport, owned);
			if (match != -1 && carried.has(match))
			{
				resolvedId = match;
				resolvedName = itemNames.get(match, null);
				return;
			}

			TeleportSpell spell = TeleportSpell.match(transport);
			if (spell != null)
			{
				resolvedSpell = spell;
				resolvedName = spell.getSpellName();
				return;
			}

			// Banked only, and no spell reading: still worth resolving — the bank overlay
			// marks it in cyan and the route line names it, which is exactly the advice a
			// banked teleport deserves.
			if (match != -1)
			{
				resolvedId = match;
				resolvedName = itemNames.get(match, null);
				return;
			}
		}
	}

	private int match(String transport, Set<Integer> owned)
	{
		String want = normalise(transport);
		if (want.length() < 4)
		{
			// Too short to be an item name; "BIQ" and friends are ring codes, not things.
			return -1;
		}

		// An exact name first; a prefix either way second, because both sides carry
		// suffixes the other does not - the route says "Games necklace (Barbarian Outpost)"
		// and the bank says "Games necklace(8)".
		int prefix = -1;
		for (int itemId : owned)
		{
			String name = itemNames.get(itemId, null);
			if (name == null)
			{
				continue;
			}
			String have = normalise(name);
			if (have.equals(want))
			{
				return itemId;
			}
			if (prefix == -1 && have.length() >= 4
				&& (have.startsWith(want) || want.startsWith(have)))
			{
				prefix = itemId;
			}
		}
		return prefix;
	}

	/** Lowercased, with any parenthetical - charges, destinations - cut off. */
	private static String normalise(String name)
	{
		String s = name.toLowerCase();
		int parenthesis = s.indexOf('(');
		if (parenthesis >= 0)
		{
			s = s.substring(0, parenthesis);
		}
		return s.trim();
	}
}
