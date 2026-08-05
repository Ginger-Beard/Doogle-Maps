package com.dooglemaps.bank;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.ProtectionPayment;
import com.dooglemaps.data.Seed;
import com.dooglemaps.guide.CarriedItems;
import com.dooglemaps.route.RunPlanner;
import com.dooglemaps.route.RunStop;
import com.dooglemaps.state.CompostSelectionStore;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.state.SeedSelectionStore;
import com.dooglemaps.state.SeedSource;
import com.dooglemaps.timer.FarmingOutfit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.gameval.ItemID;

/**
 * Everything a planned run wants, and whether you already have it.
 *
 * <p>Almost all of this is derived from things the plugin already knows — the seeds you picked,
 * the compost you chose, the generated protection payment table, the bonuses it already detects
 * and the stops the run will have. The only new data is {@link TeleportItems} and the short
 * list of storage items below, which is why this is a fold rather than a table.
 *
 * <p>The output is deliberately not "everything a farm run touches". That list would be mostly
 * noise, because the tool leprechaun holds your compost, every tool including magic secateurs,
 * and a thousand plant cures. What is worth showing is the <b>difference</b> between what the
 * run needs and what you already have — see {@link LoadoutItem.Need}.
 */
@Singleton
public class RunLoadout
{
	/**
	 * Storage items, which are worth suggesting for the runs they actually help.
	 *
	 * <p>The herb sack is not something every account can be told to bring — it wants 58
	 * Herblore, unboostable, and 750 Slayer or 250 Tithe points. Suggesting it only when it is
	 * already in the bank is what keeps that honest.
	 */
	/**
	 * Every form of the herb sack: closed, open, and the two silklined ones.
	 *
	 * <p>Four ids for one item, and the <b>open</b> one is the one that matters — it is what
	 * swallows grimy herbs before they reach the inventory, and checking only the closed id
	 * meant an account carrying the useful variant read as owning no sack at all.
	 */
	private static final int[] HERB_SACK = {
		ItemID.SLAYER_HERB_SACK,
		ItemID.SLAYER_HERB_SACK_OPEN,
		ItemID.SLAYER_HERB_SACK_SILK,
		ItemID.SLAYER_HERB_SACK_SILK_OPEN,
	};

	/** Both forms of the seed box, closed and open. */
	private static final int[] SEED_BOX = {ItemID.SEED_BOX, ItemID.SEED_BOX_OPEN};

	private final RunPlanner planner;
	private final SeedSelectionStore selection;
	private final SeedInventoryStore seeds;
	private final CompostSelectionStore compost;
	private final CarriedItems carried;
	private final BankContents bank;

	@Inject
	private RunLoadout(RunPlanner planner, SeedSelectionStore selection, SeedInventoryStore seeds,
		CompostSelectionStore compost, CarriedItems carried, BankContents bank)
	{
		this.planner = planner;
		this.selection = selection;
		this.seeds = seeds;
		this.compost = compost;
		this.carried = carried;
		this.bank = bank;
	}

	/**
	 * The loadout for a run over these patch types.
	 *
	 * <p>Takes the types rather than reading the live run, so the panel can price up a run that
	 * has not been started — which is exactly when you are standing at the bank.
	 */
	public List<LoadoutItem> forRun(Set<PatchImplementation> types)
	{
		List<LoadoutItem> items = new ArrayList<>();
		if (types.isEmpty())
		{
			return items;
		}

		addSeeds(items, types);
		addCompost(items, types);
		addPayments(items, types);
		addGear(items);
		addAxe(items, types);
		addStorage(items, types);
		addTeleports(items, types);
		return items;
	}

