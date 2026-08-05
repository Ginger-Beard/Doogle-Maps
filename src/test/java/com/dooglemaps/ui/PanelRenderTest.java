package com.dooglemaps.ui;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.data.Seed;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.PlantableResolver;
import com.dooglemaps.state.SeedInventoryStore;
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

		// Catherby's allotment, protected, so the gardener's chathead badge is in the picture.
		// Allotments are the only rendered patch type that can be paid for - herbs cannot.
		seed(store, "11062.4771", 11);   // potatoes, growing
		store.recordProtected(FarmingWorldData.getPatch("11062.4771"), true);

		ItemManager itemManager = Mockito.mock(ItemManager.class);
		ClientThread clientThread = Mockito.mock(ClientThread.class);
		when(itemManager.getImage(anyInt())).thenAnswer(invocation -> swatch(clientThread));
		when(itemManager.getImage(anyInt(), anyInt(), Mockito.anyBoolean()))
			.thenAnswer(invocation -> swatch(clientThread, invocation.getArgument(1)));

		// A farming level, so seeds render as usable rather than all level-locked.
		when(configManager.getRSProfileConfiguration(
			eq("dooglemaps"), eq("farmingLevel"), eq(int.class))).thenReturn(99);

		SeedInventoryStore seeds = construct(SeedInventoryStore.class,
			Mockito.mock(net.runelite.api.Client.class), configManager, gson);
		stockBank(seeds);
		PlantableResolver resolver = construct(PlantableResolver.class, seeds);
		com.dooglemaps.state.SeedSelectionStore selection =
			construct(com.dooglemaps.state.SeedSelectionStore.class, configManager, gson);
		// Two picked, so the render shows both the highlighted and unhighlighted states.
		selection.toggle(Seed.RANARR);
		selection.toggle(Seed.SNAPDRAGON);

		com.dooglemaps.route.PatchLocationStore patchLocations =
			construct(com.dooglemaps.route.PatchLocationStore.class, configManager, gson);
		com.dooglemaps.route.BankLocationStore bankLocations =
			construct(com.dooglemaps.route.BankLocationStore.class, configManager, gson);
		com.dooglemaps.route.ShortestPathIntegration router = construct(
			com.dooglemaps.route.ShortestPathIntegration.class,
			Mockito.mock(net.runelite.client.eventbus.EventBus.class),
			Mockito.mock(ClientThread.class));
		com.dooglemaps.state.PlayerLocation playerLocation =
			construct(com.dooglemaps.state.PlayerLocation.class,
				Mockito.mock(net.runelite.api.Client.class));
		com.dooglemaps.route.RunPlanner runPlanner = construct(com.dooglemaps.route.RunPlanner.class,
			availability, patchLocations, bankLocations, selection, seeds, store, timer, router,
			playerLocation, Mockito.mock(com.dooglemaps.bank.ToolNeeds.class),
			Mockito.mock(com.dooglemaps.state.ProtectedPatches.class),
			Mockito.mock(com.dooglemaps.state.PlantingGroups.class),
			Mockito.mock(com.dooglemaps.state.ProtectionSelectionStore.class),
			Mockito.mock(com.dooglemaps.state.RunTypeStore.class));

		// A plain mock answers false for every boolean, which would switch off all 22 patch
		// types and render an empty sidebar. In the client those come from the interface's
		// own defaults, so the patch-type section is answered true here and the rest are
		// left to the explicit stubs below.
		DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class, invocation ->
		{
			net.runelite.client.config.ConfigItem item =
				invocation.getMethod().getAnnotation(net.runelite.client.config.ConfigItem.class);
			// Both sections default to true on the real interface. A plain mock answers false,
			// which would switch off all 22 patch types and all 36 locations and render an
			// empty sidebar - a passing test of nothing.
			return item != null
				&& ("patchTypes".equals(item.section()) || "locations".equals(item.section()))
				? Boolean.TRUE
				: Mockito.RETURNS_DEFAULTS.answer(invocation);
		});
		when(config.showTimers()).thenReturn(true);
		when(config.sortProblemsFirst()).thenReturn(true);
		when(config.showStaleness()).thenReturn(true);

		// Secateurs and cape on, so the rendered rows show the yield estimate at its most
		// crowded — that is the width the sidebar actually has to survive.
		when(configManager.getRSProfileConfiguration(
			eq("dooglemaps"), Mockito.startsWith("has"), eq(boolean.class))).thenReturn(true);
		com.dooglemaps.state.FarmingBonusStore bonuses = construct(
			com.dooglemaps.state.FarmingBonusStore.class, configManager, store, itemManager,
			Mockito.mock(net.runelite.api.Client.class));

		com.dooglemaps.state.RunTypeStore runTypes =
			construct(com.dooglemaps.state.RunTypeStore.class, configManager, gson);
		// Ticked so the run section renders its reward table, which is the widest thing in
		// the panel and the most likely to overflow the sidebar.
		runTypes.setSelected(new java.util.LinkedHashSet<>(java.util.Arrays.asList(
			com.dooglemaps.data.RunOption.full(com.dooglemaps.data.PlantingGroup.of(
				com.dooglemaps.data.PatchImplementation.HERB)),
			com.dooglemaps.data.RunOption.full(com.dooglemaps.data.PlantingGroup.of(
				com.dooglemaps.data.PatchImplementation.ALLOTMENT)))));

		return construct(DoogleMapsPanel.class, store, availability, timer, itemManager, config,
			resolver, seeds, selection, runPlanner, bonuses, runTypes,
			construct(com.dooglemaps.state.CompostSelectionStore.class, configManager, gson),
			stockedHarvestStats(configManager, gson),
			Mockito.mock(com.dooglemaps.bank.RunLoadout.class),
			// A real store over the mocked config, so the render exercises the same
			// defaults-on-first-read path the client does rather than a stub that always agrees.
			construct(PanelLayoutStore.class, configManager),
			// Real, not mocked: the split is what decides how many tabs get built, and a mock
			// returning an empty group list would render a sidebar with no patch tabs at all —
			// which would pass every assertion here while showing nothing.
			construct(com.dooglemaps.state.PlantingGroups.class, config,
				construct(com.dooglemaps.state.ProtectedPatches.class, configManager),
				availability),
			construct(com.dooglemaps.state.ProtectionSelectionStore.class, configManager, gson),
			construct(com.dooglemaps.bank.BankContents.class),
			construct(com.dooglemaps.guide.CarriedItems.class),
			construct(com.dooglemaps.data.ItemNames.class));
	}

	/**
	 * A harvest history with something in it, so the panel renders its real state.
	 *
	 * <p>Seeded through the config rather than by replaying harvests: the store's own load path
	 * is what the client uses, and going through it means the fixture cannot drift from the
	 * stored format.
	 */
	private static com.dooglemaps.validate.HarvestStatsStore stockedHarvestStats(
		ConfigManager configManager, Gson gson) throws Exception
	{
		when(configManager.getRSProfileConfiguration("dooglemaps", "harvestStats")).thenReturn(
			"{\"Ranarr weed|ULTRACOMPOST\":{\"crop\":\"Ranarr weed\",\"compost\":\"ULTRACOMPOST\","
				+ "\"harvests\":14,\"items\":128,\"predicted\":126.4,\"xp\":1820.0,\"best\":13,"
				+ "\"worst\":6,\"partialItems\":4,\"partialXp\":52.0},"
				+ "\"Ranarr weed|NONE\":{\"crop\":\"Ranarr weed\",\"compost\":\"NONE\","
				+ "\"harvests\":3,\"items\":13,\"predicted\":14.2,\"xp\":390.0,\"best\":6,"
				+ "\"worst\":3},"
				+ "\"Watermelon|ULTRACOMPOST\":{\"crop\":\"Watermelon\",\"compost\":"
				+ "\"ULTRACOMPOST\",\"harvests\":8,\"items\":92,\"predicted\":88.8,"
				+ "\"xp\":1160.0,\"best\":15,\"worst\":9}}");

		com.dooglemaps.validate.HarvestStatsStore stats =
			construct(com.dooglemaps.validate.HarvestStatsStore.class, configManager, gson);
		stats.load();
		return stats;
	}

	/** Puts a few herb seeds in the bank so the seed grid has something to draw. */
	private static void stockBank(SeedInventoryStore seeds)
	{
		net.runelite.api.ItemContainer bank = Mockito.mock(net.runelite.api.ItemContainer.class);
		when(bank.getItems()).thenReturn(new net.runelite.api.Item[]{
			new net.runelite.api.Item(Seed.GUAM.getItemID(), 412),
			new net.runelite.api.Item(Seed.RANARR.getItemID(), 40),
			new net.runelite.api.Item(Seed.SNAPDRAGON.getItemID(), 12),
			new net.runelite.api.Item(Seed.TORSTOL.getItemID(), 3),
			new net.runelite.api.Item(Seed.AVANTOE.getItemID(), 118),
			new net.runelite.api.Item(Seed.IRIT.getItemID(), 7),
			new net.runelite.api.Item(Seed.KWUARM.getItemID(), 25),
		});
		seeds.record(com.dooglemaps.state.SeedSource.BANK.getContainerId(), bank);
	}

	/** A recognisable stand-in for an item sprite. */
	private static AsyncBufferedImage swatch(ClientThread clientThread)
	{
		return swatch(clientThread, 0);
	}

	/**
	 * Stands in for an item sprite, with the stack number drawn on when there is one.
	 *
	 * <p>The real ItemManager renders the quantity in the game's own font; this only needs
	 * to be close enough to check the grid's layout.
	 */
	private static AsyncBufferedImage swatch(ClientThread clientThread, int quantity)
	{
		AsyncBufferedImage image = new AsyncBufferedImage(clientThread, 36, 32, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(0x7F, 0xB2, 0x4A));
		g.fillOval(4, 2, 28, 28);
		if (quantity > 0)
		{
			g.setColor(new Color(0xFF, 0xFF, 0x00));
			g.setFont(g.getFont().deriveFont(9f));
			g.drawString(String.valueOf(quantity), 1, 9);
		}
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

	/**
	 * Renders each of the three top-level tabs to its own PNG.
	 *
	 * <p>All three, not just the one that opens by default: the run controls carry the widest
	 * thing in the panel — the reward table — and moving them behind a tab would otherwise
	 * take them out of the width guard along with the view.
	 */
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

		for (String section : new String[]{"Almanac", "Doogle Maps", "Stats"})
		{
			selectSection(panel, section);
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

			// The default tab keeps the plain name, so the usual place to look is unchanged.
			String base = System.getProperty("dooglemaps.renderOut", "build/panel.png");
			File out = "Almanac".equals(section)
				? new File(base)
				: new File(base.replace(".png", "-" + section.toLowerCase().replace(' ', '-') + ".png"));
			out.getParentFile().mkdirs();
			ImageIO.write(image, "png", out);
			System.out.println("wrote " + out.getAbsolutePath());

			reportEdgePixels(image);
			assertNoLightOutline(image);
			assertTrue(out.length() > 0);
		}

		selectSection(panel, "Almanac");
		assertTabsFit(panel);
	}

	/**
	 * Switches to one of the Almanac / Doogle Maps / Stats tabs.
	 *
	 * <p>{@code MaterialTab} is a {@code JLabel}, so the tabs are found by their text — which
	 * is also what makes them findable at all, since the group exposes them only by index.
	 *
	 * <p>It has to be the <i>group</i> that selects. {@code MaterialTab.select()} only marks
	 * the tab itself, and worse, marking it first makes the group's own select a no-op, so the
	 * strip highlights the new tab while the old page stays on screen.
	 */
	private static void selectSection(java.awt.Container root, String name)
	{
		for (java.awt.Component child : root.getComponents())
		{
			if (child instanceof net.runelite.client.ui.components.materialtabs.MaterialTab
				&& name.equals(((javax.swing.JLabel) child).getText()))
			{
				net.runelite.client.ui.components.materialtabs.MaterialTab tab =
					(net.runelite.client.ui.components.materialtabs.MaterialTab) child;
				((net.runelite.client.ui.components.materialtabs.MaterialTabGroup) tab.getParent())
					.select(tab);
				return;
			}
			if (child instanceof java.awt.Container)
			{
				selectSection((java.awt.Container) child, name);
			}
		}
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
		StringBuilder offenders = new StringBuilder();
		findTooWide(panel, "", offenders);
		assertTrue("these want more than the " + PANEL_WIDTH + "px sidebar and will be clipped:"
			+ offenders, offenders.length() == 0);
	}

	/** Names the narrowest component that is still too wide — the actual cause. */
	private static void findTooWide(java.awt.Container container, String path, StringBuilder out)
	{
		for (java.awt.Component child : container.getComponents())
		{
			int wanted = child.getPreferredSize().width;
			if (wanted <= PANEL_WIDTH)
			{
				continue;
			}

			String here = path + "/" + child.getClass().getSimpleName();
			boolean blamedAChild = false;
			if (child instanceof java.awt.Container)
			{
				int before = out.length();
				findTooWide((java.awt.Container) child, here, out);
				blamedAChild = out.length() > before;
			}

			if (!blamedAChild)
			{
				String detail = child instanceof javax.swing.JLabel
					? " text=\"" + abbreviate(((javax.swing.JLabel) child).getText()) + "\""
					: "";
				out.append("\n  ").append(here).append(" wants ").append(wanted).append("px").append(detail);
			}
		}
	}

	private static String abbreviate(String text)
	{
		if (text == null)
		{
			return "";
		}
		return text.length() <= 60 ? text : text.substring(0, 60) + "...";
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
