package com.dooglemaps.capture;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmRegion;
import com.dooglemaps.data.FarmingWorldData;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * A region scan writes the patch blob once, not once per patch.
 *
 * <h2>The reported dead end</h2>
 *
 * A session's config log held 93 writes of the {@code patches} key, 18.5KB apiece — 85% of every
 * serialised byte this plugin produced — landing in bursts on the second: ten at 22:41:42, right
 * after "Entered farming region(s) [Kourend]", nine at 22:42:08, nine at 22:42:09. That is one
 * write per patch of a freshly arrived region, because the first scan somewhere finds every patch
 * changed at once — it is catching up on days of growth.
 *
 * <p>The bytes are not the cost. {@code ConfigManager} posts {@code ConfigChanged} synchronously
 * into every subscriber in the client, so ten changed patches meant ten trips through every other
 * installed plugin's config handling, on the client thread, inside one tick. The player's log
 * shows exactly that: each write followed immediately by third-party plugins reloading config.
 *
 * <p>So these tests count {@code setRSProfileConfiguration} calls for the {@code patches} key
 * rather than looking at what was stored — the number of writes <i>is</i> the bug. Falador's
 * allotment region is the fixture because it holds five patches of four different kinds, which
 * before the fix produced five writes for one tick's scan.
 */
public class RegionScanWriteBurstTest
{
	/**
	 * A tile in Falador's allotment region. Region 12083 is shared with the Port Sarim spirit
	 * tree, split at y=3272 — north of it only the allotments' varbits are live, which is what
	 * makes this a single-region scan.
	 */
	private static final WorldPoint FALADOR_ALLOTMENTS = new WorldPoint(3050, 3300, 0);

	private Map<String, String> stored;
	private ConfigManager configManager;
	private Gson gson;

	/** Every {@code setRSProfileConfiguration} for our patches key, which is what is on trial. */
	private int patchWrites;

	/** Varbit id to the value the client will report for it; anything unlisted reads 0. */
	private Map<Integer, Integer> varbits;

	/** A varbit id the client refuses to answer for, standing in for a scan that blows up. */
	private Integer throwingVarbit;

	@Before
	public void setUp()
	{
		stored = new HashMap<>();
		varbits = new HashMap<>();
		throwingVarbit = null;
		patchWrites = 0;
		gson = new Gson();
		configManager = Mockito.mock(ConfigManager.class);

		when(configManager.getRSProfileKey()).thenReturn("profile-1");
		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer(i -> stored.get(key(i)));

		doAnswer(i ->
		{
			put(i);
			return null;
		}).when(configManager).setRSProfileConfiguration(anyString(), anyString(), Mockito.any());

		doAnswer(i ->
		{
			patchWrites++;
			put(i);
			return null;
		}).when(configManager).setRSProfileConfiguration(
			eq(DoogleMapsConfig.GROUP), eq("patches"), Mockito.any());
	}

	/** Config is addressed by group and key; join them the way RuneLite does. */
	private static String key(InvocationOnMock invocation)
	{
		Object group = invocation.getArgument(0);
		Object name = invocation.getArgument(1);
		return group + "." + name;
	}

	private void put(InvocationOnMock invocation)
	{
		Object value = invocation.getArgument(2);
		stored.put(key(invocation), String.valueOf(value));
	}

	/**
	 * The whole point: one tick, five patches recorded, one write.
	 *
	 * <p>Without the batching this asserts five — one save per changed patch, each one a
	 * synchronous ConfigChanged into every plugin in the client.
	 */
	@Test
	public void aWholeRegionScanIsOneConfigWrite()
	{
		PatchStateStore store = newStore();
		store.load();
		assertEquals("fixture: loading a fresh profile writes nothing", 0, patchWrites);

		FarmRegion region = faladorAllotments();
		assertTrue("fixture: the region has several patches to write",
			region.getPatches().size() > 1);
		everyPatchReadable(region);

		tracker(store).onGameTick(new GameTick());

		assertEquals("every patch of the region was recorded",
			region.getPatches().size(), recordedPatches(store, region));
		assertEquals("one blob describing the whole region says everything "
			+ region.getPatches().size() + " blobs said", 1, patchWrites);
	}

