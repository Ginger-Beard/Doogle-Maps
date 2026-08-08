package com.dooglemaps.validate;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/**
 * Everything {@link CropHarvestStats} cannot answer, read back from {@code harvests.csv}.
 *
 * <p>The rolled-up store gets further than it looks like it should: anything that is a
 * <b>sum</b> over patches survives a roll-up, and that turned out to include the luck percentile,
 * because variances add just as means do. What no running total can reconstruct is <i>shape</i>
 * or <i>sequence</i> — which is exactly what the three things here are:
 *
 * <ul>
 *   <li><b>Runs</b>, found by clustering timestamps. A farm run is a dozen patches in a quarter
 *       of an hour and then nothing for an hour and a half, which is unmistakable in a log of
 *       when each patch was picked.</li>
 *   <li><b>The luck histogram</b>, as opposed to the percentile — not "you are 18 up" but where
 *       your patches actually landed.</li>
 *   <li><b>Yield against Farming level</b>, which needs each row's level rather than the
 *       aggregate's.</li>
 * </ul>
 *
 * <h2>Read once, on load. Never on a panel refresh</h2>
 *
 * That is the mistake this is shaped to avoid, and it is a caller mistake rather than a format
 * one. The file is parsed a single time at start-up; the rows are folded into the summaries
 * below and then <b>dropped</b>, so the memory held is a few hundred bytes per crop rather than
 * a few megabytes per year. Harvests recorded while playing are folded into the same summaries
 * as they happen, so nothing needs re-reading to stay current.
 *
 * <p>A real database was considered and is not worth it here: SQLite means a native dependency
 * in a plugin that has none, for a dataset that fits in memory — and the plugin is read-only and
 * sends nothing anywhere, so there is never a bigger corpus to scale to.
 */
@Slf4j
@Singleton
public class HarvestHistory
{
	/**
	 * Silence that ends a run, in seconds.
	 *
	 * <p>Half an hour, which sits in a wide gap in the real distribution: patches within one
	 * sitting are seconds or a few minutes apart, and the wait for the next crop is at least
	 * forty minutes for anything worth planting. Anywhere between about ten minutes and forty
	 * would cluster identically, so the exact figure is not load-bearing.
	 */
	private static final long RUN_GAP = 30 * 60;

	/**
	 * Rows kept before the oldest are dropped.
	 *
	 * <p>Roughly eighteen months of heavy farming. The file is append-only and otherwise
	 * unbounded, which is fine for size and not fine forever; losing the oldest rows costs
	 * little because {@link HarvestStatsStore} holds the lifetime totals independently and does
	 * not read this file at all.
	 */
	private static final int MAX_ROWS = 50_000;

	/** Buckets either side of the prediction, so seven in all with the ends open. */
	private static final int HISTOGRAM_REACH = 3;
	public static final int HISTOGRAM_BUCKETS = HISTOGRAM_REACH * 2 + 1;

	/** Where a crop's patches actually landed against what was predicted for them. */
	@Value
	public static class Histogram
	{
		String crop;
		/** Counts from "three or more under" to "three or more over", in item steps. */
		int[] buckets;
		int patches;

		public int getMost()
		{
			int most = 0;
			for (int count : buckets)
			{
				most = Math.max(most, count);
			}
			return most;
		}
	}

	/** How a band of ten Farming levels actually performed. */
	@Value
	public static class LevelBand
	{
		/** The bottom of the band: 30 means levels 30 to 39. */
		int from;
		int patches;
		int items;
		double predicted;

		public double getAverage()
		{
			return patches == 0 ? 0 : (double) items / patches;
		}

		public double getAveragePredicted()
		{
			return patches == 0 ? 0 : predicted / patches;
		}
	}

	private final List<FarmRun> runs = new ArrayList<>();
	private final Map<String, int[]> histograms = new LinkedHashMap<>();
	private final Map<Integer, int[]> levelPatches = new LinkedHashMap<>();
	private final Map<Integer, double[]> levelPredicted = new LinkedHashMap<>();

	/** Whether the file has been read yet, so the panel can tell "none" from "not looked". */
	@Getter
	private boolean loaded;

	/**
	 * Package-private rather than private: it takes no dependencies, so a test can build one
	 * directly instead of going through the reflection the injected stores need.
	 */
	@Inject
	HarvestHistory()
	{
	}

	// ------------------------------------------------------------------- loading

	/**
	 * Parses the log, once.
	 *
	 * <p>Called from plugin start-up — <b>off the client thread</b>, which hands in the file
	 * so this class needs no view of profiles. Blocking on file IO during a repaint is the
	 * failure this whole class is arranged around, and the initial read is the same hazard on
	 * the client thread: fifty thousand rows and possibly a whole-file trim, at the exact
	 * moment of login. The executor pays that cost where nobody feels it, and the summaries
	 * appear when they are ready.
	 */
	public synchronized void load(File file)
	{
		runs.clear();
		histograms.clear();
		levelPatches.clear();
		levelPredicted.clear();
		loaded = true;

		read(file);
	}

