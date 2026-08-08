package com.dooglemaps.ui;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.ItemPrices;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Seed;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.state.CompostSelectionStore;
import com.dooglemaps.state.FarmingBonusStore;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.timer.FarmingBonuses;
import com.dooglemaps.validate.DiseaseStats;
import com.dooglemaps.validate.DiseaseStatsStore;
import com.dooglemaps.validate.FarmRun;
import com.dooglemaps.validate.HarvestHistory;
import com.dooglemaps.validate.HarvestStatsStore;
import com.google.gson.Gson;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Constructor;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.text.JTextComponent;
import net.runelite.api.Experience;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.laf.RuneLiteLAF;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Covers what the harvest history actually puts on screen.
 *
 * <p>{@code HarvestStatsStoreTest} already pins the arithmetic, so this is about the panel's
 * end of the bargain: that the distinction the store is careful to maintain — items from
 * abandoned patches count towards your totals but never towards an average — survives being
 * rendered, that a percentile is shown only where it means something, and that the tab fits
 * the sidebar it has to live in.
 *
 * <p>Rows are looked up <b>within a named table</b> rather than across the panel, because the
 * tab now lists the same crop three times over — once per section — and each column means
 * something different.
 */
public class HarvestStatsPanelTest
{
	/** The sidebar's width. Anything wider is clipped in the client. */
	private static final int SIDEBAR_WIDTH = 225;

	/** An empty bank, which is what every test about the history sections wants. */
	private static final Map<Seed, Integer> NO_SEEDS = Collections.emptyMap();

	private static final int FARMING_LEVEL = 75;
	private static final int PATCHES_PER_TYPE = 5;

	/**
	 * Two crops, one of them harvested under two different compost tiers, and four items
	 * picked from a ranarr patch that was walked away from.
	 *
	 * <p>The figures are chosen so a mistake shows: with the partial items wrongly folded in,
	 * ranarr averages 8.5 rather than 8.3.
	 *
	 * <p><b>No variance fields</b>, which makes this also the fixture for an older history —
	 * seventeen ranarr patches is well past the sample floor and still must not be scored.
	 */
	private static final String HISTORY =
		"{\"Ranarr weed|ULTRACOMPOST\":{\"crop\":\"Ranarr weed\",\"compost\":\"ULTRACOMPOST\","
			+ "\"harvests\":14,\"items\":128,\"predicted\":126.4,\"xp\":1820.0,\"best\":13,"
			+ "\"worst\":6,\"partialItems\":4,\"partialXp\":52.0},"
			+ "\"Ranarr weed|NONE\":{\"crop\":\"Ranarr weed\",\"compost\":\"NONE\","
			+ "\"harvests\":3,\"items\":13,\"predicted\":14.2,\"xp\":390.0,\"best\":6,"
			+ "\"worst\":3},"
			+ "\"Watermelon|ULTRACOMPOST\":{\"crop\":\"Watermelon\",\"compost\":\"ULTRACOMPOST\","
			+ "\"harvests\":8,\"items\":92,\"predicted\":88.8,\"xp\":1160.0,\"best\":15,"
			+ "\"worst\":9}}";

	/**
	 * A history recorded with the spread, and a lucky one: 220 items against a predicted 200.
	 *
	 * <p>25 patches at a variance of 3.5 each puts the total's standard deviation at 9.35, so
	 * being 20 up is a shade over two of them — the 98th percentile.
	 */
	private static final String SCORED_HISTORY =
		"{\"Ranarr weed|ULTRACOMPOST\":{\"crop\":\"Ranarr weed\",\"compost\":\"ULTRACOMPOST\","
			+ "\"harvests\":25,\"items\":220,\"predicted\":200.0,\"predictedVariance\":87.5,"
			+ "\"variancePatches\":25,\"xp\":3000.0,\"best\":14,\"worst\":5}}";

	private HarvestStatsStore stats;

	@Before
	public void setUp() throws Exception
	{
		stats = storeOf(HISTORY);
	}

	@Test
	public void abandonedPatchesCountTowardsTheTotalButNotTheAverage()
	{
		List<String> row = rowFor(panel(), HarvestStatsPanel.VALIDATION_TABLE, "Ranarr weed");

		// The patch count is what makes the other two columns legible: without it, 145 items
		// beside an average of 8.3 looks like arithmetic that does not add up.
		assertEquals("14 + 3 patches picked clean", "17", row.get(1));
		// 128 finished plus 13 finished plus the 4 left standing.
		assertEquals("everything picked belongs in the lifetime total", "145", row.get(2));
		// 141 over 17 finished patches. Counting the partial's items would give 8.5.
		assertEquals("a half-picked patch is not a low yield", "8.3", row.get(3));
	}

