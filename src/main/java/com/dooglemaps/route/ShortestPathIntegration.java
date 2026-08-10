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
import javax.annotation.Nullable;
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
	private static final String KEY_OBJECT_INFO = "objectInfo";
	private static final String KEY_DESTINATION = "destination";

	private static final String CONFIG_POST_TRANSPORTS = "postTransports";
	private static final String CONFIG_INCLUDE_BANK_PATH = "includeBankPath";
	private static final String CONFIG_POH_PORTALS = "useTeleportationPortalsPoh";

	private final EventBus eventBus;
	private final ClientThread clientThread;

	/**
	 * Whether the player's house has portal-room portals, or null while no house has been
	 * seen. Handed in as a supplier the same way the stores share single facts — the state
	 * lives in {@code PlayerHouse}, and this class only needs the answer at posting time.
	 */
	private java.util.function.Supplier<Boolean> housePortals = () -> null;

	/** Told where to read the house's portal answer from; see {@code PlayerHouse}. */
	public void setHousePortalKnowledge(java.util.function.Supplier<Boolean> housePortals)
	{
		this.housePortals = housePortals;
	}

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
		setTargets(targets, mayVisitBank, null);
	}

	/**
	 * Routes to whichever of these is cheapest to reach, from somewhere the player is not.
	 *
	 * @param targets      where the player could usefully go next; an empty set clears the path
	 * @param mayVisitBank whether the route is allowed to detour through a bank on the way
	 * @param start        where the path should begin, or null for the player's position.
	 *                     Exists for the player-owned house: the position inside is an
	 *                     instance tile, and when the route leaves on foot the true start is
	 *                     the exterior portal — see {@code GuideTracker.routeFromTheFrontDoor}
	 */
	public void setTargets(Collection<WorldPoint> targets, boolean mayVisitBank,
		@javax.annotation.Nullable WorldPoint start)
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

		Map<String, Object> configOverride = new HashMap<>();
		// Ask for the transport list back so the panel can say what the route uses.
		configOverride.put(CONFIG_POST_TRANSPORTS, true);

		// Shortest Path's POH data assumes every house has its portal-room portals built —
		// the Prifddinas respawn-portal edge is not even varbit-gated — so a portal-less
		// house is routed through furniture that is not there. Worse than a wrong line: from
		// anywhere near the destination its cost model kept preferring "teleport home, leave
		// through the respawn portal", so following the guide walked the player in and out of
		// their own front door forever. Reported from play. When the house has actually been
		// looked at and holds no portal-room portal, those edges are switched off for our
		// requests; the front-door transit lives in the general portal data and survives, as
		// do the jewellery box and mounted items, which are modelled separately and real.
		if (Boolean.FALSE.equals(housePortals.get()))
		{
			configOverride.put(CONFIG_POH_PORTALS, false);
		}

		// Only on the supply leg. Shortest Path models banking itself — its pathfinder tracks
		// whether a path has been through a bank, and BankPickupRequirements works out what to
		// grab — which is exactly right when the point of the trip is to collect things.
		//
		// It is wrong the rest of the time, and loudly so: switched on for a hop between
		// patches it will happily decide the cheapest route runs house, bank, teleport, and
		// draw that. Standing at the Ardougne patches with the work in front of you, the
		// on-screen instruction read "teleport home" and stayed there.
		//
		// Sent explicitly in BOTH directions, which is the part that was missing: an omitted
		// key falls back to the player's own Shortest Path setting, so a user with
		// "include bank path" on in SP got bank detours on plain travel legs anyway — a
		// teleport to Seers' bank in the middle of a hop to Kourend, which is exactly the
		// route the comment above says this exists to prevent. Reported from play.
		configOverride.put(CONFIG_INCLUDE_BANK_PATH, mayVisitBank);
		data.put(KEY_CONFIG, configOverride);

		// The old path's transports describe a route from where the player used to be, so they
		// stop being true the moment a new one is asked for. Reported from play: after teleporting
		// to the house, the panel still read "via Teleport to house tablet" — an instruction to do
		// the thing that had just been done. Cleared here rather than on arrival of the reply,
		// because the gap between the two is exactly when the stale list was being shown.
		currentTransports = new ArrayList<>();
		currentDestinations = new HashSet<>();
		firstTransportObject = null;
		// ...and the gap itself is now a stated fact, because empty-while-waiting and
		// empty-as-the-answer mean different things: "no transports" as an *answer* is what
		// sends the overlay to the exit portal, and jumping there during the wait lit the
		// portal for the second Shortest Path took to think. Reported from play.
		awaitingRoute = true;
		requestedAtMillis = System.currentTimeMillis();
		routeRequested = true;
		routeGeneration++;

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
		// The destinations too — they were surviving a clear, so everything that names the
		// travel target off them (destinationStop, the travel hint's region fallback) kept
		// reading a dead route's landing points indefinitely. The stalest of the reported
		// "area mismatch" generators.
		currentDestinations = new HashSet<>();
		awaitingRoute = false;
		routeRequested = false;
		routeGeneration++;
		post(new PluginMessage(NAMESPACE, MESSAGE_CLEAR));
	}

	/**
	 * Whether a route of ours is outstanding — asked for and not yet cleared.
	 *
	 * <p>The gate on the listener below. Shortest Path posts a transports message for
	 * <b>every</b> path it computes once {@code postTransports} is on — including paths the
	 * player set themselves, which have nothing to do with the run. Accepting those displayed
	 * a personal errand's hops as the farm route and pointed the destination naming at
	 * wherever the player happened to be going. With no route of ours live, the messages are
	 * simply not ours to read.
	 */
	private volatile boolean routeRequested;

	/**
	 * Whether a route has been asked for and not answered yet.
	 *
	 * <p>True from {@link #setTargets} until the transports message lands, so callers can tell
	 * "Shortest Path said the route uses nothing" from "Shortest Path has not said". Volatile,
	 * like the lists: read per frame by the overlay with no lock.
	 */
	private volatile boolean awaitingRoute;

	/** When the outstanding request was posted, for the timeout below. */
	private volatile long requestedAtMillis;

	/**
	 * A question outstanding this long is a question nobody is answering.
	 *
	 * <p>Shortest Path is a soft dependency: not installed, or disabled mid-run, no reply ever
	 * comes — and "awaiting" was unbounded, so everything gated on it (the exit-portal
	 * fallback, the travel-hint wording) stayed in the waiting state for the rest of the run.
	 * Real answers arrive within a second or two; ten is generous.
	 */
	private static final long ROUTE_ANSWER_TIMEOUT_MILLIS = 10_000;

	/** See {@link #awaitingRoute} — false once the reply lands or the timeout passes. */
	public boolean isAwaitingRoute()
	{
		return awaitingRoute
			&& System.currentTimeMillis() - requestedAtMillis < ROUTE_ANSWER_TIMEOUT_MILLIS;
	}

	/**
	 * Bumped whenever the route the world should be read against changes: a new request, a
	 * clear, an accepted answer. For per-tick caches downstream ({@code RouteItem}) whose tick
	 * key alone kept serving the previous route's answer for the rest of the tick.
	 */
	@Getter
	private volatile int routeGeneration;

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

		// Only while a route of ours is live. See routeRequested.
		if (!routeRequested)
		{
			return;
		}

		List<WorldPoint> landings = readPoints(event, KEY_DESTINATION);
		currentTransports = readTransports(event);
		firstTransportObject = readFirstObject(event);

		// Insertion-ordered, so iterating reaches the path's last landing last — the closest
		// thing the message has to "where this route ends", which the destination naming
		// prefers over any intermediate hop.
		Set<WorldPoint> destinations = new LinkedHashSet<>();
		for (WorldPoint landing : landings)
		{
			if (landing != null)
			{
				destinations.add(landing);
			}
		}
		currentDestinations = destinations;
		awaitingRoute = false;
		routeGeneration++;

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
	 *
	 * <p>Every reported hop is kept, deliberately. A walk-edge filter briefly lived here on
	 * the theory that Shortest Path reports teleports for tiles the path merely walks across;
	 * the reported case turned out to be a real hop of a real plan — a bank detour collecting
	 * a banked talisman, see the includeBankPath note in {@code setTargets} — and
	 * second-guessing the router's own account of its route is how a true instruction gets
	 * hidden.
	 */
	private static List<String> readTransports(PluginMessage event)
	{
		Object displayInfo = event.getData().get(KEY_DISPLAY_INFO);
		if (!(displayInfo instanceof List))
		{
			return new ArrayList<>();
		}

		// The object the hop goes through, alongside what its menu row says. displayInfo
		// alone read as a riddle for object transports — "via 6: Prifddinas" is a spirit
		// tree's row label with the tree cut out of it — and objectInfo is the half of the
		// message that names the thing to click. Reported from play. Item and spell hops
		// carry no objectInfo and are unchanged.
		Object objectInfo = event.getData().get(KEY_OBJECT_INFO);
		List<?> objects = objectInfo instanceof List ? (List<?>) objectInfo : null;
		List<?> entries = (List<?>) displayInfo;
		boolean parallel = objects != null && objects.size() == entries.size();

		Set<String> seen = new LinkedHashSet<>();
		for (int i = 0; i < entries.size(); i++)
		{
			Object entry = entries.get(i);
			if (!(entry instanceof String) || ((String) entry).isEmpty())
			{
				continue;
			}
			String line = (String) entry;
			if (parallel && objects.get(i) instanceof String)
			{
				String object = objectName((String) objects.get(i));
				if (!object.isEmpty() && !line.toLowerCase().contains(object.toLowerCase()))
				{
					line = object + " - " + line;
				}
			}
			seen.add(line);
		}
		return new ArrayList<>(seen);
	}

	/** The trailing object or item id on a Shortest Path {@code objectInfo} value. */
	private static final java.util.regex.Pattern OBJECT_ID =
		java.util.regex.Pattern.compile("\\s+\\d+$");

	/**
	 * Shortest Path's {@code objectInfo}, with the id taken off the end.
	 *
	 * <p>The column is {@code menuOption menuTarget objectId} in its own transport TSVs —
	 * <i>"Teleport Menu Fancy Jewellery Box 37501"</i>, <i>"Travel Spirit tree 37329"</i> — and
	 * the id is there for the router, not for reading. It reached the panel as
	 * <i>"via Teleport Menu Fancy Jewellery Box 37501 - J: Farming Guild"</i>. Reported from
	 * play; the rest of the value is left exactly as the router wrote it, since where the menu
	 * option ends and the object's name begins is not something the string says.
	 */
	static String objectName(String objectInfo)
	{
		return OBJECT_ID.matcher(objectInfo).replaceFirst("").trim();
	}

	/**
	 * The object the route's first hop goes through — "Spirit tree" — or null when the first
	 * hop is not an object (an item, a spell, or nothing at all).
	 *
	 * <p>For the overlay: the drawn line says where to walk, but nothing in the scene was
	 * marked as the thing to click when the hop was neither an item nor house furniture. The
	 * GE's spirit tree went entirely unhighlighted. Reported from play.
	 */
	@Getter
	private volatile String firstTransportObject;

	@Nullable
	private static String readFirstObject(PluginMessage event)
	{
		Object objectInfo = event.getData().get(KEY_OBJECT_INFO);
		if (!(objectInfo instanceof List) || ((List<?>) objectInfo).isEmpty())
		{
			return null;
		}
		Object first = ((List<?>) objectInfo).get(0);
		if (!(first instanceof String) || ((String) first).isEmpty())
		{
			return null;
		}
		String name = objectName((String) first);
		return name.isEmpty() ? null : name;
	}

	/**
	 * One of the message's parallel per-hop point lists, in path order.
	 *
	 * <p>Deliberately tolerant about what arrives — a single point, a list, or nothing — and
	 * order-preserving, because the <i>last</i> landing is the closest thing the message has
	 * to "where this path ends", and callers naming the destination want it distinguishable.
	 */
	private static List<WorldPoint> readPoints(PluginMessage event, String key)
	{
		List<WorldPoint> points = new ArrayList<>();

		Object value = event.getData().get(key);
		if (value instanceof WorldPoint)
		{
			points.add((WorldPoint) value);
		}
		else if (value instanceof Collection)
		{
			for (Object entry : (Collection<?>) value)
			{
				points.add(entry instanceof WorldPoint ? (WorldPoint) entry : null);
			}
		}
		return points;
	}
}
