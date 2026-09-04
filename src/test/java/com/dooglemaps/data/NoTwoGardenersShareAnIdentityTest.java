package com.dooglemaps.data;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A global check on the guard added to {@link FarmerVariants#same}: every distinct farmer id the
 * world data itself carries names a different gardener, whatever two of them happen to be called.
 *
 * <p>This is the Fossil Island squirrels' bug stated as a property rather than a single example —
 * {@code FarmingWorldData} is the one place that already knows which ids are distinct gardeners
 * (one entry per patch's {@code farmer}), so nothing here needs to enumerate the collisions by
 * hand the way {@code FarmerVariants.GROUPS} lists the ones that are secretly the <i>same</i>
 * person.
 */
public class NoTwoGardenersShareAnIdentityTest
{
	@Test
	public void everyPairOfDistinctWorldFarmerIdsIsADifferentGardener()
	{
		List<Integer> farmerIds = new ArrayList<>();
		for (FarmPatch patch : FarmingWorldData.getAllPatches())
		{
			if (patch.isProtectable() && !farmerIds.contains(patch.getFarmer()))
			{
				farmerIds.add(patch.getFarmer());
			}
		}

		// A non-trivial set to check, so the property is actually exercised rather than
		// vacuously true because the world data grew empty out from under this test.
		assertTrue("fixture: expected more than a couple of distinct farmer ids, found "
			+ farmerIds.size(), farmerIds.size() > 10);

		for (int i = 0; i < farmerIds.size(); i++)
		{
			for (int j = i + 1; j < farmerIds.size(); j++)
			{
				int a = farmerIds.get(i);
				int b = farmerIds.get(j);
				assertFalse("two distinct world-data farmer ids read as the same gardener: "
						+ a + " and " + b,
					FarmerVariants.same(a, b));
			}
		}
	}
}
