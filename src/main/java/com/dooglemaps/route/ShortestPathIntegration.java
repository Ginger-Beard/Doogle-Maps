package com.dooglemaps.route;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;

/**
 * Hands destinations to the Shortest Path plugin, which owns all the actual navigating.
 *
 * <h2>How the two plugins talk</h2>
 * Shortest Path exposes a proper API over RuneLite's {@code PluginMessage} bus, under the
 * {@code shortestpath} namespace:
 *
 * <ul>
 *   <li>{@code path} with {@code target} — a {@link WorldPoint}, a packed int, or a
 *       <b>set</b> of either. Optional {@code start} defaults to where the player is.</li>
 *   <li>{@code path} with {@code config} — a map of config overrides applied to that
 *       request, e.g. the path colour or {@code postTransports}.</li>
 *   <li>{@code clear} — drops the path and any overrides.</li>
 *   <li>It posts {@code transports} back, listing the fairy rings, spirit trees and so on
 *       the current path uses — but only when {@code postTransports} is on, which is why
 *       we turn it on via the override.</li>
 * </ul>
 *
 * <h2>Why multiple targets matter</h2>
 * Given a set of targets, Shortest Path routes to whichever is cheapest to reach <i>using
 * its own cost model</i>, including the player's own teleports and transports. That is
 * worth more than it sounds: handing it every patch still to be serviced and letting it
 * pick means the visiting order comes out of real travel cost, with no pathfinder and no
 * tour-ordering heuristic of our own. Service one, drop it from the set, post the rest.
 *
 * <h2>Soft dependency</h2>
 * Posting a message nobody is listening for does nothing at all, so none of this needs
 * Shortest Path to be installed and there is nothing to check for. When it is absent the
 * player simply gets no line on the map, and the panel's show-on-map fallback covers it.
 */
@Slf4j
@Singleton
public class ShortestPathIntegration
{
	private static final String NAMESPACE = "shortestpath";
	private static final String MESSAGE_PATH = "path";
	private static final String MESSAGE_CLEAR = "clear";
	private static final String MESSAGE_TRANSPORTS = "transports";

	private static final String KEY_START = "start";
	private static final String KEY_TARGET = "target";
	private static final String KEY_CONFIG = "config";

	private static final String CONFIG_POST_TRANSPORTS = "postTransports";
	private static final String CONFIG_INCLUDE_BANK_PATH = "includeBankPath";

	private final EventBus eventBus;
	private final ClientThread clientThread;

	/**
	 * Transports the current path uses, newest first, as reported back by Shortest Path.
	 * Empty when it is not installed, or before it has produced a path.
	 */
	@Getter
	private volatile List<String> currentTransports = new ArrayList<>();

	@Inject
	private ShortestPathIntegration(EventBus eventBus, ClientThread clientThread)
	{
		this.eventBus = eventBus;
		this.clientThread = clientThread;
	}

	/**
	 * Posts to Shortest Path from the client thread.
	 *
	 * <p>{@code EventBus.post} runs subscribers synchronously on whatever thread calls it,
	 * and Shortest Path's handler reads the player's position to default the start point —
	 * which the client only allows from its own thread. Posting straight from a button
	 * click therefore throws inside <i>their</i> plugin, which is a poor way to repay a soft
	 * dependency.
	 */
	private void post(PluginMessage message)
	{
		clientThread.invokeLater(() -> eventBus.post(message));
	}

	/**
	 * Routes to whichever of these is cheapest to reach.
	 *
	 * @param targets where the player could usefully go next; an empty set clears the path
	 */
	public void setTargets(Collection<WorldPoint> targets)
	{
		setTargets(targets, false);
	}

	/**
	 * Routes to whichever of these is cheapest to reach.
	 *
	 * @param targets      where the player could usefully go next; an empty set clears the path
	 * @param mayVisitBank whether the route is allowed to detour through a bank on the way
	 */
	public void setTargets(Collection<WorldPoint> targets, boolean mayVisitBank)
	{
		if (targets == null || targets.isEmpty())
		{
			clear();
			return;
		}

		Map<String, Object> data = new HashMap<>();
		data.put(KEY_TARGET, new HashSet<>(targets));

		Map<String, Object> configOverride = new HashMap<>();
		// Ask for the transport list back so the panel can say what the route uses.
		configOverride.put(CONFIG_POST_TRANSPORTS, true);

		// Only on the supply leg. Shortest Path models banking itself — its pathfinder tracks
		// whether a path has been through a bank, and BankPickupRequirements works out what to
		// grab — which is exactly right when the point of the trip is to collect things.
		//
		// It is wrong the rest of the time, and loudly so: switched on for a hop between
		// patches it will happily decide the cheapest route runs house, bank, teleport, and
		// draw that. Standing at the Ardougne patches with the work in front of you, the
		// on-screen instruction read "teleport home" and stayed there.
		if (mayVisitBank)
		{
			configOverride.put(CONFIG_INCLUDE_BANK_PATH, true);
		}
		data.put(KEY_CONFIG, configOverride);

		log.debug("Routing to {} target(s){}", targets.size(),
			mayVisitBank ? ", bank detours allowed" : "");
		post(new PluginMessage(NAMESPACE, MESSAGE_PATH, data));
	}

	/** Routes to a single destination. */
	public void setTarget(WorldPoint target)
	{
		setTargets(target == null ? null : java.util.Collections.singleton(target));
	}

	/**
	 * Routes from somewhere other than the player's position.
	 *
	 * <p>Useful for planning a run before setting off — "if I started at the bank, what
	 * would I reach first".
	 */
	public void setTargets(WorldPoint start, Collection<WorldPoint> targets)
	{
		if (targets == null || targets.isEmpty())
		{
			clear();
			return;
		}

		Map<String, Object> data = new HashMap<>();
		data.put(KEY_TARGET, new HashSet<>(targets));
		if (start != null)
		{
			data.put(KEY_START, start);
		}
		post(new PluginMessage(NAMESPACE, MESSAGE_PATH, data));
	}

	public void clear()
	{
		currentTransports = new ArrayList<>();
		post(new PluginMessage(NAMESPACE, MESSAGE_CLEAR));
	}

	/**
	 * Picks up the transport list Shortest Path posts for the path it just found.
	 *
	 * <p>This is the piggyback the spec hoped for but did not expect to get: it means the
	 * panel can say "this run uses the Fairy ring to Harmony" without us knowing anything
	 * about teleports ourselves.
	 */
	@Subscribe
	public void onPluginMessage(PluginMessage event)
	{
		if (!NAMESPACE.equals(event.getNamespace()) || !MESSAGE_TRANSPORTS.equals(event.getName()))
		{
			return;
		}

		Object displayInfo = event.getData().get("displayInfo");
		if (!(displayInfo instanceof List))
		{
			return;
		}

		List<String> transports = new ArrayList<>();
		for (Object entry : (List<?>) displayInfo)
		{
			if (entry instanceof String && !((String) entry).isEmpty())
			{
				transports.add((String) entry);
			}
		}

		currentTransports = transports;
		log.debug("Path uses {} transport(s)", transports.size());
	}
}
