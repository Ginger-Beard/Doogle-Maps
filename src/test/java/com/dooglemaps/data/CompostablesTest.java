package com.dooglemaps.data;

import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The bin's arithmetic, and the line between the two compost outcomes.
 *
 * <p>Both tables are hand-written from the wiki, so what is worth pinning is not their contents
 * item by item but the claims a run will be built on: where the herb cut-off falls, that the
 * grimy forms count, that the ash figures are the bin's rather than the per-bucket ones, and
 * that the tomato trap is known about.
 */
public class CompostablesTest
{
	/** Toadflax is the bottom of the supercompost herbs; everything under it is ordinary. */
	@Test
	public void theHerbCutOffIsToadflax()
	{
		assertTrue(Compostables.isSuperCompostable(ItemID.TOADFLAX));
		assertTrue("and the grimy form, which the cache calls UNIDENTIFIED_*",
			Compostables.isSuperCompostable(ItemID.UNIDENTIFIED_TOADFLAX));

		assertFalse("a ranarr is worth more than the bin and does not qualify anyway",
			Compostables.isSuperCompostable(ItemID.RANARR_WEED));
		assertFalse(Compostables.isSuperCompostable(ItemID.GUAM_LEAF));
	}

	/** The fruit a farm run actually comes home with, which is what bins get filled from. */
	@Test
	public void theFruitAndBerriesAreIn()
	{
		for (int item : new int[]{ItemID.PINEAPPLE, ItemID.WATERMELON, ItemID.PAPAYA,
			ItemID.COCONUT, ItemID.DRAGONFRUIT, ItemID.JANGERBERRIES, ItemID.WHITE_BERRIES,
			ItemID.POISONIVY_BERRIES, ItemID.CALQUAT_FRUIT, ItemID.SNAPE_GRASS})
		{
			assertTrue(item + " should make supercompost", Compostables.isSuperCompostable(item));
		}
	}

	/** Fifteen tomatoes give fifteen rotten tomatoes and no compost at all. */
	@Test
	public void theTomatoTrapIsKnownAbout()
	{
		assertTrue(Compostables.isRottenTomatoTrap(ItemID.TOMATO));
		assertFalse("and it is the only one", Compostables.isRottenTomatoTrap(ItemID.CABBAGE));
		assertFalse("a tomato is not supercompostable either",
			Compostables.isSuperCompostable(ItemID.TOMATO));
	}

	/** A selector built off this needs a stable order and no duplicates. */
	@Test
	public void theListIsOrderedAndUnique()
	{
		assertEquals("insertion order kept means no duplicate crept in",
			Compostables.superCompostables().size(),
			new java.util.HashSet<>(Compostables.superCompostables()).size());
		assertEquals("the fruit people fill bins with reads first",
			Integer.valueOf(ItemID.PINEAPPLE),
			Compostables.superCompostables().iterator().next());
	}

	/**
	 * The ash figures are the bin's, not the per-bucket ones — the whole reason the ash goes in
	 * before any bucket is filled.
	 */
	@Test
	public void theBinBeatsDoingItByTheBucket()
	{
		assertEquals(15, CompostBin.NORMAL.getCapacity());
		assertEquals(30, CompostBin.BIG.getCapacity());

		for (CompostBin bin : CompostBin.values())
		{
			assertTrue(bin + " should cost less in the bin than two ash a bucket ("
					+ bin.ashNeeded() + " against " + (bin.getCapacity() * 2) + ")",
				bin.ashNeeded() < bin.getCapacity() * 2);
		}

		assertEquals("25 for fifteen", 25, CompostBin.NORMAL.ashNeeded());
		assertEquals("50 for thirty", 50, CompostBin.BIG.ashNeeded());
	}

	/** One dose does a whole bin whatever its size, which is why it is not a per-bin column. */
	@Test
	public void aSingleCompostPotionDoseDoesEitherBin()
	{
		assertEquals(1, CompostBin.COMPOST_POTION_DOSES);
	}

	@Test
	public void theBinsAreFoundByTheirPatchType()
	{
		assertEquals(CompostBin.NORMAL, CompostBin.forType(PatchImplementation.COMPOST));
		assertEquals(CompostBin.BIG, CompostBin.forType(PatchImplementation.BIG_COMPOST));
		assertNull("a herb patch is not a bin",
			CompostBin.forType(PatchImplementation.HERB));
		assertNotNull("every bin type resolves back", CompostBin.forType(
			CompostBin.BIG.getType()));
	}
}
