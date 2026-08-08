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
	/** Names listed in full before the missing line turns into a count. */
	private static final int NAMES_SHOWN = 4;

	/** Withdraw rows shown before the rest fold into "+ N more". */
	private static final int ROWS_SHOWN = 10;

	private LoadoutSummary()
	{
	}

	/**
	 * What is still to be collected: a heading per container, then one row per item.
	 *
	 * <h2>One row per item, with its count</h2>
	 *
	 * This used to fold everything into prose — <i>"From the bank: yew sapling, cactus
	 * spine."</i> — which named the errand and withheld the only part of it anyone gets wrong:
	 * <b>how many</b>. Six ranarr patches is six seeds, but four magic trees is a hundred
	 * coconuts, and the panel was making the player do that arithmetic at the bank. Now each
	 * item is its own row with its count, and the count is {@link LoadoutItem#getWithdrawCount}
	 * — what is still missing from the pack, not the run's fixed total — so it falls as you
	 * withdraw and rises if you put something back, in step with the number on the bank slot.
	 *
	 * <h2>The bank and the vault are separate jobs</h2>
	 *
	 * Everything used to sit under <i>"From the bank:"</i>, which is wrong twice over for anyone
	 * keeping seeds in the vault: it names the wrong container, and it reads as one task, so
	 * opening the bank looks like the whole of it. There is exactly one seed vault and it is in
	 * the Farming Guild, so "the bank" is not a harmless approximation of it. The two sections
	 * can be done in either order — both are in the same room whenever both apply.
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
			// Teleports are marked in the bank and nothing more. They are a convenience the
			// player may well mean to walk past, there can be a dozen of them, and a list that
			// long buries the seeds and payments the trip actually cannot start without.
			if (item.getCategory() == LoadoutItem.Category.TELEPORT)
			{
				continue;
			}
			if (item.getNeed() == LoadoutItem.Need.WITHDRAW)
			{
				(item.getFrom() == LoadoutItem.From.SEED_VAULT ? fromVault : fromBank)
					.add(row(item));
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
		appendSection(lines, "From the bank:", fromBank);
		appendSection(lines, "From the seed vault:", fromVault);
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

	/** One item as a row: its own name, and the live count where the count is an instruction. */
	private static String row(LoadoutItem item)
	{
		int count = item.getWithdrawCount();
		return count > 0
			? "- " + item.getName() + " x" + count
			: "- " + item.getName();
	}

	/** A heading and its rows, folding the tail so the list cannot outgrow the panel. */
	private static void appendSection(List<String> lines, String heading, List<String> rows)
	{
		if (rows.isEmpty())
		{
			return;
		}
		lines.add(heading);
		if (rows.size() <= ROWS_SHOWN)
		{
			lines.addAll(rows);
			return;
		}
		lines.addAll(rows.subList(0, ROWS_SHOWN));
		lines.add("+ " + (rows.size() - ROWS_SHOWN) + " more");
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
