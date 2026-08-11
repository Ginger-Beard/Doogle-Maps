package com.dooglemaps.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.Subscribe;

/**
 * Whether the player is standing in their house, and what they can travel with while there.
 *
 * <p>Its own class because two very different things need the same answer and neither should be
 * scanning the scene for itself: the overlay outlines the furniture, and the guide has to stop
 * telling you to use a house teleport once you are already in the house.
 *
 * <p>Scanned once a tick on the client thread and handed out as an immutable list, so the
 * overlays can read it from the render thread. It holds no reference to any other store, which
 * keeps it a leaf in the lock graph — see {@code docs/NOTES.md}.
 */
@Singleton
public class PlayerHouse
{
	/**
	 * Jewellery box object ids, all three tiers.
	 *
	 * <p>Matched by id <b>as well as</b> by name, because a name lookup is one indirection away
	 * from failing quietly: an object whose appearance varies reports its name through an
	 * impostor, and the base definition can come back with a placeholder. Reported from play —
	 * the nexus outlined and the jewellery box did not, which is exactly what one working lookup
	 * and one failing lookup looks like.
	 *
	 * <p>Ids are exact and cannot half-work. The name match stays as well, so a tier added later
	 * is still covered without anyone noticing it needs to be.
	 */
	private static final Set<Integer> JEWELLERY_BOX_IDS = new HashSet<>(java.util.Arrays.asList(
		37492,   // Basic
		37501,   // Fancy
		37520)); // Ornate

	/**
	 * The superior garden's teleport builds, by id, each with the name the matching runs on.
	 *
	 * <p>The jewellery box's disease, caught in a second place: a route through the garden
	 * fairy ring — a plain built ring, wiki object 29228 — matched no furniture at all in the
	 * field, while the portals in the same house matched fine. Whatever its scene object
	 * resolves its name to, it is not something the name path can rely on, so all three
	 * builds this hotspot takes are recognised by id. The combined build's forced name
	 * carries both halves, which {@code HouseTeleports.furnitureNamedByHop} splits on the
	 * "&" so each half can claim its own hops.
	 */
	private static final java.util.Map<Integer, String> GARDEN_TELEPORT_IDS =
		java.util.Map.of(
			net.runelite.api.gameval.ObjectID.POH_SPIRIT_TREE, "Spirit tree",
			net.runelite.api.gameval.ObjectID.POH_FAIRY_RING, "Fairy ring",
			net.runelite.api.gameval.ObjectID.POH_SPIRIT_RING, "Spirit tree & fairy ring");

	/**
	 * Words identifying teleport furniture, whatever tier it was built at.
	 *
	 * <p>Everything a house can teleport you with: the nexus, the jewellery box, the mounted
	 * digsite pendant ("Digsite Pendant", in the nexus room's amulet space), the mounted glory
	 * and xeric's talisman, the superior garden's spirit tree and fairy ring, and the portal
	 * room's single-destination portals — "portal" also covers the nexus and the exits, which
	 * is harmless: a bare "Portal" can never match a route hop, see
	 * {@code HouseTeleports.furnitureServesHop}.
	 *
	 * <p>Some of these words also name things in the open world — every fairy ring, every
	 * spirit tree — which is why {@link #isInside()} does not trust this list; it wants
	 * {@link #HOUSE_MARKERS}.
	 */
	private static final String[] NAMES = {"nexus", "jewellery box", "digsite", "portal",
		"spirit tree", "fairy ring", "amulet of glory", "xeric"};

	/**
	 * Names that only ever belong to house furniture, for {@link #isInside()}.
	 *
	 * <p>A spirit tree or a fairy ring in the scene proves a garden <i>or</i> Zanaris; a
	 * Portal Nexus or a jewellery box proves a house.
	 */
	private static final String[] HOUSE_MARKERS = {"nexus", "jewellery box", "digsite"};

	private final Client client;

	private volatile List<TileObject> teleports = Collections.emptyList();

