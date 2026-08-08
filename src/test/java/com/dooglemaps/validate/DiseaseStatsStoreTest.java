package com.dooglemaps.validate;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.ProduceState;
import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Covers the one measurement the harvest log structurally cannot make.
 *
 * <p>A patch that dies produces no harvest, so it opens no record and writes no row — every
 * disease figure in the plugin came from published constants with nothing to check it against.
 * This counts the outcome from the patch state instead, so the two properties that matter are
 * that a cycle is counted <b>once</b> whichever way it ends, and that a patch which cannot be
 * diseased at all never enters the denominator.
 */
public class DiseaseStatsStoreTest
{
	/**
	 * An ordinary herb patch: diseasable, unprotected, no diary immunity.
	 *
	 * <p>Found rather than named, because most herb patches qualify and a couple do not —
	 * Trollheim and Weiss are disease-free, and picking one of those by accident would make
	 * every test here pass by counting nothing.
	 */
	private static final FarmPatch DISEASABLE_HERB = firstDiseasableHerb();

	private final Map<String, String> config = new HashMap<>();

	private ConfigManager configManager;

	@Before
	public void setUp()
	{
		configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer((InvocationOnMock call) -> config.get(call.getArgument(1)));
		doAnswer((InvocationOnMock call) ->
		{
			String key = call.getArgument(1);
			Object value = call.getArgument(2);
			config.put(key, String.valueOf(value));
			return null;
		}).when(configManager).setRSProfileConfiguration(anyString(), anyString(), Mockito.any());
	}

	/** A patch that grows to harvestable untouched is a survival. */
	@Test
	public void reachingHarvestableClosesTheCycleAsASurvival()
	{
		DiseaseStatsStore store = newStore();
		grow(store, patch());
		harvest(store, patch());

		DiseaseStats stats = onlyEntry(store);
		assertEquals(1, stats.getCycles());
		assertEquals(0, stats.getDiseased());
		assertEquals(0, stats.getDied());
		assertEquals(1.0, stats.getSurvivalRate(), 1e-9);
	}

	/**
	 * Curing a disease does not turn the cycle back into a clean one.
	 *
	 * <p>The roll still went against you, and the roll is what the published rate describes. A
	 * cured patch counts as diseased and as one cycle, not as two.
	 */
	@Test
	public void aCuredPatchIsStillARollYouLost()
	{
		DiseaseStatsStore store = newStore();
		FarmPatch patch = patch();

		grow(store, patch);
		store.observe(patch, growing(), diseased(), CompostTier.NONE, false, false);
		// Cured back to growing, then picked as normal.
		store.observe(patch, diseased(), growing(), CompostTier.NONE, false, false);
		harvest(store, patch);

		DiseaseStats stats = onlyEntry(store);
		assertEquals("one cycle, not two", 1, stats.getCycles());
		assertEquals(1, stats.getDiseased());
		assertEquals(0, stats.getDied());
	}

	/**
	 * A patch found dead counts as diseased even though the diseased state was never seen.
	 *
	 * <p>The common case in real play: a patch sickens and dies while you are elsewhere, and the
	 * only evidence is the jump straight to dead on your next visit. Counting that as "not
	 * diseased" would file the worst outcome as a clean run.
	 */
	@Test
	public void aPatchFoundDeadCountsAsDiseasedWithoutHavingBeenSeenSick()
	{
		DiseaseStatsStore store = newStore();
		FarmPatch patch = patch();

		grow(store, patch);
		store.observe(patch, growing(), dead(), CompostTier.NONE, false, false);

		DiseaseStats stats = onlyEntry(store);
		assertEquals(1, stats.getCycles());
		assertEquals(1, stats.getDiseased());
		assertEquals(1, stats.getDied());
		assertEquals(0.0, stats.getSurvivalRate(), 1e-9);
	}

	/**
	 * A patch that cannot be diseased is left out of the denominator entirely.
	 *
	 * <p>Counting its certain survival would drag every rate towards a hundred percent and make
	 * the comparison against the prediction meaningless — the prediction for it is exactly 1, so
	 * it can only ever agree.
	 */
	@Test
	public void anImmunePatchIsNotCounted()
	{
		DiseaseStatsStore store = newStore();
		FarmPatch patch = patch();

		grow(store, patch);
		// Protected by a farmer, so its survival chance is 1.
		store.observe(patch, growing(), harvestable(), CompostTier.NONE, true, false);

		assertTrue("a certain survival says nothing about the rate", store.getAll().isEmpty());
	}

