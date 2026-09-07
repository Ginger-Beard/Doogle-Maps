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
import com.dooglemaps.validate.DiseaseStatsStore;
import com.dooglemaps.validate.FarmRun;
import com.dooglemaps.validate.HarvestHistory;
import com.dooglemaps.validate.HarvestStatsStore;
import java.awt.BorderLayout;
import java.awt.Color;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
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
 * <h2>Four cards, each with an answer on the outside</h2>
 *
 * The tab used to be five stacked sections of prose, table, prose, about 1350px of it, opening
 * on a paragraph in the dimmest text on the page. Three of its five tables were the same per-crop
 * rows with a different fourth column. It is now four collapsible {@link StatSection} cards:
 *
 * <ol>
 *   <li><b>Harvests</b> — one table of what you have actually got, per crop, with a signed
 *       difference against expectation where the crop is modelled well enough to have one.
 *       Open by default; it is the question the tab exists for.</li>
 *   <li><b>Runs</b> — sittings reconstructed from the gaps between harvests, headed by the
 *       number of them between you and the next level.</li>
 *   <li><b>Bank</b> — what the seeds you hold are worth planted out, levelling as they go. The
 *       one card that reads the bank rather than the history, so also the only one with anything
 *       to say on a fresh install.</li>
 *   <li><b>Checks</b> — disease, measured against its prediction. The one figure here the
 *       harvest log could never produce, because a patch that dies produces no harvest.</li>
 * </ol>
 *
 * <p>Each card's headline is readable while the card is shut, which is the point of shutting
 * them: four numbers in the space the old tab spent on one paragraph.
 *
 * <h2>What was taken out, and why it is not hiding somewhere</h2>
 *
 * A numerical audit of a real account found that several of the tab's most confident figures were
 * measuring the plugin's own capture faults rather than the game. The luck percentile, the
 * account-wide "N items over expectation" headline, the "N items came from patches left standing"
 * line, the compost comparison, the Farming-level bands and the "23% more than estimated" verdict
 * are all gone from the screen for that reason. The code that computes them still exists — the
 * inputs are being fixed elsewhere — but a panel that prints a number it has been shown to be
 * wrong about is worse than one that prints nothing.
 *
 * <p>The tab's tooltip is <b>"Nerd."</b> and that is still the design brief. The long
 * explanations were not deleted with the prose; they moved onto the headers and columns they
 * explain, where they are one hover away instead of in the way.
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
	 * <p>Lower than the {@code +/-} column's colour floor because the two claim different things.
	 * A coloured difference is a statement about where you sit and needs the sample to be stable;
	 * a histogram is just the observations, and showing a dozen of them is honest as long as the
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

	/**
	 * Above expectation. RuneLite's own "good price" green, 8.58:1 against the row behind it.
	 *
	 * <p>The <b>sign</b> carries the meaning and the colour only reinforces it, so the column
	 * still reads for a red-green colour-blind player and in a screenshot.
	 */
	private static final Color OVER = ColorScheme.GRAND_EXCHANGE_PRICE;

	/**
	 * Below expectation, at 5.08:1.
	 *
	 * <p>Not {@code PROGRESS_ERROR_COLOR}: it measures 3.10:1 against this background, below the
	 * 4.5:1 text this small needs, and saturated red on near-black is the worst case for the most
	 * common colour deficiency.
	 */
	private static final Color UNDER = new Color(235, 120, 120);

	/** Names on the tables, so a test can tell one crop list from another. */
	static final String CROP_TABLE = "crops";
	static final String RUNS_TABLE = "runs";
	static final String EXPECTED_TABLE = "expected";

	/** Keys the collapsed state is remembered under. Global, so they follow the person. */
	static final String HARVESTS_KEY = "stats.harvests";
	static final String RUNS_KEY = "stats.runs";
	static final String BANK_KEY = "stats.bank";
	static final String CHECKS_KEY = "stats.checks";

	private final HarvestStatsStore stats;
	private final HarvestHistory history;
	private final DiseaseStatsStore disease;
	private final Prices prices;
	private final SeedInventoryStore seeds;
	private final AvailabilityProfile availability;
	private final CompostSelectionStore compost;
	private final FarmingBonusStore bonuses;

	private final JPanel body = new JPanel();

	/** The stack of cards, hidden wholesale when there is nothing at all to show. */
	private final JPanel sections = new JPanel();
	private final WrappedText nothingYet = new WrappedText();

	private final StatSection harvests;
	private final StatSection runs;
	private final StatSection bank;
	private final StatSection checks;

	/**
	 * Crop, patches, items, experience, and a signed difference against expectation.
	 *
	 * <p>One table where there were three. Lifetime, Luck and Validation listed the same rows
	 * with the same name column and the same tooltip; only the fourth column differed, and two of
	 * those three were derivable from columns already on screen. Merging them is what makes room
	 * for the one column that was worth having.
	 *
	 * <p>Sorted by experience rather than by items because that is what the reader is scanning
	 * for: a farmer knows roughly how many watermelons they have picked and does not know which
	 * crop has actually paid for the levels.
	 */
	private final DataTable cropTable = new DataTable("crop", "n", "got", "xp", "+/-");

	/** What the whole history would fetch today. One line; the caveat is on hover. */
	private final JLabel lifetimeValue = note();

	/**
	 * Last, best and average sitting.
	 *
	 * <p>Reconstructed from the gaps between harvests rather than tracked, so it covers the
	 * whole history including everything picked before the guided mode existed.
	 */
	private final DataTable runsTable = new DataTable("run", "n", "items", "xp");
	private final JLabel xpPerDay = note();
	private final JLabel xpPerHour = note();

	/**
	 * Seed, how many you hold, what one of them is worth, and what the stack is worth.
	 *
	 * <p>"each" is the column that makes this a planting guide rather than a valuation, and it
	 * is what the rows are sorted by: it answers <i>which of these should I actually be
	 * planting</i> without the question being asked.
	 */
	private final DataTable expectedTable = new DataTable("seed", "n", "each", "xp");
	private final WrappedText expectedUnlocks = new WrappedText();
	private final JLabel expectedValue = note();
	private final JLabel lockedCrops = note();

	/**
	 * Disease, measured rather than published.
	 *
	 * <p>The one figure on this tab that the harvest log could never have produced, because a
	 * patch that dies produces no harvest.
	 */
	private final JLabel diseaseNote = note();

	/**
	 * Whether the crop and seed tables are showing everything they have.
	 *
	 * <p>Not remembered between sessions, unlike the collapsed state above it: opening a card is
	 * furniture, and asking to see the other twenty-two crops is a moment's curiosity.
	 */
	private boolean showAllCrops;
	private boolean showAllSeeds;

	/**
	 * Whether the fresh-install override has already had its say, so it only fires once.
	 *
	 * <p>Without it, a player who deliberately shut Bank on an account with no harvests would
	 * find it open again on the next repaint.
	 */
	private boolean openedBankForFreshInstall;

	/**
	 * The store's two views, read once per {@link #refresh} and shared by every section.
	 *
	 * <p>Sorted in place by whichever section is drawing, so each one states the order it wants
	 * rather than inheriting the last one's.
	 */
	private List<CropHarvestStats> byCrop = new ArrayList<>();
	private Map<String, List<CropHarvestStats>> tiers = new LinkedHashMap<>();

	HarvestStatsPanel(PanelLayoutStore layout, HarvestStatsStore stats, HarvestHistory history,
		DiseaseStatsStore disease, SeedInventoryStore seeds, AvailabilityProfile availability,
		CompostSelectionStore compost, FarmingBonusStore bonuses, ItemPrices items)
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

		cropTable.setName(CROP_TABLE);
		runsTable.setName(RUNS_TABLE);
		expectedTable.setName(EXPECTED_TABLE);
		cropTable.setColumnTooltip("+/-", differenceTooltip());

		lifetimeValue.setToolTipText(Tooltips.text("Today's prices - what it would fetch now, not"
			+ " what it made at the time. Nothing recorded the price when the herb was picked and"
			+ " historical prices are not available offline, so this is the right number for"
			+ " deciding what to plant next and the wrong one for anything retrospective."));
		lockedCrops.setToolTipText(Tooltips.text("Crops you hold seeds for that this projection"
			+ " never reaches the Farming level to plant, left out of the total. There is no"
			+ " honest number for a crop you cannot reach - saying what it would be worth at a"
			+ " level you do not get to is a number about a different account."));

		nothingYet.setText("Nothing here yet. Harvest a patch, or put some seeds in the bank, "
			+ "and this fills in.\n\n"
			+ "Every patch you pick clean is recorded with what was predicted for it, so this "
			+ "doubles as a check on the estimates elsewhere in the plugin.");
		nothingYet.setAlignmentX(LEFT_ALIGNMENT);

		harvests = new StatSection(layout, HARVESTS_KEY, "Harvests", Tooltips.html(
			"Everything harvested since the plugin was installed, one row per crop."
				+ "<br><br>Items from patches you walked away from are counted in <b>got</b> and"
				+ " only there. A watermelon you picked three of is three watermelons - but three"
				+ " is not a fair sample of a full patch, so those items stay out of the"
				+ " <b>+/-</b> beside it."
				+ "<br><br>Ordered by experience, because a farmer knows roughly how many"
				+ " watermelons they have picked and does not know which crop paid for the"
				+ " levels."),
			true, cropTable, lifetimeValue);

		runs = new StatSection(layout, RUNS_KEY, "Runs", Tooltips.html(
			"Sittings, reconstructed from the gaps between your harvests - a dozen patches in a"
				+ " quarter of an hour and then nothing for an hour is a run, whether or not you"
				+ " started one."
				+ "<br><br><b>Experience per run and per day</b> rather than per hour, because"
				+ " farming is not continuous. Measured over the run alone, an hourly rate is a"
				+ " flattering number describing nothing you can keep up; measured over elapsed"
				+ " time it is a tiny one dominated by sleep."),
			false, runsTable, xpPerDay, xpPerHour);

		bank = new StatSection(layout, BANK_KEY, "Bank", Tooltips.html(bankTooltip()), false,
			expectedTable, expectedUnlocks, expectedValue, lockedCrops);

		checks = new StatSection(layout, CHECKS_KEY, "Checks", Tooltips.html(
			"Disease, on the account's own numbers rather than on the published ones."
				+ "<br><br>The only claim on this tab that could not be checked at all until it"
				+ " was measured directly: a dead patch produces no harvest, so nothing built on"
				+ " the harvest log can see one. This counts observed growth cycles against the"
				+ " survival chance predicted for those same cycles."
				+ "<br><br>Protected and immune patches are left out of both sides, so protection"
				+ " is neither credited nor charged here."),
			false, diseaseNote);

		sections.add(harvests);
		sections.add(runs);
		sections.add(bank);
		sections.add(checks);

		body.add(nothingYet);
		body.add(sections);
		add(body, BorderLayout.NORTH);
		refresh();
	}

	/** Redraws from the stores. Must run on the EDT. */
	void refresh()
	{
		boolean recorded = stats.getTotalHarvests() > 0 || stats.getTotalItems() > 0;

		harvests.setVisible(recorded);
		checks.setVisible(recorded);

		if (recorded)
		{
			// Both views of the store, read once for the whole refresh. Several sections and a
			// tooltip per row all want them, and each call rebuilds a map over every crop and
			// tier - the same mistake the patch list was carrying when it walked itself four
			// times per refresh.
			byCrop = stats.getByCrop();
			tiers = tiersByCrop();

			rebuildHarvests();
			rebuildChecks();
		}

		boolean anyRuns = rebuildRuns();
		runs.setVisible(anyRuns);

		boolean seedsToPlant = rebuildBank();
		bank.setVisible(seedsToPlant);

		// Every section that found something has to count here, or one of them gets built,
		// marked visible, and then hidden anyway inside a container nobody switched on. Runs
		// were missing from this test: they come from harvests.csv rather than from the
		// rolled-up store, and *Clear harvest history* empties the store without touching the
		// file - so a cleared account that restarted read its runs back and still showed
		// nothing at all.
		boolean anything = recorded || anyRuns || seedsToPlant;
		nothingYet.setVisible(!anything);
		sections.setVisible(anything);

		// A fresh install has seeds and no harvests, so the card that is open by default is also
		// the one that is hidden. Without this the player's first sight of the tab is a shut
		// accordion, which reads as a tab that failed to load.
		if (!openedBankForFreshInstall && !recorded && seedsToPlant && !bank.isOpen())
		{
			openedBankForFreshInstall = true;
			bank.setOpen(true, false);
		}
	}

	/**
	 * Shows every crop, or goes back to the readable twelve.
	 *
	 * <p>Rebuilds through {@link #refresh} rather than adding the missing rows, so the total row
	 * and the action row's own label are derived once from the same list. The explicit relayout
	 * is because the row that raised this event has just been removed from under it.
	 */
	private void toggleAllCrops()
	{
		showAllCrops = !showAllCrops;
		refresh();
		revalidate();
		repaint();
	}

	private void toggleAllSeeds()
	{
		showAllSeeds = !showAllSeeds;
		refresh();
		revalidate();
		repaint();
	}

	// ------------------------------------------------------------------ harvests

	private void rebuildHarvests()
	{
		harvests.setHeadline(DataTable.shortNumber(stats.getTotalXp()));
		harvests.setCaption(harvestsCaption());

		byCrop.sort(Comparator.comparingDouble(CropHarvestStats::getTotalXp).reversed());

		cropTable.clearRows();
		int limit = showAllCrops ? byCrop.size() : MAX_ROWS;
		int shown = 0;
		for (CropHarvestStats crop : byCrop)
		{
			if (shown++ >= limit)
			{
				break;
			}
			cropTable.addRow(crop.getCrop(), cropTooltip(crop, tiers.get(crop.getCrop())),
				new Color[]{null, null, null, differenceColour(crop)},
				DataTable.count(crop.getHarvests()),
				DataTable.count(crop.getTotalItems()),
				DataTable.shortNumber(crop.getTotalXp()),
				difference(crop));
		}
		if (byCrop.size() > MAX_ROWS)
		{
			cropTable.addActionRow(
				showAllCrops
					? "▴ show " + MAX_ROWS
					: "▾ show all " + byCrop.size() + " crops",
				Tooltips.text("The list is cut at " + MAX_ROWS + " crops because beyond that it"
					+ " stops being readable. The total below counts every crop either way."),
				this::toggleAllCrops);
		}

		cropTable.addTotalRow("total",
			DataTable.count(stats.getTotalHarvests()),
			DataTable.count(stats.getTotalItems()),
			DataTable.shortNumber(stats.getTotalXp()),
			"");

		String value = describeLifetimeValue();
		lifetimeValue.setText(value);
		lifetimeValue.setVisible(!value.isEmpty());
	}

	/**
	 * The one grey line under the Harvests headline: how many patches, since when, how often.
	 *
	 * <p>The cadence is one division rather than two roundings. The old line printed
	 * {@code Math.round(weeks)} beside {@code patches / weeks}, so "618 patches over 3 weeks,
	 * about 184 a week" multiplied out to 552 — two figures rounded from the same number and
	 * disagreeing on screen. Only the rate is shown now; the span is what "since" already says.
	 */
	private String harvestsCaption()
	{
		StringBuilder text = new StringBuilder(
			Plurals.of(stats.getTotalHarvests(), "patch", "patches"));

		// Without the start date these read as an account's whole farming history. They are not:
		// they begin the day the plugin was installed, and saying so is the difference between a
		// modest number and a wrong one.
		String since = firstHarvestDate();
		if (since != null)
		{
			text.append(" since ").append(since);
		}

		long first = stats.getFirstHarvest();
		long last = stats.getLastHarvest();
		if (first > 0 && last - first >= WEEK)
		{
			// Below a week the rate is one week's farming extrapolated, which for a skill you
			// touch every few days is a number invented rather than measured.
			double weeks = (last - first) / (double) WEEK;
			text.append(String.format(" · ~%.0f a week", stats.getTotalHarvests() / weeks));
		}
		return text.toString();
	}

	/**
	 * What the whole history would fetch today.
	 *
	 * <p>Deliberately "would fetch <b>now</b>". Nothing ever recorded the price at the time and
	 * historical prices are not available offline, so this is not a lifetime earnings claim and
	 * must not read like one — it is the right number for deciding what to plant next and the
	 * wrong one for anything retrospective. That sentence is on the line's tooltip rather than
	 * beside it, because it is a caveat and the number is the answer.
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
		return value <= 0 ? "" : "Worth about " + DataTable.shortNumber(value) + " gp";
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

	// ---------------------------------------------------------------------- +/-

	/**
	 * The signed percentage against expectation, or blank where the crop cannot carry one.
	 *
	 * <p>The gate is {@link CropHarvestStats#hasSurplus}, the store's own predicate, rather than
	 * a rule of the panel's: a difference is only a difference if the prediction it is measured
	 * against is a real distribution's mean. Bushes, cacti, calquat and the trees have no
	 * published formula, so the model falls back to a floor or to a wiki average measured at 99
	 * with a cape — and a crop can then read hundreds of items "over expectation" when the truth
	 * is that nobody knows what to expect. Those fall out of the column instead.
	 *
	 * <p>An empty cell, never a {@code "-"}: in a column of signed numbers a dash reads as a
	 * minus sign, and it reads as broken rather than as deliberately withheld. The tooltip says
	 * which.
	 */
	private static String difference(CropHarvestStats crop)
	{
		return crop.hasSurplus()
			? String.format("%+.0f%%", (crop.getAccuracy() - 1) * 100)
			: "";
	}

	/**
	 * Green over, red under, and grey until the sample settles.
	 *
	 * <p>The floor is {@link CropHarvestStats#MIN_PATCHES_FOR_LUCK}, reused rather than
	 * reinvented: its javadoc justifies twenty on the grounds that the spread grows like
	 * {@code √n} while the total grows like {@code n}, which is exactly the argument for not
	 * colouring a difference over four patches. The number is still shown — it is true — it just
	 * is not dressed up as a finding.
	 */
	@Nullable
	private static Color differenceColour(CropHarvestStats crop)
	{
		if (!crop.hasSurplus())
		{
			return null;
		}
		if (crop.getHarvests() < CropHarvestStats.MIN_PATCHES_FOR_LUCK)
		{
			return ColorScheme.LIGHT_GRAY_COLOR;
		}
		double accuracy = crop.getAccuracy();
		if (accuracy > 1)
		{
			return OVER;
		}
		return accuracy < 1 ? UNDER : ColorScheme.TEXT_COLOR;
	}

	/**
	 * The {@code +/-} column, explained on its own header.
	 *
	 * <p>Two things have to be said here and neither fits in a 47px column. The first is that
	 * {@code got} and {@code +/-} are computed over different sets of patches on purpose — a
	 * half-picked patch is a real pile of items and a fake low yield — which was invisible while
	 * they lived in two different tables and is unavoidable now they share a row. The second is
	 * the sample floor under the colour.
	 */
	private static String differenceTooltip()
	{
		return Tooltips.html("How far above or below the plugin's own prediction you landed, as a"
			+ " percentage.<br><br>Each patch was predicted using the level, compost and gear in"
			+ " play at the time, so the totals compare like with like."
			+ "<br><br><b>got</b> counts every item including the ones from patches you walked"
			+ " away from; <b>+/-</b> counts only patches picked clean, because a half-picked"
			+ " patch is not a low yield. That is why the two do not reconcile."
			+ "<br><br>Coloured from " + CropHarvestStats.MIN_PATCHES_FOR_LUCK + " picked patches"
			+ " on. Below that the figure is shown in grey: the spread grows like the square root"
			+ " of the patch count while the total grows with it, so a handful of patches is"
			+ " noise. Blank means either fewer than " + CropHarvestStats.MIN_PATCHES_FOR_SURPLUS
			+ " picked patches, or a crop with no published yield formula to be measured"
			+ " against - a bush, a cactus, a calquat or a tree, where the plugin's"
			+ " \"prediction\" is a floor or a level-99 average standing in for a formula nobody"
			+ " has.");
	}

	// -------------------------------------------------------------------- runs

	/**
	 * What your sittings look like, and the rates that follow from them.
	 *
	 * @return whether any run has been reconstructed, and so whether the card is drawn
	 */
	private boolean rebuildRuns()
	{
		// Read once. Every figure below is derived from this same list, so the count in the
		// caption cannot disagree with the average in the table.
		List<FarmRun> all = history.getRuns();
		if (all.isEmpty())
		{
			return false;
		}

		double perRun = history.getXpPerRun();
		String toLevel = describeRunsToNextLevel();

		// The most useful line on the tab, and it used to be the third sentence of the third
		// paragraph of the third section - below the fold on every screen.
		runs.setHeadline(toLevel != null ? toLevel : DataTable.shortNumber(perRun));
		runs.setCaption(all.size() == 1
			? "first run"
			: DataTable.shortNumber(perRun) + " xp a run over "
				+ Plurals.of(all.size(), "run", "runs"));

		runsTable.clearRows();
		addRunRow("last", history.getLastRun());
		addRunRow("best", history.getBestRun());
		if (all.size() > 1)
		{
			runsTable.addTotalRow("average",
				String.format("%.1f",
					all.stream().mapToInt(FarmRun::getPatches).sum() / (double) all.size()),
				String.format("%.0f",
					all.stream().mapToInt(FarmRun::getItems).sum() / (double) all.size()),
				DataTable.shortNumber(perRun));
		}

		double perDay = history.getXpPerDay();
		xpPerDay.setText(perDay > 0 ? DataTable.shortNumber(perDay) + " xp a day" : "");
		xpPerDay.setToolTipText(Tooltips.text("Averaged over the whole history, sleep included -"
			+ " the rate a skill on a growth timer actually runs at, and the one nobody"
			+ " displays."));
		xpPerDay.setVisible(perDay > 0);

		double active = history.getActiveXpPerHour();
		xpPerHour.setText(active > 0 ? DataTable.shortNumber(active) + " xp an hour farming" : "");
		xpPerHour.setToolTipText(Tooltips.text("Measured from the first patch of a run to the"
			+ " last, so it is the rate while you are actually farming. Not a rate you can keep"
			+ " up - the crops grow for an hour between runs."));
		xpPerHour.setVisible(active > 0);
		return true;
	}

	private void addRunRow(String name, @Nullable FarmRun run)
	{
		if (run == null)
		{
			return;
		}
		runsTable.addRow(name, runTooltip(name, run), DataTable.count(run.getPatches()),
			DataTable.count(run.getItems()), DataTable.shortNumber(run.getXp()));
	}

	private static String runTooltip(String name, FarmRun run)
	{
		StringBuilder text = new StringBuilder("<b>").append(name).append(" run</b><br>")
			.append(Plurals.of(run.getPatches(), "patch", "patches")).append(", ")
			.append(DataTable.count(run.getItems())).append(" items, ")
			.append(DataTable.shortNumber(run.getXp())).append(" experience");

		if (run.getDuration() > 0)
		{
			text.append("<br>took ").append(TimeFormat.duration(run.getDuration()));
		}
		text.append("<br>").append(TimeFormat.since(run.getEndedAt()));
		return Tooltips.html(text.toString());
	}

	/**
	 * How many more runs the next Farming level is.
	 *
	 * <p>The Runs headline. It costs almost nothing — the experience to the next level is one API
	 * call and the experience per run is already here — and it is the one thing on this tab a
	 * player would act on.
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
		return Plurals.of(runs, "run", "runs") + " to " + (level + 1);
	}

	// -------------------------------------------------------------------- bank

	/**
	 * What the bank is worth planted out.
	 *
	 * <p>The only card that reads the seed stores rather than the harvest history, so it is
	 * also the only one that can say anything on a fresh install.
	 *
	 * @return whether it has anything to show, and so whether the card is drawn at all
	 */
	private boolean rebuildBank()
	{
		PlantOutEstimate.Projection projection = PlantOutEstimate.of(ownedSeeds(),
			patchesByType(), compostByType(), bonuses.current(), seeds.getFarmingXp());

		List<PlantOutEstimate.Line> lines = projection.getLines();
		if (lines.isEmpty() && projection.getLockedCrops() == 0)
		{
			return false;
		}

		bank.setHeadline(lines.isEmpty() ? "" : DataTable.shortNumber(projection.getXp()));
		bank.setCaption(bankCaption(projection));
		bank.setTooltip(Tooltips.html(bankTooltip()
			+ "<br><br>" + describeExpected(projection).replace("\n", "<br>")));

		expectedTable.setVisible(!lines.isEmpty());
		expectedTable.clearRows();

		int limit = showAllSeeds ? lines.size() : MAX_ROWS;
		int shown = 0;
		int totalSeeds = 0;
		for (PlantOutEstimate.Line line : lines)
		{
			totalSeeds += line.getSeeds();
			if (shown++ >= limit)
			{
				continue;
			}
			expectedTable.addRow(line.getSeed().getName(), expectedTooltip(line),
				DataTable.count(line.getSeeds()),
				DataTable.shortNumber(line.getXpPerSeed()),
				DataTable.shortNumber(line.getXp()));
		}
		if (lines.size() > MAX_ROWS)
		{
			expectedTable.addActionRow(
				showAllSeeds
					? "▴ show " + MAX_ROWS
					: "▾ show all " + lines.size() + " seeds",
				Tooltips.text("The list is cut at " + MAX_ROWS + " stacks because beyond that it"
					+ " stops being readable. The total below counts every seed either way."),
				this::toggleAllSeeds);
		}
		if (!lines.isEmpty())
		{
			expectedTable.addTotalRow("total", DataTable.count(totalSeeds), "",
				DataTable.shortNumber(projection.getXp()));
		}

		expectedUnlocks.setText(describeUnlocks(projection));
		expectedUnlocks.setVisible(expectedUnlocks.getText().length() > 0);

		updateExpectedValue(projection);

		lockedCrops.setText(projection.getLockedCrops() > 0
			? "Holding " + Plurals.of(projection.getLockedCrops(), "crop", "crops")
				+ " you never reach"
			: "");
		lockedCrops.setVisible(projection.getLockedCrops() > 0);
		return true;
	}

	/** The one grey line under the Bank headline: what the number covers, and where it lands. */
	private static String bankCaption(PlantOutEstimate.Projection projection)
	{
		if (projection.getLines().isEmpty())
		{
			return "nothing you can plant yet";
		}
		return projection.getEndLevel() > projection.getStartLevel()
			? "everything you hold · " + projection.getStartLevel() + " → "
				+ projection.getEndLevel()
			: "not quite a level from " + projection.getStartLevel();
	}

	/** The fixed half of the Bank explanation; the projection's own numbers are appended. */
	private static String bankTooltip()
	{
		return "What the seeds you are holding are worth if you plant every one of them, through"
			+ " the patches you have switched on."
			+ "<br><br>Levels up as it goes: chance-to-save rises with your Farming level, so a"
			+ " big stack is worth more than its first patch suggests."
			+ "<br><br><b>Assumes you always plant the best experience per patch you can</b>, into"
			+ " the patches you have switched on. Every seed goes in eventually - the order is"
			+ " what changes."
			+ "<br><br><b>Says nothing about time.</b> Growth is real but so is logging off, and"
			+ " a date would be the first dishonest number on this tab.";
	}

	/**
	 * The projection's own working, which now lives on the header rather than under it.
	 *
	 * <p>Three sentences of methodology that were the top of the section. They are still exact
	 * and still worth reading; they are simply not the answer, and the answer is a number that
	 * was buried in the middle of them.
	 */
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
				.append("; the other ").append(DataTable.count(projection.getSeedsBeyondMaxLevel()))
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
	 * <p>The part of this card that answers "show me my path", and the only place the simulation's
	 * working is visible rather than just its total. A list rather than prose, which is why it is
	 * the one block of {@link WrappedText} that survived the move to tooltips. Capped because a
	 * bank of forty crops from level 1 unlocks something every few levels, and a wall of them
	 * stops being a route and starts being a list.
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
				.append(" (").append(DataTable.count(unlock.getSeeds())).append(" banked)");
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
	 * would be worse than omitting both. All of that is on the line's tooltip: the produce figure
	 * is the answer and the accounting is the footnote.
	 *
	 * <p><b>"Produced" rather than "profit" where the costs are notional.</b> An ironman did not
	 * buy the seed and made the compost, so the same arithmetic means a different word for them
	 * — and since the plugin cannot tell, it says what it charged rather than claiming either.
	 */
	private void updateExpectedValue(PlantOutEstimate.Projection projection)
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
			expectedValue.setText("");
			expectedValue.setVisible(false);
			return;
		}

		StringBuilder detail = new StringBuilder();
		if (cost > 0)
		{
			// Expensive compost on a cheap crop makes this negative, and "so -3k net" reads
			// like a typo rather than a warning — a loss gets called one.
			long net = value - cost;
			detail.append("Against ").append(DataTable.shortNumber(cost))
				.append(" of seeds and compost - ");
			detail.append(net < 0
				? "a " + DataTable.shortNumber(-net) + " loss."
				: "so " + DataTable.shortNumber(net) + " net.");
			detail.append(' ');
		}
		detail.append("Today's prices, and no protection payments counted - the projection does"
			+ " not model disease either, and charging for a benefit it does not credit would be"
			+ " worse than omitting both.");

		expectedValue.setToolTipText(Tooltips.text(detail.toString()));
		expectedValue.setText("Worth about " + DataTable.shortNumber(value) + " gp of produce");
		expectedValue.setVisible(true);
	}

	private String expectedTooltip(PlantOutEstimate.Line line)
	{
		Seed seed = line.getSeed();
		StringBuilder text = new StringBuilder("<b>").append(seed.getName()).append("</b><br>")
			.append(DataTable.count(line.getSeeds()))
			.append(Plurals.pick(line.getSeeds(), " seed", " seeds"))
			.append(" filling ").append(Plurals.of(line.getPatches(), "patch", "patches"));

		if (seed.getSeedsPerPatch() > 1)
		{
			// Otherwise the patch count looks like a mistake: 100 potato seeds is 33 patches.
			text.append("<br>").append(seed.getSeedsPerPatch()).append(" seeds to a patch");
		}

		text.append("<br>").append(String.format("%,.0f", line.getItems()))
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

	// ------------------------------------------------------------------ checks

	/**
	 * Disease, on the account's own numbers rather than on the published ones.
	 *
	 * <p>All that is left of the old Validation section, and deliberately so. Its crop table was
	 * the Harvests table with one derived column; its level bands were a single band of nine
	 * hundred patches mixing thirty-four crops and four yield models, which cannot show a level
	 * curve; and its "23% more than estimated" verdict was measuring the plugin's own missed
	 * compost rather than the game. Disease is the one figure here that nothing else could
	 * produce.
	 */
	private void rebuildChecks()
	{
		int cycles = disease.getTotalCycles();
		int caught = disease.getTotalDiseased();

		if (cycles < MIN_CYCLES_FOR_DISEASE)
		{
			checks.setHeadline("");
			checks.setCaption("not enough growth cycles yet");
			// Named rather than left blank. A card that shows nothing looks broken; a card that
			// says what it is waiting for is answering the question it was opened with.
			diseaseNote.setText("Needs " + MIN_CYCLES_FOR_DISEASE + " growth cycles, "
				+ cycles + " so far");
			diseaseNote.setToolTipText(Tooltips.text("Disease is a few percent a cycle for most"
				+ " crops, so a rate over a couple of dozen cycles is one patch either way and"
				+ " reads as a finding. Fifty is the floor at which it stops being actively"
				+ " misleading, not the one at which it becomes reliable."));
			diseaseNote.setVisible(true);
			return;
		}

		double expected = cycles - disease.getPredictedSurvivals();
		checks.setHeadline(String.format("%.0f%%", 100.0 * caught / cycles));
		checks.setCaption(caught + " of " + Plurals.of(cycles, "growth cycle", "growth cycles"));

		diseaseNote.setText(String.format("Disease: %,d of %,d cycles, predicted %,.0f",
			caught, cycles, expected));
		diseaseNote.setToolTipText(Tooltips.text(String.format(
			"%,d of %s caught something, against a predicted %,.0f. %,d died."
				+ " The prediction is the wiki's survival chance applied to those same cycles,"
				+ " using the compost the plugin saw go into each patch - so a bucket it never"
				+ " watched go in counts as untreated and pushes the prediction up.",
			caught, Plurals.of(cycles, "growth cycle", "growth cycles"), expected,
			disease.getTotalDied())));
		diseaseNote.setVisible(true);
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
	 * Everything about one crop that will not fit in four narrow columns.
	 *
	 * <p>The compost breakdown is the part worth hovering for: compost is the single biggest
	 * lever on yield, so a crop's overall average mixes conditions that are not comparable,
	 * and the split is what makes the number mean anything.
	 *
	 * <p>It also carries the two things the merged row can no longer say for itself — why the
	 * {@code +/-} cell is grey, or empty — and the histogram, which is the shape the running
	 * totals cannot reconstruct.
	 */
	private String cropTooltip(CropHarvestStats crop, @Nullable List<CropHarvestStats> tiers)
	{
		StringBuilder text = new StringBuilder("<b>").append(crop.getCrop())
			.append("</b><br>").append(DataTable.count(crop.getHarvests()))
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
			// Spelt out because these are the items the +/- deliberately ignores, and the gap
			// between "got" and the difference beside it is otherwise unexplained.
			text.append("<br>").append(DataTable.count(crop.getPartialItems()))
				.append(" more from patches left standing, not counted in the average");
		}
		text.append("<br>").append(DataTable.shortNumber(crop.getTotalXp())).append(" experience");

		text.append("<br><br>").append(explainDifference(crop));

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
					.append(DataTable.count(tier.getHarvests()))
					.append(Plurals.pick(tier.getHarvests(), " patch", " patches"));
			}
		}

		text.append(histogram(crop.getCrop()));
		return Tooltips.html(text.toString());
	}

	/** Why this crop's {@code +/-} is coloured, grey, or absent. */
	private static String explainDifference(CropHarvestStats crop)
	{
		if (!crop.hasSurplus())
		{
			if (crop.getPredicted() > 0 && crop.getPredictedVariance() > 0
				&& crop.getVariancePatches() == crop.getHarvests())
			{
				return "No <b>+/-</b> yet: a single patch scatters by three or four either way, so"
					+ " under " + CropHarvestStats.MIN_PATCHES_FOR_SURPLUS + " picked patches the"
					+ " figure would be quoting one roll and calling it a tendency.";
			}
			return "No <b>+/-</b> for this crop: the spread of a single patch is not modelled for"
				+ " it, or these harvests were recorded before it was. A difference against a"
				+ " prediction nobody has published would be measuring the guess.";
		}
		if (crop.getHarvests() < CropHarvestStats.MIN_PATCHES_FOR_LUCK)
		{
			return "The <b>+/-</b> is grey until "
				+ (CropHarvestStats.MIN_PATCHES_FOR_LUCK - crop.getHarvests())
				+ " more picked patches settle it. Below that the spread swamps the total.";
		}
		return String.format("<b>%,d</b> harvested against <b>%,.0f</b> predicted, over %s picked"
			+ " clean.", crop.getItems(), crop.getPredicted(),
			Plurals.of(crop.getHarvests(), "patch", "patches"));
	}

	/**
	 * Where a crop's patches actually clustered, as bars.
	 *
	 * <p>The difference column says you are 4% up; this says <i>how</i>. They answer different
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

	/**
	 * A one-line note inside a card's body.
	 *
	 * <p>A plain label, never {@link WrappedText}: a text area computes its height against the
	 * full sidebar width and clips its last line the moment it is laid out any narrower, which is
	 * exactly what a nested card does to it. One line, hard cap, and anything longer belongs on
	 * the tooltip — which is the prose budget for the whole tab in one sentence.
	 */
	private static JLabel note()
	{
		JLabel label = new JLabel();
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setAlignmentX(LEFT_ALIGNMENT);
		label.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		return label;
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
