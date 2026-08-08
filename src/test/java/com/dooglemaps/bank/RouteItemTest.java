package com.dooglemaps.bank;

import com.dooglemaps.data.ItemNames;
import com.dooglemaps.guide.CarriedItems;
import com.dooglemaps.route.ShortestPathIntegration;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Resolving Shortest Path's transport strings to an item the player owns.
 *
 * <p>The router posts display text, not item ids — so the best travel advice in the client
 * arrives as words, and turning "Games necklace (Barbarian Outpost)" into the
 * "Games necklace(8)" in the bank is a string match with both sides' suffixes cut off. The
 * match being best-effort is the design: when nothing resolves, the feature quietly does not
 * appear, which is the failure a convenience is allowed to have.
 */
public class RouteItemTest
{
	private static final int NECKLACE = 3853;
	private static final int HOUSE_TAB = 8013;

	@Test
	public void theFirstItemShapedTransportWins() throws Exception
	{
		RouteItem route = routeWith(
			Arrays.asList("Fairy ring BIQ", "Games necklace (Barbarian Outpost)"),
			names(NECKLACE, "Games necklace(8)", HOUSE_TAB, "Teleport to house"),
			NECKLACE, HOUSE_TAB);

		assertEquals("the ring is not an item; the necklace is the next thing to click",
			NECKLACE, route.currentItemId());
	}

	/** Charge suffixes on the bank side and destination suffixes on the route side both cut. */
	@Test
	public void suffixesOnEitherSideDoNotBreakTheMatch() throws Exception
	{
		RouteItem route = routeWith(
			Collections.singletonList("Teleport to house"),
			names(HOUSE_TAB, "Teleport to house"),
			HOUSE_TAB);

		assertEquals(HOUSE_TAB, route.currentItemId());
		assertEquals("Teleport to house", route.currentName());
	}

	/** A route using nothing the player owns resolves to nothing, silently. */
	@Test
	public void noMatchMeansNoItemAndNoFuss() throws Exception
	{
		RouteItem route = routeWith(
			Arrays.asList("Spirit tree", "Fairy ring CKR"),
			names(NECKLACE, "Games necklace(8)"),
			NECKLACE);

		assertEquals(-1, route.currentItemId());
		assertNull(route.currentName());
	}

	/** Short strings - ring codes and the like - never match anything. */
	@Test
	public void shortStringsAreNeverTreatedAsItemNames() throws Exception
	{
		RouteItem route = routeWith(
			Collections.singletonList("BIQ"),
			names(NECKLACE, "BIQ something"),
			NECKLACE);

		assertEquals(-1, route.currentItemId());
	}

	// ------------------------------------------------------------------- helpers

	private static Map<Integer, String> names(Object... idThenName)
	{
		Map<Integer, String> names = new HashMap<>();
		for (int i = 0; i < idThenName.length; i += 2)
		{
			names.put((Integer) idThenName[i], (String) idThenName[i + 1]);
		}
		return names;
	}

	private static RouteItem routeWith(java.util.List<String> transports,
		Map<Integer, String> names, int... carriedIds) throws Exception
	{
		ShortestPathIntegration router = Mockito.mock(ShortestPathIntegration.class);
		when(router.getCurrentTransports()).thenReturn(transports);

		ItemNames itemNames = Mockito.mock(ItemNames.class);
		when(itemNames.get(anyInt(), any())).thenAnswer(i ->
			names.getOrDefault(i.<Integer>getArgument(0), i.getArgument(1)));

		BankContents bank = Mockito.mock(BankContents.class);
		when(bank.getItemIds()).thenReturn(new java.util.LinkedHashSet<>());

		CarriedItems carried = Mockito.mock(CarriedItems.class);
		java.util.Set<Integer> ids = new java.util.LinkedHashSet<>();
		for (int id : carriedIds)
		{
			ids.add(id);
		}
		when(carried.getItemIds()).thenReturn(ids);

		Client client = Mockito.mock(Client.class);

		Constructor<?> constructor = RouteItem.class.getDeclaredConstructors()[0];
		constructor.setAccessible(true);
		return (RouteItem) constructor.newInstance(router, itemNames, bank, carried, client);
	}
}
