package com.dooglemaps.guide;

import com.dooglemaps.data.Seed;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A patch passed over still says where the seed for it is, rather than a bare "no seed".
 *
 * <h2>What this protects, and what nearly took it away</h2>
 *
 * <i>"Plant 3 snape grass seeds"</i> stood at the top of the list at Prifddinas, unperformable,
 * with the seeds in the bank. The fix made the guide go quiet about such a patch and had the panel
 * pick up the explanation instead: <i>"Skipping prifddinas - the snape grass seeds are in your
 * bank"</i>. That wording is the whole value of the change — "I forgot to withdraw them" is a
 * fixable problem where "the plugin skipped my patch" is not.
 *
 * <p>It used to fall out of the allocation for free. The allocation counted banked seeds, so a
 * patch it could not fill came back holding one anyway and the wording followed. Now that the
 * allocation stops counting them once the run is past its supply leg, such a patch is allocated
 * <b>nothing</b> — and the wording had one step to fall to, a bare "no seed", which is both less
 * true and less useful. So the skip looks the seed up for itself; see
 * {@code GuideTracker.aPickedSeedLeftBehind}.
 */
public class TheSkipNamesWhereThePickedSeedActuallyIsTest
{
	private FruitTreeRun run;

	@Before
	public void setUp() throws Exception
	{
		run = new FruitTreeRun();
		run.bothPatchesAreEmpty();

		run.saplings(Seed.PALM, 0, 0, 28);
		run.saplings(Seed.DRAGONFRUIT, 0, 0, 0);
		// One papaya sapling on the trip, which is what the run arrives and plants.
		run.saplings(Seed.PAPAYA, 1, 0, 0);
		run.papayasToPayWith(FruitTreeRun.PAPAYAS_PER_PALM * 2, 0);

		run.setOffFromTheBank();
		run.arriveAt(FruitTreeRun.AT_LLETYA);

		GuideStep planting = run.stepFor(FruitTreeRun.LLETYA);
		assertNotNull("fixture: the papaya goes in first", planting);
		assertEquals(Seed.PAPAYA.getPlantedItemID(), planting.getItemId());

		// ...and once it is in the ground there is nothing left on the trip for this stop. The
		// palms are still in the vault, affordable and outranking everything picked.
		run.saplings(Seed.PAPAYA, 0, 0, 0);
		run.tracker.onGameTick(null);
	}

	@Test
	public void aPatchWithNothingAllocatedStillNamesTheVault()
	{
		assertEquals("one patch at this stop, one line about it: " + run.skips(),
			1, run.skips().size());
		assertEquals("Skipping lletya - the palm sapling is in the seed vault.",
			run.skips().get(0));
	}

	/** And "no seed" is kept for what it says: nothing picked for this group exists anywhere. */
	@Test
	public void aSeedTheAccountDoesNotHaveAtAllIsStillJustNoSeed()
	{
		run.saplings(Seed.PALM, 0, 0, 0);
		run.tracker.onGameTick(null);

		assertTrue("with the palms gone there is nothing left to point at: " + run.skips(),
			run.skips().contains("Skipping lletya - no seed."));
	}
}
