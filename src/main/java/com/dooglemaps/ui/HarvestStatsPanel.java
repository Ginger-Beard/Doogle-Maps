package com.dooglemaps.ui;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.ItemPrices;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Seed;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.state.CompostSelectionStore;
import com.dooglemaps.state.FarmingBonusStore;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.timer.PlantOutEstimate;
import com.dooglemaps.validate.CropHarvestStats;
import com.dooglemaps.validate.DiseaseStats;
import com.dooglemaps.validate.DiseaseStatsStore;
import com.dooglemaps.validate.FarmRun;
import com.dooglemaps.validate.HarvestHistory;
import com.dooglemaps.validate.HarvestStatsStore;
import java.awt.BorderLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.api.Experience;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * What your patches have actually given you, over the lifetime of the account.
 *
 * <p>The numbers were collected to check the plugin's own arithmetic — every finished patch
 * is recorded with its prediction beside the actual — but they are the interesting ones to
 * look at as a player too, so they are shown rather than only written to a CSV.
 *
 * <h2>Sections, not one table</h2>
 *
 * The tab began as the validation table alone, which answered the developer's question and
 * only accidentally answered the player's. It is now four questions in a deliberate order,
 * each with its own heading:
 *
 * <ol>
 *   <li><b>Lifetime</b> — what you have actually got, per crop.</li>
 *   <li><b>Luck</b> — where you landed against expectation, as a position in a distribution
 *       rather than a mean.</li>
 *   <li><b>Expected</b> — what the seeds you are holding are worth, planted out, levelling up
 *       as they go. The one section that reads the bank rather than the history, so it is also
 *       the only one with anything to say on a fresh install.</li>
 *   <li><b>Validation</b> — the original crop / n / got / avg table, which is the developer's
 *       view and belongs at the bottom rather than being the whole tab.</li>
 * </ol>
 *
 * <p>The tab's tooltip is <b>"Nerd."</b> and that is the design brief. This is the one place
 * in the plugin where the answer being long is the point — everything else is a run you are
 * in the middle of; this is what you read when you are not farming.
 *
 * <p>Its own top-level tab rather than a section under the run controls. It is a history
 * rather than something you act on mid-run, so it neither needs to be on screen while you
 * farm nor deserves to be buried under a hundred patch rows when you do want it.
 */
class HarvestStatsPanel extends JPanel
{
	/** Crops shown in a table before the list is cut off. Beyond this it stops being readable. */
	private static final int MAX_ROWS = 12;

	private static final long WEEK = 7 * 24 * 3600L;

	/** Unlocks listed before the route stops being a route and starts being a list. */
	private static final int MAX_UNLOCKS = 8;

	/**
	 * Patches a crop needs before its histogram is drawn at all.
	 *
	 * <p>Lower than the percentile's floor because the two claim different things. A percentile
	 * is a confident statement about where you sit and needs the normal approximation to hold; a
	 * histogram is just the observations, and showing a dozen of them is honest as long as the
	 * count is beside each bar. Six would not be - it looks like a distribution and is a list.
	 */
	private static final int MIN_PATCHES_FOR_HISTOGRAM = 12;

	/** Characters in the longest histogram bar. */
	private static final int HISTOGRAM_WIDTH = 10;

	/**
	 * Growth cycles needed before a disease rate is shown.
	 *
	 * <p>Disease is a rare event - a few percent a cycle for most crops - so a rate over twenty
	 * cycles is almost always either zero or one patch, and both round to something that reads
	 * like a finding. Fifty is still a small sample; it is the floor at which the figure stops
	 * being actively misleading rather than the one at which it becomes reliable.
	 */
	private static final int MIN_CYCLES_FOR_DISEASE = 50;

	/** Names on the tables, so a test can tell one crop list from another. */
	static final String LIFETIME_TABLE = "lifetime";
	static final String LUCK_TABLE = "luck";
	static final String RUNS_TABLE = "runs";
	static final String EXPECTED_TABLE = "expected";
	static final String VALIDATION_TABLE = "validation";

	private final HarvestStatsStore stats;
	private final HarvestHistory history;
	private final DiseaseStatsStore disease;
	private final Prices prices;
	private final SeedInventoryStore seeds;
	private final AvailabilityProfile availability;
	private final CompostSelectionStore compost;
	private final FarmingBonusStore bonuses;

	private final JPanel body = new JPanel();

	/** The stack of sections, hidden wholesale when there is nothing at all to show. */
	private final JPanel sections = new JPanel();
	private final WrappedText nothingYet = new WrappedText();

	/**
	 * The three sections drawn from harvest history, hidden together on a fresh install.
	 *
	 * <p>Held apart from Expected because that one reads the <i>bank</i>, not the history — a
	 * player who has planted their first seeds and harvested nothing still has a projection
	 * worth reading, and hiding it behind "nothing recorded yet" would be the tab refusing to
	 * answer the one question it could.
	 */
	private final JPanel lifetimeSection = new JPanel();
	private final JPanel luckSection = new JPanel();
	private final JPanel runsSection = new JPanel();
	private final JPanel expectedSection = new JPanel();
	private final JPanel validationSection = new JPanel();

	private final WrappedText lifetimeSummary = new WrappedText();
	/**
	 * Crop, patches, items, experience — sorted by experience.
	 *
	 * <p>Sorted by experience rather than by items because that is what the reader is scanning
	 * for: a farmer knows roughly how many watermelons they have picked and does not know which
	 * crop has actually paid for the levels.
	 */
	private final DataTable lifetimeTable = new DataTable("crop", "n", "items", "xp");
	/** What it would all be worth now, and the three things that qualify that. */
	private final WrappedText lifetimeValue = new WrappedText();

	private final WrappedText luckSummary = new WrappedText();
	/**
	 * Crop, patches, items over expectation, and where that lands as a percentile.
	 *
	 * <p>The percentile is the column worth having and the one that is usually blank: it needs
	 * both a modelled spread for the crop and {@link CropHarvestStats#MIN_PATCHES_FOR_LUCK}
	 * patches of it. Blank is the honest answer there, and the surplus beside it is not — a
	 * cumulative total needs no variance and no sample size to be true.
	 */
	private final DataTable luckTable = new DataTable("crop", "n", "+/-", "luck");
	private final WrappedText luckNotes = new WrappedText();

