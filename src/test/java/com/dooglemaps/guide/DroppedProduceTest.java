package com.dooglemaps.guide;

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
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Only our own harvest overflow, and nothing else on the floor.
 *
 * <p>The feature exists because a bulk harvest into a full pack — limpwurts are the reported
 * case — drops the excess at the player's feet, and after noting the guide walked on while the
 * crops despawned. But a farming patch is a public place, so the record has to be deaf to
 * everything that is not our overflow: another player's drop, our own unrelated drops, a crop
 * spawning across the patch. Each test here pins one of those refusals.
 */
public class DroppedProduceTest
{
	private static final WorldPoint FEET = new WorldPoint(3054, 3307, 0);

	@Test
	public void ourOverflowIsRecorded()
	{
		DroppedProduce drops = withPlayerAt(FEET, 0);

		drops.onItemSpawned(spawn(ItemID.LIMPWURT_ROOT, TileItem.OWNERSHIP_SELF, FEET));

		assertEquals(1, drops.near(FEET, 20).size());
		assertEquals("the record knows what fell, for the step's wording",
			"Limpwurt roots", drops.near(FEET, 20).get(0).getProduce().getContractName());
	}

	@Test
	public void anotherPlayersDropIsNot()
	{
		DroppedProduce drops = withPlayerAt(FEET, 0);

		drops.onItemSpawned(spawn(ItemID.LIMPWURT_ROOT, TileItem.OWNERSHIP_OTHER, FEET));
		drops.onItemSpawned(spawn(ItemID.LIMPWURT_ROOT, TileItem.OWNERSHIP_NONE, FEET));

		assertTrue("someone else's roots are someone else's business",
			drops.near(FEET, 20).isEmpty());
	}

	@Test
	public void aSpawnWithRoomInThePackIsNotOverflow()
	{
		DroppedProduce drops = withPlayerAt(FEET, 4);

		drops.onItemSpawned(spawn(ItemID.LIMPWURT_ROOT, TileItem.OWNERSHIP_SELF, FEET));

		assertTrue("overflow only exists because nothing more fits",
			drops.near(FEET, 20).isEmpty());
	}

	@Test
	public void somethingOtherThanACropIsNot()
	{
		DroppedProduce drops = withPlayerAt(FEET, 0);

		drops.onItemSpawned(spawn(ItemID.POH_TABLET_TELEPORTTOHOUSE,
			TileItem.OWNERSHIP_SELF, FEET));

		assertTrue("a dropped tablet is not harvest overflow", drops.near(FEET, 20).isEmpty());
	}

	@Test
	public void aCropSpawningAcrossThePatchIsNot()
	{
		DroppedProduce drops = withPlayerAt(FEET, 0);

		drops.onItemSpawned(spawn(ItemID.LIMPWURT_ROOT, TileItem.OWNERSHIP_SELF,
			new WorldPoint(FEET.getX() + 7, FEET.getY(), 0)));

		assertTrue("overflow lands where you stand", drops.near(FEET, 20).isEmpty());
	}

	@Test
	public void pickingItUpForgetsIt()
	{
		DroppedProduce drops = withPlayerAt(FEET, 0);

		ItemSpawned spawned = spawn(ItemID.LIMPWURT_ROOT, TileItem.OWNERSHIP_SELF, FEET);
		drops.onItemSpawned(spawned);
		drops.onItemDespawned(new ItemDespawned(spawned.getTile(), spawned.getItem()));

		assertTrue("despawn covers both picked up and timed out", drops.near(FEET, 20).isEmpty());
	}

	@Test
	public void aSceneLoadClearsTheRecord()
	{
		DroppedProduce drops = withPlayerAt(FEET, 0);
		drops.onItemSpawned(spawn(ItemID.LIMPWURT_ROOT, TileItem.OWNERSHIP_SELF, FEET));

		GameStateChanged loading = new GameStateChanged();
		loading.setGameState(GameState.LOADING);
		drops.onGameStateChanged(loading);

		assertTrue("a reload replaces every TileItem, so the handles held here went stale",
			drops.near(FEET, 20).isEmpty());
	}

	/**
	 * Overflow whose spawn beats the inventory update is still recorded.
	 *
	 * <p>The ground item and the container update that filled the last slot arrive in the same
	 * server tick, and the spawn can be processed first — so at spawn time the pack still shows
	 * the free slot the harvest is about to take. Reported from play as the pick-up highlight
	 * simply never appearing. The decision now waits for the end of the tick, when the
	 * inventory has caught up.
	 */
	@Test
	public void aSpawnRacingTheInventoryUpdateIsStillOverflow()
	{
		CarriedItems carried = Mockito.mock(CarriedItems.class);
		when(carried.getFreeSlots()).thenReturn(1);
		DroppedProduce drops = withPlayerAt(FEET, carried);

		drops.onItemSpawned(spawn(ItemID.LIMPWURT_ROOT, TileItem.OWNERSHIP_SELF, FEET));
		assertTrue("the decision waits for the tick to finish", drops.near(FEET, 20).isEmpty());

		when(carried.getFreeSlots()).thenReturn(0);
		drops.onGameTick(new net.runelite.api.events.GameTick());

		assertEquals("a full pack at the tick's end is the confirmation",
			1, drops.near(FEET, 20).size());
	}

	/** The parked spawn is a one-tick question: room at the tick's end means it was not overflow. */
	@Test
	public void aParkedSpawnWithRoomAtTheTicksEndIsDiscarded()
	{
		CarriedItems carried = Mockito.mock(CarriedItems.class);
		when(carried.getFreeSlots()).thenReturn(1);
		DroppedProduce drops = withPlayerAt(FEET, carried);

		drops.onItemSpawned(spawn(ItemID.LIMPWURT_ROOT, TileItem.OWNERSHIP_SELF, FEET));
		drops.onGameTick(new net.runelite.api.events.GameTick());

		when(carried.getFreeSlots()).thenReturn(0);
		drops.onGameTick(new net.runelite.api.events.GameTick());

		assertTrue("the question was answered when the spawn's own tick ended",
			drops.near(FEET, 20).isEmpty());
	}

	private static DroppedProduce withPlayerAt(WorldPoint point, CarriedItems carried)
	{
		Player player = Mockito.mock(Player.class);
		when(player.getWorldLocation()).thenReturn(point);
		Client client = Mockito.mock(Client.class);
		when(client.getLocalPlayer()).thenReturn(player);

		return construct(DroppedProduce.class, client, carried);
	}

	private static DroppedProduce withPlayerAt(WorldPoint point, int freeSlots)
	{
		Player player = Mockito.mock(Player.class);
		when(player.getWorldLocation()).thenReturn(point);
		Client client = Mockito.mock(Client.class);
		when(client.getLocalPlayer()).thenReturn(player);

		CarriedItems carried = Mockito.mock(CarriedItems.class);
		when(carried.getFreeSlots()).thenReturn(freeSlots);

		return construct(DroppedProduce.class, client, carried);
	}

	private static ItemSpawned spawn(int itemId, int ownership, WorldPoint where)
	{
		TileItem item = Mockito.mock(TileItem.class);
		when(item.getId()).thenReturn(itemId);
		when(item.getOwnership()).thenReturn(ownership);

		Tile tile = Mockito.mock(Tile.class);
		when(tile.getWorldLocation()).thenReturn(where);

		return new ItemSpawned(tile, item);
	}
}
