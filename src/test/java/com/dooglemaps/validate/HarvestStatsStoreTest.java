package com.dooglemaps.validate;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.Produce;
import com.dooglemaps.timer.FarmingBonuses;
import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.List;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Covers the lifetime totals a stats view would be built on.
 *
 * <p>Two properties matter more than the arithmetic. Abandoned patches must not drag the
 * average down, because a half-picked patch is not a low yield — it is not a yield at all.
 * And the totals have to survive a restart, since a lifetime record that resets on logout is
 * not a lifetime record.
 */
public class HarvestStatsStoreTest
{
	private static final String FALADOR_NORTH = "12083.4771";

	/** Stands in for the config store, so a save can actually be read back. */
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
			// Typed through Object deliberately: String.valueOf is overloaded, and letting
			// inference pick for it resolves to valueOf(char[]).
			String key = call.getArgument(1);
			Object value = call.getArgument(2);
			config.put(key, String.valueOf(value));
			return null;
		}).when(configManager).setRSProfileConfiguration(anyString(), anyString(), Mockito.any());
	}

	@Test
	public void averagesOnlyThePatchesThatWereActuallyFinished()
	{
		HarvestStatsStore store = newStore();

		store.record(harvest(CompostTier.ULTRACOMPOST, 9, true));
		store.record(harvest(CompostTier.ULTRACOMPOST, 11, true));
		// Walked away from this one after two picks; it says nothing about yield.
		store.record(harvest(CompostTier.ULTRACOMPOST, 2, false));

		CropHarvestStats stats = onlyEntry(store);
		assertEquals("two finished patches", 2, stats.getHarvests());
		assertEquals(20, stats.getItems());
		assertEquals(10.0, stats.getAverageYield(), 0.001);
		assertEquals("the abandoned pick is still a real potato", 22, stats.getTotalItems());
	}

	@Test
	public void tracksBestAndWorst()
	{
		HarvestStatsStore store = newStore();

		store.record(harvest(CompostTier.NONE, 4, true));
		store.record(harvest(CompostTier.NONE, 12, true));
		store.record(harvest(CompostTier.NONE, 7, true));

		CropHarvestStats stats = onlyEntry(store);
		assertEquals(12, stats.getBest());
		assertEquals("worst must not start life at zero and stay there", 4, stats.getWorst());
	}

	/**
	 * Comparing summed totals is what makes the accuracy figure valid across mixed conditions.
	 *
	 * <p>Each row's prediction was computed under that row's own level, compost and gear, so
	 * summing predictions and summing actuals compares like with like. Averaging the
	 * predictions first would not.
	 */
	@Test
	public void accuracyComparesTotalsNotAverages()
	{
		HarvestStatsStore store = newStore();

		store.record(harvest(CompostTier.NONE, 4, true));
		store.record(harvest(CompostTier.ULTRACOMPOST, 12, true));

		assertEquals("two tiers are kept apart", 2, store.getAll().size());
		assertTrue("but the overall figure spans both", store.getOverallAccuracy() > 0);
		assertEquals(16, store.getTotalItems());
		assertEquals(2, store.getTotalHarvests());
	}

	/** Compost tiers are separate rows, because mixing them describes neither. */
	@Test
	public void keepsCompostTiersApartButCanSumThem()
	{
		HarvestStatsStore store = newStore();

		store.record(harvest(CompostTier.NONE, 4, true));
		store.record(harvest(CompostTier.ULTRACOMPOST, 12, true));

		assertEquals(2, store.getAll().size());

		List<CropHarvestStats> byCrop = store.getByCrop();
		assertEquals("one crop once summed", 1, byCrop.size());
		assertEquals(16, byCrop.get(0).getItems());
		assertEquals(8.0, byCrop.get(0).getAverageYield(), 0.001);
		assertEquals(12, byCrop.get(0).getBest());
		assertEquals(4, byCrop.get(0).getWorst());
	}

	@Test
	public void totalsSurviveARestart()
	{
		HarvestStatsStore store = newStore();
		store.record(harvest(CompostTier.SUPERCOMPOST, 8, true));
		store.record(harvest(CompostTier.SUPERCOMPOST, 6, true));

		HarvestStatsStore reloaded = newStore();
		reloaded.load();

		CropHarvestStats stats = onlyEntry(reloaded);
		assertEquals(2, stats.getHarvests());
		assertEquals(14, stats.getItems());
		assertEquals("Potato", stats.getCrop());
		assertEquals(CompostTier.SUPERCOMPOST.name(), stats.getCompost());
	}

	@Test
	public void clearingWipesTheHistory()
	{
		HarvestStatsStore store = newStore();
		store.record(harvest(CompostTier.NONE, 5, true));
		store.clear();

		assertTrue(store.getAll().isEmpty());
		assertEquals(0, store.getTotalItems());

		HarvestStatsStore reloaded = newStore();
		reloaded.load();
		assertTrue("and the wipe is persisted, not just in memory", reloaded.getAll().isEmpty());
	}

	/** An empty harvest is not a harvest, and must not count as a patch with zero yield. */
	@Test
	public void ignoresRecordsWithNothingInThem()
	{
		HarvestStatsStore store = newStore();
		store.record(harvest(CompostTier.NONE, 0, true));

		assertTrue(store.getAll().isEmpty());
	}

	// ------------------------------------------------------------------- helpers

	private HarvestStatsStore newStore()
	{
		try
		{
			return construct(HarvestStatsStore.class, configManager, new Gson());
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static CropHarvestStats onlyEntry(HarvestStatsStore store)
	{
		List<CropHarvestStats> all = store.getAll();
		assertEquals("expected one crop/tier row", 1, all.size());
		return all.get(0);
	}

	private static HarvestRecord harvest(CompostTier compost, int items, boolean completed)
	{
		FarmPatch patch = FarmingWorldData.getPatch(FALADOR_NORTH);
		assertNotNull(patch);

		HarvestRecord record = new HarvestRecord(patch, Produce.POTATO, compost, 85,
			FarmingBonuses.NONE, 0);
		record.addItems(items);
		if (completed)
		{
			record.markCompleted();
		}
		return record;
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
