package com.dooglemaps.state;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Seed;
import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Round-trips the three choices a run is built from.
 *
 * <p>All were reported as resetting after a teleport. The trigger was the plugin reloading on
 * every {@code LOGGED_IN} — which fires when the world finishes loading, not when the player
 * logs in — but a reload is only harmless if what it reads back is what was written. These
 * tests are the second half of that: they save, throw the store away, and load a fresh one,
 * which is exactly what a reload does.
 */
public class RunChoicesTest
{
	private final Map<String, String> stored = new HashMap<>();

	private ConfigManager configManager;
	private Gson gson;

	@Before
	public void setUp()
	{
		gson = new Gson();
		configManager = Mockito.mock(ConfigManager.class);

		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer((InvocationOnMock i) -> stored.get(i.getArgument(0) + "." + i.getArgument(1)));
		doAnswer((InvocationOnMock i) ->
		{
			String key = i.getArgument(0) + "." + i.getArgument(1);
			Object value = i.getArgument(2);
			stored.put(key, String.valueOf(value));
			return null;
		}).when(configManager).setRSProfileConfiguration(anyString(), anyString(), Mockito.any());
	}

	@Test
	public void compostChoicesSurviveAReload()
	{
		CompostSelectionStore store = newCompost();
		store.set(PatchImplementation.HERB, CompostTier.ULTRACOMPOST);
		store.set(PatchImplementation.HOPS, CompostTier.NONE);
		store.set(PatchImplementation.ALLOTMENT, CompostTier.SUPERCOMPOST);

		CompostSelectionStore reloaded = newCompost();
		reloaded.load();

		assertEquals(CompostTier.ULTRACOMPOST, reloaded.get(PatchImplementation.HERB));
		assertEquals(CompostTier.NONE, reloaded.get(PatchImplementation.HOPS));
		assertEquals(CompostTier.SUPERCOMPOST, reloaded.get(PatchImplementation.ALLOTMENT));
	}

	/**
	 * Picking the tier that happens to be the default must still be recorded.
	 *
	 * <p>Otherwise the choice is indistinguishable from never having chosen, and would move on
	 * its own the day the default changed.
	 */
	@Test
	public void choosingTheDefaultIsStillAChoice()
	{
		CompostSelectionStore store = newCompost();
		store.set(PatchImplementation.HERB, store.get(PatchImplementation.HERB));

		assertTrue("nothing was written, so the choice was not really stored",
			stored.containsKey("dooglemaps.runCompost"));
	}

	@Test
	public void seedChoicesSurviveAReload()
	{
		SeedSelectionStore store = newSeeds();
		store.toggle(Seed.RANARR);
		store.toggle(Seed.SNAPDRAGON);

		SeedSelectionStore reloaded = newSeeds();
		reloaded.load();

		assertEquals(EnumSet.of(Seed.RANARR, Seed.SNAPDRAGON), EnumSet.copyOf(reloaded.getSelected()));
	}

	@Test
	public void runTypeChoicesSurviveAReload()
	{
		RunTypeStore store = newTypes();
		store.setSelected(new java.util.LinkedHashSet<>(java.util.Arrays.asList(
			com.dooglemaps.data.RunOption.full(
				com.dooglemaps.data.PlantingGroup.of(PatchImplementation.HERB)),
			com.dooglemaps.data.RunOption.full(
				com.dooglemaps.data.PlantingGroup.of(PatchImplementation.ALLOTMENT)))));

		RunTypeStore reloaded = newTypes();
		reloaded.load();

		assertEquals(EnumSet.of(PatchImplementation.HERB, PatchImplementation.ALLOTMENT),
			EnumSet.copyOf(reloaded.getSelected()));
	}

	/**
	 * A run over a line that is not currently offered survives the boxes being touched.
	 *
	 * <p>Which lines exist changes: protected herbs come and go with a setting, and with the split
	 * off there is no checkbox that can speak for them. Replacing the whole selection from the
	 * boxes therefore discarded the protected herb run the moment anything else was ticked — and
	 * turning the split back on did not bring it back, because it was gone from the store.
	 */
	@Test
	public void aChoiceNotCurrentlyOnOfferIsNotDiscarded()
	{
		com.dooglemaps.data.RunOption protectedHerbs = com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.protectedOnly(PatchImplementation.HERB));
		com.dooglemaps.data.RunOption herbs = com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.HERB));
		com.dooglemaps.data.RunOption allotments = com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.ALLOTMENT));

		RunTypeStore store = newTypes();
		store.setSelected(new java.util.LinkedHashSet<>(
			java.util.Arrays.asList(protectedHerbs, herbs)));

		// The split is switched off, so only the unsplit lines are on show. The player ticks
		// allotments; the protected herb line is not something these boxes can report on.
		store.setSelected(
			new java.util.LinkedHashSet<>(java.util.Arrays.asList(herbs, allotments)),
			java.util.Arrays.asList(herbs, allotments));

		assertTrue("the protected herb run was discarded by a box that could not see it",
			store.isSelected(protectedHerbs));
		assertTrue(store.isSelected(allotments));

		// And it is still there after a reload, which is where it would have to survive.
		RunTypeStore reloaded = newTypes();
		reloaded.load();
		assertTrue(reloaded.isSelected(protectedHerbs));
	}

	/** Unticking a line that <i>is</i> on offer still unticks it. */
	@Test
	public void aChoiceOnOfferIsStillCleared()
	{
		com.dooglemaps.data.RunOption herbs = com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.HERB));

		RunTypeStore store = newTypes();
		store.setSelected(new java.util.LinkedHashSet<>(java.util.Collections.singletonList(herbs)));
		store.setSelected(new java.util.LinkedHashSet<>(),
			java.util.Collections.singletonList(herbs));

		assertTrue(store.getSelected().isEmpty());
	}

	/** A reload with nothing stored must not invent choices either. */
	@Test
	public void anEmptyProfileLoadsCleanly()
	{
		SeedSelectionStore seeds = newSeeds();
		seeds.load();
		assertTrue(seeds.getSelected().isEmpty());

		RunTypeStore types = newTypes();
		types.load();
		assertTrue(types.getSelected().isEmpty());
	}

	private CompostSelectionStore newCompost()
	{
		return construct(CompostSelectionStore.class, configManager, gson);
	}

	private SeedSelectionStore newSeeds()
	{
		return construct(SeedSelectionStore.class, configManager, gson);
	}

	private RunTypeStore newTypes()
	{
		return construct(RunTypeStore.class, configManager, gson);
	}

	@SuppressWarnings("unchecked")
	private static <T> T construct(Class<T> type, Object... args)
	{
		try
		{
			for (Constructor<?> candidate : type.getDeclaredConstructors())
			{
				if (candidate.getParameterCount() == args.length)
				{
					candidate.setAccessible(true);
					return (T) candidate.newInstance(args);
				}
			}
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException(e);
		}
		throw new IllegalStateException("no constructor of arity " + args.length + " on " + type);
	}
}
