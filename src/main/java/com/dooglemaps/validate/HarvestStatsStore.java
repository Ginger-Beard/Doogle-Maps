package com.dooglemaps.validate;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * A lifetime record of what your patches have actually given you.
 *
 * <p>Started as validation — does the predicted yield match reality — but the same numbers
 * are the interesting ones to look at as a player, so they are kept somewhere a panel can
 * read rather than only appended to a CSV. The Stats tab reads them.
 *
 * <p>Rolled up rather than kept per patch, and that shape turns out to bound what the tab can
 * ask. Anything that is a <i>sum</i> over patches survives the roll-up — totals, and the mean
 * and variance behind {@link CropHarvestStats#getLuckPercentile} — while anything about shape
 * or sequence does not. Those want {@code harvests.csv}, which keeps a row per patch.
 *
 * <p>Persisted per RuneScape profile like every other store here, so two accounts keep
 * separate histories without anything special.
 */
@Slf4j
@Singleton
public class HarvestStatsStore extends com.dooglemaps.state.ProfileJsonStore
{
	private static final String STATS_KEY = "harvestStats";

	private static final Type STATS_MAP_TYPE = new TypeToken<LinkedHashMap<String, CropHarvestStats>>()
	{
	}.getType();

	/** Keyed by crop and compost tier; see {@link #key}. */
	private final Map<String, CropHarvestStats> stats = new LinkedHashMap<>();

	@Inject
	HarvestStatsStore(ConfigManager configManager, Gson gson)
	{
		super(configManager, gson, STATS_KEY);
	}

	/**
	 * Folds one finished patch into the totals.
	 *
	 * <p>Split by compost tier because that is the single biggest lever on yield, and an
	 * average that mixed untreated and ultracomposted patches would describe neither.
	 */
	public synchronized void record(HarvestRecord record)
	{
		if (record.getItemsHarvested() <= 0)
		{
			return;
		}

		CropHarvestStats entry = stats.computeIfAbsent(
			key(record.getProduce().getName(), record.getCompost().name()),
			k ->
			{
				CropHarvestStats fresh = new CropHarvestStats();
				fresh.setCrop(record.getProduce().getName());
				fresh.setCompost(record.getCompost().name());
				fresh.setFirstHarvest(Instant.now().getEpochSecond());
				return fresh;
			});

		if (record.isCompleted())
		{
			entry.setHarvests(entry.getHarvests() + 1);
			entry.setItems(entry.getItems() + record.getItemsHarvested());
			entry.setPredicted(entry.getPredicted() + record.getPredictedYield());
			entry.setXp(entry.getXp() + record.getXpGained());

			// Counted separately from the harvest, because the two do not always agree: a crop
			// with no modelled spread contributes a prediction and no variance, and so did every
			// patch recorded before this was captured at all. Only where they match is the
			// running variance describing the same patches as the running mean.
			double variance = record.getPredictedVariance();
			if (variance > 0)
			{
				entry.setPredictedVariance(entry.getPredictedVariance() + variance);
				entry.setVariancePatches(entry.getVariancePatches() + 1);
			}

			entry.setBest(Math.max(entry.getBest(), record.getItemsHarvested()));
			entry.setWorst(entry.getWorst() == 0
				? record.getItemsHarvested()
				: Math.min(entry.getWorst(), record.getItemsHarvested()));
		}
		else
		{
			entry.setPartialItems(entry.getPartialItems() + record.getItemsHarvested());
			entry.setPartialXp(entry.getPartialXp() + record.getXpGained());
		}

		entry.setLastHarvest(Instant.now().getEpochSecond());
		save();
	}

	// ------------------------------------------------------------------- reads

	/** Every crop and tier, most recently harvested first. */
	public synchronized List<CropHarvestStats> getAll()
	{
		List<CropHarvestStats> all = new ArrayList<>(stats.values());
		all.sort(Comparator.comparingLong(CropHarvestStats::getLastHarvest).reversed());
		return all;
	}

	/**
	 * The same totals summed across compost tiers, for a per-crop view.
	 *
	 * <p>The tier fields are meaningless once summed, so the result carries no compost name
	 * rather than an arbitrary one.
	 */
	public synchronized List<CropHarvestStats> getByCrop()
	{
		Map<String, CropHarvestStats> merged = new TreeMap<>();
		for (CropHarvestStats entry : stats.values())
		{
			CropHarvestStats total = merged.computeIfAbsent(entry.getCrop(), crop ->
			{
				CropHarvestStats fresh = new CropHarvestStats();
				fresh.setCrop(crop);
				return fresh;
			});

			total.setHarvests(total.getHarvests() + entry.getHarvests());
			total.setItems(total.getItems() + entry.getItems());
			total.setPredicted(total.getPredicted() + entry.getPredicted());
			total.setPredictedVariance(total.getPredictedVariance() + entry.getPredictedVariance());
			total.setVariancePatches(total.getVariancePatches() + entry.getVariancePatches());
			total.setXp(total.getXp() + entry.getXp());
			total.setPartialItems(total.getPartialItems() + entry.getPartialItems());
			total.setPartialXp(total.getPartialXp() + entry.getPartialXp());
			total.setBest(Math.max(total.getBest(), entry.getBest()));
			total.setWorst(total.getWorst() == 0
				? entry.getWorst()
				: Math.min(total.getWorst(), Math.max(entry.getWorst(), 1)));
			total.setFirstHarvest(total.getFirstHarvest() == 0
				? entry.getFirstHarvest()
				: Math.min(total.getFirstHarvest(), entry.getFirstHarvest()));
			total.setLastHarvest(Math.max(total.getLastHarvest(), entry.getLastHarvest()));
		}

		List<CropHarvestStats> all = new ArrayList<>(merged.values());
		all.sort(Comparator.comparingInt(CropHarvestStats::getTotalItems).reversed());
		return all;
	}

	/** Patches picked clean, across every crop. */
	public synchronized int getTotalHarvests()
	{
		return stats.values().stream().mapToInt(CropHarvestStats::getHarvests).sum();
	}

	/** Every item ever harvested, partial patches included. */
	public synchronized int getTotalItems()
	{
		return stats.values().stream().mapToInt(CropHarvestStats::getTotalItems).sum();
	}

	public synchronized double getTotalXp()
	{
		return stats.values().stream().mapToDouble(CropHarvestStats::getTotalXp).sum();
	}

	/**
	 * When the first patch was recorded, as epoch seconds, or 0 with nothing recorded.
	 *
	 * <p>The stats tab needs this to be honest about what it is showing. Without a start date
	 * the totals read as an account's whole farming history, and they are not — they begin the
	 * day the plugin was installed.
	 */
	public synchronized long getFirstHarvest()
	{
		return stats.values().stream()
			.mapToLong(CropHarvestStats::getFirstHarvest)
			.filter(at -> at > 0)
			.min()
			.orElse(0);
	}

	/** When the most recent patch was recorded, so the span between the two is knowable. */
	public synchronized long getLastHarvest()
	{
		return stats.values().stream().mapToLong(CropHarvestStats::getLastHarvest).max().orElse(0);
	}

	/**
	 * Items harvested over items predicted, summed across every crop.
	 *
	 * <p>The cumulative version of luck, and the only one that needs no variance at all — so it
	 * works on a history recorded before the spread was captured, and on crops whose spread the
	 * plugin cannot model. Crops with no prediction are excluded rather than counted as a
	 * surplus of their whole harvest.
	 */
	public synchronized double getTotalSurplus()
	{
		return stats.values().stream().mapToDouble(CropHarvestStats::getSurplus).sum();
	}

	/** Items from patches that were left standing, across every crop. */
	public synchronized int getTotalPartialItems()
	{
		return stats.values().stream().mapToInt(CropHarvestStats::getPartialItems).sum();
	}

	/**
	 * How the predictions are doing overall: actual over predicted, 1.0 being spot on.
	 *
	 * <p>Only crops with a published prediction contribute, so an unpredicted crop cannot
	 * drag the figure towards zero just by being harvested.
	 */
	public synchronized double getOverallAccuracy()
	{
		double predicted = stats.values().stream().mapToDouble(CropHarvestStats::getPredicted).sum();
		if (predicted <= 0)
		{
			return 0;
		}
		int items = stats.values().stream()
			.filter(entry -> entry.getPredicted() > 0)
			.mapToInt(CropHarvestStats::getItems)
			.sum();
		return items / predicted;
	}

	// ------------------------------------------------------------- persistence

	@Override
	protected void resetForLoad()
	{
		stats.clear();
	}

	@Override
	protected void applyJson(String json)
	{
		Map<String, CropHarvestStats> loaded = gson.fromJson(json, STATS_MAP_TYPE);
		if (loaded != null)
		{
			loaded.forEach((key, entry) ->
			{
				if (entry != null && entry.getCrop() != null)
				{
					stats.put(key, entry);
				}
			});
		}
	}

	@Override
	protected Object serialized()
	{
		return stats;
	}

	/** Wipes the history. Nothing calls this yet; a stats panel will want a reset button. */
	public synchronized void clear()
	{
		stats.clear();
		save();
	}

	private static String key(String crop, String compost)
	{
		return crop + '|' + compost;
	}
}
