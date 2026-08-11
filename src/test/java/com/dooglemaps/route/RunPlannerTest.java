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
		com.dooglemaps.data.PlantingGroup contractGroup =
			com.dooglemaps.data.PlantingGroup.contract(PatchImplementation.TREE);
		when(groups.groupFor(tree)).thenReturn(contractGroup);
		// The player has ticked Farming contract, which is what puts the contract's patch in the
		// run at all - see inTheRun. Without it the guide says so rather than silently planting.
		when(runOptions.isSelected(com.dooglemaps.data.RunOption.full(contractGroup)))
			.thenReturn(true);
		contracts.recordAssigned(Produce.YEW);
		planner.reviewContract();

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
		standingIn(patch(ARDOUGNE_HERB).getRegion().getRegionId());

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
		standingIn(patch(ARDOUGNE_HERB).getRegion().getRegionId());

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

	@Test
	public void seedsInTheVaultSendYouToTheFarmingGuildNotTheNearestBank()
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
}
