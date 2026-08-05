package com.dooglemaps.ui;

import java.awt.Component;
import java.awt.Dimension;
import javax.swing.BoxLayout;
import javax.swing.JPanel;

/**
 * Two stacked sections, the second pinned to the bottom of the sidebar until it cannot be.
 *
 * <h2>Why {@code BorderLayout.SOUTH} is not this</h2>
 *
 * SOUTH puts a component at the bottom of <i>its container</i>, and the container here is inside a
 * scroll pane that sizes itself to its contents. So the container is exactly as tall as the two
 * sections together, and "the bottom of the container" is simply "directly under the first
 * section" — which is where the run controls already were. On a tab with three patch rows that
 * left the run a third of the way down the sidebar with empty space beneath it, and the whole page
 * jumped as you clicked between a short tab and a long one.
 *
 * <h2>What it does instead</h2>
 *
 * A vertical box with <b>glue</b> between the two: the glue absorbs whatever height is spare, so
 * the bottom section is pushed to the foot of the visible area. When the content is taller than
 * the viewport there is no spare height, the glue collapses, and the section simply follows on —
 * which is what scrolling wants.
 *
 * <p>The preferred height reported to the scroll pane is deliberately the <i>content's</i> height,
 * not the viewport's. Reporting the viewport height would make the pane think it always needs to
 * scroll and the scrollbar would never go away.
 */
class PageLayout extends JPanel
{
	/**
	* Space above the bottom section once it is being scrolled to.
	*
	* <p>Pinned to the foot of the sidebar the two sections are already separated by the empty
	* space between them. Scrolling removes that space entirely and the run controls end up
	* directly against the last patch row, so the gap has to be put back by hand — but only then,
	* which is why it is not simply a border.
	*/
	private static final int SCROLLED_GAP = 10;

	private final Component top;
	private final Component bottom;
	private final Component glue = javax.swing.Box.createVerticalGlue();

	PageLayout(Component top, Component bottom)
	{
		this.top = top;
		this.bottom = bottom;

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(true);

		add(top);
		add(glue);
		add(bottom);
	}

	/**
	* The height the two sections actually need, plus the gap when they are being scrolled.
	*
	* <p>Asked of the parent rather than remembered: whether this is scrolling is a fact about the
	* viewport's height against ours, and it changes as the window is resized and as tabs with
	* different row counts are opened.
	*/
	@Override
	public Dimension getPreferredSize()
	{
		Dimension wanted = new Dimension(
			Math.max(top.getPreferredSize().width, bottom.getPreferredSize().width),
			top.getPreferredSize().height + bottom.getPreferredSize().height);

		if (isScrolling(wanted.height))
		{
			wanted.height += SCROLLED_GAP;
		}
		return wanted;
	}

	/** Whether the content is taller than the space it has, so the glue will have collapsed. */
	private boolean isScrolling(int contentHeight)
	{
		java.awt.Container parent = getParent();
		return parent != null && parent.getHeight() > 0 && contentHeight > parent.getHeight();
	}

	/**
	* Puts the gap in above the bottom section, and only while scrolling.
	*
	* <p>Done here rather than by a border on the run panel because the gap must not exist when
	* the section is pinned to the foot — there it would be indistinguishable from the empty space
	* already above it, and would simply push the controls a little off the bottom.
	*/
	@Override
	public void doLayout()
	{
		// Both sections capped to the height they asked for, so the glue is the only thing that
		// can grow. Without this the top section's maximum is unbounded and BoxLayout hands the
		// spare height to it instead — the sections stay stuck together and the bottom one never
		// reaches the foot, which is exactly the arrangement being replaced.
		cap(top);
		cap(bottom);

		int gap = isScrolling(top.getPreferredSize().height + bottom.getPreferredSize().height)
			? SCROLLED_GAP
			: 0;
		glue.setMinimumSize(new Dimension(0, gap));
		glue.setPreferredSize(new Dimension(0, gap));
		super.doLayout();
	}

	/** Fixes a section's height to what it wants, leaving its width free to fill the sidebar. */
	private static void cap(Component section)
	{
		section.setMaximumSize(
			new Dimension(Integer.MAX_VALUE, section.getPreferredSize().height));
	}
}
