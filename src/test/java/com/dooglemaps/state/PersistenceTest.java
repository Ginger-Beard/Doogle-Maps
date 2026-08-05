package com.dooglemaps.state;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.ProduceState;
import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Round-trips the stores through a config manager that actually stores things.
 *
 * <p>Everything the plugin knows lives in RuneLite's per-profile config, so "does it come
 * back after a restart" is not something to find out in-game. These tests save, throw the
 * store away, load a fresh one, and check.
 */
public class PersistenceTest
{
	/** The two allotments in Falador, which share a farmer — the awkward case. */
	private static final String FALADOR_NORTH = "12083.4771";
	private static final String FALADOR_SOUTH = "12083.4772";
	private static final String FALADOR_HERB = "12083.4774";

	private Map<String, String> stored;
	private ConfigManager configManager;
	private Gson gson;

	@Before
	public void setUp()
	{
		stored = new HashMap<>();
		gson = new Gson();
		configManager = Mockito.mock(ConfigManager.class);

		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer(i -> stored.get(key(i)));
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(int.class)))
			.thenAnswer(i ->
			{
				String value = stored.get(key(i));
				return value == null ? null : Integer.valueOf(value);
			});
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(Boolean.class)))
			.thenAnswer(i ->
			{
				String value = stored.get(key(i));
				return value == null ? null : Boolean.valueOf(value);
			});

		doAnswer(i ->
		{
			Object value = i.getArgument(2);
			stored.put(key(i), String.valueOf(value));
			return null;
		}).when(configManager).setRSProfileConfiguration(anyString(), anyString(), Mockito.any());

		doAnswer(i ->
		{
			stored.remove(key(i));
			return null;
		}).when(configManager).unsetRSProfileConfiguration(anyString(), anyString());
	}

	/** Config is addressed by group and key; join them the way RuneLite does. */
	private static String key(org.mockito.invocation.InvocationOnMock invocation)
	{
		Object group = invocation.getArgument(0);
		Object name = invocation.getArgument(1);
		return group + "." + name;
	}

	@SuppressWarnings("unchecked")
	private <T> T construct(Class<T> type, Object... args) throws Exception
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

	private PatchStateStore newStateStore() throws Exception
	{
		return construct(PatchStateStore.class, configManager, gson);
	}

	private AvailabilityProfile newAvailability(PatchStateStore store) throws Exception
	{
		return construct(AvailabilityProfile.class, configManager, gson, store);
	}

	private static FarmPatch patch(String key)
	{
		FarmPatch patch = FarmingWorldData.getPatch(key);
		assertNotNull("fixture patch " + key + " no longer exists", patch);
		return patch;
	}

	private static void record(PatchStateStore store, String key, int varbitValue)
	{
		FarmPatch p = patch(key);
		ProduceState decoded = p.getImplementation().forVarbitValue(varbitValue);
		assertNotNull("varbit " + varbitValue + " does not decode for " + key, decoded);
		store.recordVarbit(p, varbitValue, decoded);
	}

	// ------------------------------------------------------------ availability

	@Test
	public void switchingAPatchOffSurvivesARestart() throws Exception
	{
		PatchStateStore store = newStateStore();
		AvailabilityProfile availability = newAvailability(store);
		availability.load();

		availability.setAvailable(patch(FALADOR_HERB), false);
		assertFalse(availability.isAvailable(patch(FALADOR_HERB)));

		// Restart: brand new objects, same config.
		PatchStateStore reloadedStore = newStateStore();
		AvailabilityProfile reloaded = newAvailability(reloadedStore);
		reloadedStore.load();
		reloaded.load();

		assertFalse("a patch switched off came back after a restart",
			reloaded.isAvailable(patch(FALADOR_HERB)));
	}

	@Test
	public void switchingAPatchOffSurvivesEvenWhenItHasCachedState() throws Exception
	{
		// The dangerous combination: availability falls back to "have we seen it" when the
		// player has expressed no preference, so a hidden patch that we DO have state for
		// would reappear if the explicit "off" were ever lost.
		PatchStateStore store = newStateStore();
		AvailabilityProfile availability = newAvailability(store);
		store.load();
		availability.load();

		record(store, FALADOR_HERB, 33);
		availability.setAvailable(patch(FALADOR_HERB), false);

		PatchStateStore reloadedStore = newStateStore();
		AvailabilityProfile reloaded = newAvailability(reloadedStore);
		reloadedStore.load();
		reloaded.load();

		assertTrue("state should still be cached", reloadedStore.get(patch(FALADOR_HERB)) != null);
		assertFalse("a hidden patch reappeared because it had cached state",
			reloaded.isAvailable(patch(FALADOR_HERB)));
	}

	@Test
	public void bulkTogglesSurviveARestart() throws Exception
	{
		PatchStateStore store = newStateStore();
		AvailabilityProfile availability = newAvailability(store);
		availability.load();

		availability.setTypeAvailable(PatchImplementation.ALLOTMENT, false);

		AvailabilityProfile reloaded = newAvailability(newStateStore());
		reloaded.load();

		assertTrue("no allotment should be available",
			reloaded.getAvailablePatches(PatchImplementation.ALLOTMENT).isEmpty());
	}

	// ----------------------------------------------------------------- compost

	@Test
	public void compostOnAnEmptyPatchIsNotWipedByTheNextScan() throws Exception
	{
		// Composting before planting is the normal order of play. A raked patch reads as
		// weeds, so treating weeds as "empty, forget the treatment" wiped it a tick later.
		PatchStateStore store = newStateStore();
		store.load();

		record(store, FALADOR_NORTH, 3);   // raked, empty
		store.recordCompost(patch(FALADOR_NORTH), CompostTier.ULTRACOMPOST);
		record(store, FALADOR_NORTH, 3);   // the tracker scans again a tick later

		assertEquals(CompostTier.ULTRACOMPOST, store.get(patch(FALADOR_NORTH)).getCompost());
	}

	/**
	 * Compost has to outlive the crop turning ripe, because that is when it does its job.
	 *
	 * <p>Its main effect is extra harvest lives, and lives are spent one per pick — all of
	 * which happens after the patch reads harvestable. Forgetting the treatment at that
	 * moment would understate an ultracomposted patch by three items, which is the difference
	 * between a useful estimate and a misleading one.
	 */
	@Test
	public void compostSurvivesUntilThePatchIsActuallyEmptied() throws Exception
	{
		PatchStateStore store = newStateStore();
		store.load();

		record(store, FALADOR_NORTH, 6);   // potato, growing
		store.recordCompost(patch(FALADOR_NORTH), CompostTier.SUPERCOMPOST);

		record(store, FALADOR_NORTH, 10);  // potato, harvestable
		assertEquals("still doing its work while the patch is being picked",
			CompostTier.SUPERCOMPOST, store.get(patch(FALADOR_NORTH)).getCompost());

		record(store, FALADOR_NORTH, 3);   // picked clean, back to weeds/empty
		assertEquals("spent along with the crop it fed",
			CompostTier.NONE, store.get(patch(FALADOR_NORTH)).getCompost());
	}

	/**
	 * Inspecting a fully grown composted patch must not make the icon flash on and vanish.
	 *
	 * <p>The reported symptom, exactly: Inspect a finished patch, the compost icon appears for
	 * an instant and is gone. Inspect is the <i>only</i> way to learn about compost applied
	 * before the plugin was watching, so the one action that recovers the information was
	 * also the one that immediately threw it away.
	 *
	 * <p>The cause was not the inspect pairing but the scan a tick later: a fully grown patch
	 * re-decodes to the same harvestable state every tick, and harvestable used to clear the
	 * treatment. So the sequence here is deliberately "record, then scan the same state
	 * again", because a single scan would have passed even with the bug.
	 */
	@Test
	public void inspectingAGrownPatchDoesNotFlashTheCompostIconAway() throws Exception
	{
		PatchStateStore store = newStateStore();
		store.load();

		record(store, FALADOR_NORTH, 10);  // potato, fully grown and ready
		store.recordCompost(patch(FALADOR_NORTH), CompostTier.ULTRACOMPOST);

		// The patch scanner runs every tick and keeps seeing the same ready potato.
		record(store, FALADOR_NORTH, 10);
		record(store, FALADOR_NORTH, 10);

		assertEquals("what Inspect just told us must survive the next scan",
			CompostTier.ULTRACOMPOST, store.get(patch(FALADOR_NORTH)).getCompost());
	}

	/** Protection, unlike compost, really is finished the moment the crop cannot be diseased. */
	@Test
	public void protectionIsSpentOnceTheCropIsReady() throws Exception
	{
		PatchStateStore store = newStateStore();
		store.load();

		record(store, FALADOR_NORTH, 6);
		store.recordProtected(patch(FALADOR_NORTH), true);
		record(store, FALADOR_NORTH, 10);

		assertFalse("a ripe crop cannot catch anything, so the payment is done",
			store.get(patch(FALADOR_NORTH)).isPatchProtected());
	}

	@Test
	public void compostOnOnePatchDoesNotDisturbItsNeighbour() throws Exception
	{
		// Falador's two allotments share a farmer and sit side by side; the reported bug
		// was one losing its icon when the other was inspected.
		PatchStateStore store = newStateStore();
		store.load();

		record(store, FALADOR_NORTH, 3);
		record(store, FALADOR_SOUTH, 3);

		store.recordCompost(patch(FALADOR_NORTH), CompostTier.ULTRACOMPOST);
		store.recordCompost(patch(FALADOR_SOUTH), CompostTier.COMPOST);

		record(store, FALADOR_NORTH, 3);
		record(store, FALADOR_SOUTH, 3);

		assertEquals(CompostTier.ULTRACOMPOST, store.get(patch(FALADOR_NORTH)).getCompost());
		assertEquals(CompostTier.COMPOST, store.get(patch(FALADOR_SOUTH)).getCompost());
	}

	@Test
	public void compostAndProtectionSurviveARestart() throws Exception
	{
		PatchStateStore store = newStateStore();
		store.load();

		record(store, FALADOR_NORTH, 6);
		store.recordCompost(patch(FALADOR_NORTH), CompostTier.ULTRACOMPOST);
		store.recordProtected(patch(FALADOR_NORTH), true);

		PatchStateStore reloaded = newStateStore();
		reloaded.load();

		PatchSnapshot snapshot = reloaded.get(patch(FALADOR_NORTH));
		assertNotNull(snapshot);
		assertEquals(CompostTier.ULTRACOMPOST, snapshot.getCompost());
		assertTrue(snapshot.isPatchProtected());
	}

	// -------------------------------------------------------------------- seeds

	/**
	 * Reading seed counts must never touch the client.
	 *
	 * <p>The panel repaints on the Swing thread, and {@code Client.getItemContainer} asserts
	 * it is on the client thread — so reading through to the client threw an AssertionError
	 * on every tab click. The client here fails loudly if anything reaches for it.
	 */
	@Test
	public void readingCountsNeverTouchesTheClient() throws Exception
	{
		net.runelite.api.Client client = Mockito.mock(net.runelite.api.Client.class);

		SeedInventoryStore seeds = construct(SeedInventoryStore.class, client, configManager, gson);
		seeds.load();

		// Capture runs on the client thread, from an item-container event, and is allowed to
		// read the client — it re-reads the sibling containers there on purpose, so a missed
		// seed-box update cannot leave a stale count behind.
		net.runelite.api.ItemContainer inventory = Mockito.mock(net.runelite.api.ItemContainer.class);
		when(inventory.getItems()).thenReturn(new net.runelite.api.Item[]{
			new net.runelite.api.Item(com.dooglemaps.data.Seed.RANARR.getItemID(), 4),
		});
		assertTrue(seeds.record(SeedSource.INVENTORY.getContainerId(), inventory));

		// From here on the client is off limits: this is what the panel does while
		// repainting on the Swing thread, where getItemContainer would assert.
		when(client.getItemContainer(Mockito.anyInt()))
			.thenThrow(new AssertionError("must be called on client thread"));

		// Everything a panel does while repainting.
		assertEquals(4, seeds.getCount(com.dooglemaps.data.Seed.RANARR, SeedSource.INVENTORY));
		assertEquals(4, seeds.getOwned(com.dooglemaps.data.Seed.RANARR));
		assertEquals(0, seeds.getOwned(com.dooglemaps.data.Seed.GUAM));
	}

	/**
	 * A full seed box round trip has to conserve seeds.
	 *
	 * <p>Both halves were reported broken, in opposite directions: filling the box made the
	 * seeds vanish, and emptying it made the count double. One cause — the client's copy of
	 * the seed box container lags a step behind the action that changed it, so reading it
	 * afterwards returns the contents from <i>before</i> the move. Filling therefore read the
	 * box as still empty, and emptying read it as still full while the seeds were also back
	 * in the inventory.
	 *
	 * <p>So the box is not read at all here. Fill and Empty have exact semantics, and the
	 * inventory is always live, which is enough to derive the box outright.
	 */
	@Test
	public void fillingAndEmptyingTheSeedBoxConservesSeeds() throws Exception
	{
		net.runelite.api.Client client = Mockito.mock(net.runelite.api.Client.class);
		SeedInventoryStore seeds = construct(SeedInventoryStore.class, client, configManager, gson);
		seeds.load();

		int ranarr = com.dooglemaps.data.Seed.RANARR.getItemID();
		net.runelite.api.ItemContainer empty = container();
		net.runelite.api.ItemContainer six = container(ranarr, 6);

		// Six loose in the inventory.
		seeds.record(SeedSource.INVENTORY.getContainerId(), six);
		assertEquals(6, seeds.getOwned(com.dooglemaps.data.Seed.RANARR));

		// Fill: they leave the inventory, and the box's own container says nothing yet.
		seeds.noteSeedBoxAction(SeedBoxAction.FILL);
		seeds.record(SeedSource.INVENTORY.getContainerId(), empty);

		assertEquals("filling the box made the seeds disappear",
			6, seeds.getOwned(com.dooglemaps.data.Seed.RANARR));
		assertEquals(6, seeds.getCount(com.dooglemaps.data.Seed.RANARR, SeedSource.SEED_BOX));
		assertEquals(0, seeds.getCount(com.dooglemaps.data.Seed.RANARR, SeedSource.INVENTORY));

		// Empty: the box tips everything back, so it is provably empty afterwards.
		seeds.noteSeedBoxAction(SeedBoxAction.EMPTY);
		seeds.record(SeedSource.INVENTORY.getContainerId(), six);

		assertEquals("emptying the box double-counted the seeds",
			6, seeds.getOwned(com.dooglemaps.data.Seed.RANARR));
		assertEquals(0, seeds.getCount(com.dooglemaps.data.Seed.RANARR, SeedSource.SEED_BOX));
	}

	/** Seeds leaving the inventory for any other reason must not be credited to the box. */
	@Test
	public void plantingSeedsDoesNotPutThemInTheSeedBox() throws Exception
	{
		net.runelite.api.Client client = Mockito.mock(net.runelite.api.Client.class);
		SeedInventoryStore seeds = construct(SeedInventoryStore.class, client, configManager, gson);
		seeds.load();

		int ranarr = com.dooglemaps.data.Seed.RANARR.getItemID();
		seeds.record(SeedSource.INVENTORY.getContainerId(), container(ranarr, 6));
		// No Fill was clicked - the seeds went into the ground.
		seeds.record(SeedSource.INVENTORY.getContainerId(), container(ranarr, 5));

		assertEquals(0, seeds.getCount(com.dooglemaps.data.Seed.RANARR, SeedSource.SEED_BOX));
		assertEquals(5, seeds.getOwned(com.dooglemaps.data.Seed.RANARR));
	}

	/** A Fill adds to whatever the box already held rather than replacing it. */
	@Test
	public void fillingOnTopOfAPartlyFullBoxAddsUp() throws Exception
	{
		net.runelite.api.Client client = Mockito.mock(net.runelite.api.Client.class);
		SeedInventoryStore seeds = construct(SeedInventoryStore.class, client, configManager, gson);
		seeds.load();

		int ranarr = com.dooglemaps.data.Seed.RANARR.getItemID();
		seeds.record(SeedSource.SEED_BOX.getContainerId(), container(ranarr, 10));
		seeds.record(SeedSource.INVENTORY.getContainerId(), container(ranarr, 6));

		seeds.noteSeedBoxAction(SeedBoxAction.FILL);
		seeds.record(SeedSource.INVENTORY.getContainerId(), container());

		assertEquals(16, seeds.getCount(com.dooglemaps.data.Seed.RANARR, SeedSource.SEED_BOX));
		assertEquals(16, seeds.getOwned(com.dooglemaps.data.Seed.RANARR));
	}

	/**
	 * Seeds can enter the box without ever touching the inventory.
	 *
	 * <p>Pickpocketing a Master Farmer with a seed box does exactly that, so there is no
	 * inventory delta to derive them from and the box is not open to report itself. The chat
	 * message is the only evidence, and it has to be able to move the count on its own.
	 */
	@Test
	public void seedsCanBeCreditedStraightToTheBox() throws Exception
	{
		net.runelite.api.Client client = Mockito.mock(net.runelite.api.Client.class);
		SeedInventoryStore seeds = construct(SeedInventoryStore.class, client, configManager, gson);
		seeds.load();

		int ranarr = com.dooglemaps.data.Seed.RANARR.getItemID();
		seeds.addToSeedBox(ranarr, 3);
		seeds.addToSeedBox(ranarr, 2);

		assertEquals(5, seeds.getCount(com.dooglemaps.data.Seed.RANARR, SeedSource.SEED_BOX));
		assertEquals(5, seeds.getOwned(com.dooglemaps.data.Seed.RANARR));

		// Anything that is not a seed, or a nonsense quantity, is ignored rather than stored.
		seeds.addToSeedBox(com.dooglemaps.data.Seed.RANARR.getItemID(), 0);
		seeds.addToSeedBox(995, 100);
		assertEquals(5, seeds.getCount(com.dooglemaps.data.Seed.RANARR, SeedSource.SEED_BOX));
	}

	/**
	 * Re-reading a container with the same contents must not notify anyone.
	 *
	 * <p>Opening a bank fires a container change carrying a thousand items, and the seed
	 * counts in it are almost always exactly what we already had. Treating that as a change
	 * rewrote the config and rebuilt the visible tab, which is what made opening a bank feel
	 * like it stuttered. The seed vault is the same shape and gets this for the same reason -
	 * both go through one code path, which is why both are checked here.
	 */
	@Test
	public void reopeningAContainerWithUnchangedContentsIsSilent() throws Exception
	{
		net.runelite.api.Client client = Mockito.mock(net.runelite.api.Client.class);
		SeedInventoryStore seeds = construct(SeedInventoryStore.class, client, configManager, gson);
		seeds.load();

		int[] notifications = {0};
		seeds.addChangeListener(() -> notifications[0]++);

		int ranarr = com.dooglemaps.data.Seed.RANARR.getItemID();
		int guam = com.dooglemaps.data.Seed.GUAM.getItemID();

		for (SeedSource source : new SeedSource[]{SeedSource.BANK, SeedSource.SEED_VAULT})
		{
			notifications[0] = 0;

			seeds.record(source.getContainerId(), container(ranarr, 40, guam, 412));
			assertEquals(source + ": first sight is a change", 1, notifications[0]);

			// Opening it again, and again, with exactly the same seeds in it.
			seeds.record(source.getContainerId(), container(ranarr, 40, guam, 412));
			seeds.record(source.getContainerId(), container(ranarr, 40, guam, 412));
			assertEquals(source + ": reopening should not repaint the panel",
				1, notifications[0]);

			// But a real change still gets through.
			seeds.record(source.getContainerId(), container(ranarr, 39, guam, 412));
			assertEquals(source + ": a withdrawal was missed", 2, notifications[0]);
			assertEquals(39, seeds.getCount(com.dooglemaps.data.Seed.RANARR, source));
		}
	}

	/** The "seen just now" timestamp is still refreshed even when nothing moved. */
	@Test
	public void anUnchangedReadStillCountsAsHavingLooked() throws Exception
	{
		net.runelite.api.Client client = Mockito.mock(net.runelite.api.Client.class);
		SeedInventoryStore seeds = construct(SeedInventoryStore.class, client, configManager, gson);
		seeds.load();

		int ranarr = com.dooglemaps.data.Seed.RANARR.getItemID();
		seeds.record(SeedSource.BANK.getContainerId(), container(ranarr, 40));

		long seen = seeds.getLastSeen(SeedSource.BANK);
		assertTrue(seen > 0);

		seeds.record(SeedSource.BANK.getContainerId(), container(ranarr, 40));
		assertTrue("the tooltip would claim the count is older than it is",
			seeds.getLastSeen(SeedSource.BANK) >= seen);
	}

	/** A stand-in item container holding the given id/quantity pairs. */
	private static net.runelite.api.ItemContainer container(int... idThenQuantity)
	{
		net.runelite.api.ItemContainer container =
			Mockito.mock(net.runelite.api.ItemContainer.class);
		net.runelite.api.Item[] items = new net.runelite.api.Item[idThenQuantity.length / 2];
		for (int i = 0; i < items.length; i++)
		{
			items[i] = new net.runelite.api.Item(idThenQuantity[i * 2], idThenQuantity[i * 2 + 1]);
		}
		when(container.getItems()).thenReturn(items);
		return container;
	}

	@Test
	public void theInventoryIsHeldInMemoryButNotWrittenToDisk() throws Exception
	{
		net.runelite.api.Client client = Mockito.mock(net.runelite.api.Client.class);
		SeedInventoryStore seeds = construct(SeedInventoryStore.class, client, configManager, gson);
		seeds.load();

		net.runelite.api.ItemContainer inventory = Mockito.mock(net.runelite.api.ItemContainer.class);
		when(inventory.getItems()).thenReturn(new net.runelite.api.Item[]{
			new net.runelite.api.Item(com.dooglemaps.data.Seed.RANARR.getItemID(), 4),
		});
		seeds.record(SeedSource.INVENTORY.getContainerId(), inventory);

		assertEquals("readable straight away", 4,
			seeds.getCount(com.dooglemaps.data.Seed.RANARR, SeedSource.INVENTORY));
		assertFalse("an inventory alone is not knowing what the account owns",
			seeds.hasEverBeenPopulated());

		SeedInventoryStore reloaded = construct(SeedInventoryStore.class, client, configManager, gson);
		reloaded.load();
		assertEquals("the inventory is re-sent on login, so it is not worth persisting",
			0, reloaded.getCount(com.dooglemaps.data.Seed.RANARR, SeedSource.INVENTORY));
	}

	@Test
	public void seedCountsSurviveARestart() throws Exception
	{
		net.runelite.api.Client client = Mockito.mock(net.runelite.api.Client.class);
		SeedInventoryStore seeds = construct(SeedInventoryStore.class, client, configManager, gson);
		seeds.load();
		assertFalse("nothing has been read yet", seeds.hasEverBeenPopulated());

		net.runelite.api.ItemContainer bank = Mockito.mock(net.runelite.api.ItemContainer.class);
		when(bank.getItems()).thenReturn(new net.runelite.api.Item[]{
			new net.runelite.api.Item(com.dooglemaps.data.Seed.RANARR.getItemID(), 40),
			new net.runelite.api.Item(com.dooglemaps.data.Seed.SNAPDRAGON.getItemID(), 12),
		});
		assertTrue(seeds.record(SeedSource.BANK.getContainerId(), bank));

		SeedInventoryStore reloaded = construct(SeedInventoryStore.class, client, configManager, gson);
		reloaded.load();

		assertTrue("the bank should be remembered", reloaded.hasEverBeenPopulated());
		assertEquals(40, reloaded.getCount(com.dooglemaps.data.Seed.RANARR, SeedSource.BANK));
		assertEquals(12, reloaded.getCount(com.dooglemaps.data.Seed.SNAPDRAGON, SeedSource.BANK));
	}
}
