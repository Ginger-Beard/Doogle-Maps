package com.dooglemaps.route;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.RunOption;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.state.ContractState;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.timer.GrowthTimer;
import com.google.gson.Gson;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * The Farming Guild is a stop while Guildmaster Jane still has business, patches or no patches.
 *
 * <h2>The run that ended in the middle of its own chain</h2>
 *
 * Reported from play on a contract-only run: the contract cadantine was harvested, and with it the
 * guild's herb patch went empty. Empty ground with no seed allocated to it is ground the guide has
 * no step for, so it was reported idle, the stop read finished, and the run ended one tick later —
 * with the reward uncollected and the next contract unasked for, which is the whole reason the trip
 * was made. Pressing Start run planned the same stop and ended the same way on the next tick.
 *
 * <p>Every stop the planner makes exists because a <b>patch</b> at it is actionable, and this one
 * has to exist because a <b>person</b> is. The guide works out whether she does — it can see the
 * contract, the ground and the seed — and pushes the answer here once a tick, beside the exemption
 * set it already pushes. See {@code GuideTracker.contractBusinessOutstanding}.
 */
public class TheRunWaitsForJaneTest
{
	private final Map<String, String> stored = new HashMap<>();

	private RunPlanner planner;
	private AvailabilityProfile availability;
	private com.dooglemaps.state.PlantingGroups groups;
	private com.dooglemaps.state.RunTypeStore runOptions;
	private FarmPatch guildHerb;

	private final PlantingGroup contractGroup = PlantingGroup.contract(PatchImplementation.HERB);

