package com.dooglemaps.guide;

import com.dooglemaps.bank.RouteItem;
import com.dooglemaps.state.PlayerHouse;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.when;

/**
 * A travel leg whose destination cannot be named still gets its travel hint.
 *
 * <h2>What it looked like</h2>
 *
 * {@code destinationStop} only names the trip when it can do so without guessing, and
 * {@code travelHint(null)} used to return null outright — so an ambiguous leg travelled with
 * "via Teleport to House" in the via-lines and <i>nothing</i> lit: no "Cast ..." line, no
 * spellbook outline, no item highlight. Reported from play, on a Catherby leg through the
 * house. The vehicle comes from the route's hops, not from the name; only the
 * destination-keyed extras (nexus row, jewellery category) stay quiet, and their overlays
 * null-guard.
 */
public class NamelessLegStillHintsTest
{
	@Test
	public void aSpellHintSurvivesANamelessDestination() throws Exception
	{
		RouteItem routeItem = Mockito.mock(RouteItem.class);
		when(routeItem.currentSpell()).thenReturn(TeleportSpell.TELEPORT_TO_HOUSE);
		PlayerHouse house = Mockito.mock(PlayerHouse.class);
		when(house.isInside()).thenReturn(false);

		TravelHint hint = travelHint(trackerWith(routeItem, house), null);

		assertNotNull("the vehicle is known from the hops, name or no name", hint);
		assertEquals("and it is the spell the route uses", true, hint.isSpell());
		assertNull("the nameless part stays honestly nameless", hint.getDestination());
	}

	/**
	 * A route that has gone quiet keeps the leg's name; one that arrives and matches nothing
	 * does not.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * The nexus row and the jewellery box line are matched <b>by destination</b>, so a nameless
	 * leg silently stops highlighting the thing you are meant to click. Reported from play as
	 * the Catherby teleport dropping out of the nexus list on the way into the house — and the
	 * log shows the leg was already nameless five seconds <i>before</i> the house was entered,
	 * which is what ruled the house out as the cause.
	 *
	 * <p>The latch that covers this was gated on being inside the house. The distinction that
	 * actually matters is not where you are standing, it is whether the router said anything:
	 * an answer that arrived and matched no stop might genuinely be a new destination, while
	 * <b>no answer at all</b> is no news. The route is wiped whenever a fresh one is asked for,
	 * discarded while instanced, and empty for a second or two around every teleport.
	 */
	@Test
	public void aQuietRouteKeepsTheLegsName() throws Exception
	{
		com.dooglemaps.route.RunPlanner planner =
			Mockito.mock(com.dooglemaps.route.RunPlanner.class);
		PlayerHouse house = Mockito.mock(PlayerHouse.class);
		when(house.isInside()).thenReturn(false);

		com.dooglemaps.route.RunStop catherby =
			Mockito.mock(com.dooglemaps.route.RunStop.class);
		com.dooglemaps.data.FarmRegion region =
			Mockito.mock(com.dooglemaps.data.FarmRegion.class);
		when(region.getRegionId()).thenReturn(11062);
		when(catherby.getRegion()).thenReturn(region);
		when(catherby.getName()).thenReturn("Catherby");

		java.util.List<com.dooglemaps.route.RunStop> remaining =
			java.util.Collections.singletonList(catherby);
		GuideTracker tracker = trackerWithPlanner(planner, house);

		// First, a route that names Catherby, which is what teaches the latch.
		when(planner.getCurrentDestinations()).thenReturn(java.util.Collections.singletonList(
			new net.runelite.api.coords.WorldPoint(2810, 3464, 0)));
		assertEquals(catherby, destinationStop(tracker, remaining));

		// Then the route is wiped, as it is on every re-ask and every teleport.
		when(planner.getCurrentDestinations())
			.thenReturn(java.util.Collections.emptyList());
		assertEquals("silence is not a change of destination",
			catherby, destinationStop(tracker, remaining));

		// But an answer that arrives and points somewhere unrelated is real news.
		when(planner.getCurrentDestinations()).thenReturn(java.util.Collections.singletonList(
			new net.runelite.api.coords.WorldPoint(3200, 3200, 0)));
		assertNull("a route that matches nothing is not the old leg",
			destinationStop(tracker, remaining));
	}

	private static com.dooglemaps.route.RunStop destinationStop(GuideTracker tracker,
		java.util.List<com.dooglemaps.route.RunStop> remaining) throws Exception
	{
		Method method = GuideTracker.class.getDeclaredMethod(
			"destinationStop", java.util.List.class);
		method.setAccessible(true);
		try
		{
			return (com.dooglemaps.route.RunStop) method.invoke(tracker, remaining);
		}
		catch (java.lang.reflect.InvocationTargetException e)
		{
			throw new AssertionError(e.getCause());
		}
	}

