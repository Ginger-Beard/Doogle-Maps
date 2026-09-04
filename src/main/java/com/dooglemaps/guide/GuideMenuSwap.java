package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.Seed;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.state.SeedSource;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.eventbus.Subscribe;

/**
 * Makes the guide's inventory click the left-click, where one is wanted.
 *
 * <p>Two shapes of swap. Jane's Contract option is <b>step-driven</b>: it only wins while the
 * guide is pointing at her. The empty bucket's Drop and the seed box's Fill/Empty are
 * <b>standing arrangements</b>: with the setting on they hold for the whole run, because there
 * is no single moment to choose — the bucket is drop-on-sight (see {@code
 * GuideTracker.appendReturnBuckets}) and the box's wanted option is read off the box itself,
 * see {@link #wantedBoxOption}. Every swap moves the named item alone and touches nothing else
 * in the pack.
 *
 * <p>This is the one place the plugin shapes input rather than describing it. The original
 * spec asked for the seed box swap on day one; it waited because of exactly that — see
 * {@code docs/design-principles.md} on the compliance line. It stays on the right side of
 * that line the same way core's own Menu Entry Swapper does: reordering what a click means is
 * the player's standing instruction, and nothing here clicks anything.
 *
 * <h2>Reordered, never renamed</h2>
 *
 * {@code SeedCapture} recognises Fill and Empty by their option <i>strings</i> to keep the
 * box's contents right, so this class only moves entries. A swap that rewrote the text would
 * silently break the seed accounting — which now decides the swap itself, so it would break
 * this class along with everything else that reads the box.
 */
@lombok.extern.slf4j.Slf4j
@Singleton
public class GuideMenuSwap
{
	private final Client client;
	private final GuideTracker tracker;
	private final DoogleMapsConfig config;
	private final SeedInventoryStore seeds;

	/** What the run still wants of a hovered item, for the withdraw-amount swap. */
	private final com.dooglemaps.bank.RunLoadout loadout;

	/** The pack as last echoed by the server, for retiring {@link #pendingTakes} entries. */
	private final CarriedItems carried;

	@Inject
	GuideMenuSwap(Client client, GuideTracker tracker, DoogleMapsConfig config,
		SeedInventoryStore seeds, com.dooglemaps.bank.RunLoadout loadout, CarriedItems carried)
	{
		this.client = client;
		this.tracker = tracker;
		this.config = config;
		this.seeds = seeds;
		this.loadout = loadout;
		this.carried = carried;
	}

