package com.dooglemaps.ui;

import java.awt.Dimension;
import javax.swing.JTextArea;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Body text that wraps to the sidebar's width.
 *
 * <p>A JLabel cannot do this. Its HTML renderer lays wrapped text out correctly but still
 * reports the <i>unwrapped</i> width as its preferred size — a CSS width on the body or a
 * div does not change that — so one sentence of help text makes the label ask for several
 * hundred pixels and pushes every panel above it past the edge of the sidebar.
 *
 * <p>A text area wraps for real. Styled to look like a label, and non-focusable so it
 * cannot take keypresses meant for the game.
 */
class WrappedText extends JTextArea
{
	/** The sidebar's width once panel and component padding are taken off. */
	private static final int WRAP_WIDTH = 195;

	WrappedText()
	{
		setLineWrap(true);
		setWrapStyleWord(true);
		setEditable(false);
		setFocusable(false);
		setOpaque(false);
		setFont(FontManager.getRunescapeSmallFont());
		setForeground(ColorScheme.LIGHT_GRAY_COLOR);
	}

	@Override
	public Dimension getPreferredSize()
	{
		// Wrapping height depends on width, and the component has no width until it is
		// laid out. Fix the width first so the height is computed against it.
		setSize(WRAP_WIDTH, Short.MAX_VALUE);
		Dimension preferred = super.getPreferredSize();
		return new Dimension(WRAP_WIDTH, preferred.height);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return getPreferredSize();
	}
}
