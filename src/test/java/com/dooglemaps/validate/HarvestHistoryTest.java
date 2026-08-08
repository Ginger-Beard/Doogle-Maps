package com.dooglemaps.validate;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers reading {@code harvests.csv} back.
 *
 * <p>Two things carry the risk. The file is <b>addressed by column name</b>, and the reason is
 * that it was not always — a positional reader silently reinterprets every historic row the
 * first time a column moves, and nothing throws. And runs are <b>inferred from the gaps</b>
 * rather than recorded, so the clustering is the only thing standing between "you did 40 runs"
 * and a number made up out of timestamps.
 */
public class HarvestHistoryTest
{
	private static final long HOUR = 3600;

	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/**
	 * A file whose columns are in a different order still reads correctly.
	 *
	 * <p>The whole point of the header work. Written positionally, these two files disagree
	 * about everything; read by name they are the same three harvests.
	 */
	@Test
	public void columnsAreReadByNameNotByPosition() throws Exception
	{
		HarvestHistory ordered = historyOf(
			"time,crop,level,predicted,actual,actual_xp,completed",
			"1000,Ranarr,80,9.00,11,340.0,true");

		// The same row with the columns shuffled and two extras interleaved.
		HarvestHistory shuffled = historyOf(
			"completed,actual,patch,time,actual_xp,crop,predicted,level,compost",
			"true,11,Falador,1000,340.0,Ranarr,9.00,80,ULTRACOMPOST");

		assertEquals(1, ordered.getRunCount());
		assertEquals(1, shuffled.getRunCount());
		assertEquals("the same harvest either way",
			ordered.getLastRun().getItems(), shuffled.getLastRun().getItems());
		assertEquals(11, shuffled.getLastRun().getItems());
		assertEquals(340.0, shuffled.getLastRun().getXp(), 1e-9);
	}

	/**
	 * A gap of more than half an hour starts a new run; anything less does not.
	 *
	 * <p>Built either side of the boundary on purpose. Five patches minutes apart are one
	 * sitting however long the sitting is, and the same five with an hour and a half in the
	 * middle are two.
	 */
	@Test
	public void runsAreFoundInTheGapsBetweenHarvests() throws Exception
	{
		List<String> rows = new ArrayList<>();
		// One sitting: five patches, two minutes apart.
		for (int patch = 0; patch < 5; patch++)
		{
			rows.add(row(10_000 + patch * 120, "Ranarr", 80, 9, 10, 300));
		}
		// Ninety minutes later, the crops have regrown and you go again.
		for (int patch = 0; patch < 3; patch++)
		{
			rows.add(row(10_000 + 90 * 60 + patch * 120, "Ranarr", 80, 9, 8, 240));
		}

		HarvestHistory history = historyOf(header(), rows.toArray(new String[0]));

		assertEquals("two sittings, not eight and not one", 2, history.getRunCount());
		assertEquals("the last one is the smaller", 3, history.getLastRun().getPatches());
		assertEquals(24, history.getLastRun().getItems());
		assertEquals("and the best is the bigger", 5, history.getBestRun().getPatches());
		assertEquals(1500.0, history.getBestRun().getXp(), 1e-9);
	}

	/** A run's length is first patch to last, which is zero for a run of one. */
	@Test
	public void aRunIsTimedFromItsFirstPatchToItsLast() throws Exception
	{
		HarvestHistory history = historyOf(header(),
			row(10_000, "Ranarr", 80, 9, 10, 300),
			row(10_600, "Ranarr", 80, 9, 10, 300));

		assertEquals(600, history.getLastRun().getDuration());
		// 600 xp over ten minutes.
		assertEquals(3600.0, history.getActiveXpPerHour(), 1.0);
	}

	/**
	 * Experience per day needs a day to have passed.
	 *
	 * <p>Otherwise it is an afternoon's farming multiplied up, which is the kind of number that
	 * reads as a measurement and is an extrapolation.
	 */
	@Test
	public void experiencePerDayIsNotExtrapolatedFromAnAfternoon() throws Exception
	{
		HarvestHistory sameDay = historyOf(header(),
			row(10_000, "Ranarr", 80, 9, 10, 300),
			row(10_000 + 4 * HOUR, "Ranarr", 80, 9, 10, 300));
		assertEquals("four hours is not a day", 0.0, sameDay.getXpPerDay(), 1e-9);

		HarvestHistory overAWeek = historyOf(header(),
			row(10_000, "Ranarr", 80, 9, 10, 700),
			row(10_000 + 7 * 24 * HOUR, "Ranarr", 80, 9, 10, 700));
		assertEquals("1400 over seven days", 200.0, overAWeek.getXpPerDay(), 1.0);
	}

