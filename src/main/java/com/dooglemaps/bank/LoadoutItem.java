package com.dooglemaps.bank;

import lombok.Value;

/**
 * One thing a run wants, and what to do about it.
 *
 * <p>The {@link Need} is the useful half. A list of everything a farm run touches would be
 * mostly noise — the leprechaun already holds your compost, your tools and a thousand plant
 * cures, so telling you to withdraw those is worse than saying nothing. What is worth showing
 * is the difference between what the run needs and what you already have.
 */
@Value
public class LoadoutItem
{
	/** What, if anything, the player has to do about this item. */
	public enum Need
	{
		/** In the bank and not on you. This is what the bank highlight is for. */
		WITHDRAW,

		/** Already carried or worn. Shown so it reads as checked off, not missing. */
		HAVE,

		/**
		 * On site, so there is nothing to withdraw.
		 *
		 * <p>The tool leprechaun stores 1,000 buckets of each compost, every farming tool
		 * including magic secateurs, and 1,000 plant cures. Banking any of that is wasted
		 * effort, and a loadout that demanded it would be actively misleading.
		 */
		AT_LEPRECHAUN,

		/** Wanted, but nowhere we can see. Worth saying so rather than silently omitting. */
		MISSING,

		/**
		 * Wanted, and we have not looked in the bank yet.
		 *
		 * <p>Distinct from {@link #MISSING} because the difference matters and the failure is
		 * loud: the bank is only readable while it is open, so on a fresh login everything not
		 * already carried looks absent. Reporting that as missing would tell someone their
		 * secateurs and payments had vanished, every session, before they had done anything.
		 */
		UNKNOWN
	}

	/** What the item is for, so the panel can group rather than list forty things flat. */
	public enum Category
	{
		SEED("Seeds"),
		COMPOST("Compost"),
		PAYMENT("Protection payments"),
		TOOL("Tools"),
		GEAR("Yield and experience gear"),
		TELEPORT("Teleports"),
		STORAGE("Storage");

		private final String label;

		Category(String label)
		{
			this.label = label;
		}

		public String getLabel()
		{
			return label;
		}
	}

	int itemId;
	String name;
	Category category;
	Need need;

	/** How many the run wants, or 0 where the count is not meaningful (gear, teleports). */
	int quantity;

	/** Why it is being suggested, for the tooltip. Never empty. */
	String reason;
}
