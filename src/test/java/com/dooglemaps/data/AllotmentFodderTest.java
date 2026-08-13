package com.dooglemaps.data;

import java.util.Set;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What a bin may be offered out of the harvest.
 *
 * <p>The list started as everything a bin accepts, which is a list of what the game permits
 * rather than of what anyone would do. Narrowed with the owner, in their words: <i>"no one's
 * ditching their herbs on compost, or limpwurts, and the rest of these aren't actually near
 * bins"</i>. Both halves of that are load-bearing — the value judgement, and the geography.
 */
public class AllotmentFodderTest
{
	/**
	 * Exactly what an allotment grows, derived rather than listed.
	 *
	 * <p>Pinned as a set rather than a count so that an allotment gaining a crop shows up here as
	 * a named difference, which is the moment to decide whether it belongs in a bin.
	 */
	@Test
	public void theListIsTheAllotmentsOwnOutput()
	{
		Set<Integer> fodder = Compostables.allotmentFodder();

		for (Produce produce : Produce.values())
		{
			if (produce.getPatchImplementation() != PatchImplementation.ALLOTMENT)
			{
				continue;
			}
			assertTrue(produce.getName() + " grows in an allotment and is not offered",
				fodder.contains(produce.getItemID()));
		}
		assertEquals("the eight an allotment grows, and nothing else", 8, fodder.size());
	}

	/**
	 * Nothing valuable, and nothing that grows somewhere else.
	 *
	 * <p>The herbs and limpwurt roots are the value judgement: both are supercompostable and both
	 * are worth many times the compost they would make. The rest is geography — fodder is only
	 * ever offered at the stop you are standing in, and a bin stands beside the allotments, so a
	 * tick against a pineapple or a coconut could never fire.
	 */
	@Test
	public void nothingValuableAndNothingFarFromABinIsOffered()
	{
		Set<Integer> fodder = Compostables.allotmentFodder();

		int[] refused = {
			ItemID.TORSTOL, ItemID.SNAPDRAGON, ItemID.UNIDENTIFIED_RANARR,
			ItemID.LIMPWURT_ROOT,
			ItemID.PINEAPPLE, ItemID.PAPAYA, ItemID.COCONUT, ItemID.CALQUAT_FRUIT,
			ItemID.YEW_ROOTS, ItemID.CELASTRUS_WOOD, ItemID.GIANT_SEAWEED,
		};
		for (int itemId : refused)
		{
			assertFalse(itemId + " should not be offered as bin fodder",
				fodder.contains(itemId));
		}
	}

	/** Every entry is something a bin would actually take, whatever the allotment table says. */
	@Test
	public void everythingOfferedIsSomethingABinAccepts()
	{
		for (int itemId : Compostables.allotmentFodder())
		{
			assertTrue(itemId + " is offered but no bin would take it",
				Compostables.isCompostable(itemId));
		}
	}

	/** The two that make supercompost are the reason an allotment run is worth binning at all. */
	@Test
	public void watermelonAndSnapeGrassMakeSupercompost()
	{
		assertTrue(Compostables.isSuperCompostable(ItemID.WATERMELON));
		assertTrue(Compostables.isSuperCompostable(ItemID.SNAPE_GRASS));
		assertTrue(Compostables.isAllotmentFodder(ItemID.WATERMELON));
		assertTrue(Compostables.isAllotmentFodder(ItemID.SNAPE_GRASS));
	}
}