	/**
	 * Patch types whose run involves chopping something down.
	 *
	 * <p>A grown tree has to be cut and its stump dug out before the patch can be replanted, so
	 * an axe is not an optimisation — without one the patch cannot be cleared at all. Fruit
	 * trees are in the list because a dead one still has to come out, even though a healthy one
	 * is left alone to keep fruiting.
	 */
	private static final Set<PatchImplementation> NEEDS_AN_AXE = EnumSet.of(
		PatchImplementation.TREE,
		PatchImplementation.FRUIT_TREE,
		PatchImplementation.HARDWOOD_TREE,
		PatchImplementation.REDWOOD,
		PatchImplementation.CALQUAT);

	/**
	 * The best axe you own and can actually swing.
	 *
	 * <p>Level as well as tier: a dragon axe from a drop is dead weight at 30 Woodcutting, and
	 * naming it would send someone to the bank for something they cannot use. The leprechaun
	 * stores every other farming tool but not this one, so it genuinely has to be carried.
	 */
	private void addAxe(List<LoadoutItem> items, Set<PatchImplementation> types)
	{
		if (types.stream().noneMatch(NEEDS_AN_AXE::contains))
		{
			return;
		}

		int level = seeds.getWoodcuttingLevel();
		for (Axes.Axe axe : Axes.byTier())
		{
			// Unknown level reads as 0, which would reject everything. Better to suggest the
			// best axe owned and be occasionally wrong than to say nothing until a Woodcutting
			// level happens to be observed.
			if (level > 0 && axe.getWoodcuttingLevel() > level)
			{
				continue;
			}

			boolean have = carried.has(axe.getItemId());
			if (!have && !bank.has(axe.getItemId()))
			{
				continue;
			}

			items.add(new LoadoutItem(axe.getItemId(), axe.getName(), LoadoutItem.Category.TOOL,
				have ? LoadoutItem.Need.HAVE : LoadoutItem.Need.WITHDRAW, 0,
				"Needed to chop a grown tree before its patch can be replanted - "
					+ "the leprechaun stores every farming tool but not this"));
			return;
		}

		// Nothing usable found. Worth saying, because arriving at a tree patch without an axe
		// means the trip achieves nothing there - but only once we have actually read a bank,
		// or every fresh login claims you own no axe at all.
		items.add(new LoadoutItem(ItemID.BRONZE_AXE, "Any axe", LoadoutItem.Category.TOOL,
			bank.hasBeenSeen() ? LoadoutItem.Need.MISSING : LoadoutItem.Need.UNKNOWN, 0,
			"A grown tree has to be chopped before the patch can be replanted"));
	}

	// ---------------------------------------------------------------------- parts

	private void addSeeds(List<LoadoutItem> items, Set<PatchImplementation> types)
	{
		Map<PatchImplementation, Integer> actionable = planner.countActionable(types);

		for (PatchImplementation type : types)
		{
			int patches = actionable.getOrDefault(type, 0);
			if (patches == 0)
			{
				continue;
			}

			for (Seed seed : selection.getSelectedFor(type))
			{
				int wanted = patches * seed.getSeedsPerPatch();
				int owned = seeds.getOwnedPlantable(seed);
				int inPack = seeds.getCount(seed, SeedSource.INVENTORY)
					+ seeds.getCount(seed, SeedSource.SEED_BOX);

				items.add(new LoadoutItem(seed.getPlantedItemID(), seed.getName(),
					LoadoutItem.Category.SEED,
					need(inPack >= wanted, owned > inPack, false),
					Math.min(wanted, Math.max(owned, 1)),
					patches + (patches == 1 ? " patch" : " patches") + " of "
						+ type.getDisplayName().toLowerCase()));
			}
		}
	}

