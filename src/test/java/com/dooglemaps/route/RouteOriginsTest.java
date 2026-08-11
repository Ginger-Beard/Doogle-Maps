package com.dooglemaps.route;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.events.PluginMessage;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Covers the one fact read from the transports message's origin list: whether the route
 * departs from inside the player-owned house.
 *
 * <p>Shortest Path models the POH as a real place — every house-furniture transport is
 * anchored inside one reserved map area — so a hop originating there is house furniture by
 * the router's own word, whatever the hop is called. This replaced wording heuristics as the
 * exit-portal decision after two of them failed in play (the nexus, then the fairy ring).
 */
public class RouteOriginsTest
{
	/** The bounds are Shortest Path's own {@code isInsidePoh}, copied exactly. */
	@Test
	public void thePohAreaMatchesShortestPathsBounds()
	{
		assertTrue("the portal room anchor",
			ShortestPathIntegration.isPohArea(new WorldPoint(1928, 5731, 0)));
		assertTrue("the canonical landing tile",
			ShortestPathIntegration.isPohArea(new WorldPoint(1923, 5709, 0)));
		assertTrue("the mounted-items anchor",
			ShortestPathIntegration.isPohArea(new WorldPoint(1960, 5750, 0)));
		assertTrue("the corners are inclusive",
			ShortestPathIntegration.isPohArea(new WorldPoint(1856, 5696, 0)));
		assertFalse("the overworld is not",
			ShortestPathIntegration.isPohArea(new WorldPoint(3000, 3300, 0)));
		assertFalse("one tile past the edge is not",
			ShortestPathIntegration.isPohArea(new WorldPoint(2048, 5731, 0)));
		assertFalse(ShortestPathIntegration.isPohArea(null));
	}

	/** An origin inside the house marks the route; a clear unmarks it. */
	@Test
	public void aHopDepartingTheHouseMarksTheRoute() throws Exception
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

		ShortestPathIntegration integration =
			construct(ShortestPathIntegration.class, eventBus, clientThread);

		// A route of ours has to be live, or the transports message is not ours to read.
		integration.setTargets(java.util.Collections.singleton(new WorldPoint(1400, 2900, 0)));

		Map<String, Object> data = new HashMap<>();
		data.put("origin", java.util.Arrays.asList(
			new WorldPoint(3222, 3218, 0),    // an overworld hop first, as real plans have
			new WorldPoint(1928, 5731, 0)));  // then the house furniture
		data.put("destination", java.util.Arrays.asList(
			new WorldPoint(1923, 5709, 0),
			new WorldPoint(1400, 2900, 0)));
		integration.onPluginMessage(new PluginMessage("shortestpath", "transports", data));

		assertTrue("a hop departs the POH, so the route goes through the house",
			integration.isRouteDepartsPoh());

		integration.clear();
		assertFalse("a cleared route makes no claim", integration.isRouteDepartsPoh());
	}

	/** A plan that never touches the house must not claim it. */
	@Test
	public void anOverworldOnlyPlanDoesNotClaimTheHouse() throws Exception
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

		ShortestPathIntegration integration =
			construct(ShortestPathIntegration.class, eventBus, clientThread);
		integration.setTargets(java.util.Collections.singleton(new WorldPoint(1400, 2900, 0)));

		Map<String, Object> data = new HashMap<>();
		data.put("origin", java.util.Collections.singletonList(new WorldPoint(3222, 3218, 0)));
		data.put("destination",
			java.util.Collections.singletonList(new WorldPoint(1400, 2900, 0)));
		integration.onPluginMessage(new PluginMessage("shortestpath", "transports", data));

		assertFalse(integration.isRouteDepartsPoh());
	}
}
