package com.dooglemaps.ui;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import net.runelite.client.util.AsyncBufferedImage;

/** Helpers for putting item sprites on labels at a size that fits the panel. */
final class Icons
{
	/**
	 * Scaled sprites, keyed on item and size.
	 *
	 * <p>Scaling is not free and the panel repaints on a timer, so without this every
	 * refresh rescaled every row icon and every tab icon from scratch — the single biggest
	 * cost in a redraw, and very noticeable on a tab with a lot of patches.
	 */
	private static final Map<Long, ImageIcon> CACHE = new ConcurrentHashMap<>();

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
	static void setScaled(JLabel label, int itemId, AsyncBufferedImage image, int size)
	{
		long key = ((long) itemId << 32) | size;

		ImageIcon cached = CACHE.get(key);
		if (cached != null)
		{
			label.setIcon(cached);
			return;
		}

		label.setIcon(scale(image, size));
		// The sprite arrives later, so only cache it once it is the real thing.
		image.onLoaded(() -> SwingUtilities.invokeLater(() ->
		{
			ImageIcon icon = scale(image, size);
			CACHE.put(key, icon);
			label.setIcon(icon);
		}));
	}

	/**
	 * Puts an item sprite on a label at its native size, greying correctly when disabled.
	 *
	 * <p>{@code AsyncBufferedImage.addTo} alone is not enough for a label that may be
	 * disabled. Swing paints a disabled label with {@link JLabel#getDisabledIcon()}, which
	 * derives a greyed copy of the icon <i>once</i> and caches it. If that happens while the
	 * sprite is still the blank placeholder — which depends entirely on whether the item has
	 * been drawn before, so it looks random — the cache holds a greyed blank and the icon
	 * stays invisible for good, tooltip and all.
	 *
	 * <p>Clearing it when the sprite arrives makes Swing derive it again from the real image.
	 * Passing null also resets the flag that says the caller supplied one, which is what
	 * allows the re-derivation.
	 */
	static void setStack(JLabel label, AsyncBufferedImage image)
	{
		image.addTo(label);
		image.onLoaded(() -> SwingUtilities.invokeLater(() ->
		{
			label.setDisabledIcon(null);
			label.repaint();
		}));
	}

	private static ImageIcon scale(BufferedImage image, int size)
	{
		return new ImageIcon(image.getScaledInstance(size, size, Image.SCALE_SMOOTH));
	}
}
