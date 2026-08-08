package com.dooglemaps.validate;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.state.FarmingBonusStore;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.timer.FarmingBonuses;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Covers how a harvest is reassembled from events that never say what they belong to.
 *
 * <p>The interesting cases are all about attribution: the game reports "you gained a ranarr"
 * and "you gained 30.5 Farming experience" as separate, unlabelled facts, and the log has to
 * decide which patch they came from. Getting that wrong would fill the CSV with confident,
 * useless numbers, which is worse than collecting nothing.
 */
public class HarvestLogTest
{
	/** Falador's north allotment, used because its varbit values are known good fixtures. */
	private static final String FALADOR_NORTH = "12083.4771";

	private PatchStateStore patches;
	private HarvestStatsStore stats;
	private HarvestLog log;

	/** Kept so a test can build a second log with the player standing somewhere else. */
	private DoogleMapsConfig config;
	private SeedInventoryStore seeds;
	private FarmingBonusStore bonuses;
	private com.dooglemaps.route.PatchLocationStore locations;

	@Before
	public void setUp() throws Exception
	{
		config = Mockito.mock(DoogleMapsConfig.class);
		when(config.logHarvests()).thenReturn(true);

		net.runelite.client.config.ConfigManager configManager =
			Mockito.mock(net.runelite.client.config.ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(int.class)))
			.thenReturn(85);

		patches = construct(PatchStateStore.class, configManager, new com.google.gson.Gson());
		patches.load();

		seeds = construct(SeedInventoryStore.class,
			Mockito.mock(net.runelite.api.Client.class), configManager, new com.google.gson.Gson());
		bonuses = construct(FarmingBonusStore.class, configManager, patches,
			Mockito.mock(net.runelite.client.game.ItemManager.class),
			Mockito.mock(net.runelite.api.Client.class));

		stats = construct(HarvestStatsStore.class, configManager, new com.google.gson.Gson());
		stats.load();

		locations = construct(
			com.dooglemaps.route.PatchLocationStore.class, configManager,
			new com.google.gson.Gson());
		locations.load();

