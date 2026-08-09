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

	/** What it is called — the item's name, or the spell's. Null when there is neither. */
	@Nullable
	String itemName;

	/** The stop being travelled to, for matching against menu entries by name. */
	String destination;

	Where where;

	/**
	 * The spellbook component to outline when the route travels by spell, or -1.
	 *
	 * <p>A third kind of thing to point at, alongside the item and the destination. A spell
	 * is always "carried" — it is on the player or nowhere — so {@code where} says nothing
	 * useful about it; what the overlay needs is the widget, and the magic tab stone when
	 * that widget is not on screen.
	 */
	int spellComponent;

	// Written out because Lombok stops generating the all-args constructor the moment an
	// explicit one exists, and the convenience form below needs to delegate to it.
	private TravelHint(int itemId, @Nullable String itemName, String destination, Where where,
		int spellComponent)
	{
		this.itemId = itemId;
		this.itemName = itemName;
		this.destination = destination;
		this.where = where;
		this.spellComponent = spellComponent;
	}

	TravelHint(int itemId, @Nullable String itemName, String destination, Where where)
	{
		this(itemId, itemName, destination, where, -1);
	}

	/** A hint that travels by spell: nothing in the pack to mark, everything in the book. */
	static TravelHint bySpell(String spellName, String destination, int spellComponent)
	{
		return new TravelHint(-1, spellName, destination, Where.CARRIED, spellComponent);
	}

	public boolean hasItem()
	{
		return itemId != -1;
	}

	public boolean isSpell()
	{
		return spellComponent != -1;
	}
}
