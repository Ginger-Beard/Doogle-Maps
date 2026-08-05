package com.dooglemaps.ui;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.RunOption;
import com.dooglemaps.state.PlantingGroups;
import com.dooglemaps.data.ProtectionPayment;
import com.dooglemaps.route.ProtectionBudget;
import com.dooglemaps.state.ProtectionSelectionStore;
import com.dooglemaps.bank.BankContents;
import com.dooglemaps.guide.CarriedItems;
import com.dooglemaps.bank.RunLoadout;
import com.dooglemaps.data.Seed;
import com.dooglemaps.route.RunEstimate;
import com.dooglemaps.route.RunPlanner;
import com.dooglemaps.route.RunStop;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.state.CompostSelectionStore;
import com.dooglemaps.state.FarmingBonusStore;
import com.dooglemaps.state.RunTypeStore;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.state.SeedSelectionStore;
import com.dooglemaps.state.SeedSource;
import com.dooglemaps.timer.FarmingBonuses;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Plan a run, then follow it.
 *
 * <p>Two states in one panel: before a run it is a list of patch types to tick with the
 * projection under it; during one it is the list of stops still to visit. That mirrors how a run
 * actually goes — you decide once, then stop deciding.
 *
 * <p><b>Nothing here narrates the run.</b> There used to be a block of text above the projection
 * — the current leg, what to take from the bank, which patches had no seed — and all of it went
 * to the on-screen panel, because following a run means watching the game rather than the
 * sidebar. The wording lives in {@code LoadoutSummary} rather than in either renderer, so the
 * side-pane checklist this is heading towards can show the same steps in the same words.
 */
class RunPanel extends JPanel
{
	private static final Color READY = new Color(0x4C, 0xAF, 0x50);
	private static final Color WARN = new Color(0xC8, 0xA2, 0x2D);
	private static final Color BAD = new Color(0xC4, 0x3B, 0x3B);

	/** Patch types worth offering as a run; the rest are one-offs you visit deliberately. */
	private static final Set<PatchImplementation> RUNNABLE = EnumSet.of(
		PatchImplementation.HERB,
		PatchImplementation.ALLOTMENT,
		PatchImplementation.FLOWER,
		PatchImplementation.HOPS,
		PatchImplementation.BUSH,
		PatchImplementation.TREE,
		PatchImplementation.FRUIT_TREE,
		PatchImplementation.HARDWOOD_TREE);

	private final RunPlanner planner;
	private final RunLoadout loadout;
	private final AvailabilityProfile availability;
	private final SeedSelectionStore selection;
	private final SeedInventoryStore seeds;

	private final RunTypeStore runTypes;

	/** For the patch-type toggles, which decide which lines are offered at all. */
	private final com.dooglemaps.DoogleMapsConfig config;
	private final FarmingBonusStore bonuses;
	private final CompostSelectionStore compost;

	/** A step lighter than the sidebar, so the expanded list reads as its own region. */
	private static final Color LIST_BACKGROUND = new Color(0x3A, 0x3A, 0x3A);

	private final Map<RunOption, JCheckBox> optionBoxes = new java.util.LinkedHashMap<>();
	private final JPanel typeSelection = new JPanel();

	/**
	 * Which of the ticked types have no seed chosen, in red, directly under the boxes.
	 *
	 * <p>The one line kept back when the rest of the pre-run narration moved to the on-screen
	 * panel, and it is kept for a different reason from the rest: it is not a step, it is a
	 * mistake. A run with a type ticked and no seed picked for it will visit those patches and
	 * plant nothing, and the place to say so is under the tick that caused it rather than
	 * further down among the projections.
	 */
	private final WrappedText noSeeds = new WrappedText();
	private final JPanel stopList = new JPanel();

	/** Everywhere the planned run will go, before it starts. See {@link #buildDestinations}. */
	private final JPanel destinations = new JPanel();
	private final JPanel destinationList = new JPanel();
	private final JButton toggleDestinations = new JButton();