	/**
	 * The abandoned items are called out where the gap they cause is visible.
	 *
	 * <p>145 items over 17 patches averaging 8.3 does not reconcile until you know four of
	 * those items came from a patch that was never finished.
	 */
	@Test
	public void theTooltipExplainsTheItemsTheAverageIgnores()
	{
		String tooltip = tooltipFor(panel(), HarvestStatsPanel.VALIDATION_TABLE, "Ranarr weed");
		assertNotNull(tooltip);
		assertTrue("the partial items need saying out loud, " + tooltip,
			tooltip.contains("4 more from patches left standing"));
	}

	@Test
	public void compostTiersAreSummedInTheRowAndSplitInTheTooltip()
	{
		HarvestStatsPanel panel = panel();

		// One line per crop, not one per crop and tier - otherwise ranarr appears twice.
		assertEquals(1,
			rowsNamed(tableNamed(panel, HarvestStatsPanel.VALIDATION_TABLE), "Ranarr weed").size());

		String tooltip = tooltipFor(panel, HarvestStatsPanel.VALIDATION_TABLE, "Ranarr weed");
		assertNotNull("the tier split is the reason to hover", tooltip);
		assertTrue("ultracompost's own average should be there, " + tooltip,
			tooltip.contains("Ultracompost"));
		assertTrue("and so should the untreated one, " + tooltip, tooltip.contains("Untreated"));
	}

	// ---------------------------------------------------------------- lifetime

	/**
	 * The lifetime rows lead on experience, and count every item including the partials.
	 *
	 * <p>Sorted by experience because that is what the reader is scanning for. Ranarr's 2262
	 * beats watermelon's 1160 while watermelon is not far behind on items, so a sort left on
	 * the old items ordering would still put ranarr first — the watermelon row's position is
	 * what actually pins this.
	 */
	@Test
	public void lifetimeLeadsOnExperienceAndCountsEverythingPicked()
	{
		HarvestStatsPanel panel = panel();
		List<String> ranarr = rowFor(panel, HarvestStatsPanel.LIFETIME_TABLE, "Ranarr weed");

		assertEquals("17", ranarr.get(1));
		assertEquals("128 + 13 finished plus the 4 left standing", "145", ranarr.get(2));
		assertEquals("1820 + 390 + the partial's 52", "2.3k", ranarr.get(3));

		assertEquals("the biggest experience earner comes first", "Ranarr weed",
			cropOrder(panel, HarvestStatsPanel.LIFETIME_TABLE).get(0));
	}

	/**
	 * The section says when it started, because otherwise it claims to be something it is not.
	 *
	 * <p>These totals begin the day the plugin was installed, not the day the account did. A
	 * page headed "lifetime" with no start date reads as the second.
	 */
	@Test
	public void lifetimeSaysWhenItStartedCounting() throws Exception
	{
		long august = ZonedDateTime.of(2026, 8, 4, 12, 0, 0, 0, ZoneId.systemDefault())
			.toEpochSecond();
		String dated = HISTORY.replace("\"harvests\":3,", "\"firstHarvest\":" + august + ",\"harvests\":3,");

		String text = textOf(panelFor(storeOf(dated), NO_SEEDS));
		assertTrue("expected a start date, got " + text, text.contains("Since 4 August"));
	}

	// ---------------------------------------------------------------- expected

	/**
	 * The bank projection appears on an account that has harvested nothing at all.
	 *
	 * <p>The reason Expected is a section of its own rather than part of the history block. A
	 * player who has just installed the plugin and holds seeds has one useful question, and the
	 * tab used to answer it with "nothing recorded yet".
	 */
	@Test
	public void expectedShowsOnAFreshInstallWithSeedsInTheBank() throws Exception
	{
		HarvestStatsPanel panel = panelFor(emptyStore(), bank(Seed.RANARR, 200));

		String text = textOf(panel);
		assertTrue("a fresh install with seeds is not empty, " + text,
			!text.contains("Nothing here yet"));
		// "Ranarr", not "Ranarr weed": the rows are named from Produce, where the crop is the
		// herb rather than the item picked. The history fixtures above carry their own strings.
		assertEquals("200", rowFor(panel, HarvestStatsPanel.EXPECTED_TABLE, "Ranarr").get(1));
	}

	/** With neither history nor seeds there is genuinely nothing, and it says so. */
	@Test
	public void anEmptyHistoryAndAnEmptyBankSayNothingYet()
	{
		String text = textOf(panelFor(emptyStore(), NO_SEEDS));
		assertTrue("an empty tab reads as a bug, " + text, text.contains("Nothing here yet"));
	}

	/**
	 * Both figures are shown where they differ, and only where they differ.
	 *
	 * <p>Two hundred ranarr is two hundred patches, planted five at a time. That is fifteen
	 * levels to an account at 32 and not one level to an account at 75, which needs 100k
	 * experience to move and would get about half of it here. So the flat figure appears in the
	 * first case and is left out of the second, because printing the same number twice is
	 * padding rather than honesty.
	 */
	@Test
	public void expectedShowsTheLevellingUpOnlyWhereItChangesTheAnswer() throws Exception
	{
		String climbing = textOf(panelFor(emptyStore(), bank(Seed.RANARR, 200), 32));
		assertTrue("expected the plant-out headline, " + climbing,
			climbing.contains("Planting it all out"));
		assertTrue("expected the flat figure beside it, " + climbing,
			climbing.contains("Staying at 32 and planting only what you can plant now"));

		String settled = textOf(panelFor(emptyStore(), bank(Seed.RANARR, 200), 75));
		assertTrue("the section is still drawn, " + settled,
			settled.contains("Planting it all out"));
		assertTrue("nothing to level up, so no second figure: " + settled,
			!settled.contains("Staying at 75 and planting"));
	}

