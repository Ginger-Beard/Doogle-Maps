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

import static com.dooglemaps.Construct.construct;
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
	 *
	 * <p>The experience is emitted here where it used to be left out. That was harmless when
	 * anything with items was recorded, and is not any more: a record holding items and <b>no
	 * farming experience</b> is refused, because picking always pays and the three biggest
	 * "left standing" rows in the real history were bank withdrawals credited to a ripe patch.
	 * See {@code AWithdrawalIsNotAHarvestTest}. A fixture for a genuine harvest has to pay for it.
	 */
	@Test
	public void statisticsAreRecordedWithVerboseLoggingOff()
	{
		when(config.logHarvests()).thenReturn(false);

		FarmPatch patch = ripePotatoPatch(CompostTier.NONE);
		inventory();
		farmingXp(1_000_000);
		inventory(Produce.POTATO.getItemID(), 4);
		farmingXp(1_000_036);   // four potatoes at 9 each

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
		farmingXp(1_000_000);
		inventory(Produce.JANGERBERRIES.getItemID(), 4);
		// Picking pays, and a record with items and no experience is now refused as a bank
		// withdrawal rather than recorded - see AWithdrawalIsNotAHarvestTest.
		farmingXp(1_000_128);
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

	/**
	 * A fruit tree and a cactus never change state when emptied, and must still close.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * {@code onPatchState} closed a record when the produce or the crop state changed. That is
	 * enough for a bush — picking the last berry moves it HARVESTABLE to GROWING — and it is not
	 * enough for the other two regrowing families. {@code PatchRules} maps a picked-clean palm to
	 * {@code (PALM, HARVESTABLE, stage 0)} at varbit 206 and a bare cactus to
	 * {@code (CACTUS, HARVESTABLE, stage 0)} at 15: same produce, same state, nothing on the
	 * plant.
	 *
	 * <p>So those records never closed on a state change at all. They survived to the 100-tick
	 * idle timeout and absorbed whatever experience arrived in the intervening minute. Found in
	 * the harvest log, not by reading: <b>25 of 25 cactus rows and 22 of 22 palm rows</b> written
	 * {@code completed=false}, with totals of 12443, 3712, 1092 and 1011 against ~250
	 * predictions. One palm record swallowed a calquat check-health 76 seconds after the last
	 * coconut.
	 *
	 * <p>The class javadoc named all three families and only the bush was ever tested.
	 */
	@Test
	public void aFruitTreePickedCleanFinishesEvenThoughItStaysHarvestable()
	{
		FarmPatch patch = ripePalmPatch();

		inventory();
		farmingXp(1_000_000);
		inventory(Produce.PALM.getItemID(), 6);
		// As above: a harvest that pays nothing is refused, so the fixture pays for its coconuts.
		farmingXp(1_000_666);
		assertEquals("fixture: the harvest opened", 1, log.getOpenHarvests().size());

		ProduceState oneLeft = stockOf(patch, Produce.PALM, CropState.HARVESTABLE, 1);
		assertNotNull("fixture: a palm at one coconut", oneLeft);
		log.onPatchState(patch, oneLeft, oneLeft);
		assertEquals("one coconut left is still the same harvest",
			1, log.getOpenHarvests().size());

		// Picked clean. Still PALM, still HARVESTABLE - only the stock says it is over.
		ProduceState bare = stockOf(patch, Produce.PALM, CropState.HARVESTABLE, 0);
		assertNotNull("fixture: a picked-clean palm still decodes harvestable", bare);
		assertEquals("fixture: and it is the same produce and state", CropState.HARVESTABLE,
			bare.getCropState());
		log.onPatchState(patch, oneLeft, bare);

		assertTrue("nothing left to pick is the end of the harvest, whatever the state says",
			log.getOpenHarvests().isEmpty());

		log.onGameTick(new net.runelite.api.events.GameTick());
		assertEquals("and it counts as a finished patch, not one the timer gave up on",
			1, stats.getTotalHarvests());
	}

	/** Part of a stock is still the same harvest, so picking one fruit must not close it. */
	@Test
	public void pickingPartOfARegrowingStockKeepsTheHarvestOpen()
	{
		FarmPatch patch = ripeJangerberryPatch();

		inventory();
		inventory(Produce.JANGERBERRIES.getItemID(), 1);

		// Three of four still on the bush. stateOf would hand back stage 0 - an empty plant -
		// which is the state that must CLOSE the record, so the fixture has to name the stock.
		ProduceState threeLeft = stockOf(patch, Produce.JANGERBERRIES, CropState.HARVESTABLE, 3);
		assertNotNull("fixture: a bush at three berries", threeLeft);
		log.onPatchState(patch, threeLeft, threeLeft);

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

	/**
	 * A flower pays for the whole patch in one drop, and that drop has to open the record.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * A limpwurt patch pays 142 once — its 120 to harvest plus the 21.5 it defers from planting —
	 * where an ordinary crop pays a pick at a time. {@code openFromExperience} only ever asked
	 * "is that one pick?", which for limpwurt means 120.5 give or take 6, so the single drop a
	 * limpwurt harvest makes matched nothing and no record opened for it.
	 *
	 * <p>The roots do reach the inventory, so a record opened a moment later off the item delta —
	 * but by then the award had been dropped on the floor. Eighty of the eighty-one limpwurt
	 * patches picked clean in the harvest CSV record actual_xp=0.0 against a predicted 120.5, and
	 * none of the forty "opening from experience alone" lines in the 2026-08-13 client logs is a
	 * limpwurt.
	 *
	 * <p>The order here is the order the game uses: the experience arrives first and the roots
	 * follow in the same tick, which is why the award has to be able to open a record rather than
	 * merely join one.
	 */
	@Test
	public void aFlowerPatchWholePatchAwardOpensTheHarvestItBelongsTo()
	{
		ripeLimpwurtPatch();

		inventory();
		farmingXp(1_000_000);
		// 120 to harvest plus 21.5 deferred from planting, as the game pays it: one drop.
		farmingXp(1_000_142);

		HarvestRecord record = onlyOpenRecord();
		assertEquals(Produce.LIMPWURT, record.getProduce());
		assertEquals("the whole-patch award belongs to the patch that paid it",
			142.0, record.getXpGained(), 0.001);

		// The roots land after the award, and must join that record rather than start a second.
		inventory(Produce.LIMPWURT.getItemID(), 7);
		assertEquals("the same harvest, not a second one", 1, log.getOpenHarvests().size());
		assertEquals(7, onlyOpenRecord().getItemsHarvested());
		assertEquals(142.0, onlyOpenRecord().getXpGained(), 0.001);
	}

	/**
	 * Widening the match must not widen it to the planting award on its own.
	 *
	 * <p>The whole-patch figure is the harvest award <i>plus</i> what planting deferred into it,
	 * and only that sum. Accepting either half alone would let a limpwurt's 21.5 - which is what
	 * arrives when someone plants a seed next door - invent a harvest of a patch nobody touched.
	 */
	@Test
	public void aFlowerPatchDoesNotOpenOnHalfItsAward()
	{
		ripeLimpwurtPatch();

		inventory();
		farmingXp(1_000_000);
		farmingXp(1_000_022);   // limpwurt's planting figure, not its harvest

		assertTrue("21.5 is not what picking a flower patch pays",
			log.getOpenHarvests().isEmpty());
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

	/**
	 * Clearing a patch pays one award more than its picks, and the prediction has to expect it.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * An allotment, hops, herb or flower patch pays nothing when the seed goes in. It pays the
	 * planting figure at the moment it is picked clean, bundled into the drop for the last pick:
	 * watermelon pays 55, 54, 55 ... and then 103 on the tick chat says "The allotment is now
	 * empty", which is its 54.5 to pick and its 48.5 to plant.
	 *
	 * <p>The prediction knew only about the picks, so nearly every replanted patch reported a
	 * mismatch of exactly one planting award. The residuals in the CSV cluster on the planting
	 * figure itself and nowhere else: the watermelon rows that overshot all land between 47.6
	 * and 49.3 against a constant of 48.5, snape grass 80.3 to 82.4 against 82, cotton 71.1 to
	 * 72.4 against 72. No count is quoted because the file keeps growing — rows logged since
	 * this fix sit at zero, and the band is the claim, not its population.
	 *
	 * <p>Both halves are pinned here, because "picked clean" is the whole of the condition: the
	 * same four potatoes left standing must predict the picks alone. That is the control group
	 * from the CSV as well, where the incomplete watermelon rows sit within a point of theirs.
	 */
	@Test
	public void predictsTheAwardAPatchPaysWhenItIsPickedClean()
	{
		FarmPatch patch = ripePotatoPatch(CompostTier.NONE);

		inventory();
		inventory(Produce.POTATO.getItemID(), 4);

		HarvestRecord record = onlyOpenRecord();
		assertEquals("still standing, so only the four picks are paid for",
			36.0, record.getPredictedXp(), 0.001);

		ProduceState ripe = patch.getImplementation().forVarbitValue(10);
		ProduceState weeds = patch.getImplementation().forVarbitValue(3);
		assertNotNull(weeds);
		log.onPatchState(patch, ripe, weeds);

		assertEquals("picked clean, and clearing the patch pays a potato's 8 to plant on top",
			44.0, record.getPredictedXp(), 0.001);
	}

	/**
	 * The bundled drop that empties a herb patch is still a pick, and has to be counted as one.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * A grimy herb goes into the sack rather than the inventory, so experience is the only thing
	 * counting picks — and the last drop of a herb patch is a pick <i>plus</i> the award for
	 * clearing it, in one lump. A ranarr's is 57.5 where a pick is 30.5, which the one-pick test
	 * rejects, so <b>every herb harvest came out a herb short</b>.
	 *
	 * <p>Visible in the CSV as an experience residual rather than as a yield one, because the
	 * missing pick took its experience with it: all thirty-five ranarr rows sit 56.2 to 57.6 over
	 * their prediction, which is 27 + 30.5. Watermelon never showed it, because its melons are in
	 * the inventory and {@code getItemsHarvested} takes the larger of the two counts.
	 */
	@Test
	public void theDropThatEmptiesAHerbPatchIsStillAPick()
	{
		ripeRanarrPatch();

		inventory();
		farmingXp(1_000_000);
		farmingXp(1_000_031);   // 30.5, one pick
		farmingXp(1_000_061);   // 30.5, two
		farmingXp(1_000_118);   // 30.5 + 27, the pick that empties the patch

		HarvestRecord record = onlyOpenRecord();
		assertEquals("three herbs went into the sack, not two", 3, record.getItemsHarvested());
		assertTrue("and none of them were seen arriving", record.isInferredFromXp());
	}

	/**
	 * A patch empties once, so the bundled drop can only be counted once.
	 *
	 * <p>Snape grass is where this bites: it pays 82 to pick and 82 to plant, so the drop that
	 * empties the patch is 164 — and so is any two picks that happen to land on one tick. There
	 * is no telling them apart by size, so the tie is broken on the thing that is certain, which
	 * is that a patch cannot be emptied twice.
	 */
	@Test
	public void thePatchClearingAwardIsOnlyEverPaidOnce()
	{
		ripeSnapeGrassPatch();

		inventory();
		farmingXp(1_000_000);
		farmingXp(1_000_082);   // one pick
		farmingXp(1_000_246);   // 82 + 82, the pick that empties the patch
		farmingXp(1_000_410);   // and again, which cannot be a second emptying

		assertEquals("two picks and one emptying, not three picks",
			2, onlyOpenRecord().getItemsHarvested());
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
	 * A palm laden with six coconuts, standing at it.
	 *
	 * <p>Seeded at full stock rather than at the first harvestable varbit, because for a fruit
	 * tree that first value is stage 0 — a plant with nothing on it. See {@link #stockOf}.
	 */
	private FarmPatch ripePalmPatch()
	{
		FarmPatch patch = null;
		for (FarmPatch candidate : FarmingWorldData.getPatches(
			com.dooglemaps.data.PatchImplementation.FRUIT_TREE))
		{
			if (stockOf(candidate, Produce.PALM, CropState.HARVESTABLE, 6) != null)
			{
				patch = candidate;
				break;
			}
		}
		assertNotNull("no fruit tree patch can hold a palm any more", patch);

		ProduceState laden = stockOf(patch, Produce.PALM, CropState.HARVESTABLE, 6);
		patches.recordVarbit(patch, varbitFor(patch, laden), laden);

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

	/**
	 * The same, at a named stock level — which for a regrowing crop is what is left on the plant.
	 *
	 * <h2>Why {@link #stateOf} is not enough for a regrowing crop</h2>
	 *
	 * It returns the <b>first</b> matching varbit, and for a bush or a fruit tree the lowest
	 * harvestable value is stage 0 — a plant that is still "harvestable" with nothing on it. A
	 * fixture built that way says "three berries left" and means "none left", which is how
	 * {@code pickingPartOfARegrowingStockKeepsTheHarvestOpen} passed while asserting the
	 * opposite of its own name.
	 */
	@javax.annotation.Nullable
	private static ProduceState stockOf(FarmPatch patch, Produce produce, CropState state,
		int stage)
	{
		for (int value = 0; value < 256; value++)
		{
			ProduceState decoded = patch.getImplementation().forVarbitValue(value);
			if (decoded != null && decoded.getProduce() == produce
				&& decoded.getCropState() == state && decoded.getStage() == stage)
			{
				return decoded;
			}
		}
		return null;
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

	/**
	 * Ripe snape grass in Falador's north allotment.
	 *
	 * <p>Snape grass because its two awards are equal — 82 to pick and 82 to plant — so the drop
	 * that empties the patch is the same size as two picks. That collision is the reason the
	 * clearing award is counted at most once.
	 */
	private FarmPatch ripeSnapeGrassPatch()
	{
		FarmPatch patch = FarmingWorldData.getPatch(FALADOR_NORTH);
		assertNotNull("fixture patch no longer exists", patch);

		ProduceState ripe = stateOf(patch, Produce.SNAPE_GRASS, CropState.HARVESTABLE);
		assertNotNull("no varbit value gives ripe snape grass", ripe);
		patches.recordVarbit(patch, varbitFor(patch, ripe), ripe);
		return patch;
	}

	/**
	 * A ripe limpwurt in Falador's flower patch, which the fixture is already standing beside.
	 *
	 * <p>A flower rather than a herb because it is the one family that pays for the whole patch
	 * in a single drop instead of a drop per pick.
	 */
	private FarmPatch ripeLimpwurtPatch()
	{
		FarmPatch patch = FarmingWorldData.getPatch("12083.4773");
		assertNotNull("fixture flower patch no longer exists", patch);

		ProduceState ripe = stateOf(patch, Produce.LIMPWURT, CropState.HARVESTABLE);
		assertNotNull("no varbit value gives a ripe limpwurt", ripe);
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
}
