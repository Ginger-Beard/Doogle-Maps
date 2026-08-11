package com.dooglemaps.guide;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.NotableHarvests;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.Seed;
import com.dooglemaps.data.SpadeClearedCrops;
import com.dooglemaps.bank.BankContents;
import com.dooglemaps.bank.TeleportItems;
import com.dooglemaps.route.PatchLocationStore;
import com.dooglemaps.route.RunPlanner;
import com.dooglemaps.route.RunStop;
import com.dooglemaps.route.ProtectionBudget;
import com.dooglemaps.route.SeedAllocation;
import com.dooglemaps.data.ProtectionPayment;
import java.util.Map;
import com.dooglemaps.state.BarbarianFarming;
import com.dooglemaps.state.CompostSelectionStore;
import com.dooglemaps.state.ContractState;
import com.dooglemaps.state.LeprechaunStore;
import com.dooglemaps.state.PatchSnapshot;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.state.PlantingGroups;
import com.dooglemaps.state.PlayerHouse;
import com.dooglemaps.state.ProtectionSelectionStore;
import com.dooglemaps.state.PlayerLocation;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.state.SeedSelectionStore;
import com.dooglemaps.timer.GrowthTimer;
import com.dooglemaps.timer.PatchProjection;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

/**
 * What guided mode is telling you to do right now.
 *
 * <p>One step at a time, for the patch you are standing at. The alternative — showing the
 * whole list for the whole stop — is the thing this is meant to replace: a plan you have to
 * read and keep your place in, rather than a thing to click.
 *
 * <p>Nothing here is stored between calls. {@link GuidePlan} derives a patch's steps from its
 * state, and this picks which patch is being asked about, so both are a function of the world
 * as it is. There is no progress to get out of step with a player who does things in their own
 * order.
 */
@Slf4j
@Singleton
public class GuideTracker
{
	private final RunPlanner planner;
	private final PatchLocationStore locations;
	private final PatchStateStore patches;
	private final GrowthTimer growthTimer;
	private final SeedInventoryStore seeds;
	private final SeedSelectionStore selection;
	private final CompostSelectionStore compost;
	private final CarriedItems carried;
	private final PlayerLocation playerLocation;
	private final LeprechaunStore leprechaun;
	private final BarbarianFarming barbarianFarming;
	private final BankContents bank;
	private final PlayerHouse house;
	private final PlantingGroups groups;
	private final ProtectionSelectionStore protection;
	private final com.dooglemaps.state.RunTypeStore runTypes;

	/** The assigned farming contract, which orders the guild stop and closes its own loop. */
	private final ContractState contracts;

	/** For the contract toggle, which is a suggestion rather than a rule. */
	private final com.dooglemaps.DoogleMapsConfig config;

	/** The game's own chatbox, for the one thing worth saying there. See contractNote. */
	private final net.runelite.client.chat.ChatMessageManager chat;

	/** The item the current route uses, for the panel's "how you will leave" line. */
	private final com.dooglemaps.bank.RouteItem routeItem;

	/** For the Magic level and the spellbook, which decide the Resurrect Crops reminder. */
	private final net.runelite.api.Client client;

	/** The crops a full-pack harvest spilled onto the ground, for the pick-up step. */
	private final DroppedProduce droppedProduce;

	/** Where banks are, for the contract seed fetch — is there one at this stop to send to. */
	private final com.dooglemaps.route.BankLocationStore bankLocations;

	/** Which daily teleports the game has said are spent, so none is offered as the way there. */
	private final com.dooglemaps.state.DailyTeleports dailyTeleports;

	@Inject
	GuideTracker(RunPlanner planner, PatchLocationStore locations, PatchStateStore patches,
		GrowthTimer growthTimer, SeedInventoryStore seeds, SeedSelectionStore selection,
		CompostSelectionStore compost, CarriedItems carried, PlayerLocation playerLocation,
		LeprechaunStore leprechaun, BarbarianFarming barbarianFarming, BankContents bank,
		PlayerHouse house, PlantingGroups groups, ProtectionSelectionStore protection,
		com.dooglemaps.state.RunTypeStore runTypes, com.dooglemaps.bank.RunLoadout loadout,
		ContractState contracts, com.dooglemaps.DoogleMapsConfig config,
		net.runelite.client.chat.ChatMessageManager chat,
		com.dooglemaps.bank.RouteItem routeItem, net.runelite.api.Client client,
		DroppedProduce droppedProduce, com.dooglemaps.route.BankLocationStore bankLocations,
		com.dooglemaps.state.DailyTeleports dailyTeleports)
	{
		this.dailyTeleports = dailyTeleports;
		this.bankLocations = bankLocations;
		this.droppedProduce = droppedProduce;
		this.client = client;
		this.routeItem = routeItem;
		this.chat = chat;
		this.contracts = contracts;
		this.config = config;
		this.loadout = loadout;
		this.runTypes = runTypes;
		this.protection = protection;
		this.groups = groups;
		this.house = house;
		this.bank = bank;
		this.leprechaun = leprechaun;
		this.barbarianFarming = barbarianFarming;
		this.planner = planner;
		this.locations = locations;
		this.patches = patches;
		this.growthTimer = growthTimer;
		this.seeds = seeds;
		this.selection = selection;
		this.compost = compost;
		this.carried = carried;
		this.playerLocation = playerLocation;
	}

	/**
	 * The outstanding steps, recomputed once a tick.
	 *
	 * <p>Cached here rather than in each overlay for two reasons. Working the steps out walks
	 * the run planner and the patch store, both synchronised, and the overlays render on the
	 * client thread while the panel reads on the Swing thread — doing that fifty times a second
	 * is pointless cross-thread lock traffic. And with two overlays now sharing the answer,
	 * caching in one of them would leave the other recomputing it.
	 */
	private final com.dooglemaps.bank.RunLoadout loadout;

	private volatile GuideStatus status = GuideStatus.idle();

	/**
	 * The patch being worked at this stop, by key, or null for "pick the nearest".
	 *
	 * <p>Only ever read and written from the tick handler, which is one thread. See
	 * {@link #chooseWorkingPatch}.
	 */
	@Nullable
	private String working;

	/** The region {@link #working} belongs to, so arriving somewhere new starts fresh. */
	private int workingRegion = -1;

	/** The region the player was in last tick, for noticing a teleport. */
	private int lastRegion = -1;

	@Subscribe
	public void onGameTick(GameTick event)
	{
		allocations.clear();
		compostWanting.clear();
		retargetIfMoved();

		// Before anything reads getRemaining(), so completion is judged against this tick's
		// answer rather than last tick's.
		reportIdlePatches();

		// The sidebar's answers for the current tickboxes, gathered here because this thread
		// already owns the planner's monitor. The panel reads the published copy lock-free;
		// see RunSnapshot for what this removes.
		planner.publishSnapshot(planner.snapshotFor(runTypes.getSelected()));

		List<GuideStep> steps = computeStepsHere();
		List<RunStop> remaining = planner.getRemaining();
		String destination = destinationName(remaining);

		// Only while travelling. Standing at a patch with work to do, the teleport is the last
		// thing anyone wants pointed at — the whole design is one instruction at a time.
		TravelHint hint = planner.isActive() && steps.isEmpty()
			? travelHint(destination)
			: null;

		WorldPoint player = playerLocation();
		// For the overlay's "Farm run - Falador" title, any stop of the run counts, not just
		// the remaining ones: stopAt() goes null the moment the stop completes (or was
		// skipped), so the place name vanished from the header while the player was still
		// standing there packing up. Reported from play. The step generation above keeps its
		// own, stricter stopAt.
		RunStop here = player == null ? null : stopAt(player);
		if (here == null && player != null)
		{
			for (RunStop stop : planner.getStops())
			{
				if (standingAt(stop, player))
				{
					here = stop;
					break;
				}
			}
		}

		status = new GuideStatus(steps, planner.isActive(), planner.isAtBankLeg(),
			remaining.size(), new ArrayList<>(planner.getCurrentTransports()),
			destination, hint, here == null ? null : here.getName(), supplyLines(),
			planner.isActive() ? contractNote(here) : null,
			planner.isActive() ? skipped : java.util.Collections.emptyList(),
			planner.isActive() && planner.isAtBankLeg()
				? planner.getSupplySources()
				: java.util.Collections.emptySet(),
			planner.isActive() ? routeItem.currentName() : null);

		routeFromTheFrontDoor();
	}

	/** Whether the front-door reroute below has already been asked for on this house visit. */
	private boolean houseStartPosted;

	/**
	 * Redraws the route from the house's exterior portal when the way out is on foot.
	 *
	 * <h2>The Prifddinas respawn point, and why the path started there</h2>
	 *
	 * A house is an instance, so the router cannot place the player standing in one — and
	 * Shortest Path's model of the way out is furniture this player may not have. Reported
	 * from play at a Prifddinas house: the route's one hop out was "Respawn Portal
	 * (Prifddinas)" — a portal-room portal Shortest Path assumes every house has — so the
	 * drawn path began at the respawn point in the middle of the city, while the player's
	 * actual next click was the exit portal, whose far side is the house portal across town.
	 *
	 * <p>The test is the same one the overlay uses to fall back to outlining the exit
	 * portal: hops were reported and none of them is served by furniture standing in this
	 * room. When that is true the route continues outside, so the journey's real start is
	 * the front door, and the router is asked again with exactly that start. When a hop
	 * <i>does</i> match the furniture — a nexus, a respawn portal that really is built —
	 * the route is followable from here and the drawn path is left alone... unless the
	 * front door opens into the destination's own neighbourhood, where walking out is the
	 * journey whatever the furniture offers; see the note at the check itself.
	 *
	 * <p>Once per visit, because the reroute changes the transports it is judged by:
	 * the fresh answer, planned from outside, may name overworld hops that happen to match
	 * garden furniture, and judging it again would ping-pong. Leaving the house arms it
	 * again.
	 */
	private void routeFromTheFrontDoor()
	{
		if (!planner.isActive() || !house.isInside())
		{
			houseStartPosted = false;
			return;
		}
		if (houseStartPosted || !status.getSteps().isEmpty())
		{
			return;
		}

		List<String> transports = status.getTransports();
		if (transports.isEmpty())
		{
			// The router has not answered yet - or is not installed, in which case there is
			// no drawn path to correct.
			return;
		}

		WorldPoint door = house.frontDoor();

		boolean served = !house.matchingFurniture(name ->
			transports.stream().anyMatch(hop -> HouseTeleports.furnitureServesHop(name, hop)))
			.isEmpty();

		// An EXPLICIT plan through the furniture is a stronger claim than "serves". The weak
		// serve is a destination-list coincidence — the Prifddinas nexus "served" a route
		// that never used it, which is what doorIsTheWay below exists to overrule. But a hop
		// that names the furniture ("Configure Fairy ring - C K Q") is the router routing
		// THROUGH it, and overruling that walked the player out the exit portal past the
		// garden ring their own route had picked. Reported from play, Aldarin.
		//
		// The router's own origins outrank both readings: a hop departing from inside the
		// POH area is house furniture by the router's word, whatever it is called. That is
		// the strongest evidence there is, and it needs no wording to match.
		boolean stronglyServed = planner.routeUsesHouseFurniture()
			|| !house.matchingFurniture(name ->
			transports.stream().anyMatch(hop -> HouseTeleports.furnitureNamedByHop(name, hop)))
			.isEmpty();

		// Serving furniture normally settles it — the route is followable from this room and
		// the drawn path is left alone. Not when the front door opens into the destination's
		// own neighbourhood: a Prifddinas house *is* the way to the Prifddinas patches, and the
		// nexus "serving" the hop — by carrying the same place name in its wiki destination
		// list — kept the path drawn from a respawn portal the player has not built. Reported
		// from play, at the same Prifddinas house as the fallback itself. Walking out is the
		// journey there, so the reroute wins; the fresh walk-only answer also clears the
		// transports, which is what hands the overlay to the exit portal.
		RunStop heading = destinationStop(planner.getRemaining());
		boolean doorIsTheWay = door != null && heading != null
			&& regionsTouch(door.getRegionID(), heading.getRegion().getRegionId());
		// A route through the house counts as served even when no furniture NAME matched -
		// the origins say the plan uses this room, and a wording gap must not walk the
		// player out the front door past it.
		if ((served || planner.routeUsesHouseFurniture()) && (stronglyServed || !doorIsTheWay))
		{
			houseStartPosted = true;
			// At INFO, once per visit (the latch above), because which way this decision went
			// has now been guessed at from symptoms twice. The reroute branch below has
			// always logged; the stay-put branch was silent, so a cleared hop list could not
			// be told apart from a reroute whose answer was walk-only.
			log.info("House furniture serves the route {} - keeping the drawn path "
				+ "(explicitly named: {}, door is the way: {})",
				transports, stronglyServed, doorIsTheWay);
			return;
		}

		if (door == null)
		{
			return;
		}

		houseStartPosted = true;
		log.info("The route's hops {} continue outside this house - redrawing the "
			+ "path from the front door at {}", transports, door);
		planner.retarget(door);
	}

	/**
	 * What the bank leg needs, or nothing when the run is not on it.
	 *
	 * <p>Built here rather than by the overlay because it walks the loadout, which walks the
	 * planner and both item stores — client-thread work, sampled once a tick like the rest of the
	 * snapshot. See {@link GuideStatus}.
	 */
	private java.util.List<String> supplyLines()
	{
		if (!planner.isActive() || !planner.isAtBankLeg())
		{
			return java.util.Collections.emptyList();
		}
		return com.dooglemaps.bank.LoadoutSummary.forItems(loadout.forRun(planner.coveredTypes()));
	}

	/**
	 * Everything the on-screen panel draws, as one consistent snapshot.
	 *
	 * <p>Sampled on the tick rather than read per frame — see {@link GuideStatus}.
	 */
	public GuideStatus getStatus()
	{
		return status;
	}

