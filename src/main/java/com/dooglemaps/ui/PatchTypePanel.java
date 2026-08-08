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

	private final JPanel rowContainer = new JPanel();
	private final WrappedText emptyMessage = new WrappedText();

	private final Map<String, PatchRow> rows = new HashMap<>();


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
		com.dooglemaps.data.ItemNames itemNames,
		com.dooglemaps.state.ContractState contracts)
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
				protection, bank, carried, itemNames, contracts)
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

		statusVisible = layout.isOpen(openKey("status"), true);
		toggleStatus.setFont(FontManager.getRunescapeSmallFont());
		Controls.styleButton(toggleStatus);
		toggleStatus.addActionListener(e ->
		{
			statusVisible = !statusVisible;
			rowContainer.setVisible(statusVisible);
			showEmptyMessage();
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
		if (seedSelector != null)
		{
			footer.add(seedSelector, BorderLayout.CENTER);
		}

		add(body, BorderLayout.NORTH);
		add(footer, BorderLayout.CENTER);
	}

	/**
	 * The section heading, which now carries the count the separate Patches list used to.
	 *
	 * <p>"14/18" is the one thing that list said which the rows do not say for themselves — you
	 * can see which patches are washed red, but not at a glance how many of the type you have
	 * switched on.
	 */
	private void updateStatusToggle()
	{
		updateStatusToggle(availablePatches().size(), groupPatches().size());
	}

	/**
	 * The same, given counts the caller has already worked out.
	 *
	 * <p>{@link #refresh} builds both lists to draw the rows, and asking for them again here made
	 * one refresh walk the patch list four times over — each walk calling {@code groups.groupFor}
	 * per patch, which is not the cheap lookup it looks like.
	 */
	private void updateStatusToggle(int on, int total)
	{
		toggleStatus.setText(Controls.collapseLabel(
			"Patch status (" + on + "/" + total + ")", statusVisible));
		// Says how to switch a patch off, because nothing else does. The rows are the control
		// now and a row does not look like a button - the only cue is the cursor, which you
		// have to already be hovering to see.
		toggleStatus.setToolTipText(Tooltips.html(on + " of " + total + " patches switched on."
			+ "<br><br><b>Click a row</b> to switch that patch off - it turns red and drops to the "
			+ "bottom. Click it again to switch it back on."
			+ "<br><br>A switched-off patch is left out of runs, counts and everything else."));
	}

	/**
	 * A section's stored state, shared by every patch group.
	 *
	 * <h2>Not namespaced by patch type, which it used to be</h2>
	 *
	 * The reasoning for namespacing was that herb seeds and tree seeds are different questions —
	 * true of the <i>contents</i>, and irrelevant to whether the section is folded up. Collapsing
	 * is a statement about how much of the sidebar you want the patch list taking, and someone who
	 * has decided that has decided it for the whole panel. Per type it meant collapsing the same
	 * section twenty-two times, one tab at a time, to get the layout you had already chosen once.
	 *
	 * <p>The seed list is included. It is the section most obviously "about" its type, and it is
	 * still the one people wanted uniform: you are either working from the list or you are not.
	 */
	private static String openKey(String section)
	{
		return section;
	}

	/**
	 * Adopts the current collapse state, which another tab may have changed.
	 *
	 * <p>Needed because these are separate panels, one per group, each holding its own idea of
	 * what is open. Sharing the stored key makes them <i>agree</i> on the next read; it does not
	 * make a panel that is already built notice. Called when a tab is selected, so the state is
	 * adopted at the only moment it could be seen to be wrong.
	 */
	void applyLayout()
	{
		statusVisible = layout.isOpen(openKey("status"), true);

		rowContainer.setVisible(statusVisible);
		showEmptyMessage();
		updateStatusToggle();

		if (seedSelector != null)
		{
			seedSelector.applyLayout();
		}
		revalidate();
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
				// The location filter first: a place you have hidden should not contribute rows
				// here or to the count in the heading.
				if (!Locations.isEnabled(config, patch))
				{
					continue;
				}
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

	/**
	 * Repaints every row against the current cache. Must run on the EDT.
	 *
	 * <h2>One list, not two</h2>
	 *
	 * This used to show the switched-on patches and offer a separate collapsible list of
	 * checkboxes underneath for switching them on and off — the same patches twice, once to read
	 * and once to edit. Switching one off made it vanish from the list you were looking at, and
	 * the only way to find out why was to open the other one.
	 *
	 * <p>Now the row is the control. Switched-off patches stay exactly where they were, washed
	 * red, and clicking one puts it back. A filter for tidying away patches you do not care
	 * about must not be able to hide the thing you would use to say that you do.
	 *
	 * <h2>Every patch is listed. There is no state filter</h2>
	 *
	 * There used to be a <i>Hide empty patches</i> setting, and it has been removed rather than
	 * defaulted off. An empty patch is not clutter — it is the patch you are about to plant
	 * into, so a planting run hid its own targets from the overview you check while running it.
	 * It never changed which patches a run <i>visited</i> ({@code RunPlanner} works from
	 * {@link com.dooglemaps.state.AvailabilityProfile}, not from this list), which made it worse
	 * rather than better: the run went somewhere the sidebar said was not there.
	 *
	 * <p>The thing it was reaching for is already covered twice over, and both are better because
	 * they are <b>decisions rather than states</b>: the per-patch availability toggle, and the
	 * per-type tabs. Those stay put. A filter keyed on what the patch happens to be doing changes
	 * under you as the crop grows.
	 */
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
			addRow(projection.getPatch(), projection, snapshots.get(projection.getPatch().getKey()),
				false);
		}

		// The switched-off ones last, in a stable order, so turning one off moves it out of the
		// way rather than leaving it among the patches you are actually farming. Derived from the
		// group's full list rather than asked for separately, so the walk is done once.
		List<FarmPatch> everything = groupPatches();
		for (FarmPatch patch : everything)
		{
			if (availability.isAvailable(patch))
			{
				continue;
			}
			PatchSnapshot snapshot = stateStore.get(patch);
			addRow(patch, growthTimer.project(patch, snapshot), snapshot, true);
		}

		emptyMessage.setText(emptyMessageFor(patches.size(), projections.size()));
		showEmptyMessage();

		updateStatusToggle(patches.size(), everything.size());
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

	private void addRow(FarmPatch patch, @javax.annotation.Nullable PatchProjection projection,
		@javax.annotation.Nullable PatchSnapshot snapshot, boolean off)
	{
		PatchRow row = rows.computeIfAbsent(patch.getKey(),
			key -> new PatchRow(patch, itemManager, config, seeds, bonuses));
		row.update(projection, snapshot);
		row.setOff(off);
		row.setOnToggle(() -> availability.setAvailable(patch, off));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		rowContainer.add(row);
		rowContainer.add(javax.swing.Box.createVerticalStrut(3));
	}

	/**
	 * Every patch this tab is responsible for, switched on or not.
	 *
	 * <p>{@link #allPatches} without the group filter would offer a split tab the chance to switch
	 * on a patch belonging to the other one — the protected herb tab listing Ardougne, which it
	 * does not own. Same test the visible rows use, so the two lists cannot drift.
	 */
	private List<FarmPatch> groupPatches()
	{
		List<FarmPatch> patches = new ArrayList<>();
		for (FarmPatch patch : allPatches())
		{
			if (!Locations.isEnabled(config, patch))
			{
				continue;
			}
			if (patch.getImplementation() != type || groups.groupFor(patch).equals(group))
			{
				patches.add(patch);
			}
		}
		return patches;
	}

	/**
	 * Shows the "nothing here yet" line only when there is something to say.
	 *
	 * <h2>The bug this replaces</h2>
	 *
	 * Two of the three places that set this visibility asked {@code emptyMessage.isEnabled()},
	 * which is a Swing property nothing in this class ever sets — so it is always true, and the
	 * condition reduced to "the status section is open". One of those two is {@link #applyLayout},
	 * which runs on <b>every tab select</b>. Clicking through the patch types therefore made an
	 * empty message visible on each one: no text, but an 8px border top and bottom and a line's
	 * worth of height, appearing as a gap between the tab strip and the Patch status heading. The
	 * next refresh set it correctly again, which is why it came and went.
	 *
	 * <p>The condition that was meant is "does it have a message", and {@link #emptyMessageFor}
	 * already answers that by returning an empty string when there are rows to show. Asked in one
	 * place now, so the three callers cannot disagree again.
	 */
	private void showEmptyMessage()
	{
		emptyMessage.setVisible(statusVisible && !emptyMessage.getText().isEmpty());
	}

	private String emptyMessageFor(int availableCount, int shownCount)
	{
		if (shownCount > 0)
		{
			return "";
		}
		if (availableCount == 0)
		{
			// Points at the rows, because there is no longer a second list to point at. If every
			// patch is off they are all still here, washed red, and clicking one is the answer.
			return "No " + type.getDisplayName().toLowerCase()
				+ " patches switched on yet. Click one below to switch it on.";
		}
		return "Nothing seen here yet. Walk past a patch, or cast Geomancy, to fill this in.";
	}
}
