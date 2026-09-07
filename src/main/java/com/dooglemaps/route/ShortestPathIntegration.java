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
 *       request, e.g. the path colour or {@code postTransports}. Only the keys its
 *       {@code cacheConfigValues} reads go through the override map; the two settings that
 *       govern its <b>own</b> re-planning — {@code recalculateDistance} and
 *       {@code cancelInstead} — are read straight off the user's config, so a request cannot
 *       ask for a route that will not be re-planned underneath it. See
 *       {@link #stillFollowingThePlan}.</li>
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
	private static final String KEY_ORIGIN = "origin";

	/**
	 * Shortest Path's own player-owned-house area, mirrored exactly.
	 *
	 * <p>Its model makes the POH a real place on the map: every transport into a house lands
	 * at one canonical tile in this area, and every house-furniture transport — the imagined
	 * portals, the jewellery box, the mounted items, the garden's spirit tree and fairy ring
	 * — departs from inside it ({@code ShortestPathPlugin.isInsidePoh}, bounds copied from
	 * its source; the x minimum excludes the Daddy's Home area, their note). A hop whose
	 * origin is in here is therefore, by the router's own word, a click on house furniture.
	 */
	static boolean isPohArea(@Nullable WorldPoint point)
	{
		return point != null
			&& point.getX() >= 1856 && point.getX() <= 2047
			&& point.getY() >= 5696 && point.getY() <= 5767;
	}

	/**
	 * Whether any hop of the current route departs from inside the house.
	 *
	 * <p>The message's {@code origin} list, previously left unread, is the honest version of
	 * every "does the route go through this house" question the guide used to answer by
	 * matching hop wording against furniture names: the router publishes where each hop
	 * happens, and a hop anchored in the POH area is house furniture whatever it is called.
	 * See {@link #isPohArea}. False while no route of ours has been answered.
	 */
	@Getter
	private volatile boolean routeDepartsPoh;

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
	 * Whether the player is currently inside an instance, or false while unknown. Handed in
	 * as a supplier the same way the house's portal answer is; see the wiring in the plugin.
	 */
	private java.util.function.Supplier<Boolean> playerInInstance = () -> false;

	/** Told how to ask whether the player is instanced; see {@link #onPluginMessage}. */
	public void setInstanceKnowledge(java.util.function.Supplier<Boolean> playerInInstance)
	{
		this.playerInInstance = playerInInstance;
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
		hops = new ArrayList<>();
		hopsPassed = 0;
		routeDepartsPoh = false;
		// What this leg is for, kept so the plan the router answers with can be told from a
		// plan it re-computes on its own later; see stillFollowingThePlan.
		requestedTargets = new HashSet<>(targets);
		nearestApproach = Integer.MAX_VALUE;
		routeAnswered = false;
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
		hops = new ArrayList<>();
		hopsPassed = 0;
		routeDepartsPoh = false;
		awaitingRoute = false;
		routeRequested = false;
		requestedTargets = new HashSet<>();
		nearestApproach = Integer.MAX_VALUE;
		routeAnswered = false;
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

	/**
	 * How long after a request its answers keep being read while the player is instanced.
	 *
	 * <p>Shortest Path's default calculation cutoff is three seconds of no progress, and a
	 * request that supersedes a running one makes the old one post first (see the note in
	 * {@link #onPluginMessage}), so the real answer can be the second or third message. Past
	 * this the messages are the router's own instance recomputes and are ignored as before.
	 */
	private static final long ROUTE_SETTLE_MILLIS = 5_000;

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

		// And not the router's own recomputes while the player is instanced. Shortest Path
		// re-paths from the player's instance-template position every tick it judges the
		// player "off the path" — from coordinates that mean nothing — and each recompute
		// posts a transports message. Reading those replaced a real route's hops and landing
		// points with an arbitrary stop's: teleporting to the POH flipped a Weiss run's
		// destination to the Ardougne bushes as the house loaded, and the furniture
		// highlights followed the garbage route. Reported from play. An answer we are
		// actually waiting on — the front-door reroute, which posts an explicit start — is
		// still read; it is the unsolicited ones that carry nothing worth knowing.
		//
		// "Waiting on" is a window, not the first message to land. Shortest Path runs a
		// pathfinder's completion callback even when that pathfinder was CANCELLED by a newer
		// request, and the callback posts whatever path the newest pathfinder holds at that
		// instant — usually nothing yet. So two requests a tick apart produce a junk
		// "no transports" message before the real answer, and taking the first message as the
		// answer then threw the real one away as an instance recompute. Reported from play:
		// entering the house on a nexus leg to Catherby, the panel lost the destination and
		// the nexus row while the nexus itself stayed lit off the previous route. Every
		// message inside the window is read and the latest wins; the window is the router's
		// own no-progress cutoff with room to spare.
		boolean unsolicited = !isAwaitingRoute()
			&& System.currentTimeMillis() - requestedAtMillis > ROUTE_SETTLE_MILLIS;

		if (unsolicited && Boolean.TRUE.equals(playerInInstance.get()))
		{
			return;
		}

		// And not a re-plan of the leg the player is already walking; see
		// stillFollowingThePlan for the whole of why.
		if (unsolicited && stillFollowingThePlan())
		{
			declineReplan(event);
			return;
		}

		List<WorldPoint> landings = readPoints(event, KEY_DESTINATION);
		currentTransports = readTransports(event);
		hops = readHops(event);
		hopsPassed = 0;

		boolean departsPoh = false;
		for (WorldPoint origin : readPoints(event, KEY_ORIGIN))
		{
			if (isPohArea(origin))
			{
				departsPoh = true;
				break;
			}
		}
		routeDepartsPoh = departsPoh;

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
		// This plan is now the one being held. Its hops are anchors the player can be judged
		// against, so the closest approach starts over with them counted in; the next tick
		// fills it back in. See stillFollowingThePlan.
		routeAnswered = true;
		nearestApproach = Integer.MAX_VALUE;
		routeGeneration++;

		log.debug("Path uses {} transport(s), ending at {} point(s)",
			currentTransports.size(), currentDestinations.size());
	}

	/**
	 * What the outstanding request asked to reach — the leg's own destinations, not the
	 * router's answer.
	 *
	 * <p>{@link #currentDestinations} cannot stand in for this: it is read from the message's
	 * {@code destination} list, which is where each <i>hop</i> lands, so a leg walked on foot
	 * answers with nothing at all. The targets are the only description of the leg that
	 * survives a walking route, and {@link #stillFollowingThePlan} needs one.
	 */
	private volatile Set<WorldPoint> requestedTargets = new HashSet<>();

	/** Where the player was as of the last tick; see {@link #noteProgress}. */
	@Nullable
	private volatile WorldPoint playerTile;

	/**
	 * The closest the player has come to the accepted plan since it was accepted, or
	 * {@code Integer.MAX_VALUE} while nothing has been measured. See {@link #approachDistance}.
	 */
	private volatile int nearestApproach = Integer.MAX_VALUE;

	/** Whether a message has been accepted as the answer to the outstanding request. */
	private volatile boolean routeAnswered;

	/** The generation a decline was last logged for, so a re-planning router logs once. */
	private volatile int declineLogged = -1;

	/**
	 * How much ground the player may lose before the leg's plan is considered abandoned.
	 *
	 * <p>Shortest Path re-plans once the player is more than its {@code recalculateDistance}
	 * from every step of the line it drew — twenty tiles by default — so a player who is
	 * merely walking a slightly different line than the one drawn has lost at most about that
	 * much ground. Real routes also make you walk away from where you are going: around a
	 * building, back to a fairy ring, along the far side of a river. This has to sit well
	 * clear of both, and losing four full screens of ground is not something following the
	 * plan does.
	 */
	private static final int PLAN_ABANDONED_TILES = 48;

	/**
	 * Whether the player is still walking the plan we accepted, so a re-plan nobody asked for
	 * should be ignored.
	 *
	 * <h2>The bug this exists for</h2>
	 *
	 * Shortest Path re-plans on its own. Every tick it checks whether the player is within
	 * {@code recalculateDistance} of any step of the line it is drawing, and when they are
	 * not it pathfinds again from wherever they are standing and posts a fresh transports
	 * message. Its walk-versus-teleport comparison can flip on a one-tile difference, so the
	 * fresh plan is not necessarily the old plan minus the ground covered: walking from
	 * Taverley to Varrock with three stops left, the guide's hint flipped mid-walk from the
	 * drawn line to "Cast Teleport to House ... via Grand Exchange Portal". Reported from
	 * play, twice — the first fix stopped <i>our</i> re-asking on a region change
	 * ({@code GuideTracker.retargetIfMoved}), which left the router's own re-plan.
	 *
	 * <p>It cannot be stopped at the source. The {@code config} override a request carries
	 * only reaches the keys Shortest Path caches for drawing; {@code recalculateDistance} and
	 * {@code cancelInstead} are read straight off the user's own settings on the tick, so
	 * there is no per-request way to ask for a route it will leave alone. (A player who wants
	 * the drawn line to stop moving too can set that setting to -1, which switches its
	 * recalculation off entirely.) So the plan is held here instead.
	 *
	 * <h2>How "still following it" is judged</h2>
	 *
	 * Not by distance from the line: the message carries only the hops, never the path, so we
	 * do not have the line to measure against. By ground made instead — the player is
	 * following the plan while they are no further from it than the closest they have been,
	 * give or take {@link #PLAN_ABANDONED_TILES}. That reads a walk to a target and a walk
	 * back to a fairy ring the plan wants taken as the same thing, which they are: progress
	 * towards the plan. Someone who has genuinely left it — walked off to a bank, ended up
	 * somewhere the leg does not go — loses ground and gets the re-plan.
	 *
	 * <p>Real jumps are not this method's problem and must not be: a teleport is a region
	 * change of more than a tick's running, and the guide asks for a fresh route itself when
	 * it sees one. An asked-for answer is never declined here — see the {@code unsolicited}
	 * test at the call site, which covers the whole of the request window.
	 */
	private boolean stillFollowingThePlan()
	{
		return groundLost() >= 0;
	}

	/**
	 * How far the player has fallen back from their closest approach to the accepted plan, or
	 * -1 when that is not a question this can answer — no plan accepted yet, no tick seen, or
	 * nothing to measure against. Unanswerable means the message is read, as it was before.
	 */
	private int groundLost()
	{
		WorldPoint player = playerTile;
		int best = nearestApproach;
		if (!routeAnswered || player == null || best == Integer.MAX_VALUE)
		{
			return -1;
		}
		int now = approachDistance(player);
		if (now == Integer.MAX_VALUE)
		{
			return -1;
		}
		int lost = now - best;
		return lost <= PLAN_ABANDONED_TILES ? Math.max(lost, 0) : -1;
	}

	/**
	 * How near the player is to the plan: the distance to the closest thing the plan is made
	 * of — a target it is heading for, or either end of one of its hops.
	 *
	 * <p>Both ends of every hop <b>still ahead</b>, and every target. The plan is a journey
	 * between known points with unknown line between them, and being near any of them is being
	 * on it — which is what makes a walk to a fairy ring sixty tiles the wrong way count as
	 * progress rather than as leaving. Hops already taken are dropped, or the anchor nearest
	 * the player would be the one behind them for the rest of the leg; see
	 * {@link #noteProgress}, which starts the closest approach over when that set changes.
	 *
	 * <p>{@code distanceTo2D}, so a staircase inside a building on the way is not a departure
	 * from the plan — plain {@code distanceTo} answers "infinitely far" across planes.
	 */
	private int approachDistance(WorldPoint player)
	{
		int nearest = Integer.MAX_VALUE;
		for (WorldPoint target : requestedTargets)
		{
			if (target != null)
			{
				nearest = Math.min(nearest, player.distanceTo2D(target));
			}
		}
		List<Hop> route = hops;
		for (int i = hopsPassed; i < route.size(); i++)
		{
			Hop hop = route.get(i);
			if (hop.origin != null)
			{
				nearest = Math.min(nearest, player.distanceTo2D(hop.origin));
			}
			if (hop.destination != null)
			{
				nearest = Math.min(nearest, player.distanceTo2D(hop.destination));
			}
		}
		return nearest;
	}

	/**
	 * Says once, per plan, that a re-plan was turned down.
	 *
	 * <p>Once, because the router re-plans every tick the player is off its line and would
	 * otherwise fill the log with the same sentence. At info, because the one thing this
	 * costs is visible on screen and the log is where the explanation has to be: Shortest
	 * Path draws its own new line whatever we do with the message, so the map can show a line
	 * to the house while the guide keeps saying walk. Holding the instruction the player is
	 * following is the lesser of the two evils; knowing which one you are looking at is the
	 * consolation.
	 */
	private void declineReplan(PluginMessage event)
	{
		if (declineLogged == routeGeneration)
		{
			return;
		}
		declineLogged = routeGeneration;

		List<String> replan = readTransports(event);
		log.info("Keeping the leg's plan: Shortest Path re-planned from {}, {} tile(s) off the"
			+ " accepted plan; its plan would go {}", playerTile, groundLost(),
			replan.isEmpty() ? "on foot" : replan.get(0));
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
	 * One hop of the drawn route, as the transports message describes it: where it is taken,
	 * where it lands, and the object it goes through when it is an object at all.
	 */
	static final class Hop
	{
		@Nullable
		final WorldPoint origin;
		@Nullable
		final WorldPoint destination;
		/** The object's menu row with the id taken off — "Climb-over Crumbling wall" — or null for an item or spell. */
		@Nullable
		final String object;

		Hop(@Nullable WorldPoint origin, @Nullable WorldPoint destination, @Nullable String object)
		{
			this.origin = origin;
			this.destination = destination;
			this.object = object;
		}
	}

	/** Every hop of the current route, in path order. Volatile like the lists. */
	private volatile List<Hop> hops = new ArrayList<>();

	/**
	 * How many leading hops the player has already taken; see {@link #noteProgress}.
	 *
	 * <p>Only ever advanced, and only from the tick, until a new answer resets it.
	 */
	private volatile int hopsPassed;

	/**
	 * How near a hop's landing tile counts as having taken the hop.
	 *
	 * <p>Teleports land a tile or two off their nominal destination; shortcuts land exactly.
	 * Nearness alone would pass a wall crossing before it is crossed — the two sides are three
	 * tiles apart — so the player must also be nearer the landing than the departure.
	 */
	private static final int HOP_TAKEN_TILES = 5;

	/**
	 * Advances past the hops the player has already taken, so {@link #getNextTransportObject}
	 * points at what is still ahead.
	 *
	 * <p>The route's first hop is not the next thing to click for long: a plan that reads
	 * "Falador teleport, climb the crumbling wall" has the wall as its second hop, and the
	 * message describing it is not re-sent when the teleport lands unless the player strays
	 * from the drawn line. Reading only the first hop meant the agility shortcuts along a walk
	 * were never outlined at all — the first hop was a teleport, or a door, or the shortcut
	 * had been passed and a later one was up. Reported from play.
	 *
	 * <p>Once a tick, from the client thread, with the player's tile.
	 */
	public void noteProgress(@Nullable WorldPoint player)
	{
		if (player == null)
		{
			return;
		}

		List<Hop> route = hops;
		int passed = hopsPassed;
		// The furthest hop the player is standing at the far end of, so a spread of duplicate
		// rows for one edge — several Transport objects share an edge — passes as one.
		for (int i = passed; i < route.size(); i++)
		{
			if (taken(route.get(i), player))
			{
				passed = i + 1;
			}
		}
		boolean movedOn = passed != hopsPassed;
		if (movedOn)
		{
			hopsPassed = passed;
			routeGeneration++;
		}

		// The tick is also where the plan-holding rule gets its facts: the transports message
		// arrives off the client thread, so the player's tile has to have been read here
		// first. See stillFollowingThePlan.
		playerTile = player;
		int approach = approachDistance(player);
		// Taking a hop retires its anchors, and the plan is suddenly measured against points
		// further off — walking through the Taverley gate leaves the gate behind and Varrock
		// is what is left to be near. That is not ground lost, so the closest approach starts
		// again from here rather than from a number belonging to a hop already behind.
		if (movedOn || approach < nearestApproach)
		{
			nearestApproach = approach;
		}
	}

	static boolean taken(Hop hop, WorldPoint player)
	{
		if (hop.destination == null)
		{
			return false;
		}
		int toLanding = player.distanceTo(hop.destination);
		if (toLanding > HOP_TAKEN_TILES)
		{
			return false;
		}
		return hop.origin == null || toLanding < player.distanceTo(hop.origin);
	}

	/**
	 * The object the route's next hop goes through — "Spirit tree", "Climb-over Crumbling
	 * wall" — or null when the next hop is not an object (an item, a spell, or nothing at all).
	 *
	 * <p>For the overlay: the drawn line says where to walk, but nothing in the scene was
	 * marked as the thing to click when the hop was neither an item nor house furniture. The
	 * GE's spirit tree went entirely unhighlighted. Reported from play. "Next", not "first":
	 * see {@link #noteProgress}.
	 */
	@Nullable
	public String getNextTransportObject()
	{
		List<Hop> route = hops;
		int index = hopsPassed;
		return index < route.size() ? route.get(index).object : null;
	}

	/**
	 * The message's parallel per-hop lists zipped into hops.
	 *
	 * <p>Tolerant of ragged lists — a point list shorter than the object list, or the other
	 * way about — because the message is another plugin's and its shape is not ours to assume.
	 */
	private static List<Hop> readHops(PluginMessage event)
	{
		List<WorldPoint> origins = readPoints(event, KEY_ORIGIN);
		List<WorldPoint> destinations = readPoints(event, KEY_DESTINATION);
		Object objectInfo = event.getData().get(KEY_OBJECT_INFO);
		List<?> objects = objectInfo instanceof List ? (List<?>) objectInfo : new ArrayList<>();

		int count = Math.max(objects.size(), Math.max(origins.size(), destinations.size()));
		List<Hop> read = new ArrayList<>(count);
		for (int i = 0; i < count; i++)
		{
			String object = null;
			if (i < objects.size() && objects.get(i) instanceof String
				&& !((String) objects.get(i)).isEmpty())
			{
				String name = objectName((String) objects.get(i));
				object = name.isEmpty() ? null : name;
			}
			read.add(new Hop(i < origins.size() ? origins.get(i) : null,
				i < destinations.size() ? destinations.get(i) : null, object));
		}
		return read;
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
