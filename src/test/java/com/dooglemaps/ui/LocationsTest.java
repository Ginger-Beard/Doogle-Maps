package com.dooglemaps.ui;

import com.dooglemaps.DoogleMapsConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Every farming region has a switch, and every switch reaches a region.
 *
 * <p>The region list is generated from RuneLite's data; the settings are written by hand. That is
 * two lists of the same thing, which is exactly the arrangement that drifts — and it drifts
 * silently, because a region with no switch simply cannot be hidden and nothing complains. When
 * Jagex adds a farming area this test is what says so.
 *
 * <p><b>It guards one list fewer than it used to.</b> There was a third: a 36-case switch in
 * {@code Locations} mapping each region name to its getter. {@code Locations.keyFor} now derives
 * that mapping instead, so the only thing left to keep in step is the settings themselves — and
 * {@link #theSettingNameIsDerivedFromTheRegionName} pins the rule the derivation depends on.
 */
public class LocationsTest
{
	/**
	 * A config that says false to every location, so an unmatched region shows up.
	 *
	 * <p>Asked the other way round from how it is used: if switching everything off still leaves a
	 * region enabled, that region is falling through to the default.
	 */
	private static DoogleMapsConfig allOff()
	{
		DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class, invocation ->
		{
			net.runelite.client.config.ConfigItem item =
				invocation.getMethod().getAnnotation(net.runelite.client.config.ConfigItem.class);
			return item != null && "locations".equals(item.section())
				? Boolean.FALSE
				: Mockito.RETURNS_DEFAULTS.answer(invocation);
		});
		return config;
	}

	@Test
	public void everyRegionInTheDataHasASwitch()
	{
		DoogleMapsConfig config = allOff();

		List<String> unswitchable = new ArrayList<>();
		for (String region : Locations.allRegions())
		{
			if (Locations.isEnabled(config, region))
			{
				unswitchable.add(region);
			}
		}

		assertTrue("these regions have no setting and cannot be hidden - add one to "
			+ "DoogleMapsConfig's locations section: " + unswitchable, unswitchable.isEmpty());
	}

	/** And nothing left over: a switch for a region that no longer exists is dead settings UI. */
	@Test
	public void everySwitchNamesARegionThatExists()
	{
		Set<String> regions = Locations.allRegions();

		List<String> orphans = new ArrayList<>();
		for (java.lang.reflect.Method method : DoogleMapsConfig.class.getMethods())
		{
			net.runelite.client.config.ConfigItem item =
				method.getAnnotation(net.runelite.client.config.ConfigItem.class);
			if (item != null && "locations".equals(item.section()) && !regions.contains(item.name()))
			{
				orphans.add(item.name());
			}
		}

		assertTrue("these settings name a place with no patches in the data: " + orphans,
			orphans.isEmpty());
	}

	@Test
	public void thereIsOneSwitchPerRegion()
	{
		int settings = 0;
		for (java.lang.reflect.Method method : DoogleMapsConfig.class.getMethods())
		{
			net.runelite.client.config.ConfigItem item =
				method.getAnnotation(net.runelite.client.config.ConfigItem.class);
			if (item != null && "locations".equals(item.section()))
			{
				settings++;
			}
		}
		assertEquals(Locations.allRegions().size(), settings);
	}

	/**
	 * The rule that turns a region name into its setting, pinned by its awkward cases.
	 *
	 * <p>{@code Locations} resolves the mapping rather than switching on it, which is what removed
	 * the 36-case list that could drift from the data. That only holds while the naming rule holds,
	 * and these four are the ones it could plausibly get wrong: an apostrophe, a lower-case word in
	 * the middle of a name, a multi-word name, and a plain one.
	 *
	 * <p>If a region ever breaks the rule the honest fix is an explicit override, not a looser
	 * rule — {@code everyRegionInTheDataHasASwitch} is what will say so.
	 */
	@Test
	public void theSettingNameIsDerivedFromTheRegionName()
	{
		assertEquals("showLocationArdougne", Locations.keyFor("Ardougne"));
		assertEquals("an apostrophe is dropped, not turned into a word boundary",
			"showLocationAnglersRetreat", Locations.keyFor("Anglers' Retreat"));
		assertEquals("every word is capitalised, including one the game leaves lower case",
			"showLocationCivitasIllaFortis", Locations.keyFor("Civitas illa Fortis"));
		assertEquals("showLocationTaiBwoWannai", Locations.keyFor("Tai Bwo Wannai"));
	}

	/** Toggling one has to rebuild the tabs, or every tab but the visible one goes stale. */
	@Test
	public void locationKeysRebuildTheSidebar()
	{
		assertTrue(PatchTabs.isTabVisibilityKey("showLocationArdougne"));
		assertFalse(PatchTabs.isTabVisibilityKey("outlineThickness"));
	}

	/** Switched on by default: a new install shows everything and hides on request. */
	@Test
	public void everywhereIsShownByDefault()
	{
		DoogleMapsConfig defaults = Mockito.mock(DoogleMapsConfig.class, invocation ->
		{
			net.runelite.client.config.ConfigItem item =
				invocation.getMethod().getAnnotation(net.runelite.client.config.ConfigItem.class);
			return item != null && "locations".equals(item.section())
				? Boolean.TRUE
				: Mockito.RETURNS_DEFAULTS.answer(invocation);
		});
		when(defaults.showLocationArdougne()).thenReturn(true);

		for (String region : Locations.allRegions())
		{
			assertTrue(region + " should be shown by default",
				Locations.isEnabled(defaults, region));
		}
	}
}