	private final WrappedText runsSummary = new WrappedText();
	/**
	 * Last, best and average sitting.
	 *
	 * <p>Reconstructed from the gaps between harvests rather than tracked, so it covers the
	 * whole history including everything picked before the guided mode existed.
	 */
	private final DataTable runsTable = new DataTable("run", "n", "items", "xp");
	private final WrappedText runsNotes = new WrappedText();

	private final WrappedText expectedSummary = new WrappedText();
	/**
	 * Seed, how many you hold, what one of them is worth, and what the stack is worth.
	 *
	 * <p>"each" is the column that makes this a planting guide rather than a valuation, and it
	 * is what the rows are sorted by: it answers <i>which of these should I actually be
	 * planting</i> without the question being asked.
	 */
	private final DataTable expectedTable = new DataTable("seed", "n", "each", "xp");
	private final WrappedText expectedUnlocks = new WrappedText();
	private final WrappedText expectedNotes = new WrappedText();

	private final WrappedText accuracy = new WrappedText();
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
	/**
	 * Average yield by band of ten Farming levels.
	 *
	 * <p>The one view that checks the level <i>scaling</i> rather than the constants, and the
	 * only thing on the tab that shows the chance-to-save curve flattening — a real effect most
	 * players never see, because nobody keeps records across forty levels of farming.
	 */
	private final DataTable levelTable = new DataTable("level", "n", "got", "pred");
	/**
	 * Disease, measured rather than published.
	 *
	 * <p>The one figure on this tab that the harvest log could never have produced, because a
	 * patch that dies produces no harvest.
	 */
	private final WrappedText diseaseNotes = new WrappedText();

	/**
	 * The store's two views, read once per {@link #refresh} and shared by every section.
	 *
	 * <p>Sorted in place by whichever section is drawing, so each one states the order it wants
	 * rather than inheriting the last one's.
	 */
	private List<CropHarvestStats> byCrop = new ArrayList<>();
	private Map<String, List<CropHarvestStats>> tiers = new LinkedHashMap<>();

	HarvestStatsPanel(HarvestStatsStore stats, HarvestHistory history, DiseaseStatsStore disease,
		SeedInventoryStore seeds, AvailabilityProfile availability, CompostSelectionStore compost,
		FarmingBonusStore bonuses, ItemPrices items)
	{
		this.prices = new Prices(items);
		this.stats = stats;
		this.history = history;
		this.disease = disease;
		this.seeds = seeds;
		this.availability = availability;
		this.compost = compost;
		this.bonuses = bonuses;

		setLayout(new BorderLayout(0, 2));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(getBackground());
		sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
		sections.setBackground(getBackground());

		lifetimeTable.setName(LIFETIME_TABLE);
		luckTable.setName(LUCK_TABLE);
		runsTable.setName(RUNS_TABLE);
		expectedTable.setName(EXPECTED_TABLE);
		table.setName(VALIDATION_TABLE);

		nothingYet.setText("Nothing here yet. Harvest a patch, or put some seeds in the bank, "
			+ "and this fills in.\n\n"
			+ "Every patch you pick clean is recorded with what was predicted for it, so this "
			+ "doubles as a check on the estimates elsewhere in the plugin.");
		nothingYet.setAlignmentX(LEFT_ALIGNMENT);

		fill(lifetimeSection, heading("Lifetime", Tooltips.html(
			"Everything harvested since the plugin was installed, one row per crop."
				+ "<br><br>Items from patches you walked away from are counted here and only here."
				+ " A watermelon you picked three of is three watermelons - but three is not a fair"
				+ " sample of a full patch, so those items stay out of every average below.")),
			lifetimeSummary, lifetimeTable, lifetimeValue);

		fill(luckSection, heading("Luck", Tooltips.html(
			"Where your harvests landed against what the game should have given you."
				+ "<br><br>Not a comparison with other players. A patch is picked until its lives"
				+ " run out, which is a distribution with a known mean <i>and</i> a known spread, so"
				+ " your own history is enough to place you in it exactly."
				+ "<br><br>The percentile needs " + CropHarvestStats.MIN_PATCHES_FOR_LUCK
				+ " picked patches of a crop before it means anything, and it is left blank until"
				+ " then rather than shown as noise.")),
			luckSummary, luckTable, luckNotes);

		fill(runsSection, heading("Runs", Tooltips.html(
			"Sittings, reconstructed from the gaps between your harvests - a dozen patches in a"
				+ " quarter of an hour and then nothing for an hour is a run, whether or not you"
				+ " started one."
				+ "<br><br><b>Experience per run and per day</b> rather than per hour, because"
				+ " farming is not continuous. Measured over the run alone, an hourly rate is a"
				+ " flattering number describing nothing you can keep up; measured over elapsed"
				+ " time it is a tiny one dominated by sleep.")),
			runsSummary, runsTable, runsNotes);

		fill(expectedSection, heading("Expected", Tooltips.html(
			"What the seeds you are holding are worth if you plant every one of them, through"
				+ " the patches you have switched on."
				+ "<br><br>Levels up as it goes: chance-to-save rises with your Farming level, so a"
				+ " big stack is worth more than its first patch suggests. The figure for staying"
				+ " on your current level is given too, because the gap between them is the point."
				+ "<br><br><b>Says nothing about time.</b> Growth is real but so is logging off, and"
				+ " a date would be the first dishonest number on this tab.")),
			expectedSummary, expectedTable, expectedUnlocks, expectedNotes);

		fill(validationSection, heading("Validation", accuracyTooltip()), accuracy, table,
			levelTable, diseaseNotes);

		body.add(nothingYet);
		body.add(sections);
		add(body, BorderLayout.NORTH);
		refresh();
	}

