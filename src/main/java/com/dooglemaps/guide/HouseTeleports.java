package com.dooglemaps.guide;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.InterfaceID;

/**
 * What a player-owned house can teleport you to, and how to recognise it on screen.
 *
 * <p>Shared because three different things ask overlapping questions about it: the world overlay
 * decides which piece of furniture to outline, the widget overlay finds the row to mark inside the
 * menu, and both need the same loose place-name match. Keeping one copy is what stops them
 * disagreeing about whether the Farming Guild is reachable.
 */
public final class HouseTeleports
{
	private HouseTeleports()
	{
	}

	/**
	 * Lists that might hold a teleport destination, whichever menu is open.
	 *
	 * <p>Scanned as a set rather than picked by interface, because the same question — "which row
	 * says where I am going" — is asked of the portal nexus's own list and of the lettered menu a
	 * jewellery box opens, and those are different interfaces with the same shape. Only rows whose
	 * text literally names the destination are ever marked, so scanning somewhere irrelevant costs
	 * nothing.
	 */
	static final int[] DESTINATION_LISTS = {
		// The whole nexus teleport interface, not its row containers. The rows sit several levels
		// down inside it and a one-level walk found nothing — which is why the nexus list was not
		// highlighting at all while the lettered menu was.
		InterfaceID.TelenexusTeleport.UNIVERSE,
		InterfaceID.TelenexusTeleport.ROWS1,
		InterfaceID.TelenexusTeleport.ROWS2,
		// The lettered option menu, which is what a jewellery box opens: "J: Farming Guild".
		InterfaceID.Menu.LJ_LAYER1,
		InterfaceID.Menu.LJ_LAYER2,
		InterfaceID.Chatmenu.OPTIONS,
	};

	/**
	 * Which jewellery-box category reaches which farming stop.
	 *
	 * <p>Short and deliberately so, on the same rule as {@link com.dooglemaps.bank.TeleportItems}:
	 * a missing entry costs a highlight, a wrong one points at the wrong panel.
	 */
	enum JewelleryCategory
	{
		/** Skills necklace: the Farming Guild, which is the one that matters on a herb run. */
		SKILLS(InterfaceID.PohJewelleryBox.SKILLS, "farming guild"),

		/** Amulet of glory: Draynor, Al Kharid, Karamja, Edgeville. */
		GLORY(InterfaceID.PohJewelleryBox.GLORY, "draynor", "al kharid", "karamja", "edgeville"),

		/** Games necklace: Burthorpe, which puts you next to the Troll Stronghold patch. */
		GAMING(InterfaceID.PohJewelleryBox.GAMING, "troll stronghold", "burthorpe");

		private final int widgetId;
		private final String[] places;

		JewelleryCategory(int widgetId, String... places)
		{
			this.widgetId = widgetId;
			this.places = places;
		}

		int getWidgetId()
		{
			return widgetId;
		}

		boolean reaches(String destination)
		{
			for (String place : places)
			{
				if (namesTheSamePlace(place, destination))
				{
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * Whether a jewellery box is known to reach this stop.
	 *
	 * <p>Used to decide which piece of furniture to outline. A house can hold both a box and a
	 * nexus, and lighting up both says "one of these two, you work out which" — which is the
	 * question the player wanted answered.
	 */
	public static boolean reachableByJewelleryBox(String destination)
	{
		for (JewelleryCategory category : JewelleryCategory.values())
		{
			if (category.reaches(destination))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * How deep to walk an interface looking for rows.
	 *
	 * <p>Deep enough for the nexus, whose list is several containers down, and bounded so a
	 * malformed tree cannot turn a per-frame scan into a hang.
	 */
	static final int MAX_WIDGET_DEPTH = 6;

	/**
	 * Places the game and this plugin call different things.
	 *
	 * <p>Containment alone does not cover these: the nexus row for the Troll Stronghold patch
	 * reads <b>Trollheim</b>, and the two share no substring at all.
	 */
	private static final Map<String, String> PLACE_ALIASES = new HashMap<>();

	static
	{
		PLACE_ALIASES.put("troll stronghold", "trollheim");
	}

	/**
	 * Whether two names refer to the same place, loosely enough to be useful.
	 *
	 * <p>Containment either way, lower-cased, plus the alias table. Requiring equality would mean
	 * this almost never fired — the game says "Catherby Teleport" where the plugin says
	 * "Catherby".
	 *
	 * <p>The empty guard matters more than it looks: {@code contains("")} is true of every string,
	 * so without it an unlabelled widget — and an interface has many — would match every
	 * destination and light the whole panel up.
	 */
	static boolean namesTheSamePlace(String a, String b)
	{
		if (a == null || b == null)
		{
			return false;
		}

		String left = a.toLowerCase().trim();
		String right = b.toLowerCase().trim();
		if (left.isEmpty() || right.isEmpty())
		{
			return false;
		}

		if (left.contains(right) || right.contains(left))
		{
			return true;
		}

		return matchesAlias(left, right) || matchesAlias(right, left);
	}

	private static boolean matchesAlias(String pluginName, String gameName)
	{
		for (Map.Entry<String, String> alias : PLACE_ALIASES.entrySet())
		{
			if (pluginName.contains(alias.getKey()) && gameName.contains(alias.getValue()))
			{
				return true;
			}
		}
		return false;
	}
}