	/** A patch left standing is a real run but not a yield observation. */
	@Test
	public void patchesLeftStandingCountTowardsARunAndNotTheHistogram() throws Exception
	{
		List<String> rows = new ArrayList<>();
		for (int patch = 0; patch < 20; patch++)
		{
			rows.add(row(10_000 + patch * 60, "Ranarr", 80, 9, 9, 270));
		}
		rows.add("10000000,Ranarr,80,9.00,2,60.0,false");

		HarvestHistory history = historyOf(header(), rows.toArray(new String[0]));

		HarvestHistory.Histogram spread = history.getHistogram("Ranarr");
		assertNotNull(spread);
		assertEquals("only the finished patches are in the distribution", 20, spread.getPatches());
	}

	/** The histogram buckets by how far the patch landed from its prediction. */
	@Test
	public void theHistogramBucketsBySurplus() throws Exception
	{
		HarvestHistory history = historyOf(header(),
			// Dead on prediction, twice.
			row(1000, "Ranarr", 80, 9, 9, 270),
			row(1060, "Ranarr", 80, 9, 9, 270),
			// Two over.
			row(1120, "Ranarr", 80, 9, 11, 330),
			// Far under, which lands in the open-ended bottom bucket.
			row(1180, "Ranarr", 80, 9, 3, 90));

		HarvestHistory.Histogram spread = history.getHistogram("Ranarr");
		assertNotNull(spread);
		assertEquals(4, spread.getPatches());

		int middle = HarvestHistory.HISTOGRAM_BUCKETS / 2;
		assertEquals("two landed exactly on prediction", 2, spread.getBuckets()[middle]);
		assertEquals("one landed two over", 1, spread.getBuckets()[middle + 2]);
		assertEquals("six under falls in the bottom bucket", 1, spread.getBuckets()[0]);
	}

	/** Yield is banded by ten levels, so the curve flattening is visible. */
	@Test
	public void yieldIsBandedByTenLevels() throws Exception
	{
		HarvestHistory history = historyOf(header(),
			row(1000, "Ranarr", 34, 7, 7, 210),
			row(1060, "Ranarr", 38, 7, 7, 210),
			row(1120, "Ranarr", 85, 9, 10, 300));

		List<HarvestHistory.LevelBand> bands = history.getLevelBands();
		assertEquals(2, bands.size());
		assertEquals("lowest band first", 30, bands.get(0).getFrom());
		assertEquals(2, bands.get(0).getPatches());
		assertEquals(7.0, bands.get(0).getAverage(), 1e-9);
		assertEquals(80, bands.get(1).getFrom());
		assertEquals(10.0, bands.get(1).getAverage(), 1e-9);
	}

	/** An unparseable line is skipped rather than taking the file down with it. */
	@Test
	public void aCorruptLineIsSkipped() throws Exception
	{
		HarvestHistory history = historyOf(header(),
			row(1000, "Ranarr", 80, 9, 10, 300),
			"this is not a row",
			"",
			row(1060, "Ranarr", 80, 9, 10, 300));

		assertEquals(1, history.getRunCount());
		assertEquals("both good rows survived", 2, history.getLastRun().getPatches());
	}

	/** A file that has never been written reads as empty, not as a failure. */
	@Test
	public void aMissingFileIsSimplyEmpty() throws Exception
	{
		HarvestHistory history = new HarvestHistory();
		history.read(new File(folder.getRoot(), "nothing-here.csv"));

		assertEquals(0, history.getRunCount());
		assertNull(history.getLastRun());
		assertNull(history.getHistogram("Ranarr"));
	}

	/**
	 * The instant format the file carried for its whole first life still reads.
	 *
	 * <p>Time was written as an ISO instant before it was written as an epoch second. Existing
	 * logs are the entire reason for the header work, so failing to read their timestamps would
	 * defeat it.
	 */
	@Test
	public void theOlderTimestampFormatStillReads() throws Exception
	{
		HarvestHistory history = historyOf(header(),
			"2026-08-04T12:00:00Z,Ranarr,80,9.00,10,300.0,true",
			"2026-08-04T12:05:00Z,Ranarr,80,9.00,10,300.0,true");

		assertEquals("one sitting five minutes long", 1, history.getRunCount());
		assertEquals(300, history.getLastRun().getDuration());
	}

	// ------------------------------------------------------------------ helpers

	private static String header()
	{
		return "time,crop,level,predicted,actual,actual_xp,completed";
	}

	private static String row(long at, String crop, int level, double predicted, int actual,
		double xp)
	{
		return String.format("%d,%s,%d,%.2f,%d,%.1f,true", at, crop, level, predicted, actual, xp);
	}

	private HarvestHistory historyOf(String header, String... rows) throws Exception
	{
		File file = folder.newFile("harvests-" + System.nanoTime() + ".csv");
		List<String> lines = new ArrayList<>();
		lines.add(header);
		for (String row : rows)
		{
			lines.add(row);
		}
		Files.write(file.toPath(), lines, StandardCharsets.UTF_8);

		HarvestHistory history = new HarvestHistory();
		history.read(file);
		return history;
	}
}
