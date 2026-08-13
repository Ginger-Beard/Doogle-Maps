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
@Singleton
public class GuideMenuSwap
{
	private final Client client;
	private final GuideTracker tracker;
	private final DoogleMapsConfig config;
	private final SeedInventoryStore seeds;

	@Inject
	GuideMenuSwap(Client client, GuideTracker tracker, DoogleMapsConfig config,
		SeedInventoryStore seeds)
	{
		this.client = client;
		this.tracker = tracker;
		this.config = config;
		this.seeds = seeds;
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
		String name = patch == null ? "" : patch.getName();
		if (name.isEmpty())
		{
			// One patch, one Pay - there is nothing to choose between.
			return;
		}

		String wanted = name.toLowerCase(java.util.Locale.ROOT);
		promoteMatching(entry ->
		{
			String option = entry.getOption();
			return entry.getNpc() != null
				&& com.dooglemaps.data.FarmerVariants.same(patch.getFarmer(), entry.getNpc().getId())
				&& option != null
				&& option.toLowerCase(java.util.Locale.ROOT).startsWith("pay")
				&& option.toLowerCase(java.util.Locale.ROOT).contains(wanted);
		});
	}

	/** Moves the named option on the matched entry to the left-click, at full priority. */
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
