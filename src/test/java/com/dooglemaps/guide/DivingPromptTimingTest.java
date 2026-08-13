package com.dooglemaps.guide;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.UnderwaterApproach;
import com.dooglemaps.route.RunPlanner;
import com.dooglemaps.route.RunStop;
import com.dooglemaps.state.PlayerLocation;
import java.lang.reflect.Constructor;
import java.util.Collections;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.when;

/**
 * When the guide is allowed to tell you to put the diving suit on.
 *
 * <p>{@link GuideTracker#underwaterApproach()} is true from the first tick of any run with a
 * seaweed or coral stop on it. That is fine for what it was written for — a scene outline, whose
 * object is not in the scene anywhere else, so a premature answer draws nothing — and wrong for
 * the two things that later borrowed it. The travel panel's "wear your diving gear before the
 * steps down" and the inventory highlight, in the guide colour that means <i>this is the step</i>,
 * both sat on screen through every herb patch and bank trip of a run whose dive was several
 * teleports away. Reported from play.
 *
 * <p>Where the player is standing rather than where the route is pointing, deliberately:
 * {@code getCurrentDestinations()} can be every outstanding target at once rather than the one
 * Shortest Path settled on, so "are we heading there" is a question the route cannot always
 * answer — and a wrong answer is the bug being fixed.
 */
public class DivingPromptTimingTest
{
	private static final FarmPatch SEAWEED =
		FarmingWorldData.getPatches(PatchImplementation.SEAWEED).get(0);
	private static final FarmPatch HERB =
		FarmingWorldData.getPatches(PatchImplementation.HERB).get(0);

	/** Somewhere that is emphatically not a shore with steps on it. */
	private static final WorldPoint FALADOR = new WorldPoint(3013, 3356, 0);

	/** The broad answer is unchanged - the outline still wants it for the whole run. */
	@Test
	public void theOutlineStillSeesTheApproachFromAnywhere() throws Exception
	{
		GuideTracker tracker = trackerAt(FALADOR, SEAWEED);

		assertNotNull("the outline's own test must not have narrowed",
			tracker.underwaterApproach());
	}

	/** The prompt does not, which is the whole fix. */
	@Test
	public void theSuitIsNotAskedForFromAcrossTheMap() throws Exception
	{
		GuideTracker tracker = trackerAt(FALADOR, SEAWEED);

		assertNull("a dive several teleports away is not an instruction yet",
			tracker.underwaterApproachAtHand());
	}

	/** Standing on the shore it is, because that is the moment the ordering matters. */
	@Test
	public void theSuitIsAskedForAtTheShore() throws Exception
	{
		UnderwaterApproach.Approach rowboat = UnderwaterApproach.forPatch(SEAWEED);
		assertNotNull(rowboat);

		GuideTracker tracker = trackerAt(rowboat.getPoint(), SEAWEED);

		assertEquals(rowboat, tracker.underwaterApproachAtHand());
	}

	/** The coral nurseries are the other half of the same rule, and a different shore. */
	@Test
	public void theCoralShoreIsItsOwnPlace() throws Exception
	{
		UnderwaterApproach.Approach seaweed = UnderwaterApproach.forPatch(SEAWEED);
		UnderwaterApproach.Approach coral =
			UnderwaterApproach.forType(PatchImplementation.CORAL);
		assertNotNull(seaweed);
		assertNotNull(coral);

		assertNull("one shore does not stand in for the other",
			trackerAt(coral.getPoint(), SEAWEED).underwaterApproachAtHand());
	}

	/** A run with nothing underwater on it never asks, wherever the player happens to be. */
	@Test
	public void aDryRunNeverAsks() throws Exception
	{
		UnderwaterApproach.Approach rowboat = UnderwaterApproach.forPatch(SEAWEED);
		assertNotNull(rowboat);

		assertNull(trackerAt(rowboat.getPoint(), HERB).underwaterApproachAtHand());
	}

	/**
	 * Before the first tick there is no location, and no location is not an invitation.
	 *
	 * <p>The opposite call from {@code UnderwaterApproach.stillWanted}, which treats an unknown
	 * region as "not under yet" because routing to the shore is the safe half of that guess.
	 * Here the safe half is silence: a prompt shown on no evidence is the thing being fixed.
	 */
	@Test
	public void anUnknownLocationSaysNothing() throws Exception
	{
		assertNull(trackerAt(null, SEAWEED).underwaterApproachAtHand());
	}

	/** A tracker whose planner has one stop holding this patch, with the player here. */
	private static GuideTracker trackerAt(WorldPoint where, FarmPatch patch) throws Exception
	{
		RunStop stop = Mockito.mock(RunStop.class);
		when(stop.getPatches()).thenReturn(Collections.singletonList(patch));

		RunPlanner planner = Mockito.mock(RunPlanner.class);
		when(planner.getRemaining()).thenReturn(Collections.singletonList(stop));

		PlayerLocation location = Mockito.mock(PlayerLocation.class);
		when(location.get()).thenReturn(where);

		Constructor<?> constructor = GuideTracker.class.getDeclaredConstructors()[0];
		constructor.setAccessible(true);

		Class<?>[] types = constructor.getParameterTypes();
		Object[] args = new Object[types.length];
		for (int i = 0; i < types.length; i++)
		{
			if (types[i] == RunPlanner.class)
			{
				args[i] = planner;
			}
			else if (types[i] == PlayerLocation.class)
			{
				args[i] = location;
			}
			else
			{
				args[i] = Mockito.mock(types[i]);
			}
		}
		return (GuideTracker) constructor.newInstance(args);
	}
}