	/** The prediction is summed beside the actual, so the two compare like with like. */
	@Test
	public void thePredictionIsSummedAlongsideTheOutcome()
	{
		DiseaseStatsStore store = newStore();
		for (int cycle = 0; cycle < 10; cycle++)
		{
			FarmPatch patch = patch();
			grow(store, patch);
			harvest(store, patch);
		}

		DiseaseStats stats = onlyEntry(store);
		assertEquals(10, stats.getCycles());
		assertTrue("a herb patch is not certain to survive",
			stats.getPredictedSurvivalRate() > 0 && stats.getPredictedSurvivalRate() < 1);
	}

	/** Emptying a patch forgets the flag, so the next cycle starts clean. */
	@Test
	public void replantingStartsACleanCycle()
	{
		DiseaseStatsStore store = newStore();
		FarmPatch patch = patch();

		grow(store, patch);
		store.observe(patch, growing(), diseased(), CompostTier.NONE, false, false);
		store.observe(patch, diseased(), dead(), CompostTier.NONE, false, false);
		// Cleared and replanted.
		store.observe(patch, dead(), empty(), CompostTier.NONE, false, false);
		grow(store, patch);
		harvest(store, patch);

		DiseaseStats stats = onlyEntry(store);
		assertEquals("two cycles now", 2, stats.getCycles());
		assertEquals("only the first caught anything", 1, stats.getDiseased());
		assertEquals(1, stats.getDied());
	}

	/** Tiers are kept apart, which is what makes the compost comparison possible. */
	@Test
	public void tiersAreKeptApart()
	{
		DiseaseStatsStore store = newStore();
		FarmPatch patch = patch();

		grow(store, patch);
		store.observe(patch, growing(), harvestable(), CompostTier.NONE, false, false);
		store.observe(patch, harvestable(), empty(), CompostTier.NONE, false, false);
		grow(store, patch);
		store.observe(patch, growing(), harvestable(), CompostTier.ULTRACOMPOST, false, false);

		assertEquals(2, store.getAll().size());
		assertEquals("summed across tiers for the per-crop view", 1, store.getByCrop().size());
		assertEquals(2, store.getByCrop().get(0).getCycles());
	}

	/** The record survives a restart, or it is not a record. */
	@Test
	public void countsSurviveARestart()
	{
		DiseaseStatsStore store = newStore();
		FarmPatch patch = patch();
		grow(store, patch);
		store.observe(patch, growing(), dead(), CompostTier.NONE, false, false);

		DiseaseStatsStore reloaded = newStore();
		reloaded.load();
		assertEquals(1, reloaded.getTotalDied());
		assertEquals(1, reloaded.getTotalCycles());
	}

	// ------------------------------------------------------------------ helpers

	private static FarmPatch patch()
	{
		return DISEASABLE_HERB;
	}

	private static FarmPatch firstDiseasableHerb()
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(
			com.dooglemaps.data.PatchImplementation.HERB))
		{
			if (com.dooglemaps.timer.DiseaseRisk.survivalChance(patch, Produce.RANARR,
				CompostTier.NONE, false, false) < 1)
			{
				return patch;
			}
		}
		throw new IllegalStateException("no diseasable herb patch in the world data");
	}

	/** Puts a patch into growing from empty, which is what planting looks like. */
	private static void grow(DiseaseStatsStore store, FarmPatch patch)
	{
		store.observe(patch, empty(), growing(), CompostTier.NONE, false, false);
	}

	private static void harvest(DiseaseStatsStore store, FarmPatch patch)
	{
		store.observe(patch, growing(), harvestable(), CompostTier.NONE, false, false);
	}

	private static ProduceState growing()
	{
		return new ProduceState(Produce.RANARR, CropState.GROWING, 2);
	}

	private static ProduceState harvestable()
	{
		return new ProduceState(Produce.RANARR, CropState.HARVESTABLE, 4);
	}

	private static ProduceState diseased()
	{
		return new ProduceState(Produce.RANARR, CropState.DISEASED, 2);
	}

	private static ProduceState dead()
	{
		return new ProduceState(Produce.RANARR, CropState.DEAD, 2);
	}

	private static ProduceState empty()
	{
		return new ProduceState(Produce.WEEDS, CropState.EMPTY, 0);
	}

	private DiseaseStatsStore newStore()
	{
		try
		{
			return construct(DiseaseStatsStore.class, configManager, new Gson());
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static DiseaseStats onlyEntry(DiseaseStatsStore store)
	{
		assertEquals("expected one crop/tier row", 1, store.getAll().size());
		return store.getAll().get(0);
	}

	@SuppressWarnings("unchecked")
	private static <T> T construct(Class<T> type, Object... args) throws Exception
	{
		for (Constructor<?> candidate : type.getDeclaredConstructors())
		{
			if (candidate.getParameterCount() == args.length)
			{
				candidate.setAccessible(true);
				return (T) candidate.newInstance(args);
			}
		}
		throw new IllegalStateException("no constructor of arity " + args.length + " on " + type);
	}
}