	/**
	 * A second scan seeing the same values writes nothing at all.
	 *
	 * <p>Guards the obvious way to get the count above wrong — flushing on every scan whether or
	 * not anything moved. Standing in a farming region is the normal state of a farm run, and a
	 * scan runs every tick, so an unconditional flush would be worse than the burst it replaced.
	 */
	@Test
	public void aScanThatSeesNoChangeWritesNothing()
	{
		PatchStateStore store = newStore();
		store.load();
		FarmRegion region = faladorAllotments();
		everyPatchReadable(region);

		PatchInteractionTracker tracker = tracker(store);
		tracker.onGameTick(new GameTick());
		int afterFirst = patchWrites;

		tracker.onGameTick(new GameTick());
		tracker.onGameTick(new GameTick());

		assertEquals("nothing in the region moved, so there was nothing to write",
			afterFirst, patchWrites);
	}

	/**
	 * A scan that dies part-way still persists the patches it got through.
	 *
	 * <p>The batch holds a region's captures in memory until the loop ends, so the flush has to
	 * be unconditional on the way out. If it were not, one patch throwing would silently discard
	 * every patch scanned before it — a strictly worse bug than the write burst, and an invisible
	 * one, because the states are all still correct in memory until the client restarts.
	 *
	 * <p>Proved by reloading a fresh store from the same config rather than by reading the live
	 * one: in memory the captures were never in doubt.
	 */
	@Test
	public void aScanThatThrowsPartWayStillPersistsWhatItRecorded()
	{
		PatchStateStore store = newStore();
		store.load();
		FarmRegion region = faladorAllotments();
		everyPatchReadable(region);

		FarmPatch survives = region.getPatches().get(0);
		FarmPatch dies = region.getPatches().get(region.getPatches().size() - 1);
		throwingVarbit = dies.getVarbit();

		try
		{
			tracker(store).onGameTick(new GameTick());
			fail("fixture: the client was supposed to throw on " + dies);
		}
		catch (IllegalStateException expected)
		{
			// The scan aborted mid-region, which is the situation under test.
		}

		assertEquals("the flush belongs in a finally", 1, patchWrites);

		PatchStateStore reloaded = newStore();
		reloaded.load();
		assertNotNull("a patch scanned before the failure was never written",
			reloaded.get(survives));
		assertNull("fixture: the patch that threw was never recorded", reloaded.get(dies));
	}

	// ------------------------------------------------------------------- helpers

	private PatchStateStore newStore()
	{
		return construct(PatchStateStore.class, configManager, gson);
	}

	private static FarmRegion faladorAllotments()
	{
		for (FarmRegion region : FarmingWorldData.getRegionsForLocation(FALADOR_ALLOTMENTS))
		{
			return region;
		}
		throw new AssertionError("no farming region at " + FALADOR_ALLOTMENTS + " any more");
	}

