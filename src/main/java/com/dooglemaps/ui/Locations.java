package com.dooglemaps.ui;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import java.lang.reflect.Method;
import java.util.Collections;
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
	 * <p>A region the switch statement has never heard of is shown. The region list is generated
	 * from RuneLite's data and this is written by hand, so the two can drift when a new farming
	 * area lands — and the safe direction for that drift is showing a patch nobody asked to hide.
	 * {@code LocationsTest} fails the build when they do drift, so it does not stay that way.
	 */
	static boolean isEnabled(DoogleMapsConfig config, String region)
	{
		switch (region)
		{
			case "Al Kharid": return config.showLocationAlKharid();
			case "Aldarin": return config.showLocationAldarin();
			case "Anglers' Retreat": return config.showLocationAnglersRetreat();
			case "Ardougne": return config.showLocationArdougne();
			case "Auburnvale": return config.showLocationAuburnvale();
			case "Avium Savannah": return config.showLocationAviumSavannah();
			case "Brimhaven": return config.showLocationBrimhaven();
			case "Catherby": return config.showLocationCatherby();
			case "Champions' Guild": return config.showLocationChampionsGuild();
			case "Civitas illa Fortis": return config.showLocationCivitasIllaFortis();
			case "Draynor Manor": return config.showLocationDraynorManor();
			case "Entrana": return config.showLocationEntrana();
			case "Etceteria": return config.showLocationEtceteria();
			case "Falador": return config.showLocationFalador();
			case "Farming Guild": return config.showLocationFarmingGuild();
			case "Fossil Island": return config.showLocationFossilIsland();
			case "Gnome Stronghold": return config.showLocationGnomeStronghold();
			case "Great Conch": return config.showLocationGreatConch();
			case "Harmony": return config.showLocationHarmony();
			case "Kastori": return config.showLocationKastori();
			case "Kourend": return config.showLocationKourend();
			case "Lletya": return config.showLocationLletya();
			case "Lumbridge": return config.showLocationLumbridge();
			case "Morytania": return config.showLocationMorytania();
			case "Port Sarim": return config.showLocationPortSarim();
			case "Prifddinas": return config.showLocationPrifddinas();
			case "Rimmington": return config.showLocationRimmington();
			case "Seaweed": return config.showLocationSeaweed();
			case "Seers' Village": return config.showLocationSeersVillage();
			case "Tai Bwo Wannai": return config.showLocationTaiBwoWannai();
			case "Taverley": return config.showLocationTaverley();
			case "Tree Gnome Village": return config.showLocationTreeGnomeVillage();
			case "Troll Stronghold": return config.showLocationTrollStronghold();
			case "Varrock": return config.showLocationVarrock();
			case "Weiss": return config.showLocationWeiss();
			case "Yanille": return config.showLocationYanille();
			default: return true;
		}
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
