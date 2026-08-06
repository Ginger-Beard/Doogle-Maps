package com.dooglemaps.ui;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.state.PlantingGroups;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.bank.RunLoadout;
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
import java.util.LinkedHashMap;
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
import lombok.extern.slf4j.Slf4j;
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
 *       <p>Planning a run and following it sits at the bottom of this same page, because
 *       choosing what to run is done while looking at what is ready.</li>
 *   <li><b>Stats</b> — what your patches have actually given you.</li>
 * </ul>
 *
 * <p>These were previously stacked in one scrolling column, which meant the run controls sat
 * below a hundred-odd patch rows and the history below those. Splitting them also settles
 * where a section heading goes: the tab is the heading.
 */
@Slf4j
public class DoogleMapsPanel extends PluginPanel
{
	private static final int TAB_ICON_SIZE = 20;

	/** Tabs per row. Chosen so a row fits the sidebar's 225px without clipping. */
	private static final int TABS_PER_ROW = 7;

	/** What the two top-level tabs are called. */
	private static final String ALMANAC_TAB = "Almanac";
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
	private final PanelLayoutStore layout;
	private final PlantingGroups groups;
	private final com.dooglemaps.state.ProtectionSelectionStore protection;
	private final com.dooglemaps.bank.BankContents bankContents;
	private final com.dooglemaps.guide.CarriedItems carriedItems;
	private final com.dooglemaps.data.ItemNames itemNames;
	private final com.dooglemaps.state.ContractState contracts;
	private final RunPanel runPanel;
	private final HarvestStatsPanel statsPanel;

	/**
	 * One panel per planting <b>group</b>, not per patch type.
	 *
	 * <p>Keyed by the group's storage key so a split type has two entries — the ordinary herbs and
	 * the protected ones — each with its own seed list and compost choice. Types that are not
	 * split have exactly one, under the bare type name, which is what they always had.
	 */
	private final Map<String, PatchTypePanel> tabPanels = new LinkedHashMap<>();

	/** Tabs whose contents no longer match the cache; redrawn when next shown. */
	private final Set<String> stale = new java.util.LinkedHashSet<>();

	private String selected;
	private final JLabel summary = new JLabel();

	private final JPanel display = new JPanel();

	/**
	 * The patch-type strip. Replaced wholesale on a rebuild rather than emptied.
	 *
	 * <p>{@code MaterialTabGroup} keeps its own list of the tabs it has been given and offers no
	 * way to take one back — {@code removeAll} is {@code Container}'s, so it takes the tabs off
	 * the screen and leaves every one of them in that list. The strip is rebuilt on every login
	 * and on every settings toggle that changes which tabs exist, so emptying it that way meant
	 * the group accumulating a fresh set each time, each holding a whole {@code PatchTypePanel}
	 * alive. A new group is the only way to actually be rid of them.
	 */
	private MaterialTabGroup tabGroup = new MaterialTabGroup(display);

	/** Holds {@link #tabGroup}, so a replacement can be swapped in where the old one sat. */
	private final JPanel tabStrip = new JPanel(new BorderLayout());

	/** The Almanac / Stats strip, and the pane it swaps between them. */
	private final JPanel sectionDisplay = new JPanel();
	private final MaterialTabGroup sectionTabs = new MaterialTabGroup(sectionDisplay);

