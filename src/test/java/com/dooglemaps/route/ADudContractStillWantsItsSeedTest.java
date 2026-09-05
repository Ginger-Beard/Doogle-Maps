package com.dooglemaps.route;

import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.data.RunOption;
import com.dooglemaps.data.Seed;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.state.ContractState;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.SeedSource;
import com.dooglemaps.timer.GrowthTimer;
import com.google.gson.Gson;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * A contract whose crop is standing there spent is a contract that still wants its seed.
 *
 * <h2>The run that opened at a bank and then said nothing at all</h2>
 *
 * Reported from play on a contract-only run. The player already had a poison ivy bush planted
 * <b>and health-checked</b> when Jane handed out a poison ivy contract — and for the check-health
 * families the check <i>is</i> the completion event, so the bush standing there can never satisfy
 * the contract however many berries it grows. The only way forward is to dig it up and plant a
 * fresh seed.
 *
 * <p>The planner read it the other way. {@code plantsNothing} asks whether the contract's crop is
 * standing ripe in the contract's own patch and, when it is, skips the group for seeds — right for
 * a crop that grew during the contract and wants handing in, and exactly wrong here. From that one
 * answer the whole run unravelled, with nothing thrown and every piece behaving as designed:
 *
 * <ol>
 *   <li>no seed was picked for anything the run visits, so
 *   <li>{@code needsSupplyTrip} answered yes on its "we cannot tell what this trip needs" clause,
 *       so
 *   <li>the run opened at a bank — the guild has one, so it opened at a bank while standing in the
 *       guild — and
 *   <li>{@code GuideTracker.computeStepsHere} returns before every step while the bank leg runs.
 * </ol>
 *
 * <p>So the guide went silent, on every tick, through repeated Stop/Start; the log said
 * <i>"seeds picked for this run: none ... -&gt; starting at a bank"</i> each time. The rest of the
 * machinery was already right: the patch stays actionable for its replant, {@code GuidePlan} has
 * the dig-up step, and the guide has words for the dud. All of it hung off a seed nobody allocated.
 */
public class ADudContractStillWantsItsSeedTest
{
	private final Map<String, String> stored = new HashMap<>();

	private RunPlanner planner;
	private AvailabilityProfile availability;
	private PatchStateStore stateStore;
	private com.dooglemaps.state.SeedInventoryStore seedInventory;
	private com.dooglemaps.state.PlantingGroups groups;
	private com.dooglemaps.state.RunTypeStore runOptions;
	private ContractState contracts;
	private FarmPatch bush;

	private final PlantingGroup contractGroup = PlantingGroup.contract(PatchImplementation.BUSH);

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

		stateStore = construct(PatchStateStore.class, configManager, gson);
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

		// The real contract store and the real selection store, because the contract's seed is
		// derived from the assignment rather than picked — a mock either side of that derivation
		// would be the test asserting its own stubbing.
		contracts = construct(ContractState.class, configManager);
		com.dooglemaps.state.SeedSelectionStore selection = construct(
			com.dooglemaps.state.SeedSelectionStore.class, configManager, gson, contracts);
		seedInventory = construct(com.dooglemaps.state.SeedInventoryStore.class,
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

		bush = guildPatch(PatchImplementation.BUSH);
		assertNotNull("the Farming Guild has no bush patch in the data", bush);
		availability.setAvailable(bush, true);

		// The reported world: a poison ivy contract assigned, and a poison ivy bush standing
		// health-checked and picked clean in the guild's own patch.
		contracts.recordAssigned(Produce.POISON_IVY);
		when(groups.contractCrop()).thenReturn(Produce.POISON_IVY);
		when(groups.contractPatchType()).thenReturn(PatchImplementation.BUSH);
		when(groups.groupFor(bush)).thenReturn(contractGroup);
		when(runOptions.isSelected(RunOption.full(contractGroup))).thenReturn(true);
		record(bush, harvestableValue(bush, Produce.POISON_IVY));
	}

