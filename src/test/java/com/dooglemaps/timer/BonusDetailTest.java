package com.dooglemaps.timer;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropYield;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.Seed;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers the two bonuses that do not behave like the others.
 *
 * <p>The Farmer's outfit multiplies experience and leaves the harvest alone; the diary rewards
 * belong to three specific patches rather than to the player. Both are easy to model as "just
 * another flag" and both would be wrong that way.
 */
public class BonusDetailTest
{
	// ------------------------------------------------------------------ outfit

	/**
	 * The pieces are not worth the same, which is unusual for a skilling outfit.
	 *
	 * <p>Most give a flat share each. These do not — the jacket is worth four times the boots —
	 * so assuming the usual 0.4% apiece would be wrong for three of the four.
	 */
	@Test
	public void outfitPiecesAreNotWorthTheSame()
	{
		assertEquals(0.004, FarmingOutfit.HAT.getBonus(), 1e-9);
		assertEquals(0.008, FarmingOutfit.TORSO.getBonus(), 1e-9);
		assertEquals(0.006, FarmingOutfit.LEGS.getBonus(), 1e-9);
		assertEquals(0.002, FarmingOutfit.BOOTS.getBonus(), 1e-9);
	}

	@Test
	public void theFullSetIsWorthTwoAndAHalfPercent()
	{
		double full = FarmingOutfit.bonusFor(Arrays.asList(FarmingOutfit.values()));
		assertEquals(0.025, full, 1e-9);
		assertEquals(FarmingOutfit.FULL_SET, full, 1e-9);

		// The set bonus only lands when all four are on.
		double threePieces = FarmingOutfit.bonusFor(Arrays.asList(
			FarmingOutfit.HAT, FarmingOutfit.TORSO, FarmingOutfit.LEGS));
		assertEquals(0.018, threePieces, 1e-9);
		assertTrue("the last piece is worth more than its own 0.2%",
			full - threePieces > FarmingOutfit.BOOTS.getBonus());
	}

	@Test
	public void nothingWornIsNoBonus()
	{
		assertEquals(0.0, FarmingOutfit.bonusFor(Collections.emptyList()), 1e-9);
	}

	/** Both body types wear the same outfit, and both must be recognised. */
	@Test
	public void bothBodyTypesAreRecognised()
	{
		for (FarmingOutfit piece : FarmingOutfit.values())
		{
			assertEquals(piece, FarmingOutfit.forItemId(piece.getMaleItemId()));
			assertEquals(piece, FarmingOutfit.forItemId(piece.getFemaleItemId()));
		}
		assertNull(FarmingOutfit.forItemId(995));
	}

	/**
	 * The outfit must never touch a harvest.
	 *
	 * <p>It is an experience multiplier. Applying it to a yield would inflate the number of
	 * items a patch gives, which no amount of clothing does.
	 */
	@Test
	public void theOutfitChangesExperienceAndNotYield()
	{
		FarmingBonuses bare = FarmingBonuses.NONE;
		FarmingBonuses dressed = bare.withOutfitBonus(FarmingOutfit.FULL_SET);

		assertEquals("yield is untouched",
			YieldEstimate.expectedHarvest(CropYield.forSeed(Seed.RANARR), 99, 6, bare),
			YieldEstimate.expectedHarvest(CropYield.forSeed(Seed.RANARR), 99, 6, dressed),
			1e-9);

		assertEquals(1000.0, bare.applyOutfit(1000), 1e-9);
		assertEquals(1025.0, dressed.applyOutfit(1000), 1e-9);
	}

	// ------------------------------------------------------------------ diaries

