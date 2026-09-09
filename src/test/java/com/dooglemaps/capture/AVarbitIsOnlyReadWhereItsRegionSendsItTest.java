package com.dooglemaps.capture;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmRegion;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.guide.CarriedItems;
import com.dooglemaps.route.RunPlanner;
import com.dooglemaps.state.BarbarianFarming;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.ProtectedPatches;
import com.dooglemaps.timer.GrowthTimer;
import com.dooglemaps.validate.DiseaseStatsStore;
import com.dooglemaps.validate.HarvestLog;
import com.google.gson.Gson;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.HashTable;
import net.runelite.api.Player;
import net.runelite.api.WidgetNode;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * A patch's varbit is recorded from the tiles its region is transmitted on, and nowhere else.
 *
 * <p>The transmitted varbits are the same handful of numbers everywhere in the game, and the
 * server fills them in for the zone the player is standing in. A region registered under a
 * neighbouring map square — Taverley under 11829, the strip north of Falador's wall — is
 * registered there to be named and routed to, not to be read: the number sitting in
 * {@code FARMING_TRANSMIT_A} out there is Falador's tree. See
 * {@code PatchInteractionTracker.transmittingAt} for the run that cost.
 *
 * <p>The other three cases are the guard's other half. Bounds and the vouched places were the
 * reason not to write this as "the player must be standing in the patch's own map square", and
 * each of them would fail that way: Catherby's allotments are read from 11061, Fossil Island's
 * hardwoods from the squares of plane 0 around them, and the Farming Guild's north wing stands
 * in 4923 rather than the 4922 it is filed under.
 */
public class AVarbitIsOnlyReadWhereItsRegionSendsItTest
{
	/** The Taverley tree patch, whose region is registered under 11829 as well as its own. */
	private static final String TAVERLEY_TREE = "11573.4771";

	/**
	 * The tile the run was on at 23:03:06 — region 11829, between Falador's north wall and
	 * Taverley, with the Taverley patch fifty tiles away.
	 */
	private static final WorldPoint BETWEEN_FALADOR_AND_TAVERLEY = new WorldPoint(2966, 3393, 0);

	/** The Taverley tree itself, in region 11573. */
	private static final WorldPoint AT_THE_TAVERLEY_TREE = new WorldPoint(2936, 3438, 0);

	/** Falador's crop that day: a maple two stages in, and the value Taverley was handed. */
	private static final int MAPLE_STAGE_TWO = 26;

	/** Taverley's own crop that day, which only arrived once the player was in 11573. */
	private static final int MAPLE_GROWN = 32;

	private Map<String, String> stored;
	private ConfigManager configManager;
	private Gson gson;

	/** Varbit id to the value the client will report for it; anything unlisted reads 0. */
	private Map<Integer, Integer> varbits;

