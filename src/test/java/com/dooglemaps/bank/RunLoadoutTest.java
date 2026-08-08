package com.dooglemaps.bank;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.FarmingTool;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
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
import java.lang.reflect.Constructor;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

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
	private SeedInventoryStore seeds;
	private com.dooglemaps.DoogleMapsConfig config;

	/** Item names the stubbed cache will answer with, so a test can name what it banked. */
	private final java.util.Map<Integer, String> names = new java.util.HashMap<>();
	private com.dooglemaps.state.ProtectionSelectionStore protection;

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
		RunPlanner planner = construct(RunPlanner.class, availability,
			construct(PatchLocationStore.class, configManager, gson),
			construct(BankLocationStore.class, configManager, gson),
			selection, seeds, patches, construct(GrowthTimer.class, configManager),
			construct(ShortestPathIntegration.class, Mockito.mock(EventBus.class),
				Mockito.mock(net.runelite.client.callback.ClientThread.class)),
			playerLocation, Mockito.mock(ToolNeeds.class),
			Mockito.mock(com.dooglemaps.state.ProtectedPatches.class),
			Mockito.mock(com.dooglemaps.state.PlantingGroups.class),
			Mockito.mock(com.dooglemaps.state.ProtectionSelectionStore.class),
			Mockito.mock(com.dooglemaps.state.RunTypeStore.class),
			// The planner asks the loadout whether anything is still to be withdrawn, and the
			// loadout is built from the planner. Guice breaks that cycle with a Provider; here the
			// same job is done by a holder filled in once both exist, a few lines below.
			(javax.inject.Provider<RunLoadout>) () -> loadoutUnderTest[0]);

		carried = construct(CarriedItems.class, Mockito.mock(net.runelite.api.Client.class));
		bank = construct(BankContents.class, configManager, gson);

		net.runelite.api.Client leprechaunClient = Mockito.mock(net.runelite.api.Client.class);
		when(leprechaunClient.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
		when(leprechaunClient.getVarbitValue(Mockito.anyInt()))
			.thenAnswer(i -> leprechaunVarbits.getOrDefault(i.getArgument(0), 0));
		leprechaun = construct(LeprechaunStore.class, leprechaunClient);
		leprechaun.onGameTick(new net.runelite.api.events.GameTick());

		ToolNeeds toolNeeds = construct(ToolNeeds.class, leprechaun, carried, bank, selection,
			construct(GrowthTimer.class, configManager),
			construct(com.dooglemaps.state.BarbarianFarming.class, configManager,
				Mockito.mock(com.dooglemaps.DoogleMapsConfig.class)));
		protection = construct(com.dooglemaps.state.ProtectionSelectionStore.class,
			configManager, gson);
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

		// A mock rather than a real store: every test here is a full run, and isHarvestOnly
		// defaults to false, which is what "a full run" means. The harvest-only path has its own
		// test below.
		runTypes = Mockito.mock(com.dooglemaps.state.RunTypeStore.class);

		// No contract assigned by default, which is what every test here but the contract ones
		// wants: getContract() answering null means contractIsStandingThere can never fire.
		contracts = Mockito.mock(com.dooglemaps.state.ContractState.class);

		loadout = construct(RunLoadout.class, planner, selection, seeds, compost, carried, bank,
			toolNeeds, leprechaun, protection, itemNames, config, tickingClient(), runTypes,
			contracts);
		loadoutUnderTest[0] = loadout;
	}

	/**
	 * The loadout, reachable from the planner that was built before it.
	 *
	 * <p>An array rather than a field because the Provider handed to the planner is created during
	 * setup and has to see an assignment made after it — the same deferral Guice does for the real
	 * cycle, done by hand. See the Provider argument in {@code setUp}.
	 */
	private final RunLoadout[] loadoutUnderTest = new RunLoadout[1];

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

		assertNotNull(find(LoadoutItem.Category.TELEPORT, "Games necklace(8)"));
		assertNotNull("one entry, every charge", find(LoadoutItem.Category.TELEPORT,
			"Games necklace(1)"));
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

	/**
	 * The Farmer's outfit is offered when a piece is missing, as one line.
	 *
	 * <p>It was absent from the loadout entirely, so anyone who left the legs in the bank was
	 * never told — and it is worth up to 2.5% Farming experience.
	 */
	@Test
	public void theFarmersOutfitIsOfferedWhenIncomplete()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);

		wearing(FarmingOutfit.HAT.getMaleItemId(), 1);
		bankHolds(FarmingOutfit.LEGS.getMaleItemId(), 1);

		LoadoutItem outfit = find(LoadoutItem.Category.GEAR, "Farmer's outfit");
		assertNotNull("a banked piece should be offered", outfit);
		assertEquals(LoadoutItem.Need.WITHDRAW, outfit.getNeed());
		assertTrue(outfit.getReason(), outfit.getReason().toLowerCase().contains("legs"));
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

		LoadoutItem outfit = find(LoadoutItem.Category.GEAR, "Farmer's outfit");
		assertNotNull(outfit);
		assertEquals(LoadoutItem.Need.HAVE, outfit.getNeed());
	}

	/** An account with no outfit at all hears nothing, which is most accounts. */
	@Test
	public void noOutfitAnywhereIsNotReportedAsMissing()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);

		assertNull("silence beats noise for something you do not own",
			find(LoadoutItem.Category.GEAR, "Farmer's outfit"));
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
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.FRUIT_TREE))
		{
			ProduceState decoded = patch.getImplementation().forVarbitValue(0);
			assertNotNull(decoded);
			patches.recordVarbit(patch, 0, decoded);
			availability.setAvailable(patch, true);
			return;
		}
		throw new AssertionError("no fruit tree patch in the generated world data");
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

	/** For a plain tree the harvest IS the chop, so harvest-only changes nothing. */
	@Test
	public void aHarvestOnlyTreeRunStillNeedsItsAxe()
	{
		readyTreePatch();
		bankHolds(ItemID.RUNE_AXE, 1);
		woodcuttingLevel(99);
		Mockito.when(runTypes.isHarvestOnly(Mockito.any())).thenReturn(true);

		assertNotNull("coming back for the logs still means swinging an axe",
			axeIn(EnumSet.of(PatchImplementation.TREE)));
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
	private static net.runelite.api.Client tickingClient()
	{
		net.runelite.api.Client client = Mockito.mock(net.runelite.api.Client.class);
		int[] tick = {0};
		when(client.getTickCount()).thenAnswer(i -> tick[0]++);
		return client;
	}

	@SuppressWarnings("unchecked")
	private static <T> T construct(Class<T> type, Object... args) throws Exception
	{
		for (Constructor<?> candidate : type.getDeclaredConstructors())
		{
			if (candidate.getParameterCount() == args.length)
			{
				candidate.setAccessible(true);
				return (T) candidate.newInstance(args);
			}
		}
		throw new IllegalStateException("no constructor of arity " + args.length + " on " + type);
	}
}
