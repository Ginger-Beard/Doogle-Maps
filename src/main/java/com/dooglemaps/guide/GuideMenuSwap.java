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

	@Subscribe
	public void onPostMenuSort(PostMenuSort event)
	{
		// Never reshuffle a menu the player is already looking at.
		if (!config.guidedMode() || client.isMenuOpen())
		{
			return;
		}

		boolean running = tracker.getStatus().isRunning();

		// The standing swap: for the whole run, an empty bucket's left-click is Drop.
		if (config.dropEmptyBuckets() && running)
		{
			promote("Drop", entry -> entry.getItemId() == ItemID.BUCKET_EMPTY);
		}

		// The box's own standing swap, for the whole run and answered by the box's contents.
		if (config.seedBoxLeftClick() && running)
		{
			promote(wantedBoxOption(seeds), entry -> isSeedBox(entry.getItemId()));
		}

		GuideStep step = tracker.getCurrentStep();

		// Jane's Contract option, while a contract step is current. The one step-driven swap
		// left, so it only wins at the moment the guide is pointing at her — and matched on
		// the NPC rather than an item id. Requested from play.
		if (config.contractLeftClick() && step != null
			&& (step.getAction() == GuideAction.HAND_IN_CONTRACT
				|| step.getAction() == GuideAction.TAKE_CONTRACT))
		{
			promote("Contract", entry -> entry.getNpc() != null
				&& com.dooglemaps.state.ContractState.JANE_NPC_IDS.contains(entry.getNpc().getId()));
		}
	}

	/** Moves the named option on the matched entry to the left-click, at full priority. */
	private void promote(String wanted, java.util.function.Predicate<MenuEntry> matches)
	{
		Menu menu = client.getMenu();
		MenuEntry[] entries = menu.getMenuEntries();
		// The left-click option is the last entry. Walked from the top so that if the wanted
		// option is already there, nothing moves. The option compare ignores case because the
		// exact capitalisation of NPC options is not pinned anywhere the way the box's Fill
		// and Empty are.
		for (int i = entries.length - 1; i >= 0; i--)
		{
			MenuEntry entry = entries[i];
			if (!wanted.equalsIgnoreCase(entry.getOption()) || !matches.test(entry))
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

	/** The box holds this many kinds of seed — six slots, one stack of one kind each. */
	private static final int SEED_BOX_KINDS = 6;

	/**
	 * Which of the box's two options to put under the left click, read off the box itself.
	 *
	 * <p>Four cases, and they are the whole rule — asked for from play, replacing a step-driven
	 * version that only swapped while a "fill it" or "empty it" step was current. Those steps
	 * are gone (see {@code GuidePlan}), and with them the question of what the guide thinks you
	 * are doing; what is left is the question the box can answer on its own.
	 *
	 * <ul>
	 *   <li>Box empty → <b>Fill</b>. There is nothing to tip out.</li>
	 *   <li>Box holding something, loose seeds in the pack that would go in → <b>Fill</b>.</li>
	 *   <li>Box holding something, nothing loose that fits → <b>Empty</b>. Covers the full box,
	 *       whose six kinds refuse a seventh however much room the stacks have.</li>
	 * </ul>
	 *
	 * <p>Both options exist on the box at all times, so whichever is not chosen is still one
	 * right-click away; this only picks which is free.
	 */
	static String wantedBoxOption(SeedInventoryStore seeds)
	{
		return anyBoxedSeeds(seeds) && !boxCanTakeLooseSeeds(seeds) ? "Empty" : "Fill";
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
	 */
	private static boolean boxCanTakeLooseSeeds(SeedInventoryStore seeds)
	{
		int kindsBoxed = 0;
		for (Seed seed : Seed.values())
		{
			if (!seed.isSapling() && seeds.getCount(seed, SeedSource.SEED_BOX) > 0)
			{
				kindsBoxed++;
			}
		}

		for (Seed seed : Seed.values())
		{
			if (seed.isSapling() || seeds.getCount(seed, SeedSource.INVENTORY) <= 0)
			{
				continue;
			}
			if (seeds.getCount(seed, SeedSource.SEED_BOX) > 0 || kindsBoxed < SEED_BOX_KINDS)
			{
				return true;
			}
		}
		return false;
	}

	/** The same two ids {@code SeedCapture} watches: closed and open. Not SEEDBOX — see there. */
	private static boolean isSeedBox(int itemId)
	{
		return itemId == ItemID.SEED_BOX
			|| itemId == ItemID.SEED_BOX_OPEN;
	}
}