	@Before
	public void setUp()
	{
		stored = new HashMap<>();
		varbits = new HashMap<>();
		gson = new Gson();

		configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileKey()).thenReturn("profile-1");
		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer(i -> stored.get(key(i)));
		doAnswer(i ->
		{
			Object value = i.getArgument(2);
			stored.put(key(i), String.valueOf(value));
			return null;
		}).when(configManager).setRSProfileConfiguration(anyString(), anyString(), any());
	}

	/**
	 * The reported bug: the tree read from the next region over is not recorded at all.
	 *
	 * <p>The fixture assertion matters as much as the assertion under test — Taverley really is
	 * registered under 11829, so this is the guard refusing a read and not the data quietly
	 * having stopped offering one.
	 */
	@Test
	public void aTreeReadFromTheNextRegionOverIsNotRecorded()
	{
		PatchStateStore store = newStore();
		store.load();

		FarmPatch taverley = patch(TAVERLEY_TREE);
		assertTrue("fixture: Taverley is still registered under 11829",
			regionsAt(BETWEEN_FALADOR_AND_TAVERLEY).contains(taverley.getRegion()));
		assertDecodes(taverley, MAPLE_STAGE_TWO);

		// What the client was holding out there: Falador's tree, on the varbit Taverley shares.
		varbits.put(taverley.getVarbit(), MAPLE_STAGE_TWO);
		tracker(store, BETWEEN_FALADOR_AND_TAVERLEY).onGameTick(new GameTick());

		assertNull("a patch fifty tiles away, in another region, was never described",
			store.get(taverley));
	}

	/**
	 * And the same read taken inside 11573 is recorded, so the guard cannot be over-broad.
	 *
	 * <p>Same patch, same varbit, same tick — only the tile is different.
	 */
	@Test
	public void theSameTreeReadInsideItsOwnRegionIsRecorded()
	{
		PatchStateStore store = newStore();
		store.load();

		FarmPatch taverley = patch(TAVERLEY_TREE);
		assertDecodes(taverley, MAPLE_GROWN);

		varbits.put(taverley.getVarbit(), MAPLE_GROWN);
		tracker(store, AT_THE_TAVERLEY_TREE).onGameTick(new GameTick());

		assertNotNull("standing at the patch is exactly when the varbit is ours",
			store.get(taverley));
		assertEquals("and it is the crop the server was sending there",
			decode(taverley, MAPLE_GROWN).getProduce(), store.get(taverley).getProduce());
	}

	/**
	 * Catherby's allotments are still read from 11061, which its bounds vouches for.
	 *
	 * <p>The plot sits in 11062; the tile here is one map square west. The hand-ported predicate
	 * says the varbits are live there, and a predicate is a statement about tiles rather than
	 * about map squares, so it outranks the "own region" rule.
	 */
	@Test
	public void catherbysAllotmentsAreStillReadFromTheSquareTheirBoundsVouchesFor()
	{
		assertReadableFrom(new WorldPoint(2810, 3450, 0), 11062);
	}

	/**
	 * Fossil Island's hardwoods are still read from the squares around them.
	 *
	 * <p>Its bounds is the whole of plane 0 minus two ladders and a staircase, spread over the
	 * eight extra squares the region is registered under. Reading them only from 14651 would be
	 * a second bug wearing the first one's clothes.
	 */
	@Test
	public void fossilIslandStillReadsItsHardwoodsFromThePlaneItsBoundsVouchesFor()
	{
		assertReadableFrom(new WorldPoint(3700, 3850, 0), 14651);
	}

	/**
	 * The Farming Guild's north wing is still read while standing in it.
	 *
	 * <p>The guild is bigger than a map square: the redwood, celastrus, anima and fruit tree are
	 * north of y=3776, in 4923, while the region they belong to is filed as 4922. An "own square"
	 * rule with no vouching would have stopped reading them from the tile they stand on.
	 */
	@Test
	public void theFarmingGuildsNorthWingIsStillReadWhileStandingInIt()
	{
		assertReadableFrom(new WorldPoint(1252, 3790, 0), 4922);
	}

	/**
	 * And the hespori is still read from the guild floor, which is what 4922 was registered for.
	 *
	 * <p>Its cave transmits as 5021 and the guild above sends the same varbit, which is the one
	 * farming spot no route walks past — see the note on the registration in FarmingWorldData.
	 */
	@Test
	public void theHesporiIsStillReadFromTheGuildFloorAbove()
	{
		assertReadableFrom(new WorldPoint(1248, 3750, 0), 5021);
	}

	// ------------------------------------------------------------------- helpers

	/**
	 * Asserts every patch of {@code regionId} is recorded from a tile in another map square.
	 *
	 * <p>The tile is checked to be in another square first, because a fixture that quietly
	 * drifted back inside the region would pass this while testing nothing.
	 */
	private void assertReadableFrom(WorldPoint where, int regionId)
	{
		PatchStateStore store = newStore();
		store.load();

		FarmRegion region = regionAt(where, regionId);
		assertTrue("fixture: " + where.getRegionID() + " is " + region.getName()
			+ "'s own map square, so this proves nothing", where.getRegionID() != regionId);

		for (FarmPatch patch : region.getPatches())
		{
			varbits.put(patch.getVarbit(), firstDecodableValue(patch));
		}

		tracker(store, where).onGameTick(new GameTick());

		for (FarmPatch patch : region.getPatches())
		{
			assertNotNull(region.getName() + "'s " + patch.getKey()
				+ " is vouched for here and was not recorded", store.get(patch));
		}
	}

	private static FarmRegion regionAt(WorldPoint where, int regionId)
	{
		for (FarmRegion region : regionsAt(where))
		{
			if (region.getRegionId() == regionId)
			{
				return region;
			}
		}
		throw new AssertionError("region " + regionId + " is no longer registered at " + where);
	}

	private static java.util.Collection<FarmRegion> regionsAt(WorldPoint where)
	{
		return FarmingWorldData.getRegionsForLocation(where);
	}

	private static FarmPatch patch(String key)
	{
		FarmPatch patch = FarmingWorldData.getPatch(key);
		assertNotNull("fixture patch " + key + " no longer exists", patch);
		return patch;
	}

	private static ProduceState decode(FarmPatch patch, int varbitValue)
	{
		return patch.getImplementation().forVarbitValue(varbitValue);
	}

	private static void assertDecodes(FarmPatch patch, int varbitValue)
	{
		assertNotNull("fixture: " + varbitValue + " no longer decodes for " + patch.getKey(),
			decode(patch, varbitValue));
	}

	private static int firstDecodableValue(FarmPatch patch)
	{
		for (int value = 0; value < 256; value++)
		{
			if (patch.getImplementation().forVarbitValue(value) != null)
			{
				return value;
			}
		}
		throw new AssertionError("no varbit value decodes for " + patch);
	}

	private PatchStateStore newStore()
	{
		return construct(PatchStateStore.class, configManager, gson);
	}

	/** The tracker wired to a real store and stand-ins for everything it merely reports to. */
	private PatchInteractionTracker tracker(PatchStateStore store, WorldPoint where)
	{
		return construct(PatchInteractionTracker.class,
			client(where),
			store,
			Mockito.mock(GrowthTimer.class),
			Mockito.mock(RunPlanner.class),
			Mockito.mock(HarvestLog.class),
			Mockito.mock(BarbarianFarming.class),
			Mockito.mock(CarriedItems.class),
			Mockito.mock(DiseaseStatsStore.class),
			Mockito.mock(ProtectedPatches.class),
			Mockito.mock(com.dooglemaps.state.TimeTrackingState.class));
	}

	/** A logged-in client standing on the given tile with no interface open. */
	@SuppressWarnings("unchecked")
	private Client client(WorldPoint where)
	{
		Client client = Mockito.mock(Client.class);
		Player player = Mockito.mock(Player.class);
		when(player.getWorldLocation()).thenReturn(where);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getLocalPlayer()).thenReturn(player);

		HashTable<WidgetNode> components = Mockito.mock(HashTable.class);
		when(components.iterator()).thenAnswer(i -> Collections.<WidgetNode>emptyIterator());
		when(client.getComponentTable()).thenReturn(components);

		when(client.getVarbitValue(anyInt()))
			.thenAnswer(i -> varbits.getOrDefault(i.<Integer>getArgument(0), 0));
		return client;
	}

	/** Config is addressed by group and key; join them the way RuneLite does. */
	private static String key(InvocationOnMock invocation)
	{
		return invocation.getArgument(0) + "." + invocation.getArgument(1);
	}
}
