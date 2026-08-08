package com.dooglemaps.validate;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Covers the promise {@code harvests.csv} is built on: columns are addressed by name.
 *
 * <p>The file used to be positional, and the failure mode of positional is the worst kind —
 * inserting a column silently reinterprets every historic row, a level becomes a compost tier,
 * and nothing throws. These tests write one harvest under several different headers and check
 * every value still lands under its own name, which is the exact property a positional reader
 * would fail.
 *
 * <p>The header fallbacks matter for the same reason: {@link HarvestCsv#columnsOf} answering
 * wrongly for a missing or empty file would make the <i>next</i> write the thing that corrupts
 * the layout.
 */
public class HarvestCsvTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** One harvest, keyed by column name, as {@code valuesOf} would shape it. */
	private static Map<String, String> aHarvest()
	{
		Map<String, String> values = new LinkedHashMap<>();
		values.put(HarvestCsv.VERSION, "1");
		values.put(HarvestCsv.TIME, "1000");
		values.put(HarvestCsv.PATCH, "Falador");
		values.put(HarvestCsv.CROP, "Ranarr");
		values.put(HarvestCsv.LEVEL, "80");
		values.put(HarvestCsv.COMPOST, "ULTRACOMPOST");
		values.put(HarvestCsv.SECATEURS, "true");
		values.put(HarvestCsv.CAPE, "false");
		values.put(HarvestCsv.ATTAS, "false");
		values.put(HarvestCsv.LIVES, "6");
		values.put(HarvestCsv.PREDICTED, "9.00");
		values.put(HarvestCsv.ACTUAL, "11");
		values.put(HarvestCsv.PREDICTED_XP, "300.0");
		values.put(HarvestCsv.ACTUAL_XP, "340.0");
		values.put(HarvestCsv.COMPLETED, "true");
		return values;
	}

	@Test
	public void aRowWrittenUnderTheDefaultHeaderReadsBack()
	{
		String line = HarvestCsv.line(HarvestCsv.COLUMNS, aHarvest());
		HarvestRow row = HarvestCsv.parse(HarvestCsv.COLUMNS, line);

		assertNotNull("a row this class wrote must be one it can read", row);
		assertEquals(1000, row.getAt());
		assertEquals("Ranarr", row.getCrop());
		assertEquals("Falador", row.getPatch());
		assertEquals(80, row.getLevel());
		assertEquals("ULTRACOMPOST", row.getCompost());
		assertEquals(9.00, row.getPredicted(), 1e-9);
		assertEquals(11, row.getActual());
		assertEquals(340.0, row.getXp(), 1e-9);
		assertEquals(true, row.isCompleted());
	}

	/**
	 * The same harvest under a reversed header is the same harvest.
	 *
	 * <p>Reversal is the harshest reordering there is: every single column is in a different
	 * position, so any read that quietly falls back to position gets every field wrong at
	 * once — a level of 80 would come back as a completed flag, and none of it would throw.
	 */
	@Test
	public void aReorderedHeaderStillPutsEveryValueUnderItsName()
	{
		List<String> reversed = new ArrayList<>(HarvestCsv.COLUMNS);
		Collections.reverse(reversed);

		String line = HarvestCsv.line(reversed, aHarvest());
		HarvestRow row = HarvestCsv.parse(reversed, line);

		assertNotNull(row);
		assertEquals("the level is still the level, wherever its column sits",
			80, row.getLevel());
		assertEquals("Ranarr", row.getCrop());
		assertEquals(1000, row.getAt());
		assertEquals(11, row.getActual());
		assertEquals("ULTRACOMPOST", row.getCompost());
		assertEquals(true, row.isCompleted());
	}

	/**
	 * A file that never grew some columns still reads, and what it lacks defaults quietly.
	 *
	 * <p>This is the "older file needs no migration" half of the promise: a header written by
	 * a previous version describes fewer columns, and the reader must take exactly what the
	 * header names and invent nothing — absent numbers are zero, absent compost is NONE,
	 * absent completion is false. Values shifting into the gaps is the positional bug again.
	 */
	@Test
	public void aSubsetHeaderReadsWhatItNamesAndDefaultsTheRest()
	{
		List<String> subset = Arrays.asList(HarvestCsv.TIME, HarvestCsv.CROP, HarvestCsv.ACTUAL);

		String line = HarvestCsv.line(subset, aHarvest());
		assertEquals("only what the header names is written", "1000,Ranarr,11", line);

		HarvestRow row = HarvestCsv.parse(subset, line);
		assertNotNull(row);
		assertEquals("Ranarr", row.getCrop());
		assertEquals(11, row.getActual());
		assertEquals("a column the file never had defaults, it does not steal a neighbour",
			0, row.getLevel());
		assertEquals("NONE", row.getCompost());
		assertEquals(0.0, row.getXp(), 1e-9);
		assertEquals(false, row.isCompleted());
	}

	/**
	 * A column this version has never heard of is written empty, not skipped.
	 *
	 * <p>Skipping it would shear the field count away from the header and shift every later
	 * value one column left — which is how a file written by a newer version would be quietly
	 * ruined by an older one appending to it.
	 */
	@Test
	public void anUnknownColumnIsWrittenEmptySoTheFieldCountNeverDrifts()
	{
		List<String> future = Arrays.asList(HarvestCsv.TIME, "moon_phase", HarvestCsv.CROP);

		String line = HarvestCsv.line(future, aHarvest());
		assertEquals("1000,,Ranarr", line);

		HarvestRow row = HarvestCsv.parse(future, line);
		assertNotNull(row);
		assertEquals("the crop did not slide into the unknown column's place",
			"Ranarr", row.getCrop());
	}

	/** A row that does not match its header is skipped, not guessed at. */
	@Test
	public void aRowWithTheWrongShapeParsesToNullRatherThanMisreading()
	{
		assertNull("two cells against a three-column header is not answerable by name",
			HarvestCsv.parse(
				Arrays.asList(HarvestCsv.TIME, HarvestCsv.CROP, HarvestCsv.ACTUAL),
				"1000,Ranarr"));
	}

	/** A file that does not exist yet gets today's layout, because it is about to be made. */
	@Test
	public void aMissingFileGetsTheDefaultColumns()
	{
		File never = new File(folder.getRoot(), "never-written.csv");

		assertEquals(HarvestCsv.COLUMNS, HarvestCsv.columnsOf(never));
	}

	/**
	 * A file that exists keeps the layout its own header describes.
	 *
	 * <p>This is the line that makes old files immortal: the header they were created with is
	 * the authority, not whatever this version would write today.
	 */
	@Test
	public void anExistingFilesOwnHeaderIsTheAuthority() throws Exception
	{
		File existing = folder.newFile("harvests.csv");
		Files.write(existing.toPath(),
			Arrays.asList("completed,actual,crop,time", "true,11,Ranarr,1000"),
			StandardCharsets.UTF_8);

		assertEquals(Arrays.asList("completed", "actual", "crop", "time"),
			HarvestCsv.columnsOf(existing));
	}

	/** No header in hand is the same as no file: the default layout, never a crash. */
	@Test
	public void aNullOrEmptyHeaderGetsTheDefaultColumns()
	{
		assertEquals(HarvestCsv.COLUMNS, HarvestCsv.columnsOf((String) null));
		assertEquals(HarvestCsv.COLUMNS, HarvestCsv.columnsOf(""));
	}
}
