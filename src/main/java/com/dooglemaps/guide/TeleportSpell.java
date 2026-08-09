package com.dooglemaps.guide;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import net.runelite.api.gameval.InterfaceID;

/**
 * The teleport spells a route can travel by, and where each one lives in the spellbook.
 *
 * <p>Exists so the guide can point at a spell the way it points at an item. Shortest Path
 * routes with the player's own transport settings, and when the best hop is a spell its
 * display text names one — "Camelot Teleport" — which is not an item, resolved to nothing,
 * and so the route line named a teleport while nothing on screen showed where to click.
 * Reported from play as exactly that: "Route: Camelot teleport" and no highlight.
 *
 * <p>One widget per spell, and all of them children of the one spellbook interface: the game
 * hides the books that are not current, so a spell's component is visible exactly when the
 * player could actually click it. That gives the overlay a two-step instruction for free —
 * outline the spell when it is on screen, and outline the magic tab stone when it is not,
 * which is the click that puts it on screen.
 *
 * <p>Matched from display strings, best-effort by design, exactly like {@code RouteItem} and
 * for the same reason: the transports arrive as Shortest Path's own wording, and the cost of
 * it rewording one is the highlight quietly not appearing — the failure a convenience is
 * allowed to have. The group variants ("Tele Group Catherby") are deliberately absent: a
 * route is travel for one.
 */
public enum TeleportSpell
{
	// The standard book.
	VARROCK("Varrock Teleport", InterfaceID.MagicSpellbook.VARROCK_TELEPORT),
	LUMBRIDGE("Lumbridge Teleport", InterfaceID.MagicSpellbook.LUMBRIDGE_TELEPORT),
	FALADOR("Falador Teleport", InterfaceID.MagicSpellbook.FALADOR_TELEPORT),
	TELEPORT_TO_HOUSE("Teleport to House", InterfaceID.MagicSpellbook.TELEPORT_TO_YOUR_HOUSE),
	CAMELOT("Camelot Teleport", InterfaceID.MagicSpellbook.CAMELOT_TELEPORT),
	// The wiki's full name, with the shorter form people actually say as an alias.
	KOUREND("Kourend Castle Teleport", InterfaceID.MagicSpellbook.KOUREND_TELEPORT,
		"Kourend Teleport"),
	ARDOUGNE("Ardougne Teleport", InterfaceID.MagicSpellbook.ARDOUGNE_TELEPORT),
	FORTIS("Civitas illa Fortis Teleport", InterfaceID.MagicSpellbook.FORTIS_TELEPORT,
		"Fortis Teleport"),
	WATCHTOWER("Watchtower Teleport", InterfaceID.MagicSpellbook.WATCHTOWER_TELEPORT),
	TROLLHEIM("Trollheim Teleport", InterfaceID.MagicSpellbook.TROLLHEIM_TELEPORT),
	APE_ATOLL("Ape Atoll Teleport", InterfaceID.MagicSpellbook.APE_TELEPORT),

	// The ancient book, in level order — which is what pins ZAROSTELEPORT1..8 to their names.
	PADDEWWA("Paddewwa Teleport", InterfaceID.MagicSpellbook.ZAROSTELEPORT1),
	SENNTISTEN("Senntisten Teleport", InterfaceID.MagicSpellbook.ZAROSTELEPORT2),
	KHARYRLL("Kharyrll Teleport", InterfaceID.MagicSpellbook.ZAROSTELEPORT3),
	LASSAR("Lassar Teleport", InterfaceID.MagicSpellbook.ZAROSTELEPORT4),
	DAREEYAK("Dareeyak Teleport", InterfaceID.MagicSpellbook.ZAROSTELEPORT5),
	CARRALLANGER("Carrallanger Teleport", InterfaceID.MagicSpellbook.ZAROSTELEPORT6),
	ANNAKARL("Annakarl Teleport", InterfaceID.MagicSpellbook.ZAROSTELEPORT7),
	GHORROCK("Ghorrock Teleport", InterfaceID.MagicSpellbook.ZAROSTELEPORT8),

	// The lunar book.
	MOONCLAN("Moonclan Teleport", InterfaceID.MagicSpellbook.TELE_MOONCLAN),
	OURANIA("Ourania Teleport", InterfaceID.MagicSpellbook.OURANIA_TELEPORT),
	WATERBIRTH("Waterbirth Teleport", InterfaceID.MagicSpellbook.TELE_WATERBIRTH),
	BARBARIAN("Barbarian Teleport", InterfaceID.MagicSpellbook.TELE_BARB_OUT),
	KHAZARD("Khazard Teleport", InterfaceID.MagicSpellbook.TELE_KHAZARD),
	FISHING_GUILD("Fishing Guild Teleport", InterfaceID.MagicSpellbook.TELE_FISH),
	CATHERBY("Catherby Teleport", InterfaceID.MagicSpellbook.TELE_CATHER),
	ICE_PLATEAU("Ice Plateau Teleport", InterfaceID.MagicSpellbook.TELE_GHORROCK),

