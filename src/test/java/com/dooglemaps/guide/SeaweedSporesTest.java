package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Calling out a seaweed spore, which is a pickup with a thirty-second clock on it.
 *
 * <p>They appear per player, one to three at a time, and are barely obtainable any other way —
 * so leaving with as many as you planted is what keeps a seaweed run self-sustaining, and a
 * player watching the patch objects will never see the bubbles. Everything here is about not
 * crying wolf: the wrong item, the wrong place, or a stale handle after a scene reload.
 */
public class SeaweedSporesTest
{
	/** The Fossil Island underwater region, where the seaweed patches are. */
	private static final int UNDERWATER = 15008;

	/** Somewhere else entirely — a bank floor, where a dropped spore proves nothing. */
	private static final int ELSEWHERE = 12850;

	private Client client;
	private ChatMessageManager chat;
	private SeaweedSpores spores;

	@Before
	public void setUp()
	{
		client = Mockito.mock(Client.class);
		chat = Mockito.mock(ChatMessageManager.class);

		DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class);
		when(config.notifySeaweedSpores()).thenReturn(true);
		when(config.guidedMode()).thenReturn(true);

		spores = construct(SeaweedSpores.class, client, chat, config);
		standIn(UNDERWATER);
	}

	@Test
	public void aSporeUnderwaterIsAnnouncedAndRemembered()
	{
		spawn(ItemID.SEAWEED_SEED);

		assertEquals(1, spores.current().size());
		verify(chat).queue(Mockito.any(QueuedMessage.class));
	}

	/** The same item on a bank floor is somebody's dropped junk, not a spawn. */
	@Test
	public void aSporeSomewhereElseIsIgnored()
	{
		standIn(ELSEWHERE);
		spawn(ItemID.SEAWEED_SEED);

		assertTrue(spores.current().isEmpty());
		verify(chat, never()).queue(Mockito.any(QueuedMessage.class));
	}

	/** Nothing else on the seabed is a spore. */
	@Test
	public void anotherItemIsIgnored()
	{
		spawn(ItemID.GIANT_SEAWEED);

		assertTrue(spores.current().isEmpty());
		verify(chat, never()).queue(Mockito.any(QueuedMessage.class));
	}

	/** Picked up or timed out, it stops being marked — matched by handle, not by tile. */
	@Test
	public void aDespawnedSporeIsForgotten()
	{
		TileItem item = spawn(ItemID.SEAWEED_SEED);
		assertEquals(1, spores.current().size());

		spores.onItemDespawned(new ItemDespawned(Mockito.mock(Tile.class), item));

		assertTrue(spores.current().isEmpty());
	}

	/**
	 * A scene reload drops everything, because the handles held here stop matching.
	 *
	 * <p>Spores also "instantly disappear upon logging out", so keeping them would mark bare
	 * seabed until something else cleared the list.
	 */
	@Test
	public void aSceneReloadClearsTheList()
	{
		spawn(ItemID.SEAWEED_SEED);

		spores.onGameStateChanged(new GameStateChanged());
		assertEquals("an unset state changes nothing", 1, spores.current().size());

		GameStateChanged loading = new GameStateChanged();
		loading.setGameState(GameState.LOADING);
		spores.onGameStateChanged(loading);

		assertTrue(spores.current().isEmpty());
	}

	/** Several at once are one message naming the count, not three identical lines. */
	@Test
	public void aBatchIsAnnouncedOnceEach()
	{
		spawn(ItemID.SEAWEED_SEED);
		spawn(ItemID.SEAWEED_SEED);

		assertEquals(2, spores.current().size());
		verify(chat, Mockito.times(2)).queue(Mockito.any(QueuedMessage.class));
	}

	/** Switched off, it watches silently and marks nothing. */
	@Test
	public void theNoticeCanBeTurnedOff()
	{
		DoogleMapsConfig off = Mockito.mock(DoogleMapsConfig.class);
		when(off.notifySeaweedSpores()).thenReturn(false);
		when(off.guidedMode()).thenReturn(true);
		spores = construct(SeaweedSpores.class, client, chat, off);

		spawn(ItemID.SEAWEED_SEED);

		verify(chat, never()).queue(Mockito.any(QueuedMessage.class));
	}

	/**
	 * Guided mode off means silence too, not a notice with nothing to look at.
	 *
	 * <p>The seabed mark is drawn by GuideOverlay, which renders nothing with guided mode off —
	 * so speaking anyway would be half a feature and a line of chat from a plugin the player
	 * has told to be quiet.
	 */
	@Test
	public void guidedModeOffSilencesItToo()
	{
		DoogleMapsConfig unguided = Mockito.mock(DoogleMapsConfig.class);
		when(unguided.notifySeaweedSpores()).thenReturn(true);
		when(unguided.guidedMode()).thenReturn(false);
		spores = construct(SeaweedSpores.class, client, chat, unguided);

		spawn(ItemID.SEAWEED_SEED);

		verify(chat, never()).queue(Mockito.any(QueuedMessage.class));
	}

	private void standIn(int regionId)
	{
		// Region id is the high byte of x and the low byte of y; build a point inside it.
		here = new WorldPoint((regionId >> 8) << 6, (regionId & 0xFF) << 6, 0);

		Player player = Mockito.mock(Player.class);
		when(player.getWorldLocation()).thenReturn(here);
		when(client.getLocalPlayer()).thenReturn(player);
	}

	/** Where the player is standing, kept out of the stubs so none nests inside another. */
	private WorldPoint here;

	private TileItem spawn(int itemId)
	{
		TileItem item = Mockito.mock(TileItem.class);
		when(item.getId()).thenReturn(itemId);

		Tile tile = Mockito.mock(Tile.class);
		when(tile.getWorldLocation()).thenReturn(here);

		spores.onItemSpawned(new ItemSpawned(tile, item));
		return item;
	}
}
