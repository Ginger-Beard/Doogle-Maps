package com.dooglemaps.guide;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.route.RunStop;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.when;

/**
 * What the panel says is waiting at the other end of a journey.
 *
 * <h2>The reported dead end</h2>
 *
 * While travelling there are no steps at all — the step list describes the stop you are
 * <b>standing in</b> — so the panel had only the stop's name. A name tells you the patches only
 * if you already know the farm: "Rimmington" means the bush to somebody who has run it a hundred
 * times and nothing to anyone else.
 *
 * <p>Reported from play as travelling to Rimmington for a minute with nothing said about what was
 * there. The log bears out the gap exactly — the leg was named at 21:22:38 and the first step list
 * appeared at 21:23:35.
 *
 * <p>Patch <b>types</b> rather than the work waiting, deliberately: what a patch wants changes
 * while you travel, what is planted does not.
 */
public class DestinationPatchesTest
{
	@Test
	public void oneOfAKindIsNamedPlainly()
	{
		assertEquals("bush", describe(stopOf(PatchImplementation.BUSH)));
	}

	/** Several of one type are counted, and pluralised — "2 allotment" reads as a typo. */
	@Test
	public void severalOfATypeArePluralised()
	{
		assertEquals("2 allotments",
			describe(stopOf(PatchImplementation.ALLOTMENT, PatchImplementation.ALLOTMENT)));
	}

	/**
	 * The sibilant rule, which is the only one the patch names need.
	 *
	 * <p>"Bushes" rather than "bushs". Cactus takes "cactuses" over "cacti" deliberately — it is
	 * the form the game's own interfaces use.
	 */
	@Test
	public void sibilantNamesTakeEs()
	{
		assertEquals("2 bushes",
			describe(stopOf(PatchImplementation.BUSH, PatchImplementation.BUSH)));
		assertEquals("2 cactuses",
			describe(stopOf(PatchImplementation.CACTUS, PatchImplementation.CACTUS)));
	}

	/** A mixed stop reads as a list, in the stop's own order so two farms read alike. */
	@Test
	public void aMixedStopListsItsTypes()
	{
		assertEquals("herb, flower, 2 allotments", describe(stopOf(
			PatchImplementation.HERB, PatchImplementation.FLOWER,
			PatchImplementation.ALLOTMENT, PatchImplementation.ALLOTMENT)));
	}

	/**
	 * Past three types the size is the useful fact and the list is not.
	 *
	 * <p>The Farming Guild has eleven. Listing them would push the destination off the panel to
	 * say something that reads as noise.
	 */
	@Test
	public void aCrowdedStopIsDescribedByItsSize()
	{
		assertEquals("5 patches", describe(stopOf(
			PatchImplementation.HERB, PatchImplementation.FLOWER, PatchImplementation.ALLOTMENT,
			PatchImplementation.BUSH, PatchImplementation.CACTUS)));
	}

	/** Nothing to say when there is no leg, which is the ordinary state at a stop. */
	@Test
	public void noStopSaysNothing()
	{
		assertNull(describe(null));
	}

	private static String describe(RunStop stop)
	{
		return GuideTracker.describePatchesAt(stop);
	}

	private static RunStop stopOf(PatchImplementation... types)
	{
		List<FarmPatch> patches = new ArrayList<>();
		for (PatchImplementation type : types)
		{
			patches.add(anyPatchOf(type));
		}
		RunStop stop = Mockito.mock(RunStop.class);
		when(stop.getPatches()).thenReturn(patches);
		return stop;
	}

	private static FarmPatch anyPatchOf(PatchImplementation type)
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(type))
		{
			return patch;
		}
		throw new AssertionError("no patch of type " + type);
	}
}
