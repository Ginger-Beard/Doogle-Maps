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

	/** The bin run's fill and ash choices, for the compost-bin steps. */
	private final com.dooglemaps.state.CompostRunStore compostRun;

	private final com.dooglemaps.data.ItemNames itemNames;

	/** The hespori run's bank leg, which replaces the withdraw list with the player's own gear. */
	private final com.dooglemaps.bank.InventorySetupsHandoff handoff;

	/** Which crops the player would rather buy a gardener out of clearing than chop themselves. */
	private final com.dooglemaps.state.PayToClearStore payToClear;

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
		com.dooglemaps.state.DailyTeleports dailyTeleports,
		com.dooglemaps.state.CompostRunStore compostRun,
		com.dooglemaps.data.ItemNames itemNames,
		com.dooglemaps.bank.InventorySetupsHandoff handoff,
		com.dooglemaps.state.PayToClearStore payToClear)
	{
		this.payToClear = payToClear;
		this.handoff = handoff;
		this.itemNames = itemNames;
		this.compostRun = compostRun;
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

	/**
	 * The player's tile as of the previous tick {@link #retargetIfMoved} ran, for telling a
	 * teleport apart from a walk that happens to cross a region boundary.
	 *
	 * <p>Null whenever last tick's tile was unknown — the LOADING tick of a teleport blanks the
	 * position store (see the null check in {@link #retargetIfMoved}) — so that the tile which
	 * follows it, wherever that turns out to be, is read as a jump rather than measured against
	 * a stale tile from before the teleport.
	 */
	@Nullable
	private WorldPoint lastPlayerTile;

	/**
	 * Tiles between two known ticks that no walk or run could cover, so seeing more than this
	 * means the player teleported. Running is the fastest non-teleport movement, at two tiles a
	 * tick; this leaves headroom above that rather than sitting on the exact limit.
	 */
	private static final int JUMP_TILES = 8;

	/**
	 * Derives the whole of this tick's guidance: the step list, the destination and the status
	 * the panel and the overlays read.
	 *
	 * <h2>Some of what this reads is one tick old, by construction</h2>
	 *
	 * The event bus orders same-priority subscribers by <b>fully-qualified</b> class name
	 * ({@code EventBus.register}, {@code thenComparing(s -> s.object.getClass().getName())}), so
	 * this plugin's tick runs in package order. Everything in {@code com.dooglemaps.capture} and
	 * {@code CarriedItems} therefore ticks <i>before</i> this — the patch scan, the seed and
	 * protection captures and the pack are all this tick's, which is what the step derivation
	 * needs and why the arrangement is worth knowing about rather than fixing.
	 *
	 * <p>What sorts <i>after</i> this is {@code com.dooglemaps.state}: {@code PlayerLocation},
	 * {@code LeprechaunStore} and {@code PlayerHouse}. So {@link #playerLocation()} — read here
	 * for {@code retargetIfMoved}, {@code noteTravelProgress}, {@code stopAt}, the drop pick-up
	 * and the leaving errands — is the tile as of the <b>previous</b> tick, and the leprechaun's
	 * tools are likewise one tick behind. That is 600ms and benign for every current caller, but
	 * it is real: within-stop ordering is nearest-first from that tile, so while the player walks
	 * between two close patches the sort input is somewhere they have already left.
	 *
	 * <p>The consequence for anyone editing: <b>the package a tick subscriber lives in is
	 * load-bearing</b>. Moving one between packages silently changes when it runs relative to
	 * this method. See {@code open-issues.txt}, where making the ordering explicit with
	 * {@code @Subscribe} priorities is recorded as deliberately deferred.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		allocations.clear();
		compostWanting.clear();
		retargetIfMoved();
		// Before the status snapshot below reads the route, so the object it points at is
		// this tick's next hop rather than one already taken.
		planner.noteTravelProgress(playerLocation());

		// Before anything reads getRemaining(), so completion is judged against this tick's
		// answer rather than last tick's.
		reportIdlePatches();

		// The sidebar's answers for the current tickboxes, gathered here because this thread
		// already owns the planner's monitor. The panel reads the published copy lock-free;
		// see RunSnapshot for what this removes.
		planner.publishSnapshot(planner.snapshotFor(runTypes.getSelected()));

		List<GuideStep> steps = computeStepsHere();
		List<RunStop> remaining = planner.getRemaining();

		// Resolved once and used twice: the name for the panel, and the region for the planner,
		// which stops re-deciding where the run is going once the route has named somewhere.
		// Pushed rather than read over there because destinationStop is the careful half of the
		// answer - Shortest Path's reply may name its pick or echo every target it was handed,
		// so a stop is named only when exactly one matches. See RunPlanner.commitDestination.
		RunStop heading = destinationStop(remaining);
		String destination = heading == null ? null : heading.getName();
		planner.commitDestination(heading == null ? -1 : heading.getRegion().getRegionId());

		// Only while travelling. Standing at a patch with work to do, the teleport is the last
		// thing anyone wants pointed at — the whole design is one instruction at a time.
		//
		// Recorded every tick, whatever else is true, so the comparison is always against the
		// previous tick rather than against whenever this last happened to be reached. Left
		// inside the && it was short-circuited away on every tick with a step at the stop, so
		// the "previous" destination could be minutes old and the gate answered nonsense.
		boolean settled = destinationSettled(destination);

		// ...and only once the destination has stopped moving. See destinationSettled.
		//
		// Not on the supply leg. There the destination is a bank, chosen fresh from the router's
		// reply every tick because commitDestination is deliberately ignored while atBankLeg —
		// so it never agrees with itself two ticks running and the gate held the hint off for the
		// whole leg. That left the patches-ahead outline as the only thing lit, which reads as
		// "every patch in the region is highlighted" with no teleport marked at all. Reported
		// from play, on the way to the bank at the start of a run.
		TravelHint hint = planner.isActive() && steps.isEmpty()
			&& (settled || planner.isAtBankLeg())
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
			destination, describePatchesAt(heading),
			patchesAhead(heading, planner.isAtBankLeg(),
				planner.isActive() && steps.isEmpty()),
			hint, here == null ? null : here.getName(), supplyLines(), withdrawLines(),
			planner.isActive() ? contractNote(here) : null,
			planner.isActive() ? skipped : java.util.Collections.emptyList(),
			planner.isActive() && planner.isAtBankLeg()
				? planner.getSupplySources()
				: contractFetchSources(steps, here),
			planner.isActive() ? routeItem.currentName() : null);

		routeFromTheFrontDoor();
	}

	/** The destination the previous tick resolved, for {@link #destinationSettled}. */
	@Nullable
	private String lastHintDestination;

	/** Whether {@link #lastHintDestination} holds a real answer rather than "not asked yet". */
	private boolean hintDestinationKnown;

	/**
	 * Whether the leg has been going to the same place long enough to point at an item for it.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"finished up at ardougne farm, prompted to tele home via home tab, by the time I clicked
	 * it swapped to my ardougne cloak: kandarin monastery. Now I'm in my POH and the nexus and
	 * jewelery box are highlighted with no sub-menu highlighting."</i>
	 *
	 * <p>The destination is re-resolved every tick, and the moment a stop <b>completes</b> is
	 * exactly when it leaves {@link RunPlanner#getRemaining()} and the next leg is chosen greedily
	 * from wherever the player is standing. {@link RunPlanner#committedStop} latches a leg once
	 * chosen, so it was not wrong here — it simply had nothing left to hold at the instant
	 * Ardougne finished. So the highlight moved between the eye and the click, and the click
	 * landed on the answer to a question that had already changed.
	 *
	 * <p>The second half of that report is why this is not cosmetic. Following the stale highlight
	 * put the player in their house with the destination now somewhere else, and the nexus and
	 * jewellery box lit with <b>nothing lit inside them</b> — the outer highlight and the sub-menu
	 * row are resolved separately, so once the two disagree the inner one cannot be derived at all.
	 * A highlight that can be wrong by the time it is followed is worse than no highlight.
	 *
	 * <p>So: one tick of agreement before anything is pointed at. A destination that has just
	 * changed shows nothing for a tick rather than showing the old answer or a half-resolved new
	 * one, and the run is left saying where it is going in words — which never went wrong — while
	 * it settles. The owner's read, and the shape they asked for: do not highlight until the
	 * destination is known and locked in.
	 *
	 * <p>Nameless legs are unaffected: a null destination equals a null destination, so a hint
	 * that travels without a name — the vehicle coming from the hops — settles immediately, as it
	 * did before.
	 */
	private boolean destinationSettled(@Nullable String destination)
	{
		boolean settled = hintDestinationKnown
			&& java.util.Objects.equals(destination, lastHintDestination);
		lastHintDestination = destination;
		hintDestinationKnown = true;
		return settled;
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

		// The gear phase's bank leg is a gear stop plus one short list: deposit everything,
		// load the player's own setup, and pick up what the cave cannot be replanted without.
		// A mixed run's farming summary is still deliberately absent — those withdrawals
		// belong to the swap-back leg after the hespori, and listing them here read as orders
		// for a pack that has no room for them — which is why the summary is asked for the
		// HESPORI alone rather than for the covered types.
		//
		// Composed here rather than inside the handoff: that class is deliberately blind to
		// the loadout, and the seed rows are the summary's own work. The leg holds for exactly
		// these rows (see supplyLegOutstanding), and a leg that holds for an item it never
		// names is the plugin refusing to say what it wants. Reported from play.
		java.util.Set<com.dooglemaps.data.PatchImplementation> covered = planner.coveredTypes();
		if (handoff.applies())
		{
			java.util.List<String> gearLines = new ArrayList<>(handoff.supplyLines());
			gearLines.addAll(com.dooglemaps.bank.LoadoutSummary.forItems(
				loadout.forRun(java.util.EnumSet.of(PatchImplementation.HESPORI))));
			return gearLines;
		}

		// The two mid-run trips lead with the reason they exist — the summary below reads as
		// a fetch list, and on these legs fetching is the second errand, not the first.
		java.util.List<String> lines = new ArrayList<>();
		com.dooglemaps.route.RunPlanner.BankLegReason reason = planner.getBankLegReason();
		if (reason == com.dooglemaps.route.RunPlanner.BankLegReason.GEAR_SWAP)
		{
			lines.add("Deposit the combat kit - the farming loadout comes back out");
		}
		else if (reason == com.dooglemaps.route.RunPlanner.BankLegReason.DEPOSIT)
		{
			lines.add("Deposit your logs and produce - the pack is full");
		}
		java.util.List<com.dooglemaps.bank.LoadoutItem> rows = loadout.forRun(covered);
		lines.addAll(com.dooglemaps.bank.LoadoutSummary.forItems(rows));
		if (lines.isEmpty())
		{
			String state = nothingToFetch(rows);
			if (state != null)
			{
				lines.add(state);
			}
		}
		return lines;
	}

	/**
	 * Why a supply leg has nothing under its heading, when it has a list to be silent about.
	 *
	 * <h2>Three unlike states rendered as one sentence</h2>
	 *
	 * An empty summary used to fall through to the panel's own fallback, <i>"nothing is picked
	 * for this run yet"</i>. That is one of the three things it can mean and the least common:
	 *
	 * <ul>
	 *   <li><b>Nothing picked.</b> No types, so no list at all — the fallback's own case, left
	 *       to the panel by returning null here.</li>
	 *   <li><b>The bank has not been read.</b> Every row the pack cannot answer for is
	 *       {@code UNKNOWN}, because a bank is only readable while it is open, and the summary
	 *       deliberately says nothing rather than reporting a bank it has not seen as empty.
	 *       The leg is right to hold — that is what the two clauses in {@code
	 *       RunPlanner.suppliesOutstanding} in front of the loadout are for — but the reason is
	 *       "open it and I will tell you", not "you have picked nothing".</li>
	 *   <li><b>You already have it all.</b> Every row reads {@code HAVE} or {@code
	 *       AT_LEPRECHAUN}, which is the ordinary end of a supply leg and the one state the old
	 *       sentence was most insulting about. Reported from play, standing at the guild with a
	 *       pack full of saplings, told nothing was picked.</li>
	 * </ul>
	 *
	 * <h2>Nothing here tells the player to do something that will not work</h2>
	 *
	 * <b>Not</b> "open and close the bank to set off", tempting as it reads. {@code leaveBank} is
	 * asked every tick by the plugin whether or not a bank is open, so a leg with nothing
	 * outstanding has already ended by the time anyone reads a line about it. If the leg is still
	 * being drawn with an empty list, something the loadout cannot see is holding it, and opening
	 * the bank would not clear that — so the line names what is being waited on and points at the
	 * way past it instead.
	 *
	 * <p>The case this was written for is fixed: {@code RunPlanner.seedsWantedFor} used to
	 * allocate with {@code ProtectionBudget.NONE} where the loadout allocated with the real one,
	 * so it could want a seed no row named and the leg could never end. The two share a budget
	 * now. The line stays as the tripwire for the next such divergence, which is worth being told
	 * about rather than left to look like a stall.
	 *
	 * @return the line to draw, or null to leave the panel's fallback to it
	 */
	@javax.annotation.Nullable
	private String nothingToFetch(java.util.List<com.dooglemaps.bank.LoadoutItem> rows)
	{
		if (rows.isEmpty())
		{
			return null;
		}

		for (com.dooglemaps.bank.LoadoutItem row : rows)
		{
			if (row.getNeed() == com.dooglemaps.bank.LoadoutItem.Need.UNKNOWN)
			{
				return "Open the bank - the run cannot tell what you are missing "
					+ "until it has read one.";
			}
		}

		String waiting = waitingOn();
		return waiting == null
			? "You have everything for this run."
			: "Nothing left on the list, but the run is still waiting on " + waiting
				+ " - press Skip step if you are ready to go.";
	}

	/** The container the planner is still holding the leg for, named, or null if none is. */
	@javax.annotation.Nullable
	private String waitingOn()
	{
		java.util.Set<com.dooglemaps.state.SeedSource> sources = planner.getSupplySources();
		boolean bank = sources.contains(com.dooglemaps.state.SeedSource.BANK);
		boolean vault = sources.contains(com.dooglemaps.state.SeedSource.SEED_VAULT);
		if (bank && vault)
		{
			return "the bank and the seed vault";
		}
		return bank ? "the bank" : vault ? "the seed vault" : null;
	}

	/**
	 * What the run still wants withdrawn, with counts, for the infobox tooltip.
	 *
	 * <p>Built here rather than in {@code ReadyInfoBox} for the reason {@link #supplyLines()}
	 * gives: that class recomputes on every store change from whichever thread fired it, and this
	 * walks the loadout and the planner. On this thread, this tick, the loadout build has already
	 * been paid for — {@code DoogleMapsPlugin.onGameTick} asks {@code anythingLeftToWithdraw}
	 * first, and {@code forRun} is cached per tick — so sampling it into the status costs a walk
	 * of a cached list.
	 *
	 * <p>Only while a run is on: this answers "what do I still take out for the trip", which is a
	 * question nobody has asked until they have started one — the loadout answers from the ticked
	 * types either way, so ungated it was a shopping list for a trip that did not exist. Reported
	 * from play.
	 *
	 * <p>Teleports are not on it, because the list is read as a set of orders and they are not
	 * one: {@code TELEPORT} is deliberately absent from {@code CANNOT_PROCEED_WITHOUT}, so a
	 * missing teleport has never held the supply leg and the run has always been happy to walk.
	 * The sidebar's bank list still carries them with their need, which is where the detail
	 * belongs.
	 */
	private java.util.List<String> withdrawLines()
	{
		if (!planner.isActive())
		{
			return java.util.Collections.emptyList();
		}

		// The hespori's own kit during the gear phase, and only that. The farming half of a
		// mixed run stays off the list for the reason the supply lines give — those are the
		// swap-back leg's orders, and listing them under a combat loadout reads as orders for
		// a pack with no room for them. The spade, the seed and the dibber are a different
		// case: the leg now holds for them (see supplyLegOutstanding), and a leg that holds
		// for an item it never names is the plugin refusing to say what it wants.
		java.util.Set<PatchImplementation> types = handoff.applies()
			? java.util.EnumSet.of(PatchImplementation.HESPORI)
			: planner.coveredTypes();

		java.util.List<String> items = new ArrayList<>();
		for (com.dooglemaps.bank.LoadoutItem item : loadout.forRun(types))
		{
			if (item.getNeed() != com.dooglemaps.bank.LoadoutItem.Need.WITHDRAW
				|| item.getCategory() == com.dooglemaps.bank.LoadoutItem.Category.TELEPORT)
			{
				continue;
			}
			// A count only where one is a decision. "Bronze axe x1" is worse than "Bronze axe".
			items.add(item.getOutstanding() > 1
				? item.getName() + " x" + item.getOutstanding()
				: item.getName());
		}
		return items;
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

	/**
	 * Forgets the current guidance, so a stopped run stops instructing immediately.
	 *
	 * <h2>Shutdown only — this is not the run boundary</h2>
	 *
	 * {@code DoogleMapsPlugin.shutDown} calls this alongside every capture's {@code reset()},
	 * and nothing else does. The run boundary is {@link #runEnded()}, which this delegates the
	 * run-scoped half of the job to rather than keeping its own copy of the list — the two
	 * copies drifting is precisely what produced the bug that method's note describes.
	 *
	 * <p>What is left here is the two things that are about the <i>plugin</i> stopping rather
	 * than a run ending: the published status, and the last-seen region.
	 */
	public void reset()
	{
		status = GuideStatus.idle();
		lastRegion = -1;
		lastPlayerTile = null;
		runEnded();
	}

	/**
	 * Clears everything scoped to one run, at the moment a run ends.
	 *
	 * <h2>This is the run boundary, and {@link #reset()} is not</h2>
	 *
	 * Called from {@link #reportIdlePatches()}'s idle branch, which runs every tick the planner
	 * is inactive — so it is reached on the tick a run stops and on every tick after it, which
	 * is what makes it the boundary rather than merely a place the clearing happens to sit.
	 *
	 * <p>The distinction has already cost a bug. These clears once lived in {@code reset()}
	 * alone, which made them session-scoped in practice while their docs claimed run scope: a
	 * skipped "pay the farmer" then silently planted that patch unprotected on every later run
	 * of the session, and a skipped pick-up was never offered again. A skip is a statement about
	 * this run; the next run starts with none.
	 *
	 * <p>It is a <b>named method</b> rather than the unlabelled branch it used to be so that
	 * "what is cleared when a run ends" is a question this class answers by its method names.
	 * While it was inline, reading the class for the run boundary led to {@code reset()} — the
	 * wrong method, giving a confident-looking answer — which is a mistake that has now been
	 * made twice, once in play and once in review.
	 */
	private void runEnded()
	{
		// Said once a run, not once a session. The next run's dead crops are new news, and a
		// downgrade the player is living with is worth repeating — these are meant to nag, and
		// someone who does not want the nagging has a setting for each.
		announcedResurrect.clear();
		announcedDowngrade = null;

		// So the first leg of the next run settles from scratch rather than inheriting agreement
		// with wherever the last one finished. See destinationSettled.
		lastHintDestination = null;
		hintDestinationKnown = false;

		// The infobox line, and it is not the same field as the one above. announcedDowngrade
		// stops the chat repeating within a run; this is what the infobox reads, and it was
		// cleared only by noteCompostDowngrade being reached again with nothing to report. A
		// run that ends while downgraded therefore left "Fallen back to compost / Worth a
		// compost bin run" standing on the infobox for the rest of the session, with no setting
		// that takes it off — turning downgrading off does not clear a line already up.
		// Reported from play as compost always showing there and not being dismissable.
		downgradedTo = null;

		skippedSteps.clear();
		announcedBlock = null;
		announcedDud = null;
		loggedErrandsAt = null;
		lastNamedStop = null;
		working = null;
		interrupted = null;
		workingRegion = -1;
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
		List<GuideStep> steps = new ArrayList<>(stepsFor(patch, patchesWanting(stop, patch),
			compostThisStopWillUse(stop)));
		steps.removeIf(step -> skippedSteps.contains(keyOf(step)));
		return steps;
	}

	private static String keyOf(GuideStep step)
	{
		return step.getPatch().getKey() + "#" + step.getAction().name();
	}

	/** Whether this stop is the hespori's — the one voice the gear phase leaves speaking. */
	private static boolean coversTheHespori(RunStop stop)
	{
		for (FarmPatch patch : stop.getPatches())
		{
			if (patch.getImplementation() == PatchImplementation.HESPORI)
			{
				return true;
			}
		}
		return false;
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
		if (!planner.isActive())
		{
			return steps;
		}

		// The supply leg speaks through the withdraw list and the highlighted booth rather than
		// through a step, so this stop's patches are deliberately silent while it runs.
		//
		// Said in the log, once per leg, because that silence is indistinguishable from the plugin
		// having stopped and has been reported as exactly that. A contract-only run whose seed was
		// never allocated opens at a bank with an empty withdraw list, so every surface goes quiet
		// at once and there is no line anywhere saying why. The contract note is the one thing that
		// survives this return — it is built beside the status rather than inside the step list,
		// which is what lets the guild's explanation stay on screen through the leg. See the
		// status build in onGameTick.
		if (planner.isAtBankLeg())
		{
			sayNothingHere("the run is at its supply leg (" + planner.getBankLegReason()
				+ "), collecting from " + planner.getSupplySources()
				+ "; an empty list there means nothing was picked for this run");
			return steps;
		}
		lastSilence = null;

		WorldPoint player = playerLocation();
		if (player == null)
		{
			return steps;
		}

		RunStop stop = stopAt(player);

		// During the gear phase, every stop but the hespori's is silent. Gearing up happens
		// at the guild bank, in the middle of the guild's own stop — and the step engine,
		// which speaks for wherever you stand, offered the big bin to a player in combat
		// kit. Reported from play: "when we gear up for hespori it should be our only target
		// until it's done". The route overlay is already pointing at the cave; the fight is
		// the only work these clothes are for.
		if (stop != null && handoff.applies() && !coversTheHespori(stop))
		{
			working = null;
			interrupted = null;
			return steps;
		}

		if (stop == null)
		{
			// Between stops. The route overlay is already saying where to go, and repeating it
			// here would be a second voice giving the same instruction.
			//
			// Also the moment to forget which patch was being worked: arriving somewhere new
			// should pick the nearest thing there, not resume a patch two teleports away.
			working = null;
			interrupted = null;
			appendLeavingErrandsAtFinishedStop(steps, player);
			announceSkipsAtFinishedStop(player);
			return steps;
		}

		if (stop.getRegion().getRegionId() != workingRegion)
		{
			workingRegion = stop.getRegion().getRegionId();
			working = null;
			interrupted = null;
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
			steps.addAll(stepsFor(first, patchesWanting(stop, first),
				compostThisStopWillUse(stop)));
		}
		for (FarmPatch patch : offered)
		{
			if (!patch.getKey().equals(first == null ? null : first.getKey()))
			{
				steps.addAll(stepsFor(patch, patchesWanting(stop, patch),
					compostThisStopWillUse(stop)));
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
					steps.addAll(stepsFor(patch, patchesWanting(stop, patch),
						compostThisStopWillUse(stop), true));
				}
			}
		}

		// One visit, said once. A full pack raises "note this with the leprechaun" from every
		// patch still holding produce — each naming its own crop — and the panel repeated the
		// notice under every harvest at the stop. Reported from play. Only the first survives:
		// the list is re-derived every tick, so once that note is followed the next crop's note
		// surfaces by itself if the pack is somehow still full.
		collapseDuplicateNotes(steps);
		collapseDuplicateWithdrawals(steps);

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
		noteLeadsWhenThePackIsFull(steps, carried.getFreeSlots());
		noteStopOrder(stop, steps);
		return steps;
	}

	/**
	 * Puts the note step in front of a harvest that cannot happen until it is followed.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"noting potato cactus step didn't highlight the potato cactus or the lep"</i> — and the
	 * session log shows why: {@code 4922.7909=HARVEST; 4922.4775=NOTE_AT_LEPRECHAUN}, the note
	 * listed but never <b>current</b>, and the overlays light the current step only.
	 *
	 * <p>The note is raised inside the plan of the patch whose produce fills the pack, so it
	 * leads only when <i>that</i> patch is the working patch. Here the working patch was another
	 * harvest entirely — one the pack, at zero free slots, could not receive a single item of.
	 * The order said "harvest first, then note"; the game only permits the reverse. The player
	 * followed the note by hand, unlit.
	 *
	 * <p>So when nothing fits and the list leads with a harvest, the one surviving note (the
	 * collapse above keeps exactly one) moves to the front. Gated on the same
	 * {@code freeSlots <= 0} that raises the note in {@code GuidePlan} — with any room at all
	 * the harvest can genuinely proceed and the order stands. A leading step that is not a
	 * harvest is left alone: paying, planting and bin work need no free slot, and the pick-up
	 * and errand steps have orderings of their own.
	 */
	static void noteLeadsWhenThePackIsFull(List<GuideStep> steps, int freeSlots)
	{
		if (freeSlots > 0 || steps.isEmpty()
			|| steps.get(0).getAction() != GuideAction.HARVEST)
		{
			return;
		}
		for (int i = 1; i < steps.size(); i++)
		{
			if (steps.get(i).getAction() == GuideAction.NOTE_AT_LEPRECHAUN)
			{
				steps.add(0, steps.remove(i));
				return;
			}
		}
	}

	/**
	 * Past this many distinct patch types, the stop is described by its size instead.
	 *
	 * <p>Three fits the line. The Farming Guild has eleven, and listing them would push the
	 * destination off the panel to tell you something you would read as noise anyway — at that
	 * size "nine patches" is the useful fact and the types are not.
	 */
	private static final int TYPES_WORTH_NAMING = 3;

	/**
	 * What is waiting at a stop, in patch types. Null when there is nothing to say.
	 *
	 * <h2>Types, not work</h2>
	 *
	 * See {@link GuideStatus#getDestinationPatches()}. What a patch <i>wants</i> can change while
	 * you travel — a crop ripens, a bin's clock finishes — so a promise made when the leg starts
	 * can be wrong by the time you arrive. What is planted cannot change under you.
	 *
	 * <p>Counted per type and ordered by the stop's own patch list, so two farms with the same
	 * types read the same way round. Pluralised properly because "2 allotment" reads as a typo
	 * and this line is on screen for the length of a journey.
	 */
	@Nullable
	static String describePatchesAt(@Nullable RunStop stop)
	{
		if (stop == null || stop.getPatches().isEmpty())
		{
			return null;
		}

		java.util.Map<PatchImplementation, Integer> counts = new java.util.LinkedHashMap<>();
		for (FarmPatch patch : stop.getPatches())
		{
			counts.merge(patch.getImplementation(), 1, Integer::sum);
		}

		if (counts.size() > TYPES_WORTH_NAMING)
		{
			int patches = stop.getPatches().size();
			return patches + " patches";
		}

		StringBuilder said = new StringBuilder();
		for (java.util.Map.Entry<PatchImplementation, Integer> entry : counts.entrySet())
		{
			if (said.length() > 0)
			{
				said.append(", ");
			}
			String name = entry.getKey().getDisplayName().toLowerCase(java.util.Locale.ROOT);
			said.append(entry.getValue() == 1
				? name
				: entry.getValue() + " " + plural(name));
		}
		return said.toString();
	}

	/**
	 * The patches to outline on the way in, or none when this leg is not a walk up to them.
	 *
	 * <h2>Why the supply leg is not a walk up to them</h2>
	 *
	 * A stop's patches are lit while travelling so the last stretch is not walked with the whole
	 * farm dark — see {@code GuideOverlay.highlightPatchesAhead}. The supply leg is a leg with no
	 * steps, so the overlay's "no step means travelling" branch draws it too, and there the
	 * highlight is answering a question nobody asked: the one thing to click is the bank.
	 *
	 * <p>Mostly invisible, because a bank usually stands a region or more from the patches and
	 * none of the stop is in the scene to outline — though not reliably so, since a scene spans
	 * regions and {@code destinationStop} matches touching ones. <b>The Farming Guild's bank is
	 * inside the patch region itself</b>, so standing at the chest with the first leg outstanding lit
	 * all thirteen patches at once — reported from play as every patch in the guild highlighted at
	 * the start of a run. {@code destinationStop} resolves the chest's region to the guild stop
	 * (region ids match), which is right for the panel's destination line and wrong for this.
	 *
	 * <p>Gated here rather than in the overlay for the reason {@link GuideStatus#getSupplySources()}
	 * records: the planner knows which leg it is on, and an overlay that re-decides that is an
	 * overlay that can disagree with the route being drawn.
	 */
	static List<FarmPatch> patchesAhead(@Nullable RunStop heading, boolean atBankLeg,
		boolean travelling)
	{
		if (heading == null || atBankLeg || !travelling)
		{
			return java.util.Collections.emptyList();
		}
		return new ArrayList<>(heading.getPatches());
	}

	/**
	 * The plural of a patch type's name — moved to {@link GuidePlan#plural}, which needs it too
	 * and is the direction the dependency already runs: this class calls that one for
	 * {@code usableCompost} and {@code seedAtHand}. The rules (sibilants, the -y family) live
	 * with the implementation.
	 */
	private static String plural(String name)
	{
		return GuidePlan.plural(name);
	}

	/** The last stop ordering logged, so it is said once per distinct answer. */
	@Nullable
	private String loggedStopOrder;

	/**
	 * Says what the panel is actually about to show, in order.
	 *
	 * <h2>This logged the wrong list, and cost a round</h2>
	 *
	 * It was handed {@code ordered} — the distance sort with {@code contractFirst} and
	 * {@code binsFirst} applied — on the assumption that this was the order the steps came out
	 * in. It is not. {@link #chooseWorkingPatch} runs afterwards and hoists the patch you are
	 * part way through to the top, so the log read "bin first" while the screen said "harvest".
	 * A diagnostic that measures something adjacent to the question confirms fixes that never
	 * reached the player, which is exactly what happened.
	 *
	 * <p>So it takes the finished list now: after the working patch is chosen, after the skips
	 * are dropped, after the errands are woven in. The first entry is the current step. Keyed by
	 * patch as well as action, because a stop with six patches called "Farming Guild" told
	 * nobody which one was which.
	 */
	private void noteStopOrder(RunStop stop, List<GuideStep> steps)
	{
		StringBuilder line = new StringBuilder();
		for (GuideStep step : steps)
		{
			line.append(step.getPatch() == null ? "?" : step.getPatch().getKey())
				.append('=').append(step.getAction().name()).append("; ");
		}

		String key = stop.getRegion().getRegionId() + "#" + line;
		if (key.equals(loggedStopOrder))
		{
			return;
		}
		loggedStopOrder = key;
		log.info("Stop {} will show, in order: {}", stop.getName(),
			line.length() == 0 ? "nothing" : line);
	}

	/**
	 * Why a stop that just completed under your feet is not asking for anything else.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * {@link #announceSkips} exists to say "skipping the allotment - your watermelon seeds are
	 * in your bank", and it could not reach the one moment it is for. Picking the last crop
	 * empties the patch on the same tick; an empty patch with no seed at hand produces no steps;
	 * no steps at any patch completes the stop; a completed stop leaves {@code getRemaining()},
	 * so {@code stopAt} answers null and {@link #computeStepsHere} returns before the
	 * announcement is ever built. The player is left standing in Falador being told to teleport.
	 *
	 * <p>Same failure shape as the leaving errands above and the same repair: the words belong to
	 * the stop rather than to a patch, so they are re-attached here once it has gone. Anchored to
	 * {@code workingRegion} for the same reason, so walking back through a stop finished an hour
	 * ago says nothing.
	 *
	 * <p>Read before {@code working} is cleared by the caller — it is not used here, but the
	 * order matters if that ever changes.
	 */
	private void announceSkipsAtFinishedStop(WorldPoint player)
	{
		for (RunStop stop : planner.getStops())
		{
			if (stop.getRegion().getRegionId() == workingRegion && standingAt(stop, player))
			{
				announceSkips(stop, stop.getPatches());
				return;
			}
		}
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
				+ " your full inventory dropped on the ground.");

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
			|| stop.getRegion().getRegionId() != ContractState.FARMING_GUILD_REGION
			// ...and only for a run that is actually doing the contract. The hold-back exists
			// to stop an ordinary crop being planted in ground the contract needs, which is
			// nobody's problem on a run that never ticked the contract line. With none
			// assigned it went further and held back the WHOLE guild — a compost-only run
			// stood at the guild bank and was offered nothing at all, because the guild was
			// being kept clear for a contract the player had not asked for. Reported from play.
			|| !contractIsInTheRun())
		{
			return ordered;
		}

		// A compost bin is never held back, whatever the contract is doing. Jane cannot assign
		// one, so it competes for no ground — and it is the one thing at the guild worth doing
		// first regardless, because emptying it frees the pack and restocks the compost. See
		// binsFirst.
		List<FarmPatch> bins = new ArrayList<>();
		for (FarmPatch patch : ordered)
		{
			if (com.dooglemaps.data.CompostBin.forType(patch.getImplementation()) != null)
			{
				bins.add(patch);
			}
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
			return contracts.hasContract() ? ordered : bins;
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
			// (the bins are added back below whichever way this goes)
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
		if (!outstanding)
		{
			return ordered;
		}

		// The contract's own patches, plus any bin, which is never withheld - see above.
		List<FarmPatch> offered = new ArrayList<>(bins);
		for (FarmPatch patch : claimed)
		{
			if (!offered.contains(patch))
			{
				offered.add(patch);
			}
		}
		return offered;
	}

	/**
	 * Whether this run is actually doing the farming contract.
	 *
	 * <p>The contract's run line only exists while one is assigned and its patch belongs to
	 * this account, so "not ticked" covers both "there is no contract" and "there is one and
	 * the player is ignoring it". Either way the guild has no reason to be kept clear.
	 *
	 * <p>The just-settled crop counts as well, because the tick outlives the assignment: between
	 * handing one in and taking the next there is nothing assigned and nothing awaiting, and a
	 * gate reading only those two went false in the middle of the chain — which took the guild
	 * hold-back and every contract errand with it, at the exact moment the errand was the only
	 * thing left to do. The stored line is unchanged throughout; it is what the player asked for.
	 */
	private boolean contractIsInTheRun()
	{
		com.dooglemaps.data.PatchImplementation type = contracts.getActiveContractType();
		if (type == null)
		{
			Produce settled = contracts.getSettledContract();
			type = settled == null ? null : settled.getPatchImplementation();
		}
		return type != null && runTypes.isSelected(
			com.dooglemaps.data.RunOption.full(
				com.dooglemaps.data.PlantingGroup.contract(type)));
	}

	/**
	 * Says, once per tier, that the run has fallen back to weaker compost.
	 *
	 * <h2>Why it is worth a line rather than a silent substitution</h2>
	 *
	 * Which bucket gets spent is a decision the player made on the tab, and a run quietly
	 * spending a different one is the plugin overriding them without saying so. Running out of
	 * ultracompost mid-run is ordinary — it is the tier people hoard and the one a bin cannot
	 * make without volcanic ash — so the substitution is worth doing, and precisely because it
	 * is worth doing it has to be visible.
	 *
	 * <p>The other half of the message is the fix: the bins are what make more, and a downgrade
	 * is the moment that is worth knowing.
	 *
	 * <p>Once per tier rather than per patch. A herb run treats a dozen patches from one stack,
	 * and a dozen identical lines is the shape of a spam bug.
	 */
	private void noteCompostDowngrade(PlantingGroup group, CompostTier wanted, CompostTier using)
	{
		if (!config.downgradeCompost() || wanted == null || using == wanted
			|| using == CompostTier.NONE)
		{
			downgradedTo = null;
			return;
		}

		String key = group.getKey() + "#" + wanted + "#" + using;
		if (key.equals(announcedDowngrade))
		{
			return;
		}
		announcedDowngrade = key;
		downgradedTo = using;

		chat.queue(net.runelite.client.chat.QueuedMessage.builder()
			.type(net.runelite.api.ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(new net.runelite.client.chat.ChatMessageBuilder()
				.append(java.awt.Color.ORANGE,
					"Out of " + wanted.getDisplayName().toLowerCase() + " - treating with "
						+ using.getDisplayName().toLowerCase()
						+ " instead. Worth a compost bin run.")
				.build())
			.build());
	}

	/** Bins already warned about this session, so the notice is said once per bin. */
	private final java.util.Set<String> announcedMissingAsh = new java.util.HashSet<>();

	/**
	 * Says, once per bin, that a supercompost bin is about to be emptied without its upgrade.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"I was just sent to ardougne farm without volcanic ash to fill the bin… I gathered a
	 * bunch at the start of a run, but as the run finished up new runs became available and I
	 * never clicked stop, I just kept going, hence not having enough."</i>
	 *
	 * <p>{@code CompostBinPlan} gates the {@code APPLY_ASH} step on the ash actually being in the
	 * pack, on the reasoning that "an instruction that cannot be followed is the loadout's failure
	 * to prevent". That holds for a run that stays inside the plan it was stocked for, and a run
	 * that keeps absorbing newly-ready stops outgrows that plan by design — the loadout is
	 * computed once, at the bank. So the guide went quiet and the bin came out as plain
	 * supercompost, with nothing said and nothing to notice.
	 *
	 * <p>A notice rather than a step, deliberately, and it is the same distinction the gate
	 * itself draws: there is no ash to fetch here, the leprechaun does not store it, so "use ash
	 * on the bin" would be an instruction with no click behind it. What the player can act on is
	 * the knowledge — bank for ash, or empty it and accept supercompost — and that is a decision,
	 * not a step. Same shape and same wording style as {@link #noteCompostDowngrade}.
	 *
	 * <p>Only once the bin is actually finished. A closed bin forty minutes from done is not a
	 * loss yet, and saying so then is noise the player cannot use.
	 */
	private void noteMissingAsh(com.dooglemaps.data.CompostBin bin, FarmPatch patch,
		PatchProjection projection)
	{
		PatchSnapshot snapshot = patches.get(patch);
		if (snapshot == null
			|| !(projection.isReady() || projection.getCropState() == CropState.HARVESTABLE))
		{
			return;
		}

		int held = carried.getInventoryCount(com.dooglemaps.data.CompostBin.VOLCANIC_ASH);
		if (!CompostBinPlan.upgradeWouldBeLost(bin, snapshot.getProduce(),
				compostRun.isAshing(), held)
			|| !announcedMissingAsh.add(patch.getKey()))
		{
			return;
		}

		chat.queue(net.runelite.client.chat.QueuedMessage.builder()
			.type(net.runelite.api.ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(new net.runelite.client.chat.ChatMessageBuilder()
				.append(java.awt.Color.ORANGE,
					"This bin is ready and you have " + (held == 0 ? "no" : String.valueOf(held))
						+ " volcanic ash - it needs " + bin.ashNeeded()
						+ ". Emptying it now gives supercompost rather than ultracompost.")
				.build())
			.build());
	}

	/** The downgrade in force, for the infobox, or null when the run is using what was picked. */
	@Nullable
	private volatile CompostTier downgradedTo;

	/** The tier the infobox should say the run has fallen back to, or null. */
	@Nullable
	public CompostTier compostDowngrade()
	{
		return config.downgradeCompost() ? downgradedTo : null;
	}

	/**
	 * The downgrade last announced, as {@code group#wanted#using}, so it is said once a run.
	 *
	 * <p>Run-scoped rather than session-scoped, deliberately: a player running with the wrong
	 * compost every run is being told every run, because that is the run it applies to and
	 * {@code DoogleMapsConfig.downgradeCompost} is the switch for anyone who has heard enough.
	 * Cleared in {@link #runEnded()}.
	 */
	@Nullable
	private String announcedDowngrade;

	/** The last thing said about the contract patch, so it is said once and not every tick. */
	private String lastContractDiagnostic;

	/** The last reason the guide had nothing to say, kept apart from the contract diagnostic so
	 * two alternating messages cannot each read as new every tick. */
	private String lastSilence;

	/**
	 * Says why there is no step, once per spell of silence.
	 *
	 * <p>Silence is the guide's normal state on a travel leg and its failure state everywhere
	 * else, and from the player's side the two are identical — "the plugin just stopped doing
	 * anything" is how the failure gets reported. Cleared the moment a stop speaks again, so a
	 * second spell says so again rather than being swallowed as a repeat.
	 */
	private void sayNothingHere(String reason)
	{
		if (!reason.equals(lastSilence))
		{
			lastSilence = reason;
			log.info("The guide has no step for where you are standing: {}", reason);
		}
	}

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
		// Pushed before the idle return below, unlike everything after it, because this one is
		// read by a run that has not started yet: Start run plans the guild stop off it when the
		// only thing left in the contract chain is a conversation with Jane. See
		// contractBusinessOutstanding and RunPlanner.ensureJaneHasAStop.
		//
		// Wrapped, because this is the first thing the tick does and the chain behind it is the
		// longest reach in the class - config, the patch stores, the planner's allocation and the
		// seed inventory, one of which can be empty or half-loaded at login. An exception thrown
		// here would take the whole tick with it: no steps, no idle report, no snapshot, on every
		// tick, which from the player's side is the plugin having stopped. So the chain is allowed
		// to fail loudly in the log and quietly on screen - "no business" is the answer that lets a
		// run end rather than the one that strands it - and the trace is printed so a silence that
		// does happen is a silence with a cause in client.log.
		boolean business;
		try
		{
			business = contractBusinessOutstanding();
		}
		catch (RuntimeException e)
		{
			log.warn("The farming contract chain could not be judged this tick, so the guild is "
				+ "not being held open on it", e);
			business = false;
		}
		planner.setContractBusinessOutstanding(business);

		if (!planner.isActive())
		{
			planner.setNothingToDo(java.util.Collections.emptySet());
			planner.setWithdrawOutstanding(false);
			// Null rather than an empty set, which is the difference between "this run owes
			// nothing" and "there is no run to speak for". An idle planner is still asked where
			// a trip would go — the panel's destination list — and the answer it worked out for
			// itself is the only one available before a run exists. See setWithdrawSources.
			planner.setWithdrawSources(null);
			runEnded();
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
		planner.setWithdrawOutstanding(supplyLegOutstanding());
		// And the same answer's containers, pushed beside it and from the same list, so where the
		// leg is sent cannot disagree with whether it is finished. See supplyLegSources.
		planner.setWithdrawSources(supplyLegSources());
		// And the narrower half of it, for the one thing worth diverting mid-run over. See
		// RunLoadout.toolsLeftToWithdraw and RunPlanner.reviewSupplies. Silent during the
		// gear phase for the reason that method's own gate gives: the farming tools the
		// combat loadout lacks are the swap-back leg's business, not a diversion's.
		planner.setToolOutstanding(!handoff.applies()
			&& loadout.toolsLeftToWithdraw(planner.coveredTypes()));
		// And the setup half of the gear leg on its own, so the planner can stop naming a bank
		// the moment it is done and let the route follow the seed to the vault. The whole leg's
		// answer above is too broad for that: it stays true for the replanting kit as well.
		planner.setGearStopOutstanding(handoff.applies() && handoff.gearOutstanding());
		// And the pack, for the mid-run deposit trip: full, on a run that chops, of things
		// only a bank can absorb. All three halves of that judgment live on this side of the
		// pushed-flag line — the free-slot count in CarriedItems, the axe question in the
		// loadout, and what the slots actually hold — which is why the planner is told
		// rather than asking. See RunPlanner.reviewDepositTrip and packFullOfLogs.
		planner.setPackFull(carried.getFreeSlots() == 0
			&& com.dooglemaps.bank.RunLoadout.chopsLogs(planner.coveredTypes())
			&& packFullOfLogs());
	}

	/**
	 * Where a run over these types would have to collect from, for the moment before it starts.
	 *
	 * <p>{@code RunPanel}'s start button needs the answer the planner used to compute for
	 * itself, and this tracker is the coordinator that holds both ends — the panel asks here,
	 * this asks the loadout, and the planner is handed the result. One question, one owner.
	 *
	 * <p>Containers rather than a bare "is anything owed", because the planner needs both and
	 * only one of them can be derived from the other. Whether the leg has anything left is
	 * {@code !isEmpty()} on this; the reverse is not true, and re-deriving the where from the
	 * seed counts is the bug this returns a set to close. See {@code RunLoadout.outstandingSources}.
	 *
	 * <p>Asked with the types the panel is about to start, not with {@code planner.coveredTypes()}:
	 * this runs <b>before</b> {@code RunPlanner.start}, so the planner is still describing the
	 * last run.
	 */
	public java.util.Set<com.dooglemaps.state.SeedSource> withdrawListSources(
		java.util.Set<PatchImplementation> types)
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

		java.util.Set<com.dooglemaps.state.SeedSource> sources = sourcesFor(types);

		if (com.dooglemaps.bank.InventorySetupsHandoff.appliesTo(types)
			&& planner.wouldOpenWithGearPhase(types))
		{
			// A run opening with a gear phase always starts at a bank, whatever the loadout
			// concluded: depositing everything and loading the setup is the trip, not a fetch
			// list to be found already satisfied. Only when the hespori would actually be a
			// stop, though — ticked but still growing is an ordinary farm run.
			//
			// Named as a container rather than returned as a bare "yes", which is what keeps the
			// planner's boolean derivable from this set: the setup is a bank errand, so BANK is
			// the honest answer to "where is this trip going", and a trip with nowhere to go is
			// exactly a trip with nothing to collect.
			sources.add(com.dooglemaps.state.SeedSource.BANK);
		}
		return sources;
	}

	/**
	 * The withdraw list's containers, in the planner's words.
	 *
	 * <p>The one place the two vocabularies meet. {@code RunLoadout} answers in
	 * {@code LoadoutItem.From}, because that is what the withdraw list prints; the planner routes
	 * by {@code SeedSource}, because that is what it counts crops in.
	 */
	private java.util.Set<com.dooglemaps.state.SeedSource> sourcesFor(
		java.util.Set<PatchImplementation> types)
	{
		return com.dooglemaps.bank.RunLoadout.asSeedSources(loadout.outstandingSources(types));
	}

	/** Fewer than this many slots of logs is not worth a bank trip to free. */
	private static final int DEPOSIT_TRIP_LOGS = 5;

	/**
	 * Whether the pack's fullness is actually logs, rather than the run's own supplies.
	 *
	 * <p>"Full, on a chopping run" was the whole gate, and it fired on a pack deliberately
	 * full of empty buckets for the guild's big bin — a fullness the run's own withdraw list
	 * had ordered. Reported from play: <i>"but I'm full of empty buckets for the big compost
	 * bin step"</i>. The deposit trip exists for the one cargo nothing else absorbs — the
	 * leprechaun refuses logs and the bins cannot eat them — so it is that cargo the gate
	 * counts, and only when shedding it frees enough space to be worth the travel. Compost
	 * rows are skipped by name: buckets of compost are Produce to the data, but they are
	 * supplies here, not harvest.
	 */
	private boolean packFullOfLogs()
	{
		int refused = 0;
		for (int itemId : carried.getItemIds())
		{
			com.dooglemaps.data.Produce produce =
				com.dooglemaps.data.Produce.getByItemID(itemId);
			if (produce == null
				|| produce.getPatchImplementation() == PatchImplementation.COMPOST
				|| produce.getPatchImplementation() == PatchImplementation.BIG_COMPOST
				|| produce.isLeprechaunNotable())
			{
				continue;
			}
			refused += carried.getInventoryCount(itemId);
			if (refused >= DEPOSIT_TRIP_LOGS)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The supply leg's live answer, pushed to the planner once a tick and re-asked beside
	 * {@code leaveBank}.
	 *
	 * <p>One method rather than two call sites asking the loadout, because the hespori run
	 * answers a different question: its leg is over when the gear stop is
	 * ({@code InventorySetupsHandoff}), with the withdraw list joining back in only when the
	 * run also covers ordinary patches — that half of the trip still has to collect what it
	 * always did. A hespori-only run deliberately never asks the loadout, whose seed and tool
	 * rows would hold the leg open for things the player's own setup is about to cover.
	 */
	public boolean supplyLegOutstanding()
	{
		if (handoff.applies())
		{
			// The gear stop, plus the hespori's own kit — and nothing wider than that.
			//
			// The farming half of a mixed run is still deliberately not collected here: seeds
			// and a combat loadout cannot share a pack, and the swap-back leg after the
			// hespori exists precisely to collect them. Asking the whole withdraw list parked
			// an everything-ticked run at the bank, fully geared, with the leg demanding six
			// yew saplings it had nowhere to put. Reported from play, and asking it for the
			// HESPORI alone is what keeps that fixed: a tree run's saplings are not in this
			// list because TREE is not in these types.
			//
			// But "the gear stop and nothing else" was too little. The cave is a farming stop
			// once the boss is dead — rake, dib, replant — and a hespori-only run never arms
			// the swap-back trip that would fetch that kit (RunPlanner.reviewGearSwap returns
			// on an empty getRemaining), so this leg is the run's only chance to collect it.
			// The leg closed on the bank shutting and the player reached the patch with no
			// seed and no way to be sent for one. Reported from play.
			//
			// What that comes to is the spade, the seed, and a dibber unless Barbarian Farming
			// is unlocked — ToolNeeds already owns those three judgements, and
			// anythingLeftToWithdraw already narrows to the categories a stop is pointless
			// without. Neither rule is restated here. The player's own loadout is still not
			// inspected: what they fight in is their business, and only the replanting kit is.
			return handoff.gearOutstanding()
				|| loadout.anythingLeftToWithdraw(
					java.util.EnumSet.of(PatchImplementation.HESPORI));
		}
		return loadout.anythingLeftToWithdraw(planner.coveredTypes());
	}

	/**
	 * The same leg's containers, narrowed exactly as {@link #supplyLegOutstanding} narrows.
	 *
	 * <p>Deliberately the same {@code handoff.applies()} split, and for the same reason: sources
	 * the leg does not wait for would light and route to containers it will not end at, which is
	 * the disagreement the gear phase has already been reported for twice. A mixed run's tree
	 * saplings belong to the swap-back trip, so a gear-phase leg asks about the hespori alone.
	 *
	 * <p>The gear stop's own bank is <b>not</b> added here. It is not a withdraw row — there is
	 * nothing to fetch, only a setup to load — and {@code RunPlanner.getSupplySources} adds it
	 * from {@code gearStopOutstanding}, which is the flag that can tell that errand from this
	 * one as each finishes. Adding it here as well would put the bank back for the whole phase
	 * and leave {@code followSupplyProgress} nothing to follow.
	 */
	public java.util.Set<com.dooglemaps.state.SeedSource> supplyLegSources()
	{
		return sourcesFor(handoff.applies()
			? java.util.EnumSet.of(PatchImplementation.HESPORI)
			: planner.coveredTypes());
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

	/**
	 * Where the seeds the pack lacks actually are, for the skip wording above.
	 *
	 * <h2>The seed box is the one answer that can be out of date</h2>
	 *
	 * Its contents are remembered across sessions and derived from Fill and Empty deltas in
	 * between, and the client holds no container for it to be checked against until the box has
	 * been opened — {@code getItemContainer} answers null, which the reconcile now says out loud.
	 * So "not in your pack" can mean "and not in your box either" or it can mean "and I last saw
	 * inside your box some time before this session".
	 *
	 * <p>Reported from play as being sent away from Falador with the watermelon seeds sitting in
	 * the box. The plugin was wrong, and worse, it was wrong confidently. It now says which of
	 * the two it means, so an answer that cannot be trusted does not read like one that can.
	 */
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
		// "Inventory", never "pack", in anything the player reads: a seed pack is a real item -
		// the farming contract reward you open for random seeds - so "not in your pack" reads as
		// a statement about that rather than about your inventory. Said from play.
		return seeds.hasSeenTheBoxThisSession()
			? "not in your inventory"
			: "not in your inventory - and your seed box has not been opened this session, so "
				+ "what I have for it is from last time. Open it once if this looks wrong";
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

		if (stop.getPatches().isEmpty())
		{
			// Every stop the planner makes is made because a patch is at it — ensureJaneHasAStop
			// included, which hangs the guild on the contract's own patch — so this is a state
			// nothing produces today. It is guarded anyway because the alternative is an
			// IndexOutOfBounds thrown from the tick that builds the step list, which takes the
			// guide silent for the rest of the session over a stop with nothing in it.
			logOnce("The Farming Guild stop has no patch to anchor Jane's errands on, so they are "
				+ "not being offered");
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
			// No item on this step, and that is the correction rather than an omission. It carried
			// the produce's id, which outlined it in the pack — and an outlined crop beside "hand
			// your X to Jane" says the crop is the price of the reward. It is not: the contract
			// completed when it was harvested, the produce is yours to keep, and noting it at the
			// leprechaun on the way past costs nothing. Corrected by the owner.
			//
			// owesYouSomething never required it either — it asks the patch whether it still owes
			// a check or a pick — so nothing but the wording and the outline was ever wrong.
			steps.add(0, GuideStep.atNpc(GuideAction.HAND_IN_CONTRACT, anchor, -1,
				ContractState.GUILDMASTER_JANE,
				"Talk to Guildmaster Jane to claim the contract reward."));
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
	 * Whether Guildmaster Jane still has business with this run.
	 *
	 * <h2>The same three errands as {@link #appendContractErrands}, asked without a step list</h2>
	 *
	 * The steps above are only ever built for the stop the player is standing in, and only while
	 * the run is alive — {@link #computeStepsHere} returns before any of it otherwise. That made
	 * the contract chain unable to keep itself going, in the one way that matters: harvesting the
	 * contract crop empties its patch, the patch then wants nothing, the guild stop reads finished,
	 * the run ends, and the hand-in and the contract behind it are never offered at all. Reported
	 * from play at a cadantine contract — the run ended on the harvest, Start run planned the same
	 * stop and ended again a tick later, and the player handed in and took the next one unguided.
	 *
	 * <p>So the same question is asked here from the contract's own state and the patch
	 * projections, which are true wherever the player is standing and whether or not a run is
	 * under way. {@code RunPlanner} is told the answer once a tick and holds the guild stop open
	 * on it; {@code RunPlanner.start} plans that stop for it alone.
	 *
	 * <h2>Every branch has to be able to go false</h2>
	 *
	 * A flag that holds a stop open is a flag that can hold a run open forever, so each of the
	 * three ends by itself: the hand-in when Jane takes the crop, the take when she names the next
	 * one, and the planting when the seed is in the ground. Skip step is the fourth way out, and
	 * the one the player has — waving the errand past withdraws the claim with it, exactly as it
	 * withdraws the step.
	 *
	 * <p>Package-private so the guide's own tests can ask it directly. Nothing outside this class
	 * reads it; the planner is told.
	 */
	boolean contractBusinessOutstanding()
	{
		if (!config.guideFarmingContracts() || !contractIsInTheRun())
		{
			return false;
		}

		if (contractToHandIn() != null)
		{
			return !wavedPast(GuideAction.HAND_IN_CONTRACT);
		}

		if (contracts.getAwaitingHandIn() != null)
		{
			// Grown, but the patch still owes a check or a pick before Jane will take it. That
			// work is the patch's own and holds the stop open by itself, so claiming it here as
			// well would be two things saying one thing.
			return false;
		}

		if (!contracts.hasContract())
		{
			// Handed in, with the next one still Jane's to give out. The settled marker is what
			// tells this window apart from "no contract and none wanted" — see
			// ContractState.getSettledContract.
			return contracts.getSettledContract() != null
				&& !wavedPast(GuideAction.TAKE_CONTRACT);
		}

		return newContractStillToPlant();
	}

	/**
	 * Whether a contract that has just been taken still wants sowing this trip.
	 *
	 * <p>Judged from the ground and the seed rather than from the step list, for the reason
	 * {@link #contractBusinessOutstanding} gives: at the moment this matters there is no step list.
	 * Three things have to be true together, and each of them is a way out of the claim.
	 *
	 * <ul>
	 *   <li><b>The seed can be got at.</b> In hand, or in a bank or the vault — and the guild has
	 *       both a few steps from Jane, so "owned somewhere" and "obtainable here" are the same
	 *       answer. A contract whose seed the player simply does not have is work for another day,
	 *       and holding the stop open for it would strand the run.
	 *   <li><b>Its patch can receive it.</b> Empty, dead, or a picked-clean crop a spade takes
	 *       straight out — the same three shapes {@link #contractSeedFetch} sends a player
	 *       shopping for. Anything else standing there has {@link #contractNote} to explain it.
	 *   <li><b>It is not already sown.</b> The contract crop growing in its own patch is the end
	 *       of the chain, and the point at which the guild opens up again — <i>unless</i> what is
	 *       standing there is spent, which is the one shape of "the contract crop is in its patch"
	 *       that is not the chain finished but the chain not yet begun. See
	 *       {@code RunPlanner.contractStandingIsSpent}: a bush checked before the contract was
	 *       taken can never satisfy it, so the dig and the sowing are both still owed and the
	 *       guild still has business. Without this the claim went false at a patch the run had
	 *       every reason to visit, Jane's stop stopped being held open, and the guide had nothing
	 *       to say for the rest of the trip.
	 * </ul>
	 */
	private boolean newContractStillToPlant()
	{
		Produce contract = contracts.getContract();
		if (contract == null)
		{
			return false;
		}

		Seed seed = contracts.getContractSeed();
		if (seed == null || !seedIsWithinReach(seed))
		{
			return false;
		}

		boolean plantable = false;
		for (FarmPatch patch : groups.patchesIn(
			com.dooglemaps.data.PlantingGroup.contract(contract.getPatchImplementation())))
		{
			PatchProjection projection = growthTimer.project(patch, patches.get(patch));
			if (projection == null)
			{
				// Never seen. Unknown ground makes no promises, here or in contractSeedFetch.
				continue;
			}

			boolean dead = projection.getCropState() == com.dooglemaps.data.CropState.DEAD;
			if (!dead && projection.getProduce() == contract
				&& !planner.contractStandingIsSpent(patch))
			{
				// Sown and growing: the chain is finished and the stop may close. A spent crop
				// reads identically from here — the contract's own produce, alive, in its own
				// patch — and is the opposite state: nothing has been sown for this contract at
				// all, and the spade is the first thing it wants.
				return false;
			}
			plantable |= dead || projection.isEmpty()
				|| (projection.isReady()
					&& SpadeClearedCrops.isSpadeCleared(patch.getImplementation()));
		}
		return plantable;
	}

	/** Whether a seed is in hand or in storage the guild can reach; see the note above. */
	private boolean seedIsWithinReach(Seed seed)
	{
		return GuidePlan.seedAtHand(seed, seeds)
			|| seeds.getPlantable(seed, com.dooglemaps.state.SeedSource.BANK) > 0
			|| seeds.getPlantable(seed, com.dooglemaps.state.SeedSource.SEED_VAULT) > 0;
	}

	/**
	 * Whether an errand of this kind has been waved past.
	 *
	 * <p>By action rather than by the step's own key, because the key carries the patch the step
	 * was anchored to and the anchor is whichever patch the stop happens to list first — which is
	 * not knowable from here, and is not what the player meant anyway. There is one Jane.
	 */
	private boolean wavedPast(GuideAction action)
	{
		for (String key : skippedSteps)
		{
			if (key.endsWith("#" + action.name()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * What the guild's ground says about a record waiting to be handed in.
	 *
	 * <p>Read here because reading a patch is what this class does; the judgment made from it is
	 * {@code ContractState.reconcileAwaitingHandIn}'s, which is a config reader two packages away
	 * and cannot see a varbit. Called from the plugin at the two moments that reconcile runs.
	 *
	 * <p>{@link #isGrownInGuild} is what "unfinished" is measured against, so the two cannot
	 * disagree: a crop that reads as a completion there can never be read as corruption here.
	 */
	public ContractState.GroundEvidence contractGroundEvidence()
	{
		Produce awaiting = contracts.getAwaitingHandIn();
		if (awaiting == null || isGrownInGuild(awaiting))
		{
			return ContractState.GroundEvidence.UNREAD;
		}

		for (FarmPatch patch : groups.patchesIn(
			com.dooglemaps.data.PlantingGroup.contract(awaiting.getPatchImplementation())))
		{
			PatchProjection projection = growthTimer.project(patch, patches.get(patch));
			if (projection != null && projection.getProduce() == awaiting)
			{
				// Standing there, and not standing there finished. Nothing else reads as proof:
				// an empty patch is what a hand-in looks like from behind, and what a harvested
				// contract looks like too.
				return ContractState.GroundEvidence.CROP_STANDS_UNFINISHED;
			}
		}
		return ContractState.GroundEvidence.UNREAD;
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
	 * stop and be ground a plant can actually reach this trip — empty, a <b>dead</b> crop, or a
	 * picked-clean spade-cleared crop, the last two being ground the replant machinery already
	 * knows how to take out. Anything else standing there has its own answer (the blocked note,
	 * the dud note, or the patch's own steps), and a withdraw step in front of those would be a
	 * shopping trip for a seed with nowhere to go.
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

			PatchProjection projection = growthTimer.project(patch, patches.get(patch));
			if (projection == null)
			{
				// Unknown ground gets no shopping trip.
				return null;
			}

			// A dead crop is the one occupant that answers every test below the wrong way, and
			// it is why this reads as three exemptions rather than one condition. Reported from
			// play: the run walked to the guild, asked for the dead contract to be cleared, and
			// only then — with the patch finally empty — offered the seed that was in the bank
			// the player had walked past to get there. Two crossings of the guild for one patch.
			//
			// It is not a fourth state to reason about: dead ground is ground the run is
			// already going to clear, which is exactly the argument the spade-cleared allowance
			// below makes for a stripped bush. The seed is wanted on this trip either way, so
			// the fetch belongs in front of the walk rather than behind the clear.
			boolean dead = projection.getCropState() == com.dooglemaps.data.CropState.DEAD;

			if (!dead && !outstandingFor(patch, stop).isEmpty())
			{
				// The trip already has clicks for this ground; the seed is not the blocker yet.
				// A dead patch has clicks too — the clear — and they are the very clicks the
				// seed has to arrive before, so this cannot be the test that turns it away.
				return null;
			}
			if (!dead && projection.getProduce() == contract)
			{
				// The contract already planted needs none — but a dead one is not planted, it
				// is a seed that has to be spent again. Same distinction contractIsInTheGround
				// draws, for the same reason.
				return null;
			}
			if (!dead && !projection.isEmpty()
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
		com.dooglemaps.state.SeedSource source = contractSeedSourceHere(stop, seed);
		if (source == com.dooglemaps.state.SeedSource.BANK)
		{
			return "bank";
		}
		return source == com.dooglemaps.state.SeedSource.SEED_VAULT ? "seed vault" : null;
	}

	/**
	 * The same question as a {@link com.dooglemaps.state.SeedSource}, for the object to outline.
	 *
	 * <p>The words were all this used to produce, and the words are only half the instruction:
	 * {@code GuideOverlay.highlightSupplyPoints} marks the bank booths and the vault from
	 * {@code GuideStatus.getSupplySources}, which was populated <b>only on the bank leg</b>. A
	 * contract is taken from Jane in the middle of a run, so the fetch step said "withdraw your
	 * irit seed from the seed vault here" while nothing in the room lit up. Reported from play.
	 *
	 * <p>Kept as one method with the wording rather than two tests of the same thing, so the
	 * sentence and the outline can never name different containers.
	 */
	@Nullable
	private com.dooglemaps.state.SeedSource contractSeedSourceHere(RunStop stop, Seed seed)
	{
		int region = stop.getRegion().getRegionId();
		if (seeds.getPlantable(seed, com.dooglemaps.state.SeedSource.BANK) > 0)
		{
			for (net.runelite.api.coords.WorldPoint bank : bankLocations.getUsableBanks())
			{
				if (bank.getRegionID() == region)
				{
					return com.dooglemaps.state.SeedSource.BANK;
				}
			}
		}
		if (seeds.getPlantable(seed, com.dooglemaps.state.SeedSource.SEED_VAULT) > 0
			&& bankLocations.getSeedVault().getRegionID() == region)
		{
			return com.dooglemaps.state.SeedSource.SEED_VAULT;
		}
		return null;
	}

	/**
	 * The container a current contract-seed fetch is pointing at, or an empty set.
	 *
	 * <p>So the supply-point outline is not the bank leg's alone. Derived from the step that is
	 * actually on the list rather than from the contract state, because the step is what carries
	 * every other condition — the run's own types, an empty patch to plant in, the seed not
	 * already in hand — and re-deciding those here is how the outline and the instruction come
	 * to disagree.
	 */
	private java.util.Set<com.dooglemaps.state.SeedSource> contractFetchSources(
		java.util.List<GuideStep> steps, @Nullable RunStop here)
	{
		Seed seed = contracts.getContractSeed();
		if (here == null || seed == null)
		{
			return java.util.Collections.emptySet();
		}
		boolean fetching = false;
		for (GuideStep step : steps)
		{
			fetching |= step.getAction() == GuideAction.FETCH_SEED;
		}
		if (!fetching)
		{
			return java.util.Collections.emptySet();
		}

		com.dooglemaps.state.SeedSource source = contractSeedSourceHere(here, seed);
		return source == null
			? java.util.Collections.emptySet()
			: java.util.Collections.singleton(source);
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
	 * crop standing {@code HARVESTABLE}, which for those families only exists after the check.
	 * The judgment itself is {@code RunPlanner.contractStandingIsSpent} rather than a copy of it
	 * here, because the same question decides whether the run allocates the seed at all — and the
	 * two answering differently is how a run came to explain a dud in words while routing as
	 * though the contract were finished. Harvest-class crops cannot be duds: their completion
	 * event empties the patch, so a standing crop is always still eligible.
	 *
	 * <p>What the record adds is the one thing the planner cannot see. A check made <i>during</i>
	 * the contract announces itself in the chatbox, and that capture writes the awaiting-hand-in
	 * record — so a record naming <b>this crop</b> is proof the check counted and the crop is not
	 * a dud. A record naming some <i>other</i> crop is a leftover from an earlier cycle and says
	 * nothing about this contract at all; refusing on any record whatsoever is how a stale
	 * snapdragon in config silenced the explanation for a live poison ivy. Reported from play.
	 *
	 * <p>No new steps hang off this. The run already knows how to replant a picked-clean bush or
	 * cactus — harvest, dig up, plant — and with the planner asking the same question the seed for
	 * that replant is now allocated; what was missing was the refusal above and the explanation
	 * this feeds, in {@link #contractNote}.
	 */
	@Nullable
	private FarmPatch contractDudPatch()
	{
		Produce assigned = contracts.getContract();
		if (assigned == null)
		{
			return null;
		}

		Produce awaiting = contracts.getAwaitingHandIn();
		if (awaiting != null && awaiting == assigned)
		{
			return null;
		}

		for (FarmPatch patch : groups.patchesIn(
			com.dooglemaps.data.PlantingGroup.contract(assigned.getPatchImplementation())))
		{
			if (planner.contractStandingIsSpent(patch))
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
			if (projection != null && projection.getProduce() == contract
				// A dead crop is not the contract in the ground; it is the contract's seed
				// spent for nothing, and another one is needed. Reading it as planted made
				// missingContractSeed go quiet exactly when the player owns no seed and is
				// about to walk to a patch they cannot refill — the same "in the ground means
				// spent" error contractDudPatch exists to correct one state over.
				&& projection.getCropState() != com.dooglemaps.data.CropState.DEAD)
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

	/**
	 * The landward steps for an underwater stop the run still has left, or null.
	 *
	 * <p>For the overlay. Answered from the planner's remaining stops rather than from the
	 * current step, because there is no step for this: the router is routing to a shore and
	 * the click that follows is a piece of travel, so it has to be markable on a travel leg.
	 *
	 * <p>Any remaining underwater stop counts, not the nearest — a run with one is a run
	 * heading there eventually, and the steps only exist in the scene when the player is
	 * already standing on the right shore, so an early outline is impossible anyway.
	 *
	 * <p>That last clause is what makes this safe <b>for an outline</b> and unsafe for anything
	 * else; see {@link #underwaterApproachAtHand()}.
	 */
	@Nullable
	public com.dooglemaps.data.UnderwaterApproach.Approach underwaterApproach()
	{
		for (RunStop stop : planner.getRemaining())
		{
			for (FarmPatch patch : stop.getPatches())
			{
				com.dooglemaps.data.UnderwaterApproach.Approach approach =
					com.dooglemaps.data.UnderwaterApproach.forPatch(patch);
				if (approach != null)
				{
					return approach;
				}
			}
		}
		return null;
	}

	/**
	 * The same approach, but only once the player is standing at it.
	 *
	 * <h2>Why the broad answer will not do here</h2>
	 *
	 * {@link #underwaterApproach()} is true from the first tick of any run with a seaweed or
	 * coral stop on it, and gets away with it because what it feeds is a scene outline: the steps
	 * are not in the scene anywhere else, so a premature answer draws nothing. A line of text has
	 * no such protection. "Wear your diving gear before the steps down" therefore sat on the
	 * travel panel for the whole run — through the herb stops, the bank leg and every hop in
	 * between — telling a player to suit up for a dive several teleports away. Reported from
	 * play: the gear should not be asked for until the dive is what is actually happening.
	 *
	 * <p>The player's own region rather than the route's destination, and that is deliberate.
	 * {@code getCurrentDestinations()} can be every outstanding target at once rather than the
	 * one Shortest Path settled on — {@code GuideStatus.destination} says as much, and only names
	 * a stop when exactly one matches — so "are we heading there" is a question the route cannot
	 * always answer, and a wrong answer here is the bug this exists to fix. Where the player is
	 * standing is never ambiguous.
	 *
	 * <p>It also happens to be the moment the instruction means anything. The approach region is
	 * the shore the router stops at, the steps down are in it, and the whole point of the line is
	 * the ordering of the two actions taken there.
	 */
	@Nullable
	public com.dooglemaps.data.UnderwaterApproach.Approach underwaterApproachAtHand()
	{
		com.dooglemaps.data.UnderwaterApproach.Approach approach = underwaterApproach();
		if (approach == null)
		{
			return null;
		}

		WorldPoint player = playerLocation();
		return player != null && player.getRegionID() == approach.getRegionId()
			? approach
			: null;
	}

	/** The object the route's next hop goes through, or null. Live, like the transports. */
	@Nullable
	public String routeObjectName()
	{
		return planner.getNextTransportObject();
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
	/**
	 * One trip to the leprechaun's compost, said once.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"in the infobox plan I have withdraw compost listed multiple times"</i>. A Falador stop
	 * emitted it three times over, once per patch — from that run's own log:
	 *
	 * <pre>
	 * 4773=WITHDRAW_COMPOST; 4773=APPLY_COMPOST;
	 * 4771=WITHDRAW_COMPOST; 4771=APPLY_COMPOST;
	 * 4772=WITHDRAW_COMPOST; 4772=APPLY_COMPOST;
	 * </pre>
	 *
	 * <p>Each patch asks for its own compost, which is right — the treating is per patch. The
	 * <b>withdrawal</b> is not: {@code GuidePlan.addCompostSteps} already takes
	 * {@code patchesToTreat} and words the step as enough for all of them, so three copies each
	 * asking for the full amount is one instruction printed three times.
	 *
	 * <h2>Keyed on the tier, not on the action</h2>
	 *
	 * Unlike {@link #collapseDuplicateNotes}, which keeps exactly one. A split herb type can want
	 * ultracompost on the protected patches and supercompost on the rest — see
	 * {@code CompostSelectionStore} — and those are two real trips for two different buckets.
	 * Collapsing by action alone would drop one of them and send the run out short.
	 *
	 * <p>Same re-derived-every-tick property the note collapse relies on: once the withdrawal is
	 * made the step stops being generated at all, so nothing has to remember that it was shown.
	 */
	private static void collapseDuplicateWithdrawals(List<GuideStep> steps)
	{
		java.util.Set<Integer> seen = new java.util.HashSet<>();
		for (java.util.Iterator<GuideStep> it = steps.iterator(); it.hasNext(); )
		{
			GuideStep step = it.next();
			if (step.getAction() == GuideAction.WITHDRAW_COMPOST
				&& !seen.add(step.getItemId()))
			{
				it.remove();
			}
		}
	}

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
		// The latch holds you to the patch you are part way through, and it outranks the
		// ordering the list arrived in — including {@code binsFirst}. That is what it is for,
		// and it is why moving a hungry bin to the front of {@code ordered} changed nothing on
		// screen: this runs afterwards and puts the patch back.
		//
		// It must not hold when the job it is holding you to cannot be done. A full pack and a
		// harvest is exactly that: you cannot pick into no slots, so "carry on with this patch"
		// is an instruction with no next click, and the thing that unblocks it — the bin beside
		// the patch, which binsFirst has already put at the front — is the one the latch is
		// hiding. Reported from play repeatedly as the bin never being offered mid harvest.
		boolean stuck = working != null && cannotCarryOn(patchIn(ordered, working), stop);

		if (working != null && !stuck)
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
		//
		// Skipped while stuck for the same reason: the twin's work is another harvest, and a
		// full pack blocks that one too.
		if (working != null && !stuck)
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

		// Back to whatever the detour interrupted, before the ordering gets a say.
		FarmPatch resumed = resumeInterrupted(ordered, stop);
		if (resumed != null)
		{
			return resumed;
		}

		// The detour the block sent you on, and the patch it interrupted, are BOTH remembered.
		//
		// The first attempt at this simply declined to move the latch while stuck, on the
		// reasoning that a blocked patch is interrupted rather than finished. That returned you
		// to the allotment correctly — and it returned you the instant the bin gave back a single
		// slot, with the bin still open and fodder still in the pack. Reported from play at
		// Falador: told to carry on harvesting before the fodder was in the bin. The detour has a
		// job of its own and has to be allowed to finish it.
		//
		// So the latch moves to the detour as it always did, and what is added is the memory of
		// where it came from. The detour keeps the latch for as long as it has work — which is
		// the ordinary stickiness, applied to the bin — and when it runs out, the handoff below
		// puts you back on the half-picked patch instead of the first thing in the ordering.
		for (FarmPatch patch : ordered)
		{
			if (!outstandingFor(patch, stop).isEmpty())
			{
				if (stuck && !patch.getKey().equals(working))
				{
					interrupted = working;
				}
				working = patch.getKey();
				return patch;
			}
		}

		// Unreachable while stuck, and worth knowing why: cannotCarryOn only answers true for a
		// patch that has outstanding steps, so the loop above must have found at least that one.
		working = null;
		interrupted = null;
		return null;
	}

	/**
	 * The patch a detour interrupted, so the guide can hand you back to it.
	 *
	 * <p>Set only when a full pack forces the latch off a half-picked patch, and read only when
	 * whatever it was forced onto has finished. Cleared with {@link #working} — the stop is the
	 * scope, and walking out of it forgets both.
	 *
	 * <p>One deep, deliberately. A detour cannot itself be interrupted: {@link #cannotCarryOn}
	 * only answers true for a patch whose next step is a harvest, and the things a full pack
	 * sends you to — a bin to fill, the leprechaun — are never harvests. A stack would be
	 * machinery for a state that cannot arise.
	 */
	@Nullable
	private String interrupted;

	/**
	 * Hands you back to the patch a full pack made you leave, once the detour is done.
	 *
	 * <h2>Why the ordering cannot do this by itself</h2>
	 *
	 * Because the ordering does not know you were part way through anything. It is distance with
	 * {@code binsFirst} applied, so when the bin stops asking for fodder the next patch in line
	 * wins — at Falador that is whichever allotment is nearest, and at Ardougne it was the herb
	 * patch. Both reported from play, and both are the same missing fact: the run left a job
	 * unfinished and nothing wrote that down.
	 *
	 * <p>Checked before the generic fallback and after the ordinary latch, so it only ever
	 * decides the case it is for: the latched patch has run out of work, and the patch it
	 * displaced still has some.
	 */
	@Nullable
	private FarmPatch resumeInterrupted(List<FarmPatch> ordered, RunStop stop)
	{
		if (interrupted == null)
		{
			return null;
		}

		FarmPatch patch = patchIn(ordered, interrupted);
		if (patch == null || outstandingFor(patch, stop).isEmpty())
		{
			// Finished some other way, or no longer at this stop. Nothing to go back to.
			interrupted = null;
			return null;
		}

		// Still blocked, so going back would be the same dead end that caused the detour. Leave
		// the memory in place and let the ordering offer whatever else there is.
		if (cannotCarryOn(patch, stop))
		{
			return null;
		}

		interrupted = null;
		working = patch.getKey();
		return patch;
	}

	/**
	 * Whether the patch the latch is holding has nothing you could actually click next.
	 *
	 * <p>One case, and it is the one that matters: its next step is a harvest and there is no
	 * room to harvest into. Deliberately narrow — the latch exists so the guide does not hop
	 * between patches mid-job, and every other kind of "outstanding" step is still performable
	 * with a full pack.
	 */
	private boolean cannotCarryOn(@Nullable FarmPatch patch, RunStop stop)
	{
		if (patch == null || carried.getFreeSlots() > 0)
		{
			return false;
		}
		List<GuideStep> its = outstandingFor(patch, stop);
		return !its.isEmpty() && its.get(0).getAction() == GuideAction.HARVEST;
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
			new ProtectionBudget(payments, seed -> protection.isProtecting(group, seed)),
			// The patch in front of the player gets the best seed still going. See
			// SeedAllocation.forPatches: the ranking was always the player's click order, but the
			// scarce seed was being reserved for whichever patch sorted first by key.
			patchUnderfoot(plantable));

		allocations.put(group.getKey(), allocation);
		return allocation;
	}

	/**
	 * Whether a spirit tree hop is one this account can actually take.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"just got routed to the farming guild via POH spirit tree - farming guild. Don't have
	 * that yet, I see the jewellery box is also highlighted though."</i> Five of the ten spirit
	 * tree destinations are farm patches rather than fixed network stops, and a tree is only in
	 * the network once somebody has grown one. Shortest Path plans through them regardless — it
	 * has one boolean for spirit trees and no per-destination control — and this plugin was
	 * agreeing, so the guild's tree was outlined beside the jewellery box that genuinely reaches
	 * the guild by skills necklace.
	 *
	 * <p>The patch state is the answer, and it is one this plugin has and the router does not:
	 * the guild's spirit tree patch read {@code varbitValue 0, WEEDS} throughout that session.
	 *
	 * <p><b>Permissive by construction.</b> Only an <i>empty</i> patch says no. Anything standing
	 * in it — growing, diseased, needing a check, or a state we have not seen before — says yes,
	 * and an unseen patch says yes as well. Wrongly hiding a teleport the player owns is a far
	 * worse failure than wrongly offering one they do not, and it is the same direction
	 * {@code SpiritTrees} errs in for the planting cap.
	 */
	public boolean spiritTreeUsableFor(String destination)
	{
		FarmPatch patch = HouseTeleports.spiritTreePatchFor(destination);
		if (patch == null)
		{
			// A network stop, or not a spirit tree destination at all. Nothing to grow.
			return true;
		}

		PatchProjection projection = growthTimer.project(patch, patches.get(patch));
		return projection == null || !projection.isEmpty();
	}

	/** Allocations built this tick, cleared at the start of the next. */
	private final Map<String, SeedAllocation> allocations = new java.util.HashMap<>();

	/**
	 * How close counts as being at a patch, for {@link #patchUnderfoot}.
	 *
	 * <p>Ten, the same number {@code RunPlanner.ARRIVED_TILES} uses for arriving at a stop. The
	 * two are answering the same question — is the player <i>here</i> — and having them disagree
	 * would mean the run considered you arrived while the allocation did not.
	 */
	private static final int UNDERFOOT_TILES = 10;

	/**
	 * The patch the player is standing at, out of the ones this group can still plant.
	 *
	 * <p>One value per tick, which is what lets {@link #allocations} stay keyed by group alone:
	 * the answer cannot differ between two patches asking in the same tick, because it is about
	 * where the <i>player</i> is rather than which patch is asking.
	 *
	 * <p>Null away from the patches, at the bank and mid-journey, and that is the right answer
	 * there: with nobody standing anywhere the key order is as good a claim as any, and it is the
	 * one the loadout is pricing against.
	 */
	@Nullable
	private FarmPatch patchUnderfoot(List<FarmPatch> plantable)
	{
		WorldPoint player = playerLocation();
		if (player == null)
		{
			return null;
		}

		FarmPatch nearest = null;
		int best = Integer.MAX_VALUE;
		for (FarmPatch patch : plantable)
		{
			WorldPoint where = locations.getLocation(patch);
			if (where == null || where.getPlane() != player.getPlane())
			{
				continue;
			}
			int distance = player.distanceTo(where);
			if (distance <= UNDERFOOT_TILES && distance < best)
			{
				best = distance;
				nearest = patch;
			}
		}
		return nearest;
	}

	// A public stepsFor(patch) overload lived here, documented for a panel's per-patch view
	// that was never built. It read the tick-scoped allocations map with no synchronisation,
	// so the day a Swing caller arrived it would have raced onGameTick's clear() — a corrupt
	// HashMap at worst, a wrong allocation shown at best. Deleted rather than fixed: dead
	// code cannot be wrong, and a future panel wants the GuideStatus snapshot anyway.

	private List<GuideStep> stepsFor(FarmPatch patch, int patchesToTreat,
		Map<Integer, Integer> compostToKeep)
	{
		return stepsFor(patch, patchesToTreat, compostToKeep, false);
	}

	/**
	 * As above, optionally forced to the harvest-shaped subset whatever the group's own run
	 * option says — for the guild patches the contract holds back, whose picking is offered
	 * while their planting is not.
	 */
	private List<GuideStep> stepsFor(FarmPatch patch, int patchesToTreat,
		Map<Integer, Integer> compostToKeep, boolean harvestShapedOnly)
	{
		PatchProjection projection = growthTimer.project(patch, patches.get(patch));
		if (projection == null)
		{
			return new ArrayList<>();
		}

		// A compost bin shares nothing with a patch but the varbit machinery - no seed, no
		// group allocation, no protection - so it branches off before any of that is asked.
		// Off the snapshot rather than the projection, for the two counts only the snapshot
		// carries; see CompostBinPlan's class note.
		//
		// The one thing the snapshot cannot answer rides along separately. A bin is marked
		// health-check-required, so the projection never promotes a finished closed bin to
		// HARVESTABLE - and isReady() is the same test RunPlanner.binActionable keeps the
		// stop open on, so passing it is what stops the guide and the planner disagreeing.
		com.dooglemaps.data.CompostBin bin =
			com.dooglemaps.data.CompostBin.forType(patch.getImplementation());
		if (bin != null)
		{
			noteMissingAsh(bin, patch, projection);
			return CompostBinPlan.forBin(bin, patch, patches.get(patch), compostRun, carried,
				leprechaun, projection.isReady(), compostToKeep);
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

		// Is or will be protected, which is not the same as "still owes a payment". The payment
		// step stands down the moment the farmer takes it; the crop is at its most protected
		// exactly then, so the compost question has to be asked of this rather than of the
		// step's own flag. See GuidePlan's paidToProtect parameter.
		boolean paidToProtect = alreadyPaid || protection.isProtecting(group, inGround);

		// The tier the plan will actually use, asked here so the downgrade can be announced
		// once rather than from inside a pure function called per patch per tick.
		CompostTier wantedTier = compost.get(group);

		// Suppressed before the note, not after. A bucket withheld because the farmer is being
		// paid is not a downgrade — nothing is missing and nothing needs fetching — and
		// announcing "using compost instead of ultracompost" there would be the plugin
		// apologising for a choice it made on purpose.
		if (com.dooglemaps.timer.CropYieldModel.compostWastedOnProtected(
			chosen != null ? chosen : inGround, paidToProtect))
		{
			wantedTier = CompostTier.NONE;
		}
		else
		{
			noteCompostDowngrade(group, wantedTier,
				GuidePlan.usableCompost(wantedTier, carried, leprechaun));
		}

		return GuidePlan.forPatch(projection,
			snapshot == null ? null : snapshot.getCompost(),
			group, chosen, seeds, compost, payToClear, carried, leprechaun, barbarianFarming,
			!alreadyPaid && protection.isProtecting(group, inGround),
			paidToProtect,
			harvestShapedOnly || !fullRun, patchesToTreat,
			binHereWants(patch, projection.getProduce()),
			itemNames);
	}

	/**
	 * Whether a bin at this patch's stop is waiting for the crop this patch is about to give.
	 *
	 * <p>The one thing {@code GuidePlan} cannot work out for itself: it is a pure function of one
	 * patch, and this is a fact about the patch next to it. Passed in rather than looked up there
	 * so that stays true.
	 *
	 * <p>Its whole job is to stop the leprechaun's "note this" and the bin's "put this in me"
	 * being given for the same crop at the same moment, with the note winning and quietly making
	 * the produce useless to the bin. See {@code GuidePlan}'s full-pack branch.
	 */
	private boolean binHereWants(FarmPatch patch, com.dooglemaps.data.Produce produce)
	{
		if (produce == null || patch.getRegion() == null)
		{
			return false;
		}

		for (FarmPatch sibling : patch.getRegion().getPatches())
		{
			if (fodderWantedBy(sibling) == produce.getItemID())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The crop this bin would take off you right now, or {@code NO_FILL} if it is not a bin,
	 * not open, or holding out for more than you are carrying.
	 *
	 * <h2>Why "would take", and not "is allowed to take"</h2>
	 *
	 * This began as the looser test — an open bin plus a crop on the fodder list — and that is
	 * not the same statement. {@code CompostBinPlan} declines to offer a token fill: below a
	 * third of the bin the step is not worth the click, and the bin keeps its contents across
	 * runs anyway. So an open bin, three watermelons, and a full pack answered <i>yes, the bin
	 * wants these</i> to the branch that suppresses the leprechaun, while the bin itself
	 * produced no step at all. Nothing to note, nothing to fill, and the harvest that needed a
	 * free slot still being asked for.
	 *
	 * <p>Asking {@link CompostBinPlan#fodderFor} instead means there is exactly one rule about
	 * what a bin will take, in the class that acts on it, and no way for the two to drift.
	 */
	private int fodderWantedBy(FarmPatch bin)
	{
		com.dooglemaps.data.CompostBin size =
			com.dooglemaps.data.CompostBin.forType(bin.getImplementation());
		int room = binRoom(bin);
		if (size == null || room <= 0)
		{
			return com.dooglemaps.state.CompostRunStore.NO_FILL;
		}

		int fill = CompostBinPlan.fodderFor(size, room, compostRun, carried);
		noteBinDecision(bin, size, room, fill);
		return fill;
	}

	/**
	 * How much more this bin will take, or 0 when it wants no produce at all.
	 *
	 * <h2>The snapshot's stage, never the projection's</h2>
	 *
	 * {@code CompostBinPlan}'s class note says why: <i>"the projection exists to move a crop
	 * forward through time, and in doing so it flattens the two numbers a bin turns on: a
	 * FILLING bin's item count is not carried at all"</i>. Read from the projection, this number
	 * froze wherever the projection left it.
	 *
	 * <p>Caught in the log at the Farming Guild's big bin: <b>"room for 27"</b> on every line
	 * while the player filled it from 25 to 30, and still <b>"room for 27 -&gt; fill with
	 * 5982"</b> on the tick it was full and {@code CompostBinPlan} was already saying to close
	 * it. The two halves of the plugin were reading one bin and disagreeing about it.
	 *
	 * <p>The fill step was never the casualty — {@code CompostBinPlan} has always used the
	 * snapshot. What was wrong is everything gated on <i>does this bin still want feeding</i>:
	 * {@link #binHereWants} suppresses the leprechaun's note for a crop a bin waits on, and
	 * {@link #binsFirst} decides where the bin sits in the stop. A full bin went on claiming both.
	 *
	 * <p>Extracted so those callers and {@link #fodderWantedBy} cannot drift apart again — the
	 * disagreement above was two copies of one sum, and this is the sum.
	 */
	private int binRoom(FarmPatch bin)
	{
		com.dooglemaps.data.CompostBin size =
			com.dooglemaps.data.CompostBin.forType(bin.getImplementation());
		if (size == null)
		{
			return 0;
		}
		PatchProjection projection = growthTimer.project(bin, patches.get(bin));
		if (projection == null)
		{
			return 0;
		}

		// Room in it, which is the only state that wants produce. A ready bin wants buckets and
		// a composting one wants nothing at all.
		if (projection.getCropState() == com.dooglemaps.data.CropState.EMPTY)
		{
			return size.getCapacity();
		}
		if (projection.getCropState() != com.dooglemaps.data.CropState.FILLING)
		{
			return 0;
		}

		com.dooglemaps.state.PatchSnapshot snapshot = patches.get(bin);
		return snapshot == null ? 0 : Math.max(0, size.getCapacity() - (snapshot.getStage() + 1));
	}

	/** The last decision logged for each bin, so each is said once per distinct answer. */
	private final Map<String, String> loggedBinDecision = new java.util.HashMap<>();

	/**
	 * Says what a bin at this stop was offered and why, once per distinct answer.
	 *
	 * <p>This has been reported three times in different clothes — the bin never offered, the
	 * bin offered too late, the guide asking for another watermelon with nowhere to put it — and
	 * each time the answer was a handful of live counts that had all moved on before it could be
	 * looked at. The inputs are: whether fodder is on at all, which crops are allowed, how many
	 * of them are in the pack, how much room the bin has, and whether the pack is full (which
	 * waives the minimum). This line carries the lot.
	 */
	private void noteBinDecision(FarmPatch bin, com.dooglemaps.data.CompostBin size, int room,
		int fill)
	{
		// Per bin, not one field for all of them. Every stop's bins are asked each tick by the
		// idle report, so a single "last answer" field was overwritten by the next bin and every
		// line re-logged on every tick - three a second in play.
		String key = room + "#" + fill + "#" + carried.getFreeSlots();
		if (key.equals(loggedBinDecision.get(bin.getKey())))
		{
			return;
		}
		loggedBinDecision.put(bin.getKey(), key);

		java.util.List<String> held = new java.util.ArrayList<>();
		for (int crop : compostRun.getFodderCrops())
		{
			held.add(crop + "x" + carried.getInventoryCount(crop));
		}
		log.info("Compost bin {} ({}): room for {}, fodder {} (allowed {}), {} free slots -> {}",
			bin.getKey(), size, room, compostRun.isFodderEnabled() ? "on" : "off", held,
			carried.getFreeSlots(),
			fill == com.dooglemaps.state.CompostRunStore.NO_FILL
				? "nothing it will take" : "fill with " + fill);
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

	/**
	 * Compost in the pack that this stop is about to use, by bucket item id.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"when I'm foddering the bin I'm told to go deposit my compost back at the lep before I
	 * use them"</i>. A bin and its allotments are one stop, so the run withdraws four
	 * ultracompost for the patches and then walks past the bin — and {@code addDepositStep}
	 * handed over every filled bucket in the pack, because "carrying compost onward buys
	 * nothing". True of compost that came <i>out of the bin</i>, and false of compost withdrawn
	 * thirty seconds ago for the patches at this very stop. The step order from the session log
	 * says it plainly:
	 *
	 * <pre>
	 * 6192.4774=WITHDRAW_COMPOST; 6192.4774=APPLY_COMPOST; 6192.4772=HARVEST;
	 * 6192.4771=HARVEST; 6192.4775=DEPOSIT_COMPOST; 6192.4773=HARVEST;
	 * </pre>
	 *
	 * Withdraw, use one, store the rest — with three allotments still to be picked that will each
	 * want a bucket. That is the back-and-forth to the leprechaun.
	 *
	 * <p>{@code CompostBinPlan}'s own note already spotted half of this — <i>"the buckets are not
	 * clutter in that case, they are the compost for the patches being replanted at this same
	 * stop"</i> — but the remedy was to move the deposit <i>after</i> the fill rather than to stop
	 * depositing what is about to be used. The insight was right and the fix was one step short.
	 *
	 * <p>Per tier, because a stop can mix them, and counted from the same {@link #countWanting}
	 * the withdrawal is sized from — so the two cannot disagree about how many buckets this stop
	 * has a use for.
	 */
	private Map<Integer, Integer> compostThisStopWillUse(RunStop stop)
	{
		Map<Integer, Integer> keep = new java.util.HashMap<>();
		for (CompostTier tier : CompostTier.values())
		{
			if (tier == CompostTier.NONE)
			{
				continue;
			}
			int wanted = compostWanting.computeIfAbsent(
				stop.getRegion().getRegionId() + "#" + tier.name(),
				key -> countWanting(stop, tier));
			if (wanted > 0)
			{
				keep.put(tier.getItemID(), wanted);
			}
		}
		return keep;
	}

	private int countWanting(RunStop stop, CompostTier tier)
	{
		int count = 0;
		for (FarmPatch other : stop.getPatches())
		{
			com.dooglemaps.data.PlantingGroup otherGroup = groups.groupFor(other);
			if (compost.get(otherGroup) != tier)
			{
				continue;
			}

			// Only patches this run will actually plant, which is the only reason a bucket is
			// ever wanted. The tier is a property of the group and survives the line being
			// unticked or set to harvest-only, so without this a Falador allotment being picked
			// and left counted towards "withdraw 4 ultracompost" and one bucket came back unused
			// every time. The same test stepsFor uses to decide whether the patch gets a compost
			// step at all — the withdrawal and the steps it is meant to supply were answering two
			// different questions. Reported from play as the count being unpredictable.
			if (!runTypes.isSelected(com.dooglemaps.data.RunOption.full(otherGroup)))
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
			if (!produce.isLeprechaunNotable() || binAtThisStopWants(stop, produce.getItemID()))
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
			if (binAtThisStopWants(stop, itemId))
			{
				continue;
			}
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
			"Note your " + plural(noteName) + "."));
	}

	/**
	 * Whether a bin at this stop is still waiting for this crop, so noting it would waste it.
	 *
	 * <h2>The two instructions are in direct conflict</h2>
	 *
	 * A bin refuses noted items. So "note your watermelons with the leprechaun" and "put your
	 * watermelons in the bin" cannot both be followed, and following the first makes the second
	 * impossible — the produce is still in your pack, and now useless to the thing standing next
	 * to it. Reported from play, in those words: <i>"I'm getting prompted to note my watermelon
	 * instead of using it on the bin"</i>.
	 *
	 * <p>{@code GuidePlan}'s mid-harvest note already stands down for this. This is the other
	 * one, said as you leave, and it needed the same guard — being later in the list is not
	 * protection when the crop is gone by the time the bin's step is reached.
	 *
	 * <p>Only the crop the bin wants is held back. Everything else in the pack is noted exactly
	 * as before, so a stop with a bin does not stop being tidied up.
	 */
	private boolean binAtThisStopWants(RunStop stop, int itemId)
	{
		if (!compostRun.allowsFodder(itemId))
		{
			return false;
		}
		for (FarmPatch patch : stop.getPatches())
		{
			if (com.dooglemaps.data.CompostBin.forType(patch.getImplementation()) == null)
			{
				continue;
			}
			PatchProjection projection = growthTimer.project(patch, patches.get(patch));
			if (projection != null
				&& (projection.getCropState() == com.dooglemaps.data.CropState.EMPTY
					|| projection.getCropState() == com.dooglemaps.data.CropState.FILLING))
			{
				return true;
			}
		}
		return false;
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
	 * <h2>A crossed boundary is not a jump</h2>
	 *
	 * This used to retarget on the region changing alone, on the reasoning that walking across a
	 * boundary asks once more than strictly needed and asking is cheap. It is not cheap: Shortest
	 * Path answers afresh from the new tile, and its walk-versus-teleport cost comparison can
	 * flip on a one-tile difference — so the route and the hint changed under the player's feet
	 * while they were still following it. Reported from play at Falador: standing on one tile the
	 * guide had drawn as a walk to Taverley, stepping onto the next tile — straddling a region
	 * boundary — flipped the instruction to "Cast Teleport to House ... via Grand Exchange
	 * Portal". So the region changing is necessary but no longer sufficient; it also has to look
	 * like a jump, judged against {@link #lastPlayerTile}:
	 *
	 * <ul>
	 * <li>the previous tile was unknown (see the null check below — that is what a teleport's
	 * LOADING tick leaves behind), or
	 * <li>the previous tile was on a different plane, or
	 * <li>the previous tile is more than {@link #JUMP_TILES} away, further than a single tick of
	 * running could cover.
	 * </ul>
	 *
	 * <p>Only while a run is actually under way.
	 */
	private void retargetIfMoved()
	{
		WorldPoint player = playerLocation();
		if (player == null)
		{
			// Unknown is not "moved". The position store blanks itself for the LOADING state
			// of every teleport, and this handler runs before it re-samples (GameTick
			// subscribers go in class-name order), so the tick after a teleport used to read
			// as region -1 and retarget from nowhere. Nowhere, to the router, is the player's
			// RAW tile — inside the house that is instance space with no map under it, and
			// the answer is an empty path. Worse than useless: the request cleared the route
			// state the in-house retarget a tick later needed (whether the plan already went
			// through the house), so a nexus leg was re-planned from the front door — and
			// the empty answer, arriving late, was taken as that plan. Reported from play,
			// Catherby via the nexus. Waiting one tick for a real tile costs nothing.
			//
			// Also blanks lastPlayerTile, so whatever tile turns up once the loading screen
			// clears is compared against nothing rather than against wherever the player was
			// before the teleport — which is what lets that tile read as a jump below.
			lastPlayerTile = null;
			return;
		}
		int region = player.getRegionID();
		WorldPoint previous = lastPlayerTile;
		lastPlayerTile = player;

		if (region != lastRegion)
		{
			lastRegion = region;
			boolean jumped = previous == null
				|| previous.getPlane() != player.getPlane()
				|| previous.distanceTo(player) > JUMP_TILES;
			if (jumped && planner.isActive())
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
		// The run's own answer first, when it has one. Working the destination out of Shortest
		// Path's reply is inference from evidence that is often briefly unavailable - wiped when a
		// route is re-asked, discarded while instanced, and sometimes carrying hops with no
		// landing worth matching - and every gap is a second where the leg has no name. That was
		// harmless until the nexus row and the jewellery box line started being matched BY
		// destination: a nameless leg stops highlighting the thing you are meant to click.
		// Reported from play as the Catherby teleport dropping out of the infobox and the nexus
		// list on the way into the house. See RunPlanner.committedStop.
		RunStop committed = planner.committedStop();
		if (committed != null && remaining.contains(committed))
		{
			lastNamedStop = committed;
			return committed;
		}

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

		// The router having said nothing is not the same as the leg having changed.
		//
		// This used to be gated on being inside the house, on the grounds that "an overworld
		// walking leg with no transports genuinely might be heading somewhere new". True of a
		// leg whose answer arrived and matched nothing — which is the case above, and still
		// returns null. Not true of one where the answer is <b>absent</b>: the route is wiped
		// the moment a fresh one is asked for, discarded outright while instanced, and empty for
		// a second or two around every teleport. None of that is news about where you are going.
		//
		// The house gate was too narrow for the case it was written for. Reported from play as
		// the Catherby teleport dropping out of the nexus list on the way into the house, and
		// the log shows the leg was already nameless five seconds BEFORE the house was entered —
		// outside it, where this did not apply. The nexus row and the jewellery box line are
		// matched by destination, so a nameless leg silently stops highlighting the thing you
		// are meant to click.
		//
		// Still bounded: the name only stands while its stop is outstanding, and a route that
		// does arrive replaces it immediately.
		if (points.isEmpty() && lastNamedStop != null && remaining.contains(lastNamedStop))
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
		//
		// claimsRegion rather than the bare id, for the stop that stands in more ground than it
		// is filed under: the coral nurseries are at 13194 and the Great Conch stop at 12581, so
		// standing among the patches found no stop, produced no steps, and left the player told
		// to travel to a place they were already in. Reported from play.
		for (RunStop stop : planner.getRemaining())
		{
			if (stop.claimsRegion(player.getRegionID()))
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
		// claimsRegion, not the bare id. A stop can stand in more ground than it is filed
		// under - the coral nurseries are region 13194 while the Great Conch stop is 12581 -
		// and RunStop.claimsRegion exists precisely so every caller answers that the same way.
		// This one was added afterwards and did not, which is how the leaving errands
		// disappeared underwater: the stop completes when the last nursery is planted or paid
		// for, appendLeavingErrandsAtFinishedStop asks this, gets no, and the note that would
		// have turned a pack of coral into one stack is never offered. Reported from play as
		// not being prompted to note at the nurseries.
		if (stop.claimsRegion(player.getRegionID()))
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
		binsFirst(ordered);
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
	/**
	 * Moves a compost bin to the front of its stop, or to the back, depending what it is waiting
	 * for.
	 *
	 * <h2>Emptying goes first; being fed goes last</h2>
	 *
	 * A <b>ready</b> bin goes to the very front, applied after {@link #contractFirst} so it
	 * outranks even the contract. The owner's reasoning is about inventory rather than priority:
	 * emptying a bin frees the pack and restocks the compost every other patch at that stop is
	 * about to want. Doing it last means arriving at the herbs with fifteen slots of produce
	 * still in hand and no ultracompost, which is the trip the bin was on the run to prevent.
	 *
	 * <p>A bin waiting to be <b>filled</b> now goes last, and it has to: the seven beside the
	 * allotments are fed from the harvest, and you cannot fill a bin from a harvest that has not
	 * happened. Putting it first there would offer a fill you are not holding, produce no step,
	 * and — through the idle report — quietly complete the stop.
	 *
	 * <p>Split on what the bin is waiting for rather than on which bin it is, so the guild's big
	 * one is ordered correctly both ways: last on the trips its own allotments feed it, first on
	 * the trips it arrives already carrying a banked fill.
	 *
	 * <p>Either way it costs nothing to be wrong about. A bin cannot be a contract's patch —
	 * Jane never assigns one — and its work shares no tool, seed or payment with anything else
	 * at the stop, so moving it can never take a click away from another patch.
	 */
	private void binsFirst(List<FarmPatch> ordered)
	{
		// Three ranks, stable within each: ready bins, everything else, bins awaiting a fill.
		ordered.sort(java.util.Comparator.comparingInt(this::binRank));
	}

	/** -1 for a bin with compost to collect, 1 for one waiting to be fed, 0 for a patch. */
	private int binRank(FarmPatch patch)
	{
		if (com.dooglemaps.data.CompostBin.forType(patch.getImplementation()) == null)
		{
			return 0;
		}
		PatchProjection projection = growthTimer.project(patch, patches.get(patch));
		if (projection == null)
		{
			// Never seen it, so nothing is known about what it holds. Treated as waiting to be
			// fed, which is the harmless half: a bin that turns out to be ready still gets its
			// emptying steps, just after the patches rather than before them.
			return 1;
		}
		boolean ready = projection.getCropState() == com.dooglemaps.data.CropState.HARVESTABLE
			|| (projection.getCropState() == com.dooglemaps.data.CropState.GROWING
				&& projection.isReady());
		if (ready)
		{
			return -1;
		}

		// A bin waiting to be fed goes last — until the pack is full and it will take what is
		// filling it, at which point it is not an errand to get to afterwards, it is the only
		// thing you can do. Last was right for the ordinary case and a dead end for this one:
		// the harvest that would feed the bin cannot proceed, the note that would free the
		// slots is suppressed precisely because the bin wants the crop, and the bin's own step
		// sits behind the harvest that is stuck. Reported from play as being told to harvest
		// more watermelons with no room for them and never once being offered the bin.
		int fill = fodderWantedBy(patch);
		if (fill == com.dooglemaps.state.CompostRunStore.NO_FILL)
		{
			return 1;
		}
		if (carried.getFreeSlots() <= 0)
		{
			return -1;
		}

		// And when what you are holding would FINISH it, which is the other time last is wrong.
		//
		// Reported from play at the Farming Guild: the big bin four short of full, six
		// watermelons in the pack, and the fill step sitting fourth behind planting an allotment
		// and two more harvests. Everything about that is individually defensible and the sum of
		// it is a bin left open with the crop that would close it in hand.
		//
		// Finishing it, specifically, rather than merely having something it would take. "Some
		// fodder" is true almost continuously once a harvest starts, and hoisting on that would
		// put the bin ahead of the very harvest meant to feed it — which is the dead end the
		// last-rank exists to prevent. Closing it is different in kind: it is a few clicks that
		// end the errand, bank the compost and free the slots for good, where carrying on risks
		// the pack filling or the crop being noted out of reach.
		int room = binRoom(patch);
		return room > 0 && carried.getInventoryCount(fill) >= room ? -1 : 1;
	}

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
