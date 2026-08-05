package com.dooglemaps.ui;

import com.dooglemaps.validate.HarvestStatsStore;
import com.google.gson.Gson;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.text.JTextComponent;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.laf.RuneLiteLAF;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Covers what the harvest history actually puts on screen.
 *
 * <p>{@code HarvestStatsStoreTest} already pins the arithmetic, so this is about the panel's
 * end of the bargain: that the distinction the store is careful to maintain — items from
 * abandoned patches count towards your totals but never towards an average — survives being
 * rendered, and that the table fits the sidebar it has to live in.
 */
public class HarvestStatsPanelTest
{
	/** The sidebar's width. Anything wider is clipped in the client. */
	private static final int SIDEBAR_WIDTH = 225;

	/**
	 * Two crops, one of them harvested under two different compost tiers, and four items
	 * picked from a ranarr patch that was walked away from.
	 *
	 * <p>The figures are chosen so a mistake shows: with the partial items wrongly folded in,
	 * ranarr averages 8.5 rather than 8.3.
	 */
	private static final String HISTORY =
		"{\"Ranarr weed|ULTRACOMPOST\":{\"crop\":\"Ranarr weed\",\"compost\":\"ULTRACOMPOST\","
			+ "\"harvests\":14,\"items\":128,\"predicted\":126.4,\"xp\":1820.0,\"best\":13,"
			+ "\"worst\":6,\"partialItems\":4,\"partialXp\":52.0},"
			+ "\"Ranarr weed|NONE\":{\"crop\":\"Ranarr weed\",\"compost\":\"NONE\","
			+ "\"harvests\":3,\"items\":13,\"predicted\":14.2,\"xp\":390.0,\"best\":6,"
			+ "\"worst\":3},"
			+ "\"Watermelon|ULTRACOMPOST\":{\"crop\":\"Watermelon\",\"compost\":\"ULTRACOMPOST\","
			+ "\"harvests\":8,\"items\":92,\"predicted\":88.8,\"xp\":1160.0,\"best\":15,"
			+ "\"worst\":9}}";

	private HarvestStatsStore stats;

	@Before
	public void setUp() throws Exception
	{
		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration("dooglemaps", "harvestStats"))
			.thenReturn(HISTORY);