	/** Redraws from the stores. Must run on the EDT. */
	void refresh()
	{
		boolean recorded = stats.getTotalHarvests() > 0 || stats.getTotalItems() > 0;

		lifetimeSection.setVisible(recorded);
		luckSection.setVisible(recorded);
		validationSection.setVisible(recorded);

		if (recorded)
		{
			// Both views of the store, read once for the whole refresh. Three sections and a
			// tooltip per row all want them, and each call rebuilds a map over every crop and
			// tier - the same mistake the patch list was carrying when it walked itself four
			// times per refresh.
			byCrop = stats.getByCrop();
			tiers = tiersByCrop();

			rebuildLifetime();
			rebuildLuck();
			rebuildValidation();
		}

		boolean anyRuns = rebuildRuns();
		runsSection.setVisible(anyRuns);

		boolean seedsToPlant = rebuildExpected();
		expectedSection.setVisible(seedsToPlant);

		// Every section that found something has to count here, or one of them gets built,
		// marked visible, and then hidden anyway inside a container nobody switched on. Runs
		// were missing from this test: they come from harvests.csv rather than from the
		// rolled-up store, and *Clear harvest history* empties the store without touching the
		// file - so a cleared account that restarted read its runs back and still showed
		// nothing at all.
		boolean anything = recorded || anyRuns || seedsToPlant;
		nothingYet.setVisible(!anything);
		sections.setVisible(anything);
	}

	// ---------------------------------------------------------------- lifetime

	private void rebuildLifetime()
	{
		StringBuilder text = new StringBuilder();
		text.append(Plurals.of(stats.getTotalHarvests(), "patch, ", "patches, "))
			.append(stats.getTotalItems()).append(" items, ")
			.append(DataTable.shortNumber(stats.getTotalXp())).append(" xp.");

		// Without the start date these read as an account's whole farming history. They are not:
		// they begin the day the plugin was installed, and saying so is the difference between a
		// modest number and a wrong one.
		String since = firstHarvestDate();
		if (since != null)
		{
			text.append("\nSince ").append(since).append('.');
		}
		lifetimeSummary.setText(text.toString());

		byCrop.sort(Comparator.comparingDouble(CropHarvestStats::getTotalXp).reversed());

		lifetimeTable.clearRows();
		int shown = 0;
		for (CropHarvestStats crop : byCrop)
		{
			if (shown++ >= MAX_ROWS)
			{
				break;
			}
			lifetimeTable.addRow(crop.getCrop(), cropTooltip(crop, tiers.get(crop.getCrop())),
				String.valueOf(crop.getHarvests()),
				String.valueOf(crop.getTotalItems()),
				DataTable.shortNumber(crop.getTotalXp()));
		}
		if (byCrop.size() > MAX_ROWS)
		{
			lifetimeTable.addRow("+ " + (byCrop.size() - MAX_ROWS) + " more", null, "", "", "");
		}

		lifetimeTable.addTotalRow("total",
			String.valueOf(stats.getTotalHarvests()),
			String.valueOf(stats.getTotalItems()),
			DataTable.shortNumber(stats.getTotalXp()));

		lifetimeValue.setText(describeLifetimeValue());
		lifetimeValue.setVisible(lifetimeValue.getText().length() > 0);
	}

	/**
	 * What the whole history would fetch today.
	 *
	 * <p>Deliberately "would fetch <b>now</b>". Nothing ever recorded the price at the time and
	 * historical prices are not available offline, so this is not a lifetime earnings claim and
	 * must not read like one — it is the right number for deciding what to plant next and the
	 * wrong one for anything retrospective.
	 */
	private String describeLifetimeValue()
	{
		long value = 0;
		for (CropHarvestStats crop : byCrop)
		{
			value += prices.valueOf(Prices.produceNamed(crop.getCrop()), crop.getTotalItems());
		}

		// Zero means the item cache has not loaded or nothing here is tradeable. Either way
		// there is no figure to show, and "worth 0" would be a claim rather than a gap.
		return value <= 0
			? ""
			: "Worth about " + DataTable.shortNumber(value)
				+ " gp at today's prices - what it would fetch now, not what it made at the time.";
	}

	/** The day the first patch was recorded, e.g. "4 August", or null with nothing recorded. */
	@Nullable
	private String firstHarvestDate()
	{
		long first = stats.getFirstHarvest();
		if (first <= 0)
		{
			return null;
		}

		ZonedDateTime at = Instant.ofEpochSecond(first).atZone(ZoneId.systemDefault());
		// The year only earns its place once it is not this one, where it is noise on every row.
		String pattern = at.getYear() == ZonedDateTime.now().getYear() ? "d MMMM" : "d MMMM yyyy";
		return DateTimeFormatter.ofPattern(pattern).format(at);
	}

	// -------------------------------------------------------------------- luck

	private void rebuildLuck()
	{
		luckSummary.setText(describeLuck());

		List<CropHarvestStats> crops = new ArrayList<>();
		for (CropHarvestStats crop : byCrop)
		{
			// A crop with no prediction has nothing to be lucky against, and showing it with a
			// blank surplus would read as "dead level" rather than "not scored".
			if (crop.getPredicted() > 0 && crop.getHarvests() > 0)
			{
				crops.add(crop);
			}
		}
		crops.sort(Comparator.comparingInt(CropHarvestStats::getHarvests).reversed());

		luckTable.clearRows();
		luckTable.setVisible(!crops.isEmpty());

		int shown = 0;
		for (CropHarvestStats crop : crops)
		{
			if (shown++ >= MAX_ROWS)
			{
				break;
			}
			luckTable.addRow(crop.getCrop(), luckTooltip(crop),
				String.valueOf(crop.getHarvests()),
				String.format("%+.0f", crop.getSurplus()),
				crop.hasLuckPercentile() ? ordinal(percentile(crop)) : "-");
		}
		if (crops.size() > MAX_ROWS)
		{
			luckTable.addRow("+ " + (crops.size() - MAX_ROWS) + " more", null, "", "", "");
		}

		luckNotes.setText(String.join("\n\n", notes()));
		luckNotes.setVisible(luckNotes.getText().length() > 0);
	}

