package com.dooglemaps.ui;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.timer.Confidence;
import com.dooglemaps.timer.GrowthTimer;
import com.dooglemaps.timer.PatchProjection;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.swing.BorderFactory;
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
 * The sidebar: every patch you use, grouped the way the Geomancy interface groups them.
 *
 * <p>Patch-type tabs across the top, one row per location under the active tab. It is a
 * view over the cache, so it is just as complete whether that cache was filled a patch at
 * a time by walking around or all at once by a Geomancy cast.
 */
public class DoogleMapsPanel extends PluginPanel
{
	private static final int TAB_ICON_SIZE = 20;

	/** Tabs per row. Chosen so a row fits the sidebar's 225px without clipping. */
	private static final int TABS_PER_ROW = 7;

	private final PatchStateStore stateStore;
	private final AvailabilityProfile availability;
	private final GrowthTimer growthTimer;
	private final ItemManager itemManager;
	private final DoogleMapsConfig config;

	private final Map<PatchImplementation, PatchTypePanel> tabPanels = new EnumMap<>(PatchImplementation.class);
	private final JLabel summary = new JLabel();

	private final JPanel display = new JPanel();
	private final MaterialTabGroup tabGroup = new MaterialTabGroup(display);

	@Inject
	private DoogleMapsPanel(PatchStateStore stateStore, AvailabilityProfile availability,
		GrowthTimer growthTimer, ItemManager itemManager, DoogleMapsConfig config)
	{
		// Wrapped, so a long list of patches scrolls rather than being clipped.
		super(true);

		this.stateStore = stateStore;
		this.availability = availability;
		this.growthTimer = growthTimer;
		this.itemManager = itemManager;
		this.config = config;

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
		}

		JPanel header = new JPanel(new BorderLayout(0, 4));
		header.setBackground(getBackground());
		header.add(summary, BorderLayout.NORTH);
		header.add(tabGroup, BorderLayout.CENTER);

		add(header, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);

		buildTabs();
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

		for (PatchImplementation type : PatchImplementation.values())
		{
			PatchTypePanel panel = new PatchTypePanel(
				type, stateStore, availability, growthTimer, itemManager, config);
			tabPanels.put(type, panel);

			MaterialTab tab = new MaterialTab(new ImageIcon(), tabGroup, panel);
			// MaterialTab is a JLabel, so the sprite can fill itself in once it loads.
			Icons.setScaled(tab, itemManager.getImage(type.getItemID()), TAB_ICON_SIZE);
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

	/** Repaints the whole panel from the cache. Safe to call from any thread. */
	public void refresh()
	{
		SwingUtilities.invokeLater(() ->
		{
			for (PatchTypePanel panel : tabPanels.values())
			{
				panel.refresh();
			}
			summary.setText(buildSummary());
			revalidate();
			repaint();
		});
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
