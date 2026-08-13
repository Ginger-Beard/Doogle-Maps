package com.dooglemaps.state;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.InventoryID;

/**
 * Somewhere seeds can be sitting.
 *
 * <p>The client only holds a container's contents while it is open, so everything except
 * the inventory has to be remembered from the last time you looked at it. All four are
 * mirrored in memory as the game hands them over, so nothing has to ask the client for
 * them later — the panel repaints on the Swing thread, and the client refuses to be read
 * from there.
 */
@Getter
@RequiredArgsConstructor
public enum SeedSource
{
	/**
	 * Not written to disk: it changes on every item pickup, and it is always sent again on
	 * login, so persisting it would mean constant config writes for nothing.
	 */
	INVENTORY("Inventory", InventoryID.INV, false),
	BANK("Bank", InventoryID.BANK, true),
	SEED_VAULT("Seed vault", InventoryID.SEED_VAULT, true),
	SEED_BOX("Seed box", InventoryID.SEED_BOX, true);

	// The box's capacity used to be declared here, having already been moved once from the two
	// places that declared it before. It is in com.dooglemaps.data.SeedBox now, along with the
	// question this file could never answer — which items a box will actually hold. Three
	// aliases for one number is how the copies happened, so this one is gone rather than
	// forwarded.

	private final String displayName;
	private final int containerId;
	/** Whether contents survive a restart. All four are held in memory regardless. */
	private final boolean persisted;
}