	/**
	 * A crop with no patches switched on does not appear, however many seeds are held.
	 *
	 * <p>The availability invariant reaching the stats tab: the fixture allows herb and
	 * allotment patches only, so a bank full of hops seeds projects to nothing.
	 */
	@Test
	public void aCropWithNoAvailablePatchesIsNotProjected() throws Exception
	{
		HarvestStatsPanel panel = panelFor(emptyStore(), bank(Seed.BARLEY, 500));

		String text = textOf(panel);
		assertTrue("hops patches are all switched off in this fixture, " + text,
			text.contains("Nothing here yet"));
	}

	/**
	 * From the bottom, the section shows the route rather than just the destination.
	 *
	 * <p>A level 1 account holding herb seeds cannot plant any of them, and the useful answer is
	 * not "nothing" — it is <i>potatoes take you to 9, guam takes you to 32, and your ranarr are
	 * waiting there</i>. The unlock list is that route.
	 */
	@Test
	public void thePathNamesWhatUnlocksOnTheWay() throws Exception
	{
		Map<Seed, Integer> bank = new LinkedHashMap<>();
		bank.put(Seed.POTATO, 9000);
		bank.put(Seed.GUAM, 500);
		bank.put(Seed.RANARR, 500);

		String text = textOf(panelFor(emptyStore(), bank, 1));

		assertTrue("expected the route, got " + text, text.contains("Unlocks on the way:"));
		assertTrue("guam is on it, " + text, text.contains("Guam"));
		assertTrue("and ranarr, with its stock named, " + text,
			text.contains("Ranarr (500 banked)"));
	}

	/** Where 99 falls in the bank is the question a big stack is actually asking. */
	@Test
	public void thePathSaysWhereNinetyNineArrives() throws Exception
	{
		String text = textOf(panelFor(emptyStore(), bank(Seed.RANARR, 200_000), 80));

		assertTrue("expected the 99 split, got " + text, text.contains("99 arrives on"));
		assertTrue("and what the surplus is worth, " + text, text.contains("more beyond it"));
	}

	/** The seeds-to-a-patch conversion is explained where it would otherwise look wrong. */
	@Test
	public void theTooltipExplainsSeedsThatShareAPatch() throws Exception
	{
		String tooltip = tooltipFor(panelFor(emptyStore(), bank(Seed.POTATO, 100)),
			HarvestStatsPanel.EXPECTED_TABLE, "Potato");

		assertNotNull(tooltip);
		assertTrue("100 seeds filling 33 patches needs explaining, " + tooltip,
			tooltip.contains("33 patches"));
		assertTrue(tooltip.contains("3 seeds to a patch"));
	}

	// ------------------------------------------------------------------- value

	/**
	 * The lifetime total gets a coin figure, and says out loud that it is today's price.
	 *
	 * <p>The caveat is not decoration. Nothing recorded what a herb was worth when it was
	 * picked, so this is "what it would fetch now" — right for deciding what to plant next and
	 * wrong for a lifetime earnings claim. Without the wording it reads as the second.
	 */
	@Test
	public void lifetimeValueIsPricedAtTodayAndSaysSo() throws Exception
	{
		String text = textOf(panelFor(storeOf(HISTORY), NO_SEEDS, FARMING_LEVEL, noRuns(),
			Mockito.mock(DiseaseStatsStore.class), pricesOf(6_000)));

		assertTrue("expected a coin figure, got " + text, text.contains("Worth about"));
		assertTrue("and the caveat with it, " + text,
			text.contains("what it would fetch now, not what it made at the time"));
	}

	/**
	 * The plant-out projection is priced net of what planting it costs.
	 *
	 * <p>Profit is the number that decides whether snapdragon beats ranarr this month; value
	 * alone is not. Protection payments are excluded and the line says so, because the
	 * projection does not model disease either.
	 */
	@Test
	public void expectedValueIsNetOfSeedsAndCompost() throws Exception
	{
		String text = textOf(panelFor(emptyStore(), bank(Seed.RANARR, 200), FARMING_LEVEL,
			noRuns(), Mockito.mock(DiseaseStatsStore.class), pricesOf(6_000)));

		assertTrue("expected a produce figure, got " + text, text.contains("gp of produce"));
		assertTrue("net of the inputs, " + text, text.contains("of seeds and compost"));
		assertTrue("with the exclusion named, " + text,
			text.contains("no protection payments counted"));
	}