	/**
	 * Open to start with, now that the run has a tab to itself.
	 *
	 * <p>Closed to start with. It was opened when the run had a page to itself and nothing to
	 * push down; folded back under the patch rows, an eleven-line list between the run controls
	 * and everything below them is back to being in the way. The count is on the button, which is
	 * what you want from it most of the time anyway.
	 */
	private final PanelLayoutStore layout;
	private final ProtectionSelectionStore protection;
	private final PlantingGroups groups;
	private final BankContents bank;
	private final CarriedItems carried;
	private boolean destinationsVisible;
	private int destinationCount;
	private int destinationPatches;

	/** Expected experience by compost tier and gear, built fresh on each refresh. */
	private final RewardTable rewardTable = new RewardTable();
	private final JButton startStop = new JButton();

	RunPanel(PanelLayoutStore layout, PlantingGroups groups,
		ProtectionSelectionStore protection, BankContents bank,
		CarriedItems carried, RunPlanner planner, RunLoadout loadout,
		AvailabilityProfile availability, SeedSelectionStore selection, SeedInventoryStore seeds,
		RunTypeStore runTypes, FarmingBonusStore bonuses, CompostSelectionStore compost,
		com.dooglemaps.DoogleMapsConfig config)
	{
		this.config = config;
		this.layout = layout;
		this.groups = groups;
		this.protection = protection;
		this.bank = bank;
		this.carried = carried;
		this.planner = planner;
		this.loadout = loadout;
		this.availability = availability;
		this.selection = selection;
		this.seeds = seeds;
		this.runTypes = runTypes;
		this.bonuses = bonuses;
		this.compost = compost;

		setLayout(new BorderLayout(0, 6));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		// A gap at the top, dividing this from everything above it. The whole page above is
		// per-patch-type — what is growing, which patches you use, which seed goes in them — and
		// everything from here down is about the run as a whole. That is the one boundary on this
		// page worth drawing, and space draws it without another heading to read.
		setBorder(BorderFactory.createEmptyBorder(14, 0, 6, 0));

		// Two across, which halves the height of a list that can now run to eleven lines. A grid
		// gives every cell the width of the widest label, and that is what decides whether this
		// fits: spelled out, "Fruit tree (harvest only)" made two columns want 290px of a 225px
		// sidebar and they were clipped. Abbreviating it to "(H/O)" is what buys the second
		// column. The render test catches exactly this, which is why it exists.
		typeSelection.setLayout(new GridLayout(0, 2, 4, 0));
		typeSelection.setBackground(getBackground());
		buildTypeBoxes();

		noSeeds.setForeground(BAD);
		noSeeds.setBorder(BorderFactory.createEmptyBorder(4, 6, 0, 6));
		noSeeds.setVisible(false);

		stopList.setLayout(new BoxLayout(stopList, BoxLayout.Y_AXIS));
		stopList.setBackground(getBackground());

		rewardTable.setBackground(getBackground());
		rewardTable.setBorder(BorderFactory.createEmptyBorder(0, 6, 4, 6));
		rewardTable.setVisible(false);

		Controls.styleButton(startStop);
		startStop.addActionListener(e -> toggleRun());

		JPanel choices = new JPanel(new BorderLayout(0, 0));
		choices.setBackground(getBackground());
		choices.add(typeSelection, BorderLayout.NORTH);
		choices.add(noSeeds, BorderLayout.CENTER);

		JPanel body = new JPanel(new BorderLayout(0, 4));
		body.setBackground(getBackground());
		body.add(choices, BorderLayout.NORTH);
		JPanel plan = new JPanel(new BorderLayout(0, 2));
		plan.setBackground(getBackground());
		plan.add(rewardTable, BorderLayout.CENTER);
		plan.add(buildDestinations(), BorderLayout.SOUTH);
		body.add(plan, BorderLayout.CENTER);
		body.add(stopList, BorderLayout.SOUTH);

		add(startStop, BorderLayout.NORTH);
		add(body, BorderLayout.CENTER);
	}

