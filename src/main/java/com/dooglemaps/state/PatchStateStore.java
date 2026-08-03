package com.dooglemaps.state;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.ProduceState;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * The cached state of every farming patch, and the only thing that writes it.
 *
 * <p>Capture components hand states in here; the panel and timers read them back out.
 * Everything is persisted per RuneScape profile, so multiple accounts need no special
 * handling.
 */
@Slf4j
@Singleton
public class PatchStateStore
{
	/** Config key holding the serialised snapshot map. */
	private static final String PATCHES_KEY = "patches";

	/** Core Time Tracking's config group, which we read once to seed a new install. */
	private static final String TIMETRACKING_GROUP = "timetracking";
	private static final String TIMETRACKING_COMPOST_SUFFIX = ".compost";
	private static final String TIMETRACKING_PROTECTED_SUFFIX = ".protected";

	private static final Type SNAPSHOT_MAP_TYPE = new TypeToken<HashMap<String, PatchSnapshot>>()
	{
	}.getType();

	private final ConfigManager configManager;
	private final Gson gson;

	/** Keyed by {@link FarmPatch#getKey()}. */
	private final Map<String, PatchSnapshot> snapshots = new HashMap<>();

	private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

	@Inject
	private PatchStateStore(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	/** Registers a callback fired after any change, used to repaint the panel. */
	public void addChangeListener(Runnable listener)
	{
		changeListeners.add(listener);
	}

	public void removeChangeListener(Runnable listener)
	{
		changeListeners.remove(listener);
	}

	/**
	 * Notifies listeners. Must never be called while holding this store's monitor:
	 * listeners read back through {@link AvailabilityProfile}, which takes its own lock
	 * and then ours, so firing under the lock would invert that order and can deadlock.
	 */
	private void fireChanged()
	{
		for (Runnable listener : changeListeners)
		{
			listener.run();
		}
	}

	// ------------------------------------------------------------------ reads

	@Nullable
	public synchronized PatchSnapshot get(FarmPatch patch)
	{
		return snapshots.get(patch.getKey());
	}

	@Nullable
	public synchronized PatchSnapshot get(String patchKey)
	{
		return snapshots.get(patchKey);
	}

	// ----------------------------------------------------------------- writes

	/**
	 * Records a patch's contents as decoded from its varbit.
	 *
	 * <p>This is the sequential state machine of the spec: as the player harvests,
	 * composts, plants and pays, the varbit walks through those states and we follow it.
	 * Compost and protection are tracked separately (the varbit does not carry them) and
	 * are cleared here whenever the patch reaches a state that discards them.
	 *
	 * @return true if anything actually changed
	 */
	public boolean recordVarbit(FarmPatch patch, int varbitValue, ProduceState decoded)
	{
		boolean changed = applyVarbit(patch, varbitValue, decoded);
		if (changed)
		{
			fireChanged();
		}
		return changed;
	}

	private synchronized boolean applyVarbit(FarmPatch patch, int varbitValue, ProduceState decoded)
	{
		PatchSnapshot snapshot = snapshots.computeIfAbsent(patch.getKey(), PatchStateStore::blank);

		boolean changed = snapshot.getVarbitValue() != varbitValue
			|| snapshot.getProduce() != decoded.getProduce()
			|| snapshot.getCropState() != decoded.getCropState()
			|| snapshot.getStage() != decoded.getStage();

		snapshot.setVarbitValue(varbitValue);
		snapshot.setProduce(decoded.getProduce());
		snapshot.setCropState(decoded.getCropState());
		snapshot.setStage(decoded.getStage());
		snapshot.setLastSeen(Instant.now().getEpochSecond());

		// Emptying, dying or ripening ends the current crop's treatment: compost is
		// consumed by the next planting and protection was bought for the crop that just
		// finished. Mirrors how core Time Tracking clears the same two flags.
		if (decoded.getCropState() == CropState.EMPTY
			|| decoded.getCropState() == CropState.DEAD
			|| decoded.getCropState() == CropState.HARVESTABLE
			|| !decoded.getProduce().isCrop())
		{
			changed |= snapshot.getCompost() != CompostTier.NONE || snapshot.isPatchProtected();
			snapshot.setCompost(CompostTier.NONE);
			snapshot.setPatchProtected(false);
		}

		if (changed)
		{
			save();
		}
		return changed;
	}

	public void recordCompost(FarmPatch patch, CompostTier tier)
	{
		if (applyCompost(patch, tier))
		{
			fireChanged();
		}
	}

	private synchronized boolean applyCompost(FarmPatch patch, CompostTier tier)
	{
		PatchSnapshot snapshot = snapshots.computeIfAbsent(patch.getKey(), PatchStateStore::blank);
		if (snapshot.getCompost() == tier)
		{
			return false;
		}

		log.debug("Compost {} recorded for {}", tier, patch);
		snapshot.setCompost(tier);
		snapshot.setLastSeen(Instant.now().getEpochSecond());
		save();
		return true;
	}

	public void recordProtected(FarmPatch patch, boolean isProtected)
	{
		if (applyProtected(patch, isProtected))
		{
			fireChanged();
		}
	}

	private synchronized boolean applyProtected(FarmPatch patch, boolean isProtected)
	{
		PatchSnapshot snapshot = snapshots.computeIfAbsent(patch.getKey(), PatchStateStore::blank);
		if (snapshot.isPatchProtected() == isProtected)
		{
			return false;
		}

		log.debug("Protection {} recorded for {}", isProtected, patch);
		snapshot.setPatchProtected(isProtected);
		snapshot.setLastSeen(Instant.now().getEpochSecond());
		save();
		return true;
	}

	private static PatchSnapshot blank(String patchKey)
	{
		PatchSnapshot snapshot = new PatchSnapshot();
		snapshot.setPatchKey(patchKey);
		snapshot.setCompost(CompostTier.NONE);
		return snapshot;
	}

	// ----------------------------------------------------------- persistence

	public void load()
	{
		doLoad();
		fireChanged();
	}

	private synchronized void doLoad()
	{
		snapshots.clear();

		String json = configManager.getRSProfileConfiguration(DoogleMapsConfig.GROUP, PATCHES_KEY);
		if (json != null && !json.isEmpty())
		{
			try
			{
				Map<String, PatchSnapshot> loaded = gson.fromJson(json, SNAPSHOT_MAP_TYPE);
				if (loaded != null)
				{
					// Drop keys for patches that no longer exist, e.g. after a data update
					// moved a patch to a different region.
					loaded.forEach((key, snapshot) ->
					{
						if (FarmingWorldData.getPatch(key) != null && snapshot != null)
						{
							snapshot.setPatchKey(key);
							if (snapshot.getCompost() == null)
							{
								snapshot.setCompost(CompostTier.NONE);
							}
							snapshots.put(key, snapshot);
						}
					});
				}
			}
			catch (JsonSyntaxException e)
			{
				log.warn("Discarding unreadable patch cache", e);
			}
		}

		int backfilled = backfillFromTimeTracking();
		if (backfilled > 0)
		{
			log.debug("Backfilled {} patches from core Time Tracking", backfilled);
			save();
		}
	}

	public synchronized void save()
	{
		configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, PATCHES_KEY, gson.toJson(snapshots));
	}

