package com.dooglemaps.state;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.PlantingGroup;
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
 * Which planting groups the player intends to pay a farmer to protect.
 *
 * <p>A choice rather than a detection, and it has to be: protection is bought <i>during</i> the
 * run, so at the moment a run is priced nothing has been paid and there is no state to read. The
 * plugin previously assumed the answer was no everywhere — {@code survivalAcross} passes
 * {@code protectedByFarmer = false} with a comment saying that assuming otherwise "would quietly
 * cancel the whole disease discount for every protectable crop".
 *
 * <p>That was the right default and the wrong end state. Anyone growing magic trees pays the
 * farmer every time, and for them the estimate was discounting a loss that cannot happen — the
 * mirror of the bug this fixes, and it understated a magic tree run by whatever the death rate
 * is. Asking once per group is the smallest thing that makes both answers available.
 *
 * <h2>Per group, like the seeds and the compost</h2>
 *
 * Because it is the same kind of decision and belongs beside them. It also means protected herbs
 * can answer differently from ordinary ones — which is the honest answer, since paying to protect
 * a patch that cannot get diseased is money for nothing.
 */
@Slf4j
@Singleton
public class ProtectionSelectionStore
{
	private static final String KEY = "runProtection";

	/**
	 * Keys the player has explicitly turned <b>off</b>.
	 *
	 * <p>Stored separately rather than as a value beside the on ones, because it exists only to
	 * tell "off" apart from "never asked" — and a second key leaves everything already saved
	 * readable by a build that has never heard of it. Absence from {@link #protecting} used to
	 * mean both things at once, which is what made the fallback below impossible to add.
	 */
	private static final String OFF_KEY = "runProtectionOff";

	private static final Type NAME_LIST_TYPE = new TypeToken<ArrayList<String>>()
	{
	}.getType();

	private final ConfigManager configManager;
	private final Gson gson;

	/** Group keys the player has said they will pay for. */
	private final Set<String> protecting = new LinkedHashSet<>();

	/** Group keys the player has said they will <i>not</i> pay for. See {@link #OFF_KEY}. */
	private final Set<String> notProtecting = new LinkedHashSet<>();
	private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

	@Inject
	ProtectionSelectionStore(ConfigManager configManager, Gson gson)
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
	 * Keyed by group <b>and</b> seed.
	 *
	 * <p>The seed half is what makes the flag meaningful: protection is a property of the crop —
	 * a magic tree costs 25 coconuts and a yew costs 10 cactus spines — so "protect this patch
	 * type" cannot be answered without knowing what is going in it. It is also what keeps the
	 * flag away from crops that have no payment at all: a herb has none, so the question is never
	 * asked and never stored.
	 *
	 * <p>The group half matters because the same seed can belong to two groups with different
	 * answers. Paying to protect a patch that cannot catch a disease is money for nothing, so
	 * protected herbs and ordinary ones must be able to disagree — and by extension so must any
	 * future split.
	 */
	private static String keyFor(PlantingGroup group, Seed seed)
	{
		return group.getKey() + "|" + seed.name();
	}

	/**
	 * Whether this crop will be paid for in this group.
	 *
	 * <h2>A split group inherits its type's answer until it is given one of its own</h2>
	 *
	 * The same fallback {@code CompostSelectionStore} has, and it is here for the same reason:
	 * splitting a type into groups must not silently reset one of them. It was missing, and the
	 * farming contract is where that first cost something visible — a cactus contract arrives as
	 * a brand-new {@code CACTUS#contract} group, so a player who protects their cactus everywhere
	 * found the one patch that pays a seed pack quietly unprotected, with a tickbox that looked
	 * like they had chosen it.
	 *
	 * <p>Not made automatic instead, which was the other option. Protection costs items you may
	 * not have, and turning it on for someone is the kind of decision this plugin does not make.
	 * Inheriting what they already chose for that patch type is the closest honest answer: if they
	 * protect cactus, the contract cactus is protected; if they never have, it stays off.
	 *
	 * <p>Explicitly turning it off on the split tab sticks, which is the whole point of tracking
	 * the offs — otherwise the fallback would keep switching it back on.
	 */
	public synchronized boolean isProtecting(PlantingGroup group, Seed seed)
	{
		if (seed == null)
		{
			return false;
		}

		String key = keyFor(group, seed);
		if (protecting.contains(key))
		{
			return true;
		}
		if (notProtecting.contains(key))
		{
			return false;
		}
		// Never asked. A split group defers to the type's own answer; the type is the root and
		// answers no, which is what it has always done.
		return !group.getScope().equals(PlantingGroup.Scope.ALL)
			&& protecting.contains(keyFor(PlantingGroup.of(group.getType()), seed));
	}

	/** Whether anything in this group is being paid for. */
	public synchronized boolean isProtectingAnything(PlantingGroup group)
	{
		String prefix = group.getKey() + "|";
		for (String key : protecting)
		{
			if (key.startsWith(prefix))
			{
				return true;
			}
		}
		return false;
	}

	/** Sets whether this crop will be paid for in this group. Returns the new state. */
	public boolean setProtecting(PlantingGroup group, Seed seed, boolean protect)
	{
		String key = keyFor(group, seed);
		synchronized (this)
		{
			// Recorded either way, so that "off" is a decision rather than the absence of one and
			// the inheritance above stops applying to this group. Compared against what is stored
			// rather than against the answered value: choosing the tier the fallback already gave
			// you must still write, or the choice would silently move if the type's changed.
			boolean changed = protect
				? protecting.add(key) | notProtecting.remove(key)
				: protecting.remove(key) | notProtecting.add(key);
			if (!changed)
			{
				return protect;
			}
			save();
		}

		log.debug("{} will {}be protected", key, protect ? "" : "not ");
		for (Runnable listener : changeListeners)
		{
			listener.run();
		}
		return protect;
	}

	public void load()
	{
		synchronized (this)
		{
			protecting.clear();
			notProtecting.clear();

			readInto(KEY, protecting);
			readInto(OFF_KEY, notProtecting);
		}

		for (Runnable listener : changeListeners)
		{
			listener.run();
		}
	}

	/** Reads one key's list of group keys, tolerating anything unreadable rather than throwing. */
	private void readInto(String key, Set<String> into)
	{
		String json = configManager.getRSProfileConfiguration(DoogleMapsConfig.GROUP, key);
		if (json == null || json.isEmpty())
		{
			return;
		}

		try
		{
			List<String> keys = gson.fromJson(json, NAME_LIST_TYPE);
			if (keys != null)
			{
				into.addAll(keys);
			}
		}
		catch (JsonSyntaxException e)
		{
			log.warn("Discarding unreadable protection selection from {}", key, e);
		}
	}

	private synchronized void save()
	{
		configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, KEY,
			gson.toJson(new ArrayList<>(protecting)));
		configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, OFF_KEY,
			gson.toJson(new ArrayList<>(notProtecting)));
	}
}
