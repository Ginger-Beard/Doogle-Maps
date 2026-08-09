package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import java.util.function.IntPredicate;
import javax.annotation.Nullable;
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
 * <p>Two shapes of swap. The seed box's Fill and Empty are <b>step-driven</b>: its left-click
 * is Open (or Close), both box steps needed a right-click at the two moments a run is
 * busiest, and which of the two is wanted depends on the moment. The empty bucket's Drop is a
 * <b>standing arrangement</b>: with the "drop empty buckets" setting on, every empty bucket
 * is drop-on-sight for the whole run — no step asks for it, because there is no moment to
 * choose; see {@code GuideTracker.appendReturnBuckets}. Both swap the named item alone and
 * touch nothing else in the pack.
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
 * silently break the seed accounting that makes the steps correct in the first place.
 */
@Singleton
public class GuideMenuSwap
{
	private final Client client;
	private final GuideTracker tracker;
	private final DoogleMapsConfig config;

	@Inject
	GuideMenuSwap(Client client, GuideTracker tracker, DoogleMapsConfig config)
	{
		this.client = client;
		this.tracker = tracker;
		this.config = config;
	}

	@Subscribe
	public void onPostMenuSort(PostMenuSort event)
	{
		// Never reshuffle a menu the player is already looking at.
		if (!config.guidedMode() || client.isMenuOpen())
		{
			return;
		}

		// The standing swap: for the whole run, an empty bucket's left-click is Drop.
		if (config.dropEmptyBuckets() && tracker.getStatus().isRunning())
		{
			promote("Drop", itemId -> itemId == ItemID.BUCKET_EMPTY);
		}

		String wanted = wantedOption(tracker.getCurrentStep());
		if (wanted != null)
		{
			promote(wanted, GuideMenuSwap::isSeedBox);
		}
	}

	/** Moves the named option on the named item to the left-click, at full priority. */
	private void promote(String wanted, IntPredicate item)
	{
		Menu menu = client.getMenu();
		MenuEntry[] entries = menu.getMenuEntries();
		// The left-click option is the last entry. Walked from the top so that if the wanted
		// option is already there, nothing moves.
		for (int i = entries.length - 1; i >= 0; i--)
		{
			MenuEntry entry = entries[i];
			if (!wanted.equals(entry.getOption()) || !item.test(entry.getItemId()))
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

	/** The option the current step wants left-clickable, or null when none does. */
	@Nullable
	private String wantedOption(@Nullable GuideStep step)
	{
		if (step == null || !config.seedBoxLeftClick())
		{
			return null;
		}
		switch (step.getAction())
		{
			case FILL_SEED_BOX:
				return "Fill";
			case WITHDRAW_SEEDS:
				return "Empty";
			default:
				return null;
		}
	}

	/** Same three ids {@code SeedCapture} watches: closed, Farming Guild's, and open. */
	private static boolean isSeedBox(int itemId)
	{
		return itemId == ItemID.SEED_BOX
			|| itemId == ItemID.SEEDBOX
			|| itemId == ItemID.SEED_BOX_OPEN;
	}
}
