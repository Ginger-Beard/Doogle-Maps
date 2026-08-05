package com.dooglemaps.state;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Seed;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * The seeds you have picked for your run.
 *
 * <p>Chosen by clicking them in the seed grid, so the choice is made against what you can
 * actually see yourself owning rather than typed into a form. Persisted per profile: a run
 * plan is not something to rebuild every time RuneLite restarts.
 *
 * <p>More than one seed per patch type is allowed on purpose. Ten herb patches and only
 * four ranarr seeds is an ordinary situation, and the answer is to take toadflax as well —
 * a single-choice model would make that impossible to express.
 */
@Slf4j
@Singleton
public class SeedSelectionStore
{
	private static final String SELECTION_KEY = "runSeeds";

	private static final Type NAME_LIST_TYPE = new TypeToken<ArrayList<String>>()
	{
	}.getType();

	private final ConfigManager configManager;
	private final Gson gson;

	private final Set<Seed> selected = new LinkedHashSet<>();
	private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

	@Inject
	private SeedSelectionStore(ConfigManager configManager, Gson gson)
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

	public synchronized boolean isSelected(Seed seed)
	{
		return selected.contains(seed);
	}

	public synchronized Set<Seed> getSelected()
	{
		return new LinkedHashSet<>(selected);
	}

	/** The seeds picked for one kind of patch, in the order they were picked. */
	public synchronized Set<Seed> getSelectedFor(PatchImplementation type)
	{
		Set<Seed> forType = new LinkedHashSet<>();
		for (Seed seed : selected)
		{
			if (seed.getPatchType() == type)
			{
				forType.add(seed);
			}
		}
		return forType;
	}

	public synchronized boolean hasAnySelection()
	{
		return !selected.isEmpty();
	}

	/** Adds or removes a seed. Returns whether it is now selected. */
	public boolean toggle(Seed seed)
	{
		boolean nowSelected;
		synchronized (this)
		{
			if (!selected.remove(seed))
			{
				selected.add(seed);
				nowSelected = true;
			}
			else
			{
				nowSelected = false;
			}
			save();
		}

		log.debug("{} {} for the run", nowSelected ? "Selected" : "Deselected", seed);
		fireChanged();
		return nowSelected;
	}

	public void clear()
	{
		synchronized (this)
		{
			if (selected.isEmpty())
			{
				return;
			}
			selected.clear();
			save();
		}
		fireChanged();
	}

	public void load()
	{
		synchronized (this)
		{
			selected.clear();

			String json = configManager.getRSProfileConfiguration(DoogleMapsConfig.GROUP, SELECTION_KEY);
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
							selected.add(Seed.valueOf(name));
						}
						catch (IllegalArgumentException e)
						{
							// A seed that no longer exists under that name; drop it.
						}
					}
				}
			}
			catch (JsonSyntaxException e)
			{
				log.warn("Discarding unreadable seed selection", e);
			}
		}
		fireChanged();
	}

	private synchronized void save()
	{
		List<String> names = new ArrayList<>();
		for (Seed seed : selected)
		{
			names.add(seed.name());
		}
		configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, SELECTION_KEY, gson.toJson(names));
	}

	private void fireChanged()
	{
		for (Runnable listener : changeListeners)
		{
			listener.run();
		}
	}
}
