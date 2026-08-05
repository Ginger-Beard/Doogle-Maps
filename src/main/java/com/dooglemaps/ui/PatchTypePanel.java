package com.dooglemaps.ui;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.state.PlantingGroups;
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
	private final PlantingGroup group;
	private final PlantingGroups groups;
	private final com.dooglemaps.state.ProtectionSelectionStore protection;
	private final com.dooglemaps.bank.BankContents bank;
	private final com.dooglemaps.guide.CarriedItems carried;
	private final com.dooglemaps.data.ItemNames itemNames;
	private final PanelLayoutStore layout;
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

	/** The patch rows themselves, which are the point of the tab and so start open. */
	private final JButton toggleStatus = new JButton();
	private boolean statusVisible;

	PatchTypePanel(PanelLayoutStore layout, PlantingGroups groups, PlantingGroup group,
		PatchStateStore stateStore, AvailabilityProfile availability,
		GrowthTimer growthTimer, ItemManager itemManager, DoogleMapsConfig config,
		PlantableResolver resolver, SeedInventoryStore seeds, SeedSelectionStore selection,
		FarmingBonusStore bonuses, CompostSelectionStore compost,
		com.dooglemaps.state.ProtectionSelectionStore protection,
		com.dooglemaps.bank.BankContents bank, com.dooglemaps.guide.CarriedItems carried,
		com.dooglemaps.data.ItemNames itemNames)
	{
		// Nothing goes in a compost bin but buckets and weeds, so a "seeds you own" list under
		// one was simply wrong. Derived from the seed table rather than named here.
		this.layout = layout;
		this.groups = groups;
		this.group = group;
		this.type = group.getType();
		this.protection = protection;
		this.bank = bank;
		this.carried = carried;
		this.itemNames = itemNames;
		this.seedSelector = PatchTabs.isPlantable(type)
			? new SeedSelectorPanel(layout, group, resolver, seeds, selection, itemManager, compost,
				protection, bank, carried, itemNames)
			: null;
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

		setupVisible = layout.isOpen(openKey("patches"), false);
		statusVisible = layout.isOpen(openKey("status"), true);
		toggleStatus.setFont(FontManager.getRunescapeSmallFont());
		Controls.styleButton(toggleStatus);
		toggleStatus.addActionListener(e ->
		{
			statusVisible = !statusVisible;
			rowContainer.setVisible(statusVisible);
			emptyMessage.setVisible(statusVisible && emptyMessage.isEnabled());
			layout.setOpen(openKey("status"), statusVisible);
			updateStatusToggle();
			revalidate();
		});
		rowContainer.setVisible(statusVisible);
		updateStatusToggle();

		JPanel rowsWithHeading = new JPanel(new BorderLayout(0, 4));
		rowsWithHeading.setBackground(getBackground());
		rowsWithHeading.add(toggleStatus, BorderLayout.NORTH);
		rowsWithHeading.add(rowContainer, BorderLayout.CENTER);

		JPanel body = new JPanel(new BorderLayout(0, 6));
		body.setBackground(getBackground());
		body.add(emptyMessage, BorderLayout.NORTH);
		body.add(rowsWithHeading, BorderLayout.CENTER);

		// Which patches you use, then which seed goes in them: the first decides what the second
		// is even choosing between, so it reads in that order. Seeds were above it, which put the
		// answer before the question.
		JPanel footer = new JPanel(new BorderLayout(0, 6));
		footer.setBackground(getBackground());
		footer.add(buildSetupSection(), BorderLayout.NORTH);
		if (seedSelector != null)
		{
			footer.add(seedSelector, BorderLayout.CENTER);
		}

		add(body, BorderLayout.NORTH);
		add(footer, BorderLayout.CENTER);
		buildToggles();
	}

	private void updateStatusToggle()
	{
		toggleStatus.setText(Controls.collapseLabel("Patch status", statusVisible));
	}

	/** Namespaces a section's stored state by patch type: herb seeds and tree seeds differ. */
	private String openKey(String section)
	{
		return section + "." + group.getKey();
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

	/**
	 * The same, filtered to the ones the player has switched on and to this tab's group.
	 *
	 * <p>The group filter is what makes a split tab show only its own patches — the protected herb
	 * tab lists Trollheim and Weiss, and the ordinary one lists everything else. With nothing
	 * split, every patch of the type falls into the one group, so this is unchanged.
	 */
	private List<FarmPatch> availablePatches()
	{
		List<FarmPatch> patches = new ArrayList<>();
		for (PatchImplementation member : members)
		{
			for (FarmPatch patch : availability.getAvailablePatches(member))
			{
				// Only the grouping of *this* tab's type is meaningful. A grouped tab that also
				// gathers other types — Gnome Stronghold's tree and fruit tree share one — keeps
				// those whole rather than splitting them too.
				if (member != type || groups.groupFor(patch).equals(group))
				{
					patches.add(patch);
				}
			}
		}
		return patches;
	}

	/**
	 * Patches of this group a run would actually plant.
	 *
	 * <p>The same test the run planner uses — empty, ripe or dead — because the payment total has
	 * to match what the run will really do. A patch mid-growth is not going to be planted today
	 * and should not be charged for.
	 */
	private static int plantableCount(List<PatchProjection> projections)
	{
		int count = 0;
		for (PatchProjection projection : projections)
		{
			if (projection.isEmpty()
				|| projection.getCropState() == com.dooglemaps.data.CropState.HARVESTABLE
				|| projection.getCropState() == com.dooglemaps.data.CropState.DEAD)
			{
				count++;
			}
		}
		return count;
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
			layout.setOpen(openKey("patches"), setupVisible);
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
		// No "Show"/"Hide" verb. The arrow and the list under it already say which way it is,
		// and the word changing under the cursor made the button read as the thing it does rather
		// than as the thing it contains.
		toggleSetup.setText(Controls.collapseLabel(
			"Patches (" + available + "/" + total + ")", setupVisible));
		updateStatusToggle();
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
			// How many patches of this group would be planted, so the protection payment can be
			// totalled. Counted from the projections already gathered above rather than asked of
			// the planner: this runs on the Swing thread, and the planner is synchronised and
			// walked from the client thread.
			seedSelector.setPatchCount(plantableCount(projections));
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
