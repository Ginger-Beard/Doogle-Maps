package com.dooglemaps.bank;

import java.util.ArrayList;
import java.util.List;

/**
 * The bank loadout as lines of text, for whatever is drawing them.
 *
 * <h2>Why this is not in the panel that used to own it</h2>
 *
 * These lines lived inside {@code RunPanel}, built into one blob of text for a label above the
 * projection table. That label is gone — following a run means watching the game, not the sidebar
 * — and the wording moved to the on-screen panel with it.
 *
 * <p>It did not move <i>into</i> the on-screen panel, though, and that is the point of this class.
 * The plan is a side-pane checklist in the quest-helper style, boxes that tick off as you go,
 * reusing exactly these steps in exactly this wording. Two renderers, one source: the moment the
 * text is written inside an overlay's render method it can only ever be an overlay.
 *
 * <p>So this returns strings and knows nothing about who draws them.
 */
public final class LoadoutSummary
{
	/** Names listed in full before the line turns into a count. */
	private static final int NAMES_SHOWN = 4;

	private LoadoutSummary()
	{
	}

	/**
	 * What is still to be collected, as one line per place it has to come out of.
	 *
	 * <p>Only the count and the first few names. The container itself highlights them, which is
	 * where you actually need to see them; this exists so you know which container to open, and so
	 * the things the plugin cannot highlight — something you own none of — still get said.
	 *
	 * <h2>The bank and the vault are separate jobs</h2>
	 *
	 * This used to put everything under <i>"From the bank:"</i>, which is wrong twice over for
	 * anyone keeping seeds in the vault: it names the wrong container, and it reads as one task, so
	 * opening the bank looks like the whole of it. There is exactly one seed vault and it is in the
	 * Farming Guild, so "the bank" is not a harmless approximation of it.
	 *
	 * <p>They are listed in whichever order the run needs them and can be done in either — nothing
	 * here or in the planner imposes a sequence, because both are in the same room whenever both
	 * apply.
	 *
	 * @return the lines, in order, or empty when there is nothing worth saying
	 */
	public static List<String> forItems(List<LoadoutItem> items)
	{
		List<String> lines = new ArrayList<>();
		if (items == null || items.isEmpty())
		{
			return lines;
		}

		List<String> fromBank = new ArrayList<>();
		List<String> fromVault = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		boolean anyUnknown = false;
		for (LoadoutItem item : items)
		{
			if (item.getNeed() == LoadoutItem.Need.WITHDRAW)
			{
				(item.getFrom() == LoadoutItem.From.SEED_VAULT ? fromVault : fromBank)
					.add(item.getName().toLowerCase());
			}
			else if (item.getNeed() == LoadoutItem.Need.MISSING)
			{
				missing.add(item.getName().toLowerCase());
			}
			else if (item.getNeed() == LoadoutItem.Need.UNKNOWN)
			{
				anyUnknown = true;
			}
		}

		boolean toWithdrawEmpty = fromBank.isEmpty() && fromVault.isEmpty();
		if (!fromBank.isEmpty())
		{
			lines.add("From the bank: " + summarise(fromBank) + ".");
		}
		if (!fromVault.isEmpty())
		{
			lines.add("From the seed vault: " + summarise(fromVault) + ".");
		}
		if (!missing.isEmpty())
		{
			// Named as skipped rather than merely absent. "Not found anywhere" left it to the
			// player to work out what the run would do about it, and the answer — carry on without
			// it — is the part worth stating: otherwise the only way to discover a patch was going
			// unplanted was to arrive at it.
			lines.add("Skipping " + summarise(missing) + " - you have none.");
		}
		// Nothing is said for the not-yet-read case on its own. A bank is only readable while it
		// is open, so before you have opened one there is nothing to report — and a line saying
		// the plugin has no information yet is what an empty section already says. The reason the
		// case is handled at all still stands: listing unread items as *missing* would read as
		// "your secateurs are gone", and that is still avoided.
		if (anyUnknown && !toWithdrawEmpty)
		{
			lines.add("Some of your bank has not been read yet.");
		}
		return lines;
	}

	/** A few names and a count, rather than a list that outgrows whatever is drawing it. */
	private static String summarise(List<String> names)
	{
		if (names.size() <= NAMES_SHOWN)
		{
			return String.join(", ", names);
		}
		return String.join(", ", names.subList(0, NAMES_SHOWN))
			+ " and " + (names.size() - NAMES_SHOWN) + " more";
	}
}
