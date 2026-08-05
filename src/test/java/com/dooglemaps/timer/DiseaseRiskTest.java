package com.dooglemaps.timer;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Produce;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers the chance a crop reaches harvest at all.
 *
 * <p>Yield estimates used to assume every patch survives, which overstated an untreated herb
 * run by roughly double. Disease is rolled once per growth tick, so a five-stage herb takes
 * three rolls at 27/128 — a coin flip on whether anything comes home.
 */
public class DiseaseRiskTest
{
	private static FarmPatch herbPatch(String regionName)
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.HERB))
		{
			if (patch.getRegion().getName().equals(regionName))
			{
				return patch;
			}
		}
		throw new IllegalStateException("no herb patch in " + regionName);
	}

	/** The figure that motivated all of this: an untreated herb is about a coin flip. */
	@Test
	public void anUntreatedHerbPatchIsRoughlyACoinFlip()
	{
		double survival = DiseaseRisk.survivalChance(
			herbPatch("Falador"), Produce.RANARR, CompostTier.NONE, false);

		assertEquals(0.49, survival, 0.02);
	}

	/**
	 * Compost is the whole defence, and the reason it is worth carrying twice over.
	 *
	 * <p>It buys harvest lives <i>and</i> cuts the disease rate by up to nine tenths, which
	 * turns a coin flip into a near certainty.
	 */
	@Test
	public void compostIsWhatMakesAHerbPatchSurvive()
	{
		FarmPatch falador = herbPatch("Falador");

		double none = DiseaseRisk.survivalChance(falador, Produce.RANARR, CompostTier.NONE, false);
		double compost = DiseaseRisk.survivalChance(falador, Produce.RANARR, CompostTier.COMPOST, false);
		double zuper = DiseaseRisk.survivalChance(falador, Produce.RANARR, CompostTier.SUPERCOMPOST, false);
		double ultra = DiseaseRisk.survivalChance(falador, Produce.RANARR, CompostTier.ULTRACOMPOST, false);

		assertTrue(none < compost);
		assertTrue(compost < zuper);
		assertTrue(zuper < ultra);
		assertEquals("ultracompost should be near certain", 0.95, ultra, 0.02);
	}

	/** Where disease is impossible, the estimate must not discount anything. */
	@Test
	public void immunePatchesNeverLoseAnything()
	{
		assertEquals(1.0, DiseaseRisk.survivalChance(
			herbPatch("Weiss"), Produce.RANARR, CompostTier.NONE, false), 1e-9);
		assertEquals(1.0, DiseaseRisk.survivalChance(
			herbPatch("Troll Stronghold"), Produce.RANARR, CompostTier.NONE, false), 1e-9);
	}

	/** Poison ivy cannot be diseased wherever it grows. */
	@Test
	public void immuneCropsNeverLoseAnythingEither()
	{
		FarmPatch bush = FarmingWorldData.getPatches(PatchImplementation.BUSH).get(0);
		assertEquals(1.0, DiseaseRisk.survivalChance(
			bush, Produce.POISON_IVY, CompostTier.NONE, false), 1e-9);
	}

	/** Paying a farmer removes the risk outright. */
	@Test
	public void aPaidFarmerRemovesTheDiscount()
	{
		assertEquals(1.0, DiseaseRisk.survivalChance(
			herbPatch("Falador"), Produce.RANARR, CompostTier.NONE, true), 1e-9);
	}

	/**
	 * Crops with no published rate are treated as safe rather than guessed at.
	 *
	 * <p>That understates the risk for allotments and hops, which is the direction that at
	 * least does not invent a penalty. {@code isRiskKnown} exists so the UI can tell the two
	 * apart rather than presenting a guess as a fact.
	 */
	@Test
	public void unpublishedRatesAreNotInvented()
	{
		assertTrue(DiseaseRisk.isRiskKnown(Produce.RANARR));
		assertFalse(DiseaseRisk.isRiskKnown(Produce.WATERMELON));

		assertEquals(1.0, DiseaseRisk.survivalChance(
			FarmingWorldData.getPatches(PatchImplementation.ALLOTMENT).get(0),
			Produce.WATERMELON, CompostTier.NONE, false), 1e-9);
	}

	/** A magic tree is far hardier than a maple, and both are hardier than a herb. */
	@Test
	public void treesDifferBySpecies()
	{
		FarmPatch tree = FarmingWorldData.getPatches(PatchImplementation.TREE).get(0);

		double maple = DiseaseRisk.survivalChance(tree, Produce.MAPLE, CompostTier.NONE, false);
		double magic = DiseaseRisk.survivalChance(tree, Produce.MAGIC, CompostTier.NONE, false);

		assertTrue("a magic tree is diseased less often per tick", magic > 0);
		assertTrue(maple > 0);
		assertTrue("but it also has far more ticks to survive", magic < 1);
	}
}