	/**
	 * The next thing to do, or null when there is nothing to say.
	 *
	 * <p>Null covers all the ordinary reasons for staying quiet: no run, still travelling,
	 * or standing among patches that are all growing.
	 */
	@Nullable
	public GuideStep getCurrentStep()
	{
		List<GuideStep> steps = status.getSteps();
		return steps.isEmpty() ? null : steps.get(0);
	}

	/**
	 * Everything outstanding at the stop you are standing in, nearest patch first.
	 *
	 * <p>The panel shows these so the stop reads as a short checklist rather than a single
	 * instruction with no sense of how much is left.
	 */
	public List<GuideStep> stepsHere()
	{
		return status.getSteps();
	}

	/** Forgets the current guidance, so a stopped run stops instructing immediately. */
	public void reset()
	{
		status = GuideStatus.idle();
		working = null;
		workingRegion = -1;
		lastRegion = -1;
		loggedErrandsAt = null;
		announcedBlock = null;
		announcedDud = null;
		lastNamedStop = null;
		skippedSteps.clear();
	}

	/**
	 * Steps the player has waved past, as {@code patchKey#ACTION}.
	 *
	 * <h2>Why a step can be skipped at all, when nothing here is stored</h2>
	 *
	 * Everything else in guided mode is derived from the world, deliberately, so that a player
	 * doing things out of order cannot get out of step with it. A skip is the one thing that
	 * <b>cannot</b> be derived: "I do not want to do this" is not a fact about the patch, and the
	 * world looks identical either way.
	 *
	 * <p>Kept as narrow as possible to limit the damage. It records the <i>action at a patch</i>
	 * rather than a position in a list, so it cannot drift the way a step index would — and it
	 * expires by itself, because the moment the patch changes state that action stops being
	 * generated and the entry simply never matches again. Cleared with the run.
	 *
	 * <p>Concurrent because the button is pressed on the Swing thread and the filter runs on the
	 * client thread.
	 */
	private final java.util.Set<String> skippedSteps =
		java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

	/**
	 * Passes over whatever is currently being asked for.
	 *
	 * <p>The escape hatch for the cases the guide cannot know about: a patch you have decided to
	 * leave, a tool you are not going to fetch, a payment you would rather not make. Without it the
	 * only way past a step you disagree with is to do it.
	 *
	 * <p>Skips the <i>step</i> rather than the patch, so the rest of that patch's work still
	 * appears — waving past "pay the farmer" should still leave you told to plant.
	 */
	public void skipCurrentStep()
	{
		GuideStep step = getCurrentStep();
		if (step == null)
		{
			return;
		}

		skippedSteps.add(keyOf(step));
		log.debug("Skipped {} at {}", step.getAction(), step.getPatch().getDisplayName());
	}

	/** Whether anything is currently being asked for, so the button can disable itself. */
	public boolean hasCurrentStep()
	{
		return getCurrentStep() != null;
	}

	/** Whether the router owes an answer, for the overlay's exit-portal patience. Lock-free. */
	public boolean routeAnswerPending()
	{
		return planner.isRouteAnswerPending();
	}

	/**
	 * Whether the skip button has a travel leg to act on instead of a step.
	 *
	 * <p>Travelling has no step — the instruction is the route — so the escape hatch used to
	 * switch off exactly when the thing being asked was a journey the player had reasons not to
	 * make. Reported from play, at Harmony: the location was being routed to and there was no
	 * way to say no to it. Only when the destination is unambiguous, the same rule the panel's
	 * "Travel to X" line follows — skipping a place the plugin cannot name would be a guess.
	 */
	public boolean canSkipTravel()
	{
		if (!planner.isActive() || getCurrentStep() != null)
		{
			return false;
		}
		// The supply leg is a destination too, and it used to be the one journey the skip
		// could not decline: every outstanding item on the withdraw list is cleared only by
		// withdrawing it, so a payment the player had decided against parked the run at the
		// bank with no way past. Skipping while at the bank leg waives the leg for the run.
		if (planner.isAtBankLeg())
		{
			return true;
		}
		return destinationStop(planner.getRemaining()) != null;
	}

	/**
	 * Drops the stop the drawn route is heading for from the rest of this run.
	 *
	 * <p>The whole location, deliberately, where {@link #skipCurrentStep} takes only the step:
	 * declining a journey means declining everything at the other end of it, and the next run
	 * offers the place again — the planner clears its skips with the stops.
	 */
	public boolean skipTravelDestination()
	{
		if (getCurrentStep() != null)
		{
			return false;
		}

		// The supply leg first: while it is on, the destination is a bank, and declining it
		// means "run without the withdrawals", not "drop a stop". See canSkipTravel.
		if (planner.isAtBankLeg())
		{
			log.debug("Skipped the supply leg");
			planner.waiveBankLeg();
			return true;
		}

		RunStop stop = destinationStop(planner.getRemaining());
		if (stop == null)
		{
			return false;
		}

		log.debug("Skipped travel to {}", stop.getName());
		planner.skipRegion(stop.getRegion().getRegionId());
		return true;
	}

	/**
	 * What a patch still wants, with anything waved past removed.
	 *
	 * <p>The single place skips are applied, because three separate questions depend on the answer
	 * and they have to agree: which patch is being worked, whether the stop can finish, and what
	 * the panel lists. Filtering in only one of them was the obvious first mistake — a skipped step
	 * would vanish from the panel and still hold the stop open forever.
	 */
	private List<GuideStep> outstandingFor(FarmPatch patch, RunStop stop)
	{
		List<GuideStep> steps = new ArrayList<>(stepsFor(patch, patchesWanting(stop, patch)));
		steps.removeIf(step -> skippedSteps.contains(keyOf(step)));
		return steps;
	}

	private static String keyOf(GuideStep step)
	{
		return step.getPatch().getKey() + "#" + step.getAction().name();
	}

	private List<GuideStep> computeStepsHere()
	{
		// Cleared first, and every tick, because it is a statement about the stop you are
		// standing in. Every early return below is a case where there is no such stop —
		// travelling, at the bank, not running — and leaving the last stop's words in place
		// would have the panel announcing a skipped patch two regions away. The planner's
		// exemptions are deliberately not cleared with it: those cover every stop and are
		// rebuilt each tick by reportIdlePatches, so wiping them here would resurrect a stop
		// that only completed because its patch was unworkable. See that method.
		skipped = new ArrayList<>();

		List<GuideStep> steps = new ArrayList<>();
		if (!planner.isActive() || planner.isAtBankLeg())
		{
			return steps;
		}

		WorldPoint player = playerLocation();
		if (player == null)
		{
			return steps;
		}

		RunStop stop = stopAt(player);
		if (stop == null)
		{
			// Between stops. The route overlay is already saying where to go, and repeating it
			// here would be a second voice giving the same instruction.
			//
			// Also the moment to forget which patch was being worked: arriving somewhere new
			// should pick the nearest thing there, not resume a patch two teleports away.
			working = null;
			appendLeavingErrandsAtFinishedStop(steps, player);
			return steps;
		}

		if (stop.getRegion().getRegionId() != workingRegion)
		{
			workingRegion = stop.getRegion().getRegionId();
			working = null;
		}

		List<FarmPatch> ordered = sortedByDistance(stop, player);

		// Everything the stop still wants, and separately the subset the run is willing to offer
		// right now. They differ only in the Farming Guild, and only while a contract is waiting on
		// something — see contractComesFirst.
		List<FarmPatch> offered = contractComesFirst(stop, ordered);
		offered = flowersAfterAllotments(offered, stop);

		FarmPatch first = chooseWorkingPatch(offered, stop);

		if (first != null)
		{
			steps.addAll(stepsFor(first, patchesWanting(stop, first)));
		}
		for (FarmPatch patch : offered)
		{
			if (!patch.getKey().equals(first == null ? null : first.getKey()))
			{
				steps.addAll(stepsFor(patch, patchesWanting(stop, patch)));
			}
		}

		// The contract holds the guild's other patches back so its own ground stays free and
		// the highlight uncontested — but held back must not mean walked past: a snape grass
		// ready to pick beside the contract patch was skipped outright, and so was a grown
		// avantoe. Reported from play. The withheld patches contribute their picking-shaped
		// work only — a harvest or a health check changes nothing the next contract could
		// want, where planting is exactly what the hold-back exists to prevent. Appended
		// after the contract's own steps, so the contract stays the current instruction.
		if (offered.size() < ordered.size())
		{
			for (FarmPatch patch : flowersAfterAllotments(ordered, stop))
			{
				if (offered.contains(patch))
				{
					continue;
				}
				PatchProjection projection = growthTimer.project(patch, patches.get(patch));
				if (projection != null
					&& (projection.hasProduceToPick() || projection.needsHealthCheck()))
				{
					steps.addAll(stepsFor(patch, patchesWanting(stop, patch), true));
				}
			}
		}

		// One visit, said once. A full pack raises "note this with the leprechaun" from every
		// patch still holding produce — each naming its own crop — and the panel repeated the
		// notice under every harvest at the stop. Reported from play. Only the first survives:
		// the list is re-derived every tick, so once that note is followed the next crop's note
		// surfaces by itself if the pack is somehow still full.
		collapseDuplicateNotes(steps);

		// Anything waved past, dropped before anyone sees the list. Done here rather than inside
		// stepsFor so the skip cannot leak into patchesWanting or the allocation — those are
		// statements about the world, and a skip is a statement about the player. After the
		// collapse above, so skipping the one visible note silences the notice instead of
		// promoting the next patch's copy of it.
		steps.removeIf(step -> skippedSteps.contains(keyOf(step)));

		// What this stop is passing over, in words for the panel. The planner's own exemptions
		// are handled separately and for every stop — see reportIdlePatches.
		announceSkips(stop, ordered);
		announceResurrectables(ordered);

		appendLeprechaunErrands(steps, stop);
		appendContractErrands(steps, stop);
		insertPickUpDrops(steps, stop, player);
		return steps;
	}

	/**
	 * The leaving errands at a stop that completed under your feet.
	 *
	 * <p>Planting the last patch is what completes a stop, and a freshly planted patch is not
	 * actionable — so the tick it happens, the stop leaves {@code getRemaining()}, {@code
	 * stopAt} goes null, and every "before moving on" errand vanished with it: note the crops,
	 * hand back the buckets. Reported from play. Same failure shape as the protection-payment
	 * bug, which was fixed by keeping the *patch* actionable; these errands belong to the stop,
	 * not a patch, so they are re-attached here instead.
	 *
	 * <p>Only at the stop that was just being worked — {@code workingRegion} — not any
	 * completed stop the player happens to walk back through later. The errands say "before
	 * moving on", and that claim is only true where the moving on is about to happen.
	 */
	private void appendLeavingErrandsAtFinishedStop(List<GuideStep> steps, WorldPoint player)
	{
		for (RunStop stop : planner.getStops())
		{
			// standingAt, not a region compare: the stop that just completed can have been
			// worked from a tile whose region id is the neighbour's — the guild's cactus
			// patch is the reported case — and the errands vanished there the moment the
			// stop did. Still anchored to workingRegion, so only the stop just being worked
			// qualifies, never a completed stop walked back through later.
			if (stop.getRegion().getRegionId() == workingRegion && standingAt(stop, player))
			{
				appendLeprechaunErrands(steps, stop);
				return;
			}
		}
	}

	/**
	 * How far from the player dropped crops still count as this stop's, in tiles.
	 *
	 * <p>Wide enough to cover walking from the patch to the leprechaun and back — Falador's
	 * flower patch to its leprechaun is well inside this — and narrow enough that overflow
	 * left at one stop cannot raise a step at the next.
	 */
	private static final int PICKUP_RADIUS = 20;

	/**
	 * Points back at the crops a full pack spilled onto the ground.
	 *
	 * <p>Bulk harvests — limpwurts are the reported case — hand over more items per pick than
	 * the pack has room for, and the game drops the change at your feet. The guide's own flow
	 * then walks you to the leprechaun to note, which frees the slots; without this step it
	 * walked straight on and the crops despawned behind it.
	 *
	 * <p>Placement follows the slots. With room in the pack the pick-up goes first — the
	 * despawn clock makes it the most urgent thing at the stop, and crops in the ground can
	 * wait where crops on the ground cannot. With no room it goes behind the first note step,
	 * because picking up into a full pack is not an instruction anyone can follow; if nothing
	 * here frees a slot, no step is raised and the record waits for one that does.
	 */
	private void insertPickUpDrops(List<GuideStep> steps, RunStop stop, WorldPoint player)
	{
		List<DroppedProduce.Drop> drops = droppedProduce.near(player, PICKUP_RADIUS);
		if (drops.isEmpty())
		{
			return;
		}

		GuideStep pickup = GuideStep.of(GuideAction.PICK_UP_DROPS, stop.getPatches().get(0),
			"Pick up the " + drops.get(0).getProduce().getContractName().toLowerCase()
				+ " your full pack dropped on the ground.");

		// Skippable like any other step: waving it past is the player saying the crops are
		// abandoned, and it must not come back every tick until they despawn. The stop-wide
		// removeIf runs before the errands are appended, so it is checked by hand here.
		if (skippedSteps.contains(keyOf(pickup)))
		{
			return;
		}

		if (carried.getFreeSlots() > 0)
		{
			steps.add(0, pickup);
			return;
		}

		int note = -1;
		for (int i = 0; i < steps.size(); i++)
		{
			if (steps.get(i).getAction() == GuideAction.NOTE_AT_LEPRECHAUN)
			{
				note = i;
				break;
			}
		}
		if (note >= 0)
		{
			steps.add(note + 1, pickup);
		}
	}

