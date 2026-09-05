package com.dooglemaps.route;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.ProtectionPayment;
import com.dooglemaps.data.Seed;
import com.dooglemaps.timer.FarmingBonuses;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The sidebar's Projected table stops promising saplings the run walked away from.
 *
 * <h2>The reported dead end, on the panel's side of it</h2>
 *
 * A fruit tree run set off with seven papaya saplings and left twenty-eight palms in the seed
 * vault, because thirteen papayas is two short of protecting a palm. One harvest later there were
 * nineteen, and the Projected table went on quoting a row of palms four regions away — the same
 * input error that had the guide reassigning patches, showing up as a promise instead of an
 * instruction. See {@code GuideTracker.supplyLegDone}.
 *
 * <h2>Why the assertion is on the estimate rather than on the panel</h2>
 *
 * Nothing in {@code RunEstimate} was wrong, and nothing in it changed: given a stock of palms it
 * correctly prices palms. The fault was entirely in what {@code RunPanel.ownedSeeds} handed it,
 * which is now the carried count once the run is past its supply leg and the owned count before
 * that. So what is worth holding still is the pair of answers either side of that switch — that
 * the two inputs really do give different tables, and which way round. A panel test would need a
 * Swing hierarchy to assert the same two numbers.
 */
public class TheProjectedPanelCountsOnlyWhatIsCarriedAfterTheSupplyLegTest
{
	/** Seven fruit tree patches, as the reported run had. */
	private static final int PATCHES = 7;

	/** Nineteen papayas: one palm's worth of protection and four left over. */
	private static final int PAPAYAS = ProtectionPayment.PALM.getQuantity() + 4;

	@Test
	public void beforeTheLegThePalmInTheVaultIsPricedIn()
	{
		Map<Seed, Integer> owned = new HashMap<>();
		owned.put(Seed.PALM, 28);
		owned.put(Seed.PAPAYA, 7);

		Map<Seed, Integer> priced = pricedPatches(owned);

		assertEquals("the palm the run can still be sent for fills what the papayas can protect",
			Integer.valueOf(1), priced.get(Seed.PALM));
		assertEquals("and the papayas fill the rest",
			Integer.valueOf(6), priced.get(Seed.PAPAYA));
	}

	@Test
	public void afterTheLegOnlyTheSaplingsInThePackAreCounted()
	{
		// The same account, the same vault, the same nineteen papayas - and the run out on the
		// road with seven papaya saplings and no palms. This is the map RunPanel now builds.
		Map<Seed, Integer> owned = new HashMap<>();
		owned.put(Seed.PALM, 0);
		owned.put(Seed.PAPAYA, 7);

		Map<Seed, Integer> priced = pricedPatches(owned);

		assertFalse("nothing is promised from the seed vault: " + priced,
			priced.containsKey(Seed.PALM));
		assertEquals("seven patches, seven papaya saplings, and the table says so",
			Integer.valueOf(7), priced.get(Seed.PAPAYA));
	}

	/** The two answers really are different, which is the whole reason the input matters. */
	@Test
	public void theTwoInputsDoNotGiveTheSameTable()
	{
		Map<Seed, Integer> anywhere = new HashMap<>();
		anywhere.put(Seed.PALM, 28);
		anywhere.put(Seed.PAPAYA, 7);

		Map<Seed, Integer> carried = new HashMap<>();
		carried.put(Seed.PALM, 0);
		carried.put(Seed.PAPAYA, 7);

		assertFalse("if these agreed there would be nothing to fix",
			pricedPatches(anywhere).equals(pricedPatches(carried)));
		assertTrue("fixture: the palm is what separates them",
			pricedPatches(anywhere).containsKey(Seed.PALM));
	}

	/** How many patches the Projected table gives each crop, for one stock of seeds. */
	private static Map<Seed, Integer> pricedPatches(Map<Seed, Integer> owned)
	{
		Set<Seed> picked = new LinkedHashSet<>();
		picked.add(Seed.PALM);
		picked.add(Seed.PAPAYA);

		Map<PatchImplementation, Integer> byType = new LinkedHashMap<>();
		byType.put(PatchImplementation.FRUIT_TREE, PATCHES);

		Map<Integer, Integer> payments = new HashMap<>();
		payments.put(ProtectionPayment.PALM.getItemID(), PAPAYAS);

		RunEstimate estimate = RunEstimate.forRun(byType, picked, owned, 99, FarmingBonuses.NONE,
			Collections.singletonMap(PatchImplementation.FRUIT_TREE, CompostTier.NONE),
			(seed, compost) -> 1.0,
			new ProtectionBudget(payments, seed -> seed == Seed.PALM));

		Map<Seed, Integer> counts = new LinkedHashMap<>();
		for (RunEstimate.Line line : estimate.getLines())
		{
			counts.merge(line.getSeed(), line.getPatches(), Integer::sum);
		}
		return counts;
	}
}
