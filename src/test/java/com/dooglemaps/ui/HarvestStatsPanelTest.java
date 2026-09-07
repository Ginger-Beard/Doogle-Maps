package com.dooglemaps.ui;

import com.dooglemaps.data.Seed;
import com.dooglemaps.validate.DiseaseStatsStore;
import com.dooglemaps.validate.HarvestStatsStore;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import net.runelite.client.ui.laf.RuneLiteLAF;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.ui.StatsPanels.FARMING_LEVEL;
import static com.dooglemaps.ui.StatsPanels.HISTORY;
import static com.dooglemaps.ui.StatsPanels.NO_SEEDS;
import static com.dooglemaps.ui.StatsPanels.SCORED_HISTORY;
import static com.dooglemaps.ui.StatsPanels.SIDEBAR_WIDTH;
import static com.dooglemaps.ui.StatsPanels.bank;
import static com.dooglemaps.ui.StatsPanels.cropOrder;
import static com.dooglemaps.ui.StatsPanels.diseaseOf;
import static com.dooglemaps.ui.StatsPanels.emptyStore;
import static com.dooglemaps.ui.StatsPanels.fullBank;
import static com.dooglemaps.ui.StatsPanels.layoutStore;
import static com.dooglemaps.ui.StatsPanels.noRuns;
import static com.dooglemaps.ui.StatsPanels.pathBank;
import static com.dooglemaps.ui.StatsPanels.pricesOf;
import static com.dooglemaps.ui.StatsPanels.rowFor;
import static com.dooglemaps.ui.StatsPanels.rowsNamed;
import static com.dooglemaps.ui.StatsPanels.someRuns;
import static com.dooglemaps.ui.StatsPanels.storeOf;
import static com.dooglemaps.ui.StatsPanels.tableNamed;
import static com.dooglemaps.ui.StatsPanels.textOf;
import static com.dooglemaps.ui.StatsPanels.tooltipFor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers what the harvest history actually puts on screen.
 *
 * <p>{@code HarvestStatsStoreTest} already pins the arithmetic, so this is about the panel's
 * end of the bargain: that the distinction the store is careful to maintain — items from
 * abandoned patches count towards your totals but never towards an average — survives being
 * rendered, and that the tab fits the sidebar it has to live in.
 *
 * <p>There is now <b>one</b> crop table where there were three, so rows are looked up in
 * {@code CROP_TABLE} rather than once per section. Most of the tab's explanations live on
 * tooltips, which is why {@link StatsPanels#textOf} collects those too — a sentence that moved
 * onto the header it explains is still a sentence the panel shows.
 *
 * <h2>What is no longer here</h2>
 *
 * A numerical audit of a real account found that several of this tab's most confident figures
 * were measuring the plugin's own capture faults rather than the game, and they came off the
 * screen. The tests that pinned them went with them:
 *
 * <ul>
 *   <li><b>The luck percentile</b> ({@code luckIsCumulativeWithoutASpreadAndUnscoredWithoutOne},
 *       {@code aPercentileAppearsOnceTheSpreadHasBeenRecorded},
 *       {@code aShortHistoryIsNotScoredEvenWithASpread}) — four of the six crops showing a 99th
 *       percentile collapsed to the middle of the distribution once patches the plugin failed to
 *       see composted were scored at their real number of lives. The sample floor those tests
 *       defended survives as the {@code +/-} column's colour floor, covered by
 *       {@code TheCropTableCarriesASignedDifferenceTest}.</li>
 *   <li><b>The account-wide surplus headline</b> and the "N items came from patches left
 *       standing" line (also
 *       {@code luckIsCumulativeWithoutASpreadAndUnscoredWithoutOne}) — 1,412 of the 1,965
 *       "left standing" items were three zero-experience records, and the surplus was dominated
 *       by crops with no published yield formula at all.</li>
 *   <li><b>The compost comparison</b> ({@code compostIsComparedOnYourOwnNumbers},
 *       {@code oneTierThroughoutSaysSoRatherThanComparingWithNothing}) — it picked limpwurt, a
 *       crop the plugin's own model says compost cannot affect, and its "untreated" arm was
 *       really "not recorded".</li>
 *   <li><b>The Farming-level bands</b> — one band of nine hundred patches mixing thirty-four
 *       crops and four yield models cannot show a level curve.</li>
 * </ul>
 */
