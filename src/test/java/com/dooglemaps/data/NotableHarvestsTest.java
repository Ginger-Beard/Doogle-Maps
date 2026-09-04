package com.dooglemaps.data;

import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The leprechaun-notable set: every {@code Produce} row the leprechaun will actually note,
 * together with the extras this class keeps of its own.
 */
public class NotableHarvestsTest
{
	/**
	 * Every crop {@code Produce} itself flags as leprechaun-notable answers true here too -
	 * this is the set {@code GuideMenuSwap} promotes Use for while the note step is current.
	 */
	@Test
	public void everyLeprechaunNotableProduceItemIsFlagged()
	{
		for (Produce produce : Produce.values())
		{
			if (produce.isLeprechaunNotable())
			{
				assertTrue(produce.name() + " (item " + produce.getItemID() + ")",
					NotableHarvests.isLeprechaunNotable(produce.getItemID()));
			}
		}
	}

	/** Food with nothing to do with farming is left alone. */
	@Test
	public void aSharkIsNotLeprechaunNotable()
	{
		assertFalse(NotableHarvests.isLeprechaunNotable(ItemID.SHARK));
	}
}
