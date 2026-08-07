package com.dooglemaps.ui;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import net.runelite.client.config.ConfigItem;

/**
 * Which places show their patches.
 *
 * <p>A coarser cut than the per-patch switch on each row. Those say "I do not farm <i>this</i>
 * patch"; this says "I do not farm <i>there</i>", which is the more common thing to want and was
 * previously twenty separate clicks spread across as many tabs.
 *
 * <h2>Display only, deliberately</h2>
 *
 * This hides rows. It does not touch {@code AvailabilityProfile}, which is what runs are built
 * from, and the two are answering different questions: availability is about the account — a
 * patch you cannot reach — while this is about how much you want to look at. Hiding a place you
 * still farm would silently drop it from your runs, and the setting that did that would be one
 * you had forgotten about by then.
 *
 * <p>The consequence worth knowing: a hidden location's patches are still routed if they are
 * switched on. Switching a patch off is the row click, and that is the one that changes runs.
 */
@lombok.extern.slf4j.Slf4j
final class Locations
{
	private Locations()
	{
	}

	/** Whether this patch's region is on show. */
	static boolean isEnabled(DoogleMapsConfig config, FarmPatch patch)
	{
		return patch == null || isEnabled(config, patch.getRegion().getName());
	}

	/**
	 * Whether a region is on show, by name.
	 *
	 * <h2>Resolved rather than switched</h2>
	 *
	 * This was a 36-case switch mapping each region name to its config getter, and it had the
	 * failure mode a hand-written mirror always has: a new farming area gets a toggle that does
	 * nothing, silently, because the {@code default} branch says "shown". {@code LocationsTest}
	 * existed to fail the build when the two drifted.
	 *
	 * <p>They cannot drift now, because the mapping is <b>computed</b>. A region's config key is
	 * mechanical — {@code "showLocation"} followed by its name with the punctuation dropped and
	 * each word capitalised — and that holds for all thirty-six, including the awkward ones:
	 * {@code Anglers' Retreat} to {@code showLocationAnglersRetreat}, and
	 * {@code Civitas illa Fortis} to {@code showLocationCivitasIllaFortis}, where the lower-case
	 * "illa" is capitalised like any other word.
	 *
	 * <p>The unknown-region behaviour is deliberately unchanged. The region list is generated from
	 * RuneLite's data and the settings are ours, so a region with no toggle is still <i>shown</i> —
	 * the safe direction for drift is showing a patch nobody asked to hide, rather than hiding one
	 * they farm.
	 */
	static boolean isEnabled(DoogleMapsConfig config, String region)
	{
		Method getter = GETTERS.get(region);
		if (getter == null)
		{
			return true;
		}

		try
		{
			return (Boolean) getter.invoke(config);
		}
		catch (ReflectiveOperationException | ClassCastException e)
		{
			// Same answer as an unknown region, for the same reason: a location filter must never
			// be the thing that hides a patch someone farms.
			return true;
		}
	}

	/** Region name to its {@code showLocation…} getter, built once. */
	private static final Map<String, Method> GETTERS = getters();

	private static Map<String, Method> getters()
	{
		Map<String, Method> byName = new HashMap<>();
		for (String region : allRegions())
		{
			try
			{
				byName.put(region, DoogleMapsConfig.class.getMethod(keyFor(region)));
			}
			catch (NoSuchMethodException e)
			{
				// A region with no setting yet. Left out, so isEnabled falls through to "shown".
				// LocationsTest is what turns this from a silent gap into a build failure.
				log.debug("No location setting for region \"{}\"", region);
			}
		}
		return Collections.unmodifiableMap(byName);
	}

	/**
	 * The config method name for a region.
	 *
	 * <p>Public to the test, which asserts every region resolves — that assertion is what replaced
	 * the drift check, and it is a stronger one: it fails on a region the settings have never heard
	 * of rather than merely on the two lists disagreeing.
	 */
	static String keyFor(String region)
	{
		StringBuilder name = new StringBuilder("showLocation");
		for (String word : region.split("\\s+"))
		{
			String letters = word.replaceAll("[^A-Za-z]", "");
			if (!letters.isEmpty())
			{
				name.append(Character.toUpperCase(letters.charAt(0)))
					.append(letters.substring(1));
			}
		}
		return name.toString();
	}

	/** Every region in the data, in the order the settings list them. */
	static Set<String> allRegions()
	{
		Set<String> regions = new LinkedHashSet<>();
		for (FarmPatch patch : FarmingWorldData.getAllPatches())
		{
			regions.add(patch.getRegion().getName());
		}
		return regions;
	}

	/**
	 * The settings keys belonging to this section.
	 *
	 * <p>Read off the config interface rather than listed again, the same way the patch-type keys
	 * are: a second hand-written list is one more place to forget, and forgetting is silent —
	 * the toggle works and the sidebar does not rebuild until a restart.
	 */
	private static final Set<String> KEYS = keys();

	private static Set<String> keys()
	{
		Set<String> keys = new HashSet<>();
		for (Method method : DoogleMapsConfig.class.getMethods())
		{
			ConfigItem item = method.getAnnotation(ConfigItem.class);
			if (item != null && "locations".equals(item.section()))
			{
				keys.add(item.keyName());
			}
		}
		return Collections.unmodifiableSet(keys);
	}

	static boolean isLocationKey(String key)
	{
		return KEYS.contains(key);
	}
}
