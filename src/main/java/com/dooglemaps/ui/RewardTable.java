package com.dooglemaps.ui;

import com.dooglemaps.route.RunEstimate;
import java.awt.BorderLayout;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;

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
	/**
	 * Headed as estimates, because that is what they are.
	 *
	 * <p>"yield" and "xp" read as counts of something that has happened; every figure here is a
	 * projection of a run not yet done. "Est" is doing real work in a column heading three
	 * characters wide.
	 */
	private final DataTable table = new DataTable("Projected", "Est Yield", "Est XP");

	RewardTable()
	{
		setLayout(new BorderLayout(0, 2));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// No caption. It said "what this assumes" and carried the gear tooltip, which the whole
		// table carries anyway - so it was a line of sidebar spent pointing at a hover that is
		// already there.
		add(table, BorderLayout.CENTER);
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

		setToolTipText(gearTooltip);
		setVisible(true);
	}
}
