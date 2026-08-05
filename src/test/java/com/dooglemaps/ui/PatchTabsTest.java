package com.dooglemaps.ui;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Seed;
import java.util.List;
import net.runelite.client.config.ConfigItem;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Covers which tabs exist and what each one owns.
 *
 * <p>The rule that matters is that a tab and its seed list stay the same thing. Everything
 * here exists to stop a patch type quietly falling off the sidebar, or turning up on a tab
 * whose seed list cannot plant it.
 */
public class PatchTabsTest
{
	/** A config with every patch type switched on, as the real defaults are. */
	private static DoogleMapsConfig allEnabled()
	{
		return Mockito.mock(DoogleMapsConfig.class, invocation ->
		{
			ConfigItem item = invocation.getMethod().getAnnotation(ConfigItem.class);
			return item != null && "patchTypes".equals(item.section())
				? Boolean.TRUE
				: Mockito.RETURNS_DEFAULTS.answer(invocation);
		});
	}

	/**
	 * Every patch type reaches the sidebar, on its own tab or folded into one.
	 *
	 * <p>The failure this guards is a type being dropped entirely — it would simply never
	 * appear, with nothing to notice it by.
	 */
	@Test
	public void everyPatchTypeIsOwnedByExactlyOneTab()
	{
		for (PatchImplementation type : PatchImplementation.values())
		{
			int owners = 0;
			for (PatchImplementation tab : PatchTabs.all())
			{
				if (PatchTabs.membersOf(tab).contains(type))
				{
					owners++;
				}
			}
			assertEquals(type + " should belong to exactly one tab", 1, owners);
		}
	}

	@Test
	public void theTwoCompostBinsShareATab()
	{
		List<PatchImplementation> tabs = PatchTabs.all();

		assertTrue(tabs.contains(PatchImplementation.COMPOST));
		assertFalse("the big bin is the same thing at a different size",
			tabs.contains(PatchImplementation.BIG_COMPOST));
		assertTrue(PatchTabs.membersOf(PatchImplementation.COMPOST)
			.contains(PatchImplementation.BIG_COMPOST));

		assertEquals("one fewer tab than there are patch types",
			PatchImplementation.values().length - 1, tabs.size());
	}

	/** Nothing is planted in a compost bin, so it gets no "seeds you own" list. */
	@Test
	public void compostBinsHaveNoSeedList()
	{
		assertFalse(PatchTabs.isPlantable(PatchImplementation.COMPOST));
		assertTrue(PatchTabs.isPlantable(PatchImplementation.HERB));
		assertTrue(PatchTabs.isPlantable(PatchImplementation.SPIRIT_TREE));
	}

	/**
	 * A tab claims a seed list exactly when seeds exist for it.
	 *
	 * <p>Derived from the seed table rather than a hand-written list, so this checks the two
	 * agree for every tab rather than spot-checking the compost bins.
	 */
	@Test
	public void plantabilityMatchesTheSeedTable()
	{
		for (PatchImplementation tab : PatchTabs.all())
		{
			boolean hasSeeds = false;
			for (PatchImplementation member : PatchTabs.membersOf(tab))
			{
				hasSeeds |= !Seed.forPatchType(member).isEmpty();
			}
			assertEquals(tab + " disagrees with the seed table",
				hasSeeds, PatchTabs.isPlantable(tab));
		}
	}

	@Test
	public void switchingATypeOffRemovesItsTab()
	{
		DoogleMapsConfig config = allEnabled();
		assertEquals(PatchTabs.all().size(), PatchTabs.enabled(config).size());

		when(config.showSpiritTree()).thenReturn(false);

		List<PatchImplementation> enabled = PatchTabs.enabled(config);
		assertFalse(enabled.contains(PatchImplementation.SPIRIT_TREE));
		assertEquals(PatchTabs.all().size() - 1, enabled.size());
		assertTrue("and nothing else moved", enabled.contains(PatchImplementation.HERB));
	}

	/**
	 * Every tab has a toggle, and the panel knows to rebuild for it.
	 *
	 * <p>A missing toggle would leave a tab permanently on; a toggle the panel does not
	 * recognise would work but need a restart to take effect. Both are silent, so both are
	 * checked here rather than left to be noticed in the client.
	 */
	@Test
	public void everyTabHasAToggleThePanelReactsTo()
	{
		DoogleMapsConfig config = allEnabled();

		for (PatchImplementation tab : PatchTabs.all())
		{
			assertTrue(tab + " has no working toggle", PatchTabs.isEnabled(config, tab));
		}

		int toggles = 0;
		for (java.lang.reflect.Method method : DoogleMapsConfig.class.getMethods())
		{
			ConfigItem item = method.getAnnotation(ConfigItem.class);
			if (item != null && "patchTypes".equals(item.section()))
			{
				toggles++;
				assertTrue("the panel ignores " + item.keyName(),
					PatchTabs.isTabVisibilityKey(item.keyName()));
			}
		}
		assertEquals("one toggle per tab", PatchTabs.all().size(), toggles);
	}
}
