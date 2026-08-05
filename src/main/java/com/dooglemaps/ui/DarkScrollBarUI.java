package com.dooglemaps.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Rectangle;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import net.runelite.client.ui.ColorScheme;

/**
 * A flat dark scrollbar, drawn rather than themed.
 *
 * <p>The look and feel is supposed to style scrollbars globally, and for most panels it
 * does. This one kept coming out in the default blue-and-white Metal colours, which is the
 * same symptom the "Show patches" button and the checkboxes had: the shared UI delegate is
 * not reliably reaching components a plugin panel creates. Chasing why is not worth it when
 * painting three rectangles settles it for good, on any look and feel.
 *
 * <p>Also drops the stepper buttons. They are a Metal-era affordance, nothing else in the
 * client has them, and they were most of what made the scrollbar look foreign.
 */
class DarkScrollBarUI extends BasicScrollBarUI
{
	/** Width of the whole bar. Narrow, matching the rest of the client's scrollers. */
	static final int WIDTH = 10;

	/**
	 * Pixels per mouse-wheel notch.
	 *
	 * <p>Swing's default unit increment is one pixel, which is why the panel needed a
	 * ludicrous amount of scrolling to move at all. A row here is about 46px, so this moves
	 * roughly a third of a row per notch and three notches clear one row.
	 */
	static final int UNIT_INCREMENT = 16;

	private static final Color TRACK = ColorScheme.DARK_GRAY_COLOR;
	private static final Color THUMB = ColorScheme.MEDIUM_GRAY_COLOR;
	private static final Color THUMB_HOVER = ColorScheme.LIGHT_GRAY_COLOR;

	@SuppressWarnings("unused") // Swing calls this reflectively when the UI class is set.
	public static ComponentUI createUI(JComponent component)
	{
		return new DarkScrollBarUI();
	}

	/** Installs on a scrollbar and sets the sizing that goes with it. */
	static void install(JScrollBar scrollBar)
	{
		scrollBar.setUI(new DarkScrollBarUI());
		scrollBar.setPreferredSize(new Dimension(WIDTH, 0));
		scrollBar.setUnitIncrement(UNIT_INCREMENT);
		scrollBar.setBackground(TRACK);
		scrollBar.setBorder(null);
		scrollBar.setFocusable(false);
		scrollBar.setOpaque(true);
	}

	@Override
	protected void configureScrollBarColors()
	{
		trackColor = TRACK;
		thumbColor = THUMB;
	}

	@Override
	protected JButton createDecreaseButton(int orientation)
	{
		return hiddenButton();
	}

	@Override
	protected JButton createIncreaseButton(int orientation)
	{
		return hiddenButton();
	}

	/**
	 * A stepper button that occupies no space.
	 *
	 * <p>{@code BasicScrollBarUI} requires both buttons to exist and lays out against their
	 * preferred size, so they are given a zero one rather than being left out.
	 */
	private static JButton hiddenButton()
	{
		JButton button = new JButton();
		button.setPreferredSize(new Dimension(0, 0));
		button.setMinimumSize(new Dimension(0, 0));
		button.setMaximumSize(new Dimension(0, 0));
		button.setFocusable(false);
		return button;
	}

	@Override
	protected void paintTrack(Graphics graphics, JComponent component, Rectangle bounds)
	{
		graphics.setColor(TRACK);
		graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
	}

	@Override
	protected void paintThumb(Graphics graphics, JComponent component, Rectangle bounds)
	{
		if (bounds.isEmpty() || !scrollbar.isEnabled())
		{
			return;
		}

		graphics.setColor(isThumbRollover() || isDragging ? THUMB_HOVER : THUMB);
		// Inset by a pixel each side so the thumb reads as a bar on a track rather than
		// filling the gutter edge to edge.
		graphics.fillRect(bounds.x + 1, bounds.y, bounds.width - 2, bounds.height);
	}
}
