package com.dooglemaps.ui;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.state.CompostSelectionStore;
import com.dooglemaps.state.PlantableResolver;
import com.dooglemaps.state.PlantableResolver.Plantable;
import com.dooglemaps.data.Seed;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.state.SeedSelectionStore;
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
	private final SeedInventoryStore seeds;
	private final SeedSelectionStore selection;
	private final ItemManager itemManager;
	private final CompostSelectionStore compost;

	private final JPanel rows = new JPanel();
	private final JLabel heading = new JLabel();
	private final WrappedText message = new WrappedText();
	private JComboBox<CompostTier> compostBox;

	SeedSelectorPanel(PatchImplementation type, PlantableResolver resolver,
		SeedInventoryStore seeds, SeedSelectionStore selection, ItemManager itemManager,
		CompostSelectionStore compost)
	{
		this.compost = compost;
		this.type = type;
		this.resolver = resolver;
		this.seeds = seeds;
		this.selection = selection;
		this.itemManager = itemManager;

		setLayout(new BorderLayout(0, 4));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		heading.setFont(FontManager.getRunescapeSmallFont());
		heading.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		heading.setBorder(BorderFactory.createEmptyBorder(0, 6, 2, 6));

		message.setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));

		rows.setLayout(new GridLayout(0, SLOTS_PER_ROW, 2, 2));
		rows.setBackground(getBackground());
		rows.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));

		JPanel body = new JPanel(new BorderLayout());
		body.setBackground(getBackground());
		body.add(message, BorderLayout.NORTH);
		body.add(rows, BorderLayout.CENTER);

		add(heading, BorderLayout.NORTH);
		add(body, BorderLayout.CENTER);
		if (usesCompost())
		{
			add(buildCompostPicker(), BorderLayout.SOUTH);
		}
	}

	void refresh()
	{
		// Built once, so a profile load after construction has to be reflected here or the
		// dropdown keeps showing what it was created with.
		if (compostBox != null && compostBox.getSelectedItem() != compost.get(type))
		{
			compostBox.setSelectedItem(compost.get(type));
		}

		rows.removeAll();

		int picked = selection.getSelectedFor(type).size();
		heading.setText(picked > 0
			? "Seeds you own - " + picked + " picked for the run"
			: "Seeds you own - click to add to your run");

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

		List<Plantable> plantables = resolver.forPatchType(type, false);
		if (plantables.isEmpty())
		{
			message.setText("No " + type.getDisplayName().toLowerCase()
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
	private boolean usesCompost()
	{
		return !Seed.forPatchType(type).isEmpty()
			&& type != PatchImplementation.COMPOST
			&& type != PatchImplementation.BIG_COMPOST;
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
		box.setSelectedItem(compost.get(type));
		box.setFont(FontManager.getRunescapeSmallFont());
		box.setFocusable(false);
		Controls.styleComboBox(box);
		box.addActionListener(e ->
		{
			Object picked = box.getSelectedItem();
			if (picked instanceof CompostTier)
			{
				compost.set(type, (CompostTier) picked);
			}
		});

		picker.add(label, BorderLayout.WEST);
		picker.add(box, BorderLayout.CENTER);
		return picker;
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

		applySelectionStyling(icon, selection.isSelected(seed));

		if (usable)
		{
			icon.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			icon.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent event)
				{
					// The store fires a change, which repaints every tab — a seed picked here
					// is part of the run everywhere, not just on this tab.
					selection.toggle(seed);
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
