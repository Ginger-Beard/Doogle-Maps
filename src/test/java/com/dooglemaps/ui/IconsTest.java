package com.dooglemaps.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.Icon;
import javax.swing.JLabel;
import net.runelite.client.util.AsyncBufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers the greyed-out seed icon that sometimes rendered as nothing at all.
 *
 * <p>Swing derives a disabled label's greyed icon once and caches it. Item sprites arrive
 * asynchronously, so whether that derivation happened before or after the sprite loaded came
 * down to whether the item had been drawn before — which is why the symptom looked random:
 * some level-locked seeds were grey, others invisible, with the tooltip still working because
 * only the icon was affected.
 */
public class IconsTest
{
	/**
	 * An item sprite that has not arrived yet, mimicking a cold ItemManager.
	 *
	 * <p>The client thread it takes is only used to marshal its own listeners, and this test
	 * fires them directly, so a mock is enough.
	 */
	private static AsyncBufferedImage placeholder()
	{
		return new AsyncBufferedImage(
			org.mockito.Mockito.mock(net.runelite.client.callback.ClientThread.class),
			36, 32, BufferedImage.TYPE_INT_ARGB);
	}

	/** Fills the image the way the real sprite eventually does, then fires the listeners. */
	private static void arrive(AsyncBufferedImage image)
	{
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(Color.GREEN);
		graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
		graphics.dispose();
		image.loaded();
	}

	/**
	 * The failing case: disabled first, sprite second.
	 *
	 * <p>Without the fix Swing has already cached a greyed copy of a blank image, and nothing
	 * later replaces it. The assertion is that the cache was dropped, so the next paint
	 * derives it from the real sprite.
	 */
	@Test
	public void aSpriteArrivingAfterTheLabelIsDisabledStillShows() throws Exception
	{
		JLabel label = new JLabel();
		AsyncBufferedImage image = placeholder();

		Icons.setStack(label, image);
		label.setEnabled(false);

		// Swing derives and caches the greyed icon the first time it paints.
		Icon staleDisabled = label.getDisabledIcon();
		assertNotNull("Swing should have derived one from the placeholder", staleDisabled);

		assertTrue("the placeholder greys to nothing, which is the bug's raw material",
			isBlank(staleDisabled));

		arrive(image);
		flushEdt();

		// The user-visible claim: whatever Swing paints for the disabled label now has
		// something in it. Asserted by rendering it rather than by inspecting Swing's cache,
		// because "invisible" is the actual symptom.
		assertFalse("the level-locked seed renders as nothing at all",
			isBlank(label.getDisabledIcon()));
	}

	/** The case that always worked: sprite already warm, so nothing to re-derive. */
	@Test
	public void aWarmSpriteGreysImmediately() throws Exception
	{
		JLabel label = new JLabel();
		AsyncBufferedImage image = placeholder();
		arrive(image);

		Icons.setStack(label, image);
		label.setEnabled(false);
		flushEdt();

		assertNotNull(label.getIcon());
		assertFalse(isBlank(label.getDisabledIcon()));
	}

	/** An enabled label is unaffected either way; only the disabled path had the bug. */
	@Test
	public void anEnabledLabelKeepsItsIcon() throws Exception
	{
		JLabel label = new JLabel();
		AsyncBufferedImage image = placeholder();

		Icons.setStack(label, image);
		arrive(image);
		flushEdt();

		assertNotNull(label.getIcon());
		assertTrue(label.isEnabled());
	}

	/** Whether an icon paints nothing at all - every pixel fully transparent. */
	private static boolean isBlank(Icon icon)
	{
		if (icon == null)
		{
			return true;
		}

		BufferedImage canvas = new BufferedImage(
			Math.max(1, icon.getIconWidth()), Math.max(1, icon.getIconHeight()),
			BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = canvas.createGraphics();
		icon.paintIcon(new JLabel(), graphics, 0, 0);
		graphics.dispose();

		for (int x = 0; x < canvas.getWidth(); x++)
		{
			for (int y = 0; y < canvas.getHeight(); y++)
			{
				if ((canvas.getRGB(x, y) >>> 24) != 0)
				{
					return false;
				}
			}
		}
		return true;
	}

	private static void flushEdt() throws Exception
	{
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
		});
	}
}
