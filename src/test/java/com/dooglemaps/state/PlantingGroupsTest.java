package com.dooglemaps.state;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Covers which patches land in which planting group.
 *
 * <p>The property that matters most is the <b>off</b> state: with the split disabled, or on an
 * account with no protected patches, every patch must fall into the plain group so the plugin
 * behaves exactly as it did before any of this existed. A regression there would not look like a
 * grouping bug — it would look like seeds and compost silently resetting.
 */
public class PlantingGroupsTest
{
	/** Weiss, disease-free after Making Friends with My Arm. */
	private static final int WEISS = 11325;

	/** Catherby, which can very much be diseased. */
	private static final int CATHERBY = 11062;

	/** Civitas illa Fortis, safe only with Colosseum Champion status. */
	private static final int FORTIS = 6192;

	private final Map<String, Object> stored = new HashMap<>();
	private DoogleMapsConfig config;
	private ConfigManager configManager;
	private AvailabilityProfile availability;

	@Before
	public void setUp() throws Exception
	{
		configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer(i -> stored.get(i.getArgument(1)));
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(int.class)))
			.thenAnswer(i -> stored.get(i.getArgument(1)));

		config = Mockito.mock(DoogleMapsConfig.class);
		when(config.separateProtectedHerbs()).thenReturn(true);

		Gson gson = new Gson();
		PatchStateStore patches = construct(PatchStateStore.class, configManager, gson);
		availability = construct(AvailabilityProfile.class, configManager, gson, patches);
		availability.load();
	}

	/** With the unlocks detected, a safe patch and a risky one land in different groups. */
	@Test
	public void aProtectedPatchGetsItsOwnGroup() throws Exception
	{
		unlockAll();
		PlantingGroups groups = build();

		assertTrue("the split should be active once a protected patch exists",
			groups.isSplit(PatchImplementation.HERB));
		assertEquals(PlantingGroup.protectedOnly(PatchImplementation.HERB),
			groups.groupFor(patch(WEISS)));
		assertEquals(PlantingGroup.of(PatchImplementation.HERB),
			groups.groupFor(patch(CATHERBY)));
	}

	/**
	 * With the setting off, everything is one group again — including the protected patches.
	 *
	 * <p>This is what makes the toggle reversible rather than a one-way door.
	 */
	@Test
	public void turningTheSplitOffPutsEverythingBackTogether() throws Exception
	{
		unlockAll();
		when(config.separateProtectedHerbs()).thenReturn(false);
		PlantingGroups groups = build();

		assertFalse(groups.isSplit(PatchImplementation.HERB));
		assertEquals("a protected patch is just a herb patch again",
			PlantingGroup.of(PatchImplementation.HERB), groups.groupFor(patch(WEISS)));
		assertEquals(1, groups.groupsFor(PatchImplementation.HERB).size());
	}

	/**
	 * An account with no unlocks gets no second tab, even with the setting on.
	 *
	 * <p>An empty protected tab would imply the player is missing something, when the truth is
	 * the feature does not apply to them yet.
	 */
	@Test
	public void noUnlocksMeansNoSplit() throws Exception
	{
		PlantingGroups groups = build();

		assertFalse(groups.isSplit(PatchImplementation.HERB));
		assertEquals(1, groups.groupsFor(PatchImplementation.HERB).size());
	}

	/** Only herbs split. Allotments and flowers share the unlock and nobody plans around it. */
	@Test
	public void otherPatchTypesAreNeverSplit() throws Exception
	{
		unlockAll();
		PlantingGroups groups = build();

		assertFalse(groups.isSplit(PatchImplementation.ALLOTMENT));
		assertFalse(groups.isSplit(PatchImplementation.FLOWER));
	}

	/**
	 * Civitas illa Fortis joins the group only when the player says so.
	 *
	 * <p>Its unlock is Colosseum Champion status, for which the client exposes no varbit — the
	 * one thing here taken on trust rather than observed.
	 */
	@Test
	public void fortisJoinsOnlyWhenDeclared() throws Exception
	{
		FarmPatch fortis = patch(FORTIS);

		assertFalse("not claimed, so not assumed", build().isProtected(fortis));

		when(config.fortisColosseumChampion()).thenReturn(true);
		assertTrue("declared, so honoured", build().isProtected(fortis));
	}

	// ------------------------------------------------------------------- helpers

	/**
	 * All four detectable unlocks, plus the patches switched on.
	 *
	 * <p>Both halves are needed, and the second is easy to forget: a patch counts as available
	 * only once it has been seen or explicitly enabled, and the split asks whether the account
	 * has any protected patch it can actually reach. An unlocked quest for a patch you have
	 * switched off is correctly no reason to show a second tab.
	 */
	private void unlockAll()
	{
		stored.put("protectedHerbRegions", 0b1111);
		for (int region : new int[]{WEISS, CATHERBY, 11321, 6967, 15148})
		{
			availability.setAvailable(patch(region), true);
		}
	}

	private PlantingGroups build() throws Exception
	{
		return construct(PlantingGroups.class, config,
			construct(ProtectedPatches.class, configManager), availability,
			construct(com.dooglemaps.state.ContractState.class, configManager));
	}

	/**
	 * The herb patch in a region, found by region rather than by a hardcoded key.
	 *
	 * <p>A key embeds the varbit id, which differs per patch and is generated data — so a
	 * hardcoded one is a fixture that breaks on a regeneration for no real reason. Region plus
	 * type is the thing the test actually means.
	 */
	private static FarmPatch patch(int regionId)
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.HERB))
		{
			if (patch.getRegion().getRegionId() == regionId)
			{
				return patch;
			}
		}
		throw new AssertionError("no herb patch in region " + regionId);
	}

	@SuppressWarnings("unchecked")
	private static <T> T construct(Class<T> type, Object... args) throws Exception
	{
		Constructor<?> constructor = type.getDeclaredConstructors()[0];
		constructor.setAccessible(true);
		return (T) constructor.newInstance(args);
	}
}