	/**
	 * Last of every {@code PostMenuSort} subscriber, deliberately.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * The fairy ring swap did nothing: Last-destination stayed on the left click with the route
	 * going through a ring, the setting on and the guard passing. Nothing here was wrong — core's
	 * Menu Entry Swapper was doing the same job with a different answer, and winning because it
	 * ran second. {@code menuentryswapper.swapFairyRing=LAST_DESTINATION} is set in every one of
	 * this account's profiles, and it is core's <i>default</i>, so this was never going to be
	 * rare.
	 *
	 * <p>{@code EventBus.register} orders subscribers by
	 * {@code comparingDouble(getPriority).reversed().thenComparing(class name)} — so equal
	 * priorities break the tie <b>alphabetically</b>, and {@code com.dooglemaps...} sorts ahead
	 * of {@code net.runelite.client.plugins.menuentryswapper...}. Every swap in this class was
	 * being made and then quietly overwritten by any core swap that happened to want the same
	 * entry; the fairy ring is simply where the two disagreed loudly enough to notice. The same
	 * hazard as the {@code GameTick} ordering noted in {@code docs/NOTES.md}, one event over.
	 *
	 * <p>A negative priority is <i>later</i>, not earlier — the comparator is reversed. Running
	 * last is the right end for this class: every swap below is opt-in, scoped to a run or a
	 * step, and expresses what the guide is asking for <i>right now</i>, which is a narrower
	 * claim than a standing preference and should be the one that survives. Outside those
	 * windows nothing is touched and core's answer stands, which is why this cannot quietly
	 * take over a menu the player set up for themselves.
	 */
	@Subscribe(priority = -1f)
	public void onPostMenuSort(PostMenuSort event)
	{
		// Never reshuffle a menu the player is already looking at.
		if (!config.guidedMode() || client.isMenuOpen())
		{
			return;
		}

		GuideStatus status = tracker.getStatus();
		boolean running = status.isRunning();

		// The standing swap: for the whole run, an empty bucket's left-click is Drop - except
		// where the guide is asking for the buckets, which is a compost bin. Same test the
		// inventory overlay's red uses, so the highlight and the left-click cannot disagree.
		if (config.dropEmptyBuckets() && running && !status.wantsEmptyBuckets())
		{
			promote("Drop", entry -> entry.getItemId() == ItemID.BUCKET_EMPTY);
		}

		// The withdraw amount closest to what is still wanted, at a bank or his store.
		//
		// Standing rather than step-driven, and a run-long arrangement like the seed box below:
		// there is no single moment to choose, because the answer is simply "however many of this
		// are still missing" and that is true of every withdrawal the trip makes.
		if (config.withdrawAmountSwap() && running)
		{
			swapWithdrawAmount();
		}

		// The box's own standing swap, for the whole run and answered by the box's contents.
		if (config.seedBoxLeftClick() && running)
		{
			promote(wantedBoxOption(seeds), entry -> isSeedBox(entry.getItemId()));
		}

		GuideStep step = tracker.getCurrentStep();

		// Jane's Contract option, while a contract step is current. Step-driven, so it only
		// wins at the moment the guide is pointing at her — and matched on the NPC rather
		// than an item id. Requested from play.
		if (config.contractLeftClick() && step != null
			&& (step.getAction() == GuideAction.HAND_IN_CONTRACT
				|| step.getAction() == GuideAction.TAKE_CONTRACT))
		{
			promote("Contract", entry -> entry.getNpc() != null
				&& com.dooglemaps.state.ContractState.JANE_NPC_IDS.contains(entry.getNpc().getId()));
		}

		// A grimy herb's Use option, while a note step is current. Every other crop he notes
		// already defaults to Use; a grimy herb's left-click is Clean, so following the
		// highlight quietly cleaned a herb instead — and cleaned herbs are ones he will not
		// note. Requested from play. Any grimy herb, not just the one the step names: the
		// step points at the biggest stack, the visit notes them all, and the menu being
		// reordered is only ever the hovered item's own.
		if (config.herbUseLeftClick() && step != null
			&& step.getAction() == GuideAction.NOTE_AT_LEPRECHAUN)
		{
			promote("Use", entry ->
				com.dooglemaps.data.NotableHarvests.isGrimyHerb(entry.getItemId()));
		}

		// The per-patch Pay, while a step is asking for one. The only swap here that prevents a
		// mistake rather than saving a click: a farmer who charges per patch offers a Pay for
		// each, only one of them is the step's, and the left click lands on whichever the NPC
		// happens to list first. Chet opens on "Pay (East)" even when the guide means West, and
		// paying the wrong nursery spends the payment.
		//
		// Matched on the patch's own name appearing in the option - the disambiguator inside its
		// region ("East", "North") is the word the farmer spells into it - rather than on wording
		// copied out of one NPC's menu. A farmer whose patches have no name, or who phrases it
		// some other way, matches nothing and the menu is left exactly as the game built it.
		if (config.payLeftClick() && step != null
			&& step.getAction() == GuideAction.PAY_FARMER)
		{
			promotePayFor(step.getPatch());
		}

		// The fairy ring, while the route is actually going through one.
		//
		// A ring opens on Zanaris or its last destination, and neither is where the run is going
		// - the code is in the hop Shortest Path reported, which the panel is already showing
		// you. So the click that matters is Configure, and it is the one the game buries.
		//
		// Scoped to the route naming a ring rather than to the whole run, which is the tighter
		// half of the rule the other step-driven swaps follow: the guide is talking about this
		// ring right now, and about no ring at all the rest of the time. Walk past one on an
		// unrelated errand and its menu is the game's own.
		//
		// Core does this too - MenuEntrySwapper's swapFairyRing offers Configure by name - which
		// is the precedent the compliance note leans on for every swap here.
		//
		// liveTransports, not the tick snapshot, and the garden ring is why. A house is an
		// instance and the router cannot path from inside one, so getCurrentTransports - which
		// is all GuideStatus carries - empties the moment the teleport lands. Reported from play:
		// Configure was not the default on the POH fairy ring. The overlay lit it correctly the
		// whole time, because highlightHouseTeleports already reads the live list; these two ask
		// the same question about the same object and were reading different answers.
		// See GuideTracker.liveTransports for the memo that keeps the hop list that entered.
		if (config.fairyRingLeftClick() && running
			&& routeUsesAFairyRing(tracker.liveTransports()))
		{
			promote("Configure", entry -> mentionsAFairyRing(entry.getTarget()));
		}
	}

