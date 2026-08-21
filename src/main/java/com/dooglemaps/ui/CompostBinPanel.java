package com.dooglemaps.ui;

import com.dooglemaps.bank.BankContents;
import com.dooglemaps.data.CompostBin;
import com.dooglemaps.data.Compostables;
import com.dooglemaps.guide.CarriedItems;
import com.dooglemaps.state.CompostRunStore;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The compost tab's run controls: what fills the bins, and whether the ash upgrade is on.
 *
 * <p>The seed selector's sibling for the one tab that has no seeds. Same conventions on
 * purpose — owned items as bank-style icons, a click to pick, the green selection tint —
 * because a player who has picked a seed already knows how to pick a fill.
 *
 * <h2>Two sections, because the bin has two useful outcomes</h2>
 *
 * Supercompost first, since it is the reason most people fill a bin by hand, and ordinary
 * compost under it. Both are offered rather than only the first: an account with no pineapples
 * still has potatoes and grimy guams, and ordinary compost is what most of a farm run actually
 * gets treated with. Each collapses on its own — a bank full of allotment crops makes the
 * ordinary list long, and someone who only ever bins pineapples should be able to fold it away.
 *
 * <p>Each list is <b>what you own</b> out of its table rather than the whole table: an icon for
 * produce you have none of is a choice you cannot act on, which is the same reasoning the seed
 * list follows. Counts come from the bank plus the pack, drawn as stack numbers the way the
 * bank draws them.
 */
class CompostBinPanel extends JPanel
{
	/** Native item sprite size, shared with the seed selector. */
	private static final int SLOT_WIDTH = 36;
	private static final int SLOT_HEIGHT = 32;
	private static final int SLOTS_PER_ROW = 5;

	/** The seed selector's selection tint, so picked reads the same everywhere. */
	private static final Color SELECTED_BACKGROUND = new Color(0x2F, 0x4A, 0x2A);
	private static final Color SELECTED_BORDER = new Color(0x7F, 0xB2, 0x4A);

	private static final Color TEXT = new Color(0xDC, 0xDC, 0xDC);

	/** Amber, for the tomato warning: a note about what will happen, not a mistake yet. */
	private static final Color NOTE = new Color(0xC8, 0xA2, 0x2D);

	/** The game's own stack-number yellow, for the queue digit on a picked fill. */
	private static final Color PRIORITY_NUMBER = new Color(0xFF, 0xFF, 0x00);

	/**
	 * A fill slot that can wear its place in the queue.
	 *
	 * <p>Lifted wholesale from {@code SeedSelectorPanel.SeedIcon}, digit placement and all, so
	 * the two grids read as the same control: the number is the order the run reaches for them,
	 * 1 first, and it is drawn top-right because the game bakes its own stack count into the
	 * sprite's top-left. Hidden when a fill is alone, since a queue of one has no order.
	 */
	private static final class FillIcon extends JLabel
	{
		private int priority;

		void setPriority(int priority)
		{
			if (this.priority != priority)
			{
				this.priority = priority;
				repaint();
			}
		}

		@Override
		protected void paintComponent(java.awt.Graphics graphics)
		{
			super.paintComponent(graphics);
			if (priority <= 0)
			{
				return;
			}

			String digits = String.valueOf(priority);
			graphics.setFont(FontManager.getRunescapeSmallFont());
			int x = getWidth() - graphics.getFontMetrics().stringWidth(digits) - 3;
			int y = graphics.getFontMetrics().getAscent() + 1;
			graphics.setColor(Color.BLACK);
			graphics.drawString(digits, x + 1, y + 1);
			graphics.setColor(PRIORITY_NUMBER);
			graphics.drawString(digits, x, y);
		}
	}

