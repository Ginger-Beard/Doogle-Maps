package com.dooglemaps.capture;

import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.ProduceState;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers which varbit transitions count as a growth tick landing.
 *
 * <p>The stakes are the growth-tick grid's phase: {@code GrowthTimer} learns when the game's
 * five-minute clock actually fires from the transitions this method lets through, and every
 * "ready at" time in the plugin hangs off that. A player action misread as a growth tick —
 * raking, planting, picking, a catch-up burst — poisons the offset silently, and the symptom
 * is timers that are wrong by up to a full tick with nothing to say why.
 *
 * <p>So the interesting property is the <b>rejections</b>: the method must only pass
 * transitions the game's clock alone can explain. Each case here is one shape of transition,
 * built from real varbit decodes ({@link PatchImplementation#forVarbitValue}) rather than
 * hand-assembled states, so a test can only claim a transition exists if the game's own
 * table can produce it.
 *
 * <p>{@code isGrowthTick} is private by design — it is a detail of the tracker, not an API —
 * so it is reached by reflection here rather than widened for the test's convenience.
 */
public class GrowthTickObservationTest
{
	/** Falador's north allotment: known-good varbit fixtures, same as HarvestLogTest uses. */
	private static final String FALADOR_NORTH = "12083.4771";

	// Allotment varbit values, from the generated PatchRules table. Hardcoded because the
	// allotment fixtures are the ones every test in this repo already leans on: weeds are
	// 0-3, potatoes grow through 6-9, are harvestable at 10-12, diseased at 135-137 and
	// dead at 199-201. A wrong number fails loudly in the sanity asserts below.
	private static final int WEEDS = 3;
	private static final int POTATO_STAGE_0 = 6;
	private static final int POTATO_STAGE_1 = 7;
	private static final int POTATO_STAGE_2 = 8;
	private static final int POTATO_STAGE_3 = 9;
	private static final int POTATO_RIPE = 10;
	private static final int POTATO_RIPE_PICKED_ONCE = 11;
	private static final int POTATO_DISEASED = 136;
	private static final int POTATO_DEAD = 200;

	@Test
	public void anOrdinaryStageAdvanceIsAGrowthTick()
	{
		FarmPatch patch = allotment();
		ProduceState before = decode(patch, POTATO_STAGE_0);
		ProduceState after = decode(patch, POTATO_STAGE_1);
		assertEquals("fixture: consecutive growing stages",
			before.getStage() + 1, after.getStage());

		assertTrue("one growing stage to the next is the clock and nothing else",
			isGrowthTick(patch, before, after));
	}

	/**
	 * A jump of two stages is not a tick, it is us catching up.
	 *
	 * <p>The burst of varbits on entering a region replays days of growth in one change. The
	 * caller filters most of that with {@code newRegionLoaded}, but the one-stage rule is the
	 * method's own last line of defence, and it is what this pins.
	 */
	@Test
	public void skippingAStageIsCatchingUpNotACropGrowing()
	{
		FarmPatch patch = allotment();

		assertFalse("two stages at once cannot be a single tick landing",
			isGrowthTick(patch, decode(patch, POTATO_STAGE_0), decode(patch, POTATO_STAGE_2)));
		assertFalse("and a stage going backwards is not growth at all",
			isGrowthTick(patch, decode(patch, POTATO_STAGE_2), decode(patch, POTATO_STAGE_1)));
	}

	/** Disease strikes on a growth tick, so seeing it arrive fixes the grid too. */
	@Test
	public void diseaseOnsetIsAGrowthTick()
	{
		FarmPatch patch = allotment();
		ProduceState diseased = decode(patch, POTATO_DISEASED);
		assertEquals("fixture: a diseased potato", CropState.DISEASED, diseased.getCropState());

		assertTrue("a growing crop only sickens when the clock fires",
			isGrowthTick(patch, decode(patch, POTATO_STAGE_2), diseased));
	}

	/**
	 * A diseased crop dying does <b>not</b> currently count, and this pins that on purpose.
	 *
	 * <p>The method's final line clearly intends diseased-to-dead to be a growth tick — death
	 * lands on the clock like everything else — but it is unreachable: a {@code DISEASED}
	 * state's {@link ProduceState#getTickRate()} is 0, so the {@code previous.getTickRate()
	 * <= 0} guard rejects the transition before the last line is consulted. This test
	 * documents the behaviour as it is; if the guard is ever reordered to let that branch
	 * breathe, this assertion should flip to {@code assertTrue} rather than be deleted.
	 */
	@Test
	public void aDiseasedCropDyingIsAGrowthTick()
	{
		FarmPatch patch = allotment();
		ProduceState diseased = decode(patch, POTATO_DISEASED);
		ProduceState dead = decode(patch, POTATO_DEAD);
		assertEquals("fixture: a dead potato", CropState.DEAD, dead.getCropState());
		assertEquals("the trap this pins: a diseased crop reports no tick rate",
			0, diseased.getTickRate());

		// This asserted false when the branch was shadowed by the tick-rate guard; the
		// diseased-to-dead check now runs before the guard, as the branch always intended.
		assertTrue("death is the clock landing, whatever the diseased state's rate says",
			isGrowthTick(patch, diseased, dead));
	}

	/** An allotment ripens on its own, so reaching harvestable is the clock's doing. */
	@Test
	public void ripeningOnItsOwnIsAGrowthTick()
	{
		FarmPatch patch = allotment();
		assertFalse("fixture: no health check stands between an allotment and harvest",
			patch.getImplementation().isHealthCheckRequired());

		assertTrue(isGrowthTick(patch, decode(patch, POTATO_STAGE_3), decode(patch, POTATO_RIPE)));
	}

	/**
	 * A bush reaching harvestable is the player checking its health, not a tick.
	 *
	 * <p>Trees and bushes sit grown-but-unchecked until the player clicks them; the varbit
	 * only moves to harvestable at that click, which can happen days after the last tick.
	 * Learning the grid's phase from it would anchor every timer to when the player happened
	 * to visit.
	 */
	@Test
	public void ripeningByHealthCheckIsThePlayerNotTheClock()
	{
		FarmPatch bush = bushPatch();
		assertTrue("fixture: bushes want a health check",
			bush.getImplementation().isHealthCheckRequired());

		ProduceState growing = lastOf(bush, Produce.JANGERBERRIES, CropState.GROWING);
		ProduceState ripe = firstOf(bush, Produce.JANGERBERRIES, CropState.HARVESTABLE);

		assertFalse("the transition happens when the player checks, not when the tick lands",
			isGrowthTick(bush, growing, ripe));
	}

	/** Weeds move on rakes and neglect, and neither side of them says anything about crops. */
	@Test
	public void weedsOnEitherSideAreNotAGrowthTick()
	{
		FarmPatch patch = allotment();
		ProduceState weeds = decode(patch, WEEDS);
		assertEquals("fixture: value " + WEEDS + " is weeds", Produce.WEEDS, weeds.getProduce());

		assertFalse("planting into a weedy patch is the player's hand",
			isGrowthTick(patch, weeds, decode(patch, POTATO_STAGE_0)));
		assertFalse("and a cleared patch reverting to weeds is not a crop growing",
			isGrowthTick(patch, decode(patch, POTATO_RIPE), weeds));
	}

	/** The crop itself changing means a replant happened in between, however fast. */
	@Test
	public void theProduceChangingIsAReplantNotATick()
	{
		FarmPatch patch = allotment();
		ProduceState onion = firstOf(patch, Produce.ONION, CropState.GROWING);

		assertFalse("a potato cannot tick into an onion",
			isGrowthTick(patch, decode(patch, POTATO_STAGE_3), onion));
	}

	/**
	 * A crop with no tick rate cannot be ticking.
	 *
	 * <p>A ripe potato does not regrow, so its harvestable values only ever move because the
	 * player is picking it — and each pick is a varbit change in exactly the one-step shape a
	 * naive rule would accept. The tick-rate guard is what keeps a harvest from being read as
	 * three growth ticks in a row.
	 */
	@Test
	public void aCropThatIsNotAdvancingCannotTick()
	{
		FarmPatch patch = allotment();
		ProduceState ripe = decode(patch, POTATO_RIPE);
		assertEquals("fixture: a ripe potato is not regrowing anything", 0, ripe.getTickRate());

		assertFalse("picking is the player, whatever the value delta looks like",
			isGrowthTick(patch, ripe, decode(patch, POTATO_RIPE_PICKED_ONCE)));
	}

	/**
	 * Even a crop that <i>does</i> regrow is not ticking while its stock merely moves.
	 *
	 * <p>Jangerberries carry a positive regrow rate, so the tick-rate guard alone does not
	 * protect them: it is the growing/diseased state checks that stop a berry being picked
	 * (harvestable, stock falling) from reading as growth.
	 */
	@Test
	public void aBerryBeingPickedIsNotAGrowthTickEvenWithARegrowRate()
	{
		FarmPatch bush = bushPatch();
		List<ProduceState> stock = statesOf(bush, Produce.JANGERBERRIES, CropState.HARVESTABLE);
		assertTrue("fixture: a bush counts its remaining berries in the varbit",
			stock.size() >= 2);
		assertTrue("fixture: a ripe bush is regrowing, so the tick-rate guard passes",
			stock.get(0).getTickRate() > 0);

		assertFalse(isGrowthTick(bush, stock.get(1), stock.get(0)));
		assertFalse(isGrowthTick(bush, stock.get(0), stock.get(1)));
	}

	// ------------------------------------------------------------------- helpers

	private static FarmPatch allotment()
	{
		FarmPatch patch = FarmingWorldData.getPatch(FALADOR_NORTH);
		assertNotNull("fixture patch no longer exists", patch);
		return patch;
	}

	/** A bush that can hold jangerberries, wherever the game keeps one. */
	private static FarmPatch bushPatch()
	{
		for (FarmPatch candidate : FarmingWorldData.getPatches(PatchImplementation.BUSH))
		{
			if (!statesOf(candidate, Produce.JANGERBERRIES, CropState.GROWING).isEmpty())
			{
				return candidate;
			}
		}
		throw new AssertionError("no bush patch can hold a jangerberry any more");
	}

	private static ProduceState decode(FarmPatch patch, int varbitValue)
	{
		ProduceState state = patch.getImplementation().forVarbitValue(varbitValue);
		assertNotNull("varbit value " + varbitValue + " no longer decodes for " + patch, state);
		return state;
	}

	/**
	 * Every state of one crop in one condition, in varbit order.
	 *
	 * <p>Found by scanning rather than by hardcoding, because bush varbit values are not
	 * memorable and a wrong one would fail as "no such state" rather than as the thing
	 * being tested.
	 */
	private static List<ProduceState> statesOf(FarmPatch patch, Produce produce, CropState state)
	{
		List<ProduceState> states = new ArrayList<>();
		for (int value = 0; value < 256; value++)
		{
			ProduceState decoded = patch.getImplementation().forVarbitValue(value);
			if (decoded != null && decoded.getProduce() == produce
				&& decoded.getCropState() == state)
			{
				states.add(decoded);
			}
		}
		return states;
	}

	private static ProduceState firstOf(FarmPatch patch, Produce produce, CropState state)
	{
		List<ProduceState> states = statesOf(patch, produce, state);
		assertFalse("no varbit value gives " + produce + " " + state + " on " + patch,
			states.isEmpty());
		return states.get(0);
	}

	private static ProduceState lastOf(FarmPatch patch, Produce produce, CropState state)
	{
		List<ProduceState> states = statesOf(patch, produce, state);
		assertFalse("no varbit value gives " + produce + " " + state + " on " + patch,
			states.isEmpty());
		return states.get(states.size() - 1);
	}

	/** The method under test, which is private by design - see the class javadoc. */
	private static boolean isGrowthTick(FarmPatch patch, ProduceState previous,
		ProduceState current)
	{
		try
		{
			Method method = PatchInteractionTracker.class.getDeclaredMethod(
				"isGrowthTick", FarmPatch.class, ProduceState.class, ProduceState.class);
			method.setAccessible(true);
			return (Boolean) method.invoke(null, patch, previous, current);
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException("isGrowthTick has moved or changed shape", e);
		}
	}
}
