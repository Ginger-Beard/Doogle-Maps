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
// AllArgsConstructor is explicit because @Value stops generating one the moment the class declares
// a constructor of its own, and the shorter one below is exactly that.
@Value
@lombok.AllArgsConstructor
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

	/**
	 * Which store a {@link Need#WITHDRAW} item comes out of.
	 *
	 * <p>{@link Need} says <i>that</i> something has to be fetched; this says <i>where from</i>,
	 * and the two are not the same question. Everything the plugin tracks lives in the bank except
	 * seeds, which can also be in the seed vault — and there is exactly one vault, in the Farming
	 * Guild, so confusing the two is not a small error.
	 *
	 * <p>It was missing, and the guide said "From the bank:" over a list that included vault seeds.
	 * Opening the bank then satisfied the whole step and the run moved on, leaving the vault
	 * unvisited and the seeds behind.
	 */
	public enum From
	{
		BANK,
		SEED_VAULT
	}

	int itemId;
	String name;
	Category category;
	Need need;

	/** How many the run wants, or 0 where the count is not meaningful (gear, teleports). */
	int quantity;

	/** Why it is being suggested, for the tooltip. Never empty. */
	String reason;

	/** Where to fetch it. Meaningless unless {@link #need} is {@link Need#WITHDRAW}. */
	From from;

	/**
	 * Everything except a seed comes out of the bank, so most callers do not say so.
	 *
	 * <p>Last in the field order and defaulted here rather than threaded through every call site,
	 * because only {@code addSeeds} has anything to decide — a tool, a teleport or a payment is in
	 * the bank or it is nowhere.
	 */
	LoadoutItem(int itemId, String name, Category category, Need need, int quantity, String reason)
	{
		this(itemId, name, category, need, quantity, reason, From.BANK);
	}
}
