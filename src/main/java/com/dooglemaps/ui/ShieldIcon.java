package com.dooglemaps.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;

/**
 * The little shield that marks a protected or disease-immune patch.
 *
 * <p>Drawn rather than shipped as a sprite so it stays crisp at any size and can be tinted to
 * distinguish "you paid a farmer" from "this crop cannot be diseased at all".
 *
 * <h2>Drawn for 9 pixels, because that is the size that matters</h2>
 *
 * The badge on a patch-type tab is about nine pixels square and the one on a row is fourteen. At
 * that size a shape is its silhouette and nothing else — there is no room for detail, and any
 * detail that is added just muddies the outline.
 *
 * <p>The first version tapered to a point at the <b>top</b> as well as the bottom, which is a fine
 * shield at 64px and a circle at 9. Everything here serves telling it apart from a dot:
 *
 * <ul>
 *   <li><b>A flat top, the full width of the icon.</b> This is the whole distinction. A horizontal
 *       edge is the one thing a circle cannot have, and it is what the eye picks up first.</li>
 *   <li><b>Straight parallel sides</b> for the top half, so those corners stay square.</li>
 *   <li><b>Straight diagonals</b> from the shoulder to a sharp point at the foot — no curve at
 *       all. Four candidates were rendered side by side at 9, 14, 18 and 32 pixels, and this
 *       was the only one still reading as a shield at nine: every version with a bowed flank,
 *       however slight, closed up into a rounded blob once scaled down. That is what the
 *       original was, and why it was reported as a dot.</li>
 *   <li><b>Supersampled.</b> Drawn at four times the size and scaled down, because Java2D's
 *       antialiasing of a nine-pixel path is coarse enough to lose the flat top to a single
 *       stair-step. This is the difference between a shield and a smudge.</li>
 *   <li><b>A rim of its own colour, darkened</b>, rather than black. A black outline at this size
 *       is a third of the icon's width and swallows the fill.</li>
 * </ul>
 */
final class ShieldIcon
{
	/** How much bigger the shape is drawn before being scaled down. */
	private static final int SUPERSAMPLE = 4;

	private ShieldIcon()
	{
	}

	static BufferedImage create(int size, Color fill)
	{
		int large = size * SUPERSAMPLE;
		BufferedImage oversized = new BufferedImage(large, large, BufferedImage.TYPE_INT_ARGB);

		Graphics2D g = oversized.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
				RenderingHints.VALUE_STROKE_PURE);

			GeneralPath shield = path(large);

			g.setColor(fill);
			g.fill(shield);

			// Darkened rather than black, and scaled with the icon so it stays one pixel once the
			// image is reduced.
			g.setColor(fill.darker().darker());
			g.setStroke(new BasicStroke(SUPERSAMPLE));
			g.draw(shield);
		}
		finally
		{
			g.dispose();
		}

		BufferedImage icon = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D out = icon.createGraphics();
		try
		{
			out.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			out.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			out.drawImage(oversized, 0, 0, size, size, null);
		}
		finally
		{
			out.dispose();
		}
		return icon;
	}

	/**
	 * A heater shield: flat across the top, straight down the sides, pointed at the foot.
	 *
	 * <p>Five straight lines and no curves. The shoulder sits at half the height, which is the
	 * balance worth keeping — higher and the flat sides vanish, lower and the point is too stubby
	 * to register at nine pixels.
	 */
	private static GeneralPath path(int size)
	{
		float w = size;
		float h = size;
		float inset = size * 0.06f;
		float left = inset;
		float right = w - inset;
		float shoulder = h * 0.50f;

		GeneralPath shield = new GeneralPath();
		shield.moveTo(left, inset);
		shield.lineTo(right, inset);
		shield.lineTo(right, shoulder);
		shield.lineTo(w / 2f, h - inset);
		shield.lineTo(left, shoulder);
		shield.closePath();
		return shield;
	}
}
