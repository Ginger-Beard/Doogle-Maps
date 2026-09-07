package com.dooglemaps.validate;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.route.PatchLocationStore;
import com.dooglemaps.state.FarmingBonusStore;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.SeedInventoryStore;
import com.google.gson.Gson;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * A north and a south allotment are two patches, and two harvests.
 *
 * <h2>The reported dead end</h2>
 *
 * {@code HarvestLog.distanceTo} answered <b>zero for every patch in the player's region</b> and
 * only fell back to coordinates when the regions differed — sound when it was written, because a
 * region is exact and a position might have been a guess. It stopped being sound once every patch
 * shipped with a measured tile.
 *
 * <p>Every allotment location has a north and a south patch in one region. Plant the same crop in
 * both and both scored zero; {@code findPatchHolding} took the first minimum; and all the produce
 * from both patches landed in one record while the second patch never opened one at all. The
 * signature in the log is unmistakable, and it is the whole of the "luck" left over after the
 * compost fix:
 *
 * <pre>
 * Watermelon  Catherby South  ultra  actual 77  predicted 22.93  xp 4495
 * Strawberry  Kourend SW      none   actual 70  predicted 11.46  xp 2132
 * Snape grass Farming Guild N ultra  actual 72  predicted 32.68  xp 7717
 * </pre>
 *
 * Seventy-seven melons out of six harvest lives is a seven-sigma patch. Two patches booked as one
 * is a Tuesday. The experience on those rows matches the item count exactly, so the items were
 * real — only the attribution was wrong, and it read back as the player being extraordinarily
 * lucky at watermelon.
 */
public class TwoAllotmentsInOneRegionGetSeparateRecordsTest
{
	/** Both of Falador's allotments: same region 12083, six tiles apart. */
	private static final String NORTH = "12083.4771";
	private static final String SOUTH = "12083.4772";

	private PatchStateStore patches;
	private HarvestStatsStore stats;
	private PatchLocationStore locations;
	private HarvestLog log;

	/** Where the player is standing, so one log can walk from one patch to the other. */
	private final AtomicReference<WorldPoint> standing = new AtomicReference<>();

	@Before
	public void setUp()
	{
		DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class);
		when(config.logHarvests()).thenReturn(true);

		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(int.class)))
			.thenReturn(85);

		patches = construct(PatchStateStore.class, configManager, new Gson());
		patches.load();

		SeedInventoryStore seeds = construct(SeedInventoryStore.class, Mockito.mock(Client.class),
			configManager, new Gson());
		FarmingBonusStore bonuses = construct(FarmingBonusStore.class, configManager, patches,
			Mockito.mock(net.runelite.client.game.ItemManager.class), Mockito.mock(Client.class));

		stats = construct(HarvestStatsStore.class, configManager, new Gson());
		stats.load();

		locations = construct(PatchLocationStore.class, configManager, new Gson());
		locations.load();

		Player player = Mockito.mock(Player.class);
		when(player.getWorldLocation()).thenAnswer(call -> standing.get());
		Client client = Mockito.mock(Client.class);
		when(client.getLocalPlayer()).thenReturn(player);

		log = construct(HarvestLog.class, config, patches, seeds, bonuses, stats,
			new HarvestHistory(), locations, client, Mockito.mock(ConfigManager.class));
	}

	@Test
	public void pickingBothAllotmentsOpensTwoHarvests()
	{
		FarmPatch north = ripePotatoes(NORTH);
		FarmPatch south = ripePotatoes(SOUTH);

		assertEquals("fixture: the two patches share a region, which is why they used to tie",
			north.getRegion().getRegionId(), south.getRegion().getRegionId());
		assertTrue("fixture: and both have a measured tile to be told apart by",
			locations.isExact(north) && locations.isExact(south));

		standAt(north);
		inventory();
		inventory(Produce.POTATO.getItemID(), 9);

		standAt(south);
		inventory(Produce.POTATO.getItemID(), 20);

		Map<String, HarvestRecord> open = log.getOpenHarvests();
		assertEquals("one record per patch, not one record for the pair", 2, open.size());
		assertEquals("the nine picked at the north patch stayed there",
			9, open.get(NORTH).getItemsHarvested());
		assertEquals("and the eleven picked at the south one went there",
			11, open.get(SOUTH).getItemsHarvested());
	}

	/**
	 * The first patch is still credited while you are standing on it.
	 *
	 * <p>The change is a preference for a measured tile over a region match, not a narrowing of
	 * what counts as being at a patch — so picking one allotment of a pair has to behave exactly
	 * as it always did.
	 */
	@Test
	public void pickingOneOfThePairIsStillOneHarvest()
	{
		ripePotatoes(NORTH);
		ripePotatoes(SOUTH);

		standAt(FarmingWorldData.getPatch(NORTH));
		inventory();
		inventory(Produce.POTATO.getItemID(), 11);

		Map<String, HarvestRecord> open = log.getOpenHarvests();
		assertEquals(1, open.size());
		assertEquals("and it is the one being stood on", NORTH,
			open.keySet().iterator().next());
	}

	// ------------------------------------------------------------------- helpers

	private void standAt(FarmPatch patch)
	{
		standing.set(locations.getLocation(patch));
	}

	private FarmPatch ripePotatoes(String key)
	{
		FarmPatch patch = FarmingWorldData.getPatch(key);
		assertNotNull("fixture patch " + key + " no longer exists", patch);

		ProduceState ripe = patch.getImplementation().forVarbitValue(10);
		assertNotNull(ripe);
		assertEquals(Produce.POTATO, ripe.getProduce());
		assertEquals(CropState.HARVESTABLE, ripe.getCropState());
		patches.recordVarbit(patch, 10, ripe);
		patches.recordCompost(patch, CompostTier.ULTRACOMPOST);
		return patch;
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
}
