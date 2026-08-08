package com.dooglemaps.timer;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Seed;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Experience;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers what a bank of seeds is worth planted out.
 *
 * <p>The arithmetic per patch is already pinned by {@code YieldEstimateTest} and
 * {@code CropYieldModelTest}, so this is about the three things layered on top: that levelling
 * up mid-stack is actually accounted for, that a stack of seeds is converted to patches rather
 * than assumed to be one each, and that the loop doing it always terminates.
 */
public class PlantOutEstimateTest
{
	private static final FarmingBonuses BARE = FarmingBonuses.NONE;

	/** Five herb patches and five allotments, which is about what a mid-level account has. */
	private static final Map<PatchImplementation, Integer> PATCHES = patches();

	/**
	 * Levelling up as you go beats standing still, and by a margin worth showing.
	 *
	 * <p>The whole reason the iteration exists. A thousand guam seeds at level 9 is many levels'
	 * worth, and chance-to-save climbs the whole way, so the naive figure is the one number here
	 * that is definitely wrong.
	 */
	@Test
	public void levellingUpMidStackBeatsStandingStill()
	{
		PlantOutEstimate.Projection projection = project(Seed.GUAM, 1000, xpFor(9));

		PlantOutEstimate.Line guam = only(projection);
		assertTrue("the iterated figure must beat the flat one: " + guam.getXp() + " vs "
				+ guam.getXpAtStartLevel(),
			guam.getXp() > guam.getXpAtStartLevel());
		assertTrue("and the level has to move with it", projection.getEndLevel() > 9);
		assertEquals(9, projection.getStartLevel());
	}

	/**
	 * One cycle cannot boost itself, because the experience arrives after it is all planted.
	 *
	 * <p>The tightest check on the loop: with more patches than seeds there is exactly one
	 * cycle, so the iterated figure must equal the flat one to the last decimal. An off-by-one
	 * that levelled up before the first patch instead of after it would show here and nowhere
	 * else.
	 */
	@Test
	public void aSingleCycleMatchesTheFlatFigureExactly()
	{
		PlantOutEstimate.Line guam = only(project(Seed.GUAM, 3, xpFor(50)));

		assertEquals(3, guam.getPatches());
		assertEquals(guam.getXpAtStartLevel(), guam.getXp(), 1e-9);
	}

	/**
	 * A stack of seeds is not a stack of patches.
	 *
	 * <p>An allotment takes three seeds, so a hundred potato seeds is thirty-three patches and
	 * one seed left in the bank. Treating the count as patches would overstate a potato stack
	 * threefold — and allotments are exactly where people hold seeds in the hundreds.
	 */
	@Test
	public void seedsAreConvertedToPatchesAtTheRateThePatchTakesThem()
	{
		assertEquals(3, Seed.POTATO.getSeedsPerPatch());

		PlantOutEstimate.Line potato = only(project(Seed.POTATO, 100, xpFor(50)));
		assertEquals("100 seeds, three to a patch", 33, potato.getPatches());
		assertEquals("the seed count is still reported as held", 100, potato.getSeeds());
	}

	/**
	 * A crop whose patches are all switched off is left out entirely.
	 *
	 * <p>The availability invariant, and also the loop's termination condition — a cycle of zero
	 * patches would never finish planting the stack.
	 */
	@Test
	public void aCropWithNoAvailablePatchesIsLeftOut()
	{
		Map<PatchImplementation, Integer> none = new EnumMap<>(PatchImplementation.class);
		none.put(PatchImplementation.HERB, 0);

		PlantOutEstimate.Projection projection = PlantOutEstimate.of(
			owned(Seed.GUAM, 500), none, compost(CompostTier.NONE), BARE, xpFor(50));

		assertTrue("no patches means nothing to project", projection.isEmpty());
		assertEquals("and it is not a locked crop either", 0, projection.getLockedCrops());
	}

	/**
	 * A seed you cannot reach is counted as a gap, not folded into the total.
	 *
	 * <p>Torstol alone at level 20 goes nowhere: there is nothing plantable to raise the level
	 * with, so the simulation stops on its first step rather than inventing a route.
	 */
	@Test
	public void aCropYouCannotReachIsCountedRatherThanProjected()
	{
		assertTrue(Seed.TORSTOL.getLevelRequirement() > 20);

		PlantOutEstimate.Projection projection = project(Seed.TORSTOL, 50, xpFor(20));

		assertTrue(projection.isEmpty());
		assertEquals(1, projection.getLockedCrops());
		assertEquals(0.0, projection.getXp(), 1e-9);
	}

	// -------------------------------------------------------------- the path