	/**
	 * Compost, which is nearly always a leprechaun item rather than a bank one.
	 *
	 * <p>Offered anyway, marked as on-site, because "you chose ultracompost and the leprechaun
	 * has it" is the useful thing to know — it stops you going hunting for a bucket.
	 */
	private void addCompost(List<LoadoutItem> items, Set<PatchImplementation> types)
	{
		Set<CompostTier> wanted = new LinkedHashSet<>();
		for (PatchImplementation type : types)
		{
			CompostTier tier = compost.get(type);
			if (tier != CompostTier.NONE)
			{
				wanted.add(tier);
			}
		}

		for (CompostTier tier : wanted)
		{
			// Both bottomless ids: 22994 is the empty bucket, 22997 the one actually holding
			// compost. Checking only the empty one meant a working bottomless bucket still
			// sent you to the leprechaun.
			boolean have = carried.hasAny(tier.getItemID(),
				ItemID.BOTTOMLESS_COMPOST_BUCKET, ItemID.BOTTOMLESS_COMPOST_BUCKET_FILLED);
			items.add(new LoadoutItem(tier.getItemID(), tier.getDisplayName(),
				LoadoutItem.Category.COMPOST,
				have ? LoadoutItem.Need.HAVE : LoadoutItem.Need.AT_LEPRECHAUN, 0,
				"The leprechaun stores 1,000 of each compost, so this rarely needs banking"));
		}
	}

	/**
	 * Protection payments, the thing most easily forgotten.
	 *
	 * <p>One entry per distinct payment across the run, because payments may be noted and a
	 * noted stack is one inventory slot however many patches it covers.
	 */
	private void addPayments(List<LoadoutItem> items, Set<PatchImplementation> types)
	{
		Set<Integer> seen = new LinkedHashSet<>();

		for (PatchImplementation type : types)
		{
			for (Seed seed : selection.getSelectedFor(type))
			{
				ProtectionPayment payment = ProtectionPayment.forSeed(seed);
				if (payment == null || !seen.add(payment.getItemID()))
				{
					continue;
				}

				items.add(new LoadoutItem(payment.getItemID(),
					payment.getProduce().getName(), LoadoutItem.Category.PAYMENT,
					need(carried.has(payment.getItemID()), bank.has(payment.getItemID()), false),
					payment.getQuantity(),
					"Protects " + seed.getName().toLowerCase()
						+ " - may be noted, but has to be the exact item"));
			}
		}
	}

	/**
	 * The things that change the numbers rather than making the run possible.
	 *
	 * <p>Magic secateurs are the awkward one. The leprechaun stores them, but the +10% only
	 * applies while they are carried or worn, so the storage is a safety net rather than a
	 * substitute — they are still worth taking.
	 */
	private void addGear(List<LoadoutItem> items)
	{
		items.add(new LoadoutItem(ItemID.FAIRY_ENCHANTED_SECATEURS, "Magic secateurs",
			LoadoutItem.Category.GEAR,
			carried.has(ItemID.FAIRY_ENCHANTED_SECATEURS)
				? LoadoutItem.Need.HAVE
				: need(false, bank.has(ItemID.FAIRY_ENCHANTED_SECATEURS), false),
			0,
			"+10% yield, and it counts in your inventory as well as worn"));

		addFarmingOutfit(items);

		for (int cape : new int[]{ItemID.SKILLCAPE_FARMING, ItemID.SKILLCAPE_FARMING_TRIMMED})
		{
			if (carried.has(cape) || bank.has(cape))
			{
				items.add(new LoadoutItem(cape, "Farming cape", LoadoutItem.Category.GEAR,
					carried.has(cape) ? LoadoutItem.Need.HAVE : LoadoutItem.Need.WITHDRAW, 0,
					"+5% yield on herbs, and it teleports to the Farming Guild"));
				break;
			}
		}
	}