public class HarvestStatsPanelTest
{
	private HarvestStatsStore stats;

	@Before
	public void setUp() throws Exception
	{
		stats = storeOf(HISTORY);
	}

	// ---------------------------------------------------------------- harvests

	/**
	 * The merged row counts every item picked, and says so where the arithmetic looks wrong.
	 *
	 * <p>145 items over 17 patches predicted at 140.6 does not reconcile until you know four of
	 * those items came from a patch that was never finished. That asymmetry used to be spread
	 * over two tables in different halves of the tab; on one row it has to be explained where
	 * the reader is.
	 */
	@Test
	public void abandonedPatchesCountTowardsTheTotalButNotTheDifference()
	{
		List<String> row = rowFor(panel(), HarvestStatsPanel.CROP_TABLE, "Ranarr weed");

		// The patch count is what makes the other columns legible: without it, 145 items beside
		// a signed percentage looks like arithmetic that does not add up.
		assertEquals("14 + 3 patches picked clean", "17", row.get(1));
		// 128 finished plus 13 finished plus the 4 left standing.
		assertEquals("everything picked belongs in the total", "145", row.get(2));
		assertEquals("1820 + 390 + the partial's 52", "2.3k", row.get(3));
	}

	/** The abandoned items are called out where the gap they cause is visible. */
	@Test
	public void theTooltipExplainsTheItemsTheDifferenceIgnores()
	{
		String tooltip = tooltipFor(panel(), HarvestStatsPanel.CROP_TABLE, "Ranarr weed");
		assertNotNull(tooltip);
		assertTrue("the partial items need saying out loud, " + tooltip,
			tooltip.contains("4 more from patches left standing"));
	}

	/** And the column header carries the same asymmetry, for a reader who never hovers a row. */
	@Test
	public void theDifferenceColumnHeaderExplainsWhatItLeavesOut()
	{
		String text = textOf(panel());
		assertTrue("expected the got/+- split on the header, got " + text,
			text.contains("a half-picked patch is not a low yield"));
		assertTrue("and the sample floor under the colour, " + text,
			text.contains("Coloured from 20 picked patches on"));
	}

	@Test
	public void compostTiersAreSummedInTheRowAndSplitInTheTooltip()
	{
		HarvestStatsPanel panel = panel();

		// One line per crop, not one per crop and tier - otherwise ranarr appears twice.
		assertEquals(1,
			rowsNamed(tableNamed(panel, HarvestStatsPanel.CROP_TABLE), "Ranarr weed").size());

		String tooltip = tooltipFor(panel, HarvestStatsPanel.CROP_TABLE, "Ranarr weed");
		assertNotNull("the tier split is the reason to hover", tooltip);
		assertTrue("ultracompost's own average should be there, " + tooltip,
			tooltip.contains("Ultracompost"));
		assertTrue("and so should the untreated one, " + tooltip, tooltip.contains("Untreated"));
	}

	/**
	 * The rows lead on experience, and count every item including the partials.
	 *
	 * <p>Sorted by experience because that is what the reader is scanning for. Ranarr's 2262
	 * beats watermelon's 1160 while watermelon is not far behind on items, so a sort left on an
	 * items ordering would still put ranarr first — the watermelon row is what actually pins it.
	 */
	@Test
	public void theCropTableLeadsOnExperienceAndCountsEverythingPicked()
	{
		HarvestStatsPanel panel = panel();

		assertEquals("the biggest experience earner comes first", "Ranarr weed",
			cropOrder(panel, HarvestStatsPanel.CROP_TABLE).get(0));
		assertEquals("1160 experience", "1.2k",
			rowFor(panel, HarvestStatsPanel.CROP_TABLE, "Watermelon").get(3));
	}

