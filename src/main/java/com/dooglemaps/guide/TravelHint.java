package com.dooglemaps.guide;

import javax.annotation.Nullable;
import lombok.Value;

/**
 * What to click to get to the next stop, and where that thing is.
 *
 * <p>The travelling counterpart to {@link GuideStep}. A step says what to do at a patch and
 * lights up the patch; this says what to travel with and lights that up — in the inventory, in
 * the bank, on the portal nexus, in the jewellery box. Same idea applied to the half of a run
 * that is not standing at a patch.
 *
 * <h2>Why the destination name is carried separately from the item</h2>
 *
 * Because the two are matched in different places and one does not imply the other. The
 * <b>item</b> is what to outline in your pack, and you have to own it. The <b>destination</b> is
 * what to match against a row in the portal nexus or a jewellery box category, and you do not
 * own anything there — the nexus is the teleport. So a leg can usefully have a destination and no
 * item, and highlighting would still have somewhere to point.
 */
@Value
public class TravelHint
{
	/** Where each teleport actually is, which decides what the instruction should say. */
	public enum Where
	{
		/** On you. Click it. */
		CARRIED,

		/** In the bank, which is a detour rather than a click. */
		BANK,

		/**
		 * Known to reach there, but not owned anywhere we can see.
		 *
		 * <p>Still worth having: the portal nexus and the jewellery box are teleports in their
		 * own right, so a destination with no item in your pack is exactly the case those two
		 * exist to cover.
		 */
		UNOWNED
	}

	/** The teleport item, or -1 when nothing owned reaches this stop. */
	int itemId;

	/** What it is called, for the panel. Null when there is no item. */
	@Nullable
	String itemName;

	/** The stop being travelled to, for matching against menu entries by name. */
	String destination;

	Where where;

	public boolean hasItem()
	{
		return itemId != -1;
	}
}
