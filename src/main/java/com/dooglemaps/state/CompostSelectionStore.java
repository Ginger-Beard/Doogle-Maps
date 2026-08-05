package com.dooglemaps.state;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.CompostTier;
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

	public synchronized CompostTier get(PatchImplementation type)
	{
		return chosen.getOrDefault(type, DEFAULT);
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
					loaded.forEach((type, tier) ->
					{
						try
						{
							chosen.put(PatchImplementation.valueOf(type), CompostTier.valueOf(tier));
						}
						catch (IllegalArgumentException e)
						{
							// A type or tier that no longer exists; drop it.
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

	private synchronized void save()
	{
		Map<String, String> names = new HashMap<>();
		chosen.forEach((type, tier) -> names.put(type.name(), tier.name()));
		configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, COMPOST_KEY, gson.toJson(names));
	}
}