	/**
	 * The Farmer's outfit, as one line rather than four.
	 *
	 * <p>Worth up to 2.5% Farming experience — jacket 0.8%, legs 0.6%, hat 0.4%, boots 0.2%,
	 * plus 0.5% for wearing all four — and was missing from the loadout entirely, so anyone who
	 * left a piece in the bank was never told.
	 *
	 * <p>One row because four would swamp a list whose other entries are one item each, and
	 * because the useful fact is "you are missing a piece", not which. The tooltip names them.
	 *
	 * <p>Reuses {@link FarmingOutfit}, which already holds the male and female id for each
	 * piece — this needed no new table, only asking the one that existed.
	 */
	private void addFarmingOutfit(List<LoadoutItem> items)
	{
		int worn = 0;
		List<String> toFetch = new ArrayList<>();
		int firstMissingId = -1;

		for (FarmingOutfit piece : FarmingOutfit.values())
		{
			if (carried.hasAny(piece.getMaleItemId(), piece.getFemaleItemId()))
			{
				worn++;
				continue;
			}

			int inBank = bank.has(piece.getMaleItemId()) ? piece.getMaleItemId()
				: bank.has(piece.getFemaleItemId()) ? piece.getFemaleItemId() : -1;
			if (inBank != -1)
			{
				toFetch.add(piece.name().toLowerCase());
				if (firstMissingId == -1)
				{
					firstMissingId = inBank;
				}
			}
		}

		if (worn == FarmingOutfit.values().length)
		{
			items.add(new LoadoutItem(FarmingOutfit.HAT.getMaleItemId(), "Farmer's outfit",
				LoadoutItem.Category.GEAR, LoadoutItem.Need.HAVE, 0,
				"All four pieces, so you have the full +2.5% experience"));
			return;
		}

		if (toFetch.isEmpty())
		{
			// Nothing worn and nothing banked. Saying "missing" would be noise for an account
			// that simply does not have the outfit, which is most of them.
			return;
		}

		items.add(new LoadoutItem(firstMissingId, "Farmer's outfit", LoadoutItem.Category.GEAR,
			LoadoutItem.Need.WITHDRAW, 0,
			"In the bank: " + String.join(", ", toFetch)
				+ ". The full set is +2.5% Farming experience"));
	}

	/**
	 * Storage, offered only where the run would actually fill it.
	 *
	 * <p>Fruit baskets and vegetable sacks matter more than they look: the leprechaun
	 * <b>cannot note</b> either, so they are the difference between carrying produce home and
	 * running out of room.
	 */
	private void addStorage(List<LoadoutItem> items, Set<PatchImplementation> types)
	{
		if (types.contains(PatchImplementation.HERB))
		{
			offerStorage(items, HERB_SACK, "Herb sack", "Holds 30 of each grimy herb");
		}

		// Fruit baskets and vegetable sacks are deliberately absent. Nobody carries them on a
		// farm run when the leprechaun notes everything — a noted stack is one slot per crop
		// type, where a basket holds five of one fruit. They matter only as protection
		// payment, which ProtectionPayment already handles with the full ids.
		offerStorage(items, SEED_BOX, "Seed box", "Keeps your seeds out of your inventory");
	}

	/**
	 * Suggests a storage item only if it exists somewhere we can see.
	 *
	 * <p>Several of these are locked behind things not every account has — the herb sack wants
	 * 58 Herblore and 750 Slayer points. Telling someone to bring one they cannot own is the
	 * failure mode this avoids.
	 */
	private void offerStorage(List<LoadoutItem> items, int[] itemIds, String name, String reason)
	{
		if (carried.hasAny(itemIds))
		{
			items.add(new LoadoutItem(itemIds[0], name, LoadoutItem.Category.STORAGE,
				LoadoutItem.Need.HAVE, 0, reason));
			return;
		}

		for (int itemId : itemIds)
		{
			if (bank.has(itemId))
			{
				// The id that is actually there, so the bank highlight lands on the right slot.
				items.add(new LoadoutItem(itemId, name, LoadoutItem.Category.STORAGE,
					LoadoutItem.Need.WITHDRAW, 0, reason));
				return;
			}
		}
	}

