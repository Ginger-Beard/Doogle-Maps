package com.dooglemaps.validate;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropYield;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.Produce;
import com.dooglemaps.timer.FarmingBonuses;
import com.google.gson.Gson;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A crop the plugin has no yield formula for cannot be over or under expectation.
 *
 * <h2>The reported dead end</h2>
 *
 * "1,441 items over expectation" was the headline on the Stats tab, and the arithmetic behind it
 * was exact. Decomposed against the raw log, <b>+868 of it was crops whose yield family the model
 * gets wrong</b> and −273 was the same fault pointing the other way:
 *
 * <ul>
 *   <li>Whiteberry +449 over a "prediction" of 4 — which is {@link
 *       com.dooglemaps.timer.CropYieldModel}'s {@code fullStock} fallback, and which the wiki
 *       gives as a bush's guaranteed <i>minimum</i>. Observed 13.35 a patch over 48 patches.</li>
 *   <li>Potato cactus −83 and cactus −47 against the wiki's measured averages, which were
 *       measured at level 99 with a cape, secateurs and ultracompost. This account is 85.</li>
 * </ul>
 *
 * <p>None of that is luck and none of it is actionable. It is the plugin quoting a number it had
 * already labelled a guess — {@code Basis.EMPIRICAL}, or a floor — and then subtracting it from
 * reality. The luck percentile beside it never had this problem, because
 * {@link CropHarvestStats#hasLuckPercentile} has always required a real variance for every patch
 * in the total. The surplus now asks the same question.
 */
public class UnmodelledCropsCarryNoSurplusTest
{
	private HarvestStatsStore stats;

	@Before
	public void setUp()
	{
		stats = construct(HarvestStatsStore.class, Mockito.mock(ConfigManager.class), new Gson());
		stats.load();
	}

	/** Fixture check: these two crops really are on opposite sides of the model. */
	@Test
	public void aBushHasNoPublishedSpreadAndAnAllotmentDoes()
	{
		assertNull("a bush has no chance-to-save constants to compute with",
			CropYield.forProduce(Produce.WHITEBERRIES));
		assertNotNull("an allotment does", CropYield.forProduce(Produce.WATERMELON));
	}

	@Test
	public void aBushHarvestAddsNothingToTheAccountWideSurplus()
	{
		for (int patch = 0; patch < 12; patch++)
		{
			// Thirteen berries against the fallback's four, which is the shape of the +449.
			record(bushPatch(), Produce.WHITEBERRIES, 13);
		}

		CropHarvestStats berries = onlyCrop();
		assertEquals("fixture: the patches were recorded", 12, berries.getHarvests());
		assertTrue("fixture: and they are well over the fallback figure",
			berries.getItems() > berries.getPredicted());
		assertEquals("fixture: with no variance behind any of them",
			0, berries.getVariancePatches());

		assertFalse(berries.hasSurplus());
		assertEquals("four is a bush's floor, not its expectation - the gap is not a surplus",
			0.0, berries.getSurplus(), 1e-9);
		assertEquals(0.0, stats.getTotalSurplus(), 1e-9);
	}

	/** The control: a crop with published constants still reports how far off it landed. */
	@Test
	public void anAllotmentWithAPublishedSpreadStillReportsItsSurplus()
	{
		for (int patch = 0; patch < 12; patch++)
		{
			record(allotmentPatch(), Produce.WATERMELON, 40);
		}

		CropHarvestStats melons = onlyCrop();
		assertTrue("a crop the model can actually score keeps its plus-or-minus",
			melons.hasSurplus());
		assertEquals(melons.getItems() - melons.getPredicted(), melons.getSurplus(), 1e-9);
		assertEquals(melons.getSurplus(), stats.getTotalSurplus(), 1e-9);
	}

	/**
	 * One patch is a roll, not a tendency.
	 *
	 * <p>The luck table showed a plus-or-minus for crops with a single completed patch — seaweed
	 * +15.1, torstol +1.9 — while the percentile column beside it hid itself below twenty for
	 * exactly that reason. A herb patch scatters by three or four either way on its own.
	 */
	@Test
	public void oneOrTwoPatchesCarryNoPlusOrMinusEither()
	{
		record(allotmentPatch(), Produce.WATERMELON, 40);

		CropHarvestStats melons = onlyCrop();
		assertEquals("fixture: modelled, but only one patch of it", 1, melons.getVariancePatches());
		assertFalse(melons.hasSurplus());
		assertEquals(0.0, melons.getSurplus(), 1e-9);
	}

	// ------------------------------------------------------------------- helpers

	private void record(FarmPatch patch, Produce produce, int items)
	{
		HarvestRecord record = new HarvestRecord(patch, produce, CompostTier.ULTRACOMPOST, 85,
			FarmingBonuses.NONE, 0);
		record.addItems(items);
		record.addXp(1);
		record.markCompleted();
		stats.record(record);
	}

	private CropHarvestStats onlyCrop()
	{
		assertEquals("expected exactly one crop in the store", 1, stats.getByCrop().size());
		return stats.getByCrop().get(0);
	}

	private static FarmPatch bushPatch()
	{
		return first(com.dooglemaps.data.PatchImplementation.BUSH);
	}

	private static FarmPatch allotmentPatch()
	{
		return first(com.dooglemaps.data.PatchImplementation.ALLOTMENT);
	}

	private static FarmPatch first(com.dooglemaps.data.PatchImplementation type)
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(type))
		{
			return patch;
		}
		throw new AssertionError("no " + type + " patch in the world data");
	}
}
