package com.dooglemaps.guide;

import com.dooglemaps.data.Produce;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.client.eventbus.Subscribe;

/**
 * Crops that overflowed onto the ground because the pack was full.
 *
 * <p>A bulk harvest — limpwurts are the reported case, and flowers generally — hands over
 * several items per pick, and the ones that do not fit drop at the player's feet. Nothing
 * else in the plugin watches ground items, so after noting freed the slots the guide moved
 * straight on and the crops despawned behind it. This records the overflow so a pick-up step
 * can point back at it.
 *
 * <h2>Only our own overflow, nothing else on the floor</h2>
 *
 * A farming patch is a public place, and "highlight the limpwurt roots on the ground" must
 * not fire on someone else's. Four tests stack up, and a spawn must pass all of them:
 *
 * <ul>
 *   <li><b>ownership</b> — the game marks who a ground item belongs to, and another player's
 *       drop is {@code OWNERSHIP_OTHER}. {@code OWNERSHIP_GROUP} is accepted too, because a
 *       group ironman's own drops carry it;</li>
 *   <li><b>identity</b> — the item must be a notable crop, looked up through
 *       {@link Produce#getByItemID}. A dropped teleport tab is not harvest overflow;</li>
 *   <li><b>a full pack</b> — overflow only exists because nothing more fits, so a spawn
 *       while there are free slots is not overflow;</li>
 *   <li><b>at the player's feet</b> — overflow lands where you stand. A crop spawning
 *       across the patch is something else's doing.</li>
 * </ul>
 *
 * <p>Entries leave the way ground items do: the despawn event covers both "picked it up"
 * and "timed out", and a scene load clears the lot rather than trusting stale handles —
 * which loses the highlight in the rare case of a reload mid-errand, and can never point
 * at an item that is no longer there.
 *
 * <p>Written only on the client thread, read from the render thread; the published list is
 * swapped whole, the same pattern as {@code PlayerHouse}.
 */
@Singleton
public class DroppedProduce
{
	/** How far from the player a spawn can land and still be overflow, in tiles. */
	private static final int SPAWN_RADIUS = 3;

	/** One crop on the ground: where it fell and what it is. */
	@Value
	public static class Drop
	{
		TileItem item;
		WorldPoint location;
		Produce produce;
	}

	private final Client client;
	private final CarriedItems carried;

	/** Client-thread working list; {@link #published} is the copy other threads read. */
	private final List<Drop> drops = new ArrayList<>();

	/**
	 * Spawns that passed every test except the full pack, held until the tick ends.
	 *
	 * <p>The full-pack test and the spawn race: the ground item and the inventory update that
	 * filled the last slot arrive in the same server tick, and the spawn can be processed
	 * first — at which point {@code CarriedItems} still shows the free slot the harvest is
	 * about to take, and genuine overflow was being rejected for it. Reported from play as
	 * the pick-up highlight simply not appearing. So a spawn with free slots showing is not
	 * refused, only parked; when the tick finishes the inventory has caught up, and the pack
	 * being full then is the confirmation. Not full then means it really was not overflow.
	 */
	private final List<Drop> pending = new ArrayList<>();

	private volatile List<Drop> published = Collections.emptyList();

	@Inject
	DroppedProduce(Client client, CarriedItems carried)
	{
		this.client = client;
		this.carried = carried;
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		TileItem item = event.getItem();
		int ownership = item.getOwnership();
		if (ownership != TileItem.OWNERSHIP_SELF && ownership != TileItem.OWNERSHIP_GROUP)
		{
			return;
		}

		Produce produce = Produce.getByItemID(item.getId());
		if (produce == null || !produce.isNotable())
		{
			return;
		}

		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}
		WorldPoint location = event.getTile().getWorldLocation();
		if (location.distanceTo(player.getWorldLocation()) > SPAWN_RADIUS)
		{
			return;
		}

		if (carried.getFreeSlots() > 0)
		{
			// Maybe overflow the inventory has not caught up with, maybe not - the end of
			// the tick can tell the two apart, so the decision waits. See pending.
			pending.add(new Drop(item, location, produce));
			return;
		}

		drops.add(new Drop(item, location, produce));
		published = new ArrayList<>(drops);
	}

	@Subscribe
	public void onGameTick(net.runelite.api.events.GameTick event)
	{
		if (pending.isEmpty())
		{
			return;
		}

		// By now every container update of the tick has landed, so the free-slot count is the
		// truth the spawn-time check could only guess at.
		if (carried.getFreeSlots() == 0)
		{
			drops.addAll(pending);
			published = new ArrayList<>(drops);
		}
		pending.clear();
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		// By identity: the despawn carries the same TileItem the spawn did. Matching on id and
		// tile instead would remove a second stack of the same crop dropped on the same square.
		pending.removeIf(drop -> drop.getItem() == event.getItem());
		if (drops.removeIf(drop -> drop.getItem() == event.getItem()))
		{
			published = new ArrayList<>(drops);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// LOADING included: a scene reload replaces every TileItem, so the handles held here
		// stop matching despawn events and would linger forever. The items still on the ground
		// re-announce themselves as the new scene spawns them in.
		if (event.getGameState() == GameState.LOADING
			|| event.getGameState() == GameState.LOGIN_SCREEN
			|| event.getGameState() == GameState.HOPPING)
		{
			reset();
		}
	}

	/** The recorded overflow within reach of this point, nearest first. */
	public List<Drop> near(WorldPoint point, int radius)
	{
		if (point == null)
		{
			return Collections.emptyList();
		}

		List<Drop> found = new ArrayList<>();
		for (Drop drop : published)
		{
			if (drop.getLocation().distanceTo(point) <= radius)
			{
				found.add(drop);
			}
		}
		found.sort(java.util.Comparator.comparingInt(drop -> drop.getLocation().distanceTo(point)));
		return found;
	}

	public void reset()
	{
		drops.clear();
		pending.clear();
		published = Collections.emptyList();
	}
}
