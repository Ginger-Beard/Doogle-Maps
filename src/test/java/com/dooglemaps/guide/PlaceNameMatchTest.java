package com.dooglemaps.guide;

import java.lang.reflect.Method;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers the loose place-name match behind the portal nexus and jewellery box highlighting.
 *
 * <p>Worth its own test because it is the one part of that feature with no game state in it, and
 * because a match that is too loose is the failure that matters: pointing at the wrong row of the
 * nexus sends someone to the wrong side of the map, which is worse than highlighting nothing.
 */
public class PlaceNameMatchTest
{
	/** The game's wording and the plugin's are rarely identical, which is the whole problem. */
	@Test
	public void differentWordingForOnePlaceStillMatches() throws Exception
	{
		assertTrue("case should not matter", matches("Farming Guild", "Farming guild"));
		assertTrue("the nexus says Trollheim where the plugin says Troll Stronghold, and the "
				+ "two share no substring - that is what the alias table is for",
			matches("Trollheim", "Troll Stronghold"));
		assertTrue("and it has to work whichever way round it is asked",
			matches("Troll Stronghold", "Trollheim"));
		assertTrue("a row naming more than the place still contains it",
			matches("Catherby Teleport", "Catherby"));
		assertTrue("and the other way round", matches("Ardougne", "Ardougne Farm"));
	}

	/** Two different places must never match, which is the failure worth guarding. */
	@Test
	public void differentPlacesDoNotMatch() throws Exception
	{
		assertFalse(matches("Varrock", "Falador"));
		assertFalse(matches("Catherby", "Ardougne"));
	}

	/**
	 * Empty is not a wildcard.
	 *
	 * <p>A plain {@code contains} says every string contains the empty one, so an unlabelled
	 * widget — of which an interface has many — would match every destination and light up the
	 * whole panel.
	 */
	@Test
	public void emptyMatchesNothing() throws Exception
	{
		assertFalse("an unlabelled row must not match", matches("", "Falador"));
		assertFalse(matches("Falador", ""));
		assertFalse(matches("   ", "Falador"));
		assertFalse(matches(null, "Falador"));
	}

	/**
	 * A jewellery box row carries its option letter, and must still match.
	 *
	 * <p>The rows read "J: Farming Guild" — the letter is the menu option to press. Containment
	 * handles it, but only in one direction, so it is worth pinning: the row contains the
	 * destination name, never the other way round.
	 */
	@Test
	public void aLetteredMenuRowStillMatches() throws Exception
	{
		assertTrue("the row names the place with its option letter in front",
			matches("J: Farming Guild", "Farming Guild"));
		assertFalse("but a different destination on the same menu must not",
			matches("L: Grand Exchange", "Farming Guild"));
	}

	/**
	 * The nexus list is player-ordered, so nothing may key off position or shortcut number.
	 *
	 * <p>Players reorder their own nexus, so "the third row" and "option 4" mean different things
	 * on different accounts. The row's <i>name</i> is the only stable thing on that screen, which
	 * is what this matches on — these assertions exist to stop anyone reintroducing a positional
	 * shortcut as an optimisation.
	 */
	@Test
	public void aRowMatchesOnItsNameWhateverPrecedesIt() throws Exception
	{
		assertTrue(matches("Varrock", "Varrock"));
		assertTrue("a leading number must not prevent a match", matches("4: Catherby", "Catherby"));
		assertTrue("nor any other decoration", matches("Catherby Teleport", "Catherby"));
		assertFalse("and a different row on the same list must not match",
			matches("2: Falador", "Catherby"));
	}

	/**
	 * Shortest Path's hop text picks the furniture, through each kind's wiki destination list.
	 *
	 * <p>The route already chose how to travel; this matching only reads its choice back. The
	 * spot checks are the shapes that have burned before: Troll Stronghold once went to the
	 * jewellery box via "games necklace reaches Burthorpe" — a climb, not an arrival — and a
	 * bare house-exit "Portal" would match half the hops in the game if it were allowed to.
	 */
	@Test
	public void routeHopsPickTheFurnitureThroughItsDestinations()
	{
		assertTrue("Trollheim is on the nexus's attunement list",
			HouseTeleports.furnitureServesHop("Portal Nexus", "Trollheim Teleport"));
		assertFalse("and on no jewellery, so the box must not claim the hop",
			HouseTeleports.furnitureServesHop("Jewellery Box", "Trollheim Teleport"));
		assertTrue("the skills necklace's Farming Guild is the box's",
			HouseTeleports.furnitureServesHop("Jewellery Box", "Farming Guild"));
		assertTrue("Fossil Island belongs to the mounted digsite pendant",
			HouseTeleports.furnitureServesHop("Digsite Pendant", "Fossil Island"));
		assertTrue("a single-destination portal carries its destination in its name",
			HouseTeleports.furnitureServesHop("Varrock Portal", "Varrock Teleport"));
		assertTrue("the garden's spirit tree covers the spirit tree network",
			HouseTeleports.furnitureServesHop("Spirit tree", "Etceteria"));
		assertFalse("no house furniture reaches Prifddinas - the teleport crystal is an item",
			HouseTeleports.furnitureServesHop("Portal Nexus", "Prifddinas"));
		assertFalse("a bare exit Portal matches nothing, however the hop is worded",
			HouseTeleports.furnitureServesHop("Portal", "Varrock Portal"));
	}