	/**
	 * Holds back the guild's other patches while the contract still wants something.
	 *
	 * <h2>Why the contract is not merely first but exclusive</h2>
	 *
	 * Being sorted to the front — which {@link #contractFirst} already does — is enough when the
	 * player follows the panel in order. It is not enough for what actually goes wrong, because
	 * every other guild patch is <i>also</i> being offered at the same time, the world outline can
	 * land on one of them, and acting on the wrong one is not a detour you can back out of.
	 *
	 * <p>A contract can only be grown in the Farming Guild, and the guild has exactly one patch of
	 * most types. So planting an ordinary crop in the patch a contract needs costs the whole
	 * contract until that crop finishes — days, for a tree. The two cases the player named:
	 *
	 * <ul>
	 *   <li>the contract's patch is <b>ripe</b> — harvest it, but do not replant it with the
	 *       ordinary seed, because the next contract may want that ground;
	 *   <li>the contract's patch is <b>empty</b> — do not fill it, for the same reason.
	 * </ul>
	 *
	 * <p>Both are prevented by the grouping, which keeps that patch out of its ordinary group for
	 * the whole contract cycle. This adds the ordering half: while there is contract business
	 * outstanding, the guild offers <b>only</b> the contract's own patch, so nothing else can be
	 * started first and nothing else competes for the highlight.
	 *
	 * <h2>Deliberately not a lock</h2>
	 *
	 * Held back, not cancelled. The moment the contract has nothing outstanding — planted and
	 * growing — the rest of the guild appears exactly as before. And a player who does not want the
	 * contract has two ways out that do not involve being stuck: switch it off in the settings, or
	 * press Skip step.
	 *
	 * <p>Completion is deliberately not filtered by this. {@link #reportIdlePatches} still sees
	 * every patch, so a stop cannot be reported finished merely because its work is being withheld.
	 */
	private List<FarmPatch> contractComesFirst(RunStop stop, List<FarmPatch> ordered)
	{
		if (!config.guideFarmingContracts()
			|| stop.getRegion().getRegionId() != ContractState.FARMING_GUILD_REGION)
		{
			return ordered;
		}

		List<FarmPatch> claimed = new ArrayList<>();
		for (FarmPatch patch : ordered)
		{
			if (contracts.claimsUntilHandedIn(patch))
			{
				claimed.add(patch);
			}
		}

		// Nothing assigned and nothing waiting: the errand is to ask Jane for one, which
		// appendContractErrands puts at the front of the list. Everything else is held back so the
		// contract she gives out has its patch still free to plant in.
		if (claimed.isEmpty())
		{
			return contracts.hasContract() ? ordered : java.util.Collections.emptyList();
		}

		// A contract patch with work left, or a reward still to collect, is the only thing on
		// offer. Once it is planted and growing both go quiet and the guild opens up again.
		boolean outstanding = contractToHandIn() != null;
		for (FarmPatch patch : claimed)
		{
			outstanding |= !outstandingFor(patch, stop).isEmpty();
		}

		if (!outstanding)
		{
			// Said out loud, once per state, because this branch firing wrongly is invisible
			// from in front of the client: the guild simply offers its herbs, and a contract
			// patch that still wants chopping reads as the plugin having wandered off.
			// Reported exactly that way — check health on the occupying magic tree, and the
			// step jumped to the avantoe. What each claimed patch actually reads as is the
			// evidence that decides where that went wrong.
			StringBuilder held = new StringBuilder();
			for (FarmPatch patch : claimed)
			{
				com.dooglemaps.state.PatchSnapshot snapshot = patches.get(patch);
				PatchProjection projection = growthTimer.project(patch, snapshot);
				held.append(patch.getDisplayName())
					.append(snapshot == null ? " never seen" : " varbit " + snapshot.getVarbitValue())
					.append(projection == null ? ", no projection"
						: " -> " + projection.getCropState()
							+ (projection.needsHealthCheck() ? " (check pending)" : "")
							+ (projection.isChoppable() ? " (choppable)" : "")
							+ (projection.isStump() ? " (stump)" : ""))
					.append("; ");
			}
			logOnce("Contract business is settled here, so the guild is open to every patch: "
				+ held);
		}
		return outstanding ? claimed : ordered;
	}

	/** The last thing said about the contract patch, so it is said once and not every tick. */
	private String lastContractDiagnostic;

	private void logOnce(String message)
	{
		if (!message.equals(lastContractDiagnostic))
		{
			lastContractDiagnostic = message;
			log.info("{}", message);
		}
	}

	/**
	 * Tells the planner which patches, at every stop, the guide has no step for.
	 *
	 * <h2>The run skips them — at every stop, not just this one</h2>
	 *
	 * A patch with no seed allocated is genuinely stuck: it wants planting, the run has nothing to
	 * put in it, and no amount of standing there changes that. Before this the stop simply never
	 * finished and the run sat with no route and no instruction, which reads as the plugin having
	 * frozen.
	 *
	 * <h2>Why every stop, every tick</h2>
	 *
	 * The first version of this ran only for the stop being stood in, and cleared the set the
	 * moment the player left — on the reasoning that it was a statement about "here". But the
	 * planner asks {@code isComplete} of <i>every</i> stop, every tick, and a stop that finished
	 * only because its patch was exempt became unfinished again the instant its exemption was
	 * wiped. The run would then route the player straight back to a patch it had already announced
	 * it was skipping, forever, and inflate the stops-remaining count on the way.
	 *
	 * <p>The predicate never needed the player to be present: {@link #outstandingFor} is a pure
	 * function of the patch stores, the allocation and what is carried or banked. So it is simply
	 * asked of every stop each tick — the same derived-not-stored rule {@code GuidePlan} states
	 * for itself, and the reason a patch that becomes doable again (the missing seed withdrawn,
	 * say) starts blocking completion on the very next tick with nothing to invalidate.
	 */
	private void reportIdlePatches()
	{
		if (!planner.isActive())
		{
			planner.setNothingToDo(java.util.Collections.emptySet());
			planner.setWithdrawOutstanding(false);
			// Cleared with the run, like the contract announcements: the next run's dead
			// crops are new news.
			announcedResurrect.clear();

			// Everything else that is scoped to "this run" is cleared here too — this branch
			// runs every tick the planner is inactive, which makes it the run boundary that
			// reset() never was. reset() is wired to plugin shutdown only, so these used to be
			// session-scoped in practice while their docs claimed run scope; a skipped
			// "pay the farmer" then silently planted that patch unprotected on every later
			// run of the session, and a skipped pick-up never offered again. A skip is a
			// statement about this run; the next run starts with none.
			skippedSteps.clear();
			announcedBlock = null;
			announcedDud = null;
			loggedErrandsAt = null;
			lastNamedStop = null;
			working = null;
			workingRegion = -1;
			return;
		}

		java.util.Set<String> idle = new java.util.LinkedHashSet<>();
		for (RunStop stop : planner.getStops())
		{
			for (FarmPatch patch : stop.getPatches())
			{
				if (outstandingFor(patch, stop).isEmpty())
				{
					idle.add(patch.getKey());
				}
			}
		}
		planner.setNothingToDo(idle);
		// The other half of the scope: the withdraw list's answer, pushed beside the blocked
		// set so the planner never has to ask the loadout — which is what removed the
		// construction cycle between the two.
		planner.setWithdrawOutstanding(loadout.anythingLeftToWithdraw(planner.coveredTypes()));
	}

	/**
	 * The withdraw list's answer for a run over these types, for the moment before it starts.
	 *
	 * <p>{@code RunPanel}'s start button needs the answer the planner used to compute for
	 * itself, and this tracker is the coordinator that holds both ends — the panel asks here,
	 * this asks the loadout, and the planner is handed the result. One question, one owner.
	 */
	public boolean withdrawListOutstanding(java.util.Set<PatchImplementation> types)
	{
		// The other half of RunPlanner's "Run planned" line, at the same moment and the same
		// level: that line says which seed sources the run trusts, this one says what the
		// loadout concluded from them — every item with its verdict, not just the fetches. A
		// bank leg that asks for the wrong things can only be judged with both halves, and
		// "what is it asking me to fetch" was being retyped from the panel by hand.
		java.util.List<String> verdicts = new java.util.ArrayList<>();
		for (com.dooglemaps.bank.LoadoutItem item : loadout.forRun(types))
		{
			StringBuilder verdict = new StringBuilder(item.getName()).append('=')
				.append(item.getNeed());
			if (item.getNeed() == com.dooglemaps.bank.LoadoutItem.Need.WITHDRAW)
			{
				verdict.append(" x").append(item.getWithdrawCount())
					.append(" from ").append(item.getFrom());
			}
			verdicts.add(verdict.toString());
		}
		log.info("Loadout for {}: {}", types, verdicts);

		return loadout.anythingLeftToWithdraw(types);
	}

	/** Resurrect Crops wants 78 Magic; the varbit value that means the Arceuus book is 3. */
	private static final int RESURRECT_MAGIC_LEVEL = 78;
	private static final int ARCEUUS_SPELLBOOK = 3;

	/**
	 * The crops the spell cannot save: grapes are not a valid target, and it fails on
	 * Hespori outright — both wiki-checked.
	 */
	private static final java.util.Set<PatchImplementation> NOT_RESURRECTABLE =
		java.util.EnumSet.of(PatchImplementation.GRAPES, PatchImplementation.HESPORI);

	/** Dead crops already spoken for, so the line lands once. Cleared with the run. */
	private final java.util.Set<String> announcedResurrect = new java.util.HashSet<>();

	/**
	 * Says, once per dead crop, that Resurrect Crops could save the clearing.
	 *
	 * <h2>Detected, not asked — and switchable off, because it is advice</h2>
	 *
	 * The reminder appears only when the Magic level makes the spell castable at all, and the
	 * wording knows which spellbook is equipped: on Arceuus it is "cast it", elsewhere it is
	 * "switch and cast it". Whether the swap is worth a seed is the player's call — some will
	 * never do it, which is why the whole thing sits behind
	 * {@code DoogleMapsConfig.resurrectCropsReminder}. The step list is untouched: the clear
	 * instruction stands, and this is the one chance to mention the alternative before the
	 * spade makes it moot.
	 */
	private void announceResurrectables(List<FarmPatch> ordered)
	{
		if (!config.resurrectCropsReminder()
			|| client.getRealSkillLevel(net.runelite.api.Skill.MAGIC) < RESURRECT_MAGIC_LEVEL)
		{
			return;
		}

		for (FarmPatch patch : ordered)
		{
			PatchProjection projection = growthTimer.project(patch, patches.get(patch));
			if (projection == null || projection.getCropState() != CropState.DEAD
				|| projection.getProduce() == null
				|| NOT_RESURRECTABLE.contains(patch.getImplementation())
				|| !announcedResurrect.add(patch.getKey()))
			{
				continue;
			}

			boolean onArceuus =
				client.getVarbitValue(net.runelite.api.gameval.VarbitID.SPELLBOOK)
					== ARCEUUS_SPELLBOOK;
			// Red, the whole line - a death notice, and the owner wants it read as one rather
			// than blending into the ordinary game chatter. An explicit colour tag instead of
			// ChatColorType.HIGHLIGHT, whose colour is whatever the user's chat settings say.
			chat.queue(net.runelite.client.chat.QueuedMessage.builder()
				.type(net.runelite.api.ChatMessageType.GAMEMESSAGE)
				.runeLiteFormattedMessage(new net.runelite.client.chat.ChatMessageBuilder()
					.append(java.awt.Color.RED,
						"Your dead " + projection.getProduce().getName().toLowerCase()
						+ (onArceuus
							? " could be revived with Resurrect Crops - one attempt, "
								+ "before you clear it."
							: " could be revived with Resurrect Crops if you switch to the "
								+ "Arceuus spellbook - one attempt, before you clear it."))
					.build())
				.build());
		}
	}

	/**
	 * Puts the skips worth explaining into words for the on-screen panel.
	 *
	 * <p>Skipping silently would be barely better than not skipping — you would reach the end of
	 * a run and find a patch untouched with no idea why. Only the reasons worth reading are
	 * worded: a patch that is merely growing produces no steps either, and saying "skipping the
	 * Catherby herb patch" about a crop doing exactly what it should would be noise.
	 *
	 * <p>Scoped to the stop being stood in, unlike {@link #reportIdlePatches} — the words are for
	 * the player's surroundings; the exemptions are for the whole run.
	 */
	private void announceSkips(RunStop stop, List<FarmPatch> ordered)
	{
		List<String> reasons = new ArrayList<>();

		for (FarmPatch patch : ordered)
		{
			if (!outstandingFor(patch, stop).isEmpty())
			{
				continue;
			}

			// Worth a word only when the patch is waiting on something the player does not have.
			// An empty patch with no seed is the case that strands runs; anything else with no
			// steps is simply finished or growing, and needs no explanation. "Waiting" implies
			// the run was going to plant it, so a group whose full line is not ticked has
			// nothing to explain either — nothing was ever going to go in.
			PatchProjection projection = growthTimer.project(patch, patches.get(patch));
			PlantingGroup group = groups.groupFor(patch);
			if (projection == null || !projection.isEmpty()
				|| !runTypes.isSelected(com.dooglemaps.data.RunOption.full(group)))
			{
				continue;
			}

			// The contract's own patch is contractNote's to explain, and it already does, in
			// better words — it says where the seed is *and* what to do about it. Both fired
			// at once at the guild: "Skipping farming guild - the cadantine seeds are in your
			// bank" stacked on top of "Your cadantine contract seed is in your bank..." said
			// the same fact twice in one panel, and the skip wording reads as skipping the
			// whole place because the guild's patches carry no disambiguator of their own.
			// Reported from play, at a fresh cadantine contract.
			if (group.isContract())
			{
				continue;
			}

			Seed chosen = allocationFor(group).seedFor(patch);
			if (chosen == null)
			{
				reasons.add("Skipping " + patch.getDisplayName().toLowerCase() + " - no seed.");
			}
			else if (!GuidePlan.seedAtHand(chosen, seeds))
			{
				// The run owns the seeds — the allocation checked — they are just not on this
				// trip. Saying where they are is what turns "the plugin skipped my patch" into
				// "I forgot to withdraw them". Reported from play, at Prifddinas, as a plant
				// instruction for seeds sitting in the bank.
				reasons.add("Skipping " + patch.getDisplayName().toLowerCase() + " - the "
					+ chosen.getName().toLowerCase()
					+ (chosen.isSapling() ? " sapling is " : " seeds are ")
					+ whereSeedsAre(chosen) + ".");
			}
		}

		skipped = reasons;
	}

