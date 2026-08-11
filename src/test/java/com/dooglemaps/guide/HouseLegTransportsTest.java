package com.dooglemaps.guide;

import com.dooglemaps.route.RunPlanner;
import com.dooglemaps.state.PlayerHouse;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * The house keeps the last hop list that planned through it.
 *
 * <h2>The Catherby exit-portal report</h2>
 *
 * A house is an instance, and arriving is exactly when the router's answer goes away: it
 * cannot path from inside, so "Teleport to House, Enter Catherby Portal" emptied the moment
 * the teleport landed. With no hops to match and the leg unnamed — a many-stop run does not
 * know which stop the router picked — every question the overlay asks came back empty, and
 * the exit portal lit while the player stood beside the nexus their own route had chosen.
 * The session logs show it exactly: outdoor sightings of the Catherby hops, and not one
 * in-house match or miss for them. Reported from play, repeatedly.
 */
public class HouseLegTransportsTest
{
	private static final List<String> CATHERBY_LEG = Arrays.asList(
		"Teleport to House", "Enter Catherby Portal - Catherby Portal");

	private GuideTracker tracker;
	private RunPlanner planner;
	private PlayerHouse house;

	@Before
	public void setUp() throws Exception
	{
		planner = Mockito.mock(RunPlanner.class);
		house = Mockito.mock(PlayerHouse.class);
		when(planner.isActive()).thenReturn(true);
		when(planner.getCurrentTransports()).thenReturn(Collections.emptyList());

		tracker = trackerWith();
	}

	/** The reported sequence: hops seen outside, cleared on arrival, still answered inside. */
	@Test
	public void theHopsThatEnteredTheHouseAnswerWhileTheLiveListIsEmpty()
	{
		when(planner.getCurrentTransports()).thenReturn(CATHERBY_LEG);
		assertEquals("live hops pass through untouched", CATHERBY_LEG,
			tracker.liveTransports());

		// The teleport lands: the router clears, and the player is now inside.
		when(planner.getCurrentTransports()).thenReturn(Collections.emptyList());
		when(house.isInside()).thenReturn(true);

		assertEquals("the house still knows what the route planned through it",
			CATHERBY_LEG, tracker.liveTransports());
	}

	/** Outside the house an empty answer is an empty answer — nothing haunts the overworld. */
	@Test
	public void theMemoryOnlyAnswersInsideTheHouse()
	{
		when(planner.getCurrentTransports()).thenReturn(CATHERBY_LEG);
		tracker.liveTransports();

		when(planner.getCurrentTransports()).thenReturn(Collections.emptyList());
		when(house.isInside()).thenReturn(false);

		assertTrue(tracker.liveTransports().isEmpty());
	}

	/** A fresh plan that does not mention the house clears what the old one left behind. */
	@Test
	public void aPlanWithoutTheHouseClearsTheMemory()
	{
		when(planner.getCurrentTransports()).thenReturn(CATHERBY_LEG);
		tracker.liveTransports();

		when(planner.getCurrentTransports())
			.thenReturn(Collections.singletonList("Ardougne cloak: Ardougne Farm"));
		tracker.liveTransports();

		when(planner.getCurrentTransports()).thenReturn(Collections.emptyList());
		when(house.isInside()).thenReturn(true);

		assertTrue("the cloak plan never went through the house",
			tracker.liveTransports().isEmpty());
	}

	/** A stopped run holds nothing over the house. */
	@Test
	public void anInactiveRunRemembersNothing()
	{
		when(planner.getCurrentTransports()).thenReturn(CATHERBY_LEG);
		tracker.liveTransports();

		when(planner.isActive()).thenReturn(false);
		when(planner.getCurrentTransports()).thenReturn(Collections.emptyList());
		tracker.liveTransports();

		when(planner.isActive()).thenReturn(true);
		when(house.isInside()).thenReturn(true);

		assertTrue(tracker.liveTransports().isEmpty());
	}

	/** A tracker with this test's planner and house, everything else auto-mocked. */
	private GuideTracker trackerWith() throws Exception
	{
		Constructor<?> constructor = GuideTracker.class.getDeclaredConstructors()[0];
		constructor.setAccessible(true);

		Class<?>[] types = constructor.getParameterTypes();
		Object[] args = new Object[types.length];
		for (int i = 0; i < types.length; i++)
		{
			Class<?> type = types[i];
			if (type == RunPlanner.class)
			{
				args[i] = planner;
			}
			else if (type == PlayerHouse.class)
			{
				args[i] = house;
			}
			else
			{
				args[i] = Mockito.mock(type);
			}
		}
		return (GuideTracker) constructor.newInstance(args);
	}
}
