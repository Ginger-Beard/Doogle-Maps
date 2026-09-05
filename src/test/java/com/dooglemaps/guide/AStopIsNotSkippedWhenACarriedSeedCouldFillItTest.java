package com.dooglemaps.guide;

import com.dooglemaps.data.Seed;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A stop with a plantable sapling in the pack gets its planting step, not an explanation.
 *
 * <h2>The reported dead end</h2>
 *
 * <i>"Skipping lletya - the palm sapling is in the seed vault"</i>, and the same again at the
 * Gnome Stronghold, on a run carrying six papaya saplings that would have gone straight into
 * both. Nothing about the papayas had changed; what had changed was that a mid-run harvest made
 * the palm affordable, so the allocation gave the patch to the palm — and a patch allocated a
 * seed that is not on the trip produces no steps at all. The player was told where a sapling was
 * instead of being told to plant the one in their hand.
 *
 * <p>The half this pins down is the one that matters to somebody standing at a patch: whatever
 * else the run believes, a seed that is <b>at hand and could fill this patch</b> must produce a
 * plant step. A skip line is for a patch nothing can be done about.
 */
public class AStopIsNotSkippedWhenACarriedSeedCouldFillItTest
{
	private FruitTreeRun run;

	@Before
	public void setUp() throws Exception
	{
		run = new FruitTreeRun();
		run.bothPatchesAreEmpty();

		run.saplings(Seed.PALM, 0, 0, 28);
		run.saplings(Seed.PAPAYA, 6, 0, 0);
		run.saplings(Seed.DRAGONFRUIT, 0, 0, 0);

		// Nineteen papayas: the palm is affordable, which is the state that used to reassign the
		// patch. It stays affordable for the whole of this test — the point is that affording a
		// palm you are not carrying changes nothing.
		run.papayasToPayWith(13, 6);

		run.setOffFromTheBank();
		run.arriveAt(FruitTreeRun.AT_LLETYA);
	}

	@Test
	public void lletyaIsPlantedRatherThanExplained()
	{
		GuideStep step = run.stepFor(FruitTreeRun.LLETYA);
		assertNotNull("a papaya sapling in the pack is a patch that can be planted", step);
		assertEquals(GuideAction.PLANT, step.getAction());
		assertEquals(Seed.PAPAYA.getPlantedItemID(), step.getItemId());

		assertTrue("and nothing about the stop is worth explaining away: " + run.skips(),
			run.skips().isEmpty());
	}

	/** And the next stop is not skipped either, which is where the palm followed the player to. */
	@Test
	public void andSoIsTheStopTheRunGoesToNext()
	{
		run.standAt(FruitTreeRun.AT_GNOME_STRONGHOLD);
		run.tracker.onGameTick(null);

		GuideStep step = run.stepFor(FruitTreeRun.GNOME_STRONGHOLD);
		assertNotNull("the second stop has a sapling for it too", step);
		assertEquals(GuideAction.PLANT, step.getAction());
		assertEquals(Seed.PAPAYA.getPlantedItemID(), step.getItemId());
		assertTrue("no skip line follows the player from stop to stop: " + run.skips(),
			run.skips().isEmpty());
	}
}
