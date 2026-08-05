package com.dooglemaps.bank;

import com.dooglemaps.data.CompostTier;
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

		SeedInventoryStore seeds = construct(SeedInventoryStore.class,
			Mockito.mock(net.runelite.api.Client.class), configManager, gson);
		selection = construct(SeedSelectionStore.class, configManager, gson);
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
			playerLocation);

		carried = construct(CarriedItems.class);
		bank = construct(BankContents.class);
		loadout = construct(RunLoadout.class, planner, selection, seeds, compost, carried, bank);
	}

	/**
	 * Compost is never a withdrawal, because the leprechaun has it.
	 *
	 * <p>1,000 buckets of each tier are stored on site. A loadout that told you to bank
	 * ultracompost would be worse than saying nothing at all.
	 */
	@Test
	public void compostIsReportedAsOnSiteRatherThanAsSomethingToWithdraw()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		compost.set(PatchImplementation.HERB, CompostTier.ULTRACOMPOST);
		bankHolds(CompostTier.ULTRACOMPOST.getItemID(), 500);

		LoadoutItem entry = find(LoadoutItem.Category.COMPOST);
		assertNotNull("the chosen compost should still be mentioned", entry);
		assertEquals("even with 500 in the bank, the leprechaun is the answer",
			LoadoutItem.Need.AT_LEPRECHAUN, entry.getNeed());
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

		LoadoutItem axe = firstOf(trees, LoadoutItem.Category.TOOL);
		assertNotNull(axe);
		assertEquals("Dragon axe", axe.getName());
		assertEquals(LoadoutItem.Need.WITHDRAW, axe.getNeed());
	}

	/** An axe you cannot swing is not the answer, however good it is. */
	@Test
	public void anAxeAboveYourLevelIsSkipped()
	{
		Set<PatchImplementation> trees = EnumSet.of(PatchImplementation.TREE);
		bankHolds(ItemID.DRAGON_AXE, 1);
		bankHolds(ItemID.RUNE_AXE, 1);
		woodcuttingLevel(45);

		assertEquals("dragon wants 61 Woodcutting", "Rune axe",
			firstOf(trees, LoadoutItem.Category.TOOL).getName());
	}

	/** A herb run has nothing to chop, so no axe is suggested. */
	@Test
	public void aHerbRunDoesNotAskForAnAxe()
	{
		readyHerbPatch();
		selection.toggle(Seed.RANARR);
		bankHolds(ItemID.DRAGON_AXE, 1);
		woodcuttingLevel(99);

		assertTrue(itemsIn(LoadoutItem.Category.TOOL).isEmpty());
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
