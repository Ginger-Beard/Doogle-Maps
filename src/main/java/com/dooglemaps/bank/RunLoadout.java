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
@lombok.extern.slf4j.Slf4j
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
	// Closed and open. Not ItemID.SEEDBOX, which is the Seed pack (22993) wearing the cache's
	// internal name — with it here, owning a contract reward passed for owning a seed box.
	private static final int[] SEED_BOX = {ItemID.SEED_BOX, ItemID.SEED_BOX_OPEN};

	/**
	 * The forestry basket first: it <i>is</i> a log basket (combined with the forestry kit),
	 * so an account that owns one is offered it alone rather than being told to bring two
	 * containers for the same logs.
	 */
	private static final int[] FORESTRY_BASKET = {
		ItemID.FORESTRY_BASKET_CLOSED,
		ItemID.FORESTRY_BASKET_OPEN,
	};

	/** Both forms of the log basket, closed and open. */
	private static final int[] LOG_BASKET = {
		ItemID.LOG_BASKET_CLOSED,
		ItemID.LOG_BASKET_OPEN,
	};

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

	/**
	 * Which groups are being visited for their harvest alone.
	 *
	 * <p>Missing entirely before, which is what had a harvest-only fruit tree run asking you to
	 * withdraw palm saplings. The planner already narrows those stops to ripe patches — see
	 * {@code RunPlanner.isActionable} — so this list came through as "four fruit tree patches to
	 * deal with", and everything downstream read that as four patches to plant in.
	 */
	private final com.dooglemaps.state.RunTypeStore runTypes;

	/** Which crop Jane wants, so a finished contract stops asking for its own seed. */
	private final com.dooglemaps.state.ContractState contracts;

	/** For the tick number the per-tick cache is keyed on; never asked for anything else. */
	private final net.runelite.api.Client client;

	@Inject
	RunLoadout(RunPlanner planner, SeedSelectionStore selection, SeedInventoryStore seeds,
		CompostSelectionStore compost, CarriedItems carried, BankContents bank, ToolNeeds tools,
		LeprechaunStore leprechaun, ProtectionSelectionStore protection,
		com.dooglemaps.data.ItemNames itemNames, com.dooglemaps.DoogleMapsConfig config,
		net.runelite.api.Client client, com.dooglemaps.state.RunTypeStore runTypes,
		com.dooglemaps.state.ContractState contracts,
		com.dooglemaps.state.CompostRunStore compostRun, BoatHolds boatHolds)
	{
		this.boatHolds = boatHolds;
		this.compostRun = compostRun;
		this.contracts = contracts;
		this.runTypes = runTypes;
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

	/**
	 * The categories a run genuinely cannot get on with, so the supply leg waits for them.
	 *
	 * <p>Not every line on the list. Teleports are a convenience and the player may well mean to
	 * walk; the yield gear is optional by definition; the herb sack and seed box only change how
	 * much you can carry back. Holding the run at the bank for any of those would be the plugin
	 * refusing to let you play, and it is exactly the noise this list is otherwise careful to
	 * avoid.
	 *
	 * <p>What is left is the set whose absence makes a stop pointless when you reach it: no seed,
	 * no compost, no payment, no tool — and the axe, which is a {@code TOOL} here and is not in
	 * {@code ToolNeeds} at all, because the leprechaun does not stock one.
	 */
	private static final Set<LoadoutItem.Category> CANNOT_PROCEED_WITHOUT = EnumSet.of(
		LoadoutItem.Category.SEED,
		// A bin's fill for the same reason a seed is: reaching an empty bin without it
		// achieves nothing there. It used to inherit this by being filed under COMPOST.
		LoadoutItem.Category.BIN_FILL,
		LoadoutItem.Category.COMPOST,
		LoadoutItem.Category.PAYMENT,
		LoadoutItem.Category.TOOL);

	/**
	 * Whether the supply leg still has something it must collect.
	 *
	 * <h2>Why the planner asks this rather than working it out again</h2>
	 *
	 * {@code RunPlanner.suppliesOutstanding} used to derive its own answer from two of the inputs
	 * this list is built from — a tool that exists only in the bank, and a seed the run is short
	 * of. That is most of the answer and it was wrong in the same way three times over, because
	 * anything the loadout learned to ask for afterwards was not added to it:
	 *
	 * <ul>
	 *   <li>the <b>axe</b>, which {@code ToolNeeds} has never known about, so a tree contract could
	 *       close the leg and send you to check a grown magic tree bare-handed;</li>
	 *   <li>the <b>protection payment</b>, so the leg could finish with none of the cactus spine
	 *       the run was about to need; and</li>
	 *   <li>the <b>contract's own seed</b>, which the planner resolves by patch type and therefore
	 *       cannot see — see {@code SeedSelectionStore.getSelectedFor(PlantingGroup)}.</li>
	 * </ul>
	 *
	 * <p>Asking the list instead means there is one answer to "what does this trip need", and the
	 * thing that decides you are finished is the same thing that told you what to take.
	 *
	 * <p><b>Nothing unobtainable blocks.</b> Only {@link LoadoutItem.Need#WITHDRAW} counts, which
	 * is by construction something that is somewhere we can see and reach. An item you own none of
	 * is {@code MISSING} and one we have not looked for yet is {@code UNKNOWN}; neither can hold
	 * the run at a bank, which is the property that stops this being a leg that never ends.
	 */
	public boolean anythingLeftToWithdraw(Set<PatchImplementation> types)
	{
		for (LoadoutItem item : forRun(types))
		{
			if (item.getNeed() == LoadoutItem.Need.WITHDRAW
				&& CANNOT_PROCEED_WITHOUT.contains(item.getCategory()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * How many of this item the run still wants, counted <b>now</b> rather than a tick ago.
	 *
	 * <p>What the withdraw-quantity swap sizes its click from. See
	 * {@code docs/withdraw-quantity-swap-spec.md} §8, which is the reason this exists at all
	 * rather than the swap simply reading {@code getWithdrawCount} off the row.
	 *
	 * <h2>Half cached and half live, because the two halves move at different speeds</h2>
	 *
	 * {@link #forRun} is cached on {@code (tick, types)} and has to be — four callers want the
	 * whole list every tick while a bank is open, and each build walks the planner. But a player
	 * clicking Withdraw-10 and then Withdraw-5 does both inside one 600ms tick, and a swap reading
	 * a tick-old {@code outstanding} would still believe fifteen were wanted on the second click.
	 * It would offer ten again and take twenty-five: the exact over-withdrawal the feature exists
	 * to prevent, caused by the feature.
	 *
	 * <p>So the run's <b>intent</b> comes from the cached row — {@code quantity} is fixed for the
	 * whole run, thirty cactus spines is thirty whatever you are holding — and what is
	 * <b>carried</b> is read live from {@code CarriedItems}, which updates on
	 * {@code ItemContainerChanged} and therefore on the withdrawal itself rather than on the tick.
	 * Only the fast-moving half is read fast.
	 *
	 * <h2>What is eligible, and what is deliberately not</h2>
	 *
	 * Bin fills, empty buckets, ash and protection payments, plus saplings — all things where
	 * taking too many costs pack space the run needs. <b>Seeds are not</b>, and that is the owner's
	 * call rather than an oversight: seeds are the item where over-withdrawing is nearly free and
	 * under-withdrawing costs a patch, which is the opposite of the bucket case. Saplings are
	 * excluded from that exception because a sapling is one patch, one item — there is no stack to
	 * be generous with.
	 *
	 * <p>Rows with no computed count answer 0 and are left alone by construction. The compost tier
	 * rows are the live case: {@code getWithdrawCount}'s own note records that their true bucket
	 * count is not worked out yet, so there is nothing to size a click from and nothing pretends
	 * otherwise.
	 *
	 * @return how many are still wanted, or 0 for anything this must not size
	 */
	public int stillWantedNow(int itemId, Set<PatchImplementation> types)
	{
		for (LoadoutItem item : forRun(types))
		{
			// Skipped rather than answered: an item can appear on the list more than once — a
			// HAVE row from one group beside a WITHDRAW row from another — and the collectible
			// row is the one the question is about.
			if (item.getItemId() != itemId || !stillToCollect(item.getNeed()))
			{
				continue;
			}
			return sizeRow(item);
		}
		return 0;
	}

	/**
	 * As above, for the run that is actually under way.
	 *
	 * <p>Saves the caller holding a planner of its own purely to ask it which types are covered —
	 * this class already has one, and the two would only ever agree.
	 */
	public int stillWantedNow(int itemId)
	{
		return stillWantedNow(itemId, planner.coveredTypes());
	}

	/**
	 * As above, by the item's <b>name</b>, for the interface that names it and nothing else.
	 *
	 * <p>The leprechaun's store. Its menu entries carry no item id on the entry or the widget, so
	 * matching by id stood the swap down at the one place the feature was built for — see
	 * {@code GuideMenuSwap.itemNameOf}, which is where the name comes from. The rows' names are
	 * {@code ItemNames}' rendering of the same items the menu is naming, so the two agree by
	 * construction.
	 *
	 * <p>First match wins, which is safe at the sizes involved: the sizeable categories — fills,
	 * buckets, ash, payments, saplings — never put two rows with one name on the same list.
	 */
	public int stillWantedNow(@javax.annotation.Nullable String name)
	{
		return stillWantedNow(name, planner.coveredTypes());
	}

	public int stillWantedNow(@javax.annotation.Nullable String name,
		Set<PatchImplementation> types)
	{
		if (name == null || name.isEmpty())
		{
			return 0;
		}
		for (LoadoutItem item : forRun(types))
		{
			if (!rowIsNamed(item, name) || !stillToCollect(item.getNeed()))
			{
				continue;
			}
			return sizeRow(item);
		}
		return 0;
	}

	/**
	 * Whether a menu's printed name means this row's item.
	 *
	 * <h2>The row's label is the plugin's wording, and a menu never prints that</h2>
	 *
	 * The name arriving here is read off a menu entry's target, and a menu prints the
	 * <b>game's</b> name for the item. The bucket row is where the two part company: the row
	 * says "Empty bucket" so the bank list reads unambiguously, and the game calls item 1925
	 * plain "Bucket" — so the leprechaun's store offered <i>'Bucket'</i> and the row lookup
	 * matched nothing. From the session log, which is what the decision line exists for:
	 * {@code item id=-1 name='Bucket' wanted=0 -> nothing to promote}.
	 *
	 * <p>So both names answer: the row's own label, and the game's name for the row's item id.
	 * The game name comes from {@code ItemNames} where it has been recorded, and from the client
	 * outright when this is the client thread — which the name path's one caller
	 * ({@code GuideMenuSwap}, on {@code PostMenuSort}) always is. Off the client thread an
	 * unrecorded name simply does not match, which fails toward no swap.
	 */
	private boolean rowIsNamed(LoadoutItem item, String name)
	{
		if (name.equalsIgnoreCase(item.getName()))
		{
			return true;
		}

		String gameName = itemNames.get(item.getItemId());
		if (gameName == null && client.isClientThread())
		{
			net.runelite.api.ItemComposition definition =
				client.getItemDefinition(item.getItemId());
			gameName = definition == null ? null : definition.getName();
		}
		return gameName != null && name.equalsIgnoreCase(gameName);
	}

	/**
	 * Whether this withdrawal wants at least a whole pack, so All is the right click.
	 *
	 * <h2>Why All is safe exactly here and nowhere else</h2>
	 *
	 * For an <b>unstackable</b> item, Withdraw-All takes what fits and stops — the pack is the
	 * cap. So once the run wants as much as the pack holds, All cannot overshoot: it takes
	 * {@code min(freeSlots, bank)}, and {@code wanted >= freeSlots} makes that at most wanted.
	 * The no-overshoot property the ladder was built on survives intact, and a thirty-melon bin
	 * load becomes one click instead of three. Asked for by the owner in exactly those terms.
	 *
	 * <p>For a stackable it is the opposite: All takes the <i>whole bank stack</i> into one
	 * slot, however large, and "wanted vs free slots" measures nothing. Volcanic ash is the one
	 * stackable these categories hold, so it is excluded by id rather than by asking the client
	 * for a definition — deterministic, and honest about being a list of one.
	 *
	 * <p>Fills and buckets only. A payment is counted <b>noted</b> — that is how anyone carries
	 * thirty of them — and a bank in noted-withdrawal mode hands All of a noted item over as one
	 * stack, which is the overshoot again by another door. Saplings never want a packful.
	 */
	public boolean fillsThePack(int itemId, Set<PatchImplementation> types)
	{
		for (LoadoutItem item : forRun(types))
		{
			if (item.getItemId() != itemId || !stillToCollect(item.getNeed()))
			{
				continue;
			}
			return fillsThePack(item);
		}
		return false;
	}

	public boolean fillsThePack(int itemId)
	{
		return fillsThePack(itemId, planner.coveredTypes());
	}

	/** As above by the item's printed name, for the interface that names it and nothing else. */
	public boolean fillsThePack(@javax.annotation.Nullable String name)
	{
		if (name == null || name.isEmpty())
		{
			return false;
		}
		for (LoadoutItem item : forRun(planner.coveredTypes()))
		{
			if (!rowIsNamed(item, name) || !stillToCollect(item.getNeed()))
			{
				continue;
			}
			return fillsThePack(item);
		}
		return false;
	}

	/**
	 * Whether the run tracked this withdrawal and now has all of it.
	 *
	 * <h2>Done is not the same as "answers zero", and the difference is every other item</h2>
	 *
	 * {@code stillWantedNow} answers zero for three unlike things: a row that has been
	 * satisfied, a row the swap must never size (a seed, deliberately), and an item that was
	 * never the run's business at all. The Examine swap exists for the first alone — swapping a
	 * seed's or a stranger's left click to Examine would break ordinary banking — so this asks
	 * the narrow question: a <b>sizeable</b> row with a real count, whose count is now covered.
	 *
	 * <p>Covered either way it shows: the cached row still reads {@code WITHDRAW} for the rest
	 * of the tick after the last withdrawal (the carried count is live, the need is not), and
	 * {@code HAVE} from the next build on. Both mean the same thing to a click.
	 */
	public boolean doneWithdrawing(int itemId, Set<PatchImplementation> types)
	{
		for (LoadoutItem item : forRun(types))
		{
			if (item.getItemId() == itemId && rowIsDone(item))
			{
				return true;
			}
		}
		return false;
	}

	public boolean doneWithdrawing(int itemId)
	{
		return doneWithdrawing(itemId, planner.coveredTypes());
	}

	/** As above by the item's printed name, for the interface that names it and nothing else. */
	public boolean doneWithdrawing(@javax.annotation.Nullable String name)
	{
		if (name == null || name.isEmpty())
		{
			return false;
		}
		for (LoadoutItem item : forRun(planner.coveredTypes()))
		{
			if (rowIsNamed(item, name) && rowIsDone(item))
			{
				return true;
			}
		}
		return false;
	}

	private boolean rowIsDone(LoadoutItem item)
	{
		if (!sizeable(item) || item.getQuantity() <= 0)
		{
			return false;
		}
		if (item.getNeed() == LoadoutItem.Need.HAVE)
		{
			return true;
		}
		return stillToCollect(item.getNeed()) && sizeRow(item) == 0;
	}

	private boolean fillsThePack(LoadoutItem item)
	{
		if (item.getCategory() != LoadoutItem.Category.BIN_FILL
			&& item.getCategory() != LoadoutItem.Category.COMPOST)
		{
			return false;
		}
		if (item.getItemId() == com.dooglemaps.data.CompostBin.VOLCANIC_ASH)
		{
			return false;
		}

		int wanted = sizeRow(item);
		int free = carried.getFreeSlots();
		return wanted > 0 && free > 0 && wanted >= free;
	}

	/** The sizing shared by the id and name lookups above: wanted less carried, or 0. */
	private int sizeRow(LoadoutItem item)
	{
		if (!sizeable(item))
		{
			return 0;
		}

		int wanted = item.getQuantity();
		if (wanted <= 0)
		{
			return 0;
		}

		// Noted only where a note is as good, which is payments and nothing else — the
		// gardener takes them noted. A noted bucket composts nothing and a noted seed plants
		// nothing, so everywhere else the pack count is the un-noted one. Both readings are
		// CarriedItems' own; see getCountIncludingNoted.
		int held = item.getCategory() == LoadoutItem.Category.PAYMENT
			? carried.getCountIncludingNoted(item.getItemId())
			: carried.getInventoryCount(item.getItemId());

		return Math.max(0, wanted - held);
	}

	/**
	 * Whether a need means "you have not got them all yet", wherever they are coming from.
	 *
	 * <h2>{@code AT_LEPRECHAUN} is not "nothing to do"</h2>
	 *
	 * This asked for {@code WITHDRAW} alone at first, and that quietly excluded the case the
	 * feature was built for. Empty buckets and compost come out of the <b>leprechaun's</b> store —
	 * he keeps a thousand of each, which is why banking them is wasted space and why their row
	 * says {@code AT_LEPRECHAUN} rather than {@code WITHDRAW}. So the one place a bin run does
	 * most of its collecting was the one place the swap stood down. Reported from play: no swap on
	 * the empty buckets for a compost trip.
	 *
	 * <p>The name is about where they are, not about whether they are wanted. Both needs mean the
	 * same thing to a click: some are still missing and there is somewhere in front of you to get
	 * them from.
	 *
	 * <p>{@code MISSING} and {@code UNKNOWN} are deliberately not here. Neither has anywhere to
	 * take them from, so neither can have a menu open on it.
	 */
	private static boolean stillToCollect(LoadoutItem.Need need)
	{
		return need == LoadoutItem.Need.WITHDRAW || need == LoadoutItem.Need.AT_LEPRECHAUN;
	}

	/** Whether a row is one the quantity swap may size a click from. See {@link #stillWantedNow}. */
	private static boolean sizeable(LoadoutItem item)
	{
		switch (item.getCategory())
		{
			case BIN_FILL:
			case COMPOST:
			case PAYMENT:
				return true;
			case SEED:
				// Saplings only. A tree seed is one patch and one item; every other seed is a
				// stack the owner would rather have too much of than too little.
				Seed seed = Seed.forItemId(item.getItemId());
				return seed != null && seed.isSapling();
			default:
				return false;
		}
	}

	/**
	 * Whether a <b>tool</b> the run cannot proceed without is still sitting in a bank.
	 *
	 * <h2>Narrower than {@link #anythingLeftToWithdraw} on purpose</h2>
	 *
	 * That one answers "would a bank trip help", and is asked at the two ends of the supply leg.
	 * This one is asked <b>once a tick, mid-run</b>, by {@code RunPlanner.reviewSupplies} — so it
	 * has to be restricted to the case where diverting is unarguable. A tool is that case: without
	 * an axe the tree cannot be chopped, without a spade the patch cannot be cleared, and no
	 * amount of standing there changes it. A seed or a payment is not, because the run can
	 * legitimately press on and skip that patch, and yanking the player to a bank for one would
	 * undo "standing on work beats going shopping".
	 *
	 * <p>The case it exists for is the axe. {@code ToolNeeds} has never known about it — see
	 * {@code reviewSupplies}, which asks that class and therefore could not see it — so a contract
	 * taken from Jane for a patch still holding last run's tree left the player at the guild being
	 * told to chop something they had nothing to chop with, with no trip back offered. Reported
	 * from play.
	 */
	public boolean toolsLeftToWithdraw(Set<PatchImplementation> types)
	{
		for (LoadoutItem item : forRun(types))
		{
			if (item.getNeed() == LoadoutItem.Need.WITHDRAW
				&& item.getCategory() == LoadoutItem.Category.TOOL)
			{
				return true;
			}
		}
		return false;
	}

	/** The last answer, and what it was worked out for. See {@link #forRun}. */
	private List<LoadoutItem> tickCache;
	private int cachedTick = -1;
	private Set<PatchImplementation> cachedTypes = EnumSet.noneOf(PatchImplementation.class);

	/**
	 * Drops the cached answer the moment any container changes.
	 *
	 * <p>The tick key alone assumed "everything this reads changes on events that advance the
	 * tick anyway", which is false in the one direction that matters: {@code
	 * ItemContainerChanged} fires several times <b>within</b> a tick. A withdrawal invalidated
	 * nothing — the bank capture's own "act on it in the same tick" refresh read the
	 * pre-withdrawal list back out of this cache, so the highlight, the filter and the
	 * supply-leg exit all lagged the click by a tick. Registered on the event bus by the
	 * plugin, beside the capture classes.
	 */
	@net.runelite.client.eventbus.Subscribe
	public void onItemContainerChanged(net.runelite.api.events.ItemContainerChanged event)
	{
		synchronized (this)
		{
			tickCache = null;
			cachedTick = -1;
		}
	}

	/**
	 * Whether this group is being visited for its harvest alone, so nothing goes in the ground.
	 *
	 * <p>Three things follow from planting and none of them apply to a harvest-only stop: the seed,
	 * the compost that goes under it, and the payment that protects it. Asking for any of them is
	 * asking the player to bank for something the run has explicitly decided not to do — reported
	 * as a fruit tree harvest run wanting palm saplings, which is the case that makes it obvious,
	 * because a fruit tree you are picking is a fruit tree you are deliberately leaving standing.
	 *
	 * <p>Nothing else in the loadout is affected. You still want the axe for a dead tree, the
	 * teleports to get there, and the seed box and herb sack for what you pick up.
	 */
	private boolean plantsNothing(PlantingGroup group)
	{
		return runTypes.isHarvestOnly(group) || contractIsStandingThere(group);
	}

	/**
	 * Whether this group is a contract whose crop is already grown and waiting.
	 *
	 * <h2>A finished contract needs no seed, and asking for one inverts the whole errand</h2>
	 *
	 * The contract cycle is <b>harvest, hand in, take the next one, then plant that</b>. Until the
	 * hand-in there is nothing to sow: the patch is full, and what goes in it next is whichever
	 * crop Jane asks for after — which nobody knows yet, least of all this class.
	 *
	 * <p>The loadout could not see that. A ripe patch is actionable, so a grown contract put its
	 * group into {@code actionableByGroup} exactly like a ripe herb patch, and every ripe patch
	 * means "clear it and replant" — so the run told you to withdraw a cactus seed for a cactus
	 * that was standing there finished. Reported from play, and it is the worst possible advice at
	 * that moment: the seed is the one thing the trip does not need, and banking for it is time
	 * spent instead of handing the contract in.
	 *
	 * <p>Asked of what is <i>actually growing</i> rather than of {@code getAwaitingHandIn}, because
	 * the two disagree in the case that matters. A contract that ripened while you were logged out
	 * sends no chat message, so nothing captured it and the hand-in flag is unset — but the patch
	 * is standing there full, which is a fact no event can be missed for. See
	 * {@code ContractState} on why both sources exist.
	 *
	 * <p>Once it is handed in the patch empties, the group's ripe produce no longer matches, and
	 * the next contract's seed is asked for normally — on the same trip, since taking the contract
	 * moves its patch into this group immediately.
	 */
	private boolean contractIsStandingThere(PlantingGroup group)
	{
		if (!group.isContract())
		{
			return false;
		}

		com.dooglemaps.data.Produce contract = contracts.getContract();
		return contract != null && planner.ripeProduceIn(group).containsKey(contract);
	}

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
		addSaltpetre(items, types);
		addCompostBinSupplies(items, types);
		addTools(items, types);
		addGear(items, types);
		addAxe(items, types);
		addStorage(items, types);
		addDivingGear(items, types);
		addListedTeleports(items);
		return items;
	}

	/**
	 * Patch types whose run involves chopping something down.
	 *
	 * <p>A grown tree has to be cut and its stump dug out before the patch can be replanted, so
	 * an axe is not an optimisation — without one the patch cannot be cleared at all. Fruit
	 * trees are in the list because a dead one still has to come out, even though a healthy one
	 * is left alone to keep fruiting.
	 *
	 * <p><b>Calquat is deliberately absent</b>, and it used to be here: the wiki is plain that a
	 * calquat clears with a spade alone, like a bush — no chop, no axe. <b>Celastrus is
	 * deliberately present</b>, and it used to be missing: its bark is harvested <i>with an
	 * axe</i>, so a celastrus run cannot do the thing it came for without one.
	 */
	private static final Set<PatchImplementation> NEEDS_AN_AXE = EnumSet.of(
		PatchImplementation.TREE,
		PatchImplementation.FRUIT_TREE,
		PatchImplementation.HARDWOOD_TREE,
		PatchImplementation.REDWOOD,
		PatchImplementation.CELASTRUS,
		// The crystal tree for the same reason celastrus is here rather than as a clearing:
		// chopping it down IS the harvest, so an axeless trip collects no shards at all.
		PatchImplementation.CRYSTAL_TREE);

	/**
	 * Whether a run over these types chops at all — the type-level half of the guide's
	 * "pack is full on a run that chops" push, which arms the mid-run deposit trip.
	 *
	 * <p>Deliberately blunter than {@link #axeNeeded}: that method asks what is actionable
	 * <i>right now</i>, which goes false the moment the last tree is cleared — exactly when
	 * the logs are at their fullest and the trip is most wanted. Whether the pack actually
	 * filled is the other half, and {@code CarriedItems} answers that.
	 */
	public static boolean chopsLogs(Set<PatchImplementation> types)
	{
		for (PatchImplementation type : types)
		{
			if (NEEDS_AN_AXE.contains(type))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The best axe you own and can actually swing.
	 *
	 * <p>Level as well as tier: a dragon axe from a drop is dead weight at 30 Woodcutting, and
	 * naming it would send someone to the bank for something they cannot use. The leprechaun
	 * stores every other farming tool but not this one, so it genuinely has to be carried.
	 */
	/**
	 * Whether anything on this run swings an axe.
	 *
	 * <p>The rule, plainly: a replanting run over any tree-shaped type needs one, a
	 * harvest-only run never does, and a contract for a tree-shaped type counts because a
	 * contract group is never harvest-only. Wiki-checked rather than assumed: a tree patch's
	 * value is the health check, which needs no tool — the chop is purely clearing the patch
	 * for the next sapling, farmed trees do not regrow for re-chopping, and even the clearing
	 * has an axeless alternative in the gardener's 200-coin removal. Per group rather than
	 * per type, because the same fruit trees can be a harvest-only tick and a contract's
	 * replant at once.
	 */
	private boolean axeNeeded(Set<PatchImplementation> types)
	{
		for (PlantingGroup group : planner.countActionableByGroup(types).keySet())
		{
			// Celastrus is the one type whose HARVEST is the axe: the bark comes off with
			// one, so even a harvest-only visit swings it. Everything else chops only to
			// clear for a replant, which harvest-only never does.
			if (group.getType() == PatchImplementation.CELASTRUS)
			{
				return true;
			}
			if (NEEDS_AN_AXE.contains(group.getType()) && !runTypes.isHarvestOnly(group))
			{
				return true;
			}
		}
		return false;
	}

	private void addAxe(List<LoadoutItem> items, Set<PatchImplementation> types)
	{
		if (!axeNeeded(types))
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
			// Said once. The trowel is the only tool two parts of this class can both want —
			// the vinery asks for it here to spread saltpetre, and the potting asks for it in
			// addPottingTrowel — and the potting row is the stricter of the two, because it
			// wants the trowel in hand at the bank rather than at a patch the leprechaun is
			// standing beside. addSeeds runs first, so that row is already here to be kept.
			if (alreadyListed(items, tool.getItemID()))
			{
				continue;
			}
			items.add(new LoadoutItem(tool.getItemID(), tool.getDisplayName(),
				LoadoutItem.Category.TOOL, needFor(requirement.getSource()), 0,
				toolReason(tool, requirement.getSource())));
		}
	}

	/** Whether a tool of this id already has a row, whoever put it there. */
	private static boolean alreadyListed(List<LoadoutItem> items, int itemId)
	{
		for (LoadoutItem item : items)
		{
			if (item.getItemId() == itemId
				&& item.getCategory() == LoadoutItem.Category.TOOL)
			{
				return true;
			}
		}
		return false;
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
		// one-run trip. Scoping to the group does not fix that on its own — see docs/TODO.md, "Picking
		// more than one seed for a patch type" — but it stops the split multiplying it.
		Map<PlantingGroup, List<FarmPatch>> actionable = planner.actionableByGroup(types);

		// Stock left after the groups already served, so two groups cannot both plan to plant the
		// same seeds. See allocate.
		Map<Seed, Integer> unspent = new java.util.HashMap<>();

		int potsNeeded = 0;
		for (Map.Entry<PlantingGroup, List<FarmPatch>> entry : actionable.entrySet())
		{
			PlantingGroup group = entry.getKey();
			List<FarmPatch> plantable = entry.getValue();
			PatchImplementation type = group.getType();
			if (plantable.isEmpty() || plantsNothing(group))
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
			Map<Seed, Integer> share = allocate(group, plantable, unspent).counts();
			// Booked before the next group asks. Nothing else has to change: a group whose seed
			// has been used up simply allocates its next choice, which is the spill the single
			// group case has always done.
			share.forEach((seed, patches) ->
				unspent.merge(seed, -patches * seed.getSeedsPerPatch(), Integer::sum));

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
				if (needsPotting)
				{
					// One pot per seed still to pot, totalled across the run's tree types and
					// added once after the loop - two tree crops short of saplings want one
					// row of pots, not two.
					potsNeeded += Math.max(0, Math.min(wanted, owned)
						- seeds.getOwnedPlantable(seed));
				}

				// The contract says *why* rather than only how many, because it is the one
				// seed on this list the player did not choose — and the one whose absence is
				// worth knowing about at the bank rather than at the patch, since arriving
				// without it costs the whole reward for another growth cycle.
				String reason = (group.isContract()
					? "Guildmaster Jane's contract"
					: patches + (patches == 1 ? " patch" : " patches") + " of "
						+ type.getDisplayName().toLowerCase())
					+ (needsPotting ? " - needs potting into a sapling first" : "");

				// What is left to fetch: what the run wants, less what is already on you,
				// and never more than you actually own. Owning fewer than the run wants is
				// the ordinary case - the answer there is "take all of them", not the
				// shortfall, which would send you to the bank for seeds that are not in it.
				int outstanding = Math.max(0, Math.min(wanted, owned) - inPack);

				// Split across the containers when neither alone covers it, bank first — the
				// bank is where the rest of the run's items are. One container per row used to
				// be the rule outright, and it sent a magic-sapling errand wholly to the vault
				// while ONE of the two wanted sat in the bank being read at that moment — the
				// bank's count then said "take 2" over a slot holding 1, which reads as the
				// arithmetic being wrong rather than as a second container being involved.
				// Reported from play. outstanding never exceeds bank+vault (owned is the cap),
				// so the two takes always sum to exactly what is left to fetch.
				int fromBank = Math.min(outstanding,
					seeds.getCount(seed, SeedSource.BANK));
				int fromVault = Math.min(outstanding - fromBank,
					seeds.getCount(seed, SeedSource.SEED_VAULT));

				if (fromBank > 0 && fromVault > 0)
				{
					items.add(new LoadoutItem(seed.getPlantedItemID(), displayName(seed),
						LoadoutItem.Category.SEED, LoadoutItem.Need.WITHDRAW,
						fromBank, fromBank, reason, LoadoutItem.From.BANK));
					items.add(new LoadoutItem(seed.getPlantedItemID(), displayName(seed),
						LoadoutItem.Category.SEED, LoadoutItem.Need.WITHDRAW,
						fromVault, fromVault, reason, LoadoutItem.From.SEED_VAULT));
					continue;
				}

				items.add(new LoadoutItem(seed.getPlantedItemID(), displayName(seed),
					LoadoutItem.Category.SEED,
					need(inPack >= wanted, owned > inPack, false),
					Math.min(wanted, Math.max(owned, 1)),
					outstanding,
					reason,
					fetchFrom(seed, wanted)));
			}
		}

		if (potsNeeded > 0)
		{
			addPottingSupplies(items, potsNeeded);
		}
	}

	/**
	 * What to call a seed on the list: the item you would actually pick up.
	 *
	 * <p>The Seed enum's own name is the crop — "Yew" — which is ambiguous at a bank holding
	 * yew seeds, yew saplings and yew logs at once. The game's own name for the planted form
	 * is used where it is known; item names are learned from banks, so a form never banked
	 * falls back to the crop with its form spelt out.
	 */
	private String displayName(Seed seed)
	{
		return itemNames.get(seed.getPlantedItemID(),
			seed.getName() + (seed.isSapling() ? " sapling" : " seed"));
	}

	/** Every can that waters, most charged first; the empty one still counts - water is free. */
	private static final int[] WATERING_CANS = {
		ItemID.ZEAH_WATERINGCAN, ItemID.WATERING_CAN_8, ItemID.WATERING_CAN_7,
		ItemID.WATERING_CAN_6, ItemID.WATERING_CAN_5, ItemID.WATERING_CAN_4,
		ItemID.WATERING_CAN_3, ItemID.WATERING_CAN_2, ItemID.WATERING_CAN_1,
		ItemID.WATERING_CAN_0,
	};

	/**
	 * The pots, the trowel and the can, for tree seeds that are still seeds.
	 *
	 * <p>A tree seed cannot go in the ground: it is sown into a filled plant pot, watered, and
	 * becomes the sapling the patch actually takes a few minutes later. The seed's own row
	 * already says <i>needs potting</i>; these are what the potting itself needs, which
	 * otherwise get discovered at the patch, a teleport too late.
	 *
	 * <p>One row of pots however many tree types are short — a pot is a pot — and one can
	 * whatever the pot count, since a can waters everything and refills anywhere with water.
	 */
	private void addPottingSupplies(List<LoadoutItem> items, int pots)
	{
		addPottingTrowel(items, pots);

		int carriedPots = carried.getCount(ItemID.PLANTPOT_COMPOST);
		int held = bank.getCount(ItemID.PLANTPOT_COMPOST) + carriedPots;
		items.add(new LoadoutItem(ItemID.PLANTPOT_COMPOST, "Filled plant pot",
			LoadoutItem.Category.TOOL,
			carriedPots >= pots ? LoadoutItem.Need.HAVE
				: held > 0 ? LoadoutItem.Need.WITHDRAW
				: bank.hasBeenSeen() ? LoadoutItem.Need.MISSING : LoadoutItem.Need.UNKNOWN,
			pots,
			// Take what exists even when it is short - potting three of four seeds still
			// plants three trees, unlike a payment, where a partial withdrawal buys a
			// partial protection nobody asked for.
			Math.max(0, Math.min(pots, held) - carriedPots),
			"To pot " + pots + (pots == 1 ? " tree seed" : " tree seeds")
				+ " into saplings - sow, water, and they are plantable in minutes. An empty "
				+ "pot fills at any weeded patch, with the trowel below",
			LoadoutItem.From.BANK));

		Integer carriedCan = firstOwned(WATERING_CANS, carried::has);
		Integer bankedCan = firstOwned(WATERING_CANS, bank::has);
		items.add(new LoadoutItem(
			carriedCan != null ? carriedCan : bankedCan != null ? bankedCan
				: ItemID.WATERING_CAN_8,
			"Watering can", LoadoutItem.Category.TOOL,
			carriedCan != null ? LoadoutItem.Need.HAVE
				: bankedCan != null ? LoadoutItem.Need.WITHDRAW
				: bank.hasBeenSeen() ? LoadoutItem.Need.MISSING : LoadoutItem.Need.UNKNOWN,
			0, "A freshly potted sapling has to be watered before it starts growing"));
	}

	/**
	 * The trowel, without which none of the potting above can happen at all.
	 *
	 * <h2>It is not only for filling the pot</h2>
	 *
	 * The pot row's wording had it as an aside — <i>an empty pot fills with a trowel</i> — which
	 * reads as an errand for anyone who buys their pots empty and as nothing at all for anyone
	 * who buys them filled. It is neither. The trowel has to be <b>in the inventory to sow the
	 * seed into the pot</b>, filled or not: the wiki's own wording for every tree seed is "using
	 * it on a plant pot while a gardening trowel is in the inventory". So a player with a stack
	 * of filled pots, a watering can and no trowel was told they had everything and could pot
	 * nothing. Reported from play.
	 *
	 * <h2>The bank, not the leprechaun, even though he stores a hundred</h2>
	 *
	 * {@code addTools} would offer this as {@code AT_LEPRECHAUN} and be right for the vinery,
	 * where the trowel is used standing at the patch. The potting is the other case: it happens
	 * at the <b>bank</b>, before you set off, because a potted seed wants five minutes to become
	 * a sapling and the travel is what pays for the wait — see {@code LoadoutSummary}. A trowel
	 * in a store you have not reached yet cannot do that, so the bank wins here and his store is
	 * only the fallback, said in the tooltip as what it costs you: the potting moves to the
	 * first patch.
	 */
	private void addPottingTrowel(List<LoadoutItem> items, int pots)
	{
		String reason = "In the inventory to sow " + (pots == 1 ? "the seed" : "the seeds")
			+ " into their pots, and again to fill an empty pot at a weeded patch";

		if (carried.has(ItemID.GARDENING_TROWEL))
		{
			items.add(new LoadoutItem(ItemID.GARDENING_TROWEL, "Gardening trowel",
				LoadoutItem.Category.TOOL, LoadoutItem.Need.HAVE, 0, reason));
			return;
		}

		if (bank.has(ItemID.GARDENING_TROWEL))
		{
			items.add(new LoadoutItem(ItemID.GARDENING_TROWEL, "Gardening trowel",
				LoadoutItem.Category.TOOL, LoadoutItem.Need.WITHDRAW, 0, reason));
			return;
		}

		if (leprechaun.has(FarmingTool.GARDENING_TROWEL))
		{
			items.add(new LoadoutItem(ItemID.GARDENING_TROWEL, "Gardening trowel",
				LoadoutItem.Category.TOOL, LoadoutItem.Need.AT_LEPRECHAUN, 0,
				reason + " - the leprechaun is holding yours, so the potting waits until "
					+ "the first patch rather than happening here"));
			return;
		}

		items.add(new LoadoutItem(ItemID.GARDENING_TROWEL, "Gardening trowel",
			LoadoutItem.Category.TOOL,
			bank.hasBeenSeen() ? LoadoutItem.Need.MISSING : LoadoutItem.Need.UNKNOWN, 0,
			reason + " - you do not have one anywhere. A farming shop sells one for a few "
				+ "coins; there is one beside most patch areas"));
	}

	private static Integer firstOwned(int[] itemIds, java.util.function.IntPredicate owned)
	{
		for (int itemId : itemIds)
		{
			if (owned.test(itemId))
			{
				return itemId;
			}
		}
		return null;
	}

	/**
	 * Which store to fetch a seed out of.
	 *
	 * <p>The same rule the planner routes by, and deliberately so: {@code getSupplySources} decides
	 * where the opening leg goes, and this decides what the guide says when you get there. If they
	 * disagreed you would be sent to one and told to look in the other.
	 *
	 * <p><b>A full patch's worth, not merely present.</b> One seed in the bank is not a reason to
	 * call this a bank job when the vault has the other five, and the bank wins the tie because
	 * everything else the run needs is there anyway — one stop rather than two.
	 */
	private LoadoutItem.From fetchFrom(Seed seed, int wanted)
	{
		if (seeds.getCount(seed, SeedSource.BANK) >= wanted)
		{
			return LoadoutItem.From.BANK;
		}
		if (seeds.getCount(seed, SeedSource.SEED_VAULT) >= wanted)
		{
			return LoadoutItem.From.SEED_VAULT;
		}
		// Neither has a full run's worth. Whichever holds any at all is where topping up starts,
		// and the bank again wins a tie.
		return seeds.getCount(seed, SeedSource.BANK) > 0
			|| seeds.getCount(seed, SeedSource.SEED_VAULT) == 0
			? LoadoutItem.From.BANK
			: LoadoutItem.From.SEED_VAULT;
	}

	/**
	 * How this group's patches divide between the seeds picked for it.
	 *
	 * <p>Built exactly as {@code GuideTracker} builds it, from the same stores, because the whole
	 * point is that the bank list and the guide agree. Anything else and you bank for one plan and
	 * plant another.
	 */
	private SeedAllocation allocate(PlantingGroup group, List<FarmPatch> plantable,
		Map<Seed, Integer> unspent)
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
			// Less whatever an earlier group in this loop already spoke for.
			//
			// Each call used to start from the full owned count, so a seed picked in two groups
			// was planned into both — two ranarrs covering two protected patches AND two
			// ordinary ones, from a stock of two. Nothing reconciled it, so the bank list asked
			// for a run the player could not plant and the groups that should have spilled to
			// their second choice never did. The split makes this reachable in ordinary play:
			// protected herbs and ordinary herbs are two groups over one stock of seeds.
			owned.put(seed, Math.max(0,
				seeds.getOwned(seed) + unspent.getOrDefault(seed, 0)));

			ProtectionPayment payment = ProtectionPayment.forSeed(seed);
			if (payment != null && protection.isProtecting(group, seed))
			{
				payments.put(payment.getItemID(),
					bank.getCount(payment.getItemID())
						+ carried.getCountIncludingNoted(payment.getItemID()));
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
			if (plantsNothing(group))
			{
				continue;
			}
			if (everySeedIsProtectedAndUnimproved(group))
			{
				continue;
			}
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
	 * Whether every seed this group would plant is one the guide will refuse to compost.
	 *
	 * <h2>The bank half of {@code CropYieldModel.compostWastedOnProtected}</h2>
	 *
	 * The guide decides per patch, holding one seed; this list is built per group, before a
	 * patch is in front of anyone. So the group is only dropped when the answer is the same for
	 * <b>every</b> seed picked for it — otherwise a herb tab with a protected magic sapling
	 * alongside a ranarr would bank no compost for the ranarr, and the run would arrive unable
	 * to do the thing the guide is about to ask for.
	 *
	 * <p>{@code allMatch} on an empty selection is vacuously true, which is the wrong answer
	 * here — "nothing picked" is not "nothing wants compost" — so an empty pick keeps the
	 * bucket. The group is going to be told it has no seed anyway, and this is not the line
	 * that should say so.
	 */
	private boolean everySeedIsProtectedAndUnimproved(PlantingGroup group)
	{
		Set<Seed> picked = selection.getSelectedFor(group);
		if (picked.isEmpty())
		{
			return false;
		}
		for (Seed seed : picked)
		{
			if (!com.dooglemaps.timer.CropYieldModel.compostWastedOnProtected(
				seed, protection.isProtecting(group, seed)))
			{
				return false;
			}
		}
		return true;
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
	 *
	 * <h2>Counted off the allocation, like the seeds above</h2>
	 *
	 * This used to multiply every payment by every actionable patch in the group, which is only
	 * right when one crop takes the whole group. With the patches split — five magics capped at
	 * the one the coconuts afford, yews taking the rest — it asked for a full run's worth of
	 * both payments, and the overstated coconut total then went <i>over</i> what the player
	 * held, so the row fell off the withdraw list as MISSING entirely. Reported from play, as a
	 * loadout that said to plant a magic and bring no coconuts, beside seventy spines for six
	 * yews. The allocation already knows how many patches each crop actually gets — and it
	 * capped the magics <i>because of</i> the coconuts — so the payments follow it the same way
	 * the seed rows do, and the three lists cannot disagree.
	 *
	 * <p>Summed across crops and groups sharing an item, rather than first-crop-wins: coconuts
	 * protect magics and dragonfruit both, and the old {@code seen} dedup silently dropped
	 * whichever asked second.
	 */
	private void addPayments(List<LoadoutItem> items, Set<PatchImplementation> types)
	{
		Map<Integer, Integer> wantedByItem = new LinkedHashMap<>();
		Map<Integer, List<String>> protectsByItem = new LinkedHashMap<>();
		Map<Integer, String> fallbackNames = new LinkedHashMap<>();

		// Only for groups the player said they would pay for. Listing every possible payment was
		// the old behaviour and it asked you to bank things you had no intention of using —
		// which, on a tree run, is several stacks of fruit nobody wanted.
		Map<PlantingGroup, List<FarmPatch>> actionable = planner.actionableByGroup(types);

		// Its own ledger, spent down exactly as addSeeds spends its own. The two loops walk the
		// same groups in the same order over the same stock, so they reach the same allocation —
		// which is the property that matters here: a payment banked for a patch the seed list
		// never intends to plant is a stack of fruit nobody wanted, and the reverse is a patch
		// planted unprotected.
		Map<Seed, Integer> unspent = new java.util.HashMap<>();

		for (Map.Entry<PlantingGroup, List<FarmPatch>> entry : actionable.entrySet())
		{
			PlantingGroup group = entry.getKey();
			// Nothing is being planted here, so there is nothing to protect. See plantsNothing.
			if (entry.getValue().isEmpty() || plantsNothing(group))
			{
				continue;
			}

			Map<Seed, Integer> allocated = allocate(group, entry.getValue(), unspent).counts();
			allocated.forEach((seed, patches) ->
				unspent.merge(seed, -patches * seed.getSeedsPerPatch(), Integer::sum));

			for (Map.Entry<Seed, Integer> share : allocated.entrySet())
			{
				Seed seed = share.getKey();
				int patches = share.getValue();
				if (patches <= 0 || !protection.isProtecting(group, seed))
				{
					continue;
				}

				ProtectionPayment payment = ProtectionPayment.forSeed(seed);
				if (payment == null)
				{
					continue;
				}

				wantedByItem.merge(payment.getItemID(),
					payment.getQuantity() * patches, Integer::sum);
				protectsByItem.computeIfAbsent(payment.getItemID(), k -> new ArrayList<>())
					.add(patches + " " + seed.getName().toLowerCase());
				fallbackNames.putIfAbsent(payment.getItemID(), payment.getProduce().getName());
			}
		}

		for (Map.Entry<Integer, Integer> entry : wantedByItem.entrySet())
		{
			int itemId = entry.getKey();
			int wanted = entry.getValue();
			// Counting noted ones, because that is how anyone actually carries thirty
			// spines - the gardener takes the note. Counting only the exact id left the
			// row on the withdraw list with the payment already in the pack.
			int held = carried.getCountIncludingNoted(itemId) + bank.getCount(itemId);
			String protects = String.join(" and ", protectsByItem.get(itemId));

			// Named after the item to bring, not the crop it protects. This row said "Magic"
			// when what you need is coconuts — the accessor is getProduce() on both halves of
			// the payment, and it is the wrong half here.
			items.add(new LoadoutItem(itemId,
				itemNames.get(itemId, fallbackNames.get(itemId)),
				LoadoutItem.Category.PAYMENT,
				paymentNeed(wanted, held, itemId), wanted,
				// Against what is carried rather than what is held: held counts the bank
				// too, and the bank is where you are about to take them from.
				Math.max(0, wanted - carried.getCountIncludingNoted(itemId)),
				held < wanted
					? "Protects " + protects + " - you have " + held
						+ " of the " + wanted + " this run needs"
					: "Protects " + protects
						+ " - noted is fine; the gardener takes the note",
				LoadoutItem.From.BANK));
		}
	}

	/**
	 * Every payment the run's <b>choices</b> could call for, funded this trip or not.
	 *
	 * <h2>Wider than the payment rows, and that is the point</h2>
	 *
	 * {@link #addPayments} lists what this trip's allocation actually spends, which is right for
	 * a shopping list and wrong for the opposite question — <i>may I put this in the bank?</i>.
	 * The allocation caps a protected crop at what its payment affords and at what you own, so a
	 * crop can be picked, protected, and still draw no patch on a given trip. Its payment is then
	 * on no row at all, and the deposit marks — which keep only what the rows name — read a
	 * carefully assembled stack as spare harvest.
	 *
	 * <p>The reported case: a tree run carrying twenty-five coconuts, marked <i>finished crops -
	 * deposit them</i> at the bank they had just been withdrawn from. Coconuts are two things at
	 * once — the palm's harvest and the magic tree's protection — and with no magic patch
	 * allocated that trip, only the harvest half was left to see them by.
	 *
	 * <p>So the answer is the selection rather than the allocation: a payment for a crop you have
	 * picked and chosen to protect is the run's currency whatever this particular trip does with
	 * it. Being wrong in this direction costs an inventory slot; being wrong the other way costs
	 * the patch the payment was for.
	 */
	public Set<Integer> paymentsForSelectedSeeds(Set<PatchImplementation> types)
	{
		Set<Integer> ids = new LinkedHashSet<>();
		if (types.isEmpty())
		{
			return ids;
		}

		for (PlantingGroup group : planner.countActionableByGroup(types).keySet())
		{
			if (plantsNothing(group))
			{
				continue;
			}
			for (Seed seed : selection.getSelectedFor(group))
			{
				ProtectionPayment payment = ProtectionPayment.forSeed(seed);
				if (payment != null && protection.isProtecting(group, seed))
				{
					ids.add(payment.getItemID());
				}
			}
		}
		return ids;
	}

	/**
	 * The diving gear, without which the underwater patches are not so much far as sealed.
	 *
	 * <p>Giant seaweed grows under Fossil Island and the coral nurseries under the Great
	 * Conch, and the fishbowl helmet and diving apparatus are worn for both. What they do
	 * differs, and the rows say so rather than repeating one sentence: the nurseries cannot be
	 * entered without them at all, while Fossil Island lets anyone dive and drains an oxygen
	 * bar until they drown. Either way the run is held for them — a seaweed trip that washes
	 * you up on the surface half-way through the second patch is the wasted leg this list
	 * exists to prevent — which is why both are in the axe's category.
	 *
	 * <h2>The medallion now settles either</h2>
	 *
	 * It used to settle only a coral-only run, on the wiki's word that the reef was the one
	 * place it reached. That stopped being true in <b>May 2026</b>: it "now functions as
	 * breathing apparatus throughout the Fossil Island underwater areas". So someone who
	 * assembled one was still being sent to a bank for a fishbowl helmet they had replaced,
	 * on a run this plugin could have said nothing about.
	 */
	private void addDivingGear(List<LoadoutItem> items, Set<PatchImplementation> types)
	{
		boolean seaweed = types.contains(PatchImplementation.SEAWEED);
		boolean coral = types.contains(PatchImplementation.CORAL);
		if (!seaweed && !coral)
		{
			return;
		}

		// One item in place of two, wherever the run is going. See the class note above.
		if (carried.has(ItemID.MEDALLION_OF_THE_DEEP) || bank.has(ItemID.MEDALLION_OF_THE_DEEP)
			|| boatHolds.has(ItemID.MEDALLION_OF_THE_DEEP))
		{
			items.add(new LoadoutItem(ItemID.MEDALLION_OF_THE_DEEP, "Medallion of the deep",
				LoadoutItem.Category.TOOL, divingNeed(ItemID.MEDALLION_OF_THE_DEEP), 0,
				"Breathing apparatus for every underwater patch, on its own - no suit needed"));
			return;
		}

		// Both pieces or neither: they only keep the oxygen bar full together, so a row saying
		// the helmet is sorted while the apparatus is silently missing would read as done.
		String reason = coral
			? "Worn to dive - the coral nurseries cannot be entered without it, and it keeps "
				+ "your oxygen at 100% once you are down"
			: "Worn to dive - anyone can enter, but without it your oxygen drains and you "
				+ "drown part-way through the patches";
		items.add(new LoadoutItem(ItemID.HUNDRED_PIRATE_DIVING_HELMET, "Fishbowl helmet",
			LoadoutItem.Category.TOOL,
			divingNeed(ItemID.HUNDRED_PIRATE_DIVING_HELMET), 0, reason));
		items.add(new LoadoutItem(ItemID.HUNDRED_PIRATE_DIVING_BACKPACK, "Diving apparatus",
			LoadoutItem.Category.TOOL,
			divingNeed(ItemID.HUNDRED_PIRATE_DIVING_BACKPACK), 0, reason));
	}

	/**
	 * Where a piece of diving gear is, counting the place it usually lives.
	 *
	 * <p>The cargo hold is asked before the bank is despaired of, which is the whole fix: the
	 * hold stores this gear taking no space and it is then reachable from every boat, so it is
	 * where a sailing player naturally leaves it — and the loadout, looking only in the bank
	 * and the pack, told them they owned none. Reported from play.
	 *
	 * <p>Unknown rather than missing while <b>neither</b> store has been read, matching the
	 * rule the bank already follows: claiming someone owns no helmet on the strength of two
	 * places nobody has looked in is a false alarm, and a hold is only sent to the client when
	 * it is opened.
	 */
	private LoadoutItem.Need divingNeed(int itemId)
	{
		if (carried.has(itemId))
		{
			return LoadoutItem.Need.HAVE;
		}
		if (boatHolds.has(itemId))
		{
			return LoadoutItem.Need.ON_BOAT;
		}
		if (bank.has(itemId))
		{
			return LoadoutItem.Need.WITHDRAW;
		}
		return bank.hasBeenSeen() || boatHolds.hasBeenSeen()
			? LoadoutItem.Need.MISSING
			: LoadoutItem.Need.UNKNOWN;
	}

	/**
	 * Saltpetre, for the vinery: every grape planting starts by treating the soil with one.
	 *
	 * <p>Wiki-checked, and previously unmodelled entirely: the patch refuses a grape seed
	 * until it has been treated, so a grape run without saltpetre arrives able to do nothing —
	 * the same shape of wasted trip the payments section exists to prevent. One per patch,
	 * counted like the seeds and pots are.
	 */
	private void addSaltpetre(List<LoadoutItem> items, Set<PatchImplementation> types)
	{
		if (!types.contains(PatchImplementation.GRAPES))
		{
			return;
		}

		int patches = 0;
		for (Map.Entry<PlantingGroup, Integer> entry
			: planner.countActionableByGroup(types).entrySet())
		{
			if (entry.getKey().getType() == PatchImplementation.GRAPES
				&& !plantsNothing(entry.getKey()))
			{
				patches += entry.getValue();
			}
		}
		if (patches == 0)
		{
			return;
		}

		int carriedCount = carried.getCount(ItemID.HOSIDIUS_SALTPETRE);
		int held = bank.getCount(ItemID.HOSIDIUS_SALTPETRE) + carriedCount;
		items.add(new LoadoutItem(ItemID.HOSIDIUS_SALTPETRE, "Saltpetre",
			LoadoutItem.Category.TOOL,
			carriedCount >= patches ? LoadoutItem.Need.HAVE
				: held > 0 ? LoadoutItem.Need.WITHDRAW
				: bank.hasBeenSeen() ? LoadoutItem.Need.MISSING : LoadoutItem.Need.UNKNOWN,
			patches,
			Math.max(0, Math.min(patches, held) - carriedCount),
			"One per grape patch - the vinery soil is treated with saltpetre before every "
				+ "planting",
			LoadoutItem.From.BANK));
	}

	/** The bin run's fill and ash choices. See {@code CompostRunStore}. */
	private final com.dooglemaps.state.CompostRunStore compostRun;

	/** What is stowed at sea, which for the diving gear is where it usually is. */
	private final BoatHolds boatHolds;

	/**
	 * What a compost-bin run takes to the bank: the fill, the ash, and something to carry the
	 * compost out in.
	 *
	 * <p>All three are {@link LoadoutItem.Category#COMPOST}, which is in
	 * {@code CANNOT_PROCEED_WITHOUT} - a bin stop reached without its fill achieves nothing
	 * there, the same shape of wasted trip as a seedless patch.
	 *
	 * <p>The fill is counted <b>un-noted in the pack</b>, deliberately: a bin takes no notes,
	 * so noted produce in the inventory is no closer to usable than produce in the bank. That
	 * also makes the quantity an honest warning about pack space - fifteen items is most of an
	 * inventory, and the tooltip says so rather than letting the stop reveal it.
	 */
	private void addCompostBinSupplies(List<LoadoutItem> items, Set<PatchImplementation> types)
	{
		com.dooglemaps.route.RunPlanner.BinWork work = planner.binWork(types);
		if (work.readyBins == 0 && work.fillableBins == 0)
		{
			return;
		}

		if (work.fillItems > 0)
		{
			addFills(items, work);
		}

		if (compostRun.isAshing() && work.ashNeeded > 0)
		{
			int ash = com.dooglemaps.data.CompostBin.VOLCANIC_ASH;
			int carriedAsh = carried.getInventoryCount(ash);
			int heldAsh = bank.getCount(ash) + carriedAsh;
			items.add(new LoadoutItem(ash, "Volcanic ash", LoadoutItem.Category.COMPOST,
				carriedAsh >= work.ashNeeded ? LoadoutItem.Need.HAVE
					: heldAsh > 0 ? LoadoutItem.Need.WITHDRAW
					: bank.hasBeenSeen() ? LoadoutItem.Need.MISSING : LoadoutItem.Need.UNKNOWN,
				work.ashNeeded,
				Math.max(0, Math.min(work.ashNeeded, heldAsh) - carriedAsh),
				"Upgrades the ready bins of supercompost to ultracompost - 25 a bin, 50 for "
					+ "the big one, and only while the compost is still in the bin",
				LoadoutItem.From.BANK));
		}

		if (work.readyBuckets > 0)
		{
			addCompostCarriers(items, work);
		}
	}

	/**
	 * How much bin fill this trip can actually carry: the room there is, right now.
	 *
	 * <h2>Why this is the live pack rather than the inventory's size</h2>
	 *
	 * It was {@code INVENTORY_SIZE} less a slot for the ash, which assumed the fill had the
	 * whole pack to itself. It does not. A compost run is routinely a compost <i>and</i> run —
	 * a contract's seed, the secateurs, the spade, a handful of teleports — and none of that
	 * was subtracted, so the row asked for twenty-seven pineapples into a pack with ten slots
	 * free.
	 *
	 * <p>That was not merely an overcount. {@code BIN_FILL} is in
	 * {@code CANNOT_PROCEED_WITHOUT}, so the row stayed {@code WITHDRAW} for the twenty-seventh
	 * pineapple that would never fit, {@code anythingLeftToWithdraw} stayed true, and
	 * {@code RunPlanner.suppliesOutstanding} held the run on the supply leg permanently — the
	 * player was told to fetch what they had no room for and was never routed to the bin at
	 * all. Reported from play.
	 *
	 * <p>Measured against the pack, the row asks for what fits and goes {@code HAVE} the moment
	 * it does, so the leg closes and the run leaves. <b>One free slot is enough</b>: it fetches
	 * one item, fills the bin one item further, and the bin is topped up on the next load —
	 * which is what {@code CompostBinPlan.addFillStep}'s part-fill wording has always described.
	 *
	 * <p>What is already in the pack counts as room, because it is: a row asking for fifteen is
	 * satisfied by fifteen already carried, not by fifteen more on top.
	 *
	 * <p>The ash still gets its slot, and only when none is carried yet — it <b>stacks</b>, so
	 * 25 of it costs one slot whatever the number says, and reserving a slot for a stack already
	 * in the pack would quietly cost the last pineapple.
	 *
	 * <p>The buckets are deliberately not reserved for. Emptying and depositing happen in turns
	 * against whatever room is going — see {@code CompostBinPlan}, which takes as many buckets as
	 * there are free slots and hands the compost straight back — so the produce does not have to
	 * share the pack with them.
	 */
	private int fillBudget()
	{
		int room = carried.getFreeSlots();
		if (compostRun.isAshing()
			&& carried.getInventoryCount(com.dooglemaps.data.CompostBin.VOLCANIC_ASH) == 0)
		{
			room--;
		}

		int alreadyCarried = 0;
		for (int fill : compostRun.getFills())
		{
			alreadyCarried += carried.getInventoryCount(fill);
		}
		return Math.max(0, room) + alreadyCarried;
	}

	/**
	 * A row per picked fill, taken in queue order until the pack is full.
	 *
	 * <p>The queue is the answer to "one crop is never enough": fifteen un-noted items a bin
	 * means eight bins want a hundred and twenty of something, and almost nobody has that of
	 * one thing. So the picks are drawn down in order — all the pineapples the trip can use,
	 * then watermelons for whatever is left — which is the same spilling the seed allocation
	 * does across patches.
	 *
	 * <p>Whole bins at a time, deliberately. A bin filled with a mixture that is not entirely
	 * supercompostable makes ordinary compost, so the guide fills each bin from one item and
	 * the loadout budgets the same way: asking for eleven pineapples and four watermelons
	 * would be banking a downgraded bin.
	 */
	private void addFills(List<LoadoutItem> items,
		com.dooglemaps.route.RunPlanner.BinWork work)
	{
		// One pack-load, never the whole job. The run's total is routinely more than an
		// inventory holds: nine empty bins want 150 items, and asking for 150 pineapples was
		// an instruction nobody could follow - it drove the bank highlight's count too.
		int budget = fillBudget();
		int binSize = Math.max(1, work.fillItems / Math.max(1, work.fillableBins));
		int outstanding = Math.min(work.fillItems, budget);
		boolean shortOfTheJob = work.fillItems > budget;

		// Whole bins at a time, EXCEPT when a bin is bigger than a pack. The big bin holds
		// thirty un-noted items against twenty-eight slots, so rounding down to a whole bin
		// gave zero every time: the fill row went MISSING, the supply leg it gates never
		// completed, and a compost-only run at the Farming Guild - whose only bin is the big
		// one - stood at the bank being told to fetch something it could not hold. Reported
		// from play. Where a bin cannot be filled in one trip the rounding is meaningless, so
		// the trip simply takes as many as it can and the bin is topped up on the next one.
		int rounding = binSize <= budget ? binSize : 1;

		for (int fill : compostRun.getFills())
		{
			if (outstanding <= 0)
			{
				break;
			}

			int carriedCount = carried.getInventoryCount(fill);
			int held = bank.getCount(fill) + carriedCount;
			// Whole bins of this item, so no bin ends up half one thing and half another.
			int wanted = Math.min(outstanding, (held / rounding) * rounding);
			if (wanted <= 0)
			{
				// Not enough for a full bin of this one. Still worth a row when it is the
				// only pick, so someone short of a bin's worth is told rather than left
				// wondering why the list is empty.
				if (compostRun.getFills().size() == 1)
				{
					items.add(new LoadoutItem(fill, itemNames.get(fill, "Bin fill"),
						LoadoutItem.Category.BIN_FILL,
						bank.hasBeenSeen() ? LoadoutItem.Need.MISSING : LoadoutItem.Need.UNKNOWN,
						Math.min(outstanding, binSize), 0,
						"A bin takes " + binSize + " un-noted, and you have " + held,
						LoadoutItem.From.BANK));
				}
				continue;
			}

			items.add(new LoadoutItem(fill, itemNames.get(fill, "Bin fill"),
				LoadoutItem.Category.BIN_FILL,
				carriedCount >= wanted ? LoadoutItem.Need.HAVE
					: held > 0 ? LoadoutItem.Need.WITHDRAW
					: bank.hasBeenSeen() ? LoadoutItem.Need.MISSING : LoadoutItem.Need.UNKNOWN,
				wanted,
				Math.max(0, Math.min(wanted, held) - carriedCount),
				fillReason(work, wanted, binSize, shortOfTheJob),
				LoadoutItem.From.BANK));
			outstanding -= wanted;
		}
	}

	/**
	 * What a fill row says, which depends on whether one trip covers the work.
	 *
	 * <p>The short case is the one worth wording carefully. A big bin alone cannot be filled in
	 * a single inventory at all — thirty un-noted items against twenty-eight slots — so a player
	 * heading to the guild expecting to finish it needs telling before they walk, not after.
	 *
	 * <p>"Room" rather than "a pack", throughout, because {@link #fillBudget()} is now the space
	 * this trip actually has rather than the size of an empty inventory. Saying "a pack holds 6"
	 * would read as a claim about inventories; what it is is a fact about this one, right now.
	 */
	private String fillReason(com.dooglemaps.route.RunPlanner.BinWork work, int wanted,
		int binSize, boolean shortOfTheJob)
	{
		String bins = work.fillableBins + (work.fillableBins == 1 ? " bin" : " bins");
		if (!shortOfTheJob)
		{
			return "Fills your " + bins + " - un-noted, so it is " + wanted + " inventory slots";
		}

		int budget = fillBudget();
		String slots = budget + (budget == 1 ? " slot" : " slots");
		if (binSize > budget)
		{
			// The big bin, or any bin against a pack that is already carrying most of a run.
			return "As many as there is room for - the bin takes " + binSize
				+ " un-noted items and you have " + slots
				+ " going spare, so it fills over more than one load.";
		}
		return wanted / Math.max(1, binSize) + " bin"
			+ (wanted / Math.max(1, binSize) == 1 ? "" : "s")
			+ " worth - un-noted and unstackable, so your " + bins + " want "
			+ work.fillItems + " items and you have room for " + budget
			+ ". The rest needs another load.";
	}

	/**
	 * Something to carry the finished compost out in: the bottomless bucket when the account
	 * owns one, the leprechaun's empties otherwise.
	 *
	 * <p>The bottomless wins outright - one slot for a whole bin against fifteen - and someone
	 * who owns one gets no bucket row at all, because the empties would be dead weight beside
	 * it. Without one, the buckets are the leprechaun's problem: he stores a thousand and
	 * stands beside every bin, so the row only sends anyone to a bank when his store has none.
	 */
	private void addCompostCarriers(List<LoadoutItem> items,
		com.dooglemaps.route.RunPlanner.BinWork work)
	{
		boolean bottomlessCarried = carried.hasAny(ItemID.BOTTOMLESS_COMPOST_BUCKET,
			ItemID.BOTTOMLESS_COMPOST_BUCKET_FILLED);
		boolean bottomlessBanked = bank.has(ItemID.BOTTOMLESS_COMPOST_BUCKET)
			|| bank.has(ItemID.BOTTOMLESS_COMPOST_BUCKET_FILLED);
		if (bottomlessCarried || bottomlessBanked)
		{
			items.add(new LoadoutItem(ItemID.BOTTOMLESS_COMPOST_BUCKET,
				"Bottomless compost bucket", LoadoutItem.Category.COMPOST,
				bottomlessCarried ? LoadoutItem.Need.HAVE : LoadoutItem.Need.WITHDRAW, 0,
				"Empties a whole bin into one slot - no ordinary buckets needed"));
			return;
		}

		int emptiesCarried = carried.getInventoryCount(ItemID.BUCKET_EMPTY);
		items.add(new LoadoutItem(ItemID.BUCKET_EMPTY, "Empty bucket",
			LoadoutItem.Category.COMPOST,
			emptiesCarried >= work.readyBuckets ? LoadoutItem.Need.HAVE
				: leprechaun.has(FarmingTool.EMPTY_BUCKET) ? LoadoutItem.Need.AT_LEPRECHAUN
				: need(false, bank.has(ItemID.BUCKET_EMPTY), false),
			work.readyBuckets,
			"One per compost coming out of the ready "
				+ (work.readyBins == 1 ? "bin" : (work.readyBins + " bins"))
				+ " - the leprechaun beside the bin stores them, so they are collected there"));
	}

	/**
	 * Whether the run can actually be protected, given how many payments exist.
	 *
	 * <p>Short is reported as <b>missing</b> rather than as a withdrawal, even when some are in
	 * the bank. Withdrawing 60 of the 100 coconuts a run needs leaves you paying for two trees
	 * and finding out about the other two on arrival — which is the wasted trip this whole
	 * section exists to prevent. Saying so before you set off is the useful answer.
	 *
	 * <p>Now that {@code addPayments} counts off the allocation, this branch is a safety net
	 * rather than the working path: the allocation caps a protected crop at what the payments
	 * afford, so what is asked for is by construction affordable. It stays because the two
	 * counts are computed from separate reads and a net under a claim like that is cheap.
	 */
	private LoadoutItem.Need paymentNeed(int wanted, int held, int itemId)
	{
		if (held < wanted)
		{
			return bank.hasBeenSeen() ? LoadoutItem.Need.MISSING : LoadoutItem.Need.UNKNOWN;
		}
		return carried.getCountIncludingNoted(itemId) >= wanted
			? LoadoutItem.Need.HAVE
			: LoadoutItem.Need.WITHDRAW;
	}

	/**
	 * Whether this trip is the hespori and nothing else, so it is fought rather than farmed.
	 *
	 * <h2>The player's own setup owns the pack on that trip</h2>
	 *
	 * The hespori is a boss in a cave, and the leg that precedes it says so: deposit everything,
	 * load your own combat loadout, and take what the cave cannot be replanted without — the
	 * spade, the seed and the dibber. Anything this class adds on top is the plugin arguing with
	 * a loadout the player built for the fight, over slots that are about to hold food.
	 *
	 * <p><b>The outfit is not useless here, and that is the point.</b> Its 2.5% does apply to the
	 * harvest — 12,600 experience becomes 12,915 — so the row was not a bug in the arithmetic
	 * sense, and a future reader checking the wiki will find it defensible. It goes anyway,
	 * because 315 experience is not a reason to fight a boss in a straw hat, and the same holds
	 * for the secateurs and the cape: neither touches a drop table. The seed box goes with them,
	 * for a trip that plants exactly one seed. Reported from play.
	 *
	 * <p>Exactly this type and no other, rather than {@code contains}. A mixed run's swap-back leg
	 * is a farm run again and wants every one of these rows back —
	 * {@code InventorySetupsHandoff.applies} asks the other question, "does this run have a gear
	 * leg at all", and copying its test here would strip the outfit off the rest of the trip.
	 */
	private static boolean fightsRatherThanFarms(Set<PatchImplementation> types)
	{
		return types.size() == 1 && types.contains(PatchImplementation.HESPORI);
	}

	/**
	 * The things that change the numbers rather than making the run possible.
	 *
	 * <p>Magic secateurs are the awkward one. The leprechaun stores them, but the +10% only
	 * applies while they are carried or worn, so the storage is a safety net rather than a
	 * substitute — they are still worth taking.
	 */
	private void addGear(List<LoadoutItem> items, Set<PatchImplementation> types)
	{
		if (fightsRatherThanFarms(types))
		{
			return;
		}

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
		//
		// Not on the hespori's own trip, which carries one seed and fights a boss with the rest
		// of the pack. See fightsRatherThanFarms.
		if (!fightsRatherThanFarms(types))
		{
			offerStorage(items, SEED_BOX, "Seed box", "Keeps your seeds out of your inventory");
		}

		// Log baskets earn the place the fruit basket is denied, because the timing is
		// different: logs arrive one per chop while the tree comes down, so the pack fills in
		// the middle of the work, before the leprechaun can note anything. The basket swallows
		// them as they land. Requested from play.
		if (types.contains(PatchImplementation.TREE)
			|| types.contains(PatchImplementation.HARDWOOD_TREE)
			|| types.contains(PatchImplementation.REDWOOD))
		{
			if (!offerStorage(items, FORESTRY_BASKET, "Forestry basket",
				"Holds 28 logs from the trees you fell"))
			{
				offerStorage(items, LOG_BASKET, "Log basket",
					"Holds 28 logs from the trees you fell");
			}
		}
	}

	/**
	 * Suggests a storage item only if it exists somewhere we can see, and says whether it did.
	 *
	 * <p>Several of these are locked behind things not every account has — the herb sack wants
	 * 58 Herblore and 750 Slayer points. Telling someone to bring one they cannot own is the
	 * failure mode this avoids.
	 */
	private boolean offerStorage(List<LoadoutItem> items, int[] itemIds, String name, String reason)
	{
		if (carried.hasAny(itemIds))
		{
			items.add(new LoadoutItem(itemIds[0], name, LoadoutItem.Category.STORAGE,
				LoadoutItem.Need.HAVE, 0, reason));
			return true;
		}

		for (int itemId : itemIds)
		{
			if (bank.has(itemId))
			{
				// The id that is actually there, so the bank highlight lands on the right slot.
				items.add(new LoadoutItem(itemId, name, LoadoutItem.Category.STORAGE,
					LoadoutItem.Need.WITHDRAW, 0, reason));
				return true;
			}
		}
		return false;
	}

	/**
	 * Teleports for the places this run actually goes, that you actually own.
	 *
	 * <p>The intersection is the whole point — see {@link TeleportItems}. A stop with nothing
	 * in the table, or nothing you own, simply produces no suggestion rather than advice you
	 * cannot follow.
	 */
	/**
	 * Whether this teleport is one the player has said they use.
	 *
	 * <h2>The list decides <i>what</i>; the table decides <i>why</i></h2>
	 *
	 * There are two sources of teleports and they are not duplicates, but they did overlap in one
	 * place and the overlap was the wrong way round.
	 *
	 * <ul>
	 *   <li>{@link TeleportItems} is a table of <b>facts</b>: an Ardougne cloak reaches Ardougne.
	 *       Only it can say that, and it is what lets the guide outline the right jewellery-box row
	 *       and say "use your Ardougne cloak" while travelling.</li>
	 *   <li>The setting is a <b>habit</b>: which of those you actually carry. Nothing can derive
	 *       it, which is why it is a text field.</li>
	 * </ul>
	 *
	 * <p>The bank offering used to come from the table alone, so cutting the list down changed
	 * nothing — every city tablet you happened to own still turned up because the table knew where
	 * it went. The setting's own description promises otherwise: <i>"cut it down to the ones you
	 * actually use"</i>. It does now.
	 *
	 * <p><b>An empty list means "no opinion", not "nothing".</b> That is the long-standing reading
	 * — see {@code listedTeleportIds} — and it keeps the feature working for anyone who never opens
	 * the setting: the table answers alone, exactly as before.
	 */
	public boolean isOnTeleportList(int itemId)
	{
		return offeredByTheList(itemId);
	}

	private boolean offeredByTheList(int itemId)
	{
		String setting = config.teleportItems();
		if (setting == null || setting.trim().isEmpty())
		{
			return true;
		}
		return listedTeleportIds().contains(itemId);
	}

	/**
	 * The teleports on the player's list, and nothing else — the whole of the feature.
	 *
	 * <h2>The region table no longer offers anything, by owner decision</h2>
	 *
	 * This used to walk {@code TeleportItems} for every region the run visits and offer
	 * whatever reached one, with the list as a filter on top. That was the plugin modelling a
	 * question another plugin already owns: Shortest Path routes with the player's own
	 * transport settings, and knows their unlocks in a way a static table never can — there
	 * are too many teleport options across account types and progression for a table to be
	 * right about anyone's. So the loadout's teleports are exactly the player's own list,
	 * Ground Items style: names in a setting, matched against items they actually own. The
	 * table survives only to power the travel hint's "which item gets you there" and to match
	 * the alternate spellings in {@code resolve}.
	 *
	 * <h2>Matched by name, against the bank and the pack, which is what makes it need no ids</h2>
	 *
	 * A list of item names cannot be turned into ids without an index of every item in the game,
	 * and there is no such thing to hand. But the only ids that matter here are the ones you own
	 * — nothing else can be filtered, laid out or highlighted — and the bank and the pack
	 * together <i>are</i> that index, for exactly the items in question. So the names are read
	 * off both and matched. The pack half matters more than it looks: a teleport you always
	 * carry may exist in the bank only as a placeholder, which is not contents.
	 *
	 * <p>Anything listed but not owned simply never appears — no advice to go and buy things.
	 */
	private void addListedTeleports(List<LoadoutItem> items)
	{
		// One row per teleport, not per charge.
		//
		// The id set behind this deliberately holds every charge — that is what a wildcard is for,
		// and what {@code isOnTeleportList} needs so the bank lights all of them — but a row is
		// advice, and "withdraw your games necklace" is one piece of advice however many charges
		// the bank happens to be holding. Listing them separately told the player to take out the
		// (5) and the (1) of a teleport crystal while the (2) sat in their pack, because each
		// charge was a different item and none of them was the one being carried. Reported from
		// play.
		//
		// Grouped by the name with its charge stripped, and a group counts as HAVE if <b>any</b>
		// of its charges is carried, which is the question the player is actually asking.
		Map<String, List<Integer>> byTeleport = new java.util.LinkedHashMap<>();
		for (int itemId : listedTeleportIds())
		{
			byTeleport.computeIfAbsent(chargeless(itemNames.get(itemId, "Teleport").toLowerCase()),
				k -> new ArrayList<>()).add(itemId);
		}

		for (List<Integer> charges : byTeleport.values())
		{
			int shown = charges.get(0);
			boolean have = false;
			for (int itemId : charges)
			{
				if (carried.has(itemId))
				{
					// The one in the pack is the one to name, so the row reads as the item the
					// player is looking at rather than as some other charge of it.
					shown = itemId;
					have = true;
					break;
				}
			}

			items.add(new LoadoutItem(shown, itemNames.get(shown, "Teleport"),
				LoadoutItem.Category.TELEPORT,
				have ? LoadoutItem.Need.HAVE : LoadoutItem.Need.WITHDRAW, 0,
				"On your teleport list"));
		}
	}

	/** The setting, bank and pack the cached answer below was worked out from. */
	private String resolvedFrom;
	private Set<Integer> resolvedBank;
	private Set<Integer> resolvedCarried;
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

		// The pack as well as the bank, and the gap was reported from play: every house tab
		// carried, none banked, and the bank holding only their placeholder — which
		// BankContents rightly does not count. Matching the bank alone meant the tabs had no
		// row at all, so the filter would not even show the placeholder, until one was
		// deposited and became bank contents. What you are carrying is as good an index of
		// "items you own whose names we know" as the bank is.
		Set<Integer> bankIds = bank.getItemIds();
		Set<Integer> carriedIds = carried.getItemIds();
		if (setting.equals(resolvedFrom) && bankIds.equals(resolvedBank)
			&& carriedIds.equals(resolvedCarried))
		{
			return resolvedIds;
		}
		resolvedFrom = setting;
		resolvedBank = bankIds;
		resolvedCarried = carriedIds;

		Set<Integer> owned = new LinkedHashSet<>(bankIds);
		owned.addAll(carriedIds);
		resolvedIds = resolve(setting, owned);
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

		// One row per physical item, not per item id. Charged and uncharged forms, an item and
		// its bank placeholder, a full and an empty Ectophial — all share their in-game name,
		// and a set keyed on the raw id turned each into its own teleport row: two Books of the
		// dead, side by side. Keyed on the name instead, holding one representative id; the
		// carried form wins when there is one, because the row's HAVE/WITHDRAW answer is read
		// off exactly that id. The name is safe as an identity precisely because it is also
		// the thing being matched: two ids the same entry matched by the same name are, to the
		// player reading the list, one item.
		Map<String, Integer> found = new LinkedHashMap<>();
		for (int itemId : bankIds)
		{
			// Two names per item, and an entry matching either counts. The game's, learned from
			// the bank, is what a player reads off their own screen; the table's is what they read
			// in this setting's default. They are not always the same string — "Teleport to house"
			// against "Teleport to house tablet", "Skills necklace(6)" against "Skills necklace" —
			// and matching only the first is why the shipped default quietly offered neither.
			// See TeleportItems.nameFor.
			String name = itemNames.get(itemId, null);
			String label = TeleportItems.nameFor(itemId);
			if (name == null && label == null)
			{
				// Not read yet, and not ours. Names are learned when a bank is opened, so this
				// resolves itself the moment there is a bank to resolve it against.
				continue;
			}

			if (matchesName(exact, wildcards, name) || matchesName(exact, wildcards, label))
			{
				String key = (name != null ? name : label).toLowerCase();
				Integer existing = found.get(key);
				if (existing == null || (!carried.has(existing) && carried.has(itemId)))
				{
					found.put(key, itemId);
				}
			}
		}
		return new LinkedHashSet<>(found.values());
	}

	/**
	 * An item name without its charge count, for grouping the variants of one teleport.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"in my inventory I have one with (2) teleports, and the notification icon is telling me to
	 * withdraw (5) and (1) of it"</i>, with "Teleport crystal" on the teleport list.
	 *
	 * <p>The grouping above already prefers a variant you are carrying — that is what the
	 * {@code carried.has} test is for — but it keyed on the <b>whole</b> name, and
	 * "teleport crystal (5)" and "teleport crystal (1)" are different strings. So each charge
	 * became a teleport of its own: two rows telling you to withdraw the same item twice, neither
	 * satisfied by the one already in your pack.
	 *
	 * <p>Digits only, and anchored to the end, because that is the charge convention and nothing
	 * else uses it — a "Slayer ring (eternal)" keeps its suffix and stays its own item. The space
	 * is optional because the game is inconsistent about it: "Teleport crystal (5)" has one,
	 * "Games necklace(8)" does not.
	 */
	private static String chargeless(String name)
	{
		return name == null ? null : name.replaceAll("\\s*\\(\\d+\\)$", "").trim();
	}

	private static boolean matchesName(Set<String> exact, List<String> wildcards,
		@javax.annotation.Nullable String name)
	{
		return name != null && (exact.contains(name.toLowerCase()) || matchesAny(wildcards, name));
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
	 * What the bank should mark: the things to take out of it, and nothing else.
	 *
	 * <p>Items the leprechaun already holds used to be marked too, in a second colour meaning
	 * "leave this". The argument for it was that unmarked compost reads as the plugin having
	 * forgotten about compost — but in practice a bank with your buckets and your ultracompost
	 * lit up reads as a bank telling you to take them, whatever colour it uses, and a highlight
	 * that means <i>don't</i> is a highlight fighting what a highlight is for. The leprechaun's
	 * store is the guide's job to talk about, at the patch, where the errand actually is.
	 *
	 * <p>Things already carried are absent for the same reason they always were: there is nothing
	 * to do about those.
	 */
	public Map<Integer, LoadoutItem.Need> highlights(Set<PatchImplementation> types)
	{
		Map<Integer, LoadoutItem.Need> marked = new LinkedHashMap<>();
		for (LoadoutItem item : forRun(types))
		{
			if (item.getNeed() == LoadoutItem.Need.WITHDRAW)
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
	 * Says what the loadout resolved to, once, when a bank is opened.
	 *
	 * <p>Same reasoning as the contract state and the stats totals: nothing showing in the bank
	 * has several quite different causes and every one of them looks identical from in front of
	 * it. No run types ticked, no seeds picked, no patch wanting anything, a bank never opened so
	 * every item still reads as {@code UNKNOWN} rather than {@code WITHDRAW}, or a run not
	 * started at all — the overlay can only respond to all five by drawing nothing.
	 *
	 * <p>One line so the answer is in {@code client.log} rather than in a back-and-forth.
	 */
	public void logState(Set<PatchImplementation> types, boolean runActive)
	{
		List<LoadoutItem> items = forRun(types);

		int withdraw = 0;
		StringBuilder counted = new StringBuilder();
		for (LoadoutItem item : items)
		{
			if (item.getNeed() != LoadoutItem.Need.WITHDRAW)
			{
				continue;
			}
			withdraw++;
			if (item.getOutstanding() > 0)
			{
				counted.append(counted.length() == 0 ? "" : ", ")
					.append(item.getName()).append(" x").append(item.getOutstanding());
			}
		}

		log.info("Doogle Maps loadout: run {}, types {}, bank seen {}; {} items, {} to withdraw"
				+ "{}",
			runActive ? "active" : "not started (bank highlight needs a started run)",
			types.isEmpty() ? "none ticked" : types.toString(),
			bank.hasBeenSeen(), items.size(), withdraw,
			counted.length() == 0 ? ", none with a count" : " - counted: " + counted);
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

		// Every charge and fill state of the same physical item, from the client's own
		// variation table: a full Ectophial (4251) and an empty one (4252) are one item to
		// the player, a skills necklace is one necklace at any charge. Matching only the
		// exact id was the root of the bank's duplicate rows — the layout held one variant,
		// the bank another, and every consumer of this method concluded they were unrelated.
		forms.addAll(net.runelite.client.game.ItemVariationMapping.getVariations(
			net.runelite.client.game.ItemVariationMapping.map(itemId)));

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
