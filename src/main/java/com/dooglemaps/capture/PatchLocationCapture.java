package com.dooglemaps.capture;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmRegion;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.route.PatchLocationStore;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.client.eventbus.Subscribe;

/**
 * Learns where patches are by watching their game objects appear.
 *
 * <p>A patch object carries the same varbit id the plugin already identifies patches by,
 * so no extra data is needed: walk near a patch once and its exact position is recorded
 * for good. That is what makes routing possible without hand-authoring coordinates for
 * every patch in the game.
 */
@Slf4j
@Singleton
public class PatchLocationCapture
{
	private final Client client;
	private final PatchLocationStore locations;

	@Inject
	PatchLocationCapture(Client client, PatchLocationStore locations)
	{
		this.client = client;
		this.locations = locations;
	}

	/**
	 * One patch object seen, waiting for a tick to record it on.
	 *
	 * <p>See {@link #onGameTick}. Held rather than recorded straight away because a scene's
	 * objects spawn <b>before</b> the stores have read anything.
	 */
	private static final class Sighting
	{
		private final FarmPatch patch;
		private final WorldPoint point;
		private final int sizeX;
		private final int sizeY;

		private Sighting(FarmPatch patch, WorldPoint point, int sizeX, int sizeY)
		{
			this.patch = patch;
			this.point = point;
			this.sizeX = sizeX;
			this.sizeY = sizeY;
		}
	}

	private final java.util.List<Sighting> pending = new java.util.ArrayList<>();

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		GameObject object = event.getGameObject();
		ObjectComposition definition = client.getObjectDefinition(object.getId());
		if (definition == null || definition.getVarbitId() == -1)
		{
			return;
		}

		WorldPoint location = object.getWorldLocation();
		for (FarmRegion region : FarmingWorldData.getRegionsForLocation(location))
		{
			for (FarmPatch patch : region.getPatches())
			{
				if (patch.getVarbit() == definition.getVarbitId())
				{
					// The footprint travels with the position: the router is sent the ring of
					// tiles AROUND the patch, and the ring is only exact once the size is known.
					// See PatchLocationStore.getRouteTargets.
					pending.add(new Sighting(patch, location, object.sizeX(), object.sizeY()));
					return;
				}
			}
		}
	}

	/**
	 * Records what the scene showed, a tick after it showed it.
	 *
	 * <h2>Why this cannot happen on the spawn</h2>
	 *
	 * A scene's objects spawn during {@code LOADING}, and the stores read their blobs on the
	 * first {@code LOGGED_IN} — so at a login the whole scene arrives <b>before</b>
	 * {@code PatchLocationStore} has read anything. Recording there wrote into an empty map,
	 * which is how a store holding six positions came to overwrite one holding a hundred; see
	 * {@code ProfileJsonStore}'s write guard, which now refuses it.
	 *
	 * <p>Refusing the write fixed the destruction and left the other half: those sightings were
	 * still thrown away, because {@code load()} clears the map on its way in. Logging in at the
	 * Farming Guild therefore learned all thirteen patches correctly and kept none of them —
	 * ten refusals in one second, and the guild's tree and redwood still carrying the wrong
	 * plane afterwards. Reported from play, from the warning itself.
	 *
	 * <p>A {@code GameTick} is the simplest thing that is provably late enough: it only fires
	 * while logged in, so the load that happens on the way to {@code LOGGED_IN} is already done.
	 * No game-state test, no ordering assumption between two subscribers, one code path for the
	 * login scene and every teleport after it.
	 *
	 * <p>Nothing clears the buffer on the way out, deliberately: a sighting held across a logout
	 * is still true when it is flushed, because a patch's position is a fact about the world
	 * rather than about the account.
	 */
	@Subscribe
	public void onGameTick(net.runelite.api.events.GameTick event)
	{
		if (pending.isEmpty())
		{
			return;
		}

		java.util.List<Sighting> seen = new java.util.ArrayList<>(pending);
		pending.clear();
		for (Sighting sighting : seen)
		{
			locations.record(sighting.patch, sighting.point, sighting.sizeX, sighting.sizeY);
		}
	}
}