	/**
	 * Empties the summaries immediately, ahead of the queued read.
	 *
	 * <p>Called on the client thread at the moment of a load, precisely because {@link #load}
	 * no longer runs there: between a profile switch and the executor getting to the file,
	 * the panel would otherwise still be showing the previous profile's numbers. Marked not
	 * loaded, so the tab says "nothing read yet" rather than "no harvests" in the gap.
	 */
	public synchronized void beginLoad()
	{
		runs.clear();
		histograms.clear();
		levelPatches.clear();
		levelPredicted.clear();
		loaded = false;
	}

	/** Reads and parses one log file. Split out so a test can point it somewhere else. */
	synchronized void read(File file)
	{
		// Under the shared file lock, because the client thread appends to this same file and
		// the trim below rewrites it wholesale - see HarvestFiles.FILE_LOCK.
		List<String> lines;
		synchronized (HarvestFiles.FILE_LOCK)
		{
			if (!file.exists())
			{
				return;
			}

			try
			{
				lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
			}
			catch (IOException e)
			{
				log.warn("Could not read {}", file, e);
				return;
			}

			if (lines.isEmpty())
			{
				return;
			}

			List<String> data = lines.subList(1, lines.size());
			if (data.size() > MAX_ROWS)
			{
				data = data.subList(data.size() - MAX_ROWS, data.size());
				trim(file, lines.get(0), data);
				lines = new ArrayList<>(lines.subList(0, 1));
				lines.addAll(data);
			}
		}

		List<String> data = lines.subList(1, lines.size());
		List<String> columns = HarvestCsv.columnsOf(lines.get(0));
		List<HarvestRow> rows = new ArrayList<>();
		for (String line : data)
		{
			if (line.isEmpty())
			{
				continue;
			}
			HarvestRow row = HarvestCsv.parse(columns, line);
			if (row != null)
			{
				rows.add(row);
			}
		}

		// Chronological, because runs are found in the gaps between consecutive patches and the
		// file is only in order if nothing has ever been appended out of turn.
		rows.sort(Comparator.comparingLong(HarvestRow::getAt));
		for (HarvestRow row : rows)
		{
			fold(row);
		}

		log.debug("Read {} harvest rows into {} runs", rows.size(), runs.size());
	}

	/**
	 * Drops the oldest rows once the file outgrows {@link #MAX_ROWS}.
	 *
	 * <p><b>On the raw lines, never on parsed rows.</b> Only a handful of the file's columns are
	 * read back — the gear flags and the predicted experience answer nothing here — so writing
	 * rows out from what was parsed would quietly delete the rest. Whatever the file holds is
	 * what gets kept, understood or not.
	 *
	 * <p>Through a temporary file and an atomic move, because the alternative is a truncated log
	 * if the client is killed mid-write, and a half-written history is worse than a large one.
	 */
	private static void trim(File file, String header, List<String> keep)
	{
		try
		{
			List<String> lines = new ArrayList<>();
			lines.add(header);
			lines.addAll(keep);

			Path temporary = file.toPath().resolveSibling(file.getName() + ".trimming");
			Files.write(temporary, lines, StandardCharsets.UTF_8);
			Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
			log.info("Trimmed {} to its most recent {} harvests", file, MAX_ROWS);
		}
		catch (IOException e)
		{
			log.warn("Could not trim {}; it will keep growing", file, e);
		}
	}

	// ------------------------------------------------------------------ capture

	/**
	 * Folds a harvest that just happened into the same summaries the file was read into.
	 *
	 * <p>So the tab is current without the file being re-read. The row is built from the record
	 * rather than from what was written, which keeps this independent of whether the CSV write
	 * succeeded.
	 */
	public synchronized void record(HarvestRecord record)
	{
		if (record.getItemsHarvested() <= 0)
		{
			return;
		}
		fold(new HarvestRow(Instant.now().getEpochSecond(), record.getProduce().getName(),
			record.getPatch().getDisplayName(), record.getFarmingLevel(),
			record.getCompost().name(), record.getPredictedYield(), record.getItemsHarvested(),
			record.getXpGained(), record.isCompleted()));
	}

