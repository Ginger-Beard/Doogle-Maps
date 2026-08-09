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
		// The jewellery box's own interface, one section layer per jewellery type, each holding
		// its "J: Farming Guild" lines as children. These were missing on the belief that the box
		// opened the lettered option menu below — it does not; the lines live in the box's own
		// sections, so the row scan found nothing and the fallback outlined the whole section.
		// Reported from play as the entire Skills necklace block lighting up.
		InterfaceID.PohJewelleryBox.DUELING,
		InterfaceID.PohJewelleryBox.GAMING,
		InterfaceID.PohJewelleryBox.COMBAT,
		InterfaceID.PohJewelleryBox.SKILLS,
		InterfaceID.PohJewelleryBox.WEALTH,
		InterfaceID.PohJewelleryBox.GLORY,
		// The lettered option menu, for the teleports that genuinely open one.
		InterfaceID.Menu.LJ_LAYER1,
		InterfaceID.Menu.LJ_LAYER2,
		InterfaceID.Chatmenu.OPTIONS,
	};

	/**
	 * Which jewellery-box category reaches which farming stop, for lighting the right button
	 * once the player is inside the box's menu.
	 *
	 * <p>Wiki-checked against each jewellery piece's own teleport list, August 2026. Reaching
	 * includes "gets you meaningfully closer" — a player standing in the box's menu wants the
	 * least-bad button, not a judgement about whether they should have used the nexus. That
	 * stricter question is {@link #furnitureFor}'s, from its own table.
	 *
	 * <p>The ring of dueling is deliberately absent: its one farming-adjacent destination,
	 * the Fortis Colosseum, is locked behind the Colosseum's Hero title and lands across the
	 * city from the Civitas patches anyway.
	 */
	enum JewelleryCategory
	{
		/** Skills necklace: the Farming Guild, a teleport into the guild itself. */
		SKILLS(InterfaceID.PohJewelleryBox.SKILLS, "farming guild"),

		/**
		 * Amulet of glory: Draynor Village, Al Kharid, Karamja, Edgeville. Karamja is Musa
		 * Point, which is also the least-bad button for Brimhaven's fruit tree and Tai Bwo
		 * Wannai's calquat — a walk or a cart, but the right island.
		 */
		GLORY(InterfaceID.PohJewelleryBox.GLORY, "draynor", "al kharid", "karamja",
			"edgeville", "brimhaven", "tai bwo wannai"),

		/**
		 * Ring of wealth: Falador Park is the tree patch's own lawn, Miscellania is the
		 * bridge to Etceteria's spirit tree, and the Grand Exchange is a short walk from
		 * Varrock castle's tree patch.
		 */
		WEALTH(InterfaceID.PohJewelleryBox.WEALTH, "falador", "etceteria", "miscellania",
			"varrock"),

		/** Combat bracelet: the Champions' Guild is the Varrock bush patch's front door. */
		COMBAT(InterfaceID.PohJewelleryBox.COMBAT, "champions' guild"),

		/** Games necklace: Burthorpe, the foot of the climb up to the Troll Stronghold patch. */
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
	 * What each kind of house teleport furniture can reach, keyed by a word from the
	 * furniture's own object name.
	 *
	 * <h2>Furniture to destinations, so the router's words pick the furniture</h2>
	 *
	 * Shortest Path plans the leg and reports its hops as display text, and those hops name
	 * <i>places and teleports</i>, not furniture. So the map runs the useful direction: for
	 * each piece of furniture standing in the room, does any hop name somewhere that
	 * furniture goes? The furniture the route uses is the one whose destination list the
	 * route mentions — no per-stop opinion of this plugin's anywhere in it.
	 *
	 * <p>Destination lists are the wiki's own, August 2026: the Portal Nexus's attunable
	 * destinations, every jewellery piece a box can hold (dueling, games, combat, skills,
	 * glory, wealth across the tiers), the mounted digsite pendant, the mounted glory and
	 * xeric's talisman, and the spirit tree network. Single-destination portals need no
	 * entry: a "Varrock Portal" carries its destination in its own name, which
	 * {@link #furnitureServesHop} handles by stripping the word "portal".
	 *
	 * <p>Prifddinas is deliberately nowhere in this map — no house furniture goes there, the
	 * teleport crystal in the pack does, and items are the travel-item highlight's job. A
	 * hop for it simply matches no furniture.
	 */
	private static final Map<String, String[]> FURNITURE_DESTINATIONS = new HashMap<>();

	static
	{
		FURNITURE_DESTINATIONS.put("nexus", new String[]{
			"varrock", "grand exchange", "lumbridge", "falador", "camelot", "seers",
			"ardougne", "catherby", "trollheim", "troll stronghold", "weiss",
			"civitas illa fortis", "kourend", "harmony", "draynor manor", "battlefront",
			"mind altar", "salve graveyard", "fenkenstrain", "edgeville", "yanille",
			"watchtower", "senntisten", "digsite", "ape atoll", "marim", "lunar isle",
			"moonclan", "forgotten cemetery", "ourania", "waterbirth", "lassar",
			"ice mountain", "barbarian outpost", "khazard", "barrows", "fishing guild",
			"ice plateau", "annakarl", "carrallanger", "dareeyak", "ghorrock",
			"arceuus library",
		});
		FURNITURE_DESTINATIONS.put("jewellery box", new String[]{
			"emir's arena", "duel arena", "castle wars", "ferox enclave",
			"fortis colosseum", "burthorpe", "barbarian outpost", "corporeal beast",
			"tears of guthix", "wintertodt", "warriors' guild", "champions' guild",
			"monastery", "ranging guild", "fishing guild", "mining guild",
			"crafting guild", "cooking guild", "woodcutting guild", "farming guild",
			"edgeville", "karamja", "draynor village", "al kharid", "miscellania",
			"grand exchange", "falador park", "dondakan",
		});
		FURNITURE_DESTINATIONS.put("digsite", new String[]{
			"digsite", "fossil island", "house on the hill", "lithkren",
		});
		FURNITURE_DESTINATIONS.put("glory", new String[]{
			"edgeville", "karamja", "draynor village", "al kharid",
		});
		FURNITURE_DESTINATIONS.put("xeric", new String[]{
			"xeric's glade", "xeric's lookout", "xeric's inferno", "xeric's heart",
			"xeric's honour", "hosidius",
		});
		FURNITURE_DESTINATIONS.put("spirit tree", new String[]{
			"tree gnome village", "gnome stronghold", "battlefield of khazard",
			"grand exchange", "feldip hills", "port sarim", "etceteria", "brimhaven",
			"hosidius", "farming guild",
		});
	}

	/**
	 * Whether a hop on Shortest Path's route is served by furniture with this name.
	 *
	 * <p>Three ways to say yes, tried cheapest first:
	 *
	 * <ul>
	 *   <li>the hop names the furniture itself ("Portal Nexus" in the text) — except for the
	 *       bare name "Portal", which every house exit shares and which would match any hop
	 *       that mentions a portal;</li>
	 *   <li>the furniture's name carries its destination, as single-destination portals do —
	 *       "Varrock Portal" serves a "Varrock Teleport" hop once the word "portal" is
	 *       stripped;</li>
	 *   <li>the furniture's kind has a destination list here and the hop names one of them.</li>
	 * </ul>
	 */
	public static boolean furnitureServesHop(String furnitureName, String hop)
	{
		if (furnitureName == null || hop == null)
		{
			return false;
		}

		String name = furnitureName.toLowerCase().trim();
		String said = hop.toLowerCase();

		if (!name.equals("portal") && !name.isEmpty() && said.contains(name))
		{
			return true;
		}

		String place = name.replace("portal", "").trim();
		if (!place.isEmpty() && !place.equals(name) && namesTheSamePlace(place, hop))
		{
			return true;
		}

		for (Map.Entry<String, String[]> kind : FURNITURE_DESTINATIONS.entrySet())
		{
			if (!name.contains(kind.getKey()))
			{
				continue;
			}
			for (String destination : kind.getValue())
			{
				if (namesTheSamePlace(destination, hop))
				{
					return true;
				}
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