	/**
	 * One box per run option, which is no longer one per patch type.
	 *
	 * <p>A type can offer more than one line: protected herbs are a different set of patches, and
	 * bushes and fruit trees can be run for their harvest alone. Built from
	 * {@code PlantingGroups.runOptions} so the list cannot disagree with the tabs above it.
	 */
	/**
	 * Rebuilds the run list because the lines it should offer have changed.
	 *
	 * <p>The same signal that rebuilds the tab strip. Without it the two disagreed: the protected
	 * herb tab would appear at the top of the page while the run list below still had no line for
	 * it, so the category existed and could not be run. Which lines exist is decided by a setting
	 * and by unlocks detected after login, and neither is known when this is first built.
	 *
	 * <p>Ticks come back from the store rather than being carried across, so a line that
	 * disappears and returns is still ticked. See {@code RunTypeStore.setSelected}.
	 */
	void structureChanged()
	{
		optionBoxes.clear();
		typeSelection.removeAll();
		buildTypeBoxes();
		typeSelection.revalidate();
		typeSelection.repaint();
		refresh();
	}

	/**
	 * Lays the run options out two across, with each pair on its own row.
	 *
	 * <h2>Why the grid needs help</h2>
	 *
	 * A {@code GridLayout} fills row by row, so a list containing three pairs and seven singles
	 * puts them wherever the count happens to land — "Fruit tree" ended up in the left column with
	 * "Fruit tree (H/O)" in the right of the <i>same</i> row only by luck, while "Bush" and
	 * "Bush (H/O)" straddled a row break. The two halves of one decision read as unrelated
	 * entries.
	 *
	 * <p>So a pair is pushed to the start of a row, padding the gap it leaves. It rarely has to:
	 * {@code PlantingGroups.runOptions} now groups the paired types at the end of the list, so the
	 * singles fill whole rows ahead of them and there is at most one gap to pad. The padding stays
	 * because the layout must not depend on that ordering holding — switching a patch type off in
	 * the settings changes how many singles there are.
	 */
	private void buildTypeBoxes()
	{
		List<RunOption> options = offeredOptions();
		int column = 0;

		for (RunOption option : options)
		{
			// A harvest-only line is drawn as part of its pair, when the full line reaches it.
			if (option.isHarvestOnly())
			{
				continue;
			}

			RunOption paired = hasHarvestOnly(options, option)
				? RunOption.harvestOnly(option.getGroup())
				: null;

			// Start a fresh row for a pair, so the two halves are side by side rather than
			// wrapped across the break.
			if (paired != null && column == 1)
			{
				typeSelection.add(filler());
				column = 0;
			}

			addOptionBox(option);
			column = (column + 1) % 2;

			if (paired != null)
			{
				addOptionBox(paired);
				column = (column + 1) % 2;
			}
		}
	}

	/**
	 * The run lines to show, honouring the patch types switched off in the settings.
	 *
	 * <p>This filter was missing, and the result was a run you could start and not configure:
	 * hiding the bush tab took away the only place to pick a bush seed or its compost, while
	 * "Bush" and "Bush (H/O)" stayed in the list underneath. The two are one decision — a type
	 * you have hidden is one you are not farming.
	 *
	 * <p>Nothing is lost by hiding one. The stored selection keeps keys that are not currently on
	 * offer, so switching the type back on brings its ticks back with it. See
	 * {@code RunTypeStore.setSelected}.
	 */
	private List<RunOption> offeredOptions()
	{
		List<RunOption> offered = new ArrayList<>();
		for (RunOption option : groups.runOptions())
		{
			if (PatchTabs.isEnabled(config, option.getType()))
			{
				offered.add(option);
			}
		}
		return offered;
	}

	/** Whether this full-run line has a harvest-only counterpart on offer. */
	private static boolean hasHarvestOnly(List<RunOption> options, RunOption full)
	{
		return options.contains(RunOption.harvestOnly(full.getGroup()));
	}