	/** Adds one row to the runs, the histogram and the level bands. */
	private void fold(HarvestRow row)
	{
		FarmRun current = runs.isEmpty() ? null : runs.get(runs.size() - 1);
		if (current == null || row.getAt() - current.getEndedAt() > RUN_GAP)
		{
			current = new FarmRun();
			runs.add(current);
		}
		current.add(row);

		// Only finished patches say anything about yield. A patch left standing is not a low
		// harvest, and putting one in a histogram would pile the "well under" bucket high.
		if (!row.isCompleted() || row.getPredicted() <= 0)
		{
			return;
		}

		int bucket = (int) Math.max(-HISTOGRAM_REACH,
			Math.min(HISTOGRAM_REACH, Math.round(row.getSurplus()))) + HISTOGRAM_REACH;
		histograms.computeIfAbsent(row.getCrop(), crop -> new int[HISTOGRAM_BUCKETS])[bucket]++;

		int band = Math.max(1, Math.min(90, row.getLevel() / 10 * 10));
		int[] counts = levelPatches.computeIfAbsent(band, level -> new int[2]);
		counts[0]++;
		counts[1] += row.getActual();
		levelPredicted.computeIfAbsent(band, level -> new double[1])[0] += row.getPredicted();
	}

	// -------------------------------------------------------------------- reads

	/** Every sitting reconstructed, oldest first. */
	public synchronized List<FarmRun> getRuns()
	{
		return new ArrayList<>(runs);
	}

	@Nullable
	public synchronized FarmRun getLastRun()
	{
		return runs.isEmpty() ? null : runs.get(runs.size() - 1);
	}

	/** The biggest sitting by experience, which is the one worth remembering. */
	@Nullable
	public synchronized FarmRun getBestRun()
	{
		FarmRun best = null;
		for (FarmRun run : runs)
		{
			if (best == null || run.getXp() > best.getXp())
			{
				best = run;
			}
		}
		return best;
	}

	public synchronized int getRunCount()
	{
		return runs.size();
	}

	public synchronized double getTotalXp()
	{
		return runs.stream().mapToDouble(FarmRun::getXp).sum();
	}

	/**
	 * Experience per run, which is the unit farming actually has.
	 *
	 * <p>"XP per hour" is the wrong question for this skill and getting that right is most of
	 * the point: a run is ten or fifteen minutes and then the crop grows for eighty while you do
	 * something else. Measured over the run alone it is a flattering number describing nothing
	 * sustainable; measured over elapsed time it is a tiny one dominated by sleep. A run is what
	 * a farmer plans around, so it is what this divides by.
	 */
	public synchronized double getXpPerRun()
	{
		return runs.isEmpty() ? 0 : getTotalXp() / runs.size();
	}

	/**
	 * Experience per day, the honest throughput figure for a skill gated by a growth timer.
	 *
	 * <p>Arguably <i>the</i> farming rate, and nobody displays it. Zero until a day has actually
	 * elapsed, because a rate over four hours extrapolated to a day is invented.
	 */
	public synchronized double getXpPerDay()
	{
		if (runs.size() < 2)
		{
			return 0;
		}
		long span = runs.get(runs.size() - 1).getEndedAt() - runs.get(0).getStartedAt();
		double days = span / 86400.0;
		return days < 1 ? 0 : getTotalXp() / days;
	}

	/**
	 * Experience per hour <i>while actually farming</i>, summed over the run clusters.
	 *
	 * <p>Worth showing because it is what people mean when they ask, and it has to be labelled
	 * active rather than left to imply it is sustainable. Runs of a single patch are excluded:
	 * their duration is zero and they would divide a real number by nothing.
	 */
	public synchronized double getActiveXpPerHour()
	{
		double xp = 0;
		long seconds = 0;
		for (FarmRun run : runs)
		{
			if (run.getDuration() > 0)
			{
				xp += run.getXp();
				seconds += run.getDuration();
			}
		}
		return seconds == 0 ? 0 : xp / (seconds / 3600.0);
	}

	/** Where one crop's patches landed against their predictions, or null with none recorded. */
	@Nullable
	public synchronized Histogram getHistogram(String crop)
	{
		int[] buckets = histograms.get(crop);
		if (buckets == null)
		{
			return null;
		}
		int patches = 0;
		for (int count : buckets)
		{
			patches += count;
		}
		return patches == 0 ? null : new Histogram(crop, buckets.clone(), patches);
	}

	/**
	 * Average yield by band of ten Farming levels, lowest first.
	 *
	 * <p>Shows the chance-to-save curve flattening, which is a real effect most players never
	 * see — and it is the one view that checks the level scaling rather than the constants.
	 */
	public synchronized List<LevelBand> getLevelBands()
	{
		List<LevelBand> bands = new ArrayList<>();
		for (Map.Entry<Integer, int[]> entry : levelPatches.entrySet())
		{
			double[] predicted = levelPredicted.get(entry.getKey());
			bands.add(new LevelBand(entry.getKey(), entry.getValue()[0], entry.getValue()[1],
				predicted == null ? 0 : predicted[0]));
		}
		bands.sort(Comparator.comparingInt(LevelBand::getFrom));
		return bands;
	}

	/** Drops everything held, for the maintenance reset. The file is the caller's problem. */
	public synchronized void clear()
	{
		runs.clear();
		histograms.clear();
		levelPatches.clear();
		levelPredicted.clear();
	}
}
