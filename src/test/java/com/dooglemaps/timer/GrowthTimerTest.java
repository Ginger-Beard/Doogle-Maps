package com.dooglemaps.timer;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.Produce;
import com.dooglemaps.state.PatchSnapshot;
import java.lang.reflect.Constructor;
import java.time.Instant;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Covers the growth-tick grid and offline catch-up.
 *
 * <p>This is the part of the plugin most likely to be quietly wrong: the arithmetic is
 * unremarkable, but a sign error in the offset or an off-by-one in the stage count shows
 * up as timers that are merely plausible rather than correct.
 */
public class GrowthTimerTest
{
	private static final String TIMETRACKING = "timetracking";

	/** Falador herb patch: an ordinary 20-minute herb, unprotected by default. */
	private static final String HERB_PATCH_KEY = "12083.4774";

	private ConfigManager configManager;
	private GrowthTimer timer;

	@Before
	public void setUp() throws Exception
	{
		configManager = Mockito.mock(ConfigManager.class);

		// No offset observed anywhere unless a test says otherwise.
		when(configManager.getRSProfileConfiguration(anyString(), anyString())).thenReturn(null);
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(int.class))).thenReturn(null);

		Constructor<GrowthTimer> constructor = GrowthTimer.class.getDeclaredConstructor(ConfigManager.class);
		constructor.setAccessible(true);
		timer = constructor.newInstance(configManager);
	}

	private void givenObservedOffset(int minutes, int precision)
	{
		when(configManager.getRSProfileConfiguration(TIMETRACKING, "farmTickOffset", int.class))
			.thenReturn(minutes);
		when(configManager.getRSProfileConfiguration(TIMETRACKING, "farmTickOffsetPrecision", int.class))
			.thenReturn(precision);
	}

	private static PatchSnapshot snapshot(Produce produce, CropState state, int stage, long lastSeen)
	{
		PatchSnapshot snapshot = new PatchSnapshot();
		snapshot.setPatchKey(HERB_PATCH_KEY);
		snapshot.setProduce(produce);
		snapshot.setCropState(state);
		snapshot.setStage(stage);
		snapshot.setLastSeen(lastSeen);
		return snapshot;
	}

	private static FarmPatch herbPatch()
	{
		FarmPatch patch = FarmingWorldData.getPatch(HERB_PATCH_KEY);
		assertNotNull("test fixture patch " + HERB_PATCH_KEY + " no longer exists", patch);
		return patch;
	}

	// ------------------------------------------------------------ tick grid

	@Test
	public void ticksLandOnTheGridWithNoOffset()
	{
		// 1970-01-01 00:07:00 sits inside the 00:00-00:20 window of a 20-minute cycle.
		long sevenPastTheHour = 7 * 60;

		assertEquals(0, timer.getTickTime(20, 0, sevenPastTheHour));
		assertEquals(20 * 60, timer.getTickTime(20, 1, sevenPastTheHour));
		assertEquals(60 * 60, timer.getTickTime(20, 3, sevenPastTheHour));
	}

	@Test
	public void tickGridIsShiftedByTheAccountOffset()
	{
		// Offsets are stored positive but always run early, so a 5-minute offset moves
		// every 20-minute tick 5 minutes earlier: :15, :35, :55 rather than :00, :20, :40.
		givenObservedOffset(5, 40);

		assertEquals(-5 * 60, timer.getTickTime(20, 0, 7 * 60));
		assertEquals(15 * 60, timer.getTickTime(20, 1, 7 * 60));
	}

	@Test
	public void offsetFromAShorterCycleIsNotAppliedToALongerOne()
	{
		// An offset learned from a 5-minute cycle only pins that cycle's phase; it says
		// nothing about where a 40-minute tick lands.
		givenObservedOffset(3, 5);

		assertEquals("should fall back to the unshifted grid", 0, timer.getTickTime(40, 0, 7 * 60));
	}

	@Test
	public void offsetFromALongCycleIsTrustedEverywhere()
	{
		givenObservedOffset(37, 40);

		// 37 % 20 = 17 minutes of shift for a 20-minute cycle.
		assertEquals(-17 * 60, timer.getTickTime(20, 0, 0));
	}

	@Test
	public void tickGridWorksBeforeTheEpochBoundary()
	{
		// Guards against a negative-modulo bug: real timestamps are large, but the maths
		// must not depend on that.
		long justBeforeATick = -60;
		assertTrue(timer.getTickTime(20, 0, justBeforeATick) <= justBeforeATick);
	}

	// ----------------------------------------------------------- projection

	@Test
	public void unseenPatchesHaveNoProjection()
	{
		assertNull(timer.project(herbPatch(), null));
	}

	@Test
	public void freshlyPlantedHerbIsAtStageZero()
	{
		long now = Instant.now().getEpochSecond();
		PatchProjection projection = timer.project(herbPatch(), snapshot(Produce.RANARR, CropState.GROWING, 0, now));

		assertNotNull(projection);
		assertEquals(0, projection.getStage());
		assertEquals(Produce.RANARR.getStages(), projection.getStages());
		assertEquals(CropState.GROWING, projection.getCropState());
	}

	@Test
	public void aCropSeenLongAgoIsProjectedToFullyGrown()
	{
		// Ranarr takes 80 minutes; a day away is more than enough.
		long aDayAgo = Instant.now().getEpochSecond() - 86400;
		PatchProjection projection = timer.project(herbPatch(),
			snapshot(Produce.RANARR, CropState.GROWING, 0, aDayAgo));

		assertNotNull(projection);
		assertEquals("should be capped at the last stage", Produce.RANARR.getStages() - 1, projection.getStage());
		assertEquals("herbs ripen without a health check", CropState.HARVESTABLE, projection.getCropState());
		assertTrue(projection.isReady());
		assertTrue(projection.isStale());
	}

	@Test
	public void treesStayGrowingUntilTheirHealthIsChecked()
	{
		// A grown tree still reads as growing until the player checks it, so we must not
		// quietly promote it to harvestable.
		FarmPatch treePatch = FarmingWorldData.getPatch("11828.4771");
		assertNotNull(treePatch);
		assertTrue(treePatch.getImplementation().isHealthCheckRequired());

		long aWeekAgo = Instant.now().getEpochSecond() - 7 * 86400;
		PatchProjection projection = timer.project(treePatch,
			snapshot(Produce.MAGIC, CropState.GROWING, 0, aWeekAgo));

		assertNotNull(projection);
		assertEquals(CropState.GROWING, projection.getCropState());
		assertEquals(Produce.MAGIC.getStages() - 1, projection.getStage());
	}

	@Test
	public void doneEstimateAllowsAtLeastTheMinimumGrowTime()
	{
		long now = Instant.now().getEpochSecond();
		PatchProjection projection = timer.project(herbPatch(), snapshot(Produce.RANARR, CropState.GROWING, 0, now));

		assertNotNull(projection);
		long remaining = projection.getDoneEstimate() - now;

		// Four 20-minute ticks remain, and planting happened partway through the first, so
		// the wait is at most the nominal 80 minutes and more than the 60 that would remain
		// if a whole tick were skipped.
		assertTrue("estimate " + remaining + "s is too short", remaining > 60 * 60);
		assertTrue("estimate " + remaining + "s exceeds the nominal grow time", remaining <= 80 * 60);
	}

	/**
	 * A fully stocked fruit tree still counts as a crop that regrows.
	 *
	 * <p>The two ideas came out conflated the first time: "this crop regrows" is a property
	 * of the crop, while "more is on the way" is about right now. A full palm has nothing on
	 * the way, and reporting it as not-a-regrowing-crop hid the coconut count exactly when
	 * it was most worth seeing.
	 */
	@Test
	public void aFullyStockedFruitTreeStillCountsAsRegrowing()
	{
		FarmPatch fruitPatch = FarmingWorldData.getPatch("11317.4771");
		assertNotNull(fruitPatch);

		// Seven states, six fruit: the top state is a full tree.
		long now = Instant.now().getEpochSecond();
		int fullTree = Produce.PALM.getHarvestStages() - 1;
		PatchProjection projection = timer.project(fruitPatch,
			snapshot(Produce.PALM, CropState.HARVESTABLE, fullTree, now));

		assertNotNull(projection);
		assertTrue("a palm tree is a crop that regrows", projection.regrows());
		assertFalse("but a full one has nothing on the way", projection.isRegrowing());
		assertEquals("a full palm holds six coconuts, not seven", 6, projection.getLivesRemaining());
	}

	@Test
	public void aPartlyPickedFruitTreeIsRegrowing()
	{
		FarmPatch fruitPatch = FarmingWorldData.getPatch("11317.4771");
		assertNotNull(fruitPatch);

		long now = Instant.now().getEpochSecond();
		PatchProjection projection = timer.project(fruitPatch,
			snapshot(Produce.PALM, CropState.HARVESTABLE, 2, now));

		assertNotNull(projection);
		assertTrue(projection.regrows());
		assertTrue("picked from, so more is coming", projection.isRegrowing());
		assertEquals("the stage is the fruit count for a regrowing crop", 2,
			projection.getLivesRemaining());
	}

	/** Herbs do not regrow, so their "lives" must never be presented as a live count. */
	@Test
	public void herbsDoNotRegrow()
	{
		long now = Instant.now().getEpochSecond();
		PatchProjection projection = timer.project(herbPatch(),
			snapshot(Produce.RANARR, CropState.HARVESTABLE, 2, now));

		assertNotNull(projection);
		assertFalse(projection.regrows());
		assertFalse(projection.isRegrowing());
		assertEquals("three states, three lives - the stage is one below the count", 3,
			projection.getLivesRemaining());
	}

	/**
	 * A fruit tree with nothing on it reads as zero, not one.
	 *
	 * <p>The state that used to be reported as "1 fruit" is really "no fruit yet", which is
	 * the whole reason the two families cannot share the same arithmetic.
	 */
	@Test
	public void anEmptyFruitTreeHoldsNothing()
	{
		FarmPatch fruitPatch = FarmingWorldData.getPatch("11317.4771");
		assertNotNull(fruitPatch);

		long now = Instant.now().getEpochSecond();
		PatchProjection projection = timer.project(fruitPatch,
			snapshot(Produce.PALM, CropState.HARVESTABLE, 0, now));

		assertNotNull(projection);
		assertEquals(0, projection.getLivesRemaining());
		assertTrue("empty, so fruit is on its way", projection.isRegrowing());
	}

	@Test
	public void deadCropsGetNoTimer()
	{
		long now = Instant.now().getEpochSecond();
		PatchProjection projection = timer.project(herbPatch(), snapshot(Produce.RANARR, CropState.DEAD, 0, now));

		assertNotNull(projection);
		assertEquals(0, projection.getDoneEstimate());
		assertEquals(Confidence.NEEDS_ACTION, projection.getConfidence());
	}

	@Test
	public void emptyPatchesReadAsEmpty()
	{
		long now = Instant.now().getEpochSecond();
		PatchProjection projection = timer.project(herbPatch(), snapshot(Produce.WEEDS, CropState.GROWING, 3, now));

		assertNotNull(projection);
		assertTrue(projection.isEmpty());
		assertEquals(Confidence.EMPTY, projection.getConfidence());
	}

	// ---------------------------------------------------------- confidence

	@Test
	public void unprotectedHerbsPastTheirFirstStageAreOnlyAnEstimate()
	{
		long now = Instant.now().getEpochSecond();
		PatchProjection projection = timer.project(herbPatch(), snapshot(Produce.RANARR, CropState.GROWING, 1, now));

		assertNotNull(projection);
		assertEquals(Confidence.ESTIMATE, projection.getConfidence());
	}

	@Test
	public void aCropInItsFirstStageCannotHaveDiseased()
	{
		long now = Instant.now().getEpochSecond();
		PatchProjection projection = timer.project(herbPatch(), snapshot(Produce.RANARR, CropState.GROWING, 0, now));

		assertNotNull(projection);
		assertEquals(Confidence.CERTAIN, projection.getConfidence());
	}

	@Test
	public void payingAFarmerMakesTheTimerCertain()
	{
		long now = Instant.now().getEpochSecond();
		PatchSnapshot snapshot = snapshot(Produce.RANARR, CropState.GROWING, 2, now);
		snapshot.setPatchProtected(true);

		PatchProjection projection = timer.project(herbPatch(), snapshot);

		assertNotNull(projection);
		assertEquals(Confidence.CERTAIN, projection.getConfidence());
	}

	@Test
	public void weissHerbsAreCertainWithoutPayment()
	{
		// Reaching Weiss at all requires Making Friends with My Arm, which is exactly what
		// makes its patch disease-free.
		FarmPatch weiss = FarmingWorldData.getPatch("11325.4771");
		assertNotNull(weiss);

		long now = Instant.now().getEpochSecond();
		PatchProjection projection = timer.project(weiss, snapshot(Produce.RANARR, CropState.GROWING, 2, now));

		assertNotNull(projection);
		assertEquals(Confidence.CERTAIN, projection.getConfidence());
	}
}