	private static FarmPatch herbPatch(String regionName)
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(
			com.dooglemaps.data.PatchImplementation.HERB))
		{
			if (patch.getRegion().getName().equals(regionName))
			{
				return patch;
			}
		}
		throw new IllegalStateException("no herb patch in " + regionName);
	}

	@Test
	public void kandarinImprovesCatherbyOnly()
	{
		DiaryBonus.Completed elite = new DiaryBonus.Completed(true, true, true, false);

		assertEquals(25, DiaryBonus.forPatch(herbPatch("Catherby"), elite));
		assertEquals("Falador's herb patch gets nothing from Kandarin",
			0, DiaryBonus.forPatch(herbPatch("Falador"), elite));
	}

	/** The three Kandarin tiers do not stack; only the best one counts. */
	@Test
	public void onlyTheHighestKandarinTierCounts()
	{
		FarmPatch catherby = herbPatch("Catherby");

		assertEquals(10, DiaryBonus.forPatch(catherby,
			new DiaryBonus.Completed(true, false, false, false)));
		assertEquals(17, DiaryBonus.forPatch(catherby,
			new DiaryBonus.Completed(true, true, false, false)));
		assertEquals(25, DiaryBonus.forPatch(catherby,
			new DiaryBonus.Completed(true, true, true, false)));
	}

	@Test
	public void kourendImprovesHosidiusAndTheGuild()
	{
		DiaryBonus.Completed kourend = new DiaryBonus.Completed(false, false, false, true);

		assertEquals(10, DiaryBonus.forPatch(herbPatch("Kourend"), kourend));
		assertEquals(10, DiaryBonus.forPatch(herbPatch("Farming Guild"), kourend));
		assertEquals(0, DiaryBonus.forPatch(herbPatch("Ardougne"), kourend));
	}

	/** Only herb patches benefit, even in the right region. */
	@Test
	public void onlyHerbPatchesBenefit()
	{
		DiaryBonus.Completed everything = new DiaryBonus.Completed(true, true, true, true);

		for (FarmPatch patch : FarmingWorldData.getPatches(
			com.dooglemaps.data.PatchImplementation.ALLOTMENT))
		{
			assertEquals(patch + " should get no diary bonus",
				0, DiaryBonus.forPatch(patch, everything));
		}
		assertEquals(0, DiaryBonus.forPatch(null, everything));
	}

	/**
	 * The diary is added to the constants, so it is worth more than it looks.
	 *
	 * <p>Elite Kandarin's +25 beats the magic secateurs on a herb patch, and the two stack.
	 */
	@Test
	public void theDiaryOutweighsTheSecateurs()
	{
		CropYield ranarr = CropYield.forSeed(Seed.RANARR);
		double bare = YieldEstimate.expectedHarvest(ranarr, 99, CompostTier.ULTRACOMPOST,
			FarmingBonuses.NONE);
		double secateurs = YieldEstimate.expectedHarvest(ranarr, 99, CompostTier.ULTRACOMPOST,
			new FarmingBonuses(true, false, false, 0));
		double diary = YieldEstimate.expectedHarvest(ranarr, 99, CompostTier.ULTRACOMPOST,
			FarmingBonuses.NONE.withDiaryBonus(25));

		assertTrue("elite Kandarin should beat the secateurs", diary > secateurs);
		assertTrue(secateurs > bare);
	}

	/**
	 * The reported comparison, pinned: Weiss against Hosidius, both ranarr and ultracomposted.
	 *
	 * <p>At level 80 the diary is worth about half a herb, so both patches display as "9" and
	 * look identical even though the bonus is applied. That is why the tooltip carries a
	 * decimal place and names the diary — the difference is real and was invisible.
	 */
	@Test
	public void theDiaryIsWorthAboutHalfAHerbAtEighty()
	{
		CropYield ranarr = CropYield.forSeed(Seed.RANARR);
		// Magic secateurs in the inventory, no Farming cape.
		FarmingBonuses secateurs = new FarmingBonuses(true, false, false, 0);

		double weiss = YieldEstimate.expectedHarvest(
			ranarr, 80, CompostTier.ULTRACOMPOST, secateurs);
		double hosidius = YieldEstimate.expectedHarvest(
			ranarr, 80, CompostTier.ULTRACOMPOST, secateurs.withDiaryBonus(10));

		assertEquals(8.73, weiss, 0.05);
		assertEquals(9.25, hosidius, 0.05);
		assertEquals("both round to the same whole number, which is what hid it",
			Math.round(weiss), Math.round(hosidius));
		assertTrue("but the diary patch is genuinely better", hosidius > weiss);
	}

	/** By 99 the same difference does cross a rounding boundary. */
	@Test
	public void atNinetyNineTheDifferenceShows()
	{
		CropYield ranarr = CropYield.forSeed(Seed.RANARR);
		FarmingBonuses secateurs = new FarmingBonuses(true, false, false, 0);

		long weiss = Math.round(YieldEstimate.expectedHarvest(
			ranarr, 99, CompostTier.ULTRACOMPOST, secateurs));
		long hosidius = Math.round(YieldEstimate.expectedHarvest(
			ranarr, 99, CompostTier.ULTRACOMPOST, secateurs.withDiaryBonus(10)));

		assertEquals(9, weiss);
		assertEquals(10, hosidius);
	}

	@Test
	public void everyEligiblePatchIsAHerbPatch()
	{
		assertTrue(DiaryBonus.isEligible(herbPatch("Catherby")));
		assertTrue(DiaryBonus.isEligible(herbPatch("Kourend")));
		assertTrue(DiaryBonus.isEligible(herbPatch("Farming Guild")));
		assertNotNull(herbPatch("Weiss"));
		assertTrue("Weiss is a herb patch but not one the diaries reach",
			!DiaryBonus.isEligible(herbPatch("Weiss")));
	}
}
