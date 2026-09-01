package com.dooglemaps.route;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.SeedSource;
import com.dooglemaps.timer.GrowthTimer;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Covers the two decisions the planner actually makes: which patches are worth visiting,
 * and what gets handed to the router.
 *
 * <p>Ordering is deliberately absent, because the planner does not do any — it posts the
 * whole outstanding set and Shortest Path picks. What is worth pinning down is that the set
 * contains the right things at the right time.
 */
public class RunPlannerTest
{
	private static final String FALADOR_HERB = "12083.4774";
	private static final String CATHERBY_HERB = "11062.4774";
	private static final String ARDOUGNE_HERB = "10548.4774";

	private Map<String, String> stored;
	private ConfigManager configManager;
	private PatchStateStore stateStore;
	private GrowthTimer timer;
	private AvailabilityProfile availability;
	private com.dooglemaps.state.SeedSelectionStore selection;
	private com.dooglemaps.state.SeedInventoryStore seedInventory;
	private BankLocationStore banks;
	private RunPlanner planner;
	private ShortestPathIntegration router;
	private com.dooglemaps.state.PlantingGroups groups;
	private com.dooglemaps.bank.ToolNeeds tools;
	private net.runelite.api.Client client;
	private com.dooglemaps.state.PlayerLocation playerLocation;
	private com.dooglemaps.state.RunTypeStore runOptions;

	/** The bin choices; the planner reads the fodder toggle when it plans stops. */
	private com.dooglemaps.state.CompostRunStore compostRun;
	private com.dooglemaps.DoogleMapsConfig pluginConfig;

	/**
	 * The real store rather than a mock, because {@code SeedSelectionStore} reads it to derive a
	 * contract's seed and the two have to agree. Writing an assignment into it is what makes
	 * {@code getSelectedFor(contract group)} answer.
	 */
	private com.dooglemaps.state.ContractState contracts;

	/** Every PluginMessage the planner caused, in order. */
	private List<PluginMessage> posted;

