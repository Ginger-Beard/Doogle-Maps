package com.dooglemaps.state;

import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.Seed;
import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Covers splitting a patch type into planting groups without losing what was already chosen.
 *
 * <p>The risk being guarded is not that the split fails to work — that is visible immediately —
 * but that turning it on quietly discards a selection, or that turning it off does not give it
 * back. Both would be found days later, by a run planted with the wrong seed.
 */
public class PlantingGroupTest
{
	private final Map<String, Object> stored = new HashMap<>();
	private SeedSelectionStore seeds;

	@Before
	public void setUp() throws Exception
	{
		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer(i -> stored.get(i.getArgument(1)));
		Mockito.doAnswer(i -> stored.put(i.getArgument(1), i.getArgument(2)))
			.when(configManager)
			.setRSProfileConfiguration(anyString(), anyString(), Mockito.<Object>any());

		seeds = construct(SeedSelectionStore.class, configManager, new Gson());
		seeds.load();
	}

	/** An unsplit group stores under the bare type name, so nothing already saved is orphaned. */
	@Test
	public void anUnsplitGroupKeepsTheOldStorageKey()
	{
		assertEquals("HERB", PlantingGroup.of(PatchImplementation.HERB).getKey());
		assertEquals("HERB#protected",
			PlantingGroup.protectedOnly(PatchImplementation.HERB).getKey());
	}

	/**
	 * Splitting starts both groups from what the type already had.
	 *
	 * <p>Otherwise the protected tab opens empty and the first click there looks like it wiped
	 * the selection — which, before the fallback existed, it would have.
	 */
	@Test
	public void aNewGroupInheritsTheTypeSelection()
	{
		seeds.toggle(Seed.RANARR);

		assertTrue("the plain group sees it",
			seeds.getSelectedFor(PlantingGroup.of(PatchImplementation.HERB)).contains(Seed.RANARR));
		assertTrue("and so does a group nothing has been picked in yet",
			seeds.getSelectedFor(PlantingGroup.protectedOnly(PatchImplementation.HERB))
				.contains(Seed.RANARR));
	}

	/** Once a group is edited it is its own list, and the other one is untouched. */
	@Test
	public void groupsDivergeOnceEdited()
	{
		seeds.toggle(Seed.GUAM);

		PlantingGroup safe = PlantingGroup.protectedOnly(PatchImplementation.HERB);
		seeds.toggle(safe, Seed.RANARR);

		assertTrue("the protected group keeps what it inherited plus the new pick",
			seeds.getSelectedFor(safe).containsAll(java.util.Arrays.asList(Seed.GUAM, Seed.RANARR)));
		assertEquals("the ordinary group is unchanged",
			java.util.Collections.singleton(Seed.GUAM),
			seeds.getSelectedFor(PlantingGroup.of(PatchImplementation.HERB)));
	}

	/**
	 * Anything asking "is this seed in the run at all" still gets a whole answer.
	 *
	 * <p>The loadout and the inventory plan work on the union rather than per group, so a seed
	 * picked only for the protected patches still has to be banked for.
	 */
	@Test
	public void theFlatSelectionRemainsTheUnion()
	{
		seeds.toggle(Seed.GUAM);
		seeds.toggle(PlantingGroup.protectedOnly(PatchImplementation.HERB), Seed.RANARR);

		assertTrue(seeds.getSelected().containsAll(
			java.util.Arrays.asList(Seed.GUAM, Seed.RANARR)));
	}

	/** And it all survives a reload, which is where a storage-key mistake would show up. */
	@Test
	public void groupPicksSurviveAReload() throws Exception
	{
		PlantingGroup safe = PlantingGroup.protectedOnly(PatchImplementation.HERB);
		seeds.toggle(safe, Seed.SNAPDRAGON);

		SeedSelectionStore reloaded = construct(SeedSelectionStore.class,
			mockConfig(), new Gson());
		reloaded.load();

		assertTrue(reloaded.getSelectedFor(safe).contains(Seed.SNAPDRAGON));
	}

	private ConfigManager mockConfig()
	{
		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer(i -> stored.get(i.getArgument(1)));
		return configManager;
	}

	@SuppressWarnings("unchecked")
	private static <T> T construct(Class<T> type, Object... args) throws Exception
	{
		Constructor<?> constructor = type.getDeclaredConstructors()[0];
		constructor.setAccessible(true);
		return (T) constructor.newInstance(args);
	}
}