	/** Where the seeds the pack lacks actually are, for the skip wording above. */
	private String whereSeedsAre(Seed seed)
	{
		if (seeds.getPlantable(seed, com.dooglemaps.state.SeedSource.BANK) > 0)
		{
			return "in your bank";
		}
		if (seeds.getPlantable(seed, com.dooglemaps.state.SeedSource.SEED_VAULT) > 0)
		{
			return "in the seed vault";
		}
		return "not in your pack";
	}

	/** Patches this stop is passing over, in words. Rebuilt each tick with the step list. */
	private List<String> skipped = new ArrayList<>();

	/**
	 * Guildmaster Jane, at the end of the Farming Guild stop.
	 *
	 * <p>Planting the contract is the middle of the job; these are both ends of it. Handing a
	 * finished one in is what turns a grown crop into seed packs and reputation, and taking the
	 * next one <i>before leaving</i> is what makes it plantable on this trip rather than in three
	 * days.
	 *
	 * <h2>Why last, and why that still allows the new one to be planted now</h2>
	 *
	 * Last, because a contract taken before the guild's patches are dealt with would have the run
	 * asking you to plant something into ground still holding the old crop. But "last" is not
	 * "after the stop is closed": nothing here is stored, so the moment the new contract lands in
	 * config the patch it wants moves into the contract group, {@link #contractFirst} pulls it to
	 * the front, and {@code GuidePlan} produces the plant step for it — all on the next tick, while
	 * you are still standing in the guild. The loop the plan asked for costs no machinery at all,
	 * because the whole guide is a function of the world rather than a position in a list.
	 *
	 * <p>Where that cannot happen — the patch is still occupied, or the run was never planned to
	 * visit it — {@link #contractNote} says so rather than leaving you to notice.
	 */
	private void appendContractErrands(List<GuideStep> steps, RunStop stop)
	{
		if (!config.guideFarmingContracts()
			|| stop.getRegion().getRegionId() != ContractState.FARMING_GUILD_REGION)
		{
			return;
		}

		FarmPatch anchor = stop.getPatches().get(0);

		Produce handIn = contractToHandIn();
		if (handIn != null)
		{
			// Front of the queue, not the back. These used to be appended after every patch at the
			// stop, on the reasoning that a contract taken early would ask you to plant into ground
			// still holding the old crop. That reasoning is about the *contract* patch, and
			// contractToHandIn already refuses while the crop is standing in it — so by the time
			// there is anything to hand in, the only thing between you and Jane is a row of herbs
			// that will still be there afterwards.
			//
			// It matters because the contract is a chain: hand in, take the next, plant it on this
			// same trip. Every guild patch done before the hand-in is a step further from starting
			// that chain, and the last link expires when you leave.
			steps.add(0, GuideStep.atNpc(GuideAction.HAND_IN_CONTRACT, anchor, handIn.getItemID(),
				ContractState.GUILDMASTER_JANE,
				"Hand your " + handIn.getName().toLowerCase()
					+ " to Guildmaster Jane for the contract reward."));
			return;
		}

		// A contract that is finished but not yet settled. hasContract() below cannot see one —
		// completion clears the assignment, which is the whole reason getAwaitingHandIn exists —
		// so without this the guide walked you to Jane to ask for a *new* contract while the last
		// one was still standing in the patch behind you, unharvested and unclaimed.
		//
		// Reported from play: check the health of the contract cactus, and the very next step is
		// Jane. It reads as the hand-in firing early and is not — it is the take-a-new-one step
		// jumping the queue, and Jane will not give one out until the last is settled anyway.
		if (contracts.getAwaitingHandIn() != null)
		{
			return;
		}

		// Only once there is nothing outstanding. Asking for a new contract while one is still
		// growing is not something Jane will do, and offering it would be the guide inventing an
		// interaction the game does not have.
		if (!contracts.hasContract())
		{
			// Also first. Taking the next contract is what puts its patch into the contract group,
			// which is what makes the seed appear in the loadout and the patch sort to the front —
			// all of which has to happen while you are still standing in the guild.
			steps.add(0, GuideStep.atNpc(GuideAction.TAKE_CONTRACT, anchor, -1,
				ContractState.GUILDMASTER_JANE,
				"Ask Guildmaster Jane for a new farming contract before you leave."));
			return;
		}

		GuideStep fetch = contractSeedFetch(stop);
		if (fetch != null)
		{
			// Front of the queue like the other contract errands. The seed is the contract's
			// whole blocker at this point, and fetching it is what turns the rest of the
			// guild's steps into "while you're at it" rather than "instead".
			steps.add(0, fetch);
		}
	}

	/**
	 * The step that fetches the contract's seed from storage at this stop, or null.
	 *
	 * <p>This used to be only a note — "your seed is in your bank; withdraw it" — which could
	 * not be the current instruction, could not be skipped, and sat in grey while the player
	 * stood a short walk from the guild's own bank chest with the run already asking them to
	 * dig at lesser patches. A fresh contract whose seed is banked <i>here</i> is a step, not
	 * a footnote. Asked for from play.
	 *
	 * <p>Only when following it leads somewhere: the contract's patch must be part of this
	 * stop and be ground a plant can actually reach this trip — empty, or a picked-clean
	 * spade-cleared crop the replant machinery already knows how to take out. Anything else
	 * standing there has its own answer (the blocked note, the dud note, or the patch's own
	 * steps), and a withdraw step in front of those would be a shopping trip for a seed with
	 * nowhere to go.
	 *
	 * <p>{@link #contractNote} asks this too, and goes quiet when it answers — the note and
	 * the step saying the same thing at once is the duplicate this replaces.
	 *
	 * <p>Deliberately does not ask {@link #contractToHandIn()}: both callers have already
	 * established there is nothing to hand in before getting here, and asking again is not
	 * free — its patch-evidence fallback <b>records</b> a completion it detects, so a second
	 * ask per tick is a second write. The awaiting flag is checked directly instead, which
	 * costs a config read and changes nothing.
	 */
	@Nullable
	private GuideStep contractSeedFetch(RunStop stop)
	{
		Produce contract = contracts.getContract();
		if (contract == null || contracts.getAwaitingHandIn() != null
			|| contractDudPatch() != null)
		{
			return null;
		}

		com.dooglemaps.data.PlantingGroup group =
			com.dooglemaps.data.PlantingGroup.contract(contract.getPatchImplementation());
		if (!runTypes.isSelected(com.dooglemaps.data.RunOption.full(group)))
		{
			return null;
		}

		Seed seed = contracts.getContractSeed();
		if (seed == null || GuidePlan.seedAtHand(seed, seeds))
		{
			return null;
		}

		FarmPatch target = null;
		for (FarmPatch patch : groups.patchesIn(group))
		{
			if (!stop.getPatches().contains(patch))
			{
				continue;
			}
			if (!outstandingFor(patch, stop).isEmpty())
			{
				// The trip already has clicks for this ground; the seed is not the blocker yet.
				return null;
			}

			PatchProjection projection = growthTimer.project(patch, patches.get(patch));
			if (projection == null || projection.getProduce() == contract)
			{
				// Unknown ground gets no shopping trip; the contract already planted needs none.
				return null;
			}
			if (!projection.isEmpty()
				&& !(projection.isReady()
					&& SpadeClearedCrops.isSpadeCleared(patch.getImplementation())))
			{
				// Occupied. Mid-growth is the blocked note's business; a picked-clean crop only
				// counts where a spade takes it straight out, because that is the one shape the
				// replant steps know how to clear — a spent fruit tree is finished ground.
				return null;
			}
			target = patch;
		}
		if (target == null)
		{
			return null;
		}

		String storage = contractSeedStorageHere(stop, seed);
		if (storage == null)
		{
			return null;
		}

		return GuideStep.of(GuideAction.FETCH_SEED, target,
			"Withdraw your " + contract.getName().toLowerCase() + " seed from the "
				+ storage + " here.");
	}

	/**
	 * The storage at this stop actually holding the contract seed, in words, or null.
	 *
	 * <p>"Here" is the claim the step makes, so it is only made where a bank the player can use
	 * shares the stop's region — seeded or learned, the same set the router trusts. The seed
	 * vault is the guild's own furniture and sits beside its bank chest, so it gets the same
	 * treatment under its own name.
	 */
	@Nullable
	private String contractSeedStorageHere(RunStop stop, Seed seed)
	{
		int region = stop.getRegion().getRegionId();
		if (seeds.getPlantable(seed, com.dooglemaps.state.SeedSource.BANK) > 0)
		{
			for (net.runelite.api.coords.WorldPoint bank : bankLocations.getUsableBanks())
			{
				if (bank.getRegionID() == region)
				{
					return "bank";
				}
			}
		}
		if (seeds.getPlantable(seed, com.dooglemaps.state.SeedSource.SEED_VAULT) > 0
			&& bankLocations.getSeedVault().getRegionID() == region)
		{
			return "seed vault";
		}
		return null;
	}

	/**
	 * The crop that is grown and unclaimed, or null.
	 *
	 * <p>Two sources, because the two failure modes are complementary and neither covers the other.
	 *
	 * <ul>
	 *   <li><b>What we captured.</b> The game's completion message is the only notice sent, and
	 *       Time Tracking wipes its own config key on the same message — so for a contract that
	 *       ripened while you were logged in, our capture is the <i>only</i> record that it did.
	 *   <li><b>What the patch shows.</b> A crop that finished growing while you were logged out
	 *       sent no message, so nothing captured it — but nothing cleared Time Tracking's key
	 *       either, and the patch itself is standing there fully grown.
	 * </ul>
	 */
	@Nullable
	private Produce contractToHandIn()
	{
		Produce awaiting = contracts.getAwaitingHandIn();

		if (awaiting == null)
		{
			// Finished while we were not watching, so no message was ever sent and nothing
			// recorded it — but the patch is standing there full, which is evidence no event can
			// be missed for.
			//
			// Recorded rather than merely returned. The crop is about to be harvested, and the
			// moment it is the patch stops showing a finished contract — so a detection that lives
			// only as long as the crop is standing would evaporate at exactly the point it becomes
			// useful, and the hand-in step would never appear. Writing it down is what carries the
			// fact across the harvest.
			Produce assigned = contracts.getContract();
			if (assigned == null || !isGrownInGuild(assigned))
			{
				return null;
			}
			contracts.recordCompleted();
			awaiting = assigned;
		}

		// Grown is not the same as picked, and this is the difference the step list has to
		// respect. A contract completes when the crop <i>finishes growing</i> — that is the moment
		// the game announces, and the moment Time Tracking clears its own key — but Jane wants the
		// produce, and it is still in the ground.
		//
		// Reported from play: a finished cactus, unharvested, and the guide asking for it to be
		// handed in. Following that means walking to Jane with nothing to give her, past the patch
		// holding the thing she wants.
		//
		// Returning null here does not lose the step; it defers it. The patch still owes you
		// something, so GuidePlan is already producing that step, and the hand-in reappears on the
		// tick after the last of it is done.
		return owesYouSomething(awaiting) ? null : awaiting;
	}

