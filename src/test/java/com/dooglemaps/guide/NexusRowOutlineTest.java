package com.dooglemaps.guide;

import java.awt.Rectangle;
import net.runelite.api.FontTypeFace;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetTextAlignment;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

/**
 * The outline round a destination row hugs its words rather than the widget.
 *
 * <p>Reported from play: a nexus row's widget is laid out as wide as the list, so outlining
 * its bounds drew a box sailing far past "Weiss" into empty interface. The text is the thing
 * the player is hunting for, so the box ends just after the last letter — measured with the
 * row's own game font, placed by the widget's own alignment, and left alone whenever there is
 * nothing trustworthy to measure with.
 */
public class NexusRowOutlineTest
{
	private static final int PADDING = 3;

	@Test
	public void aLeftAlignedRowEndsJustAfterItsText()
	{
		Widget row = row("Weiss", 80, new Rectangle(10, 20, 200, 12), WidgetTextAlignment.LEFT);

		assertEquals(new Rectangle(10, 20, 80 + PADDING * 2, 12),
			GuideInventoryOverlay.textBounds(row));
	}

	@Test
	public void aCentredRowKeepsItsTextCentred()
	{
		Widget row = row("Weiss", 80, new Rectangle(0, 0, 200, 12), WidgetTextAlignment.CENTER);

		Rectangle bounds = GuideInventoryOverlay.textBounds(row);
		assertEquals("centred text keeps its centre", (200 - 86) / 2, bounds.x);
		assertEquals(86, bounds.width);
	}

	/** Colour tags are markup, not letters, and must not widen the measurement. */
	@Test
	public void colourTagsAreNotMeasured()
	{
		// Any string measures wide except the stripped text - so the assertion below only
		// holds if the tags were removed before measuring.
		Widget row = row("<col=ff9040>Weiss</col>", 400,
			new Rectangle(0, 0, 200, 12), WidgetTextAlignment.LEFT);
		FontTypeFace font = row.getFont();
		when(font.getTextWidth("Weiss")).thenReturn(80);

		assertEquals(86, GuideInventoryOverlay.textBounds(row).width);
	}

	/** Text as wide as the row means clamping would cut letters; the full bounds stand. */
	@Test
	public void textWiderThanTheRowKeepsTheFullBounds()
	{
		Widget row = row("An extremely long destination", 400,
			new Rectangle(0, 0, 200, 12), WidgetTextAlignment.LEFT);

		assertEquals(new Rectangle(0, 0, 200, 12), GuideInventoryOverlay.textBounds(row));
	}

	/** No font to measure with means no opinion; the full bounds stand. */
	@Test
	public void aMissingFontKeepsTheFullBounds()
	{
		Widget row = Mockito.mock(Widget.class);
		when(row.getBounds()).thenReturn(new Rectangle(0, 0, 200, 12));
		when(row.getText()).thenReturn("Weiss");
		when(row.getFont()).thenReturn(null);

		assertEquals(new Rectangle(0, 0, 200, 12), GuideInventoryOverlay.textBounds(row));
	}

	// ------------------------------------------------------------------- helpers

	private static Widget row(String text, int textWidth, Rectangle bounds, int alignment)
	{
		FontTypeFace font = Mockito.mock(FontTypeFace.class);
		when(font.getTextWidth(Mockito.anyString())).thenReturn(textWidth);

		Widget row = Mockito.mock(Widget.class);
		when(row.getBounds()).thenReturn(bounds);
		when(row.getText()).thenReturn(text);
		when(row.getFont()).thenReturn(font);
		when(row.getXTextAlignment()).thenReturn(alignment);
		return row;
	}
}