	/** An empty cell, so the next pair starts a row. */
	private JPanel filler()
	{
		JPanel blank = new JPanel();
		blank.setBackground(getBackground());
		return blank;
	}

	private void addOptionBox(RunOption option)
	{
		{
			// Ticked from the saved run, so the same circuit does not have to be re-entered
			// before every run.
			JCheckBox box = new JCheckBox(option.getLabel(), runTypes.isSelected(option));
			box.setBackground(getBackground());
			Controls.styleCheckBox(box);
			box.setFont(FontManager.getRunescapeSmallFont());
			// The label is abbreviated to fit two columns, so the tooltip carries the meaning
			// rather than merely elaborating on it.
			box.setToolTipText(option.isHarvestOnly()
				? "Harvest only: visit these to pick what is ready and nothing else - the patch "
					+ "is not cleared, composted or replanted"
				: null);
			box.addActionListener(e ->
			{
				if (box.isSelected())
				{
					untick(counterpartOf(option));
				}
				// Only the lines on show are replaced. A protected herb run chosen earlier must
				// survive the split being switched off and back on, and these boxes cannot speak
				// for a line they are not displaying.
				runTypes.setSelected(getSelectedOptions(), optionBoxes.keySet());
				refresh();
			});
			optionBoxes.put(option, box);
			typeSelection.add(box);
		}
	}

	/**
	 * The other half of a type's pair: full for harvest-only, and harvest-only for full.
	 *
	 * <p>They are mutually exclusive rather than merely contradictory, which is the player's
	 * point: if you are replanting you have to harvest first, so the full run already includes
	 * the harvest; and if you are only harvesting, you have specifically decided not to replant.
	 * There is no run that wants both, so ticking one unticks the other.
	 *
	 * <p>This replaces a rule that resolved the contradiction after the fact — full wins — which
	 * was a reasonable reading of an impossible state but still let the player express it. Not
	 * being able to say it at all is better than being told what it was taken to mean.
	 */
	private static RunOption counterpartOf(RunOption option)
	{
		return option.isHarvestOnly()
			? RunOption.full(option.getGroup())
			: RunOption.harvestOnly(option.getGroup());
	}

	private void untick(RunOption option)
	{
		JCheckBox other = optionBoxes.get(option);
		if (other != null && other.isSelected())
		{
			other.setSelected(false);
		}
	}

	private Set<RunOption> getSelectedOptions()
	{
		Set<RunOption> selected = new LinkedHashSet<>();
		optionBoxes.forEach((option, box) ->
		{
			if (box.isSelected())
			{
				selected.add(option);
			}
		});
		return selected;
	}

