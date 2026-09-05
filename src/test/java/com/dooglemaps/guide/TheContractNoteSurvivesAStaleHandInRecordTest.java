package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.Produce;
import com.dooglemaps.route.RunPlanner;
import com.dooglemaps.state.ContractState;
import com.dooglemaps.state.PlantingGroups;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A hand-in record naming some other crop says nothing about this contract.
 *
 * <h2>A snapdragon in config silenced the explanation for a poison ivy</h2>
 *
 * The awaiting-hand-in record is what tells a health check made <i>during</i> a contract apart from
 * one made before it, so {@code contractDudPatch} consults it — and it used to refuse on the record
 * existing at all. Live state from the reported session had {@code contractAwaitingHandIn} still
 * naming a snapdragon from an earlier cycle while a poison ivy contract was assigned, and that
 * leftover turned off the one sentence that explained why the run wanted to dig up a healthy bush.
 *
 * <p>Only a record naming <b>this</b> contract's crop can speak for it. A record naming another is
 * either stale or about a different cycle, and either way it is not evidence about this one — it is
 * also now reconciled away every tick rather than at two moments, but the guide must not depend on
 * that having happened yet.
 */
public class TheContractNoteSurvivesAStaleHandInRecordTest
{
	private GuideTracker tracker;
	private PlantingGroups groups;
	private RunPlanner planner;
	private ContractState contracts;
	private FarmPatch bush;

	private final PlantingGroup contractGroup = PlantingGroup.contract(PatchImplementation.BUSH);

	@Before
	public void setUp() throws Exception
	{
		groups = Mockito.mock(PlantingGroups.class);
		planner = Mockito.mock(RunPlanner.class);
		contracts = Mockito.mock(ContractState.class);

		DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class);
		when(config.guideFarmingContracts()).thenReturn(true);
		when(groups.patchesIn(any())).thenReturn(Collections.emptyList());

		tracker = trackerWith(config);

		bush = guildPatch(PatchImplementation.BUSH);
		assertNotNull("the Farming Guild has no bush patch in the data", bush);

		when(contracts.getContract()).thenReturn(Produce.POISON_IVY);
		when(groups.patchesIn(contractGroup)).thenReturn(Collections.singletonList(bush));
		when(planner.contractStandingIsSpent(bush)).thenReturn(true);
	}

	/** The reported state: a stale snapdragon record beside a live poison ivy contract. */
	@Test
	public void aRecordForAnotherCropDoesNotHideTheDud() throws Exception
	{
		when(contracts.getAwaitingHandIn()).thenReturn(Produce.SNAPDRAGON);

		assertSame("the bush is still the thing the player has to be told about",
			bush, contractDudPatch());
	}

	/** With nothing recorded at all, as before. */
	@Test
	public void noRecordLeavesTheDudVisible() throws Exception
	{
		assertSame(bush, contractDudPatch());
	}

	/**
	 * A record naming this crop is the one that does speak: the check counted.
	 *
	 * <p>That is a contract completed during its own cycle, waiting to be handed in, and calling it
	 * a dud would have the guide asking for a finished contract to be dug up.
	 */
	@Test
	public void aRecordForThisCropStillRefuses() throws Exception
	{
		when(contracts.getAwaitingHandIn()).thenReturn(Produce.POISON_IVY);

		assertNull(contractDudPatch());
	}

	// ------------------------------------------------------------------- helpers

	private FarmPatch contractDudPatch() throws Exception
	{
		Method method = GuideTracker.class.getDeclaredMethod("contractDudPatch");
		method.setAccessible(true);
		try
		{
			return (FarmPatch) method.invoke(tracker);
		}
		catch (java.lang.reflect.InvocationTargetException e)
		{
			throw new AssertionError(e.getCause());
		}
	}

	private static FarmPatch guildPatch(PatchImplementation type)
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(type))
		{
			if (patch.getRegion().getRegionId() == ContractState.FARMING_GUILD_REGION)
			{
				return patch;
			}
		}
		return null;
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
			if (type == PlantingGroups.class)
			{
				args[i] = groups;
			}
			else if (type == RunPlanner.class)
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