	@Before
	public void setUp()
	{
		Gson gson = new Gson();
		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer(i -> stored.get(i.getArgument(0) + "." + i.getArgument(1)));
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(int.class)))
			.thenAnswer(i -> null);
		when(configManager.getRSProfileConfiguration(anyString(), eq("farmingLevel"),
			eq(int.class))).thenReturn(99);
		doAnswer(i ->
		{
			// Held as an Object first: getArgument is generic, and String.valueOf would otherwise
			// infer the char[] overload and cast-fail on every write.
			Object value = i.getArgument(2);
			stored.put(i.getArgument(0) + "." + i.getArgument(1), String.valueOf(value));
			return null;
		}).when(configManager).setRSProfileConfiguration(anyString(), anyString(), any());

		net.runelite.client.callback.ClientThread clientThread =
			Mockito.mock(net.runelite.client.callback.ClientThread.class);
		doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		PatchStateStore stateStore = construct(PatchStateStore.class, configManager, gson);
		availability = construct(AvailabilityProfile.class, configManager, gson, stateStore);
		PatchLocationStore locations = construct(PatchLocationStore.class, configManager, gson);
		BankLocationStore banks = construct(BankLocationStore.class, configManager, gson);
		GrowthTimer timer = construct(GrowthTimer.class, configManager);
		ShortestPathIntegration router = construct(ShortestPathIntegration.class,
			Mockito.mock(EventBus.class), clientThread);

		stateStore.load();
		availability.load();
		locations.load();
		banks.load();

		ContractState contracts = construct(ContractState.class, configManager);
		com.dooglemaps.state.SeedSelectionStore selection = construct(
			com.dooglemaps.state.SeedSelectionStore.class, configManager, gson, contracts);
		com.dooglemaps.state.SeedInventoryStore seedInventory = construct(
			com.dooglemaps.state.SeedInventoryStore.class,
			Mockito.mock(net.runelite.api.Client.class), configManager, gson);
		selection.load();
		seedInventory.load();

		planner = construct(RunPlanner.class, availability, locations, banks, selection,
			seedInventory, stateStore, timer, router,
			construct(com.dooglemaps.state.PlayerLocation.class,
				Mockito.mock(net.runelite.api.Client.class)),
			Mockito.mock(com.dooglemaps.bank.ToolNeeds.class),
			Mockito.mock(com.dooglemaps.state.ProtectedPatches.class),
			groups = Mockito.mock(com.dooglemaps.state.PlantingGroups.class),
			Mockito.mock(com.dooglemaps.state.ProtectionSelectionStore.class),
			runOptions = Mockito.mock(com.dooglemaps.state.RunTypeStore.class),
			Mockito.mock(com.dooglemaps.state.CompostRunStore.class),
			Mockito.mock(com.dooglemaps.DoogleMapsConfig.class),
			Mockito.mock(com.dooglemaps.bank.BankContents.class),
			Mockito.mock(com.dooglemaps.guide.CarriedItems.class));

		guildHerb = guildPatch(PatchImplementation.HERB);
		availability.setAvailable(guildHerb, true);

		// A contract-only run: the Farming contract line is ticked and the ordinary herb line is
		// not, so nothing at the guild belongs to the run by patch. With the contract handed back
		// the patch is in its plain group again, which is exactly what leaves the run nothing.
		when(groups.groupFor(guildHerb)).thenReturn(PlantingGroup.of(PatchImplementation.HERB));
		when(groups.contractPatchType()).thenReturn(PatchImplementation.HERB);
		when(runOptions.isSelected(RunOption.full(contractGroup))).thenReturn(true);

		// And the guide has no step for the bare patch: no seed is allocated to a group nobody
		// ticked. This is the push that completed the stop under the player.
		planner.setNothingToDo(Collections.singleton(guildHerb.getKey()));
	}

	/** Start run plans the guild for the hand-in alone, on a run with no patch work there. */
	@Test
	public void startRunPlansTheGuildForJaneAlone()
	{
		planner.setContractBusinessOutstanding(true);

		List<RunStop> stops = planner.start(EnumSet.of(PatchImplementation.HERB));

		assertEquals("the guild, for the reward and the contract behind it", 1, stops.size());
		assertEquals(ContractState.FARMING_GUILD_REGION,
			stops.get(0).getRegion().getRegionId());
		assertTrue("a run with somewhere to go is a run", planner.isActive());
		assertFalse("and the stop is not finished before it is reached",
			planner.getRemaining().isEmpty());
	}

	/** The state the run died in: the patch is bare and idle, and only Jane holds the stop open. */
	@Test
	public void theStopStaysOpenWhileTheChainIsUnfinished()
	{
		planner.setContractBusinessOutstanding(true);
		planner.start(EnumSet.of(PatchImplementation.HERB));

		assertFalse(planner.getRemaining().isEmpty());
	}

	/** And it closes normally once she is done, so the run still ends. */
	@Test
	public void theStopFinishesOnceSheIsDoneWithYou()
	{
		planner.setContractBusinessOutstanding(true);
		planner.start(EnumSet.of(PatchImplementation.HERB));

		// The contract taken and planted: nothing is owed at the guild any more.
		planner.setContractBusinessOutstanding(false);

		assertTrue("a run that cannot end is worse than one that ends early",
			planner.getRemaining().isEmpty());
	}

	/** Nothing outstanding, nothing planned: the guild is not visited for its own sake. */
	@Test
	public void aRunWithNoContractBusinessPlansNothingThere()
	{
		planner.setContractBusinessOutstanding(false);

		assertTrue(planner.start(EnumSet.of(PatchImplementation.HERB)).isEmpty());
		assertFalse(planner.isActive());
	}

	/** The tick is still the gate: an unticked contract sends nobody to the guild. */
	@Test
	public void anUntickedContractIsNotAReasonToGo()
	{
		when(runOptions.isSelected(RunOption.full(contractGroup))).thenReturn(false);
		planner.setContractBusinessOutstanding(true);

		assertTrue(planner.start(EnumSet.of(PatchImplementation.HERB)).isEmpty());
	}

	private static FarmPatch guildPatch(PatchImplementation type)
	{
		for (FarmPatch candidate : FarmingWorldData.getPatches(type))
		{
			if (candidate.getRegion().getRegionId() == ContractState.FARMING_GUILD_REGION)
			{
				return candidate;
			}
		}
		throw new AssertionError("no " + type + " patch in the Farming Guild");
	}
}
