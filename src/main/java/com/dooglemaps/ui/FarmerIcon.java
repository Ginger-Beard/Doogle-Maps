package com.dooglemaps.ui;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import lombok.extern.slf4j.Slf4j;

/**
 * The face of the gardener who is owed for a patch.
 *
 * <p>Every protected patch used to be marked with the same green shield, which said only
 * "protected" — a fact the tooltip already gave. {@code FarmPatch.getFarmer()} has always
 * known <i>which</i> gardener that is, so the badge can name a person instead: Elstan at
 * Falador, Dantaera at Catherby.
 *
 * <p>The sprites are bundled rather than rendered because they cannot be rendered. Chatheads
 * are 3D model renders, so there is no sprite to ask {@code ItemManager} for, and while the
 * RuneLite API exposes {@code NPCComposition.getChatheadModels()} and {@code Client.loadModel},
 * it has nothing that rasterises a {@code Model} to an image. See
 * {@code tools/fetch_chatheads.py} for where they come from.
 *
 * <p>Forty-eight of the forty-nine gardeners have one. The odd one out is the Tortugan who
 * tends the coral patch, who has no wiki page yet — {@link #of} returns null for him and the
 * caller keeps the shield, which is also what happens if a future patch introduces a farmer
 * whose sprite has not been fetched.
 */
@Slf4j
final class FarmerIcon
{
	private static final String PATH = "/com/dooglemaps/chatheads/%d.png";

	/**
	 * Scaled portraits, keyed on farmer and size.
	 *
	 * <p>The panel repaints on a timer, so without this every refresh would re-read and
	 * re-scale a PNG per visible row. {@code MISSING} is stored for a farmer with no sprite so
	 * that a miss costs one lookup rather than a failed resource read every redraw.
	 */
	private static final Map<Long, ImageIcon> CACHE = new ConcurrentHashMap<>();

	/** Stands in for "there is no portrait for this farmer", which a map cannot hold as null. */
	private static final ImageIcon MISSING = new ImageIcon();

	private FarmerIcon()
	{
	}

	/**
	 * This farmer's face at the given size, or null if there is not one.
	 *
	 * @param npcId the farmer from {@code FarmPatch.getFarmer()}; -1 where a patch has none
	 */
	@Nullable
	static ImageIcon of(int npcId, int size)
	{
		if (npcId < 0)
		{
			return null;
		}

		long key = ((long) npcId << 32) | size;
		ImageIcon cached = CACHE.get(key);
		if (cached != null)
		{
			return cached == MISSING ? null : cached;
		}

		BufferedImage source = read(npcId);
		if (source == null)
		{
			CACHE.put(key, MISSING);
			return null;
		}

		ImageIcon icon = new ImageIcon(fit(source, size));
		CACHE.put(key, icon);
		return icon;
	}

	@Nullable
	private static BufferedImage read(int npcId)
	{
		String path = String.format(PATH, npcId);
		try (InputStream stream = FarmerIcon.class.getResourceAsStream(path))
		{
			return stream == null ? null : ImageIO.read(stream);
		}
		catch (IOException e)
		{
			log.debug("Could not read {}", path, e);
			return null;
		}
	}

	/**
	 * Scales a portrait into a square box without distorting it.
	 *
	 * <p>Chatheads are not square and not even consistently shaped — they run from 64x127 to
	 * 117x108, because a tall hat is part of the picture. Scaling to a square would squash
	 * some faces and stretch others, so the longer side is fitted and the result centred in
	 * the box.
	 */
	private static BufferedImage fit(BufferedImage source, int size)
	{
		double scale = Math.min(size / (double) source.getWidth(), size / (double) source.getHeight());
		int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
		int height = Math.max(1, (int) Math.round(source.getHeight() * scale));

		BufferedImage box = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = box.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(source.getScaledInstance(width, height, Image.SCALE_SMOOTH),
				(size - width) / 2, (size - height) / 2, null);
		}
		finally
		{
			g.dispose();
		}
		return box;
	}
}
