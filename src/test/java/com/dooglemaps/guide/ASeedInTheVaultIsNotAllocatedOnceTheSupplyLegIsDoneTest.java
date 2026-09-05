package com.dooglemaps.guide;

import com.dooglemaps.data.Seed;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The rule itself: where a seed is stops counting the moment the run leaves the bank behind.
 *
 * <h2>Both halves are load-bearing, and they point opposite ways</h2>
 *
 * <b>Before</b> the supply leg is over, the allocation counts seeds wherever the account keeps
 * them, and it has to. That allocation is what the withdraw list is built from, so a version of it
 * that only counted what was already in the pack would tell you to bring nothing and the run would
 * set off empty. A palm in the vault is a palm this run can still have.
 *
 * <p><b>After</b> it, the same palm is four regions away, and counting it is what let a mid-run
 * harvest hand the patch under the player's feet to a sapling they were not carrying — see
 * {@link HarvestingPaymentsMidRunDoesNotReassignPatchesTest} for the run that was played.
 *
 * <p>So this holds every input still and moves only the run across that line. The palm is
 * affordable in both halves, twenty-eight of them sit in the vault in both halves, and seven
 * papaya saplings are in the pack in both halves. The only difference is whether the bank is
 * behind the player or ahead of them.
 */
public class ASeedInTheVaultIsNotAllocatedOnceTheSupplyLegIsDoneTest
{
	private FruitTreeRun run;

	@Before
	public void setUp() throws Exception
	{
		run = new FruitTreeRun();
		run.bothPatchesAreEmpty();

		run.saplings(Seed.PALM, 0, 0, 28);
		run.saplings(Seed.PAPAYA, 7, 0, 0);
		run.saplings(Seed.DRAGONFRUIT, 0, 0, 0);

		// Comfortably enough to protect a palm, so the payment budget never takes the blame for
		// anything here. Nineteen was the figure the reported run reached after one harvest.
		run.papayasToPayWith(FruitTreeRun.PAPAYAS_PER_PALM + 4, 0);
	}

	/**
	 * With the shopping still to do, the vault's palm takes the patch — which is what the
	 * withdraw list is then built from.
	 */
	@Test
	public void beforeTheLegTheVaultsPalmIsStillPartOfThePlan()
	{
		// Started standing on the work, which is the one arrangement where the supply trip is
		// still owed and the guide is nonetheless speaking about the stop.
		run.standAt(FruitTreeRun.AT_LLETYA);
		run.startWhereYouAre();
		run.tracker.onGameTick(null);

		assertFalse("fixture: the supply trip is still owed", run.tracker.isSupplyLegDone());
		assertNull("the palm outranks the papaya and the run can still be sent for it, so this "
				+ "patch is being kept for a palm rather than planted with a papaya",
			run.stepFor(FruitTreeRun.LLETYA));
	}

	/** Past the bank, the same palm is not a seed this run has, and the papaya gets the patch. */
	@Test
	public void afterTheLegOnlyWhatIsCarriedIsAllocated()
	{
		run.setOffFromTheBank();
		run.arriveAt(FruitTreeRun.AT_LLETYA);

		assertTrue("fixture: the run is past its supply leg", run.tracker.isSupplyLegDone());

		GuideStep step = run.stepFor(FruitTreeRun.LLETYA);
		assertNotNull("past the bank the run plants what it is carrying", step);
		assertEquals(GuideAction.PLANT, step.getAction());
		assertEquals(Seed.PAPAYA.getPlantedItemID(), step.getItemId());
		assertTrue("and there is nothing left to explain away: " + run.skips(),
			run.skips().isEmpty());
	}

	/**
	 * And the rule does not come undone by the seeds becoming easier to reach.
	 *
	 * <p>The palms move from the vault into the bank, which is a container this run could
	 * plausibly detour to. It changes nothing: the latch is one-way, so whatever a later detour
	 * fetches counts when it arrives in the pack and not a moment before.
	 */
	@Test
	public void movingTheSaplingsSomewhereReachableDoesNotBringThemBackIntoThePlan()
	{
		run.setOffFromTheBank();
		run.arriveAt(FruitTreeRun.AT_LLETYA);

		run.saplings(Seed.PALM, 0, 28, 0);
		run.tracker.onGameTick(null);

		GuideStep step = run.stepFor(FruitTreeRun.LLETYA);
		assertNotNull("the papaya keeps the patch", step);
		assertEquals(Seed.PAPAYA.getPlantedItemID(), step.getItemId());
		assertTrue("the supply leg is over once, per run", run.tracker.isSupplyLegDone());
	}
}
