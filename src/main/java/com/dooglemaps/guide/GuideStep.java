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
		return !isAtLeprechaun();
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

	static GuideStep atLeprechaun(GuideAction action, FarmPatch patch, int itemId,
		@Nullable Integer npcId, String text)
	{
		return new GuideStep(action, patch, itemId, npcId == null ? -1 : npcId, text);
	}
}
