package com.dooglemaps.data;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ItemID;

/** How a patch was treated, for the compost-bucket icon on each row. */
@Getter
@RequiredArgsConstructor
public enum CompostTier
{
	NONE("Untreated", -1, 0),
	COMPOST("Compost", ItemID.BUCKET_COMPOST, 1),
	SUPERCOMPOST("Supercompost", ItemID.BUCKET_SUPERCOMPOST, 2),
	ULTRACOMPOST("Ultracompost", ItemID.BUCKET_ULTRACOMPOST, 3);

	private final String displayName;
	private final int itemID;

	/**
	 * Extra harvest lives this treatment buys, on the crops that work that way.
	 *
	 * <p>Straight from Mod Ash: three lives as standard, four with compost, five with super,
	 * six with ultra. It is the larger half of what compost is for - the disease reduction
	 * gets the attention, but the yield is where the value is.
	 */
	private final int livesBonus;

	/**
	 * The next tier down, or {@link #NONE} when there is nothing below.
	 *
	 * <p>The enum's own order is the ranking — ultra, super, compost, nothing — so walking down
	 * it is walking down in strength. Written as a method rather than left to
	 * {@code ordinal() - 1} arithmetic at three call sites, which is the sort of thing that
	 * survives right up until somebody inserts a tier.
	 */
	public CompostTier weaker()
	{
		return this == NONE || ordinal() == 0 ? NONE : values()[ordinal() - 1];
	}

	/**
	 * The best treatment at or below this one that the player can actually apply now.
	 *
	 * <h2>Why a run should downgrade rather than skip</h2>
	 *
	 * A patch treated with supercompost is worth far more than an untreated one, and running
	 * out of ultracompost mid-run is ordinary — it is the tier people hoard and the one a bin
	 * cannot make without volcanic ash. Standing at a herb patch with a stack of supercompost
	 * and being told nothing is the guide refusing to help with the trip actually in front of
	 * you.
	 *
	 * <p>Only downward. Treating with something <i>stronger</i> than was asked for spends a
	 * scarcer bucket than the player chose to spend, which is their decision and not this
	 * method's.
	 *
	 * @param available answers whether a tier is in reach — carried, or in the leprechaun's
	 *                  store, which for compost is the same thing at a patch
	 */
	public CompostTier bestAvailableAtOrBelow(java.util.function.Predicate<CompostTier> available)
	{
		for (CompostTier tier = this; tier != NONE; tier = tier.weaker())
		{
			if (available.test(tier))
			{
				return tier;
			}
		}
		return NONE;
	}
}
