package com.dooglemaps.bank;

import java.lang.reflect.Constructor;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Covers telling the panel when the bank has something new to say.
 *
 * <p>Reported from play: a protection row read <i>"Open a bank to see whether you have 24
 * coconuts"</i>, and still read it after opening one — switching to another tab and back fixed it,
 * which is the signature of a stale paint rather than a wrong answer.
 *
 * <p>This class had no listeners at all. A bank open usually repainted anyway, because
 * {@code SeedInventoryStore} fires from the same event — but only when a <b>seed</b> count changed,
 * and opening a bank to check whether you have the coconuts moves no seeds. So the one case the
 * message exists for was the one case nothing refreshed.
 */
public class BankContentsTest
{
	/** An arbitrary item id; what it is does not matter, only that the count changes. */
	private static final int COCONUT = 5974;

	@Test
	public void openingABankAsksThePanelToLookAgain() throws Exception
	{
		BankContents bank = construct();

		int[] told = {0};
		bank.addChangeListener(() -> told[0]++);

		ItemContainer container = Mockito.mock(ItemContainer.class);
		when(container.getItems()).thenReturn(new Item[]{new Item(COCONUT, 24)});

		bank.record(container);
		assertEquals("the first look is always news - \"we have not checked\" became an answer",
			1, told[0]);
		assertEquals(24, bank.getCount(COCONUT));

		// Opening it again unchanged. A bank fires this event constantly and every notification
		// rebuilds the visible tab, so an unchanged bank must stay quiet — the same bar
		// SeedInventoryStore holds itself to.
		bank.record(container);
		assertEquals("an unchanged bank is not worth a repaint", 1, told[0]);

		when(container.getItems()).thenReturn(new Item[]{new Item(COCONUT, 12)});
		bank.record(container);
		assertEquals("withdrawing half of them is", 2, told[0]);
		assertEquals(12, bank.getCount(COCONUT));
	}

	/**
	 * An empty bank is still an answer.
	 *
	 * <p>"We have not looked in your bank" and "you own none" are the two readings this class
	 * exists to tell apart, and everything drawn from it says something different for each. So the
	 * first read has to notify even when there was nothing in it to report.
	 */
	@Test
	public void anEmptyBankStillCountsAsHavingLooked() throws Exception
	{
		BankContents bank = construct();

		int[] told = {0};
		bank.addChangeListener(() -> told[0]++);

		ItemContainer container = Mockito.mock(ItemContainer.class);
		when(container.getItems()).thenReturn(new Item[0]);

		bank.record(container);

		assertEquals(1, told[0]);
		assertEquals("and it is no longer \"we have not looked\"", true, bank.hasBeenSeen());
	}

	/**
	 * The bank survives a relaunch.
	 *
	 * <h2>The bug this pins down</h2>
	 *
	 * Nothing here was persisted, so at the start of every session the payments and tools read
	 * as unknown until a bank was opened — and the protection budget being zero meant a
	 * protected seed was allocated no patches at all. The withdraw list was empty and the bank
	 * filter narrowed to a set missing exactly the things the trip was for, both springing to
	 * life on the first bank open. Reported as "doesn't work on first load".
	 */
	@Test
	public void theBankIsRememberedAcrossSessions() throws Exception
	{
		java.util.Map<String, String> stored = new java.util.HashMap<>();
		BankContents bank = construct(stored);

		ItemContainer container = Mockito.mock(ItemContainer.class);
		when(container.getItems()).thenReturn(new Item[]{new Item(COCONUT, 24)});
		bank.record(container);

		// A second instance over the same config is the next session.
		BankContents nextSession = construct(stored);
		nextSession.load();

		assertEquals("the coconuts are known before any bank is opened",
			24, nextSession.getCount(COCONUT));
		assertEquals("and the bank counts as seen, so nothing reads as unknown",
			true, nextSession.hasBeenSeen());
	}

	/** An empty bank is remembered as an answer too, not as never having looked. */
	@Test
	public void anEmptySeenBankIsRememberedAsSeen() throws Exception
	{
		java.util.Map<String, String> stored = new java.util.HashMap<>();
		BankContents bank = construct(stored);

		ItemContainer container = Mockito.mock(ItemContainer.class);
		when(container.getItems()).thenReturn(new Item[0]);
		bank.record(container);

		BankContents nextSession = construct(stored);
		nextSession.load();

		assertEquals("you own none - a different answer from \"we have not looked\"",
			true, nextSession.hasBeenSeen());
		assertEquals(0, nextSession.getCount(COCONUT));
	}

	/** A profile that never banked loads to "we have not looked", same as before. */
	@Test
	public void aProfileWithNoStoredBankLoadsAsUnseen() throws Exception
	{
		BankContents bank = construct(new java.util.HashMap<>());
		bank.load();

		assertEquals(false, bank.hasBeenSeen());
	}

	private static BankContents construct() throws Exception
	{
		return construct(new java.util.HashMap<>());
	}

	/** A store over a fake config map, the same fixture shape the store tests all use. */
	@SuppressWarnings("unchecked")
	private static BankContents construct(java.util.Map<String, String> stored) throws Exception
	{
		net.runelite.client.config.ConfigManager configManager =
			Mockito.mock(net.runelite.client.config.ConfigManager.class);
		when(configManager.getRSProfileConfiguration(
			org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
			.thenAnswer(i -> stored.get(i.getArgument(0) + "." + i.getArgument(1)));
		Mockito.doAnswer(i ->
		{
			stored.put(i.getArgument(0) + "." + i.getArgument(1),
				String.valueOf((Object) i.getArgument(2)));
			return null;
		}).when(configManager).setRSProfileConfiguration(
			org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.any());

		Constructor<BankContents> constructor =
			(Constructor<BankContents>) BankContents.class.getDeclaredConstructors()[0];
		constructor.setAccessible(true);
		return constructor.newInstance(configManager, new com.google.gson.Gson());
	}
}
