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
					locations.record(patch, location);
					return;
				}
			}
		}
	}
}