	/**
	 * A count keeps every digit and gains a separator once it reaches four of them - unlike the
	 * xp column beside it, which would abbreviate the same magnitude to "9.7k".
	 */
	@Test
	public void aFourDigitItemCountGetsAThousandsSeparator() throws Exception
	{
		String history =
			"{\"Ranarr weed|ULTRACOMPOST\":{\"crop\":\"Ranarr weed\",\"compost\":\"ULTRACOMPOST\","
				+ "\"harvests\":140,\"items\":9707,\"predicted\":9600.0,\"xp\":182000.0,"
				+ "\"best\":90,\"worst\":40}}";

		List<String> row = rowFor(StatsPanels.panel(storeOf(history), NO_SEEDS),
			HarvestStatsPanel.CROP_TABLE, "Ranarr weed");

		assertEquals("9,707", row.get(2));
	}

	/**
	 * The Harvests headline is the experience total, with the patch count as its caption.
	 *
	 * <p>The tab used to open on a paragraph. The one number worth landing on is what the whole
	 * history has actually paid, and it now sits in bold beside the card's name where it is
	 * readable with the card shut.
	 */
	@Test
	public void harvestsLeadsWithTheExperienceTotalOverThePatchCount()
	{
		// 1820 + 390 + 52 + 1160, over 14 + 3 + 8 patches picked clean.
		String text = textOf(panel());
		assertTrue("expected the experience total as the headline, got " + text,
			text.contains("3.4k"));
		assertTrue("and the patch count under it, " + text, text.contains("25 patches"));
	}

	/**
	 * The card says when it started counting, because otherwise it claims to be something it is
	 * not.
	 *
	 * <p>These totals begin the day the plugin was installed, not the day the account did. A
	 * card headed "Harvests" with no start date reads as the second.
	 */
	@Test
	public void harvestsSaysWhenItStartedCounting() throws Exception
	{
		long august = ZonedDateTime.of(2026, 8, 4, 12, 0, 0, 0, ZoneId.systemDefault())
			.toEpochSecond();
		String dated = HISTORY.replace("\"harvests\":3,",
			"\"firstHarvest\":" + august + ",\"harvests\":3,");

		String text = textOf(StatsPanels.panel(storeOf(dated), NO_SEEDS));
		assertTrue("expected a start date, got " + text, text.contains("since 4 August"));
	}

	// -------------------------------------------------------------------- bank

	/**
	 * The bank projection appears on an account that has harvested nothing at all.
	 *
	 * <p>The reason Bank is a card of its own rather than part of the history block. A player who
	 * has just installed the plugin and holds seeds has one useful question, and the tab used to
	 * answer it with "nothing recorded yet".
	 */
	@Test
	public void bankShowsOnAFreshInstallWithSeedsInTheBank()
	{
		HarvestStatsPanel panel = StatsPanels.panel(emptyStore(), bank(Seed.RANARR, 200));

		String text = textOf(panel);
		assertTrue("a fresh install with seeds is not empty, " + text,
			!text.contains("Nothing here yet"));
		// "Ranarr", not "Ranarr weed": the rows are named from Produce, where the crop is the
		// herb rather than the item picked. The history fixtures carry their own strings.
		assertEquals("200", rowFor(panel, HarvestStatsPanel.EXPECTED_TABLE, "Ranarr").get(1));
	}

