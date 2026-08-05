package com.dooglemaps.ui;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.state.PatchSnapshot;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.CompostSelectionStore;
import com.dooglemaps.state.FarmingBonusStore;
import com.dooglemaps.state.PlantableResolver;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.state.SeedSelectionStore;
import com.dooglemaps.timer.Confidence;
import com.dooglemaps.timer.GrowthTimer;
import com.dooglemaps.timer.PatchProjection;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * One tab's worth of the overview: every available patch it is responsible for.
 *
 * <p>Usually that is a single patch type, and the tab, the type and the seed list are the
 * same thing. The compost bins are the exception — see {@link PatchTabs}.
 */
class PatchTypePanel extends JPanel
{
	private final PatchImplementation type;
	/** The implementations this tab covers, {@code type} first. */
	private final List<PatchImplementation> members;
	private final PatchStateStore stateStore;
	private final AvailabilityProfile availability;
	private final GrowthTimer growthTimer;
	private final ItemManager itemManager;
	private final DoogleMapsConfig config;
	private final SeedInventoryStore seeds;
	private final FarmingBonusStore bonuses;
	private final SeedSelectorPanel seedSelector;

	/** A step lighter than ColorScheme.DARK_GRAY_COLOR, which the sidebar already uses. */
	private static final java.awt.Color TOGGLE_BACKGROUND = new java.awt.Color(0x3A, 0x3A, 0x3A);

	private final JPanel rowContainer = new JPanel();
	private final JPanel patchToggles = new JPanel();
	private final WrappedText emptyMessage = new WrappedText();
	private final JButton toggleSetup = new JButton();

	private final Map<String, PatchRow> rows = new HashMap<>();
	private final Map<String, JCheckBox> toggleBoxes = new HashMap<>();

	private boolean setupVisible;

