package com.dooglemaps.ui;

import java.lang.reflect.Method;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers which settings cause the tab strip to be rebuilt.
 *
 * <p>Written after the protected-herb split shipped doing nothing: the toggle worked, the
 * grouping worked, and the sidebar never rebuilt, so ticking it appeared to have no effect at
 * all. The rebuild trigger derives its keys from the patch-type <i>section</i>, which silently
 * excludes any tab-shaping setting that lives elsewhere — and both of these do.
 *
 * <p>The failure mode is what makes it worth a test: nothing throws, nothing logs, and the
 * feature is simply invisible.
 */
public class TabRebuildTriggerTest
{
	@Test
	public void settingsThatChangeTheTabStripTriggerARebuild() throws Exception
	{
		assertTrue("splitting protected herbs adds a tab",
			triggersRebuild("separateProtectedHerbs"));
		assertTrue("declaring Colosseum Champion can be what makes that split apply",
			triggersRebuild("fortisColosseumChampion"));
	}

	/** The patch-type toggles still work, since that is where the derived keys come from. */
	@Test
	public void patchTypeVisibilityStillTriggersARebuild() throws Exception
	{
		assertTrue(triggersRebuild("showHerb"));
	}

	/**
	 * And settings that only change what is drawn must not rebuild the strip.
	 *
	 * <p>Rebuilding throws away every panel and its collapse state, so doing it on a colour
	 * change would be a visible flicker for nothing.
	 */
	@Test
	public void cosmeticSettingsDoNotTriggerARebuild() throws Exception
	{
		assertFalse(triggersRebuild("guideHighlightColour"));
		assertFalse(triggersRebuild("guidedMode"));
	}

	private static boolean triggersRebuild(String key) throws Exception
	{
		Method method = PatchTabs.class.getDeclaredMethod("isTabVisibilityKey", String.class);
		method.setAccessible(true);
		return (boolean) method.invoke(null, key);
	}
}
