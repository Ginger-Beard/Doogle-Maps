package com.dooglemaps.ui;

import com.dooglemaps.bank.BankContents;
import com.dooglemaps.data.ItemNames;
import com.dooglemaps.guide.CarriedItems;
import com.dooglemaps.state.CompostRunStore;
import java.awt.Component;
import java.awt.Container;
import javax.swing.JButton;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The bin tab's two collapsible headings, measured rather than eyeballed.
 *
 * <h2>What went wrong, and why a compile is not evidence</h2>
 *
 * The sections were first stacked in a vertical {@code BoxLayout}, which lays its children out
 * at their <b>maximum</b> width and centres them on their {@code alignmentX} — both defaulting
 * to "as wide as I asked for, in the middle". The headings came out floating mid-panel at their
 * text width. Nothing threw, nothing failed to compile, and no existing test paints this panel,
 * so the only thing that would have caught it is a person looking at the sidebar. Reported from
 * play.
 *
 * <p>So this lays the panel out at the sidebar's real width and asserts the geometry: a heading
 * spans the panel and starts at its left edge. That is the property the fix is for, stated in a
 * way a layout change cannot quietly break.
 */
public class CompostBinPanelLayoutTest
{
	/** The sidebar's width, as {@code PanelRenderTest} uses it. */
	private static final int PANEL_WIDTH = 225;

	private CompostBinPanel panel;

	@Before
	public void setUp()
	{
		BankContents bank = Mockito.mock(BankContents.class);
		// An empty bank on purpose: with nothing owned, no icons are built and the test needs
		// no real ItemManager to render sprites through.
		Mockito.when(bank.getCount(Mockito.anyInt())).thenReturn(0);

		// Fodder on, so the allotment-bins group is laid out too - a hidden heading has no
		// geometry to measure, and measuring the geometry is the whole point of this class.
		CompostRunStore store = Mockito.mock(CompostRunStore.class);
		Mockito.when(store.isFodderEnabled()).thenReturn(true);
		Mockito.when(store.getFodderCrops()).thenReturn(java.util.Collections.emptySet());
		Mockito.when(store.getFills()).thenReturn(java.util.Collections.emptyList());

		panel = new CompostBinPanel(
			construct(PanelLayoutStore.class,
				Mockito.mock(net.runelite.client.config.ConfigManager.class)),
			store,
			bank,
			Mockito.mock(CarriedItems.class),
			Mockito.mock(net.runelite.client.game.ItemManager.class),
			Mockito.mock(ItemNames.class));

		panel.refresh();
		panel.setSize(PANEL_WIDTH, panel.getPreferredSize().height);
		layout(panel);
	}

	@Test
	public void bothHeadingsSpanTheSidebar()
	{
		for (JButton heading : headings(panel))
		{
			if (!heading.isVisible())
			{
				continue;
			}
			assertTrue(heading.getText() + " is only " + heading.getWidth() + "px of "
					+ PANEL_WIDTH + " - it is sized to its text, not the sidebar",
				heading.getWidth() >= PANEL_WIDTH - 2);
		}
	}

	@Test
	public void neitherHeadingIsFloatingInTheMiddle()
	{
		for (JButton heading : headings(panel))
		{
			if (!heading.isVisible())
			{
				continue;
			}
			assertEquals(heading.getText() + " does not start at the panel's left edge",
				0, absoluteX(heading));
		}
	}

	/**
	 * Every section is present to be measured in the first place, the outer one first.
	 *
	 * <p>Four now rather than three: the whole tab folds under one heading, so a player who has
	 * finished configuring compost gets the sidebar back for the run planner and the patch
	 * statuses. The three inner sections still fold on their own inside it.
	 */
	@Test
	public void thereAreFourOfThem()
	{
		java.util.List<JButton> headings = headings(panel);
		assertEquals("the outer collapse, the guild bin's two tiers, and the fodder list",
			4, headings.size());
		assertTrue("the outer collapse comes first: " + headings.get(0).getText(),
			headings.get(0).getText().contains("Compost"));
		assertTrue(headings.get(1).getText().contains("Supercompost"));
		assertTrue(headings.get(2).getText().contains("Compost"));
		assertTrue(headings.get(3).getText().contains("Compost these"));
	}

