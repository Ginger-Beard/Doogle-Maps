package com.dooglemaps.ui;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import java.util.Set;
import java.util.LinkedHashSet;
import javax.swing.JCheckBox;
import com.dooglemaps.bank.BankContents;
import com.dooglemaps.guide.CarriedItems;
import com.dooglemaps.state.ProtectionSelectionStore;
import com.dooglemaps.data.ProtectionPayment;
import com.dooglemaps.state.CompostSelectionStore;
import com.dooglemaps.state.PlantableResolver;
import com.dooglemaps.state.PlantableResolver.Plantable;
import com.dooglemaps.data.Seed;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.state.SeedSelectionStore;
import com.dooglemaps.timer.CropYieldModel;
import com.dooglemaps.state.SeedSource;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The seeds you own for this kind of patch, at the bottom of its tab.
 *
 * <p>Deliberately keyed on {@link PatchImplementation}, the same thing the tab is, so what
 * you are looking at and what you can plant in it are always the same set.
 */
class SeedSelectorPanel extends JPanel
{
	/** Native item sprite size, so the stack number stays crisp. */
	private static final int SLOT_WIDTH = 36;
	private static final int SLOT_HEIGHT = 32;

	/** Icons across. Five 36px slots plus gaps sit inside the 225px sidebar. */
	private static final int SLOTS_PER_ROW = 5;

	/** Tint behind a seed that is part of the run. */
	private static final Color SELECTED_BACKGROUND = new Color(0x2F, 0x4A, 0x2A);
	private static final Color SELECTED_BORDER = new Color(0x7F, 0xB2, 0x4A);

	private final PatchImplementation type;
	private final PlantableResolver resolver;

	/** The assigned contract, for the one tab whose seed is chosen for the player. */
	private final com.dooglemaps.state.ContractState contracts;
	private final SeedInventoryStore seeds;
	private final SeedSelectionStore selection;
	private final ItemManager itemManager;
	private final CompostSelectionStore compost;

	private final JPanel rows = new JPanel();
	/**
	 * The section's own toggle, doubling as its heading.
	 *
	 * <p>Open to start with, unlike the setup sections below it: which seed is going in the
	 * ground is a decision you make every run, not something you set once and forget.
	 */
	private final JButton heading = new JButton();
	private final JPanel seedBody = new JPanel(new BorderLayout());
	private final PlantingGroup group;
	private final ProtectionSelectionStore protection;
	private final BankContents bank;
	private final CarriedItems carried;
	private final com.dooglemaps.data.ItemNames itemNames;
	private final PanelLayoutStore layout;
	private boolean seedsVisible;

	/** Kept so the heading can be relabelled on a toggle without a full refresh. */
	private int pickedCount;
	private final WrappedText message = new WrappedText();
	private JComboBox<CompostTier> compostBox;

	/**
	 * Says what compost is doing here, when what it is doing is not the obvious thing.
	 *
	 * <p>Shown only on the types whose yield compost cannot move, and only once something other
	 * than untreated is picked — before that there is nothing to explain. Orange rather than red:
	 * it is telling you what you bought, not telling you off.
	 */
	private final WrappedText compostNote = new WrappedText();

	/**
	 * Whether to pay the farmer for this group.
	 *
	 * <p>Shown only when the picked seeds can actually be protected, and labelled with how many
	 * payments are in the bank — because "protect these" is a decision you can only really make
	 * knowing whether you own the fruit. Hidden entirely otherwise: herbs cannot be protected and
	 * a permanently disabled checkbox is worse than no checkbox.
	 */
	private final JPanel protectPanel = new JPanel();

	/**
	 * Offers protection when it is possible, and says what it would cost from the bank.
	 *
	 * <p>The payment is a property of the crop rather than of the patch type — a magic tree wants
	 * coconuts and an oak wants tomatoes — so this reads the seeds actually picked. With several
	 * picked that want different payments it names the count of distinct ones rather than
	 * pretending there is one answer.
	 */
	/**
	 * A protect row per picked crop that has a payment.
	 *
	 * <p>Per crop rather than per patch type, because the payment is a property of the crop: a
	 * magic tree wants 25 coconuts and a yew wants 10 cactus spines, so one switch for "trees"
	 * could not say what it would cost. It also means the question is simply never asked for
	 * herbs, which have no payment at all — nothing to show, nothing to store.
	 *
	 * <p>Each row says what the run would cost in that crop and how far the player's stock goes,
	 * which is the number that decides whether to tick it.
	 */
	private void updateProtection()
	{
		protectPanel.removeAll();
		boolean any = false;

		for (Seed seed : selection.getSelectedFor(group))
		{
			ProtectionPayment payment = ProtectionPayment.forSeed(seed);
			if (payment == null)
			{
				continue;
			}

			protectPanel.add(protectRow(seed, payment));
			any = true;
		}

		protectPanel.setVisible(any);
		protectPanel.revalidate();
	}