	/**
	 * And it is the card that opens, which is the one state the default order gets wrong.
	 *
	 * <p>Harvests opens by default because it is what the tab is for — but on a fresh install
	 * Harvests is the card with nothing in it, so obeying the rule would show a new player four
	 * shut headers and nothing else.
	 */
	@Test
	public void aFreshInstallOpensOnTheOneCardThatHasSomethingToSay()
	{
		HarvestStatsPanel panel = StatsPanels.collapsed(layoutStore(), emptyStore(),
			bank(Seed.RANARR, 200), FARMING_LEVEL, noRuns(),
			Mockito.mock(DiseaseStatsStore.class), pricesOf(0));

		String caret = StatsPanels.caret(panel, "Bank").getText();
		assertTrue("the Bank caret should be open, got " + caret, caret.startsWith("▾"));
	}

	/** With neither history nor seeds there is genuinely nothing, and it says so. */
	@Test
	public void anEmptyHistoryAndAnEmptyBankSayNothingYet()
	{
		String text = textOf(StatsPanels.panel(emptyStore(), NO_SEEDS));
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
	 *
	 * <p>Both sentences are on the card's header now rather than under it: they are the
	 * projection's working, and the projection's answer is the headline they used to bury.
	 */
	@Test
	public void bankShowsTheLevellingUpOnlyWhereItChangesTheAnswer()
	{
		String climbing = textOf(StatsPanels.panel(emptyStore(), bank(Seed.RANARR, 200), 32));
		assertTrue("expected the plant-out working, " + climbing,
			climbing.contains("Planting it all out"));
		assertTrue("expected the flat figure beside it, " + climbing,
			climbing.contains("Staying at 32 and planting only what you can plant now"));

		String settled = textOf(StatsPanels.panel(emptyStore(), bank(Seed.RANARR, 200), 75));
		assertTrue("the card is still drawn, " + settled,
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
	public void aCropWithNoAvailablePatchesIsNotProjected()
	{
		String text = textOf(StatsPanels.panel(emptyStore(), bank(Seed.BARLEY, 500)));
		assertTrue("hops patches are all switched off in this fixture, " + text,
			text.contains("Nothing here yet"));
	}

	/**
	 * From the bottom, the card shows the route rather than just the destination.
	 *
	 * <p>A level 1 account holding herb seeds cannot plant any of them, and the useful answer is
	 * not "nothing" — it is <i>potatoes take you to 9, guam takes you to 32, and your ranarr are
	 * waiting there</i>. The unlock list is that route, and it is the one block of wrapped prose
	 * that survived the move to tooltips: it is a list, not a paragraph.
	 */
	@Test
	public void thePathNamesWhatUnlocksOnTheWay()
	{
		Map<Seed, Integer> bank = new LinkedHashMap<>();
		bank.put(Seed.POTATO, 9000);
		bank.put(Seed.GUAM, 500);
		bank.put(Seed.RANARR, 500);

		String text = textOf(StatsPanels.panel(emptyStore(), bank, 1));

		assertTrue("expected the route, got " + text, text.contains("Unlocks on the way:"));
		assertTrue("guam is on it, " + text, text.contains("Guam"));
		assertTrue("and ranarr, with its stock named, " + text,
			text.contains("Ranarr (500 banked)"));
	}

	/** Where 99 falls in the bank is the question a big stack is actually asking. */
	@Test
	public void thePathSaysWhereNinetyNineArrives()
	{
		String text = textOf(StatsPanels.panel(emptyStore(), bank(Seed.RANARR, 200_000), 80));

		assertTrue("expected the 99 split, got " + text, text.contains("99 arrives on"));
		assertTrue("and what the surplus is worth, " + text, text.contains("more beyond it"));
	}

	/** The seeds-to-a-patch conversion is explained where it would otherwise look wrong. */
	@Test
	public void theTooltipExplainsSeedsThatShareAPatch()
	{
		String tooltip = tooltipFor(StatsPanels.panel(emptyStore(), bank(Seed.POTATO, 100)),
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
	 * wrong for a lifetime earnings claim. Without the wording it reads as the second. It is on
	 * the line's tooltip now: the number is the answer and the caveat is the footnote.
	 */
	@Test
	public void lifetimeValueIsPricedAtTodayAndSaysSo() throws Exception
	{
		String text = textOf(StatsPanels.panel(storeOf(HISTORY), NO_SEEDS, FARMING_LEVEL, noRuns(),
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
	public void expectedValueIsNetOfSeedsAndCompost()
	{
		String text = textOf(StatsPanels.panel(emptyStore(), bank(Seed.RANARR, 200), FARMING_LEVEL,
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
		String text = textOf(StatsPanels.panel(storeOf(HISTORY), NO_SEEDS, FARMING_LEVEL, noRuns(),
			Mockito.mock(DiseaseStatsStore.class), pricesOf(0)));

		assertTrue("nothing priced, so nothing claimed: " + text, !text.contains("Worth about"));
	}

	// ------------------------------------------------------------------ checks

	/**
	 * Disease is reported measured against predicted, which nothing else on the tab could do.
	 *
	 * <p>A dead patch produces no harvest, so the harvest log never sees one — this is the only
	 * figure here that had to be captured from the patch state instead, and after the crop
	 * table, the level bands and the accuracy verdict came off the Checks card it is what is
	 * left.
	 */
	@Test
	public void diseaseIsReportedAgainstItsPrediction() throws Exception
	{
		String text = textOf(StatsPanels.panel(storeOf(HISTORY), NO_SEEDS, FARMING_LEVEL, noRuns(),
			diseaseOf(200, 18, 6, 186.0)));

		assertTrue("expected an observed disease line, got " + text,
			text.contains("18 of 200 growth cycles caught something"));
		assertTrue("and the prediction beside it, " + text, text.contains("predicted 14"));
		assertTrue("and the deaths, " + text, text.contains("6 died"));
	}

	/**
	 * Below the floor it says what it is waiting for rather than nothing at all.
	 *
	 * <p>Disease is a few percent a cycle, so a rate over twenty cycles is one patch either way
	 * and reads as a finding. That negative is the card's most important behaviour — but a card
	 * that renders empty looks broken, so the floor produces a caption rather than a
	 * disappearance.
	 */
	@Test
	public void aHandfulOfCyclesSaysNothingAboutDisease() throws Exception
	{
		String text = textOf(StatsPanels.panel(storeOf(HISTORY), NO_SEEDS, FARMING_LEVEL, noRuns(),
			diseaseOf(12, 1, 0, 11.3)));

		assertTrue("twelve cycles is not a rate, " + text, !text.contains("caught something"));
		assertTrue("but the card should say why, " + text,
			text.contains("Needs 50 growth cycles, 12 so far"));
	}

	// -------------------------------------------------------------------- runs

	/**
	 * Sittings are shown as last, best and average, and the rates are named for what they are.
	 *
	 * <p>The rate labels are the part worth pinning. An hourly figure measured over the run
	 * alone describes nothing sustainable, so it has to say so; the per-day figure is the honest
	 * throughput for a skill gated by a growth timer and has to not be called hourly. Both
	 * definitions moved to the tooltip, which is where a definition belongs — the rate itself is
	 * four characters and used to be the fourth word of a twenty-word sentence.
	 */
	@Test
	public void runsShowLastBestAndAverageWithHonestRates() throws Exception
	{
		HarvestStatsPanel panel =
			StatsPanels.panel(storeOf(HISTORY), NO_SEEDS, FARMING_LEVEL, someRuns());

		assertEquals("6", rowFor(panel, HarvestStatsPanel.RUNS_TABLE, "last").get(1));
		assertEquals("14", rowFor(panel, HarvestStatsPanel.RUNS_TABLE, "best").get(1));
		assertEquals("121", rowFor(panel, HarvestStatsPanel.RUNS_TABLE, "best").get(2));

		String text = textOf(panel);
		assertTrue("expected a per-day rate, got " + text, text.contains("xp a day"));
		assertTrue("the hourly one must say what it measures, " + text,
			text.contains("while you are actually farming"));
	}

	/**
	 * A history that exists only in the log still shows, rather than reading as an empty tab.
	 *
	 * <p>The exact shape of a real report. <i>Clear harvest history</i> empties the rolled-up
	 * store and the in-memory summaries but does not touch {@code harvests.csv}, so the next
	 * restart reads the runs straight back — and the empty-state test used to ask only about
	 * the store and the bank.
	 */
	@Test
	public void runsAloneAreEnoughToNotBeAnEmptyTab()
	{
		HarvestStatsPanel panel =
			StatsPanels.panel(emptyStore(), NO_SEEDS, FARMING_LEVEL, someRuns());

		String text = textOf(panel);
		assertTrue("runs on their own are not nothing, " + text,
			!text.contains("Nothing here yet"));
		assertEquals("and the card is actually reachable", "6",
			rowFor(panel, HarvestStatsPanel.RUNS_TABLE, "last").get(1));
	}

	/** With no runs reconstructed the card stays away rather than showing empty rows. */
	@Test
	public void noRunsMeansNoRunsSection() throws Exception
	{
		String text = textOf(StatsPanels.panel(storeOf(HISTORY), NO_SEEDS));
		assertTrue("nothing to cluster yet, " + text, !text.contains("xp a day"));
	}

	/**
	 * Runs to the next level is the Runs headline, which is the highest-value move on the tab.
	 *
	 * <p>The source already knew it. It was the third sentence of the third paragraph of the
	 * third section, below the fold on every screen; it is now readable with the card shut.
	 */
	@Test
	public void theNextLevelIsCountedInRuns() throws Exception
	{
		String text = textOf(StatsPanels.panel(storeOf(HISTORY), NO_SEEDS, 40, someRuns()));
		assertTrue("expected a runs-to-level headline, got " + text, text.contains("runs to 41"));
	}

	// ------------------------------------------------------------------ layout

	/**
	 * Also writes the Stats tab to {@code build/harvest-stats*.png}.
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
		// The scored history too, because the +/- column is only filled in and coloured there,
		// and a column that is always blank in the picture cannot be checked from the picture.
		draw(StatsPanels.panel(storeOf(SCORED_HISTORY), NO_SEEDS), "harvest-stats-scored.png");
		// Everything on at once, which is the only check that the four cards still fit together.
		draw(StatsPanels.panel(storeOf(HISTORY), fullBank(), 32, someRuns(),
			diseaseOf(214, 19, 7, 198.0), pricesOf(6_000)), "harvest-stats-full.png");
		// And the path from the bottom, which is the only picture the unlock list appears in.
		draw(StatsPanels.panel(emptyStore(), pathBank(), 1), "harvest-stats-path.png");
		// The run clustering, which only appears with a log behind it.
		draw(StatsPanels.panel(storeOf(HISTORY), NO_SEEDS, FARMING_LEVEL, someRuns()),
			"harvest-stats-runs.png");
		// And the state the tab actually opens in - four headers, one of them expanded. That is
		// what the redesign is about, so it is the picture worth keeping.
		draw(StatsPanels.collapsed(layoutStore(), storeOf(HISTORY), fullBank(), 32, someRuns(),
			diseaseOf(214, 19, 7, 198.0), pricesOf(6_000)), "harvest-stats-collapsed.png");
	}

	/** Lays the tab out at the sidebar's width, checks it fits, and leaves a picture behind. */
	private static void draw(HarvestStatsPanel panel, String name) throws Exception
	{
		panel.setSize(SIDEBAR_WIDTH, panel.getPreferredSize().height);
		StatsPanels.layout(panel);

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

	private HarvestStatsPanel panel()
	{
		return StatsPanels.panel(stats, NO_SEEDS);
	}
}
