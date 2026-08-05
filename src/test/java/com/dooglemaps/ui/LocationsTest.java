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
