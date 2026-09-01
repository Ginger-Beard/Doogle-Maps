package com.dooglemaps.guide;

import com.dooglemaps.bank.InventorySetupsHandoff;
import com.dooglemaps.bank.RunLoadout;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.route.RunPlanner;
import java.lang.reflect.Constructor;
import java.util.EnumSet;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * The supply leg's composite answer: whose verdict ends the leg, in which phase.
 *
 * <h2>The reported dead end</h2>
 *
 * The gear phase's leg asked the withdraw list as well as the handoff on a mixed run —
 * "the farming half still collects" — and that parked an everything-ticked run at the bank,
 * fully geared, with the leg demanding six yew saplings the combat pack had nowhere to put.
 * Reported from play as "staying on the bank step after I equip my loadout". The farming
 * withdrawals belong to the swap-back leg after the hespori, and to that leg alone.
 */
public class GearLegOutstandingTest
{
	private RunPlanner planner;
	private InventorySetupsHandoff handoff;
	private RunLoadout loadout;
	private GuideTracker tracker;

	@Before
	public void setUp() throws Exception
	{
		planner = Mockito.mock(RunPlanner.class);
		handoff = Mockito.mock(InventorySetupsHandoff.class);
		loadout = Mockito.mock(RunLoadout.class);

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
			else if (types[i] == InventorySetupsHandoff.class)
			{
				args[i] = handoff;
			}
			else if (types[i] == RunLoadout.class)
			{
				args[i] = loadout;
			}
			else
			{
				args[i] = Mockito.mock(types[i]);
			}
		}
		tracker = (GuideTracker) constructor.newInstance(args);

		// An everything-run's covered types, and a withdraw list with plenty on it — the
		// fixture the report came from.
		when(planner.coveredTypes()).thenReturn(
			EnumSet.of(PatchImplementation.HESPORI, PatchImplementation.HERB,
				PatchImplementation.TREE));
		when(loadout.anythingLeftToWithdraw(Mockito.anySet())).thenReturn(true);
	}

	@Test
	public void theGearLegIgnoresTheFarmingHalfOfAMixedRun()
	{
		when(handoff.applies()).thenReturn(true);
		when(handoff.gearOutstanding()).thenReturn(false);
		// The hespori's own kit is aboard. The withdraw list still has plenty on it — the
		// fixture's everything-run saplings — and that is precisely what must not hold.
		when(loadout.anythingLeftToWithdraw(EnumSet.of(PatchImplementation.HESPORI)))
			.thenReturn(false);

		assertFalse("a geared player carrying the replant kit is done with this leg; the "
			+ "saplings wait for the swap-back trip", tracker.supplyLegOutstanding());
	}

	/**
	 * But the hespori's own spade, seed and dibber do hold it, because nothing else will.
	 *
	 * <p>A hespori-only run never arms the swap-back trip, so this leg is the run's one chance
	 * to collect them. Ending on the gear stop alone sent the player to the boss with no seed
	 * and no way to be routed for one. Reported from play.
	 */
	@Test
	public void theGearLegHoldsForTheHesporisOwnReplantKit()
	{
		when(handoff.applies()).thenReturn(true);
		when(handoff.gearOutstanding()).thenReturn(false);
		when(loadout.anythingLeftToWithdraw(EnumSet.of(PatchImplementation.HESPORI)))
			.thenReturn(true);

		assertTrue("geared, but with nothing to replant with", tracker.supplyLegOutstanding());
	}

	@Test
	public void theGearLegHoldsWhileTheGearStopDoes()
	{
		when(handoff.applies()).thenReturn(true);
		when(handoff.gearOutstanding()).thenReturn(true);

		assertTrue(tracker.supplyLegOutstanding());
	}

	@Test
	public void outsideTheGearPhaseTheWithdrawListOwnsTheAnswer()
	{
		when(handoff.applies()).thenReturn(false);

		assertTrue("the swap-back and ordinary legs collect what the list says",
			tracker.supplyLegOutstanding());
	}
}
