package com.dooglemaps.guide;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;

/**
 * Marks one item, the same way everywhere.
 *
 * <p>The item's own silhouette in the highlight colour, then a translucent wash over the sprite.
 * The treatment is Quest Helper's, borrowed on purpose: it is the gesture players already read as
 * "this one", and inventing a second vocabulary for it would only be something to learn.
 *
 * <h2>Why this is shared rather than written where it is used</h2>
 *
 * The consistency <b>is</b> the feature. The bank overlay and the inventory overlay say the same
 * thing about the same item at two different moments — take this out of the bank, then this is the
 * one to click — and if they drew it differently the player would have to work out whether the
 * difference meant anything.
 *
 * <p>It was duplicated, byte for byte, in both overlays, and {@code ITEM_FILL_ALPHA} was declared
 * in three separate files. Nothing had drifted yet, which is the point: three copies of a constant
 * whose entire purpose is that two things look identical is a drift waiting for whoever next tunes
 * the alpha in one file and not the others.
 */
public final class ItemHighlight
{
	/**
	 * How heavy the wash over the sprite is.
	 *
	 * <p>Enough to read as marked at a glance across a full bank tab, light enough to leave the
	 * item recognisable underneath — which matters, because the whole point is to find a specific
	 * item rather than to notice that something is highlighted.
	 */
	public static final int FILL_ALPHA = 65;

	private ItemHighlight()
	{
	}

	/**
	 * Draws the outline and wash for an item at these bounds.
	 *
	 * @param quantity the stack size, which the outline needs so it traces the stack's own sprite
	 *                 rather than a single item's
	 */
	public static void draw(Graphics2D graphics, ItemManager itemManager, Rectangle bounds,
		int itemId, int quantity, java.awt.Color colour)
	{
		BufferedImage outline = itemManager.getItemOutline(itemId, quantity, colour);
		graphics.drawImage(outline, (int) bounds.getX(), (int) bounds.getY(), null);
		graphics.drawImage(
			ImageUtil.fillImage(itemManager.getImage(itemId, quantity, false),
				ColorUtil.colorWithAlpha(colour, FILL_ALPHA)),
			(int) bounds.getX(), (int) bounds.getY(), null);
	}
}
