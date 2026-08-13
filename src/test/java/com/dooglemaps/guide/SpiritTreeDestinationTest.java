package com.dooglemaps.guide;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.PatchImplementation;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * A spirit tree destination you have to grow is told apart from one you do not.
 *
 * <h2>The reported dead end</h2>
 *
 * <i>"just got routed to the farming guild via POH spirit tree - farming guild. Don't have that
 * yet, I see the jewellery box is also highlighted though."</i>
 *
 * <p>Five of the ten spirit tree destinations are stops on the network and five are <b>farm
 * patches</b> — a tree only joins the network once somebody grows one there. The destination
 * list claimed all ten unconditionally, so the guild's tree was outlined for a hop through it
 * while its patch sat at {@code varbitValue 0, WEEDS}, competing for attention with the
 * jewellery box that genuinely reaches the guild by skills necklace.
 *
 * <p>Shortest Path cannot help here: it takes spirit trees as one boolean with no
 * per-destination control, so the hop is still planned. This plugin has the patch state and the
 * router does not, which is the whole reason the filter belongs on this side.
 */
public class SpiritTreeDestinationTest
{
	@Test
	public void theFivePlayerGrownDestinationsResolveToTheirPatches()
	{
		for (String destination : new String[]{
			"port sarim", "etceteria", "brimhaven", "hosidius", "farming guild"})
		{
			FarmPatch patch = HouseTeleports.spiritTreePatchFor(destination);
			assertNotNull(destination + " should depend on a grown tree", patch);
			assertEquals(destination + " should resolve to a spirit tree patch",
				PatchImplementation.SPIRIT_TREE, patch.getImplementation());
		}
	}

	/**
	 * Hosidius is the one whose destination name and region name differ.
	 *
	 * <p>The data files the patch under <b>Kourend</b>, so a plain name match would have left the
	 * one destination most likely to be ungrown claiming to work.
	 */
	@Test
	public void hosidiusResolvesThroughKourend()
	{
		FarmPatch patch = HouseTeleports.spiritTreePatchFor("hosidius");
		assertNotNull(patch);
		assertEquals("Kourend", patch.getRegion().getName());
	}

	/** The network's own stops need no tree, so they resolve to nothing and are never filtered. */
	@Test
	public void theNetworksOwnStopsDependOnNothing()
	{
		for (String destination : new String[]{
			"tree gnome village", "gnome stronghold", "battlefield of khazard",
			"grand exchange", "feldip hills"})
		{
			assertNull(destination + " is on the network and needs no growing",
				HouseTeleports.spiritTreePatchFor(destination));
		}
	}

	/** And a hop about something else entirely is not a spirit tree question at all. */
	@Test
	public void anUnrelatedHopResolvesToNothing()
	{
		assertNull(HouseTeleports.spiritTreePatchFor("Teleport to House"));
		assertNull(HouseTeleports.spiritTreePatchFor("Configure Fairy ring - A I S"));
		assertNull(HouseTeleports.spiritTreePatchFor(null));
	}

	/** Matched inside a whole hop sentence, which is the shape Shortest Path actually reports. */
	@Test
	public void theDestinationIsFoundInsideARealHop()
	{
		FarmPatch patch =
			HouseTeleports.spiritTreePatchFor("Spirit tree to Farming Guild");
		assertNotNull("the hop names the guild, however it is worded", patch);
		assertEquals("Farming Guild", patch.getRegion().getName());
	}
}
