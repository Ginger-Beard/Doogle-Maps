package com.dooglemaps.bank;

import com.dooglemaps.data.FarmRegion;
import com.dooglemaps.data.FarmingWorldData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers the teleport table, which is hand-written and therefore able to be quietly wrong.
 *
 * <p>The interesting property is not that a given item is listed — that is a fact about the
 * item and easy to check by eye — but that every region of a multi-region place is reachable.
 * Seven farming locations span two map regions, and five of them originally had an entry for
 * only one half, so a run stopping in the other got no suggestion at all.
 */
public class TeleportItemsTest
{
	/**
	 * Both halves of a two-region place answer the same teleports.
	 *
	 * <p>Derived from the world data rather than from a list of the seven, so a location that
	 * gains a second region later is covered without anyone remembering to add it here.
	 */
	@Test
	public void everyRegionOfAPlaceOffersTheSameTeleports()
	{
		Set<String> broken = new TreeSet<>();

		for (FarmRegion region : FarmingWorldData.getRegions())
		{
			for (FarmRegion other : FarmingWorldData.getRegions())
			{
				if (!region.getName().equals(other.getName())
					|| region.getRegionId() == other.getRegionId())
				{
					continue;
				}

				Set<Integer> here = itemIds(region.getRegionId());
				Set<Integer> there = itemIds(other.getRegionId());
				if (!here.equals(there))
				{
					broken.add(region.getName() + " (" + region.getRegionId()
						+ " has " + here.size() + ", " + other.getRegionId()
						+ " has " + there.size() + ")");
				}
			}
		}

		assertEquals("both halves of a place must answer the same teleports: " + broken,
			new TreeSet<String>(), broken);
	}

	/** Catherby is the concrete case: 11062 and 11317 are one place. */
	@Test
	public void catherbysSecondRegionIsCovered()
	{
		assertFalse("Catherby has a teleport at all", itemIds(11062).isEmpty());
		assertEquals("and its other region gives the same answer",
			itemIds(11062), itemIds(11317));
	}

	/** The fairy ring staff is not tied to a place, so it is offered everywhere it is asked for. */
	@Test
	public void theFairyRingStaffIsUniversal()
	{
		Set<Integer> universal = new HashSet<>();
		for (TeleportItems.Teleport teleport : TeleportItems.universal())
		{
			universal.add(teleport.getItemId());
		}
		assertTrue(universal.contains(ItemID.DRAMEN_STAFF));
	}

	/** A region nothing serves says so honestly rather than guessing. */
	@Test
	public void anUnservedRegionReturnsNothing()
	{
		assertTrue(TeleportItems.forRegion(1).isEmpty());
	}

	/**
	 * The wiki's nine per-patch herb recommendations all resolve to a real farming region.
	 *
	 * <p>A mistyped region id is invisible — it simply never matches, and the teleport silently
	 * never gets suggested — so it is worth asserting rather than trusting.
	 */
	@Test
	public void theHerbRunRegionsAreAllReal()
	{
		Set<Integer> real = new HashSet<>();
		for (FarmRegion region : FarmingWorldData.getRegions())
		{
			real.add(region.getRegionId());
		}

		List<Integer> bogus = new ArrayList<>();
		for (int regionId : new int[]{
			10548,  // Ardougne
			11062,  // Catherby
			12083,  // Falador
			4922,   // Farming Guild
			15148,  // Harmony
			6967,   // Kourend
			11321,  // Troll Stronghold
			11325,  // Weiss
			6192,   // Civitas illa Fortis
		})
		{
			if (!real.contains(regionId) || TeleportItems.forRegion(regionId).isEmpty())
			{
				bogus.add(regionId);
			}
		}
		assertTrue("not a farming region, or nothing reaches it: " + bogus, bogus.isEmpty());
	}

	private static Set<Integer> itemIds(int regionId)
	{
		Set<Integer> ids = new HashSet<>();
		for (TeleportItems.Teleport teleport : TeleportItems.forRegion(regionId))
		{
			ids.add(teleport.getItemId());
		}
		return ids;
	}
}
