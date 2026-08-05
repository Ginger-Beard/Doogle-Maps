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

	private final String displayName;
	private final int containerId;
	/** Whether contents survive a restart. All four are held in memory regardless. */
	private final boolean persisted;
}
