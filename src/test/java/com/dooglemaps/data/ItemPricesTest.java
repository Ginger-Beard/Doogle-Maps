package com.dooglemaps.data;

import java.lang.reflect.Constructor;
import net.runelite.client.game.ItemManager;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the price cache that keeps {@code ItemManager} off the Swing thread.
 *
 * <p>The reason it exists is worth restating: {@code getItemPrice} resolves the canonical item
 * through {@code getItemComposition}, which asserts it is on the client thread. Calling it from
 * a panel repaint threw an {@code AssertionError} that unwound the whole refresh, so the Stats
 * tab read "nothing here yet" over a full history rather than losing one figure.
 *
 * <p>Unit tests could not have caught that — a mocked {@code ItemManager} asserts nothing — so
 * what is checked here is the contract that replaced it: everything the plugin can price is read
 * in one pass, and an unread price reads as absent rather than as free.
 */
public class ItemPricesTest
{
	@Test
	public void everythingThePluginCanPriceIsReadInOnePass() throws Exception
	{
		ItemManager itemManager = Mockito.mock(ItemManager.class);
		when(itemManager.getItemPrice(Mockito.anyInt())).thenReturn(1234);

		ItemPrices prices = newCache();
		prices.record(itemManager);

		assertTrue(prices.isLoaded());
		assertEquals("a crop", 1234, prices.get(Produce.RANARR.getItemID()));
		assertEquals("a seed", 1234, prices.get(Seed.RANARR.getPlantedItemID()));
		assertEquals("a sapling, not the seed behind it", 1234,
			prices.get(Seed.OAK.getPlantedItemID()));
		assertEquals("a compost bucket", 1234, prices.get(CompostTier.ULTRACOMPOST.getItemID()));
	}

	/**
	 * Untreated has no item, so nothing is asked about it.
	 *
	 * <p>Its id is -1. Asking the client about a negative id is the kind of thing that works
	 * until it does not, and there is no price to want either way.
	 */
	@Test
	public void theUntreatedTierIsNotLookedUp() throws Exception
	{
		ItemManager itemManager = Mockito.mock(ItemManager.class);
		when(itemManager.getItemPrice(Mockito.anyInt())).thenReturn(5);

		newCache().record(itemManager);

		verify(itemManager, never()).getItemPrice(CompostTier.NONE.getItemID());
	}

	/**
	 * An unread price is zero, and zero means "we do not know".
	 *
	 * <p>Callers show that as a gap rather than as an item worth nothing, which is why the
	 * coin lines disappear before the cache has loaded instead of claiming a total of 0.
	 */
	@Test
	public void anUnreadPriceIsAbsentRatherThanFree() throws Exception
	{
		ItemPrices prices = newCache();

		assertTrue("nothing read yet", !prices.isLoaded());
		assertEquals(0, prices.get(Produce.RANARR.getItemID()));
		assertEquals("a nonsense id is not a lookup either", 0, prices.get(-1));
	}

	/** An item the game has no price for is not cached as worth nothing. */
	@Test
	public void anUnpricedItemIsNotStoredAsZero() throws Exception
	{
		ItemManager itemManager = Mockito.mock(ItemManager.class);
		when(itemManager.getItemPrice(Mockito.anyInt())).thenReturn(0);

		ItemPrices prices = newCache();
		prices.record(itemManager);

		assertTrue("nothing had a price, so nothing was cached", !prices.isLoaded());
	}

	@Test
	public void resetEmptiesTheCache() throws Exception
	{
		ItemManager itemManager = Mockito.mock(ItemManager.class);
		when(itemManager.getItemPrice(Mockito.anyInt())).thenReturn(99);

		ItemPrices prices = newCache();
		prices.record(itemManager);
		assertTrue(prices.isLoaded());

		prices.reset();
		assertTrue("a profile change must not carry prices over", !prices.isLoaded());
	}

	private static ItemPrices newCache() throws Exception
	{
		Constructor<ItemPrices> constructor = ItemPrices.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
	}
}
