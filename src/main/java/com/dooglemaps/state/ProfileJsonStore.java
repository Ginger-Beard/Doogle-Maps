package com.dooglemaps.state;

import com.dooglemaps.DoogleMapsConfig;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * One JSON blob under one profile-scoped config key, which is what most of the stores are.
 *
 * <h2>Why a base class, when each store is only forty lines of plumbing</h2>
 *
 * Because the forty lines were copied a dozen times and the copies drifted — most visibly in
 * <i>locking</i>. Before this, which of {@code load} and {@code save} held the store's monitor
 * varied store by store, not by any per-store reasoning but by which copy was the template that
 * day. None of the variants was an observed bug, because every caller happened to hold the
 * monitor already — but "happens to be called under the lock" is a rule living in the callers'
 * heads, and the thirteenth store would have copied whichever answer it found first. Here the
 * decision is made once: {@link #load} and {@link #save} both hold the store's monitor, and a
 * subclass only ever runs inside it.
 *
 * <p>The contract for a subclass, all three called with the monitor held:
 *
 * <ul>
 *   <li>{@link #resetForLoad()} — empty the in-memory state; a load must never merge.</li>
 *   <li>{@link #applyJson(String)} — parse and admit the stored JSON, validating each entry
 *       before it goes in; stored data survives version changes, so nothing about its shape is
 *       guaranteed. A {@link JsonSyntaxException} is caught here and the whole blob dropped,
 *       matching what every store already did.</li>
 *   <li>{@link #serialized()} — the object to write, in whatever shape reads back.</li>
 * </ul>
 *
 * <p>{@link #loaded()} runs after a load <b>outside</b> the monitor, for stores with change
 * listeners: a listener reads back through the public getters, and calling out with the lock
 * held is how deadlocks start. Same rule the stores already followed one by one.
 */
@Slf4j
public abstract class ProfileJsonStore
{
	protected final ConfigManager configManager;
	protected final Gson gson;
	private final String key;

	protected ProfileJsonStore(ConfigManager configManager, Gson gson, String key)
	{
		this.configManager = configManager;
		this.gson = gson;
		this.key = key;
	}

	/** Reads this profile's blob, replacing everything held in memory. */
	public final void load()
	{
		synchronized (this)
		{
			resetForLoad();

			String json = configManager.getRSProfileConfiguration(DoogleMapsConfig.GROUP, key);
			if (json != null && !json.isEmpty())
			{
				try
				{
					applyJson(json);
				}
				catch (JsonSyntaxException e)
				{
					log.warn("Discarding unreadable {}", key, e);
				}
			}
			afterLoad();
		}
		loaded();
	}

	/**
	 * Writes the current state.
	 *
	 * <p>Serialisation happens under the store's monitor, so the picture written is always
	 * consistent — but the ConfigManager write happens <b>outside</b> it, and that split is
	 * load-bearing. ConfigManager fires {@code ConfigChanged} synchronously into every
	 * subscriber, which is arbitrary plugin code reaching into other stores; posting that
	 * with a store monitor held is how the client froze solid on an Explorer's ring
	 * teleport. The thread dump read: Client held {@code PatchStateStore} (a varbit write
	 * mid-save), the ConfigChanged subscriber wanted {@code AvailabilityProfile}; Swing held
	 * {@code AvailabilityProfile} (a panel refresh) and wanted {@code PatchStateStore}.
	 * Deadlock, found by the JVM itself.
	 *
	 * <p>The same reasoning binds callers: do not invoke this while holding the store's
	 * monitor, or the write happens under your lock and the hole is back. Mutate inside
	 * {@code synchronized}, return whether anything changed, and save from outside — see
	 * {@code PatchStateStore.recordVarbit} for the shape. This is {@code RunPlanner}'s rule
	 * ("nothing outside this class is ever called with the lock held") applied to the
	 * stores.
	 */
	protected final void save()
	{
		String json;
		synchronized (this)
		{
			json = gson.toJson(serialized());
		}
		configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, key, json);
	}

	/**
	 * Removes the stored blob outright, for the resets that also empty the memory side.
	 *
	 * <p>No monitor of its own: it reads no store state, and the unset fires ConfigChanged
	 * exactly like a write does — see {@link #save()} for why that must not happen under a
	 * store's lock. Callers empty their state inside {@code synchronized} and call this
	 * after.
	 */
	protected final void unsetStored()
	{
		configManager.unsetRSProfileConfiguration(DoogleMapsConfig.GROUP, key);
	}

	/**
	 * Work that belongs to the load but not to the blob, run inside the monitor after the
	 * parse step — <b>blob or no blob</b>, which is the point: {@code PatchStateStore}'s
	 * Time Tracking backfill exists precisely for the fresh install where there is nothing
	 * stored yet. No-op by default.
	 */
	protected void afterLoad()
	{
	}

	/** Empties the in-memory state ahead of a read. Monitor held. */
	protected abstract void resetForLoad();

	/** Parses and admits stored JSON, validating entries as they go in. Monitor held. */
	protected abstract void applyJson(String json) throws JsonSyntaxException;

	/** What to persist, in the shape {@link #applyJson} reads back. Monitor held. */
	protected abstract Object serialized();

	/** After every load, outside the monitor — the place to notify change listeners. */
	protected void loaded()
	{
	}
}
