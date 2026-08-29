package com.dooglemaps.state;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Named sets of run tickboxes, so a herb run and a tree run are one click apart.
 *
 * <p>{@link RunTypeStore} already removes the chore of re-ticking boxes for the circuit you did
 * last — it persists the last state. This removes it for the <i>other</i> circuits: a player has a
 * herb run, a tree run and a bins trip, and switching between them meant finding six or eight
 * lines in an alphabetical list that outgrew being read as a whole.
 *
 * <h2>Stored as option keys, which is why this class is small</h2>
 *
 * A preset is a name and a list of {@code RunOption} keys — the exact thing {@code RunTypeStore}
 * already persists, for the reason its own note gives: a line in that list can be a planting group
 * or a mode, not merely a patch type, so the key is the only thing that identifies it.
 *
 * <p>Keys rather than resolved options also mean a preset survives the offered list changing under
 * it. A key that no longer matches anything is simply not ticked when the preset is applied, the
 * same way {@code RunTypeStore} already ignores entries it does not recognise, and it is kept in
 * the stored preset rather than dropped — an option that vanished because a group is temporarily
 * unavailable should come back when it does.
 *
 * <h2>Run types only, deliberately</h2>
 *
 * Not seeds. {@code RunTypeStore}'s note draws the line and it is the right one: it is <i>"kept
 * apart from {@code SeedSelectionStore} deliberately. Seeds change constantly as stock runs out;
 * which patch types you bother with barely changes at all."</i> A preset carrying seed choices
 * would be stale within a week of being saved, which is worse than not having one.
 *
 * <p>Never availability, either. That is a global invariant with one home
 * ({@code AvailabilityProfile}); a preset that switched patches off would be a second source of
 * truth for it.
 */
@Slf4j
@Singleton
public class RunPresetStore extends ProfileJsonStore
{
	private static final String PRESETS_KEY = "runPresets";

	private static final Type PRESET_MAP_TYPE =
		new TypeToken<LinkedHashMap<String, ArrayList<String>>>()
		{
		}.getType();

	/**
	 * The key {@link #lastUsed} rides under, inside the same blob as the presets.
	 *
	 * <p>A reserved name rather than a second config key, so one write stores both and the two can
	 * never load out of step with each other. It cannot collide with a preset: {@link #save}
	 * refuses it by name, and a stored blob carrying it as a preset is dropped on load.
	 */
	private static final String LAST_USED_KEY = "\u0000lastUsed";

	/** Longer than any sensible name, short enough that the dropdown stays readable. */
	private static final int MAX_NAME = 40;

	/** Insertion-ordered so the dropdown lists them the way they were made. */
	private final Map<String, List<String>> presets = new LinkedHashMap<>();

	/**
	 * The preset last applied or saved, so the dropdown opens on it next session.
	 *
	 * <h2>Why remembering the name is not the same as remembering the ticks</h2>
	 *
	 * The ticks already persist on their own, through {@code RunTypeStore} — so a restart brings
	 * back the right run either way, and this changes nothing about what a run does. What it
	 * changes is what the control <i>says</i>: without it the dropdown re-derives its label from
	 * whichever saved preset happens to match the ticks first, so two presets covering the same
	 * lines make the name flip between sessions for no reason the player did.
	 *
	 * <p>Still only a preference, never an authority. It is offered to the panel and used only
	 * while it still matches the ticks — see {@link #matches} — because the ticks are the truth
	 * and this is a label for them. A preset edited away from shows {@code (unsaved)} exactly as
	 * before.
	 */
	@Nullable
	private String lastUsed;

	@Inject
	RunPresetStore(ConfigManager configManager, Gson gson)
	{
		super(configManager, gson, PRESETS_KEY);
	}

	/** Every preset name, in the order they were saved. */
	public synchronized List<String> names()
	{
		return new ArrayList<>(presets.keySet());
	}

	/** The option keys a preset ticks, or null when there is no such preset. */
	@Nullable
	public synchronized Set<String> keysOf(String name)
	{
		List<String> keys = presets.get(name);
		return keys == null ? null : new LinkedHashSet<>(keys);
	}

	/**
	 * Whether a set of option keys is exactly what this preset holds.
	 *
	 * <p>What the panel asks to decide whether the dropdown may still claim a name. Order is not
	 * compared — the tickboxes are a set, and two presets differing only in the order they were
	 * ticked are the same run.
	 */
	public synchronized boolean matches(String name, Set<String> keys)
	{
		List<String> stored = presets.get(name);
		return stored != null && new LinkedHashSet<>(stored).equals(keys);
	}

	/**
	 * Saves these option keys under this name, replacing any preset already called that.
	 *
	 * <p>Overwriting rather than refusing is the behaviour an editable dropdown implies: typing a
	 * new name makes one, pressing Save with an existing name selected updates it, and the player
	 * never has to delete something first to change it.
	 *
	 * @return the name as stored, trimmed, or null when it was blank or the keys were empty
	 */
	@Nullable
	public String save(String name, Set<String> keys)
	{
		String trimmed = name == null ? "" : name.trim();
		if (trimmed.isEmpty() || keys == null || keys.isEmpty())
		{
			// A nameless preset cannot be selected again, and an empty one is a run that visits
			// nothing. Neither is worth storing, and neither is worth an error either.
			return null;
		}
		if (trimmed.length() > MAX_NAME)
		{
			trimmed = trimmed.substring(0, MAX_NAME);
		}

		if (LAST_USED_KEY.equals(trimmed))
		{
			// The reserved name. Unreachable by typing — it starts with a NUL — but refused here
			// rather than trusted not to arrive.
			return null;
		}

		synchronized (this)
		{
			presets.put(trimmed, new ArrayList<>(keys));
		}
		save();
		log.debug("Saved run preset {} with {} options", trimmed, keys.size());
		return trimmed;
	}