	private JCheckBox protectRow(Seed seed, ProtectionPayment payment)
	{
		JCheckBox box = new JCheckBox();
		Controls.styleCheckBox(box);
		box.setBackground(getBackground());
		box.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
		box.setSelected(protection.isProtecting(group, seed));
		box.addActionListener(e -> protection.setProtecting(group, seed, box.isSelected()));

		int patches = Math.max(1, patchCount);
		int wanted = payment.getQuantity() * patches;
		int held = bank.getCount(payment.getItemID()) + carriedCount(payment.getItemID());
		int covers = held / payment.getQuantity();

		// The payment item, not the crop being protected. getProduce() is what the payment *buys*
		// — so this read "Protect magic (25 magic)" where it should say coconuts. The two are
		// different halves of the same row and the accessor names do not make that obvious.
		String noun = itemNames.get(payment.getItemID(),
			payment.getProduce().getName()).toLowerCase();
		box.setText("Protect " + seed.getName().toLowerCase() + " (" + wanted + " " + noun + ")");

		if (!bank.hasBeenSeen())
		{
			box.setForeground(TEXT);
			box.setToolTipText("Open a bank to see whether you have " + wanted);
		}
		else if (held < wanted)
		{
			// The coverage count is the useful half. "You have 75 of 150" says you are short;
			// "covers 3 of 6" says what the run will actually do about it — the rest fall to the
			// next crop you picked, which is the whole point of picking two.
			box.setForeground(SHORT);
			box.setToolTipText("<html>You have <b>" + held + "</b> of the " + wanted
				+ " this run needs.<br>Covers " + covers + " of " + patches
				+ (patches == 1 ? " patch" : " patches") + " at " + payment.getQuantity()
				+ " each.<br>The rest go to the next crop you picked.</html>");
		}
		else
		{
			box.setForeground(TEXT);
			box.setToolTipText(held + " available, " + wanted + " needed for "
				+ patches + (patches == 1 ? " patch" : " patches"));
		}
		return box;
	}

	/** How many of an item are on the player, for the have-versus-need count. */
	private int carriedCount(int itemId)
	{
		return carried == null ? 0 : carried.getCount(itemId);
	}

	/** Warning colour for a payment the run is short of. */
	private static final java.awt.Color SHORT = new java.awt.Color(0xC4, 0x3B, 0x3B);

	/** The sidebar's ordinary text colour, for everything that is not a warning. */
	private static final java.awt.Color TEXT = new java.awt.Color(0xDC, 0xDC, 0xDC);

	/** How many patches of this group the run would service. Set by the panel on each refresh. */
	private int patchCount;

	/**
	 * Told how many patches this group has, so the payment total means something.
	 *
	 * <p>Passed in rather than worked out here: the panel already knows, and asking the planner
	 * from a Swing component would walk the run planner from the wrong thread.
	 */
	void setPatchCount(int patches)
	{
		this.patchCount = patches;
	}

	/**
	 * Shared by every patch group rather than kept per type.
	 *
	 * <p>Whether the seed list is folded up is a statement about the sidebar, not about herbs —
	 * see {@code PatchTypePanel.openKey}. Per type it had to be collapsed once per tab.
	 */
	private static final String SEEDS_KEY = "seeds";

	/** Adopts the current collapse state, which another tab may have changed. */
	void applyLayout()
	{
		seedsVisible = layout.isOpen(SEEDS_KEY, true);
		seedBody.setVisible(seedsVisible);
		updateHeading();
		revalidate();
	}

	/** Amber, so it reads as a note rather than the red used for a mistake. */
	private static final java.awt.Color NOTE = new java.awt.Color(0xC8, 0xA2, 0x2D);