	@Inject
	PlayerHouse(Client client)
	{
		this.client = client;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			teleports = Collections.emptyList();
			inside = false;
			return;
		}
		teleports = scan();
		// Before the portal record below, which reads it through isInside().
		boolean was = inside;
		inside = computeInside();
		if (inside && !was)
		{
			enteredTick = client.getTickCount();
		}
		recordPortalRoomPortals();
	}

	/** The tick {@link #isInside} first became true on this visit. See {@link #justEntered}. */
	private volatile int enteredTick = -1;

	/**
	 * Whether the player arrived in the house on the current tick.
	 *
	 * <p>The one tick where "inside" and the rest of the plugin disagree. The event bus runs
	 * same-priority GameTick subscribers in class-name order, which puts {@code GuideTracker}
	 * before this class — so on the arrival tick the tracker has already taken its snapshot
	 * against the <i>previous</i> tile, still carrying the pre-teleport route, before this
	 * class notices the house. Anything that combines a live {@code isInside()} with that
	 * snapshot — the exit-portal fallback was the reported case, flashing lit for the arrival
	 * tick — should stand down while this is true; by the next tick the tracker has retargeted
	 * and the disagreement is over.
	 *
	 * <p>Client thread only: it reads the tick counter.
	 */
	public boolean justEntered()
	{
		return inside && client.getTickCount() == enteredTick;
	}

	/**
	 * Whether the last look inside a house found any portal-room portal. Null until a house
	 * has been seen this session; kept after leaving, because the question is asked precisely
	 * when the player is <i>not</i> standing in the house.
	 *
	 * <p>Exists for Shortest Path, whose POH data assumes every house has its portal-room
	 * portals built — the Prifddinas respawn-portal edge is not even varbit-gated. For a house
	 * that holds none, that assumption routed the player in and out of their own front door
	 * forever; see {@code ShortestPathIntegration}, which reads this to switch those portals
	 * off in its requests.
	 */
	@Nullable
	private volatile Boolean portalRoomPortals;

	/** See {@link #portalRoomPortals}. */
	@Nullable
	public Boolean hasPortalRoomPortals()
	{
		return portalRoomPortals;
	}

	/**
	 * Only while demonstrably inside, so the sticky answer is about the player's house rather
	 * than the scenery — and a friend's portal-lined house can only weaken the answer toward
	 * "has portals", which merely declines the override. The safe direction.
	 */
	private void recordPortalRoomPortals()
	{
		if (!isInside())
		{
			return;
		}
		portalRoomPortals = !matchingFurniture(PlayerHouse::isPortalRoomPortal).isEmpty();
	}

	/**
	 * A portal-room portal by name: "Varrock Portal", "Respawn Portal" — the trailing space
	 * excludes the bare exit "Portal", and the nexus is its own kind of furniture.
	 */
	private static boolean isPortalRoomPortal(String name)
	{
		String lower = name.toLowerCase();
		return lower.endsWith(" portal") && !lower.contains("nexus");
	}

	/**
	 * Whether the player appears to be in a player-owned house.
	 *
	 * <p>Inferred from the furniture being in the scene rather than from a region id, because a
	 * house is an instance and its region is shared with every other house — and because the only
	 * thing this is used for is deciding whether the teleport furniture is usable, which is the
	 * same question as whether it is there.
	 *
	 * <p>A house with neither a nexus nor a jewellery box reads as "not in a house", which is
	 * harmless: there is nothing to point at and nothing to suppress.
	 *
	 * <p>Answered from the last tick's computation rather than worked out on demand, because
	 * resolving an object's name goes through {@code client.getObjectDefinition}, which
	 * asserts the client thread — and this question is asked from Swing too. Reported as a
	 * crash: the sidebar's skip button asked on the EDT, straight into the assertion. One
	 * tick of staleness was already the deal, since the scan itself runs once a tick.
	 */
	public boolean isInside()
	{
		return inside;
	}

	/** The last tick's answer to {@link #isInside}; volatile so any thread may read it. */
	private volatile boolean inside;

	/** The real computation. Client thread only — it resolves names. */
	private boolean computeInside()
	{
		for (TileObject object : teleports)
		{
			if (JEWELLERY_BOX_IDS.contains(object.getId()))
			{
				return true;
			}
			ObjectComposition definition = resolve(object.getId());
			if (definition == null || definition.getName() == null)
			{
				continue;
			}
			String name = definition.getName().toLowerCase();
			for (String marker : HOUSE_MARKERS)
			{
				if (name.contains(marker))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The teleport furniture whose resolved name the given test accepts.
	 *
	 * <p>The caller brings the question — in practice "does any hop on Shortest Path's route
	 * go somewhere this furniture goes", asked through {@code HouseTeleports} — and this
	 * class only supplies the scene objects and their names, which is the part that needs the
	 * client. The jewellery box answers by its known ids when its name will not resolve — the
	 * impostor problem {@link #JEWELLERY_BOX_IDS} exists for.
	 */
	public List<TileObject> matchingFurniture(java.util.function.Predicate<String> nameTest)
	{
		List<TileObject> found = new ArrayList<>();
		for (TileObject object : teleports)
		{
			String name = furnitureName(object);
			if (name != null && nameTest.test(name))
			{
				found.add(object);
			}
		}
		return found;
	}

	/** The name the matching runs on: forced for the known-id builds, resolved otherwise. */
	@Nullable
	private String furnitureName(TileObject object)
	{
		if (JEWELLERY_BOX_IDS.contains(object.getId()))
		{
			return "jewellery box";
		}
		String garden = GARDEN_TELEPORT_IDS.get(object.getId());
		if (garden != null)
		{
			return garden;
		}
		ObjectComposition definition = resolve(object.getId());
		return definition == null ? null : definition.getName();
	}

	/**
	 * Every scanned teleport as {@code name#id}, for the unmatched-hops log.
	 *
	 * <p>Exists because "none of the hops matched the furniture here" cannot be acted on
	 * without knowing what the furniture said its names were — the fairy ring sat unmatched
	 * for two sessions while the log named only the hops' half of the comparison. Client
	 * thread only, like every furniture question.
	 */
	public List<String> furnitureNames()
	{
		List<String> names = new ArrayList<>();
		for (TileObject object : teleports)
		{
			names.add(furnitureName(object) + "#" + object.getId());
		}
		return names;
	}

	/**
	 * Where each house location's exterior portal stands, keyed by the game's own record of
	 * where the player's house is.
	 *
	 * <p>The tile you appear on after walking out the exit portal, which makes it the true
	 * start of any journey that leaves the house on foot. Varbit values and tiles are the
	 * ones Shortest Path itself uses for its "Teleport to House (Outside)" transports,
	 * cross-checked against the wiki's house portal article, August 2026.
	 */
	private static final java.util.Map<Integer, WorldPoint> FRONT_DOORS = new java.util.HashMap<>();

	static
	{
		FRONT_DOORS.put(1, new WorldPoint(2953, 3224, 0)); // Rimmington
		FRONT_DOORS.put(2, new WorldPoint(2893, 3465, 0)); // Taverley
		FRONT_DOORS.put(3, new WorldPoint(3340, 3003, 0)); // Pollnivneach
		FRONT_DOORS.put(4, new WorldPoint(2670, 3631, 0)); // Rellekka
		FRONT_DOORS.put(5, new WorldPoint(2757, 3178, 0)); // Brimhaven
		FRONT_DOORS.put(6, new WorldPoint(2544, 3096, 0)); // Yanille
		FRONT_DOORS.put(7, new WorldPoint(3239, 6076, 0)); // Prifddinas
		FRONT_DOORS.put(8, new WorldPoint(1743, 3517, 0)); // Hosidius
		FRONT_DOORS.put(9, new WorldPoint(1422, 2963, 0)); // Aldarin
	}

	/**
	 * The tile outside this player's own house portal, or null when the game has not said
	 * where the house is.
	 *
	 * <p>Client-thread only, like everything else that reads a varbit.
	 */
	@Nullable
	public WorldPoint frontDoor()
	{
		return FRONT_DOORS.get(client.getVarbitValue(VarbitID.POH_HOUSE_LOCATION));
	}

	/**
	 * The house's exit portals — the objects named exactly "Portal".
	 *
	 * <p>Every house exit shares the bare name, which is why {@code furnitureServesHop}
	 * refuses to match it against route hops: any hop mentioning a portal would light every
	 * exit. Leaving the house is still a real instruction, though — the route often continues
	 * from the front door — so the exits are reachable by asking for them outright rather
	 * than through the hop matching.
	 *
	 * <p>An exact match, not containment: "Portal Nexus" and every single-destination
	 * "Varrock Portal" contain the word, and none of them takes you outside.
	 */
	public List<TileObject> exitPortals()
	{
		return matchingFurniture(name -> name.trim().equalsIgnoreCase("portal"));
	}

	public void reset()
	{
		teleports = Collections.emptyList();
		inside = false;
		enteredTick = -1;
		// The portal answer is deliberately sticky across *this account's* comings and goings
		// — the question is asked when the player is not in the house — but it must not
		// survive into a different account's session: A's "no portal room" was suppressing
		// house-portal routes for B. reset() is called at the login screen and on profile
		// change, which is exactly the boundary the stickiness must not cross.
		portalRoomPortals = null;
	}

	/**
	 * An object's real definition, following an impostor where there is one.
	 *
	 * <p>An object whose look depends on a varbit reports its real identity through an impostor,
	 * and the base definition's name can be a placeholder. Asking the base only is how a name
	 * match works for one object and silently fails for its neighbour — which is what happened:
	 * the nexus outlined and the jewellery box did not.
	 */
	@Nullable
	private ObjectComposition resolve(int objectId)
	{
		ObjectComposition definition = client.getObjectDefinition(objectId);
		if (definition == null)
		{
			return null;
		}

		if (definition.getImpostorIds() != null)
		{
			ObjectComposition impostor = definition.getImpostor();
			if (impostor != null)
			{
				return impostor;
			}
		}
		return definition;
	}

	private List<TileObject> scan()
	{
		Scene scene = client.getTopLevelWorldView().getScene();
		Tile[][][] tiles = scene.getTiles();
		int plane = client.getTopLevelWorldView().getPlane();

		List<TileObject> found = new ArrayList<>();
		Set<Long> seen = new HashSet<>();

		for (Tile[] column : tiles[plane])
		{
			for (Tile tile : column)
			{
				if (tile == null)
				{
					continue;
				}
				for (GameObject object : tile.getGameObjects())
				{
					consider(object, seen, found);
				}
				consider(tile.getDecorativeObject(), seen, found);
				consider(tile.getWallObject(), seen, found);
				consider(tile.getGroundObject(), seen, found);
			}
		}
		return found;
	}

	private void consider(@Nullable TileObject object, Set<Long> seen, List<TileObject> found)
	{
		if (object == null || !isTeleportFurniture(object.getId()))
		{
			return;
		}
		if (seen.add(object.getHash()))
		{
			found.add(object);
		}
	}

	private boolean isTeleportFurniture(int objectId)
	{
		if (JEWELLERY_BOX_IDS.contains(objectId) || GARDEN_TELEPORT_IDS.containsKey(objectId))
		{
			return true;
		}

		ObjectComposition definition = resolve(objectId);
		if (definition == null || definition.getName() == null)
		{
			return false;
		}

		String name = definition.getName().toLowerCase();
		for (String wanted : NAMES)
		{
			if (name.contains(wanted))
			{
				return true;
			}
		}
		return false;
	}
}