	private static GuideTracker trackerWithPlanner(com.dooglemaps.route.RunPlanner planner,
		PlayerHouse house) throws Exception
	{
		Constructor<?> constructor = GuideTracker.class.getDeclaredConstructors()[0];
		constructor.setAccessible(true);
		Class<?>[] types = constructor.getParameterTypes();
		Object[] args = new Object[types.length];
		for (int i = 0; i < types.length; i++)
		{
			if (types[i] == com.dooglemaps.route.RunPlanner.class)
			{
				args[i] = planner;
			}
			else if (types[i] == PlayerHouse.class)
			{
				args[i] = house;
			}
			else
			{
				args[i] = Mockito.mock(types[i]);
			}
		}
		return (GuideTracker) constructor.newInstance(args);
	}

	/**
	 * A destination that has just changed points at nothing until it has held for a tick.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"prompted to tele home via home tab, by the time I clicked it swapped to my ardougne
	 * cloak"</i> — the leg was re-decided at the instant Ardougne completed, which is exactly when
	 * the committed stop leaves the remaining list and the next one is chosen greedily. The
	 * highlight moved between the eye and the click.
	 *
	 * <p>Asserted on the gate rather than through a tick, because the gate is the whole rule: the
	 * hint, and therefore every travel highlight hanging off it — rows, spell, nexus, the
	 * jewellery sub-menu — is suppressed for exactly as long as the answer is still moving.
	 */
	@Test
	public void aDestinationIsNotPointedAtUntilItHasHeldForATick() throws Exception
	{
		GuideTracker tracker = trackerWith(Mockito.mock(RouteItem.class),
			Mockito.mock(PlayerHouse.class));

		assertFalse("the first sight of a destination is not yet an instruction",
			settled(tracker, "Ardougne"));
		assertTrue("agreeing with itself a tick later is", settled(tracker, "Ardougne"));

		assertFalse("and a change starts the wait again",
			settled(tracker, "Kandarin monastery"));
		assertTrue(settled(tracker, "Kandarin monastery"));
	}

	/**
	 * A nameless leg settles like any other, so the vehicle-from-the-hops case is unaffected.
	 *
	 * <p>This test class exists for that case; the gate must not be what takes it away.
	 */
	@Test
	public void aNamelessLegSettlesToo() throws Exception
	{
		GuideTracker tracker = trackerWith(Mockito.mock(RouteItem.class),
			Mockito.mock(PlayerHouse.class));

		assertFalse(settled(tracker, null));
		assertTrue("null agrees with null", settled(tracker, null));
	}

	/**
	 * The gate records on every tick, so "the previous tick" means what it says.
	 *
	 * <p>It was first written inside an {@code &&} after two other conditions, so on any tick with
	 * a step at the stop it was short-circuited away and never ran. The destination it compared
	 * against could then be minutes old, which made the answer meaningless in both directions —
	 * suppressing a hint that had been stable for ages, or passing one that had just changed.
	 */
	@Test
	public void theGateComparesAgainstTheTickBeforeAndNotWheneverItLastRan() throws Exception
	{
		GuideTracker tracker = trackerWith(Mockito.mock(RouteItem.class),
			Mockito.mock(PlayerHouse.class));

		assertFalse(settled(tracker, "Ardougne"));
		assertTrue(settled(tracker, "Ardougne"));

		// A tick where the destination is momentarily unknown still counts as a tick.
		assertFalse("null is a change like any other", settled(tracker, null));
		assertFalse("...and coming back is another one", settled(tracker, "Ardougne"));
		assertTrue(settled(tracker, "Ardougne"));
	}

	private static boolean settled(GuideTracker tracker, String destination) throws Exception
	{
		Method method = GuideTracker.class.getDeclaredMethod(
			"destinationSettled", String.class);
		method.setAccessible(true);
		return (boolean) method.invoke(tracker, destination);
	}

	private static TravelHint travelHint(GuideTracker tracker, String destination)
		throws Exception
	{
		Method method = GuideTracker.class.getDeclaredMethod("travelHint", String.class);
		method.setAccessible(true);
		try
		{
			return (TravelHint) method.invoke(tracker, destination);
		}
		catch (java.lang.reflect.InvocationTargetException e)
		{
			throw new AssertionError(e.getCause());
		}
	}

	/** A tracker with everything mocked except the two collaborators the hint reads first. */
	private static GuideTracker trackerWith(RouteItem routeItem, PlayerHouse house)
		throws Exception
	{
		Constructor<?> constructor = GuideTracker.class.getDeclaredConstructors()[0];
		constructor.setAccessible(true);

		Class<?>[] types = constructor.getParameterTypes();
		Object[] args = new Object[types.length];
		for (int i = 0; i < types.length; i++)
		{
			if (types[i] == RouteItem.class)
			{
				args[i] = routeItem;
			}
			else if (types[i] == PlayerHouse.class)
			{
				args[i] = house;
			}
			else
			{
				args[i] = Mockito.mock(types[i]);
			}
		}
		return (GuideTracker) constructor.newInstance(args);
	}
}
