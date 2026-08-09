package com.dooglemaps.guide;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.Produce;
import com.dooglemaps.route.RunStop;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Where the pick-up step lands among the stop's other steps.
 *
 * <p>The placement is the feature: crops on the ground are on a despawn clock, so with room in
 * the pack they outrank everything, but picking up into a full pack is not an instruction anyone
 * can follow — there it has to wait behind the note that frees the slots. Three lines of
 * position arithmetic that would regress silently, same reason the errand order is pinned.
 */
public class DroppedPickupStepTest
{
	private GuideTracker tracker;
	private CarriedItems carried;
	private DroppedProduce droppedProduce;

	@Before
	public void setUp() throws Exception
	{
		carried = Mockito.mock(CarriedItems.class);
		droppedProduce = Mockito.mock(DroppedProduce.class);
		tracker = trackerWith(carried, droppedProduce);
	}

	@Test
	public void withRoomInThePackTheDespawnClockWins() throws Exception
	{
		dropsOnTheGround();
		when(carried.getFreeSlots()).thenReturn(5);

		List<GuideStep> steps = new ArrayList<>();
		steps.add(GuideStep.of(GuideAction.HARVEST, somePatch(), "Harvest the limpwurt."));

		insert(steps);

		assertEquals("the crops in the ground can wait; the ones on it cannot",
			GuideAction.PICK_UP_DROPS, steps.get(0).getAction());
		assertEquals(GuideAction.HARVEST, steps.get(1).getAction());
	}

	@Test
	public void withAFullPackThePickupWaitsBehindTheNote() throws Exception
	{
		dropsOnTheGround();
		when(carried.getFreeSlots()).thenReturn(0);

		List<GuideStep> steps = new ArrayList<>();
		steps.add(GuideStep.atLeprechaun(GuideAction.NOTE_AT_LEPRECHAUN, somePatch(),
			Produce.LIMPWURT.getItemID(), null, "Your inventory is full - note the limpwurt."));
		steps.add(GuideStep.of(GuideAction.HARVEST, somePatch(), "Harvest the limpwurt."));

		insert(steps);

		assertEquals("noting is what makes the pick-up possible",
			GuideAction.NOTE_AT_LEPRECHAUN, steps.get(0).getAction());
		assertEquals("so the pick-up follows it, before the harvest resumes",
			GuideAction.PICK_UP_DROPS, steps.get(1).getAction());
		assertEquals(GuideAction.HARVEST, steps.get(2).getAction());
	}

	@Test
	public void aFullPackWithNothingThatFreesASlotRaisesNoStep() throws Exception
	{
		dropsOnTheGround();
		when(carried.getFreeSlots()).thenReturn(0);

		List<GuideStep> steps = new ArrayList<>();
		steps.add(GuideStep.of(GuideAction.PLANT, somePatch(), "Plant the seed."));

		insert(steps);

		assertEquals("picking up into a full pack is not an instruction anyone can follow",
			1, steps.size());
	}

	@Test
	public void nothingOnTheGroundAddsNothing() throws Exception
	{
		when(carried.getFreeSlots()).thenReturn(5);

		List<GuideStep> steps = new ArrayList<>();
		steps.add(GuideStep.of(GuideAction.HARVEST, somePatch(), "Harvest the limpwurt."));

		insert(steps);

		assertEquals(1, steps.size());
	}

	// ------------------------------------------------------------------- helpers

	private void dropsOnTheGround()
	{
		DroppedProduce.Drop drop = new DroppedProduce.Drop(Mockito.mock(TileItem.class),
			new WorldPoint(3054, 3307, 0), Produce.LIMPWURT);
		when(droppedProduce.near(Mockito.any(), Mockito.anyInt()))
			.thenReturn(Collections.singletonList(drop));
	}

	private void insert(List<GuideStep> steps) throws Exception
	{
		Method method = GuideTracker.class.getDeclaredMethod(
			"insertPickUpDrops", List.class, RunStop.class, WorldPoint.class);
		method.setAccessible(true);
		method.invoke(tracker, steps, stopHolding(somePatch()), new WorldPoint(3054, 3307, 0));
	}

	private static FarmPatch somePatch()
	{
		return FarmingWorldData.getPatches(
			com.dooglemaps.data.PatchImplementation.FLOWER).get(0);
	}

	private static RunStop stopHolding(FarmPatch patch)
	{
		RunStop stop = Mockito.mock(RunStop.class);
		when(stop.getName()).thenReturn("Falador");
		when(stop.getPatches()).thenReturn(Collections.singletonList(patch));
		return stop;
	}

	private static GuideTracker trackerWith(CarriedItems carried, DroppedProduce droppedProduce)
		throws Exception
	{
		Constructor<?> constructor = GuideTracker.class.getDeclaredConstructors()[0];
		constructor.setAccessible(true);

		Class<?>[] types = constructor.getParameterTypes();
		Object[] args = new Object[types.length];
		for (int i = 0; i < types.length; i++)
		{
			if (types[i] == CarriedItems.class)
			{
				args[i] = carried;
			}
			else if (types[i] == DroppedProduce.class)
			{
				args[i] = droppedProduce;
			}
			else
			{
				args[i] = Mockito.mock(types[i]);
			}
		}
		return (GuideTracker) constructor.newInstance(args);
	}
}