	/** Forgets a preset. Silent when there was none by that name. */
	public void delete(String name)
	{
		boolean removed;
		synchronized (this)
		{
			removed = presets.remove(name) != null;
			if (removed && java.util.Objects.equals(lastUsed, name))
			{
				// Forgotten with it, rather than left pointing at something that no longer
				// exists. Harmless either way — matches() answers false for an unknown name — but
				// a stale pointer surviving a delete is the sort of thing that is only harmless
				// until something else starts trusting it.
				lastUsed = null;
			}
		}
		if (removed)
		{
			save();
			log.debug("Deleted run preset {}", name);
		}
	}

	/**
	 * Points any stored contract tick, in every preset, at the type the contract now wants.
	 *
	 * <p>The other half of {@code RunTypeStore.retargetContract}, and it was missing: the live
	 * selection's {@code CACTUS#contract} key was renamed the moment Jane assigned a herb, but a
	 * preset saved during the cactus contract kept the cactus key forever. Applying it then
	 * ticked nothing — the only contract line on offer was the herb one — so the contract
	 * quietly left the run every time a profile was applied after a hand-in, and the dropdown
	 * flipped to {@code (unsaved)} because the renamed live keys no longer matched the stored
	 * ones exactly. Reported from play as "the farming contract unchecks itself after doing it,
	 * messing with my profiles".
	 *
	 * <p>Renamed in lockstep with the live store, by the same rule and for the same reason that
	 * store's note gives: the tick means "do the contract", not "do cactus contracts", and
	 * renaming at write time keeps {@code matches} an exact comparison with no fuzzy family
	 * matching at read time. Merges duplicates the same way — two stale contract keys in one
	 * preset collapse into the one that is live.
	 */
	public void retargetContract(@Nullable com.dooglemaps.data.PatchImplementation type)
	{
		if (type == null)
		{
			return;
		}

		boolean changed = false;
		synchronized (this)
		{
			for (Map.Entry<String, List<String>> preset : presets.entrySet())
			{
				Set<String> renamed = new LinkedHashSet<>();
				for (String key : preset.getValue())
				{
					int marker = key.indexOf("#contract");
					renamed.add(marker < 0 ? key : type.name() + key.substring(marker));
				}
				if (!renamed.equals(new LinkedHashSet<>(preset.getValue())))
				{
					preset.setValue(new ArrayList<>(renamed));
					changed = true;
				}
			}
		}
		if (changed)
		{
			// Outside the monitor: the save fires ConfigChanged into arbitrary subscribers.
			// See ProfileJsonStore.save.
			save();
			log.debug("Retargeted preset contract ticks to {}", type);
		}
	}

	/** The preset last applied or saved, or null. Only a preference; see the field note. */
	@Nullable
	public synchronized String lastUsed()
	{
		return lastUsed;
	}

	/** Remembers which preset the player is on. Silent when it names nothing stored. */
	public void setLastUsed(@Nullable String name)
	{
		synchronized (this)
		{
			if (name != null && !presets.containsKey(name))
			{
				return;
			}
			if (java.util.Objects.equals(lastUsed, name))
			{
				// Applying the same preset twice must not write config twice. This is called from
				// a click, and a click that saves nothing new should cost nothing.
				return;
			}
			lastUsed = name;
		}
		save();
	}

	@Override
	protected void resetForLoad()
	{
		presets.clear();
		lastUsed = null;
	}

	@Override
	protected void applyJson(String json)
	{
		Map<String, List<String>> loaded = gson.fromJson(json, PRESET_MAP_TYPE);
		if (loaded == null)
		{
			return;
		}
		List<String> remembered = loaded.remove(LAST_USED_KEY);
		loaded.forEach((name, keys) ->
		{
			// Stored data survives version changes, so nothing about its shape is guaranteed —
			// the base class says so and every store here validates on the way in.
			if (name == null || name.trim().isEmpty() || keys == null || keys.isEmpty())
			{
				return;
			}
			List<String> clean = new ArrayList<>();
			for (String key : keys)
			{
				if (key != null && !key.trim().isEmpty())
				{
					clean.add(key);
				}
			}
			if (!clean.isEmpty())
			{
				presets.put(name, clean);
			}
		});

		// After the presets, so a remembered name that no longer names one is simply forgotten
		// rather than resurrecting a deleted preset in the dropdown.
		if (remembered != null && !remembered.isEmpty()
			&& presets.containsKey(remembered.get(0)))
		{
			lastUsed = remembered.get(0);
		}
	}

	@Override
	protected synchronized Object serialized()
	{
		// Under the monitor even though save() is deliberately called outside it — the copy
		// below reads the map, and every other store here snapshots its state inside its lock
		// before the write goes out. Writers are EDT-only today, so this buys consistency with
		// the pattern rather than fixing a live race.
		LinkedHashMap<String, List<String>> out = new LinkedHashMap<>(presets);
		if (lastUsed != null)
		{
			out.put(LAST_USED_KEY, java.util.Collections.singletonList(lastUsed));
		}
		return out;
	}
}
