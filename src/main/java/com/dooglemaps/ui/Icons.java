package com.dooglemaps.ui;

import java.awt.Image;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import net.runelite.client.util.AsyncBufferedImage;

/** Helpers for putting item sprites on labels at a size that fits the panel. */
final class Icons
{
	private Icons()
	{
	}

	/**
	 * Sets a scaled item sprite on a label, and sets it again once the sprite arrives.
	 *
	 * <p>{@code ItemManager.getImage} hands back a placeholder that fills itself in later.
	 * Scaling produces a plain snapshot, which would freeze that placeholder in place — so
	 * re-scale on load rather than leaving a blank square until the next refresh.
	 */
	static void setScaled(JLabel label, AsyncBufferedImage image, int size)
	{
		apply(label, image, size);
		image.onLoaded(() -> SwingUtilities.invokeLater(() -> apply(label, image, size)));
	}

	private static void apply(JLabel label, BufferedImage image, int size)
	{
		label.setIcon(new ImageIcon(image.getScaledInstance(size, size, Image.SCALE_SMOOTH)));
	}
}