	/**
	 * With no prices loaded there is no figure at all, rather than a figure of zero.
	 *
	 * <p>The item cache loads asynchronously and a panel can repaint before it is ready. "Worth
	 * about 0 gp" is a claim; showing nothing is the gap it actually is.
	 */
	@Test
	public void noPricesMeansNoCoinFigure() throws Exception
	{
		String text = textOf(panelFor(storeOf(HISTORY), NO_SEEDS, FARMING_LEVEL, noRuns(),
			Mockito.mock(DiseaseStatsStore.class), pricesOf(0)));

		assertTrue("nothing priced, so nothing claimed: " + text, !text.contains("Worth about"));
	}

	// ----------------------------------------------------------------- disease

	/**
	 * Disease is reported measured against predicted, which nothing else on the tab could do.
	 *
	 * <p>A dead patch produces no harvest, so the harvest log never sees one — this is the only
	 * figure here that had to be captured from the patch state instead.
	 */
	@Test
	public void diseaseIsReportedAgainstItsPrediction() throws Exception
	{
		String text = textOf(panelFor(storeOf(HISTORY), NO_SEEDS, FARMING_LEVEL, noRuns(),
			diseaseOf(200, 18, 6, 186.0)));

		assertTrue("expected an observed disease line, got " + text,
			text.contains("18 of 200 growth cycles caught something"));
		assertTrue("and the prediction beside it, " + text, text.contains("predicted 14"));
		assertTrue("and the deaths, " + text, text.contains("6 died"));
	}

	/**
	 * Below the floor it says nothing at all.
	 *
	 * <p>Disease is a few percent a cycle, so a rate over twenty cycles is one patch either way
	 * and reads as a finding. This is the section's most important negative.
	 */
	@Test
	public void aHandfulOfCyclesSaysNothingAboutDisease() throws Exception
	{
		String text = textOf(panelFor(storeOf(HISTORY), NO_SEEDS, FARMING_LEVEL, noRuns(),
			diseaseOf(12, 1, 0, 11.3)));

		assertTrue("twelve cycles is not a rate, " + text,
			!text.contains("caught something"));
	}

	// -------------------------------------------------------------------- runs

	/**
	 * Sittings are shown as last, best and average, and the rates are named for what they are.
	 *
	 * <p>The rate labels are the part worth pinning. An hourly figure measured over the run
	 * alone describes nothing sustainable, so it has to say <i>active</i>; the per-day figure is
	 * the honest throughput for a skill gated by a growth timer and has to not be called
	 * hourly.
	 */
	@Test
	public void runsShowLastBestAndAverageWithHonestRates() throws Exception
	{
		HarvestStatsPanel panel = panelFor(storeOf(HISTORY), NO_SEEDS, FARMING_LEVEL, someRuns());

		assertEquals("6", rowFor(panel, HarvestStatsPanel.RUNS_TABLE, "last").get(1));
		assertEquals("14", rowFor(panel, HarvestStatsPanel.RUNS_TABLE, "best").get(1));
		assertEquals("121", rowFor(panel, HarvestStatsPanel.RUNS_TABLE, "best").get(2));

		String text = textOf(panel);
		assertTrue("expected a per-day rate, got " + text, text.contains("xp a day"));
		assertTrue("the hourly one must be labelled active, " + text,
			text.contains("while you are actually farming"));
	}

	/**
	 * A history that exists only in the log still shows, rather than reading as an empty tab.
	 *
	 * <p>The exact shape of a real report. <i>Clear harvest history</i> empties the rolled-up
	 * store and the in-memory summaries but does not touch {@code harvests.csv}, so the next
	 * restart reads the runs straight back — and the empty-state test used to ask only about
	 * the store and the bank. The Runs section was built, marked visible, and then hidden
	 * anyway inside a container nothing had switched on.
	 */
	@Test
	public void runsAloneAreEnoughToNotBeAnEmptyTab() throws Exception
	{
		HarvestStatsPanel panel = panelFor(emptyStore(), NO_SEEDS, FARMING_LEVEL, someRuns());

		String text = textOf(panel);
		assertTrue("runs on their own are not nothing, " + text,
			!text.contains("Nothing here yet"));
		assertEquals("and the section is actually reachable", "6",
			rowFor(panel, HarvestStatsPanel.RUNS_TABLE, "last").get(1));
	}

	/** With no runs reconstructed the section stays away rather than showing empty rows. */
	@Test
	public void noRunsMeansNoRunsSection() throws Exception
	{
		String text = textOf(panelFor(storeOf(HISTORY), NO_SEEDS));
		assertTrue("nothing to cluster yet, " + text, !text.contains("xp a day"));
	}

	/** Runs to the next level is the cheapest useful line on the tab. */
	@Test
	public void theNextLevelIsCountedInRuns() throws Exception
	{
		String text = textOf(panelFor(storeOf(HISTORY), NO_SEEDS, 40, someRuns()));
		assertTrue("expected a runs-to-level line, got " + text, text.contains("more runs to 41"));
	}

	// -------------------------------------------------------------------- luck

