package com.dooglemaps.route;

import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.ProtectionPayment;
import com.dooglemaps.data.Seed;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class InventoryPlanTest
{
	private static Map<PatchImplementation, Integer> counts(Object... pairs)
	{
		Map<PatchImplementation, Integer> map = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
		{
			map.put((PatchImplementation) pairs[i], (Integer) pairs[i + 1]);
		}
		return map;
	}

	/** Takes the old type/seed pairs and keeps only the seeds; the type comes off the seed. */
	private static Set<Seed> seeds(Object... pairs)
	{
		Set<Seed> set = new LinkedHashSet<>();
		for (int i = 1; i < pairs.length; i += 2)
		{
			set.add((Seed) pairs[i]);
		}
		return set;
	}

	@Test
	public void aHerbRunBarelyTouchesTheInventory()
	{
		// One seed type out and one crop back, with the compost and tools in the leprechaun.
		// Quantity is irrelevant at both ends: a hundred ranarr seeds is one slot, and the
		// herbs come home noted as one more.
		InventoryPlan plan = InventoryPlan.forRun(
			seeds(PatchImplementation.HERB, Seed.RANARR),
			counts(PatchImplementation.HERB, 10),
			false, true, false);

		assertEquals(1, plan.getSeedSlots());
		assertEquals(1, plan.getHarvestSlots());
		assertEquals("compost rides in the leprechaun", 0, plan.getCompostSlots());
		assertEquals(2, plan.getTotalSlots());
		assertTrue(plan.isFeasible());
	}

	@Test
	public void theSeedBoxIsWorthFiveSlots()
	{
		assertEquals("six seed types loose", 6, InventoryPlan.seedSlotsFor(6, false));
		assertEquals("six seed types boxed", 1, InventoryPlan.seedSlotsFor(6, true));
		assertEquals("a seventh type rides outside the box", 2, InventoryPlan.seedSlotsFor(7, true));
		assertEquals("no seeds, no slot", 0, InventoryPlan.seedSlotsFor(0, true));
	}

	@Test
	public void treesCostNothingOnTheWayHome()
	{
		// You check a tree's health and leave it standing; the logs only exist once you come
		// back to chop it, so a tree run brings nothing back.
		InventoryPlan plan = InventoryPlan.forRun(
			seeds(PatchImplementation.TREE, Seed.MAGIC),
			counts(PatchImplementation.TREE, 6),
			false, true, false);

		assertEquals(0, plan.getHarvestSlots());
	}

	@Test
	public void aMixedRunIsBoundedByWhatComesBackNotWhatGoesOut()
	{
		// Allotments, flowers and herbs share regions, so adding them costs no travel — but
		// each is a distinct crop home, and that is the half that fills up.
		InventoryPlan plan = InventoryPlan.forRun(
			seeds(
				PatchImplementation.HERB, Seed.RANARR,
				PatchImplementation.ALLOTMENT, Seed.SNAPE_GRASS,
				PatchImplementation.FLOWER, Seed.LIMPWURT,
				PatchImplementation.HOPS, Seed.BARLEY,
				PatchImplementation.BUSH, Seed.POISON_IVY),
			counts(
				PatchImplementation.HERB, 10,
				PatchImplementation.ALLOTMENT, 17,
				PatchImplementation.FLOWER, 9,
				PatchImplementation.HOPS, 5,
				PatchImplementation.BUSH, 5),
			true, true, false);

		assertEquals("five seed types collapse into the box", 1, plan.getSeedSlots());
		assertEquals("five distinct crops come home", 5, plan.getHarvestSlots());
		assertTrue(plan.isFeasible());
	}

	@Test
	public void aNotedPaymentIsOneSlotHoweverBigTheStack()
	{
		// Twenty-five coconuts for a magic tree travel as a single noted stack, so protecting
		// a tree run costs exactly one slot more than not protecting it.
		InventoryPlan unprotected = InventoryPlan.forRun(
			seeds(PatchImplementation.TREE, Seed.MAGIC),
			counts(PatchImplementation.TREE, 6), false, true, false);
		InventoryPlan protectedRun = InventoryPlan.forRun(
			seeds(PatchImplementation.TREE, Seed.MAGIC),
			counts(PatchImplementation.TREE, 6), false, true, true);

		assertEquals(0, unprotected.getPaymentSlots());
		assertEquals(1, protectedRun.getPaymentSlots());
		assertEquals(25, ProtectionPayment.forSeed(Seed.MAGIC).getQuantity());
	}

	@Test
	public void cropsSharingAPaymentShareTheSlot()
	{
		// Watermelon and ironwood are both paid for in curry leaf, so protecting both costs
		// one slot, not two - the count is of distinct items, not of patch types.
		assertEquals(ProtectionPayment.forSeed(Seed.WATERMELON).getItemID(),
			ProtectionPayment.forSeed(Seed.IRONWOOD).getItemID());

		InventoryPlan plan = InventoryPlan.forRun(
			seeds(PatchImplementation.ALLOTMENT, Seed.WATERMELON,
				PatchImplementation.HARDWOOD_TREE, Seed.IRONWOOD),
			counts(PatchImplementation.ALLOTMENT, 17, PatchImplementation.HARDWOOD_TREE, 5),
			true, true, true);

		assertEquals(1, plan.getPaymentSlots());
	}

	@Test
	public void herbsHaveNoPaymentBecauseTheyCannotBeProtected()
	{
		assertNull(ProtectionPayment.forSeed(Seed.RANARR));

		InventoryPlan plan = InventoryPlan.forRun(
			seeds(PatchImplementation.HERB, Seed.RANARR),
			counts(PatchImplementation.HERB, 10), false, true, true);

		assertEquals("asking to protect herbs costs nothing, because you cannot",
			0, plan.getPaymentSlots());
	}

	@Test
	public void carryingToolsAndCompostCostsSixSlots()
	{
		InventoryPlan withStorage = InventoryPlan.forRun(
			seeds(PatchImplementation.HERB, Seed.RANARR),
			counts(PatchImplementation.HERB, 10), false, true, false);
		InventoryPlan carrying = InventoryPlan.forRun(
			seeds(PatchImplementation.HERB, Seed.RANARR),
			counts(PatchImplementation.HERB, 10), false, false, false);

		// Five tools plus the compost bucket the leprechaun would otherwise be holding.
		assertEquals(InventoryPlan.TOOL_SLOTS + 1,
			carrying.getTotalSlots() - withStorage.getTotalSlots());
	}
}
