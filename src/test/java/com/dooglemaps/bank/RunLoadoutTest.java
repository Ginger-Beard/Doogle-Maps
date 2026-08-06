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
			Mockito.mock(com.dooglemaps.state.RunTypeStore.class));

		carried = construct(CarriedItems.class);
		bank = construct(BankContents.class);

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
		when(itemNames.get(Mockito.anyInt(), Mockito.any()))
			.thenAnswer(i -> names.get(i.<Integer>getArgument(0)));

		loadout = construct(RunLoadout.class, planner, selection, seeds, compost, carried, bank,
			toolNeeds, leprechaun, protection, itemNames, config, tickingClient());
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
	public void aTeleportYouOwnForAStopOnTheRunIsSuggested()
	{
		readyHerbPatch();   // Ardougne
		selection.toggle(Seed.RANARR);
		bankHolds(ItemID.ARDY_CAPE_MEDIUM, 1);

		List<LoadoutItem> teleports = itemsIn(LoadoutItem.Category.TELEPORT);
		assertEquals(1, teleports.size());
		assertEquals(LoadoutItem.Need.WITHDRAW, teleports.get(0).getNeed());
		assertTrue(teleports.get(0).getReason(),
			teleports.get(0).getReason().contains("Ardougne"));
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

	/** Only outstanding withdrawals get highlighted, so the bank does not light up wholesale. */
	@Test
	public void onlyOutstandingWithdrawalsAreHighlighted()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		bankHolds(ItemID.ARDY_CAPE_MEDIUM, 1);
		bankHolds(ItemID.SLAYER_HERB_SACK, 1);
		carrying(ItemID.FAIRY_ENCHANTED_SECATEURS, 1);

		Set<Integer> highlighted = withdrawals(HERBS);
		assertTrue(highlighted.contains(ItemID.ARDY_CAPE_MEDIUM));
		assertTrue(highlighted.contains(ItemID.SLAYER_HERB_SACK));
		assertFalse("carried, so there is nothing to fetch",
			highlighted.contains(ItemID.FAIRY_ENCHANTED_SECATEURS));
	}

	/**
	 * Leprechaun items are marked in the bank, just not as withdrawals.
	 *
	 * <p>Leaving compost unmarked would read as the plugin having forgotten about it. Marking
	 * it as a withdrawal would have you banking a bucket you have a thousand of on site. So it
	 * is marked, in its own right, to cue asking the leprechaun once you are at the patch.
	 */
	@Test
	public void compostIsMarkedInTheBankWithoutBeingAWithdrawal()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		compost.set(PatchImplementation.HERB, CompostTier.ULTRACOMPOST);
		leprechaunHolds(FarmingTool.ULTRACOMPOST, 1000);

		int bucket = CompostTier.ULTRACOMPOST.getItemID();
		assertEquals("marked, so it shows up in the bank",
			LoadoutItem.Need.AT_LEPRECHAUN, loadout.highlights(HERBS).get(bucket));
		assertFalse("but not as something to take",
			withdrawals(HERBS).contains(bucket));
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
				&& Seed.MAGIC.getName().equals(item.getName()))
			{
				entry = item;
			}
		}
		assertNotNull("five magic seeds in the bank is owning magic seeds", entry);
		assertEquals("not MISSING - they are right there",
			LoadoutItem.Need.WITHDRAW, entry.getNeed());

		Set<Integer> marked = withdrawals(trees);
		assertTrue("the seed is what you have to find in the bank",
			marked.contains(Seed.MAGIC.getItemID()));
		assertTrue("and the sapling counts too, in case some are already potted",
			marked.contains(Seed.MAGIC.getPlantedItemID()));
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
	 * A teleport the table already knows keeps the table's answer, not the list's.
	 *
	 * <p>The table knows <i>where a teleport goes</i>, which is a better reason than "you listed
	 * it" — so it is offered first and the list does not overwrite it. Caught by getting this
	 * backwards in a test: a house tablet is already in the table, under the table's own name.
	 */
	@Test
	public void theRegionTableWinsForItemsItAlreadyKnows()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);

		int houseTablet = net.runelite.api.gameval.ItemID.POH_TABLET_TELEPORTTOHOUSE;
		bankHolds(houseTablet, 1);
		names.put(houseTablet, "Teleport to house");
		when(config.teleportItems()).thenReturn("Teleport to house");

		assertNull("not under the name you typed",
			find(LoadoutItem.Category.TELEPORT, "Teleport to house"));

		LoadoutItem entry = find(LoadoutItem.Category.TELEPORT, "Teleport to house tablet");
		assertNotNull("but under the table's own name", entry);
		assertEquals("and with the table's reason, which says what it is for",
			"Needed to use fairy rings", entry.getReason());
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

	/** The default list is derived from the table, so it cannot drift out of step with it. */
	@Test
	public void theDefaultTeleportListNamesEveryKnownTeleport()
	{
		String defaults = TeleportItems.defaultNames();
		for (TeleportItems.Teleport teleport : TeleportItems.forRegion(-1))
		{
			assertEquals("the universal teleports are on the default list too",
				true, defaults.contains(teleport.getName()));
		}
		assertEquals("and it is comma separated", true, defaults.contains(", "));
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

	private void readyHerbPatch()
	{
		FarmPatch patch = FarmingWorldData.getPatch("10548.4774");
		assertNotNull("fixture patch no longer exists", patch);
		ProduceState decoded = patch.getImplementation().forVarbitValue(43);
		assertNotNull(decoded);
		patches.recordVarbit(patch, 43, decoded);
		availability.setAvailable(patch, true);
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
	private void seedsInBank(Seed seed, int quantity)
	{
		bankHolds(seed.getItemID(), quantity);
		seeds.record(com.dooglemaps.state.SeedSource.BANK.getContainerId(),
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
