package com.dooglemaps.state;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Seed;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.EnumMap;
import java.util.EnumSet;
import com.dooglemaps.data.PlantingGroup;
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

	/**
	 * Per-group picks, stored beside the flat list rather than replacing it.
	 *
	 * <p>Two keys because they answer two questions and one cannot be derived from the other. The
	 * flat list is "is this seed in the run at all", which the loadout and the inventory plan
	 * want; this is "which group was it picked for", which only exists once something is split.
	 * Keeping the old key untouched also means an older build reads this profile and behaves
	 * exactly as it used to.
	 */
	private static final String GROUP_SELECTION_KEY = "runSeedsByGroup";

	private static final Type GROUP_MAP_TYPE =
		new TypeToken<LinkedHashMap<String, ArrayList<String>>>()
		{
		}.getType();

	private static final Type NAME_LIST_TYPE = new TypeToken<ArrayList<String>>()
	{
	}.getType();

	private final ConfigManager configManager;
	private final Gson gson;
	private final ContractState contracts;

	private final Set<Seed> selected = new LinkedHashSet<>();

	/**
	 * Picks made per planting group, keyed by {@link PlantingGroup#getKey()}.
	 *
	 * <p>Only holds groups that have actually been touched. Everything else falls back to the
	 * type-wide set, which is what keeps an existing profile working unchanged and makes turning
	 * the protected-herb split on and off a reversible, non-destructive act.
	 */
	private final Map<String, Set<Seed>> byGroup = new LinkedHashMap<>();

	/** The type-wide selection as it was loaded, for the fallback above. */
	private final Map<PatchImplementation, Set<Seed>> typeWide = new EnumMap<>(PatchImplementation.class);
	private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

	@Inject
	SeedSelectionStore(ConfigManager configManager, Gson gson, ContractState contracts)
	{
		this.configManager = configManager;
		this.gson = gson;
		this.contracts = contracts;
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

	/**
	 * The seeds picked for one planting group.
	 *
	 * <p>A seed knows only its patch <i>type</i>, so a group's selection cannot be derived by
	 * filtering the way {@link #getSelectedFor(PatchImplementation)} does — protected herbs and
	 * ordinary herbs would both return every herb seed. Splitting them means storing which group
	 * each pick was made in, which is what {@link #byGroup} is for.
	 *
	 * <p>Falls back to the type-wide selection for any group nothing has ever been picked in.
	 * That is what makes turning the split on non-destructive: a player who had chosen ranarr for
	 * herbs sees ranarr in both lists rather than two empty ones, and changes whichever they mean.
	 */
	public synchronized Set<Seed> getSelectedFor(PlantingGroup group)
	{
		if (group.isContract())
		{
			return contractSelection();
		}

		Set<Seed> picked = byGroup.get(group.getKey());
		if (picked == null)
		{
			return getSelectedFor(group.getType());
		}
		return new LinkedHashSet<>(picked);
	}

	/**
	 * The one seed a contract group can take, derived rather than stored.
	 *
	 * <p>The group's type narrows the list to that patch's seeds; the contract narrows it to
	 * exactly the crop asked for. So there is one answer, and the player never made it — which is
	 * the reason it must not be written into {@link #byGroup} on their behalf:
	 *
	 * <ul>
	 *   <li>a selection nobody made would be persisted, and would be stale the moment the contract
	 *       changed or was handed in; and
	 *   <li>it is the same rule {@code SeedAllocation} already follows — recomputed rather than
	 *       remembered — and the two must not end up disagreeing about what is going in that patch.
	 * </ul>
	 *
	 * <p>Empty when the contract has no plantable seed, which leaves the tab honestly blank rather
	 * than offering something that cannot be sown.
	 */
	private Set<Seed> contractSelection()
	{
		Set<Seed> picked = new LinkedHashSet<>();
		Seed seed = contracts.getContractSeed();
		if (seed != null)
		{
			picked.add(seed);
		}
		return picked;
	}

	/** Whether a seed is picked for a particular group. */
	public synchronized boolean isSelected(PlantingGroup group, Seed seed)
	{
		return getSelectedFor(group).contains(seed);
	}

	/**
	 * Adds or removes a seed within one group. Returns whether it is now selected.
	 *
	 * <p>The first toggle in a group copies the type-wide selection into it, so the group starts
	 * from what was already showing rather than from nothing — otherwise the first click in a
	 * freshly split tab would silently discard every other seed the player had chosen.
	 */
	public boolean toggle(PlantingGroup group, Seed seed)
	{
		if (group.isContract())
		{
			// Nothing to toggle: the contract names the crop, and the seed shown is picked
			// because it *is* picked. Answering with whether it is the contract's seed keeps this
			// consistent with what getSelectedFor would say, so a caller that toggles and then
			// re-reads is not told two different things.
			return contractSelection().contains(seed);
		}

		boolean nowSelected;
		synchronized (this)
		{
			// The type's answer as it stands *before* anything is written, which is what both
			// groups inherit. Read first because the writes below would otherwise feed back into
			// it: the flat set is the union of the groups, so seeding a group from it after
			// editing a sibling would copy the sibling's pick straight back.
			Set<Seed> before = getSelectedFor(group.getType());

			// Materialise both sides, not just the one being edited. A split turns one list into
			// two independent ones, and that has to happen on the first edit to either — leaving
			// the untouched side on the fallback means it silently mirrors whatever the other one
			// becomes.
			byGroup.computeIfAbsent(PlantingGroup.of(group.getType()).getKey(),
				key -> new LinkedHashSet<>(before));
			Set<Seed> picked = byGroup.computeIfAbsent(group.getKey(),
				key -> new LinkedHashSet<>(before));

			if (!picked.remove(seed))
			{
				picked.add(seed);
				nowSelected = true;
			}
			else
			{
				nowSelected = false;
			}

			// The flat set stays the union of every group, because everything that asks "is this
			// seed in the run at all" — the loadout, the inventory plan — wants that and not a
			// per-group answer.
			selected.clear();
			for (Set<Seed> seeds : byGroup.values())
			{
				selected.addAll(seeds);
			}
			for (PatchImplementation type : untouchedTypes())
			{
				selected.addAll(typeWide.getOrDefault(type, new LinkedHashSet<>()));
			}
			save();
		}

		log.debug("{} {} for {}", nowSelected ? "Selected" : "Deselected", seed, group.getKey());
		fireChanged();
		return nowSelected;
	}

	/** Snapshots the type-wide selection, so group fallbacks do not shift as groups are edited. */
	private synchronized void rememberTypeWide()
	{
		typeWide.clear();
		for (Seed seed : selected)
		{
			typeWide.computeIfAbsent(seed.getPatchType(), t -> new LinkedHashSet<>()).add(seed);
		}
	}

	/** Types that have never had a group-level pick, so their old flat selection still stands. */
	private Set<PatchImplementation> untouchedTypes()
	{
		Set<PatchImplementation> untouched = EnumSet.allOf(PatchImplementation.class);
		for (String key : byGroup.keySet())
		{
			String typeName = key.contains("#") ? key.substring(0, key.indexOf('#')) : key;
			try
			{
				untouched.remove(PatchImplementation.valueOf(typeName));
			}
			catch (IllegalArgumentException e)
			{
				// A key from a version that knew a type this one does not. Ignored rather than
				// thrown on: a stale key must never stop the rest of the selection loading.
				log.debug("Unknown planting group key {}", key);
			}
		}
		return untouched;
	}

	public synchronized boolean hasAnySelection()
	{
		return !selected.isEmpty();
	}

	/**
	 * Adds or removes a seed for its patch type as a whole.
	 *
	 * <p>Delegates to the group form so there is one code path rather than two that have to agree
	 * about the flat set. A type that has never been split has exactly one group, so this is the
	 * same operation it always was.
	 */
	public boolean toggle(Seed seed)
	{
		return toggle(PlantingGroup.of(seed.getPatchType()), seed);
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
			byGroup.clear();
			typeWide.clear();

			loadGroups();

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

			rememberTypeWide();
		}
		fireChanged();
	}

	/** Reads the per-group picks, tolerating anything unreadable rather than losing the rest. */
	private void loadGroups()
	{
		String json = configManager.getRSProfileConfiguration(
			DoogleMapsConfig.GROUP, GROUP_SELECTION_KEY);
		if (json == null || json.isEmpty())
		{
			return;
		}

		try
		{
			Map<String, ArrayList<String>> stored = gson.fromJson(json, GROUP_MAP_TYPE);
			if (stored == null)
			{
				return;
			}

			for (Map.Entry<String, ArrayList<String>> entry : stored.entrySet())
			{
				Set<Seed> seeds = new LinkedHashSet<>();
				for (String name : entry.getValue())
				{
					try
					{
						seeds.add(Seed.valueOf(name));
					}
					catch (IllegalArgumentException e)
					{
						// A seed renamed since this was written; drop just that one.
					}
				}
				byGroup.put(entry.getKey(), seeds);
			}
		}
		catch (JsonSyntaxException e)
		{
			log.warn("Discarding unreadable per-group seed selection", e);
		}
	}

	private synchronized void save()
	{
		List<String> names = new ArrayList<>();
		for (Seed seed : selected)
		{
			names.add(seed.name());
		}
		configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, SELECTION_KEY, gson.toJson(names));

		Map<String, List<String>> groups = new LinkedHashMap<>();
		for (Map.Entry<String, Set<Seed>> entry : byGroup.entrySet())
		{
			List<String> seedNames = new ArrayList<>();
			for (Seed seed : entry.getValue())
			{
				seedNames.add(seed.name());
			}
			groups.put(entry.getKey(), seedNames);
		}
		configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, GROUP_SELECTION_KEY,
			gson.toJson(groups));
	}

	private void fireChanged()
	{
		for (Runnable listener : changeListeners)
		{
			listener.run();
		}
	}
}