	// The Arceuus book, which is where the farming-adjacent oddities live — Harmony's herb
	// and allotment patches, and the Morytania runs by way of the graveyards.
	ARCEUUS_LIBRARY("Arceuus Library Teleport", InterfaceID.MagicSpellbook.TELEPORT_ARCEUUS_LIBRARY),
	DRAYNOR_MANOR("Draynor Manor Teleport", InterfaceID.MagicSpellbook.TELEPORT_DRAYNOR_MANOR),
	BATTLEFRONT("Battlefront Teleport", InterfaceID.MagicSpellbook.TELEPORT_BATTLEFRONT),
	MIND_ALTAR("Mind Altar Teleport", InterfaceID.MagicSpellbook.TELEPORT_MIND_ALTAR),
	SALVE_GRAVEYARD("Salve Graveyard Teleport", InterfaceID.MagicSpellbook.TELEPORT_SALVE_GRAVEYARD),
	FENKENSTRAIN("Fenkenstrain's Castle Teleport",
		InterfaceID.MagicSpellbook.TELEPORT_FENKENSTRAIN_CASTLE),
	WEST_ARDOUGNE("West Ardougne Teleport", InterfaceID.MagicSpellbook.TELEPORT_WEST_ARDOUGNE),
	HARMONY_ISLAND("Harmony Island Teleport", InterfaceID.MagicSpellbook.TELEPORT_HARMONY_ISLAND),
	CEMETERY("Cemetery Teleport", InterfaceID.MagicSpellbook.TELEPORT_CEMETERY),
	BARROWS("Barrows Teleport", InterfaceID.MagicSpellbook.TELEPORT_BARROWS),
	APE_ATOLL_DUNGEON("Ape Atoll Dungeon Teleport",
		InterfaceID.MagicSpellbook.TELEPORT_APE_ATOLL_DUNGEON);

	private final String spellName;
	private final int component;
	private final List<String> normalised = new ArrayList<>();

	TeleportSpell(String spellName, int component, String... aliases)
	{
		this.spellName = spellName;
		this.component = component;
		normalised.add(normalise(spellName));
		for (String alias : aliases)
		{
			normalised.add(normalise(alias));
		}
	}

	/** What the spell is called, for the panel line and the route line. */
	public String getSpellName()
	{
		return spellName;
	}

	/** The spell's own widget in the spellbook interface, for the overlay to outline. */
	public int getComponent()
	{
		return component;
	}

	/**
	 * The spell a transport's display text names, or null when it names none.
	 *
	 * <p>Exact matches first, over the whole table, so "West Ardougne Teleport" can never be
	 * taken by a prefix rule as plain Ardougne. Prefixes second, either way round, because
	 * both sides carry things the other does not — the route may say
	 * "Camelot Teleport (Magic 45)" and the parenthetical is already cut, but "Camelot" alone
	 * should still land.
	 */
	@Nullable
	public static TeleportSpell match(String transport)
	{
		String want = normalise(transport);
		if (want.length() < 4)
		{
			// Too short to be a spell name; ring codes and the like.
			return null;
		}

		for (TeleportSpell spell : values())
		{
			if (spell.normalised.contains(want))
			{
				return spell;
			}
		}

		for (TeleportSpell spell : values())
		{
			for (String name : spell.normalised)
			{
				if (name.startsWith(want) || want.startsWith(name))
				{
					return spell;
				}
			}
		}
		return null;
	}

	/**
	 * Lowercased, parentheticals and "via ..." tails cut, and "teleport to x" turned round
	 * into "x teleport" — the game names the same spell both ways, and one canonical shape
	 * beats two spellings of every entry.
	 */
	private static String normalise(String name)
	{
		String s = name.toLowerCase(Locale.ROOT);
		int parenthesis = s.indexOf('(');
		if (parenthesis >= 0)
		{
			s = s.substring(0, parenthesis);
		}
		// "Teleport to House via Weiss Portal" — the router's wording for a spell that is
		// only the first half of the hop. The tail names furniture, not the spell, and left
		// in place it matched nothing at all. Reported from play, on the way to Weiss.
		int via = s.indexOf(" via ");
		if (via >= 0)
		{
			s = s.substring(0, via);
		}
		s = s.trim();
		if (s.startsWith("teleport to "))
		{
			s = s.substring("teleport to ".length()) + " teleport";
		}
		return s;
	}
}