	/**
	 * A fairy ring's hop is its code, and nothing else.
	 *
	 * <p>Shortest Path's fairy data puts the bare code in the display info — "A J P",
	 * "ZANARIS", or a dash-chain — with no object name to prefix, so the hop never contains
	 * the words "fairy ring". The garden ring failed to match its own hop, the front-door
	 * reroute read the house as serving nothing, and a fairy-ring plan was replaced with a
	 * walk out the exit portal. Reported from play, Aldarin via the POH ring.
	 */
	@Test
	public void aFairyRingServesItsCodeShapedHops()
	{
		assertTrue("a bare code is a fairy ring's hop",
			HouseTeleports.furnitureServesHop("Fairy ring", "A J P"));
		assertTrue("the main ring's Zanaris hop too",
			HouseTeleports.furnitureServesHop("Fairy ring", "ZANARIS"));
		assertTrue("and a chained multi-code journey",
			HouseTeleports.furnitureServesHop("Fairy ring", "A I R - D L R - D J Q - A J S"));
		assertFalse("no other furniture may claim a code",
			HouseTeleports.furnitureServesHop("Portal Nexus", "A J P"));
		assertFalse("and a worded hop is not a code",
			HouseTeleports.furnitureServesHop("Fairy ring", "Varrock Teleport"));
	}

	/**
	 * Explicitly-named furniture is a stronger claim than a destination-list serve.
	 *
	 * <p>The distinction decides whether {@code doorIsTheWay} may overrule the furniture:
	 * a nexus whose destination list happens to carry the place is a coincidence the door
	 * may beat; a hop that names the furniture is the router routing through it, and the
	 * door must not walk the player past it. Reported from play: "Configure Fairy ring -
	 * C K Q" overruled out the exit portal.
	 */
	@Test
	public void namedFurnitureOutranksADestinationCoincidence()
	{
		assertTrue("the hop names the ring, so the claim is explicit",
			HouseTeleports.furnitureNamedByHop("Fairy ring", "Configure Fairy ring - C K Q"));
		assertTrue("a bare code names the ring too - it is how SP words these",
			HouseTeleports.furnitureNamedByHop("Fairy ring", "C K Q"));
		assertFalse("a destination the nexus reaches does not NAME the nexus",
			HouseTeleports.furnitureNamedByHop("Portal Nexus", "Trollheim Teleport"));
		assertTrue("...though it still serves it, weakly",
			HouseTeleports.furnitureServesHop("Portal Nexus", "Trollheim Teleport"));
	}

	/**
	 * An alias is a stand-in, not a synonym.
	 *
	 * <p>A nexus can hold both "Trollheim" and "Troll Stronghold" — the second attuned with
	 * stony basalt, landing beside the patch where the first lands up the mountain. The alias
	 * lets the Trollheim row carry a Troll Stronghold destination when it is the only row
	 * there is; the direct test is what lets the row scan prefer the real one when both are.
	 */
	@Test
	public void theAliasIsLooseAndTheDirectMatchIsNot() throws Exception
	{
		assertTrue("the Trollheim row can stand in for Troll Stronghold",
			matches("Troll Stronghold", "Trollheim"));
		assertFalse("but never as a direct match - the real row must win when both exist",
			matchesDirectly("Troll Stronghold", "Trollheim"));
		assertTrue("the basalt-attuned row is the direct one",
			matchesDirectly("Troll Stronghold", "Troll Stronghold"));
	}

	/**
	 * The nexus's Varrock teleport can be redirected to land at the Grand Exchange, and the row
	 * then reads "Grand Exchange" — no substring shared with the "Varrock" the run stop is named
	 * after. Reported from play: the nexus itself was outlined (the furniture list has known the
	 * pair all along) and then no row lit inside it.
	 */
	@Test
	public void theGrandExchangeRowStandsInForVarrock() throws Exception
	{
		assertTrue("the GE-redirected row can stand in for Varrock",
			matches("Grand Exchange", "Varrock"));
		assertTrue("whichever way round it is asked", matches("Varrock", "Grand Exchange"));
		assertFalse("but never as a direct match - a real Varrock row must win when both exist",
			matchesDirectly("Grand Exchange", "Varrock"));
	}

	private static boolean matchesDirectly(String a, String b) throws Exception
	{
		Method method = HouseTeleports.class
			.getDeclaredMethod("namesTheSamePlaceDirectly", String.class, String.class);
		method.setAccessible(true);
		return (boolean) method.invoke(null, a, b);
	}

	private static boolean matches(String a, String b) throws Exception
	{
		Method method = HouseTeleports.class
			.getDeclaredMethod("namesTheSamePlace", String.class, String.class);
		method.setAccessible(true);
		return (boolean) method.invoke(null, a, b);
	}
}
