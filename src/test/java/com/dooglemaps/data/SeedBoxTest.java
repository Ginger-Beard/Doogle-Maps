package com.dooglemaps.data;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The one place that answers "can this item go in a seed box", and its limit.
 *
 * <h2>Why this class was worth extracting</h2>
 *
 * The seed box produced more repeat reports than anything else in the plugin, and they were all
 * the same bug wearing different clothes: the question had no single home, so four places worked
 * it out separately and disagreed. The rule was never in doubt — every seed goes in, no sapling
 * does — but {@code Seed.isSapling()} was standing in for it, and that is a fact about the
 * <b>crop</b>, not about the item in front of you. A calquat seed goes in a box; the plant pot it
 * becomes does not.
 */
public class SeedBoxTest
{
	/**
	 * Every seed is accepted, and no sapling is.
	 *
	 * <p>Derived from the {@link Seed} table rather than hand-listed, so this is really a check
	 * that the derivation says what the rule says — including for the entries that are not
	 * botanically seeds at all. Coral frags and mushroom spores are storable, which the wiki
	 * states outright, and they have seed items of their own in the table.
	 */
	@Test
	public void everySeedGoesInAndNoSaplingDoes()
	{
		int seeds = 0;
		int saplings = 0;
		for (Seed seed : Seed.values())
		{
			assertTrue(seed.name() + "'s seed should be storable",
				SeedBox.accepts(seed.getItemID()));
			seeds++;

			if (seed.isSapling())
			{
				assertFalse(seed.name() + "'s sapling is a plant pot, not a seed",
					SeedBox.accepts(seed.getSaplingItemID()));
				saplings++;
			}
		}
		assertTrue("fixture: the seed table should not be empty", seeds > 50);
		assertTrue("fixture: there should be tree crops to check the other half against",
			saplings > 10);
	}

	/** Anything that is not a seed at all is refused, so an odd id cannot spend a kind. */
	@Test
	public void anItemThatIsNotASeedIsRefused()
	{
		assertFalse(SeedBox.accepts(net.runelite.api.gameval.ItemID.BUCKET_COMPOST));
		assertFalse(SeedBox.accepts(net.runelite.api.gameval.ItemID.SPADE));
		assertFalse("nor an id the game does not use", SeedBox.accepts(-1));
	}

	/** The limit is six kinds, and it is the only definition of it. */
	@Test
	public void theLimitIsSixKinds()
	{
		assertEquals(6, SeedBox.KINDS);
	}

	@Test
	public void kindsAreCountedOverWhatTheBoxCanHold()
	{
		assertEquals(2, SeedBox.kindsIn(Arrays.asList(
			Seed.POTATO.getItemID(),
			Seed.CALQUAT.getItemID(),
			Seed.CALQUAT.getSaplingItemID(),
			net.runelite.api.gameval.ItemID.BUCKET_COMPOST)));
		assertEquals(0, SeedBox.kindsIn(Collections.emptyList()));
	}

	/**
	 * Filtering drops what the box cannot hold, and empty stacks with it.
	 *
	 * <p>A zero-count entry is not a kind in use; leaving one in would have the box read as
	 * fuller than it is, which is the same failure the sapling caused.
	 */
	@Test
	public void filteringKeepsOnlyRealContents()
	{
		Map<Integer, Integer> contents = new LinkedHashMap<>();
		contents.put(Seed.POTATO.getItemID(), 12);
		contents.put(Seed.CALQUAT.getSaplingItemID(), 1);
		contents.put(Seed.RANARR.getItemID(), 0);

		Map<Integer, Integer> kept = SeedBox.onlyWhatItHolds(contents);

		assertEquals(Collections.singletonMap(Seed.POTATO.getItemID(), 12), kept);
	}
}
