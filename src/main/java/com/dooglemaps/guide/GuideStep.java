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
		// would be two targets for one click. The farmer is named on the step itself. Paying to
		// clear is the same shape for the same reason — one click, one target, and the gardener
		// who is about to fell the tree is that target, not the tree.
		//
		// The contract steps carry a patch only because every step does — it is what places them
		// at the right stop. The click is on Guildmaster Jane, who stands nowhere near the patch,
		// so lighting it would point at the wrong side of the guild.
		//
		// The pick-up step's target is the crops on the ground, drawn from DroppedProduce's
		// record — same one-click-one-target rule.
		//
		// Fetching the contract seed happens at the bank, which the step model cannot outline;
		// lighting the patch would point away from the click. The bank filter takes over the
		// moment the bank is open — the seed is already on the withdraw list.
		return !isAtLeprechaun() && !isAtGuildmaster()
			&& action != GuideAction.PAY_FARMER
			&& action != GuideAction.PAY_TO_CLEAR
			&& action != GuideAction.PICK_UP_DROPS
			&& action != GuideAction.FETCH_SEED;
	}

	/**
	 * Whether the step's item should be outlined in the inventory.
	 *
	 * <p>False for the four steps whose item never leaves the pack by being clicked in it: the
	 * payment for protection, the payment to clear, and both contract steps all move the item
	 * (or nothing at all, for the contract steps) through an NPC's dialogue instead — "Pay",
	 * "Yes.", or the reward conversation itself. Outlining the coins in the pack on top of that
	 * pointed at a second target for a click that only ever happens in the chatbox. Reported
	 * from play, alongside the dialogue's own title row getting the same treatment it should
	 * not have — see {@code GuideInventoryOverlay#isOptionRow}.
	 *
	 * <p>{@link GuideAction#TAKE_CONTRACT} and {@link GuideAction#HAND_IN_CONTRACT} never carry
	 * an item in the first place — {@link #hasItem()} is already false for them — so naming them
	 * here changes nothing today. They are named anyway so this method answers the same question
	 * {@link #highlightsPatch()} does for its four exemptions: one place that says which steps
	 * are dialogue-only, rather than that fact being re-derived per surface.
	 */
	public boolean highlightsItemInPack()
	{
		return action != GuideAction.PAY_FARMER
			&& action != GuideAction.PAY_TO_CLEAR
			&& action != GuideAction.TAKE_CONTRACT
			&& action != GuideAction.HAND_IN_CONTRACT;
	}

	/** Whether this step happens in front of Guildmaster Jane rather than at a patch. */
	public boolean isAtGuildmaster()
	{
		return action == GuideAction.HAND_IN_CONTRACT
			|| action == GuideAction.TAKE_CONTRACT;
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
			|| action == GuideAction.RETURN_BUCKETS
			|| action == GuideAction.DEPOSIT_COMPOST;
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
	 * this was first written as — handing over versus taking out.
	 *
	 * <ul>
	 *   <li><b>Noting a crop</b> is an ordinary inventory click. You use the crop on him, his
	 *       interface is not open, and the pack is what you are looking at.</li>
	 *   <li><b>Withdrawing</b> and <b>returning buckets</b> both happen inside his interface, so
	 *       both are looked up as slots rather than scanned for in the inventory. Which
	 *       <i>pane</i> differs; see {@link #itemIsOnYourSideOfTheStore()}.</li>
	 * </ul>
	 */
	public boolean itemIsInStore()
	{
		return action == GuideAction.WITHDRAW_COMPOST
			|| action == GuideAction.WITHDRAW_TOOL
			|| action == GuideAction.RETURN_BUCKETS
			|| action == GuideAction.DEPOSIT_COMPOST;
	}

	/**
	 * Whether the slot to click is on <b>your</b> side of the leprechaun's interface.
	 *
	 * <h2>His interface has two panes, and this took three attempts to get right</h2>
	 *
	 * Opening the tool leprechaun shows <b>both</b> at once: his own store in a pane of its own,
	 * and a second pane laid <i>over your inventory</i> listing the same categories — bucket,
	 * composts, tools, watering can — but holding <b>your</b> items. Depositing is a click on the
	 * second one.
	 *
	 * <p>The two wrong answers before this both came from not knowing that. Treating a bucket
	 * return as a store click lit up <i>his</i> bucket slot, which shows the thousand he already
	 * holds — reading as "take these" at the moment the instruction is "give him yours". Treating
	 * it as an ordinary inventory click then lit nothing at all, because the inventory is covered
	 * by his side pane and the widget behind it is hidden.
	 *
	 * <p>So the question is not store-versus-inventory. Both are slots in one interface, and what
	 * differs is whose column the item is in: you take out of his, and you put into yours.
	 */
	public boolean itemIsOnYourSideOfTheStore()
	{
		// Depositing filled compost buckets is the same click as handing back empties: your
		// column of his interface, not his. See the class note above on the two panes.
		return action == GuideAction.RETURN_BUCKETS
			|| action == GuideAction.DEPOSIT_COMPOST;
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