	/** The account-wide line: how far up or down you are, and what you left standing. */
	private String describeLuck()
	{
		StringBuilder text = new StringBuilder();

		int scored = 0;
		for (CropHarvestStats crop : byCrop)
		{
			if (crop.getPredicted() > 0)
			{
				scored += crop.getHarvests();
			}
		}

		double surplus = stats.getTotalSurplus();
		if (scored == 0)
		{
			text.append("Nothing harvested yet that the plugin makes a prediction for.");
		}
		else if (Math.abs(surplus) < 1)
		{
			text.append("Level with expectation across ")
				.append(Plurals.of(scored, "patch.", "patches."));
		}
		else
		{
			// Cumulative rather than a percentile, because this one needs no spread and no
			// sample size to be true - it is simply what you got minus what was predicted.
			text.append(String.format("%.0f %s %s expectation, across ", Math.abs(surplus),
					Plurals.pick(Math.round(Math.abs(surplus)), "item", "items"),
					surplus > 0 ? "over" : "under"))
				.append(Plurals.of(scored, "patch.", "patches."));
		}

		int partial = stats.getTotalPartialItems();
		if (partial > 0)
		{
			// The one actionable line on the tab: these are patches that still had crop on them
			// when you walked off.
			text.append('\n').append(partial)
				.append(Plurals.pick(partial, " item", " items"))
				.append(" came from patches left standing.");
		}
		return text.toString();
	}

	/** The prose that will not fit in a column: what compost did, and how often you farm. */
	private List<String> notes()
	{
		List<String> notes = new ArrayList<>();
		String compost = describeCompost();
		if (compost != null)
		{
			notes.add(compost);
		}
		String cadence = describeCadence();
		if (cadence != null)
		{
			notes.add(cadence);
		}
		return notes;
	}

	/**
	 * What compost has actually been worth, on this account's own numbers.
	 *
	 * <p>The only question anyone asks about compost, and the store can answer it because it
	 * has always split every crop by tier. Answered from the crop with the most patches farmed
	 * under more than one tier, since that is the comparison with the least noise in it.
	 *
	 * <p><b>Most players use one tier forever</b>, so the usual case is that no comparison can
	 * be drawn at all. That degrades to saying which tier you have always used rather than to a
	 * half-answer, because "ultracompost gave you 8.4 a patch" with nothing to compare against
	 * is a fact masquerading as a finding.
	 */
	@Nullable
	private String describeCompost()
	{
		CropHarvestStats best = null;
		CropHarvestStats worst = null;
		int mostPatches = 0;

		for (Map.Entry<String, List<CropHarvestStats>> entry : tiers.entrySet())
		{
			CropHarvestStats high = null;
			CropHarvestStats low = null;
			int patches = 0;

			for (CropHarvestStats tier : entry.getValue())
			{
				if (tier.getHarvests() == 0)
				{
					continue;
				}
				patches += tier.getHarvests();
				if (high == null || tier.getAverageYield() > high.getAverageYield())
				{
					high = tier;
				}
				if (low == null || tier.getAverageYield() < low.getAverageYield())
				{
					low = tier;
				}
			}

			if (high != null && high != low && patches > mostPatches)
			{
				mostPatches = patches;
				best = high;
				worst = low;
			}
		}

		if (best == null)
		{
			return onlyTierUsed();
		}

		return String.format("%s gave you %.1f %s a patch against %.1f %s, over %s.",
			tierName(best.getCompost()), best.getAverageYield(), best.getCrop().toLowerCase(),
			worst.getAverageYield(), tierName(worst.getCompost()).toLowerCase(),
			Plurals.of(mostPatches, "patch", "patches"));
	}

	/** Said when there is only ever one tier in the history, so no comparison exists to draw. */
	@Nullable
	private String onlyTierUsed()
	{
		Set<String> tiers = new LinkedHashSet<>();
		for (CropHarvestStats entry : stats.getAll())
		{
			if (entry.getHarvests() > 0)
			{
				tiers.add(entry.getCompost());
			}
		}
		return tiers.size() == 1
			? "You have only ever used " + tierName(tiers.iterator().next()).toLowerCase()
				+ ", so there is nothing here to compare it against."
			: null;
	}

	/**
	 * How long you have been at it, and how often.
	 *
	 * <p>Only once a week has passed. Below that the rate is one week's farming extrapolated,
	 * which for a skill you touch every few days is a number invented rather than measured.
	 */
	@Nullable
	private String describeCadence()
	{
		long first = stats.getFirstHarvest();
		long last = stats.getLastHarvest();
		if (first <= 0 || last - first < WEEK)
		{
			return null;
		}

		double weeks = (last - first) / (double) WEEK;
		return String.format("%s over %s, about %.0f a week.",
			Plurals.of(stats.getTotalHarvests(), "patch", "patches"),
			Plurals.of(Math.round(weeks), "week", "weeks"),
			stats.getTotalHarvests() / weeks);
	}

	private String luckTooltip(CropHarvestStats crop)
	{
		StringBuilder text = new StringBuilder("<b>").append(crop.getCrop()).append("</b><br>")
			.append(String.format("%d harvested against %.0f predicted, over %s",
				crop.getItems(), crop.getPredicted(),
				Plurals.of(crop.getHarvests(), "patch", "patches")));

		if (crop.getBest() > 0)
		{
			text.append("<br>best patch ").append(crop.getBest())
				.append(", worst ").append(crop.getWorst());
		}

		if (crop.hasLuckPercentile())
		{
			long at = percentile(crop);
			text.append("<br><br>Your total sits at the <b>").append(ordinal(at))
				.append(" percentile</b> of where ").append(crop.getHarvests())
				.append(" patches should land - so ").append(at)
				.append("% of the time the game would have given you less than this.");
		}
		else if (crop.getHarvests() < CropHarvestStats.MIN_PATCHES_FOR_LUCK)
		{
			text.append("<br><br>Not enough yet for a percentile: ")
				.append(CropHarvestStats.MIN_PATCHES_FOR_LUCK - crop.getHarvests())
				.append(" more picked patches. Below that the spread swamps the total and the"
					+ " figure would be noise.");
		}
		else
		{
			// Either the crop has no modelled spread, or its history predates the spread being
			// recorded. Both are "we cannot place this", and the distinction is not the reader's
			// problem - what matters is that the blank is deliberate.
			text.append("<br><br>No percentile for this crop: the spread of a single patch is not"
				+ " modelled for it, or these harvests were recorded before it was.");
		}

		text.append(histogram(crop.getCrop()));
		return Tooltips.html(text.toString());
	}