	@Inject
	private DoogleMapsPanel(PatchStateStore stateStore, AvailabilityProfile availability,
		GrowthTimer growthTimer, ItemManager itemManager, DoogleMapsConfig config,
		PlantableResolver resolver, SeedInventoryStore seeds, SeedSelectionStore selection,
		RunPlanner runPlanner, FarmingBonusStore bonuses, RunTypeStore runTypes,
		CompostSelectionStore compost, HarvestStatsStore harvestStats, RunLoadout loadout,
		PanelLayoutStore layout, PlantingGroups groups,
		com.dooglemaps.state.ProtectionSelectionStore protection,
		com.dooglemaps.bank.BankContents bankContents,
		com.dooglemaps.guide.CarriedItems carriedItems, com.dooglemaps.data.ItemNames itemNames,
		com.dooglemaps.state.ContractState contracts)
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
		this.layout = layout;
		this.groups = groups;
		this.protection = protection;
		this.bankContents = bankContents;
		this.carriedItems = carriedItems;
		this.itemNames = itemNames;
		this.contracts = contracts;
		this.runPanel = new RunPanel(layout, groups, protection, bankContents, carriedItems, runPlanner, loadout, availability, selection, seeds, runTypes,
			bonuses, compost, config);
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
			fillTheViewport();
		}

		sectionDisplay.setLayout(new BorderLayout());
		sectionDisplay.setBackground(ColorScheme.DARK_GRAY_COLOR);

		buildTabs();
		buildSectionTabs();

		add(sectionTabs, BorderLayout.NORTH);
		add(sectionDisplay, BorderLayout.CENTER);
	}

	/**
	 * Lets the panel use the sidebar's full height rather than only its content's.
	 *
	 * <h2>Why anything is needed</h2>
	 *
	 * {@code PluginPanel} puts itself into a {@code BorderLayout} wrapper at <b>NORTH</b> and
	 * scrolls that. NORTH gives a component exactly its preferred height, so the panel is always
	 * as tall as its contents and never taller — and "the bottom of the panel" is therefore
	 * wherever the contents happen to end, not the bottom of the sidebar. No amount of layout
	 * inside the panel can anchor anything to the foot of a container that has no foot.
	 *
	 * <p>Moving it to CENTER hands it whatever height the viewport has, which is what
	 * {@link PageLayout} needs to push the run controls down. When the contents are taller than
	 * the viewport, CENTER still yields the preferred height and it scrolls exactly as before.
	 *
	 * <p>Guarded rather than assumed. This reaches into another class's construction, so every
	 * step is checked and doing nothing leaves the old top-anchored behaviour — a layout that is
	 * merely less pretty, rather than a panel that fails to build.
	 */
	private void fillTheViewport()
	{
		java.awt.Component view = getScrollPane().getViewport().getView();
		if (!(view instanceof JPanel) || !(((JPanel) view).getLayout() instanceof BorderLayout))
		{
			return;
		}

		JPanel wrapper = (JPanel) view;
		if (getParent() != wrapper)
		{
			return;
		}

		wrapper.remove(this);
		wrapper.add(this, BorderLayout.CENTER);
	}

	/**
	 * The Almanac page: the summary line, the patch-type strip, the rows, and the run below them.
	 *
	 * <p><b>Doogle Maps sits at the bottom of this page rather than in a tab of its own.</b> It
	 * had a tab briefly, and the reasoning was that a run is a separate activity from reading the
	 * almanac and that a top-level tab makes a natural heading for it. Play said otherwise:
	 * deciding what to run is done <i>while</i> looking at what is ready, and putting the two on
	 * separate pages meant switching back and forth to make one decision.
	 *
	 * <p>The earlier objection to stacking them was real but is about a different arrangement —
	 * the run controls used to sit below a hundred-odd patch rows <i>and</i> the whole harvest
	 * history. Stats keeps its own tab, so what is below the rows now is just the run.
	 *
	 * <p>No heading over it. One was tried on the reasoning that a section on a shared page needs
	 * naming, and it was wrong twice over: the Start run button already announces what the section
	 * is, and a labelled rule between the patch list and the run implied a boundary that does not
	 * matter to anyone — you scroll past it either way.
	 */
	private JPanel buildAlmanac()
	{
		JPanel header = new JPanel(new BorderLayout(0, 4));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.add(summary, BorderLayout.NORTH);
		tabStrip.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tabStrip.add(tabGroup, BorderLayout.CENTER);
		header.add(tabStrip, BorderLayout.CENTER);

		JPanel top = new JPanel(new BorderLayout(0, 4));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.add(header, BorderLayout.NORTH);
		top.add(display, BorderLayout.CENTER);

		// The run sits at the bottom of the sidebar rather than directly under the rows. On a tab
		// with three patches the rows used to end a third of the way down and the run controls sat
		// with them, leaving two thirds of the panel empty beneath — so the whole page moved up and
		// down as you clicked between a three-patch tab and a twenty-patch one.
		//
		// The glue takes whatever height is spare and gives it back when there is none, so the run
		// is pinned to the bottom when everything fits and pushed off it into the scroll when it
		// does not. See PageLayout for why this cannot simply be BorderLayout.SOUTH.
		JPanel almanac = new PageLayout(top, runPanel);
		almanac.setBackground(ColorScheme.DARK_GRAY_COLOR);
		return almanac;
	}

	/**
	 * The two top-level tabs.
	 *
	 * <p>Text rather than icons, because unlike the patch types these are not things with a
	 * sprite.
	 *
	 * <p>Two rather than three: the run folded back into the Almanac page beneath the patch rows,
	 * because choosing what to run is done while looking at what is ready. The section carries no
	 * heading of its own: the Start run button says what it is.
	 */
	private void buildSectionTabs()
	{
		// A horizontal box rather than a grid, because the names are not the same length and a
		// grid gives every tab the width of the longest. See sizeSectionTabs.
		sectionTabs.setLayout(new BoxLayout(sectionTabs, BoxLayout.X_AXIS));
		sectionTabs.setBackground(ColorScheme.DARK_GRAY_COLOR);

		List<MaterialTab> tabs = new ArrayList<>();
		tabs.add(sectionTab(ALMANAC_TAB, buildAlmanac(),
			"Every patch you use, what is growing in it, and your run"));
		// "Nerd." The tooltip described the tab, which the tab's own name already does. This one
		// tells you what you are in for.
		tabs.add(sectionTab(STATS_TAB, statsPanel, "Nerd."));

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
	 * <p>Equal shares fit, but spend the same width on "Stats" as on a much longer name. Sharing
	 * the width by what each name actually needs fits them all, and — unlike hardcoded numbers —
	 * survives both a rename, which is a live prospect for this panel, and a tab being added or
	 * removed, which has now happened twice.
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
	 * Rebuilds the tab strip because something changed which tabs should exist.
	 *
	 * <p>Public so the plugin can call it when the protected-patch unlocks are first detected,
	 * which happens after the panel has already been built.
	 */
	public void structureChanged()
	{
		SwingUtilities.invokeLater(this::rebuildTabs);
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
		// A new group rather than an emptied one; see the field.
		tabStrip.remove(tabGroup);
		tabGroup = new MaterialTabGroup(display);
		tabStrip.add(tabGroup, BorderLayout.CENTER);

		tabPanels.clear();
		stale.clear();
		selected = null;
		buildTabs();
		// The run list offers a line per planting group too, so it goes stale for exactly the same
		// reasons the strip does. Rebuilt together, because a tab with no matching run line is a
		// category you can see and cannot run.
		runPanel.structureChanged();
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

		// One tab per group rather than per type. Ordinarily that is the same thing; a split type
		// gets two, with the protected patches first because that is the shorter list and the more
		// consequential choice.
		//
		// The contract is lifted out of its type's position to the very front of the strip. It is
		// not a patch type — it is a job that moves from cactus to bushes to herbs as it is
		// reassigned — so leaving it filed under whichever type it currently wants would have the
		// tab appearing in a different place each week. Pinned first it is always where you left
		// it, and first rather than last because it is the highest-value thing in a run.
		List<PlantingGroup> ordered = new ArrayList<>();
		List<PlantingGroup> contracts = new ArrayList<>();
		for (PatchImplementation type : PatchTabs.enabled(config))
		{
			for (PlantingGroup group : groups.groupsFor(type))
			{
				(group.isContract() ? contracts : ordered).add(group);
			}
		}
		ordered.addAll(0, contracts);

		for (PlantingGroup group : ordered)
		{
			MaterialTab tab = buildTabFor(group);
			if (first == null)
			{
				first = tab;
			}
		}

		if (first != null)
		{
			tabGroup.select(first);
		}

		// Said outright, because when the protected tab is missing there is no way to tell from
		// the sidebar which half of the condition failed: the setting being off looks exactly
		// like the unlocks not having been read yet, and both look like a bug in the strip.
		log.info("Built {} patch tabs; protected herbs split={} (setting={}, qualifying patches={})",
			tabPanels.size(), groups.isSplit(PatchImplementation.HERB),
			config.separateProtectedHerbs(), groups.countProtected(PatchImplementation.HERB));
	}

	private MaterialTab buildTabFor(PlantingGroup group)
	{
		PatchImplementation type = group.getType();
		PatchTypePanel panel = new PatchTypePanel(
			layout, groups, group, stateStore, availability, growthTimer, itemManager, config,
			resolver, seeds, selection, bonuses, compost, protection, bankContents, carriedItems,
			itemNames, contracts);
		tabPanels.put(group.getKey(), panel);

		MaterialTab tab = new MaterialTab(new ImageIcon(), tabGroup, panel);
		tab.setOnSelectEvent(() ->
		{
			selected = group.getKey();
			// Collapsed sections are shared across the tabs, and each tab is a separate panel
			// built at a different time. Sharing the stored key makes them agree on the next
			// read; adopting it here is what makes an already-built tab notice.
			panel.applyLayout();
			refreshSelected();
			return true;
		});
		// Guildmaster Jane's face for the contract, and a crop sprite for everything else.
		//
		// She is the right icon precisely because the contract has no stable crop: a tab that
		// showed a cactus this week and a bush the next would look like the patch-type tabs it
		// sits beside while behaving nothing like them. The job is hers, and her face does not
		// move. It also settles the badging problem outright — a face among crop sprites needs no
		// mark in the corner to be told apart.
		if (group.isContract())
		{
			javax.swing.ImageIcon face = FarmerIcon.of(
				com.dooglemaps.state.ContractState.GUILDMASTER_JANE, TAB_ICON_SIZE);
			if (face != null)
			{
				tab.setIcon(face);
			}
			else
			{
				// Her portrait is bundled, so this should not happen — but a missing resource must
				// not cost the tab its icon entirely, and the diamond badge still says "contract".
				Icons.setScaled(tab, type.getItemID(), itemManager.getImage(type.getItemID()),
					TAB_ICON_SIZE, badgeFor(group));
			}
		}
		else
		{
			// MaterialTab is a JLabel, so the sprite can fill itself in once it loads.
			Icons.setScaled(tab, type.getItemID(), itemManager.getImage(type.getItemID()),
				TAB_ICON_SIZE, badgeFor(group));
		}
		// The tooltip is the only thing telling the two herb tabs apart — they necessarily share
		// an icon, since the game has one herb-patch sprite and inventing a second would be
		// making up iconography for a distinction the game does not draw.
		tab.setToolTipText(tooltipFor(group));
		tab.setPreferredSize(new Dimension(TAB_ICON_SIZE + 6, TAB_ICON_SIZE + 6));
		tabGroup.addTab(tab);
		return tab;
	}

	/** Which mark, if any, tells this tab apart from its type's other tabs. */
	private static Icons.Badge badgeFor(PlantingGroup group)
	{
		if (group.isContract())
		{
			return Icons.Badge.CONTRACT;
		}
		return group.isProtectedOnly() ? Icons.Badge.PROTECTED : Icons.Badge.NONE;
	}

	/**
	 * What hovering the tab says.
	 *
	 * <p>The contract tab names the crop as well as the group, because unlike every other tab it
	 * is <i>about</i> one specific crop and that is the thing worth knowing at a glance — "Herb
	 * (contract)" tells you a contract exists but not whether you have the seed for it.
	 */
	private String tooltipFor(PlantingGroup group)
	{
		if (!group.isContract())
		{
			return group.getDisplayName();
		}
		// The run line's wording, not the group's. "Cactus (contract)" names the patch type, which
		// is the one thing about a contract that is not the point — the crop after the dash
		// already says what it wants, so the type was saying it twice and burying the job.
		com.dooglemaps.data.Produce crop = contracts.getContract();
		String job = com.dooglemaps.data.RunOption.full(group).getLabel();
		return crop == null ? job : job + " - " + crop.getName();
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
