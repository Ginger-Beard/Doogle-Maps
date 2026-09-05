package com.dooglemaps.bank;

import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.data.RunOption;
import com.dooglemaps.data.Seed;
import com.dooglemaps.guide.CarriedItems;
import com.dooglemaps.route.BankLocationStore;
import com.dooglemaps.route.PatchLocationStore;
import com.dooglemaps.route.RunPlanner;
import com.dooglemaps.route.ShortestPathIntegration;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.state.CompostSelectionStore;
import com.dooglemaps.state.ContractState;
import com.dooglemaps.state.LeprechaunStore;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.state.SeedSelectionStore;
import com.dooglemaps.timer.GrowthTimer;
import com.google.gson.Gson;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * A contract that grew during the contract still asks for no seed.
 *
 * <h2>The rule this guards, and the rule it guards it against</h2>
 *
 * The contract cycle is harvest, hand in, take the next, then plant <i>that</i> — so while the
 * contract's own crop is standing there grown, the seed is the one thing the trip does not need,
 * and asking for it is what once had a run withdrawing a cactus seed for a cactus standing
 * finished in the guild. That refusal is {@code contractIsStandingThere}.
 *
 * <p>It has since been narrowed, because one shape of "the contract's crop is standing there" is
 * not a finished contract at all: a check-health crop checked <b>before</b> the contract was taken
 * can never satisfy it, and wants digging up and replacing. This test is the other side of that
 * narrowing — a crop that finished growing during the contract, still unchecked, is the genuine
 * article and must go on asking for nothing. Getting this wrong costs a whole growth cycle: the
 * dig-up it would invite destroys the contract's own crop.
 *
 * <p>The loadout asks the planner rather than deciding for itself, so the two cannot bank for one
 * plan and plant another; see {@code RunPlanner.contractStandingIsSpent}.
 */
public class AGrownContractStillNeedsNoSeedTest
{
	private final Map<String, Object> stored = new HashMap<>();

	private PatchStateStore patches;
	private AvailabilityProfile availability;
	private RunLoadout loadout;
	private com.dooglemaps.state.PlantingGroups groups;
	private com.dooglemaps.state.RunTypeStore runTypes;
	private ContractState contracts;
	private FarmPatch bush;

	private final PlantingGroup contractGroup = PlantingGroup.contract(PatchImplementation.BUSH);

