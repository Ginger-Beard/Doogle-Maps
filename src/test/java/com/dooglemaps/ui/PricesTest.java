package com.dooglemaps.ui;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.ItemPrices;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.Seed;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Covers the Stats tab's coin arithmetic.
 *
 * <p>Two of these numbers have already been wrong in ways a glance would not catch, and both
 * were unit errors rather than sums: charging a <b>tree run its seed price</b> instead of the
 * sapling's understates it by an order of magnitude, and charging an <b>allotment one seed</b>
 * instead of three understates every planting on the cheapest patches. So the tests here pin
 * which item and which multiplier each cost is built from, not just that multiplication works.
 *
 * <p>The price source ({@link ItemPrices}) is the one mock, because it is a cache filled on
 * the client thread — everything else is the real enums, which is where the per-patch counts
 * and planted-item rules actually live.
 */
public class PricesTest
{
	private ItemPrices items;
	private Prices prices;

	@Before
	public void setUp()
	{
		items = Mockito.mock(ItemPrices.class);
		prices = new Prices(items);
	}

	private void priced(int itemId, int price)
	{
		when(items.get(itemId)).thenReturn(price);
	}

	@Test
	public void aPileOfCropsIsWorthPriceTimesCount()
	{
		priced(Produce.RANARR.getItemID(), 7_000);

		assertEquals(7_000L * 20, prices.valueOf(Produce.RANARR, 20));
	}

	/** Fractional counts exist — predicted yields are averages — and they round, not truncate. */
	@Test
	public void aFractionalCountRoundsToTheNearestCoin()
	{
		priced(Produce.RANARR.getItemID(), 7);

		assertEquals("7 x 2.5 is 17.5, which is 18 and not 17",
			18, prices.valueOf(Produce.RANARR, 2.5));
	}

	@Test
	public void nothingAndNobodyIsWorthNothing()
	{
		priced(Produce.RANARR.getItemID(), 7_000);

		assertEquals("no produce, no value", 0, prices.valueOf(null, 5));
		assertEquals("no count, no value", 0, prices.valueOf(Produce.RANARR, 0));
		assertEquals("a negative count is a bug upstream, not negative coins",
			0, prices.valueOf(Produce.RANARR, -3));
	}

	/**
	 * A crop the cache has no price for is worth zero, and says so via {@code isKnown}.
	 *
	 * <p>Zero rather than a guess is {@link ItemPrices#get}'s contract; {@code isKnown} is how
	 * the panel shows a gap instead of claiming a harvest was worthless. Before the item cache
	 * loads, every price looks like this.
	 */
	@Test
	public void anUnpricedCropIsAGapNotAZeroValuedHarvest()
	{
		// Nothing stubbed: the mock cache answers 0 for everything, like an unloaded one.
		assertEquals(0, prices.valueOf(Produce.RANARR, 20));
		assertFalse("and the caller can tell it is a gap", prices.isKnown(Produce.RANARR));

		priced(Produce.RANARR.getItemID(), 7_000);
		assertTrue(prices.isKnown(Produce.RANARR));
	}

	/** A single-seed crop costs exactly its seed price per patch. */
	@Test
	public void aHerbPatchCostsOneSeedEach()
	{
		assertEquals("fixture: herbs take one seed", 1, Seed.RANARR.getSeedsPerPatch());
		priced(Seed.RANARR.getPlantedItemID(), 30_000);

		assertEquals(30_000L * 4, prices.seedCost(Seed.RANARR, 4));
	}

	/** An allotment takes three seeds, and the cost has to say so or every potato run is cheap. */
	@Test
	public void anAllotmentChargesAllThreeSeeds()
	{
		assertEquals("fixture: allotments take three seeds", 3, Seed.POTATO.getSeedsPerPatch());
		priced(Seed.POTATO.getPlantedItemID(), 5);

		assertEquals(5L * 2 * 3, prices.seedCost(Seed.POTATO, 2));
	}

	/**
	 * A tree run is priced by the sapling, never the seed.
	 *
	 * <p>The sapling is what goes in the ground and what the player actually bought or grew;
	 * the seed can be an order of magnitude cheaper. Both are priced differently here on
	 * purpose, so charging the wrong one cannot pass by coincidence.
	 */
	@Test
	public void aTreeIsChargedItsSaplingNotItsSeed()
	{
		assertTrue("fixture: yews are planted as saplings", Seed.YEW.isSapling());
		priced(Seed.YEW.getItemID(), 60_000);            // the seed
		priced(Seed.YEW.getPlantedItemID(), 200_000);    // the sapling

		assertEquals("one sapling per patch, at the sapling's price",
			200_000L * 3, prices.seedCost(Seed.YEW, 3));
	}

	@Test
	public void noSeedOrNoPatchesCostsNothing()
	{
		priced(Seed.RANARR.getPlantedItemID(), 30_000);

		assertEquals(0, prices.seedCost(null, 4));
		assertEquals(0, prices.seedCost(Seed.RANARR, 0));
		assertEquals(0, prices.seedCost(Seed.RANARR, -1));
	}

	@Test
	public void compostIsChargedPerPatch()
	{
		priced(CompostTier.ULTRACOMPOST.getItemID(), 1_200);

		assertEquals(1_200L * 9, prices.compostCost(CompostTier.ULTRACOMPOST, 9));
	}

	/**
	 * Leaving a patch untreated is free, and must never go looking for a price.
	 *
	 * <p>NONE's item id is a sentinel (-1), so asking the cache for it would be asking for a
	 * price of nothing in particular — the branch has to short-circuit before the lookup.
	 */
	@Test
	public void untreatedPatchesAreFree()
	{
		assertEquals(0, prices.compostCost(CompostTier.NONE, 9));
	}

	@Test
	public void noTierOrNoPatchesCostsNothing()
	{
		priced(CompostTier.ULTRACOMPOST.getItemID(), 1_200);

		assertEquals(0, prices.compostCost(null, 9));
		assertEquals(0, prices.compostCost(CompostTier.ULTRACOMPOST, 0));
		assertEquals(0, prices.compostCost(CompostTier.ULTRACOMPOST, -2));
	}
}