	/**
	 * Folding the outer section takes everything with it — both lists, both checkboxes and the
	 * fodder group — which is the whole point of it.
	 */
	@Test
	public void theOuterCollapseFoldsTheEntireTab()
	{
		java.util.List<JButton> headings = headings(panel);
		JButton outer = headings.get(0);
		assertTrue("it starts open", innerSectionsVisible(panel) > 0);

		outer.doClick();

		assertEquals("nothing below the heading survives the fold",
			0, innerSectionsVisible(panel));
		assertTrue("and the heading says it is folded: " + outer.getText(),
			outer.getText().startsWith("▸"));
	}

	/** How many of the inner heading buttons are actually on screen. */
	private static int innerSectionsVisible(Container container)
	{
		java.util.List<JButton> headings = headings(container);
		int visible = 0;
		for (int i = 1; i < headings.size(); i++)
		{
			if (headings.get(i).isShowing() || isEffectivelyVisible(headings.get(i)))
			{
				visible++;
			}
		}
		return visible;
	}

	/** Visible in its own right and in every ancestor up to the panel. */
	private static boolean isEffectivelyVisible(java.awt.Component component)
	{
		for (java.awt.Component c = component; c != null; c = c.getParent())
		{
			if (!c.isVisible())
			{
				return false;
			}
		}
		return true;
	}

	/** With fodder off the allotment group folds away, leaving the guild bin's two. */
	@Test
	public void theFodderSectionHidesWhenItIsSwitchedOff()
	{
		CompostRunStore off = Mockito.mock(CompostRunStore.class);
		Mockito.when(off.isFodderEnabled()).thenReturn(false);
		Mockito.when(off.getFodderCrops()).thenReturn(java.util.Collections.emptySet());
		Mockito.when(off.getFills()).thenReturn(java.util.Collections.emptyList());

		BankContents bank = Mockito.mock(BankContents.class);
		Mockito.when(bank.getCount(Mockito.anyInt())).thenReturn(0);

		CompostBinPanel folded = new CompostBinPanel(
			construct(PanelLayoutStore.class,
				Mockito.mock(net.runelite.client.config.ConfigManager.class)),
			off, bank, Mockito.mock(CarriedItems.class),
			Mockito.mock(net.runelite.client.game.ItemManager.class),
			Mockito.mock(ItemNames.class));
		folded.refresh();

		int visible = 0;
		for (JButton heading : headings(folded))
		{
			if (heading.isVisible())
			{
				visible++;
			}
		}
		assertEquals("the outer collapse, plus only the guild bin's two lists", 3, visible);
	}

	/** Every heading button in the panel, in the order they are laid out down the sidebar. */
	private static java.util.List<JButton> headings(Container container)
	{
		java.util.List<JButton> found = new java.util.ArrayList<>();
		collect(container, found);
		found.sort(java.util.Comparator.comparingInt(CompostBinPanelLayoutTest::absoluteY));
		assertNotNull(found);
		return found;
	}

	private static void collect(Container container, java.util.List<JButton> found)
	{
		for (Component child : container.getComponents())
		{
			if (child instanceof JButton)
			{
				found.add((JButton) child);
			}
			else if (child instanceof Container)
			{
				collect((Container) child, found);
			}
		}
	}

	/** A child's x relative to the panel, which is what "floating in the middle" is about. */
	private static int absoluteX(Component child)
	{
		int x = 0;
		for (Component at = child; at != null && !(at instanceof CompostBinPanel);
			at = at.getParent())
		{
			x += at.getX();
		}
		return x;
	}

	private static int absoluteY(Component child)
	{
		int y = 0;
		for (Component at = child; at != null && !(at instanceof CompostBinPanel);
			at = at.getParent())
		{
			y += at.getY();
		}
		return y;
	}

	/** Lays out a tree that was never added to a window; mirrors PanelRenderTest's helper. */
	private static void layout(Container container)
	{
		container.doLayout();
		for (Component child : container.getComponents())
		{
			if (child instanceof Container)
			{
				layout((Container) child);
			}
		}
	}
}