		// Standing at Falador's allotments, because a pick is only credited to a patch you are
		// near enough to be picking - see MAX_ATTRIBUTION_DISTANCE.
		log = logWithPlayerAt(new net.runelite.api.coords.WorldPoint(3054, 3307, 0));
	}

	/** A fresh log sharing this fixture's stores, with the player on the given tile. */
	private HarvestLog logWithPlayerAt(net.runelite.api.coords.WorldPoint where) throws Exception
	{
		return construct(HarvestLog.class, config, patches, seeds, bonuses, stats,
			new HarvestHistory(), locations, clientAt(where),
			org.mockito.Mockito.mock(net.runelite.client.config.ConfigManager.class));
	}

	/** A client whose local player is standing on the given tile. */
	private static net.runelite.api.Client clientAt(net.runelite.api.coords.WorldPoint where)
	{
		net.runelite.api.Player player = Mockito.mock(net.runelite.api.Player.class);
		when(player.getWorldLocation()).thenReturn(where);

		net.runelite.api.Client client = Mockito.mock(net.runelite.api.Client.class);
		when(client.getLocalPlayer()).thenReturn(player);
		return client;
	}

	@Test
	public void tracksAHarvestFromTheItemsThatArrive()
	{
		ripePotatoPatch(CompostTier.ULTRACOMPOST);

		// Start from an empty inventory, then pick three potatoes one at a time.
		inventory();
		inventory(Produce.POTATO.getItemID(), 1);
		inventory(Produce.POTATO.getItemID(), 2);
		inventory(Produce.POTATO.getItemID(), 3);

		HarvestRecord record = onlyOpenRecord();
		assertEquals(3, record.getItemsHarvested());
		assertEquals(Produce.POTATO, record.getProduce());
		assertEquals("compost has to survive the crop ripening to be recorded here",
			CompostTier.ULTRACOMPOST, record.getCompost());
		assertEquals(6, record.getLives());
	}

	/**
	 * The count must come from the inventory, not the patch varbit.
	 *
	 * <p>An ultracomposted patch has six lives but the varbit only has three harvest states,
	 * so it cannot represent the sixth pick. Counting items is the only way to see a harvest
	 * that runs past three — which is every composted patch, i.e. the normal case.
	 */
	@Test
	public void countsPastWhatTheVarbitCanRepresent()
	{
		ripePotatoPatch(CompostTier.ULTRACOMPOST);

		inventory();
		for (int picked = 1; picked <= 11; picked++)
		{
			inventory(Produce.POTATO.getItemID(), picked);
		}

		assertEquals(11, onlyOpenRecord().getItemsHarvested());
	}

	@Test
	public void attributesFarmingExperienceToThePatchBeingPicked()
	{
		ripePotatoPatch(CompostTier.NONE);

		inventory();
		farmingXp(1_000_000);           // the first reading only establishes a baseline
		inventory(Produce.POTATO.getItemID(), 1);
		farmingXp(1_000_009);           // a potato is 9 experience

		assertEquals(9.0, onlyOpenRecord().getXpGained(), 0.001);
	}

	/** A baseline reading must never be counted as a gain, or every session starts wrong. */
	@Test
	public void theFirstExperienceReadingIsNotAGain()
	{
		ripePotatoPatch(CompostTier.NONE);

		inventory();
		inventory(Produce.POTATO.getItemID(), 1);
		farmingXp(1_000_000);

		assertEquals(0.0, onlyOpenRecord().getXpGained(), 0.001);
	}

	/**
	 * Items with no ripe patch behind them are ignored.
	 *
	 * <p>Buying potatoes, or being handed them, must not open a harvest record — a patch that
	 * was never picked would otherwise be scored as though it had been.
	 */
	@Test
	public void ignoresCropsThatNoPatchIsHolding()
	{
		inventory();
		inventory(Produce.POTATO.getItemID(), 20);

		assertTrue("no ripe potato patch exists, so this is not a harvest",
			log.getOpenHarvests().isEmpty());
	}

	/** Losing items - eating, banking, dropping - is not a negative harvest. */
	@Test
	public void aFallingCountIsNotAHarvest()
	{
		ripePotatoPatch(CompostTier.NONE);

		inventory(Produce.POTATO.getItemID(), 10);
		inventory(Produce.POTATO.getItemID(), 4);

		assertTrue(log.getOpenHarvests().isEmpty());
	}

	/** The patch losing its crop is what closes the record. */
	@Test
	public void thePatchEmptyingEndsTheHarvest()
	{
		FarmPatch patch = ripePotatoPatch(CompostTier.NONE);

		inventory();
		inventory(Produce.POTATO.getItemID(), 4);
		assertEquals(1, log.getOpenHarvests().size());

		ProduceState ripe = patch.getImplementation().forVarbitValue(10);
		ProduceState weeds = patch.getImplementation().forVarbitValue(3);
		assertNotNull(weeds);
		log.onPatchState(patch, ripe, weeds);

		assertTrue("picked clean, so the record is finished and written out",
			log.getOpenHarvests().isEmpty());
	}

	/**
	 * Statistics are collected whenever the plugin is enabled, whatever the log setting says.
	 *
	 * <p>{@code logHarvests} used to gate every observation here, which meant a setting worded
	 * as a developer's log toggle silently emptied the whole Stats tab — and cost you months of
	 * history you did not know you were not keeping. It now governs the client-log commentary
	 * and nothing else.
	 */
	@Test
	public void statisticsAreRecordedWithVerboseLoggingOff()
	{
		when(config.logHarvests()).thenReturn(false);

		FarmPatch patch = ripePotatoPatch(CompostTier.NONE);
		inventory();
		inventory(Produce.POTATO.getItemID(), 4);

		assertEquals("the harvest is still being watched", 1, log.getOpenHarvests().size());

		ProduceState ripe = patch.getImplementation().forVarbitValue(10);
		ProduceState weeds = patch.getImplementation().forVarbitValue(3);
		assertNotNull(weeds);
		log.onPatchState(patch, ripe, weeds);
		// The record is held for a tick so late experience can still reach it.
		log.onGameTick(new net.runelite.api.events.GameTick());

		assertEquals("and it reached the lifetime totals", 1, stats.getTotalHarvests());
		assertEquals(4, stats.getTotalItems());
	}

	/**
	 * A bush that regrows still finishes its harvest.
	 *
	 * <p>The record used to close only when the patch <i>emptied</i>, which never happens to a
	 * bush, a fruit tree or a cactus — pick the last berry and the patch goes straight back to
	 * growing more. So every regrowing crop sat open until the idle timer abandoned it, and
	 * every berry ever picked was filed as "left standing": jangerberry reported zero harvests
	 * against seven items, and could never contribute an average.
	 */
	@Test
	public void aRegrowingBushFinishesWhenItsStockRunsOut()
	{
		FarmPatch patch = ripeJangerberryPatch();

		inventory();
		inventory(Produce.JANGERBERRIES.getItemID(), 4);
		assertEquals(1, log.getOpenHarvests().size());

		// Picked to nothing: the patch is not empty, it is growing the next lot.
		log.onPatchState(patch, stateOf(patch, Produce.JANGERBERRIES, CropState.HARVESTABLE),
			stateOf(patch, Produce.JANGERBERRIES, CropState.GROWING));

		assertTrue("nothing left to pick is the end of the harvest, empty or not",
			log.getOpenHarvests().isEmpty());

		// Written on the following tick, so any experience from the last pick lands first.
		log.onGameTick(new net.runelite.api.events.GameTick());
		assertEquals("and it counts as a finished patch, not an abandoned one",
			1, stats.getTotalHarvests());
	}

	/** Part of a stock is still the same harvest, so picking one fruit must not close it. */
	@Test
	public void pickingPartOfARegrowingStockKeepsTheHarvestOpen()
	{
		FarmPatch patch = ripeJangerberryPatch();

		inventory();
		inventory(Produce.JANGERBERRIES.getItemID(), 1);

		ProduceState ripe = stateOf(patch, Produce.JANGERBERRIES, CropState.HARVESTABLE);
		log.onPatchState(patch, ripe, ripe);

		assertEquals("three berries left is the same harvest", 1, log.getOpenHarvests().size());
	}

	/**
	 * A crop picked up far from any patch holding it is not a harvest.
	 *
	 * <p>Without a distance limit the nearest ripe patch was credited however far away it was,
	 * so a bank withdrawal or a trade would be scored as a harvest — and with several ripe
	 * patches of one crop on a run, everything funnelled into whichever was picked first. One
	 * record claimed 110 watermelons against a predicted 11.
	 */
	@Test
	public void doesNotCreditAPickToAPatchOnTheOtherSideOfTheMap() throws Exception
	{
		ripePotatoPatch(CompostTier.NONE);

		HarvestLog fromVarrock = logWithPlayerAt(new net.runelite.api.coords.WorldPoint(3210, 3424, 0));
		fromVarrock.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, emptyContainer()));
		fromVarrock.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV,
			containerOf(Produce.POTATO.getItemID(), 20)));

		assertTrue("Falador's allotment is not where these came from",
			fromVarrock.getOpenHarvests().isEmpty());
	}

	/**
	 * Experience for the pick that empties a patch still reaches the record.
	 *
	 * <p>Item, varbit and experience all land in the same tick, and the varbit arrives first.
	 * The record used to be written the moment the patch emptied, so the last award missed it
	 * entirely — and a flower patch empties on its <i>only</i> pick, so a limpwurt harvest
	 * logged 0 experience against a predicted 120.
	 */
	@Test
	public void experienceArrivingAfterThePatchEmptiesIsStillCounted()
	{
		FarmPatch patch = ripePotatoPatch(CompostTier.NONE);

		inventory();
		farmingXp(1_000_000);
		inventory(Produce.POTATO.getItemID(), 4);

		// The varbit says empty before the experience drop is delivered.
		log.onPatchState(patch, patch.getImplementation().forVarbitValue(10),
			patch.getImplementation().forVarbitValue(3));
		farmingXp(1_000_036);

		log.onGameTick(new net.runelite.api.events.GameTick());

		assertEquals("the last pick's experience belongs to the harvest that earned it",
			36.0, stats.getAll().get(0).getXp(), 0.001);
	}

	/**
	 * Herbs swallowed by an open herb sack are still counted.
	 *
	 * <p>An open herb sack takes a grimy herb the instant it is picked, so it never reaches the
	 * inventory and there is no delta to count. That is why the plugin had watermelon, limpwurt
	 * and snape grass rows and not one herb, despite herb patches being harvested.
	 *
	 * <p>The experience still arrives, at a rate published per crop, so the picks can be
	 * counted from it. Here: five ranarr at 30.5 each and not a single item event.
	 */
	@Test
	public void herbsThatGoStraightIntoTheSackAreCountedFromExperience()
	{
		FarmPatch patch = ripeRanarrPatch();

		inventory();
		farmingXp(1_000_000);
		for (int pick = 1; pick <= 5; pick++)
		{
			farmingXp(1_000_000 + (int) Math.round(30.5 * pick));
		}

		HarvestRecord record = onlyOpenRecord();
		assertEquals("the patch is " + patch.getDisplayName(), 5, record.getItemsHarvested());
		assertTrue("and it should be flagged as inferred rather than seen",
			record.isInferredFromXp());
	}

	/** Experience that matches no ripe crop nearby must not invent a harvest. */
	@Test
	public void unrelatedFarmingExperienceDoesNotOpenAHarvest()
	{
		ripeRanarrPatch();

		inventory();
		farmingXp(1_000_000);
		farmingXp(1_000_007);   // not a multiple of any ripe crop's per-pick rate

		assertTrue("planting and check-health experience is not a pick",
			log.getOpenHarvests().isEmpty());
	}

	/** The prediction travels with the record, so a CSV row explains itself later. */
	@Test
	public void carriesThePredictionItWillBeJudgedAgainst()
	{
		ripePotatoPatch(CompostTier.SUPERCOMPOST);

		inventory();
		inventory(Produce.POTATO.getItemID(), 1);

		HarvestRecord record = onlyOpenRecord();
		assertEquals(85, record.getFarmingLevel());
		assertEquals(FarmingBonuses.NONE, record.getBonuses());
		assertTrue("a supercomposted potato patch should beat its five guaranteed lives",
			record.getPredictedYield() > 5);
	}

	// ------------------------------------------------------------------- helpers

	/** Puts a fully grown, harvestable potato crop in Falador's north allotment. */
	private FarmPatch ripePotatoPatch(CompostTier compost)
	{
		FarmPatch patch = FarmingWorldData.getPatch(FALADOR_NORTH);
		assertNotNull("fixture patch no longer exists", patch);

		ProduceState growing = patch.getImplementation().forVarbitValue(6);
		assertNotNull(growing);
		patches.recordVarbit(patch, 6, growing);
		patches.recordCompost(patch, compost);

		ProduceState ripe = patch.getImplementation().forVarbitValue(10);
		assertNotNull(ripe);
		assertEquals(Produce.POTATO, ripe.getProduce());
		patches.recordVarbit(patch, 10, ripe);
		return patch;
	}

	/**
	 * A ripe jangerberry bush, wherever the game keeps one.
	 *
	 * <p>Found by asking the patch which varbit value means what, rather than by hardcoding
	 * numbers: bush varbit values are not memorable and a wrong one would fail as "no such
	 * state" rather than as the thing being tested.
	 */
	private FarmPatch ripeJangerberryPatch()
	{
		FarmPatch patch = null;
		for (FarmPatch candidate : FarmingWorldData.getPatches(
			com.dooglemaps.data.PatchImplementation.BUSH))
		{
			if (stateOf(candidate, Produce.JANGERBERRIES, CropState.HARVESTABLE) != null)
			{
				patch = candidate;
				break;
			}
		}
		assertNotNull("no bush patch can hold a jangerberry any more", patch);

		ProduceState ripe = stateOf(patch, Produce.JANGERBERRIES, CropState.HARVESTABLE);
		patches.recordVarbit(patch, varbitFor(patch, ripe), ripe);

		// Standing at the bush, since a pick is only credited to a patch you are near.
		try
		{
			log = logWithPlayerAt(somewhereIn(patch.getRegion().getRegionId()));
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
		return patch;
	}

	/** The middle of a map region, from the id's packed x and y. */
	private static net.runelite.api.coords.WorldPoint somewhereIn(int regionId)
	{
		return new net.runelite.api.coords.WorldPoint(
			((regionId >>> 8) << 6) + 32, ((regionId & 0xFF) << 6) + 32, 0);
	}

	/** The first varbit value that decodes to this produce in this state, or null. */
	@javax.annotation.Nullable
	private static ProduceState stateOf(FarmPatch patch, Produce produce, CropState state)
	{
		for (int value = 0; value < 256; value++)
		{
			ProduceState decoded = patch.getImplementation().forVarbitValue(value);
			if (decoded != null && decoded.getProduce() == produce
				&& decoded.getCropState() == state)
			{
				return decoded;
			}
		}
		return null;
	}

	/** Compared by value, because forVarbitValue builds a fresh state on every call. */
	private static int varbitFor(FarmPatch patch, ProduceState wanted)
	{
		for (int value = 0; value < 256; value++)
		{
			if (wanted.equals(patch.getImplementation().forVarbitValue(value)))
			{
				return value;
			}
		}
		throw new IllegalStateException("no varbit value decodes to " + wanted);
	}

	private static ItemContainer emptyContainer()
	{
		return containerOf();
	}

	private static ItemContainer containerOf(int... idThenQuantity)
	{
		ItemContainer container = Mockito.mock(ItemContainer.class);
		Item[] items = new Item[idThenQuantity.length / 2];
		for (int i = 0; i < items.length; i++)
		{
			items[i] = new Item(idThenQuantity[i * 2], idThenQuantity[i * 2 + 1]);
		}
		when(container.getItems()).thenReturn(items);
		return container;
	}

	/**
	 * A ripe ranarr in Falador's herb patch, with the player standing at it.
	 *
	 * <p>Ranarr rather than a generic herb because its 30.5 per pick is fractional, which is
	 * the case rounding has to survive over a long harvest.
	 */
	private FarmPatch ripeRanarrPatch()
	{
		FarmPatch patch = FarmingWorldData.getPatch("12083.4774");
		assertNotNull("fixture herb patch no longer exists", patch);

		ProduceState ripe = stateOf(patch, Produce.RANARR, CropState.HARVESTABLE);
		assertNotNull("no varbit value gives a ripe ranarr", ripe);
		patches.recordVarbit(patch, varbitFor(patch, ripe), ripe);
		return patch;
	}

	private void inventory(int... idThenQuantity)
	{
		log.onItemContainerChanged(
			new ItemContainerChanged(InventoryID.INV, containerOf(idThenQuantity)));
	}

	private void farmingXp(int total)
	{
		StatChanged event = Mockito.mock(StatChanged.class);
		when(event.getSkill()).thenReturn(Skill.FARMING);
		when(event.getXp()).thenReturn(total);
		log.onStatChanged(event);
	}

	private HarvestRecord onlyOpenRecord()
	{
		Map<String, HarvestRecord> open = new HashMap<>(log.getOpenHarvests());
		assertEquals("expected exactly one harvest in flight", 1, open.size());
		return open.values().iterator().next();
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