	/**
	 * Shared across the tabs like the seed list's own key, so folding a section stays folded.
	 *
	 * <p>Only one tab shows these, so the sharing buys nothing today — it is here because the
	 * layout store's keys are a flat namespace and a bare "super" would be the sort of name
	 * another section could reasonably want later.
	 */
	private static final String SUPER_KEY = "binFillSuper";
	private static final String ORDINARY_KEY = "binFillOrdinary";
	private static final String FODDER_KEY = "binFodder";

	/**
	 * The outer collapse, over everything on this tab.
	 *
	 * <h2>Why the whole tab folds rather than each list separately</h2>
	 *
	 * The three inner sections fold on their own, and that was all there was — so a player who
	 * had finished configuring compost still gave up most of the sidebar to it, and the run
	 * planner and patch statuses underneath were pushed off the bottom. Which fills to use and
	 * which crops to spare are settled once and then left alone for weeks, which is exactly the
	 * shape the seed selector's own "Select seed" collapse exists for. Same idiom, same store,
	 * same persistence across tabs. Asked for from play.
	 */
	private static final String ALL_KEY = "binAll";

	private final CompostRunStore store;
	private final BankContents bank;
	private final CarriedItems carried;
	private final ItemManager itemManager;
	private final com.dooglemaps.data.ItemNames itemNames;
	private final PanelLayoutStore layout;

	private final JButton superHeading = new JButton();
	private final JButton ordinaryHeading = new JButton();
	private final JPanel superGrid = new JPanel();
	private final JPanel ordinaryGrid = new JPanel();
	private final WrappedText message = new WrappedText();
	private final WrappedText tomatoNote = new WrappedText();
	private final JCheckBox ashBox = new JCheckBox();

	private final JButton fodderHeading = new JButton();
	private final JPanel fodderGrid = new JPanel();
	private final JCheckBox fodderBox = new JCheckBox();
	private final WrappedText fodderNote = new WrappedText();

	/** The outer collapse's toggle, doubling as the tab's heading. See {@link #ALL_KEY}. */
	private final JButton heading = new JButton();

	/** Everything the outer collapse hides: both lists, both checkboxes and the fodder group. */
	private final JPanel allBody = new JPanel(new BorderLayout(0, 0));

	private boolean allVisible;