	/**
	 * Gives every patch of the region a varbit value its own rules can decode.
	 *
	 * <p>Found by asking the implementation rather than hardcoded, so a data update that renumbers
	 * a patch kind fails as a wrong write count rather than as a silently skipped patch — an
	 * undecodable value makes {@code capture} return before it records anything.
	 */
	private void everyPatchReadable(FarmRegion region)
	{
		for (FarmPatch patch : region.getPatches())
		{
			varbits.put(patch.getVarbit(), firstDecodableValue(patch));
		}
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

	private static int recordedPatches(PatchStateStore store, FarmRegion region)
	{
		int seen = 0;
		for (FarmPatch patch : region.getPatches())
		{
			if (store.get(patch) != null)
			{
				seen++;
			}
		}
		return seen;
	}

	/**
	 * A protection payment this store lost is restored when the player walks in.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"I can't pay for a patch I haven't even planted yet… I was immediately prompted to pay
	 * for the east patch, which wasn't harvested yet."</i> Both Great Conch coral patches had
	 * their {@code patchProtected} flag flipped to false in a single write, with the varbit
	 * unchanged at 4 (GROWING), no coral harvest anywhere in the session log, and the player
	 * standing in Ardougne at the time. The client's own record disagreed the whole way through —
	 * {@code timetracking.…12581.4771.protected=true} — so the guide asked for a payment the
	 * player had already made, on a crop they had not touched.
	 *
	 * <p>{@code backfillFrom} always knew how to answer this and only ever ran at login. It runs
	 * on region entry now, where a wrong answer is about to cost money.
	 */
	@Test
	public void arrivingRestoresAProtectionPaymentTimeTrackingStillRemembers()
	{
		PatchStateStore store = newStore();
		FarmPatch patch = FarmingWorldData.getRegionsForLocation(FALADOR_ALLOTMENTS)
			.iterator().next().getPatches().get(0);

		// Our record says unpaid on a crop still growing, which is the state that raises a
		// payment step. The client's record says it was paid for.
		int growing = growingValue(patch);
		store.recordVarbit(patch, growing, patch.getImplementation().forVarbitValue(growing));
		// ...and the client agrees, so the scan itself does not overwrite the crop. A scan that
		// read weeds here would clear the payment on the spot, which is the store's own
		// crop-just-left rule doing exactly what it should.
		varbits.put(patch.getVarbit(), growing);
		assertFalse("fixture: this store thinks the payment is outstanding",
			store.get(patch).isPatchProtected());

		com.dooglemaps.state.TimeTrackingState timeTracking =
			Mockito.mock(com.dooglemaps.state.TimeTrackingState.class);
		when(timeTracking.isProtected(patch)).thenReturn(Boolean.TRUE);

		trackerWith(store, timeTracking).onGameTick(new GameTick());

		assertTrue("walking in should not re-ask for money already paid",
			store.get(patch).isPatchProtected());
	}

	/** A varbit value for this patch meaning "a crop, still growing" — the protectable state. */
	private int growingValue(FarmPatch patch)
	{
		for (int value = 0; value < 256; value++)
		{
			com.dooglemaps.data.ProduceState decoded =
				patch.getImplementation().forVarbitValue(value);
			if (decoded != null && decoded.getProduce() != null
				&& decoded.getProduce().isCrop()
				&& decoded.getCropState() == com.dooglemaps.data.CropState.GROWING)
			{
				return value;
			}
		}
		throw new AssertionError("no growing-crop varbit for " + patch);
	}

	/** The tracker wired to a real store and stand-ins for everything it merely reports to. */
	private PatchInteractionTracker tracker(PatchStateStore store)
	{
		return trackerWith(store,
			Mockito.mock(com.dooglemaps.state.TimeTrackingState.class));
	}

	private PatchInteractionTracker trackerWith(PatchStateStore store,
		com.dooglemaps.state.TimeTrackingState timeTracking)
	{
		return construct(PatchInteractionTracker.class,
			client(),
			store,
			Mockito.mock(GrowthTimer.class),
			Mockito.mock(RunPlanner.class),
			Mockito.mock(HarvestLog.class),
			Mockito.mock(BarbarianFarming.class),
			Mockito.mock(CarriedItems.class),
			Mockito.mock(DiseaseStatsStore.class),
			Mockito.mock(ProtectedPatches.class),
			timeTracking);
	}

	/** A logged-in client standing in Falador with no interface open. */
	@SuppressWarnings("unchecked")
	private Client client()
	{
		Client client = Mockito.mock(Client.class);
		Player player = Mockito.mock(Player.class);
		when(player.getWorldLocation()).thenReturn(FALADOR_ALLOTMENTS);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getLocalPlayer()).thenReturn(player);

		// Varbits are not sent while a modal is open, so the tracker walks this table every tick
		// to check. Nothing is open here; a fresh iterator per call because it is asked more than
		// once.
		HashTable<WidgetNode> components = Mockito.mock(HashTable.class);
		when(components.iterator()).thenAnswer(i -> Collections.<WidgetNode>emptyIterator());
		when(client.getComponentTable()).thenReturn(components);

		when(client.getVarbitValue(anyInt())).thenAnswer(i ->
		{
			int id = i.getArgument(0);
			if (throwingVarbit != null && throwingVarbit == id)
			{
				throw new IllegalStateException("varbit " + id + " is unreadable");
			}
			return varbits.getOrDefault(id, 0);
		});
		return client;
	}
}