	/**
	 * The cumulative figure is shown on a history with no spread; the percentile is not.
	 *
	 * <p>The two have different requirements and that is the whole point of splitting them.
	 * "You are four items up" is simply what you got minus what was predicted and needs no
	 * distribution behind it. "You are at the 98th percentile" needs one, and seventeen ranarr
	 * patches recorded before the spread existed cannot supply it however many there are.
	 */
	@Test
	public void luckIsCumulativeWithoutASpreadAndUnscoredWithoutOne()
	{
		HarvestStatsPanel panel = panel();

		// 141 against 140.6 for ranarr, 92 against 88.8 for watermelon.
		String text = textOf(panel);
		assertTrue("expected a cumulative surplus, got " + text,
			text.contains("4 items over expectation"));

		assertEquals("a history with no recorded spread cannot be placed", "-",
			rowFor(panel, HarvestStatsPanel.LUCK_TABLE, "Ranarr weed").get(3));
	}

	@Test
	public void aPercentileAppearsOnceTheSpreadHasBeenRecorded() throws Exception
	{
		HarvestStatsPanel panel = panelFor(storeOf(SCORED_HISTORY), NO_SEEDS);
		List<String> row = rowFor(panel, HarvestStatsPanel.LUCK_TABLE, "Ranarr weed");

		assertEquals("25", row.get(1));
		assertEquals("220 against a predicted 200", "+20", row.get(2));
		assertEquals("20 over a standard deviation of 9.35", "98th", row.get(3));
	}

	/**
	 * Below the sample floor there is no percentile, whatever the spread says.
	 *
	 * <p>The most important negative on the tab: a figure over a handful of patches is noise
	 * presented as a finding, and a stats page that does that is a machine for inventing
	 * patterns.
	 */
	@Test
	public void aShortHistoryIsNotScoredEvenWithASpread() throws Exception
	{
		String tooFew = SCORED_HISTORY
			.replace("\"harvests\":25", "\"harvests\":6")
			.replace("\"variancePatches\":25", "\"variancePatches\":6");

		HarvestStatsPanel panel = panelFor(storeOf(tooFew), NO_SEEDS);
		assertEquals("-", rowFor(panel, HarvestStatsPanel.LUCK_TABLE, "Ranarr weed").get(3));

		String tooltip = tooltipFor(panel, HarvestStatsPanel.LUCK_TABLE, "Ranarr weed");
		assertNotNull(tooltip);
		assertTrue("the blank needs explaining, " + tooltip, tooltip.contains("Not enough yet"));
	}

	/** Compost is answered from the player's own harvests, which is the only version anyone wants. */
	@Test
	public void compostIsComparedOnYourOwnNumbers()
	{
		String text = textOf(panel());
		// 128/14 against 13/3, over the 17 patches of the crop with two tiers behind it.
		assertTrue("expected a measured compost comparison, got " + text,
			text.contains("Ultracompost gave you 9.1 ranarr weed a patch against 4.3 untreated"));
	}

	/**
	 * One tier throughout degrades to saying so, rather than to half a comparison.
	 *
	 * <p>Most accounts are this case. "Ultracompost gave you 8.8 a patch" with nothing beside
	 * it is a fact dressed as a finding.
	 */
	@Test
	public void oneTierThroughoutSaysSoRatherThanComparingWithNothing() throws Exception
	{
		String text = textOf(panelFor(storeOf(SCORED_HISTORY), NO_SEEDS));
		assertTrue("expected the degraded form, got " + text,
			text.contains("only ever used ultracompost"));
	}

	/**
	 * Also writes the Stats tab to {@code build/harvest-stats.png}.
	 *
	 * <p>Same reasoning as {@code PanelRenderTest}: a Swing layout fault throws nothing, it
	 * just looks wrong, so leaving the image behind is the quickest way to check a change
	 * without launching the client.
	 */
	@Test
	public void theTableFitsTheSidebar() throws Exception
	{
		RuneLiteLAF.setup();

		draw(panel(), "harvest-stats.png");
		// The scored history too, because the percentile column only has anything in it there
		// and a column that is always blank in the picture cannot be checked from the picture.
		draw(panelFor(storeOf(SCORED_HISTORY), NO_SEEDS), "harvest-stats-scored.png");
		// And the whole tab at once, which is the only one that shows Expected in its place
		// between Luck and Validation - the section order is the thing a picture checks best.
		// Everything on at once, which is the only check that the sections still fit together.
		draw(panelFor(storeOf(HISTORY), fullBank(), 32, someRuns(), diseaseOf(214, 19, 7, 198.0),
			pricesOf(6_000)), "harvest-stats-full.png");
		// And the path from the bottom, which is the only picture the unlock list appears in.
		draw(panelFor(emptyStore(), pathBank(), 1), "harvest-stats-path.png");
		// The run clustering and the level curve, which only appear with a log behind them.
		draw(panelFor(storeOf(HISTORY), NO_SEEDS, FARMING_LEVEL, someRuns()), "harvest-stats-runs.png");
	}

