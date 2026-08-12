package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.RunOption;
import com.dooglemaps.route.RunStop;
import com.dooglemaps.state.ContractState;
import com.dooglemaps.state.RunTypeStore;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * What the Farming Guild offers, and in what order.
 *
 * <h2>The hold-back was too broad in two directions</h2>
 *
 * {@code contractComesFirst} keeps the guild clear while a contract wants something, so an
 * ordinary crop cannot be planted in ground the contract needs. Two things were wrong with
 * where it applied:
 *
 * <ul>
 *   <li>it fired on runs that were <b>not doing the contract at all</b>, and with none assigned
 *       it withheld the entire guild — a compost-only run stood at the guild bank and was
 *       offered nothing, because the guild was being kept clear for a contract nobody asked
 *       for;</li>
 *   <li>it withheld the <b>compost bin</b>, which Jane can never assign, so it competes for no
 *       ground and has no reason to wait for anything.</li>
 * </ul>
 *
 * <p>And the bin is not merely allowed through but goes first: emptying it frees the pack and
 * restocks the compost every other patch at that stop is about to want.
 */
public class GuildBinPrecedenceTest
{
	private ContractState contracts;
	private RunTypeStore runTypes;
	private DoogleMapsConfig config;
	private GuideTracker tracker;

	@Before
	public void setUp() throws Exception
	{
		contracts = Mockito.mock(ContractState.class);
		runTypes = Mockito.mock(RunTypeStore.class);
		config = Mockito.mock(DoogleMapsConfig.class);
		when(config.guideFarmingContracts()).thenReturn(true);

		tracker = trackerWith();
	}

	/**
	 * The reported dead end: no contract assigned, and the whole guild withheld.
	 *
	 * <p>With the contract line unticked there is nothing to keep the ground clear for, so the
	 * guild is open — and a compost-only run is offered its bin.
	 */
	@Test
	public void aRunWithoutTheContractIsOfferedTheGuild() throws Exception
	{
		when(contracts.getActiveContractType()).thenReturn(null);

		List<FarmPatch> offered = offer(guildPatches());

		assertFalse("the guild was held clear for a contract nobody asked for",
			offered.isEmpty());
		assertTrue("including the bin", offered.stream().anyMatch(
			patch -> patch.getImplementation() == PatchImplementation.BIG_COMPOST));
	}

	/**
	 * With a contract assigned but its line unticked, the guild still opens.
	 *
	 * <p>"Not selected for the run" is the owner's rule, and it covers this as well as the case
	 * above: a player ignoring an assigned contract is not asking for its ground to be reserved.
	 */
	@Test
	public void anUntickedContractDoesNotHoldTheGuild() throws Exception
	{
		when(contracts.getActiveContractType()).thenReturn(PatchImplementation.HERB);
		when(runTypes.isSelected(RunOption.full(PlantingGroup.contract(
			PatchImplementation.HERB)))).thenReturn(false);

		assertFalse(offer(guildPatches()).isEmpty());
	}

	/** Ticked, with nothing claimed yet, the hold-back still does its job — bar the bin. */
	@Test
	public void atickedContractStillHoldsTheGuildButNotTheBin() throws Exception
	{
		when(contracts.getActiveContractType()).thenReturn(PatchImplementation.HERB);
		when(runTypes.isSelected(RunOption.full(PlantingGroup.contract(
			PatchImplementation.HERB)))).thenReturn(true);
		when(contracts.hasContract()).thenReturn(false);
		when(contracts.claimsUntilHandedIn(Mockito.any())).thenReturn(false);

		List<FarmPatch> offered = offer(guildPatches());

		assertEquals("only the bin survives the hold-back", 1, offered.size());
		assertEquals(PatchImplementation.BIG_COMPOST, offered.get(0).getImplementation());
	}

	/** And wherever a bin is offered it is offered first. */
	@Test
	public void theBinSortsToTheFrontOfItsStop() throws Exception
	{
		List<FarmPatch> ordered = new ArrayList<>(guildPatches());
		// Deliberately last to start with, as distance ordering could easily leave it.
		ordered.sort((a, b) -> Boolean.compare(
			a.getImplementation() == PatchImplementation.BIG_COMPOST,
			b.getImplementation() == PatchImplementation.BIG_COMPOST));
		assertFalse("fixture should not start with the bin",
			ordered.get(0).getImplementation() == PatchImplementation.BIG_COMPOST);

		Method binsFirst = GuideTracker.class.getDeclaredMethod("binsFirst", List.class);
		binsFirst.setAccessible(true);
		binsFirst.invoke(tracker, ordered);

		assertEquals("emptying it frees the pack for everything else here",
			PatchImplementation.BIG_COMPOST, ordered.get(0).getImplementation());
	}

	/** Every guild patch this account uses, bin included. */
	private static List<FarmPatch> guildPatches()
	{
		List<FarmPatch> patches = new ArrayList<>();
		for (FarmPatch patch : FarmingWorldData.getAllPatches())
		{
			if (patch.getRegion().getRegionId() == ContractState.FARMING_GUILD_REGION)
			{
				patches.add(patch);
			}
		}
		assertFalse("no guild patches in the world data", patches.isEmpty());
		return patches;
	}

	private List<FarmPatch> offer(List<FarmPatch> ordered) throws Exception
	{
		RunStop stop = Mockito.mock(RunStop.class);
		com.dooglemaps.data.FarmRegion region = ordered.get(0).getRegion();
		when(stop.getRegion()).thenReturn(region);
		when(stop.getPatches()).thenReturn(ordered);

		Method method = GuideTracker.class.getDeclaredMethod(
			"contractComesFirst", RunStop.class, List.class);
		method.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<FarmPatch> offered = (List<FarmPatch>) method.invoke(tracker, stop, ordered);
		return offered;
	}

	private GuideTracker trackerWith() throws Exception
	{
		Constructor<?> constructor = GuideTracker.class.getDeclaredConstructors()[0];
		constructor.setAccessible(true);

		Class<?>[] types = constructor.getParameterTypes();
		Object[] args = new Object[types.length];
		for (int i = 0; i < types.length; i++)
		{
			if (types[i] == ContractState.class)
			{
				args[i] = contracts;
			}
			else if (types[i] == RunTypeStore.class)
			{
				args[i] = runTypes;
			}
			else if (types[i] == DoogleMapsConfig.class)
			{
				args[i] = config;
			}
			else
			{
				args[i] = Mockito.mock(types[i]);
			}
		}
		GuideTracker built = (GuideTracker) constructor.newInstance(args);
		assertNotNull(built);
		return built;
	}
}
