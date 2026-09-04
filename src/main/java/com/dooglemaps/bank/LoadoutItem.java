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
// No AllArgsConstructor: @Value stops generating one the moment the class declares a
// constructor of its own, and this class declares four - the 9-arg one below, written by hand
// so it can carry seedsToPot, and three narrower ones that delegate to it for callers with
// nothing to say about a field added later.
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

		/**
		 * Stowed in a boat's cargo hold, so there is nothing to fetch from a bank.
		 *
		 * <p>{@link #AT_LEPRECHAUN}'s counterpart for the one pair of items that live at sea.
		 * A hold stores diving gear taking no space at all and it is then reachable from every
		 * boat you own, so a suit in a hold is as good as carried for a trip that sails — and
		 * reporting it as missing, which is what looking only in the bank did, sent players
		 * hunting for a helmet that was already where it needed to be.
		 */
		ON_BOAT,

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

		/**
		 * Produce going into a compost bin, as opposed to compost coming out of one.
		 *
		 * <p>Apart from {@link #COMPOST} because the two belong in different halves of a bank.
		 * A bucket of ultracompost is gear — it comes from the leprechaun and sits with the
		 * tools — while fifteen pineapples are what the trip is <i>for</i>, in the same sense a
		 * seed is: bulk produce you go and get, in a quantity, that the stop achieves nothing
		 * without. Filed under {@code COMPOST} they were laid out in the gear block, which is
		 * where the reported "compostable items in the gear section" came from.
		 */
		BIN_FILL("Compost bin fill"),

		COMPOST("Compost"),
		PAYMENT("Protection payments"),

		/**
		 * Coins a gardener will take to fell a grown tree, so the player never swings an axe.
		 *
		 * <p>Deliberately its own category rather than folded into {@link #PAYMENT}: a
		 * protection payment guards a crop that is still growing, while this buys a standing,
		 * checked one out of being chopped — different moments in a patch's life, and a
		 * tooltip that mixed the two reasons would read as one payment covering both.
		 */
		CLEARING("Clearing fees"),

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

	/**
	 * How many are still to come out of the bank, as opposed to how many the run wants.
	 *
	 * <p>The difference is what makes the bank's number worth watching. {@link #quantity} is
	 * fixed for the whole run — thirty cactus spines is thirty whatever you are holding — so a
	 * count drawn from it sits at thirty until you have them all and then vanishes. This one is
	 * what is <b>missing from the pack</b>, so it falls as you withdraw and rises again if you
	 * put something back, because {@code CarriedItems} is watching both.
	 *
	 * <p>Zero where counting does not apply: an axe or a teleport is one thing you either have
	 * or do not, and "1" beside it says nothing the highlight has not already said.
	 */
	int outstanding;

	/** Why it is being suggested, for the tooltip. Never empty. */
	String reason;

	/**
	 * How many to show beside this item, or 0 when the number is not an instruction.
	 *
	 * <p>The one rule for every surface that prints a count — the cyan number on the bank slot
	 * and the {@code x30} on the withdraw list — so the two can never disagree.
	 *
	 * <p>Every withdrawal carries one. The counted rows ({@link #quantity} set) show what is
	 * still {@link #outstanding}; a unit row — an axe, a can, a seed box — shows {@code 1}
	 * until it is on you, at which point its need stops being {@code WITHDRAW} and the number
	 * goes with it. Unit rows used to show nothing, on the reasoning that the highlight
	 * already said "take this" — but with the bank filtered there <i>is</i> no highlight, and
	 * the number is the only mark the slot gets. Reported from play as a felling axe sitting
	 * markless in a filtered bank.
	 *
	 * <p>Compost is the one exception: its true bucket count is not computed here yet, and a
	 * {@code 1} over the forty buckets a run actually wants would be a wrong number rather
	 * than a missing one.
	 *
	 * <p>Gated on {@link Need#WITHDRAW} rather than on the count alone: a {@code MISSING} or
	 * {@code UNKNOWN} item still carries the arithmetic in {@link #outstanding}, but there is
	 * nothing in the bank to put its number on, and printing one would read as "this is here,
	 * take thirty" about a thing the run just said it cannot find.
	 */
	public int getWithdrawCount()
	{
		if (need != Need.WITHDRAW)
		{
			return 0;
		}
		if (quantity > 0)
		{
			return Math.max(0, outstanding);
		}
		return category == Category.COMPOST ? 0 : 1;
	}

	/** Where to fetch it. Meaningless unless {@link #need} is {@link Need#WITHDRAW}. */
	From from;

	/**
	 * How many of <b>this row's</b> count are being fetched as seeds rather than as saplings,
	 * and so still want a plant pot. Zero when the saplings cover it.
	 *
	 * <p>Per row, not per crop: a bank+vault split pair is two rows, and each says only what it
	 * is itself asking you to pick up. The crop's total shortfall is the sum of the pair, which
	 * is what {@code addSeeds} counts pots against. Carrying the total on both rows instead made
	 * the bank slot and the vault slot each claim the whole of it.
	 */
	int seedsToPot;

	/** The full constructor. Every narrower one below delegates here. */
	LoadoutItem(int itemId, String name, Category category, Need need, int quantity,
		int outstanding, String reason, From from, int seedsToPot)
	{
		this.itemId = itemId;
		this.name = name;
		this.category = category;
		this.need = need;
		this.quantity = quantity;
		this.outstanding = outstanding;
		this.reason = reason;
		this.from = from;
		this.seedsToPot = seedsToPot;
	}

	/**
	 * Everything except a seed comes out of the bank, so most callers do not say so.
	 *
	 * <p>Last in the field order and defaulted here rather than threaded through every call site,
	 * because only {@code addSeeds} has anything to decide — a tool, a teleport or a payment is in
	 * the bank or it is nowhere.
	 */
	LoadoutItem(int itemId, String name, Category category, Need need, int quantity, String reason)
	{
		this(itemId, name, category, need, quantity, 0, reason, From.BANK, 0);
	}

	/**
	 * The uncounted form, for the ten-odd things whose answer is "bring it" rather than a number.
	 *
	 * <p>Kept so adding {@link #outstanding} did not mean touching every call site to write a
	 * zero, which would have put the noise where the decision is not.
	 */
	LoadoutItem(int itemId, String name, Category category, Need need, int quantity, String reason,
		From from)
	{
		this(itemId, name, category, need, quantity, 0, reason, from, 0);
	}

	/**
	 * The 8-arg shape every caller but {@code addSeeds} still uses, with {@link #seedsToPot} at
	 * zero — a tool, a payment or a bin fill has no seed form to weigh potting against.
	 */
	LoadoutItem(int itemId, String name, Category category, Need need, int quantity,
		int outstanding, String reason, From from)
	{
		this(itemId, name, category, need, quantity, outstanding, reason, from, 0);
	}
}
