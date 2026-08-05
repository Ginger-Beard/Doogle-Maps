package com.dooglemaps.state;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.PatchImplementation;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * What you intend to treat each kind of patch with.
 *
 * <p>Compost cannot be read off a patch you have not planted yet, and it is the single biggest
 * lever on yield — ultra nearly doubles a herb patch. So rather than guessing, or pricing every
 * tier and leaving the player to find their row, the intent is stated once per patch type and
 * everything downstream uses it.
 *
 * <p>Per patch type rather than globally because that is how people actually farm: ultra on the
 * herbs that are worth it, nothing on the hops.
 */
@Slf4j
@Singleton
public class CompostSelectionStore
{
	private static final String COMPOST_KEY = "runCompost";

	private static final Type NAME_MAP_TYPE = new TypeToken<HashMap<String, String>>()
	{
	}.getType();

	/**
	 * What a patch type is treated with until the player says otherwise.
	 *
	 * <p>Ultracompost, because anyone running a plugin to plan farm runs is almost certainly
	 * using it, and a default of "none" would understate every estimate by half.
	 */
	private static final CompostTier DEFAULT = CompostTier.ULTRACOMPOST;

	private final ConfigManager configManager;
	private final Gson gson;

	/**
	 * Per-group choices, for groups that have been set explicitly.
	 *
	 * <p>Sparse on purpose: anything absent falls back to the type's choice, which is what makes
	 * the protected split reversible without losing a setting.
	 */
	private final Map<String, CompostTier> byGroup = new java.util.LinkedHashMap<>();

	private final Map<PatchImplementation, CompostTier> chosen =
		new EnumMap<>(PatchImplementation.class);

	private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

	@Inject
	private CompostSelectionStore(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
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
	 * The compost chosen for one planting group.
	 *
	 * <p>Falls back to the type's choice for any group never set, so splitting protected herbs out
	 * starts both tabs from what was already chosen rather than resetting one to untreated. That
	 * matters more here than for seeds: an unnoticed reset to NONE is a whole run planted
	 * untreated.
	 */
	public synchronized CompostTier get(PlantingGroup group)
	{
		CompostTier tier = byGroup.get(group.getKey());
		return tier != null ? tier : get(group.getType());
	}

	public void set(PlantingGroup group, CompostTier tier)
	{
		synchronized (this)
		{
			byGroup.put(group.getKey(), tier);
			// The type-wide choice tracks the unsplit group, so anything still asking by type —
			// and turning the split back off — sees the answer the player last gave for "all of
			// them" rather than one they gave for a subset.
			if (!group.isProtectedOnly())
			{
				set(group.getType(), tier);
				return;
			}
			save();
		}

		log.debug("{} will be treated with {}", group.getKey(), tier);
		for (Runnable listener : changeListeners)
		{
			listener.run();
		}
	}

	/**
	 * The compost this patch type will be treated with.
	 *
	 * <p>NONE for a type where treating it changes nothing the plugin can tell you — no lives
	 * mechanic and no published disease rate. Those have no dropdown, so any stored value is one
	 * the player can no longer see or alter, and the default is ultracompost: left alone it would
	 * have the estimate quietly assuming a treatment nobody chose. Everywhere the dropdown <i>is</i>
	 * offered the choice is honoured in full, including the types where it buys survival rather
	 * than yield. See {@code CropYieldModel.compostMatters}.
	 */
	public synchronized CompostTier get(PatchImplementation type)
	{
		return com.dooglemaps.timer.CropYieldModel.compostMatters(type)
			? chosen.getOrDefault(type, DEFAULT)
			: CompostTier.NONE;
	}

	/** What every selected type is treated with, for pricing a whole run. */
	public synchronized Map<PatchImplementation, CompostTier> getAll()
	{
		Map<PatchImplementation, CompostTier> all = new EnumMap<>(PatchImplementation.class);
		for (PatchImplementation type : PatchImplementation.values())
		{
			all.put(type, get(type));
		}
		return all;
	}

	public void set(PatchImplementation type, CompostTier tier)
	{
		synchronized (this)
		{
			// Compared against what is actually stored, not against the defaulted answer.
			// Otherwise picking the tier that happens to be the default writes nothing, and
			// the choice would silently move if the default ever changed.
			if (chosen.get(type) == tier)
			{
				return;
			}
			chosen.put(type, tier);
			save();
		}

		log.debug("{} will be treated with {}", type, tier);
		for (Runnable listener : changeListeners)
		{
			listener.run();
		}
	}

	public void load()
	{
		synchronized (this)
		{
			chosen.clear();
			byGroup.clear();

			String json = configManager.getRSProfileConfiguration(DoogleMapsConfig.GROUP, COMPOST_KEY);
			if (json == null || json.isEmpty())
			{
				return;
			}

			try
			{
				Map<String, String> loaded = gson.fromJson(json, NAME_MAP_TYPE);
				if (loaded != null)
				{
					loaded.forEach((key, tier) ->
					{
						CompostTier parsed;
						try
						{
							parsed = CompostTier.valueOf(tier);
						}
						catch (IllegalArgumentException e)
						{
							// A tier that no longer exists; drop it.
							return;
						}

						// A bare enum name is a patch type; anything else is a planting group.
						// Same rule as save, and it is what lets a file written before groups
						// existed load unchanged.
						try
						{
							chosen.put(PatchImplementation.valueOf(key), parsed);
						}
						catch (IllegalArgumentException e)
						{
							byGroup.put(key, parsed);
						}
					});
				}
			}
			catch (JsonSyntaxException e)
			{
				log.warn("Discarding unreadable compost selection", e);
			}
		}
	}

	/**
	 * Writes both maps, keyed the same way they are read back.
	 *
	 * <h2>The group choices used to be written nowhere</h2>
	 *
	 * This serialised {@link #chosen} alone, so a compost picked on the protected herb tab lived
	 * in memory and was gone at the next login — silently, because the fallback is to the type's
	 * choice and that always answers something. The protected patches then quietly used whatever
	 * the ordinary ones were set to, and the estimate below the run went with it. Nothing looked
	 * broken; the numbers were just for a compost you had not chosen.
	 *
	 * <p>One map rather than two config keys, because the two are read as one question and a
	 * half-written pair is worse than either. A bare enum name is a type and anything else is a
	 * group key, which is the same rule {@code load} applies — and it means everything written by
	 * an older build still reads correctly.
	 */
	private synchronized void save()
	{
		Map<String, String> names = new HashMap<>();
		chosen.forEach((type, tier) -> names.put(type.name(), tier.name()));
		byGroup.forEach((key, tier) -> names.put(key, tier.name()));
		configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, COMPOST_KEY, gson.toJson(names));
	}
}
