package com.dooglemaps.ui;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.bank.RunLoadout;
import com.dooglemaps.guide.GuideTracker;
import com.dooglemaps.route.RunPlanner;
import com.dooglemaps.state.CompostSelectionStore;
import com.dooglemaps.state.FarmingBonusStore;
import com.dooglemaps.state.PlantableResolver;
import com.dooglemaps.state.RunTypeStore;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.state.SeedSelectionStore;
import com.dooglemaps.timer.Confidence;
import com.dooglemaps.timer.GrowthTimer;
import com.dooglemaps.timer.PatchProjection;
import com.dooglemaps.validate.HarvestStatsStore;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;

/**
 * The sidebar, in three parts.
 *
 * <p>Top-level tabs across the top, because the panel does three different jobs and only one
 * of them is wanted at a time:
 *
 * <ul>
 *   <li><b>Almanac</b> — every patch you use, grouped the way the Geomancy interface groups
 *       them, with its own tab strip per patch type. A view over the cache, so it is just as
 *       complete whether that cache was filled a patch at a time by walking around or all at
 *       once by a Geomancy cast.</li>
 *   <li><b>Doogle Maps</b> — planning a run and following it. The routing half, which is
 *       where the name always fitted best.</li>
 *   <li><b>Stats</b> — what your patches have actually given you.</li>
 * </ul>
 *
 * <p>These were previously stacked in one scrolling column, which meant the run controls sat
 * below a hundred-odd patch rows and the history below those. Splitting them also settles
 * where a section heading goes: the tab is the heading.
 */
public class DoogleMapsPanel extends PluginPanel
{
	private static final int TAB_ICON_SIZE = 20;

	/** Tabs per row. Chosen so a row fits the sidebar's 225px without clipping. */
	private static final int TABS_PER_ROW = 7;

	/** What the three top-level tabs are called. */
	private static final String ALMANAC_TAB = "Almanac";
	private static final String RUN_TAB = "Doogle Maps";
	private static final String STATS_TAB = "Stats";

	/** The panel's own left and right border, which the tab strip does not get to use. */
	private static final int SECTION_TAB_MARGIN = 12;

	private final PatchStateStore stateStore;
	private final AvailabilityProfile availability;
	private final GrowthTimer growthTimer;
	private final ItemManager itemManager;
	private final DoogleMapsConfig config;
	private final PlantableResolver resolver;
	private final SeedInventoryStore seeds;
	private final SeedSelectionStore selection;
	private final FarmingBonusStore bonuses;
	private final CompostSelectionStore compost;
	private final RunPanel runPanel;
	private final HarvestStatsPanel statsPanel;

	private final Map<PatchImplementation, PatchTypePanel> tabPanels = new EnumMap<>(PatchImplementation.class);

	/** Tabs whose contents no longer match the cache; redrawn when next shown. */
	private final Set<PatchImplementation> stale = EnumSet.noneOf(PatchImplementation.class);

	private PatchImplementation selected;
	private final JLabel summary = new JLabel();

	private final JPanel display = new JPanel();
	private final MaterialTabGroup tabGroup = new MaterialTabGroup(display);

	/** The Almanac / Doogle Maps / Stats strip, and the pane it swaps between them. */
	private final JPanel sectionDisplay = new JPanel();
	private final MaterialTabGroup sectionTabs = new MaterialTabGroup(sectionDisplay);