	PatchTypePanel(PatchImplementation type, PatchStateStore stateStore, AvailabilityProfile availability,
		GrowthTimer growthTimer, ItemManager itemManager, DoogleMapsConfig config,
		PlantableResolver resolver, SeedInventoryStore seeds, SeedSelectionStore selection,
		FarmingBonusStore bonuses, CompostSelectionStore compost)
	{
		// Nothing goes in a compost bin but buckets and weeds, so a "seeds you own" list under
		// one was simply wrong. Derived from the seed table rather than named here.
		this.seedSelector = PatchTabs.isPlantable(type)
			? new SeedSelectorPanel(type, resolver, seeds, selection, itemManager, compost)
			: null;
		this.type = type;
		this.members = PatchTabs.membersOf(type);
		this.stateStore = stateStore;
		this.availability = availability;
		this.growthTimer = growthTimer;
		this.itemManager = itemManager;
		this.config = config;
		this.seeds = seeds;
		this.bonuses = bonuses;

		setLayout(new BorderLayout(0, 6));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));

		rowContainer.setLayout(new BoxLayout(rowContainer, BoxLayout.Y_AXIS));
		rowContainer.setBackground(getBackground());

		emptyMessage.setBorder(BorderFactory.createEmptyBorder(8, 6, 8, 6));

		JPanel body = new JPanel(new BorderLayout(0, 6));
		body.setBackground(getBackground());
		body.add(emptyMessage, BorderLayout.NORTH);
		body.add(rowContainer, BorderLayout.CENTER);

		// Seeds sit directly beneath the patch rows: what is planted, then what you could
		// plant next. The availability toggles are setup rather than run information, so they
		// go last.
		JPanel footer = new JPanel(new BorderLayout(0, 6));
		footer.setBackground(getBackground());
		if (seedSelector != null)
		{
			footer.add(seedSelector, BorderLayout.NORTH);
		}
		footer.add(buildSetupSection(), BorderLayout.CENTER);

		add(body, BorderLayout.NORTH);
		add(footer, BorderLayout.CENTER);
		buildToggles();
	}

	/**
	 * The per-patch on/off list.
	 *
	 * <p>Switching a patch off removes it from everything, not just this list, so the
	 * player never sees a patch they cannot reach.
	 */
	/** Every patch this tab covers, across all its implementations. */
	private List<FarmPatch> allPatches()
	{
		if (members.size() == 1)
		{
			return FarmingWorldData.getPatches(type);
		}

		List<FarmPatch> patches = new ArrayList<>();
		for (PatchImplementation member : members)
		{
			patches.addAll(FarmingWorldData.getPatches(member));
		}
		return patches;
	}

	/** The same, filtered to the ones the player has switched on. */
	private List<FarmPatch> availablePatches()
	{
		if (members.size() == 1)
		{
			return availability.getAvailablePatches(type);
		}

		List<FarmPatch> patches = new ArrayList<>();
		for (PatchImplementation member : members)
		{
			patches.addAll(availability.getAvailablePatches(member));
		}
		return patches;
	}

	private JPanel buildSetupSection()
	{
		JPanel section = new JPanel(new BorderLayout(0, 4));
		section.setBackground(getBackground());

		toggleSetup.setFont(FontManager.getRunescapeSmallFont());
		Controls.styleButton(toggleSetup);
		toggleSetup.addActionListener(e ->
		{
			setupVisible = !setupVisible;
			patchToggles.setVisible(setupVisible);
			updateSetupButtonText();
			revalidate();
		});

		patchToggles.setLayout(new BoxLayout(patchToggles, BoxLayout.Y_AXIS));
		// Lighter than the panel behind it, so the dropdown reads as its own region rather
		// than blending into the sidebar.
		patchToggles.setBackground(TOGGLE_BACKGROUND);
		patchToggles.setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));
		patchToggles.setVisible(false);

		section.add(toggleSetup, BorderLayout.NORTH);
		section.add(patchToggles, BorderLayout.CENTER);

		updateSetupButtonText();
		return section;
	}

	private void updateSetupButtonText()
	{
		int available = availablePatches().size();
		int total = allPatches().size();
		toggleSetup.setText((setupVisible ? "Hide" : "Show") + " patches (" + available + "/" + total + ")");
	}

	/**
	 * Builds the on/off checkboxes once.
	 *
	 * <p>The patch list is fixed, so these are created up front and only their ticked
	 * state is synced afterwards. Rebuilding them on every refresh would replace the
	 * components under the player's cursor mid-click.
	 */
	private void buildToggles()
	{
		JPanel bulk = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		bulk.setBackground(patchToggles.getBackground());
		bulk.add(bulkButton("All", true));
		bulk.add(bulkButton("None", false));
		bulk.setAlignmentX(Component.LEFT_ALIGNMENT);
		patchToggles.add(bulk);

		for (FarmPatch patch : allPatches())
		{
			JCheckBox box = new JCheckBox(patch.getDisplayName(), availability.isAvailable(patch));
			box.setFont(FontManager.getRunescapeSmallFont());
			box.setBackground(patchToggles.getBackground());
			Controls.styleCheckBox(box);
			box.setAlignmentX(Component.LEFT_ALIGNMENT);
			box.addActionListener(e -> availability.setAvailable(patch, box.isSelected()));
			toggleBoxes.put(patch.getKey(), box);
			patchToggles.add(box);
		}
	}

	/** Syncs the checkboxes to the profile without replacing them. */
	private void syncToggles()
	{
		for (FarmPatch patch : allPatches())
		{
			JCheckBox box = toggleBoxes.get(patch.getKey());
			if (box == null)
			{
				continue;
			}
			boolean available = availability.isAvailable(patch);
			if (box.isSelected() != available)
			{
				box.setSelected(available);
			}
			box.setToolTipText(availability.isExplicitlySet(patch)
				? null
				: "Switched on because we have seen this patch before.");
		}
	}

	private JButton bulkButton(String text, boolean available)
	{
		JButton button = new JButton(text);
		button.setFont(FontManager.getRunescapeSmallFont());
		Controls.styleButton(button);
		button.setMargin(new java.awt.Insets(0, 4, 0, 4));
		button.addActionListener(e -> availability.setTypeAvailable(type, available));
		return button;
	}

	/** Repaints every row against the current cache. Must run on the EDT. */
	void refresh()
	{
		List<FarmPatch> patches = availablePatches();

		List<PatchProjection> projections = new ArrayList<>(patches.size());
		Map<String, PatchSnapshot> snapshots = new HashMap<>();
		for (FarmPatch patch : patches)
		{
			PatchSnapshot snapshot = stateStore.get(patch);
			snapshots.put(patch.getKey(), snapshot);

			PatchProjection projection = growthTimer.project(patch, snapshot);
			if (projection == null)
			{
				continue;
			}
			if (config.hideEmptyPatches() && projection.isEmpty())
			{
				continue;
			}
			projections.add(projection);
		}

		if (config.sortProblemsFirst())
		{
			// Diseased and dead first, then whatever is ready, then by how soon it is due.
			projections.sort(Comparator
				.comparingInt((PatchProjection p) -> p.getConfidence() == Confidence.NEEDS_ACTION ? 0 : 1)
				.thenComparingInt(p -> p.isReady() ? 0 : 1)
				.thenComparingLong(p -> p.getDoneEstimate() == 0 ? Long.MAX_VALUE : p.getDoneEstimate())
				.thenComparing(p -> p.getPatch().getDisplayName()));
		}

		rowContainer.removeAll();
		for (PatchProjection projection : projections)
		{
			FarmPatch patch = projection.getPatch();
			PatchRow row = rows.computeIfAbsent(patch.getKey(),
				key -> new PatchRow(patch, itemManager, config, seeds, bonuses));
			row.update(projection, snapshots.get(patch.getKey()));
			row.setAlignmentX(Component.LEFT_ALIGNMENT);
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
			rowContainer.add(row);
			rowContainer.add(javax.swing.Box.createVerticalStrut(3));
		}

		emptyMessage.setText(emptyMessageFor(patches.size(), projections.size()));
		emptyMessage.setVisible(projections.isEmpty());

		syncToggles();
		updateSetupButtonText();
		if (seedSelector != null)
		{
			seedSelector.refresh();
		}

		rowContainer.revalidate();
		rowContainer.repaint();
	}

	private String emptyMessageFor(int availableCount, int shownCount)
	{
		if (shownCount > 0)
		{
			return "";
		}
		if (availableCount == 0)
		{
			return "No " + type.getDisplayName().toLowerCase()
				+ " patches switched on yet. Use the list below to pick the ones you use.";
		}
		return "Nothing seen here yet. Walk past a patch, or cast Geomancy, to fill this in.";
	}
}
