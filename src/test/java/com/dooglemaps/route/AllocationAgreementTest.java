package com.dooglemaps.route;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.ProtectionPayment;
import com.dooglemaps.data.Seed;
import com.dooglemaps.timer.FarmingBonuses;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Asserts the reward table and the guide plant the same thing.
 *
 * <p>They used to disagree, and both were internally consistent: the table allocated by rank and
 * spilled when a crop ran out, while the guide picked one seed for the whole patch type by its
 * own rule. So the panel could budget three magics and three yews while the guide said "plant
 * magic" at all six trees.
 *
 * <p>The fix was a shared allocation, and this is what stops the two drifting apart again — it
 * compares the counts rather than the code, so a change to either that does not change the other
 * fails here rather than in play.
 */
public class AllocationAgreementTest
{
	private static final int MAGIC_COST = ProtectionPayment.MAGIC.getQuantity();

	/** The case the shared allocation was built for. */
	@Test
	public void theGuideAndTheEstimateAgreeWhenPaymentsRunOut()
	{
		assertAgreement(6, MAGIC_COST * 3);
	}

	/** And when there is enough for everything, so the better crop takes the lot. */
	@Test
	public void theyAgreeWhenPaymentsArePlentiful()
	{
		assertAgreement(6, MAGIC_COST * 99);
	}

	/** And when there are none at all, so the protected crop is skipped entirely. */
	@Test
	public void theyAgreeWhenThereAreNoPayments()
	{
		assertAgreement(6, 0);
	}

	/** Fewer patches than seeds, which is the ordinary case rather than the interesting one. */
	@Test
	public void theyAgreeOnASmallRun()
	{
		assertAgreement(2, MAGIC_COST * 99);
	}

	private void assertAgreement(int patchCount, int coconuts)
	{
		Map<Seed, Integer> owned = new HashMap<>();
		owned.put(Seed.MAGIC, 10);
		owned.put(Seed.YEW, 10);

		Set<Seed> selected = new LinkedHashSet<>();
		selected.add(Seed.MAGIC);
		selected.add(Seed.YEW);

		Map<PatchImplementation, Integer> byType = new LinkedHashMap<>();
		byType.put(PatchImplementation.TREE, patchCount);

		RunEstimate estimate = RunEstimate.forRun(byType, selected, owned, 99,
			FarmingBonuses.NONE,
			Collections.singletonMap(PatchImplementation.TREE, CompostTier.NONE),
			(seed, compost) -> 0.5, budget(coconuts));

		SeedAllocation allocation = SeedAllocation.forPatches(
			treePatches(patchCount), selected, owned, 99, budget(coconuts));

		assertEquals("the guide must plant what the panel budgeted",
			countsOf(estimate), allocation.counts());
	}

	/** A fresh budget per call: it is spent down, so the two sides each need their own. */
	private static ProtectionBudget budget(int coconuts)
	{
		Map<Integer, Integer> available = new HashMap<>();
		available.put(ProtectionPayment.MAGIC.getItemID(), coconuts);

		Predicate<Seed> wanted = seed -> seed == Seed.MAGIC;
		return new ProtectionBudget(available, wanted);
	}

	private static Map<Seed, Integer> countsOf(RunEstimate estimate)
	{
		Map<Seed, Integer> counts = new LinkedHashMap<>();
		for (RunEstimate.Line line : estimate.getLines())
		{
			counts.merge(line.getSeed(), line.getPatches(), Integer::sum);
		}
		return counts;
	}

	/** Real tree patches, so the allocation sorts by the same keys the client would give it. */
	private static List<FarmPatch> treePatches(int count)
	{
		List<FarmPatch> patches = new ArrayList<>(
			FarmingWorldData.getPatches(PatchImplementation.TREE));
		if (patches.size() < count)
		{
			throw new AssertionError("only " + patches.size() + " tree patches exist");
		}
		return patches.subList(0, count);
	}
}