	/** Lays the tab out at the sidebar's width, checks it fits, and leaves a picture behind. */
	private static void draw(HarvestStatsPanel panel, String name) throws Exception
	{
		panel.setSize(SIDEBAR_WIDTH, panel.getPreferredSize().height);
		layout(panel);

		assertTrue("the history must not push the sidebar wider: "
				+ panel.getPreferredSize().width,
			panel.getPreferredSize().width <= SIDEBAR_WIDTH);

		BufferedImage image = new BufferedImage(
			panel.getWidth(), Math.max(1, panel.getHeight()), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		panel.printAll(g);
		g.dispose();

		File out = new File("build/" + name);
		out.getParentFile().mkdirs();
		ImageIO.write(image, "png", out);
		System.out.println("wrote " + out.getAbsolutePath());
	}

	/**
	 * Lays a component out for real, which nothing does until it has a peer or is told to.
	 *
	 * <p>Only {@code doLayout}, so every layout manager gets to place its own children.
	 * Forcing each child to its preferred size instead produces a picture of a panel nobody
	 * will ever see — it was how this first reported the table as zero pixels wide.
	 */
	private static void layout(Container container)
	{
		container.doLayout();
		for (Component child : container.getComponents())
		{
			if (child instanceof Container)
			{
				layout((Container) child);
			}
		}
	}

	@Test
	public void theHeadlineNamesTheLifetimeTotals()
	{
		// 14 + 3 + 8 patches picked clean, and 145 + 92 items including the partials.
		String text = textOf(panel());
		assertTrue("expected 25 patches and 237 items, got " + text,
			text.contains("25 patches, 237 items"));
	}

	// ------------------------------------------------------------------ helpers

	private HarvestStatsPanel panel()
	{
		return panelFor(stats, NO_SEEDS);
	}

	/**
	 * A panel over one history and one bank.
	 *
	 * <p>The four stores behind the Expected section are mocked rather than built, because that
	 * section's arithmetic is {@code PlantOutEstimateTest}'s job — here they exist to be a bank,
	 * and most of these tests want that bank empty so the section stays out of the way.
	 */
	private static HarvestStatsPanel panelFor(HarvestStatsStore history, Map<Seed, Integer> bank)
	{
		return panelFor(history, bank, FARMING_LEVEL);
	}

	private static HarvestStatsPanel panelFor(HarvestStatsStore history, Map<Seed, Integer> bank,
		int farmingLevel)
	{
		return panelFor(history, bank, farmingLevel, noRuns());
	}

	private static HarvestStatsPanel panelFor(HarvestStatsStore history, Map<Seed, Integer> bank,
		int farmingLevel, HarvestHistory runLog)
	{
		return panelFor(history, bank, farmingLevel, runLog,
			Mockito.mock(DiseaseStatsStore.class), pricesOf(0));
	}

	private static HarvestStatsPanel panelFor(HarvestStatsStore history, Map<Seed, Integer> bank,
		int farmingLevel, HarvestHistory runLog, DiseaseStatsStore disease)
	{
		return panelFor(history, bank, farmingLevel, runLog, disease, pricesOf(0));
	}

	/** Every item priced the same, which is enough to tell a figure from its absence. */
	private static ItemPrices pricesOf(int each)
	{
		ItemPrices items = Mockito.mock(ItemPrices.class);
		when(items.get(Mockito.anyInt())).thenReturn(each);
		return items;
	}

	private static HarvestStatsPanel panelFor(HarvestStatsStore history, Map<Seed, Integer> bank,
		int farmingLevel, HarvestHistory runLog, DiseaseStatsStore disease, ItemPrices items)
	{
		SeedInventoryStore seeds = Mockito.mock(SeedInventoryStore.class);
		for (Map.Entry<Seed, Integer> entry : bank.entrySet())
		{
			when(seeds.getOwned(entry.getKey())).thenReturn(entry.getValue());
		}
		when(seeds.getFarmingXp()).thenReturn(Experience.getXpForLevel(farmingLevel));

		AvailabilityProfile availability = Mockito.mock(AvailabilityProfile.class);
		when(availability.getAvailablePatches(Mockito.any()))
			.thenAnswer(call -> patchesOfType(call.getArgument(0)));

		CompostSelectionStore compost = Mockito.mock(CompostSelectionStore.class);
		when(compost.get(Mockito.any(PatchImplementation.class)))
			.thenReturn(CompostTier.ULTRACOMPOST);

		FarmingBonusStore bonuses = Mockito.mock(FarmingBonusStore.class);
		when(bonuses.current()).thenReturn(FarmingBonuses.NONE);

		return new HarvestStatsPanel(history, runLog, disease, seeds, availability, compost,
			bonuses, items);
	}

	/** A plausible mid-level bank: a herb stack, an allotment stack, and one locked crop. */
	private static Map<Seed, Integer> fullBank()
	{
		Map<Seed, Integer> owned = new LinkedHashMap<>();
		owned.put(Seed.RANARR, 200);
		owned.put(Seed.GUAM, 80);
		owned.put(Seed.WATERMELON, 150);
		owned.put(Seed.TORSTOL, 40);
		return owned;
	}

	/**
	 * A run log with nothing in it.
	 *
	 * <p>Stubbed rather than built, because the panel's job here is rendering what the store
	 * returns and {@code HarvestHistoryTest} already owns the clustering. The empty lists are
	 * what Mockito would default to anyway; they are written out so the fixture says what it is
	 * rather than relying on that.
	 */
	private static HarvestHistory noRuns()
	{
		HarvestHistory runs = Mockito.mock(HarvestHistory.class);
		when(runs.getRuns()).thenReturn(Collections.emptyList());
		when(runs.getLevelBands()).thenReturn(Collections.emptyList());
		return runs;
	}

	/** A disease record with the totals the Validation lines are built from. */
	private static DiseaseStatsStore diseaseOf(int cycles, int caught, int died, double predicted)
	{
		DiseaseStats entry = new DiseaseStats();
		entry.setCrop("Ranarr weed");
		entry.setCompost("ULTRACOMPOST");
		entry.setCycles(cycles);
		entry.setDiseased(caught);
		entry.setDied(died);
		entry.setPredictedSurvivals(predicted);

		DiseaseStatsStore store = Mockito.mock(DiseaseStatsStore.class);
		when(store.getTotalCycles()).thenReturn(cycles);
		when(store.getTotalDiseased()).thenReturn(caught);
		when(store.getTotalDied()).thenReturn(died);
		when(store.getPredictedSurvivals()).thenReturn(predicted);
		when(store.getAll()).thenReturn(Collections.singletonList(entry));
		return store;
	}

	/** A log with two sittings in it and a level curve worth drawing. */
	private static HarvestHistory someRuns()
	{
		FarmRun last = farmRun(6, 48, 1_500, 10_000, 10_600);
		FarmRun best = farmRun(14, 121, 4_200, 5_000, 6_100);

		HarvestHistory runs = Mockito.mock(HarvestHistory.class);
		when(runs.getRuns()).thenReturn(java.util.Arrays.asList(best, last));
		when(runs.getLastRun()).thenReturn(last);
		when(runs.getBestRun()).thenReturn(best);
		when(runs.getXpPerRun()).thenReturn(2_800.0);
		when(runs.getXpPerDay()).thenReturn(21_000.0);
		when(runs.getActiveXpPerHour()).thenReturn(52_000.0);
		when(runs.getLevelBands()).thenReturn(java.util.Arrays.asList(
			new HarvestHistory.LevelBand(30, 40, 260, 268.0),
			new HarvestHistory.LevelBand(70, 55, 470, 461.0)));
		when(runs.getHistogram(Mockito.anyString())).thenReturn(new HarvestHistory.Histogram(
			"Ranarr weed", new int[]{2, 4, 9, 14, 8, 5, 3}, 45));
		return runs;
	}

	private static FarmRun farmRun(int patches, int items, double xp, long from, long to)
	{
		FarmRun run = new FarmRun();
		run.setPatches(patches);
		run.setItems(items);
		run.setXp(xp);
		run.setStartedAt(from);
		run.setEndedAt(to);
		return run;
	}

	/** A level 1 account that has been given a lot of seeds, which is the path-to-99 case. */
	private static Map<Seed, Integer> pathBank()
	{
		Map<Seed, Integer> owned = new LinkedHashMap<>();
		owned.put(Seed.POTATO, 30_000);
		owned.put(Seed.GUAM, 4_000);
		owned.put(Seed.RANARR, 4_000);
		owned.put(Seed.SNAPDRAGON, 2_000);
		owned.put(Seed.TORSTOL, 1_000);
		return owned;
	}

	private static Map<Seed, Integer> bank(Seed seed, int count)
	{
		Map<Seed, Integer> owned = new LinkedHashMap<>();
		owned.put(seed, count);
		return owned;
	}

	/** Five of every patch type the fixtures plant into, none of anything else. */
	private static List<FarmPatch> patchesOfType(PatchImplementation type)
	{
		if (type != PatchImplementation.HERB && type != PatchImplementation.ALLOTMENT)
		{
			return Collections.emptyList();
		}
		List<FarmPatch> all = FarmingWorldData.getPatches(type);
		return all.subList(0, Math.min(PATCHES_PER_TYPE, all.size()));
	}

	/** A store loaded from one JSON history, which is the only input the panel has. */
	private static HarvestStatsStore storeOf(@Nullable String history) throws Exception
	{
		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration("dooglemaps", "harvestStats"))
			.thenReturn(history);

		HarvestStatsStore store = construct(HarvestStatsStore.class, configManager, new Gson());
		store.load();
		return store;
	}

