package com.dooglemaps.state;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.PatchImplementation;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * The kinds of patch your run covers.
 *
 * <p>Persisted per profile, because it is a choice about how you farm rather than about this
 * particular run. Most people do the same circuit every time — herbs and allotments, or trees
 * on a slower rotation — and re-ticking the same boxes before every run is exactly the sort of
 * chore a farming plugin ought to be removing.
 *
 * <p>Kept apart from {@link SeedSelectionStore} deliberately. Seeds change constantly as stock
 * runs out; which patch types you bother with barely changes at all.
 */
@Slf4j
@Singleton
public class RunTypeStore
{
	private static final String TYPES_KEY = "runTypes";

	private static final Type NAME_LIST_TYPE = new TypeToken<ArrayList<String>>()
	{
	}.getType();

	private final ConfigManager configManager;
	private final Gson gson;

	private final Set<PatchImplementation> selected = EnumSet.noneOf(PatchImplementation.class);

	@Inject
	private RunTypeStore(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	public synchronized Set<PatchImplementation> getSelected()
	{
		// copyOf is safe on an empty EnumSet, unlike an empty ordinary collection.
		return EnumSet.copyOf(selected);
	}

	public synchronized boolean isSelected(PatchImplementation type)
	{
		return selected.contains(type);
	}

	/** Replaces the whole selection, which is how the checkboxes report themselves. */
	public void setSelected(Set<PatchImplementation> types)
	{
		synchronized (this)
		{
			if (selected.equals(types))
			{
				return;
			}
			selected.clear();
			selected.addAll(types);
			save();
		}
		log.debug("Run covers {}", types);
	}

	public void load()
	{
		synchronized (this)
		{
			selected.clear();

			String json = configManager.getRSProfileConfiguration(DoogleMapsConfig.GROUP, TYPES_KEY);
			if (json == null || json.isEmpty())
			{
				return;
			}

			try
			{
				List<String> names = gson.fromJson(json, NAME_LIST_TYPE);
				if (names != null)
				{
					for (String name : names)
					{
						try
						{
							selected.add(PatchImplementation.valueOf(name));
						}
						catch (IllegalArgumentException e)
						{
							// A patch type that no longer exists; drop it.
						}
					}
				}
			}
			catch (JsonSyntaxException e)
			{
				log.warn("Discarding unreadable run type selection", e);
			}
		}
	}

	private synchronized void save()
	{
		List<String> names = new ArrayList<>();
		for (PatchImplementation type : selected)
		{
			names.add(type.name());
		}
		configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, TYPES_KEY, gson.toJson(names));
	}
}
