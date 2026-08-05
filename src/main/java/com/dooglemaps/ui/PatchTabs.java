package com.dooglemaps.ui;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Seed;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.client.config.ConfigItem;

/**
 * Which tabs the sidebar shows, and what each one covers.
 *
 * <p>Tabs stay keyed on {@link PatchImplementation} so the seed list on a tab is exactly the
 * set of seeds that go in that patch — the two cannot drift apart. The one exception is the
 * pair of compost bins, which are the same thing at two sizes and were never worth two tabs.
 * A tab therefore covers one implementation, or occasionally two.
 */
final class PatchTabs
{
	/**
	 * Implementations that are shown on another tab instead of getting their own.
	 *
	 * <p>Only the compost bins. The big bin is the Hosidius one; it decodes from a different
	 * varbit and holds thirty items instead of fifteen, but for someone looking at a farming
	 * overview it is simply "the compost bin over there".
	 */
	private static final Map<PatchImplementation, PatchImplementation> FOLDED_INTO =
		new EnumMap<>(PatchImplementation.class);

	static
	{
		FOLDED_INTO.put(PatchImplementation.BIG_COMPOST, PatchImplementation.COMPOST);
	}

	private PatchTabs()
	{
	}

	/** Every tab, in the order the sidebar shows them. */
	static List<PatchImplementation> all()
	{
		List<PatchImplementation> tabs = new ArrayList<>();
		for (PatchImplementation type : PatchImplementation.values())
		{
			if (!FOLDED_INTO.containsKey(type))
			{
				tabs.add(type);
			}
		}
		return Collections.unmodifiableList(tabs);
	}

	/** The implementations a tab is responsible for, itself first. */
	static List<PatchImplementation> membersOf(PatchImplementation tab)
	{
		List<PatchImplementation> members = new ArrayList<>();
		members.add(tab);
		FOLDED_INTO.forEach((folded, into) ->
		{
			if (into == tab)
			{
				members.add(folded);
			}
		});
		return Collections.unmodifiableList(members);
	}

	/**
	 * Whether anything can be planted in this kind of patch.
	 *
	 * <p>Derived from the seed table rather than listed by hand, so it cannot go stale. It is
	 * false for the compost bins, which take buckets and weeds rather than seeds — a "seeds
	 * you own" list under them was nonsense.
	 */
	static boolean isPlantable(PatchImplementation tab)
	{
		for (PatchImplementation member : membersOf(tab))
		{
			if (!Seed.forPatchType(member).isEmpty())
			{
				return true;
			}
		}
		return false;
	}

	/** Whether the player has left this tab switched on. */
	static boolean isEnabled(DoogleMapsConfig config, PatchImplementation tab)
	{
		switch (tab)
		{
			case HERB: return config.showHerb();
			case ALLOTMENT: return config.showAllotment();
			case FLOWER: return config.showFlower();
			case HOPS: return config.showHops();
			case BUSH: return config.showBush();
			case TREE: return config.showTree();
			case FRUIT_TREE: return config.showFruitTree();
			case HARDWOOD_TREE: return config.showHardwoodTree();
			case GRAPES: return config.showGrapes();
			case CACTUS: return config.showCactus();
			case CALQUAT: return config.showCalquat();
			case CELASTRUS: return config.showCelastrus();
			case REDWOOD: return config.showRedwood();
			case SPIRIT_TREE: return config.showSpiritTree();
			case CRYSTAL_TREE: return config.showCrystalTree();
			case SEAWEED: return config.showSeaweed();
			case CORAL: return config.showCoral();
			case MUSHROOM: return config.showMushroom();
			case BELLADONNA: return config.showBelladonna();
			case HESPORI: return config.showHespori();
			case ANIMA: return config.showAnima();
			case COMPOST: return config.showCompost();
			default: return true;
		}
	}

	/**
	 * The settings keys belonging to the patch-type section.
	 *
	 * <p>Read off the config interface rather than listed again here. A second hand-written
	 * list would be one more place to forget when a tab is added, and the failure would be
	 * silent: the toggle would work but the tab strip would not rebuild until a restart.
	 */
	private static final Set<String> VISIBILITY_KEYS = visibilityKeys();

	private static Set<String> visibilityKeys()
	{
		Set<String> keys = new HashSet<>();
		for (Method method : DoogleMapsConfig.class.getMethods())
		{
			ConfigItem item = method.getAnnotation(ConfigItem.class);
			if (item != null && "patchTypes".equals(item.section()))
			{
				keys.add(item.keyName());
			}
		}
		return Collections.unmodifiableSet(keys);
	}

	/** Whether a settings key is one of the patch-type toggles. */
	/**
	 * Keys that change which tabs exist, outside the patch-type section.
	 *
	 * <p>{@link #VISIBILITY_KEYS} is derived from the section a setting sits in, which is a neat
	 * trick right up until a setting somewhere else also changes the tab strip. These two do:
	 * splitting protected herbs adds a tab, and declaring Colosseum Champion status can be what
	 * makes that split apply at all. Listed by hand because there is no annotation that means
	 * "this changes the shape of the sidebar".
	 */
	private static final Set<String> STRUCTURE_KEYS = new HashSet<>(java.util.Arrays.asList(
		"separateProtectedHerbs",
		"fortisColosseumChampion"));

	static boolean isTabVisibilityKey(String key)
	{
		// The location keys too. They do not change which tabs exist, but they change what every
		// tab contains, and the panel only redraws the one you are looking at — so without this
		// the tab you toggled from would be right and the other twenty stale.
		return VISIBILITY_KEYS.contains(key)
			|| STRUCTURE_KEYS.contains(key)
			|| Locations.isLocationKey(key);
	}

	/** The tabs actually on show, honouring the settings. */
	static List<PatchImplementation> enabled(DoogleMapsConfig config)
	{
		List<PatchImplementation> tabs = new ArrayList<>();
		for (PatchImplementation tab : all())
		{
			if (isEnabled(config, tab))
			{
				tabs.add(tab);
			}
		}
		return tabs;
	}
}
