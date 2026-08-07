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
import com.dooglemaps.data.Seed;
import com.dooglemaps.route.RunEstimate;
import com.dooglemaps.route.RunPlanner;
import com.dooglemaps.route.RunStop;
import com.dooglemaps.state.CompostSelectionStore;
import com.dooglemaps.state.FarmingBonusStore;
import com.dooglemaps.state.RunTypeStore;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.state.SeedSelectionStore;
import com.dooglemaps.timer.FarmingBonuses;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
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
	private static final Color BAD = new Color(0xC4, 0x3B, 0x3B);

	/** Between the two run buttons, so the destructive one is not touching the routine one. */
	private static final int BUTTON_GAP = 4;

	// There was a RUNNABLE set here, listing the patch types worth offering as a run. It was dead
	// — offeredOptions() asks PlantingGroups.runOptions() — and it had drifted: PlantingGroups
	// gained CACTUS and this copy never did. Two lists of the same thing, one of them unread and
	// wrong, is worse than one, so it is gone rather than corrected.

	private final RunPlanner planner;
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

	/** Everywhere the planned run will go. See {@link #buildDestinations}. */
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

	/**
	 * The one sentence about the table that people trip over, on screen rather than on a hover.
	 *
	 * <p>A herb run hands you a pack of snapdragons on the way round and <b>none of them are in
	 * this table</b> — they grew from the seeds you planted last time. The table prices the seeds
	 * going in now, and what they give you when you come back for them.
	 *
	 * <p>That was three paragraphs at the top of the gear tooltip, which is to say it was invisible:
	 * nobody hovers a table long enough to read to paragraph two, and the person who needs this
	 * does not yet know there is anything to ask about. It is a caption because it is true of every
	 * run and never changes — the tooltip keeps what varies.
	 */
	private final JLabel caption = new JLabel();

	/**
	 * The run controls, which are two different shapes.
	 *
	 * <p>Before a run there is one decision — start — so the button takes the whole width. During
	 * one there are two, and they are not equals: stopping is rare and destructive, skipping is
	 * routine. So skip gets the room and stop gets the colour, rather than splitting evenly and
	 * letting the dangerous one look like the ordinary one.
	 */
	private final JButton startStop = new JButton();
	private final JButton skipStep = new JButton();
	private final JPanel controls = new JPanel(new java.awt.GridBagLayout());

	/**
	 * What the guide is asking for, repeated in the sidebar.
	 *
	 * <p>The on-screen panel is where you read this while playing — that is the whole reason it
	 * exists. This is for the moment you are looking at the sidebar anyway, deciding whether to
	 * skip: a skip button with no statement of what it would skip is a button you cannot press
	 * with any confidence.
	 */
	private final JLabel currentStep = new JLabel();

	/** Told what the guide is asking for, and how to wave it past. */
	private final com.dooglemaps.guide.GuideTracker guideTracker;

	RunPanel(PanelLayoutStore layout, PlantingGroups groups,
		ProtectionSelectionStore protection, BankContents bank,
		CarriedItems carried, RunPlanner planner,
		SeedSelectionStore selection, SeedInventoryStore seeds,
		RunTypeStore runTypes, FarmingBonusStore bonuses, CompostSelectionStore compost,
		com.dooglemaps.DoogleMapsConfig config, com.dooglemaps.guide.GuideTracker guideTracker)
	{
		this.guideTracker = guideTracker;
		this.config = config;
		this.layout = layout;
		this.groups = groups;
		this.protection = protection;
		this.bank = bank;
		this.carried = carried;
		this.planner = planner;
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

		rewardTable.setBackground(getBackground());
		rewardTable.setBorder(BorderFactory.createEmptyBorder(0, 6, 4, 6));
		rewardTable.setVisible(false);

		// A plain label rather than WrappedText: this sits inside a bordered panel, where
		// WrappedText's fixed wrap width overshoots and clips the last line. Two short lines that
		// do not need wrapping is the version that cannot go wrong - the same call RunPanel
		// already makes for the destination list's footnotes.
		caption.setFont(FontManager.getRunescapeSmallFont());
		caption.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		caption.setBorder(BorderFactory.createEmptyBorder(0, 6, 2, 6));
		caption.setText("<html>Counts the seeds going in,<br>not what you pick on the way round."
			+ "</html>");
		caption.setToolTipText(Tooltips.html("Clearing a patch harvests <i>last</i> run's crop. "
			+ "That is already in the ground and owes nothing to the seeds you are about to sow, "
			+ "so it is not in these figures."));
		caption.setVisible(false);

		Controls.styleButton(startStop);
		startStop.addActionListener(e -> toggleRun());

		Controls.styleButton(skipStep);
		skipStep.setText("Skip step");
		skipStep.setToolTipText(Tooltips.text("Pass over what the guide is currently asking for. "
			+ "The rest of that patch's work still appears - waving past a payment does not skip "
			+ "the planting."));
		skipStep.addActionListener(e ->
		{
			guideTracker.skipCurrentStep();
			refresh();
		});

		// Weighted rather than split evenly, and the weights are the point: skipping is routine and
		// stopping is not, so the ordinary action gets the room and the destructive one gets the
		// colour. Rebuilt on every refresh because the row is one button before a run and two
		// during it; see updateControls.
		controls.setBackground(getBackground());

		currentStep.setFont(FontManager.getRunescapeSmallFont());
		currentStep.setBorder(BorderFactory.createEmptyBorder(4, 6, 0, 6));
		currentStep.setVisible(false);

		JPanel choices = new JPanel(new BorderLayout(0, 0));
		choices.setBackground(getBackground());
		choices.add(typeSelection, BorderLayout.NORTH);
		choices.add(noSeeds, BorderLayout.CENTER);

		JPanel body = new JPanel(new BorderLayout(0, 4));
		body.setBackground(getBackground());
		body.add(choices, BorderLayout.NORTH);
		JPanel table = new JPanel(new BorderLayout(0, 0));
		table.setBackground(getBackground());
		table.add(rewardTable, BorderLayout.CENTER);
		table.add(caption, BorderLayout.SOUTH);

		JPanel plan = new JPanel(new BorderLayout(0, 2));
		plan.setBackground(getBackground());
		plan.add(table, BorderLayout.CENTER);
		plan.add(buildDestinations(), BorderLayout.SOUTH);
		body.add(plan, BorderLayout.CENTER);

		JPanel header = new JPanel(new BorderLayout(0, 0));
		header.setBackground(getBackground());
		header.add(controls, BorderLayout.NORTH);
		header.add(currentStep, BorderLayout.CENTER);

		updateControls(false);
		add(header, BorderLayout.NORTH);
		add(body, BorderLayout.CENTER);
	}

	/**
	 * Puts the buttons and the current step into the state the run is in.
	 *
	 * <p>Stopping is coloured rather than merely labelled. It is the one control here that throws
	 * work away, and before a run it is not offered at all — so it has no chance to become a shape
	 * the eye skips over, and it should not look like the button beside it.
	 */
	private void updateRunControls(boolean running)
	{
		startStop.setText(running ? "Stop run" : "Start run");
		startStop.setForeground(running ? BAD : ColorScheme.TEXT_COLOR);

		updateControls(running);
		refreshCurrentStep();
	}

	/**
	 * Redraws just the current-step line and the button that acts on it. Must run on the EDT.
	 *
	 * <h2>Why this is separate from {@link #refresh}</h2>
	 *
	 * The guide re-derives its step every game tick, but the sidebar is redrawn from a 20-second
	 * idle timer — so this line sat up to twenty seconds behind the on-screen panel, which reads
	 * the same tracker per frame. Two places showing the same instruction and disagreeing for most
	 * of the time between stops.
	 *
	 * <p>Calling the full {@link #refresh} on the tick would fix the staleness by rebuilding the
	 * reward table, the destination list and the stop list a hundred times a minute. This touches
	 * a label and a button instead, so the plugin can afford to call it on every tick.
	 */
	void refreshCurrentStep()
	{
		com.dooglemaps.guide.GuideStep step =
			planner.isActive() ? guideTracker.getCurrentStep() : null;
		skipStep.setEnabled(step != null);

		// Only while there is one. A "Current step:" label with nothing after it, sitting there for
		// the whole of a walk between stops, is a line that trains you to stop reading it.
		currentStep.setVisible(step != null);
		if (step != null)
		{
			currentStep.setForeground(config.guideHighlightColour());
			// The same wording the on-screen panel uses, wrapped to the sidebar's width — not the
			// tooltip width, which is deliberately wider than the panel and clipped the last
			// character off every line that wrapped.
			currentStep.setText(Tooltips.inPanel("Current step: " + step.getText()));
		}
	}

	/**
	 * Lays the control row out for the state the run is in.
	 *
	 * <p>Rebuilt rather than toggled because the two states are different shapes, not the same
	 * shape with something hidden: one full-width button before a run, two weighted ones during
	 * it. Hiding a component inside a grid leaves its column behind, which is how a 60/40 split
	 * becomes a 60% button with a gap next to it.
	 */
	private void updateControls(boolean running)
	{
		controls.removeAll();

		java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
		c.fill = java.awt.GridBagConstraints.HORIZONTAL;
		c.gridy = 0;

		if (running)
		{
			// A gap between them, because these two buttons are the same size, the same shape and
			// sit on the same dark background: abutting, they read as one control with a line down
			// it, and the one on the right ends the run. Colour alone was doing all the work of
			// telling them apart.
			c.gridx = 0;
			c.weightx = 0.6;
			c.insets = new java.awt.Insets(0, 0, 0, BUTTON_GAP);
			controls.add(skipStep, c);

			c.gridx = 1;
			c.weightx = 0.4;
			c.insets = new java.awt.Insets(0, 0, 0, 0);
			controls.add(startStop, c);
		}
		else
		{
			c.gridx = 0;
			c.weightx = 1;
			controls.add(startStop, c);
		}

		controls.revalidate();
		controls.repaint();
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
			// wrapped across the break. The contract gets one for the same reason a pair does —
			// it is the one line that is not a patch type, and sharing a row with the tail of the
			// list is what makes it read as another of them.
			if ((paired != null || option.getGroup().isContract()) && column == 1)
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
				? Tooltips.text("Harvest only: visit these to pick what is ready and nothing else "
					+ "- the patch is not cleared, composted or replanted")
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
			+ Plurals.of(destinationPatches, "patch)", "patches)"),
			destinationsVisible));
		toggleDestinations.setToolTipText(Tooltips.html("Everywhere this run will take you."
			+ "<br><br>Places only - which teleports to take is your call, and depends on what you "
			+ "have unlocked."));
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

		updateRunControls(running);
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

		// Rebuilt whether or not a run is under way, which it used not to be.
		//
		// Hiding the projection during a run was wrong on its own terms: what the trip is worth is
		// the thing you most want to check <i>while doing it</i> — deciding whether the last three
		// stops are worth the trip is a mid-run question, and answering it meant stopping the run.
		// It also changes as you go, since patches leave the plan as they are planted, so a live
		// figure is more use than the one you saw before setting off.
		//
		// The destination list likewise — and once it stays up, it is the only such list there is.
		// A second one used to unfold beneath it during a run, in the same "Name  (n)" shape, with
		// no heading to say how the two differed. The dropdown already updates as stops drop out
		// of the plan, so the pair were saying the same thing twice, one of them unlabelled.
		rebuildRewardTable(getSelectedTypes());
		rebuildDestinations(getSelectedTypes());

		if (running)
		{
			// The one thing that really is meaningless mid-run: a warning about a seed you would
			// have needed before setting off.
			noSeeds.setVisible(false);
		}
		else
		{
			updateNoSeeds(getSelectedTypes());
		}

		revalidate();
		repaint();
	}

	/**
	 * Names the ticked types nothing will be planted in.
	 *
	 * <p>Reported per type: a type with a seed picked for one of its groups and not the other is a
	 * subtler thing than this line is for, and the group with no seed still gets its patches
	 * counted as unfilled in the projection below.
	 *
	 * <h2>Asked per group, though, which it used not to be</h2>
	 *
	 * It read the type-wide selection, and a contract's seed is never in it — the contract's crop
	 * is derived from the assignment rather than picked, deliberately, so that nothing the player
	 * never chose gets persisted. A cactus contract with no ordinary cactus seed ticked therefore
	 * reported <i>"No seed picked for: cactus"</i> while the contract tab was sitting right above
	 * it showing the cactus seed already selected. Asking each ticked group what <i>it</i> will
	 * plant is the question this line always meant, and it happens to be the only one a derived
	 * selection can answer.
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
			if (nothingToPlantIn(type))
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
	 * Whether no group of this type that the run will actually plant in has a seed.
	 *
	 * <p>None rather than any, matching what the line says: naming a type here means nothing will
	 * go in the ground anywhere in it. A type with one group filled and another empty is a real
	 * situation and not what this warning is about — the projection below already counts the empty
	 * group's patches as unfilled.
	 */
	private boolean nothingToPlantIn(PatchImplementation type)
	{
		for (PlantingGroup group : groups.groupsFor(type))
		{
			if (!runTypes.isSelected(RunOption.full(group)) || runTypes.isHarvestOnly(group))
			{
				continue;
			}
			if (!selection.getSelectedFor(group).isEmpty())
			{
				return false;
			}
		}
		return true;
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

	/*
	 * There was a second list here, built during a run: the remaining stops, the supply stop, and
	 * the transports for the current leg. It is gone, and nothing replaced it.
	 *
	 * The stop rows were the destination dropdown's rows — the same "Name  (n)" formatting, drawn
	 * directly beneath it with no heading of their own, so the dropdown appeared to have a second
	 * body hanging off it. The dropdown was hidden mid-run when this was written, which is what
	 * made two lists sensible; now that it stays, one of them was simply redundant.
	 *
	 * The other two rows were already on the game screen, which is where you read a run from:
	 * GuideStepOverlay names the bank leg, and takes the same getCurrentTransports for its travel
	 * line. Keeping them here would have left a "Bank first" and a "via ..." floating under a list
	 * they were no longer part of.
	 */

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
			caption.setVisible(false);
			return;
		}

		RunEstimate estimate = estimateFor(types, owned, level);
		rewardTable.setData(estimate, describeGear(level, estimate));
		// Follows the table rather than being set independently: setData hides itself on an empty
		// estimate, and a caption under nothing is a stray sentence.
		caption.setVisible(rewardTable.isVisible());
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
	 *
	 * <h2>What this deliberately no longer says</h2>
	 *
	 * It used to open with three paragraphs explaining that the table prices the seeds going in
	 * rather than the crop you pick on the way round. That is the single most useful sentence
	 * about this table and it was in the worst possible place: paragraph two of a thousand
	 * characters, on a hover, above the part that actually changes.
	 *
	 * <p>So the explanation moved to {@link #caption}, where it is on screen without being asked
	 * for, and the tooltip kept the half that varies - the level, the gear, the discount. A
	 * tooltip is for "why is this number what it is"; a caption is for "what is this number".
	 */
	private String describeGear(int level, RunEstimate estimate)
	{
		FarmingBonuses carried = bonuses.current();
		StringBuilder text = new StringBuilder("At Farming level ").append(level)
			.append(", with:<br>");

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
					+ "surviving.",
				Math.round(estimate.getSurvivalChance() * 100)));
		}

		// Harvest-only runs are the exception to the caption, and it is worth one line rather
		// than a paragraph: there is no seed going in, so they count the harvest award alone.
		if (anyHarvestOnly())
		{
			text.append("<br><br>Harvest-only lines count the harvest award alone - nothing is "
				+ "being planted in those.");
		}

		return Tooltips.html(text.toString());
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
}
