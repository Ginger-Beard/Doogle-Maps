package com.dooglemaps.validate;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.timer.DiseaseRisk;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * What actually happened to your patches, disease-wise.
 *
 * <h2>The one thing the harvest log structurally cannot see</h2>
 *
 * Yield has been checkable against reality since the harvest log existed. Disease never has,
 * because <b>a patch that dies produces no harvest</b> — it never opens a record, never writes a
 * row, and is invisible to every figure built on them. So every disease and death number in the
 * plugin has come from the published constants with nothing to test it against, which is the
 * exact situation the yield work existed to get out of.
 *
 * <p>This closes it from the other end: not from what was picked, but from what the patch state
 * did. The varbits are already watched for growth ticks and harvest ends, so the transitions are
 * there to be counted.
 *
 * <h2>What counts as what</h2>
 *
 * <ul>
 *   <li><b>Entering diseased</b> flags the cycle. Curing it afterwards does not un-flag it — the
 *       roll still went against you, and the roll is what the published rate describes.</li>
 *   <li><b>Reaching harvestable</b> closes the cycle as an outcome, diseased or not.</li>
 *   <li><b>Reaching dead</b> closes it too, and counts as diseased whether or not the diseased
 *       state itself was ever seen. A patch that sickened and died while you were elsewhere
 *       shows up as a single jump to dead, and treating that as "not diseased" would count the
 *       worst outcome as a clean run.</li>
 *   <li><b>Emptying</b> — harvested, cleared, replanted — forgets the flag, so the next cycle in
 *       that patch starts clean.</li>
 * </ul>
 *
 * <p><b>Only observed cycles count.</b> One that began and ended entirely while you were away is
 * missing data, not a survival, and is left out of the denominator rather than counted as one.
 */
@Slf4j
@Singleton
public class DiseaseStatsStore extends com.dooglemaps.state.ProfileJsonStore
{
	private static final String STATS_KEY = "diseaseStats";

	private static final Type STATS_MAP_TYPE = new TypeToken<LinkedHashMap<String, DiseaseStats>>()
	{
	}.getType();

	/** Keyed by crop and compost tier; see {@link #key}. */
	private final Map<String, DiseaseStats> stats = new LinkedHashMap<>();

	/**
	 * Patches known to have caught something during the cycle they are currently in.
	 *
	 * <p>In memory only, and deliberately. It describes a growth cycle in flight, and a cycle
	 * that spans a logout has already lost the transitions in the middle of it — persisting the
	 * flag would preserve a fact about a cycle whose outcome we may never see.
	 */
	private final Set<String> sickThisCycle = new HashSet<>();

	@Inject
	DiseaseStatsStore(ConfigManager configManager, Gson gson)
	{
		super(configManager, gson, STATS_KEY);
	}

	/**
	 * Folds one observed state change into the record.
	 *
	 * <p>Called from the patch scanner, which already decodes every transition it sees for the
	 * growth timer and the harvest log.
	 *
	 * @param protectedByFarmer whether a farmer had been paid to watch this patch
	 * @param diseaseFree       whether the account's own unlocks make this patch immune
	 */
	public synchronized void observe(FarmPatch patch, @Nullable ProduceState previous,
		ProduceState current, CompostTier compost, boolean protectedByFarmer, boolean diseaseFree)
	{
		if (previous == null || current.getCropState() == previous.getCropState())
		{
			return;
		}

		String key = patch.getKey();
		switch (current.getCropState())
		{
			case DISEASED:
				sickThisCycle.add(key);
				break;

			case DEAD:
				// Counted as diseased whichever way it got here: either we saw the diseased
				// state and flagged it, or we missed it and this is the only evidence there was
				// ever a roll at all.
				close(patch, produceOf(previous, current), compost, protectedByFarmer, diseaseFree,
					true, true);
				sickThisCycle.remove(key);
				break;

			case HARVESTABLE:
				// Only from growing. A bush going back to harvestable as it regrows is the same
				// cycle continuing, not a new one survived.
				if (previous.getCropState() == CropState.GROWING)
				{
					close(patch, produceOf(previous, current), compost, protectedByFarmer,
						diseaseFree, sickThisCycle.contains(key), false);
					sickThisCycle.remove(key);
				}
				break;

			case EMPTY:
				sickThisCycle.remove(key);
				break;

			default:
				break;
		}
	}

