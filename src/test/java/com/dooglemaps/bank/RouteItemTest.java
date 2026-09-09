package com.dooglemaps.bank;

import com.dooglemaps.data.ItemNames;
import com.dooglemaps.guide.CarriedItems;
import com.dooglemaps.route.ShortestPathIntegration;
import com.dooglemaps.state.DailyTeleports;
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
	private static final int CLOAK = 13123;
	private static final int PENDANT = 29893;
	/** The pendant's other form, which is what the remembered bank was holding. */
	private static final int PENDANT_BANKED_FORM = 29894;

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

	/**
	 * A spell-shaped hop resolves to the spell, so the guide has something to point at.
	 *
	 * <p>The reported gap: "Route: Camelot teleport" named the router's pick while nothing on
	 * screen showed where to click, because a spell matches no owned item and fell through.
	 */
	@Test
	public void aSpellShapedTransportResolvesToTheSpell() throws Exception
	{
		RouteItem route = routeWith(
			Arrays.asList("Fairy ring BIQ", "Camelot Teleport"),
			names(NECKLACE, "Games necklace(8)"),
			NECKLACE);

		assertEquals(com.dooglemaps.guide.TeleportSpell.CAMELOT, route.currentSpell());
		assertEquals("Camelot Teleport", route.currentName());
		assertEquals("a spell is not an item", -1, route.currentItemId());
	}

	/** A carried tablet with the spell's name wins: one click where the spell is two. */
	@Test
	public void anOwnedTabletBeatsTheSpellReadingOfTheSameWords() throws Exception
	{
		RouteItem route = routeWith(
			Collections.singletonList("Camelot Teleport"),
			names(HOUSE_TAB, "Camelot teleport"),
			HOUSE_TAB);

		assertEquals(HOUSE_TAB, route.currentItemId());
		assertNull(route.currentSpell());
	}

	/**
	 * A tablet in the <b>bank</b> must not block the spell reading of the same words.
	 *
	 * <p>The reported gap: "Teleport to House via Weiss Portal" resolved to house tablets
	 * sitting in the bank, the spell was never considered, and the travel hint — rightly
	 * refusing to point at something not carried — showed nothing at all. The spell is
	 * castable from where the player is standing; the tablets are a detour.
	 */
	@Test
	public void aBankedTabletDoesNotBlockTheSpell() throws Exception
	{
		RouteItem route = routeWithBanked(
			Collections.singletonList("Teleport to House via Weiss Portal"),
			names(HOUSE_TAB, "Teleport to house"),
			HOUSE_TAB);

		assertEquals(com.dooglemaps.guide.TeleportSpell.TELEPORT_TO_HOUSE, route.currentSpell());
		assertEquals(-1, route.currentItemId());
	}

	/** With no spell reading at all, a banked item still resolves - the bank marks it. */
	@Test
	public void aBankedItemWithNoSpellReadingStillResolves() throws Exception
	{
		RouteItem route = routeWithBanked(
			Collections.singletonList("Games necklace (Barbarian Outpost)"),
			names(NECKLACE, "Games necklace(8)"),
			NECKLACE);

		assertEquals(NECKLACE, route.currentItemId());
		assertNull(route.currentSpell());
	}

	/** Path order still rules: an item hop before a spell hop is the next actual click. */
	@Test
	public void anEarlierItemHopBeatsALaterSpellHop() throws Exception
	{
		RouteItem route = routeWith(
			Arrays.asList("Games necklace (Barbarian Outpost)", "Camelot Teleport"),
			names(NECKLACE, "Games necklace(8)"),
			NECKLACE);

		assertEquals(NECKLACE, route.currentItemId());
		assertNull(route.currentSpell());
	}

	/**
	 * A hop the game has said is spent for the day is passed over, not resolved.
	 *
	 * <p>The reported case: the Ardougne cloak's farm teleport runs out after five uses and
	 * nothing in the client can count them — see {@code DailyTeleports} — so Shortest Path
	 * goes on planning through it, and this named the cloak as the thing to click. Once the
	 * game has refused it outright, the next hop is the one that will actually be used.
	 */
	@Test
	public void aTeleportTheGameHasRefusedTodayIsSkipped() throws Exception
	{
		DailyTeleports spent = Mockito.mock(DailyTeleports.class);
		when(spent.isHopSpent("Ardougne cloak: Ardougne Farm")).thenReturn(true);

		RouteItem route = routeWith(
			Arrays.asList("Ardougne cloak: Ardougne Farm", "Games necklace (Barbarian Outpost)"),
			names(CLOAK, "Ardougne cloak", NECKLACE, "Games necklace(8)"),
			spent, CLOAK, NECKLACE);

		assertEquals("the cloak is spent, so the necklace is the next real click",
			NECKLACE, route.currentItemId());
	}

	/** ...and while it has charges, it is the router's pick like any other hop. */
	@Test
	public void theSameHopResolvesNormallyWhileItHasCharges() throws Exception
	{
		RouteItem route = routeWith(
			Arrays.asList("Ardougne cloak: Ardougne Farm", "Games necklace (Barbarian Outpost)"),
			names(CLOAK, "Ardougne cloak", NECKLACE, "Games necklace(8)"),
			CLOAK, NECKLACE);

		assertEquals(CLOAK, route.currentItemId());
	}

	/**
	 * The one on the player wins over a same-named one in the bank.
	 *
	 * <p>The reported case: a Pendant of Ates round the player's neck, the route's first hop
	 * <i>"Pendant of ates: 3. Ralos' Rise"</i>, and nothing lit anywhere — not the pack (it is
	 * not in the pack) and not the worn-equipment tab stone. The pendant has two forms and the
	 * remembered bank was holding the other one, so the name matched the <b>banked</b> id first
	 * and the hop resolved as a detour to the bank. {@code GuideTracker.travelHint} then rightly
	 * refused to point at a teleport that is not on you, and the leg travelled with no
	 * highlight at all.
	 *
	 * <p>Which is the opposite of what this class says it does — "a carried item first, the
	 * spell second, a banked item last". The order is decided before the carried test is
	 * reached, so a bank id with the same name settles it.
	 */
	@Test
	public void aWornTeleportBeatsTheSameNameSittingInTheBank() throws Exception
	{
		RouteItem route = routeWithBankAndCarried(
			Collections.singletonList("Pendant of ates: 3. Ralos' Rise"),
			names(PENDANT_BANKED_FORM, "Pendant of Ates", PENDANT, "Pendant of Ates"),
			new int[]{PENDANT_BANKED_FORM},
			new int[]{PENDANT});

		assertEquals("the pendant round your neck is the click, not the one in the bank",
			PENDANT, route.currentItemId());
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
		return routeWith(transports, names, Mockito.mock(DailyTeleports.class), carriedIds);
	}

	private static RouteItem routeWith(java.util.List<String> transports,
		Map<Integer, String> names, DailyTeleports dailyTeleports, int... carriedIds)
		throws Exception
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
		when(carried.has(Mockito.anyInt()))
			.thenAnswer(i -> ids.contains(i.<Integer>getArgument(0)));

		Client client = Mockito.mock(Client.class);

		return com.dooglemaps.Construct.construct(RouteItem.class, router, itemNames, bank,
			carried, client, dailyTeleports);
	}

	/** The same, with a bank and a pack at once - for the hops both could answer. */
	private static RouteItem routeWithBankAndCarried(java.util.List<String> transports,
		Map<Integer, String> names, int[] bankedIds, int[] carriedIds) throws Exception
	{
		ShortestPathIntegration router = Mockito.mock(ShortestPathIntegration.class);
		when(router.getCurrentTransports()).thenReturn(transports);

		ItemNames itemNames = Mockito.mock(ItemNames.class);
		when(itemNames.get(anyInt(), any())).thenAnswer(i ->
			names.getOrDefault(i.<Integer>getArgument(0), i.getArgument(1)));

		BankContents bank = Mockito.mock(BankContents.class);
		java.util.LinkedHashSet<Integer> banked = new java.util.LinkedHashSet<>();
		for (int id : bankedIds)
		{
			banked.add(id);
		}
		when(bank.getItemIds()).thenReturn(banked);

		CarriedItems carried = Mockito.mock(CarriedItems.class);
		java.util.Set<Integer> ids = new java.util.LinkedHashSet<>();
		for (int id : carriedIds)
		{
			ids.add(id);
		}
		when(carried.getItemIds()).thenReturn(ids);
		when(carried.has(Mockito.anyInt()))
			.thenAnswer(i -> ids.contains(i.<Integer>getArgument(0)));

		return com.dooglemaps.Construct.construct(RouteItem.class, router, itemNames, bank,
			carried, Mockito.mock(Client.class), Mockito.mock(DailyTeleports.class));
	}

	/** The same, but the items are in the bank rather than on the player. */
	private static RouteItem routeWithBanked(java.util.List<String> transports,
		Map<Integer, String> names, int... bankedIds) throws Exception
	{
		ShortestPathIntegration router = Mockito.mock(ShortestPathIntegration.class);
		when(router.getCurrentTransports()).thenReturn(transports);

		ItemNames itemNames = Mockito.mock(ItemNames.class);
		when(itemNames.get(anyInt(), any())).thenAnswer(i ->
			names.getOrDefault(i.<Integer>getArgument(0), i.getArgument(1)));

		BankContents bank = Mockito.mock(BankContents.class);
		java.util.LinkedHashSet<Integer> ids = new java.util.LinkedHashSet<>();
		for (int id : bankedIds)
		{
			ids.add(id);
		}
		when(bank.getItemIds()).thenReturn(ids);

		CarriedItems carried = Mockito.mock(CarriedItems.class);
		when(carried.getItemIds()).thenReturn(new java.util.LinkedHashSet<>());

		return com.dooglemaps.Construct.construct(RouteItem.class, router, itemNames, bank,
			carried, Mockito.mock(Client.class), Mockito.mock(DailyTeleports.class));
	}
}