	/**
	 * Where a crop's patches actually clustered, as bars.
	 *
	 * <p>The percentile says you are at the 71st; this says <i>how</i>. They answer different
	 * questions and the shape is the one a running total cannot reconstruct — it needs the rows
	 * back, which is what {@link HarvestHistory} is for.
	 *
	 * <p>Empty below a floor of its own. A histogram over six patches is six bars of height one,
	 * which looks like a finding and is a picture of nothing.
	 */
	private String histogram(String crop)
	{
		HarvestHistory.Histogram spread = history.getHistogram(crop);
		if (spread == null || spread.getPatches() < MIN_PATCHES_FOR_HISTOGRAM)
		{
			return "";
		}

		StringBuilder text = new StringBuilder("<br><br>Where they landed, against prediction:");
		int most = Math.max(1, spread.getMost());
		int reach = HarvestHistory.HISTOGRAM_BUCKETS / 2;

		for (int bucket = 0; bucket < spread.getBuckets().length; bucket++)
		{
			int delta = bucket - reach;
			int count = spread.getBuckets()[bucket];
			// Scaled to the tallest bar rather than to the patch count, so a crop with one
			// dominant bucket still shows the shape of the rest.
			int bar = (int) Math.round(HISTOGRAM_WIDTH * (double) count / most);

			text.append("<br>").append(label(delta, reach)).append(' ');
			for (int drawn = 0; drawn < bar; drawn++)
			{
				text.append('#');
			}
			text.append(' ').append(count);
		}
		return text.toString();
	}

	/** The bucket's name, with the outermost two open-ended because the tails are unbounded. */
	private static String label(int delta, int reach)
	{
		if (delta <= -reach)
		{
			return "-" + reach + " or worse";
		}
		if (delta >= reach)
		{
			return "+" + reach + " or better";
		}
		return delta > 0 ? "+" + delta : String.valueOf(delta);
	}

	/** Clamped off the ends: nothing is ever the 0th or 100th percentile of a live distribution. */
	private static long percentile(CropHarvestStats crop)
	{
		return Math.min(99, Math.max(1, Math.round(crop.getLuckPercentile())));
	}

	private static String ordinal(long value)
	{
		long lastTwo = value % 100;
		if (lastTwo >= 11 && lastTwo <= 13)
		{
			return value + "th";
		}
		switch ((int) (value % 10))
		{
			case 1:
				return value + "st";
			case 2:
				return value + "nd";
			case 3:
				return value + "rd";
			default:
				return value + "th";
		}
	}

	// -------------------------------------------------------------------- runs

	/**
	 * What your sittings look like, and the rates that follow from them.
	 *
	 * @return whether any run has been reconstructed, and so whether the section is drawn
	 */
	private boolean rebuildRuns()
	{
		// Read once. Every figure below is derived from this same list, so the count in the
		// summary cannot disagree with the average in the table.
		List<FarmRun> runs = history.getRuns();
		if (runs.isEmpty())
		{
			return false;
		}

		runsSummary.setText(Plurals.of(runs.size(), "run", "runs") + " so far, "
			+ DataTable.shortNumber(history.getXpPerRun()) + " xp each.");

		runsTable.clearRows();
		addRunRow("last", history.getLastRun());
		addRunRow("best", history.getBestRun());
		if (runs.size() > 1)
		{
			runsTable.addTotalRow("average",
				String.format("%.1f",
					runs.stream().mapToInt(FarmRun::getPatches).sum() / (double) runs.size()),
				String.format("%.0f",
					runs.stream().mapToInt(FarmRun::getItems).sum() / (double) runs.size()),
				DataTable.shortNumber(history.getXpPerRun()));
		}

		runsNotes.setText(String.join("\n\n", runNotes()));
		runsNotes.setVisible(runsNotes.getText().length() > 0);
		return true;
	}

	private void addRunRow(String name, @Nullable FarmRun run)
	{
		if (run == null)
		{
			return;
		}
		runsTable.addRow(name, runTooltip(name, run), String.valueOf(run.getPatches()),
			String.valueOf(run.getItems()), DataTable.shortNumber(run.getXp()));
	}

	private static String runTooltip(String name, FarmRun run)
	{
		StringBuilder text = new StringBuilder("<b>").append(name).append(" run</b><br>")
			.append(Plurals.of(run.getPatches(), "patch", "patches")).append(", ")
			.append(run.getItems()).append(" items, ")
			.append(DataTable.shortNumber(run.getXp())).append(" experience");

		if (run.getDuration() > 0)
		{
			text.append("<br>took ").append(TimeFormat.duration(run.getDuration()));
		}
		text.append("<br>").append(TimeFormat.since(run.getEndedAt()));
		return Tooltips.html(text.toString());
	}

	/** The rates, each named for what it actually measures. */
	private List<String> runNotes()
	{
		List<String> notes = new ArrayList<>();

		double perDay = history.getXpPerDay();
		if (perDay > 0)
		{
			// The honest throughput number for a skill gated by a growth timer rather than by
			// your attention, and the one nobody displays.
			notes.add(DataTable.shortNumber(perDay) + " xp a day, averaged over the whole"
				+ " history - the rate a skill on a growth timer actually runs at.");
		}

		double active = history.getActiveXpPerHour();
		if (active > 0)
		{
			// Labelled active rather than left to imply it is sustainable: it is measured from
			// the first patch of a run to the last, which is a fraction of the day.
			notes.add(DataTable.shortNumber(active) + " xp an hour while you are actually"
				+ " farming. Not a rate you can keep up - the crops grow for an hour between"
				+ " runs.");
		}

		String toLevel = describeRunsToNextLevel();
		if (toLevel != null)
		{
			notes.add(toLevel);
		}
		return notes;
	}

	/**
	 * How many more runs the next Farming level is.
	 *
	 * <p>Arguably the single most useful line on the tab, and it costs almost nothing: the
	 * experience to the next level is one API call and the experience per run is already here.
	 */
	@Nullable
	private String describeRunsToNextLevel()
	{
		int xp = seeds.getFarmingXp();
		double perRun = history.getXpPerRun();
		if (xp <= 0 || perRun <= 0)
		{
			return null;
		}

		int level = Math.min(Experience.MAX_REAL_LEVEL, Experience.getLevelForXp(xp));
		if (level >= Experience.MAX_REAL_LEVEL)
		{
			return null;
		}

		int runs = (int) Math.max(1, Math.ceil((Experience.getXpForLevel(level + 1) - xp) / perRun));
		return "About " + Plurals.of(runs, "more run", "more runs") + " to " + (level + 1)
			+ ", at the rate your runs have been paying.";
	}

