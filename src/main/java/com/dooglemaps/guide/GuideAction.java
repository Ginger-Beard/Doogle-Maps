package com.dooglemaps.guide;

/**
 * The kinds of thing guided mode ever asks you to do.
 *
 * <p>Deliberately small. Every step at a stop is one of these seven, and each one names a
 * <i>single</i> click, because the whole point is that you always know the next thing to press
 * — not the next phase of a plan.
 *
 * <p>The order they appear in is the order the spec lays out (§13.7): clear the patch out
 * before putting anything back in it, and do everything at one place before travelling.
 */
public enum GuideAction
{
	/** Pick the patch. Repeats until nothing is left on it or the inventory fills. */
	HARVEST("Harvest"),

	/** Trade the crop to the leprechaun so it comes back noted and stops filling the pack. */
	NOTE_AT_LEPRECHAUN("Note with the leprechaun"),

	/** Clear a dead crop, or the weeds, so the patch can be planted. */
	CLEAR("Clear the patch"),

	/** Take compost out of the leprechaun's storage. */
	WITHDRAW_COMPOST("Withdraw compost"),

	/**
	 * Take a tool out of the leprechaun's storage.
	 *
	 * <p>Only ever raised for a tool the step in front of you cannot be done without, and only
	 * when he is actually holding one — his store is read, not assumed. A rake you do not have
	 * and he does not have is a problem for the bank leg, not for a step here.
	 */
	WITHDRAW_TOOL("Withdraw a tool"),

	/** Take seeds out of the seed box. */
	WITHDRAW_SEEDS("Empty the seed box"),

	/** Treat the patch. Before the seed, always — compost on a planted patch is wasted. */
	APPLY_COMPOST("Apply compost"),

	/** Sow. The last thing done at a patch, and what marks it serviced. */
	PLANT("Plant"),

	/**
	 * Pay the farmer to watch over the crop.
	 *
	 * <p>After planting, because there is nothing to protect until something is in the ground.
	 * Only raised for patches that can be protected and groups the player chose to protect.
	 */
	PAY_FARMER("Pay the farmer"),

	/** Hand the empty buckets back before leaving, so they stop costing slots. */
	RETURN_BUCKETS("Return empty buckets");

	private final String label;

	GuideAction(String label)
	{
		this.label = label;
	}

	public String getLabel()
	{
		return label;
	}
}