	@Before
	public void setUp()
	{
		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer(i -> (String) stored.get(i.getArgument(0) + "." + i.getArgument(1)));
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(int.class)))
			.thenAnswer(i -> stored.get(i.getArgument(0) + "." + i.getArgument(1)));
		stored.put("dooglemaps.farmingLevel", 99);
		doAnswer(i ->
		{
			Object value = i.getArgument(2);
			stored.put(i.getArgument(0) + "." + i.getArgument(1), String.valueOf(value));
			return null;
		}).when(configManager).setRSProfileConfiguration(anyString(), anyString(), any());

		Gson gson = new Gson();
		patches = construct(PatchStateStore.class, configManager, gson);
		patches.load();
		availability = construct(AvailabilityProfile.class, configManager, gson, patches);
		availability.load();

		// One real contract store behind both the selection (which derives the contract's seed
		// from the assignment) and the loadout (which asks what is assigned). Two would let the
		// fixture hold two opinions about one contract.
		contracts = construct(ContractState.class, configManager);
		SeedSelectionStore selection = construct(SeedSelectionStore.class, configManager, gson,
			contracts);
		SeedInventoryStore seeds = construct(SeedInventoryStore.class,
			Mockito.mock(net.runelite.api.Client.class), configManager, gson);
		CompostSelectionStore compost = construct(CompostSelectionStore.class, configManager, gson);
		compost.load();

		CarriedItems carried = construct(CarriedItems.class,
			Mockito.mock(net.runelite.api.Client.class));
		BankContents bank = construct(BankContents.class, configManager, gson);
		com.dooglemaps.state.ProtectionSelectionStore protection = construct(
			com.dooglemaps.state.ProtectionSelectionStore.class, configManager, gson);
		com.dooglemaps.state.PayToClearStore payToClear = construct(
			com.dooglemaps.state.PayToClearStore.class, configManager, gson);

		RunPlanner planner = construct(RunPlanner.class, availability,
			construct(PatchLocationStore.class, configManager, gson),
			construct(BankLocationStore.class, configManager, gson),
			selection, seeds, patches, construct(GrowthTimer.class, configManager),
			construct(ShortestPathIntegration.class, Mockito.mock(EventBus.class),
				Mockito.mock(net.runelite.client.callback.ClientThread.class)),
			construct(com.dooglemaps.state.PlayerLocation.class,
				Mockito.mock(net.runelite.api.Client.class)),
			Mockito.mock(ToolNeeds.class),
			Mockito.mock(com.dooglemaps.state.ProtectedPatches.class),
			groups = Mockito.mock(com.dooglemaps.state.PlantingGroups.class),
			protection,
			runTypes = Mockito.mock(com.dooglemaps.state.RunTypeStore.class),
			Mockito.mock(com.dooglemaps.state.CompostRunStore.class),
			Mockito.mock(com.dooglemaps.DoogleMapsConfig.class),
			bank, carried);

		net.runelite.api.Client leprechaunClient = Mockito.mock(net.runelite.api.Client.class);
		when(leprechaunClient.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
		LeprechaunStore leprechaun = construct(LeprechaunStore.class, leprechaunClient);

		com.dooglemaps.DoogleMapsConfig config =
			Mockito.mock(com.dooglemaps.DoogleMapsConfig.class);
		when(config.teleportItems()).thenReturn("");

		com.dooglemaps.data.ItemNames itemNames = Mockito.mock(com.dooglemaps.data.ItemNames.class);
		when(itemNames.get(Mockito.anyInt(), Mockito.any())).thenAnswer(i -> i.getArgument(1));

		loadout = construct(RunLoadout.class, planner, selection, seeds, compost, carried, bank,
			construct(ToolNeeds.class, leprechaun, carried, bank, selection,
				construct(GrowthTimer.class, configManager),
				construct(com.dooglemaps.state.BarbarianFarming.class, configManager,
					Mockito.mock(com.dooglemaps.DoogleMapsConfig.class)),
				availability, patches),
			leprechaun, protection, payToClear, itemNames, config, tickingClient(),
			runTypes, contracts, Mockito.mock(com.dooglemaps.state.CompostRunStore.class),
			construct(com.dooglemaps.bank.BoatHolds.class, configManager, gson));

		bush = guildPatch(PatchImplementation.BUSH);
		assertNotNull("the Farming Guild has no bush patch in the data", bush);
		availability.setAvailable(bush, true);

		// Seeds in the bank throughout, which is what makes the refusal worth asserting: a run
		// that asks for nothing because there is nothing to ask for proves nothing at all.
		stockBank(seeds, Seed.POISON_IVY, 7);

		contracts.recordAssigned(Produce.POISON_IVY);
		when(groups.contractCrop()).thenReturn(Produce.POISON_IVY);
		when(groups.groupFor(bush)).thenReturn(contractGroup);
		when(runTypes.isSelected(RunOption.full(contractGroup))).thenReturn(true);
	}

	/**
	 * The contract's crop grown and waiting on its health check: nothing to withdraw.
	 *
	 * <p>The awaiting-hand-in record is set as well, which is the ordinary shape of this state —
	 * the completion fired and was captured — and it must not be what the answer turns on: a
	 * contract that ripened while the player was logged out sends no message at all, and the patch
	 * standing there full is the fact no event can be missed for.
	 */
	@Test
	public void aGrownUncheckedContractAsksForNothing()
	{
		record(bush, grownUncheckedValue(bush, Produce.POISON_IVY));
		contracts.recordCompleted();

		assertTrue("the seed is the one thing this trip does not need: " + seedRows(),
			seedRows().isEmpty());
	}

	/** Without the record either — the logged-out case — the ground alone still answers. */
	@Test
	public void theSameIsTrueWithNothingRecorded()
	{
		record(bush, grownUncheckedValue(bush, Produce.POISON_IVY));

		assertTrue("the patch is full, which is what decides it: " + seedRows(),
			seedRows().isEmpty());
	}

	/**
	 * And the case the narrowing was for: a crop checked before the contract does want its seed.
	 *
	 * <p>Kept in this file deliberately, beside the rule it is the exception to. The two states
	 * differ by one health check and by nothing else visible, so a test of either alone reads as
	 * an assertion about bushes rather than about contracts.
	 */
	@Test
	public void aSpentCropDoesWantItsSeed()
	{
		record(bush, harvestableValue(bush, Produce.POISON_IVY));

		List<LoadoutItem> rows = seedRows();
		assertEquals("dig it up and plant a fresh one, which starts at the bank: " + rows,
			1, rows.size());
		assertTrue("and it is the contract's own seed: " + rows.get(0).getName(),
			rows.get(0).getName().startsWith(Seed.POISON_IVY.getName()));
	}

	// ------------------------------------------------------------------- helpers

	private List<LoadoutItem> seedRows()
	{
		List<LoadoutItem> found = new java.util.ArrayList<>();
		for (LoadoutItem item : loadout.forRun(EnumSet.of(PatchImplementation.BUSH)))
		{
			if (item.getCategory() == LoadoutItem.Category.SEED)
			{
				found.add(item);
			}
		}
		return found;
	}

	private void record(FarmPatch patch, int varbitValue)
	{
		ProduceState decoded = patch.getImplementation().forVarbitValue(varbitValue);
		assertNotNull("varbit " + varbitValue + " does not decode for " + patch.getKey(), decoded);
		patches.recordVarbit(patch, varbitValue, decoded);
	}

	private static int harvestableValue(FarmPatch patch, Produce produce)
	{
		return valueFor(patch, produce, CropState.HARVESTABLE);
	}

	private static int grownUncheckedValue(FarmPatch patch, Produce produce)
	{
		return valueFor(patch, produce, CropState.GROWING);
	}

	/** The last varbit decoding to this crop in this state, which for GROWING is the grown one. */
	private static int valueFor(FarmPatch patch, Produce produce, CropState state)
	{
		int last = -1;
		for (int value = 0; value < 256; value++)
		{
			ProduceState decoded = patch.getImplementation().forVarbitValue(value);
			if (decoded != null && decoded.getProduce() == produce
				&& decoded.getCropState() == state)
			{
				last = value;
			}
		}
		if (last < 0)
		{
			throw new AssertionError("no " + state + " varbit decodes for " + produce);
		}
		return last;
	}

	private static void stockBank(SeedInventoryStore seeds, Seed seed, int quantity)
	{
		net.runelite.api.ItemContainer container =
			Mockito.mock(net.runelite.api.ItemContainer.class);
		when(container.getItems()).thenReturn(new net.runelite.api.Item[]{
			new net.runelite.api.Item(seed.getItemID(), quantity),
		});
		seeds.record(com.dooglemaps.state.SeedSource.BANK.getContainerId(), container);
	}

	private static net.runelite.api.Client tickingClient()
	{
		net.runelite.api.Client client = Mockito.mock(net.runelite.api.Client.class);
		int[] tick = {0};
		when(client.getTickCount()).thenAnswer(i -> tick[0]++);
		return client;
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
