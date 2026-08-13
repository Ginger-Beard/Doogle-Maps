package com.dooglemaps.bank;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Which of the seed vault's left-hand category rows should light up.
 *
 * <h2>Two earlier attempts failed for want of this</h2>
 *
 * {@code BankHighlightOverlay.highlightVault} records them: "the category half never worked. Two
 * attempts at finding the label failed to highlight anything, and neither could be verified from
 * outside the client." Both were looking at the wrong widget. The rows are
 * {@code SeedVault.CATEGORY_LIST} — group 631, child 8 — which was read off the widget inspector
 * and given from play.
 *
 * <h2>And no table of crop to category</h2>
 *
 * A map from each crop to its category name would be a fourth thing to keep in step with the
 * game, wrong the day a category is renamed and wrong silently. The vault already knows: its
 * item list is laid out in labelled sections, so a seed's category is the nearest header above
 * it — the same thing the player reads off the screen. That is what this pins.
 */
public class SeedVaultCategoryTest
{
	@Test
	public void aSeedIsFiledUnderTheNearestHeaderAboveIt()
	{
		Widget headers = list(
			text("Allotment", 0), text("Flowers", 100),
			text("Herbs", 200), text("Hops", 300));
		Widget items = list(
			item(5318, 10),     // an allotment seed
			item(5296, 210),    // a herb seed
			item(5307, 310));   // a hops seed

		Set<String> wanted = BankHighlightOverlay.categoriesHolding(
			headers, items, setOf(5296));

		assertEquals("only the section the wanted seed sits in",
			Collections.singleton("herbs"), wanted);
	}

	/** Several wanted seeds light several rows, and each is named once. */
	@Test
	public void everySectionHoldingAWantedSeedIsNamed()
	{
		Widget headers = list(
			text("Allotment", 0), text("Flowers", 100), text("Herbs", 200));
		Widget items = list(
			item(5318, 10), item(5319, 20),   // two allotment seeds
			item(5296, 210));                 // one herb seed

		Set<String> wanted = BankHighlightOverlay.categoriesHolding(
			headers, items, setOf(5318, 5319, 5296));

		assertEquals(new LinkedHashSet<>(Arrays.asList("allotment", "herbs")), wanted);
	}

	/**
	 * A header <b>below</b> an item does not claim it.
	 *
	 * <p>The nearest header above is the rule, and "nearest" has to mean above or it files every
	 * seed one section too far down — which would light a row the player then scrolls to and
	 * finds nothing in. Worth its own assertion because the off-by-one is invisible until you
	 * are standing at the vault.
	 */
	@Test
	public void aHeaderBelowAnItemDoesNotClaimIt()
	{
		Widget headers = list(text("Allotment", 0), text("Herbs", 200));
		Widget items = list(item(5318, 199));   // one pixel above the Herbs header

		assertEquals("199 is still under Allotment", Collections.singleton("allotment"),
			BankHighlightOverlay.categoriesHolding(headers, items, setOf(5318)));

		Widget onTheLine = list(item(5318, 200));
		assertEquals("and a seed level with a header belongs to it",
			Collections.singleton("herbs"),
			BankHighlightOverlay.categoriesHolding(headers, onTheLine, setOf(5318)));
	}

	/** An item above every header belongs to none of them rather than to the first. */
	@Test
	public void anItemAboveEveryHeaderIsNotFiledAtAll()
	{
		Widget headers = list(text("Allotment", 100));
		Widget items = list(item(5318, 10));

		assertTrue("there is no header above it, so there is no honest answer",
			BankHighlightOverlay.categoriesHolding(headers, items, setOf(5318)).isEmpty());
	}

	/**
	 * An unrecognised layout draws nothing rather than guessing.
	 *
	 * <p>This is the part the two failed attempts got wrong twice, so it is worth an assertion of
	 * its own: with no headers there is no honest answer, and an outline on the wrong row is
	 * worse than none.
	 */
	@Test
	public void noHeadersMeansNoHighlight()
	{
		assertTrue(BankHighlightOverlay.categoriesHolding(
			list(), list(item(5296, 10)), setOf(5296)).isEmpty());
		assertTrue("and nothing wanted means nothing lit",
			BankHighlightOverlay.categoriesHolding(
				list(text("Herbs", 0)), list(item(5296, 10)), Collections.emptySet()).isEmpty());
	}

	/** Colour tags come off, since the vault writes its headers in the interface's own colour. */
	@Test
	public void aTaggedHeaderStillMatches()
	{
		Widget headers = list(text("<col=ff981f>Herbs</col>", 0));
		Widget items = list(item(5296, 10));

		assertEquals(Collections.singleton("herbs"),
			BankHighlightOverlay.categoriesHolding(headers, items, setOf(5296)));
	}

	/** A section with nothing wanted in it stays dark, however many seeds it holds. */
	@Test
	public void anUnwantedSectionIsNotNamed()
	{
		Widget headers = list(text("Allotment", 0), text("Herbs", 200));
		Widget items = list(item(5318, 10), item(5319, 20), item(5296, 210));

		assertEquals(Collections.singleton("allotment"),
			BankHighlightOverlay.categoriesHolding(headers, items, setOf(5318)));
	}

	// ------------------------------------------------------------------ helpers

	private static Set<Integer> setOf(int... ids)
	{
		Set<Integer> set = new LinkedHashSet<>();
		for (int id : ids)
		{
			set.add(id);
		}
		return set;
	}

	/** A container whose dynamic children are these widgets, as the vault's lists are. */
	private static Widget list(Widget... children)
	{
		Widget widget = Mockito.mock(Widget.class);
		when(widget.getDynamicChildren()).thenReturn(children);
		return widget;
	}

	private static Widget text(String text, int relativeY)
	{
		Widget widget = leaf(relativeY);
		when(widget.getText()).thenReturn(text);
		return widget;
	}

	private static Widget item(int itemId, int relativeY)
	{
		Widget widget = leaf(relativeY);
		when(widget.getItemId()).thenReturn(itemId);
		return widget;
	}

	private static Widget leaf(int relativeY)
	{
		Widget widget = Mockito.mock(Widget.class);
		when(widget.getRelativeY()).thenReturn(relativeY);
		return widget;
	}
}
