package com.dooglemaps.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;

/**
 * The little diamond that marks the patch an assigned farming contract has claimed.
 *
 * <p>Everything {@link ShieldIcon} says about drawing for nine pixels applies here, and one thing
 * more: this badge has to be told apart from that one, on a tab strip where both can be visible at
 * once. Colour alone will not do it — at this size the mark is a handful of pixels and the eye
 * reads its outline long before its tint, so an amber shield would simply look like a shield drawn
 * in the wrong colour.
 *
 * <p>A diamond because it is the shape that shares no edge with a shield. The shield's whole
 * identity is the flat top; this has a point there instead, and points at all four compass
 * bearings, so the two silhouettes disagree everywhere rather than only at the bottom. It is also
 * the shape that survives being reduced: four straight lines meeting at right-ish angles stay
 * legible where anything with a curve closes into the blob the shield's first version became.
 *
 * <p>Supersampled and rimmed exactly as the shield is, for the same reasons — see that class.
 */
final class ContractIcon
{
	/** How much bigger the shape is drawn before being scaled down. */
	private static final int SUPERSAMPLE = 4;

	private ContractIcon()
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

			GeneralPath diamond = path(large);

			g.setColor(fill);
			g.fill(diamond);

			g.setColor(fill.darker().darker());
			g.setStroke(new BasicStroke(SUPERSAMPLE));
			g.draw(diamond);
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
	 * A square stood on its corner.
	 *
	 * <p>The inset is the shield's, so the two badges occupy the same footprint and the tab strip
	 * does not look ragged when a contract tab sits beside a protected one.
	 */
	private static GeneralPath path(int size)
	{
		float extent = size;
		float inset = size * 0.06f;
		float mid = extent / 2f;

		GeneralPath diamond = new GeneralPath();
		diamond.moveTo(mid, inset);
		diamond.lineTo(extent - inset, mid);
		diamond.lineTo(mid, extent - inset);
		diamond.lineTo(inset, mid);
		diamond.closePath();
		return diamond;
	}
}
