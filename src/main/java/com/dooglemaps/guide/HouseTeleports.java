package com.dooglemaps.guide;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
		// The reworked menu interface the spirit tree's "Spirit Tree Locations" list opens -
		// the game moved it off the old Menu group, so "6: Prifddinas" sat in a widget tree
		// this scan never visited and no row lit. Reported from play at the GE tree.
		InterfaceID.MenuNew.UNIVERSE,
		InterfaceID.MenuNew.CONTENT,
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
	 */
	enum JewelleryCategory
	{
		/**
		 * Ring of dueling: Emir's Arena lands beside the Al Kharid cactus patch, which makes it
		 * the router's own pick for that stop. This entry used to be deliberately absent, on the
		 * belief that the ring's only farming-adjacent destination was the Fortis Colosseum —
		 * wrong by one arena: with no DUELING category to claim the route's "1: Emir's Arena"
		 * hop, the glory's button lit off the bare stop name "Al Kharid" instead, and the
		 * highlight pointed at the palace end of town. Reported from play, more than once.
		 * Castle Wars and Ferox are here so a route planned through them can claim the button
		 * too; the stop-name side never matches them, which is correct — neither is anywhere's
		 * best stand-in.
		 */
		DUELING(InterfaceID.PohJewelleryBox.DUELING, "emir's arena", "duel arena",
			"castle wars", "ferox enclave"),

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
		// The network's own stops, which every spirit tree reaches and nobody has to grow.
		FURNITURE_DESTINATIONS.put("spirit tree", new String[]{
			"tree gnome village", "gnome stronghold", "battlefield of khazard",
			"grand exchange", "feldip hills", "port sarim", "etceteria", "brimhaven",
			"hosidius", "farming guild",
		});
	}

	/**
	 * The five spirit tree destinations that only exist if the player grew the tree.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"just got routed to the farming guild via POH spirit tree - farming guild. Don't have
	 * that yet, I see the jewellery box is also highlighted though."</i> Shortest Path planned a
	 * hop through a spirit tree that does not exist, and this class agreed with it, because the
	 * destination list above claims all ten stops unconditionally. Five of them are stops on the
	 * network and five are <b>farm patches</b> — a spirit tree is only in the network once
	 * somebody has grown one there.
	 *
	 * <p>This plugin knows which, and Shortest Path does not: the guild's spirit tree patch was
	 * sitting at {@code varbitValue 0, WEEDS} in the session that produced the report. The
	 * highlight lit the furniture anyway, alongside the jewellery box that genuinely reaches the
	 * guild by skills necklace — so the one piece of furniture that would work was competing for
	 * attention with one that would not.
	 *
	 * <p>Resolved from {@link FarmingWorldData} rather than written out as keys, so it cannot
	 * drift from the patch table. Note {@code hosidius} maps to the region the data calls
	 * <b>Kourend</b>, which is the one place the destination's name and the region's differ.
	 *
	 * <p><b>This cannot suppress the route</b>, only our pointing at it. Shortest Path takes
	 * spirit trees as one boolean ({@code shortestpath.usePohSpiritTree}) with no per-destination
	 * control, so a hop through a tree you have not grown is still planned. What changes is that
	 * the guide no longer endorses it by outlining the tree.
	 */
	private static final Map<String, FarmPatch> GROWN_SPIRIT_TREES = grownSpiritTrees();

	private static Map<String, FarmPatch> grownSpiritTrees()
	{
		Map<String, String> byRegionName = new LinkedHashMap<>();
		byRegionName.put("port sarim", "Port Sarim");
		byRegionName.put("etceteria", "Etceteria");
		byRegionName.put("brimhaven", "Brimhaven");
		byRegionName.put("hosidius", "Kourend");
		byRegionName.put("farming guild", "Farming Guild");

		Map<String, FarmPatch> found = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : byRegionName.entrySet())
		{
			for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.SPIRIT_TREE))
			{
				if (patch.getRegion().getName().equals(entry.getValue()))
				{
					found.put(entry.getKey(), patch);
					break;
				}
			}
		}
		return found;
	}

	/**
	 * The patch a spirit tree hop depends on, or null when the destination needs no growing.
	 *
	 * <p>Null for every network stop, and for anything that is not a spirit tree destination at
	 * all — so a caller with no opinion about patch state can ignore this entirely.
	 */
	@javax.annotation.Nullable
	public static FarmPatch spiritTreePatchFor(String destination)
	{
		if (destination == null)
		{
			return null;
		}
		String said = destination.toLowerCase();
		for (Map.Entry<String, FarmPatch> entry : GROWN_SPIRIT_TREES.entrySet())
		{
			if (said.contains(entry.getKey()))
			{
				return entry.getValue();
			}
		}
		return null;
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
	/**
	 * What a fairy ring's hop looks like: a code of three single letters — "a j p" — or
	 * "zanaris", or several codes chained with dashes, exactly as Shortest Path's
	 * fairy_rings.tsv writes its display info. Lowercased input; see the fairy clause below.
	 */
	private static final java.util.regex.Pattern FAIRY_CODE = java.util.regex.Pattern.compile(
		"(zanaris|[a-z](\\s[a-z]){2})(\\s*-\\s*(zanaris|[a-z](\\s[a-z]){2}))*");

	/**
	 * Whether a hop names this furniture <b>explicitly</b> — the router planned through it.
	 *
	 * <p>The strong half of {@link #furnitureServesHop}, split out because the two kinds of
	 * "serves" carry different weight. A hop that says "Configure Fairy ring" or "Portal
	 * Nexus" is the route going through that furniture; a hop that merely lands somewhere on
	 * the furniture's destination list may be coincidence — the Prifddinas nexus "served" a
	 * route that never used it, which is what {@code doorIsTheWay} exists to overrule. An
	 * explicit plan must not be overruled by it. See {@code GuideTracker.routeFromTheFrontDoor}.
	 */
	public static boolean furnitureNamedByHop(String furnitureName, String hop)
	{
		if (furnitureName == null || hop == null)
		{
			return false;
		}

		String name = furnitureName.toLowerCase().trim();
		String said = hop.toLowerCase();

		// A combined build — "Spirit tree & fairy ring" — is two furnitures in one object,
		// and a hop only ever names the half it uses, so the whole name can never appear in
		// the hop's text. Found from the field the hard way: the garden ring stayed dark for
		// a route whose own hop said "Configure Fairy ring - C K Q", because the containment
		// below was being asked about a name longer than the hop. Each half answers for
		// itself.
		if (name.contains("&"))
		{
			for (String part : name.split("&"))
			{
				if (furnitureNamedByHop(part.trim(), hop))
				{
					return true;
				}
			}
			return false;
		}

		if (!name.equals("portal") && !name.isEmpty() && said.contains(name))
		{
			return true;
		}

		// The fairy clause, both wordings. Shortest Path's fairy hop is either prose that
		// says "fairy ring" ("Configure Fairy ring - C K Q") or the bare CODE — its
		// fairy_rings.tsv carries "A J P", "ZANARIS", or a chain like "A I R - D L R" as the
		// whole display info, with an empty objectInfo on the destination rows. Matching the
		// prose form by kind rather than by full-name containment is what lets a decorated
		// or combined ring name still claim its own hop.
		if (name.contains("fairy ring")
			&& (said.contains("fairy ring") || FAIRY_CODE.matcher(said.trim()).matches()))
		{
			return true;
		}
		return false;
	}

	/**
	 * Whether this piece of furniture is a spirit tree, and so subject to the grown-tree check.
	 *
	 * <h2>Why the check has to be asked about the furniture and not just the hop</h2>
	 *
	 * {@code GuideTracker.spiritTreeUsableFor} answers "has a tree actually been grown at the
	 * place this hop names", and {@link #spiritTreePatchFor} finds that place by looking for a
	 * known spirit-tree name <b>anywhere in the hop string</b>. A hop names its vehicle as well as
	 * its destination, so <i>"Teleport Menu Fancy Jewellery Box - J: Farming Guild"</i> contains
	 * "farming guild" and is read as a spirit tree question — and answered no, because the guild's
	 * spirit tree patch is empty.
	 *
	 * <p>That vetoed the <b>jewellery box</b>, which has nothing to do with spirit trees. Reported
	 * from play: teleported to the house for the Farming Guild and nothing was outlined, with the
	 * box plainly in the room. The log had both halves — {@code Ornate Jewellery Box#29156} in the
	 * furniture and {@code Fancy Jewellery Box - J: Farming Guild} in the hops — and still no
	 * match, which is what ruled out the name matching and left the guard.
	 *
	 * <p>It bites at exactly the destinations that are also spirit-tree stops: the Farming Guild,
	 * Hosidius, Etceteria, Brimhaven, Port Sarim. Anywhere else the guard passes and nobody notices.
	 */
	public static boolean isSpiritTree(@javax.annotation.Nullable String furnitureName)
	{
		return furnitureName != null
			&& furnitureName.toLowerCase().contains("spirit tree");
	}

	public static boolean furnitureServesHop(String furnitureName, String hop)
	{
		if (furnitureName == null || hop == null)
		{
			return false;
		}

		String name = furnitureName.toLowerCase().trim();
		String said = hop.toLowerCase();

		if (furnitureNamedByHop(furnitureName, hop))
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
	 * Places whose teleports the game names differently.
	 *
	 * <p>Containment alone does not cover these: a nexus serving the Troll Stronghold patch
	 * may do it through its <b>Trollheim</b> row, and the two share no substring at all. An
	 * alias is a stand-in, not a synonym — the nexus can hold a real "Troll Stronghold" row
	 * too (attuned with stony basalt), which lands beside the patch where Trollheim lands up
	 * the mountain. {@code namesTheSamePlaceDirectly} is how a caller prefers the real row
	 * when both are on offer.
	 */
	private static final Map<String, String> PLACE_ALIASES = new HashMap<>();

	static
	{
		PLACE_ALIASES.put("troll stronghold", "trollheim");

		// The nexus's Varrock teleport can be redirected to the Grand Exchange, and the row
		// then *says* "Grand Exchange" — no substring in common with the "Varrock" the run
		// stop is named after. The furniture match has known this pair since the nexus
		// destination list was written (see FURNITURE_DESTINATIONS); the row match needs the
		// same vocabulary, or the plugin lights the nexus and then goes dark inside its menu.
		// Reported from play. A nexus holding a real "Varrock" row still wins over this: the
		// direct-beats-aliased preference in GuideInventoryOverlay handles that.
		PLACE_ALIASES.put("varrock", "grand exchange");
	}

	/**
	 * Whether two names refer to the same place, loosely enough to be useful.
	 *
	 * <p>Containment either way, canonicalised, plus the alias table. Requiring equality would
	 * mean this almost never fired — the game says "Catherby Teleport" where the plugin says
	 * "Catherby".
	 */
	static boolean namesTheSamePlace(String a, String b)
	{
		if (namesTheSamePlaceDirectly(a, b))
		{
			return true;
		}

		String left = canonical(a);
		String right = canonical(b);
		if (left.isEmpty() || right.isEmpty())
		{
			return false;
		}
		return matchesAlias(left, right) || matchesAlias(right, left);
	}

	/**
	 * As {@link #namesTheSamePlace}, but by the name alone — no aliases. A direct match is the
	 * row that actually lands at the destination, where an aliased one is the best stand-in;
	 * callers with a whole list to choose from should light the direct rows when any exist.
	 *
	 * <p>The empty guard matters more than it looks: {@code contains("")} is true of every string,
	 * so without it an unlabelled widget — and an interface has many — would match every
	 * destination and light the whole panel up.
	 */
	static boolean namesTheSamePlaceDirectly(String a, String b)
	{
		String left = canonical(a);
		String right = canonical(b);
		if (left.isEmpty() || right.isEmpty())
		{
			return false;
		}

		return left.contains(right) || right.contains(left);
	}

	/**
	 * A name reduced to the words that identify a place: interface tags stripped, lower-cased,
	 * every run of punctuation and spacing collapsed to one space.
	 *
	 * <p>The tags are the half that cost a bug — twice, in the same session's logs. A jewellery
	 * box row is really {@code <col=ccccff>1:</col> Emir's Arena}, so the router's own row name
	 * "1: Emir's Arena" failed plain containment on the colour tag, and the match fell through
	 * to the destination — which the <i>glory's</i> "R: Al Kharid" row carries verbatim. The
	 * exact loop the hops-before-destination ordering was built to prevent, reintroduced by
	 * markup. The nexus does it with spacing instead: its rows read {@code <col=ffffff>D</col>
	 * :  Poison Waste}, colon outside the tag and two spaces deep, which no plain substring of
	 * "D: Poison Waste" survives. Both reduce to the same canonical words.
	 */
	private static String canonical(@javax.annotation.Nullable String text)
	{
		if (text == null)
		{
			return "";
		}
		return net.runelite.client.util.Text.removeTags(text)
			.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
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
