package com.dooglemaps.route;

import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.ProtectionPayment;
import com.dooglemaps.data.Seed;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.Value;

/**
 * Whether a run will actually fit in your inventory.
 *
 * <h2>It is a count of item <i>types</i>, not of items</h2>
 * Seeds stack per type, so a hundred ranarr seeds is one slot, and quantity never matters
 * on the way out. Harvested crops are the same in reverse: noting them at a leprechaun
 * collapses the quantity but not the type, so every distinct crop you bring home costs a
 * slot no matter how much of it there is.
 *
 * <p>That makes the whole thing a distinct-types problem at both ends, and it makes the
 * return leg the binding half. A herb run needs three slots and could not overflow if it
 * tried. A mixed run carrying allotment, flower and herb seeds to a dozen regions comes
 * home with a dozen different crops, and that is what runs out.
 *
 * <h2>What cannot be collapsed</h2>
 * Two things resist the tidy one-slot-per-type rule. The leprechaun refuses to note fruit
 * baskets, vegetable sacks, Falador cabbages, crystal and spirit saplings, or logs, so a
 * run bringing those home pays per item rather than per type. And a payment must be handed
 * over in exactly the form asked for, so a basket of apples cannot be five loose apples.
 *
 * <h2>The seed box</h2>
 * It holds six seed types in one slot, so it is worth a flat five slots the moment a run
 * involves more than one seed — which is why it is worth knowing whether the player has
 * one rather than assuming.
 */
@Value
public class InventoryPlan
{
	/** Ordinary inventory size. */
	public static final int TOTAL_SLOTS = 28;

	/** Seed types a single seed box holds. */
	public static final int SEED_BOX_CAPACITY = 6;

	/** Rake, spade, dibber, secateurs, watering can. */
	public static final int TOOL_SLOTS = 5;

	int seedSlots;
	int compostSlots;
	int toolSlots;

	/** One per distinct payment item; they can be noted, so quantity never matters. */
	int paymentSlots;

	/** One per distinct crop coming back, whether noted or not. */
	int harvestSlots;

	public int getTotalSlots()
	{
		return seedSlots + compostSlots + toolSlots + paymentSlots + harvestSlots;
	}

	public int getSlotsFree()
	{
		return TOTAL_SLOTS - getTotalSlots();
	}

	public boolean isFeasible()
	{
		return getSlotsFree() >= 0;
	}

	/**
	 * Works out what a run will occupy.
	 *
	 * @param chosenSeeds       every seed the run will carry. More than one per patch type
	 *                          is normal: ten herb patches and four ranarr seeds means
	 *                          taking toadflax as well
	 * @param patchesPerType    how many patches of each type the run will visit
	 * @param hasSeedBox        whether the player owns a seed box
	 * @param usesLeprechaunStorage whether tools and compost live in leprechaun storage
	 *                              rather than the inventory
	 * @param protecting        whether the run pays farmers to protect what it plants
	 */
	public static InventoryPlan forRun(Set<Seed> chosenSeeds,
		Map<PatchImplementation, Integer> patchesPerType, boolean hasSeedBox,
		boolean usesLeprechaunStorage, boolean protecting)
	{
		Set<Seed> seeds = new LinkedHashSet<>(chosenSeeds);
		seeds.remove(null);

		int seedSlots = seedSlotsFor(seeds.size(), hasSeedBox);

		// One slot per distinct crop coming home. Crops that are not harvested into the
		// inventory at all - trees, which give logs only when you chop them later - do not
		// count against the run.
		Set<Produce> harvests = new LinkedHashSet<>();
		for (Seed seed : seeds)
		{
			PatchImplementation type = seed.getPatchType();
			Integer count = patchesPerType.get(type);
			if (count == null || count <= 0)
			{
				continue;
			}
			if (isHarvestedIntoInventory(type))
			{
				harvests.add(seed.getProduce());
			}
		}

		// Distinct payment items, not distinct patch types: several crops are protected with
		// the same thing, and one noted stack covers the lot.
		Set<Integer> payments = new LinkedHashSet<>();
		if (protecting)
		{
			for (Seed seed : seeds)
			{
				ProtectionPayment payment = ProtectionPayment.forSeed(seed);
				if (payment != null)
				{
					payments.add(payment.getItemID());
				}
			}
		}

		return new InventoryPlan(
			seedSlots,
			compostSlots(seeds.isEmpty(), usesLeprechaunStorage),
			usesLeprechaunStorage ? 0 : TOOL_SLOTS,
			payments.size(),
			harvests.size());
	}

	/**
	 * Compost costs nothing when the leprechaun is holding it.
	 *
	 * <p>Tool leprechauns store up to a thousand each of compost, supercompost and
	 * ultracompost, along with the bottomless bucket — so on a normal run the bucket never
	 * enters the inventory at all.
	 */
	static int compostSlots(boolean noSeeds, boolean usesLeprechaunStorage)
	{
		if (noSeeds || usesLeprechaunStorage)
		{
			return 0;
		}
		return 1;
	}

	static int seedSlotsFor(int seedTypes, boolean hasSeedBox)
	{
		if (seedTypes == 0)
		{
			return 0;
		}
		if (!hasSeedBox)
		{
			return seedTypes;
		}
		// One box, holding six types; anything beyond that rides in the inventory.
		return 1 + Math.max(0, seedTypes - SEED_BOX_CAPACITY);
	}

	/**
	 * Whether servicing this patch type fills inventory slots on the way home.
	 *
	 * <p>Trees and their relatives do not: you check their health and leave them standing,
	 * and the logs only exist once you come back to chop them. Everything else hands you
	 * produce on the spot.
	 */
	static boolean isHarvestedIntoInventory(PatchImplementation type)
	{
		switch (type)
		{
			case TREE:
			case HARDWOOD_TREE:
			case REDWOOD:
			case SPIRIT_TREE:
			case CRYSTAL_TREE:
			case ANIMA:
				return false;
			default:
				return true;
		}
	}
}
