package com.dooglemaps.guide;

import com.dooglemaps.bank.RouteItem;
import com.dooglemaps.state.PlayerHouse;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
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
