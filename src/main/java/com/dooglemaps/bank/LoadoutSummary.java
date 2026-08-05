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
	 * What is still to be pulled out of the bank, as at most three lines.
	 *
	 * <p>Only the count and the first few names. The bank itself highlights them, which is where
	 * you actually need to see them; this exists so you know to open a bank at all, and so the
	 * things the plugin cannot highlight — something you own none of — still get said.
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

		List<String> toWithdraw = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		boolean anyUnknown = false;
		for (LoadoutItem item : items)
		{
			if (item.getNeed() == LoadoutItem.Need.WITHDRAW)
			{
				toWithdraw.add(item.getName().toLowerCase());
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

		if (!toWithdraw.isEmpty())
		{
			lines.add("From the bank: " + summarise(toWithdraw) + ".");
		}
		if (!missing.isEmpty())
		{
			// Worth saying out loud: an item you own none of cannot be highlighted in the bank,
			// so silence here would read as "nothing else needed".
			lines.add("Not found anywhere: " + summarise(missing) + ".");
		}
		// Nothing is said for the not-yet-read case on its own. A bank is only readable while it
		// is open, so before you have opened one there is nothing to report — and a line saying
		// the plugin has no information yet is what an empty section already says. The reason the
		// case is handled at all still stands: listing unread items as *missing* would read as
		// "your secateurs are gone", and that is still avoided.
		if (anyUnknown && !toWithdraw.isEmpty())
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