	/**
	 * The whole point: crops unlock as the bank is planted, and then get planted themselves.
	 *
	 * <p>Starting at level 1 nothing but potato is plantable — guam needs 9 and ranarr 32. The
	 * potato stack has to carry the account far enough for each of the others to come into play,
	 * and every one of them has to end up in the ground. A model that priced each stack from
	 * level 1 would report both herbs as unplantable and stop.
	 */
	@Test
	public void cropsUnlockAsYouPlantAndThenGetPlanted()
	{
		Map<Seed, Integer> owned = new LinkedHashMap<>();
		owned.put(Seed.POTATO, 9000);
		owned.put(Seed.GUAM, 500);
		owned.put(Seed.RANARR, 500);

		PlantOutEstimate.Projection projection = PlantOutEstimate.of(
			owned, PATCHES, compost(CompostTier.ULTRACOMPOST), BARE, 0);

		assertEquals("starts at the bottom", 1, projection.getStartLevel());
		assertEquals("nothing is out of reach on this path", 0, projection.getLockedCrops());
		assertEquals("all three end up planted", 3, projection.getLines().size());

		for (PlantOutEstimate.Line line : projection.getLines())
		{
			assertTrue(line.getSeed() + " was never planted", line.getPatches() > 0);
		}

		List<Seed> unlocked = new ArrayList<>();
		int previous = 0;
		for (PlantOutEstimate.Unlock unlock : projection.getUnlocks())
		{
			assertTrue("unlocks must read in the order they happen",
				unlock.getLevel() >= previous);
			previous = unlock.getLevel();
			unlocked.add(unlock.getSeed());
		}

		assertTrue("guam unlocks on the way, " + unlocked, unlocked.contains(Seed.GUAM));
		assertTrue("and so does ranarr, " + unlocked, unlocked.contains(Seed.RANARR));
		assertTrue("potato was plantable from the start and is not an unlock",
			!unlocked.contains(Seed.POTATO));
	}

	/** An unlock reports the level it happens at and what is waiting in the bank for it. */
	@Test
	public void anUnlockNamesItsLevelAndTheStockBehindIt()
	{
		Map<Seed, Integer> owned = new LinkedHashMap<>();
		owned.put(Seed.POTATO, 9000);
		owned.put(Seed.RANARR, 250);

		PlantOutEstimate.Projection projection = PlantOutEstimate.of(
			owned, PATCHES, compost(CompostTier.ULTRACOMPOST), BARE, 0);

		PlantOutEstimate.Unlock ranarr = null;
		for (PlantOutEstimate.Unlock unlock : projection.getUnlocks())
		{
			if (unlock.getSeed() == Seed.RANARR)
			{
				ranarr = unlock;
			}
		}

		assertNotNull("ranarr should unlock on this path", ranarr);
		assertTrue("at or past its requirement of " + Seed.RANARR.getLevelRequirement(),
			ranarr.getLevel() >= Seed.RANARR.getLevelRequirement());
		assertEquals("and it names what is banked for it", 250, ranarr.getSeeds());
	}

	/**
	 * Reaching 99 splits the bank into what got you there and what was surplus.
	 *
	 * <p>Both halves are worth having: the first is the path, the second is the answer to "and
	 * then what". They have to add up to what was actually planted, which is the check here.
	 */
	@Test
	public void reachingNinetyNineSplitsTheBank()
	{
		// 80 to 99 is 11M experience and an untreated ranarr patch pays about 156 of it, so this
		// wants six figures of seed rather than the five it first had.
		PlantOutEstimate.Projection projection = project(Seed.RANARR, 100_000, xpFor(80));

		assertTrue("100,000 ranarr is more than enough", projection.isReachesMaxLevel());
		assertEquals(99, projection.getEndLevel());
		assertTrue(projection.getSeedsToMaxLevel() > 0);
		assertTrue(projection.getSeedsBeyondMaxLevel() > 0);

		int planted = only(projection).getPatches() * Seed.RANARR.getSeedsPerPatch();
		assertEquals("the two halves are the whole thing", planted,
			projection.getSeedsToMaxLevel() + projection.getSeedsBeyondMaxLevel());
		assertEquals(projection.getXp(),
			projection.getXpToMaxLevel() + projection.getXpBeyondMaxLevel(), 1e-6);
	}

	/** A bank that falls short says so, and claims no leftovers. */
	@Test
	public void aBankThatFallsShortOfNinetyNineSaysSo()
	{
		PlantOutEstimate.Projection projection = project(Seed.RANARR, 200, xpFor(60));

		assertTrue(!projection.isReachesMaxLevel());
		assertTrue(projection.getEndLevel() < 99);
		assertEquals(0, projection.getSeedsBeyondMaxLevel());
		assertEquals(0.0, projection.getXpBeyondMaxLevel(), 1e-9);
	}

	/**
	 * The best experience per patch goes in first, which is what the path claims to be.
	 *
	 * <p>Observable through how much of the bank 99 costs. From 98 it takes about 1.23M
	 * experience; a ranarr patch pays roughly 260 of it and a guam patch roughly 100, so
	 * best-first arrives on something like five thousand seeds and worst-first would need three
	 * times that. The bound is loose on purpose — it is checking which crop was chosen, not
	 * reproducing the yield arithmetic.
	 */
	@Test
	public void theBestCropPerPatchIsPlantedFirst()
	{
		Map<Seed, Integer> owned = new LinkedHashMap<>();
		owned.put(Seed.GUAM, 40_000);
		owned.put(Seed.RANARR, 40_000);

		PlantOutEstimate.Projection projection = PlantOutEstimate.of(
			owned, PATCHES, compost(CompostTier.ULTRACOMPOST), BARE, xpFor(98));

		assertTrue(projection.isReachesMaxLevel());
		assertTrue("99 should cost about 5,000 ranarr, not 15,000 guam: "
				+ projection.getSeedsToMaxLevel(),
			projection.getSeedsToMaxLevel() < 9_000);
	}

