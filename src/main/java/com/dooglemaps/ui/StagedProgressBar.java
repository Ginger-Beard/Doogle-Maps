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
	private static final int SEGMENT_GAP = 1;
	private static final int HEIGHT = 8;

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

			graphics.setColor(ColorScheme.DARKER_GRAY_COLOR);
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

			float segmentWidth = (width - (SEGMENT_GAP * (total - 1))) / (float) total;
			for (int i = 0; i < total; i++)
			{
				graphics.setColor(i < filled ? fillColor : ColorScheme.MEDIUM_GRAY_COLOR);
				int x = Math.round(i * (segmentWidth + SEGMENT_GAP));
				graphics.fillRect(x, 0, Math.round(segmentWidth), height);
			}
		}
		finally
		{
			graphics.dispose();
		}
	}
}
