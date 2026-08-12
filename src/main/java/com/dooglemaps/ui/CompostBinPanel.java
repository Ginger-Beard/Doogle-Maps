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
import javax.swing.BorderFactory;
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
 * <p>The list is <b>what you own</b> out of the supercompostable table, not the whole table:
 * an icon for produce you have none of would be a choice you cannot act on, exactly the
 * reasoning the seed list follows. Counts come from the bank plus the pack, drawn as stack
 * numbers the way the bank draws them.
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

	private final CompostRunStore store;
	private final BankContents bank;
	private final CarriedItems carried;
	private final ItemManager itemManager;
	private final com.dooglemaps.data.ItemNames itemNames;

	private final JPanel grid = new JPanel();
	private final WrappedText message = new WrappedText();
	private final JCheckBox ashBox = new JCheckBox();

	CompostBinPanel(CompostRunStore store, BankContents bank, CarriedItems carried,
		ItemManager itemManager, com.dooglemaps.data.ItemNames itemNames)
	{
		this.store = store;
		this.bank = bank;
		this.carried = carried;
		this.itemManager = itemManager;
		this.itemNames = itemNames;

		setLayout(new BorderLayout(0, 4));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		JLabel heading = new JLabel("Bin fill for the run");
		heading.setFont(FontManager.getRunescapeSmallFont());
		heading.setForeground(TEXT);
		heading.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));

		grid.setBackground(getBackground());
		message.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));

		Controls.styleCheckBox(ashBox);
		ashBox.setBackground(getBackground());
		ashBox.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
		ashBox.setText("Upgrade with volcanic ash");
		ashBox.addActionListener(e -> store.setAshing(ashBox.isSelected()));

		JPanel body = new JPanel(new BorderLayout(0, 4));
		body.setBackground(getBackground());
		body.add(grid, BorderLayout.NORTH);
		body.add(message, BorderLayout.CENTER);
		body.add(ashBox, BorderLayout.SOUTH);

		add(heading, BorderLayout.NORTH);
		add(body, BorderLayout.CENTER);
	}

	/** Rebuilt on the sidebar's ordinary refresh, like every other section that reads stores. */
	void refresh()
	{
		grid.removeAll();

		int shown = 0;
		for (int itemId : Compostables.superCompostables())
		{
			int owned = bank.getCount(itemId) + carried.getCountIncludingNoted(itemId);
			if (owned <= 0)
			{
				continue;
			}
			grid.add(buildIcon(itemId, owned));
			shown++;
		}

		// The grid wants full rows so the last one is not stretched into giant cells.
		int rows = Math.max(1, (shown + SLOTS_PER_ROW - 1) / SLOTS_PER_ROW);
		grid.setLayout(new GridLayout(rows, SLOTS_PER_ROW, 2, 2));
		for (int filler = shown; filler < rows * SLOTS_PER_ROW; filler++)
		{
			JPanel blank = new JPanel();
			blank.setBackground(getBackground());
			grid.add(blank);
		}

		if (shown == 0)
		{
			message.setForeground(TEXT);
			message.setText(bank.hasBeenSeen()
				? "Nothing supercompostable owned - pineapples and watermelons are the "
					+ "usual fills."
				: "Open a bank to see what you could fill the bins with.");
			message.setVisible(true);
		}
		else
		{
			message.setVisible(false);
		}

		int ashOwned = bank.getCount(CompostBin.VOLCANIC_ASH)
			+ carried.getCountIncludingNoted(CompostBin.VOLCANIC_ASH);
		ashBox.setSelected(store.isAshing());
		ashBox.setToolTipText(Tooltips.html("25 ash a bin, 50 for the guild's big one - the "
			+ "whole bin upgrades to ultracompost while the compost is still in it.<br>You own "
			+ "<b>" + ashOwned + "</b> volcanic ash."));

		revalidate();
		repaint();
	}

	private JLabel buildIcon(int itemId, int owned)
	{
		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(SLOT_WIDTH, SLOT_HEIGHT));
		icon.setHorizontalAlignment(SwingConstants.CENTER);

		boolean selected = store.getFillItem() == itemId;
		Icons.setStack(icon, itemManager.getImage(itemId, owned, true));
		icon.setOpaque(selected);
		icon.setBackground(selected ? SELECTED_BACKGROUND : null);
		icon.setBorder(selected
			? BorderFactory.createLineBorder(SELECTED_BORDER, 1)
			: BorderFactory.createEmptyBorder(1, 1, 1, 1));

		String name = itemNames.get(itemId, "This");
		icon.setToolTipText(Tooltips.html("<b>" + name + "</b><br>" + owned
			+ " owned - a bin takes 15 un-noted, the big one 30.<br>"
			+ (selected ? "Picked; click to clear." : "Click to fill the bins with these.")));

		icon.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		icon.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent event)
			{
				// The store toggles - picking the picked one clears it - and this panel's
				// refresh redraws the tint on the sidebar's next pass; the click's own
				// refresh below makes it immediate.
				store.setFillItem(itemId);
				refresh();
			}
		});
		return icon;
	}
}
