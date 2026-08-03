package com.dooglemaps.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;

/**
 * The little shield that marks a protected or disease-immune patch.
 *
 * <p>Drawn rather than shipped as a sprite so it stays crisp at any size and can be
 * tinted to distinguish "you paid a farmer" from "this crop cannot be diseased at all".
 */
final class ShieldIcon
{
	private ShieldIcon()
	{
	}

	static BufferedImage create(int size, Color fill)
	{
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics graphics = image.getGraphics();
		Graphics2D g = (Graphics2D) graphics;
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

			float w = size;
			float h = size;
			float inset = size * 0.12f;

			GeneralPath shield = new GeneralPath();
			shield.moveTo(w / 2f, inset);
			shield.lineTo(w - inset, h * 0.28f);
			shield.lineTo(w - inset, h * 0.58f);
			// Taper to a point at the bottom.
			shield.quadTo(w - inset, h * 0.86f, w / 2f, h - inset);
			shield.quadTo(inset, h * 0.86f, inset, h * 0.58f);
			shield.lineTo(inset, h * 0.28f);
			shield.closePath();

			g.setColor(fill);
			g.fill(shield);

			g.setColor(new Color(0, 0, 0, 140));
			g.setStroke(new BasicStroke(1f));
			g.draw(shield);
		}
		finally
		{
			graphics.dispose();
		}
		return image;
	}
}
