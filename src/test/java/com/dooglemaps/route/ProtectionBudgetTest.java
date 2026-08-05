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
import java.util.function.Predicate;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Covers seed allocation being limited by what the player can afford to protect.
 *
 * <p>The scenario this exists for, from play: six tree patches ready, magic and yew both picked,
 * plenty of both seeds, and only 75 coconuts. 75 coconuts protects exactly three magic trees, so
 * the honest plan is three magics and three yews — not six magics, three of which would go in
 * unprotected after the player asked for protection.
 */
public class ProtectionBudgetTest
{
	private static final int MAGIC_COST = ProtectionPayment.MAGIC.getQuantity();
	private static final int YEW_COST = ProtectionPayment.YEW.getQuantity();

	/** The reported case, end to end. */
	@Test
	public void coconutsRunningOutHandsTheRestToTheNextCrop()
	{
		RunEstimate estimate = estimate(6, budgetOf(
			ProtectionPayment.MAGIC.getItemID(), MAGIC_COST * 3,
			ProtectionPayment.YEW.getItemID(), YEW_COST * 10));

		assertEquals("three magics is what 75 coconuts covers", 3, patchesFor(estimate, Seed.MAGIC));
		assertEquals("and the other three patches fall to the yews",
			3, patchesFor(estimate, Seed.YEW));
	}

	/** With enough for everything, the better crop simply takes the lot. */
	@Test
	public void plentyOfPaymentsLeavesTheOrdinaryRankingAlone()
	{
		RunEstimate estimate = estimate(6, budgetOf(
			ProtectionPayment.MAGIC.getItemID(), MAGIC_COST * 99,
			ProtectionPayment.YEW.getItemID(), YEW_COST * 99));

		assertEquals(6, patchesFor(estimate, Seed.MAGIC));
		assertEquals(0, patchesFor(estimate, Seed.YEW));
	}

	/**
	 * A crop nobody asked to protect is not limited by payments it does not need.
	 *
	 * <p>The failure worth guarding: treating "no payment budget" as "cannot plant" would stop a
	 * run planting anything the player had not bought fruit for, which is backwards.
	 */
	@Test
	public void anUnprotectedCropIsNotCapped()
	{
		RunEstimate estimate = estimate(6, new ProtectionBudget(new HashMap<>(), seed -> false));

		assertEquals("no protection asked for, so no limit",
			6, patchesFor(estimate, Seed.MAGIC));
	}

	/** And a protected crop with no payments at all yields the patches rather than hogging them. */
	@Test
	public void noPaymentsMeansTheCropIsSkippedEntirely()
	{
		RunEstimate estimate = estimate(6, budgetOf(
			ProtectionPayment.MAGIC.getItemID(), 0,
			ProtectionPayment.YEW.getItemID(), YEW_COST * 10));

		assertEquals("no coconuts, so no magics", 0, patchesFor(estimate, Seed.MAGIC));
		assertEquals("every patch goes to the yews", 6, patchesFor(estimate, Seed.YEW));
	}

	/** Protected patches are not discounted for a disease they cannot catch. */
	@Test
	public void aProtectedPatchIsNotDiscounted()
	{
		RunEstimate estimate = estimate(6, budgetOf(
			ProtectionPayment.MAGIC.getItemID(), MAGIC_COST * 6,
			ProtectionPayment.YEW.getItemID(), 0));

		for (RunEstimate.Line line : estimate.getLines())
		{
			if (line.getSeed() == Seed.MAGIC)
			{
				assertEquals("paid for, so it cannot die", 1.0, line.getSurvivalChance(), 0.0001);
			}
		}
		assertTrue(patchesFor(estimate, Seed.MAGIC) > 0);
	}

	// ------------------------------------------------------------------- helpers

	/** A budget holding these payments, with both crops flagged for protection. */
	private static ProtectionBudget budgetOf(int itemA, int countA, int itemB, int countB)
	{
		Map<Integer, Integer> available = new HashMap<>();
		available.put(itemA, countA);
		available.put(itemB, countB);

		Predicate<Seed> wanted = seed -> seed == Seed.MAGIC || seed == Seed.YEW;
		return new ProtectionBudget(available, wanted);
	}

	/**
	 * A tree run over this many patches, with magic and yew picked and plenty of both seeds.
	 *
	 * <p>Survival is forced to a losing number so that a discounted patch is obviously distinct
	 * from a protected one, which is what the survival assertion turns on.
	 */
	private static RunEstimate estimate(int patches, ProtectionBudget budget)
	{
		Map<PatchImplementation, Integer> byType = new LinkedHashMap<>();
		byType.put(PatchImplementation.TREE, patches);

		Set<Seed> selected = new LinkedHashSet<>();
		selected.add(Seed.MAGIC);
		selected.add(Seed.YEW);

		Map<Seed, Integer> owned = new HashMap<>();
		owned.put(Seed.MAGIC, 10);
		owned.put(Seed.YEW, 10);

		return RunEstimate.forRun(byType, selected, owned, 99, FarmingBonuses.NONE,
			Collections.singletonMap(PatchImplementation.TREE, CompostTier.NONE),
			(seed, compost) -> 0.5, budget);
	}

	private static int patchesFor(RunEstimate estimate, Seed seed)
	{
		int patches = 0;
		for (RunEstimate.Line line : estimate.getLines())
		{
			if (line.getSeed() == seed)
			{
				patches += line.getPatches();
			}
		}
		return patches;
	}
}
