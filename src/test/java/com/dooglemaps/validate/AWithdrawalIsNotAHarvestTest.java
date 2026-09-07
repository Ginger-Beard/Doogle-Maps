package com.dooglemaps.validate;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.state.FarmingBonusStore;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.SeedInventoryStore;
import com.google.gson.Gson;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Items with no Farming experience behind them did not come out of the ground.
 *
 * <h2>The reported dead end</h2>
 *
 * The Stats tab's one actionable line told the player that <b>1,965 items were left standing on
 * patches</b> and they should go back for them. 1,412 of those items were three limpwurt records
 * at the Farming Guild — 908, 266 and 238 roots, each against a predicted 6.91, each with
 * {@code actual_xp = 0.0}. There is no way to pick 908 roots and earn nothing; a flower patch pays
 * 120 for being cleared and pays it every time.
 *
 * <p>What actually happened is that a record stays open for {@link
 * HarvestLog#IDLE_TICKS_BEFORE_ABANDON} — a hundred ticks, a full minute — while its patch stands
 * ripe, and every matching item that lands in the inventory in that minute is credited to it. A
 * withdrawal of limpwurt roots from the bank at the Farming Guild is exactly that. Another ~170
 * items were the same shape on snape grass.
 *
 * <p>So the experience is the test of whether a pick happened, and it is a good one: it is paid
 * per item for every crop that has a harvest award, in the same tick as the item, and the plugin
 * already has the published figure for it.
 */
public class AWithdrawalIsNotAHarvestTest
{
	/** Falador's flower patch, which the fixture stands beside. */
	private static final String FALADOR_FLOWER = "12083.4773";

	private PatchStateStore patches;
	private HarvestStatsStore stats;
	private HarvestLog log;

	private DoogleMapsConfig config;
	private SeedInventoryStore seeds;
	private FarmingBonusStore bonuses;
	private com.dooglemaps.route.PatchLocationStore locations;

	@Before
	public void setUp()
	{
		config = Mockito.mock(DoogleMapsConfig.class);
		when(config.logHarvests()).thenReturn(true);

		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(int.class)))
			.thenReturn(85);

		patches = construct(PatchStateStore.class, configManager, new Gson());
		patches.load();

		seeds = construct(SeedInventoryStore.class, Mockito.mock(Client.class), configManager,
			new Gson());
		bonuses = construct(FarmingBonusStore.class, configManager, patches,
			Mockito.mock(net.runelite.client.game.ItemManager.class), Mockito.mock(Client.class));

		stats = construct(HarvestStatsStore.class, configManager, new Gson());
		stats.load();

		locations = construct(com.dooglemaps.route.PatchLocationStore.class, configManager,
			new Gson());
		locations.load();

		log = logWithPlayerAt(new WorldPoint(3054, 3307, 0));
	}

	@Test
	public void hundredsOfRootsWithNoExperienceAreNotRecorded()
	{
		ripeLimpwurtPatch();

		inventory();
		inventory(Produce.LIMPWURT.getItemID(), 238);
		assertEquals("fixture: the items were credited to the ripe patch beside the bank",
			1, log.getOpenHarvests().size());

		abandon();

		assertEquals("238 roots for no experience is a bank withdrawal, not a harvest",
			0, stats.getTotalHarvests());
		assertEquals(0, stats.getTotalItems());
	}

	/**
	 * The control, and the reason the rule is on experience rather than on the count.
	 *
	 * <p>A patch genuinely left standing is real data — it is what the "partial" totals exist for
	 * — so the rule has to reject the withdrawal without rejecting the abandoned harvest beside
	 * it. Experience separates them cleanly where a plausibility bound on the item count would
	 * not: an ultracomposted allotment really does give twenty-odd.
	 */
	@Test
	public void thoseSameRootsWithTheirExperienceAreRecordedAsUsual()
	{
		ripeLimpwurtPatch();

		inventory();
		farmingXp(1_000_000);
		// 120 to pick the patch plus the 21.5 it defers from planting, as the game pays it.
		farmingXp(1_000_142);
		inventory(Produce.LIMPWURT.getItemID(), 7);

		abandon();

		assertEquals("this one was picked, and the experience says so", 7, stats.getTotalItems());
	}

	/**
	 * A farmed tree is chopped for logs, and chopping is Woodcutting.
	 *
	 * <p>Teak, maple, yew, magic and camphor rows in the store carry 1 to 38 items and zero
	 * farming experience: they are logs from felling the tree the patch grew, scored against a
	 * per-patch yield of 1 and contributing +144 to the account's "items over expectation".
	 * {@link com.dooglemaps.timer.CropYieldModel#hasMeaningfulYield} has always known a tree has
	 * no per-patch yield worth quoting — nothing asked it before writing.
	 */
	@Test
	public void logsChoppedFromAFarmedTreeAreNotAHarvest()
	{
		FarmPatch patch = ripeTreePatch();
		assertNotNull("fixture: a tree patch that can hold a maple", patch);

		inventory();
		inventory(Produce.MAPLE.getItemID(), 38);
		assertEquals("fixture: the logs were credited to the tree that grew them",
			1, log.getOpenHarvests().size());

		abandon();

		assertEquals("logs are cut, not picked, and the patch's yield was never 38",
			0, stats.getTotalItems());
	}

	// ------------------------------------------------------------------- helpers

	/** Runs the idle timer out, which abandons every open record and writes it. */
	private void abandon()
	{
		for (int tick = 0; tick <= 101; tick++)
		{
			log.onGameTick(new GameTick());
		}
		assertEquals("fixture: nothing is still in flight", 0, log.getOpenHarvests().size());
	}

	private FarmPatch ripeLimpwurtPatch()
	{
		FarmPatch patch = FarmingWorldData.getPatch(FALADOR_FLOWER);
		assertNotNull("fixture flower patch no longer exists", patch);

		ProduceState ripe = stateOf(patch, Produce.LIMPWURT, CropState.HARVESTABLE);
		assertNotNull("no varbit value gives a ripe limpwurt", ripe);
		patches.recordVarbit(patch, varbitFor(patch, ripe), ripe);
		return patch;
	}

	/** A tree patch holding a grown maple, with the player standing at it. */
	private FarmPatch ripeTreePatch()
	{
		for (FarmPatch candidate : FarmingWorldData.getPatches(PatchImplementation.TREE))
		{
			ProduceState ripe = stateOf(candidate, Produce.MAPLE, CropState.HARVESTABLE);
			if (ripe == null)
			{
				continue;
			}
			patches.recordVarbit(candidate, varbitFor(candidate, ripe), ripe);
			log = logWithPlayerAt(locations.getLocation(candidate));
			return candidate;
		}
		return null;
	}

	private HarvestLog logWithPlayerAt(WorldPoint where)
	{
		return construct(HarvestLog.class, config, patches, seeds, bonuses, stats,
			new HarvestHistory(), locations, clientAt(where),
			Mockito.mock(ConfigManager.class));
	}

	private static Client clientAt(WorldPoint where)
	{
		Player player = Mockito.mock(Player.class);
		when(player.getWorldLocation()).thenReturn(where);

		Client client = Mockito.mock(Client.class);
		when(client.getLocalPlayer()).thenReturn(player);
		return client;
	}

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

	private void inventory(int... idThenQuantity)
	{
		ItemContainer container = Mockito.mock(ItemContainer.class);
		Item[] items = new Item[idThenQuantity.length / 2];
		for (int i = 0; i < items.length; i++)
		{
			items[i] = new Item(idThenQuantity[i * 2], idThenQuantity[i * 2 + 1]);
		}
		when(container.getItems()).thenReturn(items);
		log.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, container));
	}

	private void farmingXp(int total)
	{
		StatChanged event = Mockito.mock(StatChanged.class);
		when(event.getSkill()).thenReturn(Skill.FARMING);
		when(event.getXp()).thenReturn(total);
		log.onStatChanged(event);
	}
}