	/**
	 * Teleports for the places this run actually goes, that you actually own.
	 *
	 * <p>The intersection is the whole point — see {@link TeleportItems}. A stop with nothing
	 * in the table, or nothing you own, simply produces no suggestion rather than advice you
	 * cannot follow.
	 */
	private void addTeleports(List<LoadoutItem> items, Set<PatchImplementation> types)
	{
		Set<Integer> regions = new LinkedHashSet<>();
		for (RunStop stop : planner.previewStops(types))
		{
			regions.add(stop.getRegion().getRegionId());
		}

		Set<Integer> offered = new LinkedHashSet<>();
		for (int region : regions)
		{
			String where = regionName(region, types);
			for (TeleportItems.Teleport teleport : TeleportItems.forRegion(region))
			{
				addTeleportIfOwned(items, offered, teleport, "Reaches " + where);
			}
		}

		for (TeleportItems.Teleport teleport : TeleportItems.universal())
		{
			addTeleportIfOwned(items, offered, teleport, "Needed to use fairy rings");
		}
	}

	private void addTeleportIfOwned(List<LoadoutItem> items, Set<Integer> offered,
		TeleportItems.Teleport teleport, String reason)
	{
		int itemId = teleport.getItemId();
		if (!offered.add(itemId))
		{
			return;
		}

		boolean have = carried.has(itemId);
		if (!have && !bank.has(itemId))
		{
			// Not owned. Saying nothing is the point: a list of teleports to go and buy is
			// advice, and advice is wrong for anyone whose unlocks differ.
			return;
		}

		items.add(new LoadoutItem(itemId, teleport.getName(), LoadoutItem.Category.TELEPORT,
			have ? LoadoutItem.Need.HAVE : LoadoutItem.Need.WITHDRAW, 0, reason));
	}

	private String regionName(int regionId, Set<PatchImplementation> types)
	{
		for (RunStop stop : planner.previewStops(types))
		{
			if (stop.getRegion().getRegionId() == regionId)
			{
				return stop.getName();
			}
		}
		return "a stop on this run";
	}

	/**
	 * What to do about an item.
	 *
	 * <p>The last line is the subtle one. "Not in the bank" and "we have not read your bank"
	 * are different facts, and the bank is only readable while it is open — so before you have
	 * opened one this session, everything not already carried looks absent. Calling that
	 * missing would announce that your secateurs and payments had vanished, every login.
	 */
	private LoadoutItem.Need need(boolean carried, boolean inBank, boolean atLeprechaun)
	{
		if (carried)
		{
			return LoadoutItem.Need.HAVE;
		}
		if (atLeprechaun)
		{
			return LoadoutItem.Need.AT_LEPRECHAUN;
		}
		if (inBank)
		{
			return LoadoutItem.Need.WITHDRAW;
		}
		return bank.hasBeenSeen() ? LoadoutItem.Need.MISSING : LoadoutItem.Need.UNKNOWN;
	}

	/**
	 * What the bank should mark, and why.
	 *
	 * <p>Two kinds, and the second is the point. {@code WITHDRAW} is "take this". But an item
	 * the leprechaun already holds is worth marking too, in its own colour: seeing your
	 * ultracompost lit differently in the bank is what stops you withdrawing it, and cues you
	 * to ask the leprechaun once you are at the patch. Leaving it unmarked would just look like
	 * the plugin had forgotten about compost.
	 *
	 * <p>Things already carried are absent, since there is nothing to do about those.
	 */
	public Map<Integer, LoadoutItem.Need> highlights(Set<PatchImplementation> types)
	{
		Map<Integer, LoadoutItem.Need> marked = new LinkedHashMap<>();
		for (LoadoutItem item : forRun(types))
		{
			if (item.getNeed() == LoadoutItem.Need.WITHDRAW
				|| item.getNeed() == LoadoutItem.Need.AT_LEPRECHAUN)
			{
				marked.put(item.getItemId(), item.getNeed());
			}
		}
		return marked;
	}

	/** Why an item is marked, for the hover. Null when it is not part of this run. */
	@Nullable
	public LoadoutItem itemFor(Set<PatchImplementation> types, int itemId)
	{
		for (LoadoutItem item : forRun(types))
		{
			if (item.getItemId() == itemId)
			{
				return item;
			}
		}
		return null;
	}

}
