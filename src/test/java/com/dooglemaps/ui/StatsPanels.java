package com.dooglemaps.ui;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.ItemPrices;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Seed;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.state.CompostSelectionStore;
import com.dooglemaps.state.FarmingBonusStore;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.timer.FarmingBonuses;
import com.dooglemaps.validate.DiseaseStats;
import com.dooglemaps.validate.DiseaseStatsStore;
import com.dooglemaps.validate.FarmRun;
import com.dooglemaps.validate.HarvestHistory;
import com.dooglemaps.validate.HarvestStatsStore;
import com.google.gson.Gson;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.text.JTextComponent;
import net.runelite.api.Experience;
import net.runelite.client.config.ConfigManager;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The fixtures and the tree-walking behind every test of the Stats tab.
 *
 * <p>Shared rather than copied because the tab is now four cards tested by four classes, and the
 * expensive half of each of those tests is the eight mocked stores it takes to stand a panel up.
 * The inspection half is shared for a stronger reason: {@link #textOf} and {@link #openAll}
 * encode what "the panel shows this" now means, and two classes disagreeing about that would be
 * two classes testing different panels.
 */
final class StatsPanels
{
	/** The sidebar's width. Anything wider is clipped in the client. */
	static final int SIDEBAR_WIDTH = 225;

	/** An empty bank, which is what every test about the history cards wants. */
	static final Map<Seed, Integer> NO_SEEDS = Collections.emptyMap();

	static final int FARMING_LEVEL = 75;
	private static final int PATCHES_PER_TYPE = 5;

	/**
	 * Two crops, one of them harvested under two different compost tiers, and four items
	 * picked from a ranarr patch that was walked away from.
	 *
	 * <p><b>No variance fields</b>, which makes this also the fixture for an older history —
	 * seventeen ranarr patches is well past the sample floor and still must not be scored,
	 * because the variance does not cover every patch in the total.
	 */
	static final String HISTORY =
		"{\"Ranarr weed|ULTRACOMPOST\":{\"crop\":\"Ranarr weed\",\"compost\":\"ULTRACOMPOST\","
			+ "\"harvests\":14,\"items\":128,\"predicted\":126.4,\"xp\":1820.0,\"best\":13,"
			+ "\"worst\":6,\"partialItems\":4,\"partialXp\":52.0},"
			+ "\"Ranarr weed|NONE\":{\"crop\":\"Ranarr weed\",\"compost\":\"NONE\","
			+ "\"harvests\":3,\"items\":13,\"predicted\":14.2,\"xp\":390.0,\"best\":6,"
			+ "\"worst\":3},"
			+ "\"Watermelon|ULTRACOMPOST\":{\"crop\":\"Watermelon\",\"compost\":\"ULTRACOMPOST\","
			+ "\"harvests\":8,\"items\":92,\"predicted\":88.8,\"xp\":1160.0,\"best\":15,"
			+ "\"worst\":9}}";

	/**
	 * A history recorded with the spread, and a lucky one: 220 items against a predicted 200.
	 *
	 * <p>Twenty-five patches is past the colour floor, so this is the fixture where the
	 * {@code +/-} column is not only filled in but coloured.
	 */
	static final String SCORED_HISTORY =
		"{\"Ranarr weed|ULTRACOMPOST\":{\"crop\":\"Ranarr weed\",\"compost\":\"ULTRACOMPOST\","
			+ "\"harvests\":25,\"items\":220,\"predicted\":200.0,\"predictedVariance\":87.5,"
			+ "\"variancePatches\":25,\"xp\":3000.0,\"best\":14,\"worst\":5}}";

	private StatsPanels()
	{
	}

	// ---------------------------------------------------------------- fixtures

	/**
	 * A layout store over a map, so a test can watch a collapsed state survive a rebuild.
	 *
	 * <p>Real rather than mocked: a mock answers false to every {@code isOpen} and would report
	 * every card shut, which is the opposite of what the default is.
	 */
	static PanelLayoutStore layoutStore()
	{
		Map<String, Object> stored = new HashMap<>();
		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getConfiguration(anyString(), anyString(), eq(Boolean.class)))
			.thenAnswer(call -> stored.get(call.getArgument(1)));
		// Mockito.<Object>any() rather than any(): ConfigManager has both a String overload and a
		// generic one, and a bare any() infers the String one — which stubs an overload the store
		// never calls, so the answer never fires and everything reads back null.
		Mockito.doAnswer(call -> stored.put(call.getArgument(1), call.getArgument(2)))
			.when(configManager)
			.setConfiguration(anyString(), anyString(), Mockito.<Object>any());
		return construct(PanelLayoutStore.class, configManager);
	}

	static HarvestStatsPanel panel(HarvestStatsStore history, Map<Seed, Integer> bank)
	{
		return panel(history, bank, FARMING_LEVEL);
	}

	static HarvestStatsPanel panel(HarvestStatsStore history, Map<Seed, Integer> bank,
		int farmingLevel)
	{
		return panel(history, bank, farmingLevel, noRuns());
	}

	static HarvestStatsPanel panel(HarvestStatsStore history, Map<Seed, Integer> bank,
		int farmingLevel, HarvestHistory runLog)
	{
		return panel(history, bank, farmingLevel, runLog,
			Mockito.mock(DiseaseStatsStore.class), pricesOf(0));
	}

	static HarvestStatsPanel panel(HarvestStatsStore history, Map<Seed, Integer> bank,
		int farmingLevel, HarvestHistory runLog, DiseaseStatsStore disease)
	{
		return panel(history, bank, farmingLevel, runLog, disease, pricesOf(0));
	}

	/**
	 * A panel over one history and one bank, with every card opened.
	 *
	 * <p>Opened by clicking the carets, not by a back door: the cards are shut by default now, so
	 * a test that walked the tree regardless would be asserting about text nobody can read. See
	 * {@link #openAll}.
	 */
	static HarvestStatsPanel panel(HarvestStatsStore history, Map<Seed, Integer> bank,
		int farmingLevel, HarvestHistory runLog, DiseaseStatsStore disease, ItemPrices items)
	{
		HarvestStatsPanel panel = collapsed(layoutStore(), history, bank, farmingLevel, runLog,
			disease, items);
		openAll(panel);
		return panel;
	}

	/** The same panel as it actually opens: Harvests expanded, the other three shut. */
	static HarvestStatsPanel collapsed(PanelLayoutStore layout, HarvestStatsStore history,
		Map<Seed, Integer> bank, int farmingLevel, HarvestHistory runLog,
		DiseaseStatsStore disease, ItemPrices items)
	{
		SeedInventoryStore seeds = Mockito.mock(SeedInventoryStore.class);
		for (Map.Entry<Seed, Integer> entry : bank.entrySet())
		{
			when(seeds.getOwned(entry.getKey())).thenReturn(entry.getValue());
		}
		when(seeds.getFarmingXp()).thenReturn(Experience.getXpForLevel(farmingLevel));

		AvailabilityProfile availability = Mockito.mock(AvailabilityProfile.class);
		when(availability.getAvailablePatches(Mockito.any()))
			.thenAnswer(call -> patchesOfType(call.getArgument(0)));

		CompostSelectionStore compost = Mockito.mock(CompostSelectionStore.class);
		when(compost.get(Mockito.any(PatchImplementation.class)))
			.thenReturn(CompostTier.ULTRACOMPOST);

		FarmingBonusStore bonuses = Mockito.mock(FarmingBonusStore.class);
		when(bonuses.current()).thenReturn(FarmingBonuses.NONE);

		return new HarvestStatsPanel(layout, history, runLog, disease, seeds, availability,
			compost, bonuses, items);
	}

	/** Every item priced the same, which is enough to tell a figure from its absence. */
	static ItemPrices pricesOf(int each)
	{
		ItemPrices items = Mockito.mock(ItemPrices.class);
		when(items.get(Mockito.anyInt())).thenReturn(each);
		return items;
	}

	/** A plausible mid-level bank: a herb stack, an allotment stack, and one locked crop. */
	static Map<Seed, Integer> fullBank()
	{
		Map<Seed, Integer> owned = new LinkedHashMap<>();
		owned.put(Seed.RANARR, 200);
		owned.put(Seed.GUAM, 80);
		owned.put(Seed.WATERMELON, 150);
		owned.put(Seed.TORSTOL, 40);
		return owned;
	}

	/** A level 1 account that has been given a lot of seeds, which is the path-to-99 case. */
	static Map<Seed, Integer> pathBank()
	{
		Map<Seed, Integer> owned = new LinkedHashMap<>();
		owned.put(Seed.POTATO, 30_000);
		owned.put(Seed.GUAM, 4_000);
		owned.put(Seed.RANARR, 4_000);
		owned.put(Seed.SNAPDRAGON, 2_000);
		owned.put(Seed.TORSTOL, 1_000);
		return owned;
	}

	static Map<Seed, Integer> bank(Seed seed, int count)
	{
		Map<Seed, Integer> owned = new LinkedHashMap<>();
		owned.put(seed, count);
		return owned;
	}

	/**
	 * A run log with nothing in it.
	 *
	 * <p>Stubbed rather than built, because the panel's job here is rendering what the store
	 * returns and {@code HarvestHistoryTest} already owns the clustering.
	 */
	static HarvestHistory noRuns()
	{
		HarvestHistory runs = Mockito.mock(HarvestHistory.class);
		when(runs.getRuns()).thenReturn(Collections.emptyList());
		when(runs.getLevelBands()).thenReturn(Collections.emptyList());
		return runs;
	}

	/** A log with two sittings in it. */
	static HarvestHistory someRuns()
	{
		FarmRun last = farmRun(6, 48, 1_500, 10_000, 10_600);
		FarmRun best = farmRun(14, 121, 4_200, 5_000, 6_100);

		HarvestHistory runs = Mockito.mock(HarvestHistory.class);
		when(runs.getRuns()).thenReturn(Arrays.asList(best, last));
		when(runs.getLastRun()).thenReturn(last);
		when(runs.getBestRun()).thenReturn(best);
		when(runs.getXpPerRun()).thenReturn(2_800.0);
		when(runs.getXpPerDay()).thenReturn(21_000.0);
		when(runs.getActiveXpPerHour()).thenReturn(52_000.0);
		when(runs.getLevelBands()).thenReturn(Arrays.asList(
			new HarvestHistory.LevelBand(30, 40, 260, 268.0),
			new HarvestHistory.LevelBand(70, 55, 470, 461.0)));
		when(runs.getHistogram(Mockito.anyString())).thenReturn(new HarvestHistory.Histogram(
			"Ranarr weed", new int[]{2, 4, 9, 14, 8, 5, 3}, 45));
		return runs;
	}

	/**
	 * A run with a fixed experience figure.
	 *
	 * <p>Set through {@code skillXp} rather than a single {@code xp} field, which was split in two
	 * when the runs stopped being built from harvest records alone: a record only exists where a
	 * patch produced an item, and check-health on a tree — where most farming experience is —
	 * never opens one. {@code FarmRun.getXp} reports the larger of the two sources, so either one
	 * reproduces the figure this fixture wants.
	 */
	private static FarmRun farmRun(int patches, int items, double xp, long from, long to)
	{
		FarmRun run = new FarmRun();
		run.setPatches(patches);
		run.setItems(items);
		run.setSkillXp(xp);
		run.setStartedAt(from);
		run.setEndedAt(to);
		return run;
	}

	/** A disease record with the totals the Checks card is built from. */
	static DiseaseStatsStore diseaseOf(int cycles, int caught, int died, double predicted)
	{
		DiseaseStats entry = new DiseaseStats();
		entry.setCrop("Ranarr weed");
		entry.setCompost("ULTRACOMPOST");
		entry.setCycles(cycles);
		entry.setDiseased(caught);
		entry.setDied(died);
		entry.setPredictedSurvivals(predicted);

		DiseaseStatsStore store = Mockito.mock(DiseaseStatsStore.class);
		when(store.getTotalCycles()).thenReturn(cycles);
		when(store.getTotalDiseased()).thenReturn(caught);
		when(store.getTotalDied()).thenReturn(died);
		when(store.getPredictedSurvivals()).thenReturn(predicted);
		when(store.getAll()).thenReturn(Collections.singletonList(entry));
		return store;
	}

	/** Five of every patch type the fixtures plant into, none of anything else. */
	private static List<FarmPatch> patchesOfType(PatchImplementation type)
	{
		if (type != PatchImplementation.HERB && type != PatchImplementation.ALLOTMENT)
		{
			return Collections.emptyList();
		}
		List<FarmPatch> all = FarmingWorldData.getPatches(type);
		return all.subList(0, Math.min(PATCHES_PER_TYPE, all.size()));
	}

	/** A store loaded from one JSON history, which is the only input the panel has. */
	static HarvestStatsStore storeOf(@Nullable String history) throws Exception
	{
		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration("dooglemaps", "harvestStats"))
			.thenReturn(history);

		HarvestStatsStore store = construct(HarvestStatsStore.class, configManager, new Gson());
		store.load();
		return store;
	}

	static HarvestStatsStore emptyStore()
	{
		try
		{
			return storeOf(null);
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
	}

	// -------------------------------------------------------------- inspection

	/**
	 * Clicks every shut card open.
	 *
	 * <p>The cards are collapsed by default, and {@link #textOf} reports only what is visible —
	 * so without this half the tab's assertions would be asserting about a hidden panel. It walks
	 * for the closed caret and presses it rather than calling into the panel, because the button
	 * is the real affordance and a test-only {@code expandAll()} on the production class would
	 * check nothing a player can do.
	 *
	 * <p>Repeated until a pass finds nothing, since opening a card can reveal another.
	 */
	static void openAll(Container container)
	{
		for (int pass = 0; pass < 10; pass++)
		{
			List<AbstractButton> shut = new ArrayList<>();
			collectCarets(container, shut);
			if (shut.isEmpty())
			{
				return;
			}
			for (AbstractButton caret : shut)
			{
				caret.doClick(0);
			}
		}
	}

	private static void collectCarets(Container container, List<AbstractButton> found)
	{
		for (Component child : container.getComponents())
		{
			if (child instanceof AbstractButton
				&& ((AbstractButton) child).getText() != null
				&& ((AbstractButton) child).getText().startsWith("▸"))
			{
				found.add((AbstractButton) child);
			}
			if (child instanceof Container)
			{
				collectCarets((Container) child, found);
			}
		}
	}

	/** The caret button of one card, found by the name in its label. */
	static AbstractButton caret(Container panel, String title)
	{
		for (AbstractButton button : buttons(panel))
		{
			String text = button.getText();
			if (text != null && text.endsWith(" " + title))
			{
				return button;
			}
		}
		throw new AssertionError("no card headed " + title);
	}

	static List<AbstractButton> buttons(Container container)
	{
		List<AbstractButton> found = new ArrayList<>();
		collectButtons(container, found);
		return found;
	}

	private static void collectButtons(Container container, List<AbstractButton> found)
	{
		for (Component child : container.getComponents())
		{
			if (child instanceof AbstractButton)
			{
				found.add((AbstractButton) child);
			}
			if (child instanceof Container)
			{
				collectButtons((Container) child, found);
			}
		}
	}

	/**
	 * The table a card draws into, found by the name the panel gives it.
	 *
	 * <p>By name because the assertions are about which card a number appears in. Position in the
	 * component tree would say the same thing far more fragilely.
	 */
	static Container tableNamed(Container panel, String name)
	{
		Container found = search(panel, name);
		assertNotNull("no table named " + name, found);
		return found;
	}

	@Nullable
	private static Container search(Container container, String name)
	{
		for (Component child : container.getComponents())
		{
			if (!(child instanceof Container))
			{
				continue;
			}
			if (name.equals(child.getName()))
			{
				return (Container) child;
			}
			Container found = search((Container) child, name);
			if (found != null)
			{
				return found;
			}
		}
		return null;
	}

	/** The crop names of a table's rows, in the order they are drawn. */
	static List<String> cropOrder(Container panel, String table)
	{
		List<String> names = new ArrayList<>();
		for (Container row : rows(tableNamed(panel, table)))
		{
			List<Component> cells = labels(row);
			if (!cells.isEmpty())
			{
				names.add(((JLabel) cells.get(0)).getText());
			}
		}
		// The heading row leads and is not a crop.
		return names.subList(1, names.size());
	}

	/** The cell texts of the one row in {@code table} whose name column reads {@code name}. */
	static List<String> rowFor(Container panel, String table, String name)
	{
		List<String> cells = new ArrayList<>();
		for (Component cell : cellsFor(panel, table, name))
		{
			cells.add(((JLabel) cell).getText());
		}
		return cells;
	}

	/** The same row as its labels, for assertions about colour rather than text. */
	static List<Component> cellsFor(Container panel, String table, String name)
	{
		List<List<Component>> matches = new ArrayList<>();
		for (Container row : rows(tableNamed(panel, table)))
		{
			List<Component> cells = labels(row);
			if (!cells.isEmpty() && name.equals(((JLabel) cells.get(0)).getText()))
			{
				matches.add(cells);
			}
		}
		assertEquals("expected exactly one " + name + " row in " + table, 1, matches.size());
		return matches.get(0);
	}

	static List<List<String>> rowsNamed(Container panel, String name)
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

	@Nullable
	static String tooltipFor(Container panel, String table, String name)
	{
		for (Container row : rows(tableNamed(panel, table)))
		{
			List<Component> cells = labels(row);
			if (!cells.isEmpty() && name.equals(((JLabel) cells.get(0)).getText())
				&& row instanceof JComponent)
			{
				return ((JComponent) row).getToolTipText();
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
	static List<Container> rows(Container panel)
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
	static List<Component> labels(Container row)
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

	/**
	 * Every piece of text the panel shows, for assertions about prose rather than cells.
	 *
	 * <p><b>Visible components only.</b> The tab keeps every card built and hides the ones with
	 * nothing to say, so walking the tree regardless of visibility reports text nobody can read —
	 * which made "the empty message is not shown" pass on a panel that was showing a full
	 * projection.
	 *
	 * <p><b>Tooltips included.</b> The redesign moved most of the tab's prose onto the header and
	 * column it explains, which is a change of <i>where</i> the panel says something rather than
	 * of <i>whether</i> — and this helper's job has always been "text the panel shows".
	 */
	static String textOf(Container container)
	{
		StringBuilder text = new StringBuilder();
		for (Component child : container.getComponents())
		{
			if (!child.isVisible())
			{
				continue;
			}
			if (child instanceof JComponent)
			{
				String tooltip = ((JComponent) child).getToolTipText();
				if (tooltip != null)
				{
					text.append(tooltip).append('\n');
				}
			}
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

	/**
	 * Lays a component out for real, which nothing does until it has a peer or is told to.
	 *
	 * <p>Only {@code doLayout}, so every layout manager gets to place its own children. Forcing
	 * each child to its preferred size instead produces a picture of a panel nobody will ever
	 * see — it was how this first reported the table as zero pixels wide.
	 */
	static void layout(Container container)
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
}
