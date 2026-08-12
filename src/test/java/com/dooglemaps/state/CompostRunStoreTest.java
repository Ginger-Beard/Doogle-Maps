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

		store.toggleFill(ItemID.PINEAPPLE);
		assertEquals(ItemID.PINEAPPLE, store.getFillItem());
		assertTrue(store.hasFill());
	}

	/** Picking the picked one clears it — the toggle the icon click relies on. */
	@Test
	public void pickingTheSameFillAgainClearsIt()
	{
		store.toggleFill(ItemID.PINEAPPLE);
		store.toggleFill(ItemID.PINEAPPLE);

		assertFalse(store.hasFill());
	}

	/**
	 * Several fills queue in pick order, and the digits follow — the seed grid's rule.
	 *
	 * <p>One crop is rarely enough: fifteen un-noted items a bin means eight bins want a
	 * hundred and twenty of something. Picking pineapples then watermelons means "use
	 * pineapples, then watermelons when they run out".
	 */
	@Test
	public void fillsQueueInThePickedOrder()
	{
		store.toggleFill(ItemID.PINEAPPLE);
		store.toggleFill(ItemID.WATERMELON);
		store.toggleFill(ItemID.PAPAYA);

		assertEquals(java.util.Arrays.asList(ItemID.PINEAPPLE, ItemID.WATERMELON,
			ItemID.PAPAYA), store.getFills());
		assertEquals("the first is what the run reaches for",
			ItemID.PINEAPPLE, store.getFillItem());
		assertEquals(1, store.priorityOf(ItemID.PINEAPPLE));
		assertEquals(3, store.priorityOf(ItemID.PAPAYA));
		assertEquals("unpicked items have no place in the queue",
			0, store.priorityOf(ItemID.POTATO));
	}

	/** A lone pick shows no digit, because a queue of one has no order. */
	@Test
	public void aLoneFillWearsNoNumber()
	{
		store.toggleFill(ItemID.PINEAPPLE);

		assertTrue(store.hasFill());
		assertEquals(0, store.priorityOf(ItemID.PINEAPPLE));
	}

	/** Re-picking sends an item to the back, which is how reordering is done. */
	@Test
	public void rePickingSendsAFillToTheBack()
	{
		store.toggleFill(ItemID.PINEAPPLE);
		store.toggleFill(ItemID.WATERMELON);
		store.toggleFill(ItemID.PINEAPPLE);   // removes it
		store.toggleFill(ItemID.PINEAPPLE);   // ...and it rejoins at the end

		assertEquals(java.util.Arrays.asList(ItemID.WATERMELON, ItemID.PINEAPPLE),
			store.getFills());
	}

	/**
	 * A single stored id — what the one-pick version wrote — still reads as a queue of one.
	 *
	 * <p>The compatibility contract: an existing profile keeps the fill it had chosen rather
	 * than silently losing it to a format change.
	 */
	@Test
	public void aProfileFromTheSinglePickVersionStillLoads()
	{
		stored.put("compostBinFill", String.valueOf(ItemID.PINEAPPLE));

		assertEquals(java.util.Collections.singletonList(ItemID.PINEAPPLE), store.getFills());
	}

	/**
	 * Every fill must make supercompost for the answer to be yes.
	 *
	 * <p>Each bin is filled from one item, so an ordinary item anywhere in the queue means
	 * some bins come out ordinary — which is what the ash box needs to know.
	 */
	@Test
	public void aMixedQueueDoesNotCountAsSupercompost()
	{
		store.toggleFill(ItemID.PINEAPPLE);
		assertTrue(store.fillMakesSupercompost());

		store.toggleFill(ItemID.POTATO);
		assertFalse("one ordinary pick is enough to make it not all supercompost",
			store.fillMakesSupercompost());
	}

	/** An item no bin would take is refused, not stored. */
	@Test
	public void aFillNoBinWouldTakeIsRefused()
	{
		store.toggleFill(ItemID.RUNE_SCIMITAR);

		assertFalse("a bin does not take scimitars", store.hasFill());
	}

	/**
	 * Both tiers are storable, and the store says which one a fill will make.
	 *
	 * <p>The panel offers both lists — an account with no pineapples still has potatoes — so
	 * the store's job is only to refuse what a bin would not accept at all. Which tier results
	 * is a question, not a restriction.
	 */
	@Test
	public void eitherTierCanBePickedAndIsReportedHonestly()
	{
		store.toggleFill(ItemID.PINEAPPLE);
		assertTrue(store.hasFill());
		assertTrue("pineapples make supercompost", store.fillMakesSupercompost());

		store.toggleFill(ItemID.POTATO);
		assertTrue("an ordinary fill is a real choice, not an error", store.hasFill());
		assertFalse("...and it makes ordinary compost", store.fillMakesSupercompost());
	}

	/**
	 * A tomato is storable, because a mostly-tomato bin is fine.
	 *
	 * <p>Only an <b>entirely</b> tomato bin is wasted — the wiki's own escape is "at least one
	 * compostable item must be of a different type" — so the store keeps it and the panel warns
	 * instead of the store refusing a legitimate pick.
	 */
	@Test
	public void aTomatoIsStorableAndFlaggedRatherThanRefused()
	{
		store.toggleFill(ItemID.TOMATO);

		assertTrue(store.hasFill());
		assertTrue("the panel is what warns about it",
			com.dooglemaps.data.Compostables.isRottenTomatoTrap(store.getFillItem()));
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
		stored.put("compostBinFill", String.valueOf(ItemID.RUNE_SCIMITAR));

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

		store.toggleFill(ItemID.PINEAPPLE);
		store.setAshing(true);

		assertEquals(2, fired[0]);
	}
}
