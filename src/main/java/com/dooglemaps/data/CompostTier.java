package com.dooglemaps.data;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.ItemID;

/** How a patch was treated, for the compost-bucket icon on each row. */
@Getter
@RequiredArgsConstructor
public enum CompostTier
{
	NONE("Untreated", -1),
	COMPOST("Compost", ItemID.COMPOST),
	SUPERCOMPOST("Supercompost", ItemID.SUPERCOMPOST),
	ULTRACOMPOST("Ultracompost", ItemID.ULTRACOMPOST);

	private final String displayName;
	private final int itemID;
}
