package com.dooglemaps.route;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.events.PluginMessage;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * The leg's plan stays the leg's plan while the player is walking it.
 *
 * <h2>The report</h2>
 *
 * Walking from Taverley towards Varrock with three stops left, the guide's travel hint
 * flipped mid-walk from the drawn walking line to "Cast Teleport to House ... via Grand
 * Exchange Portal". Nothing of ours had asked for a new route: Shortest Path re-plans on its
 * own every tick the player is more than its recalculate distance from the line it drew, and
 * its walk-versus-teleport comparison can flip on a one-tile difference. The first fix
 * stopped our own re-asking on a region change and the flip came back.
 *
 * <p>Nothing in its message API can ask for a route it will leave alone — the per-request
 * config override does not reach {@code recalculateDistance} — so the plan is held on this
 * side instead: a re-plan nobody asked for is ignored while the player is still making
 * ground on the plan already accepted. See {@code ShortestPathIntegration}.
 */
public class AnUnaskedReplanNearTheLineIsDeclinedTest
{
	private static final WorldPoint TAVERLEY = new WorldPoint(2932, 3438, 0);
	private static final WorldPoint VARROCK_PATCH = new WorldPoint(3227, 3458, 0);

	/** Half way along the road, and as close to Varrock as the player has yet been. */
	private static final WorldPoint HALF_WAY = new WorldPoint(3080, 3420, 0);

	/** Twelve tiles back from that: a doorway, a bend in the road, a stray click. */
	private static final WorldPoint A_DOORWAY_OFF = new WorldPoint(3068, 3420, 0);

	/** Sixty tiles back: not the road to Varrock any more. */
	private static final WorldPoint GONE_WANDERING = new WorldPoint(3020, 3420, 0);

	private ShortestPathIntegration integration;
	private boolean instanced;

	@Before
	public void setUp()
	{
		net.runelite.client.eventbus.EventBus eventBus =
			Mockito.mock(net.runelite.client.eventbus.EventBus.class);
		net.runelite.client.callback.ClientThread clientThread =
			Mockito.mock(net.runelite.client.callback.ClientThread.class);
		doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		integration = construct(ShortestPathIntegration.class, eventBus, clientThread);
		integration.setInstanceKnowledge(() -> instanced);
	}

	/** The report itself: a few tiles of drift must not turn a walk into a teleport. */
	@Test
	public void anUnaskedReplanNearTheLineIsDeclined() throws Exception
	{
		walkToVarrock();
		integration.noteProgress(A_DOORWAY_OFF);
		unasked();

		integration.onPluginMessage(houseAndPortal(A_DOORWAY_OFF));

		assertTrue("the leg is still the walk it was planned as",
			integration.getCurrentTransports().isEmpty());
		assertTrue("and still ends where it ended",
			integration.getCurrentDestinations().isEmpty());
		assertNull("with nothing new to click", integration.getNextTransportObject());
	}

	/** Ground genuinely lost is the player leaving the plan, and the re-plan is the answer. */
	@Test
	public void anUnaskedReplanFarFromTheLineIsTaken() throws Exception
	{
		walkToVarrock();
		integration.noteProgress(GONE_WANDERING);
		unasked();

		integration.onPluginMessage(houseAndPortal(GONE_WANDERING));

		assertEquals("a player who has left the plan gets the router's new one",
			Collections.singletonList("Teleport to House"),
			integration.getCurrentTransports());
		assertTrue("ending where the leg was always going",
			integration.getCurrentDestinations().contains(VARROCK_PATCH));
		assertEquals("with the portal as the next thing to click once the house has loaded",
			"Enter Grand Exchange Portal", nextObjectAfterTheHouse());
	}

	/**
	 * An answer to a question of ours is never declined — a retarget from a real jump, Skip,
	 * Start, the front-door reroute. They arrive with no ground lost at all, which is exactly
	 * the shape the rule above turns down.
	 */
	@Test
	public void anExplicitRetargetIsAlwaysTaken()
	{
		walkToVarrock();
		integration.noteProgress(HALF_WAY);

		// As the guide does when it sees a jump: ask again, from here.
		integration.setTargets(Collections.singleton(VARROCK_PATCH));
		integration.onPluginMessage(houseAndPortal(HALF_WAY));

		assertEquals("the answer to our own question is the route",
			Collections.singletonList("Teleport to House"),
			integration.getCurrentTransports());
	}

	/** The older guard is untouched: an instanced player's recomputes are still garbage. */
	@Test
	public void theInstanceGuardStillHolds() throws Exception
	{
		walkToVarrock();
		instanced = true;
		// Far enough off that the plan would otherwise be given up on.
		integration.noteProgress(GONE_WANDERING);
		unasked();

		integration.onPluginMessage(houseAndPortal(GONE_WANDERING));

		assertTrue("a recompute from instance-template coordinates is not a route",
			integration.getCurrentTransports().isEmpty());
		assertTrue(integration.getCurrentDestinations().isEmpty());
	}

	/** The object of the hop left once the house teleport has landed. */
	private String nextObjectAfterTheHouse()
	{
		integration.noteProgress(new WorldPoint(1923, 5709, 0));
		return integration.getNextTransportObject();
	}

	/** A leg asked for and answered as a plain walk, with the player setting off. */
	private void walkToVarrock()
	{
		integration.setTargets(Collections.singleton(VARROCK_PATCH));

		// A walk uses no transports, so the router's answer is a message full of empty lists.
		Map<String, Object> data = new HashMap<>();
		data.put("origin", Collections.emptyList());
		data.put("destination", Collections.emptyList());
		data.put("objectInfo", Collections.emptyList());
		data.put("displayInfo", Collections.emptyList());
		integration.onPluginMessage(new PluginMessage("shortestpath", "transports", data));

		integration.noteProgress(TAVERLEY);
		integration.noteProgress(HALF_WAY);
	}

	/**
	 * Ages the outstanding request past the settle window, which is what makes the next
	 * message one nobody asked for. The same reach as {@code NextHopObjectTest} uses.
	 */
	private void unasked() throws Exception
	{
		java.lang.reflect.Field asked =
			ShortestPathIntegration.class.getDeclaredField("requestedAtMillis");
		asked.setAccessible(true);
		asked.setLong(integration, System.currentTimeMillis() - 60_000);
	}

	/** The flip that was reported: teleport home, then out through the GE portal. */
	private static PluginMessage houseAndPortal(WorldPoint from)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("origin", Arrays.asList(from, new WorldPoint(1928, 5731, 0)));
		data.put("destination",
			Arrays.asList(new WorldPoint(1923, 5709, 0), VARROCK_PATCH));
		data.put("objectInfo",
			Arrays.asList(null, "Enter Grand Exchange Portal 33430"));
		data.put("displayInfo", Arrays.asList("Teleport to House", null));
		return new PluginMessage("shortestpath", "transports", data);
	}
}