	// ---------------------------------------------------------------- expected

	/**
	 * What the bank is worth planted out.
	 *
	 * <p>The only section that reads the seed stores rather than the harvest history, so it is
	 * also the only one that can say anything on a fresh install.
	 *
	 * @return whether it has anything to show, and so whether the section is drawn at all
	 */
	private boolean rebuildExpected()
	{
		PlantOutEstimate.Projection projection = PlantOutEstimate.of(ownedSeeds(),
			patchesByType(), compostByType(), bonuses.current(), seeds.getFarmingXp());

		List<PlantOutEstimate.Line> lines = projection.getLines();
		if (lines.isEmpty() && projection.getLockedCrops() == 0)
		{
			return false;
		}

		expectedSummary.setText(describeExpected(projection));
		expectedTable.setVisible(!lines.isEmpty());

		expectedTable.clearRows();
		int shown = 0;
		int totalSeeds = 0;
		for (PlantOutEstimate.Line line : lines)
		{
			totalSeeds += line.getSeeds();
			if (shown++ >= MAX_ROWS)
			{
				continue;
			}
			expectedTable.addRow(line.getSeed().getName(), expectedTooltip(line),
				String.valueOf(line.getSeeds()),
				DataTable.shortNumber(line.getXpPerSeed()),
				DataTable.shortNumber(line.getXp()));
		}
		if (lines.size() > MAX_ROWS)
		{
			expectedTable.addRow("+ " + (lines.size() - MAX_ROWS) + " more", null, "", "", "");
		}
		if (!lines.isEmpty())
		{
			expectedTable.addTotalRow("total", String.valueOf(totalSeeds), "",
				DataTable.shortNumber(projection.getXp()));
		}

		expectedUnlocks.setText(describeUnlocks(projection));
		expectedUnlocks.setVisible(expectedUnlocks.getText().length() > 0);

		expectedNotes.setText(String.join("\n\n", expectedNotes(projection)));
		expectedNotes.setVisible(expectedNotes.getText().length() > 0);
		return true;
	}

	private static String describeExpected(PlantOutEstimate.Projection projection)
	{
		if (projection.getLines().isEmpty())
		{
			return "Nothing here you can plant yet.";
		}

		StringBuilder text = new StringBuilder("Planting it all out: ")
			.append(DataTable.shortNumber(projection.getXp())).append(" xp");

		if (projection.getEndLevel() > projection.getStartLevel())
		{
			text.append(", taking you from ").append(projection.getStartLevel())
				.append(" to ").append(projection.getEndLevel()).append('.');
		}
		else
		{
			text.append(", not quite a level from ").append(projection.getStartLevel()).append('.');
		}

		// Where 99 falls in the bank, which is the question a big stack is really asking. Only
		// worth saying when there is something left over - "99 arrives on all of them" is what
		// the line above already said.
		if (projection.isReachesMaxLevel() && projection.getSeedsBeyondMaxLevel() > 0)
		{
			text.append("\n99 arrives on ")
				.append(Plurals.of(projection.getSeedsToMaxLevel(), "seed", "seeds"))
				.append("; the other ").append(projection.getSeedsBeyondMaxLevel())
				.append(" are worth ").append(DataTable.shortNumber(
					projection.getXpBeyondMaxLevel()))
				.append(" more beyond it.");
		}

		// The flat figure earns its place only where it differs. Below a percent it is the same
		// number twice and reads as padding.
		double gap = projection.getXp() - projection.getXpAtStartLevel();
		if (gap > projection.getXp() * 0.01)
		{
			text.append("\nStaying at ").append(projection.getStartLevel())
				.append(" and planting only what you can plant now, it would be ")
				.append(DataTable.shortNumber(projection.getXpAtStartLevel()))
				.append(" - the difference is the levelling up as you go.");
		}
		return text.toString();
	}

	/**
	 * The route through the levels: what becomes plantable, and when.
	 *
	 * <p>The part of this section that answers "show me my path", and the only place the
	 * simulation's working is visible rather than just its total. Capped because a bank of forty
	 * crops from level 1 unlocks something every few levels, and a wall of them stops being a
	 * route and starts being a list.
	 */
	private static String describeUnlocks(PlantOutEstimate.Projection projection)
	{
		List<PlantOutEstimate.Unlock> unlocks = projection.getUnlocks();
		if (unlocks.isEmpty())
		{
			return "";
		}

		StringBuilder text = new StringBuilder("Unlocks on the way:");
		int shown = 0;
		for (PlantOutEstimate.Unlock unlock : unlocks)
		{
			if (shown++ >= MAX_UNLOCKS)
			{
				text.append("\n+ ").append(unlocks.size() - MAX_UNLOCKS).append(" more");
				break;
			}
			text.append("\n").append(unlock.getLevel()).append(" - ")
				.append(unlock.getSeed().getName())
				.append(" (").append(unlock.getSeeds()).append(" banked)");
		}
		return text.toString();
	}

	/**
	 * What planting the bank out would be worth in coins, net of what it costs to plant.
	 *
	 * <p>Profit rather than value, because that is the number that actually decides whether
	 * snapdragon beats ranarr this month — and it moves with the market in a way no guide keeps
	 * up with. Seeds and compost are charged; protection payments are not, because the
	 * projection does not model disease either and charging for a benefit it does not credit
	 * would be worse than omitting both.
	 *
	 * <p><b>"Produced" rather than "profit" where the costs are notional.</b> An ironman did not
	 * buy the seed and made the compost, so the same arithmetic means a different word for them
	 * — and since the plugin cannot tell, it says what it charged rather than claiming either.
	 */
	@Nullable
	private String describeExpectedValue(PlantOutEstimate.Projection projection)
	{
		long value = 0;
		long cost = 0;

		for (PlantOutEstimate.Line line : projection.getLines())
		{
			Seed seed = line.getSeed();
			value += prices.valueOf(seed.getProduce(), line.getItems());
			cost += prices.seedCost(seed, line.getPatches());
			cost += prices.compostCost(compost.get(seed.getPatchType()), line.getPatches());
		}

		if (value <= 0)
		{
			return null;
		}

		StringBuilder text = new StringBuilder("Worth about ")
			.append(DataTable.shortNumber(value)).append(" gp of produce");
		if (cost > 0)
		{
			text.append(", against ").append(DataTable.shortNumber(cost))
				.append(" of seeds and compost - so ")
				.append(DataTable.shortNumber(value - cost)).append(" net");
		}
		text.append(". Today's prices, and no protection payments counted.");
		return text.toString();
	}

