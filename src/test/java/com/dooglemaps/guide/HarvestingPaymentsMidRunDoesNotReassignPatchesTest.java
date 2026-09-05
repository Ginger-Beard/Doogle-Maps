package com.dooglemaps.guide;

import com.dooglemaps.data.Seed;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Picking fruit mid-run does not hand the patch you are standing at to a sapling in the vault.
 *
 * <h2>The reported dead end</h2>
 *
 * A fruit tree run, palm ticked to protect at fifteen papayas a patch, thirteen papayas in the
 * bank at setting-off time. Palm was therefore unaffordable, the allocation skipped it, and the
 * run took seven papaya saplings and left twenty-eight palms in the seed vault. Then the first
 * tree was harvested for six papayas: nineteen covers a palm, palm became affordable, and the
 * allocation — which is rebuilt from live stores on every single tick — promptly gave a patch to
 * a sapling four regions away. The patch it gave it to was the one under the player's feet,
 * because {@code SeedAllocation}'s first claim puts it there. Lletya and then the Gnome
 * Stronghold each answered "Skipping - the palm sapling is in the seed vault" while six papaya
 * saplings sat in the pack with nothing to do.
 *
 * <p>The rebuilding is right and stays. What was wrong is that its input — "how many of this seed
 * do I have" — went on counting containers the run had walked away from. This walks the reported
 * sequence and asserts the harvest changes nothing about where the run is going to plant.
 */
public class HarvestingPaymentsMidRunDoesNotReassignPatchesTest
{
	private FruitTreeRun run;

	@Before
	public void setUp() throws Exception
	{
		run = new FruitTreeRun();
		run.bothPatchesAreEmpty();

		// The reported stock, to the sapling: the palms never left the vault, the papayas are
		// the seven that did.
		run.saplings(Seed.PALM, 0, 0, 28);
		run.saplings(Seed.PAPAYA, 7, 0, 0);
		run.saplings(Seed.DRAGONFRUIT, 0, 0, 0);

		// Two short of a palm, which is why the palms are still in the vault.
		run.papayasToPayWith(13, 0);

		run.setOffFromTheBank();
		run.arriveAt(FruitTreeRun.AT_LLETYA);
	}

	@Test
	public void aHarvestThatAffordsAPalmDoesNotTakeThePatchOffThePapaya()
	{
		GuideStep before = run.stepFor(FruitTreeRun.LLETYA);
		assertNotNull("fixture: the patch under the player wants a papaya sapling", before);
		assertEquals(GuideAction.PLANT, before.getAction());
		assertEquals(Seed.PAPAYA.getPlantedItemID(), before.getItemId());

		// The first tree is picked: six papayas into the pack, and the palm's fifteen are covered.
		run.papayasToPayWith(13, 6);
		run.tracker.onGameTick(null);

		GuideStep after = run.stepFor(FruitTreeRun.LLETYA);
		assertNotNull("the patch still has its planting step - a sapling in the vault is not a "
			+ "seed this run can plant, however affordable it has become", after);
		assertEquals(GuideAction.PLANT, after.getAction());
		assertEquals("and it is still the papaya that is going in the ground",
			Seed.PAPAYA.getPlantedItemID(), after.getItemId());

		assertTrue("nothing is skipped, because nothing was reassigned: " + run.skips(),
			run.skips().isEmpty());
	}
}