	/** The wording Shortest Path reports for a ring hop, e.g. "Configure Fairy ring - C K Q". */
	static boolean routeUsesAFairyRing(java.util.List<String> hops)
	{
		for (String hop : hops)
		{
			if (mentionsAFairyRing(hop))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether this text names a fairy ring.
	 *
	 * <p>Tags stripped because a menu target arrives coloured — {@code <col=00ffff>Fairy ring} —
	 * and a plain {@code contains} would miss it about half the time depending on where the tag
	 * fell. The same {@code Text.removeTags} the bank highlight uses on widget text.
	 */
	static boolean mentionsAFairyRing(String text)
	{
		return text != null && net.runelite.client.util.Text.removeTags(text)
			.toLowerCase(java.util.Locale.ROOT).contains("fairy ring");
	}

	/** Puts this patch's own Pay option under the left click, if the farmer offers one. */
	private void promotePayFor(com.dooglemaps.data.FarmPatch patch)
	{
		if (patch == null)
		{
			return;
		}

		if (isOnlyPatchForItsFarmer(patch))
		{
			// No sibling patch shares this farmer, so there is nothing else a plain "Pay"
			// could mean - the exact id is the whole disambiguation and the option's wording
			// does not have to name the patch at all. Fossil Island's three hardwood patches
			// are three different squirrels, each the sole farmer of its own patch, and each
			// squirrel's menu reads a plain "Pay" with no patch name in it anywhere - so the
			// name-contains rule below could never match there and paying never promoted.
			// Reported from play: none of the three squirrels ever swapped.
			promoteMatching(entry -> entry.getNpc() != null
				&& entry.getNpc().getId() == patch.getFarmer()
				&& startsWithPay(entry.getOption()));
			return;
		}

		String name = patch.getName();
		if (name.isEmpty())
		{
			// Several patches share this farmer and this one has no disambiguating name -
			// nothing in the option text can single it out, so leave the menu as built.
			return;
		}

		String wanted = name.toLowerCase(java.util.Locale.ROOT);
		promoteMatching(entry ->
		{
			String option = entry.getOption();
			return entry.getNpc() != null
				&& com.dooglemaps.data.FarmerVariants.same(patch.getFarmer(), entry.getNpc().getId())
				&& startsWithPay(option)
				&& option.toLowerCase(java.util.Locale.ROOT).contains(wanted);
		});
	}

	private static boolean startsWithPay(@javax.annotation.Nullable String option)
	{
		return option != null && option.toLowerCase(java.util.Locale.ROOT).startsWith("pay");
	}

	/**
	 * Whether no other patch in this farmer's region answers to the same farmer id.
	 *
	 * <p>A farmer tending several patches (Chet, the two coral nurseries) needs the option text
	 * itself to say which one was chosen; a farmer with exactly one needs nothing beyond the
	 * exact id, which is the case the name-contains rule cannot cover at Fossil Island.
	 */
	private static boolean isOnlyPatchForItsFarmer(com.dooglemaps.data.FarmPatch patch)
	{
		if (patch.getRegion() == null)
		{
			return true;
		}
		for (com.dooglemaps.data.FarmPatch sibling : patch.getRegion().getPatches())
		{
			if (sibling != patch && sibling.getFarmer() == patch.getFarmer())
			{
				return false;
			}
		}
		return true;
	}

	/** Moves the named option on the matched entry to the left-click, at full priority. */
	/**
	 * Puts the withdraw amount nearest what the run still wants under the left click.
	 *
	 * <p>See {@code docs/withdraw-quantity-swap-spec.md}. The arithmetic is
	 * {@code WithdrawQuantity}; this half is only about finding the item and the offered amounts.
	 *
	 * <h2>Collection verbs only</h2>
	 *
	 * "Withdraw" and "Remove" — the bank's word and the leprechaun's, the two confirmed in play —
	 * and nothing else. This scanned by amount alone at first, on the reasoning that the verb was
	 * cosmetic, and the bank's <b>inventory side</b> proved otherwise: hovering an item there
	 * offers {@code Deposit-1/-5/-10}, those parse as amounts, and {@code stillWantedNow} is
	 * non-zero for exactly the items the run is mid-collecting — so the swap promoted
	 * {@code Deposit-10} on the very buckets it had just told the player to withdraw. A click
	 * re-aimed to put the supplies back, by the feature that exists to make clicks safe. Found in
	 * review; the spec's §7 had already settled deposits as not this feature's business.
	 *
	 * <h2>One item, or nothing</h2>
	 *
	 * A menu is built for the thing under the cursor, so every withdraw entry on it should name
	 * the same item. If two ever do not — a shape this has not seen but cannot rule out — the menu
	 * is left exactly as the game built it rather than guessed at. A swap that picks the wrong
	 * item's amount is worse than no swap. An entry that cannot be identified <b>at all</b>
	 * stands the whole swap down for the same reason: it used to be quietly absorbed as "matches
	 * anything", which made the guard weaker than its own promise.
	 *
	 * <h2>Matched by the amount, not by the wording</h2>
	 *
	 * The entry to promote is found by asking {@code WithdrawQuantity.amountNamed} again rather
	 * than by rebuilding the option string. {@code Withdraw-X} answers 0 to that question whatever
	 * number it is wearing, so it can never be found and never be moved — see
	 * {@code WithdrawQuantity.LADDER}.
	 *
	 * <h2>All, when the run wants at least a whole pack</h2>
	 *
	 * The one case the ladder was the wrong tool for: thirty melons into twenty-odd free slots
	 * is three ladder clicks that all mean "fill the pack", and Withdraw-All says exactly that
	 * in one — for an unstackable it takes what fits and stops, so with wanted at or past the
	 * pack it cannot overshoot. {@code RunLoadout.fillsThePack} owns the guard rails (fills and
	 * buckets only, never the stackable ash, never noted-friendly payments). Asked for by the
	 * owner: "when withdraw count is equal or more than free inv space, just use the All
	 * option."
	 */
	/**
	 * Collection clicks the server has not echoed yet, keyed {@code "#id"} or {@code "n:name"}.
	 *
	 * <h2>The click is the freshest fact there is</h2>
	 *
	 * {@code stillWantedNow} subtracts the pack, and the pack only updates on the server's
	 * echo — up to a game tick after the click. So a player banking at full speed outran the
	 * swap: the item just taken still read as wanted, the same left click stood armed for a
	 * second stack, and the inert flip arrived a beat late. Reported from play: <i>"our menu
	 * swapping for withdraw can't keep up with the player unless they deliberately slow
	 * down"</i> — and then, asked exactly right: <i>"can we count clicks outside of the games
	 * timekeeping?"</i> We can: {@code MenuOptionClicked} fires client-side the instant the
	 * click happens, so this ledger is written at click speed and the tick appears only as a
	 * safety horizon. An entry retires the moment the echo lands (the carried count rising
	 * past its baseline) or after {@link #PENDING_CLICK_TICKS} for a click that never landed —
	 * a full pack, an empty bank slot — so a wrong guess can only under-promote, briefly.
	 */
	private final java.util.Map<String, PendingTake> pendingTakes = new java.util.HashMap<>();

	/** The horizon for a click whose echo never comes; the echo itself usually retires it. */
	private static final int PENDING_CLICK_TICKS = 2;

	private static final class PendingTake
	{
		int amount;
		int clickedAtTick;
		int itemId;
		int heldAtClick;

		/**
		 * The printed name from the clicked entry, because the same item's menu does not
		 * build the same way every frame: a session log showed one palm sapling alternating
		 * between {@code id=5502} and {@code id=-1, name='Palm sapling'} within a second. A
		 * click counted under the id key was invisible to a name-only frame, and that frame's
		 * stale promotion was the residue of the very overshoot this ledger removes —
		 * "we're still going over when the timing is wrong". The name is the bridge.
		 */
		@javax.annotation.Nullable
		String itemName;
	}

	@Subscribe
	public void onMenuOptionClicked(net.runelite.api.events.MenuOptionClicked event)
	{
		if (config.withdrawAmountSwap())
		{
			countCollectionClick(event.getMenuEntry());
		}
	}

	/**
	 * Writes one collection click into {@link #pendingTakes}, at click time.
	 *
	 * <p>Counts only rows the run is actually sizing — the same {@code stillWantedNow} gate
	 * the swap itself uses, so ordinary banking is never ledgered — and never more than the
	 * row still wants: a right-clicked Withdraw-10 against a want of four counts four, which
	 * is what lets the inert flip fire on the very next menu.
	 */
	void countCollectionClick(@javax.annotation.Nullable MenuEntry entry)
	{
		if (entry == null || !isCollectionOption(entry.getOption()))
		{
			return;
		}
		int amount = com.dooglemaps.bank.WithdrawQuantity.amountNamed(entry.getOption());
		boolean all = amount <= 0
			&& com.dooglemaps.bank.WithdrawQuantity.namesAll(entry.getOption());
		if (amount <= 0 && !all)
		{
			return;
		}
		int itemId = itemIdOf(entry);
		String itemName = itemNameOf(entry);
		if (itemId <= 0 && itemName == null)
		{
			return;
		}

		int listed = itemId > 0 ? loadout.stillWantedNow(itemId) : 0;
		if (listed <= 0 && itemName != null)
		{
			listed = loadout.stillWantedNow(itemName);
		}
		if (listed <= 0)
		{
			return;
		}

		int taken = all ? listed : Math.min(amount, listed);
		String key = itemId > 0 ? "#" + itemId : "n:" + itemName;
		PendingTake pending = pendingTakes.computeIfAbsent(key, k -> new PendingTake());
		if (pending.amount == 0)
		{
			// The baseline belongs to the first un-echoed click; a second click before the
			// echo accumulates against the same one.
			pending.itemId = itemId;
			pending.heldAtClick = itemId > 0 ? carried.getCountIncludingNoted(itemId) : -1;
		}
		if (pending.itemName == null)
		{
			pending.itemName = itemName;
		}
		pending.amount += taken;
		pending.clickedAtTick = client.getTickCount();
		log.debug("Counted a collection click before its echo: {} x{} pending", key, taken);
	}

	/** What the un-echoed clicks have already taken of this item; expired entries drop here. */
	private int pendingTaken(int itemId, @javax.annotation.Nullable String itemName)
	{
		int now = client.getTickCount();
		pendingTakes.values().removeIf(pending ->
			now - pending.clickedAtTick > PENDING_CLICK_TICKS
				|| (pending.itemId > 0
					&& carried.getCountIncludingNoted(pending.itemId) > pending.heldAtClick));

		PendingTake pending = itemId > 0 ? pendingTakes.get("#" + itemId) : null;
		if (pending == null && itemName != null)
		{
			pending = pendingTakes.get("n:" + itemName);
		}
		if (pending == null && itemName != null)
		{
			// The bridge for a frame that names the item without its id, against a click
			// counted under the id — see PendingTake.itemName. A handful of entries at most,
			// scanned only on a double miss.
			for (PendingTake candidate : pendingTakes.values())
			{
				if (itemName.equals(candidate.itemName))
				{
					pending = candidate;
					break;
				}
			}
		}
		return pending == null ? 0 : pending.amount;
	}

	private void swapWithdrawAmount()
	{
		MenuEntry[] entries = client.getMenu().getMenuEntries();

		int itemId = -1;
		String itemName = null;
		// Which container the menu belongs to, for sizing a split bank/vault pair from its own
		// row rather than whichever half a plain id lookup happens to reach first. Every entry on
		// one menu is the same interface, so the first one seen settles it for the whole call.
		com.dooglemaps.bank.LoadoutItem.From from = com.dooglemaps.bank.LoadoutItem.From.BANK;
		boolean seenAny = false;
		boolean allOffered = false;
		java.util.Set<Integer> offered = new java.util.HashSet<>();
		for (MenuEntry entry : entries)
		{
			if (!isCollectionOption(entry.getOption()))
			{
				continue;
			}
			int amount = com.dooglemaps.bank.WithdrawQuantity.amountNamed(entry.getOption());
			boolean all = amount <= 0
				&& com.dooglemaps.bank.WithdrawQuantity.namesAll(entry.getOption());
			if (amount <= 0 && !all)
			{
				continue;
			}

			int on = itemIdOf(entry);
			String named = itemNameOf(entry);
			if (on <= 0 && named == null)
			{
				// A candidate nobody can identify. Guessing that it is about the same item as
				// its neighbours is exactly the guess the one-item rule exists to refuse.
				logStandDown("unidentifiable entry '" + entry.getOption() + "'");
				return;
			}
			if (!seenAny)
			{
				itemId = on;
				itemName = named;
				from = fromOf(entry);
				seenAny = true;
			}
			else
			{
				// By id where both entries have one — the stronger identity — and by the
				// printed name otherwise.
				boolean same = (on > 0 && itemId > 0)
					? on == itemId
					: java.util.Objects.equals(named, itemName);
				if (!same)
				{
					logStandDown("two items on one menu");
					return;
				}
			}
			if (all)
			{
				allOffered = true;
			}
			else
			{
				offered.add(amount);
			}
		}

		if (offered.isEmpty() && !allOffered)
		{
			return;
		}

		// All, when the run wants at least a whole pack of an unstackable — one click that takes
		// exactly what fits and cannot overshoot. RunLoadout.fillsThePack carries the argument
		// and the exclusions; the id-then-name order is the same junk-id fallback the sizing
		// below uses, for the same reason.
		if (allOffered
			&& ((itemId > 0 && loadout.fillsThePack(itemId))
				|| (itemName != null && loadout.fillsThePack(itemName))))
		{
			logDecision("item id=" + itemId + " name='" + itemName
				+ "' wants a packful -> promote All");
			final int item = itemId;
			final String name = itemName;
			promoteMatching(entry ->
				com.dooglemaps.bank.WithdrawQuantity.namesAll(entry.getOption())
					&& isCollectionOption(entry.getOption())
					&& (item > 0 ? itemIdOf(entry) == item
						: java.util.Objects.equals(itemNameOf(entry), name)));
			return;
		}

		// Live, and that is the whole of why this is not read off the row's own count. A player
		// clicking ten and then five does both inside one tick, and a tick-old answer would offer
		// ten again and take twenty-five. See RunLoadout.stillWantedNow.
		//
		// The vault-widget overload only, and only when this menu IS the vault's: a bank menu
		// keeps calling the plain overload it always has, so a split bank/vault pair is sized
		// from the row whose own container matches this menu's, without touching the ordinary,
		// unsplit case at all.
		boolean vaultMenu = from == com.dooglemaps.bank.LoadoutItem.From.SEED_VAULT;
		int wanted = itemId > 0
			? (vaultMenu ? loadout.stillWantedNow(itemId, from) : loadout.stillWantedNow(itemId))
			: 0;

		// The name whenever the id answers nothing — not only when there is no id at all.
		//
		// An id is only trusted as far as it finds a row. Interfaces that do not populate the
		// item op properly can hand back a POSITIVE id that is not the item — and a junk id
		// sizes a row that does not exist, answers zero, and stands the swap down with nothing
		// logged, which is indistinguishable from working-as-intended. The fallback is safe by
		// construction: a genuine id and the printed name find the SAME row, so falling back can
		// never turn a real "nothing wanted" into a number — the seed row says 0 by either path.
		// Only an id that matches no row leaves the name to find the right one.
		if (wanted <= 0 && itemName != null)
		{
			wanted = vaultMenu ? loadout.stillWantedNow(itemName, from)
				: loadout.stillWantedNow(itemName);
		}

		// Then the clicks the server has not confirmed yet, counted at click speed rather
		// than tick speed — see pendingTakes. `listed` keeps the pre-ledger answer, because a
		// sized row covered by pending clicks earns the inert flip below without waiting for
		// doneWithdrawing's container-based agreement.
		int listed = wanted;
		wanted = Math.max(0, wanted - pendingTaken(itemId, itemName));

		// Done withdrawing means the left click goes INERT, not merely unhelped. With the count
		// satisfied, the game's own default — Withdraw-1, or whatever the quantity toggle says —
		// is the one remaining way to take more than the list asked, and it is sitting under
		// the very click the player has been spamming. Examine is the option that does nothing,
		// which is exactly what is wanted of the next stray click.
		//
		// Only for a row the run TRACKED and has now covered — RunLoadout.doneWithdrawing draws
		// that line, and it is the whole safety of the feature: wanted comes back zero for
		// seeds (deliberately unsized) and for items that were never the run's business, and
		// swapping those to Examine would break ordinary banking.
		if (wanted <= 0
			&& (listed > 0
				|| (itemId > 0 && (vaultMenu ? loadout.doneWithdrawing(itemId, from)
					: loadout.doneWithdrawing(itemId)))
				|| (itemName != null && (vaultMenu ? loadout.doneWithdrawing(itemName, from)
					: loadout.doneWithdrawing(itemName)))))
		{
			logDecision("item id=" + itemId + " name='" + itemName
				+ "' fully collected -> promote Examine");
			final int item = itemId;
			final String name = itemName;
			promoteMatching(entry -> "Examine".equalsIgnoreCase(entry.getOption())
				&& (item > 0 ? itemIdOf(entry) == item
					: java.util.Objects.equals(itemNameOf(entry), name)));
			return;
		}

		int amount = com.dooglemaps.bank.WithdrawQuantity.choose(offered, wanted);

		// The whole decision, at debug, once per distinct answer. The swap's guards are
		// deliberately silent in game, and that silence has now cost two play sessions of
		// guessing at the leprechaun — this line is what makes the third report answerable from
		// client.log: which item was seen, what the run said it wanted, and what was chosen.
		logDecision("item id=" + itemId + " name='" + itemName + "' wanted=" + wanted
			+ " offered=" + offered + " -> "
			+ (amount > 0 ? "promote " + amount : "nothing to promote"));

		if (amount <= 0)
		{
			return;
		}

		final int chosen = amount;
		final int item = itemId;
		final String name = itemName;
		promoteMatching(entry ->
			com.dooglemaps.bank.WithdrawQuantity.amountNamed(entry.getOption()) == chosen
				&& isCollectionOption(entry.getOption())
				&& (item > 0 ? itemIdOf(entry) == item
					: java.util.Objects.equals(itemNameOf(entry), name)));
	}

	/**
	 * Whether an option is one of the two collection verbs, ahead of its dash.
	 *
	 * <p>A whitelist like {@code WithdrawQuantity.LADDER} and for the same reason: everything off
	 * it is left exactly where the game put it. {@code Deposit} is the one that bites (see
	 * {@code swapWithdrawAmount}); a shop's {@code Buy 5} never parsed anyway — spaces, not
	 * dashes — but an accident is not a guarantee, and this makes it one.
	 */
	private static boolean isCollectionOption(@javax.annotation.Nullable String option)
	{
		if (option == null)
		{
			return false;
		}
		int dash = option.indexOf('-');
		if (dash <= 0)
		{
			return false;
		}
		String verb = option.substring(0, dash);
		return "Withdraw".equalsIgnoreCase(verb) || "Remove".equalsIgnoreCase(verb);
	}

	/** The last line logged, so hovering the same menu does not repeat it every frame. */
	private String loggedSwapLine;

	/**
	 * Says at debug why a ladder-shaped menu was left alone.
	 *
	 * <p>The swap's failures are deliberately silent in game — a menu quietly not rearranged is
	 * the correct behaviour, not an error — but "it never worked at the leprechaun" cost a
	 * play-session of guessing precisely because nothing recorded which guard stood it down.
	 * One line, once per distinct reason, is the difference between reading the answer out of
	 * {@code client.log} and theorising from in front of the client.
	 */
	private void logStandDown(String reason)
	{
		logSwapLine("Withdraw-amount swap standing down: " + reason);
	}

	/** As {@link #logStandDown}, for the decision a ladder-shaped menu produced. */
	private void logDecision(String decision)
	{
		logSwapLine("Withdraw-amount swap: " + decision);
	}

	private void logSwapLine(String line)
	{
		if (!log.isDebugEnabled() || line.equals(loggedSwapLine))
		{
			return;
		}
		loggedSwapLine = line;
		log.debug("{}", line);
	}

	/**
	 * The item a menu entry is about, asked of the entry and then of the widget behind it.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * The swap worked nowhere at the leprechaun. The wording was not the problem — his options are
	 * <i>"Remove-1"</i>, <i>"Remove-5"</i> and so on rather than <i>"Withdraw-N"</i>, and
	 * {@code WithdrawQuantity.amountNamed} reads the number after the first dash without caring
	 * which verb precedes it, so those parse exactly as a bank's do.
	 *
	 * <p>What failed is that {@code MenuEntry.getItemId} is not populated for every interface's
	 * item ops. His store is a widget of its own rather than the bank, so the entry answered
	 * nothing, the swap could not tell which item was hovered, and it stood down — silently, which
	 * is the right failure but an invisible one.
	 *
	 * <p>The widget knows regardless, which is the ordinary way to ask this of an interface. The
	 * entry is still asked first: it is the cheaper answer and it is the one the bank gives.
	 */
	private static int itemIdOf(MenuEntry entry)
	{
		int onEntry = entry.getItemId();
		if (onEntry > 0)
		{
			return onEntry;
		}

		net.runelite.api.widgets.Widget widget = entry.getWidget();
		return widget == null ? -1 : widget.getItemId();
	}

	/**
	 * Which container a collection menu's entry belongs to, for sizing a split bank/vault pair
	 * from its own row rather than whichever half a bare item id reaches first.
	 *
	 * <p>The entry carries no such flag of its own; its widget does, by which interface laid it
	 * out — {@code InterfaceID.SEED_VAULT}, the same group id {@code BankHighlightOverlay} reads
	 * the vault's widgets under. Defaults to {@link com.dooglemaps.bank.LoadoutItem.From#BANK}
	 * whenever there is no widget to ask, which is the safe default: everything the loadout
	 * tracks except a seed comes out of the bank.
	 */
	private static com.dooglemaps.bank.LoadoutItem.From fromOf(MenuEntry entry)
	{
		net.runelite.api.widgets.Widget widget = entry.getWidget();
		if (widget != null
			&& net.runelite.api.widgets.WidgetUtil.componentToInterface(widget.getId())
				== net.runelite.api.gameval.InterfaceID.SEED_VAULT)
		{
			return com.dooglemaps.bank.LoadoutItem.From.SEED_VAULT;
		}
		return com.dooglemaps.bank.LoadoutItem.From.BANK;
	}

	/**
	 * The item a menu entry is about, by the name printed on the entry itself.
	 *
	 * <h2>The second leprechaun fix, because the first one held only in the test</h2>
	 *
	 * {@code itemIdOf} was taught to ask the widget when the entry answered nothing, and the test
	 * pinned exactly that — a mocked widget carrying the id. In play the swap still never worked
	 * at his store: neither the entry nor the resolved widget carries an item id there, so
	 * identification failed and the swap stood down on the one interface the feature was built
	 * for. Reported from play, twice.
	 *
	 * <p>What every item op does carry, on every interface, is the item's <b>name in the
	 * target</b> — <i>"&lt;col=ff9040&gt;Empty bucket&lt;/col&gt;"</i> — because that is the text
	 * the menu shows the player. The loadout's rows carry the same names, so
	 * {@code RunLoadout.stillWantedNow(String)} can size from one. Ids are still preferred where
	 * they exist: a name is the menu's rendering and an id is the item.
	 */
	@javax.annotation.Nullable
	private static String itemNameOf(MenuEntry entry)
	{
		String target = entry.getTarget();
		if (target == null)
		{
			return null;
		}
		String name = target.replaceAll("<[^>]*>", "").trim();
		return name.isEmpty() ? null : name;
	}

	private void promote(String wanted, java.util.function.Predicate<MenuEntry> matches)
	{
		// The option compare ignores case because the exact capitalisation of NPC options is
		// not pinned anywhere the way the box's Fill and Empty are.
		promoteMatching(entry -> wanted.equalsIgnoreCase(entry.getOption()) && matches.test(entry));
	}

	/**
	 * As {@link #promote}, for an option whose exact wording is not known in advance.
	 *
	 * <p>Exists for the per-patch Pay options: "Pay (East)" is the farmer's wording, not ours,
	 * and matching it exactly would mean writing a string read off one NPC's menu into the
	 * plugin and hoping the next such farmer phrases it the same way.
	 */
	private void promoteMatching(java.util.function.Predicate<MenuEntry> matches)
	{
		Menu menu = client.getMenu();
		MenuEntry[] entries = menu.getMenuEntries();
		// The left-click option is the last entry. Walked from the top so that if the wanted
		// option is already there, nothing moves.
		for (int i = entries.length - 1; i >= 0; i--)
		{
			MenuEntry entry = entries[i];
			if (!matches.test(entry))
			{
				continue;
			}

			// The game marks Drop as a low-priority op, and a low-priority entry on top makes
			// a left click open the menu instead of acting — which read as "left click became
			// right click". Reported from play, twice: the first fix cleared the deprioritized
			// flag, but for an inventory item the priority lives in the entry's *type* —
			// CC_OP_LOW_PRIORITY against CC_OP — which is also exactly what core's Menu Entry
			// Swapper raises when it promotes an op. Both are set even when the entry is
			// already on top, since they, not the position, decide whether the click acts.
			entry.setDeprioritized(false);
			if (entry.getType() == net.runelite.api.MenuAction.CC_OP_LOW_PRIORITY)
			{
				entry.setType(net.runelite.api.MenuAction.CC_OP);
			}

			if (i != entries.length - 1)
			{
				entries[i] = entries[entries.length - 1];
				entries[entries.length - 1] = entry;
				menu.setMenuEntries(entries);
			}
			return;
		}
	}

	// The six-kind limit lives in com.dooglemaps.data.SeedBox, along with the question of which
	// items a box will hold at all. This file used to keep its own alias for the number.

	/**
	 * Which of the box's two options to put under the left click, read off the box itself.
	 *
	 * <p><b>One condition, and the highlight's own:</b> Fill when there is a loose seed in the
	 * pack that the box would actually take, Empty otherwise. Asked for from play in those
	 * words — "only lit orange with the menu set to Fill if there are seeds in the player's
	 * inventory and free space in the seed box, else the menu should be Empty and unlit".
	 *
	 * <p>It used to be a different expression: {@code anyBoxedSeeds && !boxCanTakeLooseSeeds},
	 * on the reasoning that an empty box has "nothing to tip out" so Fill is the safe default.
	 * That is true and it is not the point. {@code GuideInventoryOverlay} lights the box on
	 * {@code looseSeedTheBoxWouldTake}, so the two agreed everywhere except the one state the
	 * old default created — an empty box and an empty pack — where the box sat unlit with Fill
	 * under the left click. A free click that does nothing, on an item the overlay is saying
	 * nothing about, is worse than the right-click it saved.
	 *
	 * <p>So both now read the same method. Empty on a box with nothing in it is a click that
	 * does nothing either, which is the honest cost of the change and a smaller one: it matches
	 * what the box looks like.
	 *
	 * <p>Both options exist on the box at all times, so whichever is not chosen is still one
	 * right-click away; this only picks which is free.
	 */
	static String wantedBoxOption(SeedInventoryStore seeds)
	{
		return boxCanTakeLooseSeeds(seeds) ? "Fill" : "Empty";
	}

	/** Whether the box holds anything at all. */
	private static boolean anyBoxedSeeds(SeedInventoryStore seeds)
	{
		for (Seed seed : Seed.values())
		{
			if (seeds.getCount(seed, SeedSource.SEED_BOX) > 0)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether any loose seed could actually go into the box.
	 *
	 * <p>Not simply "are there loose seeds": the box holds six <i>kinds</i>, one stack each, so
	 * a seventh kind cannot go in however much room the six stacks have. A loose seed whose
	 * kind is already boxed always fits; a new kind fits only while a slot is free. Saplings
	 * are left out — the box will not take one, and for tree crops the count cannot tell an
	 * acorn from the sapling it became.
	 *
	 * <p>Package-visible for {@code GuideInventoryOverlay}: the fill-side box highlight is
	 * this same question, and two copies of the six-kinds rule would drift.
	 */
	static boolean boxCanTakeLooseSeeds(SeedInventoryStore seeds)
	{
		return looseSeedTheBoxWouldTake(seeds) != null;
	}

	/**
	 * The first loose seed the box would actually accept, or null when there is none.
	 *
	 * <h2>Two ways in, and the six kinds are the only limit</h2>
	 *
	 * The box holds six <b>kinds</b>, one stack each, with no practical limit per stack — the
	 * wiki's own figure is 2,147,483,647. So a loose seed of a kind already boxed always fits
	 * and always saves a slot; a new kind fits only while one of the six is free. That is the
	 * whole rule, and the fill highlight and {@link #wantedBoxOption} both read this method, so
	 * the orange and the left-click cannot disagree.
	 *
	 * <p>Split out from {@link #boxCanTakeLooseSeeds} so the overlay can say <i>which</i> seed
	 * carried the decision. "The box never lights" is not diagnosable after the fact — the
	 * answer is a handful of live counts and they have all moved on by the time it is
	 * reported — which is the same reason the take-seeds-out side grew its own log line.
	 */
	static Seed looseSeedTheBoxWouldTake(SeedInventoryStore seeds)
	{
		int kindsBoxed = kindsInTheBox(seeds);
		for (Seed seed : Seed.values())
		{
			if (seeds.getSeedCount(seed, SeedSource.INVENTORY) <= 0)
			{
				continue;
			}
			if (seeds.getSeedCount(seed, SeedSource.SEED_BOX) > 0
				|| kindsBoxed < com.dooglemaps.data.SeedBox.KINDS)
			{
				return seed;
			}
		}
		return null;
	}

	/**
	 * How many of the six kinds the box is currently recorded as holding.
	 *
	 * <h2>Counted by the seed, not by the crop</h2>
	 *
	 * This and the loop above both used to skip every crop whose {@code isSapling()} was true,
	 * with the note "the box will not take one, and for tree crops the count cannot tell an
	 * acorn from the sapling it became". Those are two different things, and conflating them
	 * cost a whole slot. The <b>sapling</b> is what the box refuses. The <b>seed</b> — a calquat
	 * seed, an acorn, a magic seed — goes in like any other and takes one of the six.
	 *
	 * <p>So a box holding five ordinary seeds and a calquat seed counted as five kinds, read as
	 * having a slot free, and lit to fill with a seventh kind loose in the pack that it would
	 * have refused. Reported from play three times as the box "staying orange"; found by
	 * resolving the live box contents, where 5290 turned out to be a calquat seed.
	 *
	 * <p>{@code SeedInventoryStore.getSeedCount} is what makes the distinction possible: it
	 * counts the seed item and never the sapling, which {@code isSapling()} cannot do because it
	 * is a fact about the crop rather than about the item in front of you.
	 *
	 * <p>Delegated rather than counted here, because there were two implementations of this and
	 * the log caught them disagreeing about the same box in the same second — the store's Fill
	 * guard said six kinds while this said five. The store owns the box's contents, so it owns
	 * the count.
	 */
	static int kindsInTheBox(SeedInventoryStore seeds)
	{
		return seeds.kindsInTheSeedBox();
	}


	/** The same two ids {@code SeedCapture} watches: closed and open. Not SEEDBOX — see there. */
	private static boolean isSeedBox(int itemId)
	{
		return itemId == ItemID.SEED_BOX
			|| itemId == ItemID.SEED_BOX_OPEN;
	}
}