	/**
	 * Everywhere the run will go, listed before it starts.
	 *
	 * <p>Exists because the run could previously only say what was <i>next</i>, and only once
	 * you had already reached the bank — so there was no moment at which you could see the
	 * whole trip and work out which teleports to take. Now the destinations are on screen
	 * while you are still standing at the bank deciding.
	 *
	 * <p>Names places rather than teleport items. Shortest Path reports the transports for the
	 * leg it is currently drawing, so no later leg has been costed and there is nothing honest
	 * to say about it here.
	 *
	 * <p>Teleport <i>items</i> are handled elsewhere and the other way round — see
	 * {@code TeleportItems}. Rather than saying "to reach Ardougne, bring X", which is advice
	 * and is wrong for anyone whose unlocks differ, the loadout crosses what a teleport reaches
	 * with what is actually in your bank. This list stays about destinations.
	 *
	 * <p>The count stays on the button either way, so how big the trip is reads at a glance
	 * even with the list shut.
	 */
	private JPanel buildDestinations()
	{
		destinationsVisible = layout.isOpen("destinations", false);
		destinations.setLayout(new BorderLayout(0, 2));
		destinations.setBackground(getBackground());
		destinations.setBorder(BorderFactory.createEmptyBorder(2, 6, 0, 6));
		destinations.setVisible(false);

		toggleDestinations.setFont(FontManager.getRunescapeSmallFont());
		Controls.styleButton(toggleDestinations);
		toggleDestinations.addActionListener(e ->
		{
			destinationsVisible = !destinationsVisible;
			destinationList.setVisible(destinationsVisible);
			layout.setOpen("destinations", destinationsVisible);
			updateDestinationsButton();
			revalidate();
		});

		destinationList.setLayout(new BoxLayout(destinationList, BoxLayout.Y_AXIS));
		destinationList.setBackground(LIST_BACKGROUND);
		destinationList.setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));
		destinationList.setVisible(destinationsVisible);

		destinations.add(toggleDestinations, BorderLayout.NORTH);
		destinations.add(destinationList, BorderLayout.CENTER);
		return destinations;
	}

	/**
	 * Redraws the destination list for the currently ticked patch types.
	 *
	 * <p>Sorted by name, which is not the order the run will visit them in — that is chosen a
	 * leg at a time by whichever stop is cheapest to reach next. An arbitrary but stable order
	 * is the honest presentation; numbering them would imply a tour that does not exist.
	 */
	private void rebuildDestinations(Set<PatchImplementation> types)
	{
		destinationList.removeAll();

		List<RunStop> stops = planner.previewStops(types);
		if (stops.isEmpty())
		{
			destinations.setVisible(false);
			return;
		}

		stops.sort(Comparator.comparing(RunStop::getName));

		int patches = 0;
		for (RunStop stop : stops)
		{
			patches += stop.getPatches().size();
			destinationList.add(row(stop.getName() + "  (" + stop.getPatches().size() + ")",
				ColorScheme.TEXT_COLOR));
		}

		// The list looks like an itinerary and is not one, which is the thing worth being clear
		// about: nothing here decides a route. Each leg goes to whichever remaining stop is
		// cheapest from wherever you are standing, which only Shortest Path can answer and only
		// once you are there.
		//
		// Two short labels rather than one wrapped sentence. WrappedText assumes it gets the
		// sidebar's full width, and inside this doubly-bordered list it does not — see the note
		// on that class. Short enough not to need wrapping is the version that cannot clip.
		destinationList.add(row("Alphabetical, not travel order.", ColorScheme.MEDIUM_GRAY_COLOR));
		destinationList.add(row("Route decides as you go.", ColorScheme.MEDIUM_GRAY_COLOR));

		destinations.setVisible(true);
		destinationCount = stops.size();
		destinationPatches = patches;
		updateDestinationsButton();
	}

	private void updateDestinationsButton()
	{
		// No "Show"/"Hide" verb, and the patch count promoted onto the button. What someone wants
		// from this closed is the size of the trip, and "11 stops" alone did not give it — four
		// tree patches in four places is a very different afternoon from eleven patches in three.
		toggleDestinations.setText(Controls.collapseLabel("Destinations ("
			+ destinationCount + " stops, "
			+ destinationPatches + (destinationPatches == 1 ? " patch)" : " patches)"),
			destinationsVisible));
		toggleDestinations.setToolTipText("<html>Everywhere this run will take you."
			+ "<br>Places only - which teleports to take is your call, and depends on what you "
			+ "have unlocked.</html>");
	}

	/**
	 * The patch types the ticked options cover.
	 *
	 * <p>Several options can share a type, so this is a projection rather than the selection
	 * itself — the planner works in types plus filters, and the filters live in the store.
	 */
	private Set<PatchImplementation> getSelectedTypes()
	{
		Set<PatchImplementation> selected = EnumSet.noneOf(PatchImplementation.class);
		optionBoxes.forEach((option, box) ->
		{
			if (box.isSelected())
			{
				selected.add(option.getType());
			}
		});
		return selected;
	}

	private void toggleRun()
	{
		if (planner.isActive())
		{
			planner.stop();
		}
		else
		{
			planner.start(getSelectedTypes());
		}
		refresh();
	}

	/** Redraws for the current run state. Must run on the EDT. */
	void refresh()
	{
		boolean running = planner.isActive();

		startStop.setText(running ? "Stop run" : "Start run");
		optionBoxes.forEach((option, box) ->
		{
			box.setEnabled(!running);
			// Re-read rather than trusting the box: these are built once, and a profile load
			// after that would otherwise leave them showing the state from before it.
			if (box.isSelected() != runTypes.isSelected(option))
			{
				box.setSelected(runTypes.isSelected(option));
			}
		});

		stopList.removeAll();
		if (running)
		{
			rewardTable.setVisible(false);
			noSeeds.setVisible(false);
			// The stop list below is the live version of the same thing, current target first,
			// so a second static copy of it would only be a staler duplicate.
			destinations.setVisible(false);
			buildStopList();
		}
		else
		{
			rebuildRewardTable(getSelectedTypes());
			rebuildDestinations(getSelectedTypes());
			updateNoSeeds(getSelectedTypes());
		}

		revalidate();
		repaint();
	}

	/**
	 * Names the ticked types nothing will be planted in.
	 *
	 * <p>Asked per type rather than per group: a type with a seed picked for one of its groups and
	 * not the other is a subtler thing than this line is for, and the group with no seed still
	 * gets its patches counted as unfilled in the projection below.
	 */
	private void updateNoSeeds(Set<PatchImplementation> types)
	{
		List<String> unpicked = new ArrayList<>();
		for (PatchImplementation type : types)
		{
			// A type in the run only for its harvest needs no seed, so saying one is missing is
			// telling the player to fix something that is not wrong.
			if (onlyHarvestedFor(type))
			{
				continue;
			}
			if (selection.getSelectedFor(type).isEmpty())
			{
				unpicked.add(type.getDisplayName().toLowerCase());
			}
		}

		noSeeds.setVisible(!unpicked.isEmpty());
		if (!unpicked.isEmpty())
		{
			noSeeds.setText("No seed picked for: " + String.join(", ", unpicked) + ".");
		}
	}

	/**
	 * Whether every group of this type in the run is harvest-only.
	 *
	 * <p>Every group, not any: a type with a full run over one group and a harvest-only pass over
	 * another does still want a seed, for the group that plants.
	 */
	private boolean onlyHarvestedFor(PatchImplementation type)
	{
		boolean any = false;
		for (PlantingGroup group : groups.groupsFor(type))
		{
			if (!runTypes.isSelected(RunOption.full(group))
				&& !runTypes.isSelected(RunOption.harvestOnly(group)))
			{
				continue;
			}
			any = true;
			if (!runTypes.isHarvestOnly(group))
			{
				return false;
			}
		}
		return any;
	}

	/** Whether any group in the run is being visited for its harvest alone. */
	private boolean anyHarvestOnly()
	{
		for (PatchImplementation type : getSelectedTypes())
		{
			for (PlantingGroup group : groups.groupsFor(type))
			{
				if (runTypes.isHarvestOnly(group))
				{
					return true;
				}
			}
		}
		return false;
	}

	private void buildStopList()
	{
		if (planner.isAtBankLeg())
		{
			stopList.add(row(describeSupplyStop(), READY));
		}

		// The instruction for the patch in front of you is deliberately *not* here any more. It
		// lives on the game screen now, in GuideStepOverlay, because following a run means
		// watching the patch rather than the sidebar — and a step you have to look away to read
		// is one you stop reading. This list keeps the half you read while standing still.
		for (RunStop stop : planner.getRemaining())
		{
			stopList.add(row(stop.getName() + "  (" + stop.getPatches().size() + ")",
				ColorScheme.TEXT_COLOR));
		}

		Collection<String> transports = planner.getCurrentTransports();
		if (!transports.isEmpty())
		{
			stopList.add(row("via " + String.join(", ", transports), ColorScheme.LIGHT_GRAY_COLOR));
		}
	}

	private JLabel row(String text, Color colour)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(colour);
		label.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		label.setMaximumSize(new Dimension(Integer.MAX_VALUE, label.getPreferredSize().height));
		return label;
	}

	/**
	 * Names the opening stop by where the seeds actually are.
	 *
	 * <p>There is one seed vault and it is in the Farming Guild, so "go to a bank" is
	 * actively wrong for seeds stored there.
	 */
	private String describeSupplyStop()
	{
		Set<SeedSource> sources = planner.getSupplySources();
		boolean bank = sources.contains(SeedSource.BANK);
		boolean vault = sources.contains(SeedSource.SEED_VAULT);

		if (bank && vault)
		{
			return "Bank and seed vault first";
		}
		if (vault)
		{
			return "Seed vault first - Farming Guild";
		}
		return "Bank first - grab seeds and payments";
	}

	/**
	 * Prices every crop in the run, using what the player said they would treat it with.
	 *
	 * <p>One number per crop rather than a grid of possibilities. The compost dropdowns on each
	 * patch tab say what is going on the ground, and everything else — secateurs, the cape,
	 * attas, the outfit, the diary rewards — is detected rather than asked for. So there is a
	 * single correct answer to show, and the tooltip explains what went into it.
	 */
	private void rebuildRewardTable(Set<PatchImplementation> types)
	{
		Map<PatchImplementation, Integer> actionable = planner.countActionable(types);
		Set<Seed> chosen = selection.getSelected();
		Map<Seed, Integer> owned = ownedSeeds();
		int level = seeds.getFarmingLevel();

		// A seed selection is not required any more. A harvest-only run has nothing to plant by
		// definition — the crop is already in the ground — and hiding the table because nothing
		// was picked priced a bush run at nothing, which is precisely the run it was asked about.
		if (actionable.isEmpty() || level <= 0 || (chosen.isEmpty() && !anyHarvestOnly()))
		{
			rewardTable.setVisible(false);
			return;
		}

		RunEstimate estimate = estimateFor(types, owned, level);
		rewardTable.setData(estimate, describeGear(level, estimate));
	}

	/**
	 * Prices the run, one planting group at a time.
	 *
	 * <p>Each group is costed against its own seeds and its own compost, then the parts are
	 * merged. Pricing the whole type in one go would let the seed picked for the protected
	 * patches fill the ordinary ones too — the estimate ranks by experience and would happily put
	 * ranarr in all eight — which is the arrangement the split exists to prevent.
	 *
	 * <p>With nothing split there is one group per type and this is the single call it always
	 * was.
	 */
	private RunEstimate estimateFor(Set<PatchImplementation> types, Map<Seed, Integer> owned,
		int level)
	{
		Map<PlantingGroup, Integer> byGroup = planner.countActionableByGroup(types);
		List<RunEstimate> parts = new ArrayList<>();

		for (Map.Entry<PlantingGroup, Integer> entry : byGroup.entrySet())
		{
			PlantingGroup group = entry.getKey();

			// Priced against what is growing rather than against a seed, because there is no seed
			// in a harvest-only trip and asking for one is what made these read as zero.
			if (runTypes.isHarvestOnly(group))
			{
				parts.add(RunEstimate.forHarvest(planner.ripeProduceIn(group), level,
					bonuses.current()));
				continue;
			}

			Map<PatchImplementation, Integer> one =
				java.util.Collections.singletonMap(group.getType(), entry.getValue());
			Map<PatchImplementation, CompostTier> tier =
				java.util.Collections.singletonMap(group.getType(), compost.get(group));

			// Survival over this group's own patches, not the whole type. The protected herbs
			// cannot be diseased and the ordinary ones can, so one blended figure was wrong for
			// both of them.
			parts.add(RunEstimate.forRun(one, selection.getSelectedFor(group), owned, level,
				bonuses.current(), tier, planner.survivalIn(group), budget(group)));
		}

		return RunEstimate.merge(parts);
	}

	/**
	 * What this group can afford to protect, as a spendable tally.
	 *
	 * <p>One per group rather than one for the run, because groups are priced separately and a
	 * shared budget would let the first group priced quietly spend the second's coconuts. Two
	 * groups genuinely competing for the same payment is possible but rare, and over-promising
	 * within one group is the case that actually happens.
	 */
	private ProtectionBudget budget(PlantingGroup group)
	{
		Map<Integer, Integer> available = new java.util.HashMap<>();
		for (Seed seed : selection.getSelectedFor(group))
		{
			ProtectionPayment payment = ProtectionPayment.forSeed(seed);
			if (payment == null || !protection.isProtecting(group, seed))
			{
				continue;
			}
			available.put(payment.getItemID(),
				bank.getCount(payment.getItemID()) + carried.getCount(payment.getItemID()));
		}

		return new ProtectionBudget(available, seed -> protection.isProtecting(group, seed));
	}

	/**
	 * Spells out every bonus that went into the figures, and every one that did not.
	 *
	 * <p>All of this is detected rather than configured, which is the right default but leaves
	 * the player no way to check what the plugin thinks it can see. Saying so is the whole of
	 * the fix - and it also makes a wrong number diagnosable rather than merely wrong.
	 */
	private String describeGear(int level, RunEstimate estimate)
	{
		FarmingBonuses carried = bonuses.current();
		StringBuilder text = new StringBuilder(
			"<html>Estimated yield and XP, from your Farming level, gear, diaries, compost and "
				+ "protection.<br>Harvest-only runs count the harvest award alone.<br><br>")
			.append("At Farming level ").append(level).append(", with:<br>");

		text.append(carried.isMagicSecateurs()
			? "&bull; magic secateurs (+10% yield)<br>"
			: "&bull; <i>no magic secateurs</i><br>");
		text.append(carried.isFarmingCape()
			? "&bull; Farming cape (+5%, herbs only)<br>"
			: "&bull; <i>no Farming cape</i><br>");
		text.append(carried.isAttas()
			? "&bull; attas growing (+5% yield)<br>"
			: "&bull; <i>no attas planted</i><br>");
		text.append(carried.getOutfitBonus() > 0
			? String.format("&bull; Farmer's outfit (+%.1f%% xp)<br>", carried.getOutfitBonus() * 100)
			: "&bull; <i>no Farmer's outfit</i><br>");

		if (estimate.getSurvivalChance() < 0.999)
		{
			// The old wording said paying farmers was not assumed and would raise this. It had
			// been true once and was not any more: protection is read from the Protect boxes and
			// applied per patch, along with the compost and whichever patches this account's
			// unlocks make disease-free.
			text.append(String.format("<br>Discounted for disease: about %d%% of patches "
					+ "should reach harvest.<br>Protected patches are already counted as "
					+ "surviving.<br>",
				Math.round(estimate.getSurvivalChance() * 100)));
		}

		return text.append("</html>").toString();
	}

	@SuppressWarnings("unused")
	private static String unusedGearSummary(FarmingBonuses carried, int level)
	{
		StringBuilder text = new StringBuilder("yield (xp) at level ").append(level);
		if (carried.isMagicSecateurs())
		{
			text.append(", secateurs");
		}
		if (carried.isFarmingCape())
		{
			text.append(", cape");
		}
		if (carried.isAttas())
		{
			text.append(", attas");
		}
		if (carried.getOutfitBonus() > 0)
		{
			text.append(String.format(", outfit +%.1f%%", carried.getOutfitBonus() * 100));
		}
		if (!carried.isMagicSecateurs())
		{
			text.append(", no secateurs");
		}
		return text.toString();
	}

	private Map<Seed, Integer> ownedSeeds()
	{
		Map<Seed, Integer> owned = new EnumMap<>(Seed.class);
		for (Seed seed : selection.getSelected())
		{
			owned.put(seed, seeds.getOwned(seed));
		}
		return owned;
	}

	private static String formatNumber(double value)
	{
		return value >= 10_000
			? String.format("%,dk", Math.round(value / 1000))
			: String.format("%,d", Math.round(value));
	}

}
