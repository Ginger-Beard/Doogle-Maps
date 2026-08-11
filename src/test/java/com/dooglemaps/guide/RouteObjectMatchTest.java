package com.dooglemaps.guide;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Which scene object a route hop is pointing at.
 *
 * <h2>The bug this pins</h2>
 *
 * Shortest Path's {@code objectInfo} column is {@code menuOption menuTarget objectID}, and the
 * target is the object's name — so a hop arrives as "Travel Spirit tree" while the object in the
 * scene is called "Spirit tree". {@code GuideOverlay.scanForObjects} compared the two with
 * {@code equalsIgnoreCase}, which can never be true, so the overworld route-object outline had
 * never lit anything since the day it was written. The Grand Exchange spirit tree was logged as
 * fixed once; what was fixed then was the panel's <i>wording</i> — the id came off the end — and
 * the outline was left comparing a menu row against an object name.
 *
 * <p>The repair cannot be a plain suffix match, and that is the whole reason this file exists:
 * "Enter Annakarl Portal" ends in "Portal", and a player-owned house is full of objects named
 * exactly that. The rule is a suffix on a word boundary <b>plus</b> a prefix short enough to be
 * a menu option, and "short enough" is two words because that is the longest option in any of
 * Shortest Path's twenty-four transport TSVs. Every fixture below is a real row from them.
 */
public class RouteObjectMatchTest
{
	/** The reported case, and the shape most hops take: one verb, then the object. */
	@Test
	public void aVerbInFrontOfTheNameStillFindsTheObject()
	{
		assertTrue(matches("Travel Spirit tree", "Spirit tree"));
		assertTrue(matches("Configure Fairy ring", "Fairy ring"));
		assertTrue(matches("Enter Annakarl Portal", "Annakarl Portal"));
		assertTrue(matches("Climb-up Ladder", "Ladder"));
	}

	/**
	 * Two-word options, which is as long as the data ever goes.
	 *
	 * <p>The jewellery box is the one that matters in play — it is house furniture, so it is
	 * matched by id elsewhere too — but the glory and the talisman rows word a <b>destination</b>
	 * into the option slot, and they have to survive the same rule.
	 */
	@Test
	public void twoWordOptionsAreStillOptions()
	{
		assertTrue(matches("Teleport Menu Fancy Jewellery Box", "Fancy Jewellery Box"));
		assertTrue(matches("Edgeville Amulet of Glory", "Amulet of Glory"));
		assertTrue(matches("Look out Xeric's Talisman", "Xeric's Talisman"));
		assertTrue(matches("The Pandemonium Captain Tobias", "Captain Tobias"));
	}

	/**
	 * The disaster a plain suffix rule would cause, refused.
	 *
	 * <p>"Enter Annakarl Portal" ends in "Portal". Every house exit is named "Portal", so a
	 * suffix rule would outline all of them the moment a wilderness portal hop was planned —
	 * and the exit portal lighting when it should not is a bug this file has already seen
	 * reported twice from play, by two different routes.
	 *
	 * <p>Note that the two-word option cap does <b>not</b> settle this on its own: "Enter
	 * Annakarl" is two words, so both parses of that hop fit it. What settles it is that a
	 * one-word name may only follow a one-word option — a genuine bare name is the whole of a
	 * two-word hop, as "Climb-up Ladder" is.
	 */
	@Test
	public void aBareNameCannotClaimALongerHop()
	{
		assertEquals("the exit portals must not answer for a wilderness one", 0,
			GuideOverlay.routeObjectMatch("Enter Annakarl Portal", "Portal"));
		assertEquals(0,
			GuideOverlay.routeObjectMatch("Teleport Menu Fancy Jewellery Box", "Box"));
		assertTrue("but a real bare name still works",
			matches("Climb-up Ladder", "Ladder"));
	}

	/**
	 * Where both are in the scene, the longer name wins.
	 *
	 * <p>"Enter Ape Atoll Dungeon Portal" legitimately matches "Ape Atoll Dungeon Portal" by the
	 * rule above, and "Dungeon Portal" by it too — two words of prefix left. The scan keeps only
	 * the longest, so the object the router actually named is the one outlined.
	 */
	@Test
	public void theLongestNameIsThePreferredOne()
	{
		String hop = "Enter Ape Atoll Dungeon Portal";
		assertTrue(GuideOverlay.routeObjectMatch(hop, "Ape Atoll Dungeon Portal")
			> GuideOverlay.routeObjectMatch(hop, "Dungeon Portal"));
		assertEquals("and the bare word never gets a look in", 0,
			GuideOverlay.routeObjectMatch(hop, "Portal"));
	}

	/** A word boundary, not a character one: "tree" must not match inside "Spirit tree". */
	@Test
	public void theMatchIsOnWholeWords()
	{
		assertEquals(0, GuideOverlay.routeObjectMatch("Travel Spirit tree", "pirit tree"));
		assertEquals(0, GuideOverlay.routeObjectMatch("Travel Spirit tree", "Travel Spirit"));
	}

	/**
	 * A hop that is exactly the object's name still matches, which is the old rule kept.
	 *
	 * <p>Nothing in the TSVs writes one, but the value reaches here through an id strip that can
	 * leave anything, and dropping the case would be a silent narrowing.
	 */
	@Test
	public void aBareObjectNameIsStillAMatch()
	{
		assertTrue(matches("Spirit tree", "Spirit tree"));
		assertEquals(0, GuideOverlay.routeObjectMatch("Spirit tree", "Fairy ring"));
	}

	private static boolean matches(String hop, String objectName)
	{
		return GuideOverlay.routeObjectMatch(hop, objectName) == objectName.length();
	}
}
