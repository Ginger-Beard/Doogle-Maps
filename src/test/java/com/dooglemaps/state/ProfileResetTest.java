package com.dooglemaps.state;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.data.Seed;
import com.dooglemaps.route.BankLocationStore;
import com.dooglemaps.route.PatchLocationStore;
import com.dooglemaps.timer.FarmingBonuses;
import com.dooglemaps.validate.CropHarvestStats;
import com.dooglemaps.validate.HarvestStatsStore;
import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Covers what a profile reset takes and, more importantly, what it leaves.
 *
 * <p>Everything cached is rebuilt by playing, so clearing it is cheap. Two things are not:
 * the player's settings, and the lifetime harvest statistics. Both live in the same config
 * group as the cache, so "clear the group" would have taken them with it — which is why the
 * reset names each store instead, and why the interesting assertions here are the survivals.
 */
public class ProfileResetTest
{
	private static final String FALADOR_NORTH = "12083.4771";

	private final Map<String, String> stored = new HashMap<>();

	private ConfigManager configManager;
	private Gson gson;

	private PatchStateStore patches;
	private AvailabilityProfile availability;
	private SeedInventoryStore seeds;
	private SeedSelectionStore selection;
	private FarmingBonusStore bonuses;
	private PatchLocationStore patchLocations;
	private BankLocationStore bankLocations;
	private HarvestStatsStore stats;
	private ProfileReset reset;