	/** Records one finished growth cycle against the crop and tier it happened under. */
	private void close(FarmPatch patch, @Nullable Produce produce, CompostTier compost,
		boolean protectedByFarmer, boolean diseaseFree, boolean caught, boolean dead)
	{
		if (produce == null || produce == Produce.WEEDS)
		{
			return;
		}

		// A patch that cannot be diseased says nothing about the disease rate, and counting its
		// certain survival would dilute every rate towards a hundred percent.
		double predicted = DiseaseRisk.survivalChance(patch, produce, compost, protectedByFarmer,
			diseaseFree);
		if (predicted >= 1)
		{
			return;
		}

		CompostTier tier = compost == null ? CompostTier.NONE : compost;
		DiseaseStats entry = stats.computeIfAbsent(key(produce.getName(), tier.name()), k ->
		{
			DiseaseStats fresh = new DiseaseStats();
			fresh.setCrop(produce.getName());
			fresh.setCompost(tier.name());
			fresh.setFirstSeen(Instant.now().getEpochSecond());
			return fresh;
		});

		entry.setCycles(entry.getCycles() + 1);
		entry.setPredictedSurvivals(entry.getPredictedSurvivals() + predicted);
		if (caught)
		{
			entry.setDiseased(entry.getDiseased() + 1);
		}
		if (dead)
		{
			entry.setDied(entry.getDied() + 1);
		}
		entry.setLastSeen(Instant.now().getEpochSecond());
		save();
	}

	/** The crop this cycle was growing, preferring whichever state still names one. */
	@Nullable
	private static Produce produceOf(ProduceState previous, ProduceState current)
	{
		return current.getProduce() != null && current.getProduce() != Produce.WEEDS
			? current.getProduce()
			: previous.getProduce();
	}

	// ------------------------------------------------------------------- reads

	/** Every crop and tier observed, most cycles first. */
	public synchronized List<DiseaseStats> getAll()
	{
		List<DiseaseStats> all = new ArrayList<>(stats.values());
		all.sort((a, b) -> Integer.compare(b.getCycles(), a.getCycles()));
		return all;
	}

	/** The same summed across compost tiers, for a per-crop view. */
	public synchronized List<DiseaseStats> getByCrop()
	{
		Map<String, DiseaseStats> merged = new TreeMap<>();
		for (DiseaseStats entry : stats.values())
		{
			DiseaseStats total = merged.computeIfAbsent(entry.getCrop(), crop ->
			{
				DiseaseStats fresh = new DiseaseStats();
				fresh.setCrop(crop);
				return fresh;
			});
			total.setCycles(total.getCycles() + entry.getCycles());
			total.setDiseased(total.getDiseased() + entry.getDiseased());
			total.setDied(total.getDied() + entry.getDied());
			total.setPredictedSurvivals(
				total.getPredictedSurvivals() + entry.getPredictedSurvivals());
		}

		List<DiseaseStats> all = new ArrayList<>(merged.values());
		all.sort((a, b) -> Integer.compare(b.getCycles(), a.getCycles()));
		return all;
	}

	public synchronized int getTotalCycles()
	{
		return stats.values().stream().mapToInt(DiseaseStats::getCycles).sum();
	}

	public synchronized int getTotalDiseased()
	{
		return stats.values().stream().mapToInt(DiseaseStats::getDiseased).sum();
	}

	public synchronized int getTotalDied()
	{
		return stats.values().stream().mapToInt(DiseaseStats::getDied).sum();
	}

	/** What the published rates said should have survived, over the same cycles. */
	public synchronized double getPredictedSurvivals()
	{
		return stats.values().stream().mapToDouble(DiseaseStats::getPredictedSurvivals).sum();
	}

	// ------------------------------------------------------------- persistence

	@Override
	protected void resetForLoad()
	{
		stats.clear();
		sickThisCycle.clear();
	}

	@Override
	protected void applyJson(String json)
	{
		Map<String, DiseaseStats> loaded = gson.fromJson(json, STATS_MAP_TYPE);
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

	public synchronized void clear()
	{
		stats.clear();
		sickThisCycle.clear();
		save();
	}

	private static String key(String crop, String compost)
	{
		return crop + '|' + compost;
	}
}