	CompostBinPanel(PanelLayoutStore layout, CompostRunStore store, BankContents bank,
		CarriedItems carried, ItemManager itemManager,
		com.dooglemaps.data.ItemNames itemNames)
	{
		this.layout = layout;
		this.store = store;
		this.bank = bank;
		this.carried = carried;
		this.itemManager = itemManager;
		this.itemNames = itemNames;

		setLayout(new BorderLayout(0, 4));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		// Kept, and now inside the outer collapse: it labels the two lists above the fodder
		// group, which are the guild bin's alone. The outer heading cannot carry that name
		// because it now covers the allotment bins too.
		JLabel guildHeading = new JLabel("Farming Guild bin");
		guildHeading.setFont(FontManager.getRunescapeSmallFont());
		guildHeading.setForeground(TEXT);
		guildHeading.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));

		allVisible = layout.isOpen(ALL_KEY, true);
		heading.setFont(FontManager.getRunescapeSmallFont());
		Controls.styleButton(heading);
		heading.addActionListener(e ->
		{
			allVisible = !allVisible;
			allBody.setVisible(allVisible);
			layout.setOpen(ALL_KEY, allVisible);
			updateHeading();
			revalidate();
		});

		superGrid.setBackground(getBackground());
		ordinaryGrid.setBackground(getBackground());
		message.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
		tomatoNote.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
		tomatoNote.setForeground(NOTE);
		tomatoNote.setVisible(false);

		wire(superHeading, SUPER_KEY, superGrid);
		wire(ordinaryHeading, ORDINARY_KEY, ordinaryGrid);
		wire(fodderHeading, FODDER_KEY, fodderGrid);

		fodderGrid.setBackground(getBackground());
		fodderNote.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
		Controls.styleCheckBox(fodderBox);
		fodderBox.setBackground(getBackground());
		fodderBox.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
		fodderBox.setText("Fill bins from your harvest");
		fodderBox.addActionListener(e ->
		{
			store.setFodderEnabled(fodderBox.isSelected());
			refresh();
		});

		Controls.styleCheckBox(ashBox);
		ashBox.setBackground(getBackground());
		ashBox.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
		ashBox.setText("Upgrade with volcanic ash");
		ashBox.addActionListener(e -> store.setAshing(ashBox.isSelected()));

		// Nested BorderLayouts rather than a vertical BoxLayout, which is the same shape the
		// seed selector uses and is chosen for a concrete reason: a BoxLayout on the Y axis
		// lays its children out at their MAXIMUM width and centres them on their alignmentX,
		// both of which default to "as wide as I asked for, in the middle". The two heading
		// buttons came out floating mid-panel at their text width instead of spanning the
		// sidebar. BorderLayout stretches NORTH and CENTER to the full width with no
		// alignment or maximum-size fiddling to keep in step with the content.
		JPanel lists = new JPanel(new BorderLayout(0, 4));
		lists.setBackground(getBackground());
		lists.add(section(superHeading, superGrid), BorderLayout.NORTH);
		lists.add(section(ordinaryHeading, ordinaryGrid), BorderLayout.CENTER);

		JPanel body = new JPanel(new BorderLayout(0, 4));
		body.setBackground(getBackground());
		body.add(lists, BorderLayout.NORTH);
		body.add(message, BorderLayout.CENTER);

		JPanel footer = new JPanel(new BorderLayout(0, 2));
		footer.setBackground(getBackground());
		footer.add(tomatoNote, BorderLayout.NORTH);
		footer.add(ashBox, BorderLayout.CENTER);
		body.add(footer, BorderLayout.SOUTH);

		// The second group: the seven bins beside the allotments, which have no bank near them
		// and are fed from what you have just picked. Its own heading rather than a third
		// collapsible under the guild's, because it answers a different question - not "what
		// shall I bring" but "what am I willing to spare".
		JPanel fodder = new JPanel(new BorderLayout(0, 4));
		fodder.setBackground(getBackground());
		fodder.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		// No heading over this group. It had one - "Allotment bins" - and it was one label too
		// many: the checkbox under it already says what the group does, and a title that only
		// restates the control beneath it is furniture. Settled with the owner.
		JPanel fodderTop = new JPanel(new BorderLayout(0, 2));
		fodderTop.setBackground(getBackground());
		fodderTop.add(fodderBox, BorderLayout.NORTH);
		fodderTop.add(fodderNote, BorderLayout.CENTER);

		fodder.add(fodderTop, BorderLayout.NORTH);
		fodder.add(section(fodderHeading, fodderGrid), BorderLayout.CENTER);

		// The guild's own label rides inside the collapse with the lists it names.
		JPanel guild = new JPanel(new BorderLayout(0, 4));
		guild.setBackground(getBackground());
		guild.add(guildHeading, BorderLayout.NORTH);
		guild.add(body, BorderLayout.CENTER);

		allBody.setBackground(getBackground());
		allBody.add(guild, BorderLayout.NORTH);
		allBody.add(fodder, BorderLayout.CENTER);
		allBody.setVisible(allVisible);

		add(heading, BorderLayout.NORTH);
		add(allBody, BorderLayout.CENTER);
		updateHeading();
	}

	/**
	 * The outer heading, which says what is picked so a folded section is not a blank statement.
	 *
	 * <p>Counts the fills chosen for the guild bin and the crops chosen for the allotment bins
	 * together, because folded away they are one decision — "is compost set up" — and that is the
	 * question the label has to answer without being opened.
	 */
	private void updateHeading()
	{
		int picked = store.getFills().size() + store.getFodderCrops().size();
		heading.setText(Controls.collapseLabel(
			picked > 0 ? "Compost (" + picked + " picked)" : "Compost", allVisible));
	}

	/** Wires one heading button to fold its grid, remembering the choice like every section. */
	private void wire(JButton heading, String key, JPanel grid)
	{
		heading.setFont(FontManager.getRunescapeSmallFont());
		Controls.styleButton(heading);
		heading.addActionListener(e ->
		{
			layout.setOpen(key, !layout.isOpen(key, true));
			refresh();
		});
		grid.setVisible(layout.isOpen(key, true));
	}

	/** One heading with its grid under it, both spanning the sidebar. */
	private JPanel section(JButton heading, JPanel grid)
	{
		JPanel section = new JPanel(new BorderLayout(0, 2));
		section.setBackground(getBackground());
		section.add(heading, BorderLayout.NORTH);
		section.add(grid, BorderLayout.CENTER);
		return section;
	}

	/** Rebuilt on the sidebar's ordinary refresh, like every other section that reads stores. */
	void refresh()
	{
		// Re-read rather than trust the field: the store is shared with the layout of every other
		// tab, and the count in the heading moves whenever a fill or a fodder crop is picked.
		allVisible = layout.isOpen(ALL_KEY, true);
		allBody.setVisible(allVisible);
		updateHeading();

		int superShown = fill(superGrid, Compostables.superCompostables(),
			layout.isOpen(SUPER_KEY, true), false);
		int ordinaryShown = fill(ordinaryGrid, Compostables.ordinaryCompostables(),
			layout.isOpen(ORDINARY_KEY, true), false);
		refreshFodder();

		superHeading.setText(Controls.collapseLabel(
			"Supercompost (" + superShown + ")", layout.isOpen(SUPER_KEY, true)));
		ordinaryHeading.setText(Controls.collapseLabel(
			"Compost (" + ordinaryShown + ")", layout.isOpen(ORDINARY_KEY, true)));

		// One message for both lists rather than one each: "you own nothing binnable" is the
		// useful statement, and saying it twice under two empty grids reads as two faults.
		if (superShown == 0 && ordinaryShown == 0)
		{
			message.setForeground(TEXT);
			message.setText(bank.hasBeenSeen()
				? "Nothing you own can go in a bin. Pineapples and watermelons make "
					+ "supercompost; potatoes and grimy herbs make ordinary compost."
				: "Open a bank to see what you could fill the bins with.");
			message.setVisible(true);
		}
		else
		{
			message.setVisible(false);
		}

		// Two things worth saying at the point the choice was made rather than leaving the
		// player to find out at a bin: the tomato trap, and that a queue mixing the tiers will
		// fill some bins with ordinary compost.
		tomatoNote.setText(fillWarning());
		tomatoNote.setVisible(!fillWarning().isEmpty());

		int ashOwned = bank.getCount(CompostBin.VOLCANIC_ASH)
			+ carried.getCountIncludingNoted(CompostBin.VOLCANIC_ASH);
		ashBox.setSelected(store.isAshing());
		ashBox.setToolTipText(Tooltips.html(ashTooltip(ashOwned)));

		revalidate();
		repaint();
	}

	/**
	 * The warning under the grids, or empty when the queue is unremarkable.
	 *
	 * <p>Mixed tiers is the subtle one and the reason it is said here: each bin is filled from
	 * a single item, so a queue of pineapples then potatoes is not a downgrade of anything —
	 * it means the pineapple bins come out super and the potato bins come out ordinary. That
	 * is a perfectly reasonable thing to want and a nasty surprise to discover, so it is
	 * stated rather than prevented.
	 */
	private String fillWarning()
	{
		// Fodder first, and it is the likelier trap of the two: a bin is fed from one crop at a
		// time, so sparing tomatoes and nothing else means every bin you feed comes out rotten.
		for (int crop : store.getFodderCrops())
		{
			if (Compostables.isRottenTomatoTrap(crop) && store.getFodderCrops().size() == 1)
			{
				return "Tomatoes are the only crop you have spared, and a bin filled only with "
					+ "them makes rotten tomatoes rather than compost. Spare another crop too.";
			}
		}

		for (int fill : store.getFills())
		{
			if (Compostables.isRottenTomatoTrap(fill))
			{
				return "A bin filled only with tomatoes makes rotten tomatoes, not compost. "
					+ "Add at least one item of another kind.";
			}
		}

		boolean anySuper = false;
		boolean anyOrdinary = false;
		for (int fill : store.getFills())
		{
			anySuper |= Compostables.isSuperCompostable(fill);
			anyOrdinary |= !Compostables.isSuperCompostable(fill);
		}
		if (anySuper && anyOrdinary)
		{
			return "Your queue mixes the tiers. Each bin is filled from one item, so the "
				+ "supercompostable ones give supercompost and the rest give ordinary compost.";
		}
		return "";
	}

	/**
	 * What the ash box explains, which depends on whether the fill will give it anything to do.
	 *
	 * <p>Ash upgrades supercompost and nothing else, so a ticked box over an ordinary fill is a
	 * combination that quietly does nothing — worth saying at the box rather than leaving the
	 * player to notice that the ultracompost never arrived.
	 */
	private String ashTooltip(int ashOwned)
	{
		String owned = "<br>You own <b>" + ashOwned + "</b> volcanic ash.";
		if (store.hasFill() && !store.fillMakesSupercompost())
		{
			// Wiki-checked: ash is used ON supercompost, and there is no ash route from
			// ordinary compost at all. The compost potion is the only way across that gap.
			return "Ash only works on supercompost, and at least one of your fills makes "
				+ "ordinary compost - those bins cannot be upgraded with ash. A compost "
				+ "potion turns a finished bin of ordinary compost into supercompost "
				+ "first.<br>Bins already holding supercompost are still upgraded." + owned;
		}
		return "25 ash a bin, 50 for the guild's big one - the whole bin upgrades to "
			+ "ultracompost while the compost is still in it." + owned;
	}

	/**
	 * Draws one list's owned items into its grid, and says how many that was.
	 *
	 * <p>The count is reported even while the section is folded, because it is what the heading
	 * shows — a folded "Compost (12)" is the whole reason folding is safe.
	 */
	/**
	 * The allotment-bins group: the toggle, the crop grid and the line explaining the trade.
	 *
	 * <p>Everything compostable in one grid rather than split by tier, because the question here
	 * is "would I spare this" and the answer does not sort by what it produces. The tier is still
	 * in each icon's tooltip, and {@code CompostBinPlan} prefers a supercompostable when it can
	 * fill a bin outright.
	 */
	private void refreshFodder()
	{
		boolean on = store.isFodderEnabled();
		fodderBox.setSelected(on);
		fodderBox.setToolTipText(Tooltips.html(
			"The seven bins beside the allotments have no bank near them - Falador is about "
				+ "65 tiles, Ardougne 96 - so carrying their fill across the map costs a farm "
				+ "run's worth of inventory.<br><br>With this on they are filled from the "
				+ "harvest you are already holding when you finish the patches beside them, "
				+ "and the run never banks for them."));

		int shown = fill(fodderGrid, Compostables.allotmentFodder(),
			on && layout.isOpen(FODDER_KEY, true), true);
		// "Crops you will spare" was the first wording and it reads both ways: to spare something
		// can mean to give it up or to save it from harm, and here the two readings are exact
		// opposites. Reported as contradictory against the line below it, which is about crops
		// going INTO a bin. "Compost these" has one meaning.
		fodderHeading.setText(Controls.collapseLabel(
			"Compost these (" + store.getFodderCrops().size() + ")",
			layout.isOpen(FODDER_KEY, true)));
		fodderHeading.setVisible(on);
		fodderGrid.setVisible(on && layout.isOpen(FODDER_KEY, true));

		if (!on)
		{
			fodderNote.setVisible(false);
			return;
		}

		fodderNote.setForeground(store.getFodderCrops().isEmpty() ? NOTE : TEXT);
		fodderNote.setText(store.getFodderCrops().isEmpty()
			? "Pick the allotment crops you would rather compost than keep."
			: shown == 0
				? "You own none of the crops you picked, so nothing will go in a bin this run."
				: "Put in the bin beside the patch that grew them, and never carried anywhere "
					+ "else.");
		fodderNote.setVisible(true);
	}

	private int fill(JPanel grid, Set<Integer> table, boolean open, boolean fodder)
	{
		grid.removeAll();
		grid.setVisible(open);

		int shown = 0;
		for (int itemId : table)
		{
			int owned = bank.getCount(itemId) + carried.getCountIncludingNoted(itemId);
			if (owned <= 0)
			{
				continue;
			}
			shown++;
			if (open)
			{
				grid.add(buildIcon(itemId, owned, fodder));
			}
		}

		if (!open || shown == 0)
		{
			grid.setLayout(new GridLayout(1, SLOTS_PER_ROW, 2, 2));
			return shown;
		}

		// Full rows, so the last one is not stretched into giant cells.
		int rows = Math.max(1, (shown + SLOTS_PER_ROW - 1) / SLOTS_PER_ROW);
		grid.setLayout(new GridLayout(rows, SLOTS_PER_ROW, 2, 2));
		for (int filler = shown; filler < rows * SLOTS_PER_ROW; filler++)
		{
			JPanel blank = new JPanel();
			blank.setBackground(getBackground());
			grid.add(blank);
		}
		return shown;
	}

	private JLabel buildIcon(int itemId, int owned, boolean fodder)
	{
		FillIcon icon = new FillIcon();
		icon.setPreferredSize(new Dimension(SLOT_WIDTH, SLOT_HEIGHT));
		icon.setHorizontalAlignment(SwingConstants.CENTER);

		boolean selected = fodder
			? store.getFodderCrops().contains(itemId)
			: store.getFills().contains(itemId);
		// No digit on the fodder grid: it is a permission set, and a set has no order to show.
		icon.setPriority(fodder ? 0 : store.priorityOf(itemId));
		Icons.setStack(icon, itemManager.getImage(itemId, owned, true));
		icon.setOpaque(selected);
		icon.setBackground(selected ? SELECTED_BACKGROUND : null);
		icon.setBorder(selected
			? BorderFactory.createLineBorder(SELECTED_BORDER, 1)
			: BorderFactory.createEmptyBorder(1, 1, 1, 1));

		String name = itemNames.get(itemId, "This");
		String makes = Compostables.isSuperCompostable(itemId)
			? "supercompost" : "ordinary compost";
		int priority = store.priorityOf(itemId);
		icon.setToolTipText(Tooltips.html("<b>" + name + "</b><br>" + owned
			+ " owned - a bin takes 15 un-noted, the big one 30.<br>A full bin of these makes "
			+ makes + ".<br>"
			+ (fodder
				? (selected
					? "Spared for the bins; click to keep them instead."
					: "Click to let the bins have these when you pick them.")
				: selected
					? (priority > 0 ? "Number " + priority + " in the queue; click to remove."
						: "Picked; click to clear.")
					: "Click to fill the bins with these.")));

		icon.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		icon.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent event)
			{
				// The store toggles - picking a picked one removes it, and a fresh pick goes
				// to the BACK of the queue, which is how reordering is done here and in the
				// seed grid alike. The click's own refresh redraws the digits immediately.
				if (fodder)
				{
					store.toggleFodderCrop(itemId);
				}
				else
				{
					// The store toggles - a fresh pick goes to the BACK of the queue, which is
					// how reordering is done here and in the seed grid alike.
					store.toggleFill(itemId);
				}
				refresh();
			}
		});
		return icon;
	}
}
