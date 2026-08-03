package com.dooglemaps.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;

/**
 * The sidebar button icon: a map pin with a leaf in it.
 *
 * <p>Drawn rather than shipped as a sprite so there is no binary asset to keep in step
 * with the rest of the plugin.
 */
public final class PluginIcon
{
	private static final int SIZE = 24;

	private static final Color PIN = new Color(0x3E, 0x8E, 0x41);
	private static final Color PIN_DARK = new Color(0x2A, 0x62, 0x2C);
	private static final Color LEAF = new Color(0x9C, 0xCC, 0x65);

	private PluginIcon()
	{
	}

	public static BufferedImage create()
	{
		BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

			// Teardrop pin: a circle on top tapering to a point at the bottom.
			GeneralPath pin = new GeneralPath();
			pin.moveTo(12, 22.5);
			pin.curveTo(5.5, 14, 3.5, 12, 3.5, 9);
			pin.curveTo(3.5, 4.3, 7.3, 1.5, 12, 1.5);
			pin.curveTo(16.7, 1.5, 20.5, 4.3, 20.5, 9);
			pin.curveTo(20.5, 12, 18.5, 14, 12, 22.5);
			pin.closePath();

			g.setColor(PIN);
			g.fill(pin);
			g.setColor(PIN_DARK);
			g.setStroke(new BasicStroke(1.2f));
			g.draw(pin);

			g.setColor(new Color(0xF2, 0xF2, 0xF2));
			g.fill(new Ellipse2D.Float(6.5f, 3.5f, 11f, 11f));

			// Leaf inside the pin's head.
			GeneralPath leaf = new GeneralPath();
			leaf.moveTo(8.5, 12);
			leaf.quadTo(8.5, 5.5, 15.5, 5.5);
			leaf.quadTo(15.5, 12.5, 8.5, 12);
			leaf.closePath();

			g.setColor(LEAF);
			g.fill(leaf);
			g.setColor(PIN_DARK);
			g.setStroke(new BasicStroke(1f));
			g.draw(leaf);
			g.draw(new java.awt.geom.Line2D.Float(8.5f, 12f, 14f, 7f));
		}
		finally
		{
			g.dispose();
		}
		return image;
	}
}