	@Before
	public void setUp() throws Exception
	{
		gson = new Gson();
		configManager = Mockito.mock(ConfigManager.class);

		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer((InvocationOnMock i) -> stored.get(key(i)));
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(int.class)))
			.thenAnswer((InvocationOnMock i) ->
			{
				String value = stored.get(key(i));
				return value == null ? null : Integer.valueOf(value);
			});
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(boolean.class)))
			.thenAnswer((InvocationOnMock i) ->
			{
				String value = stored.get(key(i));
				return value == null ? null : Boolean.valueOf(value);
			});
		doAnswer((InvocationOnMock i) ->
		{
			String name = i.getArgument(1);
			Object value = i.getArgument(2);
			stored.put(i.getArgument(0) + "." + name, String.valueOf(value));
			return null;
		}).when(configManager).setRSProfileConfiguration(anyString(), anyString(), Mockito.any());
		doAnswer((InvocationOnMock i) ->
		{
			stored.remove(key(i));
			return null;
		}).when(configManager).unsetRSProfileConfiguration(anyString(), anyString());

		patches = construct(PatchStateStore.class, configManager, gson);
		availability = construct(AvailabilityProfile.class, configManager, gson, patches);
		seeds = construct(SeedInventoryStore.class,
			Mockito.mock(net.runelite.api.Client.class), configManager, gson);
		selection = construct(SeedSelectionStore.class, configManager, gson);
		bonuses = construct(FarmingBonusStore.class, configManager, patches,
			Mockito.mock(net.runelite.client.game.ItemManager.class),
			Mockito.mock(net.runelite.api.Client.class));
		patchLocations = construct(PatchLocationStore.class, configManager, gson);
		bankLocations = construct(BankLocationStore.class, configManager, gson);
		stats = construct(HarvestStatsStore.class, configManager, gson);

		reset = construct(ProfileReset.class, patches, seeds, bonuses, patchLocations,
			bankLocations);
	}

	private static String key(InvocationOnMock invocation)
	{
		return invocation.getArgument(0) + "." + invocation.getArgument(1);
	}

	/** Fills every store with something, so a reset has work to do. */
	private void populate()
	{
		FarmPatch patch = patch();

		ProduceState ripe = patch.getImplementation().forVarbitValue(10);
		assertNotNull(ripe);
		patches.recordVarbit(patch, 10, ripe);
		patches.recordCompost(patch, CompostTier.ULTRACOMPOST);

		availability.setAvailable(patch, false);
		selection.toggle(Seed.RANARR);
		patchLocations.record(patch, new WorldPoint(3050, 3300, 0));
		bankLocations.record(new WorldPoint(3185, 3436, 0));
		stored.put("dooglemaps.hasFarmingCape", "true");

		// Seeded through the stored JSON rather than by building a HarvestRecord, whose
		// mutators are package-private on purpose - only HarvestLog should be writing them.
		CropHarvestStats harvested = new CropHarvestStats();
		harvested.setCrop("Potato");
		harvested.setCompost(CompostTier.ULTRACOMPOST.name());
		harvested.setHarvests(1);
		harvested.setItems(11);
		harvested.setBest(11);
		harvested.setWorst(11);
		Map<String, CropHarvestStats> seeded = new HashMap<>();
		seeded.put("Potato|" + CompostTier.ULTRACOMPOST.name(), harvested);
		stored.put("dooglemaps.harvestStats", gson.toJson(seeded));
		stats.load();
	}

	@Test
	public void clearsEverythingLearnedAboutTheAccount()
	{
		populate();
		assertNotNull(patches.get(patch()));

		reset.reset();

		assertNull("patch state survived", patches.get(patch()));
		assertEquals("a learned bank survived", 0, bankLocations.getLearnedCount());
		assertEquals("the cached Farming level survived", 0, seeds.getFarmingLevel());
		assertEquals("a carried bonus survived", FarmingBonuses.NONE, bonuses.current());
	}

	/**
	 * Choices the player made by clicking are settings in all but name.
	 *
	 * <p>Which patches are shown and which seeds are picked for the run were both typed in by
	 * hand, so a button meant to clear stale <i>observed</i> state has no business touching
	 * them — and re-hiding a hundred patches is not a small thing to ask.
	 */
	@Test
	public void keepsThePlayersOwnChoices()
	{
		populate();

		reset.reset();

		assertTrue("the explicit hide was cleared", availability.isExplicitlySet(patch()));
		assertFalse("and it should still be hidden", availability.isAvailable(patch()));
		assertTrue("the run's seed selection was cleared",
			selection.getSelected().contains(Seed.RANARR));
	}

	/**
	 * The one thing a reset must never take.
	 *
	 * <p>Harvest statistics are earned history — nothing rebuilds them by playing, unlike
	 * every other store here. They share a config group with the cache, so this is a real
	 * hazard rather than a hypothetical one.
	 */
	@Test
	public void keepsHarvestStatistics()
	{
		populate();
		assertEquals(11, stats.getTotalItems());

		reset.reset();

		assertEquals("the harvest history was swept up with the cache", 11, stats.getTotalItems());

		HarvestStatsStore reloaded = statsFromConfig();
		assertEquals("and it is still on disk", 11, reloaded.getTotalItems());
		assertEquals(1, reloaded.getTotalHarvests());
	}

	/** Settings share the config group with the cache, and must come through untouched. */
	@Test
	public void keepsSettings()
	{
		populate();
		stored.put("dooglemaps.showTimers", "false");
		stored.put("dooglemaps.showSpiritTree", "false");

		reset.reset();

		assertEquals("false", stored.get("dooglemaps.showTimers"));
		assertEquals("false", stored.get("dooglemaps.showSpiritTree"));
	}

	/**
	 * The reset may only hold stores it is allowed to clear.
	 *
	 * <p>Read off {@link ProfileReset}'s own fields rather than trusting the other tests to
	 * notice. Each of these is off limits for a different reason — the stats cannot be
	 * rebuilt at all, the other two are the player's own choices — but the failure mode is
	 * identical and silent: someone adds a dependency, and data disappears.
	 */
	@Test
	public void holdsNoStoreItMustNotClear()
	{
		Class<?>[] offLimits = {
			HarvestStatsStore.class,   // earned history; nothing rebuilds it
			AvailabilityProfile.class, // the player's shown/hidden patch toggles
			SeedSelectionStore.class,  // the seeds picked for the run
			RunTypeStore.class,        // the patch types the run covers
			CompostSelectionStore.class, // what the player intends to treat each type with
		};

		for (Field field : ProfileReset.class.getDeclaredFields())
		{
			for (Class<?> banned : offLimits)
			{
				assertTrue("ProfileReset must not hold " + banned.getSimpleName(),
					field.getType() != banned);
			}
		}
	}

	/**
	 * A reset lands on the fresh-install state, not an empty one.
	 *
	 * <p>Core Time Tracking has usually been recording the same varbits for as long as the
	 * account has existed, and a first run backfills from it. Reloading after a reset runs
	 * that same backfill, so the overview repopulates instead of sitting blank until the
	 * player walks the whole world again.
	 */
	@Test
	public void reloadingAfterAResetSeedsFromCoreTimeTracking()
	{
		populate();

		// What core Time Tracking has quietly stored for this patch: "<varbit>:<unixSeconds>".
		stored.put("timetracking." + FALADOR_NORTH, "10:1700000000");
		stored.put("timetracking." + FALADOR_NORTH + ".compost", "ULTRACOMPOST");

		reset.reset();
		assertNull("the reset should have emptied our own cache", patches.get(patch()));

		patches.load();

		PatchSnapshot seeded = patches.get(patch());
		assertNotNull("core Time Tracking's own record was ignored", seeded);
		assertEquals(10, seeded.getVarbitValue());
		assertEquals(CompostTier.ULTRACOMPOST, seeded.getCompost());
	}

	/**
	 * The Farming level has to come back without waiting for a Farming XP drop.
	 *
	 * <p>It is otherwise only learned from {@code StatChanged}, which fires when the player
	 * gains Farming experience — possibly not for hours. Meanwhile a level of 0 makes every
	 * yield estimate hide itself, so the panel would quietly lose a feature after a reset.
	 */
	@Test
	public void theFarmingLevelIsRelearnedWithoutAnXpDrop() throws Exception
	{
		net.runelite.api.Client client = Mockito.mock(net.runelite.api.Client.class);
		when(client.getRealSkillLevel(net.runelite.api.Skill.FARMING)).thenReturn(92);
		SeedInventoryStore live = construct(SeedInventoryStore.class, client, configManager, gson);

		live.recordFarmingLevel();
		assertEquals(92, live.getFarmingLevel());

		live.clear();
		assertEquals("cleared, as the reset intends", 0, live.getFarmingLevel());

		// What the plugin does on the client thread straight after resetting.
		live.recordFarmingLevel();
		assertEquals(92, live.getFarmingLevel());
	}

	/**
	 * Seed counts come back for whatever the client is still holding.
	 *
	 * <p>The bank cannot be re-read from memory if it was never opened, but the inventory
	 * always can, and so can anything opened earlier in the session. Waiting for the next
	 * container event would leave the seed list blank in the meantime.
	 */
	@Test
	public void seedCountsAreRelearnedFromContainersTheClientStillHolds() throws Exception
	{
		net.runelite.api.Client client = Mockito.mock(net.runelite.api.Client.class);
		SeedInventoryStore live = construct(SeedInventoryStore.class, client, configManager, gson);

		net.runelite.api.ItemContainer inventory =
			Mockito.mock(net.runelite.api.ItemContainer.class);
		when(inventory.getItems()).thenReturn(new net.runelite.api.Item[]{
			new net.runelite.api.Item(Seed.RANARR.getItemID(), 7),
		});
		when(client.getItemContainer(SeedSource.INVENTORY.getContainerId()))
			.thenReturn(inventory);

		live.clear();
		assertEquals(0, live.getOwned(Seed.RANARR));

		live.relearnFromClient();
		assertEquals(7, live.getOwned(Seed.RANARR));
	}

	/** Resetting twice, or with nothing stored, must be uneventful. */
	@Test
	public void isSafeToRunOnAnEmptyProfile()
	{
		reset.reset();
		reset.reset();

		assertNull(patches.get(patch()));
		assertEquals(0, bankLocations.getLearnedCount());
	}

	// ------------------------------------------------------------------- helpers

	private HarvestStatsStore statsFromConfig()
	{
		try
		{
			HarvestStatsStore store = construct(HarvestStatsStore.class, configManager, gson);
			store.load();
			return store;
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static FarmPatch patch()
	{
		FarmPatch patch = FarmingWorldData.getPatch(FALADOR_NORTH);
		assertNotNull("fixture patch no longer exists", patch);
		return patch;
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
