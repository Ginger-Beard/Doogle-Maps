package com.dooglemaps.guide;

import com.dooglemaps.bank.RouteItem;
import com.dooglemaps.route.RunPlanner;
import com.dooglemaps.state.DailyTeleports;
import com.dooglemaps.state.PlayerHouse;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

/**
 * The travel hint stops naming a teleport the game has refused for the day.
 *
 * <h2>What it looked like</h2>
 *
 * The Ardougne cloak's farm teleport is five a day on the cloak 3, and the count is in no
 * varbit the client receives — see {@code DailyTeleports}. So Shortest Path went on routing
 * through it with none left, and when its own pick was skipped the {@code TeleportItems}
 * fallback, which lists the cloak for the Ardougne region, said "use your Ardougne cloak"
 * instead. Reported from play. Two instructions for the same click you cannot make.
 */
public class SpentTeleportIsNotTheHintTest
{
	/** The Ardougne farm patch — region 10548, which is what the teleport table is keyed on. */
	private static final WorldPoint ARDOUGNE_PATCH = new WorldPoint(2665, 3374, 0);

	@Test
	public void theCloakIsTheHintWhileItHasCharges() throws Exception
	{
		TravelHint hint = travelHint(trackerWith(false));

		assertEquals("nothing has said otherwise, so the router's own table answers",
			ItemID.ARDY_CAPE_HARD, hint.getItemId());
	}

	@Test
	public void aSpentCloakIsNotOfferedAsTheWayThere() throws Exception
	{
		TravelHint hint = travelHint(trackerWith(true));

		assertEquals("the game has refused it today; nothing carried reaches the patch",
			-1, hint.getItemId());
	}

	private static TravelHint travelHint(GuideTracker tracker) throws Exception
	{
		Method method = GuideTracker.class.getDeclaredMethod("travelHint", String.class);
		method.setAccessible(true);
		try
		{
			return (TravelHint) method.invoke(tracker, "Ardougne");
		}
		catch (java.lang.reflect.InvocationTargetException e)
		{
			throw new AssertionError(e.getCause());
		}
	}

	/** A tracker travelling to the Ardougne patch with a cloak 3 on, spent or not. */
	private static GuideTracker trackerWith(boolean spent) throws Exception
	{
		Constructor<?> constructor = GuideTracker.class.getDeclaredConstructors()[0];
		constructor.setAccessible(true);

		Class<?>[] types = constructor.getParameterTypes();
		Object[] args = new Object[types.length];
		for (int i = 0; i < types.length; i++)
		{
			args[i] = Mockito.mock(types[i]);
		}
		GuideTracker tracker = (GuideTracker) constructor.newInstance(args);

		for (Object arg : args)
		{
			if (arg instanceof PlayerHouse)
			{
				when(((PlayerHouse) arg).isInside()).thenReturn(false);
			}
			else if (arg instanceof RouteItem)
			{
				when(((RouteItem) arg).currentItemId()).thenReturn(-1);
			}
			else if (arg instanceof RunPlanner)
			{
				when(((RunPlanner) arg).getCurrentDestinations())
					.thenReturn(Collections.singleton(ARDOUGNE_PATCH));
			}
			else if (arg instanceof com.dooglemaps.bank.RunLoadout)
			{
				when(((com.dooglemaps.bank.RunLoadout) arg).isOnTeleportList(Mockito.anyInt()))
					.thenReturn(true);
			}
			else if (arg instanceof CarriedItems)
			{
				when(((CarriedItems) arg).has(ItemID.ARDY_CAPE_HARD)).thenReturn(true);
			}
			else if (arg instanceof DailyTeleports)
			{
				when(((DailyTeleports) arg).isItemSpent(Mockito.anyInt())).thenReturn(spent);
			}
		}
		return tracker;
	}
}
