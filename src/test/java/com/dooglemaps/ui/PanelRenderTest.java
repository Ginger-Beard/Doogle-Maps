package com.dooglemaps.ui;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.timer.GrowthTimer;
import com.google.gson.Gson;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Constructor;
import javax.imageio.ImageIO;
import com.formdev.flatlaf.FlatClientProperties;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.laf.RuneLiteLAF;
import net.runelite.client.util.AsyncBufferedImage;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Renders the sidebar to a PNG so it can be looked at without launching the client.
 *
 * <p>Swing layout bugs are invisible to ordinary tests — nothing throws, the panel just
 * looks wrong — so this paints the real component tree under RuneLite's own look and feel
 * and asserts on the pixels. It also leaves the image in the build directory, which is the
 * quickest way to check a layout change without a full client launch.
 *
 * <p>Set {@code -Ddooglemaps.renderOut=<path>} to write the PNG somewhere else.
 */
public class PanelRenderTest
{
	private static final int PANEL_WIDTH = 225;
	private static final int WIDTH = 242;   // PANEL_WIDTH + SCROLLBAR_WIDTH
	private static final int HEIGHT = 1100;

	private DoogleMapsPanel buildPanel() throws Exception
	{
		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString())).thenReturn(null);
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(int.class))).thenReturn(null);

		Gson gson = new Gson();

		PatchStateStore store = construct(PatchStateStore.class, configManager, gson);
		AvailabilityProfile availability = construct(AvailabilityProfile.class, configManager, gson, store);
		GrowthTimer timer = construct(GrowthTimer.class, configManager);

		// Real varbit values through the real capture path, so the rows are drawn from
		// exactly the data the plugin would hold in-game.
		seed(store, "12083.4774", 33);   // ranarr, growing
		seed(store, "11062.4774", 43);   // toadflax, ready
		seed(store, "11325.4771", 75);   // snapdragon, just planted
		seed(store, "6967.4774", 141);   // ranarr, diseased
		seed(store, "10548.4774", 171);  // dead
		seed(store, "14391.4774", 3);    // raked, empty

		ItemManager itemManager = Mockito.mock(ItemManager.class);
		ClientThread clientThread = Mockito.mock(ClientThread.class);
		when(itemManager.getImage(anyInt())).thenAnswer(invocation -> swatch(clientThread));

		DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class);
		when(config.showTimers()).thenReturn(true);
		when(config.sortProblemsFirst()).thenReturn(true);
		when(config.showStaleness()).thenReturn(true);

		return construct(DoogleMapsPanel.class, store, availability, timer, itemManager, config);
	}

	/** A recognisable stand-in for an item sprite. */
	private static AsyncBufferedImage swatch(ClientThread clientThread)
	{
		AsyncBufferedImage image = new AsyncBufferedImage(clientThread, 36, 32, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(0x7F, 0xB2, 0x4A));
		g.fillOval(4, 2, 28, 28);
		g.dispose();
		return image;
	}

	private static void seed(PatchStateStore store, String key, int varbitValue)
	{
		FarmPatch patch = FarmingWorldData.getPatch(key);
		assertNotNull("fixture patch " + key + " no longer exists", patch);

		ProduceState decoded = patch.getImplementation().forVarbitValue(varbitValue);
		assertNotNull("varbit " + varbitValue + " does not decode for " + key, decoded);
		store.recordVarbit(patch, varbitValue, decoded);
	}

	@SuppressWarnings("unchecked")
	private static <T> T construct(Class<T> type, Object... args) throws Exception
	{
		for (Constructor<?> candidate : type.getDeclaredConstructors())
		{
			if (candidate.getParameterCount() == args.length)
			{
				candidate.setAccessible(true);
				return (T) candidate.newInstance(args);
			}
		}
		throw new IllegalStateException("no constructor of arity " + args.length + " on " + type);
	}

	/**
	 * Paints the panel the way the client does: as a tab inside RuneLite's sidebar.
	 *
	 * <p>Rendering the panel on its own misses anything the sidebar itself contributes,
	 * which is exactly where a stray outline could hide.
	 */
	@Test
	public void rendersInsideTheSidebar() throws Exception
	{
		RuneLiteLAF.setup();

		DoogleMapsPanel panel = buildPanel();
		flushEdt(panel);

		// Mirrors ClientUI: a right-tabbed pane holding each plugin's wrapped panel.
		JTabbedPane sidebar = new JTabbedPane(JTabbedPane.RIGHT);
		sidebar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		sidebar.setOpaque(true);
		sidebar.putClientProperty(FlatClientProperties.STYLE,
			"tabInsets: 2,5,2,5; variableSize: true; deselectable: true; tabHeight: 26");
		sidebar.insertTab(null, null, panel.getWrappedPanel(), "Doogle Maps", 0);
		sidebar.setSelectedIndex(0);

		sidebar.setSize(WIDTH + 30, HEIGHT);
		layout(sidebar);

		BufferedImage image = new BufferedImage(sidebar.getWidth(), HEIGHT, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		sidebar.printAll(g);
		g.dispose();

		File out = new File(System.getProperty("dooglemaps.renderOut", "build/panel.png")
			.replace(".png", "-sidebar.png"));
		out.getParentFile().mkdirs();
		ImageIO.write(image, "png", out);
		System.out.println("wrote " + out.getAbsolutePath());

		// Walk the panel's own rectangle inside the sidebar looking for a light frame.
		java.awt.Rectangle b = panel.getWrappedPanel().getBounds();
		System.out.println("panel bounds in sidebar " + b);
		for (int d = 0; d < 3; d++)
		{
			System.out.println("  inset " + d
				+ " topLeft=" + hex(image.getRGB(b.x + d, b.y + d))
				+ " top=" + hex(image.getRGB(b.x + b.width / 2, b.y + d))
				+ " left=" + hex(image.getRGB(b.x + d, b.y + b.height / 2))
				+ " right=" + hex(image.getRGB(b.x + b.width - 1 - d, b.y + b.height / 2))
				+ " bottom=" + hex(image.getRGB(b.x + b.width / 2, b.y + b.height - 1 - d)));
		}
	}

	/**
	 * Shows the panel in a real window and screenshots it.
	 *
	 * <p>{@code printAll} skips some painting — focus rings in particular — so a real
	 * window is the only way to see exactly what the client shows. Needs a display, so it
	 * is opt-in: {@code -Ddooglemaps.live=true}.
	 */
	@Test
	public void rendersInALiveWindow() throws Exception
	{
		org.junit.Assume.assumeTrue(Boolean.getBoolean("dooglemaps.live"));

		RuneLiteLAF.setup();

		DoogleMapsPanel panel = buildPanel();
		flushEdt(panel);

		javax.swing.JFrame frame = new javax.swing.JFrame("Doogle Maps");
		JTabbedPane sidebar = new JTabbedPane(JTabbedPane.RIGHT);
		sidebar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		sidebar.setOpaque(true);
		sidebar.putClientProperty(FlatClientProperties.STYLE,
			"tabInsets: 2,5,2,5; variableSize: true; deselectable: true; tabHeight: 26");
		sidebar.insertTab(null, null, panel.getWrappedPanel(), "Doogle Maps", 0);
		sidebar.setSelectedIndex(0);

		frame.getContentPane().setBackground(java.awt.Color.MAGENTA);
		frame.getContentPane().add(sidebar);
		frame.setSize(WIDTH + 60, HEIGHT);
		frame.setVisible(true);

		SwingUtilities.invokeAndWait(() ->
		{
		});
		Thread.sleep(1200);

		java.awt.Rectangle bounds = new java.awt.Rectangle(frame.getLocationOnScreen(), frame.getSize());
		BufferedImage shot = new java.awt.Robot().createScreenCapture(bounds);
		File out = new File("build/panel-live.png");
		ImageIO.write(shot, "png", out);
		System.out.println("wrote " + out.getAbsolutePath());

		frame.dispose();
	}

	private static void flushEdt(DoogleMapsPanel panel) throws Exception
	{
		panel.refresh();
		SwingUtilities.invokeAndWait(() ->
		{
		});
	}

	@Test
	public void rendersTheSidebar() throws Exception
	{
		RuneLiteLAF.setup();

		DoogleMapsPanel panel = buildPanel();
		panel.refresh();
		// refresh() repaints on the EDT; wait for it before painting.
		SwingUtilities.invokeAndWait(() ->
		{
		});

		// Open the patch list so the checkboxes are visible in the render.
		clickShowPatches(panel);
		SwingUtilities.invokeAndWait(() ->
		{
		});

		JPanel wrapped = panel.getWrappedPanel();
		wrapped.setSize(WIDTH, HEIGHT);
		layout(wrapped);

		BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		wrapped.printAll(g);
		g.dispose();

		File out = new File(System.getProperty("dooglemaps.renderOut", "build/panel.png"));
		out.getParentFile().mkdirs();
		ImageIO.write(image, "png", out);
		System.out.println("wrote " + out.getAbsolutePath());

		reportEdgePixels(image);
		assertNoLightOutline(image);
		assertTabsFit(panel);
		assertTrue(out.length() > 0);
	}

	/** Presses the visible "Show patches" button, whichever tab is selected. */
	private static void clickShowPatches(java.awt.Container root)
	{
		for (java.awt.Component child : root.getComponents())
		{
			if (child instanceof javax.swing.JButton
				&& ((javax.swing.JButton) child).getText().startsWith("Show patches"))
			{
				((javax.swing.JButton) child).doClick();
				return;
			}
			if (child instanceof java.awt.Container)
			{
				clickShowPatches((java.awt.Container) child);
			}
		}
	}

	/** Lays out a component tree that was never added to a window. */
	private static void layout(java.awt.Container container)
	{
		container.doLayout();
		for (java.awt.Component child : container.getComponents())
		{
			if (child instanceof java.awt.Container)
			{
				layout((java.awt.Container) child);
			}
		}
	}

	/** Prints the colour of the outermost ring, which is where a stray outline shows up. */
	private static void reportEdgePixels(BufferedImage image)
	{
		System.out.println("top-left      " + hex(image.getRGB(0, 0)));
		System.out.println("top edge      " + hex(image.getRGB(image.getWidth() / 2, 0)));
		System.out.println("left edge     " + hex(image.getRGB(0, image.getHeight() / 2)));
		System.out.println("right edge    " + hex(image.getRGB(image.getWidth() - 1, image.getHeight() / 2)));
		System.out.println("bottom edge   " + hex(image.getRGB(image.getWidth() / 2, image.getHeight() - 1)));
		System.out.println("one in (1,1)  " + hex(image.getRGB(1, 1)));
		System.out.println("two in (2,2)  " + hex(image.getRGB(2, 2)));
	}

	/**
	 * No part of the panel may want to be wider than the sidebar.
	 *
	 * <p>Anything that does gets silently clipped rather than wrapped — which is how the
	 * tab strip lost two thirds of its tabs when the tab count grew.
	 */
	private static void assertTabsFit(DoogleMapsPanel panel)
	{
		for (java.awt.Component child : panel.getComponents())
		{
			int wanted = child.getPreferredSize().width;
			assertTrue("a " + child.getClass().getSimpleName() + " wants " + wanted
					+ "px, wider than the " + PANEL_WIDTH + "px sidebar - it will be clipped",
				wanted <= PANEL_WIDTH);
		}
	}

	/**
	 * The panel must not paint a light border of its own; the sidebar is uniformly dark and
	 * a stray outline stands out badly against the rest of the client.
	 */
	private static void assertNoLightOutline(BufferedImage image)
	{
		int w = image.getWidth();
		int h = image.getHeight();

		assertEdgeNotAFrame(image, "top", 0, 0, 1, 0, w);
		assertEdgeNotAFrame(image, "bottom", 0, h - 1, 1, 0, w);
		assertEdgeNotAFrame(image, "left", 0, 0, 0, 1, h);
		assertEdgeNotAFrame(image, "right", w - 1, 0, 0, 1, h);
	}

	/**
	 * Fails when an edge is light along most of its length.
	 *
	 * <p>Deliberately not "no light pixel anywhere on the edge": content legitimately
	 * reaches the edge — a row of text at the bottom of a long list, for instance. What
	 * must never happen is a continuous light line tracing the panel, which is what a
	 * stray border looks like.
	 */
	private static void assertEdgeNotAFrame(BufferedImage image, String edge,
		int x0, int y0, int dx, int dy, int length)
	{
		int light = 0;
		for (int i = 0; i < length; i++)
		{
			int rgb = image.getRGB(x0 + (dx * i), y0 + (dy * i));
			int brightest = Math.max(Math.max((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF), rgb & 0xFF);
			if (brightest >= 0x50)
			{
				light++;
			}
		}

		int percent = (light * 100) / length;
		assertTrue("the " + edge + " edge is light for " + percent
			+ "% of its length - that is a border around the panel, not content", percent < 60);
	}

	private static String hex(int rgb)
	{
		return String.format("#%06X", rgb & 0xFFFFFF);
	}
}
