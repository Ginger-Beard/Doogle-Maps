package com.dooglemaps.ui;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.validate.CropHarvestStats;
import com.dooglemaps.validate.HarvestStatsStore;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;

/**
 * What your patches have actually given you, over the lifetime of the account.
 *
 * <p>The numbers were collected to check the plugin's own arithmetic — every finished patch
 * is recorded with its prediction beside the actual — but they are the interesting ones to
 * look at as a player too, so they are shown rather than only written to a CSV. The panel
 * serves both readings at once: the "avg" and "pred" columns side by side are the validation,
 * and are also just how well a crop is treating you.
 *
 * <p>Its own top-level tab rather than a section under the run controls. It is a history
 * rather than something you act on mid-run, so it neither needs to be on screen while you
 * farm nor deserves to be buried under a hundred patch rows when you do want it.
 */
class HarvestStatsPanel extends JPanel
{
	/** Crops shown before the list is cut off. Beyond this it stops being readable. */
	private static final int MAX_ROWS = 12;

	private final HarvestStatsStore stats;

	private final JPanel body = new JPanel();
	private final WrappedText headline = new WrappedText();
	/**
	 * Patches, items, average.
	 *
	 * <p>The patch count is here rather than in the tooltip because without it the other two
	 * columns look like they disagree: 23 limpwurt roots beside an average of 3 reads as one
	 * impossible harvest, when what it means is one finished patch and several abandoned ones.
	 * The predicted average moved to the tooltip to make room — the headline already says
	 * whether the estimates are matching overall, which is the version of that most people
	 * want.
	 */
	private final DataTable table = new DataTable("crop", "n", "got", "avg");

	HarvestStatsPanel(HarvestStatsStore stats)
	{
		this.stats = stats;

		setLayout(new BorderLayout(0, 2));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(getBackground());

		headline.setAlignmentX(LEFT_ALIGNMENT);
		headline.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		table.setAlignmentX(LEFT_ALIGNMENT);
		table.setBackground(getBackground());

		body.add(headline);
		body.add(table);

		add(body, BorderLayout.NORTH);
		refresh();
	}

	/** Redraws from the store. Must run on the EDT. */
	void refresh()
	{
		int harvests = stats.getTotalHarvests();

		if (harvests == 0)
		{
			headline.setText("Nothing recorded yet. Harvest a patch and it turns up here.\n\n"
				+ "Every patch you pick clean is recorded with what was predicted for it, so "
				+ "this doubles as a check on the estimates elsewhere in the plugin.");
			headline.setToolTipText(null);
			table.setVisible(false);
			return;
		}

		headline.setText(describeTotals(harvests));
		headline.setToolTipText(accuracyTooltip());
		table.setVisible(true);

		rebuildTable();
	}

	private String describeTotals(int harvests)
	{
		StringBuilder text = new StringBuilder();
		text.append(Plurals.of(harvests, "patch, ", "patches, "))
			.append(stats.getTotalItems()).append(" items, ")
			.append(DataTable.shortNumber(stats.getTotalXp())).append(" xp.");

		double accuracy = stats.getOverallAccuracy();
		if (accuracy > 0)
		{
			text.append('\n').append(describeAccuracy(accuracy));
		}
		return text.toString();
	}

	/**
	 * How the predictions are doing, in words rather than as a bare ratio.
	 *
	 * <p>Within a twentieth either way is called right: the figure is noisy until a couple of
	 * dozen patches are in, and reporting "1.03x" for what is almost certainly sampling noise
	 * would invite chasing it.
	 */
	private static String describeAccuracy(double accuracy)
	{
		if (accuracy >= 0.95 && accuracy <= 1.05)
		{
			return "Estimates are matching what you get.";
		}
		return accuracy > 1
			? String.format("You are getting %.0f%% more than estimated.", (accuracy - 1) * 100)
			: String.format("You are getting %.0f%% less than estimated.", (1 - accuracy) * 100);
	}

