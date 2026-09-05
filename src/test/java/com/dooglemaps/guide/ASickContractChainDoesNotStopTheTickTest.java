package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.route.RunPlanner;
import com.dooglemaps.state.ContractState;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.mockito.Mockito.when;

/**
 * A contract question that blows up loses the contract, not the whole tick.
 *
 * <h2>Why this is guarded at all</h2>
 *
 * The contract claim is pushed to the planner as the <b>first</b> thing the guide's tick does,
 * ahead of the idle report, the snapshot and the step list, because a run that has not started yet
 * reads it. Everything after it therefore depends on it returning: an exception thrown from that
 * one call takes the step list with it, on every tick, and the plugin goes silent in a way that
 * looks exactly like the bug this pass was chasing — where nothing was thrown at all and the
 * silence came from a routing decision instead.
 *
 * <p>The chain behind that call is the longest reach in the class: config, the patch stores, the
 * seed inventory and the planner's own allocation, some of which are empty or half-loaded at login.
 * So it is allowed to fail loudly in the log and quietly on screen. "No business" is the safe
 * answer — it lets a run end rather than stranding it — and the trace is printed so a silence that
 * does happen has a cause in client.log.
 */
public class ASickContractChainDoesNotStopTheTickTest
{
	private GuideTracker tracker;
	private RunPlanner planner;
	private ContractState contracts;

	@Before
	public void setUp() throws Exception
	{
		planner = Mockito.mock(RunPlanner.class);
		contracts = Mockito.mock(ContractState.class);

		DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class);
		when(config.guideFarmingContracts()).thenReturn(true);

		tracker = trackerWith(config);
	}

	/** The push still happens, with the claim withdrawn, and the tick carries on. */
	@Test
	public void theClaimIsWithdrawnRatherThanThrown() throws Exception
	{
		when(contracts.getActiveContractType())
			.thenThrow(new IllegalStateException("the contract store fell over"));

		reportIdlePatches();

		Mockito.verify(planner).setContractBusinessOutstanding(false);
	}

	/** And a healthy chain with nothing assigned still answers, so the guard is not the answer. */
	@Test
	public void aHealthyChainStillAnswersForItself() throws Exception
	{
		reportIdlePatches();

		Mockito.verify(planner).setContractBusinessOutstanding(false);
	}

	private void reportIdlePatches() throws Exception
	{
		Method method = GuideTracker.class.getDeclaredMethod("reportIdlePatches");
		method.setAccessible(true);
		try
		{
			method.invoke(tracker);
		}
		catch (java.lang.reflect.InvocationTargetException e)
		{
			throw new AssertionError("the tick must survive a sick contract chain", e.getCause());
		}
	}

	/** A tracker whose collaborators are this test's mocks, everything else auto-mocked. */
	private GuideTracker trackerWith(DoogleMapsConfig config) throws Exception
	{
		Constructor<?> constructor = GuideTracker.class.getDeclaredConstructors()[0];
		constructor.setAccessible(true);

		Class<?>[] types = constructor.getParameterTypes();
		Object[] args = new Object[types.length];
		for (int i = 0; i < types.length; i++)
		{
			Class<?> type = types[i];
			if (type == RunPlanner.class)
			{
				args[i] = planner;
			}
			else if (type == ContractState.class)
			{
				args[i] = contracts;
			}
			else if (type == DoogleMapsConfig.class)
			{
				args[i] = config;
			}
			else
			{
				args[i] = Mockito.mock(type);
			}
		}
		return (GuideTracker) constructor.newInstance(args);
	}
}