	/** The seed is wanted, so the run owes the container holding it a visit. */
	@Test
	public void theSpentCropsReplacementSeedIsCollected()
	{
		stock(SeedSource.SEED_VAULT, Seed.POISON_IVY, 7);

		planner.start(EnumSet.of(PatchImplementation.BUSH));

		assertTrue("the dud has to be dug up and replanted, which starts with the seed: "
				+ planner.getSupplySources(),
			planner.getSupplySources().contains(SeedSource.SEED_VAULT));
	}

	/** And with the seeds already on you, the run starts where you are rather than at a bank. */
	@Test
	public void theRunDoesNotOpenAtABankWithTheSeedsInHand()
	{
		stock(SeedSource.INVENTORY, Seed.POISON_IVY, 7);

		planner.start(EnumSet.of(PatchImplementation.BUSH));

		assertTrue("the guild is the stop", planner.isActive());
		assertTrue("nothing is left to collect", planner.getSupplySources().isEmpty());
		assertFalse("with the seed in the pack there is nothing a bank could add",
			planner.isAtBankLeg());
	}

	/** The predicate itself, at the group the loadout and the guide both ask it about. */
	@Test
	public void theStandingCropReadsAsSpent()
	{
		assertTrue("checked before the contract, so no harvest of it can ever count",
			planner.contractStandingIsSpent(contractGroup));
		assertTrue(planner.contractStandingIsSpent(bush));
	}

	/**
	 * A crop that is merely grown — not yet health-checked — is not spent.
	 *
	 * <p>This is the state a contract that ripened while you were logged out is in, and it is the
	 * legitimate "harvest it and hand it in" case: the check has not happened, so it is the check
	 * that will complete the contract. Reading it as spent would send a player to dig up a
	 * finished contract, which is the most expensive mistake this plugin could talk anyone into.
	 */
	@Test
	public void aGrownButUncheckedContractCropIsNotSpent()
	{
		record(bush, grownUncheckedValue(bush, Produce.POISON_IVY));

		assertFalse(planner.contractStandingIsSpent(bush));
		assertFalse(planner.contractStandingIsSpent(contractGroup));
	}

	/** And a crop of the wrong sort standing there says nothing about this contract either. */
	@Test
	public void anotherCropStandingThereIsNotThisContractsBusiness()
	{
		record(bush, harvestableValue(bush, Produce.REDBERRIES));

		assertFalse(planner.contractStandingIsSpent(bush));
	}

	// ------------------------------------------------------------------- helpers

	private void record(FarmPatch patch, int varbitValue)
	{
		ProduceState decoded = patch.getImplementation().forVarbitValue(varbitValue);
		assertNotNull("varbit " + varbitValue + " does not decode for " + patch.getKey(), decoded);
		stateStore.recordVarbit(patch, varbitValue, decoded);
	}

	/** A varbit meaning "this crop, grown and checked", decoded rather than assumed. */
	private static int harvestableValue(FarmPatch patch, Produce produce)
	{
		return valueFor(patch, produce, CropState.HARVESTABLE);
	}

	/** And one meaning "this crop, finished growing but still to be checked". */
	private static int grownUncheckedValue(FarmPatch patch, Produce produce)
	{
		return valueFor(patch, produce, CropState.GROWING);
	}

	private static int valueFor(FarmPatch patch, Produce produce, CropState state)
	{
		int last = -1;
		for (int value = 0; value < 256; value++)
		{
			ProduceState decoded = patch.getImplementation().forVarbitValue(value);
			if (decoded != null && decoded.getProduce() == produce
				&& decoded.getCropState() == state)
			{
				// The last of the growing states, which is the grown-unchecked one; for
				// HARVESTABLE any of them will do and the last is as good as the first.
				last = value;
			}
		}
		if (last < 0)
		{
			throw new AssertionError("no " + state + " varbit decodes for " + produce);
		}
		return last;
	}

	private void stock(SeedSource source, Seed seed, int quantity)
	{
		net.runelite.api.ItemContainer container =
			Mockito.mock(net.runelite.api.ItemContainer.class);
		when(container.getItems()).thenReturn(new net.runelite.api.Item[]{
			new net.runelite.api.Item(seed.getItemID(), quantity),
		});
		seedInventory.record(source.getContainerId(), container);
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
		return null;
	}
}
