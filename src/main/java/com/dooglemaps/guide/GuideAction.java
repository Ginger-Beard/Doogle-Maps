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
	/**
	 * Check a grown crop's health, which is what turns it into something you can pick.
	 *
	 * <p>Every tree, bush, cactus, calquat, celastrus and redwood wants this first, and until it
	 * happens the patch is still {@code GROWING} as far as the game is concerned — so nothing else
	 * about it is clickable. It is also where the experience is: a magic tree pays over 13,000 for
	 * this one click and almost nothing for the logs.
	 *
	 * <p>Its absence was the reason a finished contract could stop a run dead. The crop was grown,
	 * the almanac said "ready", and the guide had no action for it — so the patch produced no step,
	 * nothing was highlighted, and the run moved on to something else entirely.
	 */
	CHECK_HEALTH("Check health"),

	/** Pick the patch. Repeats until nothing is left on it or the inventory fills. */
	HARVEST("Harvest"),

	/**
	 * Chop a checked tree down, which is what a tree's "harvest" actually is.
	 *
	 * <p>Split out from {@link #HARVEST} because a tree is not picked and what it leaves behind is
	 * not an empty patch. Checking the health turns a grown tree into one you can chop; chopping it
	 * leaves a <b>stump</b>, and the stump has to be dug out before anything else goes in. Three
	 * clicks, three different tools, and the game gives them three different varbit values.
	 *
	 * <p>The plugin had one word for all of it. A checked tree and its stump decode identically —
	 * same crop, same {@code HARVESTABLE}, same stage — so the guide said <i>"harvest the magic"</i>
	 * at the tree, said it again at the stump, and went on saying it until the player worked out on
	 * their own that a spade was wanted. Reported from play on a yew contract that could not start
	 * because the magic tree in front of it never finished.
	 */
	CHOP("Chop it down"),

	/** Trade the crop to the leprechaun so it comes back noted and stops filling the pack. */
	NOTE_AT_LEPRECHAUN("Note with the leprechaun"),

	/** Clear a dead crop, the weeds, or a felled stump, so the patch can be planted. */
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
	RETURN_BUCKETS("Return empty buckets"),

	/**
	 * Give Guildmaster Jane the crop she asked for, and take the reward.
	 *
	 * <p>Nothing has happened until you walk back to her. Forgetting is easy — the patch looks
	 * done, the run moves on, and the seed packs sit unclaimed until you next happen to be in the
	 * guild, which for most people is the next contract.
	 */
	HAND_IN_CONTRACT("Hand in the contract"),

	/**
	 * Ask her for the next one.
	 *
	 * <p>At the <i>end</i> of the guild stop, but still while you are there: taking it before you
	 * leave is what makes it plantable on this trip rather than in three days. Which difficulty to
	 * ask for is deliberately not suggested — easy, medium and hard draw from different crop pools
	 * and choosing for someone is both wrong and outside what this plugin does.
	 */
	TAKE_CONTRACT("Take a new contract");

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