	private String accuracyTooltip()
	{
		// All three sentences earn their place - the last one is the only explanation of why
		// "got" does not equal n x "avg" - so this is wrapped rather than cut.
		return Tooltips.html("Compares what you actually harvested against what the plugin"
			+ " predicted for those same patches.<br><br>Each patch was predicted using the level,"
			+ " compost and gear in play at the time, so the totals compare like with like."
			+ "<br><br>Patches you walked away from are counted in the item totals but kept out of"
			+ " the averages - a half-picked patch is not a low yield.");
	}

	private void rebuildTable()
	{
		table.clearRows();

		Map<String, List<CropHarvestStats>> byTier = tiersByCrop();
		List<CropHarvestStats> crops = stats.getByCrop();

		int shown = 0;
		for (CropHarvestStats crop : crops)
		{
			if (shown++ >= MAX_ROWS)
			{
				break;
			}
			table.addRow(crop.getCrop(), cropTooltip(crop, byTier.get(crop.getCrop())),
				String.valueOf(crop.getHarvests()),
				String.valueOf(crop.getTotalItems()),
				format(crop.getAverageYield()));
		}

		if (crops.size() > MAX_ROWS)
		{
			table.addRow("+ " + (crops.size() - MAX_ROWS) + " more", null, "", "", "");
		}

		table.addTotalRow("total", String.valueOf(stats.getTotalHarvests()),
			String.valueOf(stats.getTotalItems()), "");
	}

	/** The per-compost rows behind each crop's summed line, for that crop's tooltip. */
	private Map<String, List<CropHarvestStats>> tiersByCrop()
	{
		Map<String, List<CropHarvestStats>> byCrop = new LinkedHashMap<>();
		for (CropHarvestStats entry : stats.getAll())
		{
			byCrop.computeIfAbsent(entry.getCrop(), crop -> new ArrayList<>()).add(entry);
		}
		return byCrop;
	}

	/**
	 * Everything about one crop that will not fit in three narrow columns.
	 *
	 * <p>The compost breakdown is the part worth hovering for: compost is the single biggest
	 * lever on yield, so a crop's overall average mixes conditions that are not comparable,
	 * and the split is what makes the number mean anything.
	 */
	private String cropTooltip(CropHarvestStats crop, List<CropHarvestStats> tiers)
	{
		StringBuilder text = new StringBuilder("<b>").append(crop.getCrop())
			.append("</b><br>").append(crop.getHarvests())
			.append(Plurals.pick(crop.getHarvests(), " patch picked clean", " patches picked clean"));

		if (crop.getHarvests() > 0)
		{
			text.append("<br>best ").append(crop.getBest())
				.append(", worst ").append(crop.getWorst())
				.append("<br>predicted ").append(format(crop.getAveragePredicted()))
				.append(" a patch");
		}
		if (crop.getPartialItems() > 0)
		{
			// Spelt out because these are the items the average deliberately ignores, and the
			// gap between "got" and n x "avg" is otherwise unexplained.
			text.append("<br>").append(crop.getPartialItems())
				.append(" more from patches left standing, not counted in the average");
		}
		text.append("<br>").append(DataTable.shortNumber(crop.getTotalXp())).append(" experience");

		if (tiers != null && tiers.size() > 1)
		{
			text.append("<br><br>By compost:");
			for (CropHarvestStats tier : tiers)
			{
				if (tier.getHarvests() == 0)
				{
					continue;
				}
				text.append("<br>&bull; ").append(tierName(tier.getCompost())).append(": ")
					.append(format(tier.getAverageYield())).append(" avg over ")
					.append(tier.getHarvests())
					.append(Plurals.pick(tier.getHarvests(), " patch", " patches"));
			}
		}

		return Tooltips.html(text.toString());
	}

	/** The tier's display name, falling back to whatever was stored if it no longer exists. */
	private static String tierName(String stored)
	{
		if (stored == null)
		{
			return "unknown";
		}
		try
		{
			return CompostTier.valueOf(stored).getDisplayName();
		}
		catch (IllegalArgumentException e)
		{
			return stored.toLowerCase();
		}
	}

	private static String format(double value)
	{
		return value <= 0 ? "-" : String.format("%.1f", value);
	}

}