		stats = construct(HarvestStatsStore.class, configManager, new Gson());
		stats.load();
	}

	@Test
	public void abandonedPatchesCountTowardsTheTotalButNotTheAverage()
	{
		HarvestStatsPanel panel = panel();
		List<String> row = rowFor(panel, "Ranarr weed");

		// The patch count is what makes the other two columns legible: without it, 145 items
		// beside an average of 8.3 looks like arithmetic that does not add up.
		assertEquals("14 + 3 patches picked clean", "17", row.get(1));
		// 128 finished plus 13 finished plus the 4 left standing.
		assertEquals("everything picked belongs in the lifetime total", "145", row.get(2));
		// 141 over 17 finished patches. Counting the partial's items would give 8.5.
		assertEquals("a half-picked patch is not a low yield", "8.3", row.get(3));
	}

	/**
	 * The abandoned items are called out where the gap they cause is visible.
	 *
	 * <p>145 items over 17 patches averaging 8.3 does not reconcile until you know four of
	 * those items came from a patch that was never finished.
	 */
	@Test
	public void theTooltipExplainsTheItemsTheAverageIgnores()
	{
		String tooltip = tooltipFor(panel(), "Ranarr weed");
		assertNotNull(tooltip);
		assertTrue("the partial items need saying out loud, " + tooltip,
			tooltip.contains("4 more from patches left standing"));
	}

	@Test
	public void compostTiersAreSummedInTheRowAndSplitInTheTooltip()
	{
		HarvestStatsPanel panel = panel();

		// One line per crop, not one per crop and tier - otherwise ranarr appears twice.
		assertEquals(1, rowsNamed(panel, "Ranarr weed").size());

		String tooltip = tooltipFor(panel, "Ranarr weed");
		assertNotNull("the tier split is the reason to hover", tooltip);
		assertTrue("ultracompost's own average should be there, " + tooltip,
			tooltip.contains("Ultracompost"));
		assertTrue("and so should the untreated one, " + tooltip, tooltip.contains("Untreated"));
	}

	@Test
	public void anEmptyHistorySaysSoRatherThanShowingAnEmptyTable()
	{
		String text = textOf(new HarvestStatsPanel(emptyStore()));
		assertTrue("an empty table reads as a bug, " + text, text.contains("Nothing recorded yet"));
	}

	/**
	 * Also writes the Stats tab to {@code build/harvest-stats.png}.
	 *
	 * <p>Same reasoning as {@code PanelRenderTest}: a Swing layout fault throws nothing, it
	 * just looks wrong, so leaving the image behind is the quickest way to check a change
	 * without launching the client.
	 */
	@Test
	public void theTableFitsTheSidebar() throws Exception
	{
		RuneLiteLAF.setup();

		HarvestStatsPanel panel = panel();
		panel.setSize(SIDEBAR_WIDTH, panel.getPreferredSize().height);
		layout(panel);

		assertTrue("the history must not push the sidebar wider: "
				+ panel.getPreferredSize().width,
			panel.getPreferredSize().width <= SIDEBAR_WIDTH);

		BufferedImage image = new BufferedImage(
			panel.getWidth(), Math.max(1, panel.getHeight()), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		panel.printAll(g);
		g.dispose();

		File out = new File("build/harvest-stats.png");
		out.getParentFile().mkdirs();
		ImageIO.write(image, "png", out);
		System.out.println("wrote " + out.getAbsolutePath());
	}

	/**
	 * Lays a component out for real, which nothing does until it has a peer or is told to.
	 *
	 * <p>Only {@code doLayout}, so every layout manager gets to place its own children.
	 * Forcing each child to its preferred size instead produces a picture of a panel nobody
	 * will ever see — it was how this first reported the table as zero pixels wide.
	 */
	private static void layout(Container container)
	{
		container.doLayout();
		for (Component child : container.getComponents())
		{
			if (child instanceof Container)
			{
				layout((Container) child);
			}
		}
	}

	@Test
	public void theHeadlineNamesTheLifetimeTotals()
	{
		// 14 + 3 + 8 patches picked clean, and 145 + 92 items including the partials.
		String text = textOf(panel());
		assertTrue("expected 25 patches and 237 items, got " + text,
			text.contains("25 patches, 237 items"));
	}

	// ------------------------------------------------------------------ helpers

	private HarvestStatsPanel panel()
	{
		return new HarvestStatsPanel(stats);
	}

	private HarvestStatsStore emptyStore()
	{
		try
		{
			ConfigManager configManager = Mockito.mock(ConfigManager.class);
			HarvestStatsStore empty = construct(HarvestStatsStore.class, configManager, new Gson());
			empty.load();
			return empty;
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
	}

	/** The cell texts of the one row whose name column reads {@code name}. */
	private static List<String> rowFor(Container panel, String name)
	{
		List<List<String>> matches = rowsNamed(panel, name);
		assertEquals("expected exactly one " + name + " row", 1, matches.size());
		return matches.get(0);
	}

	private static List<List<String>> rowsNamed(Container panel, String name)
	{
		List<List<String>> matches = new ArrayList<>();
		for (Container row : rows(panel))
		{
			List<String> cells = new ArrayList<>();
			for (Component child : labels(row))
			{
				cells.add(((JLabel) child).getText());
			}
			if (!cells.isEmpty() && name.equals(cells.get(0)))
			{
				matches.add(cells);
			}
		}
		return matches;
	}

	private static String tooltipFor(Container panel, String name)
	{
		for (Container row : rows(panel))
		{
			List<Component> cells = labels(row);
			if (!cells.isEmpty() && name.equals(((JLabel) cells.get(0)).getText())
				&& row instanceof javax.swing.JComponent)
			{
				return ((javax.swing.JComponent) row).getToolTipText();
			}
		}
		return null;
	}

	/**
	 * Every container holding a name label, i.e. every table row.
	 *
	 * <p>Found by shape rather than by type because {@code DataTable} builds rows out of plain
	 * panels; a marker class would exist only for this test.
	 */
	private static List<Container> rows(Container panel)
	{
		List<Container> found = new ArrayList<>();
		collectRows(panel, found);
		return found;
	}

	private static void collectRows(Container container, List<Container> found)
	{
		if (!labels(container).isEmpty())
		{
			found.add(container);
		}
		for (Component child : container.getComponents())
		{
			if (child instanceof Container)
			{
				collectRows((Container) child, found);
			}
		}
	}

	/** The labels of one row, in left-to-right order: the name, then each value cell. */
	private static List<Component> labels(Container row)
	{
		List<Component> found = new ArrayList<>();
		Component name = null;
		for (Component child : row.getComponents())
		{
			if (child instanceof JLabel)
			{
				name = child;
			}
		}
		if (name == null)
		{
			return found;
		}
		found.add(name);
		for (Component child : row.getComponents())
		{
			if (child instanceof Container)
			{
				for (Component cell : ((Container) child).getComponents())
				{
					if (cell instanceof JLabel)
					{
						found.add(cell);
					}
				}
			}
		}
		return found.size() > 1 ? found : new ArrayList<>();
	}

	/** Every piece of text the panel shows, for assertions about prose rather than cells. */
	private static String textOf(Container container)
	{
		StringBuilder text = new StringBuilder();
		for (Component child : container.getComponents())
		{
			if (child instanceof JLabel)
			{
				text.append(((JLabel) child).getText()).append('\n');
			}
			else if (child instanceof JTextComponent)
			{
				text.append(((JTextComponent) child).getText()).append('\n');
			}
			else if (child instanceof AbstractButton)
			{
				text.append(((AbstractButton) child).getText()).append('\n');
			}
			if (child instanceof Container)
			{
				text.append(textOf((Container) child));
			}
		}
		return text.toString();
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
}
