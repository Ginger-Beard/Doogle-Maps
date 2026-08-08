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
}