	/**
	 * Whether the contract patch has anything left to do at it — a check, or produce to pick.
	 *
	 * <h2>Not "is the plant still standing", which is what this used to ask</h2>
	 *
	 * The deferral above was written against {@link #isGrownInGuild}, and for a crop that is used
	 * up — a herb, an allotment — the two are the same question: pick it and the patch empties.
	 *
	 * <p>A crop that <b>regrows</b> never empties. A cactus, bush or fruit tree stays
	 * {@code HARVESTABLE} for the rest of its life, including when picked clean, so "still in the
	 * ground" is permanently true and the hand-in was deferred <i>forever</i> — a cactus contract
	 * could never be handed in at all. That is the same stock-of-zero trap
	 * {@link PatchProjection#hasProduceToPick} was written for, arrived at from the other side.
	 *
	 * <p>Asking what is <i>outstanding</i> gets both right, and gets the order right with it: while
	 * there is fruit on the plant the harvest step comes first and the hand-in waits behind it, and
	 * once there is not, Jane is the only thing left. Checking a potato cactus leaves it grown with
	 * a stock of zero — verified against the varbit table, where a freshly checked one reads
	 * HARVESTABLE at stage 0 and its cacti come back one every five minutes — so there is genuinely
	 * nothing to pick at that moment, and holding the hand-in back for produce that does not exist
	 * yet would strand the run.
	 */
	private boolean owesYouSomething(Produce contract)
	{
		for (FarmPatch patch : groups.patchesIn(
			com.dooglemaps.data.PlantingGroup.contract(contract.getPatchImplementation())))
		{
			PatchProjection projection = growthTimer.project(patch, patches.get(patch));
			if (projection == null || projection.getProduce() != contract)
			{
				continue;
			}
			if (projection.needsHealthCheck() || projection.hasProduceToPick())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a guild patch is standing there holding the finished contract crop.
	 *
	 * <p>Only ever asked to <b>detect</b> a completion nothing recorded. Whether the hand-in can
	 * happen yet is a different question with a different answer for regrowing crops; see
	 * {@link #owesYouSomething}.
	 *
	 * <h2>A varbit witness, not a clock</h2>
	 *
	 * This used {@code isReady()}, which is true for a crop merely <i>past its done estimate</i>
	 * — an extrapolation, not an observation. That mattered because the caller <b>persists</b>
	 * off this answer: {@code recordCompleted()} rewrites config and deletes the assignment
	 * record, so one tick of a wrong estimate — a patch not looked at since planting, a
	 * projection running ahead of the world — did permanent damage off evidence no one had
	 * seen. Now it takes the varbit's own word: {@code HARVESTABLE} actually decoded from the
	 * patch, or the check-health case, whose final stage never reads {@code HARVESTABLE} until
	 * checked and which {@link #owesYouSomething} holds the hand-in back for anyway — so a
	 * premature answer there defers rather than deceives. The completion the player was
	 * <i>online</i> for is caught by the game's own chat line in {@code ContractCapture};
	 * this fallback only needs to cover growth finished while logged out, and the player is
	 * then standing in the world where the varbit can actually be read.
	 *
	 * <p>Deliberately requires the produce to <i>match</i>. A dead herb reads as {@code ANYHERB}
	 * and a patch someone planted something else in is not a completed contract, so anything but an
	 * exact match is no answer at all.
	 */
	private boolean isGrownInGuild(Produce contract)
	{
		for (FarmPatch patch : groups.patchesIn(
			com.dooglemaps.data.PlantingGroup.contract(contract.getPatchImplementation())))
		{
			PatchProjection projection = growthTimer.project(patch, patches.get(patch));
			if (projection == null || projection.getProduce() != contract)
			{
				continue;
			}

			// Which state counts as evidence depends on how the crop's contract completes,
			// and the wiki is explicit about the split: a health-checked crop completes its
			// contract AT THE CHECK, anything else completes at the final harvest. For the
			// check-health families HARVESTABLE only exists after the check — so a
			// HARVESTABLE bush with no completion on record was checked BEFORE the contract
			// was taken, and the wiki's word is that such a crop can never satisfy it at
			// all ("plant a new seed and wait for it to grow"). Reading it as a completion
			// is exactly how a pre-checked poison ivy walked its owner to Jane with berries
			// she would not take. Reported from play. The logged-out case this fallback
			// exists for cannot produce it either: growth finishing offline leaves the crop
			// grown-but-unchecked, because the check is a click only a present player can
			// make. See contractDudPatch, which is where that state now gets its answer.
			boolean healthChecked = patch.getImplementation().isHealthCheckRequired();
			if (healthChecked
				? projection.needsHealthCheck()
				: projection.getCropState() == com.dooglemaps.data.CropState.HARVESTABLE)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The standing guild crop that matches the assigned contract but can never satisfy it,
	 * or null.
	 *
	 * <p>Wiki-checked: a contract crop whose <b>health check happened before the contract was
	 * assigned</b> is spent — the check is the completion event for its whole family, it fires
	 * once per planting, and harvesting what regrows changes nothing. The only way forward is
	 * to dig the crop up and plant a fresh one.
	 *
	 * <p>That state is identified exactly as {@link #isGrownInGuild} refuses it: a check-health
	 * crop standing {@code HARVESTABLE} (which only exists after the check) while nothing is
	 * recorded as awaiting hand-in (a check made <i>during</i> the contract announces itself in
	 * the chatbox, and that capture writes the record before this is ever asked). Harvest-class
	 * crops cannot be duds — their completion event empties the patch, so a standing crop is
	 * always still eligible.
	 *
	 * <p>No new steps hang off this. The run already knows how to replant a picked-clean bush or
	 * cactus — harvest, dig up, plant — and the contract machinery already allocates the seed;
	 * what was missing was only the refusal above and the explanation this feeds, in
	 * {@link #contractNote}.
	 */
	@Nullable
	private FarmPatch contractDudPatch()
	{
		Produce assigned = contracts.getContract();
		if (assigned == null || contracts.getAwaitingHandIn() != null
			|| !assigned.getPatchImplementation().isHealthCheckRequired())
		{
			return null;
		}

		for (FarmPatch patch : groups.patchesIn(
			com.dooglemaps.data.PlantingGroup.contract(assigned.getPatchImplementation())))
		{
			PatchProjection projection = growthTimer.project(patch, patches.get(patch));
			if (projection != null && projection.getProduce() == assigned
				&& projection.getCropState() == com.dooglemaps.data.CropState.HARVESTABLE)
			{
				return patch;
			}
		}
		return null;
	}

	/**
	 * What to say about a contract the run cannot deal with here, or null when there is nothing.
	 *
	 * <p>Shown rather than a step, because there is nothing to click. A contract whose patch is
	 * still occupied, or which the run was never planned to visit, is genuinely work for next time
	 * — and the honest thing is to say so, because from the player's side "the guide went quiet"
	 * and "there is nothing to do" look identical.
	 */
	@Nullable
	private String contractNote(@Nullable RunStop here)
	{
		if (!config.guideFarmingContracts() || here == null
			|| here.getRegion().getRegionId() != ContractState.FARMING_GUILD_REGION)
		{
			return null;
		}

		Produce contract = contracts.getContract();
		if (contract == null || contractToHandIn() != null)
		{
			return null;
		}

		// Said even while the steps are already walking the player through it, because the
		// steps alone read as vandalism: "dig up the poison ivy" at a healthy producing bush
		// needs its why. missingContractSeed stays quiet here — it treats the contract crop
		// being in the ground as "seed already spent", which is exactly wrong for a dud —
		// so the fresh seed is asked for in this wording instead.
		if (contractDudPatch() != null)
		{
			announceDudContract(contract);
			return "The " + contract.getName().toLowerCase() + " here was health-checked "
				+ "before the contract was taken, so it can never count for it. Dig it up "
				+ "and plant a fresh " + contract.getName().toLowerCase() + " seed.";
		}

		String missing = missingContractSeed(contract);
		if (missing != null)
		{
			return missing;
		}

		Produce blocking = null;
		for (FarmPatch patch : groups.patchesIn(
			com.dooglemaps.data.PlantingGroup.contract(contract.getPatchImplementation())))
		{
			if (here.getPatches().contains(patch) && !outstandingFor(patch, here).isEmpty())
			{
				// This trip can still deal with it, so the steps will say so themselves.
				return null;
			}

			PatchProjection projection = growthTimer.project(patch, patches.get(patch));
			if (projection != null && projection.getProduce() == contract)
			{
				// Already in the ground and growing. Nothing to do, and nothing to warn about.
				return null;
			}

			// Something else, mid-growth. Not work — the run walks past it — but the reason the
			// contract is going nowhere, and the one thing here the player might want to act on.
			if (projection != null && !projection.isEmpty() && !projection.isReady())
			{
				blocking = projection.getProduce();
			}
		}

		if (blocking == null)
		{
			// Which of two very different problems is it? The old wording blamed the checkbox
			// unconditionally, and the reported case was a ticked contract whose seed sat in
			// the bank — the note said "tick Farming contract" while the box was ticked, and
			// the honest answer ("it's in your bank") was only in the skip list beside it.
			com.dooglemaps.data.PlantingGroup group =
				com.dooglemaps.data.PlantingGroup.contract(contract.getPatchImplementation());
			Seed seed = contracts.getContractSeed();
			if (runTypes.isSelected(com.dooglemaps.data.RunOption.full(group))
				&& seed != null && !GuidePlan.seedAtHand(seed, seeds))
			{
				// Owned — missingContractSeed above already returned for "owns none" — just
				// not on you. Where the storage holding it is at this very stop, the fetch is
				// a real step on the panel and the note saying it too would be the duplicate
				// this used to produce. The note survives only for the stops that cannot act:
				// the seed is in a bank somewhere else, and saying where is all there is.
				if (contractSeedFetch(here) != null)
				{
					return null;
				}
				// A semicolon rather than " - ": the overlay wraps this at panel width, and a
				// break landing just before the dash opened the next line with "- withdraw",
				// which reads as another step in the checklist above it. Reported from play.
				return "Your " + contract.getName().toLowerCase() + " contract seed is "
					+ whereSeedsAre(seed) + "; withdraw it to plant the contract this trip.";
			}

			return "Your " + contract.getName().toLowerCase()
				+ " contract is not part of this run; tick Farming contract to include it.";
		}

		// Said in the chatbox as well as here, once. The panel note is only read by someone already
		// looking at the sidebar, and this is a decision — dig it up, or come back — that is worth
		// interrupting for. It is the only thing in the plugin that talks to the chatbox, and it
		// stays that way: a run that narrates itself there is a run nobody reads.
		announceBlockedContract(contract, blocking);

		return "Your " + contract.getName().toLowerCase() + " contract wants the patch the "
			+ blocking.getName().toLowerCase() + " is growing in. Dig it up to get on with the "
			+ "contract, or leave it and come back when it is ready.";
	}

	/**
	 * The contract we have already said is blocked, as {@code contract>blocker}.
	 *
	 * <p>Recomputed every tick like everything else here, so without this the message would go to
	 * the chatbox fifty times a minute for as long as the player stood in the guild. Keyed on both
	 * crops rather than a flag, so it speaks up again if either changes — a new contract, or someone
	 * clearing the patch and planting something different in it. Cleared with the run.
	 */
	@Nullable
	private String announcedBlock;

	private void announceBlockedContract(Produce contract, Produce blocking)
	{
		String key = contract.name() + ">" + blocking.name();
		if (key.equals(announcedBlock))
		{
			return;
		}
		announcedBlock = key;

		chat.queue(net.runelite.client.chat.QueuedMessage.builder()
			.type(net.runelite.api.ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(new net.runelite.client.chat.ChatMessageBuilder()
				.append(net.runelite.client.chat.ChatColorType.HIGHLIGHT)
				.append("Your " + contract.getName().toLowerCase() + " contract")
				.append(net.runelite.client.chat.ChatColorType.NORMAL)
				.append(" wants a patch with a " + blocking.getName().toLowerCase()
					+ " still growing in it. You can remove it to get on with the contract, or "
					+ "wait until it is ready.")
				.build())
			.build());
	}

	/** The contract already announced as a dud, so it is said once per run like the block. */
	@Nullable
	private String announcedDud;

	/**
	 * Says, once, that the standing crop cannot complete the contract.
	 *
	 * <p>A chat line as well as the panel note, same reasoning as the blocked contract: the run
	 * is about to ask for a healthy producing bush to be dug up, which without its why reads as
	 * the guide malfunctioning. The one situation in the contract cycle the game itself never
	 * explains — Jane just restates the assignment as if nothing were planted.
	 */
	private void announceDudContract(Produce contract)
	{
		if (contract.name().equals(announcedDud))
		{
			return;
		}
		announcedDud = contract.name();

		chat.queue(net.runelite.client.chat.QueuedMessage.builder()
			.type(net.runelite.api.ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(new net.runelite.client.chat.ChatMessageBuilder()
				.append(net.runelite.client.chat.ChatColorType.HIGHLIGHT)
				.append("Your " + contract.getName().toLowerCase() + " contract")
				.append(net.runelite.client.chat.ChatColorType.NORMAL)
				.append(" cannot be completed by the " + contract.getName().toLowerCase()
					+ " already growing here - its health was checked before the contract was "
					+ "taken. Dig it up and plant a fresh one.")
				.build())
			.build());
	}

	/**
	 * Says so when the contract wants a seed you have none of, or null when it does not.
	 *
	 * <h2>Why this is a note and not a step</h2>
	 *
	 * There is nothing to click. Buying is a trip somewhere else entirely and swapping is a
	 * dialogue choice the plugin will not make for you — so a step would sit at the top of the list
	 * unperformable, which is the arrangement {@link GuideStatus#getContractNote()} exists to
	 * avoid.
	 *
	 * <p>Silent once the crop is in the ground: at that point the seed has already been spent and
	 * saying you do not own one is both true and useless.
	 *
	 * <h2>Which route it names is a setting, deliberately</h2>
	 *
	 * Neither is free. Buying keeps the contract and its reward tier, and for most contract crops
	 * is simply unavailable to an ironman. Asking Jane works on any account but she swaps
	 * <i>downwards</i>, so it costs the tier — and an easy contract cannot be swapped at all, which
	 * is why that caveat is in the wording rather than assumed away.
	 *
	 * <p>Detecting the account type was considered and dropped: it would pick the cheaper-sounding
	 * route for a main who would rather keep a hard contract, and there is no way to know which
	 * they want. Asked once, in the settings, beats guessing every run.
	 */
	@Nullable
	private String missingContractSeed(Produce contract)
	{
		Seed seed = contracts.getContractSeed();
		if (seed == null || contractIsInTheGround(contract))
		{
			return null;
		}

		// Both forms count. Owning the seed but not the sapling is a different problem with a
		// different fix — a plant pot — and the loadout already says so.
		if (seeds.getOwned(seed) >= seed.getSeedsPerPatch())
		{
			return null;
		}

		String lead = "No " + seed.getName().toLowerCase() + " seed for the contract.";
		switch (config.contractSeedAdvice())
		{
			case BUY:
				return lead + " Buy one before you plant it, or ask Jane for an easier contract.";
			case ASK_FOR_EASIER:
				return lead + " Ask Jane for an easier contract - she swaps downwards, and an easy "
					+ "one cannot be swapped at all.";
			default:
				return lead;
		}
	}

	/** Whether the contract's crop is already growing in the patch it was assigned to. */
	private boolean contractIsInTheGround(Produce contract)
	{
		for (FarmPatch patch : groups.patchesIn(
			com.dooglemaps.data.PlantingGroup.contract(contract.getPatchImplementation())))
		{
			PatchProjection projection = growthTimer.project(patch, patches.get(patch));
			if (projection != null && projection.getProduce() == contract)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The things worth doing while you are stood at the leprechaun anyway.
	 *
	 * <p>Noting crops and handing back empty buckets are both free once you are there and both
	 * cost an inventory slot each for the rest of the run if forgotten. They used to be appended
	 * only at the <b>end</b> of a stop, on the reasoning that interrupting a harvest to tidy up is
	 * worse than the tidying is worth.
	 *
	 * <p>That reasoning holds right up until something else sends you to him — withdrawing
	 * compost, or a full pack. Then the walk has already been paid for, and telling you to make it
	 * again later is the plugin wasting your time. So when the current step is already at the
	 * leprechaun these move up behind it and you do the lot in one visit; otherwise they stay at
	 * the end, where they were.
	 *
	 * <p>Deliberately not merged into one step. Each is a separate click on him and the guide's
	 * whole idiom is one click per instruction — a combined "note and hand back" would be the
	 * only step in the plugin that meant two actions.
	 */
	private void appendLeprechaunErrands(List<GuideStep> steps, RunStop stop)
	{
		// A patch here still to be checked is a patch with more work behind it, and the guide knows
		// it: checking is never the last thing you do at a tree, bush or cactus — a harvest and a
		// clear follow, and neither exists as a step until the check happens.
		//
		// So "note your crops with the leprechaun before moving on" turned up directly beneath
		// "check the health of the magic tree", telling you to pack up in the middle of a patch you
		// had not started. Reported from play.
		//
		// Only when nothing is already taking you to him. A compost withdrawal makes the visit
		// certain whatever else is outstanding, and bundling the errands into that trip is the
		// whole point of this method.
		if (firstLeprechaunStep(steps) < 0 && anythingStillToCheck(stop))
		{
			return;
		}

		List<GuideStep> errands = new ArrayList<>();
		appendNoteBeforeLeaving(errands, stop);
		appendReturnBuckets(errands, stop);

		// Never twice. A full pack already raises a note step of its own, and repeating it
		// directly underneath would read as two separate trips.
		errands.removeIf(errand -> containsAction(steps, errand.getAction()));

		// Just before the *first* leprechaun step, wherever it is in the list — not only when it
		// happens to be the current one. That was the bug: standing mid-harvest with a compost
		// withdrawal two steps away, the visit was already certain and the errands still went to
		// the bottom of the list, where the panel's four-line window never showed them.
		//
		// Inserting at the visit rather than at the top also keeps the harvest uninterrupted: you
		// finish the patch, and the noting appears as you set off for him.
		int visit = firstLeprechaunStep(steps);
		if (visit >= 0)
		{
			noteErrandBundle(stop, errands);

			// In front of the step that brought you here, not behind it. Both of these *free*
			// inventory slots and withdrawing compost *fills* them, so doing them the other way
			// round can leave you taking four buckets into a pack still holding four limpwurts.
			// Handing things over before taking things out is the order that always fits.
			//
			// ...unless the step that brought you here is itself a note. Then it outranks the
			// errands: noting frees a whole stack of slots where the bucket frees one, and
			// putting the bucket return in front of a full-pack note meant depositing the
			// bucket un-filled the pack by one slot, the note step vanished, and the guide
			// bounced you back to harvest a single watermelon before asking again. Reported
			// from play. So the errands go behind whatever noting is already at the visit —
			// same trip, right order.
			while (visit < steps.size()
				&& steps.get(visit).getAction() == GuideAction.NOTE_AT_LEPRECHAUN)
			{
				visit++;
			}
			steps.addAll(visit, errands);
		}
		else
		{
			steps.addAll(errands);
		}
	}

	/**
	 * Whether furniture in this room serves any hop of the current route — the same test the
	 * overlay outlines by and {@link #routeFromTheFrontDoor} judges by, asked here so the
	 * travel hint cannot contradict what is being outlined. Client thread only, like every
	 * furniture question.
	 *
	 * <p>The destination counts as well as the hops, mirroring the overlay's own fallback:
	 * the router's model has no nexus, so furniture that genuinely reaches this leg's
	 * destination can be absent from every hop — and the hint then named a route item while
	 * the overlay outlined the nexus, which is the contradiction this method exists to
	 * prevent. Reported from play, Stony basalt in the nexus.
	 */
	private boolean furnitureServesTheRoute(@Nullable String destination)
	{
		// The router's own origins first: a hop departing from inside the POH area is house
		// furniture by its word, no wording required. See RunPlanner.routeUsesHouseFurniture.
		if (planner.routeUsesHouseFurniture())
		{
			return true;
		}
		java.util.Collection<String> transports = planner.getCurrentTransports();
		if (!transports.isEmpty() && !house.matchingFurniture(name ->
			transports.stream().anyMatch(hop -> HouseTeleports.furnitureServesHop(name, hop)))
			.isEmpty())
		{
			return true;
		}
		return destination != null && !house.matchingFurniture(name ->
			HouseTeleports.furnitureServesHop(name, destination)).isEmpty();
	}

	/**
	 * Whether the router's plan goes through the house, for the overlay's exit-portal
	 * decision. Live from the planner, like {@link #liveTransports}.
	 */
	public boolean routeDepartsTheHouse()
	{
		return planner.routeUsesHouseFurniture();
	}

	/**
	 * The route's hops as they are <i>right now</i>, not as they were at the last tick.
	 *
	 * <p>For the overlay's furniture matching, and only that. The once-a-tick snapshot in
	 * {@code GuideStatus} is right for everything the Swing panel reads, but the router's
	 * answer arrives asynchronously mid-tick — and judging the exit-portal fallback against a
	 * snapshot taken while the question was still out lit the exit portal for the rest of the
	 * tick after the answer had already named the nexus. Live here, exactly as
	 * {@link #furnitureServesTheRoute} already reads it, so the overlay and the tracker agree
	 * within the same frame. Client thread only.
	 *
	 * <h2>The house keeps the last plan that entered it</h2>
	 *
	 * A house is an instance, and arriving in one is exactly when the router's answer goes
	 * away: it cannot path from inside, so the hop list that said "Teleport to House, Enter
	 * Catherby Portal" empties the moment the teleport lands. With nothing to match and the
	 * leg unnamed — a many-stop run does not know which stop the router had picked — every
	 * question the overlay asks came back empty, and the exit portal lit as the last resort
	 * while the player stood beside the nexus their own route had chosen. Reported from play,
	 * repeatedly. So the last hop list that planned <i>through</i> the house is kept, and
	 * answers for the house while the live list is empty; a fresh non-empty answer replaces
	 * it, and a plan that does not mention the house clears it.
	 */
	public List<String> liveTransports()
	{
		List<String> live = new ArrayList<>(planner.getCurrentTransports());
		if (!planner.isActive())
		{
			houseLegTransports = java.util.Collections.emptyList();
			return live;
		}

		if (!live.isEmpty())
		{
			houseLegTransports = mentionsTheHouse(live)
				? new ArrayList<>(live)
				: java.util.Collections.<String>emptyList();
			return live;
		}

		return house.isInside() ? houseLegTransports : live;
	}

	/** The remembered house-bound hop list; see {@link #liveTransports}. */
	private List<String> houseLegTransports = java.util.Collections.emptyList();

	/** Whether any hop names the house — the "Teleport to House" leg, in the router's words. */
	private static boolean mentionsTheHouse(List<String> hops)
	{
		for (String hop : hops)
		{
			if (hop != null && hop.toLowerCase().contains("house"))
			{
				return true;
			}
		}
		return false;
	}

	/** The object the route's first hop goes through, or null. Live, like the transports. */
	@Nullable
	public String routeObjectName()
	{
		return planner.getFirstTransportObject();
	}

	/** Where the first step that happens at the leprechaun is, or -1 if there is none. */
	private static int firstLeprechaunStep(List<GuideStep> steps)
	{
		for (int i = 0; i < steps.size(); i++)
		{
			if (steps.get(i).isAtLeprechaun())
			{
				return i;
			}
		}
		return -1;
	}

	/** The stop the errand bundle was last reported for, so it is said once per visit. */
	@Nullable
	private String loggedErrandsAt;

	/**
	 * Says what was bundled into a leprechaun visit, once per stop.
	 *
	 * <p>Added because "I went to him with four limpwurts and it never told me to note them" is
	 * not diagnosable after the fact: the errands are derived fresh every tick from what is in the
	 * pack, so by the time it is reported the evidence is gone. This records what was in there at
	 * the moment the decision was made, including when the answer was <b>nothing</b> — which is
	 * the case worth catching, since a missing prompt and a prompt that was never generated look
	 * identical from the outside.
	 */
	private void noteErrandBundle(RunStop stop, List<GuideStep> errands)
	{
		if (stop.getName().equals(loggedErrandsAt))
		{
			return;
		}
		loggedErrandsAt = stop.getName();

		List<String> names = new ArrayList<>();
		for (GuideStep errand : errands)
		{
			names.add(errand.getAction().name());
		}

		log.info("At the leprechaun in {}: bundling {}. Crops in the pack: {}.",
			stop.getName(), names.isEmpty() ? "nothing extra" : names, cropsCarried());
	}

	/** What harvested produce is in the pack, for the line above. */
	private String cropsCarried()
	{
		List<String> held = new ArrayList<>();
		for (Produce produce : Produce.values())
		{
			// The same filter the noting decision uses, so this line records that decision's
			// actual inputs — compost buckets in the pack are not crops he would note.
			if (!produce.isNotable())
			{
				continue;
			}
			int count = carried.getInventoryCount(produce.getItemID());
			if (count > 0)
			{
				held.add(count + " " + produce.getName().toLowerCase());
			}
		}
		return held.isEmpty() ? "none" : String.join(", ", held);
	}

	/**
	 * Keeps only the first "note this with the leprechaun" step in the list.
	 *
	 * <p>The note is raised per patch — each names the crop that patch would add to a pack with
	 * no room for it — but the trip it asks for is one trip, and a stop with four harvestable
	 * patches listed it four times. The first occurrence is the working patch's, which names the
	 * crop being harvested right now, so it is the copy worth keeping.
	 */
	private static void collapseDuplicateNotes(List<GuideStep> steps)
	{
		boolean seen = false;
		for (java.util.Iterator<GuideStep> it = steps.iterator(); it.hasNext(); )
		{
			if (it.next().getAction() == GuideAction.NOTE_AT_LEPRECHAUN)
			{
				if (seen)
				{
					it.remove();
				}
				seen = true;
			}
		}
	}

	private static boolean containsAction(List<GuideStep> steps, GuideAction action)
	{
		for (GuideStep step : steps)
		{
			if (step.getAction() == action)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The patch being worked right now — which is <b>not</b> simply the nearest one.
	 *
	 * <p>Reported from play: mid-harvest on the watermelons, walking to the far side of the area
	 * put you nearer the flower patch, and the highlight jumped to it. Half-harvesting a patch and
	 * then being pointed at a different one is worse than useless — you are moving <i>because</i>
	 * of the step you are on, most often to reach the leprechaun, and the guide reading that as a
	 * change of mind is the plugin arguing with the player.
	 *
	 * <p>So a patch is chosen once and kept until it has nothing left to ask for. Distance only
	 * decides the <i>opening</i> pick at a stop, which is what it is actually good for.
	 *
	 * <h2>The one piece of state in guided mode, and why it is allowed</h2>
	 *
	 * Everything else here is deliberately a pure function of the world — see {@link GuidePlan} —
	 * and this does not weaken that. What each patch wants is still derived fresh every tick;
	 * what is remembered is only <i>which patch is being asked about</i>, which is genuinely a
	 * fact about the session rather than about the world. It also cannot get stuck: the moment
	 * the remembered patch has no steps left it is dropped, so finishing one moves on by itself,
	 * and doing them out of order is still fine because a patch someone else finished simply
	 * stops producing steps.
	 */
	/**
	 * Holds a grown flower's pick back until the allotments beside it are done.
	 *
	 * <p>A fully grown flower is the allotments' disease protection — wiki-checked, and the
	 * moment it is picked the guard is gone while the crops beside it still stand. So a
	 * flower whose only outstanding work is picking moves to the back of the offer, behind
	 * the allotments it is guarding; picked last, it comes straight back as the replant that
	 * guards the next cycle. A flower that needs <i>planting</i> keeps its place, because
	 * getting the guard up early is the point.
	 *
	 * <p>Deferral, deliberately, not re-sorting — the sanctioned tool under design principle
	 * #9. The sticky working patch is untouched: a flower already being worked stays worked.
	 */
	private List<FarmPatch> flowersAfterAllotments(List<FarmPatch> offered, RunStop stop)
	{
		boolean allotmentWork = false;
		for (FarmPatch patch : offered)
		{
			if (patch.getImplementation() == PatchImplementation.ALLOTMENT
				&& !outstandingFor(patch, stop).isEmpty())
			{
				allotmentWork = true;
				break;
			}
		}
		if (!allotmentWork)
		{
			return offered;
		}

		List<FarmPatch> reordered = new ArrayList<>();
		List<FarmPatch> held = new ArrayList<>();
		for (FarmPatch patch : offered)
		{
			if (patch.getImplementation() == PatchImplementation.FLOWER
				&& pickOnly(outstandingFor(patch, stop)))
			{
				held.add(patch);
			}
			else
			{
				reordered.add(patch);
			}
		}
		reordered.addAll(held);
		return reordered;
	}

	/** Whether these steps are only the pick (and its noting) - nothing that plants or clears. */
	private static boolean pickOnly(List<GuideStep> steps)
	{
		if (steps.isEmpty())
		{
			return false;
		}
		for (GuideStep step : steps)
		{
			if (step.getAction() != GuideAction.HARVEST
				&& step.getAction() != GuideAction.NOTE_AT_LEPRECHAUN)
			{
				return false;
			}
		}
		return true;
	}

	@Nullable
	private FarmPatch chooseWorkingPatch(List<FarmPatch> ordered, RunStop stop)
	{
		if (working != null)
		{
			for (FarmPatch patch : ordered)
			{
				if (patch.getKey().equals(working) && !outstandingFor(patch, stop).isEmpty())
				{
					return patch;
				}
			}
		}

		// A finished allotment hands the baton to its twin before anything nearer. The
		// allotments come as a pair everywhere they appear, and working them back to back is
		// what lets one trip to the leprechaun note both harvests — settled with the owner,
		// like the stickiness above. Only the handoff is special-cased: the opening pick at a
		// stop is still simply the nearest patch with work.
		if (working != null)
		{
			FarmPatch finished = patchIn(ordered, working);
			if (finished != null
				&& finished.getImplementation() == PatchImplementation.ALLOTMENT)
			{
				for (FarmPatch patch : ordered)
				{
					if (patch.getImplementation() == PatchImplementation.ALLOTMENT
						&& !patch.getKey().equals(working)
						&& !outstandingFor(patch, stop).isEmpty())
					{
						working = patch.getKey();
						return patch;
					}
				}
			}
		}

		for (FarmPatch patch : ordered)
		{
			if (!outstandingFor(patch, stop).isEmpty())
			{
				working = patch.getKey();
				return patch;
			}
		}

		working = null;
		return null;
	}

	@Nullable
	private static FarmPatch patchIn(List<FarmPatch> patches, String key)
	{
		for (FarmPatch patch : patches)
		{
			if (patch.getKey().equals(key))
			{
				return patch;
			}
		}
		return null;
	}

	/**
	 * The run's seed assignment for a group, rebuilt each tick.
	 *
	 * <p>Cached for the tick because several patches at a stop ask for it and building it walks
	 * the availability and seed stores. Not cached longer: as patches are planted their seeds and
	 * payments leave the stores, and the next allocation continues from what remains — which is
	 * what keeps it correct without anything having to be remembered.
	 */
	private SeedAllocation allocationFor(PlantingGroup group)
	{
		SeedAllocation cached = allocations.get(group.getKey());
		if (cached != null)
		{
			return cached;
		}

		List<FarmPatch> plantable = new ArrayList<>();
		for (FarmPatch patch : groups.patchesIn(group))
		{
			PatchProjection projection = growthTimer.project(patch, patches.get(patch));
			if (projection != null && (projection.isEmpty()
				|| projection.getCropState() == CropState.HARVESTABLE
				|| projection.getCropState() == CropState.DEAD))
			{
				plantable.add(patch);
			}
		}
		// The spirit tree cap, applied here and in the loadout's copy through the same
		// class, so the two allocations keep agreeing - see SpiritTrees.
		plantable = com.dooglemaps.state.SpiritTrees.trimToCap(patches,
			seeds.getFarmingLevel(), group, plantable);

		Map<Seed, Integer> owned = new java.util.HashMap<>();
		Map<Integer, Integer> payments = new java.util.HashMap<>();
		for (Seed seed : selection.getSelectedFor(group))
		{
			owned.put(seed, seeds.getOwnedPlantable(seed));

			ProtectionPayment payment = ProtectionPayment.forSeed(seed);
			if (payment != null && protection.isProtecting(group, seed))
			{
				payments.put(payment.getItemID(),
					bank.getCount(payment.getItemID())
						+ carried.getCountIncludingNoted(payment.getItemID()));
			}
		}

		SeedAllocation allocation = SeedAllocation.forPatches(plantable,
			selection.getSelectedFor(group), owned, seeds.getFarmingLevel(),
			new ProtectionBudget(payments, seed -> protection.isProtecting(group, seed)));

		allocations.put(group.getKey(), allocation);
		return allocation;
	}

	/** Allocations built this tick, cleared at the start of the next. */
	private final Map<String, SeedAllocation> allocations = new java.util.HashMap<>();

	// A public stepsFor(patch) overload lived here, documented for a panel's per-patch view
	// that was never built. It read the tick-scoped allocations map with no synchronisation,
	// so the day a Swing caller arrived it would have raced onGameTick's clear() — a corrupt
	// HashMap at worst, a wrong allocation shown at best. Deleted rather than fixed: dead
	// code cannot be wrong, and a future panel wants the GuideStatus snapshot anyway.

	private List<GuideStep> stepsFor(FarmPatch patch, int patchesToTreat)
	{
		return stepsFor(patch, patchesToTreat, false);
	}

	/**
	 * As above, optionally forced to the harvest-shaped subset whatever the group's own run
	 * option says — for the guild patches the contract holds back, whose picking is offered
	 * while their planting is not.
	 */
	private List<GuideStep> stepsFor(FarmPatch patch, int patchesToTreat, boolean harvestShapedOnly)
	{
		PatchProjection projection = growthTimer.project(patch, patches.get(patch));
		if (projection == null)
		{
			return new ArrayList<>();
		}

		PatchSnapshot snapshot = patches.get(patch);
		// Resolved per patch, not per type: with protected herbs split out, the ranarr goes in
		// Weiss and the guam goes in Falador, and those are the same patch type.
		PlantingGroup group = groups.groupFor(patch);

		// The run's own allocation rather than a rule of this class's own. The two used to
		// disagree — the panel would budget three magics and three yews, and the guide would say
		// "plant magic" at all six trees, because it picked one seed for the whole type. Sharing
		// the allocation is what makes the guidance and the promise the same thing.
		Seed chosen = allocationFor(group).seedFor(patch);

		// Full treatment only for a group whose full line is actually ticked, not merely one
		// that is not harvest-only. The two differ for a patch standing at a stop while its
		// group has no line ticked at all — a contract hand-in re-groups the guild's old
		// contract patch back into its plain group mid-run, and if that group is unticked the
		// old rule read it as a full run and offered to dig up the picked-clean crop. Reported
		// from play: a poison ivy, harvested for its contract moments earlier, got a "dig it
		// up" step the player never asked for. Digging up a regrowing crop is only wanted
		// where the player has said "replant these" — the full tick — or where the contract
		// machinery says so through its own ticked line.
		boolean fullRun = runTypes.isSelected(com.dooglemaps.data.RunOption.full(group));

		// The allocation only names seeds for patches this trip can still plant, so for a crop
		// already in the ground `chosen` is null — and the protection question was being asked
		// of a null seed, which is never protected. That made the payment step unreachable for
		// exactly the case its own doc names: the tree that has just gone in. Reported from
		// play, on a contract tree. Ask about the crop actually standing there instead, and
		// stop asking once the farmer has taken the payment.
		Seed inGround = chosen != null || projection.getProduce() == null
			? chosen
			: Seed.forProduce(projection.getProduce());
		boolean alreadyPaid = snapshot != null && snapshot.isPatchProtected();
		return GuidePlan.forPatch(projection,
			snapshot == null ? null : snapshot.getCompost(),
			group, chosen, seeds, compost, carried, leprechaun, barbarianFarming,
			!alreadyPaid && protection.isProtecting(group, inGround),
			harvestShapedOnly || !fullRun, patchesToTreat);
	}

	// An expectedYield() helper lived here, feeding GuidePlan's pre-harvest seed-box nudge.
	// Both went together when the box settled on its plain rhythm — empty before planting,
	// fill before leaving — which needs no yield model at all.

	/**
	 * How many patches at this stop are waiting for the same compost as this one.
	 *
	 * <p>So the withdrawal can say "take 4" rather than "take some". Counted per <i>tier</i>,
	 * because a stop can mix them — ultra on the herbs and nothing on the hops is a normal way
	 * to farm, and both are patches here.
	 *
	 * <p><b>Counted once per stop-and-tier per tick</b>, not per asking patch. The answer is
	 * the same for every patch sharing a tier, and this used to be recomputed for each — a
	 * loop over the stop inside a loop over the stop, 169 projections a tick at the Farming
	 * Guild, and more once the idle report started asking for every stop. The projections
	 * are individually cheap since {@code GrowthTimer} gained its cache; the shape was the
	 * problem, and the cache below is what fixes the shape. Cleared with {@link #allocations}
	 * at the top of the tick, for the same reason it is.
	 */
	private final Map<String, Integer> compostWanting = new java.util.HashMap<>();

	private int patchesWanting(RunStop stop, FarmPatch patch)
	{
		CompostTier tier = compost.get(groups.groupFor(patch));
		if (tier == CompostTier.NONE)
		{
			return 0;
		}
		return compostWanting.computeIfAbsent(
			stop.getRegion().getRegionId() + "#" + tier.name(),
			key -> countWanting(stop, tier));
	}

	private int countWanting(RunStop stop, CompostTier tier)
	{
		int count = 0;
		for (FarmPatch other : stop.getPatches())
		{
			if (compost.get(groups.groupFor(other)) != tier)
			{
				continue;
			}

			PatchProjection projection = growthTimer.project(other, patches.get(other));
			PatchSnapshot snapshot = patches.get(other);
			boolean alreadyTreated = snapshot != null && snapshot.getCompost() == tier;

			// Only patches that will actually be planted and are not already treated. A ripe
			// patch counts: it is about to be picked and will want compost straight after.
			if (projection != null && !alreadyTreated
				&& (projection.isEmpty() || projection.getCropState() == CropState.HARVESTABLE
					|| projection.getCropState() == CropState.DEAD))
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * Tells you to note what you are carrying before you leave a stop.
	 *
	 * <p>Harvested crops do not stack, so walking away with twenty-three watermelons is
	 * twenty-three slots gone for the rest of the run. The leprechaun turns each pile into one
	 * noted stack and he is standing right there, so it is close to free — but only if someone
	 * remembers, which is the entire point of a guide.
	 *
	 * <p>Appended after the patch work rather than woven into it, so it becomes the
	 * <i>current</i> step only once there is nothing left to do here. Suggesting it mid-harvest
	 * would interrupt the thing it is meant to tidy up after.
	 */
	/** Whether a patch at this stop is grown but unchecked, and so has work still to reveal. */
	private boolean anythingStillToCheck(RunStop stop)
	{
		for (FarmPatch patch : stop.getPatches())
		{
			PatchProjection projection = growthTimer.project(patch, patches.get(patch));
			if (projection != null && projection.needsHealthCheck())
			{
				return true;
			}
		}
		return false;
	}

	private void appendNoteBeforeLeaving(List<GuideStep> steps, RunStop stop)
	{
		int noteItem = 0;
		String noteName = null;
		int most = 0;

		for (Produce produce : Produce.values())
		{
			// What he will actually take, not merely a crop. Two rounds of this: the compost
			// tiers are Produce too, for the bin's sake, and scanning for isCrop() told a player
			// at Prifddinas to "note" the ultracompost they had just withdrawn — which hands it
			// back to him, which raised the withdrawal again, forever. Then logs, reported from
			// play: every tree family's Produce item IS its logs, and "Any type of logs" is on
			// his refusal list. See Produce.isLeprechaunNotable for the full set and the roots
			// that are still worth the trip.
			if (!produce.isLeprechaunNotable())
			{
				continue;
			}
			int held = carried.getInventoryCount(produce.getItemID());
			if (held > most)
			{
				most = held;
				noteItem = produce.getItemID();
				noteName = produce.getName().toLowerCase();
			}
		}

		// The harvests Produce cannot name — tree roots, and grimy herbs, which the enum
		// records clean. Even a single one is worth noting: none of them stack unnoted, so
		// the root dug here and the one dug two stops later are two slots, where the noted
		// pair is one. Loose grimy herbs also mean the herb sack is full, closed or absent —
		// an open sack swallows them before they reach the pack — so no sack check is needed.
		for (int itemId : NotableHarvests.ids())
		{
			int held = carried.getInventoryCount(itemId);
			if (held > most)
			{
				most = held;
				noteItem = itemId;
				noteName = NotableHarvests.nameOf(itemId);
			}
		}

		if (noteName == null)
		{
			return;
		}

		// No trailing "why" on this one - "unnoted crops cost a slot each" rode along for a
		// while and read as noise to the person seeing it every stop. Removed by request.
		steps.add(GuideStep.atLeprechaun(GuideAction.NOTE_AT_LEPRECHAUN,
			stop.getPatches().get(0), noteItem, null,
			"Note your " + noteName + " with the leprechaun before moving on."));
	}

	/**
	 * Tells you to hand the empty buckets back before leaving.
	 *
	 * <p>Composting from ordinary buckets leaves one empty per patch — four or five by the end
	 * of a stop, each costing a slot for the rest of the run. The leprechaun stores a thousand
	 * of them and is standing right there, so it is close to free and is what players do
	 * anyway; it just needs remembering, which is the whole job of a guide.
	 *
	 * <p>Last, after the noting, because both are leprechaun business and there is no sense
	 * walking to him twice.
	 */
	private void appendReturnBuckets(List<GuideStep> steps, RunStop stop)
	{
		int empties = carried.getInventoryCount(ItemID.BUCKET_EMPTY);
		if (empties == 0)
		{
			return;
		}

		// Dropping instead, by setting — and dropping is not a step at all. A step earns its
		// place by needing a moment chosen for it; a bucket can be dropped the instant it
		// empties, anywhere, so the guide stays quiet and the bucket itself carries the whole
		// instruction: always highlighted during a run, Drop always its left-click. Reported
		// from play against the first version, which put it in the step list. See
		// GuideMenuSwap and GuideInventoryOverlay.
		if (config.dropEmptyBuckets())
		{
			return;
		}

		steps.add(GuideStep.atLeprechaun(GuideAction.RETURN_BUCKETS,
			stop.getPatches().get(0), ItemID.BUCKET_EMPTY, null,
			"Give the leprechaun your " + empties + " empty bucket"
				+ (empties == 1 ? "" : "s") + " before moving on."));
	}

	/**
	 * Asks for a fresh route when the player has jumped somewhere.
	 *
	 * <p>Shortest Path recalculates its own line, but what it reported back to us was worked out
	 * from where the player used to be. So after a teleport the panel kept naming the tablet that
	 * had just been used, and the hops it listed were the old journey's.
	 *
	 * <p>Keyed on the region changing rather than on distance, which is what makes it a teleport
	 * test rather than a walking one — walking across a region boundary asks once more than
	 * strictly needed, and asking is cheap. Only while a run is actually under way.
	 */
	private void retargetIfMoved()
	{
		WorldPoint player = playerLocation();
		int region = player == null ? -1 : player.getRegionID();

		if (region != lastRegion)
		{
			lastRegion = region;
			if (planner.isActive())
			{
				planner.retarget();
			}
		}
	}

	/**
	 * The best way of getting to the current destination, and where that thing is.
	 *
	 * <p><b>Shortest Path's own pick first</b>, when it resolves to something clickable on the
	 * player. This method used to be built from {@link TeleportItems} alone, on the reasoning
	 * that the router's strings are for reading, not matching — but {@code RouteItem} has since
	 * learned to resolve them best-effort (items and now spells), and the route on screen is
	 * the best travel advice in the client: it planned the whole journey with the player's own
	 * transport settings. Highlighting our table's pick while the drawn line uses something
	 * else was the guide arguing with the map. Reported from play: "Route: Camelot teleport"
	 * with the spell nowhere highlighted.
	 *
	 * <p>When nothing of the route's resolves, the {@link TeleportItems} table takes over as
	 * before — carried beats banked beats neither, which is the order of how much work each
	 * costs. "Neither" is still worth returning: the portal nexus and the jewellery box get
	 * you places without owning any item, so a destination alone is enough to highlight
	 * something.
	 */
	@Nullable
	private TravelHint travelHint(@Nullable String destination)
	{
		// A nameless destination is not a hint-less leg. destinationStop only names the trip
		// when it can do so without guessing, and a null here used to drop the whole hint —
		// so a run too ambiguous to name travelled with no spell line, no item highlight and
		// no spellbook outline, while the via-lines happily listed the hops. Reported from
		// play: "via Teleport to House" on screen and nothing lit. The vehicle comes from the
		// route's hops, not from the name; only the destination-keyed extras (the nexus row,
		// the jewellery category) go quiet, and their overlays already null-guard.

		// The route's own pick can be a house teleport you have already taken: Shortest Path
		// words a hop like "Teleport to house via Catherby Portal", which resolves to the
		// tablet — and keeps resolving to it while you stand in the house it brought you to.
		// The tablet stayed lit in the pack, competing with the nexus the overlay was
		// outlining as the actual next click. Reported from play. Same rule as the universal
		// fallback below, but narrowed to house teleports: a carried Xeric's talisman is a
		// perfectly good hint from inside the house.
		boolean inHouse = house.isInside();

		// More generally: standing beside furniture that reaches this destination, the
		// furniture is the instruction — one click, no runes — and the overlay is already
		// outlining it, by exactly this test. The route's own hop still names its vehicle
		// ("via Trollheim Teleport"), and resolving that put a lit spellbook next to a lit
		// nexus: two contradictory instructions, with the nexus the one the player actually
		// wanted. Reported from play. A destination-only hint keeps the furniture the answer,
		// and still lights the right row once the nexus is open.
		if (inHouse && furnitureServesTheRoute(destination))
		{
			return new TravelHint(-1, null, destination, TravelHint.Where.UNOWNED);
		}

		TeleportSpell spell = routeItem.currentSpell();
		if (spell != null && !(inHouse && spell == TeleportSpell.TELEPORT_TO_HOUSE))
		{
			return TravelHint.bySpell(spell.getSpellName(), destination, spell.getComponent());
		}

		// Only when carried: a route item still in the bank is a detour, and the table below
		// already words banked teleports in the way the panel expects.
		int routed = routeItem.currentItemId();
		String routedName = routeItem.currentName();
		boolean routedHouseTeleport = routedName != null
			&& routedName.toLowerCase(java.util.Locale.ROOT).startsWith("teleport to house");
		if (routed != -1 && carried.has(routed) && !(inHouse && routedHouseTeleport))
		{
			return new TravelHint(routed, routedName, destination,
				TravelHint.Where.CARRIED);
		}

		TeleportItems.Teleport banked = null;
		// Back to front, like destinationStop: the last landing is where the journey ends,
		// and a hint should offer a teleport to there rather than to a bank passed through.
		List<WorldPoint> landings = new ArrayList<>(planner.getCurrentDestinations());
		java.util.Collections.reverse(landings);
		for (WorldPoint point : landings)
		{
			for (TeleportItems.Teleport teleport : TeleportItems.forRegion(point.getRegionID()))
			{
				// Only ones the player says they use. The bank stopped offering the rest when the
				// teleport list became a filter, and a hint naming something the loadout never
				// told you to bring is an instruction you cannot follow.
				if (!loadout.isOnTeleportList(teleport.getItemId()))
				{
					continue;
				}
				// Nor one the game has already refused for the day. This table's Ardougne
				// entry is the cloak's farm-patch teleport, which runs out — and with the
				// router's own pick skipped for the same reason, this fallback was the next
				// thing to say "use your Ardougne cloak". See DailyTeleports.
				if (dailyTeleports.isItemSpent(teleport.getItemId()))
				{
					continue;
				}
				if (carried.has(teleport.getItemId()))
				{
					return new TravelHint(teleport.getItemId(), teleport.getName(), destination,
						TravelHint.Where.CARRIED);
				}
				if (banked == null && bank.has(teleport.getItemId()))
				{
					banked = teleport;
				}
			}
		}

		if (banked != null)
		{
			return new TravelHint(banked.getItemId(), banked.getName(), destination,
				TravelHint.Where.BANK);
		}

		// Nothing lands next to the stop, so fall back to the teleports that get you somewhere
		// useful rather than somewhere specific — your house, from which the nexus and the
		// jewellery box are both highlighted. Last, deliberately: a direct teleport is always the
		// better instruction, and this should never displace one.
		// ...unless you are already standing in the house, in which case a house teleport is the
		// one thing that certainly will not help. Reported from play: arriving in the POH left the
		// tablet outlined and the panel still saying to use it, which is the plugin telling you to
		// go where you are. The furniture is what to click now, and the overlay is already
		// outlining it.
		if (!house.isInside())
		{
			for (TeleportItems.Teleport teleport : TeleportItems.universal())
			{
				// Only things that actually teleport you. A Dramen staff is carried so that a
				// fairy ring works; "use your Dramen staff" is not an instruction anyone can
				// follow.
				if (teleport.teleportsYou() && loadout.isOnTeleportList(teleport.getItemId())
					&& carried.has(teleport.getItemId()))
				{
					return new TravelHint(teleport.getItemId(), teleport.getName(), destination,
						TravelHint.Where.CARRIED);
				}
			}
		}

		return new TravelHint(-1, null, destination, TravelHint.Where.UNOWNED);
	}

	/**
	 * Which stop the drawn path is heading for, if that can be said without guessing.
	 *
	 * <p>The run hands Shortest Path every outstanding stop and lets it route to the cheapest,
	 * so the choice is made inside another plugin — but it reports a destination back with the
	 * path, and that is enough to name the place rather than only the hops.
	 *
	 * <p>Matched by region, and <b>only when exactly one stop matches</b>. What that destination
	 * contains is not documented: it may be the point the router settled on, or an echo of every
	 * target it was given. Requiring a unique match is correct under either reading — one match
	 * means the answer is unambiguous however it was produced, and anything else stays quiet.
	 * A wrong place name would send someone across the map, which is far worse than no name.
	 */
	@Nullable
	private String destinationName(List<RunStop> remaining)
	{
		RunStop found = destinationStop(remaining);
		return found == null ? null : found.getName();
	}

	/** The stop the route last unambiguously named, for the stretches the router says nothing. */
	@Nullable
	private RunStop lastNamedStop;

	/**
	 * The stop behind {@link #destinationName}, for the travel skip to act on. Same rule: only
	 * when unambiguous.
	 *
	 * <h2>The points are per-hop arrivals, and the last one is the journey's end</h2>
	 *
	 * Shortest Path's {@code destination} payload is one point per <b>transport</b> — where
	 * each hop lands, read straight from its path (verified in its source), and now kept in
	 * path order by the integration. So the honest reading is back to front: the final landing
	 * is where the route ends, and any earlier point is somewhere passed through — a
	 * bank-detour teleport enroute must not name the trip after the bank's town. Walked from
	 * the last point backwards, taking the first point that names a stop; the backward walk
	 * covers the house case, whose last hop lands wherever the exit drops you, a region away
	 * from the patches. A point whose region merely <i>touches</i> a stop's counts only when
	 * it touches exactly one — near two stops at once stays quiet, and an exact match always
	 * outranks a touching one at the same point.
	 */
	@Nullable
	private RunStop destinationStop(List<RunStop> remaining)
	{
		List<WorldPoint> points = new ArrayList<>(planner.getCurrentDestinations());
		for (int i = points.size() - 1; i >= 0; i--)
		{
			WorldPoint destination = points.get(i);
			RunStop near = null;
			boolean nearClash = false;
			for (RunStop stop : remaining)
			{
				if (stop.getRegion().getRegionId() == destination.getRegionID())
				{
					lastNamedStop = stop;
					return stop;
				}
				if (regionsTouch(stop.getRegion().getRegionId(), destination.getRegionID()))
				{
					nearClash |= near != null && !near.getName().equals(stop.getName());
					near = stop;
				}
			}
			if (near != null && !nearClash)
			{
				lastNamedStop = near;
				return near;
			}
		}

		// Inside the house the router often has nothing to say at all: the reroute from the
		// front door produces a pure walk, whose answer carries no transports and so no points.
		// The journey has not changed — only the wording went quiet — so the last name stands
		// while its stop is still outstanding. Only in the house, deliberately: an overworld
		// walking leg with no transports genuinely might be heading somewhere new.
		if (house.isInside() && lastNamedStop != null && remaining.contains(lastNamedStop))
		{
			return lastNamedStop;
		}
		return null;
	}

	/** Whether two region ids sit within one region of each other on the map grid. */
	private static boolean regionsTouch(int a, int b)
	{
		return Math.abs((a >> 8) - (b >> 8)) <= 1 && Math.abs((a & 0xFF) - (b & 0xFF)) <= 1;
	}

	/** The run stop the player is standing in, or null if they are between stops. */
	@Nullable
	private RunStop stopAt(WorldPoint player)
	{
		// Exact region matches first, so a stop can never lose its own region's ground to a
		// neighbour's edge tolerance.
		for (RunStop stop : planner.getRemaining())
		{
			if (stop.getRegion().getRegionId() == player.getRegionID())
			{
				return stop;
			}
		}
		for (RunStop stop : planner.getRemaining())
		{
			if (standingAt(stop, player))
			{
				return stop;
			}
		}
		return null;
	}

	/**
	 * How far past its region's edge a stop still claims the player, in tiles.
	 *
	 * <p>A stop is a 64x64 map region, and nothing pins its patches to the middle: the
	 * Farming Guild's cactus patch works out to sit close enough to the edge that standing
	 * on its far side put the player's region id one column over — and every region-keyed
	 * question answered "between stops" at once: the step list emptied, the note step went
	 * with it, and the leprechaun stopped highlighting until the player walked ~6 tiles back
	 * over an invisible line. Reported from play. Ten tiles covers standing anywhere around
	 * a patch while staying far too small to claim a player genuinely travelling away.
	 */
	private static final int STOP_EDGE_TILES = 10;

	/**
	 * Whether the player is standing at this stop, region edges notwithstanding: the stop's
	 * own region, or a touching one within {@link #STOP_EDGE_TILES} of one of its patches.
	 *
	 * <p>The distance needs a learned patch location, which is the right failure direction —
	 * a patch never walked up to has nothing to measure from, and also no work the player
	 * could be standing beside.
	 */
	private boolean standingAt(RunStop stop, WorldPoint player)
	{
		int region = stop.getRegion().getRegionId();
		if (region == player.getRegionID())
		{
			return true;
		}
		if (!regionsTouch(region, player.getRegionID()))
		{
			return false;
		}
		for (FarmPatch patch : stop.getPatches())
		{
			if (distance(player, patch) <= STOP_EDGE_TILES)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The stop's patches, closest first.
	 *
	 * <p>So that standing at Falador's herb patch talks about the herb patch, not about
	 * whichever allotment happened to be listed first.
	 *
	 * <p>This needs <b>real coordinates</b>, which is not what it had at first. Comparing
	 * regions was useless here: every patch at a stop shares a region by construction, so all
	 * of them tied and the "nearest" patch was simply the first in the list. Standing at one
	 * patch and being given instructions for another is most of what made the highlighting look
	 * random and look like it stopped working after the first harvest.
	 */
	private List<FarmPatch> sortedByDistance(RunStop stop, WorldPoint player)
	{
		// Every patch at the stop, deliberately including ones already touched this run. This
		// used to drop the stop's serviced set, which was safe while "serviced" meant "planted"
		// — but the capture layer now reports every varbit change, so the first harvest marked
		// the patch serviced and the guide crossed it off with the compost and replant still
		// undone: the step went straight from harvesting to travel, and the leprechaun errands
		// were the only voice left. Reported from play, at Troll Stronghold and the protected
		// herbs both. A patch with nothing left produces no steps anyway — outstandingFor is
		// derived from state — so there is nothing here for a finished patch to break.
		List<FarmPatch> ordered = new ArrayList<>(stop.getPatches());
		ordered.sort((a, b) -> Integer.compare(distance(player, a), distance(player, b)));
		contractFirst(ordered);
		return ordered;
	}

	/**
	 * Moves the patch an assigned contract has claimed to the front, whatever the distance says.
	 *
	 * <p>This is the one place in the run where order is load-bearing rather than convenient. A
	 * contract wants a specific patch type and the guild has exactly one of most of them, so any
	 * ordinary planting done first takes the only patch available — and the contract then waits a
	 * full growth cycle for a mistake that cost nothing to avoid.
	 *
	 * <p>Applied after the distance sort rather than instead of it, so everything else at the stop
	 * keeps the nearest-first order it had, and two contract patches — the guild's two allotments
	 * are the only case — stay in distance order relative to each other.
	 */
	private void contractFirst(List<FarmPatch> ordered)
	{
		// A finished contract still owns its patch. Guarding on hasContract() alone dropped the
		// priority the instant the crop completed — which is exactly when the patch matters most,
		// because picking it is what unlocks the hand-in and the hand-in unlocks the next
		// contract. See ContractState.claimsUntilHandedIn.
		if (!config.guideFarmingContracts()
			|| (!contracts.hasContract() && contracts.getAwaitingHandIn() == null))
		{
			return;
		}

		List<FarmPatch> claimed = new ArrayList<>();
		for (FarmPatch patch : ordered)
		{
			if (contracts.claimsUntilHandedIn(patch))
			{
				claimed.add(patch);
			}
		}

		if (!claimed.isEmpty())
		{
			ordered.removeAll(claimed);
			ordered.addAll(0, claimed);
		}
	}

	/**
	 * How far the player is from a patch.
	 *
	 * <p>Falls back to the region when the position has never been learned — a patch you have
	 * not walked up to yet has only its region's centre, which is honest but coarse. Sorting
	 * still works, since a patch with a real position beats one without.
	 */
	private int distance(WorldPoint player, FarmPatch patch)
	{
		WorldPoint location = locations.getLocation(patch);
		if (location == null || location.getPlane() != player.getPlane())
		{
			return Integer.MAX_VALUE;
		}
		return player.distanceTo(location);
	}

	/**
	 * Where the player is, from the cached sample.
	 *
	 * <p>Not asked of the client directly: this runs from the Swing thread when the panel
	 * refreshes as well as from the client thread when the overlay draws, and
	 * {@code getWorldLocation} asserts the latter.
	 */
	@Nullable
	private WorldPoint playerLocation()
	{
		return playerLocation.get();
	}
}