	@Before
	public void setUp() throws Exception
	{
		stored = new HashMap<>();
		posted = new ArrayList<>();
		Gson gson = new Gson();

		configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer(i -> stored.get(key(i)));
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(int.class)))
			.thenAnswer(i -> null);
		// A Farming level, because the supply-sources question now goes through the same
		// allocation as the loadout, and an allocation at level 0 plants nothing — which is
		// never the state a run is in: the level is recorded the moment the client has it.
		when(configManager.getRSProfileConfiguration(anyString(), eq("farmingLevel"),
			eq(int.class))).thenReturn(99);
		doAnswer(i ->
		{
			Object value = i.getArgument(2);
			stored.put(key(i), String.valueOf(value));
			return null;
		}).when(configManager).setRSProfileConfiguration(anyString(), anyString(), any());

		EventBus eventBus = Mockito.mock(EventBus.class);
		doAnswer(i ->
		{
			Object event = i.getArgument(0);
			if (event instanceof PluginMessage)
			{
				posted.add((PluginMessage) event);
			}
			return null;
		}).when(eventBus).post(any());

		stateStore = construct(PatchStateStore.class, configManager, gson);
		availability = construct(AvailabilityProfile.class, configManager, gson, stateStore);
		PatchLocationStore locations = construct(PatchLocationStore.class, configManager, gson);
		patchLocations = locations;
		banks = construct(BankLocationStore.class, configManager, gson);
		timer = construct(GrowthTimer.class, configManager);
		// Run queued client-thread work immediately, so the test sees what was posted.
		net.runelite.client.callback.ClientThread clientThread =
			Mockito.mock(net.runelite.client.callback.ClientThread.class);
		doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		router = construct(ShortestPathIntegration.class, eventBus, clientThread);

		stateStore.load();
		availability.load();
		locations.load();
		banks.load();

		contracts = construct(com.dooglemaps.state.ContractState.class, configManager);
		selection = construct(com.dooglemaps.state.SeedSelectionStore.class, configManager, gson,
			contracts);
		seedInventory = construct(com.dooglemaps.state.SeedInventoryStore.class,
			Mockito.mock(net.runelite.api.Client.class), configManager, gson);
		selection.load();
		seedInventory.load();

		client = Mockito.mock(net.runelite.api.Client.class);
		playerLocation = construct(com.dooglemaps.state.PlayerLocation.class, client);
		planner = construct(RunPlanner.class, availability, locations, banks, selection,
			seedInventory, stateStore, timer, router,
			playerLocation, tools = Mockito.mock(com.dooglemaps.bank.ToolNeeds.class),
			Mockito.mock(com.dooglemaps.state.ProtectedPatches.class),
			groups = Mockito.mock(com.dooglemaps.state.PlantingGroups.class),
			Mockito.mock(com.dooglemaps.state.ProtectionSelectionStore.class),
			runOptions = Mockito.mock(com.dooglemaps.state.RunTypeStore.class),
			compostRun = Mockito.mock(com.dooglemaps.state.CompostRunStore.class),
			pluginConfig = Mockito.mock(com.dooglemaps.DoogleMapsConfig.class));
	}

	/**
	 * The withdraw list the planner asks whether the shopping is finished.
	 *
	 * <p>A mock answering "nothing outstanding" by default, so these tests keep exercising the
	 * seed-and-tool reasoning they were written for. The list is the planner's <i>additional</i>
	 * source of things to collect — the axe, the payments, the compost — and a test that cares
	 * about those stubs it. See {@code RunPlanner.suppliesOutstanding}.
	 */
	private com.dooglemaps.bank.RunLoadout loadout =
		Mockito.mock(com.dooglemaps.bank.RunLoadout.class);

	/**
	 * A contract taken mid-run brings its patch into the stop you are standing in.
	 *
	 * <h2>The gap this closes</h2>
	 *
	 * The contract chain happens inside the guild stop — hand the finished one in, take the next,
	 * plant it before leaving — and which crop Jane names cannot be known until she names it. So
	 * the stop was planned without the patch it wants, its list was fixed at that moment, and
	 * {@code contractNote} read the absence as "this run cannot deal with your contract".
	 *
	 * <p>Reported as a yew contract written off for the week with a grown tree standing in the
	 * patch — a check and a clear away from being plantable.
	 */
	@Test
	public void aContractTakenMidRunJoinsTheStop()
	{
		// A grown but unchecked tree: the state the reported one was actually in.
		FarmPatch tree = guildPatch(PatchImplementation.TREE);
		assertNotNull("the guild has no tree patch in the data", tree);
		record(tree.getKey(), grownUnchecked(tree));
		availability.setAvailable(tree, true);

		FarmPatch herb = guildPatch(PatchImplementation.HERB);
		assertNotNull("the guild has no herb patch in the data", herb);
		// Weeds, i.e. an empty patch: something the run will certainly plan to visit.
		record(herb.getKey(), 3);
		availability.setAvailable(herb, true);

		// A run over herbs only. Nothing knows a tree contract is coming, because it has not
		// been handed out yet.
		planner.start(EnumSet.of(PatchImplementation.HERB));
		RunStop guild = planner.getRemaining().stream()
			.filter(stop -> stop.getRegion().getRegionId() == herb.getRegion().getRegionId())
			.findFirst()
			.orElseThrow(() -> new AssertionError("no guild stop was planned"));
		assertFalse("the run was planned without it", guild.getPatches().contains(tree));

		// Jane hands out a yew contract while you are standing there.
		when(groups.contractCrop()).thenReturn(Produce.YEW);
		tickTheContract(PatchImplementation.TREE);
		when(groups.groupFor(tree))
			.thenReturn(com.dooglemaps.data.PlantingGroup.contract(PatchImplementation.TREE));
		planner.reviewContract();

		assertTrue("the patch the contract wants is now part of the stop",
			guild.getPatches().contains(tree));
	}

	/**
	 * A contract taken mid-run sends you back for what it needs.
	 *
	 * <h2>Why the supply leg cannot have covered it</h2>
	 *
	 * The bank trip happens at the start. A contract handed out an hour later can want things
	 * nothing on that trip had a reason to bring — its own seed, and for a tree contract on a run
	 * that was never visiting a tree, an axe.
	 *
	 * <p>Reported from play: told to check the health of a magic tree with neither axe nor sapling
	 * in the pack, because the run was planned over herbs.
	 */
	@Test
	public void aContractTakenMidRunSendsYouBackForItsTools()
	{
		FarmPatch tree = guildPatch(PatchImplementation.TREE);
		record(tree.getKey(), grownUnchecked(tree));
		availability.setAvailable(tree, true);

		FarmPatch herb = guildPatch(PatchImplementation.HERB);
		record(herb.getKey(), 3);
		availability.setAvailable(herb, true);

		// Nothing outstanding when the run was planned: no tool is bank-only for a herb run.
		planner.start(EnumSet.of(PatchImplementation.HERB));
		planner.leaveBank();
		assertFalse("the supply leg is done with", planner.isAtBankLeg());

		// The contract arrives, and its axe is in the bank.
		when(tools.anyOnlyInBank(Mockito.any())).thenReturn(true);
		when(groups.contractCrop()).thenReturn(Produce.YEW);
		tickTheContract(PatchImplementation.TREE);
		when(groups.groupFor(tree))
			.thenReturn(com.dooglemaps.data.PlantingGroup.contract(PatchImplementation.TREE));
		planner.reviewContract();

		assertTrue("collect the axe and the sapling before doing the contract",
			planner.isAtBankLeg());
	}

	/**
	 * A contract's own seed is something the run has to go and collect.
	 *
	 * <h2>The planner could not see it at all</h2>
	 *
	 * Seeds were resolved by asking {@code SeedSelectionStore} for each patch <i>type</i> in the
	 * run. That overload filters the flat set of picks the player made, and a contract's seed is
	 * never in it — it is derived from the assignment, deliberately, because nobody picked it. So
	 * every routing decision here was made as though the yew did not exist: the vault was not
	 * owed, the supply leg was not aimed at it, and {@code leaveBank} was happy to declare the
	 * shopping finished with no sapling in the pack.
	 *
	 * <p>Meanwhile {@code RunLoadout} resolved by planting <i>group</i>, saw it, and put it on the
	 * withdraw list. Reported from play as a list reading "from the bank: yew" while the seed vault
	 * was the thing lit up — two components answering one question two ways.
	 */
	@Test
	public void aContractSeedIsCollectedLikeAnyOther()
	{
		FarmPatch tree = guildPatch(PatchImplementation.TREE);
		record(tree.getKey(), grownUnchecked(tree));
		availability.setAvailable(tree, true);

		FarmPatch herb = guildPatch(PatchImplementation.HERB);
		record(herb.getKey(), 3);
		availability.setAvailable(herb, true);

		// The sapling is in the vault, and it is the only thing this run is short of. Nothing is
		// ticked for trees — that is the point: the player never chose a tree seed, Jane did.
		stockVault(com.dooglemaps.data.Seed.YEW, 1);

		planner.start(EnumSet.of(PatchImplementation.HERB));

		when(groups.contractCrop()).thenReturn(Produce.YEW);
		tickTheContract(PatchImplementation.TREE);
		com.dooglemaps.data.PlantingGroup contractGroup =
			com.dooglemaps.data.PlantingGroup.contract(PatchImplementation.TREE);
		when(groups.groupFor(tree)).thenReturn(contractGroup);
		// The player has ticked Farming contract, which is what puts the contract's patch in the
		// run at all - see inTheRun. Without it the guide says so rather than silently planting.
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.full(contractGroup)))
			.thenReturn(true);
		contracts.recordAssigned(Produce.YEW);
		planner.reviewContract();

		System.out.println("PROBE sources=" + planner.getSupplySources()
			+ " targets=" + lastTargets() + " vault=" + banks.getSeedVault());
		System.out.println("PROBE usableBanks=" + banks.getUsableBanks());
		for (PluginMessage m : posted)
		{
			System.out.println("PROBE msg " + m.getName() + " -> " + m.getData());
		}
		assertTrue("the vault holds the contract's sapling, so the run owes it a visit",
			planner.getSupplySources().contains(SeedSource.SEED_VAULT));
		assertTrue("and the route has to go there",
			lastTargets().contains(banks.getSeedVault()));
	}

	/**
	 * Taking a tree contract does not go shopping for every tree seed ever ticked.
	 *
	 * <h2>The same wrong question, answered too broadly instead of too narrowly</h2>
	 *
	 * A contract adds its patch type to the live run, which is right — the guild's patch has to be
	 * serviced whether or not the player asked for trees. Asking the selection store by <i>type</i>
	 * then pulled in every tree seed the account had ever picked, for a run whose only tree patch
	 * belongs to the contract. A magic sapling chosen months ago became a thing this trip had to
	 * fetch, and the route was aimed at wherever it happened to live.
	 *
	 * <p>Resolving by group has no such problem: the guild's tree patch is in the contract group
	 * and nothing else is.
	 */
	@Test
	public void aTreeContractDoesNotDragInEveryTreeSeedYouEverPicked()
	{
		FarmPatch tree = guildPatch(PatchImplementation.TREE);
		record(tree.getKey(), grownUnchecked(tree));
		availability.setAvailable(tree, true);

		FarmPatch herb = guildPatch(PatchImplementation.HERB);
		record(herb.getKey(), 3);
		availability.setAvailable(herb, true);

		// Magic was picked for trees at some point and is sitting in the vault. The contract is
		// for a yew, which is in the bank.
		selection.toggle(com.dooglemaps.data.Seed.MAGIC);
		stockVault(com.dooglemaps.data.Seed.MAGIC, 5);
		stockBank(com.dooglemaps.data.Seed.YEW, 5);

		planner.start(EnumSet.of(PatchImplementation.HERB));

		when(groups.contractCrop()).thenReturn(Produce.YEW);
		tickTheContract(PatchImplementation.TREE);
		com.dooglemaps.data.PlantingGroup contractGroup =
			com.dooglemaps.data.PlantingGroup.contract(PatchImplementation.TREE);
		when(groups.groupFor(tree)).thenReturn(contractGroup);
		// The player has ticked Farming contract, which is what puts the contract's patch in the
		// run at all - see inTheRun. Without it the guide says so rather than silently planting.
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.full(contractGroup)))
			.thenReturn(true);
		contracts.recordAssigned(Produce.YEW);
		planner.reviewContract();

		assertFalse("the contract's patch wants a yew, so the magic in the vault is not this "
				+ "run's problem",
			planner.getSupplySources().contains(SeedSource.SEED_VAULT));
	}

	/**
	 * Emptying one container redraws the route for the other.
	 *
	 * <p>The leg visits two now, and finishes them one at a time. The route is posted rather than
	 * polled, so without noticing the change the line keeps pointing at a vault whose seeds are
	 * already in the pack.
	 */
	@Test
	public void emptyingOneContainerRedrawsForTheOther()
	{
		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);
		when(runOptions.isSelected(Mockito.any())).thenReturn(true);

		when(tools.anyOnlyInBank(Mockito.any())).thenReturn(true);
		planner.start(EnumSet.of(PatchImplementation.HERB));
		assertTrue(planner.isAtBankLeg());

		int postsBefore = posted.size();

		// Nothing about the run changed, so nothing should be redrawn.
		planner.leaveBank();
		assertEquals("an unchanged leg posts nothing", postsBefore, posted.size());
	}

	/** And it does not send you back a second time for a contract already accounted for. */
	@Test
	public void asettledContractDoesNotKeepReopeningTheBankLeg()
	{
		FarmPatch tree = guildPatch(PatchImplementation.TREE);
		record(tree.getKey(), grownUnchecked(tree));
		availability.setAvailable(tree, true);

		FarmPatch herb = guildPatch(PatchImplementation.HERB);
		record(herb.getKey(), 3);
		availability.setAvailable(herb, true);

		planner.start(EnumSet.of(PatchImplementation.HERB));
		when(tools.anyOnlyInBank(Mockito.any())).thenReturn(true);
		when(groups.contractCrop()).thenReturn(Produce.YEW);
		tickTheContract(PatchImplementation.TREE);
		when(groups.groupFor(tree))
			.thenReturn(com.dooglemaps.data.PlantingGroup.contract(PatchImplementation.TREE));

		planner.reviewContract();

		// The axe is now in the pack, so the leg ends the way it normally would.
		when(tools.anyOnlyInBank(Mockito.any())).thenReturn(false);
		planner.leaveBank();

		// The tick loop calls this again, and again, for as long as the contract is assigned.
		planner.reviewContract();
		planner.reviewContract();

		assertFalse("one diversion, not one a tick", planner.isAtBankLeg());
	}

	/**
	 * The route is re-posted when a contract widens the run, even mid-collection.
	 *
	 * <h2>The three-way disagreement this caused</h2>
	 *
	 * A route is posted once per leg and {@code getSupplyTargets} is derived from the run's types.
	 * Starting a run at a bank means the supply leg is already under way, so the old test — divert
	 * only if we are not already collecting — skipped the re-post, and the drawn line stayed aimed
	 * where it was before the contract existed. The seed vault highlight is read fresh every tick,
	 * so it had already moved: the same value, at two different ages.
	 *
	 * <p>Reported from play: vault outlined, path drawn to the bank, withdraw list naming neither.
	 */
	@Test
	public void wideningTheRunRepostsTheRouteEvenWhileAlreadyCollecting()
	{
		FarmPatch tree = guildPatch(PatchImplementation.TREE);
		record(tree.getKey(), grownUnchecked(tree));
		availability.setAvailable(tree, true);

		FarmPatch herb = guildPatch(PatchImplementation.HERB);
		record(herb.getKey(), 3);
		availability.setAvailable(herb, true);

		when(tools.anyOnlyInBank(Mockito.any())).thenReturn(true);
		planner.start(EnumSet.of(PatchImplementation.HERB));
		assertTrue("the run opens by collecting", planner.isAtBankLeg());

		int postsBefore = posted.size();

		when(groups.contractCrop()).thenReturn(Produce.YEW);
		tickTheContract(PatchImplementation.TREE);
		when(groups.groupFor(tree))
			.thenReturn(com.dooglemaps.data.PlantingGroup.contract(PatchImplementation.TREE));
		planner.reviewContract();

		assertTrue("the line has to be redrawn for what the run now needs",
			posted.size() > postsBefore);
	}

	/**
	 * And the run reports the types it is actually covering, not the boxes that were ticked.
	 *
	 * <p>Everything scoped to "what does this run need" — the withdraw list, the bank highlight,
	 * the bank filter — used to ask {@code RunTypeStore}, which records a choice the player made.
	 * A contract taken mid-run is not such a choice, so the store never hears about it and those
	 * three were left describing a narrower run than the one being routed.
	 */
	@Test
	public void theRunReportsTheTypesItCoversIncludingTheContracts()
	{
		FarmPatch tree = guildPatch(PatchImplementation.TREE);
		record(tree.getKey(), grownUnchecked(tree));
		availability.setAvailable(tree, true);

		FarmPatch herb = guildPatch(PatchImplementation.HERB);
		record(herb.getKey(), 3);
		availability.setAvailable(herb, true);

		planner.start(EnumSet.of(PatchImplementation.HERB));
		assertFalse("trees were never ticked",
			planner.coveredTypes().contains(PatchImplementation.TREE));

		when(groups.contractCrop()).thenReturn(Produce.YEW);
		tickTheContract(PatchImplementation.TREE);
		when(groups.groupFor(tree))
			.thenReturn(com.dooglemaps.data.PlantingGroup.contract(PatchImplementation.TREE));
		planner.reviewContract();

		assertTrue("but the run is covering one now",
			planner.coveredTypes().contains(PatchImplementation.TREE));
		assertTrue("without losing what was ticked",
			planner.coveredTypes().contains(PatchImplementation.HERB));
	}

	/** With no run in flight there is only one answer, and it is the ticked boxes. */
	@Test
	public void withNoRunTheCoveredTypesAreTheTickedOnes()
	{
		when(runOptions.getSelected()).thenReturn(EnumSet.of(PatchImplementation.HERB));

		assertFalse("nothing is running", planner.isActive());
		assertEquals(EnumSet.of(PatchImplementation.HERB), planner.coveredTypes());
	}

	/**
	 * Ticking a farming contract asks for the contract's patch, not every patch of its type.
	 *
	 * <h2>What one tick used to mean</h2>
	 *
	 * The contract option is stored as {@code TREE#contract}; {@code RunTypeStore.typeOf} strips
	 * from the {@code #} so the run covers {@code TREE}; and stop planning was scoped by type, so it
	 * swept in every tree patch on the account. Reported from play as an eighteen-stop run that
	 * should have been twelve, asking for two yew saplings, twenty-five coconuts to protect a magic
	 * tree in another kingdom and ten cactus spines for the yew — none of it ticked for.
	 *
	 * <p>Run options are per planting group and the run is carried as a set of types; this is the
	 * seam between them, and it belongs on the question "did the player ask for this patch" rather
	 * than on "does this patch want work". See {@code inTheRun}.
	 */
	@Test
	public void aContractTickDoesNotDragInEveryPatchOfItsType()
	{
		FarmPatch guildTree = guildPatch(PatchImplementation.TREE);
		FarmPatch otherTree = null;
		for (FarmPatch candidate : FarmingWorldData.getPatches(PatchImplementation.TREE))
		{
			if (candidate.getRegion().getRegionId() != guildTree.getRegion().getRegionId())
			{
				otherTree = candidate;
				break;
			}
		}
		assertNotNull("the data has only one tree patch", otherTree);

		// Both empty and both wanting a sapling, so only the selection can tell them apart.
		record(guildTree.getKey(), 0);
		record(otherTree.getKey(), 0);
		availability.setAvailable(guildTree, true);
		availability.setAvailable(otherTree, true);

		// The guild's is the contract's; the other is an ordinary tree patch.
		when(groups.groupFor(guildTree))
			.thenReturn(com.dooglemaps.data.PlantingGroup.contract(PatchImplementation.TREE));
		when(groups.groupFor(otherTree))
			.thenReturn(com.dooglemaps.data.PlantingGroup.of(PatchImplementation.TREE));

		// Only the contract is ticked, which is what "Farming contract" on the panel means.
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.contract(PatchImplementation.TREE)))).thenReturn(true);

		List<FarmPatch> planned = new ArrayList<>();
		for (RunStop stop : planner.previewStops(EnumSet.of(PatchImplementation.TREE)))
		{
			planned.addAll(stop.getPatches());
		}

		assertTrue("the contract's patch is what was asked for", planned.contains(guildTree));
		assertFalse("a tree patch nobody ticked is not part of the run",
			planned.contains(otherTree));
	}

	/** Taking one is enough on its own to reopen a guild the run had already finished with. */
	@Test
	public void aContractReopensAStopThatHadNothingLeft()
	{
		FarmPatch tree = guildPatch(PatchImplementation.TREE);
		assertNotNull("the guild has no tree patch in the data", tree);
		record(tree.getKey(), grownUnchecked(tree));
		availability.setAvailable(tree, true);

		// No stop here at all: the run was over herbs elsewhere.
		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB));

		assertTrue("nothing planned in the guild", planner.getRemaining().stream()
			.noneMatch(stop -> stop.getRegion().getRegionId() == tree.getRegion().getRegionId()));

		when(groups.contractCrop()).thenReturn(Produce.YEW);
		tickTheContract(PatchImplementation.TREE);
		when(groups.groupFor(tree))
			.thenReturn(com.dooglemaps.data.PlantingGroup.contract(PatchImplementation.TREE));
		planner.reviewContract();

		assertTrue("a contract can only be done in the guild, so the run has to go there",
			planner.getRemaining().stream()
				.anyMatch(stop -> stop.getRegion().getRegionId() == tree.getRegion().getRegionId()));
	}

	/** The varbit for this patch holding a crop that has finished growing but not been checked. */
	private static int grownUnchecked(FarmPatch patch)
	{
		for (int value = 0; value < 256; value++)
		{
			ProduceState decoded = patch.getImplementation().forVarbitValue(value);
			if (decoded != null && decoded.getProduce() != null && decoded.getProduce().isCrop()
				&& decoded.getCropState() == com.dooglemaps.data.CropState.GROWING
				&& decoded.getStage() == decoded.getProduce().getStages() - 1)
			{
				return value;
			}
		}
		throw new IllegalStateException("no grown-but-unchecked varbit for " + patch.getKey());
	}

	/** Catherby's fruit tree. Varbit 20 is a laden apple tree, 14 the same tree picked clean. */
	private static final String CATHERBY_FRUIT = "11317.4771";
	private static final int APPLES_SIX = 20;
	private static final int APPLES_NONE = 14;

	/**
	 * A patch the guide cannot act on stops holding the run up.
	 *
	 * <p>An empty patch always wants planting as far as the planner is concerned, so a patch with
	 * no seed allocated to it left the stop unable to finish and the run with no route and no next
	 * instruction — which reads as the plugin having frozen. The guide is the only thing that knows
	 * whether there is anything to click, so it reports that, and the run skips the patch rather
	 * than waiting on it. The player is told separately; see {@code GuideStatus.skipped}.
	 */
	@Test
	public void aPatchTheGuideCannotActOnDoesNotHoldTheRunUp()
	{
		// Varbit 3 is a raked, empty herb patch: actionable, and it will stay that way until
		// something is planted in it.
		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB));

		assertEquals("an empty patch is worth visiting", 1, planner.getRemaining().size());

		planner.reviewProgress();
		assertEquals("and nothing has changed, so it still is",
			1, planner.getRemaining().size());

		// What the guide reports when it has no step to offer for the patch.
		planner.setNothingToDo(java.util.Collections.singleton(FALADOR_HERB));
		planner.reviewProgress();

		assertTrue("nothing can be done here, so the run moves on rather than waiting",
			planner.getRemaining().isEmpty());
	}

	/**
	 * A harvest-only stop finishes when the fruit is gone, not when something is planted.
	 *
	 * <h2>The bug this pins down</h2>
	 *
	 * A stop used to finish only when the capture layer watched every patch in it turn into a
	 * growing crop. A harvest-only trip plants nothing by definition, so <b>no harvest-only stop
	 * could ever finish</b> — the run sat with no route and no next instruction, which reads as the
	 * plugin having frozen rather than as a patch it is waiting on.
	 *
	 * <p>Both varbits here are {@code HARVESTABLE}. That is the trap underneath it: "no fruit on the
	 * tree" is one of a fruit tree's harvestable states, not a separate state, so every test of the
	 * form {@code cropState == HARVESTABLE} answered "yes, still worth picking" forever.
	 */
	@Test
	public void aHarvestOnlyStopFinishesOnceThePatchIsPickedClean()
	{
		when(runOptions.isHarvestOnly(any())).thenReturn(true);

		record(CATHERBY_FRUIT, APPLES_SIX);
		availability.setAvailable(patch(CATHERBY_FRUIT), true);
		planner.start(EnumSet.of(PatchImplementation.FRUIT_TREE));

		assertEquals("a laden tree is worth the trip", 1, planner.getRemaining().size());

		// Pick it clean. Still HARVESTABLE, and nothing is ever planted here.
		record(CATHERBY_FRUIT, APPLES_NONE);
		planner.onPatchChanged(patch(CATHERBY_FRUIT));

		assertTrue("nothing left to pick, so the stop is done", planner.getRemaining().isEmpty());
	}

	/**
	 * And a stripped tree is not a reason to travel in the first place.
	 *
	 * <p>The same confusion seen from the planning end rather than the finishing end: a run that
	 * included every "harvestable" fruit tree would route you across the map to trees you had
	 * already emptied.
	 */
	@Test
	public void aPickedCleanTreeIsNotWorthTravellingTo()
	{
		when(runOptions.isHarvestOnly(any())).thenReturn(true);

		record(CATHERBY_FRUIT, APPLES_NONE);
		availability.setAvailable(patch(CATHERBY_FRUIT), true);
		planner.start(EnumSet.of(PatchImplementation.FRUIT_TREE));

		assertTrue("there is nothing on it to pick", planner.getRemaining().isEmpty());
	}

	/**
	 * A stripped bush is still worth travelling to when the run replants it.
	 *
	 * <p>The regrowing exception used to read every picked-clean regrowing crop as finished,
	 * which is right for a fruit tree — the fruit comes back and the guide does not model the
	 * chop — but wrong for a bush a spade digs straight out: the run walked past patches whose
	 * whole remaining job was dig-and-replant. Reported from play.
	 */
	@Test
	public void aPickedCleanBushStillCountsWhenReplanting()
	{
		FarmPatch bush = FarmingWorldData.getPatches(PatchImplementation.BUSH).get(0);
		selection.toggle(com.dooglemaps.data.Seed.REDBERRIES);
		stockBank(com.dooglemaps.data.Seed.REDBERRIES, 10);

		record(bush.getKey(), 10);   // redberries, harvestable, nothing left on it
		availability.setAvailable(bush, true);
		planner.start(EnumSet.of(PatchImplementation.BUSH));

		assertEquals("the dig-and-replant is real work", 1, planner.getRemaining().size());
	}

	/** With no replacement seed owned anywhere, a stripped bush really is finished. */
	@Test
	public void aPickedCleanBushWithNoSeedOwnedIsFinished()
	{
		FarmPatch bush = FarmingWorldData.getPatches(PatchImplementation.BUSH).get(0);

		record(bush.getKey(), 10);
		availability.setAvailable(bush, true);
		planner.start(EnumSet.of(PatchImplementation.BUSH));

		assertTrue("nothing to pick and nothing to plant", planner.getRemaining().isEmpty());
	}

	/**
	 * A grown-but-unchecked crop still counts on a harvest-only run.
	 *
	 * <p>A cactus or fruit tree finishes growing into a state the game still calls GROWING —
	 * only the player's health check makes it harvestable — so asking only hasProduceToPick
	 * walked a harvest-only run past the one click that makes the picking (and the crop's
	 * real experience) available. Reported from play: a guild cactus at check-health, skipped
	 * the moment the rest of the guild was done.
	 */
	@Test
	public void aGrownUncheckedCropStillCountsOnHarvestOnly()
	{
		when(runOptions.isHarvestOnly(any())).thenReturn(true);

		FarmPatch fruit = patch(CATHERBY_FRUIT);
		int unchecked = grownUncheckedValue(fruit);
		assertTrue("no grown-unchecked varbit found for the fixture", unchecked >= 0);
		record(CATHERBY_FRUIT, unchecked);
		availability.setAvailable(fruit, true);
		planner.start(EnumSet.of(PatchImplementation.FRUIT_TREE));

		assertEquals("the check is the click that makes the picking available",
			1, planner.getRemaining().size());
	}

	/**
	 * No start-less route is posted while the player is inside an instance.
	 *
	 * <p>A request with no explicit start defaults, upstream, to the raw player position —
	 * garbage inside an instance — and the recomputed route lands on an arbitrary stop.
	 * Reported from play: teleporting to the POH flipped a Weiss run's destination to the
	 * Ardougne bushes as the house loaded. Leaving the instance retargets normally.
	 */
	@Test
	public void noStartlessRouteIsPostedFromInsideAnInstance()
	{
		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);
		selection.toggle(com.dooglemaps.data.Seed.RANARR);
		stockInventory(com.dooglemaps.data.Seed.RANARR, 10);
		standingIn((100 << 8) | 80);   // an instance's virtual area: region x-part >= 100

		planner.start(EnumSet.of(PatchImplementation.HERB));

		assertTrue("a route from instance coordinates would start from nowhere",
			lastTargets().isEmpty());
	}

	/**
	 * The player's own house is the one instance with a knowable start: its front door.
	 *
	 * <p>Refusing outright there left a replanned run <b>dead</b> — every retarget begins by
	 * wiping the route state, so a run planned or replanned while standing in the POH showed
	 * no destination, no via-lines and nothing outlined, and never asked the router again
	 * until the player walked out. Reported from play, replanned inside the house. Same
	 * compromise {@code routeFromTheFrontDoor} makes; other instances still stay silent, as
	 * the test above pins.
	 */
	@Test
	public void aPlanMadeInsideTheHouseRoutesFromTheFrontDoor()
	{
		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);
		selection.toggle(com.dooglemaps.data.Seed.RANARR);
		stockInventory(com.dooglemaps.data.Seed.RANARR, 10);
		standingIn((100 << 8) | 80);
		WorldPoint door = new WorldPoint(2953, 3224, 0);
		planner.setHouseKnowledge(() -> door);

		planner.start(EnumSet.of(PatchImplementation.HERB));

		assertFalse("the run still gets a route", lastTargets().isEmpty());
		assertEquals("planned from where the house opens onto the overworld",
			door, lastStart());
	}

	/**
	 * A live plan through the house is never re-routed from the front door.
	 *
	 * <p>The fallback above exists for the dead no-plan state; its first version fired on
	 * <b>every</b> in-house retarget, so entering the POH on a jewellery-box leg threw away
	 * the box hop mid-teleport — the via-line vanished, and the row highlight (which reads
	 * the hop for its row name) fell back to the stop's name and lit "R: Al Kharid" over
	 * "1: Emir's Arena". Reported from play, twice. The router's own origins say the plan
	 * uses the house, and such a plan keeps its route.
	 */
	@Test
	public void aLiveRouteThroughTheHouseKeepsItsRouteInside()
	{
		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);
		selection.toggle(com.dooglemaps.data.Seed.RANARR);
		stockInventory(com.dooglemaps.data.Seed.RANARR, 10);
		standingIn(12894);
		planner.setHouseKnowledge(() -> new WorldPoint(2953, 3224, 0));
		planner.start(EnumSet.of(PatchImplementation.HERB));
		assertFalse("fixture: a route was asked for", lastTargets().isEmpty());

		// The router's answer: the second hop departs the POH — house furniture by its own
		// word (a jewellery box, say), whatever it is called.
		Map<String, Object> data = new HashMap<>();
		data.put("origin", java.util.Arrays.asList(
			new WorldPoint(3222, 3218, 0), new WorldPoint(1928, 5731, 0)));
		data.put("destination", java.util.Arrays.asList(new WorldPoint(1923, 5709, 0)));
		router.onPluginMessage(new PluginMessage("shortestpath", "transports", data));

		int posts = posted.size();
		standingIn((100 << 8) | 80);   // teleported into the house
		planner.retarget();

		assertEquals("the next click is furniture in this room; the route must survive",
			posts, posted.size());
	}

	/** The start in the most recent path message, or null when none was carried. */
	private WorldPoint lastStart()
	{
		for (int i = posted.size() - 1; i >= 0; i--)
		{
			PluginMessage message = posted.get(i);
			if (message.getData() != null && message.getData().get("start") != null)
			{
				return (WorldPoint) message.getData().get("start");
			}
		}
		return null;
	}

	/**
	 * A part-full bush on a harvest-only run waits for the cap; a full one goes in.
	 *
	 * <h2>Regrowth stops at the cap</h2>
	 *
	 * Wiki-checked: the regrowing families refill one unit per fixed tick and never past
	 * their cap, so a full plant is idle while a part-full one is still working — visiting
	 * early buys nothing and costs the trip, and for bushes also forfeits the level-scaled
	 * yield the wiki ties to full regrowth. Reported from play as being routed back to
	 * half-full bushes. Planning-time only, like the cluster hold: once you are there
	 * picking, the falling stock must not complete the stop under your feet.
	 */
	@Test
	public void aRefillingBushWaitsForTheCapOnHarvestOnly()
	{
		FarmPatch bush = FarmingWorldData.getPatches(PatchImplementation.BUSH).get(0);
		availability.setAvailable(bush, true);
		when(runOptions.isHarvestOnly(any())).thenReturn(true);

		int partial = bushValueWhere(bush, true);
		int full = bushValueWhere(bush, false);
		assertTrue("fixture: the varbit table has both a refilling and a full state",
			partial >= 0 && full >= 0);

		ProduceState refilling = bush.getImplementation().forVarbitValue(partial);
		stateStore.recordVarbit(bush, partial, refilling);
		assertTrue("still refilling - the trip can wait",
			planner.start(EnumSet.of(PatchImplementation.BUSH)).isEmpty());

		ProduceState capped = bush.getImplementation().forVarbitValue(full);
		stateStore.recordVarbit(bush, full, capped);
		assertFalse("at the cap the plant is idle - now the trip pays",
			planner.start(EnumSet.of(PatchImplementation.BUSH)).isEmpty());
	}

	/**
	 * The ready counter's question: would a run started right now visit this patch?
	 *
	 * <p>The counter used to count every available patch — an unticked type and a refilling
	 * bush both inflated a number that exists to say "a farm run is worth starting".
	 */
	@Test
	public void theReadyCounterOnlyCountsWhatARunWouldVisit()
	{
		FarmPatch bush = FarmingWorldData.getPatches(PatchImplementation.BUSH).get(0);
		com.dooglemaps.data.PlantingGroup group =
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.BUSH);
		when(groups.groupFor(bush)).thenReturn(group);

		int full = bushValueWhere(bush, false);
		stateStore.recordVarbit(bush, full, bush.getImplementation().forVarbitValue(full));
		assertFalse("unticked is not a reason to go, however ready it is",
			planner.selectedForRuns(bush));

		when(runOptions.isSelected(com.dooglemaps.data.RunOption.harvestOnly(group)))
			.thenReturn(true);
		when(runOptions.isHarvestOnly(any())).thenReturn(true);
		assertTrue("ticked and full - a run would go", planner.selectedForRuns(bush));

		int partial = bushValueWhere(bush, true);
		stateStore.recordVarbit(bush, partial, bush.getImplementation().forVarbitValue(partial));
		assertFalse("ticked but still refilling - the run is deliberately waiting",
			planner.selectedForRuns(bush));
	}

	/**
	 * A bush varbit value that projects to stock with (or without) more on the way, or -1.
	 *
	 * <p>Found by decoding rather than hard-coded, the same way {@link #grownUncheckedValue}
	 * works, so the test keeps meaning something if the generated tables move.
	 */
	private int bushValueWhere(FarmPatch patch, boolean stillRegrowing)
	{
		for (int value = 0; value < 256; value++)
		{
			ProduceState decoded = patch.getImplementation().forVarbitValue(value);
			if (decoded == null || decoded.getProduce() == null || !decoded.getProduce().isCrop())
			{
				continue;
			}
			stateStore.recordVarbit(patch, value, decoded);
			com.dooglemaps.timer.PatchProjection projection =
				timer.project(patch, stateStore.get(patch));
			if (projection != null && projection.hasProduceToPick()
				&& projection.isRegrowing() == stillRegrowing)
			{
				return value;
			}
		}
		return -1;
	}

	/**
	 * A varbit value meaning "a real crop, freshly in the ground", or -1.
	 *
	 * <p>The state that ends a patch's business with the run, whatever it grows. Decoded rather
	 * than hardcoded because the one hardcoded number in these tests — {@code service}'s varbit
	 * 4 — is a guam seedling in a herb patch and plain <i>weeds</i> in a bush, which is a patch
	 * still asking to be dealt with.
	 */
	private int justPlantedValue(FarmPatch patch)
	{
		for (int value = 0; value < 256; value++)
		{
			ProduceState decoded = patch.getImplementation().forVarbitValue(value);
			if (decoded != null && decoded.getProduce() != null
				&& decoded.getProduce().isCrop()
				&& decoded.getCropState() == com.dooglemaps.data.CropState.GROWING)
			{
				return value;
			}
		}
		return -1;
	}

	/** The crop's grown-but-unchecked varbit value, or -1 when the data has none. */
	private int grownUncheckedValue(FarmPatch patch)
	{
		for (int value = 0; value < 256; value++)
		{
			ProduceState decoded = patch.getImplementation().forVarbitValue(value);
			if (decoded == null || decoded.getProduce() == null
				|| !decoded.getProduce().isCrop()
				|| decoded.getCropState() != com.dooglemaps.data.CropState.GROWING
				|| decoded.getStage() != decoded.getProduce().getStages() - 1)
			{
				continue;
			}
			stateStore.recordVarbit(patch, value, decoded);
			com.dooglemaps.timer.PatchProjection projection =
				timer.project(patch, stateStore.get(patch));
			if (projection != null && projection.needsHealthCheck())
			{
				return value;
			}
		}
		return -1;
	}

	/** Puts the local player somewhere in the given map region. */
	/**
	 * Puts the player <b>at</b> a patch, rather than merely in its map region.
	 *
	 * <p>{@link #standingIn} drops them on the region's arithmetic centre, which is a fine stand-in
	 * for "somewhere in this region" and is not the same statement as "at this stop". Arrival is
	 * measured against the patches now — see {@code RunPlanner.hasArrivedAt}, and the Brimhaven
	 * spirit tree thirty-eight tiles from its own palm tree that made the difference matter.
	 */
	private void standingAt(FarmPatch patch)
	{
		WorldPoint where = patchLocations.getLocation(patch);
		net.runelite.api.Player player = Mockito.mock(net.runelite.api.Player.class);
		when(player.getWorldLocation()).thenReturn(where);
		when(client.getLocalPlayer()).thenReturn(player);
		playerLocation.onGameTick(new net.runelite.api.events.GameTick());
	}

	private void standingIn(int regionId)
	{
		WorldPoint where = new WorldPoint(
			((regionId >>> 8) << 6) + 32, ((regionId & 0xFF) << 6) + 32, 0);
		net.runelite.api.Player player = Mockito.mock(net.runelite.api.Player.class);
		when(player.getWorldLocation()).thenReturn(where);
		when(client.getLocalPlayer()).thenReturn(player);

		// The position is sampled once a tick rather than read on demand, so the tick has to
		// happen for anything to see it - exactly as in the client.
		playerLocation.onGameTick(new net.runelite.api.events.GameTick());
	}

	private static String key(InvocationOnMock invocation)
	{
		Object group = invocation.getArgument(0);
		Object name = invocation.getArgument(1);
		return group + "." + name;
	}

	private static FarmPatch patch(String key)
	{
		FarmPatch patch = FarmingWorldData.getPatch(key);
		assertNotNull("fixture patch " + key + " no longer exists", patch);
		return patch;
	}

	private void stockVault(com.dooglemaps.data.Seed seed, int quantity)
	{
		stock(SeedSource.SEED_VAULT, seed, quantity);
	}

	private void stockBank(com.dooglemaps.data.Seed seed, int quantity)
	{
		stock(SeedSource.BANK, seed, quantity);
	}

	private void stockInventory(com.dooglemaps.data.Seed seed, int quantity)
	{
		stock(SeedSource.INVENTORY, seed, quantity);
	}

	private void stock(SeedSource source, com.dooglemaps.data.Seed seed, int quantity)
	{
		net.runelite.api.ItemContainer container = Mockito.mock(net.runelite.api.ItemContainer.class);
		when(container.getItems()).thenReturn(new net.runelite.api.Item[]{
			new net.runelite.api.Item(seed.getItemID(), quantity),
		});
		seedInventory.record(source.getContainerId(), container);
	}

	/**
	 * Deals with a patch the way the game does, which is the only way a run advances.
	 *
	 * <p>These tests used to call {@code markServiced} on its own, and that is not a simulation of
	 * anything: the tracker only calls it when it has <i>watched</i> a varbit change into a growing
	 * crop, and a stop is now finished when nothing at it is still actionable rather than when a
	 * counter fills. Marking a patch serviced while it sits there empty asserted on a state the
	 * game cannot produce, and it is exactly the gap that let the run stall in play.
	 *
	 * <p>Varbit 4 is a guam at its first growth stage — a real crop in the ground, so the patch
	 * stops being actionable, which is what ends a stop.
	 */
	private void service(String key)
	{
		record(key, 4);
		planner.onPatchChanged(patch(key));
	}

	private void record(String key, int varbitValue)
	{
		FarmPatch p = patch(key);
		ProduceState decoded = p.getImplementation().forVarbitValue(varbitValue);
		assertNotNull("varbit " + varbitValue + " does not decode for " + key, decoded);
		stateStore.recordVarbit(p, varbitValue, decoded);
	}

	/**
	 * The map regions the most recent path message routes to — one per stop.
	 *
	 * <p>Counted by region rather than by tile because a stop no longer posts a single
	 * point: the router is handed the ring of tiles around the patch, so the patch can be
	 * arrived <i>beside</i> rather than stood on. See PatchLocationStore.getRouteTargets.
	 */
	private Set<Integer> regionsTargeted()
	{
		Set<Integer> regions = new java.util.HashSet<>();
		for (WorldPoint target : lastTargets())
		{
			regions.add(target.getRegionID());
		}
		return regions;
	}

	/** Targets in the most recent path message, or empty if the last message was a clear. */
	@SuppressWarnings("unchecked")
	private Set<WorldPoint> lastTargets()
	{
		for (int i = posted.size() - 1; i >= 0; i--)
		{
			PluginMessage message = posted.get(i);
			if ("clear".equals(message.getName()))
			{
				return Collections.emptySet();
			}
			Object target = message.getData().get("target");
			if (target instanceof Set)
			{
				return (Set<WorldPoint>) target;
			}
		}
		return Collections.emptySet();
	}

	// ------------------------------------------------------- what goes in a run

	@Test
	public void aGrowingCropIsNotWorthVisiting()
	{
		record(FALADOR_HERB, 33);   // ranarr, growing
		availability.setAvailable(patch(FALADOR_HERB), true);

		List<RunStop> stops = planner.start(EnumSet.of(PatchImplementation.HERB));

		assertTrue("a growing crop should be left alone", stops.isEmpty());
		assertFalse(planner.isActive());
	}

	@Test
	public void readyEmptyAndDeadPatchesAllGoInTheRun()
	{
		record(CATHERBY_HERB, 43);    // toadflax, harvestable
		record(FALADOR_HERB, 3);      // raked, empty
		record(ARDOUGNE_HERB, 171);   // dead
		for (String key : new String[]{CATHERBY_HERB, FALADOR_HERB, ARDOUGNE_HERB})
		{
			availability.setAvailable(patch(key), true);
		}

		List<RunStop> stops = planner.start(EnumSet.of(PatchImplementation.HERB));

		assertEquals("ready, empty and dead all want attention", 3, stops.size());
		assertTrue(planner.isActive());
	}

	/**
	 * Skipping a travel destination drops the whole stop, for this round only.
	 *
	 * <p>The travel leg's escape hatch: a step can be waved past because it exists as a step,
	 * but "travel to Harmony" is a route, and there was no way to say no to it — the run kept
	 * routing there however firmly the player was not going. Reported from play. The next
	 * round offers the place again, which is why the set clears with the stops.
	 */
	@Test
	public void aSkippedRegionIsLeftOutUntilTheNextRun()
	{
		record(CATHERBY_HERB, 3);
		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(CATHERBY_HERB), true);
		availability.setAvailable(patch(FALADOR_HERB), true);

		planner.start(EnumSet.of(PatchImplementation.HERB));
		assertEquals(2, planner.getRemaining().size());

		int catherby = patch(CATHERBY_HERB).getRegion().getRegionId();
		planner.skipRegion(catherby);

		assertTrue("the skipped region is off the route",
			planner.getRemaining().stream()
				.noneMatch(stop -> stop.getRegion().getRegionId() == catherby));
		assertTrue("the checklist agrees with the route",
			planner.getRemainingPatches().stream()
				.noneMatch(p -> p.getRegion().getRegionId() == catherby));
		assertTrue("the rest of the run carries on", planner.isActive());

		planner.stop();
		planner.start(EnumSet.of(PatchImplementation.HERB));
		assertEquals("the next round offers the place again",
			2, planner.getRemaining().size());
	}

	/**
	 * With the hold-clusters setting on, a shared plot waits for its slowest selected patch.
	 *
	 * <p>Falador's plot is the fixture: herb ready, allotment still growing, both types in the
	 * run — no trip. The scoping is the interesting half, pinned by the second act: with only
	 * herbs ticked, the growing allotment is not a selected sibling and holds nothing.
	 */
	@Test
	public void aSharedPlotWaitsForItsSlowestSelectedPatch()
	{
		when(pluginConfig.holdClustersUntilReady()).thenReturn(true);

		record(FALADOR_HERB, 43);      // ready to harvest
		record("12083.4771", 7);       // allotment beside it, still growing
		availability.setAvailable(patch(FALADOR_HERB), true);
		availability.setAvailable(patch("12083.4771"), true);

		assertTrue("the flower-first trip is the trip this setting exists to skip",
			planner.start(EnumSet.of(PatchImplementation.HERB, PatchImplementation.ALLOTMENT))
				.isEmpty());

		assertEquals("with only herbs ticked, the growing allotment holds nothing",
			1, planner.start(EnumSet.of(PatchImplementation.HERB)).size());
	}

	/**
	 * ...and the compost bin standing on that plot waits with it.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"I was just sent to ardougne farm without volcanic ash to fill the bin, and with
	 * watermelons still in progress, per our combined allotment/herb/flower patches rule, we
	 * shouldn't have even gone here"</i>. The hold itself was working — the session log's
	 * <i>Shared plots</i> line has Ardougne <b>[free to visit]</b> at plan time with all four
	 * patches ripe, and they were serviced. What brought the player back an hour later was the
	 * bin: left composting, its clock came round, {@code binActionable} re-opened the stop, and
	 * {@code clusterHeld} had no opinion because a bin is not one of the three cluster types.
	 * The trip the setting exists to prevent was made for the one patch on that ground the
	 * setting could not see.
	 *
	 * <p>A bin is the best possible thing to make wait: nothing in it spoils, it has no disease
	 * clock, and it is standing among the patches that will want its compost.
	 */
	@Test
	public void aBinOnASharedPlotDoesNotKeepThePlotsStopAlive()
	{
		when(pluginConfig.holdClustersUntilReady()).thenReturn(true);
		when(compostRun.isFodderEnabled()).thenReturn(true);
		standingIn(VARROCK_REGION);

		// Everything at the plot ready, and the bin finished too: one trip, correctly planned.
		record(FALADOR_HERB, 43);
		record(FALADOR_BIN, readyBinValue());
		availability.setAvailable(patch(FALADOR_HERB), true);
		availability.setAvailable(patch(FALADOR_BIN), true);

		planner.start(EnumSet.of(PatchImplementation.HERB, PatchImplementation.COMPOST));
		planner.leaveBank();
		assertTrue("fixture: the plot is a stop on this run",
			planner.getRemaining().stream().anyMatch(this::isFalador));

		// The herb is picked and replanted, and the player leaves. The bin is still sat there
		// finished — and on its own that is not worth the plot being kept open, because the
		// plot cannot be worked again until the new herb grows.
		service(FALADOR_HERB);

		assertTrue("the bin alone should not hold the stop open: " + planner.getRemaining(),
			planner.getRemaining().stream().noneMatch(this::isFalador));
	}

	/**
	 * A held bin is refused at the adoption door, not merely re-held after walking through it.
	 *
	 * <p>{@code reviewBins} adopted on raw actionability and cleared the stop's completion
	 * announcement; only {@code stillWanted} re-holding the bin a tick later kept the run from
	 * travelling — a redundant completion edge and a spare retarget, safe by coincidence. Now
	 * it asks {@code clusterHeld} the same way {@code planStops} does, so a held bin never
	 * joins the stop at all.
	 */
	@Test
	public void aHeldBinIsNotAdoptedIntoItsPlotsStop()
	{
		when(pluginConfig.holdClustersUntilReady()).thenReturn(true);
		when(compostRun.isFodderEnabled()).thenReturn(true);
		standingIn(VARROCK_REGION);

		record(FALADOR_HERB, 43);
		availability.setAvailable(patch(FALADOR_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB, PatchImplementation.COMPOST));
		planner.leaveBank();

		// The herb is replanted and watched; the bin only now finishes composting.
		service(FALADOR_HERB);
		record(FALADOR_BIN, readyBinValue());
		availability.setAvailable(patch(FALADOR_BIN), true);
		planner.reviewBins();

		RunStop falador = planner.getStops().stream().filter(this::isFalador)
			.findFirst().orElseThrow(AssertionError::new);
		assertTrue("the growing herb holds the bin, so it never enters the stop",
			falador.getPatches().stream()
				.noneMatch(p -> p.getKey().equals(FALADOR_BIN)));
	}

	private boolean isFalador(RunStop stop)
	{
		return stop.getRegion().getRegionId()
			== patch(FALADOR_HERB).getRegion().getRegionId();
	}

	/** Falador's compost bin, on the same ground as its allotments. */
	private static final String FALADOR_BIN = "12083.4775";

	/** A bin varbit meaning "finished, waiting to be emptied", decoded rather than assumed. */
	private int readyBinValue()
	{
		FarmPatch bin = patch(FALADOR_BIN);
		for (int value = 0; value < 256; value++)
		{
			ProduceState decoded = bin.getImplementation().forVarbitValue(value);
			if (decoded != null && decoded.getProduce() != null
				&& decoded.getCropState() == com.dooglemaps.data.CropState.HARVESTABLE)
			{
				return value;
			}
		}
		throw new AssertionError("no harvestable varbit decodes for the Falador bin");
	}

	/**
	 * A held plot does not come back the moment one of its patches ripens.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * The two hold rules gated {@code planStops} and nothing else, so they decided whether a stop
	 * was <b>created</b> and had no say in whether it came <b>back</b> — and a stop returns the
	 * instant one of its patches turns actionable, which is exactly the moment the hold exists to
	 * ignore. Reported as "hold shared plots until all are ready isn't working - just got routed
	 * to Ardougne again after only limpwurts were ready": the flower finishes in twenty minutes
	 * and the herb beside it takes eighty.
	 */
	@Test
	public void aHeldPlotStaysHeldWhenOnlyOnePatchRipens()
	{
		when(pluginConfig.holdClustersUntilReady()).thenReturn(true);
		standingIn(VARROCK_REGION);

		// Both ready at the start, so the stop is planned and then finished.
		record(FALADOR_HERB, 43);
		record(FALADOR_FLOWER, 12);
		availability.setAvailable(patch(FALADOR_HERB), true);
		availability.setAvailable(patch(FALADOR_FLOWER), true);
		planner.start(EnumSet.of(PatchImplementation.HERB, PatchImplementation.FLOWER));
		planner.leaveBank();

		// Both replanted, through the watched path — replanting happens with the player at the
		// stop, so the interaction tracker sees it, and being seen is now load-bearing: the
		// hold re-applies only to patches the stop has serviced once, so that it can never
		// complete a stop over ripe work the player has not finished. See stillWanted.
		record(FALADOR_HERB, 4);
		planner.onPatchChanged(patch(FALADOR_HERB));
		record(FALADOR_FLOWER, 8);
		planner.onPatchChanged(patch(FALADOR_FLOWER));
		assertTrue("fixture: a freshly replanted plot wants nothing",
			planner.getRemaining().isEmpty());

		// The flower ripens first — while the player is away, so no event fires. The herb
		// beside it is still growing, so the trip is exactly the one the setting exists to skip.
		record(FALADOR_FLOWER, 12);

		assertTrue("one ripe flower must not fetch you back to a plot that is not ready",
			planner.getRemaining().isEmpty());
	}

	/** And it does come back once the slow one catches up. */
	@Test
	public void aHeldPlotReturnsWhenTheWholePlotIsReady()
	{
		when(pluginConfig.holdClustersUntilReady()).thenReturn(true);
		standingIn(VARROCK_REGION);

		record(FALADOR_HERB, 43);
		record(FALADOR_FLOWER, 12);
		availability.setAvailable(patch(FALADOR_HERB), true);
		availability.setAvailable(patch(FALADOR_FLOWER), true);
		planner.start(EnumSet.of(PatchImplementation.HERB, PatchImplementation.FLOWER));
		planner.leaveBank();

		record(FALADOR_HERB, 4);
		planner.onPatchChanged(patch(FALADOR_HERB));
		record(FALADOR_FLOWER, 8);
		planner.onPatchChanged(patch(FALADOR_FLOWER));
		assertTrue(planner.getRemaining().isEmpty());

		record(FALADOR_FLOWER, 12);
		record(FALADOR_HERB, 43);

		assertEquals("with the whole plot ready the trip is worth making",
			1, planner.getRemaining().size());
	}

	/**
	 * Ripe work the player has not touched keeps its stop, whatever its siblings do.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * The hold leaked into the completion test unqualified, so a part-serviced plot — flower
	 * replanted, herb still ripe and never touched — completed the moment the player crossed
	 * the region boundary: the freshly growing flower held the herb, {@code isComplete} went
	 * true, and the ripe herb was silently dropped for the rest of the run. The hold may only
	 * speak for work the stop has already seen done once; unfinished promises are kept.
	 */
	@Test
	public void aPartServicedPlotKeepsItsUnfinishedRipeWork()
	{
		when(pluginConfig.holdClustersUntilReady()).thenReturn(true);
		standingIn(VARROCK_REGION);

		record(FALADOR_HERB, 43);
		record(FALADOR_FLOWER, 12);
		availability.setAvailable(patch(FALADOR_HERB), true);
		availability.setAvailable(patch(FALADOR_FLOWER), true);
		planner.start(EnumSet.of(PatchImplementation.HERB, PatchImplementation.FLOWER));
		planner.leaveBank();

		// The flower is replanted and watched being replanted; the herb is never touched.
		record(FALADOR_FLOWER, 8);
		planner.onPatchChanged(patch(FALADOR_FLOWER));

		assertTrue("a growing sibling must not drop ripe work the player has not finished",
			planner.getRemaining().stream().anyMatch(this::isFalador));
	}

	/**
	 * A held plot rides along into a stop the run is making anyway.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * The hold saves a teleport, and only a teleport. The Farming Guild's tree made the guild
	 * a stop whatever the plot beside it was doing — so holding the plot out of that stop
	 * saved nothing and hid the ripe herb from a trip already being paid for: the guide stood
	 * the player at the guild and never mentioned it, and nothing could adopt it mid-run.
	 */
	@Test
	public void aHeldPlotRidesAlongWhenTheStopIsMadeAnyway()
	{
		when(pluginConfig.holdClustersUntilReady()).thenReturn(true);
		standingIn(VARROCK_REGION);

		FarmPatch herb = guildPatch(PatchImplementation.HERB);
		FarmPatch flower = guildPatch(PatchImplementation.FLOWER);
		FarmPatch tree = guildPatch(PatchImplementation.TREE);
		record(herb.getKey(), 43);
		record(flower.getKey(), 8);
		// The tree is left unobserved: never seen is worth a look, which is all the stop needs.
		availability.setAvailable(herb, true);
		availability.setAvailable(flower, true);
		availability.setAvailable(tree, true);

		planner.start(EnumSet.of(PatchImplementation.HERB, PatchImplementation.FLOWER,
			PatchImplementation.TREE));
		planner.leaveBank();

		RunStop guild = planner.getRemaining().stream()
			.filter(stop -> stop.getRegion().getRegionId() == FARMING_GUILD_REGION)
			.findFirst().orElseThrow(AssertionError::new);
		assertTrue("the ripe herb rides along - there is no teleport left to save",
			guild.getPatches().contains(herb));
	}

	/**
	 * And without other work there, the hold still holds — riding along never creates a stop.
	 */
	@Test
	public void aHeldPlotAloneStillHoldsTheTrip()
	{
		when(pluginConfig.holdClustersUntilReady()).thenReturn(true);
		standingIn(VARROCK_REGION);

		FarmPatch herb = guildPatch(PatchImplementation.HERB);
		FarmPatch flower = guildPatch(PatchImplementation.FLOWER);
		record(herb.getKey(), 43);
		record(flower.getKey(), 8);
		availability.setAvailable(herb, true);
		availability.setAvailable(flower, true);

		planner.start(EnumSet.of(PatchImplementation.HERB, PatchImplementation.FLOWER));
		planner.leaveBank();

		assertTrue("nothing else wants the guild, so the plot's hold stands",
			planner.getRemaining().stream()
				.noneMatch(stop -> stop.getRegion().getRegionId() == FARMING_GUILD_REGION));
	}

	/**
	 * Standing on any of the plot's own ground escapes the hold, not just its canonical id.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * Catherby's plot answers to four map regions (11061/11062/11317/11318), and the exemption
	 * compared only the canonical one — so a player one tile over the boundary, exactly where
	 * finishing the previous run leaves them, was read as elsewhere and the plot beside them
	 * was held even though the teleport the hold saves was already spent.
	 */
	@Test
	public void standingOnThePlotsExtraGroundEscapesTheHold()
	{
		when(pluginConfig.holdClustersUntilReady()).thenReturn(true);
		// 11061 is Catherby plot ground, but not the plot's canonical region id.
		standingIn(11061);

		record(CATHERBY_HERB, 43);
		record(CATHERBY_FLOWER, 8);
		availability.setAvailable(patch(CATHERBY_HERB), true);
		availability.setAvailable(patch(CATHERBY_FLOWER), true);

		planner.start(EnumSet.of(PatchImplementation.HERB, PatchImplementation.FLOWER));
		planner.leaveBank();

		assertTrue("you are standing at the plot, so nothing there is held",
			planner.getRemaining().stream()
				.anyMatch(stop -> stop.getRegion().getRegionId() == 11062));
	}

	private static final int FARMING_GUILD_REGION = 4922;

	private static final String CATHERBY_FLOWER = "11062.4773";

	/** The hespori patch's key: its cave's region, and FARMING_TRANSMIT_J. */
	private static final String HESPORI = "5021.7908";

	/**
	 * The whole mixed-run gear cycle, driven end to end: gear leg, hespori first, swap-back
	 * bank trip, farm circuit.
	 *
	 * <p>One test for the sequence rather than four for the parts, deliberately — hespori
	 * seeds are rare and the patch takes a day to regrow, so this lifecycle gets almost no
	 * live rehearsals; what a real run will meet is the <i>chain</i>, and the joints between
	 * the phases are where the design has the most room to be wrong.
	 */
	@Test
	public void aMixedHesporiRunSwapsGearThenContinuesFarming()
	{
		standingIn(VARROCK_REGION);
		FarmPatch hespori = patch(HESPORI);
		record(HESPORI, 7);   // the boss is up
		availability.setAvailable(hespori, true);
		record(FALADOR_HERB, 43);
		availability.setAvailable(patch(FALADOR_HERB), true);

		// The guide's pre-start answer for a gear run is "outstanding", so the run opens at
		// a bank — see GuideTracker.withdrawListOutstanding.
		planner.start(EnumSet.of(PatchImplementation.HESPORI, PatchImplementation.HERB), true);
		assertTrue("a gear run opens at a bank", planner.isAtBankLeg());
		assertTrue("and the gear phase is on", planner.isGearPhase());

		// The handoff calls the gear collected: bank opened and closed. The guide pushes that
		// verdict; the leg ends on it. The tick review runs while the phase is on, as it does
		// every tick in production — the swap trip arms on the phase's falling edge, and an
		// edge needs the rising side to have been seen.
		planner.setWithdrawOutstanding(false);
		planner.leaveBank();
		planner.reviewProgress();
		assertFalse(planner.isAtBankLeg());

		// The hespori comes first, whatever is cheaper to reach: the route goes to the cave
		// entrance in the guild, and nowhere near the herb stop.
		assertEquals("only the cave entrance is routed during the gear phase",
			Collections.singleton(FARMING_GUILD_REGION), regionsTargeted());

		// Killed and replanted: the patch turns into a growing seedling.
		record(HESPORI, 4);
		planner.onPatchChanged(patch(HESPORI));
		planner.reviewProgress();

		assertFalse("the fight is over, so the gear phase is too", planner.isGearPhase());
		assertTrue("and the swap-back bank trip armed", planner.isAtBankLeg());
		assertEquals(RunPlanner.BankLegReason.GEAR_SWAP, planner.getBankLegReason());

		// Farming supplies collected; the circuit continues where an hespori-only run would
		// have ended.
		planner.setWithdrawOutstanding(false);
		planner.leaveBank();
		assertFalse(planner.isAtBankLeg());
		assertTrue("the herb stop is still the run's to make",
			planner.getRemaining().stream().anyMatch(this::isFalador));
	}

	/** An hespori-only run ends after the fight; there is no swap-back trip to nowhere. */
	@Test
	public void anHesporiOnlyRunEndsWithoutASwapBackTrip()
	{
		standingIn(VARROCK_REGION);
		record(HESPORI, 7);
		availability.setAvailable(patch(HESPORI), true);

		planner.start(EnumSet.of(PatchImplementation.HESPORI), true);
		planner.setWithdrawOutstanding(false);
		planner.leaveBank();
		planner.reviewProgress();

		record(HESPORI, 4);
		planner.onPatchChanged(patch(HESPORI));
		planner.reviewProgress();

		assertFalse("nothing left to farm, so no bank trip", planner.isAtBankLeg());
	}

	/** A growing hespori under an everything-tick is an ordinary farm run, not a gear run. */
	@Test
	public void aGrowingHesporiLeavesTheRunAnOrdinaryFarmRun()
	{
		standingIn(VARROCK_REGION);
		record(HESPORI, 4);   // growing - contributes no stop
		availability.setAvailable(patch(HESPORI), true);
		record(FALADOR_HERB, 43);
		availability.setAvailable(patch(FALADOR_HERB), true);

		planner.start(EnumSet.of(PatchImplementation.HESPORI, PatchImplementation.HERB), false);
		planner.leaveBank();

		assertFalse("no hespori stop, so no gear phase", planner.isGearPhase());
		assertTrue("the herbs are the run",
			planner.getRemaining().stream().anyMatch(this::isFalador));
	}

	/**
	 * A weedy hespori is an ordinary patch on an ordinary run: raking wants a farm kit, not
	 * a fight one. The gear phase is keyed on the boss being <b>up</b>, nothing less — a
	 * stop existing is not enough, because a stop exists for weeds too, and it also cannot
	 * be the phase's end key, because the kill leaves exactly this weedy stop behind.
	 */
	@Test
	public void aWeedyHesporiIsAnOrdinaryStopNotAGearRun()
	{
		standingIn(VARROCK_REGION);
		record(HESPORI, 1);   // weeds - rake work, not a fight
		availability.setAvailable(patch(HESPORI), true);

		planner.start(EnumSet.of(PatchImplementation.HESPORI), false);
		planner.leaveBank();

		assertFalse("weeds are not a boss", planner.isGearPhase());
		assertTrue("but the patch is still the run's to rake",
			planner.getRemaining().stream()
				.anyMatch(stop -> stop.getRegion().getRegionId() == 5021));
		planner.reviewProgress();
		assertFalse("and a run that never geared up owes no swap-back trip",
			planner.isAtBankLeg());
	}

	/**
	 * The state that sent a farmer's outfit to a boss fight: the hespori's timer has run out,
	 * but nobody has stood in front of the patch since, so its varbit still reads GROWING.
	 *
	 * <p>The hespori is health-check-required, and {@code GrowthTimer} deliberately refuses to
	 * promote such a crop to HARVESTABLE on the clock alone. So a hespori projection can
	 * <b>never</b> read HARVESTABLE until the player is standing at the patch — the panel says
	 * exactly that with its own {@code "ready?"}, where the question mark is "the clock says
	 * done, I have not seen it". Keying the gear phase on HARVESTABLE therefore keyed it on
	 * something unknowable before arrival: routing planned the hespori stop from
	 * {@code isReady()} and packed a seed, the phase stayed off, and the bank leg fell through
	 * to the ordinary withdraw list — farmer's outfit and seed box, into the cave. Reported
	 * from play. Both tests are {@code isReady()} now, and this is the case that separates
	 * them.
	 *
	 * <p>Seeded through core Time Tracking's own record, which is the one road that carries a
	 * {@code lastSeen} of our choosing: the store hands out copies, so a backdated snapshot
	 * cannot simply be written back.
	 */
	@Test
	public void aRipeButUncheckedHesporiStillArmsTheGearPhase()
	{
		standingIn(VARROCK_REGION);

		// Last seen three days ago at its first growing stage — long enough that the
		// projection's done estimate is well past, which is the whole of "ready?".
		long threeDaysAgo = java.time.Instant.now().getEpochSecond() - 3 * 24 * 60 * 60;
		stored.put("timetracking." + HESPORI, "4:" + threeDaysAgo);
		stateStore.load();

		FarmPatch hespori = patch(HESPORI);
		com.dooglemaps.timer.PatchProjection projection =
			timer.project(hespori, stateStore.get(hespori));
		assertEquals("fixture: the varbit still reads GROWING",
			com.dooglemaps.data.CropState.GROWING, projection.getCropState());
		assertTrue("fixture: but the clock says it is done - the panel's \"ready?\"",
			projection.isReady());

		availability.setAvailable(hespori, true);
		planner.start(EnumSet.of(PatchImplementation.HESPORI), true);

		assertTrue("the run routed to the hespori it cannot yet have checked",
			planner.getRemaining().stream()
				.anyMatch(stop -> stop.getRegion().getRegionId() == 5021));
		assertTrue("so it must gear up for the fight it is walking into",
			planner.isGearPhase());
	}

	/**
	 * A pack that fills mid-run on a chopping run earns a deposit trip, which ends when the
	 * pack has space again.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"we'll fill up with logs"</i> — tree clearing fills the pack with logs, which never
	 * note, and every later stop then has no room to harvest into. The withdraw list cannot
	 * see it coming, because nothing on it is missing.
	 */
	@Test
	public void aFullPackOnAChoppingRunEarnsADepositTrip()
	{
		standingIn(VARROCK_REGION);
		FarmPatch tree = guildPatch(PatchImplementation.TREE);
		availability.setAvailable(tree, true);
		record(FALADOR_HERB, 43);
		availability.setAvailable(patch(FALADOR_HERB), true);

		planner.start(EnumSet.of(PatchImplementation.TREE, PatchImplementation.HERB), false);
		planner.leaveBank();
		assertFalse("fixture: the opening leg is done", planner.isAtBankLeg());

		// The guide pushes "full, on a run that chops"; the planner arms on the edge.
		planner.setPackFull(true);
		planner.reviewProgress();
		assertTrue("a full pack is a bank trip", planner.isAtBankLeg());
		assertEquals(RunPlanner.BankLegReason.DEPOSIT, planner.getBankLegReason());

		// Still full: the leg holds even with nothing on the withdraw list.
		planner.leaveBank();
		assertTrue("the trip is not over while the pack is", planner.isAtBankLeg());

		// Deposited: space again, and the run moves on.
		planner.setPackFull(false);
		planner.leaveBank();
		assertFalse(planner.isAtBankLeg());
		assertNull(planner.getBankLegReason());
	}

	/**
	 * A deposit trip ends with the pack, whatever else the run still wants from a bank.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * The leg's exit used to fall through to the ordinary supply clauses, and the run's
	 * standing wants — vault seeds the player had deliberately waived among them — chained a
	 * trip that existed to shed logs open forever: <i>"the plugin really doesn't like when I
	 * drop things, it thinks my pack is full"</i>. Dropping IS dealing with the pack.
	 */
	@Test
	public void aDepositTripEndsWithThePackWhateverElseIsWanted()
	{
		standingIn(VARROCK_REGION);
		FarmPatch tree = guildPatch(PatchImplementation.TREE);
		availability.setAvailable(tree, true);

		planner.start(EnumSet.of(PatchImplementation.TREE), false);
		planner.leaveBank();

		planner.setPackFull(true);
		planner.reviewProgress();
		assertTrue(planner.isAtBankLeg());

		// The run still wants plenty from a bank — and the player sheds the pack on the
		// ground instead of visiting one.
		planner.setWithdrawOutstanding(true);
		planner.setPackFull(false);
		planner.leaveBank();

		assertFalse("the trip was about the pack, and the pack is dealt with",
			planner.isAtBankLeg());
	}

	/** Waving the deposit trip past is honoured until the pack has emptied and filled again. */
	@Test
	public void aWaivedDepositTripDoesNotReArmOnTheSameFill()
	{
		standingIn(VARROCK_REGION);
		FarmPatch tree = guildPatch(PatchImplementation.TREE);
		availability.setAvailable(tree, true);

		planner.start(EnumSet.of(PatchImplementation.TREE), false);
		planner.leaveBank();

		planner.setPackFull(true);
		planner.reviewProgress();
		assertTrue(planner.isAtBankLeg());
		planner.waiveBankLeg();
		assertFalse(planner.isAtBankLeg());

		planner.reviewProgress();
		assertFalse("the level must not re-arm what the player waved past",
			planner.isAtBankLeg());

		planner.setPackFull(false);
		planner.reviewProgress();
		planner.setPackFull(true);
		planner.reviewProgress();
		assertTrue("a fresh fill is a fresh edge", planner.isAtBankLeg());
	}

	/**
	 * The slowest crop outranks the nearest stop when the run chooses its next leg.
	 *
	 * <p>Requested from play: growth happens in the background, so a run cut short should
	 * have started its longest clocks first — the magic tree's hours count while the herbs
	 * are still being walked between. Travel still breaks ties among equally slow stops.
	 */
	@Test
	public void theSlowestCropOutranksTheNearestStop()
	{
		when(pluginConfig.slowestCropsFirst()).thenReturn(true);
		standingIn(VARROCK_REGION);

		record(FALADOR_HERB, 43);
		availability.setAvailable(patch(FALADOR_HERB), true);
		selection.toggle(com.dooglemaps.data.Seed.RANARR);

		FarmPatch tree = guildPatch(PatchImplementation.TREE);
		availability.setAvailable(tree, true);
		selection.toggle(com.dooglemaps.data.Seed.MAGIC);

		planner.start(EnumSet.of(PatchImplementation.HERB, PatchImplementation.TREE), false);
		planner.leaveBank();

		assertEquals("the magic tree's clock starts first",
			Collections.singleton(FARMING_GUILD_REGION), regionsTargeted());
	}

	/** The off switch keeps the old order: everything on offer, the router picks by travel. */
	@Test
	public void withSlowestFirstOffEveryStopStaysOnOffer()
	{
		when(pluginConfig.slowestCropsFirst()).thenReturn(false);
		standingIn(VARROCK_REGION);

		record(FALADOR_HERB, 43);
		availability.setAvailable(patch(FALADOR_HERB), true);
		selection.toggle(com.dooglemaps.data.Seed.RANARR);

		FarmPatch tree = guildPatch(PatchImplementation.TREE);
		availability.setAvailable(tree, true);
		selection.toggle(com.dooglemaps.data.Seed.MAGIC);

		planner.start(EnumSet.of(PatchImplementation.HERB, PatchImplementation.TREE), false);
		planner.leaveBank();

		assertTrue("both stops are the router's to choose between",
			regionsTargeted().contains(FARMING_GUILD_REGION)
				&& regionsTargeted().contains(
					patch(FALADOR_HERB).getRegion().getRegionId()));
	}

	/**
	 * Gearing up inside the guild, the cave is the run's only route.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * The cave entrance shares the guild's region with a dozen patches, so a player gearing
	 * up at the guild bank counted as "standing on work", the route was cleared, and the run
	 * offered the big bin to someone in combat kit — <i>"taking me to the big bin after
	 * gearing up. When we gear up for hespori it should be our only target until its
	 * done"</i>. Work you cannot do in the gear you are wearing is not work you are
	 * standing on.
	 */
	@Test
	public void theGearPhaseTargetsOnlyTheCaveEvenAmidGuildWork()
	{
		standingIn(FARMING_GUILD_REGION);
		record(HESPORI, 7);
		availability.setAvailable(patch(HESPORI), true);
		FarmPatch tree = guildPatch(PatchImplementation.TREE);
		availability.setAvailable(tree, true);

		planner.start(EnumSet.of(PatchImplementation.HESPORI, PatchImplementation.TREE), true);
		planner.setWithdrawOutstanding(false);
		planner.leaveBank();

		assertEquals("one target: the cave entrance, not the guild's own patches",
			Collections.singleton(new WorldPoint(1230, 3730, 0)), lastTargets());
	}

	/**
	 * A harvest-only cactus is not worth a trip for one spine of four.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * A cactus regrows a spine every twenty minutes and holds four, so twenty minutes after
	 * picking it clean there is exactly one on it. {@code heldForRegrowth} says to wait — and it
	 * gated {@code planStops} only, so once the stop had been completed the single spine
	 * un-completed it and the run went back. Reported from play: "we're going back to cactuses
	 * too soon... after already harvesting it twenty minutes prior, only now receiving 1 cactus
	 * spine".
	 */
	@Test
	public void aPartlyRegrownCactusDoesNotFetchYouBack()
	{
		FarmPatch cactus = FarmingWorldData.getPatches(PatchImplementation.CACTUS).get(0);
		availability.setAvailable(cactus, true);
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.harvestOnly(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.CACTUS)))).thenReturn(true);
		when(runOptions.isHarvestOnly(Mockito.any())).thenReturn(true);
		standingIn(VARROCK_REGION);

		// Varbit 18: all four spines on it, which is worth the trip.
		stateStore.recordVarbit(cactus, 18, cactus.getImplementation().forVarbitValue(18));
		Set<PatchImplementation> cacti = EnumSet.of(PatchImplementation.CACTUS);
		planner.start(cacti);
		planner.leaveBank();
		assertEquals("fixture: a full cactus is worth visiting", 1,
			planner.getRemaining().size());

		// Picked clean, then one spine back twenty minutes later.
		stateStore.recordVarbit(cactus, 15, cactus.getImplementation().forVarbitValue(15));
		assertTrue("fixture: a bare cactus wants nothing", planner.getRemaining().isEmpty());

		stateStore.recordVarbit(cactus, 16, cactus.getImplementation().forVarbitValue(16));

		assertTrue("one spine of four is not a trip", planner.getRemaining().isEmpty());
	}

	/**
	 * A full run waits for the regrowth too — the spade is why, not the exemption.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * The hold used to exempt full runs on the argument that holding a replant trip hostage
	 * to berries the spade is about to destroy would be backwards. Play refuted it on its own
	 * terms: a potato cactus visited at one potato of seven harvests one and digs the other
	 * six out of existence. <i>"we did go back to the potato cactus too soon... I want it
	 * full."</i>
	 */
	@Test
	public void aFullRunWaitsForTheRegrowthToo()
	{
		FarmPatch cactus = FarmingWorldData.getPatches(PatchImplementation.CACTUS).get(0);
		availability.setAvailable(cactus, true);
		standingIn(VARROCK_REGION);

		// One spine back of four, on the FULL cactus line - no harvest-only tick anywhere.
		stateStore.recordVarbit(cactus, 15, cactus.getImplementation().forVarbitValue(15));
		planner.start(EnumSet.of(PatchImplementation.CACTUS));
		planner.leaveBank();

		assertTrue("a part-regrown plant is not worth the trip on any line",
			planner.getRemaining().isEmpty());

		// Fully regrown: now the trip harvests everything the spade would have destroyed.
		stateStore.recordVarbit(cactus, 18, cactus.getImplementation().forVarbitValue(18));
		planner.start(EnumSet.of(PatchImplementation.CACTUS));
		planner.leaveBank();

		assertEquals("full is worth the trip", 1, planner.getRemaining().size());
	}

	/**
	 * But standing at it, the first pick must not end the stop under your feet.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * {@code heldForRegrowth} says its own javadoc twice over: planning-time only, because "a
	 * completion filter would read 'no longer full' as 'not worth visiting' and finish the stop
	 * under their feet at the first berry". It then became one, in {@code isComplete}, without
	 * the standing-here guard {@code clusterHeld} carries.
	 *
	 * <p>So picking one unit dropped the stop out of {@code getRemaining()},
	 * {@code GuideTracker.stopAt} answered null, and {@code computeStepsHere} took its
	 * between-stops branch — where the only thing left to say is the leaving errand. Reported
	 * from play at the Ardougne monastery bush on a harvest-only run: told to note each white
	 * berry as it was picked, and never told to pick the next one.
	 *
	 * <p>The hold is about not making the <b>trip</b>. Standing there, the trip is already spent.
	 * The test above keeps its half of that: away from the patch, one spine is still not a trip.
	 */
	@Test
	public void standingAtAPartlyPickedPatchTheStopStaysOpen()
	{
		FarmPatch cactus = FarmingWorldData.getPatches(PatchImplementation.CACTUS).get(0);
		availability.setAvailable(cactus, true);
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.harvestOnly(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.CACTUS)))).thenReturn(true);
		when(runOptions.isHarvestOnly(Mockito.any())).thenReturn(true);
		standingIn(cactus.getRegion().getRegionId());

		// Full, then one picked - the moment the shipped test above measures from a distance.
		stateStore.recordVarbit(cactus, 18, cactus.getImplementation().forVarbitValue(18));
		planner.start(EnumSet.of(PatchImplementation.CACTUS));
		planner.leaveBank();
		assertEquals("fixture: a full cactus is worth visiting", 1, planner.getRemaining().size());

		stateStore.recordVarbit(cactus, 17, cactus.getImplementation().forVarbitValue(17));

		assertEquals("standing here, the rest of the crop is still work", 1,
			planner.getRemaining().size());
	}

	/**
	 * Landing in a stop's region is not arriving at it, when the patch is across the region.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * The route was cleared the moment the player stood anywhere in a remaining stop's region.
	 * Right where the arrival point <b>is</b> the patches — Falador drops you among them, and a
	 * line there competes with the per-patch guidance rather than adding to it.
	 *
	 * <p>Wrong wherever it is not. Brimhaven's spirit tree and its palm tree patch are both region
	 * 11058, at {@code (2802,3203)} and {@code (2765,3213)} — thirty-eight tiles apart. Taking the
	 * tree wiped the route on the arrival tick and left the palm tree most of a screen away with
	 * nothing drawn. Reported from play as the line flashing and vanishing, which is the reply to
	 * the pre-teleport request landing a tick late and being cleared behind it.
	 */
	@Test
	public void landingAcrossTheRegionFromAPatchStillGetsARoute()
	{
		FarmPatch palm = FarmingWorldData.getPatches(PatchImplementation.FRUIT_TREE).stream()
			.filter(p -> p.getRegion().getRegionId() == BRIMHAVEN_REGION)
			.findFirst().orElseThrow(AssertionError::new);
		availability.setAvailable(palm, true);

		// Where the spirit tree drops you: same region, the far side of it.
		WorldPoint spiritTree = new WorldPoint(2802, 3203, 0);
		WorldPoint patchAt = patchLocations.getLocation(palm);
		assertTrue("fixture: the two are far enough apart to be the point",
			spiritTree.distanceTo(patchAt) > 20);
		standingAtPoint(spiritTree);

		planner.start(EnumSet.of(PatchImplementation.FRUIT_TREE));

		assertFalse("the patch is across the region, so it still wants a line",
			lastTargets().isEmpty());
	}

	/** And standing on the patch clears it, which is what the whole rule is for. */
	@Test
	public void standingAtThePatchClearsTheRoute()
	{
		FarmPatch palm = FarmingWorldData.getPatches(PatchImplementation.FRUIT_TREE).stream()
			.filter(p -> p.getRegion().getRegionId() == BRIMHAVEN_REGION)
			.findFirst().orElseThrow(AssertionError::new);
		availability.setAvailable(palm, true);
		standingAt(palm);

		planner.start(EnumSet.of(PatchImplementation.FRUIT_TREE));

		assertTrue("you are stood on it; a drawn line would be a second voice",
			lastTargets().isEmpty());
	}

	/** Brimhaven, whose spirit tree and palm tree share a region and little else. */
	private static final int BRIMHAVEN_REGION = 11058;

	private void standingAtPoint(WorldPoint where)
	{
		net.runelite.api.Player player = Mockito.mock(net.runelite.api.Player.class);
		when(player.getWorldLocation()).thenReturn(where);
		when(client.getLocalPlayer()).thenReturn(player);
		playerLocation.onGameTick(new net.runelite.api.events.GameTick());
	}

	/** Lumbridge's hops patch and the Champions' Guild bush - one teleport, two regions. */
	private static final int LUMBRIDGE_HOPS_REGION = 12851;
	private static final int CHAMPIONS_GUILD_REGION = 12596;

	/**
	 * Two regions that share a way in are serviced as one stop.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * The route is chosen a leg at a time by whichever remaining stop is cheapest to reach, so
	 * two stops in the same corner of the map are not necessarily consecutive: the run serviced
	 * one, travelled away, and came back to the same teleport later for the other. Reported from
	 * play — <i>"we end up back there at the same tele to champions guild later in the run doing
	 * the bush or hops or vice versa"</i>.
	 */
	@Test
	public void regionsSharingAWayInAreOneStop()
	{
		FarmPatch hops = FarmingWorldData.getPatches(PatchImplementation.HOPS).stream()
			.filter(p -> p.getRegion().getRegionId() == LUMBRIDGE_HOPS_REGION)
			.findFirst().orElseThrow(AssertionError::new);
		FarmPatch bush = FarmingWorldData.getPatches(PatchImplementation.BUSH).stream()
			.filter(p -> p.getRegion().getRegionId() == CHAMPIONS_GUILD_REGION)
			.findFirst().orElseThrow(AssertionError::new);
		availability.setAvailable(hops, true);
		availability.setAvailable(bush, true);
		// Left unobserved: a patch never seen is treated as worth a look, which is all this
		// test needs and avoids pinning a varbit decode it is not about.
		standingIn(VARROCK_REGION);

		planner.start(EnumSet.of(PatchImplementation.HOPS, PatchImplementation.BUSH));
		planner.leaveBank();

		java.util.List<RunStop> stops = planner.getRemaining();
		assertEquals("one arrival, so one stop", 1, stops.size());
		RunStop only = stops.get(0);
		assertEquals("and it is the teleport's own destination", CHAMPIONS_GUILD_REGION,
			only.getRegion().getRegionId());
		assertEquals("the hops joined the bush", 2, only.getPatches().size());
		assertTrue("standing at the hops is standing at this stop",
			only.claimsRegion(LUMBRIDGE_HOPS_REGION));
	}

	/**
	 * And a region only ever joins a stop the run is already making.
	 *
	 * <p>The whole safety of the merge. Without the bush there is nothing to share the trip
	 * with, so Lumbridge keeps its own stop rather than becoming a Champions' Guild stop with no
	 * patches in the Champions' Guild — which everything downstream keys on and would misread.
	 */
	@Test
	public void aSharedRegionAloneKeepsItsOwnStop()
	{
		FarmPatch hops = FarmingWorldData.getPatches(PatchImplementation.HOPS).stream()
			.filter(p -> p.getRegion().getRegionId() == LUMBRIDGE_HOPS_REGION)
			.findFirst().orElseThrow(AssertionError::new);
		availability.setAvailable(hops, true);
		standingIn(VARROCK_REGION);

		planner.start(EnumSet.of(PatchImplementation.HOPS));
		planner.leaveBank();

		java.util.List<RunStop> stops = planner.getRemaining();
		assertEquals(1, stops.size());
		assertEquals("nothing to share with, so it stands alone", LUMBRIDGE_HOPS_REGION,
			stops.get(0).getRegion().getRegionId());
	}

	/**
	 * Entrana comes after the Ardougne monastery, because the monks confiscate the teleport.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * The Ardougne cloak serves the monastery bush stop, and the Entrana boat bans it as
	 * combat gear — so Entrana first meant a second bank trip on the mainland just to fetch
	 * the cloak back. <i>"we should always do the entrana run after the ardougne farm run...
	 * which means re-banking after."</i> While both stops remain, Entrana is simply not
	 * offered; with the monastery done, it is offered exactly as before.
	 */
	@Test
	public void entranaWaitsForTheArdougneMonastery()
	{
		FarmPatch monasteryBush = FarmingWorldData.getPatches(PatchImplementation.BUSH).stream()
			.filter(p -> p.getRegion().getRegionId() == 10290)
			.findFirst().orElseThrow(AssertionError::new);
		FarmPatch entranaHops = FarmingWorldData.getPatches(PatchImplementation.HOPS).stream()
			.filter(p -> p.getRegion().getRegionId() == 11060)
			.findFirst().orElseThrow(AssertionError::new);
		availability.setAvailable(monasteryBush, true);
		availability.setAvailable(entranaHops, true);
		standingIn(VARROCK_REGION);

		planner.start(EnumSet.of(PatchImplementation.BUSH, PatchImplementation.HOPS));
		planner.leaveBank();

		assertTrue("the monastery is on offer", regionsTargeted().contains(10290));
		assertFalse("Entrana is withheld while the cloak's stop remains",
			regionsTargeted().contains(11060));

		// The monastery done with, Entrana is an ordinary stop again.
		availability.setAvailable(monasteryBush, false);
		planner.start(EnumSet.of(PatchImplementation.HOPS));
		planner.leaveBank();
		assertTrue("with the monastery gone, Entrana is offered",
			regionsTargeted().contains(11060));
	}

	/**
	 * Catherby's fruit tree and the plot are one Catherby arrival.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * Two regions, both named "Catherby", each its own stop — so the run committed to one while
	 * the other stayed a separate leg, re-teleported to Catherby twice, and the nexus row
	 * highlight churned between two destinations it could not tell apart by name. Requested in
	 * the hops pair's own words: Catherby's herb, bin, allotments, flower and fruit tree are
	 * one trip.
	 */
	@Test
	public void catherbysFruitTreeJoinsThePlotsStop()
	{
		FarmPatch herb = patch(CATHERBY_HERB);
		FarmPatch fruit = patch(CATHERBY_FRUIT);
		availability.setAvailable(herb, true);
		availability.setAvailable(fruit, true);
		// Both left unobserved: never seen is worth a look, which is all this test needs.
		standingIn(VARROCK_REGION);

		planner.start(EnumSet.of(PatchImplementation.HERB, PatchImplementation.FRUIT_TREE));
		planner.leaveBank();

		java.util.List<RunStop> stops = planner.getRemaining();
		assertEquals("one arrival serves the whole town", 1, stops.size());
		RunStop only = stops.get(0);
		assertEquals("hosted by the plot, where the teleports land", 11062,
			only.getRegion().getRegionId());
		assertTrue("standing at the fruit tree is standing at this stop",
			only.claimsRegion(11317));
	}

	/**
	 * The second half of a shared stop gets a route of its own.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"between champions guild bush patch and the lumby hops patch we should use SP to
	 * navigate"</i>. The merge above is what makes those two one stop, and three separate
	 * shortcuts in {@code RunPlanner} then read "one stop" as "one place", which it is for every
	 * stop the game files and not for this one — the patches are about fifty tiles apart:
	 *
	 * <ul>
	 *   <li>{@code hasArrivedAt} counted arriving at <i>any</i> patch as arriving at the stop, so
	 *       reaching the bush wiped the route.</li>
	 *   <li>{@code RunStop.getRouteTargets} answers with patch zero, so even a fresh request
	 *       aimed at the bush the player was standing on and Shortest Path finished instantly.</li>
	 *   <li>{@code onPatchChanged} only re-asked when the <i>whole</i> stop was done, so nothing
	 *       triggered a new route when the bush was finished.</li>
	 * </ul>
	 *
	 * <p>Any one of them left alone is enough to reproduce the report, which is why this drives
	 * the whole sequence rather than testing the three separately.
	 */
	@Test
	public void theSecondHalfOfASharedStopIsRoutedTo()
	{
		FarmPatch hops = FarmingWorldData.getPatches(PatchImplementation.HOPS).stream()
			.filter(p -> p.getRegion().getRegionId() == LUMBRIDGE_HOPS_REGION)
			.findFirst().orElseThrow(AssertionError::new);
		FarmPatch bush = FarmingWorldData.getPatches(PatchImplementation.BUSH).stream()
			.filter(p -> p.getRegion().getRegionId() == CHAMPIONS_GUILD_REGION)
			.findFirst().orElseThrow(AssertionError::new);
		availability.setAvailable(hops, true);
		availability.setAvailable(bush, true);
		standingIn(VARROCK_REGION);

		planner.start(EnumSet.of(PatchImplementation.HOPS, PatchImplementation.BUSH));
		planner.leaveBank();

		// Fixture: the two really are far enough apart for this to be a question at all.
		assertTrue("the fifty tiles are the whole point of this test",
			patchLocations.getLocation(bush).distanceTo(patchLocations.getLocation(hops)) > 10);

		// Arriving at the bush clears the route, which is right: the work is under your feet and
		// a drawn line would be a second voice.
		standingAt(bush);
		planner.retarget();
		assertTrue("stood on the bush, so no route", lastTargets().isEmpty());

		// Pick and replant it. Not service(), which plants varbit 4 — a guam seedling for a herb
		// patch, but plain weeds for a bush, so the patch would still be asking to be dealt with
		// and the test would prove nothing. Found by decoding rather than assumed.
		int planted = justPlantedValue(bush);
		assertTrue("no growing-crop varbit decodes for this bush", planted >= 0);
		record(bush.getKey(), planted);
		planner.onPatchChanged(bush);

		// The hops are still to do, and are most of Lumbridge away.

		assertFalse("the walk to the hops is a journey and wants a route",
			lastTargets().isEmpty());
		assertEquals("routed to the hops, not back to the bush being stood on",
			Collections.singleton(LUMBRIDGE_HOPS_REGION), regionsTargeted());
	}

	/** Falador's flower patch, which ripens far faster than the herb beside it. */
	private static final String FALADOR_FLOWER = "12083.4773";

	/**
	 * The plot the player is standing on is never held.
	 *
	 * <p>The hold exists to save the teleport, and standing there means it is already spent —
	 * starting a run at Falador should pick Falador's ready herb whatever the allotment
	 * beside it is doing.
	 */
	@Test
	public void thePlotBeingStoodOnIsNeverHeld()
	{
		when(pluginConfig.holdClustersUntilReady()).thenReturn(true);
		standingIn(12083);

		record(FALADOR_HERB, 43);
		record("12083.4771", 7);
		availability.setAvailable(patch(FALADOR_HERB), true);
		availability.setAvailable(patch("12083.4771"), true);

		assertEquals("the teleport this setting saves is already spent",
			1, planner.start(EnumSet.of(PatchImplementation.HERB, PatchImplementation.ALLOTMENT))
				.size());
	}

	/** The same plot, setting off: the ready herb gets its trip exactly as before. */
	@Test
	public void aSharedPlotIsNotHeldByDefault()
	{
		record(FALADOR_HERB, 43);
		record("12083.4771", 7);
		availability.setAvailable(patch(FALADOR_HERB), true);
		availability.setAvailable(patch("12083.4771"), true);

		assertEquals(1,
			planner.start(EnumSet.of(PatchImplementation.HERB, PatchImplementation.ALLOTMENT))
				.size());
	}

	@Test
	public void patchesSwitchedOffAreNeverInARun()
	{
		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), false);

		assertTrue("availability is a global invariant",
			planner.start(EnumSet.of(PatchImplementation.HERB)).isEmpty());
	}

	@Test
	public void oneRegionIsOneStopHoweverManyPatchTypes()
	{
		// Falador's allotments, flower and herb patches share a region, so servicing all of
		// them is a single stop and costs no extra travel.
		record("12083.4771", 3);
		record("12083.4772", 3);
		record("12083.4773", 3);
		record(FALADOR_HERB, 3);
		for (String key : new String[]{"12083.4771", "12083.4772", "12083.4773", FALADOR_HERB})
		{
			availability.setAvailable(patch(key), true);
		}

		List<RunStop> stops = planner.start(EnumSet.of(
			PatchImplementation.ALLOTMENT, PatchImplementation.FLOWER, PatchImplementation.HERB));

		assertEquals("four patches, one place to stand", 1, stops.size());
		assertEquals(4, stops.get(0).getPatches().size());
	}

	// --------------------------------------------------- starting where you stand

	/**
	 * Standing on work, the run starts there rather than at a bank.
	 *
	 * <p>Reported from play: stood at the Ardougne patches with dead limpwurt, dead and ripe
	 * watermelons and part-picked guams, with everything needed already carried, and Start run
	 * asked for a teleport to a bank. Being routed away from crops you are stood next to is
	 * wrong whatever the trip needs collecting later.
	 */
	@Test
	public void aRunStartsWhereYouAreStandingWhenThereIsWorkThere()
	{
		record(ARDOUGNE_HERB, 43);   // toadflax, ready to pick
		availability.setAvailable(patch(ARDOUGNE_HERB), true);
		standingIn(patch(ARDOUGNE_HERB).getRegion().getRegionId());

		planner.start(EnumSet.of(PatchImplementation.HERB));

		assertTrue(planner.isActive());
		assertFalse("there is work underfoot; the bank can wait", planner.isAtBankLeg());
	}

	/**
	 * A bank in the same region beats standing on work, because reaching it costs nothing.
	 *
	 * <p>Reported from play: starting a contract run inside the Farming Guild, at the guild's own
	 * bank, with no seed and no protection payment withdrawn — and being told to go and clear the
	 * patch. The rule above is about <i>travel</i>: it exists so a run does not teleport you off
	 * crops you are stood next to. The guild's bank, its seed vault and eleven of its patches share
	 * one region, so there is no journey to weigh against the work, and arriving at the patch
	 * without the seed means nothing can be done there anyway.
	 */
	@Test
	public void aBankInTheSameRegionIsCollectedFromBeforeTheWorkThere()
	{
		FarmPatch guildHerb = guildPatch(PatchImplementation.HERB);
		record(guildHerb.getKey(), 3);   // raked and empty, so it wants a seed
		availability.setAvailable(guildHerb, true);
		standingIn(guildHerb.getRegion().getRegionId());

		// Picked, and sitting in the bank you are standing next to — which is what makes the trip
		// necessary and, here, trivial.
		selection.toggle(com.dooglemaps.data.Seed.RANARR);
		stockBank(com.dooglemaps.data.Seed.RANARR, 5);

		planner.start(EnumSet.of(PatchImplementation.HERB));

		assertTrue(planner.isActive());
		assertTrue("the bank is twenty steps away, so collect first", planner.isAtBankLeg());
	}

	/** The Farming Guild's patch of a type, which is where its bank and vault also are. */
	/**
	 * The reported failure, rebuilt from the account's own recorded patch states.
	 *
	 * <h2>Why this fixture is real numbers rather than invented ones</h2>
	 *
	 * "It still routes me to a plot with a crop growing" survived two rounds of reading the
	 * hold's logic and finding it correct, so the next step is not another reading — it is the
	 * actual world it failed in. These varbit values are lifted verbatim from the reporting
	 * account's stored snapshots for the Farming Guild plot: the flower ready, both allotments
	 * and the herb still growing. If the hold works, this plot is not visited.
	 *
	 * <p>The other half of the reconstruction is {@code inTheRun}, which every other test here
	 * gets for free because the mocked {@code PlantingGroups} answers null and the check passes
	 * trivially. In the client it does not: the group has to be one the player ticked. Both
	 * mocks are made to behave here, which is the only way this test can fail for the reason it
	 * is looking for.
	 */
	@Test
	public void theGuildPlotIsHeldWhileItsHerbAndAllotmentsGrow()
	{
		when(pluginConfig.holdClustersUntilReady()).thenReturn(true);
		standingIn(12850);   // anywhere that is not the guild

		groupsAndOptionsBehaveNormally(EnumSet.of(PatchImplementation.HERB,
			PatchImplementation.ALLOTMENT, PatchImplementation.FLOWER));

		// Live values from the account's stored snapshots, 2026-08-11.
		guildState(PatchImplementation.FLOWER, 32);      // limpwurt, HARVESTABLE - the trip
		guildState(PatchImplementation.HERB, 56);        // avantoe, GROWING stage 3 of 5
		guildAllotments(59, 58);                         // watermelons, GROWING stages 7 and 6

		Set<PatchImplementation> types = EnumSet.of(PatchImplementation.HERB,
			PatchImplementation.ALLOTMENT, PatchImplementation.FLOWER);
		assertTrue("the ready flower is not worth the teleport while the plot is still growing",
			planner.start(types).stream()
				.noneMatch(stop -> stop.getRegion().getRegionId() == 4922));
	}

	/** And once they are all done, the trip is on. */
	@Test
	public void theGuildPlotIsVisitedOnceNothingIsStillGrowing()
	{
		when(pluginConfig.holdClustersUntilReady()).thenReturn(true);
		standingIn(12850);

		groupsAndOptionsBehaveNormally(EnumSet.of(PatchImplementation.HERB,
			PatchImplementation.ALLOTMENT, PatchImplementation.FLOWER));

		guildState(PatchImplementation.FLOWER, 32);      // limpwurt, ready
		guildState(PatchImplementation.HERB, 57);        // avantoe, HARVESTABLE
		guildAllotments(60, 60);                         // watermelons, ready

		Set<PatchImplementation> types = EnumSet.of(PatchImplementation.HERB,
			PatchImplementation.ALLOTMENT, PatchImplementation.FLOWER);
		assertTrue("nothing is holding it now",
			planner.start(types).stream()
				.anyMatch(stop -> stop.getRegion().getRegionId() == 4922));
	}

	/**
	 * A tool that leaves your pack mid-run sends the run back for it.
	 *
	 * <h2>Why nothing caught this</h2>
	 *
	 * Whether a run needs a bank was decided once, at {@code start}, and re-asked only for a
	 * contract taken from Jane. Deposit your spade at the second stop and no question was ever
	 * asked again — and the guide's own tool step could not cover it either, being deliberately
	 * silent when the leprechaun has none. Reported from play, in those words: never prompted to
	 * withdraw one, mid run.
	 */
	@Test
	public void aToolLostMidRunSendsTheRunBackForIt()
	{
		record(FALADOR_HERB, 43);
		availability.setAvailable(patch(FALADOR_HERB), true);
		standingIn(12850);
		// Seeds in hand, so the run has no reason of its own to open at a bank.
		selection.toggle(com.dooglemaps.data.Seed.TOADFLAX);
		stockInventory(com.dooglemaps.data.Seed.TOADFLAX, 5);

		// Setting off with everything in hand: no bank leg.
		when(tools.anyOnlyInBank(any())).thenReturn(false);
		planner.start(EnumSet.of(PatchImplementation.HERB));
		assertFalse("nothing was owed at the start", planner.isAtBankLeg());

		// The spade goes into the bank, and the leprechaun has none either - which is exactly
		// what anyOnlyInBank means.
		when(tools.anyOnlyInBank(any())).thenReturn(true);
		planner.reviewSupplies();

		assertTrue("the run diverts to a bank for it", planner.isAtBankLeg());
	}

	/**
	 * The leprechaun's copy is the cheaper trip, so it never causes a divert.
	 *
	 * <p>{@code anyOnlyInBank} is false whenever he has one, and the patch's own tool step
	 * already says "get it from him". Pinned because the tempting shape for this feature — "the
	 * spade is not in your pack, go to a bank" — would teleport a player away from a leprechaun
	 * standing next to them.
	 */
	@Test
	public void aToolTheLeprechaunHoldsNeverDivertsTheRun()
	{
		record(FALADOR_HERB, 43);
		availability.setAvailable(patch(FALADOR_HERB), true);
		standingIn(12850);
		// Seeds in hand, so the run has no reason of its own to open at a bank.
		selection.toggle(com.dooglemaps.data.Seed.TOADFLAX);
		stockInventory(com.dooglemaps.data.Seed.TOADFLAX, 5);

		when(tools.anyOnlyInBank(any())).thenReturn(false);
		planner.start(EnumSet.of(PatchImplementation.HERB));
		planner.reviewSupplies();

		assertFalse("his store is not a bank trip", planner.isAtBankLeg());
	}

	/** A bank leg the player waived stays waived - the escape hatch outranks this. */
	@Test
	public void aWaivedBankLegIsNotReArmedByALostTool()
	{
		record(FALADOR_HERB, 43);
		availability.setAvailable(patch(FALADOR_HERB), true);
		standingIn(12850);

		// No seeds picked, so this run opens at a bank - which is what there is to waive.
		when(tools.anyOnlyInBank(any())).thenReturn(false);
		planner.start(EnumSet.of(PatchImplementation.HERB));
		assertTrue("this run opens at a bank", planner.isAtBankLeg());
		planner.waiveBankLeg();
		assertFalse("and the player pressed Skip on it", planner.isAtBankLeg());

		when(tools.anyOnlyInBank(any())).thenReturn(true);
		planner.reviewSupplies();

		assertFalse("no means no for the rest of the run", planner.isAtBankLeg());
	}

	/**
	 * The ready counter does not advertise a plot the run would refuse to visit.
	 *
	 * <h2>The half of the hold that was missing</h2>
	 *
	 * {@code selectedForRuns} — the top-left infobox's "a farm run is worth starting" number —
	 * applied {@code heldForRegrowth} but not {@code clusterHeld}, and the two have to be the
	 * same list or the number is a lie. Kourend, from this account's stored state, is the case:
	 * a ripe watermelon and a ripe limpwurt beside a ranarr with most of its growth left. Start
	 * run correctly refuses the teleport; the counter was saying "two ready, go now" about it,
	 * which is the trip the setting exists to prevent.
	 */
	@Test
	public void theReadyCounterDoesNotAdvertiseAHeldPlot()
	{
		when(pluginConfig.holdClustersUntilReady()).thenReturn(true);
		standingIn(12850);
		groupsAndOptionsBehaveNormally(EnumSet.of(PatchImplementation.HERB,
			PatchImplementation.ALLOTMENT, PatchImplementation.FLOWER));

		// Kourend, live values: north allotment raked empty, south ripe, flower ripe, and a
		// ranarr in the herb patch with its growth ahead of it.
		FarmPatch flower = kourendPatch(PatchImplementation.FLOWER);
		FarmPatch herb = kourendPatch(PatchImplementation.HERB);
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.ALLOTMENT))
		{
			if (patch.getRegion().getRegionId() == 6967)
			{
				record(patch.getKey(), 60);          // watermelon, ready
				availability.setAvailable(patch, true);
			}
		}
		record(flower.getKey(), 32);                 // limpwurt, ready
		availability.setAvailable(flower, true);
		record(herb.getKey(), 32);                   // ranarr, growing
		availability.setAvailable(herb, true);

		assertFalse("a ripe flower on a plot the run will not visit is not a reason to go",
			planner.selectedForRuns(flower));

		// And the counter agrees with Start run, which is the property that was broken.
		assertTrue("Start run refuses this plot", planner.start(EnumSet.of(
			PatchImplementation.HERB, PatchImplementation.ALLOTMENT,
			PatchImplementation.FLOWER)).stream()
			.noneMatch(stop -> stop.getRegion().getRegionId() == 6967));
	}

	private static FarmPatch kourendPatch(PatchImplementation type)
	{
		for (FarmPatch candidate : FarmingWorldData.getPatches(type))
		{
			if (candidate.getRegion().getRegionId() == 6967)
			{
				return candidate;
			}
		}
		throw new AssertionError("no " + type + " patch at Kourend");
	}

	/**
	 * Makes the two mocks that stand between a patch and {@code inTheRun} answer like the real
	 * thing: every patch in its plain group, and the given types ticked for a full run.
	 */
	private void groupsAndOptionsBehaveNormally(Set<PatchImplementation> ticked)
	{
		when(groups.groupFor(any())).thenAnswer(i -> i.getArgument(0) == null
			? null
			: com.dooglemaps.data.PlantingGroup.of(
				((FarmPatch) i.getArgument(0)).getImplementation()));
		when(runOptions.isSelected(any())).thenAnswer(i ->
		{
			com.dooglemaps.data.RunOption option = i.getArgument(0);
			return ticked.contains(option.getGroup().getType());
		});
	}

	private void guildState(PatchImplementation type, int varbitValue)
	{
		FarmPatch patch = guildPatch(type);
		record(patch.getKey(), varbitValue);
		availability.setAvailable(patch, true);
	}

	private void guildAllotments(int north, int south)
	{
		int[] values = {north, south};
		int index = 0;
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.ALLOTMENT))
		{
			if (patch.getRegion().getRegionId() != 4922)
			{
				continue;
			}
			record(patch.getKey(), values[index++]);
			availability.setAvailable(patch, true);
		}
		assertEquals("the guild should have two allotment patches", 2, index);
	}

	private static FarmPatch guildPatch(PatchImplementation type)
	{
		for (FarmPatch candidate : com.dooglemaps.data.FarmingWorldData.getPatches(type))
		{
			if (candidate.getRegion().getRegionId() == 4922)
			{
				return candidate;
			}
		}
		throw new AssertionError("no " + type + " patch in the Farming Guild");
	}

	/**
	 * No path is drawn to the ground you are standing on.
	 *
	 * <p>Handing the router the stop you are already at made it plot a route to your own feet
	 * for a moment before working out it had arrived — the flicker seen when a run starts on
	 * top of some work.
	 */
	@Test
	public void theStopYouAreStandingInIsNotRoutedTo()
	{
		record(ARDOUGNE_HERB, 43);
		availability.setAvailable(patch(ARDOUGNE_HERB), true);
		standingAt(patch(ARDOUGNE_HERB));

		planner.start(EnumSet.of(PatchImplementation.HERB));

		assertTrue("nowhere to navigate to, so no path", lastTargets().isEmpty());
		assertTrue("but the run is very much still running", planner.isActive());
	}

	/**
	 * Nothing is routed at all while there is work where you stand.
	 *
	 * <p>This deliberately replaced the opposite behaviour. Routing to the next stop while
	 * standing on ripe crops is a second instruction competing with the one guided mode is
	 * giving — and in practice Shortest Path drew a teleport-and-bank route across the screen
	 * the whole time the player was working the patch in front of them.
	 *
	 * <p>Finishing a location before travelling is also the shape the wiki's own farm-run guide
	 * describes, so the route has nothing useful to say until this stop is done.
	 */
	@Test
	public void nothingIsRoutedWhileThereIsWorkWhereYouStand()
	{
		record(ARDOUGNE_HERB, 43);
		record(CATHERBY_HERB, 43);
		availability.setAvailable(patch(ARDOUGNE_HERB), true);
		availability.setAvailable(patch(CATHERBY_HERB), true);
		standingAt(patch(ARDOUGNE_HERB));

		planner.start(EnumSet.of(PatchImplementation.HERB));

		assertTrue("Catherby can wait until Ardougne is finished", lastTargets().isEmpty());
	}

	/** Once the stop underfoot is done, the rest of the run is routed again. */
	@Test
	public void routingResumesOnceTheStopUnderfootIsFinished()
	{
		record(ARDOUGNE_HERB, 43);
		record(CATHERBY_HERB, 43);
		availability.setAvailable(patch(ARDOUGNE_HERB), true);
		availability.setAvailable(patch(CATHERBY_HERB), true);

		// Seeds in hand, or finishing the stop would correctly trigger the supply trip that was
		// deferred at the start and route to every bank instead.
		selection.toggle(com.dooglemaps.data.Seed.TOADFLAX);
		stockInventory(com.dooglemaps.data.Seed.TOADFLAX, 5);

		standingIn(patch(ARDOUGNE_HERB).getRegion().getRegionId());

		planner.start(EnumSet.of(PatchImplementation.HERB));
		service(ARDOUGNE_HERB);

		assertEquals("Catherby is the only thing left, so route to it",
			1, regionsTargeted().size());
	}

	/** The deferred trip is not forgotten — it happens once the work here is done. */
	@Test
	public void theDeferredSupplyTripHappensAfterTheStopIsFinished()
	{
		record(ARDOUGNE_HERB, 43);
		availability.setAvailable(patch(ARDOUGNE_HERB), true);
		standingIn(patch(ARDOUGNE_HERB).getRegion().getRegionId());

		planner.start(EnumSet.of(PatchImplementation.HERB));
		assertFalse(planner.isAtBankLeg());

		service(ARDOUGNE_HERB);

		assertTrue("the supplies were owed, not cancelled", planner.isAtBankLeg());
	}

	/**
	 * A seed picked for a patch type the run does not cover cannot send it to a bank.
	 *
	 * <p>Every selected seed used to be considered whatever the run was, so one bush seed
	 * picked at some point in the past made every herb run start at a bank.
	 */
	@Test
	public void aSeedForAnotherPatchTypeDoesNotForceASupplyTrip()
	{
		record(ARDOUGNE_HERB, 43);
		availability.setAvailable(patch(ARDOUGNE_HERB), true);

		// Picked, owned nowhere, and irrelevant: this run has no bush in it.
		selection.toggle(com.dooglemaps.data.Seed.REDBERRIES);
		// And the herb the run does cover is already in the pack.
		selection.toggle(com.dooglemaps.data.Seed.TOADFLAX);
		stockInventory(com.dooglemaps.data.Seed.TOADFLAX, 5);

		planner.start(EnumSet.of(PatchImplementation.HERB));

		assertTrue("the bush seed is nothing to do with this run",
			planner.getSupplySources().isEmpty());
	}

	// ------------------------------------------------------- previewing a run

	/**
	 * The panel promises a set of destinations before the run starts, so the promise has to be
	 * the run. Comparing against {@code start} rather than against a hand-written expectation
	 * is the point: a filter that changed on one side only would pass a fixed list.
	 */
	@Test
	public void previewingARunGivesExactlyTheStopsItWouldHave()
	{
		record(CATHERBY_HERB, 43);
		record(FALADOR_HERB, 3);
		record(ARDOUGNE_HERB, 171);
		record("12083.4771", 3);
		for (String key : new String[]{CATHERBY_HERB, FALADOR_HERB, ARDOUGNE_HERB, "12083.4771"})
		{
			availability.setAvailable(patch(key), true);
		}

		Set<PatchImplementation> types =
			EnumSet.of(PatchImplementation.HERB, PatchImplementation.ALLOTMENT);

		List<String> previewed = new ArrayList<>();
		for (RunStop stop : planner.previewStops(types))
		{
			previewed.add(stop.getName() + "/" + stop.getPatches().size());
		}

		List<String> actual = new ArrayList<>();
		for (RunStop stop : planner.start(types))
		{
			actual.add(stop.getName() + "/" + stop.getPatches().size());
		}

		assertEquals(actual, previewed);
	}

	@Test
	public void previewingARunDoesNotStartOne()
	{
		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);

		assertFalse("a preview should be free of side effects",
			planner.previewStops(EnumSet.of(PatchImplementation.HERB)).isEmpty());

		assertFalse("looking at a run must not begin one", planner.isActive());
		assertTrue("and must not route anywhere", lastTargets().isEmpty());
	}

	// ------------------------------------------------------------ what is routed

	@Test
	public void aRunStartsByRoutingToABank()
	{
		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);

		planner.start(EnumSet.of(PatchImplementation.HERB));

		assertTrue(planner.isAtBankLeg());
		assertTrue("the opening leg should target banks, not patches", lastTargets().size() > 1);
	}

	/**
	 * A trip that wants the vault <b>and</b> the bank goes to the guild, not the nearest bank.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"when we start a run or are on a bank trip and need to go to the seed vault, we need to
	 * navigate to the farming guild's seed vault, its the only one in the game, getting navigated
	 * to one of the other banks isn't the right move."</i>
	 *
	 * <p>The vault-only case below was always right. This is the one that was not: with both
	 * containers wanted, {@code supplyTargetsFor} handed the router the vault <i>plus every
	 * usable bank in the game</i>, and the router did exactly as asked and picked the cheapest —
	 * which is nearly always some other bank, because there is nearly always one closer than the
	 * Farming Guild. The vault then still had to happen, from wherever that left you.
	 *
	 * <p>The guild's chest is ten tiles from the vault, so both errands are served by one
	 * arrival and there is no trade-off being made here. Two targets, never one: returning the
	 * vault <i>instead of</i> the banks is the older bug, where the route pointed at the vault
	 * while the withdraw list read "From the bank: yew, yew, rune pouch".
	 */
	@Test
	public void wantingBothTheVaultAndTheBankRoutesToTheGuildAlone()
	{
		// One of each, and two patches to plant them in, so the allocation genuinely needs both
		// containers rather than emptying whichever it reaches first.
		stockVault(com.dooglemaps.data.Seed.RANARR, 1);
		selection.toggle(com.dooglemaps.data.Seed.RANARR);
		stockBank(com.dooglemaps.data.Seed.SNAPDRAGON, 1);
		selection.toggle(com.dooglemaps.data.Seed.SNAPDRAGON);

		record(FALADOR_HERB, 3);
		record(CATHERBY_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);
		availability.setAvailable(patch(CATHERBY_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB));

		assertEquals("fixture: the leg wants both containers",
			EnumSet.of(SeedSource.BANK, SeedSource.SEED_VAULT),
			EnumSet.copyOf(planner.getSupplySources()));

		Set<WorldPoint> targets = lastTargets();
		assertEquals("the guild serves both, so nowhere else is offered: " + targets,
			2, targets.size());
		assertTrue("the vault itself", targets.contains(banks.getSeedVault()));
		assertTrue("and the chest ten tiles from it",
			targets.contains(banks.getFarmingGuildBank()));
	}

	@Test
	public void seedsInTheVaultAloneSendYouToTheVault()
	{
		// There is one seed vault and it is in the Farming Guild, so routing to "a bank" for
		// vault seeds is precisely the wrong side of the map.
		stockVault(com.dooglemaps.data.Seed.RANARR, 40);
		selection.toggle(com.dooglemaps.data.Seed.RANARR);

		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB));

		assertEquals(java.util.Collections.singleton(SeedSource.SEED_VAULT),
			planner.getSupplySources());
		assertEquals("one target: the vault", 1, lastTargets().size());
		assertEquals(banks.getSeedVault(), lastTargets().iterator().next());
	}

	@Test
	public void bankedSeedsStillRouteToAnyBank()
	{
		stockBank(com.dooglemaps.data.Seed.RANARR, 40);
		selection.toggle(com.dooglemaps.data.Seed.RANARR);

		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB));

		assertEquals(java.util.Collections.singleton(SeedSource.BANK), planner.getSupplySources());
		assertTrue("every usable bank is a candidate", lastTargets().size() > 1);
	}

	@Test
	public void seedsAlreadyCarriedNeedNoSupplyTrip()
	{
		stockInventory(com.dooglemaps.data.Seed.RANARR, 10);
		selection.toggle(com.dooglemaps.data.Seed.RANARR);

		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB));

		assertTrue(planner.getSupplySources().isEmpty());
		assertFalse("nothing to collect, so go straight to the patches", planner.isAtBankLeg());
		assertEquals("one outstanding stop", 1, regionsTargeted().size());
	}

	@Test
	public void reachingABankSwitchesRoutingToThePatches()
	{
		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB));

		planner.leaveBank();

		assertFalse(planner.isAtBankLeg());
		assertEquals("one outstanding stop, one place targeted", 1, regionsTargeted().size());
	}

	/**
	 * Opening a bank is not the same as having collected anything.
	 *
	 * <p>{@code leaveBank} used to end the leg outright, and it is called from the first bank
	 * container event — so merely opening a bank finished the shopping. With the seeds in the
	 * <b>vault</b>, opening the guild's chest for the payments ended the leg and the vault three
	 * steps away never got its turn: the run then walked to the patches with nothing to plant.
	 */
	@Test
	public void openingABankDoesNotEndTheLegWhileTheVaultStillHasTheSeeds()
	{
		selection.toggle(com.dooglemaps.data.Seed.RANARR);
		stockVault(com.dooglemaps.data.Seed.RANARR, 20);

		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB));

		assertTrue("the seeds are in the vault, so the run opens at a supply point",
			planner.isAtBankLeg());

		planner.leaveBank();
		assertTrue("nothing has been withdrawn, so the leg is still owed",
			planner.isAtBankLeg());

		// Withdrawing is what actually changes the answer: the seeds are on the player now.
		stockInventory(com.dooglemaps.data.Seed.RANARR, 20);
		stockVault(com.dooglemaps.data.Seed.RANARR, 0);
		planner.leaveBank();

		assertFalse("collected, so the run moves on", planner.isAtBankLeg());
	}

	/**
	 * A seed you own nowhere must not strand the run at the bank.
	 *
	 * <p>The counterpart to the test above, and the reason the leg's condition is
	 * {@code suppliesOutstanding} rather than {@code needsSupplyTrip}: something unobtainable is
	 * not a thing withdrawing can fix, so blocking on it would be a run that never starts. The run
	 * goes ahead and skips those patches — which {@code LoadoutSummary} says out loud, so it is
	 * not discovered on arrival.
	 */
	@Test
	public void aSeedYouOwnNowhereDoesNotBlockTheLeg()
	{
		selection.toggle(com.dooglemaps.data.Seed.RANARR);

		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB));

		planner.leaveBank();

		assertFalse("nothing reachable is outstanding, so the run gets going",
			planner.isAtBankLeg());
	}

	@Test
	public void servicingAPatchDropsItFromTheTargets()
	{
		record(CATHERBY_HERB, 3);
		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(CATHERBY_HERB), true);
		availability.setAvailable(patch(FALADOR_HERB), true);

		planner.start(EnumSet.of(PatchImplementation.HERB));
		planner.leaveBank();
		assertEquals(2, regionsTargeted().size());

		service(FALADOR_HERB);

		assertEquals("the serviced stop should stop being a target", 1, regionsTargeted().size());
		assertEquals(1, planner.getRemaining().size());
	}

	@Test
	public void finishingTheLastStopEndsTheRun()
	{
		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB));
		planner.leaveBank();

		service(FALADOR_HERB);

		// Completion arms rather than ends: the emptiness has to be confirmed against a
		// fresh exemption push before the run deactivates, so a one-tick over-broad push
		// (the loadout blind for a tick) cannot kill a live run. The two lines below are
		// what the next game tick does - the guide pushes its exemptions, the plugin polls.
		assertTrue("still active until the next tick confirms", planner.isActive());
		planner.setNothingToDo(Collections.emptySet());
		planner.reviewProgress();

		assertFalse(planner.isActive());
		assertTrue("nothing left to route to", planner.getRemaining().isEmpty());
		assertEquals("the path should be cleared", Collections.emptySet(), lastTargets());
	}

	/**
	 * Out of supplies is not out of work: the run goes back for another load.
	 *
	 * <p>The reported dead end, in its general form. A pack holds one load, so a stop can finish
	 * for no better reason than that the produce ran out — the bin is still {@code FILLING}, the
	 * guide has no step for it because a bin takes no notes, and {@code isComplete} counts a
	 * patch with no step as done. Every stop then reads complete and the run ended a load early
	 * with the bank still holding five hundred pineapples.
	 */
	@Test
	public void runningOutOfSuppliesSendsTheRunBackRatherThanEndingIt()
	{
		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB));
		planner.leaveBank();

		service(FALADOR_HERB);

		// The withdraw list says a bank trip would unblock something - the fill this run could
		// not carry in one load. Pushed the way the guide pushes it, once a tick.
		planner.setWithdrawOutstanding(true);
		planner.setNothingToDo(Collections.emptySet());
		planner.reviewProgress();

		assertTrue("the run is not over while a bank trip would help", planner.isActive());
		assertTrue("and the trip is its next leg", planner.isAtBankLeg());
	}

	/** With nothing a bank could add, the same path still ends the run. */
	@Test
	public void nothingLeftToCollectStillEndsTheRun()
	{
		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB));
		planner.leaveBank();

		service(FALADOR_HERB);

		planner.setWithdrawOutstanding(false);
		planner.setNothingToDo(Collections.emptySet());
		planner.reviewProgress();

		assertFalse(planner.isActive());
	}

	/**
	 * A waived supply leg outranks the diversion, or the escape hatch stops being one.
	 *
	 * <p>Skip step during the supply leg means no for the rest of the run, and a run that ends
	 * is a run the player can restart - where a leg that reopens on the way out is not something
	 * they can refuse twice.
	 */
	@Test
	public void aWaivedLegIsNotReopenedByTheDiversion()
	{
		record(FALADOR_HERB, 3);
		availability.setAvailable(patch(FALADOR_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB));
		planner.waiveBankLeg();

		service(FALADOR_HERB);

		planner.setWithdrawOutstanding(true);
		planner.setNothingToDo(Collections.emptySet());
		planner.reviewProgress();

		assertFalse("no means no for the rest of the run", planner.isAtBankLeg());
		assertFalse(planner.isActive());
	}

	/**
	 * The guild's bin routes when it is empty or collectable, and only then.
	 *
	 * <p>Ready compost is worth the trip and so is an empty bin — settled with the owner: no fill
	 * selection is needed to be shown a bin with room in it, since the guide simply stays quiet
	 * when there is nothing to say. A closed bin still composting is the one state with nothing at
	 * it but a lid. This is the bin the run tick means; see CompostBin.coveredByTheBinTick.
	 */
	@Test
	public void theGuildBinRoutesWhenEmptyOrCollectable()
	{
		FarmPatch bin = FarmingWorldData.getPatches(PatchImplementation.BIG_COMPOST).get(0);
		availability.setAvailable(bin, true);
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.COMPOST))))
			.thenReturn(true);
		Set<PatchImplementation> types = EnumSet.of(PatchImplementation.BIG_COMPOST);

		stateStore.recordVarbit(bin, 62, bin.getImplementation().forVarbitValue(62));
		assertEquals("finished compost is worth the trip", 1, planner.previewStops(types).size());

		stateStore.recordVarbit(bin, 0, bin.getImplementation().forVarbitValue(0));
		assertEquals("an empty bin routes with no fill chosen at all",
			1, planner.previewStops(types).size());

		stateStore.recordVarbit(bin, 40, bin.getImplementation().forVarbitValue(40));
		assertEquals("a part-filled bin still has room, so it routes too",
			1, planner.previewStops(types).size());

		stateStore.recordVarbit(bin, 97, bin.getImplementation().forVarbitValue(97));
		assertEquals("a closed bin still composting is the one that does not",
			0, planner.previewStops(types).size());
	}

	/**
	 * A bin beside the allotments is never a reason to travel, in any state.
	 *
	 * <h2>The whole point of the split</h2>
	 *
	 * There is no bank near any of the seven — Catherby ~23 tiles, Falador ~65, Ardougne ~96, and
	 * three of them have no seeded bank at all — so arriving at one with nothing to put in it is a
	 * wasted trip, and carrying fifteen un-noted items to it across the map is what the change came
	 * from. Even finished compost sitting in one does not earn a journey of its own.
	 */
	@Test
	public void anAllotmentBinNeverEarnsAStopOfItsOwn()
	{
		FarmPatch bin = FarmingWorldData.getPatches(PatchImplementation.COMPOST).get(0);
		availability.setAvailable(bin, true);
		when(compostRun.isFodderEnabled()).thenReturn(true);
		when(compostRun.getFodderCrops()).thenReturn(Collections.emptySet());
		Set<PatchImplementation> types = EnumSet.of(PatchImplementation.COMPOST);

		for (int varbit : new int[]{62, 0, 40})
		{
			stateStore.recordVarbit(bin, varbit, bin.getImplementation().forVarbitValue(varbit));
			assertTrue("varbit " + varbit + " should not plan a stop",
				planner.previewStops(types).isEmpty());
		}
	}

	/**
	 * ...but it is serviced at a stop the run is making anyway.
	 *
	 * <p>Falador's bin is `12083.4775` and its allotments `12083.4771`–`4774`: one region, one
	 * stop, a few tiles apart. That is what makes feeding it from the harvest free, and it is the
	 * reason the seven are opportunistic rather than dropped.
	 */
	@Test
	public void anAllotmentBinIsPickedUpAtAStopTheRunAlreadyMakes()
	{
		FarmPatch herb = patch(FALADOR_HERB);
		FarmPatch bin = null;
		for (FarmPatch candidate : FarmingWorldData.getPatches(PatchImplementation.COMPOST))
		{
			if (candidate.getRegion().getRegionId() == herb.getRegion().getRegionId())
			{
				bin = candidate;
				break;
			}
		}
		assertNotNull("fixture: Falador should carry a compost bin beside its allotments", bin);

		record(FALADOR_HERB, 43);
		availability.setAvailable(herb, true);
		availability.setAvailable(bin, true);
		stateStore.recordVarbit(bin, 0, bin.getImplementation().forVarbitValue(0));
		when(compostRun.isFodderEnabled()).thenReturn(true);
		when(compostRun.getFodderCrops()).thenReturn(Collections.emptySet());

		List<RunStop> stops = planner.previewStops(EnumSet.of(PatchImplementation.HERB));
		assertEquals(1, stops.size());
		assertTrue("the bin rides along with the stop the herbs earned",
			stops.get(0).getPatches().contains(bin));
	}

	/**
	 * Switching fodder on mid-run still gets the bins serviced.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * The bins were put into stops by {@code planStops}, which runs at {@code start} and never
	 * again — so a player who ticked "fill bins from your harvest" after pressing Start got
	 * nothing for the rest of the trip, silently. Reported from play: nineteen watermelons picked
	 * at Ardougne, its bin empty, watermelon on the fodder list, and no fill ever offered.
	 */
	@Test
	public void aBinJoinsAStopWhenFodderIsSwitchedOnMidRun()
	{
		FarmPatch herb = patch(FALADOR_HERB);
		FarmPatch bin = binBeside(herb);

		record(FALADOR_HERB, 43);
		availability.setAvailable(herb, true);
		availability.setAvailable(bin, true);
		stateStore.recordVarbit(bin, 0, bin.getImplementation().forVarbitValue(0));

		// Off when the run is planned, which is the whole point.
		when(compostRun.isFodderEnabled()).thenReturn(false);
		planner.start(EnumSet.of(PatchImplementation.HERB));
		assertFalse("fixture: the bin is not in the plan",
			planner.getStops().get(0).getPatches().contains(bin));

		when(compostRun.isFodderEnabled()).thenReturn(true);
		when(compostRun.getFodderCrops()).thenReturn(Collections.emptySet());
		planner.reviewBins();

		assertTrue("the bin joins the stop the run is already making",
			planner.getStops().get(0).getPatches().contains(bin));
	}

	/** It still never creates a stop of its own, however the poll is timed. */
	@Test
	public void thePollStillDoesNotLetABinEarnAStop()
	{
		FarmPatch bin = FarmingWorldData.getPatches(PatchImplementation.COMPOST).get(0);
		availability.setAvailable(bin, true);
		stateStore.recordVarbit(bin, 0, bin.getImplementation().forVarbitValue(0));
		when(compostRun.isFodderEnabled()).thenReturn(true);
		when(compostRun.getFodderCrops()).thenReturn(Collections.emptySet());

		record(FALADOR_HERB, 43);
		availability.setAvailable(patch(FALADOR_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB));
		int before = planner.getStops().size();

		planner.reviewBins();

		assertEquals("no new stop, ever", before, planner.getStops().size());
	}

	/** The compost bin sharing a region with this patch. */
	private static FarmPatch binBeside(FarmPatch patch)
	{
		for (FarmPatch candidate : FarmingWorldData.getPatches(PatchImplementation.COMPOST))
		{
			if (candidate.getRegion().getRegionId() == patch.getRegion().getRegionId())
			{
				return candidate;
			}
		}
		throw new AssertionError("no compost bin beside " + patch.getDisplayName());
	}

	/** With fodder off, the seven are not the run's concern at all. */
	@Test
	public void fodderOffLeavesTheAllotmentBinsAlone()
	{
		FarmPatch herb = patch(FALADOR_HERB);
		FarmPatch bin = null;
		for (FarmPatch candidate : FarmingWorldData.getPatches(PatchImplementation.COMPOST))
		{
			if (candidate.getRegion().getRegionId() == herb.getRegion().getRegionId())
			{
				bin = candidate;
				break;
			}
		}
		assertNotNull(bin);

		record(FALADOR_HERB, 43);
		availability.setAvailable(herb, true);
		availability.setAvailable(bin, true);
		stateStore.recordVarbit(bin, 0, bin.getImplementation().forVarbitValue(0));
		when(compostRun.isFodderEnabled()).thenReturn(false);

		List<RunStop> stops = planner.previewStops(EnumSet.of(PatchImplementation.HERB));
		assertEquals(1, stops.size());
		assertFalse(stops.get(0).getPatches().contains(bin));
	}

	/**
	 * A bin the run never goes near is not packed for either.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"should not prompt for volcanic ash if I don't have compost checked for the run"</i> —
	 * with the session log showing <b>Volcanic ash x175</b> on a run over
	 * {@code types [TREE, FRUIT_TREE]}. A hundred and seventy-five is seven bins at twenty-five,
	 * which is every allotment bin in the game: {@code binWork} asked availability for them
	 * rather than asking the run, so it counted bins the route passes nowhere near. The bins
	 * themselves obey {@link #anAllotmentBinNeverEarnsAStopOfItsOwn} — it was only the bank list
	 * that had not heard.
	 */
	@Test
	public void anAllotmentBinTheRunNeverReachesIsNotPackedFor()
	{
		FarmPatch bin = patch(FALADOR_BIN);
		record(FALADOR_BIN, readySupercompostValue());
		availability.setAvailable(bin, true);

		// The run is somewhere else entirely, which is the whole of the complaint.
		record(ARDOUGNE_HERB, 43);
		availability.setAvailable(patch(ARDOUGNE_HERB), true);
		when(compostRun.isFodderEnabled()).thenReturn(true);
		when(compostRun.getFodderCrops()).thenReturn(Collections.emptySet());

		RunPlanner.BinWork work = planner.binWork(EnumSet.of(PatchImplementation.HERB));
		assertEquals("no ash for a bin the run does not visit", 0, work.ashNeeded);
		assertEquals("and no buckets to empty it with", 0, work.readyBins);
	}

	/** ...but the same bin at a stop the run is making is packed for in full. */
	@Test
	public void anAllotmentBinAtAStopTheRunMakesIsPackedFor()
	{
		FarmPatch bin = patch(FALADOR_BIN);
		record(FALADOR_BIN, readySupercompostValue());
		availability.setAvailable(bin, true);

		record(FALADOR_HERB, 43);
		availability.setAvailable(patch(FALADOR_HERB), true);
		when(compostRun.isFodderEnabled()).thenReturn(true);
		when(compostRun.getFodderCrops()).thenReturn(Collections.emptySet());

		RunPlanner.BinWork work = planner.binWork(EnumSet.of(PatchImplementation.HERB));
		assertEquals("the bin is at the herbs' own stop, so its ash is worth a slot",
			com.dooglemaps.data.CompostBin.NORMAL.ashNeeded(), work.ashNeeded);
		assertEquals(1, work.readyBins);
		assertEquals("its fill is still the harvest's job, never the bank's", 0, work.fillItems);
	}

	/** A bin varbit meaning "finished, and what is in it is supercompost" — the ash's one target. */
	private int readySupercompostValue()
	{
		FarmPatch bin = patch(FALADOR_BIN);
		for (int value = 0; value < 256; value++)
		{
			ProduceState decoded = bin.getImplementation().forVarbitValue(value);
			if (decoded != null && decoded.getProduce() == Produce.SUPERCOMPOST
				&& decoded.getCropState() == com.dooglemaps.data.CropState.HARVESTABLE)
			{
				return value;
			}
		}
		throw new AssertionError("no finished-supercompost varbit decodes for the Falador bin");
	}

	/**
	 * A ready bin's fill is the NEXT bank visit's problem, never this one's.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"I'm getting prompted to take watermelons out for the big bin before emptying it
	 * first"</i> — and the two cannot share a pack: emptying wants free slots for the buckets,
	 * the fill is thirty un-noted items, so the player had to bank the fill again at the guild
	 * to make room for the emptying the guide should have led with.
	 *
	 * <p>The bin still counts as fillable — it is about to be — so the "pick a fill" warning
	 * and the worth-visiting answer keep seeing it. Only the items wait for the varbit to
	 * actually read empty.
	 */
	@org.junit.Test
	public void aReadyBigBinsFillWaitsForTheEmptying()
	{
		com.dooglemaps.data.FarmPatch bin = com.dooglemaps.data.FarmingWorldData
			.getPatches(PatchImplementation.BIG_COMPOST).get(0);
		availability.setAvailable(bin, true);
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.COMPOST))))
			.thenReturn(true);
		when(compostRun.isFodderEnabled()).thenReturn(false);
		int ready = readyBigBinValue(bin);
		stateStore.recordVarbit(bin, ready, bin.getImplementation().forVarbitValue(ready));

		RunPlanner.BinWork work =
			planner.binWork(EnumSet.of(PatchImplementation.BIG_COMPOST));
		assertEquals("the emptying is this visit's work", 1, work.readyBins);
		// One per compost still in it — the exact count is the stage decode's business.
		assertTrue("buckets are still asked for", work.readyBuckets > 0);
		assertEquals("still fillable, so the pick-a-fill warning stands", 1, work.fillableBins);
		assertEquals("but no fill items until the compost is out", 0, work.fillItems);
	}

	/** ...and the moment the varbit reads empty, the fill is asked for in full. */
	@org.junit.Test
	public void anEmptiedBigBinAsksForItsFill()
	{
		com.dooglemaps.data.FarmPatch bin = com.dooglemaps.data.FarmingWorldData
			.getPatches(PatchImplementation.BIG_COMPOST).get(0);
		availability.setAvailable(bin, true);
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.COMPOST))))
			.thenReturn(true);
		when(compostRun.isFodderEnabled()).thenReturn(false);
		stateStore.recordVarbit(bin, 0, bin.getImplementation().forVarbitValue(0));

		RunPlanner.BinWork work =
			planner.binWork(EnumSet.of(PatchImplementation.BIG_COMPOST));
		assertEquals(0, work.readyBins);
		assertEquals(1, work.fillableBins);
		assertEquals(com.dooglemaps.data.CompostBin.BIG.getCapacity(), work.fillItems);
	}

	/**
	 * A finished stop with a supply point in its own region collects before moving on.
	 *
	 * <h2>The reported dead end, twice in one session</h2>
	 *
	 * <i>"never got routed back to the bank for watermelons to the big bin, I did it anyways and
	 * then it started to realize it"</i>, then <i>"just filled it and now its sending me to
	 * ardougne instead of to get the rest of my melons."</i> Mid-run supplies had exactly one
	 * collector — {@code divertForSupplies}, which fires only when the run would otherwise end —
	 * so the run walked away from the guild's own chest with the bin's fill inside it.
	 *
	 * <p>The whole lifecycle is walked because each leg of it is a gate with a reason: the
	 * starting supply leg ends first (this is about supplies going outstanding <b>mid-run</b>),
	 * and the mid-work check is the anti-yank rule — the fill row opens the moment the emptied
	 * bin frees the slots, stood at the bin with work left, and nothing may be interrupted.
	 */
	@org.junit.Test
	public void aFinishedStopWithASupplyPointHereCollectsBeforeMovingOn()
	{
		com.dooglemaps.data.FarmPatch bin = readyGuildBinRun();

		// The starting supply leg runs its course: nothing to collect, so it ends at once.
		org.junit.Assert.assertTrue("a bin run starting at the guild opens at its chest",
			planner.isAtBankLeg());
		planner.leaveBank();
		org.junit.Assert.assertFalse("nothing outstanding, so the leg ends",
			planner.isAtBankLeg());

		// The fill goes outstanding mid-run — the guide pushes this flag every tick — but the
		// stop still has work, so nothing may be interrupted.
		planner.setWithdrawOutstanding(true);
		planner.reviewNearbySupplies();
		org.junit.Assert.assertFalse("mid-work is never yanked to a bank",
			planner.isAtBankLeg());

		// The stop finishes — the bin exempted is how the guide reports it unactionable.
		planner.setNothingToDo(java.util.Collections.singleton(bin.getKey()));
		planner.reviewNearbySupplies();
		org.junit.Assert.assertTrue(
			"done here, wanting supplies, chest in this region: collect them now",
			planner.isAtBankLeg());
	}

	/** Without a withdrawal outstanding, a finished stop routes onward exactly as before. */
	@org.junit.Test
	public void aFinishedStopWithNothingOutstandingIsNotSentToTheBank()
	{
		com.dooglemaps.data.FarmPatch bin = readyGuildBinRun();
		planner.leaveBank();

		planner.setNothingToDo(java.util.Collections.singleton(bin.getKey()));
		planner.reviewNearbySupplies();

		org.junit.Assert.assertFalse(planner.isAtBankLeg());
	}

	/** A run over the guild's ready big bin, with the player standing in the guild. */
	private com.dooglemaps.data.FarmPatch readyGuildBinRun()
	{
		com.dooglemaps.data.FarmPatch bin = com.dooglemaps.data.FarmingWorldData
			.getPatches(PatchImplementation.BIG_COMPOST).get(0);
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.COMPOST))))
			.thenReturn(true);
		availability.setAvailable(bin, true);
		// Ready, which is what makes the bin actionable and the stop exist — and the state the
		// report started from: the emptying is what puts the fill in play.
		int ready = readyBigBinValue(bin);
		stateStore.recordVarbit(bin, ready, bin.getImplementation().forVarbitValue(ready));
		standingIn(4922);

		planner.start(EnumSet.of(PatchImplementation.BIG_COMPOST));
		org.junit.Assert.assertEquals("the guild stop must exist, or these prove nothing",
			1, planner.getStops().size());
		return bin;
	}

	/** A big-bin varbit meaning "finished compost, ready to scoop", whatever the tier. */
	private static int readyBigBinValue(com.dooglemaps.data.FarmPatch bin)
	{
		for (int value = 0; value < 256; value++)
		{
			ProduceState decoded = bin.getImplementation().forVarbitValue(value);
			if (decoded != null
				&& decoded.getCropState() == com.dooglemaps.data.CropState.HARVESTABLE)
			{
				return value;
			}
		}
		throw new AssertionError("no finished varbit decodes for the big bin");
	}

	/**
	 * The bin-type fold lives here now, so it is tested here.
	 *
	 * <h2>Why the fold has to happen inside</h2>
	 *
	 * The run list offers one "Compost bin" line carrying the ordinary bin's type, and the guild
	 * has no ordinary bin — only the big one. Asking {@code binWork} about the type on the
	 * checkbox therefore matches nothing in the guild, which is the same trap
	 * {@code CompostBin.coveredByTheBinTick} exists for elsewhere.
	 *
	 * <p>This assertion used to live in {@code CompostBinWarningTest}, where the panel did the
	 * fold for itself before calling the planner from the Swing thread. The call moved onto the
	 * snapshot to get off that thread; the fold moved with it, and so does its test.
	 */
	@org.junit.Test
	public void fillableBinsInFoldsTheTickToTheGuildsBigBin()
	{
		com.dooglemaps.data.FarmPatch bin = com.dooglemaps.data.FarmingWorldData
			.getPatches(PatchImplementation.BIG_COMPOST).get(0);
		availability.setAvailable(bin, true);
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.COMPOST))))
			.thenReturn(true);
		// Empty, so there is room to put produce in.
		stateStore.recordVarbit(bin, 0, bin.getImplementation().forVarbitValue(0));

		java.util.Set<PatchImplementation> ticked = EnumSet.of(PatchImplementation.COMPOST);
		org.junit.Assert.assertEquals("the tick alone names no bin the guild has",
			0, planner.binWork(ticked).fillableBins);
		org.junit.Assert.assertEquals("...and folded, it finds the empty big bin standing there",
			1, planner.fillableBinsIn(ticked));
	}

	/** A selection with no bin in it answers zero without asking about bins at all. */
	@org.junit.Test
	public void fillableBinsInIsZeroForASelectionWithNoBin()
	{
		org.junit.Assert.assertEquals(0,
			planner.fillableBinsIn(EnumSet.of(PatchImplementation.HERB)));
	}

	/**
	 * A bins-only run with just the Farming Guild enabled finds the guild's bin.
	 *
	 * <h2>The guild has no ordinary compost bin — only the big one</h2>
	 *
	 * The run list offers a single "Compost bin" line carrying the ordinary bin's type, and the
	 * fold to both sizes lived in RunTypeStore.getSelected alone. Anything building its own set
	 * from the checkboxes — the panel's preview, its destination list, and the run it starts —
	 * therefore planned over COMPOST only, matched no patch anywhere in the guild, and reported
	 * nothing to do while the player stood at the guild bank looking at the bin. Reported from
	 * play. CompostBin.coveredByTheBinTick is the shared answer; this pins the case it exists for.
	 */
	@org.junit.Test
	public void aGuildOnlyBinRunFindsTheBigBin()
	{
		com.dooglemaps.data.FarmPatch bigBin = null;
		for (com.dooglemaps.data.FarmPatch patch
			: com.dooglemaps.data.FarmingWorldData.getPatches(PatchImplementation.BIG_COMPOST))
		{
			bigBin = patch;
			break;
		}
		org.junit.Assert.assertNotNull("the guild's big bin is missing from the world data",
			bigBin);
		org.junit.Assert.assertTrue("this test is about the guild",
			bigBin.getRegion().getName().contains("Farming Guild"));

		availability.setAvailable(bigBin, true);
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.COMPOST))))
			.thenReturn(true);
		stateStore.recordVarbit(bigBin, 62, bigBin.getImplementation().forVarbitValue(62));

		// Exactly what a checkbox reports: the line's own type, and nothing else.
		java.util.Set<PatchImplementation> ticked = EnumSet.of(PatchImplementation.COMPOST);
		org.junit.Assert.assertEquals("the tick alone matches no patch in the guild",
			0, planner.previewStops(ticked).size());
		org.junit.Assert.assertEquals("...and widened, it finds the bin standing there",
			1, planner.previewStops(
				com.dooglemaps.data.CompostBin.coveredByTheBinTick(ticked)).size());
	}

	/**
	 * Ticks the contract's own run line, which reviewContract now requires.
	 *
	 * <p>The contract is a run option like any other, and adopting its patch is something the
	 * player asks for rather than something an assignment imposes — see
	 * RunPlanner.contractIsInTheRun.
	 */
	/**
	 * A contract's crop does not park the plot it is standing in.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"our group rule about not harvesting allotment/herb/flower patches until they're all
	 * ready should not apply in the farming guild where one of them was a contract I just
	 * completed, this caused me to skip the flower/allotment patches."</i>
	 *
	 * <p>Completing a contract plants the next crop Jane asked for, so the guild's plot acquires
	 * a freshly sown patch at a moment nothing else there chose. {@code clusterHeld} read it as
	 * any other growing crop and parked the whole plot behind it — and a contract patch will
	 * essentially never ripen in step with the plot, so the ready flower and allotments beside it
	 * would go on being skipped for as long as the contract ran.
	 *
	 * <p>The hold is a bargain between patches sharing a growth cycle. A contract keeps Jane's
	 * clock, so it is not a party to it.
	 */
	@Test
	public void aContractCropDoesNotHoldTheGuildPlot()
	{
		when(pluginConfig.holdClustersUntilReady()).thenReturn(true);
		standingIn(VARROCK_REGION);

		FarmPatch herb = guildPatch(PatchImplementation.HERB);
		FarmPatch flower = guildPatch(PatchImplementation.FLOWER);
		assertNotNull("the guild has no herb patch in the data", herb);
		assertNotNull("the guild has no flower patch in the data", flower);

		// The flower is ready to pick; the herb has the contract's crop freshly in the ground.
		record(flower.getKey(), readyFlowerValue(flower));
		record(herb.getKey(), justPlantedValue(herb));
		availability.setAvailable(flower, true);
		availability.setAvailable(herb, true);

		// Fixture: as a plain ticked herb, the growing crop DOES hold the plot. That is the
		// setting working, and it is what makes the contract case a change rather than a no-op.
		//
		// Both halves of the tick are needed once groupFor is stubbed: inTheRun stops taking its
		// null-group shortcut and starts asking runOptions, which a bare mock answers "no" to.
		when(groups.groupFor(herb))
			.thenReturn(com.dooglemaps.data.PlantingGroup.of(PatchImplementation.HERB));
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.HERB)))).thenReturn(true);
		assertTrue("fixture: an ordinary growing herb holds its plot",
			planner.start(EnumSet.of(PatchImplementation.HERB, PatchImplementation.FLOWER))
				.stream().noneMatch(this::isGuild));

		// The same patch, the same crop, now Jane's.
		when(groups.groupFor(herb))
			.thenReturn(com.dooglemaps.data.PlantingGroup.contract(PatchImplementation.HERB));
		tickTheContract(PatchImplementation.HERB);

		assertTrue("the ready flower beside it is not skipped for a contract's clock",
			planner.start(EnumSet.of(PatchImplementation.HERB, PatchImplementation.FLOWER))
				.stream().anyMatch(this::isGuild));
	}

	private boolean isGuild(RunStop stop)
	{
		return stop.getRegion().getRegionId()
			== guildPatch(PatchImplementation.HERB).getRegion().getRegionId();
	}

	/** A flower varbit meaning "grown and ready to pick", decoded rather than assumed. */
	private int readyFlowerValue(FarmPatch patch)
	{
		for (int value = 0; value < 256; value++)
		{
			ProduceState decoded = patch.getImplementation().forVarbitValue(value);
			if (decoded != null && decoded.getProduce() != null
				&& decoded.getProduce().isCrop()
				&& decoded.getCropState() == com.dooglemaps.data.CropState.HARVESTABLE)
			{
				return value;
			}
		}
		throw new AssertionError("no harvestable varbit decodes for " + patch);
	}

	private void tickTheContract(PatchImplementation type)
	{
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.contract(type)))).thenReturn(true);
	}

	/**
	 * A contract nobody ticked is not adopted, and sends nobody to a bank.
	 *
	 * <p>Reported from play twice, and the log named it: a compost-only run at the Farming
	 * Guild logged "Contract needs collecting for; routing to a supply point" with an empty
	 * seed list. reviewContract bypasses inTheRun by design — its job is to adopt a patch the
	 * plan does not have — so it was the one contract path that never consulted the tick.
	 */
	@org.junit.Test
	public void anUntickedContractIsNotAdoptedMidRun()
	{
		FarmPatch tree = guildPatch(PatchImplementation.TREE);
		org.junit.Assert.assertNotNull(tree);
		record(tree.getKey(), grownUnchecked(tree));
		availability.setAvailable(tree, true);

		FarmPatch herb = guildPatch(PatchImplementation.HERB);
		org.junit.Assert.assertNotNull(herb);
		record(herb.getKey(), 3);
		availability.setAvailable(herb, true);

		planner.start(EnumSet.of(PatchImplementation.HERB));
		RunStop guild = planner.getRemaining().stream()
			.filter(stop -> stop.getRegion().getRegionId() == herb.getRegion().getRegionId())
			.findFirst()
			.orElseThrow(() -> new AssertionError("no guild stop was planned"));

		// Jane has one assigned, and the player has not ticked it.
		when(groups.contractCrop()).thenReturn(Produce.YEW);
		when(groups.groupFor(tree))
			.thenReturn(com.dooglemaps.data.PlantingGroup.contract(PatchImplementation.TREE));
		planner.reviewContract();

		org.junit.Assert.assertFalse(
			"an unticked contract must not pull its patch into the run",
			guild.getPatches().contains(tree));
		org.junit.Assert.assertFalse("nor widen the run's types to match",
			planner.coveredTypes().contains(PatchImplementation.TREE));
	}

	/**
	 * A coral run is routed to the steps, from anywhere.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * The divert used to apply only while the player stood in the steps' own region — the one
	 * place it buys nothing — so the entire journey there was routed to the patch instead. A
	 * seabed patch has no learned location and never gets one, so {@code PatchLocationStore}
	 * falls through to the middle of the region: tile (3168, 2400), which in the world is a
	 * table on the Great Conch's deck. Reported from play, in those words.
	 */
	@Test
	public void aCoralRunIsRoutedToTheStepsRatherThanTheRegionCentre()
	{
		FarmPatch coral = coralPatch();
		availability.setAvailable(coral, true);
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.CORAL)))).thenReturn(true);
		record(coral.getKey(), 0);

		standingIn(FALADOR_REGION);
		planner.start(EnumSet.of(PatchImplementation.CORAL));
		planner.leaveBank();

		com.dooglemaps.data.UnderwaterApproach.Approach steps =
			com.dooglemaps.data.UnderwaterApproach.forType(PatchImplementation.CORAL);
		assertNotNull(steps);
		assertTrue("the steps are what the router is given: " + lastTargets(),
			lastTargets().contains(steps.getPoint()));
		assertFalse("and the deck of the ship is not",
			regionsTargeted().contains(coral.getRegion().getRegionId()));
	}

	/**
	 * Standing among the nurseries, there is no route at all - not one back up the steps.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * The nurseries are region 13194 and their stop is filed under the Great Conch at 12581, so
	 * every "am I there yet" test compared the wrong pair of numbers and answered no. The run
	 * then did the only thing left: it routed the player back UP to the steps they had just
	 * walked down, while the patches sat in front of them. Reported from play, and the region
	 * read straight off the {@code Run planned:} line in client.log.
	 */
	@Test
	public void theDivertStopsOnceThePlayerIsAtTheNurseries()
	{
		FarmPatch coral = coralPatch();
		availability.setAvailable(coral, true);
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.CORAL)))).thenReturn(true);
		record(coral.getKey(), 0);

		planner.start(EnumSet.of(PatchImplementation.CORAL));
		planner.leaveBank();
		standingIn(CORAL_NURSERIES_REGION);
		planner.retarget();

		assertTrue("work underfoot means no route: " + lastTargets(), lastTargets().isEmpty());
	}

	/** And the deck it is filed under still counts, which is where the calquat stands. */
	@Test
	public void theDivertAlsoStopsOnTheDeck()
	{
		FarmPatch coral = coralPatch();
		availability.setAvailable(coral, true);
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.CORAL)))).thenReturn(true);
		record(coral.getKey(), 0);

		planner.start(EnumSet.of(PatchImplementation.CORAL));
		planner.leaveBank();
		standingIn(coral.getRegion().getRegionId());
		planner.retarget();

		assertTrue(lastTargets().isEmpty());
	}

	/** The stop claims both its own ground and the seabed below it, and nothing else. */
	@Test
	public void theGreatConchStopClaimsTheSeabedToo()
	{
		FarmPatch coral = coralPatch();
		availability.setAvailable(coral, true);
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.CORAL)))).thenReturn(true);
		record(coral.getKey(), 0);
		planner.start(EnumSet.of(PatchImplementation.CORAL));

		RunStop conch = planner.getRemaining().get(0);
		assertTrue("the deck it is filed under",
			conch.claimsRegion(coral.getRegion().getRegionId()));
		assertTrue("and the seabed its patches are on",
			conch.claimsRegion(CORAL_NURSERIES_REGION));
		assertFalse("but not the shore the steps are on - that is travel, not arrival",
			conch.claimsRegion(13094));
		assertFalse("nor anywhere else", conch.claimsRegion(VARROCK_REGION));
	}

	/**
	 * A run begun among the nurseries does not open at a bank.
	 *
	 * <p>The compost bins' fill is a withdrawal like any other, and "standing on work beats
	 * going shopping" is meant to defer the trip until the stop underfoot is done. It could not:
	 * {@code standingAtAStop} compared 13194 against 12581, decided the player was standing on
	 * nothing, and sent them off to a bank with weedy coral patches in front of them. Reported
	 * from play, with the loadout asking for sixteen pineapples and fifty volcanic ash.
	 */
	@Test
	public void aRunBegunAtTheNurseriesDefersTheBankTrip()
	{
		FarmPatch coral = coralPatch();
		availability.setAvailable(coral, true);
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.CORAL)))).thenReturn(true);
		record(coral.getKey(), 0);

		standingIn(CORAL_NURSERIES_REGION);
		planner.start(EnumSet.of(PatchImplementation.CORAL), true);

		assertFalse("the patches underfoot come first", planner.isAtBankLeg());
	}

	/** The same run begun anywhere else does collect first, which is the rule being deferred. */
	@Test
	public void theSameRunBegunElsewhereStillBanksFirst()
	{
		FarmPatch coral = coralPatch();
		availability.setAvailable(coral, true);
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.CORAL)))).thenReturn(true);
		record(coral.getKey(), 0);

		standingIn(VARROCK_REGION);
		planner.start(EnumSet.of(PatchImplementation.CORAL), true);

		assertTrue(planner.isAtBankLeg());
	}

	/** The seabed the nurseries are on, read off client.log's "Run planned:" line. */
	private static final int CORAL_NURSERIES_REGION = 13194;

	/** The same store the planner routes through, for tests that learn a patch's tile. */
	private PatchLocationStore patchLocations;

	/** The Great Conch's calquat is on the deck, and is not routed to the seabed's steps. */
	@Test
	public void aDryPatchSharingTheStopKeepsItsOwnTargets()
	{
		FarmPatch calquat = null;
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.CALQUAT))
		{
			if (patch.getRegion().getRegionId() == coralPatch().getRegion().getRegionId())
			{
				calquat = patch;
				break;
			}
		}
		assertNotNull("fixture: the Great Conch should carry a calquat too", calquat);

		availability.setAvailable(calquat, true);
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.CALQUAT)))).thenReturn(true);
		record(calquat.getKey(), 0);

		standingIn(FALADOR_REGION);
		planner.start(EnumSet.of(PatchImplementation.CALQUAT));
		planner.leaveBank();

		com.dooglemaps.data.UnderwaterApproach.Approach steps =
			com.dooglemaps.data.UnderwaterApproach.forType(PatchImplementation.CORAL);
		assertNotNull(steps);
		assertFalse("a tree on the deck is not reached by diving: " + lastTargets(),
			lastTargets().contains(steps.getPoint()));
		assertFalse("the route targets nothing at all", lastTargets().isEmpty());
	}

	/**
	 * The steps are handed over as a ring, so the search can end beside them.
	 *
	 * <p>Shortest Path only finishes by stepping <b>onto</b> a target tile, and a set of steps
	 * leading into the sea is no more standable-on than a tree. The guess offered from play was
	 * to move the coordinates a tile west; that is the right cause and one quarter of the fix.
	 */
	@Test
	public void theStepsAreOfferedWithTheTilesAroundThem()
	{
		FarmPatch coral = coralPatch();
		availability.setAvailable(coral, true);
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.CORAL)))).thenReturn(true);
		record(coral.getKey(), 0);

		standingIn(FALADOR_REGION);
		planner.start(EnumSet.of(PatchImplementation.CORAL));
		planner.leaveBank();

		net.runelite.api.coords.WorldPoint steps =
			com.dooglemaps.data.UnderwaterApproach.forType(PatchImplementation.CORAL).getPoint();
		assertTrue("the tile west of the steps: " + lastTargets(),
			lastTargets().contains(new net.runelite.api.coords.WorldPoint(
				steps.getX() - 1, steps.getY(), steps.getPlane())));
		assertTrue("and the other three sides",
			lastTargets().contains(new net.runelite.api.coords.WorldPoint(
				steps.getX() + 1, steps.getY(), steps.getPlane()))
			&& lastTargets().contains(new net.runelite.api.coords.WorldPoint(
				steps.getX(), steps.getY() - 1, steps.getPlane()))
			&& lastTargets().contains(new net.runelite.api.coords.WorldPoint(
				steps.getX(), steps.getY() + 1, steps.getPlane())));
	}

	/**
	 * The Conch's calquat has a pin now, so it is routed to rather than dropped.
	 *
	 * <p>Given from play at the spot: (3128, 2405). Worth an assertion of its own because the
	 * tile is in region 12325 while the patch is filed under 12581 — the ship spans thirteen
	 * regions and all its varbits sit on one — so a check that the pin "lands in its own region"
	 * would reject a coordinate that is simply correct.
	 */
	@Test
	public void theConchCalquatIsRoutedToItsPinnedTile()
	{
		FarmPatch coral = coralPatch();
		FarmPatch calquat = conchCalquat();
		availability.setAvailable(coral, true);
		availability.setAvailable(calquat, true);
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.CORAL)))).thenReturn(true);
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.CALQUAT)))).thenReturn(true);
		record(coral.getKey(), 0);
		record(calquat.getKey(), 0);

		standingIn(FALADOR_REGION);
		planner.start(EnumSet.of(PatchImplementation.CORAL, PatchImplementation.CALQUAT));
		planner.leaveBank();

		assertTrue("the pinned deck tile: " + lastTargets(),
			lastTargets().contains(new net.runelite.api.coords.WorldPoint(3128, 2405, 0)));
		assertFalse("and never the region centre",
			lastTargets().contains(new net.runelite.api.coords.WorldPoint(3168, 2400, 0)));
	}

	/** Once the deck patch has been walked up to, it is a real target again. */
	@Test
	public void aLearnedDeckPatchIsRoutedToAlongsideTheSteps()
	{
		FarmPatch coral = coralPatch();
		FarmPatch calquat = conchCalquat();
		availability.setAvailable(coral, true);
		availability.setAvailable(calquat, true);
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.CORAL)))).thenReturn(true);
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.full(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.CALQUAT)))).thenReturn(true);
		record(coral.getKey(), 0);
		record(calquat.getKey(), 0);
		net.runelite.api.coords.WorldPoint deck =
			new net.runelite.api.coords.WorldPoint(3155, 2411, 0);
		patchLocations.record(calquat, deck, 1, 1);

		standingIn(FALADOR_REGION);
		planner.start(EnumSet.of(PatchImplementation.CORAL, PatchImplementation.CALQUAT));
		planner.leaveBank();

		assertTrue("the learned tile: " + lastTargets(), lastTargets().contains(deck));
	}

	/** The Great Conch's calquat, which shares its stop with the two nurseries. */
	private static FarmPatch conchCalquat()
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.CALQUAT))
		{
			if (patch.getRegion().getRegionId() == coralPatch().getRegion().getRegionId())
			{
				return patch;
			}
		}
		throw new AssertionError("fixture: the Great Conch should carry a calquat too");
	}

	// ------------------------------------------- the committed leg

	/**
	 * Three stops, so a leg has somewhere else it could have swung to.
	 *
	 * @return the region ids of the planned stops
	 */
	private Set<Integer> threeHerbStops()
	{
		for (String key : new String[]{FALADOR_HERB, CATHERBY_HERB, ARDOUGNE_HERB})
		{
			record(key, 43);
			availability.setAvailable(patch(key), true);
		}
		selection.toggle(com.dooglemaps.data.Seed.TOADFLAX);
		stockInventory(com.dooglemaps.data.Seed.TOADFLAX, 5);

		// Deliberately not one of the three. Standing in a remaining stop's region clears the
		// route outright - work underfoot beats travelling - and would mask everything below.
		standingIn(VARROCK_REGION);
		planner.start(EnumSet.of(PatchImplementation.HERB));
		planner.leaveBank();
		return regionsTargeted();
	}

	/** Somewhere with no stop of its own, so a route is actually drawn. */
	private static final int VARROCK_REGION = 12854;

	/** Next door to the Ardougne herb stop, and not a stop itself. */
	private static final int ARDOUGNE_BUSH_REGION = 10290;

	/** Undecided, the router is handed everything and picks - that is the ordering strategy. */
	@Test
	public void anUndecidedRunOffersEveryStop()
	{
		assertEquals("all three are candidates until one is chosen", 3, threeHerbStops().size());
	}

	/**
	 * Once the route names a stop, that is where the run is going.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * "Cheapest to reach" is measured from where the player is, and the run re-asked on every
	 * region change - so crossing a boundary on the way to one stop could hand the leg to
	 * another, and a few regions later hand it back. The line, the destination name, the hops
	 * and the highlighted teleport all swung round with it. Reported from play as the route and
	 * the infobox changing direction constantly with several run types ticked.
	 */
	@Test
	public void acommittedLegSurvivesTheNextRetarget()
	{
		threeHerbStops();
		int catherby = patch(CATHERBY_HERB).getRegion().getRegionId();

		planner.commitDestination(catherby);
		// The region change that used to re-open the question - and a pointed one: this is the
		// region next door to the Ardougne stop, which is exactly when "cheapest from here"
		// used to hand the leg over mid-journey. Next door rather than in it, since standing
		// in a remaining stop clears the route outright and would prove nothing.
		standingIn(ARDOUGNE_BUSH_REGION);
		planner.retarget();

		assertEquals("one leg, and it is the one already chosen",
			Collections.singleton(catherby), regionsTargeted());
	}

	/** Finishing the committed stop hands the choice back, greedily, from wherever you are. */
	@Test
	public void finishingTheCommittedStopChoosesAfresh()
	{
		threeHerbStops();
		int falador = patch(FALADOR_HERB).getRegion().getRegionId();

		planner.commitDestination(falador);
		service(FALADOR_HERB);

		assertEquals("the other two are candidates again", 2, regionsTargeted().size());
		assertFalse("and the finished one is not",
			regionsTargeted().contains(falador));
	}

	/** So does waving it past, which is the escape hatch when the pick was not the one wanted. */
	@Test
	public void skippingTheCommittedStopChoosesAfresh()
	{
		threeHerbStops();
		int catherby = patch(CATHERBY_HERB).getRegion().getRegionId();

		planner.commitDestination(catherby);
		planner.skipRegion(catherby);

		assertEquals(2, regionsTargeted().size());
		assertFalse(regionsTargeted().contains(catherby));
	}

	/**
	 * A route that names nowhere is not a decision to forget the one already made.
	 *
	 * <p>An unnamed destination is the ordinary state for a second or two after every request -
	 * Shortest Path may echo every target it was handed rather than name its pick - so treating
	 * it as "undecided" would put the run straight back to re-choosing on every reply.
	 */
	@Test
	public void anUnnamedRouteDoesNotReleaseTheLeg()
	{
		threeHerbStops();
		int catherby = patch(CATHERBY_HERB).getRegion().getRegionId();

		planner.commitDestination(catherby);
		planner.commitDestination(-1);
		planner.retarget();

		assertEquals(Collections.singleton(catherby), regionsTargeted());
	}

	/**
	 * A tool the withdraw list wants mid-run earns a trip back, even if ToolNeeds cannot see it.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * {@code reviewSupplies} asked {@code ToolNeeds.anyOnlyInBank} and nothing else, and the
	 * <b>axe has never been a ToolNeeds tool</b> — it is a {@code RunLoadout} row. So a contract
	 * taken from Jane for a patch still holding last run's tree left the player at the Farming
	 * Guild being told to chop something they had nothing to chop with, and no trip back was ever
	 * offered. Reported from play, along with the forestry basket the logs need.
	 */
	@Test
	public void aToolTheWithdrawListWantsMidRunDivertsToABank()
	{
		record(FALADOR_HERB, 43);
		availability.setAvailable(patch(FALADOR_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB));
		planner.leaveBank();
		assertFalse("nothing was owed at the start", planner.isAtBankLeg());

		// ToolNeeds still says no - the axe is not one of its tools, which is the whole point.
		when(tools.anyOnlyInBank(Mockito.any())).thenReturn(false);
		planner.setToolOutstanding(true);
		planner.reviewSupplies();

		assertTrue("an axe in the bank is a reason to go back for it", planner.isAtBankLeg());
	}

	/** A seed or a payment is not: the run can press on and skip that patch. */
	@Test
	public void anOrdinaryWithdrawalDoesNotDivertMidRun()
	{
		record(FALADOR_HERB, 43);
		availability.setAvailable(patch(FALADOR_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB));
		planner.leaveBank();

		when(tools.anyOnlyInBank(Mockito.any())).thenReturn(false);
		planner.setWithdrawOutstanding(true);
		planner.setToolOutstanding(false);
		planner.reviewSupplies();

		assertFalse("standing on work still beats going shopping for a seed",
			planner.isAtBankLeg());
	}

	/** And a waived leg outranks it, as it does every other diversion. */
	@Test
	public void aWaivedLegIsNotReopenedByAMissingTool()
	{
		record(FALADOR_HERB, 43);
		availability.setAvailable(patch(FALADOR_HERB), true);
		planner.start(EnumSet.of(PatchImplementation.HERB));
		planner.waiveBankLeg();

		planner.setToolOutstanding(true);
		planner.reviewSupplies();

		assertFalse("no means no for the rest of the run", planner.isAtBankLeg());
	}

	/**
	 * The committed stop is nameable even when the router has said nothing at all.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * The destination name is inferred from Shortest Path's reply, and the reply is briefly
	 * unavailable more often than it looks: wiped whenever a route is re-asked, discarded outright
	 * while the player is instanced, and sometimes carrying hops with no landing worth matching.
	 * Harmless until the nexus row and the jewellery box line began matching <b>by destination</b>
	 * - then a nameless leg silently stops highlighting the thing you are meant to click.
	 * Reported as the Catherby teleport dropping out of the infobox and the nexus list on the way
	 * into the house, with the log showing "(unnamed leg)" five seconds before the house was even
	 * entered.
	 */
	@Test
	public void aCommittedLegIsStillNameableWithNoRouteReply()
	{
		threeHerbStops();
		int catherby = patch(CATHERBY_HERB).getRegion().getRegionId();
		planner.commitDestination(catherby);

		RunStop named = planner.committedStop();
		assertNotNull("the run knows where it is going without being told again", named);
		assertEquals(catherby, named.getRegion().getRegionId());
	}

	/** It goes quiet the moment that stop is done, so the next leg is chosen fresh. */
	@Test
	public void aFinishedStopIsNoLongerTheCommittedOne()
	{
		threeHerbStops();
		int falador = patch(FALADOR_HERB).getRegion().getRegionId();
		planner.commitDestination(falador);
		assertNotNull(planner.committedStop());

		service(FALADOR_HERB);

		assertNull("a finished stop cannot be where the run is heading",
			planner.committedStop());
	}

	/** And it says nothing on the supply leg, whose destination is a bank rather than a stop. */
	@Test
	public void theSupplyLegIsNotNamedFromTheCommitment()
	{
		threeHerbStops();
		planner.commitDestination(patch(CATHERBY_HERB).getRegion().getRegionId());
		planner.setToolOutstanding(true);
		planner.reviewSupplies();

		assertTrue("fixture: the run should be collecting", planner.isAtBankLeg());
		assertNull(planner.committedStop());
	}

	/** Falador, for standing somewhere that is emphatically not a shore. */
	private static final int FALADOR_REGION = 12083;

	private static FarmPatch coralPatch()
	{
		FarmPatch coral = FarmingWorldData.getPatches(PatchImplementation.CORAL).get(0);
		assertNotNull(coral);
		return coral;
	}

	/** The nursery objects are known by id, since nothing on the seabed carries the varbit. */
	@org.junit.Test
	public void theNurseryObjectsAreKnownById()
	{
		int[] objects = com.dooglemaps.data.UnderwaterApproach.objectsFor(
			PatchImplementation.CORAL);

		org.junit.Assert.assertEquals("both nurseries, which cannot be told apart",
			2, objects.length);
		org.junit.Assert.assertEquals(0,
			com.dooglemaps.data.UnderwaterApproach.objectsFor(PatchImplementation.HERB).length);
	}
}
