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
	 * Cure a diseased crop before it dies.
	 *
	 * <p>The state with a clock on it: a diseased crop stops growing and, left alone, the next
	 * cycle can kill it. The run used to route to it — {@code DISEASED} is actionable — and
	 * then have no word for it, so the one patch on the trip that was actually urgent was the
	 * one silently skipped. The tool splits by family: secateurs prune trees, fruit trees,
	 * spirit trees, bushes and calquats; a plant cure does everything else.
	 */
	CURE("Cure"),

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

	/**
	 * Pick your overflowed crops back up off the ground.
	 *
	 * <p>A bulk harvest into a full pack drops the excess at your feet — limpwurts hand over
	 * several roots per pick and the game keeps the change on the floor. Raised once there is
	 * room again, which in practice means straight after the noting that made it; the crops
	 * despawn on a clock, so it goes in front of everything else at the stop.
	 */
	PICK_UP_DROPS("Pick up your crops"),

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

	/**
	 * Hand the empty buckets back before leaving, so they stop costing slots.
	 *
	 * <p>Only when the "drop empty buckets" setting is off. Dropping has no action of its
	 * own on purpose: it is not a step but a standing arrangement — the bucket stays
	 * highlighted with Drop as its left-click for the whole run. See {@code GuideMenuSwap}.
	 */
	RETURN_BUCKETS("Return empty buckets"),

	/**
	 * Use volcanic ash on a ready bin of supercompost, turning the whole thing to ultracompost.
	 *
	 * <p>Its place in the order is the entire reason it exists as a step: 25 ash (50 in the big
	 * bin) upgrades every compost still <b>in</b> the bin, where a filled bucket costs 2 ash on
	 * its own — so the ash has to go on before the first bucket comes out, and a step is how a
	 * guide says "now, not later". See {@code CompostBinPlan}.
	 */
	APPLY_ASH("Add volcanic ash"),

	/**
	 * Take the finished compost out of the bin, into empty buckets or the bottomless one.
	 *
	 * <p>Not {@link #HARVEST}: a bin is not picked, the click needs containers in the pack, and
	 * what it leaves behind is an empty bin to refill rather than an empty patch to plant.
	 */
	EMPTY_BIN("Empty the bin"),

	/**
	 * Hand the <b>filled</b> compost buckets to the leprechaun, into his store.
	 *
	 * <p>The other direction from {@link #WITHDRAW_COMPOST}, and the companion of
	 * {@link #RETURN_BUCKETS}: he stores a thousand of each tier, every farming area has him
	 * standing by, and compost carried onward is slots spent on something he hands back
	 * anywhere. Happens in his interface, on your side of it, like the bucket return.
	 */
	DEPOSIT_COMPOST("Store the compost"),

	/**
	 * Put the chosen produce into the bin — fifteen items, thirty in the big one.
	 *
	 * <p>Un-noted, which is the game's rule and the reason the loadout warns about pack space:
	 * a bin's fill is most of an inventory on its own.
	 */
	FILL_BIN("Fill the bin"),

	/**
	 * Close a full bin's lid, which is what starts the composting.
	 *
	 * <p>A step of its own because forgetting it is the classic bin mistake: a full open bin
	 * composts nothing, forever, and looks exactly like one that is working.
	 */
	CLOSE_BIN("Close the bin"),

	/**
	 * Fetch the contract's seed from the bank or seed vault at this stop.
	 *
	 * <p>The guild keeps both a short walk from Jane, and a contract seed sitting in one of
	 * them is the difference between planting the contract this trip and watching the run walk
	 * past its own highest priority. This existed only as a grey note — "withdraw it to plant
	 * the contract this trip" — which could not be the current instruction and could not be
	 * skipped; it sat under steps about lesser patches while the player stood beside the very
	 * bank that would resolve it. Asked for from play.
	 */
	FETCH_SEED("Withdraw the seed"),

	/**
	 * Give Guildmaster Jane the crop she asked for, and take the reward.
	 *
	 * <p>Nothing has happened until you walk back to her. Forgetting is easy — the patch looks
	 * done, the run moves on, and the seed packs sit unclaimed until you next happen to be in the
	 * guild, which for most people is the next contract.
	 */
	/**
	 * A tree seed that has to spend time in a plant pot before it can go in the ground.
	 *
	 * <p>Not something the player can do at the patch: a plant pot of soil is a bank errand, and
	 * the sapling then needs watering. So this step is a <b>diagnosis</b> rather than a click —
	 * it says why the patch in front of you is not going to be planted this trip.
	 */
	POT_SEED("Pot the seed first"),

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
