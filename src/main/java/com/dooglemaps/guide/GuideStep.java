package com.dooglemaps.guide;

import com.dooglemaps.data.FarmPatch;
import javax.annotation.Nullable;
import lombok.Value;

/**
 * One thing to do, and what to highlight while doing it.
 *
 * <p>Modelled on Quest Helper's steps, which is the vocabulary players already read: a short
 * instruction, and the game object or inventory item it refers to lit up so there is no
 * hunting for it. The difference is that a quest step is written by hand and this one is
 * derived from the patch in front of you.
 *
 * <p>A step names at most one <b>object</b> and one <b>item</b>, because that is what an action
 * in this game is — use the thing on the thing. Applying compost highlights the bucket and the
 * patch together; harvesting highlights only the patch.
 */
@Value
public class GuideStep
{
	GuideAction action;

	/** The patch this concerns. Every step has one — guided mode is per patch. */
	FarmPatch patch;

	/** Item id to light up in the inventory, or -1 when the step is a bare object click. */
	int itemId;

	/**
	 * NPC id to light up, or -1. Only the leprechaun, and only for noting and withdrawing.
	 */
	int npcId;

	/** What to tell the player, already phrased as an instruction. */
	String text;

	public boolean hasItem()
	{
		return itemId != -1;
	}

	public boolean hasNpc()
	{
		return npcId != -1;
	}

	/**
	 * Whether the patch itself should be highlighted.
	 *
	 * <p>False for the two steps that are about the leprechaun rather than the ground:
	 * lighting up a patch you are walking away from would point at the wrong thing.
	 */
	public boolean highlightsPatch()
	{
		// Paying happens at the farmer standing beside the patch, so lighting the patch as well
		// would be two targets for one click. The farmer is named on the step itself.
		return !isAtLeprechaun() && action != GuideAction.PAY_FARMER;
	}

	/**
	 * Whether this step happens <i>at</i> the leprechaun rather than at the patch.
	 *
	 * <p>The distinction matters because the action and the item are not enough to tell them
	 * apart: withdrawing compost and applying it name the same bucket, so highlighting his
	 * store whenever the step mentions compost left the slot lit after the withdrawal was
	 * done. Where the click happens is a property of the step, not of the item.
	 */
	public boolean isAtLeprechaun()
	{
		return action == GuideAction.NOTE_AT_LEPRECHAUN
			|| action == GuideAction.WITHDRAW_COMPOST
			|| action == GuideAction.WITHDRAW_TOOL
			|| action == GuideAction.RETURN_BUCKETS;
	}

	/**
	 * Whether the item should be looked for in the leprechaun's store rather than the inventory.
	 *
	 * <p>A separate question from {@link #isAtLeprechaun()}, and conflating the two cost a bug:
	 * every step at the leprechaun looked its item up in his store, so a full inventory told you
	 * to note your watermelons and then highlighted nothing, because a watermelon has no slot in
	 * his store — it is in your pack, which is the whole reason he is being visited.
	 *
	 * <p>Both halves of that instruction only work together. He is outlined so you know who to
	 * click; the item is outlined so you know what to click <i>on</i> him.
	 *
	 * <p>The right test turned out not to be which <i>direction</i> the item moves, which is what
	 * this was first written as — handing over versus taking out. It is <b>which screen is in
	 * front of you when the click happens</b>, and those are not the same question:
	 *
	 * <ul>
	 *   <li><b>Noting a crop</b> is an inventory click. You use the crop on him, and the pack is
	 *       what you are looking at.</li>
	 *   <li><b>Returning buckets</b> is not, even though it also hands something over. His store
	 *       opens over the inventory, listing his own contents, and the bucket slot in <i>that</i>
	 *       is what gets clicked. Reported from play, and it is why direction was the wrong
	 *       rule.</li>
	 *   <li><b>Withdrawing</b> anything is likewise a click in his store.</li>
	 * </ul>
	 */
	public boolean itemIsInStore()
	{
		return action == GuideAction.WITHDRAW_COMPOST
			|| action == GuideAction.WITHDRAW_TOOL
			|| action == GuideAction.RETURN_BUCKETS;
	}

	static GuideStep of(GuideAction action, FarmPatch patch, String text)
	{
		return new GuideStep(action, patch, -1, -1, text);
	}

	static GuideStep withItem(GuideAction action, FarmPatch patch, int itemId, String text)
	{
		return new GuideStep(action, patch, itemId, -1, text);
	}

	/** A step performed on a named NPC beside the patch, such as paying the farmer. */
	static GuideStep atNpc(GuideAction action, FarmPatch patch, int itemId, int npcId, String text)
	{
		return new GuideStep(action, patch, itemId, npcId, text);
	}

	static GuideStep atLeprechaun(GuideAction action, FarmPatch patch, int itemId,
		@Nullable Integer npcId, String text)
	{
		return new GuideStep(action, patch, itemId, npcId == null ? -1 : npcId, text);
	}
}
