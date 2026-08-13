package com.dooglemaps.state;

import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * House furniture is dropped when the scene is, not a tick later.
 *
 * <h2>The reported dead end</h2>
 *
 * The scan runs once a {@link GameTick} and the overlay draws every frame, so between a scene
 * reload and the next tick there were up to ~30 frames holding {@link TileObject}s whose scene
 * data the client had already freed. Asking one for its clickbox threw a
 * {@code NullPointerException} <i>inside</i> the client, and since
 * {@code OverlayRenderer.safeRender} catches per overlay rather than per object, the route
 * object and every patch ahead went dark for that frame as well. Twenty-two of twenty-five
 * traces in a week of logs came in through the house teleports, in bursts, on entry and exit.
 *
 * <p>The tick-shaped hole is the whole bug: nothing here is wrong about <i>which</i> furniture
 * it found, only about how long it kept saying so.
 */
public class HouseFurnitureGoesWithTheSceneTest
{
	/** Ornate jewellery box — matched by id, so no {@code ObjectComposition} is needed. */
	private static final int ORNATE_JEWELLERY_BOX = 37520;

	private PlayerHouse house;

	@Before
	public void setUp()
	{
		Client client = Mockito.mock(Client.class);
		WorldView view = Mockito.mock(WorldView.class);
		Scene scene = Mockito.mock(Scene.class);
		Tile tile = Mockito.mock(Tile.class);
		GameObject box = Mockito.mock(GameObject.class);

		when(box.getId()).thenReturn(ORNATE_JEWELLERY_BOX);
		when(box.getHash()).thenReturn(1L);
		when(tile.getGameObjects()).thenReturn(new GameObject[]{box});
		when(scene.getTiles()).thenReturn(new Tile[][][]{{{tile}}});
		when(view.getScene()).thenReturn(scene);
		when(view.getPlane()).thenReturn(0);
		when(client.getTopLevelWorldView()).thenReturn(view);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

		house = new PlayerHouse(client);
	}

	/** A tick inside the house finds the box, which is the precondition for the rest. */
	@Test
	public void aTickInsideTheHouseFindsTheFurniture()
	{
		house.onGameTick(new GameTick());

		List<TileObject> found = house.matchingFurniture(name -> name.contains("jewellery"));
		assertEquals("the scan found the box", 1, found.size());
		assertTrue("and reads the house as entered", house.isInside());
	}

	/**
	 * The fix: {@code LOADING} empties the list on its own, with no tick in between.
	 *
	 * <p>Deliberately asserted without calling {@link PlayerHouse#onGameTick} afterwards — a
	 * tick would have cleared it anyway, and clearing it on the tick is exactly the behaviour
	 * that left ~30 frames of freed objects to draw.
	 */
	@Test
	public void aSceneReloadEmptiesTheFurnitureWithoutWaitingForATick()
	{
		house.onGameTick(new GameTick());
		assertFalse(house.matchingFurniture(name -> name.contains("jewellery")).isEmpty());

		house.onGameStateChanged(stateChange(GameState.LOADING));

		assertTrue("nothing stale is handed to the overlay",
			house.matchingFurniture(name -> name.contains("jewellery")).isEmpty());
		assertFalse("and the house question goes with it", house.isInside());
	}

	/** Every state that frees the scene, not just the reload — logging out and hopping too. */
	@Test
	public void everyStateThatIsNotLoggedInDropsTheFurniture()
	{
		for (GameState state : GameState.values())
		{
			if (state == GameState.LOGGED_IN)
			{
				continue;
			}

			house.onGameTick(new GameTick());
			assertFalse("precondition for " + state,
				house.matchingFurniture(name -> name.contains("jewellery")).isEmpty());

			house.onGameStateChanged(stateChange(state));
			assertTrue(state + " should have dropped the furniture",
				house.matchingFurniture(name -> name.contains("jewellery")).isEmpty());
		}
	}

	/**
	 * And {@code LOGGED_IN} does not, which is the state the scan itself arrives in.
	 *
	 * <p>Guards the obvious wrong fix — clearing on every state change — which would blank the
	 * furniture on the tick it was found and highlight nothing at all.
	 */
	@Test
	public void arrivingLoggedInLeavesTheScanAlone()
	{
		house.onGameTick(new GameTick());

		house.onGameStateChanged(stateChange(GameState.LOGGED_IN));

		assertFalse("the furniture the scan just found survives",
			house.matchingFurniture(name -> name.contains("jewellery")).isEmpty());
		assertTrue(house.isInside());
	}

	private static GameStateChanged stateChange(GameState state)
	{
		GameStateChanged event = new GameStateChanged();
		event.setGameState(state);
		return event;
	}
}
