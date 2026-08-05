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
import net.runelite.api.events.GameTick;
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
 * keeps it a leaf in the lock graph — see {@code NOTES.md}.
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

	/** Words identifying teleport furniture, whatever tier it was built at. */
	private static final String[] NAMES = {"nexus", "jewellery box"};

	private final Client client;

	private volatile List<TileObject> teleports = Collections.emptyList();

	@Inject
	private PlayerHouse(Client client)
	{
		this.client = client;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			teleports = Collections.emptyList();
			return;
		}
		teleports = scan();
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
	 */
	public boolean isInside()
	{
		return !teleports.isEmpty();
	}

	/** The teleport furniture in the current scene, for the overlay to outline. */
	public List<TileObject> getTeleports()
	{
		return teleports;
	}

	/**
	 * Just the jewellery boxes, or just the nexuses.
	 *
	 * <p>Split because a house can hold both, and outlining both says "one of these two, you work
	 * out which" — which is the question the player was asking. Reported from play: heading for
	 * the Farming Guild by jewellery box, the nexus lit up as well.
	 */
	public List<TileObject> getJewelleryBoxes()
	{
		return filter(true);
	}

	public List<TileObject> getNexuses()
	{
		return filter(false);
	}

	private List<TileObject> filter(boolean wantBoxes)
	{
		List<TileObject> found = new ArrayList<>();
		for (TileObject object : teleports)
		{
			if (isJewelleryBox(object.getId()) == wantBoxes)
			{
				found.add(object);
			}
		}
		return found;
	}

	/** Whether an object is a jewellery box rather than a nexus. */
	private boolean isJewelleryBox(int objectId)
	{
		if (JEWELLERY_BOX_IDS.contains(objectId))
		{
			return true;
		}

		ObjectComposition definition = resolve(objectId);
		return definition != null && definition.getName() != null
			&& definition.getName().toLowerCase().contains("jewellery box");
	}

	public void reset()
	{
		teleports = Collections.emptyList();
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
		if (JEWELLERY_BOX_IDS.contains(objectId))
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
