package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;

/**
 * Seaweed spores appearing on the seabed while you are down there.
 *
 * <h2>Why this is worth interrupting somebody for</h2>
 *
 * A seaweed run is one of the few that can pay for itself indefinitely, and only if you leave
 * with as many spores as you planted. They are barely obtainable any other way, and the game
 * gives you almost no chance to notice one: they appear <b>individually for each player</b>,
 * one to three at a time, and are gone again in <b>thirty seconds</b>. Someone servicing two
 * patches with their eyes on the patch objects will simply never see the bubbles.
 *
 * <p>So this is deliberately not a run step. A spore is an interruption with a half-minute
 * clock on it, not a thing to be queued behind raking and planting — the guide keeps saying
 * whatever it was saying, and this speaks over the top and marks the tile.
 *
 * <h2>What it does not filter on, and why</h2>
 *
 * <b>Ownership.</b> {@code DroppedProduce} rejects anything not owned by the player, because a
 * farming patch is a public place and someone else's dropped limpwurts must not be highlighted.
 * The seabed is the opposite case: spores are spawned per player and the wiki notes that "all
 * players whose spores spawn on that tick will spawn at the same tile", so ownership flags are
 * not something to lean on. The item id plus the underwater region is a tight enough test on
 * its own — nothing else there is a seaweed spore.
 */

@Singleton
public class SeaweedSpores
{
	/**
	 * The Fossil Island underwater region, which is where the seaweed patches are.
	 *
	 * <p>Region-gated rather than global so a spore lying on a bank floor somewhere — from a
	 * drop, a death pile, another player — cannot set this off. It is also the only place they
	 * spawn, so the gate costs nothing.
	 */
	private static final int UNDERWATER_REGION = 15008;

	/** One spore on the seabed: the handle for despawn matching, and where it is. */
	@Value
	public static class Spore
	{
		TileItem item;
		WorldPoint location;
	}

	private final Client client;
	private final ChatMessageManager chat;
	private final DoogleMapsConfig config;

	private final List<Spore> spores = new ArrayList<>();

	/**
	 * The published copy, swapped wholesale so the overlay never reads a half-written list.
	 *
	 * <p>Same arrangement as {@code DroppedProduce}: this is written on the client thread and
	 * read while rendering, with no lock on either side.
	 */
	private volatile List<Spore> published = Collections.emptyList();

	@Inject
	SeaweedSpores(Client client, ChatMessageManager chat, DoogleMapsConfig config)
	{
		this.client = client;
		this.chat = chat;
		this.config = config;
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		TileItem item = event.getItem();
		// SEAWEED_SEED in the cache's naming - the game's own name for the item is "seaweed
		// spore", and Seed.SEAWEED already plants this same id.
		if (item.getId() != ItemID.SEAWEED_SEED || !underwater())
		{
			return;
		}

		WorldPoint location = event.getTile().getWorldLocation();
		spores.add(new Spore(item, location));
		published = new ArrayList<>(spores);

		announce();
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		// By identity, like the dropped-crop record: two spores can share a tile, and matching
		// on id and position would take the wrong one off the list.
		if (spores.removeIf(spore -> spore.getItem() == event.getItem()))
		{
			published = new ArrayList<>(spores);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// Spores "will instantly disappear upon logging out", and a scene reload replaces every
		// TileItem so the handles held here would stop matching their despawns and linger for
		// ever. Anything still on the seabed re-announces itself as the new scene spawns it.
		if (event.getGameState() == GameState.LOADING
			|| event.getGameState() == GameState.LOGIN_SCREEN
			|| event.getGameState() == GameState.HOPPING)
		{
			reset();
		}
	}

	/**
	 * Says one has appeared, once per spawn.
	 *
	 * <p>Per spawn rather than per tick or per batch: one to three can land together and each
	 * is a separate pickup, but the line names the count instead of repeating itself, because
	 * three identical messages in one tick is the shape of a spam bug.
	 */
	private void announce()
	{
		// Both switches, so the chatbox and the seabed mark agree. The highlight is drawn by
		// GuideOverlay, which draws nothing at all with guided mode off — a spoken notice with
		// no mark to look at would be half a feature, and worse, a line of chat from a plugin
		// the player has told to be quiet.
		if (!config.notifySeaweedSpores() || !config.guidedMode())
		{
			return;
		}

		int count = spores.size();
		chat.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(new ChatMessageBuilder()
				.append(java.awt.Color.CYAN, count == 1
					? "A seaweed spore has spawned nearby - grab it before it goes."
					: count + " seaweed spores are on the seabed - grab them before they go.")
				.build())
			.build());
	}

	/** The spores currently on the seabed, for the overlay. Never null, often empty. */
	public List<Spore> current()
	{
		return published;
	}

	/** Whether the player is in the underwater area at all. */
	private boolean underwater()
	{
		Player player = client.getLocalPlayer();
		return player != null
			&& player.getWorldLocation().getRegionID() == UNDERWATER_REGION;
	}

	/** Forgotten on logout and on a scene reload; see {@link #onGameStateChanged}. */
	public void reset()
	{
		spores.clear();
		published = Collections.emptyList();
	}
}
