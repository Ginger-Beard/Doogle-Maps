package com.dooglemaps.ui;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers which collapsible sections are remembered, and where that is stored.
 *
 * <p>Small enough to look not worth testing, and the thing it guards is not: a section that
 * silently forgets is a section the player closes every single session.
 */
public class PanelLayoutStoreTest
{
	private final Map<String, Object> stored = new HashMap<>();
	private ConfigManager configManager;
	private PanelLayoutStore layout;

	@Before
	public void setUp() throws Exception
	{
		configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getConfiguration(anyString(), anyString(), eq(Boolean.class)))
			.thenAnswer(i -> stored.get(i.getArgument(1)));
		// Mockito.<Object>any() rather than any(): ConfigManager has both
		// setConfiguration(String, String, String) and a generic (String, String, T), and a bare
		// any() infers the String one — which stubs an overload the store never calls, so the
		// answer never fires and everything reads back null.
		Mockito.doAnswer(i -> stored.put(i.getArgument(1), i.getArgument(2)))
			.when(configManager)
			.setConfiguration(anyString(), anyString(), Mockito.<Object>any());

		layout = construct(PanelLayoutStore.class, configManager);
	}

	/** Before anyone has touched a section, the caller's default decides. */
	@Test
	public void anUntouchedSectionUsesTheGivenDefault()
	{
		assertTrue(layout.isOpen("seeds.HERB", true));
		assertFalse(layout.isOpen("patches.HERB", false));
	}

	/** And once touched, the stored answer wins over the default in both directions. */
	@Test
	public void aStoredStateOverridesTheDefault()
	{
		layout.setOpen("seeds.HERB", false);
		assertFalse("closing something that opens by default must stick",
			layout.isOpen("seeds.HERB", true));

		layout.setOpen("patches.HERB", true);
		assertTrue("and opening something that closes by default must stick too",
			layout.isOpen("patches.HERB", false));
	}

	/**
	 * Sections are namespaced per patch type.
	 *
	 * <p>Herb seeds and tree seeds are different questions — someone mid-way through a herb grind
	 * with no interest in trees should be able to collapse one without losing the other.
	 */
	@Test
	public void oneTypeDoesNotSpeakForAnother()
	{
		layout.setOpen("seeds.HERB", false);
		assertTrue("collapsing herbs must not collapse trees", layout.isOpen("seeds.TREE", true));
	}

	/**
	 * Stored per install, not per RuneScape profile.
	 *
	 * <p>Everything else this plugin keeps is per profile because it describes that account. This
	 * describes the person: someone who wants the seed list open wants it open on their ironman
	 * too, and storing it per profile would mean arranging the panel once per account.
	 */
	@Test
	public void layoutIsNotStoredAgainstTheRunescapeProfile()
	{
		layout.setOpen("seeds.HERB", false);
		layout.isOpen("seeds.HERB", true);

		verify(configManager, never())
			.setRSProfileConfiguration(anyString(), anyString(), Mockito.<Object>any());
		verify(configManager, never())
			.getRSProfileConfiguration(anyString(), anyString(), Mockito.<Class<Boolean>>any());
	}

	@SuppressWarnings("unchecked")
	private static <T> T construct(Class<T> type, Object... args) throws Exception
	{
		Constructor<?> constructor = type.getDeclaredConstructors()[0];
		constructor.setAccessible(true);
		return (T) constructor.newInstance(args);
	}
}
