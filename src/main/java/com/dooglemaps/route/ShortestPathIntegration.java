package com.dooglemaps.route;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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

	private static final String KEY_DISPLAY_INFO = "displayInfo";
	private static final String KEY_DESTINATION = "destination";

	private static final String CONFIG_POST_TRANSPORTS = "postTransports";
	private static final String CONFIG_INCLUDE_BANK_PATH = "includeBankPath";

	private final EventBus eventBus;
	private final ClientThread clientThread;

	/**
	 * Transports the current path uses, <b>in path order</b>, as reported back by Shortest Path.
	 * Empty when it is not installed, or before it has produced a path.
	 *
	 * <p>Path order, not "newest first", which is what this used to claim — it is built by
	 * walking the path from the first step forward. Worth having right, because it means the list
	 * reads as a journey rather than as an unordered set of hops.
	 *
	 * <p>De-duplicated. One edge of a path can produce several {@code Transport} objects sharing a
	 * display string, so a single portal could be listed twice in a row — which is exactly how it
	 * looked in play, and reads as the plugin having lost count rather than as two real hops.
	 */
	@Getter
	private volatile List<String> currentTransports = new ArrayList<>();

	/**
	 * Where Shortest Path says the path it is drawing ends.
	 *
	 * <p>The thing that was assumed unobtainable. The run hands over every outstanding stop and
	 * lets the router pick the cheapest, so nothing here knew which one it chose — but the
	 * transports message carries a {@code destination} alongside the display strings, so it can
	 * simply be read.
	 *
	 * <p>A list, because the API takes a set of targets and this may well be echoing all of them
	 * back rather than naming the one that won. Callers must cope with both; see
	 * {@code GuideTracker}, which only names a stop when exactly one matches.
	 */
	@Getter
	private volatile Set<WorldPoint> currentDestinations = new HashSet<>();

	@Inject
	ShortestPathIntegration(EventBus eventBus, ClientThread clientThread)
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

		// The old path's transports describe a route from where the player used to be, so they
		// stop being true the moment a new one is asked for. Reported from play: after teleporting
		// to the house, the panel still read "via Teleport to house tablet" — an instruction to do
		// the thing that had just been done. Cleared here rather than on arrival of the reply,
		// because the gap between the two is exactly when the stale list was being shown.
		currentTransports = new ArrayList<>();
		currentDestinations = new HashSet<>();

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
	 * Picks up what Shortest Path posts about the path it just found.
	 *
	 * <p>This is the piggyback the spec hoped for but did not expect to get: the panel can say
	 * "this run uses the Fairy ring to Harmony" without us knowing anything about teleports
	 * ourselves — and, it turns out, can say where the path ends too.
	 *
	 * <p>The message carries {@code origin}, {@code destination}, {@code objectInfo} and
	 * {@code displayInfo}. Two of those are read here; the other two are left alone rather than
	 * decoded speculatively.
	 */
	@Subscribe
	public void onPluginMessage(PluginMessage event)
	{
		if (!NAMESPACE.equals(event.getNamespace()) || !MESSAGE_TRANSPORTS.equals(event.getName()))
		{
			return;
		}

		currentTransports = readTransports(event);
		currentDestinations = readDestinations(event);

		log.debug("Path uses {} transport(s), ending at {} point(s)",
			currentTransports.size(), currentDestinations.size());
	}

	/**
	 * The transports the path uses, in order and without repeats.
	 *
	 * <p>De-duplicated because one edge can yield several {@code Transport} objects sharing a
	 * display string — a portal listed twice running is one hop reported twice, not two hops, and
	 * shown raw it reads as a counting bug. Insertion order is kept, so the list still reads as
	 * the journey.
	 */
	private static List<String> readTransports(PluginMessage event)
	{
		Object displayInfo = event.getData().get(KEY_DISPLAY_INFO);
		if (!(displayInfo instanceof List))
		{
			return new ArrayList<>();
		}

		Set<String> seen = new LinkedHashSet<>();
		for (Object entry : (List<?>) displayInfo)
		{
			if (entry instanceof String && !((String) entry).isEmpty())
			{
				seen.add((String) entry);
			}
		}
		return new ArrayList<>(seen);
	}

	/**
	 * Where the path ends, as far as Shortest Path will say.
	 *
	 * <p>Deliberately tolerant about what arrives. It may be the single point the router settled
	 * on, or it may be every target it was handed — the API takes a set, so both are plausible
	 * and the difference is not documented. Reading it as a set and letting the caller decide
	 * what a set of two means is the version of this that cannot be wrong.
	 */
	private static Set<WorldPoint> readDestinations(PluginMessage event)
	{
		Set<WorldPoint> destinations = new HashSet<>();

		Object destination = event.getData().get(KEY_DESTINATION);
		if (destination instanceof WorldPoint)
		{
			destinations.add((WorldPoint) destination);
		}
		else if (destination instanceof Collection)
		{
			for (Object entry : (Collection<?>) destination)
			{
				if (entry instanceof WorldPoint)
				{
					destinations.add((WorldPoint) entry);
				}
			}
		}
		return destinations;
	}
}
