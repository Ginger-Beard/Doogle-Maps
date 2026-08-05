package com.dooglemaps.ui;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
		setScaled(label, itemId, image, size, false);
	}

	/**
	 * The item's sprite, optionally with a shield badged into its corner.
	 *
	 * <p>The badge exists because the two herb tabs necessarily share an icon — the game has one
	 * herb-patch sprite, and inventing a second would be making up iconography for a distinction
	 * the game does not draw. That left the tooltip as the only way to tell them apart, which
	 * means hovering every time.
	 *
	 * <p>A shield rather than any other mark because the panel already uses one for exactly this:
	 * a protected patch's row carries a shield. Reusing it means the badge needs no explaining.
	 */
	static void setScaled(JLabel label, int itemId, AsyncBufferedImage image, int size,
		boolean shielded)
	{
		// The badge is part of the identity of the cached image, so it belongs in the key. Without
		// it the first tab drawn would win and both herb tabs would show whatever it had.
		long key = ((long) itemId << 32) | (size << 1) | (shielded ? 1 : 0);

		ImageIcon cached = CACHE.get(key);
		if (cached != null)
		{
			label.setIcon(cached);
			return;
		}

		label.setIcon(badge(scale(image, size), size, shielded));
		// The sprite arrives later, so only cache it once it is the real thing.
		image.onLoaded(() -> SwingUtilities.invokeLater(() ->
		{
			ImageIcon icon = badge(scale(image, size), size, shielded);
			CACHE.put(key, icon);
			label.setIcon(icon);
		}));
	}

	/** Draws a small shield into the bottom-right of an icon. */
	private static ImageIcon badge(ImageIcon icon, int size, boolean shielded)
	{
		if (!shielded || icon == null)
		{
			return icon;
		}

		// Just under half the tab, which is large enough to read at a glance and small enough to
		// leave the crop sprite recognisable — the tab still has to say "herb" first.
		int badgeSize = Math.max(8, size * 9 / 20);
		BufferedImage shield = ShieldIcon.create(badgeSize, SHIELD);

		BufferedImage combined = new BufferedImage(
			icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = combined.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			icon.paintIcon(null, g, 0, 0);
			g.drawImage(shield, combined.getWidth() - badgeSize, combined.getHeight() - badgeSize,
				null);
		}
		finally
		{
			g.dispose();
		}
		return new ImageIcon(combined);
	}

	/** The same green the protected-patch rows use, so the two read as one idea. */
	private static final Color SHIELD = new Color(0x4C, 0xAF, 0x50);

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
