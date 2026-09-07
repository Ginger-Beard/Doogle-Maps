package com.dooglemaps.validate;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Produce;
import com.dooglemaps.state.FarmingBonusStore;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.timer.FarmingBonuses;
import com.google.gson.Gson;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.StatChanged;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * A run's experience is the experience the run earned, not the part a patch handed over.
 *
 * <h2>The reported dead end</h2>
 *
 * Every experience figure in the Runs section was <b>five times too small</b>, and every one of
 * them reproduced exactly from the data, so the arithmetic was never in question. The input was.
 * Runs were clustered out of {@link HarvestRecord}s, and a harvest record only exists where a
 * patch put an item in the inventory — <b>check-health on a tree or a fruit tree opens no record
 * at all</b>, and that is where the experience is: a yew check pays 7,069, a mahogany 15,720, a
 * dragonfruit 17,335, against a ranarr pick's 30.5.
 *
 * <p>Measured over the audited month: the account gained about 3.35M Farming experience and the
 * harvest log accounts for 710,425 of it. Twenty per cent. So "11.3k xp a run" was really 53k,
 * "21.2k a day" was 100k, and — worst of all — {@code describeRunsToNextLevel} divided a
 * <i>real</i> experience requirement by that partial rate and told the player <b>34 more runs to
 * 91</b> when the honest answer was about seven. A five-times-wrong estimate is worse than none.
 *
 * <p>Every Farming drop is folded into the run cluster now, whether or not it could be attributed
 * to a patch. The two sources are combined with a max rather than a sum: every drop a harvest
 * record saw is also a drop the skill saw, so adding them would double-count the picks.
 */
public class ARunCountsEveryFarmingXpDropTest
{
	private HarvestHistory history;

	@Before
	public void setUp()
	{
		history = new HarvestHistory();
	}

	/**
	 * A tree run harvests nothing at all, and used to be invisible.
	 *
	 * <p>Checking a yew's health is a farm run by any definition a player would use — it is the
	 * trip, and it is 7,069 experience. Nothing about it opens a harvest record.
	 */
	@Test
	public void aCheckHealthWithNoHarvestIsStillARun()
	{
		history.recordFarmingXp(7069);

		assertEquals("the trip happened, so it is a run", 1, history.getRunCount());
		assertEquals(7069.0, history.getTotalXp(), 1e-9);
	}

	/** The picks are counted once, not once by each source. */
	@Test
	public void aPicksExperienceIsNotCountedTwice()
	{
		// The order the game uses: the drop arrives, then the record it belonged to is written.
		history.recordFarmingXp(305);
		history.record(harvestOf(10, 305));

		assertEquals("one sitting", 1, history.getRunCount());
		assertEquals("305 experience was earned once", 305.0, history.getTotalXp(), 1e-9);
	}

	/**
	 * A mixed run reports the whole of what it earned.
	 *
	 * <p>The shape of an ordinary herb run with trees on the way: a handful of patches picked,
	 * and several thousand experience from checks that no record can ever see.
	 */
	@Test
	public void aHerbRunWithTreesOnTheWayCountsBoth()
	{
		history.recordFarmingXp(305);
		history.record(harvestOf(10, 305));
		history.recordFarmingXp(7069);

		assertEquals(1, history.getRunCount());
		assertEquals("the picks and the check, not just the picks",
			7374.0, history.getTotalXp(), 1e-9);
		assertEquals(7374.0, history.getXpPerRun(), 1e-9);
	}

	/**
	 * The drops reach the history through the log, which is the only thing watching them.
	 *
	 * <p>Wired here rather than assumed: {@code HarvestLog.onStatChanged} is already the one
	 * subscriber to Farming {@code StatChanged}, and it forwards the gain before it tries to
	 * attribute it to anything — so a drop that belongs to no patch still reaches the run.
	 */
	@Test
	public void theHarvestLogForwardsEveryFarmingDrop()
	{
		HarvestLog log = logWiredTo(history);

		farmingXp(log, 1_000_000);              // the baseline reading is not a gain
		farmingXp(log, 1_007_069);              // a yew check, beside no patch at all

		assertEquals(1, history.getRunCount());
		assertEquals(7069.0, history.getTotalXp(), 1e-9);
	}

	// ------------------------------------------------------------------- helpers

	private static HarvestRecord harvestOf(int items, double xp)
	{
		FarmPatch patch = FarmingWorldData.getPatches(PatchImplementation.HERB).iterator().next();
		HarvestRecord record = new HarvestRecord(patch, Produce.RANARR, CompostTier.ULTRACOMPOST,
			85, FarmingBonuses.NONE, 0);
		record.addItems(items);
		record.addXp(xp);
		record.markCompleted();
		return record;
	}

	private static void farmingXp(HarvestLog log, int total)
	{
		StatChanged event = Mockito.mock(StatChanged.class);
		when(event.getSkill()).thenReturn(Skill.FARMING);
		when(event.getXp()).thenReturn(total);
		log.onStatChanged(event);
	}

	private static HarvestLog logWiredTo(HarvestHistory history)
	{
		DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class);
		when(config.logHarvests()).thenReturn(false);

		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(int.class)))
			.thenReturn(85);

		PatchStateStore patches = construct(PatchStateStore.class, configManager, new Gson());
		patches.load();

		SeedInventoryStore seeds = construct(SeedInventoryStore.class, Mockito.mock(Client.class),
			configManager, new Gson());
		FarmingBonusStore bonuses = construct(FarmingBonusStore.class, configManager, patches,
			Mockito.mock(net.runelite.client.game.ItemManager.class), Mockito.mock(Client.class));
		HarvestStatsStore stats = construct(HarvestStatsStore.class, configManager, new Gson());
		stats.load();
		com.dooglemaps.route.PatchLocationStore locations = construct(
			com.dooglemaps.route.PatchLocationStore.class, configManager, new Gson());
		locations.load();

		Player player = Mockito.mock(Player.class);
		when(player.getWorldLocation()).thenReturn(new WorldPoint(3210, 3424, 0));
		Client client = Mockito.mock(Client.class);
		when(client.getLocalPlayer()).thenReturn(player);

		return construct(HarvestLog.class, config, patches, seeds, bonuses, stats, history,
			locations, client, Mockito.mock(ConfigManager.class));
	}
}