	private List<String> expectedNotes(PlantOutEstimate.Projection projection)
	{
		List<String> notes = new ArrayList<>();

		String value = describeExpectedValue(projection);
		if (value != null)
		{
			notes.add(value);
		}

		if (projection.getLockedCrops() > 0)
		{
			notes.add("You hold " + Plurals.of(projection.getLockedCrops(), "crop", "crops")
				+ " this never reaches the level for, left out of the total.");
		}

		// The one assumption a reader could not infer from the numbers. It decides the order,
		// not the contents: every seed is planted eventually, and best-first is simply the order
		// that gets the most out of the levelling.
		if (projection.getLines().size() > 1)
		{
			notes.add("Assumes you always plant the best experience per patch you can, into the"
				+ " patches you have switched on. Every seed goes in eventually - the order is"
				+ " what changes.");
		}
		return notes;
	}

	private String expectedTooltip(PlantOutEstimate.Line line)
	{
		Seed seed = line.getSeed();
		StringBuilder text = new StringBuilder("<b>").append(seed.getName()).append("</b><br>")
			.append(line.getSeeds()).append(Plurals.pick(line.getSeeds(), " seed", " seeds"))
			.append(" filling ").append(Plurals.of(line.getPatches(), "patch", "patches"));

		if (seed.getSeedsPerPatch() > 1)
		{
			// Otherwise the patch count looks like a mistake: 100 potato seeds is 33 patches.
			text.append("<br>").append(seed.getSeedsPerPatch()).append(" seeds to a patch");
		}

		text.append("<br>").append(String.format("%.0f", line.getItems()))
			.append(" items, ").append(DataTable.shortNumber(line.getXp())).append(" experience");

		CompostTier tier = compost.get(seed.getPatchType());
		text.append("<br>treated with ").append(tierName(tier.name()).toLowerCase());

		text.append("<br><br>").append(DataTable.shortNumber(line.getXpPerSeed()))
			.append(" experience per seed, which is what this list is ordered by.");

		return Tooltips.html(text.toString());
	}

	/** Every crop the player holds anything of, seeds and saplings alike. */
	private Map<Seed, Integer> ownedSeeds()
	{
		Map<Seed, Integer> owned = new LinkedHashMap<>();
		for (Seed seed : Seed.values())
		{
			int count = seeds.getOwned(seed);
			if (count > 0)
			{
				owned.put(seed, count);
			}
		}
		return owned;
	}

	/**
	 * Patches of each type the account can actually use.
	 *
	 * <p>From {@link AvailabilityProfile} rather than from the run planner: this is about
	 * capacity, not about what is actionable right now, and a patch mid-growth still counts
	 * towards what a bank of seeds will eventually go into.
	 */
	private Map<PatchImplementation, Integer> patchesByType()
	{
		Map<PatchImplementation, Integer> counts = new LinkedHashMap<>();
		for (PatchImplementation type : PatchImplementation.values())
		{
			counts.put(type, availability.getAvailablePatches(type).size());
		}
		return counts;
	}

	private Map<PatchImplementation, CompostTier> compostByType()
	{
		Map<PatchImplementation, CompostTier> chosen = new LinkedHashMap<>();
		for (PatchImplementation type : PatchImplementation.values())
		{
			CompostTier tier = compost.get(type);
			// Untreated stands in for "not chosen". A null in this map would survive
			// getOrDefault, since the key is present, and reach the yield model as one.
			chosen.put(type, tier == null ? CompostTier.NONE : tier);
		}
		return chosen;
	}

	// -------------------------------------------------------------- validation

	private void rebuildValidation()
	{
		double overall = stats.getOverallAccuracy();
		accuracy.setText(overall > 0
			? describeAccuracy(overall)
			: "Nothing harvested yet that the plugin makes a prediction for.");
		accuracy.setToolTipText(accuracyTooltip());

		table.clearRows();

		// By items, not by experience: this is the estimate-checking view, and the crops worth
		// checking first are the ones with the most harvests behind them.
		byCrop.sort(Comparator.comparingInt(CropHarvestStats::getTotalItems).reversed());

		int shown = 0;
		for (CropHarvestStats crop : byCrop)
		{
			if (shown++ >= MAX_ROWS)
			{
				break;
			}
			table.addRow(crop.getCrop(), cropTooltip(crop, tiers.get(crop.getCrop())),
				String.valueOf(crop.getHarvests()),
				String.valueOf(crop.getTotalItems()),
				format(crop.getAverageYield()));
		}

		if (byCrop.size() > MAX_ROWS)
		{
			table.addRow("+ " + (byCrop.size() - MAX_ROWS) + " more", null, "", "", "");
		}

		table.addTotalRow("total", String.valueOf(stats.getTotalHarvests()),
			String.valueOf(stats.getTotalItems()), "");

		rebuildLevelBands();

		diseaseNotes.setText(String.join("\n\n", diseaseNotes()));
		diseaseNotes.setVisible(diseaseNotes.getText().length() > 0);
	}

	/**
	 * Disease, on the account's own numbers rather than on the published ones.
	 *
	 * <p>The only claim on this tab that could not be checked at all until it was measured
	 * directly: a dead patch produces no harvest, so nothing built on the harvest log can see
	 * one. Everything here therefore compares a count of observed growth cycles against the
	 * survival chance predicted for those same cycles.
	 */
	private List<String> diseaseNotes()
	{
		int cycles = disease.getTotalCycles();
		if (cycles < MIN_CYCLES_FOR_DISEASE)
		{
			return Collections.emptyList();
		}

		List<String> notes = new ArrayList<>();
		int caught = disease.getTotalDiseased();
		double expected = cycles - disease.getPredictedSurvivals();

		notes.add(String.format("Disease: %d of %s caught something, against a predicted %.0f."
				+ " %d died.", caught, Plurals.of(cycles, "growth cycle", "growth cycles"),
			expected, disease.getTotalDied()));

		String compare = compareTiersOnDisease();
		if (compare != null)
		{
			notes.add(compare);
		}
		return notes;
	}

