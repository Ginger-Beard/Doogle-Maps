package com.dooglemaps.bank;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingTool;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.ProtectionPayment;
import com.dooglemaps.data.Seed;
import com.dooglemaps.guide.CarriedItems;
import com.dooglemaps.route.RunPlanner;
import com.dooglemaps.route.ProtectionBudget;
import com.dooglemaps.route.RunStop;
import com.dooglemaps.route.SeedAllocation;
import com.dooglemaps.state.CompostSelectionStore;
import com.dooglemaps.state.LeprechaunStore;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.state.ProtectionSelectionStore;
import com.dooglemaps.state.SeedSelectionStore;
import com.dooglemaps.state.SeedSource;
import com.dooglemaps.timer.FarmingOutfit;
import java.util.ArrayList;
import java.util.Collections;
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
	private final ToolNeeds tools;
	private final LeprechaunStore leprechaun;
	private final ProtectionSelectionStore protection;
	private final com.dooglemaps.data.ItemNames itemNames;
	private final com.dooglemaps.DoogleMapsConfig config;

	/** For the tick number the per-tick cache is keyed on; never asked for anything else. */
	private final net.runelite.api.Client client;

	@Inject
	private RunLoadout(RunPlanner planner, SeedSelectionStore selection, SeedInventoryStore seeds,
		CompostSelectionStore compost, CarriedItems carried, BankContents bank, ToolNeeds tools,
		LeprechaunStore leprechaun, ProtectionSelectionStore protection,
		com.dooglemaps.data.ItemNames itemNames, com.dooglemaps.DoogleMapsConfig config,
		net.runelite.api.Client client)
	{
		this.client = client;
		this.config = config;
		this.protection = protection;
		this.itemNames = itemNames;
		this.planner = planner;
		this.selection = selection;
		this.seeds = seeds;
		this.compost = compost;
		this.carried = carried;
		this.bank = bank;
		this.tools = tools;
		this.leprechaun = leprechaun;
	}

	/**
	 * The loadout for a run over these patch types.
	 *
	 * <p>Takes the types rather than reading the live run, so the panel can price up a run that
	 * has not been started — which is exactly when you are standing at the bank.
	 */
	public List<LoadoutItem> forRun(Set<PatchImplementation> types)
	{
		synchronized (this)
		{
			// One build a tick, shared by everyone who asks.
			//
			// Four callers wanted this list every tick while a bank was open — the highlight
			// overlay twice, the filter's refresh, and the guide's supply lines — and each build
			// walks the planner. Worse, addTeleports asked previewStops once for the region set
			// and regionName asked again *per region*, so a 28-stop run was 29 synchronised
			// replans a build and about a hundred a tick, all for an answer that cannot change
			// between them.
			//
			// Keyed on the tick and the types, because those are the only two things that decide
			// it from the caller's side. Everything else it reads — the bank, the seed counts, the
			// leprechaun — changes on events that advance the tick anyway.
			if (tickCache != null && cachedTick == client.getTickCount()
				&& cachedTypes.equals(types))
			{
				return tickCache;
			}
		}

		List<LoadoutItem> items = build(types);

		synchronized (this)
		{
			cachedTick = client.getTickCount();
			cachedTypes = EnumSet.noneOf(PatchImplementation.class);
			cachedTypes.addAll(types);
			tickCache = Collections.unmodifiableList(items);
			return tickCache;
		}
	}

	/** The last answer, and what it was worked out for. See {@link #forRun}. */
	private List<LoadoutItem> tickCache;
	private int cachedTick = -1;
	private Set<PatchImplementation> cachedTypes = EnumSet.noneOf(PatchImplementation.class);

	private List<LoadoutItem> build(Set<PatchImplementation> types)
	{
		List<LoadoutItem> items = new ArrayList<>();
		if (types.isEmpty())
		{
			return items;
		}

		addSeeds(items, types);
		addCompost(items, types);
		addPayments(items, types);
		addTools(items, types);
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

	/**
	 * The farming tools, checked against the leprechaun's store rather than assumed to be in it.
	 *
	 * <p>This used to be silent, on the reasoning that the leprechaun holds every tool so there
	 * is nothing useful to say. That is true of an account that has deposited them and false of
	 * one that has not — and the failure is a whole trip: arriving at a weedy patch without a
	 * rake means nothing at that stop can be raked, treated or planted.
	 *
	 * <p>Now his store is readable ({@link LeprechaunStore}) the common case stays quiet in a
	 * different way: the row says <i>on site</i>, which is a statement rather than an assumption,
	 * and the run does not go near a bank for it.
	 */
	private void addTools(List<LoadoutItem> items, Set<PatchImplementation> types)
	{
		for (ToolNeeds.Requirement requirement : tools.forRun(types))
		{
			FarmingTool tool = requirement.getTool();
			items.add(new LoadoutItem(tool.getItemID(), tool.getDisplayName(),
				LoadoutItem.Category.TOOL, needFor(requirement.getSource()), 0,
				toolReason(tool, requirement.getSource())));
		}
	}

	private static LoadoutItem.Need needFor(ToolNeeds.Source source)
	{
		switch (source)
		{
			case CARRIED:
				return LoadoutItem.Need.HAVE;
			case AT_LEPRECHAUN:
				return LoadoutItem.Need.AT_LEPRECHAUN;
			case BANK:
				return LoadoutItem.Need.WITHDRAW;
			case NOWHERE:
				return LoadoutItem.Need.MISSING;
			default:
				return LoadoutItem.Need.UNKNOWN;
		}
	}

	/**
	 * What to do about a tool, said in the tooltip.
	 *
	 * <p>The {@code NOWHERE} wording is the one that earns this feature. Every other case is a
	 * click or a withdrawal; that one means the run cannot be completed as planned and the fix is
	 * a shop rather than a bank. Named as a shop deliberately — the plugin never suggests the
	 * Grand Exchange, so an ironman gets the same advice as a main.
	 */
	private static String toolReason(FarmingTool tool, ToolNeeds.Source source)
	{
		switch (source)
		{
			case AT_LEPRECHAUN:
				return tool.getReason() + " - the leprechaun is holding yours, so pick it up "
					+ "at the first patch rather than banking for it";
			case BANK:
				return tool.getReason() + " - in your bank, and not in the leprechaun's store, "
					+ "so it has to be withdrawn";
			case NOWHERE:
				return tool.getReason() + " - you do not have one anywhere. A farming shop sells "
					+ "one for a few coins; there is one beside most patch areas";
			case UNKNOWN:
				return tool.getReason() + " - open a bank and this will say whether you have one";
			default:
				return tool.getReason();
		}
	}

	// ---------------------------------------------------------------------- parts

	private void addSeeds(List<LoadoutItem> items, Set<PatchImplementation> types)
	{
		// Per planting group, not per type. Two reasons, and the second is a bug this fixes.
		//
		// The split one: protected herbs and ordinary herbs take different seeds, and asking for
		// each against the whole type's patch count would bank a full run's worth of both.
		//
		// The one that was already wrong: this loop asks for `patches * seedsPerPatch` for *every*
		// selected seed, so picking two seeds for one type asked for two runs of seed for a
		// one-run trip. Scoping to the group does not fix that on its own — see TODO.md, "Picking
		// more than one seed for a patch type" — but it stops the split multiplying it.
		Map<PlantingGroup, List<FarmPatch>> actionable = planner.actionableByGroup(types);

		for (Map.Entry<PlantingGroup, List<FarmPatch>> entry : actionable.entrySet())
		{
			PlantingGroup group = entry.getKey();
			List<FarmPatch> plantable = entry.getValue();
			PatchImplementation type = group.getType();
			if (plantable.isEmpty())
			{
				continue;
			}

			// How the run will actually divide these patches between the picked seeds — not one
			// full run's worth of every seed, which is what this asked for and is why picking a
			// second herb had it telling you to bank eight ranarrs *and* eight snapdragons for an
			// eight-patch trip.
			//
			// The same allocation the guide plants from and the estimate prices, so the three
			// cannot disagree about what is going in the ground. See AllocationAgreementTest.
			Map<Seed, Integer> share = allocate(group, plantable).counts();

			for (Seed seed : selection.getSelectedFor(group))
			{
				int patches = share.getOrDefault(seed, 0);
				if (patches == 0)
				{
					// Picked, but this run has no patch for it — every one is spoken for by a
					// crop that ranked higher, or you have none of this seed. Saying "bring 0"
					// would be noise; saying nothing is the honest answer.
					continue;
				}
				int wanted = patches * seed.getSeedsPerPatch();
				// Both forms, not just the plantable one. A tree crop is a seed until you pot it,
				// and what is sitting in the bank is almost always the seed — so asking
				// getOwnedPlantable here reported five magic seeds as MISSING, told you to go and
				// buy some, and was wrong in the most alarming direction available. It was also
				// inconsistent on its own terms: inPack below has always counted both.
				int owned = seeds.getOwned(seed);
				int inPack = seeds.getCount(seed, SeedSource.INVENTORY)
					+ seeds.getCount(seed, SeedSource.SEED_BOX);
				// Owned, but not in a form that can go in the ground yet. Worth saying at the
				// bank, because a plant pot is the one thing you cannot fix at the patch.
				boolean needsPotting = seed.isSapling() && seeds.getOwnedPlantable(seed) < wanted;

				items.add(new LoadoutItem(seed.getPlantedItemID(), seed.getName(),
					LoadoutItem.Category.SEED,
					need(inPack >= wanted, owned > inPack, false),
					Math.min(wanted, Math.max(owned, 1)),
					// The contract says *why* rather than only how many, because it is the one
					// seed on this list the player did not choose — and the one whose absence is
					// worth knowing about at the bank rather than at the patch, since arriving
					// without it costs the whole reward for another growth cycle.
					(group.isContract()
						? "Guildmaster Jane's contract"
						: patches + (patches == 1 ? " patch" : " patches") + " of "
							+ type.getDisplayName().toLowerCase())
						+ (needsPotting ? " - needs potting into a sapling first" : "")));
			}
		}
	}

	/**
	 * How this group's patches divide between the seeds picked for it.
	 *
	 * <p>Built exactly as {@code GuideTracker} builds it, from the same stores, because the whole
	 * point is that the bank list and the guide agree. Anything else and you bank for one plan and
	 * plant another.
	 */
	private SeedAllocation allocate(PlantingGroup group, List<FarmPatch> plantable)
	{
		Set<Seed> picked = selection.getSelectedFor(group);

		Map<Seed, Integer> owned = new java.util.HashMap<>();
		Map<Integer, Integer> payments = new java.util.HashMap<>();
		for (Seed seed : picked)
		{
			// Either form, where the guide's copy of this asks for the plantable one — and the
			// difference is deliberate rather than drift. The guide is deciding what can go in
			// the ground *now*, so an acorn you have not potted is no use to it. This is deciding
			// what to take out of the bank, and an acorn is exactly the thing to take: you pot it
			// on the way. Measuring plantable here made a tree run tell you to bring nothing
			// whenever your seeds were still seeds.
			owned.put(seed, seeds.getOwned(seed));

			ProtectionPayment payment = ProtectionPayment.forSeed(seed);
			if (payment != null && protection.isProtecting(group, seed))
			{
				payments.put(payment.getItemID(),
					bank.getCount(payment.getItemID()) + carried.getCount(payment.getItemID()));
			}
		}

		return SeedAllocation.forPatches(plantable, picked, owned, seeds.getFarmingLevel(),
			new ProtectionBudget(payments, seed -> protection.isProtecting(group, seed)));
	}

	/**
	 * Compost, which is <i>usually</i> a leprechaun item rather than a bank one.
	 *
	 * <p>Offered anyway, marked as on-site, because "you chose ultracompost and the leprechaun
	 * has it" is the useful thing to know — it stops you going hunting for a bucket.
	 *
	 * <p>"Usually" is the change. This asserted on-site unconditionally, which is right for a
	 * stocked account and wrong in the worst direction for anyone else: told to leave the compost
	 * in the bank, they arrive with none and every patch on the run goes in untreated. His store
	 * is now read rather than assumed, so a tier he does not have reads as a withdrawal.
	 */
	private void addCompost(List<LoadoutItem> items, Set<PatchImplementation> types)
	{
		// Per group: a split herb type can want ultra on the protected patches and super on the
		// rest, and both have to be banked for.
		Set<CompostTier> wanted = new LinkedHashSet<>();
		for (PlantingGroup group : planner.countActionableByGroup(types).keySet())
		{
			CompostTier tier = compost.get(group);
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
				LoadoutItem.Category.COMPOST, compostNeed(tier, have), 0,
				compostReason(tier, have)));
		}
	}

	/**
	 * Whether the chosen compost is a click at the patch or a thing to withdraw.
	 *
	 * <p>The leprechaun's store is checked for the exact tier, because they are stored separately
	 * and having a thousand buckets of ordinary compost is no help to someone who picked ultra.
	 */
	private LoadoutItem.Need compostNeed(CompostTier tier, boolean carrying)
	{
		if (carrying)
		{
			return LoadoutItem.Need.HAVE;
		}
		if (leprechaun.hasCompost(tier))
		{
			return LoadoutItem.Need.AT_LEPRECHAUN;
		}
		if (bank.has(tier.getItemID()))
		{
			return LoadoutItem.Need.WITHDRAW;
		}

		// He has none and neither does the bank — but stay quiet about it until both have been
		// read. Before the first tick after login his store reads as empty, and the bank stays
		// unknown until one is opened.
		if (!leprechaun.hasBeenRead() || !bank.hasBeenSeen())
		{
			return LoadoutItem.Need.UNKNOWN;
		}
		return LoadoutItem.Need.MISSING;
	}

	private String compostReason(CompostTier tier, boolean carrying)
	{
		if (carrying)
		{
			return "Already on you";
		}
		if (leprechaun.hasCompost(tier))
		{
			return "The leprechaun is holding " + leprechaun.getCount(FarmingTool.forCompost(tier))
				+ " or more, so this does not need banking";
		}
		if (bank.has(tier.getItemID()))
		{
			return "The leprechaun has none of this tier stored, so bring it from the bank - "
				+ "one bucket per patch";
		}
		return "You chose " + tier.getDisplayName().toLowerCase()
			+ " and there is none on you, in the leprechaun's store or in the bank";
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

		// Only for groups the player said they would pay for. Listing every possible payment was
		// the old behaviour and it asked you to bank things you had no intention of using —
		// which, on a tree run, is several stacks of fruit nobody wanted.
		Map<PlantingGroup, Integer> actionable = planner.countActionableByGroup(types);
		for (PlantingGroup group : actionable.keySet())
		{
			int patches = actionable.getOrDefault(group, 0);
			for (Seed seed : selection.getSelectedFor(group))
			{
				if (!protection.isProtecting(group, seed))
				{
					continue;
				}

				ProtectionPayment payment = ProtectionPayment.forSeed(seed);
				if (payment == null || !seen.add(payment.getItemID()))
				{
					continue;
				}

				// Per patch, multiplied by them. The quantity was the payment for a *single*
				// patch however many the run had, so a four-tree run asked for 25 coconuts and
				// needed 100 — and you would find out at the fourth tree, having already
				// travelled there.
				//
				// The slot count is still one: payments may be noted, and a noted stack is one
				// slot whatever its size. That is why this is a quantity fix rather than an
				// inventory one.
				int wanted = payment.getQuantity() * patches;
				int held = carried.getCount(payment.getItemID())
					+ bank.getCount(payment.getItemID());

				// Named after the item to bring, not the crop it protects. This row said "Magic"
				// when what you need is coconuts — the accessor is getProduce() on both halves of
				// the payment, and it is the wrong half here.
				items.add(new LoadoutItem(payment.getItemID(),
					itemNames.get(payment.getItemID(), payment.getProduce().getName()),
					LoadoutItem.Category.PAYMENT,
					paymentNeed(payment, wanted, held), wanted,
					held < wanted
						? "Protects " + seed.getName().toLowerCase() + " - you have " + held
							+ " of the " + wanted + " this run needs"
						: "Protects " + seed.getName().toLowerCase()
							+ " - may be noted, but has to be the exact item"));
			}
		}
	}

	/**
	 * Whether the run can actually be protected, given how many payments exist.
	 *
	 * <p>Short is reported as <b>missing</b> rather than as a withdrawal, even when some are in
	 * the bank. Withdrawing 60 of the 100 coconuts a run needs leaves you paying for two trees
	 * and finding out about the other two on arrival — which is the wasted trip this whole
	 * section exists to prevent. Saying so before you set off is the useful answer.
	 */
	private LoadoutItem.Need paymentNeed(ProtectionPayment payment, int wanted, int held)
	{
		if (held < wanted)
		{
			return bank.hasBeenSeen() ? LoadoutItem.Need.MISSING : LoadoutItem.Need.UNKNOWN;
		}
		return carried.getCount(payment.getItemID()) >= wanted
			? LoadoutItem.Need.HAVE
			: LoadoutItem.Need.WITHDRAW;
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
		// Where they are decides what to say. The store is the interesting case: they are not on
		// you, so the +10% is not applying, but the errand is a click at the first patch rather
		// than a trip to a bank — and being sent to a bank for a pair the leprechaun is already
		// holding is exactly the kind of wasted leg this plugin exists to remove.
		boolean secateursCarried = carried.has(ItemID.FAIRY_ENCHANTED_SECATEURS);
		boolean secateursStored = leprechaun.has(FarmingTool.MAGIC_SECATEURS);
		items.add(new LoadoutItem(ItemID.FAIRY_ENCHANTED_SECATEURS, "Magic secateurs",
			LoadoutItem.Category.GEAR,
			secateursCarried
				? LoadoutItem.Need.HAVE
				: secateursStored
					? LoadoutItem.Need.AT_LEPRECHAUN
					: need(false, bank.has(ItemID.FAIRY_ENCHANTED_SECATEURS), false),
			0,
			secateursStored && !secateursCarried
				? "+10% yield, but only while they are on you - the leprechaun has your pair, "
					+ "so take them out at the first patch"
				: "+10% yield, and it counts in your inventory as well as worn"));

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
		// Asked once and kept. This used to be walked here and then walked again inside
		// regionName for every region in it, which turned one replan into one per stop.
		List<RunStop> stops = planner.previewStops(types);

		Set<Integer> regions = new LinkedHashSet<>();
		for (RunStop stop : stops)
		{
			regions.add(stop.getRegion().getRegionId());
		}

		Set<Integer> offered = new LinkedHashSet<>();
		for (int region : regions)
		{
			String where = regionName(region, stops);
			for (TeleportItems.Teleport teleport : TeleportItems.forRegion(region))
			{
				addTeleportIfOwned(items, offered, teleport, "Reaches " + where);
			}
		}

		for (TeleportItems.Teleport teleport : TeleportItems.universal())
		{
			addTeleportIfOwned(items, offered, teleport, "Needed to use fairy rings");
		}

		addListedTeleports(items, offered);
	}

	/**
	 * Whatever the player put on the teleport list, found by name in their bank.
	 *
	 * <p>The table above knows which teleports <i>reach a farming region</i>, which is a fact
	 * about the map. It cannot know that you always bring a games necklace, or that your house
	 * tab is how every trip starts — that is a fact about you, so it is a setting.
	 *
	 * <h2>Matched by name, against the bank, which is what makes it need no ids</h2>
	 *
	 * A list of item names cannot be turned into ids without an index of every item in the game,
	 * and there is no such thing to hand. But the only ids that matter here are the ones in your
	 * bank — nothing else can be filtered, laid out or highlighted — and the bank <i>is</i> that
	 * index, for exactly the items in question. So the names are read off it and matched.
	 *
	 * <p>The same reasoning as Ground Items' highlighted and hidden lists, which match on name for
	 * the same reason: what belongs on such a list is per account and cannot be derived.
	 *
	 * <p>Anything listed but not owned simply never appears — no advice to go and buy things.
	 */
	private void addListedTeleports(List<LoadoutItem> items, Set<Integer> offered)
	{
		for (int itemId : listedTeleportIds())
		{
			if (offered.contains(itemId))
			{
				// Already offered by the region table, which knows where it goes — a better
				// reason than "you listed it" for the same item.
				continue;
			}

			offered.add(itemId);
			items.add(new LoadoutItem(itemId, itemNames.get(itemId, "Teleport"),
				LoadoutItem.Category.TELEPORT,
				carried.has(itemId) ? LoadoutItem.Need.HAVE : LoadoutItem.Need.WITHDRAW, 0,
				"On your teleport list"));
		}
	}

	/** The setting and bank the cached answer below was worked out from. */
	private String resolvedFrom;
	private Set<Integer> resolvedBank;
	private Set<Integer> resolvedIds = Collections.emptySet();

	/**
	 * Which bank items the teleport list names, wildcards included.
	 *
	 * <p><b>Cached, and it has to be.</b> This is asked once a tick by the bank highlight and again
	 * whenever the panel repaints, and a wildcard match compiles a regular expression every time it
	 * is called — {@code WildcardMatcher} builds the pattern from scratch per call. Against a
	 * thousand bank items that is a thousand compilations a tick, for an answer that changes only
	 * when the setting is edited or the bank is opened. So the inputs are compared and the answer
	 * reused; comparing a set of ids is far cheaper than the work it avoids.
	 *
	 * <p>Exact names are matched first, from a set, and only entries actually containing a
	 * {@code *} go anywhere near the matcher — the same split Ground Items makes, and for the same
	 * reason. Most of a list is exact, so most of it costs a hash lookup.
	 */
	private synchronized Set<Integer> listedTeleportIds()
	{
		// Null-safe because a config proxy can legitimately answer null for a string, and an
		// empty list is a real answer meaning "only the ones you already know about".
		String setting = config.teleportItems();
		setting = setting == null ? "" : setting.trim();

		Set<Integer> bankIds = bank.getItemIds();
		if (setting.equals(resolvedFrom) && bankIds.equals(resolvedBank))
		{
			return resolvedIds;
		}
		resolvedFrom = setting;
		resolvedBank = bankIds;
		resolvedIds = resolve(setting, bankIds);
		return resolvedIds;
	}

	private Set<Integer> resolve(String setting, Set<Integer> bankIds)
	{
		if (setting.isEmpty())
		{
			return Collections.emptySet();
		}

		Set<String> exact = new LinkedHashSet<>();
		List<String> wildcards = new ArrayList<>();
		for (String entry : setting.split(","))
		{
			String trimmed = entry.trim();
			if (trimmed.isEmpty())
			{
				continue;
			}
			if (trimmed.indexOf('*') >= 0)
			{
				wildcards.add(trimmed);
			}
			else
			{
				exact.add(trimmed.toLowerCase());
			}
		}

		Set<Integer> found = new LinkedHashSet<>();
		for (int itemId : bankIds)
		{
			String name = itemNames.get(itemId, null);
			if (name == null)
			{
				// Not read yet. Names are learned when a bank is opened, so this resolves itself
				// the moment there is a bank to resolve it against.
				continue;
			}

			if (exact.contains(name.toLowerCase()) || matchesAny(wildcards, name))
			{
				found.add(itemId);
			}
		}
		return found;
	}

	private static boolean matchesAny(List<String> wildcards, String name)
	{
		for (String pattern : wildcards)
		{
			if (net.runelite.client.util.WildcardMatcher.matches(pattern, name))
			{
				return true;
			}
		}
		return false;
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

	private String regionName(int regionId, List<RunStop> stops)
	{
		for (RunStop stop : stops)
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
				for (int itemId : bankFormsOf(item.getItemId()))
				{
					marked.put(itemId, item.getNeed());
				}
			}
		}
		return marked;
	}

	/**
	 * Every form of an item that could be the one in your bank.
	 *
	 * <p>One id for everything except a tree crop, which exists as two: the seed you buy and the
	 * sapling it becomes in a plant pot. A {@code LoadoutItem} names the <b>planted</b> form,
	 * because that is what you carry to the patch and what the panel should draw — but it is the
	 * wrong thing to match a bank against, where the seed is what is actually sitting there.
	 *
	 * <p>Getting this wrong was invisible in the loadout and loud in the bank: the filter hid the
	 * magic seeds it was supposed to be showing you, and the highlight never marked them, on
	 * exactly the runs where the seed is expensive enough to care about.
	 *
	 * <p>Both forms are returned rather than whichever you happen to hold, because both are
	 * legitimately "the thing this run needs" — you may have potted some already.
	 */
	static Set<Integer> bankFormsOf(int itemId)
	{
		Set<Integer> forms = new LinkedHashSet<>();
		forms.add(itemId);

		Seed seed = Seed.forItemId(itemId);
		if (seed != null && seed.isSapling())
		{
			forms.add(seed.getItemID());
			forms.add(seed.getPlantedItemID());
		}
		return forms;
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
