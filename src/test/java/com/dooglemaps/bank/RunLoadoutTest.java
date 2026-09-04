package com.dooglemaps.bank;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.FarmingTool;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.data.Seed;
import com.dooglemaps.guide.CarriedItems;
import com.dooglemaps.route.BankLocationStore;
import com.dooglemaps.route.PatchLocationStore;
import com.dooglemaps.route.RunPlanner;
import com.dooglemaps.route.ShortestPathIntegration;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.state.CompostSelectionStore;
import com.dooglemaps.state.LeprechaunStore;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.state.SeedSelectionStore;
import com.dooglemaps.timer.FarmingOutfit;
import com.dooglemaps.timer.GrowthTimer;
import com.google.gson.Gson;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Covers what the bank loadout suggests, and — more importantly — what it declines to.
 *
 * <p>The failure mode worth guarding against is not omission but noise: a list that names
 * everything a farm run touches would send you hunting for compost the leprechaun already
 * holds a thousand of, and for teleports you do not own.
 */
public class RunLoadoutTest
{
	private static final Set<PatchImplementation> HERBS = EnumSet.of(PatchImplementation.HERB);

	private final java.util.Map<String, Object> stored = new java.util.HashMap<>();

	private PatchStateStore patches;
	private AvailabilityProfile availability;
	private SeedSelectionStore selection;
	private CompostSelectionStore compost;
	private CarriedItems carried;
	private BankContents bank;
	private RunLoadout loadout;

	/** Stubbed per test where a group is being visited for its harvest alone. */
	private com.dooglemaps.state.RunTypeStore runTypes;

	/** Stubbed per test where a farming contract is in play. */
	private com.dooglemaps.state.ContractState contracts;

	/** The grouper the planner uses; stubbed where a contract group is the point. */
	private com.dooglemaps.state.PlantingGroups groups;

	/** Stubbed per test where the bin run's fill or ash choice matters. */
	private com.dooglemaps.state.CompostRunStore compostRun;

	/** The planner the loadout is built on, for the tests that ask where a run would go. */
	private RunPlanner planner;

	/** The planner's own run-options mock, so a test can tick the compost line for routing. */
	private com.dooglemaps.state.RunTypeStore plannerRunOptions;

	/** The boats' cargo holds, empty until a test stows something. */
	private com.dooglemaps.bank.BoatHolds boatHolds;

	/** A second loadout whose tick never advances; see the note in setUp. */
	private RunLoadout frozenLoadout;
	private SeedInventoryStore seeds;
	private com.dooglemaps.DoogleMapsConfig config;

	/** Item names the stubbed cache will answer with, so a test can name what it banked. */
	private final java.util.Map<Integer, String> names = new java.util.HashMap<>();
	private com.dooglemaps.state.ProtectionSelectionStore protection;

	/** Which standing crops the run's fixture has said to buy a gardener out of clearing. */
	private com.dooglemaps.state.PayToClearStore payToClear;

	/**
	 * The leprechaun's store, empty to begin with and stocked per test.
	 *
	 * <p>Empty is the interesting default now that it is read rather than assumed: an account
	 * that has never deposited a bucket is exactly the case the loadout used to get wrong.
	 */
	private LeprechaunStore leprechaun;
	private final java.util.Map<Integer, Integer> leprechaunVarbits = new java.util.HashMap<>();

