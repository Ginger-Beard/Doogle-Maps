package com.dooglemaps.validate;

import com.dooglemaps.DoogleMapsConfig;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
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
 * read rather than only appended to a CSV. Nothing displays them yet.
 *
 * <p>Persisted per RuneScape profile like every other store here, so two accounts keep
 * separate histories without anything special.
 */
@Slf4j
@Singleton
public class HarvestStatsStore
{
	private static final String STATS_KEY = "harvestStats";

	private static final Type STATS_MAP_TYPE = new TypeToken<LinkedHashMap<String, CropHarvestStats>>()
	{
	}.getType();

	private final ConfigManager configManager;
	private final Gson gson;

	/** Keyed by crop and compost tier; see {@link #key}. */
	private final Map<String, CropHarvestStats> stats = new LinkedHashMap<>();

	@Inject
	private HarvestStatsStore(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
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

	public synchronized void load()
	{
		stats.clear();

		String json = configManager.getRSProfileConfiguration(DoogleMapsConfig.GROUP, STATS_KEY);
		if (json == null || json.isEmpty())
		{
			return;
		}

		try
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
		catch (JsonSyntaxException e)
		{
			log.warn("Discarding unreadable harvest stats", e);
		}
	}

	/** Wipes the history. Nothing calls this yet; a stats panel will want a reset button. */
	public synchronized void clear()
	{
		stats.clear();
		save();
	}

	private void save()
	{
		configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, STATS_KEY, gson.toJson(stats));
	}

	private static String key(String crop, String compost)
	{
		return crop + '|' + compost;
	}
}