	/**
	 * What compost bought you against disease, measured.
	 *
	 * <p>The other half of the compost question, and the half nobody can answer from the wiki
	 * for their own account: ultracompost's yield bonus is easy to feel, its disease protection
	 * is invisible until something dies. Needs both tiers to have a real sample behind them,
	 * because a tier with four cycles against one with two hundred is a comparison in name only.
	 */
	@Nullable
	private String compareTiersOnDisease()
	{
		DiseaseStats most = null;
		DiseaseStats least = null;

		for (DiseaseStats entry : disease.getAll())
		{
			if (entry.getCycles() < MIN_CYCLES_FOR_DISEASE)
			{
				continue;
			}
			if (most == null || entry.getSurvivalRate() > most.getSurvivalRate())
			{
				most = entry;
			}
			if (least == null || entry.getSurvivalRate() < least.getSurvivalRate())
			{
				least = entry;
			}
		}

		if (most == null || most == least)
		{
			return null;
		}

		return String.format("%s lost %.0f%% of its cycles to disease; %s lost %.0f%%.",
			tierName(most.getCompost()), (1 - most.getSurvivalRate()) * 100,
			tierName(least.getCompost()).toLowerCase(), (1 - least.getSurvivalRate()) * 100);
	}

	/**
	 * Yield by band of ten Farming levels, which checks the level scaling rather than the
	 * constants.
	 *
	 * <p>Needs at least two bands to say anything — a single band is one number with nothing to
	 * compare it against, and the whole point is the shape of the curve between them.
	 */
	private void rebuildLevelBands()
	{
		List<HarvestHistory.LevelBand> bands = history.getLevelBands();
		levelTable.setVisible(bands.size() > 1);
		if (bands.size() < 2)
		{
			return;
		}

		levelTable.clearRows();
		for (HarvestHistory.LevelBand band : bands)
		{
			levelTable.addRow(band.getFrom() + "-" + (band.getFrom() + 9),
				Tooltips.html("Levels " + band.getFrom() + " to " + (band.getFrom() + 9)
					+ "<br>" + Plurals.of(band.getPatches(), "patch", "patches")
					+ " picked clean<br>" + band.getItems() + " items against a predicted "
					+ String.format("%.0f", band.getPredicted())
					+ "<br><br>Chance to save climbs with the level and then flattens, so the"
					+ " gap between bands should shrink as they rise."),
				String.valueOf(band.getPatches()),
				format(band.getAverage()),
				format(band.getAveragePredicted()));
		}
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

	private static String accuracyTooltip()
	{
		// All three sentences earn their place - the last one is the only explanation of why
		// "got" does not equal n x "avg" - so this is wrapped rather than cut.
		return Tooltips.html("Compares what you actually harvested against what the plugin"
			+ " predicted for those same patches.<br><br>Each patch was predicted using the level,"
			+ " compost and gear in play at the time, so the totals compare like with like."
			+ "<br><br>Patches you walked away from are counted in the item totals but kept out of"
			+ " the averages - a half-picked patch is not a low yield.");
	}

	// ----------------------------------------------------------------- shared

	/** The per-compost rows behind each crop's summed line, for that crop's tooltip. */
	private Map<String, List<CropHarvestStats>> tiersByCrop()
	{
		Map<String, List<CropHarvestStats>> grouped = new LinkedHashMap<>();
		for (CropHarvestStats entry : stats.getAll())
		{
			grouped.computeIfAbsent(entry.getCrop(), crop -> new ArrayList<>()).add(entry);
		}
		return grouped;
	}

	/**
	 * Everything about one crop that will not fit in three narrow columns.
	 *
	 * <p>The compost breakdown is the part worth hovering for: compost is the single biggest
	 * lever on yield, so a crop's overall average mixes conditions that are not comparable,
	 * and the split is what makes the number mean anything.
	 */
	private String cropTooltip(CropHarvestStats crop, @Nullable List<CropHarvestStats> tiers)
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

	/**
	 * A section heading, in the body font rather than a bold or coloured one.
	 *
	 * <p>The whole tab is the same small font, and the hierarchy comes from brightness instead:
	 * headings in the full text colour, prose a shade down, column names a shade below that.
	 * Nothing else in the plugin introduces a second font or an accent colour for emphasis, and
	 * a tab that did would look like it came from somewhere else.
	 */
	private static JLabel heading(String text, String tooltip)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.TEXT_COLOR);
		label.setToolTipText(tooltip);
		label.setAlignmentX(LEFT_ALIGNMENT);
		// Space above rather than below, so a heading sits with the section it names instead of
		// floating equidistant between two of them.
		label.setBorder(BorderFactory.createEmptyBorder(10, 0, 3, 0));
		return label;
	}

	/**
	 * Builds one section — a heading and its contents — and adds it to the stack.
	 *
	 * <p>A panel per section rather than one flat stack, so a section can be hidden as a unit.
	 * That is what lets Expected appear on an account with seeds and no harvests while the
	 * three history sections stay away.
	 *
	 * <p>Alignment is set here rather than at each call site because {@code BoxLayout} centres
	 * anything that does not ask otherwise, and one component that forgets to ask is enough to
	 * make the whole column look ragged. No border on the section panel: {@link WrappedText}
	 * computes its height against the full sidebar width and clips its last line if it is given
	 * any less.
	 */
	private void fill(JPanel section, JComponent... children)
	{
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(getBackground());
		section.setAlignmentX(LEFT_ALIGNMENT);

		for (JComponent child : children)
		{
			child.setAlignmentX(LEFT_ALIGNMENT);
			if (child.isOpaque())
			{
				child.setBackground(getBackground());
			}
			section.add(child);
		}
		sections.add(section);
	}

	/** The tier's display name, falling back to whatever was stored if it no longer exists. */
	private static String tierName(@Nullable String stored)
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