	/**
	 * Seeds patches we have never seen from core Time Tracking's own cache.
	 *
	 * <p>Time Tracking is on by default and has usually been quietly recording the same
	 * varbits for as long as the account has existed, so a fresh install of this plugin
	 * can start with a populated overview instead of an empty one. Read-only, and only
	 * ever fills gaps — anything we have captured ourselves wins.
	 *
	 * @return how many patches were seeded
	 */
	private int backfillFromTimeTracking()
	{
		int seeded = 0;
		for (FarmPatch patch : FarmingWorldData.getAllPatches())
		{
			if (snapshots.containsKey(patch.getKey()))
			{
				continue;
			}

			String stored = configManager.getRSProfileConfiguration(TIMETRACKING_GROUP, patch.getKey());
			if (stored == null)
			{
				continue;
			}

			// Stored as "<varbitValue>:<unixSeconds>".
			String[] parts = stored.split(":");
			if (parts.length != 2)
			{
				continue;
			}

			final int varbitValue;
			final long lastSeen;
			try
			{
				varbitValue = Integer.parseInt(parts[0]);
				lastSeen = Long.parseLong(parts[1]);
			}
			catch (NumberFormatException e)
			{
				continue;
			}

			ProduceState decoded = patch.getImplementation().forVarbitValue(varbitValue);
			if (decoded == null || lastSeen <= 0)
			{
				continue;
			}

			PatchSnapshot snapshot = blank(patch.getKey());
			snapshot.setVarbitValue(varbitValue);
			snapshot.setProduce(decoded.getProduce());
			snapshot.setCropState(decoded.getCropState());
			snapshot.setStage(decoded.getStage());
			snapshot.setLastSeen(lastSeen);
			snapshot.setCompost(readTimeTrackingCompost(patch));
			snapshot.setPatchProtected(Boolean.TRUE.equals(configManager.getRSProfileConfiguration(
				TIMETRACKING_GROUP, patch.getKey() + TIMETRACKING_PROTECTED_SUFFIX, Boolean.class)));

			snapshots.put(patch.getKey(), snapshot);
			seeded++;
		}
		return seeded;
	}

	private CompostTier readTimeTrackingCompost(FarmPatch patch)
	{
		String value = configManager.getRSProfileConfiguration(
			TIMETRACKING_GROUP, patch.getKey() + TIMETRACKING_COMPOST_SUFFIX);
		if (value == null)
		{
			return CompostTier.NONE;
		}

		// Core stores CompostState's enum name: COMPOST, SUPERCOMPOST or ULTRACOMPOST.
		try
		{
			return CompostTier.valueOf(value.toUpperCase());
		}
		catch (IllegalArgumentException e)
		{
			return CompostTier.NONE;
		}
	}

	public void clear()
	{
		doClear();
		fireChanged();
	}

	private synchronized void doClear()
	{
		snapshots.clear();
		configManager.unsetRSProfileConfiguration(DoogleMapsConfig.GROUP, PATCHES_KEY);
	}
}
