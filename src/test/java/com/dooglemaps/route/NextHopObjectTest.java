package com.dooglemaps.route;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.events.PluginMessage;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Which hop of the drawn route the scene outline should point at, and which of Shortest
 * Path's replies get read while the player is instanced.
 *
 * <h2>Agility shortcuts were never outlined</h2>
 *
 * The overlay lit the object of the route's <b>first</b> hop. A shortcut is almost never
 * first: the plan reads "Falador teleport, then climb the crumbling wall", and once the
 * teleport has landed the message is not re-sent unless the player leaves the drawn line.
 * The route has to move along with the player.
 *
 * <h2>The lost Catherby leg</h2>
 *
 * Shortest Path runs a pathfinder's completion callback even when a newer request cancelled
 * it, and that callback posts whatever the newest pathfinder holds at the moment — usually
 * nothing yet. So an in-house reroute a tick behind another request saw an empty message
 * first, took it as the answer, and dropped the real one as an instance recompute. The
 * panel lost its destination and its nexus row; the nexus stayed lit off the old route.
 */
public class NextHopObjectTest
{
	private static final WorldPoint LUMBRIDGE = new WorldPoint(3222, 3218, 0);
	private static final WorldPoint FALADOR_LANDING = new WorldPoint(2965, 3380, 0);
	private static final WorldPoint WALL_EAST = new WorldPoint(2936, 3355, 0);
	private static final WorldPoint WALL_WEST = new WorldPoint(2934, 3355, 0);
	private static final WorldPoint TAVERLEY_PATCH = new WorldPoint(2900, 3430, 0);

	private ShortestPathIntegration integration;
	private boolean instanced;

	@Before
	public void setUp() throws Exception
	{
		net.runelite.client.eventbus.EventBus eventBus =
			Mockito.mock(net.runelite.client.eventbus.EventBus.class);
		net.runelite.client.callback.ClientThread clientThread =
			Mockito.mock(net.runelite.client.callback.ClientThread.class);
		doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		integration = construct(ShortestPathIntegration.class, eventBus, clientThread);
		integration.setInstanceKnowledge(() -> instanced);
		integration.setTargets(java.util.Collections.singleton(TAVERLEY_PATCH));
	}

	/** Teleport, then the wall: the wall becomes the object once the teleport has landed. */
	@Test
	public void theNextObjectMovesAlongWithThePlayer()
	{
		integration.onPluginMessage(teleportThenWall());

		assertNull("the first hop is a spell, so nothing in the scene is its object",
			integration.getNextTransportObject());

		integration.noteProgress(LUMBRIDGE);
		assertNull("still standing at the start", integration.getNextTransportObject());

		integration.noteProgress(new WorldPoint(2966, 3381, 0));   // landed, a tile off
		assertEquals("the teleport is taken; the wall is next",
			"Climb-over Crumbling wall", integration.getNextTransportObject());

		// Standing at the near side of the wall is within a few tiles of its far side, and
		// must NOT count as having crossed it - that is exactly when the outline is wanted.
		integration.noteProgress(WALL_EAST);
		assertEquals("not crossed yet", "Climb-over Crumbling wall",
			integration.getNextTransportObject());

		integration.noteProgress(WALL_WEST);
		assertNull("crossed; nothing left to click", integration.getNextTransportObject());
	}

	/** Progress never goes backwards on its own; a new answer starts it over. */
	@Test
	public void aFreshAnswerStartsFromTheFirstHopAgain()
	{
		integration.onPluginMessage(teleportThenWall());
		integration.noteProgress(FALADOR_LANDING);
		integration.noteProgress(LUMBRIDGE);   // wandered back: still past the teleport
		assertEquals("Climb-over Crumbling wall", integration.getNextTransportObject());

		integration.setTargets(java.util.Collections.singleton(TAVERLEY_PATCH));
		integration.onPluginMessage(teleportThenWall());
		assertNull("a new route, read from its first hop", integration.getNextTransportObject());
	}

	/** Duplicate rows for one edge - several Transport objects share it - pass together. */
	@Test
	public void duplicateRowsForOneEdgePassAsOne()
	{
		Map<String, Object> data = new HashMap<>();
		data.put("origin", Arrays.asList(WALL_EAST, WALL_EAST, WALL_WEST));
		data.put("destination", Arrays.asList(WALL_WEST, WALL_WEST, TAVERLEY_PATCH));
		data.put("objectInfo", Arrays.asList("Climb-over Crumbling wall 24222",
			"Climb-over Crumbling wall 24223", "Open Gate 100"));
		data.put("displayInfo", Arrays.asList(null, null, null));
		integration.onPluginMessage(new PluginMessage("shortestpath", "transports", data));

		integration.noteProgress(WALL_WEST);
		assertEquals("Open Gate", integration.getNextTransportObject());
	}

	/** The reported sequence: junk first, the real answer a moment later, player instanced. */
	@Test
	public void aLaterReplyInsideTheWindowIsReadWhileInstanced()
	{
		instanced = true;
		integration.setTargets(java.util.Collections.singleton(TAVERLEY_PATCH),
			false, LUMBRIDGE);   // an explicit start, as the front-door reroute posts

		integration.onPluginMessage(new PluginMessage("shortestpath", "transports",
			new HashMap<>()));   // the cancelled pathfinder's empty callback
		assertTrue(integration.getCurrentTransports().isEmpty());
		assertFalse(integration.isAwaitingRoute());

		integration.onPluginMessage(teleportThenWall());
		assertEquals("the real answer, arriving second, is the route",
			Arrays.asList("Falador Teleport"), integration.getCurrentTransports());
		assertFalse(integration.getCurrentDestinations().isEmpty());
	}

	/** Past the window, an instanced player's unsolicited messages are ignored as before. */
	@Test
	public void aStaleInstancedRecomputeIsStillIgnored() throws Exception
	{
		instanced = true;
		integration.setTargets(java.util.Collections.singleton(TAVERLEY_PATCH),
			false, LUMBRIDGE);
		integration.onPluginMessage(teleportThenWall());
		assertEquals(Arrays.asList("Falador Teleport"), integration.getCurrentTransports());

		java.lang.reflect.Field asked =
			ShortestPathIntegration.class.getDeclaredField("requestedAtMillis");
		asked.setAccessible(true);
		asked.setLong(integration, System.currentTimeMillis() - 60_000);

		Map<String, Object> data = new HashMap<>();
		data.put("origin", Arrays.asList(new WorldPoint(1928, 5731, 0)));
		data.put("destination", Arrays.asList(new WorldPoint(2801, 3449, 0)));
		data.put("objectInfo", Arrays.asList("Enter Catherby Portal 33432"));
		data.put("displayInfo", Arrays.asList("Catherby Portal"));
		integration.onPluginMessage(new PluginMessage("shortestpath", "transports", data));

		assertEquals("a minute-old request's recompute is not read",
			Arrays.asList("Falador Teleport"), integration.getCurrentTransports());
	}

	/** Falador teleport from Lumbridge, then the crumbling wall west of the city. */
	private static PluginMessage teleportThenWall()
	{
		Map<String, Object> data = new HashMap<>();
		data.put("origin", Arrays.asList(LUMBRIDGE, WALL_EAST));
		data.put("destination", Arrays.asList(FALADOR_LANDING, WALL_WEST));
		data.put("objectInfo", Arrays.asList(null, "Climb-over Crumbling wall 24222"));
		data.put("displayInfo", Arrays.asList("Falador Teleport", null));
		return new PluginMessage("shortestpath", "transports", data);
	}
}
