package com.dooglemaps.route;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BankLocationsTest
{
	@Test
	public void theSeededBanksAreAllRealPlaces()
	{
		assertEquals("seeded bank count changed - re-scrape or update this",
			49, BankLocations.getSeeded().size());

		for (BankLocations.Bank bank : BankLocations.getSeeded())
		{
			WorldPoint location = bank.location;
			assertTrue(bank.name + " has a nonsense coordinate",
				location.getX() > 0 && location.getY() > 0);
			assertEquals(bank.name + " should be on the ground floor", 0, location.getPlane());
			assertTrue(bank.name + " has no name", bank.name.length() > 0);
		}
	}

	@Test
	public void wellKnownBanksAreCovered()
	{
		// The banks a farm run actually starts from. If the scrape ever drops these, the
		// bank-first leg of a run quietly starts routing somewhere daft.
		for (String expected : new String[]{"Falador", "Catherby", "Ardougne", "Varrock", "Draynor"})
		{
			assertTrue("no seeded bank named " + expected,
				BankLocations.getSeeded().stream().anyMatch(b -> b.name.startsWith(expected)));
		}
	}
}
