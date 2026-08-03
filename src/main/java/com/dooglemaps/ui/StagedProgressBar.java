package com.dooglemaps.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JComponent;
import lombok.Setter;
import net.runelite.client.ui.ColorScheme;

/**
 * A progress bar divided into one segment per growth stage.
 *
 * <p>Segments rather than a smooth fill because that is what the underlying data is: the
 * game tells us "stage 3 of 5", not a percentage. Showing it as continuous would imply a
 * precision we do not have.
 */
public class StagedProgressBar extends JComponent
{
	/**
	 * Gap between segments.
	 *
	 * <p>Two pixels, not one: the client is commonly run at fractional UI scaling, where a
	 * single-pixel gap lands on a half pixel and can vanish entirely, leaving one
	 * undivided green bar.
	 */
	private static final int SEGMENT_GAP = 2;
	private static final int HEIGHT = 9;

	/** Above this many stages the segments get too thin to read, so fill smoothly. */
	private static final int MAX_DRAWN_SEGMENTS = 16;

	@Setter
	private int stage;
	@Setter
	private int stages = 1;
	@Setter
	private Color fillColor = ColorScheme.PROGRESS_COMPLETE_COLOR;
	/** Draws every segment filled regardless of stage, for finished crops. */
	@Setter
	private boolean complete;

	public StagedProgressBar()
	{
		setPreferredSize(new Dimension(0, HEIGHT));
		setMinimumSize(new Dimension(0, HEIGHT));
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D graphics = (Graphics2D) g.create();
		try
		{
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

			int width = getWidth();
			int height = getHeight();

			// Painted behind the segments so the gaps read as dark dividers.
			graphics.setColor(ColorScheme.DARKER_GRAY_COLOR.darker());
			graphics.fillRect(0, 0, width, height);

			int total = Math.max(stages, 1);
			// stage is 0-based, so stage 0 of 5 still shows one segment of progress.
			int filled = complete ? total : Math.min(stage + 1, total);

			if (total > MAX_DRAWN_SEGMENTS)
			{
				graphics.setColor(fillColor);
				graphics.fillRect(0, 0, Math.round(width * (filled / (float) total)), height);
				return;
			}

			// Derive each segment from exact fractional boundaries rather than stepping by a
			// rounded width. Rounding the width and the offset independently lets the two
			// drift into each other, closing the gap on some segments and not others — which
			// is how the dividers vanished at fractional UI scaling.
			for (int i = 0; i < total; i++)
			{
				int start = Math.round((float) width * i / total);
				int end = Math.round((float) width * (i + 1) / total);

				// Every segment but the last gives up its right edge to the divider, and the
				// divider is never allowed to eat the whole segment on a narrow bar.
				int segmentWidth = end - start - (i < total - 1 ? SEGMENT_GAP : 0);
				if (segmentWidth < 1)
				{
					segmentWidth = 1;
				}

				graphics.setColor(i < filled ? fillColor : ColorScheme.MEDIUM_GRAY_COLOR);
				graphics.fillRect(start, 0, segmentWidth, height);
			}
		}
		finally
		{
			graphics.dispose();
		}
	}
}
