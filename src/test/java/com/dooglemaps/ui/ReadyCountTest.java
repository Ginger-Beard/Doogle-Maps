package com.dooglemaps.ui;

import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Produce;
import com.dooglemaps.timer.Confidence;
import com.dooglemaps.timer.PatchProjection;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import javax.annotation.Nullable;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * What the in-game counter calls "ready", which is not the same as "finished growing".
 *
 * <h2>The reported dead end</h2>
 *
 * <i>"I'm all out of patches to do in this run and the infobox is saying I have the following
 * ready (I don't, I just did them), all fruit tree patches, etceteria and rimmington berry
 * patches"</i> — every one of them a fruit tree or a bush, on a harvest-only run.
 *
 * <p>{@code PatchProjection.isReady()} answers "has it finished growing", and for a crop that
 * regrows that stays true forever once it has: picking a fruit tree does not un-grow it. The
 * counter exists to say whether a farm run is worth starting, and it was saying yes to a farm of
 * bare trees.
 */
public class ReadyCountTest
{
	/** A stripped fruit tree: grown, checked, and with every coconut already taken. */
	@Test
	public void aPickedFruitTreeIsNotReady() throws Exception
	{
		assertFalse("nothing left on it is nothing to go for",
			worthAVisit(fruitTree(Produce.PALM, CropState.HARVESTABLE, 0)));
	}

	/** ...and one still carrying fruit is. */
	@Test
	public void aFruitTreeWithFruitOnItIsReady() throws Exception
	{
		assertTrue(worthAVisit(fruitTree(Produce.PALM, CropState.HARVESTABLE, 6)));
	}

	/**
	 * A bush that has been stripped is the other half of the same report — Etceteria and
	 * Rimmington are both bush patches.
	 */
	@Test
	public void aPickedBushIsNotReady() throws Exception
	{
		assertFalse(worthAVisit(fruitTree(Produce.CADAVABERRIES, CropState.HARVESTABLE, 0)));
	}

	/**
	 * A crop that does not regrow keeps the old answer, because it cannot be in this state: pick a
	 * herb patch and it is empty, and empty never reaches this test.
	 */
	@Test
	public void aCropThatDoesNotRegrowIsUnaffected() throws Exception
	{
		assertTrue(worthAVisit(patchOf(PatchImplementation.HERB, Produce.RANARR,
			CropState.HARVESTABLE, 0)));
	}

	private static PatchProjection fruitTree(Produce produce, CropState state, int lives)
		throws Exception
	{
		PatchImplementation type = produce.getPatchImplementation();
		assertNotNull("this test wants a regrowing crop: " + produce, type);
		assertTrue(produce + " should regrow, or this test proves nothing",
			produce.getRegrowTickrate() > 0);
		return patchOf(type, produce, state, lives);
	}

	private static PatchProjection patchOf(PatchImplementation type, Produce produce,
		CropState state, int lives) throws Exception
	{
		FarmPatch patch = FarmingWorldData.getPatches(type).get(0);
		assertNotNull(patch);

		long now = java.time.Instant.now().getEpochSecond();
		Constructor<PatchProjection> ctor = PatchProjection.class.getDeclaredConstructor(
			FarmPatch.class, Produce.class, CropState.class, int.class, int.class,
			long.class, int.class, long.class, Confidence.class, boolean.class, long.class,
			boolean.class, int.class);
		ctor.setAccessible(true);
		return ctor.newInstance(patch, produce, state, 0, produce.getStages(),
			now - 60, lives, 0L, Confidence.CERTAIN, false, now, false, -1);
	}

	private static boolean worthAVisit(@Nullable PatchProjection projection) throws Exception
	{
		Method method = ReadyInfoBox.class.getDeclaredMethod(
			"stillWorthAVisit", PatchProjection.class);
		method.setAccessible(true);
		return (boolean) method.invoke(null, projection);
	}
}