	/**
	 * Shows or hides the "disease only" note for the tier currently picked.
	 *
	 * <p>The second half is not a general hint — it is what the estimate does. A protected crop
	 * survives outright, so the discount compost buys is one the payment has already bought, and
	 * treating the patch as well changes nothing at all. Worth saying before the buckets are
	 * carried rather than after.
	 */
	private void updateCompostNote()
	{
		boolean say = compostBox != null
			&& CropYieldModel.compostOnlyHelpsDisease(type)
			&& compost.get(group) != CompostTier.NONE;

		compostNote.setVisible(say);
		if (say)
		{
			compostNote.setText("Only lowers disease chance here, not yield. "
				+ "Not needed if you are paying for protection.");
		}
	}

	private void updateHeading()
	{
		heading.setText(Controls.collapseLabel(
			pickedCount > 0 ? "Select seed (" + pickedCount + " picked)" : "Select seed",
			seedsVisible));
	}

	SeedSelectorPanel(PanelLayoutStore layout, PlantingGroup group, PlantableResolver resolver,
		SeedInventoryStore seeds, SeedSelectionStore selection, ItemManager itemManager,
		CompostSelectionStore compost, ProtectionSelectionStore protection, BankContents bank,
		CarriedItems carried, com.dooglemaps.data.ItemNames itemNames,
		com.dooglemaps.state.ContractState contracts)
	{
		this.contracts = contracts;
		this.layout = layout;
		this.group = group;
		this.protection = protection;
		this.bank = bank;
		this.carried = carried;
		this.itemNames = itemNames;
		this.compost = compost;
		this.type = group.getType();
		this.resolver = resolver;
		this.seeds = seeds;
		this.selection = selection;
		this.itemManager = itemManager;

		setLayout(new BorderLayout(0, 4));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		// No gap above this. The seed list and the compost choice are both per-patch-type
		// settings, the same as the patch status and availability sections above them, so a break
		// here would divide things that belong together. The break moved down to sit above the
		// run section — see RunPanel — which is where the subject actually changes.
		setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

		// A title rather than a sentence. It read "Seeds you own - 1 picked for the run", which
		// described the list instead of naming it — and a description sitting where a heading
		// belongs makes a panel feel like prose you have to read rather than a form you fill in.
		// The count stays, in brackets, because it is the one part that changes.
		seedsVisible = layout.isOpen(SEEDS_KEY, true);
		compostNote.setForeground(NOTE);
		compostNote.setBorder(BorderFactory.createEmptyBorder(2, 6, 0, 6));
		compostNote.setVisible(false);

		protectPanel.setLayout(new javax.swing.BoxLayout(protectPanel, javax.swing.BoxLayout.Y_AXIS));
		protectPanel.setBackground(getBackground());
		protectPanel.setBorder(BorderFactory.createEmptyBorder(2, 0, 4, 0));

		heading.setFont(FontManager.getRunescapeSmallFont());
		Controls.styleButton(heading);
		heading.addActionListener(e ->
		{
			seedsVisible = !seedsVisible;
			seedBody.setVisible(seedsVisible);
			layout.setOpen(SEEDS_KEY, seedsVisible);
			updateHeading();
			revalidate();
		});

		message.setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));

		rows.setLayout(new GridLayout(0, SLOTS_PER_ROW, 2, 2));
		rows.setBackground(getBackground());
		rows.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));

		seedBody.setBackground(getBackground());
		seedBody.add(message, BorderLayout.NORTH);
		seedBody.add(rows, BorderLayout.CENTER);
		seedBody.setVisible(seedsVisible);

		add(heading, BorderLayout.NORTH);
		add(seedBody, BorderLayout.CENTER);

		JPanel below = new JPanel(new BorderLayout(0, 2));
		below.setBackground(getBackground());
		if (usesCompost())
		{
			below.add(buildCompostPicker(), BorderLayout.NORTH);
		}
		below.add(compostNote, BorderLayout.CENTER);
		below.add(protectPanel, BorderLayout.SOUTH);
		add(below, BorderLayout.SOUTH);
	}

	void refresh()
	{
		// Built once, so a profile load after construction has to be reflected here or the
		// dropdown keeps showing what it was created with.
		if (compostBox != null && compostBox.getSelectedItem() != compost.get(group))
		{
			compostBox.setSelectedItem(compost.get(group));
		}
		updateCompostNote();

		rows.removeAll();

		pickedCount = selection.getSelectedFor(group).size();
		updateHeading();
		updateProtection();

		// With nothing ever cached we genuinely do not know what the player owns, so say
		// that rather than showing an empty list that reads as "you have none".
		if (!seeds.hasEverBeenPopulated())
		{
			// The seed box is deliberately not mentioned: people move seeds in and out of it
			// constantly, so pointing a first-run prompt at it would be misleading.
			message.setText("Please visit your bank and/or seed vault first");
			message.setVisible(true);
			revalidate();
			repaint();
			return;
		}

		List<Plantable> plantables = group.isContract()
			? contractPlantables()
			: resolver.forPatchType(type, false);
		if (plantables.isEmpty())
		{
			message.setText(group.isContract()
				? "No seed for this contract could be found."
				: "No " + type.getDisplayName().toLowerCase()
					+ " seeds found in your bank, vault, seed box or inventory.");
			message.setVisible(true);
			revalidate();
			repaint();
			return;
		}

		message.setVisible(false);
		for (Plantable plantable : plantables)
		{
			rows.add(buildIcon(plantable));
		}
		// GridLayout only fills the row it is given, so pad the last one to keep the icons
		// left-aligned instead of stretched across the panel.
		for (int i = plantables.size() % SLOTS_PER_ROW; i > 0 && i < SLOTS_PER_ROW; i++)
		{
			rows.add(new JLabel());
		}

		revalidate();
		repaint();
	}

	/**
	 * Whether treating this kind of patch is even a thing.
	 *
	 * <p>You cannot compost a spirit tree or a compost bin, and offering the choice would
	 * imply the estimate changes with it.
	 */
	/**
	 * Whether treating this kind of patch is a decision worth offering.
	 *
	 * <h2>Asked of the yield model rather than listed</h2>
	 *
	 * It used to be "anything with a seed that is not a compost bin", which offered the dropdown
	 * on sixteen patch types where every value in it produced the same projection. Compost raises
	 * a yield through the lives mechanic, and most types do not have one — a tree gives one log
	 * however it was treated, and a grown bush holds a fixed stock. Only herbs, allotments, hops
	 * and giant seaweed actually respond.
	 *
	 * <p>Compost has a second effect, though, and it is why this is not simply "does the yield
	 * move": it also cuts the chance of disease. A fruit tree gives the same fruit however it was
	 * treated and is still very much worth composting, so the dropdown stays there and
	 * {@link #compostNote} says what it is buying. See {@code CropYieldModel.compostMatters}.
	 */
	private boolean usesCompost()
	{
		return CropYieldModel.compostMatters(type);
	}

	/**
	 * The compost this kind of patch will be treated with.
	 *
	 * <p>Sits with the seeds because it is the same kind of decision — what you are taking for
	 * this patch type — and because compost is worth more to a yield than every other bonus
	 * combined. Stating it once here is what lets the run summary give a single honest number
	 * instead of a grid of possibilities.
	 */
	private JPanel buildCompostPicker()
	{
		JPanel picker = new JPanel(new BorderLayout(4, 0));
		picker.setBackground(getBackground());
		picker.setBorder(BorderFactory.createEmptyBorder(6, 6, 0, 6));

		JLabel label = new JLabel("Treat with");
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		compostBox = new JComboBox<>(CompostTier.values());
		JComboBox<CompostTier> box = compostBox;
		box.setSelectedItem(compost.get(group));
		box.setFont(FontManager.getRunescapeSmallFont());
		box.setFocusable(false);
		Controls.styleComboBox(box);
		box.addActionListener(e ->
		{
			Object picked = box.getSelectedItem();
			if (picked instanceof CompostTier)
			{
				compost.set(group, (CompostTier) picked);
				updateCompostNote();
			}
		});

		picker.add(label, BorderLayout.WEST);
		picker.add(box, BorderLayout.CENTER);
		return picker;
	}

	/**
	 * The single seed a contract tab shows.
	 *
	 * <p>Unowned seeds are included here and nowhere else, which is the whole difference. An
	 * ordinary tab lists what you have, because it is asking you to choose; this one is telling
	 * you what Jane asked for, and a blank tab would say "no contract" when the truth is "you do
	 * not own the seed" — which is a thing to go and fix rather than a thing to be silent about.
	 * It draws greyed, exactly as an unusable seed does anywhere else, and the loadout says the
	 * same in words before you set off.
	 */
	private List<Plantable> contractPlantables()
	{
		Seed wanted = contracts.getContractSeed();
		if (wanted == null)
		{
			return java.util.Collections.emptyList();
		}

		List<Plantable> only = new java.util.ArrayList<>();
		for (Plantable plantable : resolver.forPatchType(type, true))
		{
			if (plantable.getSeed() == wanted)
			{
				only.add(plantable);
			}
		}
		return only;
	}

	/**
	 * One seed, drawn the way the bank draws it.
	 *
	 * <p>{@code ItemManager.getImage(id, quantity, stackable)} renders the stack number onto
	 * the sprite in the game's own font and colours, so the counts read exactly as they do
	 * in a bank tab rather than as Swing text bolted alongside.
	 */
	private JLabel buildIcon(Plantable plantable)
	{
		Seed seed = plantable.getSeed();

		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(SLOT_WIDTH, SLOT_HEIGHT));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		icon.setToolTipText(buildTooltip(plantable));

		// Seeds stack, so the quantity is drawn as a stack number rather than repeated.
		// Goes through Icons so a seed that is greyed out still shows up — see setStack.
		//
		// The sapling rather than the seed for a tree: that is the item you carry to the
		// patch, and the one you are looking for in the bank.
		Icons.setStack(icon,
			itemManager.getImage(seed.getPlantedItemID(), plantable.getOwned(), true));

		// A seed you cannot plant yet still belongs in the list — you own it, and knowing it
		// is waiting for a level is useful — but it should not look available.
		boolean usable = plantable.isLevelMet() && plantable.isUsable();
		icon.setEnabled(usable);

		applySelectionStyling(icon, selection.isSelected(group, seed));

		// A contract seed renders as picked because it *is* picked — the group's type narrows the
		// list to that patch's seeds and the contract narrows it to one. It simply must not be
		// clickable: there is no other answer to offer, and a click that silently did nothing
		// would read as the tab being broken.
		if (usable && !group.isContract())
		{
			icon.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			icon.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent event)
				{
					// The store fires a change, which repaints every tab — a seed picked here
					// is part of the run everywhere, not just on this tab.
					selection.toggle(group, seed);
				}
			});
		}

		return icon;
	}

	/** Marks a seed as part of the run, or clears the mark. */
	private static void applySelectionStyling(JLabel icon, boolean selected)
	{
		icon.setOpaque(selected);
		icon.setBackground(selected ? SELECTED_BACKGROUND : null);
		icon.setBorder(selected
			? BorderFactory.createLineBorder(SELECTED_BORDER, 1)
			: BorderFactory.createEmptyBorder(1, 1, 1, 1));
	}

	private String buildTooltip(Plantable plantable)
	{
		StringBuilder text = new StringBuilder("<html><b>")
			.append(plantable.getSeed().getName())
			.append("</b><br>Farming level ")
			.append(plantable.getSeed().getLevelRequirement());

		if (!plantable.isLevelMet())
		{
			text.append(" — you have ").append(seeds.getFarmingLevel());
		}

		Seed seed = plantable.getSeed();
		int perPatch = seed.getSeedsPerPatch();
		text.append("<br>").append(perPatch)
			.append(seed.isSapling() ? " sapling" : " seed").append(perPatch == 1 ? "" : "s")
			.append(" per patch");

		// The distinction people actually trip over: you cannot plant an acorn. Say so rather
		// than greying the icon out and leaving them to work out why.
		if (plantable.needsPotting())
		{
			int unpotted = plantable.getOwned() - plantable.getPlantable();
			text.append("<br><i>").append(unpotted)
				.append(unpotted == 1 ? " seed still needs" : " seeds still need")
				.append(" potting into a sapling</i>");
		}
		else if (plantable.isLevelMet() && plantable.getPlantable() < perPatch)
		{
			text.append("<br>Not enough for a patch");
		}

		// Where they are, and how stale that is, so "40 ranarr" can be trusted or not.
		for (SeedSource source : SeedSource.values())
		{
			int count = seeds.getCount(plantable.getSeed(), source);
			if (count <= 0)
			{
				continue;
			}
			text.append("<br>").append(source.getDisplayName()).append(": ").append(count);
			long lastSeen = seeds.getLastSeen(source);
			if (source.isPersisted() && lastSeen > 0)
			{
				text.append(" <i>(seen ").append(TimeFormat.since(lastSeen)).append(")</i>");
			}
		}

		return text.append("</html>").toString();
	}
}
