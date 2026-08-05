package com.dooglemaps.ui;

import java.awt.Dimension;
import javax.swing.JPanel;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The run section sits at the foot of the sidebar, and gets a gap once it is scrolled to.
 *
 * <p>Both halves are easy to get subtly wrong and invisible when they are: pinning is only
 * noticeable on a short patch tab, and the gap only when the page is long enough to scroll. The
 * failure in either direction is a layout that looks nearly right.
 */
public class PageLayoutTest
{
	/** A section of fixed height, standing in for the rows or the run controls. */
	private static JPanel section(int height)
	{
		JPanel panel = new JPanel();
		panel.setPreferredSize(new Dimension(200, height));
		return panel;
	}

	/** Lays the page out inside a parent of a given height, the way a viewport would. */
	private static PageLayout laidOutIn(int parentHeight, int topHeight, int bottomHeight)
	{
		JPanel top = section(topHeight);
		JPanel bottom = section(bottomHeight);
		PageLayout page = new PageLayout(top, bottom);

		JPanel viewport = new JPanel(new java.awt.BorderLayout());
		viewport.add(page, java.awt.BorderLayout.CENTER);
		viewport.setSize(200, parentHeight);
		viewport.doLayout();
		page.doLayout();
		return page;
	}

	/**
	 * With room to spare, the bottom section reaches the foot.
	 *
	 * <p>This is the whole point: a tab with three patch rows used to leave the run controls a
	 * third of the way down with empty space beneath, and the page jumped as you clicked between
	 * a short tab and a long one.
	 */
	@Test
	public void theBottomSectionIsPinnedToTheFootWhenThereIsRoom()
	{
		PageLayout page = laidOutIn(600, 100, 80);
		java.awt.Component bottom = page.getComponent(2);

		assertEquals("the bottom section should end at the foot of the page",
			page.getHeight(), bottom.getY() + bottom.getHeight());
		assertTrue("and the page should have taken the full height it was given",
			page.getHeight() >= 600);
	}

	/**
	 * With more content than height, it simply follows on — plus a gap.
	 *
	 * <p>Pinned to the foot the two sections are separated by the empty space between them.
	 * Scrolling removes that space entirely and the run controls end up against the last patch
	 * row, so the gap has to be put back — but only then, which is why it is not a border.
	 */
	@Test
	public void scrollingLeavesAGapAboveTheBottomSection()
	{
		PageLayout page = laidOutIn(200, 400, 80);
		java.awt.Component top = page.getComponent(0);
		java.awt.Component bottom = page.getComponent(2);

		int gap = bottom.getY() - (top.getY() + top.getHeight());
		assertTrue("no breathing room between the rows and the run once scrolling: " + gap,
			gap > 0);
	}

	/** And the page asks for the height its content needs, so the scrollbar can go away. */
	@Test
	public void thePreferredHeightFollowsTheContent()
	{
		PageLayout roomy = laidOutIn(600, 100, 80);
		assertEquals("a short page must not claim the viewport's height, or it would always "
			+ "look like it needs scrolling", 180, roomy.getPreferredSize().height);

		PageLayout scrolling = laidOutIn(200, 400, 80);
		assertTrue("a long page should ask for its content plus the gap",
			scrolling.getPreferredSize().height > 480);
	}
}
