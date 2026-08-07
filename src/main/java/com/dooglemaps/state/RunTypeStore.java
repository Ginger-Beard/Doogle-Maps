package com.dooglemaps.state;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.RunOption;
import javax.annotation.Nullable;
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

	/**
	 * Selected option keys rather than patch types.
	 *
	 * <p>Keys because a line in that list is no longer just a type: it can be a planting group —
	 * protected herbs — or a mode, such as harvesting a bush without replanting it. A key that
	 * happens to be a bare enum name is a full run over an unsplit type, which is exactly what
	 * was stored before, so an existing profile loads unchanged.
	 */
	private final Set<String> selected = new java.util.LinkedHashSet<>();

	@Inject
	private RunTypeStore(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	/**
	 * The patch types the run covers, for everything that still works in types.
	 *
	 * <p>Derived rather than stored: several selections can share a type — protected herbs and
	 * ordinary ones both being herbs — and the planner's job is to visit the patches, which is a
	 * question about types plus filters rather than about the lines a player ticked.
	 */
	public synchronized Set<PatchImplementation> getSelected()
	{
		Set<PatchImplementation> types = EnumSet.noneOf(PatchImplementation.class);
		for (String key : selected)
		{
			PatchImplementation type = typeOf(key);
			if (type != null)
			{
				types.add(type);
			}
		}
		return types;
	}

	public synchronized boolean isSelected(RunOption option)
	{
		return selected.contains(option.getKey());
	}

	/**
	 * Whether this group is being run for its harvest alone.
	 *
	 * <p>True only when the harvest-only line is ticked and the full one is not. Ticking both is
	 * a contradiction the player can express, and the full run is the safe reading of it: it does
	 * everything the harvest-only run does and more, so nothing is skipped that was asked for.
	 */
	public synchronized boolean isHarvestOnly(PlantingGroup group)
	{
		return selected.contains(RunOption.harvestOnly(group).getKey())
			&& !selected.contains(RunOption.full(group).getKey());
	}

	/**
	 * The patch type a stored key belongs to, or null if it names one this build has lost.
	 *
	 * <h2>Everything before the first {@code #}, rather than a list of known suffixes</h2>
	 *
	 * This stripped {@code "#harvest"} and {@code "#protected"} by name, and a key scheme with a
	 * hand-maintained parser is a key scheme with one suffix missing. It was {@code "#contract"}:
	 * a ticked farming contract stored as {@code CACTUS#contract}, failed to parse, and was
	 * <b>dropped on load</b> — so the box came back unticked on every restart, which is how this
	 * was reported.
	 *
	 * <p>The quieter half was worse. {@link #getSelected()} maps keys to types through here too, so
	 * a contract was contributing no patch type at all: with only the contract ticked, the run
	 * covered nothing and would have planned an empty trip. That failed silently, in the same
	 * session, with nothing to suggest the tick had not registered.
	 *
	 * <p>Splitting on the marker cannot fall behind, and a fourth scope needs no change here.
	 * {@code SeedSelectionStore} was already parsing its own keys this way, which is the version
	 * that should have been copied. Nested suffixes are handled by taking the <i>first</i> marker —
	 * a harvest-only contract would be {@code CACTUS#contract#harvest}, and the type is still
	 * {@code CACTUS}.
	 */
	@Nullable
	private static PatchImplementation typeOf(String key)
	{
		int marker = key.indexOf('#');
		String name = marker < 0 ? key : key.substring(0, marker);

		try
		{
			return PatchImplementation.valueOf(name);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	/** Replaces the whole selection, which is how the checkboxes report themselves. */
	public void setSelected(Set<RunOption> options)
	{
		replace(keysOf(options));
	}

	/**
	 * Replaces the selection <b>within the lines currently on offer</b>, leaving the rest alone.
	 *
	 * <p>Which lines exist is not fixed: protected herbs appear and disappear with a setting. The
	 * checkboxes can only report on what they are showing, so replacing the whole selection from
	 * them silently discards anything not currently listed — turn the split off, touch any box,
	 * and the protected herb run you had chosen is gone for good, including when you turn it back
	 * on.
	 *
	 * <p>Keeping the unoffered keys is safe because nothing acts on a line the player cannot see:
	 * with the split off, protected patches are part of the ordinary herb group anyway, so the
	 * stored key contributes no patches until it is on offer again.
	 */
	public void setSelected(Set<RunOption> ticked, java.util.Collection<RunOption> offered)
	{
		Set<String> offeredKeys = keysOf(offered);
		Set<String> keys = new java.util.LinkedHashSet<>();

		synchronized (this)
		{
			for (String key : selected)
			{
				if (!offeredKeys.contains(key))
				{
					keys.add(key);
				}
			}
		}
		keys.addAll(keysOf(ticked));
		replace(keys);
	}

	private static Set<String> keysOf(java.util.Collection<RunOption> options)
	{
		Set<String> keys = new java.util.LinkedHashSet<>();
		for (RunOption option : options)
		{
			keys.add(option.getKey());
		}
		return keys;
	}

	private void replace(Set<String> keys)
	{
		synchronized (this)
		{
			if (selected.equals(keys))
			{
				return;
			}
			selected.clear();
			selected.addAll(keys);
			save();
		}
		log.debug("Run covers {}", keys);
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
						// Kept whatever it says, provided it still names a type this build knows.
						// Unrecognised keys are dropped rather than throwing: a selection saved by
						// a newer build must not stop an older one loading the rest.
						if (typeOf(name) != null)
						{
							selected.add(name);
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
		configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, TYPES_KEY,
			gson.toJson(new ArrayList<>(selected)));
	}
}
