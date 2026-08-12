package com.dooglemaps.state;

import com.dooglemaps.DoogleMapsConfig;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * The bin run's two choices, and the guard on the one that could go wrong.
 *
 * <p>The fill is validated against {@code Compostables} in both directions — storing and
 * reading — because everything downstream assumes the chosen item makes supercompost, and a
 * stale or wild id would have the run filling bins with something that makes ordinary compost,
 * or with tomatoes, which make nothing at all.
 */
public class CompostRunStoreTest
{
	private final Map<String, Object> stored = new HashMap<>();
	private CompostRunStore store;

	@Before
	public void setUp()
	{
		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		Mockito.when(configManager.getRSProfileConfiguration(
			eq(DoogleMapsConfig.GROUP), anyString(), any(Class.class)))
			.thenAnswer((InvocationOnMock i) -> stored.get(i.<String>getArgument(1)));
		Mockito.doAnswer(i ->
		{
			stored.put(i.getArgument(1), i.<Object>getArgument(2));
			return null;
		}).when(configManager).setRSProfileConfiguration(
			eq(DoogleMapsConfig.GROUP), anyString(), any());
		Mockito.doAnswer(i ->
		{
			stored.remove(i.<String>getArgument(1));
			return null;
		}).when(configManager).unsetRSProfileConfiguration(
			eq(DoogleMapsConfig.GROUP), anyString());

		store = construct(CompostRunStore.class, configManager);
	}

	@Test
	public void theFillRoundTrips()
	{
		assertFalse(store.hasFill());

		store.setFillItem(ItemID.PINEAPPLE);
		assertEquals(ItemID.PINEAPPLE, store.getFillItem());
		assertTrue(store.hasFill());
	}

	/** Picking the picked one clears it — the toggle the icon click relies on. */
	@Test
	public void pickingTheSameFillAgainClearsIt()
	{
		store.setFillItem(ItemID.PINEAPPLE);
		store.setFillItem(ItemID.PINEAPPLE);

		assertFalse(store.hasFill());
	}

	/** An item the table does not vouch for is refused, not stored. */
	@Test
	public void aNonSupercompostableFillIsRefused()
	{
		store.setFillItem(ItemID.TOMATO);

		assertFalse("tomatoes make rotten tomatoes, not compost", store.hasFill());
	}

	/**
	 * A stored id the table no longer vouches for reads back as no fill.
	 *
	 * <p>The regeneration case: an id stored under one build of the data must not be trusted
	 * by the next — everything downstream assumes the fill makes supercompost.
	 */
	@Test
	public void aStaleStoredFillReadsAsNone()
	{
		stored.put("compostBinFill", ItemID.TOMATO);

		assertFalse(store.hasFill());
		assertEquals(CompostRunStore.NO_FILL, store.getFillItem());
	}

	@Test
	public void theAshToggleRoundTrips()
	{
		assertFalse(store.isAshing());

		store.setAshing(true);
		assertTrue(store.isAshing());

		store.setAshing(false);
		assertFalse(store.isAshing());
	}

	@Test
	public void changesFireTheListeners()
	{
		int[] fired = {0};
		store.addChangeListener(() -> fired[0]++);

		store.setFillItem(ItemID.PINEAPPLE);
		store.setAshing(true);

		assertEquals(2, fired[0]);
	}
}