	private HarvestStatsStore emptyStore()
	{
		try
		{
			return storeOf(null);
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
	}

	/**
	 * The table a section draws into, found by the name the panel gives it.
	 *
	 * <p>By name because the tab lists the same crops three times over and the assertions are
	 * about which section a number appears in. Position in the component tree would say the
	 * same thing far more fragilely.
	 */
	private static Container tableNamed(Container panel, String name)
	{
		Container found = search(panel, name);
		assertNotNull("no table named " + name, found);
		return found;
	}

	@Nullable
	private static Container search(Container container, String name)
	{
		for (Component child : container.getComponents())
		{
			if (!(child instanceof Container))
			{
				continue;
			}
			if (name.equals(child.getName()))
			{
				return (Container) child;
			}
			Container found = search((Container) child, name);
			if (found != null)
			{
				return found;
			}
		}
		return null;
	}

	/** The crop names of a table's rows, in the order they are drawn. */
	private static List<String> cropOrder(Container panel, String table)
	{
		List<String> names = new ArrayList<>();
		for (Container row : rows(tableNamed(panel, table)))
		{
			List<Component> cells = labels(row);
			if (!cells.isEmpty())
			{
				names.add(((JLabel) cells.get(0)).getText());
			}
		}
		// The heading row leads and is not a crop.
		return names.subList(1, names.size());
	}

	/** The cell texts of the one row in {@code table} whose name column reads {@code name}. */
	private static List<String> rowFor(Container panel, String table, String name)
	{
		List<List<String>> matches = rowsNamed(tableNamed(panel, table), name);
		assertEquals("expected exactly one " + name + " row in " + table, 1, matches.size());
		return matches.get(0);
	}

	private static List<List<String>> rowsNamed(Container panel, String name)
	{
		List<List<String>> matches = new ArrayList<>();
		for (Container row : rows(panel))
		{
			List<String> cells = new ArrayList<>();
			for (Component child : labels(row))
			{
				cells.add(((JLabel) child).getText());
			}
			if (!cells.isEmpty() && name.equals(cells.get(0)))
			{
				matches.add(cells);
			}
		}
		return matches;
	}

	@Nullable
	private static String tooltipFor(Container panel, String table, String name)
	{
		for (Container row : rows(tableNamed(panel, table)))
		{
			List<Component> cells = labels(row);
			if (!cells.isEmpty() && name.equals(((JLabel) cells.get(0)).getText())
				&& row instanceof javax.swing.JComponent)
			{
				return ((javax.swing.JComponent) row).getToolTipText();
			}
		}
		return null;
	}

	/**
	 * Every container holding a name label, i.e. every table row.
	 *
	 * <p>Found by shape rather than by type because {@code DataTable} builds rows out of plain
	 * panels; a marker class would exist only for this test.
	 */
	private static List<Container> rows(Container panel)
	{
		List<Container> found = new ArrayList<>();
		collectRows(panel, found);
		return found;
	}

	private static void collectRows(Container container, List<Container> found)
	{
		if (!labels(container).isEmpty())
		{
			found.add(container);
		}
		for (Component child : container.getComponents())
		{
			if (child instanceof Container)
			{
				collectRows((Container) child, found);
			}
		}
	}

	/** The labels of one row, in left-to-right order: the name, then each value cell. */
	private static List<Component> labels(Container row)
	{
		List<Component> found = new ArrayList<>();
		Component name = null;
		for (Component child : row.getComponents())
		{
			if (child instanceof JLabel)
			{
				name = child;
			}
		}
		if (name == null)
		{
			return found;
		}
		found.add(name);
		for (Component child : row.getComponents())
		{
			if (child instanceof Container)
			{
				for (Component cell : ((Container) child).getComponents())
				{
					if (cell instanceof JLabel)
					{
						found.add(cell);
					}
				}
			}
		}
		return found.size() > 1 ? found : new ArrayList<>();
	}

	/**
	 * Every piece of text the panel shows, for assertions about prose rather than cells.
	 *
	 * <p><b>Visible components only.</b> The tab keeps every section built and hides the ones
	 * with nothing to say, so walking the tree regardless of visibility reports text nobody can
	 * read — which made "the empty message is not shown" pass on a panel that was showing a full
	 * projection.
	 */
	private static String textOf(Container container)
	{
		StringBuilder text = new StringBuilder();
		for (Component child : container.getComponents())
		{
			if (!child.isVisible())
			{
				continue;
			}
			if (child instanceof JLabel)
			{
				text.append(((JLabel) child).getText()).append('\n');
			}
			else if (child instanceof JTextComponent)
			{
				text.append(((JTextComponent) child).getText()).append('\n');
			}
			else if (child instanceof AbstractButton)
			{
				text.append(((AbstractButton) child).getText()).append('\n');
			}
			if (child instanceof Container)
			{
				text.append(textOf((Container) child));
			}
		}
		return text.toString();
	}

	@SuppressWarnings("unchecked")
	private static <T> T construct(Class<T> type, Object... args) throws Exception
	{
		for (Constructor<?> candidate : type.getDeclaredConstructors())
		{
			if (candidate.getParameterCount() == args.length)
			{
				candidate.setAccessible(true);
				return (T) candidate.newInstance(args);
			}
		}
		throw new IllegalStateException("no constructor of arity " + args.length + " on " + type);
	}
}
