package com.dooglemaps.guide;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Matching Shortest Path's transport wording to a spell in the book.
 *
 * <p>Best-effort like {@code RouteItem}'s item matching and tested the same way: the exact
 * name, the name with a suffix, the turned-round "Teleport to X" form — and the strings that
 * must never match, because a wrong spell highlight sends someone across the map.
 */
public class TeleportSpellTest
{
	@Test
	public void theExactNameMatches()
	{
		assertEquals(TeleportSpell.CAMELOT, TeleportSpell.match("Camelot Teleport"));
	}

	@Test
	public void suffixesAreCutBeforeMatching()
	{
		assertEquals(TeleportSpell.TROLLHEIM, TeleportSpell.match("Trollheim Teleport (Magic 61)"));
	}

	@Test
	public void teleportToXIsTheSameSpellSaidBackwards()
	{
		assertEquals(TeleportSpell.CAMELOT, TeleportSpell.match("Teleport to Camelot"));
		assertEquals(TeleportSpell.TELEPORT_TO_HOUSE, TeleportSpell.match("Teleport to House"));
	}

	/**
	 * The router's "via ..." tail names furniture, not the spell, and is cut before matching.
	 *
	 * <p>Reported from play as "via Teleport to House via Weiss Portal" lighting neither the
	 * magic tab nor the spell.
	 */
	@Test
	public void theViaTailIsCutBeforeMatching()
	{
		assertEquals(TeleportSpell.TELEPORT_TO_HOUSE,
			TeleportSpell.match("Teleport to House via Weiss Portal"));
		assertEquals(TeleportSpell.CAMELOT,
			TeleportSpell.match("Camelot Teleport via Seers' Village"));
	}

	/**
	 * Exact matches win over prefixes across the whole table, or West Ardougne — the Arceuus
	 * spell beside the West Ardougne herb run — would be taken as plain Ardougne.
	 */
	@Test
	public void westArdougneIsNotArdougne()
	{
		assertEquals(TeleportSpell.WEST_ARDOUGNE, TeleportSpell.match("West Ardougne Teleport"));
		assertEquals(TeleportSpell.ARDOUGNE, TeleportSpell.match("Ardougne Teleport"));
	}

	/** The lunar book's Catherby spell, which a Camelot-less herb run travels by. */
	@Test
	public void theLunarSpellsAreInTheTable()
	{
		assertEquals(TeleportSpell.CATHERBY, TeleportSpell.match("Catherby Teleport"));
		assertEquals(TeleportSpell.ICE_PLATEAU, TeleportSpell.match("Ice Plateau Teleport"));
	}

	@Test
	public void transportsThatAreNotSpellsMatchNothing()
	{
		assertNull(TeleportSpell.match("Fairy ring BIQ"));
		assertNull(TeleportSpell.match("Spirit tree"));
		assertNull(TeleportSpell.match("Games necklace (Barbarian Outpost)"));
	}

	/** Ring codes and other short strings are never spell names. */
	@Test
	public void shortStringsNeverMatch()
	{
		assertNull(TeleportSpell.match("BIQ"));
		assertNull(TeleportSpell.match(""));
	}
}
