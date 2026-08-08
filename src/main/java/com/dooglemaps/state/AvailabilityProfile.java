package com.dooglemaps.state;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PatchRequirements;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Which patches this account actually uses.
 *
 * <p>This is a global invariant, not a display filter: a patch that is off must never be
 * shown, planted into, gathered for, routed to or highlighted anywhere in the plugin. If
 * you have not done <i>Making Friends with My Arm</i>, Weiss is simply absent.
 *
 * <p>The mechanism is deliberately manual. A hardcoded patch-to-unlock table plus quest
 * and diary varbit detection would need maintenance every time Jagex touches a patch or a
 * requirement; letting the player decide is zero-maintenance and always right for their
 * account. Auto-detection, if it ever lands, only pre-fills these toggles.
 *
 * <p>Patches the player has never explicitly set default to "on if we have ever seen
 * state for it" — having stood next to a patch is good evidence of being able to reach
 * it, so an existing account starts with a sensible set rather than a blank panel.
 */
@Slf4j
@Singleton
public class AvailabilityProfile extends ProfileJsonStore
{
	private static final String AVAILABILITY_KEY = "availability";

	private static final Type TOGGLE_MAP_TYPE = new TypeToken<HashMap<String, Boolean>>()
	{
	}.getType();

	private final PatchStateStore stateStore;

	/**
	 * The account's Farming level, for the Farming Guild's tier doors.
	 *
	 * <p>A supplier rather than the store that owns it. {@code SeedInventoryStore} is a leaf and
	 * this class sits above {@code PatchStateStore} in the lock order; taking a reference to
	 * another store would add an edge to a graph that is deliberately kept a line. Reading one
	 * int through a lambda keeps this a leaf-plus-a-number. See {@code docs/NOTES.md} on lock
	 * ordering.
	 */
	private java.util.function.IntSupplier farmingLevel = () -> 0;

	/** Told where to read the Farming level from, once the store that knows it exists. */
	public void setFarmingLevel(java.util.function.IntSupplier farmingLevel)
	{
		this.farmingLevel = farmingLevel;
	}

	/** Explicit player choices only; absent means "fall back to whether we've seen it". */
	private final Map<String, Boolean> toggles = new HashMap<>();

	private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

	@Inject
	AvailabilityProfile(ConfigManager configManager, Gson gson, PatchStateStore stateStore)
	{
		super(configManager, gson, AVAILABILITY_KEY);
		this.stateStore = stateStore;
	}

	public void addChangeListener(Runnable listener)
	{
		changeListeners.add(listener);
	}

	public void removeChangeListener(Runnable listener)
	{
		changeListeners.remove(listener);
	}

	/**
	 * Whether this patch exists for this account.
	 *
	 * <p>The level gate comes first and cannot be overridden. Everything else here is the
	 * player's own choice, but a Farming Guild tier is a locked door: a level-50 account cannot
	 * visit the redwood patch however firmly it ticks the box, and letting it try would route
	 * someone to a wall. See {@link PatchRequirements}.
	 */
	public synchronized boolean isAvailable(FarmPatch patch)
	{
		if (!PatchRequirements.isReachable(patch, farmingLevel.getAsInt()))
		{
			return false;
		}

		Boolean explicit = toggles.get(patch.getKey());
		if (explicit != null)
		{
			return explicit;
		}
		// hasSeen rather than get() != null: this runs per patch on every refresh, and get()
		// copies the snapshot to hand it out safely. Asking whether one exists needs no copy.
		return stateStore.hasSeen(patch);
	}

	/** Whether the player has made an explicit choice, as opposed to us inferring one. */
	public synchronized boolean isExplicitlySet(FarmPatch patch)
	{
		return toggles.containsKey(patch.getKey());
	}

	public void setAvailable(FarmPatch patch, boolean available)
	{
		synchronized (this)
		{
			toggles.put(patch.getKey(), available);
			save();
		}
		fireChanged();
	}


	public void setAllAvailable(boolean available)
	{
		synchronized (this)
		{
			for (FarmPatch patch : FarmingWorldData.getAllPatches())
			{
				toggles.put(patch.getKey(), available);
			}
			save();
		}
		fireChanged();
	}

	public void setTypeAvailable(PatchImplementation type, boolean available)
	{
		synchronized (this)
		{
			for (FarmPatch patch : FarmingWorldData.getPatches(type))
			{
				toggles.put(patch.getKey(), available);
			}
			save();
		}
		fireChanged();
	}

	/** The patches of one type this account uses, in world order. */
	public synchronized List<FarmPatch> getAvailablePatches(PatchImplementation type)
	{
		List<FarmPatch> result = new ArrayList<>();
		for (FarmPatch patch : FarmingWorldData.getPatches(type))
		{
			if (isAvailable(patch))
			{
				result.add(patch);
			}
		}
		return result;
	}

	public synchronized List<FarmPatch> getAllAvailablePatches()
	{
		List<FarmPatch> result = new ArrayList<>();
		for (FarmPatch patch : FarmingWorldData.getAllPatches())
		{
			if (isAvailable(patch))
			{
				result.add(patch);
			}
		}
		return result;
	}

	/** Forgets every explicit on/off, falling back to "show what we have seen". */
	public void clear()
	{
		synchronized (this)
		{
			toggles.clear();
			unsetStored();
		}
		fireChanged();
	}

	@Override
	protected void resetForLoad()
	{
		toggles.clear();
	}

	@Override
	protected void applyJson(String json)
	{
		Map<String, Boolean> loaded = gson.fromJson(json, TOGGLE_MAP_TYPE);
		if (loaded != null)
		{
			loaded.forEach((key, value) ->
			{
				if (value != null && FarmingWorldData.getPatch(key) != null)
				{
					toggles.put(key, value);
				}
			});
		}
	}

	@Override
	protected Object serialized()
	{
		return toggles;
	}

	@Override
	protected void loaded()
	{
		fireChanged();
	}

	/**
	 * Notifies listeners. Called outside this profile's monitor: listeners read back
	 * through both stores, and holding a lock across that invites a deadlock.
	 */
	private void fireChanged()
	{
		for (Runnable listener : changeListeners)
		{
			listener.run();
		}
	}
}
