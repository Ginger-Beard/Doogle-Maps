package com.dooglemaps.guide;

import java.lang.reflect.Constructor;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Knowing what is in the pack before anything in it moves.
 *
 * <h2>The read that was missing</h2>
 *
 * Everything here arrived by {@code ItemContainerChanged}, which covers every moment except the
 * first. The client sends a container when it <i>changes</i>, so a plugin switched on mid-session
 * was never told what the player was holding, and a profile change handed over a different
 * account's pack without anything having moved in it.
 *
 * <p>The consequence was loud rather than subtle: every teleport, cloak and ring already in the
 * inventory read as something the player did not own and went on the bank withdraw list. Reported
 * from play as being told to withdraw house tablets with a stack of them in the pack — and it
 * cleared the moment any item was moved, which is what made it look like a display glitch instead
 * of a missing read.
 *
 * <p>{@code SeedInventoryStore.relearnFromClient} had solved exactly this for seeds. These pin the
 * same guarantee for the pack and the worn items.
 */
public class CarriedItemsTest
{
	@Test
	public void anUntouchedInventoryIsStillKnownAbout() throws Exception
	{
		// Built before the stubbing that returns it: containerOf stubs a mock of its own, and
		// Mockito cannot have a second when() open inside the argument list of the first.
		ItemContainer pack = containerOf(8013, 196);
		Client client = Mockito.mock(Client.class);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(pack);

		CarriedItems carried = construct(CarriedItems.class, client);
		assertFalse("nothing has fired an event, so nothing is known yet", carried.has(8013));

		carried.relearnFromClient();

		assertTrue("the tablets were there the whole time", carried.has(8013));
		assertEquals(196, carried.getCount(8013));
		assertEquals("one stack, one slot", 27, carried.getFreeSlots());
	}

	/**
	 * Worn items too, which is the half that would otherwise stay silent longest.
	 *
	 * <p>Equipment changes even less often than the pack — a cloak put on before the plugin was
	 * enabled may not move again for hours — so priming matters more here, not less.
	 */
	@Test
	public void wornItemsArePrimedAsWell() throws Exception
	{
		ItemContainer worn = containerOf(13123, 1);
		Client client = Mockito.mock(Client.class);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(worn);

		CarriedItems carried = construct(CarriedItems.class, client);
		carried.relearnFromClient();

		assertTrue("an Ardougne cloak on your back is not something to fetch from the bank",
			carried.has(13123));
		assertEquals("equipment is worn, not carried, so it costs no slots",
			CarriedItems.INVENTORY_SIZE, carried.getFreeSlots());
	}

	/** Logging out with no containers to read empties it rather than throwing. */
	@Test
	public void nothingToReadIsNotAFailure() throws Exception
	{
		Client client = Mockito.mock(Client.class);
		when(client.getItemContainer(Mockito.anyInt())).thenReturn(null);

		CarriedItems carried = construct(CarriedItems.class, client);
		carried.relearnFromClient();

		assertFalse(carried.has(8013));
		assertEquals(CarriedItems.INVENTORY_SIZE, carried.getFreeSlots());
	}

	private static ItemContainer containerOf(int... idThenQuantity)
	{
		Item[] items = new Item[idThenQuantity.length / 2];
		for (int i = 0; i < items.length; i++)
		{
			items[i] = new Item(idThenQuantity[i * 2], idThenQuantity[i * 2 + 1]);
		}
		ItemContainer container = Mockito.mock(ItemContainer.class);
		when(container.getItems()).thenReturn(items);
		return container;
	}

	@SuppressWarnings("unchecked")
	private static <T> T construct(Class<T> type, Object... args) throws Exception
	{
		for (Constructor<?> candidate : type.getDeclaredConstructors())
		{
			if (candidate.getParameterCount() == args.length)
			{
				candidate.setAccessible(true);
				return (T) candidate.newInstance(args);
			}
		}
		throw new IllegalStateException("no constructor of arity " + args.length + " on " + type);
	}
}
