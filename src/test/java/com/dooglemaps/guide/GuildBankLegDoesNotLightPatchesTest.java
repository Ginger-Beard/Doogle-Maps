package com.dooglemaps.guide;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmRegion;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.route.RunStop;
import java.util.List;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * What is outlined while the run is still collecting its supplies.
 *
 * <h2>The reported dead end</h2>
 *
 * A stop's patches are lit on the way in, so the last stretch of a journey is not walked with the
 * whole farm dark. The supply leg has no steps either, so the overlay's "no step means travelling"
 * branch drew it the same way — invisible everywhere the bank sits a region away from the patches.
 *
 * <p>The Farming Guild's bank chest is <b>inside</b> the patch region, so
 * {@code destinationStop} resolves it to the guild stop and standing at the chest lit all thirteen
 * patches at once. Reported from play as every patch in the guild being highlighted at the start
 * of a run.
 */
public class GuildBankLegDoesNotLightPatchesTest
{
	/** The reported case: at the guild's own bank, with the guild as the leg's destination. */
	@Test
	public void theSupplyLegLightsNothingEvenWhenItsBankIsInThePatchRegion()
	{
		assertTrue(GuideTracker.patchesAhead(farmingGuild(), true, true).isEmpty());
	}

	/** The behaviour the gate must not cost: an ordinary travel leg still lights the farm. */
	@Test
	public void anOrdinaryTravelLegStillLightsThePatchesAhead()
	{
		RunStop guild = farmingGuild();
		List<FarmPatch> ahead = GuideTracker.patchesAhead(guild, false, true);
		assertEquals(guild.getPatches(), ahead);
		assertTrue("the guild is the crowded stop this was reported at", ahead.size() > 5);
	}

	/**
	 * Standing at the stop, where the step's own highlight is the one instruction on screen.
	 *
	 * <p>The overlay only draws this list when there is no current step, so it is belt and braces
	 * — but the field says "empty when not travelling" and nothing else makes that true.
	 */
	@Test
	public void arrivedIsNotTravelling()
	{
		assertTrue(GuideTracker.patchesAhead(farmingGuild(), false, false).isEmpty());
	}

	/** No leg named, nothing to light. */
	@Test
	public void noDestinationLightsNothing()
	{
		assertTrue(GuideTracker.patchesAhead(null, false, true).isEmpty());
	}

	private static RunStop farmingGuild()
	{
		FarmRegion region = null;
		for (FarmRegion candidate : FarmingWorldData.getRegions())
		{
			if (candidate.getRegionId() == 4922)
			{
				region = candidate;
				break;
			}
		}
		if (region == null)
		{
			throw new AssertionError("the Farming Guild's patch region is missing");
		}

		RunStop stop = Mockito.mock(RunStop.class);
		when(stop.getPatches()).thenReturn(region.getPatches());
		return stop;
	}
}
