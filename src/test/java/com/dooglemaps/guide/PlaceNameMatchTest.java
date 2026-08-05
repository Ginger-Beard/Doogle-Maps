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

	private static boolean matches(String a, String b) throws Exception
	{
		Method method = HouseTeleports.class
			.getDeclaredMethod("namesTheSamePlace", String.class, String.class);
		method.setAccessible(true);
		return (boolean) method.invoke(null, a, b);
	}
}