	/** Ordered by experience per seed, which is what makes the list a planting guide. */
	@Test
	public void rowsAreOrderedByExperiencePerSeed()
	{
		Map<Seed, Integer> owned = new LinkedHashMap<>();
		owned.put(Seed.GUAM, 100);
		owned.put(Seed.RANARR, 100);
		owned.put(Seed.POTATO, 100);

		PlantOutEstimate.Projection projection = PlantOutEstimate.of(
			owned, PATCHES, compost(CompostTier.ULTRACOMPOST), BARE, xpFor(85));

		double previous = Double.MAX_VALUE;
		for (PlantOutEstimate.Line line : projection.getLines())
		{
			assertTrue("out of order at " + line.getSeed(), line.getXpPerSeed() <= previous);
			previous = line.getXpPerSeed();
		}
		assertEquals("ranarr pays more per seed than guam", Seed.RANARR,
			projection.getLines().get(0).getSeed());
	}

	/**
	 * A huge stack through one patch terminates, and at 99 stops recomputing.
	 *
	 * <p>The loop plants a cycle at a time, so ten thousand seeds through a single patch is ten
	 * thousand iterations unless the level stops moving. It does at 99, and the short-circuit
	 * there is what keeps a pathological bank from stalling a panel repaint.
	 */
	@Test(timeout = 2000)
	public void aPathologicalStackStillTerminates()
	{
		Map<PatchImplementation, Integer> one = new EnumMap<>(PatchImplementation.class);
		one.put(PatchImplementation.HERB, 1);

		PlantOutEstimate.Projection projection = PlantOutEstimate.of(
			owned(Seed.RANARR, 100_000), one, compost(CompostTier.NONE), BARE,
			Experience.MAX_SKILL_XP);

		assertEquals("already capped", 99, projection.getStartLevel());
		assertEquals(99, projection.getEndLevel());
		assertTrue(only(projection).getXp() > 0);
	}

	/** The end level follows the experience the projection says you would gain. */
	@Test
	public void theEndLevelIsWhereTheTotalActuallyLandsYou()
	{
		PlantOutEstimate.Projection projection = project(Seed.RANARR, 400, xpFor(60));

		int expected = Math.min(99,
			Experience.getLevelForXp((int) (xpFor(60) + projection.getXp())));
		assertEquals(expected, projection.getEndLevel());
	}

	/** Trees pay for planting and checking rather than for the harvest, and still count. */
	@Test
	public void aCropThatPaysNothingPerItemIsStillWorthPlanting()
	{
		assertNotNull(com.dooglemaps.data.CropXp.forSeed(Seed.OAK));

		Map<PatchImplementation, Integer> trees = new EnumMap<>(PatchImplementation.class);
		trees.put(PatchImplementation.TREE, 5);

		PlantOutEstimate.Projection projection = PlantOutEstimate.of(
			owned(Seed.OAK, 10), trees, compost(CompostTier.NONE), BARE, xpFor(20));

		assertTrue("planting and check-health experience is real", only(projection).getXp() > 0);
	}

	// ------------------------------------------------------------------ helpers

	private static PlantOutEstimate.Projection project(Seed seed, int count, int farmingXp)
	{
		return PlantOutEstimate.of(owned(seed, count), PATCHES,
			compost(CompostTier.NONE), BARE, farmingXp);
	}

	private static PlantOutEstimate.Line only(PlantOutEstimate.Projection projection)
	{
		assertEquals("expected one crop", 1, projection.getLines().size());
		return projection.getLines().get(0);
	}

	private static Map<Seed, Integer> owned(Seed seed, int count)
	{
		Map<Seed, Integer> owned = new LinkedHashMap<>();
		owned.put(seed, count);
		return owned;
	}

	private static Map<PatchImplementation, Integer> patches()
	{
		Map<PatchImplementation, Integer> counts = new EnumMap<>(PatchImplementation.class);
		counts.put(PatchImplementation.HERB, 5);
		counts.put(PatchImplementation.ALLOTMENT, 5);
		counts.put(PatchImplementation.TREE, 5);
		return counts;
	}

	private static Map<PatchImplementation, CompostTier> compost(CompostTier tier)
	{
		Map<PatchImplementation, CompostTier> tiers = new EnumMap<>(PatchImplementation.class);
		for (PatchImplementation type : PatchImplementation.values())
		{
			tiers.put(type, tier);
		}
		return tiers;
	}

	private static int xpFor(int level)
	{
		return Experience.getXpForLevel(level);
	}

	/** Guards the level the levelling-up test starts from: guam must be plantable at 9. */
	@Test
	public void guamIsPlantableFromTheStart()
	{
		assertTrue(Seed.GUAM.getLevelRequirement() <= 9);
	}
}