	@Inject
	private DoogleMapsPanel(PatchStateStore stateStore, AvailabilityProfile availability,
		GrowthTimer growthTimer, ItemManager itemManager, DoogleMapsConfig config,
		PlantableResolver resolver, SeedInventoryStore seeds, SeedSelectionStore selection,
		RunPlanner runPlanner, FarmingBonusStore bonuses, RunTypeStore runTypes,
		CompostSelectionStore compost, HarvestStatsStore harvestStats, GuideTracker guide, RunLoadout loadout)
	{
		// Wrapped, so a long list of patches scrolls rather than being clipped.
		super(true);

		this.stateStore = stateStore;
		this.availability = availability;
		this.growthTimer = growthTimer;
		this.itemManager = itemManager;
		this.config = config;
		this.resolver = resolver;
		this.seeds = seeds;
		this.selection = selection;
		this.bonuses = bonuses;
		this.compost = compost;
		this.runPanel = new RunPanel(runPlanner, guide, loadout, availability, selection, seeds, runTypes,
			bonuses, compost);
		this.statsPanel = new HarvestStatsPanel(harvestStats);

		setLayout(new BorderLayout(0, 4));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

		summary.setFont(FontManager.getRunescapeSmallFont());
		summary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		summary.setHorizontalAlignment(SwingConstants.CENTER);
		summary.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));

		display.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// The scroll pane PluginPanel wraps us in spans the whole sidebar, so anything it
		// paints around its edge reads as a border around the entire panel. Strip its
		// border and viewport border outright, and stop it taking focus - a focusable
		// control in a side panel also swallows keypresses meant for the game.
		if (getScrollPane() != null)
		{
			getScrollPane().setBorder(BorderFactory.createEmptyBorder());
			getScrollPane().setViewportBorder(null);
			getScrollPane().setBackground(ColorScheme.DARK_GRAY_COLOR);
			getScrollPane().setFocusable(false);
			getScrollPane().getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
			getScrollPane().getViewport().setFocusable(false);

			// Painted by hand rather than themed. Styling it the way core's Time Tracking
			// panel does was not enough: it still came out in the default blue-and-white
			// Metal colours, the same way the checkboxes did, and it scrolled a pixel per
			// wheel notch. See DarkScrollBarUI.
			DarkScrollBarUI.install(getScrollPane().getVerticalScrollBar());
		}

		sectionDisplay.setLayout(new BorderLayout());
		sectionDisplay.setBackground(ColorScheme.DARK_GRAY_COLOR);

		buildTabs();
		buildSectionTabs();

		add(sectionTabs, BorderLayout.NORTH);
		add(sectionDisplay, BorderLayout.CENTER);
	}

	/**
	 * The Almanac page: the summary line, the patch-type strip, and the active tab's rows.
	 *
	 * <p>Everything that was in the sidebar before the run controls, unchanged and in the same
	 * order — this is the page people spend their time on.
	 */
	private JPanel buildAlmanac()
	{
		JPanel header = new JPanel(new BorderLayout(0, 4));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.add(summary, BorderLayout.NORTH);
		header.add(tabGroup, BorderLayout.CENTER);

		JPanel almanac = new JPanel(new BorderLayout(0, 4));
		almanac.setBackground(ColorScheme.DARK_GRAY_COLOR);
		almanac.add(header, BorderLayout.NORTH);
		almanac.add(display, BorderLayout.CENTER);
		return almanac;
	}

	/**
	 * The three top-level tabs.
	 *
	 * <p>Text rather than icons, because unlike the patch types these are not things with a
	 * sprite — and "Doogle Maps" in particular is a name, which is the whole reason it earns
	 * a tab of its own.
	 */
	private void buildSectionTabs()
	{
		// A horizontal box rather than a grid, because the three names are not the same
		// length and a grid gives every tab the width of the longest — three times "Doogle
		// Maps" is 253px of a 225px sidebar. See sizeSectionTabs.
		sectionTabs.setLayout(new BoxLayout(sectionTabs, BoxLayout.X_AXIS));
		sectionTabs.setBackground(ColorScheme.DARK_GRAY_COLOR);

		List<MaterialTab> tabs = new ArrayList<>();
		tabs.add(sectionTab(ALMANAC_TAB, buildAlmanac(),
			"Every patch you use, and what is growing in it"));
		tabs.add(sectionTab(RUN_TAB, runPanel, "Plan a farm run, then follow it"));
		tabs.add(sectionTab(STATS_TAB, statsPanel,
			"What your patches have actually given you"));

		sizeSectionTabs(tabs);
		sectionTabs.select(tabs.get(0));
	}

	private MaterialTab sectionTab(String name, JPanel content, String tooltip)
	{
		MaterialTab tab = new MaterialTab(name, sectionTabs, content);
		tab.setFont(FontManager.getRunescapeSmallFont());
		tab.setToolTipText(tooltip);
		tab.setHorizontalAlignment(SwingConstants.CENTER);
		// MaterialTab pads generously, which is right for one tab and too much for a strip.
		tab.setBorder(BorderFactory.createEmptyBorder(5, 2, 5, 2));
		sectionTabs.addTab(tab);
		return tab;
	}

	/**
	 * Divides the sidebar between the tabs in proportion to their names.
	 *
	 * <p>Equal thirds fit, but clip "Doogle Maps" to "Doogle M..." while leaving "Stats" with
	 * space going spare. Sharing the width by what each name actually needs fits all three,
	 * and — unlike three hardcoded numbers — survives a rename, which is a live prospect for
	 * this panel.
	 *
	 * <p>Only ever scales down. If the names get shorter the tabs keep their natural size
	 * rather than stretching to fill, which is how a tab strip is expected to look.
	 */
	private static void sizeSectionTabs(List<MaterialTab> tabs)
	{
		int available = PANEL_WIDTH - SECTION_TAB_MARGIN;

		int natural = 0;
		int height = 0;
		for (MaterialTab tab : tabs)
		{
			natural += tab.getPreferredSize().width;
			height = Math.max(height, tab.getPreferredSize().height);
		}

		if (natural <= available)
		{
			return;
		}

		int used = 0;
		for (int i = 0; i < tabs.size(); i++)
		{
			MaterialTab tab = tabs.get(i);
			// The last tab takes whatever rounding left over, so the strip lands exactly on
			// the width rather than a pixel or two either side of it.
			int width = i == tabs.size() - 1
				? available - used
				: (int) Math.floor(tab.getPreferredSize().width * (available / (double) natural));
			used += width;

			Dimension size = new Dimension(width, height);
			tab.setPreferredSize(size);
			tab.setMinimumSize(size);
			tab.setMaximumSize(size);
		}
	}

	/**
	 * Reacts to a settings change, rebuilding the tab strip if the change needs it.
	 *
	 * <p>Only the patch-type toggles do. Everything else in the settings changes what a tab
	 * draws, not which tabs exist, and is covered by the ordinary refresh.
	 */
	public void configChanged(@Nullable String key)
	{
		if (key != null && PatchTabs.isTabVisibilityKey(key))
		{
			SwingUtilities.invokeLater(this::rebuildTabs);
		}
	}

	/**
	 * Rebuilds the tab strip after the enabled types change.
	 *
	 * <p>Tabs are built once at construction, so switching a type off in the settings has to
	 * throw the strip away and start again — there is no way to remove one from a
	 * {@code MaterialTabGroup}.
	 */
	void rebuildTabs()
	{
		tabGroup.removeAll();
		tabPanels.clear();
		stale.clear();
		selected = null;
		buildTabs();
		revalidate();
		repaint();
	}

	private void buildTabs()
	{
		// MaterialTabGroup ships with a FlowLayout, whose preferred height only ever
		// accounts for a single row. With more tabs than fit across the sidebar it still
		// wraps when painted, but the parent has already sized it for one row, so every
		// row after the first is clipped away. A GridLayout with a fixed column count
		// reports an honest height for however many rows the tabs need.
		tabGroup.setLayout(new GridLayout(0, TABS_PER_ROW, 2, 2));

		MaterialTab first = null;

		for (PatchImplementation type : PatchTabs.enabled(config))
		{
			PatchTypePanel panel = new PatchTypePanel(
				type, stateStore, availability, growthTimer, itemManager, config, resolver, seeds,
				selection, bonuses, compost);
			tabPanels.put(type, panel);

			MaterialTab tab = new MaterialTab(new ImageIcon(), tabGroup, panel);
			tab.setOnSelectEvent(() ->
			{
				selected = type;
				refreshSelected();
				return true;
			});
			// MaterialTab is a JLabel, so the sprite can fill itself in once it loads.
			Icons.setScaled(tab, type.getItemID(), itemManager.getImage(type.getItemID()), TAB_ICON_SIZE);
			tab.setToolTipText(type.getDisplayName());
			tab.setPreferredSize(new Dimension(TAB_ICON_SIZE + 6, TAB_ICON_SIZE + 6));
			tabGroup.addTab(tab);

			if (first == null)
			{
				first = tab;
			}
		}

		if (first != null)
		{
			tabGroup.select(first);
		}
	}

	/**
	 * Logs every component in the panel that could paint a light outline.
	 *
	 * <p>Not called anywhere by default. Wire it into {@code startUp} when chasing a
	 * rendering oddity: the culprit is usually a component the plugin never created — a
	 * scroll pane, a viewport, a look-and-feel border — and this names it outright rather
	 * than leaving it to be inferred from a screenshot.
	 */
	@SuppressWarnings("unused")
	public void logDiagnostics()
	{
		SwingUtilities.invokeLater(() -> PanelDiagnostics.report(getWrappedPanel()));
	}

	/**
	 * Repaints the panel from the cache. Safe to call from any thread.
	 *
	 * <p>Only the visible tab is rebuilt. Rebuilding all 23 meant throwing away and
	 * recreating several hundred Swing components every time anything changed — and the
	 * panel refreshes on a timer, so that was happening three times a minute whether or not
	 * the sidebar was even open. The others are marked stale and catch up when selected.
	 */
	public void refresh()
	{
		SwingUtilities.invokeLater(() ->
		{
			stale.addAll(tabPanels.keySet());
			refreshSelected();
			runPanel.refresh();
			statsPanel.refresh();
			summary.setText(buildSummary());
			revalidate();
			repaint();
		});
	}

	/** Rebuilds the visible tab if anything has changed since it was last drawn. */
	private void refreshSelected()
	{
		if (selected == null || !stale.remove(selected))
		{
			return;
		}
		tabPanels.get(selected).refresh();
	}

	private String buildSummary()
	{
		List<PatchProjection> projections = projectAvailable();
		if (projections.isEmpty())
		{
			return "Nothing tracked yet";
		}

		int ready = 0;
		int problems = 0;
		long soonest = Long.MAX_VALUE;

		for (PatchProjection projection : projections)
		{
			if (projection.isEmpty())
			{
				continue;
			}
			if (projection.getConfidence() == Confidence.NEEDS_ACTION)
			{
				problems++;
			}
			else if (projection.isReady())
			{
				ready++;
			}
			else if (projection.getDoneEstimate() > 0)
			{
				soonest = Math.min(soonest, projection.getDoneEstimate());
			}
		}

		StringBuilder text = new StringBuilder("<html><center>");
		text.append(ready).append(ready == 1 ? " patch ready" : " patches ready");
		if (problems > 0)
		{
			text.append(", ").append(problems).append(" need attention");
		}
		if (soonest != Long.MAX_VALUE)
		{
			long remaining = soonest - System.currentTimeMillis() / 1000L;
			if (remaining > 0)
			{
				text.append("<br>next in ").append(TimeFormat.duration(remaining));
			}
		}
		return text.append("</center></html>").toString();
	}

	/** Projections for every patch this account uses, skipping ones never seen. */
	public List<PatchProjection> projectAvailable()
	{
		List<PatchProjection> projections = new ArrayList<>();
		for (FarmPatch patch : availability.getAllAvailablePatches())
		{
			PatchProjection projection = growthTimer.project(patch, stateStore.get(patch));
			if (projection != null)
			{
				projections.add(projection);
			}
		}
		return projections;
	}
}
