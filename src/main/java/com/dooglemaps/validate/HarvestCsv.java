package com.dooglemaps.validate;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * The shape of {@code harvests.csv}, shared by the thing that writes it and the thing that
 * reads it back.
 *
 * <h2>Columns are addressed by name, never by position</h2>
 *
 * The file used to be written and read positionally, which meant <b>inserting a column silently
 * reinterpreted every historic row</b> — a level becoming a compost tier, a prediction becoming a
 * yield. Nothing would have thrown; the numbers would simply have been wrong, and wrong in a way
 * that looks like a modelling error rather than a parsing one.
 *
 * <p>So a file's own header is the authority on its layout. {@link #columnsOf} reads it back and
 * rows are emitted in <i>that</i> order, which means a file written by an older version keeps
 * working untouched and needs no migration: the header it was created with still describes it.
 * New files get {@link #COLUMNS}, and unknown columns are written empty rather than skipped, so
 * the field count never drifts from the header.
 *
 * <p>The {@code version} column is belt to that braces. Name-addressing already survives columns
 * being added or moved; the version is for the change it cannot survive — a column keeping its
 * name and changing its meaning.
 */
@Slf4j
final class HarvestCsv
{
	/** Bumped when a column's <i>meaning</i> changes. Adding or moving one does not need it. */
	static final int FORMAT_VERSION = 1;

	static final String VERSION = "version";
	static final String TIME = "time";
	static final String PATCH = "patch";
	static final String CROP = "crop";
	static final String LEVEL = "level";
	static final String COMPOST = "compost";
	static final String SECATEURS = "secateurs";
	static final String CAPE = "cape";
	static final String ATTAS = "attas";
	static final String LIVES = "lives";
	static final String PREDICTED = "predicted";
	static final String ACTUAL = "actual";
	static final String PREDICTED_XP = "predicted_xp";
	static final String ACTUAL_XP = "actual_xp";
	static final String COMPLETED = "completed";

	/** What a file created today is given. Existing files keep whatever they were made with. */
	static final List<String> COLUMNS = Collections.unmodifiableList(Arrays.asList(
		VERSION, TIME, PATCH, CROP, LEVEL, COMPOST, SECATEURS, CAPE, ATTAS, LIVES,
		PREDICTED, ACTUAL, PREDICTED_XP, ACTUAL_XP, COMPLETED));

	private HarvestCsv()
	{
	}

	/**
	 * The columns a file actually has, or {@link #COLUMNS} where it has none yet.
	 *
	 * <p>An unreadable or empty file is treated as a new one. Failing to parse a validation log
	 * is not a reason to stop recording harvests.
	 */
	static List<String> columnsOf(File file)
	{
		if (!file.exists())
		{
			return COLUMNS;
		}
		try (java.io.BufferedReader reader = Files.newBufferedReader(file.toPath(),
			StandardCharsets.UTF_8))
		{
			return columnsOf(reader.readLine());
		}
		catch (IOException e)
		{
			log.warn("Could not read the header of {}, treating it as a new file", file, e);
			return COLUMNS;
		}
	}

	/** The same answer from a header already in hand, for a caller that read the file itself. */
	static List<String> columnsOf(String header)
	{
		return header == null || header.isEmpty() ? COLUMNS : split(header);
	}

	/** One harvest as a column-name to value map, from which any layout can be written. */
	static Map<String, String> valuesOf(HarvestRecord record, long at)
	{
		Map<String, String> values = new LinkedHashMap<>();
		values.put(VERSION, String.valueOf(FORMAT_VERSION));
		values.put(TIME, String.valueOf(at));
		values.put(PATCH, clean(record.getPatch().getDisplayName()));
		values.put(CROP, clean(record.getProduce().getName()));
		values.put(LEVEL, String.valueOf(record.getFarmingLevel()));
		values.put(COMPOST, record.getCompost().name());
		values.put(SECATEURS, String.valueOf(record.getBonuses().isMagicSecateurs()));
		values.put(CAPE, String.valueOf(record.getBonuses().isFarmingCape()));
		values.put(ATTAS, String.valueOf(record.getBonuses().isAttas()));
		values.put(LIVES, String.valueOf(record.getLives()));
		values.put(PREDICTED, String.format("%.2f", record.getPredictedYield()));
		values.put(ACTUAL, String.valueOf(record.getItemsHarvested()));
		values.put(PREDICTED_XP, String.format("%.1f", record.getPredictedXp()));
		values.put(ACTUAL_XP, String.format("%.1f", record.getXpGained()));
		values.put(COMPLETED, String.valueOf(record.isCompleted()));
		return values;
	}

	/** Lays values out in one file's column order, blanking anything it does not carry. */
	static String line(List<String> columns, Map<String, String> values)
	{
		StringBuilder text = new StringBuilder();
		for (int column = 0; column < columns.size(); column++)
		{
			if (column > 0)
			{
				text.append(',');
			}
			text.append(values.getOrDefault(columns.get(column), ""));
		}
		return text.toString();
	}

	/**
	 * One data line, read against the header it was written under.
	 *
	 * @return null where the line cannot be made sense of, which is skipped rather than fatal
	 */
	@Nullable
	static HarvestRow parse(List<String> columns, String line)
	{
		List<String> cells = split(line);
		if (cells.size() != columns.size())
		{
			return null;
		}

		Map<String, String> values = new LinkedHashMap<>();
		for (int column = 0; column < columns.size(); column++)
		{
			values.put(columns.get(column), cells.get(column));
		}

		long at = time(values.get(TIME));
		String crop = values.get(CROP);
		if (at <= 0 || crop == null || crop.isEmpty())
		{
			return null;
		}

		return new HarvestRow(at, crop, values.getOrDefault(PATCH, ""),
			(int) number(values.get(LEVEL)), values.getOrDefault(COMPOST, "NONE"),
			number(values.get(PREDICTED)), (int) number(values.get(ACTUAL)),
			number(values.get(ACTUAL_XP)), Boolean.parseBoolean(values.get(COMPLETED)));
	}

	/**
	 * Epoch seconds from either format the file has carried.
	 *
	 * <p>It was written as an ISO instant for its whole life before this and as a plain epoch
	 * second after, and both have to keep reading — the whole point of the header work is that
	 * an existing file is not thrown away.
	 */
	private static long time(@Nullable String value)
	{
		if (value == null || value.isEmpty())
		{
			return 0;
		}
		try
		{
			return Long.parseLong(value.trim());
		}
		catch (NumberFormatException notEpoch)
		{
			try
			{
				return java.time.Instant.parse(value.trim()).getEpochSecond();
			}
			catch (java.time.format.DateTimeParseException notInstant)
			{
				return 0;
			}
		}
	}

	private static double number(@Nullable String value)
	{
		if (value == null || value.isEmpty())
		{
			return 0;
		}
		try
		{
			return Double.parseDouble(value.trim());
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	/** Plain comma splitting, which is safe because every value written strips its commas. */
	private static List<String> split(String line)
	{
		List<String> cells = new ArrayList<>();
		int start = 0;
		for (int at = 0; at <= line.length(); at++)
		{
			if (at == line.length() || line.charAt(at) == ',')
			{
				cells.add(line.substring(start, at).trim());
				start = at + 1;
			}
		}
		return cells;
	}

	private static String clean(String value)
	{
		return value.replace(',', ' ');
	}
}