	@Before
	public void setUp() throws Exception
	{
		// A backing map rather than a blanket answer, so a test can set one skill level without
		// setting every integer the plugin reads.
		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(int.class)))
			.thenAnswer(i -> stored.get(i.getArgument(0) + "." + i.getArgument(1)));
		stored.put("dooglemaps.farmingLevel", 99);

		Gson gson = new Gson();
		patches = construct(PatchStateStore.class, configManager, gson);
		patches.load();
		availability = construct(AvailabilityProfile.class, configManager, gson, patches);
		availability.load();

		seeds = construct(SeedInventoryStore.class,
			Mockito.mock(net.runelite.api.Client.class), configManager, gson);
		selection = construct(SeedSelectionStore.class, configManager, gson,
			construct(com.dooglemaps.state.ContractState.class, configManager));
		compost = construct(CompostSelectionStore.class, configManager, gson);
		compost.load();

		com.dooglemaps.state.PlayerLocation playerLocation =
			construct(com.dooglemaps.state.PlayerLocation.class,
				Mockito.mock(net.runelite.api.Client.class));
		// Hoisted above the planner, which now reads the fodder toggle too - the loadout and
		// the planner have to agree about the bins, so they share one mock rather than two.
		compostRun = Mockito.mock(com.dooglemaps.state.CompostRunStore.class);
		// Hoisted above the planner for the same reason, and it matters more here: the planner
		// caps a protected crop by the payments in these two exactly as the loadout does, and
		// the whole point of that is that the two reach the same allocation. Two separate
		// fixtures would let them disagree in the test and agree nowhere else.
		carried = construct(CarriedItems.class, Mockito.mock(net.runelite.api.Client.class));
		bank = construct(BankContents.class, configManager, gson);
		protection = construct(com.dooglemaps.state.ProtectionSelectionStore.class,
			configManager, gson);
		payToClear = construct(com.dooglemaps.state.PayToClearStore.class, configManager, gson);
		RunPlanner planner = this.planner = construct(RunPlanner.class, availability,
			construct(PatchLocationStore.class, configManager, gson),
			construct(BankLocationStore.class, configManager, gson),
			selection, seeds, patches, construct(GrowthTimer.class, configManager),
			construct(ShortestPathIntegration.class, Mockito.mock(EventBus.class),
				Mockito.mock(net.runelite.client.callback.ClientThread.class)),
			playerLocation, Mockito.mock(ToolNeeds.class),
			Mockito.mock(com.dooglemaps.state.ProtectedPatches.class),
			groups = Mockito.mock(com.dooglemaps.state.PlantingGroups.class),
			protection,
			plannerRunOptions = Mockito.mock(com.dooglemaps.state.RunTypeStore.class),
			compostRun,
			Mockito.mock(com.dooglemaps.DoogleMapsConfig.class),
			bank, carried);

		net.runelite.api.Client leprechaunClient = Mockito.mock(net.runelite.api.Client.class);
		when(leprechaunClient.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
		when(leprechaunClient.getVarbitValue(Mockito.anyInt()))
			.thenAnswer(i -> leprechaunVarbits.getOrDefault(i.getArgument(0), 0));
		leprechaun = construct(LeprechaunStore.class, leprechaunClient);
		leprechaun.onGameTick(new net.runelite.api.events.GameTick());

		// The last two are the same stores every other fixture here writes to, so a test that
		// puts weeds on a patch is asking the real question: with auto-weed on, whether the rake
		// is needed turns on whether any patch is actually weedy right now.
		ToolNeeds toolNeeds = construct(ToolNeeds.class, leprechaun, carried, bank, selection,
			construct(GrowthTimer.class, configManager),
			construct(com.dooglemaps.state.BarbarianFarming.class, configManager,
				Mockito.mock(com.dooglemaps.DoogleMapsConfig.class)),
			availability, patches);
		// A real teleport list would need item names, which only the client can supply, so the
		// name cache is stubbed from a map the tests can write to. Empty by default: most of
		// these are about the rest of the loadout, where the region table answers alone.
		config = Mockito.mock(com.dooglemaps.DoogleMapsConfig.class);
		when(config.teleportItems()).thenReturn("");

		com.dooglemaps.data.ItemNames itemNames =
			Mockito.mock(com.dooglemaps.data.ItemNames.class);
		// Honouring the fallback argument like the real class does, not answering null for
		// everything - the seed display names lean on exactly that difference.
		when(itemNames.get(Mockito.anyInt(), Mockito.any()))
			.thenAnswer(i -> names.getOrDefault(i.<Integer>getArgument(0), i.getArgument(1)));
		// The one-arg form answers null for the unrecorded, like the real class - the name-based
		// sizing leans on exactly that to fall through to the client.
		when(itemNames.get(Mockito.anyInt()))
			.thenAnswer(i -> names.get(i.<Integer>getArgument(0)));

		// A mock rather than a real store: every test here is a full run, and isHarvestOnly
		// defaults to false, which is what "a full run" means. The harvest-only path has its own
		// test below.
		runTypes = Mockito.mock(com.dooglemaps.state.RunTypeStore.class);

		// No contract assigned by default, which is what every test here but the contract ones
		// wants: getContract() answering null means contractIsStandingThere can never fire.
		contracts = Mockito.mock(com.dooglemaps.state.ContractState.class);

		loadout = construct(RunLoadout.class, planner, selection, seeds, compost, carried, bank,
			toolNeeds, leprechaun, protection, payToClear, itemNames, config, tickingClient(),
			runTypes, contracts, compostRun,
			boatHolds = construct(com.dooglemaps.bank.BoatHolds.class, configManager, gson));

		// The same collaborators over a client whose tick never moves, so forRun's cache actually
		// holds. Only one test wants it — see whatIsStillWantedFallsWithinOneTick — and it is built
		// here because the collaborators above are locals.
		frozenLoadout = construct(RunLoadout.class, planner, selection, seeds, compost, carried,
			bank, toolNeeds, leprechaun, protection, payToClear, itemNames, config, frozenClient(),
			runTypes, contracts, compostRun, boatHolds);
	}

	/**
	 * Compost he is holding is not a withdrawal, whatever the bank has.
	 *
	 * <p>The point of the row is to stop you hunting for a bucket you can pick up on site.
	 * Telling you to bank ultracompost he already has would be worse than saying nothing.
	 */
	@Test
	public void compostIsReportedAsOnSiteRatherThanAsSomethingToWithdraw()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		compost.set(PatchImplementation.HERB, CompostTier.ULTRACOMPOST);
		bankHolds(CompostTier.ULTRACOMPOST.getItemID(), 500);
		leprechaunHolds(FarmingTool.ULTRACOMPOST, 1000);

		LoadoutItem entry = find(LoadoutItem.Category.COMPOST);
		assertNotNull("the chosen compost should still be mentioned", entry);
		assertEquals("even with 500 in the bank, the leprechaun is the answer",
			LoadoutItem.Need.AT_LEPRECHAUN, entry.getNeed());
	}

	/**
	 * The count the quantity swap sizes from falls as the pack fills, within a single tick.
	 *
	 * <h2>Why this is the test that matters for that feature</h2>
	 *
	 * {@code forRun} is cached on the tick, and a player clicking Withdraw-10 then Withdraw-5 does
	 * both inside one 600ms tick. A swap reading a tick-old {@code outstanding} would still believe
	 * fifteen were wanted on the second click, offer ten again, and take twenty-five — the exact
	 * over-withdrawal the feature exists to prevent. See the spec, §8.
	 *
	 * <p>So the tick is deliberately <b>not</b> advanced between the reads below: the whole point
	 * is that the answer moves anyway, because the intent is cached and the pack is not.
	 */
	@Test
	public void whatIsStillWantedFallsWithinOneTick()
	{
		// The tree-protection fixture, which is the one proven to raise a counted payment row.
		readyAllTreePatches();
		selection.toggle(Seed.MAGIC);
		bankHolds(Seed.MAGIC.getItemID(), 5);
		seeds.record(com.dooglemaps.state.SeedSource.BANK.getContainerId(),
			containerOf(Seed.MAGIC.getItemID(), 5));
		protection.setProtecting(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.TREE), Seed.MAGIC, true);
		bankHolds(ItemID.COCONUT, 200);

		java.util.Set<PatchImplementation> trees = EnumSet.of(PatchImplementation.TREE);

		int wanted = loadout.stillWantedNow(ItemID.COCONUT, trees);
		assertTrue("the fixture must actually want some, or this test proves nothing: " + wanted,
			wanted > 0);

		carrying(ItemID.COCONUT, 25);
		assertEquals("twenty-five in the pack is twenty-five fewer to fetch, on the same tick",
			wanted - 25, loadout.stillWantedNow(ItemID.COCONUT, trees));

		// ...and now against a client whose tick does not move, so forRun's cache genuinely
		// holds. This is the half the ordinary fixture cannot show: tickingClient() advances the
		// tick on every call, which defeats the cache and would let a tick-old read pass this
		// test unnoticed.
		RunLoadout frozen = frozenLoadout;

		int before = frozen.stillWantedNow(ItemID.COCONUT, trees);
		assertTrue("the frozen fixture wants some too: " + before, before > 0);

		carrying(ItemID.COCONUT, 50);
		assertEquals("the cached row still says what it said",
			before, cachedWithdrawCount(frozen, ItemID.COCONUT, trees));
		assertEquals("but what is still wanted has moved with the pack",
			Math.max(0, before - 25), frozen.stillWantedNow(ItemID.COCONUT, trees));
	}

	/**
	 * Empty buckets are sized even though they come from his store rather than a bank.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"still not working on empty bucket withdraw for compost"</i>. {@code stillWantedNow}
	 * accepted only {@code WITHDRAW}, and the leprechaun keeps a thousand buckets — so their row
	 * says {@code AT_LEPRECHAUN}, and the one place a bin run does most of its collecting was the
	 * one place the swap stood down.
	 *
	 * <p>Asserted through the need rather than around it, because the name is about <b>where</b>
	 * they are and not about whether any are wanted.
	 */
	@Test
	public void bucketsFromHisStoreAreStillSized()
	{
		bigBinAt(62);   // finished compost, so the run wants buckets to take it out in
		leprechaunHolds(FarmingTool.EMPTY_BUCKET, 1000);

		Set<PatchImplementation> bins = EnumSet.of(PatchImplementation.BIG_COMPOST);

		LoadoutItem row = null;
		for (LoadoutItem item : loadout.forRun(bins))
		{
			if (item.getItemId() == ItemID.BUCKET_EMPTY)
			{
				row = item;
			}
		}
		assertNotNull("the fixture must raise a bucket row, or this proves nothing", row);
		assertEquals("and it should be his, which is the whole point",
			LoadoutItem.Need.AT_LEPRECHAUN, row.getNeed());

		assertTrue("his store is still somewhere to collect from: "
				+ loadout.stillWantedNow(ItemID.BUCKET_EMPTY, bins),
			loadout.stillWantedNow(ItemID.BUCKET_EMPTY, bins) > 0);
	}

	/**
	 * ...and they are sized by <b>name</b> too, which is how his store actually asks.
	 *
	 * <p>His menu entries carry no item id on the entry or the widget — the id-based lookup stood
	 * the swap down at the one interface the feature was built for, and it held in the suite only
	 * because the test mocked a widget that knew the id. The one thing his menu does carry is the
	 * item's printed name, and the row's name is the same rendering of the same item.
	 */
	@Test
	public void bucketsFromHisStoreAreSizedByNameToo()
	{
		bigBinAt(62);
		leprechaunHolds(FarmingTool.EMPTY_BUCKET, 1000);

		Set<PatchImplementation> bins = EnumSet.of(PatchImplementation.BIG_COMPOST);

		assertEquals("the name answers exactly what the id answers",
			loadout.stillWantedNow(ItemID.BUCKET_EMPTY, bins),
			loadout.stillWantedNow("Empty bucket", bins));
		assertTrue(loadout.stillWantedNow("Empty bucket", bins) > 0);
	}

	/**
	 * The game's own name for the item sizes the row too — which is the name a menu prints.
	 *
	 * <h2>The reported dead end, round four, and the one the decision log caught</h2>
	 *
	 * <i>"remove menu still not swapping"</i> — and this time {@code client.log} answered
	 * instead of costing a session: {@code item id=-1 name='Bucket' wanted=0 -> nothing to
	 * promote}. The row is labelled "Empty bucket" so the bank list reads unambiguously, but a
	 * menu prints the <b>game's</b> name for the item, and the game calls item 1925 plain
	 * "Bucket". Matching menu names against row labels was comparing the plugin's wording with
	 * the game's, and they part company at exactly this row.
	 *
	 * <p>So both names answer — the label, and the game's name for the row's item id.
	 */
	@Test
	public void theGamesOwnNameForTheItemSizesTheRowToo()
	{
		bigBinAt(62);
		leprechaunHolds(FarmingTool.EMPTY_BUCKET, 1000);
		// What the game calls item 1925, which is not what the row's label calls it.
		names.put(ItemID.BUCKET_EMPTY, "Bucket");

		Set<PatchImplementation> bins = EnumSet.of(PatchImplementation.BIG_COMPOST);

		assertEquals("the menu's name answers exactly what the id answers",
			loadout.stillWantedNow(ItemID.BUCKET_EMPTY, bins),
			loadout.stillWantedNow("Bucket", bins));
		assertTrue(loadout.stillWantedNow("Bucket", bins) > 0);
	}

	/**
	 * A withdrawal wanting at least a whole pack answers "take All" — and only that kind.
	 *
	 * <p>The big bin's thirty buckets against an empty pack's twenty-eight slots is the case the
	 * rule exists for; the normal bin's fifteen against the same pack is the case that keeps the
	 * ladder. Ash is the deliberate exception even when the numbers say otherwise: it stacks, so
	 * All would take the bank's whole pile rather than what fits, which is the overshoot the
	 * whole feature promises never to make.
	 */
	@Test
	public void wantingAWholePackAnswersAll()
	{
		binAt(62);
		leprechaunHolds(FarmingTool.EMPTY_BUCKET, 1000);
		// A pack mostly full of the run, so the fifteen buckets wanted are more than fit.
		net.runelite.api.Item[] held = new net.runelite.api.Item[20];
		for (int i = 0; i < held.length; i++)
		{
			held[i] = new net.runelite.api.Item(4000 + i, 1);
		}
		net.runelite.api.ItemContainer pack =
			Mockito.mock(net.runelite.api.ItemContainer.class);
		when(pack.getItems()).thenReturn(held);
		carried.record(pack);

		Set<PatchImplementation> bins = EnumSet.of(PatchImplementation.COMPOST);

		assertTrue("fifteen buckets into eight free slots is a packful",
			loadout.fillsThePack(ItemID.BUCKET_EMPTY, bins));
	}

	@Test
	public void wantingLessThanAPackKeepsTheLadder()
	{
		binAt(62);
		leprechaunHolds(FarmingTool.EMPTY_BUCKET, 1000);
		Set<PatchImplementation> bins = EnumSet.of(PatchImplementation.COMPOST);

		assertTrue("the fixture must still want the buckets",
			loadout.stillWantedNow(ItemID.BUCKET_EMPTY, bins) > 0);
		assertFalse("fifteen into twenty-eight free is not a packful",
			loadout.fillsThePack(ItemID.BUCKET_EMPTY, bins));
	}

	@Test
	public void ashNeverAnswersAllHoweverMuchIsWanted()
	{
		bigBinAt(62);
		when(compostRun.isAshing()).thenReturn(true);
		bankHolds(com.dooglemaps.data.CompostBin.VOLCANIC_ASH, 5000);
		Set<PatchImplementation> bins = EnumSet.of(PatchImplementation.BIG_COMPOST);

		assertTrue("the ash is wanted...",
			loadout.stillWantedNow(com.dooglemaps.data.CompostBin.VOLCANIC_ASH, bins) > 0);
		assertFalse("...but it stacks, so All would take the bank's whole pile",
			loadout.fillsThePack(com.dooglemaps.data.CompostBin.VOLCANIC_ASH, bins));
	}

	/**
	 * Done means "tracked and now covered", never merely "answers zero".
	 *
	 * <p>{@code stillWantedNow} answers zero for a satisfied row, for a seed the swap must never
	 * size, and for an item that was never the run's business — and the Examine swap is only
	 * safe for the first. A pack holding every bucket the bins want is done; the same pack a
	 * bucket short is not; and a seed is never done however many are carried, because it was
	 * never sized to begin with.
	 */
	@Test
	public void doneMeansTrackedAndCovered()
	{
		binAt(62);
		leprechaunHolds(FarmingTool.EMPTY_BUCKET, 1000);
		Set<PatchImplementation> bins = EnumSet.of(PatchImplementation.COMPOST);

		assertFalse("a bucket short is not done",
			loadout.doneWithdrawing(ItemID.BUCKET_EMPTY, bins));

		// Every bucket the bin wants, in the pack.
		int wanted = loadout.stillWantedNow(ItemID.BUCKET_EMPTY, bins);
		net.runelite.api.Item[] held = new net.runelite.api.Item[wanted];
		for (int i = 0; i < held.length; i++)
		{
			held[i] = new net.runelite.api.Item(ItemID.BUCKET_EMPTY, 1);
		}
		net.runelite.api.ItemContainer pack =
			Mockito.mock(net.runelite.api.ItemContainer.class);
		when(pack.getItems()).thenReturn(held);
		carried.record(pack);

		assertTrue("every bucket collected is done",
			loadout.doneWithdrawing(ItemID.BUCKET_EMPTY, bins));
	}

	/** A seed is never done, because it was never the swap's to size. */
	@Test
	public void aSeedIsNeverDoneWithdrawing()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		bankHolds(Seed.RANARR.getItemID(), 50);
		seeds.record(com.dooglemaps.state.SeedSource.BANK.getContainerId(),
			containerOf(Seed.RANARR.getItemID(), 50));

		assertFalse(loadout.doneWithdrawing(Seed.RANARR.getItemID(), HERBS));
	}

	/**
	 * Seeds are never sized, so the swap leaves them on whatever the player normally uses.
	 *
	 * <p>The owner's call and the reasoning is worth keeping: over-withdrawing a seed is nearly
	 * free and under-withdrawing costs a patch, which is the opposite of the bucket case that
	 * prompted the feature.
	 */
	@Test
	public void seedsAreNeverSized()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		bankHolds(Seed.RANARR.getItemID(), 50);
		seeds.record(com.dooglemaps.state.SeedSource.BANK.getContainerId(),
			containerOf(Seed.RANARR.getItemID(), 50));

		assertEquals("a seed row is not something to size a click from",
			0, loadout.stillWantedNow(Seed.RANARR.getItemID(), HERBS));
	}

	/**
	 * A tree run whose saplings are all being paid for banks no compost at all.
	 *
	 * <h2>The bank half of the guide's refusal</h2>
	 *
	 * A protection payment is immunity outright, and a tree has no lives mechanic for compost to
	 * improve, so the guide will not ask for a bucket on a protected sapling — see
	 * {@code CropYieldModel.compostWastedOnProtected}. Banking one anyway would be a slot spent
	 * on an item no step is ever going to name, which is precisely the noise this list exists to
	 * avoid. Asked for from play.
	 */
	@Test
	public void aFullyProtectedTreeRunBanksNoCompost()
	{
		readyTreePatch();
		selection.toggle(Seed.YEW);
		compost.set(PatchImplementation.TREE, CompostTier.ULTRACOMPOST);
		bankHolds(CompostTier.ULTRACOMPOST.getItemID(), 500);
		protection.setProtecting(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.TREE), Seed.YEW, true);

		assertNull("nothing on this run can use it", treeCompostRow());
	}

	/**
	 * ...and the same run with the protection off banks it again, so the row is answering the
	 * protection question rather than having quietly gone missing.
	 */
	@Test
	public void anUnprotectedTreeRunStillBanksItsCompost()
	{
		readyTreePatch();
		selection.toggle(Seed.YEW);
		compost.set(PatchImplementation.TREE, CompostTier.ULTRACOMPOST);
		bankHolds(CompostTier.ULTRACOMPOST.getItemID(), 500);

		assertNotNull("unprotected, the bucket is the only disease cover there is",
			treeCompostRow());
	}

	/**
	 * The compost row of a <b>tree</b> run, or null.
	 *
	 * <p>{@code find} runs the herb set, which is what nearly every test here wants; these two
	 * are about trees and would otherwise both answer null for the wrong reason.
	 */
	@Nullable
	private LoadoutItem treeCompostRow()
	{
		for (LoadoutItem item : loadout.forRun(EnumSet.of(PatchImplementation.TREE)))
		{
			if (item.getCategory() == LoadoutItem.Category.COMPOST)
			{
				return item;
			}
		}
		return null;
	}

	/**
	 * Compost he does <b>not</b> have is a withdrawal, and this is the case that was wrong.
	 *
	 * <p>On-site was asserted unconditionally, so an account that had never deposited a bucket
	 * was told to leave its compost in the bank — and then arrived with none and planted every
	 * patch on the run untreated. His store is read now, so absence is noticed.
	 */
	@Test
	public void compostTheLeprechaunDoesNotHaveIsAWithdrawal()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		compost.set(PatchImplementation.HERB, CompostTier.ULTRACOMPOST);
		bankHolds(CompostTier.ULTRACOMPOST.getItemID(), 500);

		LoadoutItem entry = find(LoadoutItem.Category.COMPOST);
		assertNotNull(entry);
		assertEquals("he has none of this tier, so it has to come from the bank",
			LoadoutItem.Need.WITHDRAW, entry.getNeed());
	}

	/**
	 * The tier is what is checked, not compost in general.
	 *
	 * <p>They are stored in separate slots, and a thousand buckets of ordinary compost is no use
	 * at all to someone who picked ultra.
	 */
	@Test
	public void aDifferentTierInHisStoreDoesNotCount()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		compost.set(PatchImplementation.HERB, CompostTier.ULTRACOMPOST);
		bankHolds(CompostTier.ULTRACOMPOST.getItemID(), 500);
		leprechaunHolds(FarmingTool.COMPOST, 1000);

		assertEquals(LoadoutItem.Need.WITHDRAW, find(LoadoutItem.Category.COMPOST).getNeed());
	}

	/** A tool he is holding is collected at the patch, not banked for. */
	@Test
	public void aToolInHisStoreIsNotAWithdrawal()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		leprechaunHolds(FarmingTool.RAKE, 1);
		leprechaunHolds(FarmingTool.SPADE, 1);
		leprechaunHolds(FarmingTool.SEED_DIBBER, 1);

		for (LoadoutItem item : itemsIn(LoadoutItem.Category.TOOL))
		{
			assertEquals(item.getName(), LoadoutItem.Need.AT_LEPRECHAUN, item.getNeed());
		}
	}

	/**
	 * Auto-weed drops the rake from the loadout only while nothing is actually weedy.
	 *
	 * <h2>"Has auto-weed" is not "has no weeds"</h2>
	 *
	 * The unlock stops weeds <b>growing</b>. It does not rake a patch that is already weedy, so
	 * a patch that was weedy when it was bought — or one first reached afterwards — still needs
	 * the rake, and the loadout used to leave it in the bank on the strength of the unlock
	 * alone. Reported from play alongside the guide's own half of the same wrong assumption: a
	 * weedy patch, no rake fetched, and the run going straight to the compost step.
	 */
	@Test
	public void autoweedDropsTheRakeWhileNothingIsWeedy()
	{
		stored.put("dooglemaps.autoweed", GrowthTimer.AUTOWEED_ON);
		readyHerbPatch();
		selection.toggle(Seed.RANARR);

		assertNull("with the unlock and no weeds standing, the rake is noise",
			find(LoadoutItem.Category.TOOL, "Rake"));
	}

	/**
	 * The other half, as its own run because the loadout answers once per tick.
	 *
	 * <p>Same account, same unlock, one patch that was already weedy when it was bought.
	 */
	@Test
	public void aWeedyPatchStillWantsTheRakeDespiteAutoweed()
	{
		stored.put("dooglemaps.autoweed", GrowthTimer.AUTOWEED_ON);
		weedyHerbPatch();
		selection.toggle(Seed.RANARR);

		assertNotNull("a weedy patch needs the rake, unlock or no unlock",
			find(LoadoutItem.Category.TOOL, "Rake"));
	}

	/** A herb patch with its weeds still standing: varbit 0 is unraked, stage 3 of 3. */
	private void weedyHerbPatch()
	{
		FarmPatch patch = FarmingWorldData.getPatches(PatchImplementation.HERB).get(0);
		ProduceState decoded = patch.getImplementation().forVarbitValue(0);
		assertNotNull(decoded);
		assertEquals("fixture must be weeds", Produce.WEEDS, decoded.getProduce());
		patches.recordVarbit(patch, 0, decoded);
		availability.setAvailable(patch, true);
	}

	/**
	 * A tool nowhere at all is called missing, which is the whole point of reading his store.
	 *
	 * <p>This is the trip that would otherwise be wasted: arriving at a weedy patch with no rake
	 * on you, none stored and none in the bank means nothing at that stop can be raked, treated
	 * or planted. It is worth one line before setting off.
	 */
	@Test
	public void aToolYouOwnNowhereIsReportedMissing()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		bankHolds(ItemID.SPADE, 1);

		LoadoutItem rake = find(LoadoutItem.Category.TOOL, "Rake");
		assertNotNull("a rake is needed and should be named", rake);
		assertEquals(LoadoutItem.Need.MISSING, rake.getNeed());
		assertTrue("the fix is a shop, not a bank",
			rake.getReason().toLowerCase().contains("shop"));

		assertEquals("the spade is in the bank, so that one is a withdrawal",
			LoadoutItem.Need.WITHDRAW, find(LoadoutItem.Category.TOOL, "Spade").getNeed());
	}

	/**
	 * A teleport you do not own is not suggested.
	 *
	 * <p>This is the whole reason the teleport table is item-to-place rather than
	 * place-to-advice: an ironman with no Ardougne cloak should see nothing, not a shopping
	 * list.
	 */
	@Test
	public void teleportsYouDoNotOwnAreNeverSuggested()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);

		assertTrue("nothing owned, so nothing to say",
			itemsIn(LoadoutItem.Category.TELEPORT).isEmpty());
	}

	@Test
	public void aTeleportIsOnlyOfferedIfListed()
	{
		readyHerbPatch();   // Ardougne - so the old region table would have offered the cloak
		selection.toggle(Seed.RANARR);
		bankHolds(ItemID.ARDY_CAPE_MEDIUM, 1);
		names.put(ItemID.ARDY_CAPE_MEDIUM, "Ardougne cloak 2");
		when(config.teleportItems()).thenReturn("Games necklace(8)");

		assertTrue("owned and it reaches a stop, but it is not on your list, so nothing",
			itemsIn(LoadoutItem.Category.TELEPORT).isEmpty());

		when(config.teleportItems()).thenReturn("Ardougne cloak 2");
		List<LoadoutItem> teleports = itemsIn(LoadoutItem.Category.TELEPORT);
		assertEquals("listing it is what offers it", 1, teleports.size());
		assertEquals(LoadoutItem.Need.WITHDRAW, teleports.get(0).getNeed());
	}

	/** A teleport for somewhere the run does not go is not suggested either. */
	@Test
	public void aTeleportForSomewhereElseIsNotSuggested()
	{
		readyHerbPatch();   // Ardougne
		selection.toggle(Seed.RANARR);
		bankHolds(ItemID.TELETAB_HARMONY, 5);

		assertTrue("this run does not go to Harmony",
			itemsIn(LoadoutItem.Category.TELEPORT).isEmpty());
	}

	/** Already carrying it means it is checked off, not asked for again. */
	@Test
	public void somethingAlreadyCarriedIsNotAWithdrawal()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		carrying(ItemID.ARDY_CAPE_MEDIUM, 1);
		names.put(ItemID.ARDY_CAPE_MEDIUM, "Ardougne cloak 2");
		when(config.teleportItems()).thenReturn("Ardougne cloak 2");

		List<LoadoutItem> teleports = itemsIn(LoadoutItem.Category.TELEPORT);
		assertEquals(1, teleports.size());
		assertEquals(LoadoutItem.Need.HAVE, teleports.get(0).getNeed());
		assertFalse("nothing to highlight in the bank",
			withdrawals(HERBS).contains(ItemID.ARDY_CAPE_MEDIUM));
	}

	/**
	 * Storage is only offered when it exists.
	 *
	 * <p>The herb sack wants 58 Herblore, unboostable, and 750 Slayer points. Suggesting one
	 * to an account that cannot have it is the failure this avoids.
	 */
	@Test
	public void storageIsOnlySuggestedIfYouActuallyHaveIt()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);

		assertTrue("no herb sack anywhere, so no suggestion",
			itemsIn(LoadoutItem.Category.STORAGE).isEmpty());

		bankHolds(ItemID.SLAYER_HERB_SACK, 1);
		List<LoadoutItem> storage = itemsIn(LoadoutItem.Category.STORAGE);
		assertEquals(1, storage.size());
		assertEquals("Herb sack", storage.get(0).getName());
	}

	/**
	 * The seed count is what is left to fetch, and it moves both ways.
	 *
	 * <p>The number over a bank slot is only worth watching if it tracks the pack. A count taken
	 * from what the run <i>wants</i> sits at its full figure until you have every one and then
	 * vanishes, which tells you nothing while you are part way through — and part way through is
	 * the whole time you are standing there.
	 */
	@Test
	public void theSeedCountFallsAsYouWithdrawAndRisesIfYouPutSomeBack()
	{
		// Several patches, because one herb patch wants one seed and a count of one is exactly
		// the case the display deliberately does not draw.
		readyHerbPatches(5);
		selection.toggle(Seed.RANARR);
		seedsInBank(Seed.RANARR, 50);

		int wanted = onlySeed().getQuantity();
		assertTrue("the run wants a real number of these", wanted > 1);
		assertEquals("nothing in the pack yet, so all of them", wanted, onlySeed().getOutstanding());

		seedsInInventory(Seed.RANARR, 1);
		assertEquals("one fetched", wanted - 1, onlySeed().getOutstanding());
		assertEquals("what the run wants has not changed", wanted, onlySeed().getQuantity());

		// Put it back.
		seedsInInventory(Seed.RANARR, 0);
		assertEquals("and the count goes back up", wanted, onlySeed().getOutstanding());

		seedsInInventory(Seed.RANARR, wanted);
		assertEquals("nothing left to fetch", 0, onlySeed().getOutstanding());
		assertEquals(LoadoutItem.Need.HAVE, onlySeed().getNeed());
	}

	/**
	 * Owning fewer seeds than the run wants asks for all of them, not for the shortfall.
	 *
	 * <p>The difference matters at the bank: one ranarr seed for a five-patch run is "take the
	 * one", and a count of four would send you looking for seeds that are not there.
	 */
	@Test
	public void aShortSeedStackAsksForWhatYouActuallyHave()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		seedsInBank(Seed.RANARR, 1);

		assertEquals("one owned, so one to fetch", 1, onlySeed().getOutstanding());
	}

	/** Nothing uncounted claims a count, so the bank never draws "1" beside an axe. */
	@Test
	public void thingsThatAreNotCountedCarryNoOutstanding()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		bankHolds(ItemID.ARDY_CAPE_MEDIUM, 1);

		for (LoadoutItem item : loadout.forRun(HERBS))
		{
			if (item.getCategory() != LoadoutItem.Category.SEED
				&& item.getCategory() != LoadoutItem.Category.PAYMENT)
			{
				assertEquals(item.getName() + " should carry no count", 0, item.getOutstanding());
			}
		}
	}

	/** Only outstanding withdrawals get highlighted, so the bank does not light up wholesale. */
	@Test
	public void onlyOutstandingWithdrawalsAreHighlighted()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		bankHolds(ItemID.ARDY_CAPE_MEDIUM, 1);
		names.put(ItemID.ARDY_CAPE_MEDIUM, "Ardougne cloak 2");
		when(config.teleportItems()).thenReturn("Ardougne cloak 2");
		bankHolds(ItemID.SLAYER_HERB_SACK, 1);
		carrying(ItemID.FAIRY_ENCHANTED_SECATEURS, 1);

		Set<Integer> highlighted = withdrawals(HERBS);
		assertTrue(highlighted.contains(ItemID.ARDY_CAPE_MEDIUM));
		assertTrue(highlighted.contains(ItemID.SLAYER_HERB_SACK));
		assertFalse("carried, so there is nothing to fetch",
			highlighted.contains(ItemID.FAIRY_ENCHANTED_SECATEURS));
	}

	/**
	 * Leprechaun items are not marked in the bank at all.
	 *
	 * <p>They used to be, in a second colour meaning "leave this" — but a highlight over your
	 * ultracompost reads as take it whatever colour it is, and the errand is at the patch rather
	 * than at the bank. The loadout still knows he has it; the bank simply stays quiet.
	 */
	@Test
	public void compostTheLeprechaunHoldsIsNotMarkedInTheBank()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		compost.set(PatchImplementation.HERB, CompostTier.ULTRACOMPOST);
		leprechaunHolds(FarmingTool.ULTRACOMPOST, 1000);

		int bucket = CompostTier.ULTRACOMPOST.getItemID();
		assertNull("he has it, so the bank has nothing to say about it",
			loadout.highlights(HERBS).get(bucket));
		assertFalse("and it is certainly not something to take",
			withdrawals(HERBS).contains(bucket));
		assertEquals("but the loadout still knows where it is",
			LoadoutItem.Need.AT_LEPRECHAUN, loadout.itemFor(HERBS, bucket).getNeed());
	}

	/**
	 * A tree run wants the best axe you can actually swing.
	 *
	 * <p>The leprechaun stores every other farming tool, so this is the one that has to be
	 * carried — and without it a grown tree cannot be cleared, so the trip achieves nothing.
	 */
	@Test
	public void aTreeRunAsksForTheBestUsableAxe()
	{
		Set<PatchImplementation> trees = EnumSet.of(PatchImplementation.TREE);
		readyTreePatch();
		bankHolds(ItemID.DRAGON_AXE, 1);
		bankHolds(ItemID.RUNE_AXE, 1);
		woodcuttingLevel(99);

		LoadoutItem axe = axeIn(trees);
		assertNotNull(axe);
		assertEquals("Dragon axe", axe.getName());
		assertEquals(LoadoutItem.Need.WITHDRAW, axe.getNeed());
	}

	/**
	 * A tree seed in the bank is found, marked and not filtered away.
	 *
	 * <p>Reported from play, as the bank filter hiding the very seeds it was supposed to show. A
	 * tree crop exists as two items — the seed you buy and the sapling it becomes in a plant pot —
	 * and a {@code LoadoutItem} names the <b>planted</b> form, which is right for the panel and
	 * wrong for a bank, where the seed is what is actually sitting there.
	 *
	 * <p>It was wrong twice over: {@code getOwnedPlantable} counted only saplings, so the row read
	 * MISSING with the seeds in the bank, and the highlight set held only the sapling id, so
	 * nothing was marked and the filter hid them.
	 */
	@Test
	public void aTreeSeedInTheBankIsOwnedAndMarked()
	{
		Set<PatchImplementation> trees = EnumSet.of(PatchImplementation.TREE);
		readyTreePatch();
		selection.toggle(Seed.MAGIC);
		// The seed, not the sapling — which is how anyone actually holds one.
		seedsInBank(Seed.MAGIC, 5);
		woodcuttingLevel(99);

		LoadoutItem entry = null;
		for (LoadoutItem item : loadout.forRun(trees))
		{
			if (item.getCategory() == LoadoutItem.Category.SEED
				&& item.getItemId() == Seed.MAGIC.getPlantedItemID())
			{
				entry = item;
			}
		}
		assertNotNull("five magic seeds in the bank is owning magic seeds", entry);
		assertEquals("not MISSING - they are right there",
			LoadoutItem.Need.WITHDRAW, entry.getNeed());
		assertEquals("named as the item you would pick up, not the bare crop - \"Magic\" is "
				+ "ambiguous at a bank holding seeds, saplings and logs at once",
			"Magic sapling", entry.getName());

		Set<Integer> marked = withdrawals(trees);
		assertTrue("the seed is what you have to find in the bank",
			marked.contains(Seed.MAGIC.getItemID()));
		assertTrue("and the sapling counts too, in case some are already potted",
			marked.contains(Seed.MAGIC.getPlantedItemID()));
	}

	/**
	 * Saplings already in stock cover the want, so the seed form is not asked for too.
	 *
	 * <p>Reported from play: papaya seeds outlined, counted and their vault tab lit while 59
	 * saplings sat in the vault covering a want of four. {@code bankFormsOf} always added both
	 * forms of a sapling crop on the reasoning that you might have potted some already — but the
	 * row already knows whether potting is still outstanding, and asking for the seed on top of
	 * saplings that already cover the want is clutter rather than a second genuine item.
	 */
	@Test
	public void saplingsInStockDoNotAskForTheSeed()
	{
		Set<PatchImplementation> trees = EnumSet.of(PatchImplementation.TREE);
		readyTreePatch();
		selection.toggle(Seed.MAGIC);
		saplingsInBank(Seed.MAGIC, 1);
		woodcuttingLevel(99);

		LoadoutItem entry = itemNamed(trees, "Magic sapling");
		assertNotNull("a sapling in the bank is owning the crop", entry);
		assertEquals("the saplings already cover the want - nothing left to pot",
			0, entry.getSeedsToPot());

		Set<Integer> marked = withdrawals(trees);
		assertTrue("the sapling is what is actually sitting in the bank",
			marked.contains(Seed.MAGIC.getPlantedItemID()));
		assertFalse("saplings cover the want, so the seed form is not asked for too",
			marked.contains(Seed.MAGIC.getItemID()));
	}

	/**
	 * Owning fewer saplings than the run wants still asks for both forms, and the row says by how
	 * much.
	 */
	@Test
	public void aSeedShortfallStillAsksForBothForms()
	{
		Set<PatchImplementation> trees = EnumSet.of(PatchImplementation.TREE);
		readyTreePatch();
		selection.toggle(Seed.MAGIC);
		seedsInBank(Seed.MAGIC, 5);
		woodcuttingLevel(99);

		LoadoutItem entry = itemNamed(trees, "Magic sapling");
		assertNotNull(entry);
		assertEquals("plantable is zero against a want of one - the whole one still needs "
				+ "a pot",
			1, entry.getSeedsToPot());

		Set<Integer> marked = withdrawals(trees);
		assertTrue(marked.contains(Seed.MAGIC.getItemID()));
		assertTrue(marked.contains(Seed.MAGIC.getPlantedItemID()));
	}

	/**
	 * The reported case: a pair of un-potted seeds sitting in the pack must count against the
	 * click, exactly as a sapling in the same slot would.
	 *
	 * <p>The withdraw-5-for-4 report. {@code sizeRow} used to recompute the count against the
	 * sapling id and the inventory alone; the row's own {@code outstanding} already folds in the
	 * seed form and the seed box, and is what the panel and the cyan slot print. The point of the
	 * test is that the swap's answer is the row's own count, whatever that count is.
	 *
	 * <p>It used to be four here, on the reasoning that two un-potted seeds in the pack count the
	 * same as two saplings. They do not: 59 saplings in the vault cover all six patches, so
	 * nothing is going to be potted and those two seeds are not being planted. The want is six.
	 */
	@Test
	public void anUnpottedSeedInThePackDoesNotInflateTheClick()
	{
		Set<PatchImplementation> trees = EnumSet.of(PatchImplementation.TREE);
		readyTreePatches(6);
		selection.toggle(Seed.MAGIC);
		saplingsInVault(Seed.MAGIC, 59);
		seedsInInventory(Seed.MAGIC, 2);
		woodcuttingLevel(99);

		LoadoutItem entry = itemNamed(trees, "Magic sapling");
		assertNotNull(entry);
		assertEquals("the saplings cover the want, so the carried seeds are not part of it",
			6, entry.getWithdrawCount());
		assertEquals("sized off the row's own outstanding, not re-derived against the "
				+ "sapling id and the inventory alone",
			6, loadout.stillWantedNow(Seed.MAGIC.getPlantedItemID(), trees));
	}

	/** As above, with the two un-potted seeds in the seed box rather than the pack. */
	@Test
	public void aSeedBoxOfSeedsCountsAgainstTheClick()
	{
		Set<PatchImplementation> trees = EnumSet.of(PatchImplementation.TREE);
		readyTreePatches(6);
		selection.toggle(Seed.MAGIC);
		saplingsInVault(Seed.MAGIC, 59);
		seedsInBox(Seed.MAGIC, 2);
		woodcuttingLevel(99);

		LoadoutItem entry = itemNamed(trees, "Magic sapling");
		assertNotNull(entry);
		assertEquals("the box is weighed exactly as the pack is, and by the same rule",
			6, entry.getWithdrawCount());
		assertEquals(6, loadout.stillWantedNow(Seed.MAGIC.getPlantedItemID(), trees));
	}

	/**
	 * The reported bug: a bank holding two seeds must not beat a vault holding sixty-four
	 * saplings.
	 *
	 * <p>Six papayas wanted. The split took the containers in order and folded both forms
	 * together, so the bank's two seeds were spoken for first — the player withdrew two seeds
	 * that cannot go in the ground, and the vault row then read four against a true need of six.
	 * A sapling is the thing you plant; a seed is a sapling plus an errand at a plant pot, so
	 * every sapling is taken before any seed, wherever each is kept.
	 */
	@Test
	public void vaultSaplingsBeatBankSeeds()
	{
		Set<PatchImplementation> trees = EnumSet.of(PatchImplementation.TREE);
		readyTreePatches(6);
		selection.toggle(Seed.MAGIC);
		seedsInBank(Seed.MAGIC, 2);
		saplingsInVault(Seed.MAGIC, 64);
		woodcuttingLevel(99);

		List<LoadoutItem> rows = seedRows(Seed.MAGIC);
		assertEquals("the vault alone covers it, so nothing is split off to the bank",
			1, rows.size());
		assertEquals("where the saplings are", LoadoutItem.From.SEED_VAULT, rows.get(0).getFrom());
		assertEquals(6, rows.get(0).getQuantity());
		assertEquals(6, rows.get(0).getWithdrawCount());
		assertEquals("nothing is being fetched as a seed", 0, rows.get(0).getSeedsToPot());

		assertFalse("the bank's two seeds are not part of this run",
			withdrawals(trees).contains(Seed.MAGIC.getItemID()));
		assertNull("and with nothing to pot there is no pot to bring",
			itemNamed(trees, "Filled plant pot"));
	}

	/**
	 * Seeds already in the pack count against the want only when they are going to be potted.
	 *
	 * <p>Otherwise the arithmetic quietly plants them: two loose papaya seeds took two off a want
	 * of six while sixty-four saplings sat covering all six, and the errand came back one row
	 * short.
	 */
	@Test
	public void carriedSeedsDoNotCountWhenSaplingsCoverTheWant()
	{
		Set<PatchImplementation> trees = EnumSet.of(PatchImplementation.TREE);
		readyTreePatches(6);
		selection.toggle(Seed.MAGIC);
		seedsInBank(Seed.MAGIC, 2);
		saplingsInVault(Seed.MAGIC, 64);
		seedsInInventory(Seed.MAGIC, 2);
		woodcuttingLevel(99);

		List<LoadoutItem> rows = seedRows(Seed.MAGIC);
		assertEquals(1, rows.size());
		assertEquals(LoadoutItem.From.SEED_VAULT, rows.get(0).getFrom());
		assertEquals("the carried seeds are not going in the ground, so they are not the want",
			6, rows.get(0).getWithdrawCount());
		assertEquals(0, rows.get(0).getSeedsToPot());
	}

	/**
	 * Seeds are fetched, but only for the part the saplings cannot cover, and the row that
	 * fetches them is the row that says so.
	 */
	@Test
	public void seedsFillOnlyTheSaplingShortfall()
	{
		Set<PatchImplementation> trees = EnumSet.of(PatchImplementation.TREE);
		readyTreePatches(6);
		selection.toggle(Seed.MAGIC);
		seedsInBank(Seed.MAGIC, 2);
		saplingsInVault(Seed.MAGIC, 3);
		woodcuttingLevel(99);

		List<LoadoutItem> rows = seedRows(Seed.MAGIC);
		assertEquals("three saplings and two seeds is two containers", 2, rows.size());
		assertEquals(LoadoutItem.From.BANK, rows.get(0).getFrom());
		assertEquals("the seeds, and only as many as the saplings left short",
			2, rows.get(0).getWithdrawCount());
		assertEquals("the bank row is the one fetching them", 2, rows.get(0).getSeedsToPot());
		assertEquals(LoadoutItem.From.SEED_VAULT, rows.get(1).getFrom());
		assertEquals(3, rows.get(1).getWithdrawCount());
		assertEquals("the vault's share is saplings, already potted",
			0, rows.get(1).getSeedsToPot());

		assertTrue("both forms are worth finding in this run",
			withdrawals(trees).contains(Seed.MAGIC.getItemID()));
		LoadoutItem pots = itemNamed(trees, "Filled plant pot");
		assertNotNull("two seeds want two pots", pots);
		assertEquals(2, pots.getQuantity());
		assertTrue("and the seed row says why out loud",
			rows.get(0).getReason().contains("needs potting into a sapling first"));
	}

	/**
	 * Within one container the sapling still goes first: a bank holding both forms gives up its
	 * sapling before it gives up a seed.
	 */
	@Test
	public void aBankSaplingIsTakenBeforeItsSeeds()
	{
		Set<PatchImplementation> trees = EnumSet.of(PatchImplementation.TREE);
		readyTreePatches(6);
		selection.toggle(Seed.MAGIC);
		// One combined record: seeds.record swaps the whole container in, so the two
		// single-form helpers would leave only the second form owned.
		bankHolds(Seed.MAGIC.getPlantedItemID(), 1);
		bankHolds(Seed.MAGIC.getItemID(), 2);
		seeds.record(com.dooglemaps.state.SeedSource.BANK.getContainerId(),
			containerOf(Seed.MAGIC.getPlantedItemID(), 1, Seed.MAGIC.getItemID(), 2));
		saplingsInVault(Seed.MAGIC, 4);
		woodcuttingLevel(99);

		List<LoadoutItem> rows = seedRows(Seed.MAGIC);
		assertEquals(2, rows.size());
		assertEquals(LoadoutItem.From.BANK, rows.get(0).getFrom());
		assertEquals("its own sapling plus the one seed the vault's four left short",
			2, rows.get(0).getWithdrawCount());
		assertEquals("of which one is a seed", 1, rows.get(0).getSeedsToPot());
		assertEquals(LoadoutItem.From.SEED_VAULT, rows.get(1).getFrom());
		assertEquals(4, rows.get(1).getWithdrawCount());
		assertEquals(0, rows.get(1).getSeedsToPot());

		LoadoutItem pots = itemNamed(trees, "Filled plant pot");
		assertNotNull(pots);
		assertEquals("one seed, one pot", 1, pots.getQuantity());
	}

	/**
	 * A split bank/vault pair sizes and finishes from its own row, not whichever half a bare
	 * item id happens to reach first.
	 *
	 * <p>The second half of the withdraw-5-for-4 report: a menu open on the vault has to be sized
	 * from the vault's own row, and must not read as done the moment the bank's smaller share is
	 * taken while the vault's share is still sitting there untouched.
	 */
	@Test
	public void aSplitRowSizesItsOwnContainer()
	{
		Set<PatchImplementation> trees = EnumSet.of(PatchImplementation.TREE);
		readyAllTreePatches();
		selection.toggle(Seed.MAGIC);
		saplingsInBank(Seed.MAGIC, 1);
		saplingsInVault(Seed.MAGIC, 4);
		woodcuttingLevel(99);

		int saplingId = Seed.MAGIC.getPlantedItemID();
		assertEquals("the vault row sizes its own share, not the bank row's",
			4, loadout.stillWantedNow(saplingId, trees, LoadoutItem.From.SEED_VAULT));
		assertFalse("neither container's row is satisfied yet",
			loadout.doneWithdrawing(saplingId, trees, LoadoutItem.From.SEED_VAULT));

		// The bank's one sapling is taken: it leaves the bank and lands in the pack.
		saplingsInBank(Seed.MAGIC, 0);
		saplingsInInventory(Seed.MAGIC, 1);

		assertFalse("the vault's four are still outstanding - the bank emptying out must "
				+ "not read as the vault row being done",
			loadout.doneWithdrawing(saplingId, trees, LoadoutItem.From.SEED_VAULT));
	}

	/**
	 * A tree seed that is still a seed brings its potting supplies with it.
	 *
	 * <p>The seed row has said <i>needs potting</i> for a while; what it did not say is what the
	 * potting needs — a filled plant pot per seed and a watering can — so both were discovered
	 * at the patch, a teleport too late.
	 */
	@Test
	public void aSeedStillToBePottedAsksForPotsAndACan()
	{
		Set<PatchImplementation> trees = EnumSet.of(PatchImplementation.TREE);
		readyTreePatch();
		selection.toggle(Seed.MAGIC);
		seedsInBank(Seed.MAGIC, 5);
		woodcuttingLevel(99);
		bankHolds(net.runelite.api.gameval.ItemID.PLANTPOT_COMPOST, 12);
		bankHolds(net.runelite.api.gameval.ItemID.WATERING_CAN_8, 1);

		LoadoutItem pots = itemNamed(trees, "Filled plant pot");
		assertNotNull("no sapling potted yet, so the pots are part of the trip", pots);
		assertEquals(LoadoutItem.Need.WITHDRAW, pots.getNeed());
		assertEquals("one pot per seed to pot, not per seed owned", 1, pots.getQuantity());
		assertEquals("and the slot count follows it", 1, pots.getWithdrawCount());

		LoadoutItem can = itemNamed(trees, "Watering can");
		assertNotNull("a fresh sapling has to be watered before it grows", can);
		assertEquals(LoadoutItem.Need.WITHDRAW, can.getNeed());
		assertEquals("a unit thing still to fetch counts as one", 1, can.getWithdrawCount());
	}

	/** A bin at a chosen varbit, ticked into the run so the planner's binWork can see it. */
	private void binAt(int varbitValue)
	{
		FarmPatch bin = null;
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.COMPOST))
		{
			bin = patch;
			break;
		}
		assertNotNull("no compost bin in the generated world data", bin);
		ProduceState decoded = bin.getImplementation().forVarbitValue(varbitValue);
		assertNotNull("varbit " + varbitValue + " decodes to nothing for a bin", decoded);
		patches.recordVarbit(bin, varbitValue, decoded);
		availability.setAvailable(bin, true);

		// The run tick no longer covers the seven beside the allotments - it means the guild's
		// big bin alone. What puts a small bin in a run is the fodder toggle, so that is what a
		// fixture for one has to set. See CompostBin.coveredByTheBinTick.
		when(compostRun.isFodderEnabled()).thenReturn(true);
		when(compostRun.getFodderCrops()).thenReturn(java.util.Collections.emptySet());
	}

	private static final Set<PatchImplementation> BINS = EnumSet.of(PatchImplementation.COMPOST);
	private static final Set<PatchImplementation> BIG_BINS =
		EnumSet.of(PatchImplementation.BIG_COMPOST);

	/**
	 * An empty bin beside the allotments banks <b>nothing</b>, which is the whole change.
	 *
	 * <p>It used to bank fifteen un-noted items - most of an inventory - carried all day to a bin
	 * with no bank anywhere near it. Measured against {@code BankLocations}: Catherby ~23 tiles,
	 * Falador ~65, Ardougne ~96, and three of the seven have no seeded bank at all. Reported from
	 * play as running a whole farm run with a pack full of pineapples. It is fed from the harvest
	 * standing next to it now; see {@code CompostBinPlan}.
	 */
	@Test
	public void anEmptyAllotmentBinBanksNothing()
	{
		binAt(0);
		when(compostRun.getFills())
			.thenReturn(java.util.Collections.singletonList(ItemID.PINEAPPLE));
		names.put(ItemID.PINEAPPLE, "Pineapple");
		bankHolds(ItemID.PINEAPPLE, 40);

		assertNull("the seven are never the bank's problem", itemNamed(BINS, "Pineapple"));
	}

	/**
	 * The guild's bin still banks its fill, counted un-noted and by capacity.
	 *
	 * <p>The one bin with a bank in its own region, so it is the one a run can be asked to carry
	 * for. The quantity doubles as the pack-space warning: thirty un-noted items is more than an
	 * inventory, and the row is where that gets said before the trip rather than at the bin.
	 */
	@Test
	public void theGuildBinBanksItsFill()
	{
		bigBinAt(0);
		when(compostRun.getFills())
			.thenReturn(java.util.Collections.singletonList(ItemID.PINEAPPLE));
		names.put(ItemID.PINEAPPLE, "Pineapple");
		bankHolds(ItemID.PINEAPPLE, 40);

		LoadoutItem fill = itemNamed(BIG_BINS, "Pineapple");
		assertNotNull("the fill is what the bin trip is for", fill);
		assertEquals(LoadoutItem.Need.WITHDRAW, fill.getNeed());
		assertEquals("bulk produce the trip is for, so it lays out with the seeds",
			LoadoutItem.Category.BIN_FILL, fill.getCategory());
	}

	/**
	 * The fill row asks for one pack-load, never the run's whole total.
	 *
	 * <p>Bins take their fill un-noted and nothing supercompostable stacks, so the item count
	 * IS the slot count - and a run over every empty bin wants far more than an inventory
	 * holds. It asked for all of it, which drove the bank highlight's withdraw count too.
	 */
	@Test
	public void theFillNeverExceedsOnePackLoad()
	{
		bigBinAt(0);
		when(compostRun.getFills())
			.thenReturn(java.util.Collections.singletonList(ItemID.PINEAPPLE));
		names.put(ItemID.PINEAPPLE, "Pineapple");
		bankHolds(ItemID.PINEAPPLE, 500);

		LoadoutItem fill = itemNamed(BIG_BINS, "Pineapple");
		assertNotNull(fill);
		assertTrue("asked for " + fill.getQuantity() + ", which no inventory holds",
			fill.getQuantity() <= com.dooglemaps.guide.CarriedItems.INVENTORY_SIZE);
		assertTrue("and the withdraw count follows it",
			fill.getWithdrawCount() <= com.dooglemaps.guide.CarriedItems.INVENTORY_SIZE);
	}

	/**
	 * The guild's big bin can be supplied, even though no pack holds a full fill.
	 *
	 * <p>The reported dead end: a compost-only run at the Farming Guild, whose ONLY bin is the
	 * big one at thirty un-noted items against twenty-eight slots. Rounding the ask down to
	 * whole bins gave zero every time, so the row went MISSING, the supply leg it gates never
	 * completed, and the run stood at the bank being told to fetch what it could not hold.
	 */
	@Test
	public void theBigBinIsSuppliableEvenThoughAPackCannotHoldAFullFill()
	{
		bigBinAt(0);
		when(compostRun.getFills())
			.thenReturn(java.util.Collections.singletonList(ItemID.PINEAPPLE));
		names.put(ItemID.PINEAPPLE, "Pineapple");
		bankHolds(ItemID.PINEAPPLE, 500);

		Set<PatchImplementation> bins = EnumSet.of(PatchImplementation.BIG_COMPOST);
		LoadoutItem fill = itemNamed(bins, "Pineapple");
		assertNotNull("the big bin still wants supplying", fill);
		assertEquals("not missing - it is a withdrawal a pack can actually make",
			LoadoutItem.Need.WITHDRAW, fill.getNeed());
		assertTrue("and it asks for a packful: " + fill.getQuantity(),
			fill.getQuantity() > 0
				&& fill.getQuantity() <= com.dooglemaps.guide.CarriedItems.INVENTORY_SIZE);
		assertTrue("saying why one load cannot do it: " + fill.getReason(),
			fill.getReason().contains("more than one load"));
	}

	/** A bin at a chosen varbit in the guild, ticked into the run. */
	private void bigBinAt(int varbitValue)
	{
		FarmPatch bin = FarmingWorldData.getPatches(PatchImplementation.BIG_COMPOST).get(0);
		assertNotNull("no big compost bin in the generated world data", bin);
		ProduceState decoded = bin.getImplementation().forVarbitValue(varbitValue);
		assertNotNull(decoded);
		patches.recordVarbit(bin, varbitValue, decoded);
		availability.setAvailable(bin, true);

		when(plannerRunOptions.isSelected(
			com.dooglemaps.data.RunOption.full(
				com.dooglemaps.data.PlantingGroup.of(PatchImplementation.COMPOST))))
			.thenReturn(true);
	}

	/**
	 * The fill row asks for the room there is, not the room an empty pack would have.
	 *
	 * <p>A compost run is routinely a compost <i>and</i> run - a contract's seed, the secateurs,
	 * a handful of teleports - and the budget used to be {@code INVENTORY_SIZE} less a slot for
	 * the ash, as though the produce had the whole pack to itself.
	 */
	@Test
	public void theFillAsksOnlyForWhatThePackCanActuallyHold()
	{
		bigBinAt(0);
		when(compostRun.getFills())
			.thenReturn(java.util.Collections.singletonList(ItemID.PINEAPPLE));
		names.put(ItemID.PINEAPPLE, "Pineapple");
		bankHolds(ItemID.PINEAPPLE, 500);
		packFullExcept(1);

		LoadoutItem fill = itemNamed(BIG_BINS, "Pineapple");
		assertNotNull("one slot is still worth a pineapple", fill);
		assertEquals("one free slot, so one item", 1, fill.getQuantity());
		assertEquals("and the withdraw count follows it", 1, fill.getWithdrawCount());
	}

	/**
	 * A pack with no room left does not hold the run at the bank.
	 *
	 * <p>The reported dead end, and the reason this is asserted on
	 * {@link RunLoadout#anythingLeftToWithdraw} rather than on the row: {@code BIN_FILL} is in
	 * {@code CANNOT_PROCEED_WITHOUT}, so a row stuck on {@code WITHDRAW} for produce that could
	 * never fit kept {@code RunPlanner.suppliesOutstanding} true forever. The player was told to
	 * fetch pineapples they had no room for and was never routed to the bin at all.
	 */
	@Test
	public void aFullPackDoesNotHoldTheRunAtTheBank()
	{
		bigBinAt(0);
		when(compostRun.getFills())
			.thenReturn(java.util.Collections.singletonList(ItemID.PINEAPPLE));
		names.put(ItemID.PINEAPPLE, "Pineapple");
		bankHolds(ItemID.PINEAPPLE, 500);
		carrying(ItemID.PINEAPPLE, 5);
		packFullExcept(0);

		LoadoutItem fill = itemNamed(BIG_BINS, "Pineapple");
		assertNotNull(fill);
		assertEquals("what is carried is the whole of what this trip can take",
			LoadoutItem.Need.HAVE, fill.getNeed());
		assertFalse("the supply leg has to be able to close",
			loadout.anythingLeftToWithdraw(BIG_BINS));
	}

	/**
	 * Fills the pack down to this many free slots with items nothing else looks at.
	 *
	 * <p>One slot per distinct id, which is how {@code CarriedItems.record} counts them - so the
	 * filler has to be that many different ids rather than one big stack.
	 */
	private void packFullExcept(int freeSlots)
	{
		int used = carriedStock.size();
		for (int i = 0; used + i < com.dooglemaps.guide.CarriedItems.INVENTORY_SIZE - freeSlots; i++)
		{
			carriedStock.put(FILLER_BASE + i, 1);
		}
		carried.record(containerOf(flatten(carriedStock)));
		assertEquals("fixture did not leave the room it meant to",
			freeSlots, carried.getFreeSlots());
	}

	/** Ids no data table in the plugin knows about, so the filler cannot be mistaken for kit. */
	private static final int FILLER_BASE = 900_000;

	// ------------------------------------------- the guild bin's two sources

	/** Watermelon, harvestable — see PatchRules' allotment table. */
	private static final int WATERMELON_READY = 62;
	/** The same crop, still growing. */
	private static final int WATERMELON_GROWING = 52;

	/**
	 * The guild's own allotments feed its bin, so nothing is banked for it.
	 *
	 * <p>The guild is the only place with allotments, a bin and a bank all in one region, so it
	 * is the only bin that can be supplied either way. Every input to the choice is known before
	 * the run starts, which is what lets the loadout answer it rather than the player.
	 */
	@Test
	public void theGuildBinDoesNotBankWhatItsOwnAllotmentsWillGrow()
	{
		bigBinAt(0);
		guildAllotmentAt(WATERMELON_READY);
		fodder(ItemID.WATERMELON);
		binFill(ItemID.PINEAPPLE);

		assertNull("thirty watermelons are about to be picked ten tiles away",
			itemNamed(BIG_BINS, "Pineapple"));
	}

	/** Not ready this trip is the same as not there: the bank has to supply it. */
	@Test
	public void theGuildBinBanksWhenItsAllotmentsAreStillGrowing()
	{
		bigBinAt(0);
		guildAllotmentAt(WATERMELON_GROWING);
		fodder(ItemID.WATERMELON);
		binFill(ItemID.PINEAPPLE);

		assertNotNull("nothing to pick yet, so bring some", itemNamed(BIG_BINS, "Pineapple"));
	}

	/** A crop the player will not spare is the same as no crop at all. */
	@Test
	public void theGuildBinBanksWhenTheCropIsNotOnTheFodderList()
	{
		bigBinAt(0);
		guildAllotmentAt(WATERMELON_READY);
		fodder(ItemID.POTATO);
		binFill(ItemID.PINEAPPLE);

		assertNotNull("watermelons are not spared, so they are not a source",
			itemNamed(BIG_BINS, "Pineapple"));
	}

	/** And with the whole idea switched off, the bank is the only source there is. */
	@Test
	public void theGuildBinBanksWhenFodderIsOff()
	{
		bigBinAt(0);
		guildAllotmentAt(WATERMELON_READY);
		when(compostRun.isFodderEnabled()).thenReturn(false);
		when(compostRun.getFodderCrops())
			.thenReturn(java.util.Collections.singleton(ItemID.WATERMELON));
		binFill(ItemID.PINEAPPLE);

		assertNotNull(itemNamed(BIG_BINS, "Pineapple"));
	}

	/** Records the guild's north allotment at a varbit and switches the patch on. */
	private void guildAllotmentAt(int varbitValue)
	{
		FarmPatch allotment = null;
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.ALLOTMENT))
		{
			if (patch.getRegion().getRegionId() == 4922)
			{
				allotment = patch;
				break;
			}
		}
		assertNotNull("fixture: the Farming Guild should have an allotment", allotment);
		ProduceState decoded = allotment.getImplementation().forVarbitValue(varbitValue);
		assertNotNull("varbit " + varbitValue + " decodes to nothing", decoded);
		patches.recordVarbit(allotment, varbitValue, decoded);
		availability.setAvailable(allotment, true);
	}

	private void fodder(int... itemIds)
	{
		java.util.Set<Integer> crops = new java.util.LinkedHashSet<>();
		for (int itemId : itemIds)
		{
			crops.add(itemId);
		}
		when(compostRun.isFodderEnabled()).thenReturn(true);
		when(compostRun.getFodderCrops()).thenReturn(crops);
	}

	private void binFill(int itemId)
	{
		when(compostRun.getFills())
			.thenReturn(java.util.Collections.singletonList(itemId));
		names.put(itemId, "Pineapple");
		bankHolds(itemId, 500);
	}

	/** With no fill chosen there is no fill row - and no guessing at one. */
	@Test
	public void noFillChosenMeansNoFillRow()
	{
		binAt(0);
		when(compostRun.getFills()).thenReturn(java.util.Collections.emptyList());
		names.put(ItemID.PINEAPPLE, "Pineapple");
		bankHolds(ItemID.PINEAPPLE, 40);

		assertNull(itemNamed(BINS, "Pineapple"));
	}

	/**
	 * A ready bin of supercompost banks the ash when the upgrade is ticked - 25, the bin's
	 * whole price, not the two-per-bucket one.
	 */
	@Test
	public void aReadyBinOfSupercompostBanksTheAshWhenTicked()
	{
		binAt(62);
		when(compostRun.isAshing()).thenReturn(true);
		bankHolds(com.dooglemaps.data.CompostBin.VOLCANIC_ASH, 100);

		LoadoutItem ash = itemNamed(BINS, "Volcanic ash");
		assertNotNull("the upgrade was asked for", ash);
		assertEquals("the bin price, while everything is still in it", 25, ash.getQuantity());
		assertEquals(LoadoutItem.Need.WITHDRAW, ash.getNeed());
	}

	/**
	 * A ready bin banks NO fill while the compost is still in it.
	 *
	 * <h2>Two dead ends have fought over this line, and this is the second's turn</h2>
	 *
	 * This test used to assert the opposite — "a bin about to be emptied is a bin about to
	 * want filling" — because the fill was once counted only for bins <i>already</i> empty, so
	 * an all-ready run packed buckets and ash and no produce, and the run ended with the bin
	 * standing open. Counting the ready bin's fill up front fixed that and caused this:
	 * <i>"I'm getting prompted to take watermelons out for the big bin before emptying it
	 * first"</i>. The fill cannot go into a bin still holding compost, and it cannot share the
	 * pack with the emptying either — that phase wants free slots for the buckets — so the
	 * withdrawal did not just come early, it blocked the step in front of it, and the player
	 * banked it again at the guild to make room. Reported from play.
	 *
	 * <p>The first dead end stays fixed by different means than the up-front row:
	 * {@code divertForSupplies} now exists, so the moment the emptied bin's varbit reads empty
	 * the fill row goes WITHDRAW (see {@code anEmptiedBigBinAsksForItsFill}) and a run out of
	 * work with a withdrawal outstanding is sent back for another load rather than ended — at
	 * the guild, the one place a bank-fed bin exists, that is a walk to the chest in-region.
	 */
	@Test
	public void aReadyBinBanksNoFillUntilItIsEmptied()
	{
		bigBinAt(62);
		when(compostRun.getFills())
			.thenReturn(java.util.Collections.singletonList(ItemID.PINEAPPLE));
		names.put(ItemID.PINEAPPLE, "Pineapple");
		bankHolds(ItemID.PINEAPPLE, 500);

		assertNull("the pack belongs to the emptying until the compost is out",
			itemNamed(BIG_BINS, "Pineapple"));
	}

	/** Unticked, the ash stays out of the list however much of it the bank holds. */
	@Test
	public void ashStaysOffTheListWhenNotTicked()
	{
		binAt(62);
		when(compostRun.isAshing()).thenReturn(false);
		bankHolds(com.dooglemaps.data.CompostBin.VOLCANIC_ASH, 100);

		assertNull(itemNamed(BINS, "Volcanic ash"));
	}

	/** A ready bin's buckets come from the leprechaun beside it, not from a bank. */
	@Test
	public void bucketsForAReadyBinAreCollectedAtTheLeprechaun()
	{
		binAt(62);
		leprechaunHolds(FarmingTool.EMPTY_BUCKET, 1000);

		LoadoutItem buckets = itemNamed(BINS, "Empty bucket");
		assertNotNull("fifteen compost need fifteen buckets", buckets);
		assertEquals(LoadoutItem.Need.AT_LEPRECHAUN, buckets.getNeed());
		assertEquals("one per compost in the bin", 15, buckets.getQuantity());
	}

	/** A bottomless compost bucket replaces the fifteen ordinary ones outright. */
	@Test
	public void aBottomlessBucketReplacesTheEmpties()
	{
		binAt(62);
		leprechaunHolds(FarmingTool.EMPTY_BUCKET, 1000);
		bankHolds(ItemID.BOTTOMLESS_COMPOST_BUCKET, 1);

		assertNotNull(itemNamed(BINS, "Bottomless compost bucket"));
		assertNull("no ordinary buckets beside it", itemNamed(BINS, "Empty bucket"));
	}

	/**
	 * The vinery needs a gardening trowel, without which its saltpetre cannot be applied.
	 *
	 * <p>The loadout banked twelve patches' worth of fertiliser and nothing to spread it
	 * with: "players will need to use saltpetre on the patches with a gardening trowel in
	 * order to treat the soil before planting", and no other patch family prepares its soil,
	 * so nothing else had ever asked for one.
	 */
	@Test
	public void aGrapeRunCarriesTheTrowelItsSaltpetreNeeds()
	{
		readyPatchOf(PatchImplementation.GRAPES);
		bankHolds(ItemID.GARDENING_TROWEL, 1);

		Set<PatchImplementation> vinery = EnumSet.of(PatchImplementation.GRAPES);
		LoadoutItem trowel = itemNamed(vinery, "Gardening trowel");
		assertNotNull("no trowel, no soil treatment, no planting", trowel);
		assertEquals(LoadoutItem.Need.WITHDRAW, trowel.getNeed());
	}

	/** ...and a tree run with its saplings already made asks for none: nothing is being potted. */
	@Test
	public void aTreeRunWithNothingToPotDoesNotAskForTheTrowel()
	{
		readyPatchOf(PatchImplementation.TREE);
		selection.toggle(Seed.MAPLE);
		saplingsInInventory(Seed.MAPLE, 10);
		bankHolds(ItemID.GARDENING_TROWEL, 1);

		assertNull("the saplings are made; there is nothing to sow into a pot",
			itemNamed(EnumSet.of(PatchImplementation.TREE), "Gardening trowel"));
	}

	/**
	 * A tree run that still has to make its saplings asks for the trowel.
	 *
	 * <p>Reported from play. The pot row's wording had the trowel as an aside about filling an
	 * empty pot, which reads as nothing at all to anyone whose pots are already filled. It is
	 * not an aside: the trowel has to be in the inventory to sow the seed into the pot at all —
	 * "using it on a plant pot while a gardening trowel is in the inventory" is the wiki's
	 * wording for every tree seed — so a player with filled pots, a watering can and no trowel
	 * was told they had everything and could pot nothing.
	 */
	@Test
	public void aTreeRunThatHasToPotAsksForTheTrowel()
	{
		readyPatchOf(PatchImplementation.TREE);
		selection.toggle(Seed.MAPLE);
		// Seeds, not saplings: this is the run that has potting to do.
		seedsInBank(Seed.MAPLE, 10);
		bankHolds(net.runelite.api.gameval.ItemID.PLANTPOT_COMPOST, 10);
		bankHolds(ItemID.GARDENING_TROWEL, 1);

		Set<PatchImplementation> trees = EnumSet.of(PatchImplementation.TREE);
		assertNotNull("the pot is asked for", itemNamed(trees, "Filled plant pot"));

		LoadoutItem trowel = itemNamed(trees, "Gardening trowel");
		assertNotNull("no trowel, no sapling", trowel);
		assertEquals(LoadoutItem.Need.WITHDRAW, trowel.getNeed());
	}

	/** Carrying one is the end of it, and the row says so rather than nagging. */
	@Test
	public void aCarriedTrowelIsCheckedOffRatherThanWithdrawn()
	{
		readyPatchOf(PatchImplementation.TREE);
		selection.toggle(Seed.MAPLE);
		seedsInBank(Seed.MAPLE, 10);
		carrying(ItemID.GARDENING_TROWEL, 1);

		LoadoutItem trowel = itemNamed(EnumSet.of(PatchImplementation.TREE), "Gardening trowel");
		assertNotNull(trowel);
		assertEquals(LoadoutItem.Need.HAVE, trowel.getNeed());
	}

	/**
	 * The leprechaun's is the fallback, not the answer, because the potting happens here.
	 *
	 * <p>His store is at the patch and the potting is at the bank — a potted seed wants five
	 * minutes to become a sapling, and the travel is what pays for the wait. So a trowel only
	 * he is holding still gets a row, saying what it costs: the potting waits for the first
	 * patch.
	 */
	@Test
	public void aTrowelOnlyTheLeprechaunHasSaysThePottingWaits()
	{
		readyPatchOf(PatchImplementation.TREE);
		selection.toggle(Seed.MAPLE);
		seedsInBank(Seed.MAPLE, 10);
		leprechaunHolds(FarmingTool.GARDENING_TROWEL, 1);

		LoadoutItem trowel = itemNamed(EnumSet.of(PatchImplementation.TREE), "Gardening trowel");
		assertNotNull(trowel);
		assertEquals(LoadoutItem.Need.AT_LEPRECHAUN, trowel.getNeed());
		assertTrue(trowel.getReason(),
			trowel.getReason().toLowerCase().contains("first patch"));
	}

	/**
	 * A picked, protected crop's payment counts even on a trip that plants none of it.
	 *
	 * <p>Reported from play: twenty-five coconuts marked <i>finished crops - deposit them</i> at
	 * the bank they had just been withdrawn from. The coconut is the magic tree's protection and
	 * the palm's harvest at once, and the deposit marks keep only what the payment rows name — so
	 * a trip whose allocation found no magic patch left the harvest half to speak for it.
	 */
	@Test
	public void aPickedProtectedCropsPaymentIsNeverJustHarvest()
	{
		readyPatchOf(PatchImplementation.TREE);
		selection.toggle(Seed.MAGIC);
		protection.setProtecting(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.TREE), Seed.MAGIC, true);

		// Owned nowhere, so the allocation cannot give it a patch and no payment row is built.
		Set<PatchImplementation> trees = EnumSet.of(PatchImplementation.TREE);
		assertNull("no magic to plant, so nothing on the shopping list",
			itemNamed(trees, "Coconut"));

		assertTrue("the coconuts are still the run's currency, not spare harvest",
			loadout.paymentsForSelectedSeeds(trees).contains(ItemID.COCONUT));
	}

	/** A crop the player has turned protection off for keeps nothing back. */
	@Test
	public void anUnprotectedCropsPaymentIsNotKept()
	{
		readyPatchOf(PatchImplementation.TREE);
		selection.toggle(Seed.MAGIC);
		protection.setProtecting(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.TREE), Seed.MAGIC, false);

		assertFalse("protection off means the coconuts are just coconuts",
			loadout.paymentsForSelectedSeeds(EnumSet.of(PatchImplementation.TREE))
				.contains(ItemID.COCONUT));
	}

	/**
	 * The planner and the loadout allocate the same run, so a leg cannot hold for a phantom.
	 *
	 * <h2>The reported run</h2>
	 *
	 * A tree and hardwood trip parked at the Farming Guild: every sapling already in the pack,
	 * an empty withdraw list, and the route still pointed at the seed vault. The planner's copy
	 * of the allocation ran with {@code ProtectionBudget.NONE}, so a protected crop the player
	 * could not pay for drew patches there and none in the loadout's — and the leg's exit
	 * condition is "nothing outstanding", which a seed that was never on the list can never
	 * clear.
	 *
	 * <p>Magic is the unaffordable one here: picked, protected, and not a coconut anywhere. It
	 * ranks above maple, so an unbudgeted allocation plants it and sends the run to the vault
	 * holding it. Budgeted, maple takes every patch and they are all in the pack already.
	 */
	@Test
	public void theSupplyLegDoesNotHoldForACropThePaymentsCannotAfford()
	{
		int patches = readyAllTreePatches();
		selection.toggle(Seed.MAGIC);
		selection.toggle(Seed.MAPLE);
		protection.setProtecting(
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.TREE), Seed.MAGIC, true);

		// The magic is in the vault, which is where the run used to be sent for it.
		seeds.record(com.dooglemaps.state.SeedSource.SEED_VAULT.getContainerId(),
			containerOf(Seed.MAGIC.getPlantedItemID(), 5));
		// The maple is already on the player, so a budgeted plan has nothing to collect.
		saplingsInInventory(Seed.MAPLE, patches);

		Set<PatchImplementation> trees = EnumSet.of(PatchImplementation.TREE);
		assertTrue("the loadout has nothing to fetch",
			com.dooglemaps.bank.LoadoutSummary.forItems(loadout.forRun(trees)).isEmpty());

		planner.start(trees);
		assertTrue("so the planner must not be waiting on a container either",
			planner.getSupplySources().isEmpty());
	}

	/** The vinery and the potting both want one, and one row is what a player can act on. */
	@Test
	public void aVineryAndTreeRunListsTheTrowelOnce()
	{
		readyPatchOf(PatchImplementation.GRAPES);
		readyPatchOf(PatchImplementation.TREE);
		selection.toggle(Seed.MAPLE);
		seedsInBank(Seed.MAPLE, 10);
		bankHolds(ItemID.GARDENING_TROWEL, 1);

		Set<PatchImplementation> both =
			EnumSet.of(PatchImplementation.GRAPES, PatchImplementation.TREE);
		int rows = 0;
		for (LoadoutItem item : loadout.forRun(both))
		{
			if (item.getItemId() == ItemID.GARDENING_TROWEL)
			{
				rows++;
			}
		}
		assertEquals("one trowel, one row", 1, rows);
	}

	/**
	 * A coral run's tool list matches the nursery's own menus: no rake (the empty state
	 * merely decodes as weeds - "Coral nursery[Inspect,Guide]", no Rake option), no dibber
	 * (a frag is placed), secateurs in (diseased coral is pruned), and the spade stays for
	 * the dead-coral Clear.
	 */
	@Test
	public void aCoralRunCarriesTheNurserysOwnTools()
	{
		readyPatchOf(PatchImplementation.CORAL);
		selection.toggle(com.dooglemaps.data.PlantingGroup.of(PatchImplementation.CORAL),
			Seed.ELKHORN_CORAL);
		bankHolds(ItemID.RAKE, 1);
		bankHolds(ItemID.DIBBER, 1);
		bankHolds(ItemID.SECATEURS, 1);

		Set<PatchImplementation> coral = EnumSet.of(PatchImplementation.CORAL);
		assertNull("a nursery is never raked", itemNamed(coral, "Rake"));
		assertNull("a frag is placed, not dibbed", itemNamed(coral, "Seed dibber"));
		assertNotNull("diseased coral is pruned", itemNamed(coral, "Secateurs"));
		assertNotNull("dead coral still clears with a spade", itemNamed(coral, "Spade"));
	}

	/** A bin-only run wants no ground tools at all - two lidded boxes need no rake or spade. */
	@Test
	public void aBinOnlyRunNeedsNoGroundTools()
	{
		binAt(0);
		bankHolds(ItemID.RAKE, 1);
		bankHolds(ItemID.SPADE, 1);

		assertNull(itemNamed(BINS, "Rake"));
		assertNull(itemNamed(BINS, "Spade"));
		assertNull(itemNamed(BINS, "Seed dibber"));
	}

	private LoadoutItem itemNamed(Set<PatchImplementation> types, String name)
	{
		for (LoadoutItem item : loadout.forRun(types))
		{
			if (name.equals(item.getName()))
			{
				return item;
			}
		}
		return null;
	}

	/** A crop with no sapling form contributes exactly one id, not a speculative second. */
	@Test
	public void anOrdinarySeedStillMatchesOnlyItself()
	{
		assertEquals(java.util.Collections.singleton(Seed.RANARR.getItemID()),
			RunLoadout.bankFormsOf(Seed.RANARR.getItemID()));
	}

	/**
	 * The row-aware version agrees with the bare-id one for a crop with no sapling form: there
	 * is no potting shortfall to weigh, so there is only ever the one id to fetch.
	 */
	@Test
	public void anOrdinarySeedStillMatchesOnlyItselfByRow()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		seedsInBank(Seed.RANARR, 5);

		assertEquals(java.util.Collections.singleton(Seed.RANARR.getItemID()),
			RunLoadout.formsToFetch(onlySeed()));
	}

	/**
	 * An item on the teleport list is picked up out of the bank, by name.
	 *
	 * <p>What reaches a farming region is a fact about the map and lives in {@code TeleportItems}.
	 * What <i>you</i> always bring is not derivable at all, which is why it is a setting — and
	 * matching it by name against the bank is what lets a list of words become item ids without an
	 * index of every item in the game.
	 */
	@Test
	public void anItemOnTheTeleportListIsOfferedFromTheBank() throws Exception
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);

		int gamesNecklace = 3853;
		bankHolds(gamesNecklace, 1);
		names.put(gamesNecklace, "Games necklace(8)");
		when(config.teleportItems()).thenReturn("Games necklace(8), Skills necklace(6)");

		LoadoutItem entry = find(LoadoutItem.Category.TELEPORT, "Games necklace(8)");
		assertNotNull("it is on the list and it is in the bank", entry);
		assertEquals(LoadoutItem.Need.WITHDRAW, entry.getNeed());
		assertEquals("On your teleport list", entry.getReason());
	}

	/**
	 * A listed teleport you carry is still offered — with only a placeholder behind it.
	 *
	 * <p>Reported from play: every house tab on the player, none banked, the bank holding just
	 * their placeholder — which is not contents, and rightly so. Matching the list against the
	 * bank alone meant the tabs had no row at all, so the filter would not even show their
	 * placeholder, until one was deposited and became contents. The pack is as good an index
	 * of "items you own whose names we know" as the bank is.
	 */
	@Test
	public void aListedTeleportCarriedButNotBankedIsStillOffered() throws Exception
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);

		int houseTab = 8013;
		carrying(houseTab, 24);
		names.put(houseTab, "Teleport to house");
		when(config.teleportItems()).thenReturn("Teleport to house");

		// The universal table offers it first, but the row carries the game's own name -
		// "tablet" is the table's label, not what the player reads on their screen.
		LoadoutItem entry = find(LoadoutItem.Category.TELEPORT, "Teleport to house");
		assertNotNull("all of them on you and none banked is still owning them", entry);
		assertEquals("and nothing to withdraw - they are in your pack",
			LoadoutItem.Need.HAVE, entry.getNeed());
	}

	/**
	 * The shipped default names have to match items the player actually owns.
	 *
	 * <h2>A default that could never match anything</h2>
	 *
	 * The teleport setting is a list of names, resolved by comparing them with the names of things
	 * in the bank. The default shipped {@code "Teleport to house tablet"}; the game calls item 8013
	 * {@code "Teleport to house"}. A name that matches nothing fails silently by design, so the
	 * house tablet was never offered to anyone who had not edited the setting by hand — and the
	 * test that was meant to guard the defaults compared them against our own table rather than
	 * against the game, so it agreed with itself and passed.
	 *
	 * <p>Both spellings now resolve, which is what {@code TeleportItems.nameFor} is for. This pins
	 * the game's, because that is the one that was broken and the one a player would type.
	 */
	@Test
	public void theGameSpellingOfATeleportResolvesTooNotJustOurLabel() throws Exception
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);

		int houseTablet = ItemID.POH_TABLET_TELEPORTTOHOUSE;
		bankHolds(houseTablet, 25);
		names.put(houseTablet, "Teleport to house");
		when(config.teleportItems()).thenReturn(TeleportItems.defaultNames());

		assertNotNull("the shipped default has to reach the item the game names differently",
			find(LoadoutItem.Category.TELEPORT, "Teleport to house"));
	}

	/**
	 * Something you are already carrying is not a withdrawal, teleports included.
	 *
	 * <p>{@code CarriedItems} is fed only by container events, so a plugin switched on mid-session
	 * knew nothing about a pack nobody had touched — and everything in it read as missing and went
	 * on the withdraw list. Reported from play as being told to bank for house tablets with a stack
	 * of them in the inventory. The priming that fixes it is
	 * {@code CarriedItems.relearnFromClient}; this pins the behaviour it restores.
	 */
	@Test
	public void aTeleportInYourPackIsNotAWithdrawal() throws Exception
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);

		int houseTablet = ItemID.POH_TABLET_TELEPORTTOHOUSE;
		bankHolds(houseTablet, 25);
		names.put(houseTablet, "Teleport to house");
		when(config.teleportItems()).thenReturn("Teleport to house");
		carrying(houseTablet, 4);

		LoadoutItem entry = find(LoadoutItem.Category.TELEPORT, "Teleport to house");
		assertNotNull(entry);
		assertEquals("they are in the pack, whatever the bank also has",
			LoadoutItem.Need.HAVE, entry.getNeed());
	}

	/**
	 * What the supply leg waits for, and what it does not.
	 *
	 * <h2>The gate the planner used to derive for itself</h2>
	 *
	 * {@code RunPlanner.suppliesOutstanding} worked out its own answer from a tool that was
	 * bank-only and a seed the run was short of. That is most of the list and it missed three
	 * things that were on it: the axe — which {@code ToolNeeds} has never known about — the
	 * protection payment, and a contract's own seed. Each was reported from play as reaching a
	 * patch unable to do anything there.
	 *
	 * <p>It asks the list now. Which means the list has to be clear about what is genuinely
	 * blocking: a teleport is a convenience and holding the run at a bank for one would be the
	 * plugin refusing to let somebody play.
	 */
	@Test
	public void theAxeHoldsTheSupplyLegButATeleportDoesNot() throws Exception
	{
		readyTreePatch();

		int axe = ItemID.RUNE_AXE;
		bankHolds(axe, 1);
		woodcuttingLevel(99);

		assertTrue("an axe in the bank is a reason to still be at the bank",
			loadout.anythingLeftToWithdraw(EnumSet.of(PatchImplementation.TREE)));

		carrying(axe, 1);
		assertFalse("and picking it up is what finishes the errand",
			loadout.anythingLeftToWithdraw(EnumSet.of(PatchImplementation.TREE)));

		int houseTablet = ItemID.POH_TABLET_TELEPORTTOHOUSE;
		bankHolds(houseTablet, 25);
		names.put(houseTablet, "Teleport to house");
		when(config.teleportItems()).thenReturn("Teleport to house");

		assertFalse("a teleport left in the bank is the player's business, not a blocker",
			loadout.anythingLeftToWithdraw(EnumSet.of(PatchImplementation.TREE)));
	}

	/**
	 * A wildcard covers every charge of an item, which is the whole reason it is wanted.
	 *
	 * <p>Jewellery is the case: a games necklace is eight different items and the game names each
	 * one for its charges, so an exact list means eight entries that all mean "my games necklace".
	 *
	 * <h2>Matched by every charge, offered as one</h2>
	 *
	 * This used to assert a row per charge, which is what the code did and is not what a wildcard
	 * is for. Matching every charge is the point — {@code isOnTeleportList} still answers yes to
	 * all of them, so the bank lights them all — but a loadout row is <i>advice</i>, and "withdraw
	 * your games necklace" is one piece of advice however many charges are in the bank.
	 *
	 * <p>Reported from play, on a teleport crystal: told to withdraw the (5) and the (1) while the
	 * (2) was in the pack, because each charge was a different item and none was the one carried.
	 */
	@Test
	public void aWildcardMatchesEveryChargeOfAnItem()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);

		bankHolds(3853, 1);
		names.put(3853, "Games necklace(8)");
		bankHolds(3861, 1);
		names.put(3861, "Games necklace(1)");
		when(config.teleportItems()).thenReturn("Games necklace*");

		int rows = 0;
		for (LoadoutItem item : loadout.forRun(HERBS))
		{
			if (item.getCategory() == LoadoutItem.Category.TELEPORT)
			{
				rows++;
			}
		}
		assertEquals("one necklace, one row, whatever the bank is holding", 1, rows);

		assertTrue("and every charge is still matched, so the bank lights them all",
			loadout.isOnTeleportList(3853) && loadout.isOnTeleportList(3861));
	}

	/** A charge in the pack satisfies the row, whichever charge the bank happens to hold. */
	@Test
	public void aCarriedChargeSatisfiesTheWholeTeleport()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);

		bankHolds(3853, 1);
		names.put(3853, "Games necklace(8)");
		names.put(3861, "Games necklace(1)");
		carrying(3861, 1);
		when(config.teleportItems()).thenReturn("Games necklace*");

		LoadoutItem row = find(LoadoutItem.Category.TELEPORT);
		assertNotNull(row);
		assertEquals("the one in the pack is what the row should name",
			"Games necklace(1)", row.getName());
		assertEquals("and it is not something to go and fetch",
			LoadoutItem.Need.HAVE, row.getNeed());
	}

	/** A wildcard is still a pattern, not a substring: it must not swallow the whole bank. */
	@Test
	public void aWildcardDoesNotMatchUnrelatedItems()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);

		bankHolds(3853, 1);
		names.put(3853, "Games necklace(8)");
		bankHolds(1712, 1);
		names.put(1712, "Amulet of glory(4)");
		when(config.teleportItems()).thenReturn("Games necklace*");

		assertNotNull(find(LoadoutItem.Category.TELEPORT, "Games necklace(8)"));
		assertNull("a glory is not a games necklace",
			find(LoadoutItem.Category.TELEPORT, "Amulet of glory(4)"));
	}

	/** Exact entries keep working alongside wildcards, and stay case-insensitive. */
	@Test
	public void exactAndWildcardEntriesCoexist()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);

		bankHolds(3853, 1);
		names.put(3853, "Games necklace(8)");
		bankHolds(11105, 1);
		names.put(11105, "Skills necklace(6)");
		when(config.teleportItems()).thenReturn("Games necklace*, skills NECKLACE(6)");

		assertNotNull(find(LoadoutItem.Category.TELEPORT, "Games necklace(8)"));
		assertNotNull("case should not matter for an exact entry",
			find(LoadoutItem.Category.TELEPORT, "Skills necklace(6)"));
	}

	/**
	 * The list is the only thing that offers a teleport - the table offers nothing.
	 *
	 * <p>By owner decision: Shortest Path routes with the player's own transport settings and
	 * knows their unlocks in a way a static table never can, so the loadout's teleports are
	 * exactly what the player listed, matched by the game's names, Ground Items style. One
	 * row, the game's name, the list's reason.
	 */
	@Test
	public void theListIsTheOnlySourceOfTeleports()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);

		int houseTablet = ItemID.POH_TABLET_TELEPORTTOHOUSE;
		bankHolds(houseTablet, 1);
		names.put(houseTablet, "Teleport to house");
		when(config.teleportItems()).thenReturn("Teleport to house");

		assertNull("never under this file's own label for the item",
			find(LoadoutItem.Category.TELEPORT, "Teleport to house tablet"));

		LoadoutItem entry = find(LoadoutItem.Category.TELEPORT, "Teleport to house");
		assertNotNull("under the game's own name for it", entry);
		assertEquals("offered because you listed it, which is now the only reason there is",
			"On your teleport list", entry.getReason());
	}

	/** Something listed but not owned says nothing, rather than advising a purchase. */
	@Test
	public void anItemOnTheListYouDoNotOwnIsNotMentioned()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		when(config.teleportItems()).thenReturn("Skills necklace(6)");

		assertNull(find(LoadoutItem.Category.TELEPORT, "Skills necklace(6)"));
	}

	/** An empty list is a real answer: only the teleports the plugin already knows about. */
	@Test
	public void anEmptyTeleportListIsNotAnError()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		when(config.teleportItems()).thenReturn("   ");

		// The point is simply that building the loadout does not throw.
		assertNotNull(loadout.forRun(HERBS));
	}

	/**
	 * Every name on the default list is one the table actually knows.
	 *
	 * <p>The default used to <i>be</i> the table — every entry, derived — which made drift
	 * impossible and the list useless: twenty-eight city tablets nobody travels by. It is a
	 * curated handful now, which reintroduces the one risk deriving it removed, a typo. A name
	 * that matches nothing fails silently, because the list is resolved against bank item names
	 * and an entry that matches none simply never appears.
	 *
	 * <p>So the direction that matters is checked and the other is not: the default must not name
	 * something that does not exist, but the table is free to know teleports the default leaves
	 * off. That is now the normal case rather than a fault.
	 */
	@Test
	public void everyDefaultTeleportIsOneTheTableKnows()
	{
		java.util.Set<String> known = new java.util.HashSet<>();
		for (String name : TeleportItems.allKnownNames().split(","))
		{
			known.add(name.trim());
		}

		for (String name : TeleportItems.defaultNames().split(","))
		{
			assertTrue("\"" + name.trim() + "\" is on the default teleport list but is not a name "
				+ "any table entry uses - a typo here fails silently", known.contains(name.trim()));
		}
	}

	/**
	 * The list decides what is offered; the table only decides why.
	 *
	 * <p>These two overlapped, and the wrong one won. The bank offering came from the table alone,
	 * so cutting the list down changed nothing — every tablet you happened to own still turned up,
	 * because the table knew where it went. The setting's own description promises the opposite:
	 * "cut it down to the ones you actually use".
	 */
	@Test
	public void aTeleportTheTableKnowsIsNotOfferedUnlessItIsOnTheList()
	{
		readyHerbPatch();   // Ardougne
		selection.toggle(Seed.RANARR);
		bankHolds(ItemID.ARDY_CAPE_MEDIUM, 1);
		names.put(ItemID.ARDY_CAPE_MEDIUM, "Ardougne cloak 2");

		when(config.teleportItems()).thenReturn("Ectophial");
		assertNull("the table knows it reaches Ardougne, but the player says they do not use it",
			find(LoadoutItem.Category.TELEPORT, "Ardougne cloak 2"));

		when(config.teleportItems()).thenReturn("Ardougne cloak 2");
		assertNotNull("on the list, so offered - and the table still supplies the reason",
			find(LoadoutItem.Category.TELEPORT, "Ardougne cloak 2"));
	}

	/**
	 * Two seeds for one patch type split the patches; they do not each get a full run.
	 *
	 * <p>Reported from play as ending up with far too many of the second seed. Every picked seed
	 * asked for {@code patches * seedsPerPatch}, so two herbs over eight patches wanted eight of
	 * each — two runs' worth of seed for a one-run trip. It is now the share
	 * {@code SeedAllocation} actually gives them, which is the same division the guide plants
	 * from and the estimate prices.
	 */
	@Test
	public void twoSeedsForOneTypeSplitThePatchesRatherThanDoublingThem()
	{
		java.util.List<FarmPatch> herbs = new java.util.ArrayList<>();
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.HERB))
		{
			ProduceState empty = patch.getImplementation().forVarbitValue(3);
			assertNotNull(empty);
			patches.recordVarbit(patch, 3, empty);
			availability.setAvailable(patch, true);
			herbs.add(patch);
			if (herbs.size() == 4)
			{
				break;
			}
		}

		selection.toggle(Seed.RANARR);
		selection.toggle(Seed.SNAPDRAGON);
		// Two of one and plenty of the other, so the split is forced rather than incidental.
		seedsInBank(Seed.RANARR, 2);
		seedsInBank(Seed.SNAPDRAGON, 50);

		int asked = 0;
		for (LoadoutItem item : loadout.forRun(HERBS))
		{
			if (item.getCategory() == LoadoutItem.Category.SEED)
			{
				asked += item.getQuantity();
			}
		}

		assertEquals("four patches means four seeds between them, not four of each",
			herbs.size(), asked);
	}

	/** An axe you cannot swing is not the answer, however good it is. */
	@Test
	public void anAxeAboveYourLevelIsSkipped()
	{
		readyTreePatch();
		Set<PatchImplementation> trees = EnumSet.of(PatchImplementation.TREE);
		bankHolds(ItemID.DRAGON_AXE, 1);
		bankHolds(ItemID.RUNE_AXE, 1);
		woodcuttingLevel(45);

		assertEquals("dragon wants 61 Woodcutting", "Rune axe", axeIn(trees).getName());
	}

	/** A herb run has nothing to chop, so no axe is suggested. */
	@Test
	public void aHerbRunDoesNotAskForAnAxe()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		bankHolds(ItemID.DRAGON_AXE, 1);
		woodcuttingLevel(99);

		// Not "no tools" — a herb run still wants a rake and a spade, and both are listed. The
		// requirement is that nothing on it needs chopping.
		assertNull("nothing on a herb run has to be cut down",
			axeIn(EnumSet.of(PatchImplementation.HERB)));
	}

	/**
	 * Before a bank has been opened, nothing is reported as missing.
	 *
	 * <p>The bank is only readable while it is open, so on a fresh login everything not already
	 * carried looks absent. Calling that missing would announce that your secateurs and your
	 * protection payments had vanished, every single session, before you had done anything.
	 */
	@Test
	public void anUnreadBankIsUnknownRatherThanMissing()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);

		for (LoadoutItem item : loadout.forRun(HERBS))
		{
			assertTrue(item.getName() + " should not be called missing before we have looked",
				item.getNeed() != LoadoutItem.Need.MISSING);
		}

		// Once a bank has been read and genuinely lacks them, missing is the right word.
		bankHolds(ItemID.SEED_BOX, 1);
		boolean anyMissing = false;
		for (LoadoutItem item : loadout.forRun(HERBS))
		{
			anyMissing |= item.getNeed() == LoadoutItem.Need.MISSING;
		}
		assertTrue("an empty bank we have actually read means missing", anyMissing);
	}

	/**
	 * Worn items count as owned.
	 *
	 * <p>{@code CarriedItems} read only the inventory container, never equipment, so everything
	 * worn was invisible: a Farming cape on your back, an axe in your hand and an Ardougne
	 * cloak round your neck all read as things you did not own and were told to fetch.
	 */
	@Test
	public void wornItemsAreOwned()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		wearing(ItemID.ARDY_CAPE_MEDIUM, 1);
		names.put(ItemID.ARDY_CAPE_MEDIUM, "Ardougne cloak 2");
		when(config.teleportItems()).thenReturn("Ardougne cloak 2");

		List<LoadoutItem> teleports = itemsIn(LoadoutItem.Category.TELEPORT);
		assertEquals(1, teleports.size());
		assertEquals("worn is not something to withdraw",
			LoadoutItem.Need.HAVE, teleports.get(0).getNeed());
	}

	/** Worn items are not in your pack, so they must not eat inventory slots. */
	@Test
	public void wornItemsDoNotConsumeInventorySlots()
	{
		int before = carried.getFreeSlots();
		wearing(ItemID.SKILLCAPE_FARMING, 1);
		assertEquals("equipment is worn, not carried", before, carried.getFreeSlots());
	}

	private static final String[] OUTFIT_PIECE_NAMES = {
		"Farmer's strawhat", "Farmer's jacket", "Farmer's boro trousers", "Farmer's boots"
	};

	/**
	 * The Farmer's outfit is offered piece by piece, not as one line.
	 *
	 * <p>It was absent from the loadout entirely, so anyone who left the legs in the bank was
	 * never told. Each piece is its own row so each gets its own bank slot, the way every other
	 * gear item here does.
	 */
	@Test
	public void theFarmersOutfitIsOfferedWhenIncomplete()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);

		wearing(FarmingOutfit.HAT.getMaleItemId(), 1);
		bankHolds(FarmingOutfit.LEGS.getMaleItemId(), 1);

		LoadoutItem hat = find(LoadoutItem.Category.GEAR, "Farmer's strawhat");
		assertNotNull("the worn hat should be offered", hat);
		assertEquals(LoadoutItem.Need.HAVE, hat.getNeed());

		LoadoutItem legs = find(LoadoutItem.Category.GEAR, "Farmer's boro trousers");
		assertNotNull("the banked legs should be offered", legs);
		assertEquals(LoadoutItem.Need.WITHDRAW, legs.getNeed());
		assertEquals(FarmingOutfit.LEGS.getMaleItemId(), legs.getItemId());

		assertNull("the jacket is owned by neither the player nor the bank",
			find(LoadoutItem.Category.GEAR, "Farmer's jacket"));
		assertNull("the boots are owned by neither the player nor the bank",
			find(LoadoutItem.Category.GEAR, "Farmer's boots"));
	}

	/** Wearing all four says so, rather than nagging. */
	@Test
	public void aCompleteFarmersOutfitReadsAsDone()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		for (FarmingOutfit piece : FarmingOutfit.values())
		{
			wearing(piece.getMaleItemId(), 1);
		}

		for (String name : OUTFIT_PIECE_NAMES)
		{
			LoadoutItem piece = find(LoadoutItem.Category.GEAR, name);
			assertNotNull(name, piece);
			assertEquals(name, LoadoutItem.Need.HAVE, piece.getNeed());
		}
	}

	/** An account with no outfit at all hears nothing, which is most accounts. */
	@Test
	public void noOutfitAnywhereIsNotReportedAsMissing()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);

		for (String name : OUTFIT_PIECE_NAMES)
		{
			assertNull("silence beats noise for something you do not own",
				find(LoadoutItem.Category.GEAR, name));
		}
	}

	/**
	 * The hespori's own trip is a boss fight, so it asks for none of the farm run's gear.
	 *
	 * <p>Reported from play: the gear leg told the player to load their combat setup and then, in
	 * the same breath, to withdraw a farmer's outfit and a seed box for it. The outfit's 2.5% does
	 * reach the harvest, which is why the row survived this long — 315 experience is still not a
	 * reason to fight a boss in a straw hat.
	 */
	@Test
	public void theHesporiTripAsksForNoOutfitAndNoSeedBox()
	{
		readyPatchOf(PatchImplementation.HESPORI);
		bankTheWholeOutfit();
		bankHolds(ItemID.SEED_BOX, 1);

		Set<PatchImplementation> hespori = EnumSet.of(PatchImplementation.HESPORI);
		// Anchored, or three absences would pass on an empty list and prove nothing.
		assertFalse("the spade and the dibber are still asked for",
			loadout.forRun(hespori).isEmpty());
		for (String name : OUTFIT_PIECE_NAMES)
		{
			assertNull("a boss fight is not dressed for experience", itemNamed(hespori, name));
		}
		assertNull("one seed does not want a box", itemNamed(hespori, "Seed box"));
		assertNull("nothing on that drop table is a yield roll",
			itemNamed(hespori, "Magic secateurs"));
	}

	/** A run with farming in it as well keeps every one of them, for the legs that farm. */
	@Test
	public void aRunWithMoreThanTheHesporiStillWantsTheOutfit()
	{
		readyPatchOf(PatchImplementation.HESPORI);
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		bankTheWholeOutfit();
		bankHolds(ItemID.SEED_BOX, 1);

		Set<PatchImplementation> mixed =
			EnumSet.of(PatchImplementation.HESPORI, PatchImplementation.HERB);
		for (String name : OUTFIT_PIECE_NAMES)
		{
			LoadoutItem piece = itemNamed(mixed, name);
			assertNotNull("the swap-back leg is a farm run again", piece);
			assertEquals(name, LoadoutItem.Need.WITHDRAW, piece.getNeed());
		}
		assertNotNull(itemNamed(mixed, "Seed box"));
		assertNotNull(itemNamed(mixed, "Magic secateurs"));
	}

	private void bankTheWholeOutfit()
	{
		for (FarmingOutfit piece : FarmingOutfit.values())
		{
			bankHolds(piece.getMaleItemId(), 1);
		}
	}

	/**
	 * An open herb sack counts as owning one.
	 *
	 * <p>Four ids for one item, and the open variant is the one that matters — it is what
	 * swallows grimy herbs before they reach the inventory.
	 */
	@Test
	public void anOpenHerbSackCountsAsOwningOne()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		wearing(ItemID.SLAYER_HERB_SACK_OPEN, 1);

		List<LoadoutItem> storage = itemsIn(LoadoutItem.Category.STORAGE);
		assertEquals(1, storage.size());
		assertEquals(LoadoutItem.Need.HAVE, storage.get(0).getNeed());
	}

	@Test
	public void noPatchTypesMeansNoLoadout()
	{
		assertTrue(loadout.forRun(EnumSet.noneOf(PatchImplementation.class)).isEmpty());
	}

	/**
	 * Payment rows follow the allocation, exactly as the seed rows do.
	 *
	 * <p>The reported run: five magic saplings, plenty of yews, 49 coconuts, every tree patch
	 * empty. The coconuts protect one magic at 25 each, so the allocation plants one magic and
	 * gives the yews the rest — and the seed rows said so. The payment rows did their own
	 * arithmetic instead: every payment times <i>every</i> patch in the group, which asked for
	 * seven patches of coconuts (175) and seven of spines (70). The spine figure was simply
	 * wrong beside six yews; the coconut one then went over what the player held, so the row
	 * fell off the withdraw list as MISSING — a loadout that said to plant a magic and bring
	 * no coconuts. Reported from play.
	 */
	@Test
	public void paymentRowsFollowTheAllocationRatherThanTheWholeGroup()
	{
		int patches = readyAllTreePatches();
		selection.toggle(Seed.MAGIC);   // picked first, so the magics outrank the yews
		selection.toggle(Seed.YEW);
		// One combined record: seeds.record swaps the whole container in, so two calls of
		// the single-seed helper would leave only the second seed owned.
		bankHolds(Seed.MAGIC.getItemID(), 5);
		bankHolds(Seed.YEW.getItemID(), 74);
		seeds.record(com.dooglemaps.state.SeedSource.BANK.getContainerId(),
			containerOf(Seed.MAGIC.getItemID(), 5, Seed.YEW.getItemID(), 74));
		com.dooglemaps.data.PlantingGroup trees =
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.TREE);
		protection.setProtecting(trees, Seed.MAGIC, true);
		protection.setProtecting(trees, Seed.YEW, true);
		bankHolds(ItemID.COCONUT, 49);
		bankHolds(ItemID.CACTUS_SPINE, 200);

		int magics = 49 / 25;
		int yews = patches - magics;

		LoadoutItem coconuts = payment(ItemID.COCONUT);
		assertNotNull("the one magic the coconuts afford still wants its payment", coconuts);
		assertEquals("25 per magic, for the magics actually being planted",
			25 * magics, coconuts.getQuantity());
		assertEquals("49 in the bank covers that, so it is a withdrawal, not missing",
			LoadoutItem.Need.WITHDRAW, coconuts.getNeed());

		LoadoutItem spines = payment(ItemID.CACTUS_SPINE);
		assertNotNull(spines);
		assertEquals("10 per yew, for the patches the yews actually take",
			10 * yews, spines.getQuantity());
		assertEquals(LoadoutItem.Need.WITHDRAW, spines.getNeed());
	}

	/** And a crop the payments cannot cover at all asks for nothing rather than for zero. */
	@Test
	public void anUnaffordableCropContributesNoPaymentRow()
	{
		readyAllTreePatches();
		selection.toggle(Seed.MAGIC);
		selection.toggle(Seed.YEW);
		// One combined record: seeds.record swaps the whole container in, so two calls of
		// the single-seed helper would leave only the second seed owned.
		bankHolds(Seed.MAGIC.getItemID(), 5);
		bankHolds(Seed.YEW.getItemID(), 74);
		seeds.record(com.dooglemaps.state.SeedSource.BANK.getContainerId(),
			containerOf(Seed.MAGIC.getItemID(), 5, Seed.YEW.getItemID(), 74));
		com.dooglemaps.data.PlantingGroup trees =
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.TREE);
		protection.setProtecting(trees, Seed.MAGIC, true);
		protection.setProtecting(trees, Seed.YEW, true);
		// Not enough for a single magic: the allocation gives them all to the yews.
		bankHolds(ItemID.COCONUT, 24);
		bankHolds(ItemID.CACTUS_SPINE, 200);

		assertNull("no magic is being planted, so no coconuts are asked for",
			payment(ItemID.COCONUT));
	}

	/**
	 * The whole reported scenario, stocks verbatim from the live profile: saplings split
	 * across bank and vault, payments capping the magics.
	 *
	 * <p>55 coconuts protect two magics at 25 each, so the allocation plants 2 magics and
	 * 5 yews across the 7 patches. The magic saplings sit 1 in the bank and 4 in the vault —
	 * and the fetch now says exactly that: one row per container, 1 from the bank, 1 from
	 * the vault. The old single-container rule sent the whole errand to the vault, and the
	 * bank's count then said "take 2" over a slot holding one — which read as the arithmetic
	 * being wrong rather than as a second container being involved. Reported from play.
	 */
	@Test
	public void aFetchNeitherContainerCoversIsSplitAcrossBoth()
	{
		int patches = readyAllTreePatches();
		selection.toggle(Seed.MAGIC);
		selection.toggle(Seed.YEW);
		com.dooglemaps.data.PlantingGroup trees =
			com.dooglemaps.data.PlantingGroup.of(PatchImplementation.TREE);
		protection.setProtecting(trees, Seed.MAGIC, true);
		protection.setProtecting(trees, Seed.YEW, true);

		bankHolds(Seed.MAGIC.getPlantedItemID(), 1);
		bankHolds(Seed.YEW.getPlantedItemID(), 6);
		bankHolds(ItemID.COCONUT, 55);
		bankHolds(ItemID.CACTUS_SPINE, 93);
		seeds.record(com.dooglemaps.state.SeedSource.BANK.getContainerId(),
			containerOf(Seed.MAGIC.getPlantedItemID(), 1, Seed.YEW.getPlantedItemID(), 6));
		seeds.record(com.dooglemaps.state.SeedSource.SEED_VAULT.getContainerId(),
			containerOf(Seed.MAGIC.getPlantedItemID(), 4, Seed.YEW.getPlantedItemID(), 68));

		int magics = 55 / 25;
		int yews = patches - magics;

		List<LoadoutItem> magic = seedRows(Seed.MAGIC);
		assertEquals("one row per container holding a share", 2, magic.size());
		assertEquals("the bank's share is the one sapling it holds",
			LoadoutItem.From.BANK, magic.get(0).getFrom());
		assertEquals(1, magic.get(0).getWithdrawCount());
		assertEquals("the vault covers the rest",
			LoadoutItem.From.SEED_VAULT, magic.get(1).getFrom());
		assertEquals(magics - 1, magic.get(1).getWithdrawCount());

		List<LoadoutItem> yew = seedRows(Seed.YEW);
		assertEquals("a container that covers it alone keeps a single row", 1, yew.size());
		assertEquals(yews, yew.get(0).getQuantity());
		assertEquals(LoadoutItem.From.BANK, yew.get(0).getFrom());

		assertEquals(25 * magics, payment(ItemID.COCONUT).getQuantity());
		assertEquals(10 * yews, payment(ItemID.CACTUS_SPINE).getQuantity());
	}

	/** The seed rows of a tree run for one crop, in list order. */
	private List<LoadoutItem> seedRows(Seed seed)
	{
		List<LoadoutItem> rows = new java.util.ArrayList<>();
		for (LoadoutItem item : loadout.forRun(EnumSet.of(PatchImplementation.TREE)))
		{
			if (item.getCategory() == LoadoutItem.Category.SEED
				&& item.getItemId() == seed.getPlantedItemID())
			{
				rows.add(item);
			}
		}
		return rows;
	}

	/** A payment row of a tree run, by its item id. */
	@Nullable
	private LoadoutItem payment(int itemId)
	{
		for (LoadoutItem item : loadout.forRun(EnumSet.of(PatchImplementation.TREE)))
		{
			if (item.getCategory() == LoadoutItem.Category.PAYMENT && item.getItemId() == itemId)
			{
				return item;
			}
		}
		return null;
	}

	/** Every tree patch in the data, empty and switched on. */
	private int readyAllTreePatches()
	{
		int count = 0;
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.TREE))
		{
			ProduceState decoded = patch.getImplementation().forVarbitValue(0);
			assertNotNull(decoded);
			patches.recordVarbit(patch, 0, decoded);
			availability.setAvailable(patch, true);
			count++;
		}
		assertTrue("this scenario wants several tree patches", count >= 2);
		return count;
	}

	/** Readies a chosen number of tree patches, for a want that has to land on an exact figure. */
	private void readyTreePatches(int count)
	{
		int ready = 0;
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.TREE))
		{
			ProduceState decoded = patch.getImplementation().forVarbitValue(0);
			assertNotNull(decoded);
			patches.recordVarbit(patch, 0, decoded);
			availability.setAvailable(patch, true);
			if (++ready == count)
			{
				return;
			}
		}
		throw new AssertionError("fewer than " + count + " tree patches in the world data");
	}

	// ------------------------------------------------------------------- helpers

	/** Ardougne's herb patch, ready to pick and switched on for this account. */
	/** An empty tree patch, so a tree run has somewhere to plant and wants a seed for it. */
	private void readyTreePatch()
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.TREE))
		{
			ProduceState decoded = patch.getImplementation().forVarbitValue(0);
			assertNotNull(decoded);
			patches.recordVarbit(patch, 0, decoded);
			availability.setAvailable(patch, true);
			return;
		}
		throw new AssertionError("no tree patch in the generated world data");
	}

	private void readyFruitTreePatch()
	{
		readyPatchOf(PatchImplementation.FRUIT_TREE);
	}

	private void readyPatchOf(PatchImplementation type)
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(type))
		{
			ProduceState decoded = patch.getImplementation().forVarbitValue(0);
			assertNotNull(decoded);
			patches.recordVarbit(patch, 0, decoded);
			availability.setAvailable(patch, true);
			return;
		}
		throw new AssertionError("no " + type + " patch in the generated world data");
	}

	/** The vinery refuses a grape seed until the soil is treated - one saltpetre per patch. */
	@Test
	public void aGrapeRunAsksForItsSaltpetre()
	{
		readyPatchOf(PatchImplementation.GRAPES);
		bankHolds(net.runelite.api.gameval.ItemID.HOSIDIUS_SALTPETRE, 40);

		LoadoutItem saltpetre = itemNamed(EnumSet.of(PatchImplementation.GRAPES), "Saltpetre");
		assertNotNull("a grape run cannot plant a thing without it", saltpetre);
		assertEquals(LoadoutItem.Need.WITHDRAW, saltpetre.getNeed());
		assertEquals("one per patch", 1, saltpetre.getQuantity());
		assertEquals("and counted on the slot like the seeds are", 1,
			saltpetre.getWithdrawCount());
	}

	/** The underwater patches are sealed without the suit - both pieces, worn. */
	@Test
	public void aSeaweedRunAsksForTheDivingSuit()
	{
		readyPatchOf(PatchImplementation.SEAWEED);
		bankHolds(net.runelite.api.gameval.ItemID.HUNDRED_PIRATE_DIVING_HELMET, 1);
		bankHolds(net.runelite.api.gameval.ItemID.HUNDRED_PIRATE_DIVING_BACKPACK, 1);

		Set<PatchImplementation> types = EnumSet.of(PatchImplementation.SEAWEED);
		assertNotNull("no helmet, no dive", itemNamed(types, "Fishbowl helmet"));
		assertNotNull("no apparatus, no dive", itemNamed(types, "Diving apparatus"));
	}

	/** The medallion is breathing apparatus on its own, so a run with one needs no suit. */
	@Test
	public void theMedallionSparesTheSuitForACoralOnlyRun()
	{
		readyPatchOf(PatchImplementation.CORAL);
		carrying(net.runelite.api.gameval.ItemID.MEDALLION_OF_THE_DEEP, 1);
		bankHolds(net.runelite.api.gameval.ItemID.HUNDRED_PIRATE_DIVING_HELMET, 1);

		Set<PatchImplementation> types = EnumSet.of(PatchImplementation.CORAL);
		assertNotNull("the medallion is the whole requirement",
			itemNamed(types, "Medallion of the deep"));
		assertNull("so the suit is not asked for", itemNamed(types, "Fishbowl helmet"));
	}

	/**
	 * Diving gear stowed on a boat is owned, not missing.
	 *
	 * <p>The reported false alarm: "skipping fishbowl helmet, diving apparatus - you have none"
	 * to a player whose gear was in a cargo hold. A hold stores diving gear taking <b>no space
	 * at all</b> and it is then "accessible from all boats when stored in any of them", so a
	 * hold is exactly where a sailing player leaves it — and the loadout was looking only in
	 * the bank and the pack.
	 */
	@Test
	public void divingGearInACargoHoldIsNotReportedMissing()
	{
		readyPatchOf(PatchImplementation.CORAL);
		stowOnBoat(net.runelite.api.gameval.ItemID.HUNDRED_PIRATE_DIVING_HELMET);
		stowOnBoat(net.runelite.api.gameval.ItemID.HUNDRED_PIRATE_DIVING_BACKPACK);

		Set<PatchImplementation> coral = EnumSet.of(PatchImplementation.CORAL);
		LoadoutItem helmet = itemNamed(coral, "Fishbowl helmet");
		assertNotNull(helmet);
		assertEquals("it is on the boat, which is where it needs to be",
			LoadoutItem.Need.ON_BOAT, helmet.getNeed());
		assertEquals(LoadoutItem.Need.ON_BOAT,
			itemNamed(coral, "Diving apparatus").getNeed());
	}

	/** With nothing stowed and a bank that has been read, missing is still the honest answer. */
	@Test
	public void divingGearNowhereIsStillMissing()
	{
		readyPatchOf(PatchImplementation.CORAL);
		bankHolds(ItemID.RAKE, 1);   // so the bank counts as seen

		assertEquals(LoadoutItem.Need.MISSING,
			itemNamed(EnumSet.of(PatchImplementation.CORAL), "Fishbowl helmet").getNeed());
	}

	/** Carried still beats stowed - there is nothing to collect if it is already on you. */
	@Test
	public void carriedDivingGearOutranksTheHold()
	{
		readyPatchOf(PatchImplementation.CORAL);
		stowOnBoat(net.runelite.api.gameval.ItemID.HUNDRED_PIRATE_DIVING_HELMET);
		carrying(net.runelite.api.gameval.ItemID.HUNDRED_PIRATE_DIVING_HELMET, 1);

		assertEquals(LoadoutItem.Need.HAVE,
			itemNamed(EnumSet.of(PatchImplementation.CORAL), "Fishbowl helmet").getNeed());
	}

	/** Puts an item in a boat's cargo hold, through the store's own recording path. */
	private void stowOnBoat(int itemId)
	{
		boatHolds.record(containerOf(itemId, 1));
	}

	/**
	 * ...and on a seaweed run too, which it did not used to.
	 *
	 * <p>The medallion reached only the reef when this was written, so a seaweed leg fell
	 * through to the two-piece suit. That changed in <b>May 2026</b> — it "now functions as
	 * breathing apparatus throughout the Fossil Island underwater areas" — and until this test
	 * existed, someone who had assembled one was still being sent to a bank for a fishbowl
	 * helmet they had replaced.
	 */
	@Test
	public void theMedallionSparesTheSuitOnASeaweedRunAsWell()
	{
		readyPatchOf(PatchImplementation.SEAWEED);
		carrying(net.runelite.api.gameval.ItemID.MEDALLION_OF_THE_DEEP, 1);
		bankHolds(net.runelite.api.gameval.ItemID.HUNDRED_PIRATE_DIVING_HELMET, 1);
		bankHolds(net.runelite.api.gameval.ItemID.HUNDRED_PIRATE_DIVING_BACKPACK, 1);

		Set<PatchImplementation> types = EnumSet.of(PatchImplementation.SEAWEED);
		assertNotNull("Fossil Island counts now", itemNamed(types, "Medallion of the deep"));
		assertNull("so neither piece is asked for", itemNamed(types, "Fishbowl helmet"));
		assertNull(itemNamed(types, "Diving apparatus"));
	}

	/** A calquat clears with a spade alone, like a bush - the wiki is plain. No axe, ever. */
	@Test
	public void aCalquatRunNeedsNoAxe()
	{
		readyPatchOf(PatchImplementation.CALQUAT);
		bankHolds(ItemID.RUNE_AXE, 1);
		woodcuttingLevel(99);

		assertNull("no chop anywhere in a calquat's life",
			axeIn(EnumSet.of(PatchImplementation.CALQUAT)));
	}

	/** Celastrus bark comes off with an axe, so even harvest-only celastrus swings one. */
	@Test
	public void aHarvestOnlyCelastrusRunStillNeedsItsAxe()
	{
		readyPatchOf(PatchImplementation.CELASTRUS);
		bankHolds(ItemID.RUNE_AXE, 1);
		woodcuttingLevel(99);
		Mockito.when(runTypes.isHarvestOnly(Mockito.any())).thenReturn(true);

		assertNotNull("the bark IS the axe - the one type whose harvest swings it",
			axeIn(EnumSet.of(PatchImplementation.CELASTRUS)));
	}

	/**
	 * The axe rule, plainly: a tree run needs one whatever kind it is, a fruit tree run only
	 * when it replants — a harvest-only fruit visit picks and never chops. A contract group is
	 * never harvest-only, so a tree contract asks by the same test.
	 */
	@Test
	public void aHarvestOnlyFruitTreeRunNeedsNoAxe()
	{
		readyFruitTreePatch();
		bankHolds(ItemID.RUNE_AXE, 1);
		woodcuttingLevel(99);

		Set<PatchImplementation> fruit = EnumSet.of(PatchImplementation.FRUIT_TREE);
		assertNotNull("a replanting fruit run chops the old tree out first",
			axeIn(fruit));

		Mockito.when(runTypes.isHarvestOnly(Mockito.any())).thenReturn(true);
		assertNull("picking fruit swings nothing", axeIn(fruit));
	}

	/**
	 * A harvest-only tree run needs no axe either — wiki-checked, not assumed.
	 *
	 * <p>The health check is where a tree patch's value is and it needs no tool; the chop only
	 * clears the patch for a replant, and farmed trees do not regrow for re-chopping. This
	 * asserted the opposite for a day, on a "the harvest is the chop" model the wiki does not
	 * support.
	 */
	@Test
	public void aHarvestOnlyTreeRunNeedsNoAxeEither()
	{
		readyTreePatch();
		bankHolds(ItemID.RUNE_AXE, 1);
		woodcuttingLevel(99);
		Mockito.when(runTypes.isHarvestOnly(Mockito.any())).thenReturn(true);

		assertNull("checking health swings nothing, and that is the whole visit",
			axeIn(EnumSet.of(PatchImplementation.TREE)));
	}

	/**
	 * A tree contract on a patch still holding last run's tree asks for the axe and the basket.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * Jane hands out a contract for a patch that is not empty — the tree in it grew while you were
	 * away and has not been check-healthed. Planting the contract's crop means checking, chopping
	 * and digging the stump out first, so the trip needs an <b>axe</b>; and the logs arrive one per
	 * chop, before the leprechaun can note anything and he refuses logs anyway, so it needs
	 * somewhere to put them. Reported from play as having to fetch both by hand.
	 *
	 * <p>Modelled with the contract's own {@code PlantingGroup}, which is the thing the earlier
	 * axe tests do not do — they let the mocked grouper fall back to the plain group, and the
	 * plain group answers a different question about harvest-only.
	 */
	@Test
	public void aTreeContractOnAnUnclearedPatchAsksForTheAxeAndTheBasket()
	{
		FarmPatch tree = guildTreePatch();

		// Varbit 12: an oak fully grown and NOT yet check-healthed - the state Jane can hand you.
		ProduceState decoded = tree.getImplementation().forVarbitValue(12);
		assertNotNull(decoded);
		patches.recordVarbit(tree, 12, decoded);
		availability.setAvailable(tree, true);

		com.dooglemaps.data.PlantingGroup contract =
			com.dooglemaps.data.PlantingGroup.contract(PatchImplementation.TREE);
		when(groups.groupFor(tree)).thenReturn(contract);
		when(plannerRunOptions.isSelected(
			com.dooglemaps.data.RunOption.full(contract))).thenReturn(true);

		bankHolds(ItemID.RUNE_AXE, 1);
		bankHolds(ItemID.FORESTRY_BASKET_CLOSED, 1);
		woodcuttingLevel(99);

		Set<PatchImplementation> trees = EnumSet.of(PatchImplementation.TREE);
		LoadoutItem axe = axeIn(trees);
		assertNotNull("without one the contract's patch cannot be cleared at all", axe);
		assertEquals(LoadoutItem.Need.WITHDRAW, axe.getNeed());

		LoadoutItem basket = itemNamed(trees, "Forestry basket");
		assertNotNull("the logs arrive mid-chop, and the leprechaun refuses them", basket);
	}

	/** The Farming Guild's tree patch, which is the one a tree contract lands on. */
	private static FarmPatch guildTreePatch()
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.TREE))
		{
			if (patch.getRegion().getRegionId() == 4922)
			{
				return patch;
			}
		}
		throw new AssertionError("no tree patch at the Farming Guild");
	}

	/** Readies several herb patches, for the counts that only mean something above one. */
	private void readyHerbPatches(int count)
	{
		int ready = 0;
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.HERB))
		{
			ProduceState decoded = patch.getImplementation().forVarbitValue(43);
			assertNotNull(decoded);
			patches.recordVarbit(patch, 43, decoded);
			availability.setAvailable(patch, true);
			if (++ready == count)
			{
				return;
			}
		}
		throw new AssertionError("fewer than " + count + " herb patches in the world data");
	}

	private void readyHerbPatch()
	{
		FarmPatch patch = FarmingWorldData.getPatch("10548.4774");
		assertNotNull("fixture patch no longer exists", patch);
		ProduceState decoded = patch.getImplementation().forVarbitValue(43);
		assertNotNull(decoded);
		patches.recordVarbit(patch, 43, decoded);
		availability.setAvailable(patch, true);
	}

	/**
	 * A run that only picks what is ripe asks for nothing to put in the ground.
	 *
	 * <p>Reported as a fruit tree harvest-only run telling the player to withdraw palm saplings.
	 * The planner already narrows a harvest-only stop to ripe patches, so the loadout received
	 * "four fruit tree patches to deal with" and read that, reasonably, as four patches to plant
	 * in — nothing here had ever been told the difference.
	 *
	 * <p>All three planting consequences are checked, not just the seed. Compost goes under a seed
	 * and a payment protects one, so a stop that plants nothing wants none of them — and each was
	 * a separate loop that would have needed the same guard.
	 */
	@Test
	public void aHarvestOnlyRunAsksForNothingToPlant()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		seedsInBank(Seed.RANARR, 20);
		compost.set(PatchImplementation.HERB, CompostTier.ULTRACOMPOST);
		bankHolds(CompostTier.ULTRACOMPOST.getItemID(), 500);

		assertFalse("a full run does want its seed",
			itemsIn(LoadoutItem.Category.SEED).isEmpty());

		Mockito.when(runTypes.isHarvestOnly(Mockito.any())).thenReturn(true);

		assertTrue("nothing is being planted, so no seed",
			itemsIn(LoadoutItem.Category.SEED).isEmpty());
		assertTrue("and nothing to put under one",
			itemsIn(LoadoutItem.Category.COMPOST).isEmpty());
		assertTrue("and nothing to protect",
			itemsIn(LoadoutItem.Category.PAYMENT).isEmpty());
	}

	/**
	 * A seed is fetched from wherever it actually is.
	 *
	 * <p>{@code Need.WITHDRAW} says only that something is not on you. It was then reported as
	 * <i>"From the bank:"</i> whatever the truth, so a vault seed sent you to the wrong container —
	 * and there is one seed vault, in the Farming Guild, so that is not a near miss.
	 *
	 * <p>The bank wins a tie deliberately: everything else the run needs is in there anyway, so one
	 * stop beats two.
	 */
	@Test
	public void aSeedIsFetchedFromWhicheverStoreHoldsIt()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);

		seedsInVault(Seed.RANARR, 20);
		assertEquals("only the vault has it", LoadoutItem.From.SEED_VAULT,
			itemsIn(LoadoutItem.Category.SEED).get(0).getFrom());

		seedsInBank(Seed.RANARR, 20);
		assertEquals("both have it, so the bank - where the rest of the run's items are",
			LoadoutItem.From.BANK, itemsIn(LoadoutItem.Category.SEED).get(0).getFrom());
	}

	/**
	 * A seed held in both stores is fetched from one of them, not both.
	 *
	 * <p>{@code Need.WITHDRAW} only means "not on you", which is true of a seed sitting in either
	 * store — so the bank highlight and the vault highlight both claimed it, and the run appeared
	 * to want two lots. {@code From} is the answer to which one the run means, and it is what the
	 * two overlays now split on.
	 */
	@Test
	public void aSeedInBothStoresIsClaimedByOnlyOneOfThem()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		seedsInBank(Seed.RANARR, 20);
		seedsInVault(Seed.RANARR, 20);

		List<LoadoutItem> seedItems = itemsIn(LoadoutItem.Category.SEED);
		assertEquals("one row, not one per store", 1, seedItems.size());
		assertEquals("the bank wins a tie - everything else the run needs is there anyway",
			LoadoutItem.From.BANK, seedItems.get(0).getFrom());
	}

	private List<LoadoutItem> itemsIn(LoadoutItem.Category category)
	{
		List<LoadoutItem> found = new java.util.ArrayList<>();
		for (LoadoutItem item : loadout.forRun(HERBS))
		{
			if (item.getCategory() == category)
			{
				found.add(item);
			}
		}
		return found;
	}

	/** The first item of a category for an arbitrary run, or null. */
	private LoadoutItem firstOf(Set<PatchImplementation> types, LoadoutItem.Category category)
	{
		for (LoadoutItem item : loadout.forRun(types))
		{
			if (item.getCategory() == category)
			{
				return item;
			}
		}
		return null;
	}

	/** Item ids marked as "take this", as the bank overlay would draw them. */
	private Set<Integer> withdrawals(Set<PatchImplementation> types)
	{
		Set<Integer> ids = new java.util.LinkedHashSet<>();
		loadout.highlights(types).forEach((itemId, need) ->
		{
			if (need == LoadoutItem.Need.WITHDRAW)
			{
				ids.add(itemId);
			}
		});
		return ids;
	}

	private void woodcuttingLevel(int level)
	{
		stored.put("dooglemaps.woodcuttingLevel", level);
	}

	/**
	 * The axe suggested for a run, or null if none was.
	 *
	 * <p>Named rather than taken as "the first tool", which is what these tests used to do. That
	 * worked only while the axe was the sole entry in the category; the farming tools are listed
	 * there too now, and an assertion that breaks when a neighbouring row appears was never
	 * really asserting anything about axes.
	 */
	/**
	 * Puts something in the leprechaun's store.
	 *
	 * <p>Written to the base varbit and read back through the store's own tick handler, so the
	 * test exercises the real path rather than a shortcut into the map.
	 */
	private void leprechaunHolds(FarmingTool tool, int count)
	{
		leprechaunVarbits.put(tool.getVarbits()[0], count);
		leprechaun.onGameTick(new net.runelite.api.events.GameTick());
	}

	private LoadoutItem axeIn(Set<PatchImplementation> types)
	{
		for (LoadoutItem item : loadout.forRun(types))
		{
			if (item.getCategory() == LoadoutItem.Category.TOOL
				&& item.getName().toLowerCase().contains("axe"))
			{
				return item;
			}
		}
		return null;
	}

	private LoadoutItem find(LoadoutItem.Category category)
	{
		List<LoadoutItem> found = itemsIn(category);
		return found.isEmpty() ? null : found.get(0);
	}

	/**
	 * Adds to the bank rather than replacing it.
	 *
	 * <p>{@code BankContents.record} takes a whole container and swaps it in, which is right
	 * for the real event and wrong for a test that stocks two things in two calls — the first
	 * silently vanished.
	 */
	/**
	 * Stocks a seed in the bank, in both the places that have to agree about it.
	 *
	 * <p>{@code bankHolds} alone is not enough and the difference is easy to miss: it feeds
	 * {@link BankContents}, which answers "is this item in the bank", while seed <i>counts</i>
	 * come from {@link com.dooglemaps.state.SeedInventoryStore}, which is fed by its own container
	 * events. A test that stocked only the first had every seed read as owned nowhere.
	 */
	/**
	 * Puts seeds in the pack, which is what withdrawing one looks like from here.
	 *
	 * <p>The inventory rather than {@link CarriedItems}, because seed <i>counts</i> come from
	 * {@link com.dooglemaps.state.SeedInventoryStore} and that is the store the run's own
	 * arithmetic reads.
	 */
	private void seedsInInventory(Seed seed, int quantity)
	{
		seeds.record(com.dooglemaps.state.SeedSource.INVENTORY.getContainerId(),
			quantity <= 0 ? containerOf() : containerOf(seed.getItemID(), quantity));
	}

	/** The single seed row this run produces, re-read so a change of stock is picked up. */
	private LoadoutItem onlySeed()
	{
		List<LoadoutItem> rows = itemsIn(LoadoutItem.Category.SEED);
		assertEquals("expected exactly one seed row", 1, rows.size());
		return rows.get(0);
	}

	/** The plantable form in the pack: a tree run whose potting is already done. */
	private void saplingsInInventory(Seed seed, int quantity)
	{
		seeds.record(com.dooglemaps.state.SeedSource.INVENTORY.getContainerId(),
			containerOf(seed.getPlantedItemID(), quantity));
	}

	private void seedsInBank(Seed seed, int quantity)
	{
		bankHolds(seed.getItemID(), quantity);
		seeds.record(com.dooglemaps.state.SeedSource.BANK.getContainerId(),
			containerOf(seed.getItemID(), quantity));
	}

	/**
	 * The same, in the seed vault.
	 *
	 * <p>Deliberately does <b>not</b> touch {@link BankContents}: the vault is not the bank, and a
	 * fixture that stocked both would hide exactly the confusion these tests exist to catch.
	 */
	private void seedsInVault(Seed seed, int quantity)
	{
		seeds.record(com.dooglemaps.state.SeedSource.SEED_VAULT.getContainerId(),
			containerOf(seed.getItemID(), quantity));
	}

	/**
	 * The plantable form sitting in the bank: a sapling already potted, rather than the seed
	 * {@link #seedsInBank} stocks.
	 */
	private void saplingsInBank(Seed seed, int quantity)
	{
		bankHolds(seed.getPlantedItemID(), quantity);
		seeds.record(com.dooglemaps.state.SeedSource.BANK.getContainerId(),
			containerOf(seed.getPlantedItemID(), quantity));
	}

	/** The plantable form in the vault. See {@link #seedsInVault} on why this skips BankContents. */
	private void saplingsInVault(Seed seed, int quantity)
	{
		seeds.record(com.dooglemaps.state.SeedSource.SEED_VAULT.getContainerId(),
			containerOf(seed.getPlantedItemID(), quantity));
	}

	/** Seeds sitting in the seed box, which count against the click the same as the pack does. */
	private void seedsInBox(Seed seed, int quantity)
	{
		seeds.record(com.dooglemaps.state.SeedSource.SEED_BOX.getContainerId(),
			quantity <= 0 ? containerOf() : containerOf(seed.getItemID(), quantity));
	}

	private void bankHolds(int itemId, int quantity)
	{
		bankStock.put(itemId, quantity);
		bank.record(containerOf(flatten(bankStock)));
	}

	private void carrying(int itemId, int quantity)
	{
		carriedStock.put(itemId, quantity);
		carried.record(containerOf(flatten(carriedStock)));
	}

	/** Puts an item in the equipment container, which is a different thing from carrying it. */
	private void wearing(int itemId, int quantity)
	{
		wornStock.put(itemId, quantity);
		carried.recordEquipment(containerOf(flatten(wornStock)));
	}

	private final java.util.LinkedHashMap<Integer, Integer> wornStock =
		new java.util.LinkedHashMap<>();

	/** The first item of a category with this name, or null. */
	private LoadoutItem find(LoadoutItem.Category category, String name)
	{
		for (LoadoutItem item : itemsIn(category))
		{
			if (name.equals(item.getName()))
			{
				return item;
			}
		}
		return null;
	}

	private final java.util.LinkedHashMap<Integer, Integer> bankStock =
		new java.util.LinkedHashMap<>();
	private final java.util.LinkedHashMap<Integer, Integer> carriedStock =
		new java.util.LinkedHashMap<>();

	private static int[] flatten(java.util.Map<Integer, Integer> stock)
	{
		int[] flat = new int[stock.size() * 2];
		int i = 0;
		for (java.util.Map.Entry<Integer, Integer> entry : stock.entrySet())
		{
			flat[i++] = entry.getKey();
			flat[i++] = entry.getValue();
		}
		return flat;
	}

	private static ItemContainer containerOf(int... idThenQuantity)
	{
		Item[] items = new Item[idThenQuantity.length / 2];
		for (int i = 0; i < items.length; i++)
		{
			items[i] = new Item(idThenQuantity[i * 2], idThenQuantity[i * 2 + 1]);
		}
		ItemContainer container = Mockito.mock(ItemContainer.class);
		when(container.getItems()).thenReturn(items);
		return container;
	}

	/**
	 * A client whose tick advances on every read.
	 *
	 * <p>{@code forRun} memoises per tick, which is right in the client and wrong in a test: a
	 * test changes the bank and asks again within what would be one tick, and would be handed the
	 * answer from before its own setup. An always-advancing tick disables the cache without the
	 * production code needing to know it is under test.
	 */
	/** A client stuck on one tick, so {@code RunLoadout.forRun}'s cache actually holds. */
	private static net.runelite.api.Client frozenClient()
	{
		net.runelite.api.Client client = Mockito.mock(net.runelite.api.Client.class);
		when(client.getTickCount()).thenReturn(7);
		return client;
	}

	/** The count the cached row carries, which is the thing {@code stillWantedNow} must not use. */
	private static int cachedWithdrawCount(RunLoadout from, int itemId,
		java.util.Set<PatchImplementation> types)
	{
		for (LoadoutItem item : from.forRun(types))
		{
			if (item.getItemId() == itemId)
			{
				return item.getWithdrawCount();
			}
		}
		return 0;
	}

	private static net.runelite.api.Client tickingClient()
	{
		net.runelite.api.Client client = Mockito.mock(net.runelite.api.Client.class);
		int[] tick = {0};
		when(client.getTickCount()).thenAnswer(i -> tick[0]++);
		return client;
	}

	// ------------------------------------------------------- pay-to-clear

	/**
	 * A checked, standing magic tree the player has said to buy a gardener out of clearing.
	 *
	 * <p>Varbit 61 is the checked, choppable state — per {@code TreeStumpTest
	 * .magicTellsItsThreeEndStatesApart} and {@code RunPlannerTest.clearableInCountsACheckedStandingTree}.
	 */
	@Test
	public void aCheckedStandingMagicTreeAsksForItsClearingFee()
	{
		treePatch(0, 61);
		payToClear.setPayingFor(Seed.MAGIC, true);
		bankHolds(ItemID.COINS, 500);

		LoadoutItem coins = itemNamed(EnumSet.of(PatchImplementation.TREE), "Coins");
		assertNotNull("a checked, paid-for magic tree should ask for its clearing fee", coins);
		assertEquals(LoadoutItem.Category.CLEARING, coins.getCategory());
		assertEquals(200, coins.getQuantity());
		assertEquals(LoadoutItem.Need.WITHDRAW, coins.getNeed());
	}

	/** Two grown, paid-for magic trees are twice the fee - a gardener charges per tree. */
	@Test
	public void twoCheckedMagicTreesDoubleTheClearingFee()
	{
		treePatch(0, 61);
		treePatch(1, 61);
		payToClear.setPayingFor(Seed.MAGIC, true);
		bankHolds(ItemID.COINS, 500);

		LoadoutItem coins = itemNamed(EnumSet.of(PatchImplementation.TREE), "Coins");
		assertNotNull(coins);
		assertEquals(400, coins.getQuantity());
	}

	/** Already carrying the whole fee reads as done, exactly like a protection payment does. */
	@Test
	public void carryingTheWholeFeeAlreadyReadsAsHave()
	{
		treePatch(0, 61);
		payToClear.setPayingFor(Seed.MAGIC, true);
		carrying(ItemID.COINS, 50_000);

		LoadoutItem coins = itemNamed(EnumSet.of(PatchImplementation.TREE), "Coins");
		assertNotNull(coins);
		assertEquals(LoadoutItem.Need.HAVE, coins.getNeed());
	}

	/** Never having said to pay for magic means no coins row at all - the axe stays the plan. */
	@Test
	public void aMagicTreeNeverToggledOnAsksForNoCoins()
	{
		treePatch(0, 61);
		bankHolds(ItemID.COINS, 500);

		assertNull("nothing was ever paid for, so there is nothing to bank",
			itemNamed(EnumSet.of(PatchImplementation.TREE), "Coins"));
	}

	/** A felled stump has nothing left for a gardener to clear - varbit 62. */
	@Test
	public void aStumpAsksForNoClearingFee()
	{
		treePatch(0, 62);
		payToClear.setPayingFor(Seed.MAGIC, true);
		bankHolds(ItemID.COINS, 500);

		assertNull("nothing standing means nothing to buy",
			itemNamed(EnumSet.of(PatchImplementation.TREE), "Coins"));
	}

	/**
	 * A grown-but-unchecked tree is counted too - varbit 60. The coins have to be in the pack
	 * before the run reaches the patch, and by the time it does the check will already be done.
	 */
	@Test
	public void aGrownButUncheckedMagicTreeIsCountedToo()
	{
		treePatch(0, 60);
		payToClear.setPayingFor(Seed.MAGIC, true);
		bankHolds(ItemID.COINS, 500);

		LoadoutItem coins = itemNamed(EnumSet.of(PatchImplementation.TREE), "Coins");
		assertNotNull(coins);
		assertEquals(200, coins.getQuantity());
	}

	/** A harvest-only visit plants nothing and clears nothing either. */
	@Test
	public void aHarvestOnlyTreeRunAsksForNoClearingFee()
	{
		treePatch(0, 61);
		payToClear.setPayingFor(Seed.MAGIC, true);
		bankHolds(ItemID.COINS, 500);
		Mockito.when(runTypes.isHarvestOnly(Mockito.any())).thenReturn(true);

		assertNull("a harvest-only stop is not one this run will clear",
			itemNamed(EnumSet.of(PatchImplementation.TREE), "Coins"));
	}

	/**
	 * Fruit still on a laden fruit tree does not stop the gardener's price - the guide picks it
	 * first and pays after, but the coins have to already be on the withdraw list by then.
	 *
	 * <p>Varbit 175 is papaya's own top-of-range HARVESTABLE value ({@code PatchRules}: 169-175,
	 * seven states for up to six fruit, mirroring the apple range {@code RunPlannerTest
	 * .clearableInCountsALadenFruitTree} uses).
	 */
	@Test
	public void aLadenPapayaAsksForItsClearingFeeToo()
	{
		fruitTreePatch(175);
		payToClear.setPayingFor(Seed.PAPAYA, true);
		bankHolds(ItemID.COINS, 500);

		LoadoutItem coins = itemNamed(EnumSet.of(PatchImplementation.FRUIT_TREE), "Coins");
		assertNotNull("fruit still on it does not stop the gardener's price", coins);
		assertEquals(200, coins.getQuantity());
	}

	/**
	 * A dead redwood asks for its 2,000 coins with no toggle to check at all - Alexandra's coins
	 * are the only way one is ever cleared, so there is no axe alternative to fall back to.
	 */
	@Test
	public void aDeadRedwoodAsksForItsFeeWithNoToggleAtAll()
	{
		redwoodPatch(28);   // dead - RunPlannerTest.deadRedwoodsInCountsADeadRedwood

		LoadoutItem coins = itemNamed(EnumSet.of(PatchImplementation.REDWOOD), "Coins");
		assertNotNull("a dead redwood has no other route", coins);
		assertEquals(2000, coins.getQuantity());
	}

	/**
	 * A leg short of nothing but clearing coins must not be held for them, and the withdraw-amount
	 * swap must never be sized off the player's whole cash stack.
	 *
	 * <p>{@code CLEARING} sits outside both {@code CANNOT_PROCEED_WITHOUT} and {@code sizeable} -
	 * see their javadocs - so a run that has genuinely gathered everything else waits for nothing,
	 * and a bank slot of coins never gets a Withdraw-10 armed on it.
	 */
	@Test
	public void onlyCoinsOutstandingDoesNotHoldTheSupplyLeg()
	{
		treePatch(0, 61);
		payToClear.setPayingFor(Seed.MAGIC, true);
		bankHolds(ItemID.COINS, 200);
		carrying(ItemID.RUNE_AXE, 1);
		woodcuttingLevel(99);
		leprechaunHolds(FarmingTool.RAKE, 1);
		leprechaunHolds(FarmingTool.SPADE, 1);
		leprechaunHolds(FarmingTool.SEED_DIBBER, 1);

		Set<PatchImplementation> types = EnumSet.of(PatchImplementation.TREE);
		LoadoutItem coins = itemNamed(types, "Coins");
		assertNotNull(coins);
		assertEquals("the fixture should actually owe the fee for this assertion to mean anything",
			LoadoutItem.Need.WITHDRAW, coins.getNeed());

		assertFalse("coins outstanding alone must never hold the supply leg",
			loadout.anythingLeftToWithdraw(types));
		assertEquals("a cash stack must not get a withdraw-amount click sized on it",
			0, loadout.stillWantedNow(ItemID.COINS, types));
	}

	/** A grown, standing tree of the given implementation, at a chosen index so two can differ. */
	private FarmPatch treePatch(int index, int varbitValue)
	{
		return payableTreePatch(PatchImplementation.TREE, index, varbitValue);
	}

	/** A fruit tree patch at the given varbit - see {@code PatchRules} for papaya's own range. */
	private FarmPatch fruitTreePatch(int varbitValue)
	{
		return payableTreePatch(PatchImplementation.FRUIT_TREE, 0, varbitValue);
	}

	/** A redwood patch at the given varbit. Only {@code DEAD} (28) is a nameable clearable state. */
	private FarmPatch redwoodPatch(int varbitValue)
	{
		return payableTreePatch(PatchImplementation.REDWOOD, 0, varbitValue);
	}

	/**
	 * Records a patch of this type at this varbit, marks it available, and stubs the grouper to
	 * answer the plain group for it.
	 *
	 * <h2>Why the grouper has to be stubbed here and not for every other fixture patch</h2>
	 *
	 * {@code RunPlanner.clearableIn} and {@code deadRedwoodsIn} require {@code groups.groupFor}
	 * to answer the exact group being asked about, with no null fallback — unlike {@code
	 * actionableByGroup}, which falls back to {@code PlantingGroup.of(type)} when the mock
	 * answers null. An unstubbed {@code groups} mock therefore makes every patch invisible to
	 * {@code clearableIn} specifically, which is why {@code RunPlannerTest}'s own clearable-in
	 * tests stub it per patch too.
	 */
	private FarmPatch payableTreePatch(PatchImplementation type, int index, int varbitValue)
	{
		FarmPatch patch = FarmingWorldData.getPatches(type).get(index);
		ProduceState decoded = patch.getImplementation().forVarbitValue(varbitValue);
		assertNotNull("varbit " + varbitValue + " does not decode for " + type, decoded);
		patches.recordVarbit(patch, varbitValue, decoded);
		availability.setAvailable(patch, true);

		com.dooglemaps.data.PlantingGroup group = com.dooglemaps.data.PlantingGroup.of(type);
		when(groups.groupFor(patch)).thenReturn(group);
		// Once groupFor stops answering null, RunPlanner.inTheRun stops taking its own "nobody
		// answered, so let it through" shortcut and asks whether this group's run is actually
		// ticked - which the ordinary readyTreePatch-style fixtures never needed to stub, because
		// they leave groupFor unstubbed and get that shortcut for free. So the run has to be
		// ticked here explicitly, or actionableByGroup drops the patch before addClearingFees
		// ever asks clearableIn about it.
		when(plannerRunOptions.isSelected(com.dooglemaps.data.RunOption.full(group)))
			.thenReturn(true);
		return patch;
	}
}
