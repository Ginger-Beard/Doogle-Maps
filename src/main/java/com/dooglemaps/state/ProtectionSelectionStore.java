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

	private static final Type NAME_LIST_TYPE = new TypeToken<ArrayList<String>>()
	{
	}.getType();

	private final ConfigManager configManager;
	private final Gson gson;

	/** Group keys the player has said they will pay for. */
	private final Set<String> protecting = new LinkedHashSet<>();
	private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

	@Inject
	private ProtectionSelectionStore(ConfigManager configManager, Gson gson)
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

	public synchronized boolean isProtecting(PlantingGroup group, Seed seed)
	{
		return seed != null && protecting.contains(keyFor(group, seed));
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
			boolean changed = protect ? protecting.add(key) : protecting.remove(key);
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

			String json = configManager.getRSProfileConfiguration(DoogleMapsConfig.GROUP, KEY);
			if (json == null || json.isEmpty())
			{
				return;
			}

			try
			{
				List<String> keys = gson.fromJson(json, NAME_LIST_TYPE);
				if (keys != null)
				{
					protecting.addAll(keys);
				}
			}
			catch (JsonSyntaxException e)
			{
				log.warn("Discarding unreadable protection selection", e);
			}
		}

		for (Runnable listener : changeListeners)
		{
			listener.run();
		}
	}

	private synchronized void save()
	{
		configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, KEY,
			gson.toJson(new ArrayList<>(protecting)));
	}
}
