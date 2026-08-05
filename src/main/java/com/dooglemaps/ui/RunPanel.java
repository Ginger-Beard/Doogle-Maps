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
import com.dooglemaps.bank.LoadoutItem;
import com.dooglemaps.bank.RunLoadout;
import com.dooglemaps.data.Seed;
import com.dooglemaps.route.InventoryPlan;
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
 * <p>Two states in one panel: before a run it is a list of patch types to tick and a
 * feasibility summary; during one it is the list of stops with the current target at the
 * top. That mirrors how a run actually goes — you decide once, then stop deciding.
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

	/** Loadout items named before the line turns into a count. */
	private static final int LOADOUT_NAMES_SHOWN = 4;

	private final RunPlanner planner;
	private final RunLoadout loadout;
	private final AvailabilityProfile availability;
	private final SeedSelectionStore selection;
	private final SeedInventoryStore seeds;

	private final RunTypeStore runTypes;
	private final FarmingBonusStore bonuses;
	private final CompostSelectionStore compost;

	/** A step lighter than the sidebar, so the expanded list reads as its own region. */
	private static final Color LIST_BACKGROUND = new Color(0x3A, 0x3A, 0x3A);

	private final Map<RunOption, JCheckBox> optionBoxes = new java.util.LinkedHashMap<>();
	private final JPanel typeSelection = new JPanel();
	private final JPanel stopList = new JPanel();
	private final WrappedText summary = new WrappedText();

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
		RunTypeStore runTypes, FarmingBonusStore bonuses, CompostSelectionStore compost)
	{
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

		stopList.setLayout(new BoxLayout(stopList, BoxLayout.Y_AXIS));
		stopList.setBackground(getBackground());

		summary.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

		rewardTable.setBackground(getBackground());
		rewardTable.setBorder(BorderFactory.createEmptyBorder(0, 6, 4, 6));
		rewardTable.setVisible(false);

		Controls.styleButton(startStop);
		startStop.addActionListener(e -> toggleRun());

		JPanel body = new JPanel(new BorderLayout(0, 4));
		body.setBackground(getBackground());
		body.add(typeSelection, BorderLayout.NORTH);
		JPanel plan = new JPanel(new BorderLayout(0, 2));
		plan.setBackground(getBackground());
		plan.add(summary, BorderLayout.NORTH);
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

	private void buildTypeBoxes()
	{
		for (RunOption option : groups.runOptions())
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
			// The stop list below is the live version of the same thing, current target first,
			// so a second static copy of it would only be a staler duplicate.
			destinations.setVisible(false);
			buildStopList();
			summary.setText(describeProgress());
		}
		else
		{
			summary.setText(describePlan());
			rebuildRewardTable(getSelectedTypes());
			rebuildDestinations(getSelectedTypes());
		}

		revalidate();
		repaint();
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

	private String describeProgress()
	{
		int remaining = planner.getRemaining().size();
		if (planner.isAtBankLeg())
		{
			Set<SeedSource> sources = planner.getSupplySources();
			if (sources.contains(SeedSource.SEED_VAULT) && !sources.contains(SeedSource.BANK))
			{
				return "Your seeds are in the seed vault, so the run starts at the Farming Guild.";
			}
			if (sources.contains(SeedSource.SEED_VAULT))
			{
				return "Some seeds are banked and some are in the vault - the Farming Guild has "
					+ "both, its bank chest is right next to the vault.";
			}
			// Worth separating, because they feel identical from the sidebar and are not. With
			// nothing picked the plugin cannot know what the trip needs, so a bank is a guess
			// rather than a plan - and someone who already has their seeds should be told that
			// picking them is what skips the detour, not left wondering why it wants a bank.
			if (!selection.hasAnySelection())
			{
				return "No seeds picked, so this heads for a bank. Click the seeds you want on "
					+ "each patch tab and the run will go straight to the patches instead.";
			}
			return "Heading to the nearest bank. The run continues once you get there.";
		}
		return remaining == 1
			? "One stop left."
			: remaining + " stops left.";
	}

	/**
	 * What the selected run would involve, before committing to it.
	 *
	 * <p>The interesting number is inventory slots, because a mixed run is limited by what
	 * it can carry home rather than by anything about the route.
	 */
	private String describePlan()
	{
		Set<PatchImplementation> types = getSelectedTypes();
		if (types.isEmpty())
		{
			return "Pick the patch types you want to run.";
		}

		// Only the seeds the player actually picked, and only for the types in this run.
		Set<Seed> chosen = new LinkedHashSet<>();
		Map<PatchImplementation, Integer> counts = new EnumMap<>(PatchImplementation.class);
		List<String> unpicked = new ArrayList<>();

		for (PatchImplementation type : types)
		{
			counts.put(type, availability.getAvailablePatches(type).size());

			Set<Seed> forType = selection.getSelectedFor(type);
			if (forType.isEmpty())
			{
				unpicked.add(type.getDisplayName().toLowerCase());
			}
			chosen.addAll(forType);
		}

		if (chosen.isEmpty())
		{
			return "Now pick your seeds - click them in the seed list on each patch tab.";
		}

		InventoryPlan plan = InventoryPlan.forRun(chosen, counts, hasSeedBox(), true, true);

		StringBuilder text = new StringBuilder();

		// The slot breakdown — "7 of 28 inventory slots: 3 seeds, 1 payments, 3 crops home" —
		// used to lead this. It was arithmetic the player never has to act on: the only decision
		// it feeds is whether the run fits, and that is worth a line only when the answer is no.
		// Sitting at the top of the panel it was the first thing read and the least useful.
		if (!plan.isFeasible())
		{
			text.append("That will not fit (")
				.append(plan.getTotalSlots()).append(" of ").append(InventoryPlan.TOTAL_SLOTS)
				.append(" slots) - drop a patch type or a seed.");
		}
		if (!unpicked.isEmpty())
		{
			text.append("\n\nNo seed picked for: ").append(String.join(", ", unpicked)).append('.');
		}

		appendLoadout(text, types);

		appendReward(text, types);
		return text.toString();
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

		if (actionable.isEmpty() || chosen.isEmpty() || level <= 0)
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
			Map<PatchImplementation, Integer> one =
				java.util.Collections.singletonMap(group.getType(), entry.getValue());
			Map<PatchImplementation, CompostTier> tier =
				java.util.Collections.singletonMap(group.getType(), compost.get(group));

			parts.add(RunEstimate.forRun(one, selection.getSelectedFor(group), owned, level,
				bonuses.current(), tier, planner.survivalAcross(types), budget(group)));
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
		StringBuilder text = new StringBuilder("<html>At Farming level ").append(level)
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
			text.append(String.format("<br>Discounted for disease: about %d%% of patches "
					+ "should reach harvest.<br>Paying farmers is not assumed, and would raise "
					+ "this.<br>",
				Math.round(estimate.getSurvivalChance() * 100)));
		}

		text.append("<br>Secateurs count in your inventory as well as worn. The cape, outfit and "
			+ "attas are read from your equipment and your anima patch. Diary rewards are applied "
			+ "per patch, so Catherby, Hosidius and the Farming Guild differ.");
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

	/**
	 * What the run is worth, over every patch it will actually visit.
	 *
	 * <p>The per-patch figures are already on each row's tooltip; this is the question those
	 * cannot answer, which is whether the whole circuit is worth doing before logging out.
	 */
	private void appendReward(StringBuilder text, Set<PatchImplementation> types)
	{
		RunEstimate estimate = RunEstimate.forRun(
			planner.countActionable(types),
			selection.getSelected(),
			ownedSeeds(),
			seeds.getFarmingLevel(),
			bonuses.current());

		// Everything per-crop is in the table below, so only what the table cannot say goes
		// here - which is the patches nothing will be planted in.
		int unfilled = estimate.getUnfilledPatches();
		if (unfilled > 0)
		{
			text.append("\n\n").append(unfilled)
				.append(unfilled == 1 ? " patch has" : " patches have")
				.append(" no seed to fill them.");
		}
	}

	/**
	 * What is still to be pulled out of the bank, in one line.
	 *
	 * <p>Only the count and the first few names. The bank itself highlights them, which is where
	 * you actually need to see them; this exists so you know to open a bank at all, and so the
	 * things the plugin cannot highlight — something you own none of — still get said.
	 */
	private void appendLoadout(StringBuilder text, Set<PatchImplementation> types)
	{
		List<LoadoutItem> items = loadout.forRun(types);
		if (items.isEmpty())
		{
			return;
		}

		List<String> toWithdraw = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		boolean anyUnknown = false;
		for (LoadoutItem item : items)
		{
			if (item.getNeed() == LoadoutItem.Need.WITHDRAW)
			{
				toWithdraw.add(item.getName().toLowerCase());
			}
			else if (item.getNeed() == LoadoutItem.Need.MISSING)
			{
				missing.add(item.getName().toLowerCase());
			}
			else if (item.getNeed() == LoadoutItem.Need.UNKNOWN)
			{
				anyUnknown = true;
			}
		}

		if (!toWithdraw.isEmpty())
		{
			text.append("\n\nFrom the bank: ").append(summarise(toWithdraw)).append('.');
		}
		if (!missing.isEmpty())
		{
			// Worth saying out loud: an item you own none of cannot be highlighted in the bank,
			// so silence here would read as "nothing else needed".
			text.append("\n\nNot found anywhere: ").append(summarise(missing)).append('.');
		}
		// Nothing is said for the not-yet-read case. A bank is only readable while it is open, so
		// before you have opened one this section has nothing to report — and "Open a bank and
		// this will say what to take" was a line telling you the plugin had no information yet,
		// which is what an empty section already says. The reason it existed still stands: the
		// alternative of listing unread items as *missing* would read as "your secateurs are
		// gone", and that is still avoided. Silence is simply the better way to avoid it.
		if (anyUnknown && !toWithdraw.isEmpty())
		{
			text.append(" Some of your bank has not been read yet.");
		}
	}

	/** A few names and a count, rather than a list that outgrows the sidebar. */
	private static String summarise(List<String> names)
	{
		if (names.size() <= LOADOUT_NAMES_SHOWN)
		{
			return String.join(", ", names);
		}
		return String.join(", ", names.subList(0, LOADOUT_NAMES_SHOWN))
			+ " and " + (names.size() - LOADOUT_NAMES_SHOWN) + " more";
	}

	/** How many of every seed the account has, for working out how far the picked ones stretch. */
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

	/** Whether any seed is sitting in a seed box, which is worth five slots. */
	private boolean hasSeedBox()
	{
		for (Seed seed : Seed.values())
		{
			if (seeds.getCount(seed, com.dooglemaps.state.SeedSource.SEED_BOX) > 0)
			{
				return true;
			}
		}
		return false;
	}
}
