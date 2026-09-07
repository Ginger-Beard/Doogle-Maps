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
	 * A route hop's own row name finds its row, and only its row.
	 *
	 * <h2>The Al Kharid loop</h2>
	 *
	 * The row highlight used to be keyed on the destination alone, and a jewellery box can
	 * carry the stop's name verbatim on a row the router did not pick: an Al Kharid trip
	 * planned through "1: Emir's Arena" lit "R: Al Kharid" instead, and following the
	 * highlight landed somewhere the router immediately re-planned around — teleport home,
	 * box again, forever. Reported from play. The overlay now asks the hops' own row names
	 * first ({@code GuideInventoryOverlay.rowNames}); these are the matcher facts that
	 * ordering rests on.
	 */
	@Test
	public void aHopsOwnRowNameFindsExactlyItsRow() throws Exception
	{
		assertTrue("the row the router planned through matches its own name",
			matches("1: Emir's Arena", "1: Emir's Arena"));
		assertFalse("and the stop-named row does not match the hop's row name",
			matches("R: Al Kharid", "1: Emir's Arena"));
	}

	/**
	 * The rows as the game actually writes them, markup and all.
	 *
	 * <h2>How the Al Kharid loop came back</h2>
	 *
	 * The test above pinned the ordering with clean text, and the live rows are not clean: a
	 * jewellery box row is {@code <col=ccccff>1:</col> Emir's Arena}, so the hop's row name
	 * failed containment on the colour tag, the match fell through to the destination — and the
	 * glory's {@code <col=ccccff>R:</col> Al Kharid} row carries that verbatim. Same bug, third
	 * report, reintroduced by markup the tests never exercised. The nexus's variant is spacing:
	 * {@code <col=ffffff>D</col> :  Poison Waste}, colon outside the tag and two spaces deep —
	 * the session log showed exactly that row failing to match "D: Poison Waste". These are the
	 * game's own strings, copied from the log, so the matcher is tested against reality rather
	 * than against what reality ought to look like.
	 */
	@Test
	public void theGamesOwnMarkupDoesNotBreakTheMatch() throws Exception
	{
		assertTrue("a colour-tagged box row still matches the hop's row name",
			matches("<col=ccccff>1:</col> Emir's Arena", "1: Emir's Arena"));
		assertTrue("a tag-split nexus row still matches, spacing and all",
			matches("<col=ffffff>D</col> :  Poison Waste", "D: Poison Waste"));
		assertTrue("and the plain destination still finds its tagged row",
			matches("<col=ccccff>R:</col> Al Kharid", "Al Kharid"));
		assertFalse("but a tagged row is still not every row",
			matches("<col=ccccff>R:</col> Al Kharid", "1: Emir's Arena"));
		assertTrue("the nexus's own Catherby row, as the log printed it",
			matches("<col=ffffff>4</col> :  Catherby", "Catherby"));
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
	 * A combined build is both furnitures at once, and a hop only ever names one half.
	 *
	 * <p>"Spirit tree &amp; fairy ring" is the name {@code PlayerHouse} forces onto the
	 * combined garden build (wiki object 29229) — the garden teleports are matched by id,
	 * their scene names having failed in the field — and it is <i>longer than either hop</i>,
	 * so plain containment could never say yes for either half. Split on the "&amp;", each
	 * half answers for itself; a decorated ring name is matched by kind as well.
	 */
	@Test
	public void aCombinedBuildAnswersForEachOfItsHalves()
	{
		assertTrue("the ring half claims the worded fairy hop",
			HouseTeleports.furnitureNamedByHop(
				"Spirit tree & fairy ring", "Configure Fairy ring - C K Q"));
		assertTrue("and the bare code",
			HouseTeleports.furnitureNamedByHop("Spirit tree & fairy ring", "A J P"));
		assertTrue("the tree half claims a spirit tree hop",
			HouseTeleports.furnitureNamedByHop(
				"Spirit tree & fairy ring", "Travel Spirit tree - 6: Prifddinas"));
		assertTrue("and its destination list still serves through the combined name",
			HouseTeleports.furnitureServesHop("Spirit tree & fairy ring", "Gnome Stronghold"));
		// Not "Varrock Teleport": the tree half genuinely serves that one, through its Grand
		// Exchange destination and the varrock<->grand exchange alias.
		assertFalse("no half of it claims an unrelated hop",
			HouseTeleports.furnitureServesHop("Spirit tree & fairy ring", "Trollheim Teleport"));

		assertTrue("a decorated ring name is matched by kind, not by full-name containment",
			HouseTeleports.furnitureNamedByHop(
				"Fairy ring (Superior Garden)", "Configure Fairy ring - C K Q"));
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

	/**
	 * The Fossil Island rowboat's "Select an option" menu, as reported from play 2026-09-06:
	 * standing at the barge landing with the hardwood stop's own name as the only want, nothing
	 * lit because none of "Row to the barge.", "Row to the north of the island.", "Row to the
	 * camp." or "Dive into the sea." says "Fossil Island". "The north of the island" is the
	 * Mushroom Forest's own rowboat, at the forest's north-east corner; "the camp" is the
	 * Museum Camp on the far side, which a first draft of the alias wrongly chose. The seaweed
	 * stop dives instead of rowing anywhere.
	 */
	@Test
	public void theFossilIslandRowboatAliasesLandOnTheirOwnRowsOnly() throws Exception
	{
		assertTrue("the hardwood stop's own name stands in for the forest's landing",
			matches("Fossil Island", "Row to the north of the island."));
		assertFalse("but not the barge landing",
			matches("Fossil Island", "Row to the barge."));
		assertFalse("nor the Museum Camp, on the far side of the island from the patches",
			matches("Fossil Island", "Row to the camp."));
		assertFalse("nor the dive, which is the seaweed stop's row",
			matches("Fossil Island", "Dive into the sea."));

		assertTrue("the underwater seaweed stop dives rather than rows",
			matches("Seaweed", "Dive into the sea."));
		assertFalse("and does not light any of the rowboat's landings",
			matches("Seaweed", "Row to the camp."));

		assertFalse("a farming stop never lights the barge landing on its own",
			matches("Fossil Island", "Row to the barge."));
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
