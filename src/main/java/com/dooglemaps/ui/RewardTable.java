package com.dooglemaps.ui;

import com.dooglemaps.route.RunEstimate;
import java.awt.BorderLayout;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * What each crop in the run is worth.
 *
 * <p>One row per crop rather than a single total, because the total answers the wrong
 * question: "84 crops" says nothing about whether a run is worth doing, and what comes home
 * is the point.
 *
 * <p>A single figure per crop rather than a grid of possibilities. The compost dropdown beside
 * each tab's seed list says what is going on the ground, and every other bonus — secateurs,
 * the cape, attas, the outfit, the diary rewards — is detected from the player. So there is one
 * correct answer to show; hovering explains what went into it.
 */
class RewardTable extends JPanel
{
	private final DataTable table = new DataTable("crop", "yield", "xp");
	private final JLabel caption = new JLabel();

	RewardTable()
	{
		setLayout(new BorderLayout(0, 2));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		caption.setFont(FontManager.getRunescapeSmallFont());
		caption.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		caption.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));

		add(table, BorderLayout.CENTER);
		add(caption, BorderLayout.SOUTH);
	}

	/**
	 * Redraws for one run.
	 *
	 * @param gearTooltip an explanation of every bonus applied, shown on hover
	 */
	void setData(RunEstimate estimate, String gearTooltip)
	{
		table.clearRows();

		if (estimate == null || estimate.isEmpty())
		{
			setVisible(false);
			return;
		}

		for (RunEstimate.Line line : estimate.getLines())
		{
			table.addRow(line.getSeed().getName(), null,
				String.valueOf(Math.round(line.getExpectedYield())),
				DataTable.shortNumber(line.getExpectedXp()));
		}

		table.addTotalRow("total",
			String.valueOf(Math.round(estimate.getTotalYield())),
			DataTable.shortNumber(estimate.getTotalXp()));

		caption.setText("what this assumes");
		caption.setToolTipText(gearTooltip);
		setToolTipText(gearTooltip);
		setVisible(true);
	}
}
