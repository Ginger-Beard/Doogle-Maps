package com.dooglemaps.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;

/**
 * The checkbox tick, drawn rather than themed.
 *
 * <p>RuneLite paints checkboxes near-black on a near-black sidebar, which makes them
 * invisible. The obvious fix is FlatLaf's {@code CheckBox.icon.*} style properties, but
 * {@code RuneLiteCheckBoxUI} hands out a <i>shared</i> UI instance, so a style set on one
 * component after construction does not stick. Supplying an explicit icon bypasses the
 * look and feel entirely, which is the only way to be sure of the result.
 */
final class CheckIcon
{
	private static final int SIZE = 14;

	private static final Color BOX = new Color(0x3C, 0x3C, 0x3C);
	private static final Color BORDER = new Color(0x6E, 0x6E, 0x6E);
	private static final Color CHECK = Color.WHITE;

	private CheckIcon()
	{
	}

	static ImageIcon unchecked()
	{
		return new ImageIcon(draw(false));
	}

	static ImageIcon checked()
	{
		return new ImageIcon(draw(true));
	}

	private static BufferedImage draw(boolean checked)
	{
		BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

			g.setColor(BOX);
			g.fillRect(1, 1, SIZE - 3, SIZE - 3);

			g.setColor(BORDER);
			g.setStroke(new BasicStroke(1f));
			g.drawRect(1, 1, SIZE - 3, SIZE - 3);

			if (checked)
			{
				g.setColor(CHECK);
				g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.drawPolyline(
					new int[]{4, 6, 10},
					new int[]{7, 10, 4},
					3);
			}
		}
		finally
		{
			g.dispose();
		}
		return image;
	}
}
