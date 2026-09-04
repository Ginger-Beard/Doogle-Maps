package com.dooglemaps.guide;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * The pay-to-clear conversation is a denylist, not an allowlist — see the class note on {@code
 * GuideInventoryOverlay#highlightClearOptions}. This pins it against the wiki-quoted lines
 * themselves rather than against a guess at their shape.
 *
 * <h2>Why a denylist, spelled out in the fixture</h2>
 *
 * <i>"Yes, you're right - I'll do it myself."</i> is the wiki's own wording for the talk-to
 * path's refusal, and it starts with the very word — "Yes" — that the payment dialogue's
 * acceptance does ({@code highlightPayOptions}' allowlist). An allowlist built the same way for
 * this conversation would light up a decline; this test is what would catch that regression.
 *
 * <p>No Chatmenu-widget test existed anywhere in this codebase to copy, so this builds a small
 * Mockito fixture: a mocked {@code Chatmenu.OPTIONS} widget whose dynamic children are mocked
 * rows, each carrying one option's text and a null font (which {@code textBounds} falls back to
 * the row's own bounds for). The outlined rows are recovered by intercepting {@code
 * Graphics2D.draw(Shape)}.
 */
public class ClearanceDialogueHighlightTest
{
	private GuideInventoryOverlay overlay;
	private Client client;
	private Graphics2D graphics;
	private List<Rectangle> drawn;

	@Before
	public void setUp()
	{
		client = Mockito.mock(Client.class);
		GuideTracker tracker = Mockito.mock(GuideTracker.class);
		com.dooglemaps.DoogleMapsConfig config = Mockito.mock(com.dooglemaps.DoogleMapsConfig.class);
		ItemManager itemManager = Mockito.mock(ItemManager.class);
		CarriedItems carried = Mockito.mock(CarriedItems.class);
		com.dooglemaps.state.SeedInventoryStore seeds =
			Mockito.mock(com.dooglemaps.state.SeedInventoryStore.class);

		overlay = construct(GuideInventoryOverlay.class, client, tracker, config, itemManager,
			carried, seeds);

		drawn = new ArrayList<>();
		graphics = Mockito.mock(Graphics2D.class);
		Mockito.doAnswer(invocation ->
		{
			Object shape = invocation.getArgument(0);
			if (shape instanceof Rectangle)
			{
				drawn.add((Rectangle) shape);
			}
			return null;
		}).when(graphics).draw(Mockito.any(Shape.class));
	}

	/**
	 * The three acceptance-path lines are outlined; the three declines are not — matched by
	 * exact wiki wording, not by a guess at a shorter prefix.
	 */
	@Test
	public void outlinesTheAcceptancesAndSkipsTheDeclines()
	{
		String[] accepted = {
			"Yes.",
			"Here's 200 Coins - chop my tree down please.",
			"I can't be bothered - I'd rather pay you to do it.",
		};
		String[] declined = {
			"No.",
			"I don't want to pay that much, sorry.",
			"Yes, you're right - I'll do it myself.",
		};

		java.util.Map<String, Rectangle> rows = optionsWidget(concat(accepted, declined));

		overlay.highlightClearOptions(graphics, Color.CYAN);

		assertEquals("exactly the three acceptance rows are outlined", 3, drawn.size());
		for (String text : accepted)
		{
			assertTrue("expected \"" + text + "\" to be outlined", drawn.contains(rows.get(text)));
		}
		for (String text : declined)
		{
			assertFalse("expected \"" + text + "\" NOT to be outlined",
				drawn.contains(rows.get(text)));
		}
	}

	private static String[] concat(String[] a, String[] b)
	{
		String[] all = new String[a.length + b.length];
		System.arraycopy(a, 0, all, 0, a.length);
		System.arraycopy(b, 0, all, a.length, b.length);
		return all;
	}

	/** Builds a mocked {@code Chatmenu.OPTIONS} widget with one mocked row per option text. */
	private java.util.Map<String, Rectangle> optionsWidget(String[] optionTexts)
	{
		Widget list = Mockito.mock(Widget.class);
		when(list.isHidden()).thenReturn(false);
		Rectangle listBounds = new Rectangle(0, 0, 500, 500);
		when(list.getBounds()).thenReturn(listBounds);

		Widget[] rows = new Widget[optionTexts.length];
		java.util.Map<String, Rectangle> bounds = new java.util.HashMap<>();
		for (int i = 0; i < optionTexts.length; i++)
		{
			Widget row = Mockito.mock(Widget.class);
			when(row.getText()).thenReturn(optionTexts[i]);
			when(row.getFont()).thenReturn(null);
			Rectangle rowBounds = new Rectangle(10, i * 20, 400, 18);
			when(row.getBounds()).thenReturn(rowBounds);
			rows[i] = row;
			bounds.put(optionTexts[i], rowBounds);
		}
		when(list.getDynamicChildren()).thenReturn(rows);
		when(client.getWidget(InterfaceID.Chatmenu.OPTIONS)).thenReturn(list);
		return bounds;
	}
}
