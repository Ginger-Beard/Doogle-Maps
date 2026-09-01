package com.dooglemaps.route;

import com.dooglemaps.bank.ToolNeeds;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmRegion;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.Seed;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.state.SeedSelectionStore;
import com.dooglemaps.state.SeedSource;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.PlayerLocation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.state.PlantingGroups;
import com.dooglemaps.state.ProtectedPatches;
import com.dooglemaps.state.ProtectionSelectionStore;
import com.dooglemaps.state.RunTypeStore;
import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropState;
import com.dooglemaps.timer.DiseaseRisk;
import com.dooglemaps.timer.GrowthTimer;
import com.dooglemaps.timer.PatchProjection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/**
 * Works out where a run goes, and keeps track of how far through it you are.
 *
 * <h2>Why there is no route-ordering algorithm here</h2>
 * The obvious design is to compute a tour: cluster nearby patches, order the clusters,
 * walk the list. It is also wrong, and the data says so plainly. Straight-line distance
 * puts Entrana 97 tiles from Catherby and Brimhaven 124 from Entrana — both islands you
 * cannot walk to. Any distance threshold that correctly groups Taverley with Falador's
 * tree (68 tiles apart, genuinely walkable) also confidently groups Entrana with Catherby.
 * Getting that right needs real map topology, teleport unlocks and transport availability.
 *
 * <p>Shortest Path already has all of that, and its API takes a <i>set</i> of targets and
 * routes to whichever is cheapest to actually reach. So the planner does not order
 * anything: it hands over every stop still outstanding and lets the router pick. Service
 * one, drop it, hand over the rest.
 *
 * <p>Grouping falls out of that for free. After Falador's tree patch, Taverley is simply
 * the cheapest remaining target, so the route continues on foot — nobody had to know they
 * were a pair. The same logic declines to walk to Entrana, because for the router it is not
 * cheap at all.
 *
 * <p>What the player <i>does</i> choose is which patch types are in the run — a herb run,
 * or herbs with allotments and flowers. That is a selection, not a routing decision, and
 * because those patch types share a region it costs no extra travel anyway.
 */
@Slf4j
@Singleton
public class RunPlanner
{
	private final AvailabilityProfile availability;
	private final PatchLocationStore locations;
	private final BankLocationStore banks;
	private final SeedSelectionStore selection;
	private final SeedInventoryStore seedInventory;
	private final PatchStateStore stateStore;
	private final GrowthTimer growthTimer;
	private final ShortestPathIntegration router;

	/**
	 * The bin choices, for the one question stop planning has to ask of them.
	 *
	 * <p>Only the fodder toggle is read here. Which crops are allowed is the guide's business,
	 * and what to bank is the loadout's; this class needs to know one thing — whether the seven
	 * bins beside the allotments are the run's concern at all.
	 */
	private final com.dooglemaps.state.CompostRunStore compostRun;
	private final PlayerLocation playerLocation;

	/**
	 * Which farming tools the run needs and where they are.
	 *
	 * <p>A one-way edge, like everything else this class calls: {@code ToolNeeds} holds no
	 * reference back here and reads only leaf stores, so it cannot take the locks in the other
	 * order. See {@code docs/NOTES.md} on lock ordering.
	 */
	private final ToolNeeds tools;

	/** Which herb patches this account's unlocks make immune. A leaf, like everything else here. */
	private final ProtectedPatches protectedPatches;
	private final PlantingGroups groups;
	private final ProtectionSelectionStore protection;

	/** The player's ticked run options, for the harvest-only filter. Named apart from the
	 * planner's own {@code runTypes} field, which is the live run's patch types. */
	private final RunTypeStore runOptions;
	private final com.dooglemaps.DoogleMapsConfig config;

	/** Stops still to do, keyed by region id so a patch can be found quickly. */
	private final Map<Integer, RunStop> stops = new LinkedHashMap<>();

	/**
	 * Regions whose stop has already been reported finished.
	 *
	 * <p>Completion is derived from patch state, so it stays true once reached — this is what turns
	 * that level into an edge, so the router is asked for a new route once rather than on every
	 * subsequent change. Cleared with the run. See {@link #onPatchChanged}.
	 */
	private final Set<Integer> announced = new java.util.HashSet<>();

	/**
	 * Regions the player has waved past for the rest of this run.
	 *
	 * <p>The travel leg's version of skipping a step. A step can be skipped because it exists as
	 * a step; "travel to Harmony" is not a step but a route, so it needed its own escape hatch —
	 * without one, the only way past a destination you are not going to was to stand somewhere
	 * until you had done it. See {@code GuideTracker.skipTravelDestination}.
	 *
	 * <p>Whole regions rather than patch keys because that is what the player is declining: the
	 * journey. Filtered in {@link #getRemaining} rather than removed from {@link #stops}, since
	 * {@link #reviewContract} and {@link #getStopFor} index that map by region and a skipped
	 * stop's patches are still real. Cleared with the run, so "until the next round" comes free.
	 */
	private final Set<Integer> skippedRegions = new java.util.HashSet<>();

	/**
	 * Patch keys the guide has nothing to offer for, so the run stops waiting on them.
	 *
	 * <h2>Why the guide is asked rather than worked out here</h2>
	 *
	 * A patch can be genuinely actionable and still impossible to act on: an empty patch always
	 * wants planting, but not if the allocation gave it no seed; a dead crop always wants clearing,
	 * but not without an axe. Those left the run waiting for something the player could never do.
	 *
	 * <p>Answering it here would mean rebuilding the seed allocation — including the protection
	 * budget it is capped by, which needs the bank and the pack — and then hoping the answer
	 * matched the guide's. Two independent allocations that must agree is exactly the arrangement
	 * {@code SeedAllocation} was written to end. The guide already computes the real one and the
	 * real step list, so it is asked instead, and it reports the whole set fresh each tick: nothing
	 * here can go stale, because nothing here is remembered longer than a tick.
	 *
	 * <p>The set covers <i>every</i> stop in the run, not the one being stood in. It has to,
	 * because {@link #isComplete} is asked of every stop every tick: an exemption that existed
	 * only while the player was present evaporated the moment they left, the skipped stop read
	 * as unfinished again, and the run routed them straight back to a patch it had already said
	 * it was skipping. See {@code GuideTracker.reportIdlePatches}.
	 *
	 * <p>The dependency runs guide → planner, which is the direction it already ran.
	 */
	private volatile Set<String> nothingToDo = Collections.emptySet();

	/**
	 * Whether a run is under way.
	 *
	 * <p>Volatile, and that is not decoration. It is written under this class's monitor and read
	 * through a Lombok getter, which is not synchronised — so without this there is no
	 * happens-before edge between the write and the read at all. The writer is usually the Swing
	 * thread (the Start/Stop button) and the readers are usually the client thread: the bank
	 * filter, the bank highlight overlay and the guide tracker all ask every tick. A missed write
	 * leaves the overlay drawing for a run that has stopped, intermittently and unreproducibly.
	 *
	 * <p>{@code volatile} rather than a synchronised getter because nothing needs this consistent
	 * with {@link #atBankLeg} or with {@link #stops} — every reader wants one flag. See
	 * {@code ShortestPathIntegration}, which does the same for the same reason.
	 */
	@Getter
	private volatile boolean active;

	/** True while the run is still routing to a bank rather than to patches. Volatile; see {@link #active}. */
	@Getter
	private volatile boolean atBankLeg;

	/**
	 * Why the current supply leg exists, for the two flavours whose banks read differently.
	 *
	 * <p>{@code OPENING} is the ordinary collect-before-you-go leg and nothing special-cases
	 * it. {@code GEAR_SWAP} is the trip back after the hespori to put the farming loadout on
	 * again ({@link #reviewGearSwap}); {@code DEPOSIT} is the mid-run trip to shed a full pack
	 * ({@link #reviewDepositTrip}). The guide's supply lines and the bank overlay read this to
	 * say and mark the right thing; the leg's lifecycle is otherwise identical.
	 */
	public enum BankLegReason
	{
		OPENING,
		GEAR_SWAP,
		DEPOSIT
	}

	/** Null while no supply leg is on. Guarded by the monitor beside {@link #atBankLeg}. */
	@Nullable
	private BankLegReason bankLegReason;

	/**
	 * Whether the pack is full <i>of logs, on a run that chops</i>, pushed once a tick by the
	 * guide the same way the withdraw list's answer is — the judgment (free slots, whether
	 * these types swing an axe, and whether the slots hold logs rather than the run's own
	 * supplies) belongs to the carried-items and loadout side, which the planner never asks
	 * directly. See {@code GuideTracker.packFullOfLogs}.
	 */
	private volatile boolean packFull;

	/** The last pushed value, so {@link #reviewDepositTrip} arms on the edge, not the level. */
	private boolean lastPackFull;

	/** One gear-swap trip per run; armed by {@link #reviewGearSwap}, reset by {@code start}. */
	private boolean gearSwapDone;

	/** Whether this run has been in its gear phase at all — the swap trip's precondition. */
	private boolean gearPhaseSeen;

	public void setPackFull(boolean full)
	{
		packFull = full;
	}

	/** The current supply leg's flavour, or null when no leg is on. */
	@Nullable
	public synchronized BankLegReason getBankLegReason()
	{
		return atBankLeg ? bankLegReason : null;
	}

	/** Patch types this run covers, for scoping which seeds it actually needs. */
	private final Set<PatchImplementation> runTypes = EnumSet.noneOf(PatchImplementation.class);

	/**
	 * Whether a supply trip is still owed.
	 *
	 * <p>Set when the run needs one but starts on top of some work instead. Without it the
	 * deferred trip would simply never happen, and the run would arrive at an empty patch with
	 * no seed for it.
	 */
	private boolean supplyOwed;

	/**
	 * The supply sources the current route was drawn for, or null when not collecting.
	 *
	 * <p>The leg visits two containers now, and empties them one at a time. Emptying one changes
	 * where the run still has to go, and the route is posted rather than polled — so without
	 * noticing the change, the line keeps pointing at a vault whose seeds are already in the pack.
	 */
	@Nullable
	private Set<SeedSource> postedSources;

	/**
	 * The withdraw list's answer — anything still to collect — pushed in rather than asked for.
	 *
	 * <h2>What this replaces: a {@code Provider<RunLoadout>} and the cycle it papered over</h2>
	 *
	 * {@code RunLoadout} is built from this planner — it asks {@link #actionableByGroup} which
	 * patches the run will service — and this planner used to ask the loadout back, which is a
	 * cycle Guice would not construct and a {@code Provider} only postponed. The dependency now
	 * runs one way: the loadout reads the planner, and the planner is <b>told</b> the withdraw
	 * list's answer — every tick by {@code GuideTracker.reportIdlePatches}, the same push that
	 * carries the blocked-patch set, and refreshed at the moments that matter mid-tick
	 * (the plugin's and {@code BankCapture}'s calls ahead of {@link #leaveBank()}, and
	 * {@link #start} seeding it from its caller). The rule the Provider's note used to state —
	 * never dereferenced under this monitor — is now structural: there is nothing left to
	 * dereference.
	 */
	private volatile boolean withdrawOutstanding;

	@Inject
	RunPlanner(AvailabilityProfile availability, PatchLocationStore locations,
		BankLocationStore banks, SeedSelectionStore selection, SeedInventoryStore seedInventory,
		PatchStateStore stateStore, GrowthTimer growthTimer, ShortestPathIntegration router,
		PlayerLocation playerLocation, ToolNeeds tools, ProtectedPatches protectedPatches,
		PlantingGroups groups, ProtectionSelectionStore protection, RunTypeStore runOptions,
		com.dooglemaps.state.CompostRunStore compostRun,
		com.dooglemaps.DoogleMapsConfig config)
	{
		this.compostRun = compostRun;
		this.config = config;
		this.runOptions = runOptions;
		this.protection = protection;
		this.groups = groups;
		this.protectedPatches = protectedPatches;
		this.tools = tools;
		this.playerLocation = playerLocation;
		this.availability = availability;
		this.locations = locations;
		this.banks = banks;
		this.selection = selection;
		this.seedInventory = seedInventory;
		this.stateStore = stateStore;
		this.growthTimer = growthTimer;
		this.router = router;
	}

	/**
	 * Starts a run covering the given patch types.
	 *
	 * <p>Only patches the account actually uses are included — availability is a global
	 * invariant, so a run can never contain somewhere you cannot reach.
	 *
	 * @return the stops the run will visit, in no particular order
	 */
	public List<RunStop> start(Set<PatchImplementation> types)
	{
		// The flagless form exists for fixtures, where the withdraw list is empty by
		// construction. Production goes through the two-argument form: RunPanel asks the
		// guide, which asks the loadout, before this planner is ever involved.
		return start(types, false);
	}

	/**
	 * Starts a run covering the given patch types.
	 *
	 * @param withdrawOutstanding the withdraw list's answer for these types, computed by the
	 *                            caller — the planner no longer asks the loadout itself, which
	 *                            is what removed the construction cycle between the two
	 */
	public List<RunStop> start(Set<PatchImplementation> types, boolean withdrawOutstanding)
	{
		this.withdrawOutstanding = withdrawOutstanding;
		synchronized (this)
		{
			stops.clear();
			announced.clear();
			skippedRegions.clear();
			committedRegion = -1;
			stops.putAll(planStops(types));
			runTypes.clear();
			runTypes.addAll(types);
			active = !stops.isEmpty();
		}

		// Published before needsSupplyTrip asks it — the fresh run's phase, not the last
		// run's leftovers. See gearPhaseNow.
		gearPhaseNow = computeGearPhase();

		// Worked out with the lock released, because both walk the availability and patch
		// stores and the order has to stay RunPlanner -> Availability -> PatchStateStore.
		boolean wantsSupplies = active && needsSupplyTrip();
		boolean here = standingAtAStop();
		boolean canBankHere = wantsSupplies && supplyPointIsHere();

		synchronized (this)
		{
			supplyOwed = wantsSupplies;
			bankLegWaived = false;
			runCompletePending = false;
			postedSources = null;
			// Standing on work beats going shopping. The supplies are still owed and the run
			// picks them up once this stop is done, but nothing justifies teleporting away from
			// ripe crops you are already stood next to.
			//
			// Unless the shopping is right there. That rule is about *travel* — it exists so the
			// run does not teleport you off a patch you are standing on — and a bank in the same
			// region costs none. Reported from play: starting a contract run inside the Farming
			// Guild, at the guild's own bank, with no seed and no payment withdrawn, and being
			// told to go and clear the patch. The seed was twenty steps away and the run would
			// have reached the patch unable to do anything there.
			atBankLeg = wantsSupplies && (!here || canBankHere);
			bankLegReason = atBankLeg ? BankLegReason.OPENING : null;
			gearSwapDone = false;
			gearPhaseSeen = false;
			lastPackFull = false;
		}

		// At INFO, and deliberately verbose, because "why did it send me to a bank" has cost
		// several rounds of guessing and every input to that decision is on this one line.
		// Tools are on it for the same reason: they are now one of the things that can send you
		// to a bank, so leaving them off would put the line back to being half an answer.
		log.info("Run planned: {} stops {}; you are in region {} which is {}{}; "
				+ "seeds picked for this run: {}; sources read: {}; still to collect: {}; "
				+ "tools: {} -> {}",
			stops.size(), stopRegions(), playerLocation.getRegionId(),
			here ? "a stop on this run" : "not a stop on this run",
			canBankHere ? " and has a supply point in it" : "",
			describeSelectedForThisRun(), describeSeedSourceAges(), getSupplySources(),
			describeTools(),
			atBankLeg ? "starting at a bank" : "starting where you are");

		if (config.holdClustersUntilReady())
		{
			log.info("Shared plots: {}", describeClusterHolds(types));
		}

		// Said at the same moment and level as the Run-planned line, because the gear phase's
		// verdict once rested on a state nothing refreshed: a stale "weedy" read had a grown
		// boss treated as an ordinary planting errand for half a day, and the decision was
		// invisible after the fact. Reported from play: "I think the state got into a weird
		// position there". The state now refreshes from the guild itself — the sprout by the
		// cave shares the patch's varbit, see FarmingWorldData — but the verdict stays logged,
		// because the fix and the line answer the same question from opposite ends.
		if (types.contains(PatchImplementation.HESPORI))
		{
			log.info("Hespori check: {} - so the gear phase is {}", describeHesporiState(),
				isGearPhase() ? "ON (fight first, farm after)" : "off (ordinary patch)");
		}

		// Outside the lock, deliberately, and for the same reason markServiced and leaveBank
		// do it that way: retargeting posts across threads into another plugin's event bus,
		// and EventBus delivers synchronously. Holding this planner's monitor while arbitrary
		// subscriber code runs is the exact shape of the freeze under investigation, so the
		// rule is that nothing outside this class is ever called with the lock held.
		retarget();
		return getStops();
	}

	/**
	 * Every stop a run over these types would have, without starting anything.
	 *
	 * <p>The whole point is that it is the same computation {@link #start} does, so what the
	 * panel promises before setting off cannot drift from what the run turns out to be.
	 *
	 * <p>In no particular order, and deliberately so: the route is chosen a leg at a time by
	 * whichever remaining stop is cheapest to reach, so there is no tour to show. Ordering
	 * these would be inventing one.
	 */
	public synchronized List<RunStop> previewStops(Set<PatchImplementation> types)
	{
		return new ArrayList<>(planStops(types).values());
	}

	/**
	 * Groups every actionable patch of these types into one stop per region.
	 *
	 * <p>Region is the unit because that is already how the patches cluster — see
	 * {@link RunStop}. Shared by {@link #start} and {@link #previewStops} so the plan the
	 * player is shown and the run they get are the same thing by construction.
	 */
	private Map<Integer, RunStop> planStops(Set<PatchImplementation> types)
	{
		Map<Integer, List<FarmPatch>> byRegion = new LinkedHashMap<>();
		Map<Integer, List<FarmPatch>> heldClusters = new LinkedHashMap<>();
		for (PatchImplementation type : types)
		{
			// The seven beside the allotments arrive by one road only - addOpportunisticBins,
			// below - so that "never a reason to travel" holds however this was called. The
			// ticked set should not carry COMPOST at all (coveredByTheBinTick swaps it for the
			// guild's bin), but a caller that passes the raw type would otherwise plan a stop
			// for a bin in the middle of nowhere, and that is the exact trip being removed.
			if (type == PatchImplementation.COMPOST)
			{
				continue;
			}
			for (FarmPatch patch : availability.getAvailablePatches(type))
			{
				if (!inTheRun(patch) || !isActionable(patch) || heldForRegrowth(patch))
				{
					continue;
				}
				if (clusterHeld(patch, types))
				{
					heldClusters.computeIfAbsent(patch.getRegion().getRegionId(),
						k -> new ArrayList<>()).add(patch);
					continue;
				}
				byRegion.computeIfAbsent(patch.getRegion().getRegionId(), k -> new ArrayList<>()).add(patch);
			}
		}

		// The hold saves a teleport, and only a teleport. A region the run is stopping at
		// anyway — the Farming Guild for its trees, Kourend for its spirit tree — has no
		// teleport left to save, so holding its plot out of the stop merely hid ripe work
		// from a trip already being paid for: the guide stood the player at the guild and
		// never mentioned the ripe herb beside them, and nothing could adopt it mid-run.
		// Held patches therefore ride along wherever a stop exists, and still never create
		// one — the same shape as addOpportunisticBins below.
		heldClusters.forEach((regionId, held) ->
		{
			List<FarmPatch> here = byRegion.get(regionId);
			if (here != null)
			{
				here.addAll(held);
			}
		});

		addOpportunisticBins(byRegion);
		mergeSharedStops(byRegion);

		Map<Integer, RunStop> planned = new LinkedHashMap<>();
		for (List<FarmPatch> patches : byRegion.values())
		{
			FarmRegion region = patches.get(0).getRegion();
			planned.put(region.getRegionId(), new RunStop(region, patches));
		}
		return planned;
	}

	/**
	 * Folds a region into the stop it shares a way in with, when the run is making both.
	 *
	 * <h2>Why a merge rather than a grouping key</h2>
	 *
	 * Grouping by {@code SharedStops.hostOf} up front would be shorter and is wrong in one case
	 * that matters: run the hops without the bush and the group's key is the Champions' Guild
	 * while every patch in it is in Lumbridge. Everything downstream keys on
	 * {@code stop.getRegion().getRegionId()} — routing, arrival, the stop's name — so the stop
	 * would claim to be somewhere it has no patches.
	 *
	 * <p>Done afterwards, the rule states itself: a region joins a stop that <b>already exists</b>,
	 * and otherwise keeps its own. That is the same shape as {@link #addOpportunisticBins}, and
	 * it is why nothing here can create travel or move a stop the run was not already making.
	 *
	 * <p>Appended rather than prepended, so {@code patches.get(0)} stays a host patch and
	 * {@code RunStop.getLocation} still routes to the Champions' Guild rather than to the hops
	 * fifty tiles down the road.
	 */
	/**
	 * Every fold already logged, so a per-tick replan does not repeat them. A set rather than
	 * the single latched string it used to be, because two live folds (the hops, and now
	 * Catherby's fruit tree) alternated through one latch and re-logged each other every
	 * tick — the exact spam the latch existed to stop. See {@link #mergeSharedStops}.
	 */
	private final Set<String> loggedMerges = new LinkedHashSet<>();

	private void mergeSharedStops(Map<Integer, List<FarmPatch>> byRegion)
	{
		for (Integer joining : new ArrayList<>(byRegion.keySet()))
		{
			int host = com.dooglemaps.data.SharedStops.hostOf(joining);
			if (host == joining)
			{
				continue;
			}

			List<FarmPatch> hostPatches = byRegion.get(host);
			if (hostPatches == null)
			{
				// The host is not a stop on this run, so there is nothing to share a trip with.
				continue;
			}

			hostPatches.addAll(byRegion.remove(joining));

			// Once per distinct answer, not once per call. planStops runs from previewStops,
			// which the sidebar's per-tick snapshot asks for - so an unconditional line here is
			// one every 0.6s for the whole run. Reported from play at the Farming Guild, as a
			// wall of identical DEBUG. Same latch noteStopOrder uses, and for the same reason:
			// the interesting event is the decision changing.
			if (loggedMerges.add(joining + "->" + host))
			{
				log.debug("Region {} folded into the {} stop - one arrival serves both", joining,
					hostPatches.get(0).getRegion().getName());
			}
		}
	}

	/**
	 * Adds the allotment bins to stops the run is already making, and to no others.
	 *
	 * <h2>A bin beside the allotments is never a reason to travel</h2>
	 *
	 * It has no bank near it — Catherby ~23 tiles, Falador ~65, Ardougne ~96, and Civitas,
	 * Canifis and Prifddinas none the plugin ships at all — so arriving at one with nothing to
	 * put in it is a wasted trip, and hauling fifteen un-noted items to it across the map is the
	 * complaint this whole change came from. What it <i>is</i> good for is the harvest you are
	 * holding when you finish the patches it sits beside, which is what players do by hand.
	 *
	 * <p>So it is bolted onto a region that already has a stop and never creates one. That is
	 * the whole of "opportunistic", expressed as one rule in one place — a filter applied
	 * afterwards would have said the same thing less directly, and threading it through
	 * {@link #isActionable} would have meant that method consulting the run, which it must not.
	 *
	 * <p>Done here rather than through {@code types} because the ticked set no longer carries
	 * {@code COMPOST} at all: {@code CompostBin.coveredByTheBinTick} narrows the line to the
	 * guild's big bin, which is deliberate — it is what keeps these seven out of the loadout,
	 * out of {@code binWork} and out of every bank row.
	 */
	private void addOpportunisticBins(Map<Integer, List<FarmPatch>> byRegion)
	{
		if (!compostRun.isFodderEnabled())
		{
			// The toggle governs the seven entirely, filling and emptying alike. Off is the same
			// state as an unticked run line: the run does not touch them.
			return;
		}

		for (FarmPatch patch : availability.getAvailablePatches(PatchImplementation.COMPOST))
		{
			List<FarmPatch> here = byRegion.get(patch.getRegion().getRegionId());
			if (here == null || !isActionable(patch))
			{
				continue;
			}
			here.add(patch);
		}
	}

	/**
	 * Adopts the allotment bins into stops the run is already making, once a tick.
	 *
	 * <h2>Why planning them once was not enough</h2>
	 *
	 * {@link #addOpportunisticBins} runs inside {@link #planStops}, which runs at {@link #start}
	 * and never again. Everything it decided is therefore frozen at the moment the player pressed
	 * the button, and two ordinary things happen after that:
	 *
	 * <ul>
	 *   <li><b>The setting is switched on mid-run.</b> A player who ticks "fill bins from your
	 *       harvest" after starting gets nothing for the rest of the trip, silently — the bins
	 *       were never put in the stops and no amount of standing next to one changes it. This is
	 *       the reported case: nineteen watermelons picked at Ardougne, its bin empty, the crop on
	 *       the fodder list, and no fill ever offered.</li>
	 *   <li><b>A bin becomes worth servicing later.</b> One that was still composting at the start
	 *       finishes on the clock an hour into the run, and a plan made before that cannot know.</li>
	 * </ul>
	 *
	 * <p>So it is a poll, exactly like {@link #reviewContract()} — which exists for the same shape
	 * of problem, a patch the plan did not have — and it borrows that method's {@code adopt}. The
	 * rule it enforces is unchanged: a bin joins a stop the run is <b>already</b> making and never
	 * creates one, so it still cannot cause a step of travel.
	 */
	public void reviewBins()
	{
		if (!active || !compostRun.isFodderEnabled())
		{
			return;
		}

		// Outside the lock, like every store walk here: RunPlanner -> Availability -> patches.
		List<FarmPatch> joining = new ArrayList<>();
		Set<PatchImplementation> types = runTypesSnapshot();
		for (FarmPatch patch : availability.getAvailablePatches(PatchImplementation.COMPOST))
		{
			// The same question planStops asks before a bin may enter the plan, asked at the
			// same door. Without it this was a side entrance: a held bin was adopted on raw
			// actionability and the completion announcement cleared, and only stillWanted
			// re-holding it a tick later kept the run from travelling — a redundant completion
			// edge and a spare retarget, held safe by coincidence rather than decision.
			if (isActionable(patch) && !clusterHeld(patch, types))
			{
				joining.add(patch);
			}
		}

		synchronized (this)
		{
			for (FarmPatch patch : joining)
			{
				int regionId = patch.getRegion().getRegionId();
				RunStop stop = stops.get(regionId);
				if (stop == null || stop.contains(patch))
				{
					continue;
				}
				stop.adopt(patch);
				// It has work again, so a completion announced before the bin arrived must not
				// keep the stop from being routed to. Same reason reviewContract does this.
				announced.remove(regionId);
				log.debug("Compost bin at {} joined the stop", stop.getName());
			}
		}
	}

	/**
	 * Whether the player actually asked for this patch, as opposed to its patch type.
	 *
	 * <h2>Run options are per group; stop planning was per type</h2>
	 *
	 * Every box on the run panel is a {@link PlantingGroup} — "Herb", "Herb (protected)", "Cactus
	 * (contract)" — but a run is carried around as a set of {@link PatchImplementation}, because
	 * that is what the availability profile is keyed by. The two are not interchangeable, and
	 * collapsing one into the other is how a single tick came to mean far more than it said.
	 *
	 * <p>A farming contract is the case that makes it obvious. Its option is stored as
	 * {@code TREE#contract}, {@code RunTypeStore.typeOf} strips everything from the {@code #} so the
	 * run covers {@code TREE}, and stop planning then swept in <b>every tree patch on the account</b>
	 * — the guild's contract patch and five others nobody asked about. Reported from play as an
	 * eighteen-stop run that should have been twelve: two yew saplings to withdraw instead of one,
	 * twenty-five coconuts to protect a magic tree in another kingdom, and ten cactus spines for the
	 * yew, none of which the player had ticked anything to ask for.
	 *
	 * <p>That type-stripping is not itself wrong — it is what stops a contract-only run from
	 * covering nothing at all, and its own javadoc explains why. The type is the right answer to
	 * "which patches might this run touch"; it is the wrong answer to "which patches does it want",
	 * and only this asks the second question.
	 *
	 * <h2>Deliberately not folded into {@link #isActionable}</h2>
	 *
	 * They look like the same filter and are not. {@code isActionable} asks whether a patch wants
	 * something doing, which is a fact about the patch; this asks whether the run was asked to go
	 * there, which is a fact about the player. {@code isComplete} walks a stop's patches through
	 * {@code isActionable} to decide the stop is finished — so a patch failing <i>this</i> test
	 * would read as needing nothing, and a contract patch adopted mid-run by {@link #reviewContract}
	 * would let its stop finish without the contract being done.
	 */
	private boolean inTheRun(FarmPatch patch)
	{
		// The bins answer to two different questions now, and neither is groupFor's - asked
		// before it because groupFor honestly reports the big bin's own type, whose key no line
		// ever stores. See CompostBin.coveredByTheBinTick for why the tick narrowed.
		//
		// The guild's big bin is the ticked line: it is the only bin with a bank in its own
		// region, so it is the only one a run can be asked to supply.
		if (patch.getImplementation() == PatchImplementation.BIG_COMPOST)
		{
			return runOptions.isSelected(com.dooglemaps.data.RunOption.full(
				PlantingGroup.of(PatchImplementation.COMPOST)));
		}

		// The seven beside the allotments are not a line at all. They are in a run whenever the
		// player has said crops may be fed to a bin, and are serviced wherever the run already
		// goes - planStops drops any stop that would exist only for one, so they never cause
		// travel of their own.
		if (com.dooglemaps.data.CompostBin.forType(patch.getImplementation()) != null)
		{
			return compostRun.isFodderEnabled();
		}

		PlantingGroup group = groups.groupFor(patch);
		if (group == null)
		{
			return true;
		}
		return runOptions.isSelected(com.dooglemaps.data.RunOption.full(group))
			|| runOptions.isSelected(com.dooglemaps.data.RunOption.harvestOnly(group));
	}

	/**
	 * Whether a run started right now would care about this patch — ticked for a run, and not
	 * being held back by either of the two things that hold a patch back.
	 *
	 * <p>For the ready counter, whose number used to count every available patch: a bush at
	 * three berries and a herb patch whose type was never ticked both inflated a count that
	 * exists to say "a farm run is worth starting". Reported from play. Everything here is
	 * lock-guarded store reads and pure projection arithmetic, so it is safe from the
	 * counter's any-thread update path.
	 *
	 * <p>The shared-plot hold was missing from here while its sibling {@code heldForRegrowth}
	 * was present, and the two have to be the same list or the number is a lie: a plot waiting
	 * for its slowest crop is precisely a patch <b>Start run would not visit</b>, so counting
	 * its ready flower said "go now" about a teleport the setting exists to prevent. The run's
	 * own types are what the hold is scoped to, which is what {@code runTypes()} answers.
	 */
	public boolean selectedForRuns(FarmPatch patch)
	{
		return inTheRun(patch) && !heldForRegrowth(patch)
			&& !clusterHeld(patch, tickedTypes());
	}

	/**
	 * The patch types ticked for a run right now, for questions asked outside a run.
	 *
	 * <p>{@link #runTypes} is the <i>live</i> run's set and is empty between runs, which is
	 * exactly when the ready counter is being looked at. Derived from the ticks instead, the
	 * same way starting a run derives them.
	 */
	private Set<PatchImplementation> tickedTypes()
	{
		Set<PatchImplementation> types = EnumSet.noneOf(PatchImplementation.class);
		for (PatchImplementation type : PatchImplementation.values())
		{
			for (FarmPatch patch : availability.getAvailablePatches(type))
			{
				if (inTheRun(patch))
				{
					types.add(type);
					break;
				}
			}
		}
		return types;
	}

	/**
	 * Whether this patch is worth walking to right now.
	 *
	 * <p>A run is for patches that need something doing: ready to harvest, empty and waiting
	 * to be planted, or diseased and about to die. A crop that is merely growing wants
	 * leaving alone, and routing to it would waste the trip.
	 */
	/**
	 * How many patches of each type a run over these types would actually visit.
	 *
	 * <p>Same filter {@link #start} uses — available to this account, and actually wanting
	 * something doing — but without starting anything, so the panel can price a run up before
	 * the player commits to it.
	 */
	public synchronized Map<PatchImplementation, Integer> countActionable(
		Set<PatchImplementation> types)
	{
		Map<PatchImplementation, Integer> counts = new LinkedHashMap<>();
		for (PatchImplementation type : types)
		{
			int actionable = 0;
			for (FarmPatch patch : availability.getAvailablePatches(type))
			{
				// Same questions planStops asks, and for the same reason: this prices the
				// run the panel offers, so counting patches the run will not visit would quote a
				// trip nobody asked for. See inTheRun and clusterHeld.
				if (inTheRun(patch) && isActionable(patch) && !clusterHeld(patch, types)
					&& !heldForRegrowth(patch))
				{
					actionable++;
				}
			}
			if (actionable > 0)
			{
				counts.put(type, actionable);
			}
		}
		return counts;
	}

	/**
	 * The same counts, split by planting group.
	 *
	 * <p>What the estimate needs once protected patches are their own decision: eight herb patches
	 * might be two protected and six ordinary, and those get different seeds. Groups with nothing
	 * to do are left out, the same way empty types are.
	 */
	public synchronized Map<PlantingGroup, Integer> countActionableByGroup(
		Set<PatchImplementation> types)
	{
		Map<PlantingGroup, Integer> counts = new LinkedHashMap<>();
		actionableByGroup(types).forEach((group, patches) -> counts.put(group, patches.size()));
		return counts;
	}

	/**
	 * The same patches, listed rather than counted.
	 *
	 * <p>A count is enough to price a group; it is not enough to work out <b>which seed goes
	 * where</b> when more than one is picked, and that needs the patches themselves — see
	 * {@code SeedAllocation}. Sharing one method means the loadout cannot be pricing a different
	 * set of patches from the one the guide plants in.
	 */
	public synchronized Map<PlantingGroup, List<FarmPatch>> actionableByGroup(
		Set<PatchImplementation> types)
	{
		Map<PlantingGroup, List<FarmPatch>> byGroup = new LinkedHashMap<>();
		for (PatchImplementation type : types)
		{
			for (FarmPatch patch : availability.getAvailablePatches(type))
			{
				if (!inTheRun(patch) || !isActionable(patch) || clusterHeld(patch, types)
					|| heldForRegrowth(patch))
				{
					continue;
				}

				// Falls back to the plain group rather than trusting the grouper to answer. A
				// null key here would propagate into the estimate and the loadout — both of which
				// then ask it for a patch type — and turn a grouping question into an NPE two
				// classes away from the cause.
				PlantingGroup group = groups.groupFor(patch);
				byGroup.computeIfAbsent(group != null ? group : PlantingGroup.of(type),
					k -> new ArrayList<>()).add(patch);
			}
		}

		// The spirit tree cap, applied where every consumer inherits it at once - the
		// loadout's seed counts, the payments, the panel's pricing and the snapshot all
		// derive from this map. The guide's own plantable list is built separately and
		// trims through the same class, so the two keep agreeing. See SpiritTrees.
		byGroup.replaceAll((group, patches) -> com.dooglemaps.state.SpiritTrees.trimToCap(
			stateStore, seedInventory.getFarmingLevel(), group, patches));
		return byGroup;
	}

	/**
	 * What is actually growing in this group's actionable patches, by crop.
	 *
	 * <p>For a harvest-only run there is no seed to price against — the crop is already in the
	 * ground and the whole point is that you are not planting anything. The projection therefore
	 * has to be built from what is <i>there</i>, which only the state store knows, so it is
	 * answered here rather than reconstructed from a seed selection that is legitimately empty.
	 *
	 * <p>Patches with nothing identifiable growing are left out rather than guessed at.
	 */
	public synchronized Map<Produce, Integer> ripeProduceIn(PlantingGroup group)
	{
		Map<Produce, Integer> byProduce = new LinkedHashMap<>();
		for (FarmPatch patch : availability.getAvailablePatches(group.getType()))
		{
			PlantingGroup patchGroup = groups.groupFor(patch);
			if (patchGroup == null || !patchGroup.equals(group) || !isActionable(patch))
			{
				continue;
			}

			PatchProjection projection = growthTimer.project(patch, stateStore.get(patch));
			if (projection == null || projection.getProduce() == null || projection.isEmpty())
			{
				continue;
			}
			byProduce.merge(projection.getProduce(), 1, Integer::sum);
		}
		return byProduce;
	}

	/**
	 * How likely a crop is to survive across the patches this run will actually visit.
	 *
	 * <p>Averaged over them, because the same seed behaves differently depending on where it
	 * goes: a ranarr in Weiss cannot be diseased at all, one in Falador has about a coin's
	 * chance untreated. Averaging is right here because the run plants in all of them.
	 *
	 * <p>Whether a farmer will be paid is <b>asked</b> rather than assumed. It used to be assumed
	 * false, on the reasoning that nothing has been bought yet at the moment a run is priced —
	 * true, but it left the estimate discounting a loss that cannot happen for anyone who always
	 * pays, which is most people growing anything expensive. The choice is per planting group;
	 * see {@code ProtectionSelectionStore}.
	 */
	public RunEstimate.Survival survivalAcross(Set<PatchImplementation> types)
	{
		// Not synchronised, and it used to be — over nothing. The body constructs a closure and
		// returns; the traversal happens later, from RunEstimate, on whatever thread calls it. So
		// the keyword covered the allocation and left the store walk unguarded, which is the exact
		// opposite of what it read as. Worse for the next person than for the machine: this class
		// documents a lock ordering, and a method that looks compliant and is not is how that
		// ordering gets quietly broken.
		return (seed, compost) ->
		{
			if (seed == null || !types.contains(seed.getPatchType()))
			{
				return 1;
			}
			return survivalOver(availability.getAvailablePatches(seed.getPatchType()), seed,
				compost);
		};
	}

	/**
	 * The same, over one planting group's patches rather than the whole type.
	 *
	 * <h2>Why the type is the wrong unit</h2>
	 *
	 * Averaging across the type blends groups that have nothing in common. With protected herbs
	 * split out, Trollheim and Weiss cannot be diseased at all and Ardougne very much can — so the
	 * average came out somewhere in the middle and was then applied to <i>both</i> groups. The
	 * protected patches were quietly discounted for a risk they do not carry, and the ordinary
	 * ones were credited with safety they do not have.
	 *
	 * <p>Each group is already priced separately, against its own seeds and its own compost, so
	 * its survival belongs on the same footing.
	 */
	public RunEstimate.Survival survivalIn(PlantingGroup group)
	{
		if (group == null)
		{
			return (seed, compost) -> 1;
		}

		// The patch list is resolved <b>now</b> rather than inside the lambda, and that is a
		// correctness fix rather than a tidy-up. The estimate invokes the returned function later,
		// from another thread, so a lazily-read list could be a different set of patches from the
		// counts priced beside it — the group discounted for a disease risk averaged over patches
		// the count never included.
		//
		// It also removes the `synchronized` this method used to carry, which guarded the lambda's
		// construction and nothing else. See survivalAcross.
		List<FarmPatch> patches = new ArrayList<>();
		for (FarmPatch patch : availability.getAvailablePatches(group.getType()))
		{
			PlantingGroup patchGroup = groups.groupFor(patch);
			if (patchGroup != null && patchGroup.equals(group))
			{
				patches.add(patch);
			}
		}

		return (seed, compost) -> seed == null || seed.getPatchType() != group.getType()
			? 1
			: survivalOver(patches, seed, compost);
	}

	/**
	 * Mean chance this crop reaches harvest across the patches the run will actually visit.
	 *
	 * <p>Every input the model has is applied per patch, because all three vary by patch: the
	 * compost chosen for the group, whether the player is paying a farmer for this crop, and
	 * whether the patch is disease-free for this account at all.
	 */
	private double survivalOver(List<FarmPatch> patches, Seed seed, CompostTier compost)
	{
		if (seed.getProduce() == null)
		{
			return 1;
		}

		double total = 0;
		int counted = 0;
		for (FarmPatch patch : patches)
		{
			if (!isActionable(patch))
			{
				continue;
			}
			// A grown flower beside an allotment is the same guarantee a payment buys -
			// wiki-checked, see FlowerGuard - so the estimate treats the two alike.
			boolean paid = (protection.isProtecting(groups.groupFor(patch), seed)
				&& DiseaseRisk.isProtectable(patch))
				|| com.dooglemaps.state.FlowerGuard.guarding(stateStore, patch,
					seed.getProduce());
			total += DiseaseRisk.survivalChance(patch, seed.getProduce(), compost, paid,
				groups.isProtected(patch));
			counted++;
		}
		return counted == 0 ? 1 : total / counted;
	}

	/**
	 * Whether a stop still wants anything doing.
	 *
	 * <h2>Derived, not counted, and that is the whole fix</h2>
	 *
	 * A stop is finished exactly when none of its patches is still actionable — which is the same
	 * test {@code planStops} used to decide the stop existed. So a stop ends precisely when it
	 * would no longer be created, and the two cannot disagree.
	 *
	 * <p>What this replaces was {@code RunStop.isComplete}, a count of patches the capture layer
	 * had watched turn into a growing crop. That made completion depend on the player planting
	 * every patch, and stranded the run whenever they could not: a harvest-only stop plants nothing
	 * ever, a patch with no seed allocated has nothing to click, a dead crop with no axe cannot be
	 * cleared, and a patch that turns out to want nothing was never going to change state at all.
	 * The run then sat with no route and no instruction, because {@link #retarget()} clears the
	 * router while there is work in the region you are standing in.
	 *
	 * <p>It is also the principle {@code GuidePlan} already states for itself — <i>"a pure function
	 * of the patch's current state, no progress counter"</i>. The step list was derived and the
	 * stop list was counted, and only the counted half could get stuck.
	 *
	 * <p>Called with the planner's monitor held in some paths and not others; it reads only the
	 * availability and patch stores, so it takes no lock of this class's own. See the ordering note
	 * at the top of the file.
	 */
	private boolean isComplete(RunStop stop)
	{
		Set<String> blocked = nothingToDo;
		Set<PatchImplementation> types = runTypesSnapshot();
		for (FarmPatch patch : stop.getPatches())
		{
			// Actionable *and* actually doable. A patch the guide has no step for is one the
			// player cannot act on however long they stand there, so waiting on it is waiting
			// forever. See nothingToDo.
			//
			// ...and not one the run is deliberately waiting on. The two hold rules used to gate
			// planStops and nothing else, so they decided whether a stop was CREATED and had no
			// say in whether it came back - and a stop comes back the instant one of its patches
			// turns actionable again, which is exactly the moment both rules exist to ignore.
			// Two reports, one cause:
			//
			//   "hold shared plots until all are ready isn't working - just got routed to
			//    Ardougne again after only limpwurts were ready". The flower finishes in twenty
			//    minutes and the herb beside it takes eighty; the stop was completed after the
			//    first visit, then un-completed by the flower alone.
			//
			//   "we're going back to cactuses too soon - harvested it twenty minutes prior, only
			//    receiving 1 cactus spine". A harvest-only cactus regrows a spine every twenty
			//    minutes and holds four; one spine made the stop actionable again.
			if (stillWanted(stop, patch, blocked, types))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Whether this one patch still has work the player could go and do.
	 *
	 * <p>Lifted out of {@link #isComplete} unchanged, because three questions turn out to be
	 * the same question and were answering it differently: whether a stop is finished, whether
	 * the player has <i>arrived</i> at one (see {@link #hasArrivedAt}), and which patch the
	 * router should be aimed at (see {@link #routeTargetsFor}). The last two used to ask about
	 * every patch at the stop regardless of whether anything was left to do there.
	 *
	 * <p>Callers pass {@code blocked} and {@code types} in rather than each reading them, so a
	 * walk over a stop's patches takes one snapshot of the run's types instead of one per patch.
	 *
	 * <h2>The cluster hold applies here only to patches already dealt with once</h2>
	 *
	 * Two mid-run cases look identical to {@link #clusterHeld} — an actionable cluster patch
	 * beside a growing sibling — and they deserve opposite answers, split by {@code serviced}:
	 *
	 * <ul>
	 *   <li><b>Serviced, then ripened again</b> — the flower replanted at this stop coming back
	 *       in twenty minutes while the herb takes eighty. The trip's promise was kept; new
	 *       work waits for the plot. Held, which is the Ardougne report this test pins.</li>
	 *   <li><b>Never serviced</b> — a patch the plan promised and the player has not finished:
	 *       out of seed, ran for the bank, part-way through the plot. Holding it because its
	 *       serviced neighbour started growing completed the stop <i>under</i> the player the
	 *       moment they crossed the region boundary, silently dropping ripe work the run had
	 *       already paid the travel for. Not held.</li>
	 * </ul>
	 *
	 * <p>The plot's compost bin is the exception, held with its plot whether or not it was
	 * touched: a bin left composting is not unfinished work the run owes, it is the thing the
	 * plot's own test says is the best possible thing to make wait — nothing in it spoils, and
	 * skipping it was a choice the player already made standing next to it.
	 *
	 * <p>This is the reconciliation of {@code clusterHeld}'s old "planning-time only" rule with
	 * the two reports that forced it into completion: the hold does gate completion, but only
	 * for work the run has already done once.
	 */
	private boolean stillWanted(RunStop stop, FarmPatch patch, Set<String> blocked,
		Set<PatchImplementation> types)
	{
		return isActionable(patch) && !blocked.contains(patch.getKey())
			&& !(clusterHeld(patch, types) && (stop.wasServiced(patch) || isClusterBin(patch)))
			&& !heldForRegrowth(patch);
	}

	/**
	 * The panel's answers for the current tickbox selection, republished once a tick.
	 *
	 * <p>Volatile and immutable, like {@code GuideStatus}: the EDT reads it lock-free, and the
	 * client thread replaces it whole. Null until the first tick of a session, which is why
	 * the panel keeps a live-query fallback — see {@code RunPanel.snapshotFor}.
	 */
	@Nullable
	private volatile RunSnapshot snapshot;

	/**
	 * Builds the panel's snapshot for one selection. Client thread, by design — every query
	 * here takes this planner's monitor, and gathering them where the monitor already lives
	 * is the whole point of publishing a snapshot instead.
	 */
	public RunSnapshot snapshotFor(Set<PatchImplementation> types)
	{
		Map<PlantingGroup, Integer> byGroup = countActionableByGroup(types);
		Map<PlantingGroup, Map<Produce, Integer>> ripe = new LinkedHashMap<>();
		Map<PlantingGroup, RunEstimate.Survival> survival = new LinkedHashMap<>();
		for (PlantingGroup group : byGroup.keySet())
		{
			ripe.put(group, ripeProduceIn(group));
			survival.put(group, survivalIn(group));
		}
		return new RunSnapshot(
			types.isEmpty()
				? EnumSet.noneOf(PatchImplementation.class)
				: EnumSet.copyOf(types),
			previewStops(types), countActionable(types), byGroup, ripe, survival,
			fillableBinsIn(types));
	}

	/**
	 * How many bins in this selection the run could put produce into.
	 *
	 * <p>The run panel's "No fill picked for the compost bins." line is gated on this being
	 * more than zero: emptying finished compost is a complete bin run on its own — the buckets
	 * come from the leprechaun and no produce is involved — so someone whose bins are all
	 * sitting full would otherwise be told to fix something they never wanted.
	 *
	 * <h2>The tick is folded, because it names one bin and means both</h2>
	 *
	 * The checkbox carries the ordinary bin's type and covers the guild's big one as well, so
	 * asking {@link #binWork} about the type on the checkbox would miss the big bin entirely.
	 * {@code CompostBin.coveredByTheBinTick} is the same fold the panel used to do for itself,
	 * and it lives here now because the call it guards does.
	 *
	 * <p>Package-private rather than private so {@code RunPlannerTest} can put the fold under
	 * test directly — it was covered from the panel's side while the call was there, and the
	 * coverage should not be lost by moving the call.
	 *
	 * <p>No fodder test is needed even though a fill can now come from the harvest:
	 * {@link #binWork} counts no fillable items for a bin the guild's own allotments will
	 * supply, or for any of the seven beside the allotments, so this goes quiet by itself in
	 * exactly the cases where a "pick a fill" warning would be a false alarm.
	 */
	int fillableBinsIn(Set<PatchImplementation> types)
	{
		Set<PatchImplementation> bins = EnumSet.noneOf(PatchImplementation.class);
		for (PatchImplementation type : types)
		{
			if (com.dooglemaps.data.CompostBin.forType(type) != null)
			{
				bins.add(type);
			}
		}
		if (bins.isEmpty())
		{
			return 0;
		}
		return binWork(com.dooglemaps.data.CompostBin.coveredByTheBinTick(bins)).fillableBins;
	}

	public void publishSnapshot(RunSnapshot snapshot)
	{
		this.snapshot = snapshot;
	}

	@Nullable
	public RunSnapshot getSnapshot()
	{
		return snapshot;
	}

	/**
	 * Told the withdraw list's current answer — the other half of the guide's per-tick push.
	 *
	 * <p>Also called just before {@link #leaveBank()} by the plugin's tick and by
	 * {@code BankCapture}'s container event, so a withdrawal is acted on in the same tick it
	 * happens rather than one push later.
	 */
	public void setWithdrawOutstanding(boolean outstanding)
	{
		withdrawOutstanding = outstanding;
	}

	/**
	 * The same push, narrowed to tools — the one thing worth a mid-run trip back.
	 *
	 * <p>Separate from {@link #withdrawOutstanding} because the two are asked at different
	 * moments and must answer differently. That one decides whether the supply leg is finished;
	 * this one decides whether to <b>start a new one</b> in the middle of a run, which only a
	 * tool justifies. See {@code RunLoadout.toolsLeftToWithdraw}.
	 */
	private volatile boolean toolOutstanding;

	/** Told whether a tool the run needs is still in a bank; see {@link #reviewSupplies()}. */
	public void setToolOutstanding(boolean outstanding)
	{
		toolOutstanding = outstanding;
	}

	/**
	 * Told which patches, across every stop, the guide currently has no step for.
	 *
	 * <p>Replaced wholesale each tick rather than added to, so a patch that becomes doable again —
	 * you withdraw the seed you were missing, or fetch an axe — starts blocking completion again on
	 * the very next tick. Accumulating would have meant a patch skipped once staying skipped.
	 */
	public void setNothingToDo(Set<String> patchKeys)
	{
		nothingToDo = patchKeys == null || patchKeys.isEmpty()
			? Collections.emptySet()
			: Collections.unmodifiableSet(new LinkedHashSet<>(patchKeys));
		synchronized (this)
		{
			// A generation stamp for the run-complete confirmation; see retarget().
			exemptionPushes++;
		}
	}

	/**
	 * How many times the exemption set has been pushed, for run-complete confirmation.
	 *
	 * <p>Completion is judged against {@code nothingToDo}, a set pushed from outside once a
	 * tick — and a single over-broad push (the loadout blind for a tick, an exception freezing
	 * the producer) made every stop read complete for one tick, at which point {@code
	 * retarget()} ended the run outright. Deactivation now needs the emptiness confirmed
	 * against a <b>later</b> push than the one that first produced it, so a one-tick lie
	 * cannot kill a run.
	 */
	private int exemptionPushes;

	/** Set when retarget() first sees nothing remaining; see {@link #exemptionPushes}. */
	private boolean runCompletePending;
	private int runCompleteGeneration = -1;

	private boolean isActionable(FarmPatch patch)
	{
		PatchProjection projection = growthTimer.project(patch, stateStore.get(patch));
		if (projection == null)
		{
			// Never seen it. Worth a look: it may well be empty.
			return true;
		}

		// Compost bins first, because every test below asks a crop question a bin cannot
		// answer - isReady would promote a composting bin off its clock, and the generated
		// data marks bins health-check-required, which would hand them to a branch about
		// checking trees. See binActionable.
		if (com.dooglemaps.data.CompostBin.forType(patch.getImplementation()) != null)
		{
			return binActionable(projection);
		}

		// Harvest-only: something to pick counts and nothing else does. An empty bush patch is not a
		// reason to travel when the player has said they are not replanting, and a laden one is
		// precisely what they came for — so this narrows the trip rather than merely changing what
		// happens once you arrive.
		//
		// hasProduceToPick, not the raw HARVESTABLE state. A picked-clean bush is still
		// "harvestable" — grown, with a stock of zero — so this stayed true after the player had
		// stripped it, the stop never stopped being actionable, and a harvest-only run could not
		// finish a stop at all. See PatchProjection.hasProduceToPick.
		//
		// The health check counts too. A grown cactus or fruit tree is still GROWING until the
		// player checks it, so it has nothing to pick YET — and asking only hasProduceToPick
		// walked the run past the one click that makes the picking (and the crop's real
		// experience) available. Reported from play: a guild cactus at check-health, skipped
		// the moment the rest of the guild was done. The guide's check step never gated on
		// harvest-only; it was only this routing test that did.
		if (runOptions.isHarvestOnly(groups.groupFor(patch)))
		{
			return projection.hasProduceToPick() || projection.needsHealthCheck();
		}

		// Finished growing counts, even while the varbit still says GROWING. A tree that is fully
		// grown but not yet health-checked reads as GROWING at its last stage — only the *checked*
		// states are HARVESTABLE — so a run skipped every grown tree and priced a tree run at one
		// patch when there were seven to chop, dig and replant. Reported from play.
		//
		// isReady() is the almanac's own test for this and is what puts "ready" on those rows, so
		// using it here makes the run agree with what the panel above it already says.
		if (projection.isReady())
		{
			// One exception, and it is narrow on purpose: a regrowing crop that has been checked
			// and stripped is finished with. The fruit comes back on its own, digging up a healthy
			// tree is the last thing anyone wants, and leaving it "actionable" meant the stop it
			// sits in could never complete.
			//
			// Narrowed to HARVESTABLE deliberately. A fruit tree that has finished growing but not
			// been health-checked also regrows and also has no fruit — and it very much does want
			// something doing, which is the check itself.
			//
			// Narrower still since the bushes: a stripped bush or cactus on a run that REPLANTS
			// it is not finished — a spade takes it straight out and the chosen seed goes in, and
			// treating it as done meant the guide never asked for the dig. Reported from play.
			// Owned stock counts here (the supply leg can fetch a banked seed); whether it is at
			// hand is the guide's stricter question, and when it says no the idle report unblocks
			// the stop the same way it does for any other missing seed.
			if (projection.regrows() && projection.getCropState() == CropState.HARVESTABLE)
			{
				return projection.hasProduceToPick() || wantsReplantClear(patch);
			}
			return true;
		}

		// A growing crop the player asked to protect, whose farmer has not been paid, still
		// wants one thing: the payment. Without this, planting flipped the patch to "nothing
		// doing", the stop completed under the player's feet, and the guide walked off in the
		// middle of the transaction — the sapling in the ground and the gardener unpaid beside
		// it. Whether the payment can actually be made is the guide's question (the pack must
		// hold it); when it cannot, the guide's idle report unblocks the stop through
		// nothingToDo, so this cannot strand a run. See GuidePlan.addProtectionStep.
		if (wantsProtectionPayment(patch, projection))
		{
			return true;
		}

		switch (projection.getCropState())
		{
			case HARVESTABLE:
			case DISEASED:
			case DEAD:
			case EMPTY:
				return true;
			default:
				// Weeds mean an empty patch, whatever the crop state says.
				return projection.isEmpty();
		}
	}

	/**
	 * Whether a compost bin wants a visit.
	 *
	 * <p>Empty or collectable, off the crop state the varbit machinery already decodes —
	 * settled with the owner:
	 *
	 * <ul>
	 *   <li><b>HARVESTABLE</b> - finished compost sitting in it. The emptying needs only
	 *       buckets, and the leprechaun beside the bin stores a thousand.</li>
	 *   <li><b>EMPTY or FILLING</b> - room for produce. Routed whether or not a fill has been
	 *       chosen on the compost tab: the bin is beside patches the run is passing anyway,
	 *       and someone with a pack full of pineapples should not need a selection to be shown
	 *       the bin. With nothing chosen and nothing carried the guide simply stays quiet and
	 *       the idle report completes the stop, like any patch whose seed was left behind.</li>
	 *   <li><b>GROWING</b> - closed and composting. Worth the trip only once the clock says it
	 *       must have finished ({@code isReady} reads the projection's done estimate); before
	 *       that there is nothing at the bin but a lid.</li>
	 * </ul>
	 */
	private boolean binActionable(PatchProjection projection)
	{
		switch (projection.getCropState())
		{
			case HARVESTABLE:
			case EMPTY:
			case FILLING:
				return true;
			case GROWING:
				return projection.isReady();
			default:
				return false;
		}
	}

	/**
	 * What the run's compost bins add up to, for the loadout's rows.
	 *
	 * <p>One synchronized walk rather than several getters, for the same reason
	 * {@code countActionableByGroup} is: the loadout reads from the Swing thread and every
	 * public method here takes the planner's monitor, so the answer should be one lock trip.
	 *
	 * <p>The counts split by what the loadout needs to say. Ready bins want buckets (and say
	 * how many are left in them, which the snapshot's stage carries); ready bins of
	 * <b>supercompost</b> want ash, and only those, because ash upgrades nothing else - the
	 * decode names the product even while a closed bin is still composting. Fillable bins want
	 * the chosen produce, less whatever a part-filled bin already holds. A bin never seen
	 * counts as an empty one, exactly as {@code isActionable} treats it as worth a look.
	 *
	 * <h2>A ready bin is also a fillable one, and missing that ended runs early</h2>
	 *
	 * The two counts used to be exclusive - a ready bin was counted for its buckets and its ash
	 * and then skipped for the fill. But emptying a bin is what makes it empty: the visit that
	 * takes thirty buckets of ultracompost out leaves a bin wanting thirty items of produce, in
	 * the same breath, at the same bin. Counting only the buckets meant the loadout packed no
	 * pineapples for it, and {@code CompostBinPlan.addFillStep} is deliberately quiet when the
	 * fill is not in the pack - so the moment the last bucket went to the leprechaun the bin had
	 * no step, the stop read as finished and the run ended with every bin left open and empty.
	 * Reported from play.
	 *
	 * <p>A whole bin's worth, because a ready bin empties completely; the part-filled remainder
	 * only applies to {@code FILLING}. This does mean a stop of ready bins asks for more produce
	 * than a pack holds, which is already handled rather than newly broken - see
	 * {@code RunLoadout.fillReason} and its short-of-the-job wording.
	 */
	public synchronized BinWork binWork(Set<PatchImplementation> types)
	{
		Counts counts = new Counts();

		for (PatchImplementation type : types)
		{
			com.dooglemaps.data.CompostBin bin = com.dooglemaps.data.CompostBin.forType(type);
			if (bin == null)
			{
				continue;
			}
			for (FarmPatch patch : availability.getAvailablePatches(type))
			{
				if (!inTheRun(patch))
				{
					continue;
				}
				count(counts, bin, patch, banksItsFill(patch));
			}
		}

		// The seven beside the allotments, for their ash and their buckets and nothing else.
		//
		// They are not in `types` - CompostBin.coveredByTheBinTick narrows the tick to the guild's
		// bin, which is what keeps their fill out of the loadout. But ash is one inventory slot
		// however much of it you carry, and without it a small bin filled from harvest could never
		// be upgraded to ultracompost through the plugin at all. That is a real feature lost for a
		// slot saved, so the fill is the only thing withheld.
		//
		// The type test stays even though a covered set can never contain COMPOST: a caller that
		// passes the raw type gets those bins from the loop above, and counting them twice would
		// double every figure on the row.
		if (compostRun.isFodderEnabled() && !types.contains(PatchImplementation.COMPOST))
		{
			for (FarmPatch patch : allotmentBinsInTheRun(types))
			{
				count(counts, com.dooglemaps.data.CompostBin.NORMAL, patch, false);
			}
		}

		return new BinWork(counts.readyBins, counts.readyBuckets, counts.ashNeeded,
			counts.fillableBins, counts.fillItems);
	}

	/**
	 * The allotment bins the run will actually stand in front of, and no others.
	 *
	 * <h2>Availability is not the run</h2>
	 *
	 * {@link #binWork} asked {@code availability.getAvailablePatches(COMPOST)} for these, which is
	 * every bin the account can reach rather than every bin the trip goes past — and unlike the
	 * loop above it, nothing narrowed the answer afterwards. So a run over the trees was told to
	 * bank <b>175 volcanic ash</b>, seven bins' worth, for a route that passed at most one of them.
	 * Reported from play, and the buckets were overstated the same way.
	 *
	 * <p>The rule these bins arrive by is {@link #addOpportunisticBins}: a bin joins a stop the run
	 * is <b>already</b> making and never creates one. That rule lives in the plan, not in a
	 * predicate, so this reads the plan rather than restating it — anything the run would adopt is
	 * in a stop's patch list, and anything it would not is not. Reading it back also picks up
	 * {@link #reviewBins} mid-run and survives {@link #mergeSharedStops} folding a bin's region
	 * into its host, which a region-set test would have got wrong in exactly that case.
	 *
	 * <p>The live stops while a run is on, and a fresh plan otherwise — which is the same pair
	 * {@code coveredTypes} chooses between, and for the same reason: at the bank, before the
	 * button, the plan is the only statement of where the trip goes.
	 */
	private List<FarmPatch> allotmentBinsInTheRun(Set<PatchImplementation> types)
	{
		Collection<RunStop> planned = active && !stops.isEmpty()
			? stops.values()
			: planStops(types).values();

		List<FarmPatch> bins = new ArrayList<>();
		for (RunStop stop : planned)
		{
			for (FarmPatch patch : stop.getPatches())
			{
				if (patch.getImplementation() == PatchImplementation.COMPOST)
				{
					bins.add(patch);
				}
			}
		}
		return bins;
	}

	/** The running totals {@link #binWork} builds, so both of its loops share one accumulation. */
	private static final class Counts
	{
		private int readyBins;
		private int readyBuckets;
		private int ashNeeded;
		private int fillableBins;
		private int fillItems;
	}

	/**
	 * Adds one bin to the totals.
	 *
	 * @param banksTheFill whether this bin's produce is the bank's problem. False for the seven
	 *                     beside the allotments, which are fed from the harvest standing next to
	 *                     them, and false for the guild's bin on the trips its own allotments
	 *                     will feed it — in both cases the buckets and the ash still count, and
	 *                     only the fifteen-to-thirty un-noted items are withheld.
	 */
	private void count(Counts counts, com.dooglemaps.data.CompostBin bin, FarmPatch patch,
		boolean banksTheFill)
	{
		com.dooglemaps.state.PatchSnapshot snapshot = stateStore.get(patch);
		PatchProjection projection = growthTimer.project(patch, snapshot);
		if (projection == null)
		{
			binDecodes.remove(patch.getKey());
			addFill(counts, bin.getCapacity(), banksTheFill);
			return;
		}

		boolean ready = projection.getCropState() == CropState.HARVESTABLE
			|| (projection.getCropState() == CropState.GROWING && projection.isReady());
		if (!ready)
		{
			// Forgotten the moment the bin stops being ready, so coming back to ready prints
			// again. Without this a bin emptied and refilled to the same stage would match the
			// entry left over from before it was emptied and say nothing — and "no recent line"
			// has to mean "unchanged", never "no longer counted". See logBinDecode.
			binDecodes.remove(patch.getKey());
		}
		if (ready)
		{
			counts.readyBins++;
			int buckets = projection.getCropState() == CropState.HARVESTABLE
				? snapshot.getStage() + 1
				: bin.getCapacity();
			counts.readyBuckets += buckets;

			// One line per ready bin, because the number it produces has no other way of being
			// seen and it decides how many buckets the run asks for.
			//
			// Reported from play: the empty-bucket highlight vanished after a single bucket, which
			// can only happen if this came out as 1. The arithmetic is right — a bin's stage is
			// its compost count less one, so a full normal bin is stage 14 and wants fifteen — so
			// the question is whether the SNAPSHOT is telling the truth about the stage, and that
			// is not answerable from in front of the client. The raw varbit is printed beside the
			// decode for exactly that: a stale snapshot shows up here as a stage that does not
			// match the bin the player is standing at.
			//
			// Only when the answer changes. The loadout is rebuilt once a tick for the whole run —
			// {@code DoogleMapsPlugin.onGameTick} asks anythingLeftToWithdraw every tick, and the
			// per-tick cache in RunLoadout means that build is the one everybody shares — so this
			// wrote the same sentence a hundred times a minute and buried everything around it.
			// A repeat carries no information the first line did not: what this was added to catch
			// is the decode being WRONG, and a decode that changes is exactly what still prints.
			logBinDecode(patch, bin, snapshot, projection, buckets);
			if (projection.getProduce() == Produce.SUPERCOMPOST
				|| projection.getProduce() == Produce.BIG_SUPERCOMPOST)
			{
				counts.ashNeeded += bin.ashNeeded();
			}
			// And then it is an empty bin at the same stop — but NOT on this bank visit.
			//
			// The fill used to be counted here in full, and the supply leg withdrew it before
			// the run set off: a pack of watermelons for a bin that cannot take a single one
			// until its compost is out. The two phases have opposite pack profiles — emptying
			// wants free slots (the buckets come from the leprechaun in batches the size of
			// whatever room is going; see CompostBinPlan), filling wants a pack of un-noted
			// items — so the fill did not just arrive early, it physically blocked the step
			// before it. Reported from play at the guild: told to carry thirty watermelons to
			// a full big bin, then having to bank them again to make room for the emptying.
			//
			// So a ready bank-fed bin counts as fillable — the "pick a fill" warning and the
			// worth-routing-to answer both still see it — and contributes no fill items until
			// its varbit actually reads EMPTY. From there the ordinary machinery takes over:
			// the row goes WITHDRAW, and divertForSupplies makes the collection the run's next
			// leg — at the guild, the one place a bank-fed bin exists, that is a walk to the
			// chest in the same region rather than a teleport.
			if (banksTheFill)
			{
				counts.fillableBins++;
			}
			return;
		}
		if (projection.getCropState() == CropState.EMPTY)
		{
			addFill(counts, bin.getCapacity(), banksTheFill);
		}
		else if (projection.getCropState() == CropState.FILLING)
		{
			addFill(counts, Math.max(0, bin.getCapacity() - (snapshot.getStage() + 1)),
				banksTheFill);
		}
	}

	/**
	 * The last decode printed for each bin, so an unchanged one is not printed again.
	 *
	 * <p>Only ever touched from {@link #count}, which is only ever reached through the
	 * synchronised {@link #binWork} — the same monitor everything else in here is under.
	 *
	 * <p>One short string per ready bin, dropped again the moment the bin stops being ready — see
	 * {@link #count}. That is what keeps "nothing printed lately" meaning "the answer has not
	 * changed" rather than "this bin is no longer counted at all", which is the difference that
	 * makes the line worth reading in a log a week later.
	 */
	private final Map<String, String> binDecodes = new java.util.HashMap<>();

	/** The debug line from {@link #count}, printed when this bin's answer is not the last one. */
	private void logBinDecode(FarmPatch patch, com.dooglemaps.data.CompostBin bin,
		com.dooglemaps.state.PatchSnapshot snapshot, PatchProjection projection, int buckets)
	{
		if (!log.isDebugEnabled())
		{
			return;
		}

		String decode = snapshot.getVarbitValue() + "/" + projection.getCropState() + "/"
			+ snapshot.getStage() + "/" + buckets + "/" + bin.ashNeeded();
		if (decode.equals(binDecodes.put(patch.getKey(), decode)))
		{
			return;
		}

		log.debug("Ready bin {} ({}): varbit {} -> {} stage {}, so {} bucket(s), ash {}",
			patch.getKey(), bin, snapshot.getVarbitValue(), projection.getCropState(),
			snapshot.getStage(), buckets, bin.ashNeeded());
	}

	private static void addFill(Counts counts, int items, boolean banksTheFill)
	{
		if (!banksTheFill)
		{
			return;
		}
		counts.fillableBins++;
		counts.fillItems += items;
	}

	/**
	 * Whether this bin's produce is a bank's problem, which only the guild's ever is.
	 *
	 * <p>Two reasons a bin does not bank, and they are different. The seven beside the allotments
	 * <b>never</b> do: there is no bank near any of them, so the fill would be carried across the
	 * map, which is the complaint this came from. The guild's does, unless its own allotments will
	 * feed it that trip.
	 *
	 * <p>Stated once, here, because getting it as {@code !guildWillFeed(patch)} was wrong in
	 * exactly the way a negation of a narrower question usually is: that reads false for a small
	 * bin, and false-for-the-wrong-reason came out as "so the bank must supply it".
	 */
	private boolean banksItsFill(FarmPatch bin)
	{
		return bin.getImplementation() == PatchImplementation.BIG_COMPOST
			&& !guildWillFeed(bin);
	}

	/**
	 * Whether the Farming Guild's own allotments will feed the big bin this trip.
	 *
	 * <h2>The one bin with two sources</h2>
	 *
	 * The guild has allotments, the big bin and a bank all in region 4922 — the only place all
	 * three are true, and the reason {@link #supplyPointIsHere()} exists. So it can be fed either
	 * way, and every input to the choice is known before the run starts: are those patches in the
	 * run, planted with a crop the player allows as fodder, and going to be pickable this trip.
	 *
	 * <p>All three true and the bank is not asked for anything. Any one false — unplanted, not
	 * ready yet, planted with something not on the list, or fodder switched off — and today's
	 * bank row returns unchanged, because those all mean the same thing: the guild cannot supply
	 * it, so the bank must.
	 *
	 * <p>Deliberately all-or-nothing rather than banking the shortfall against a predicted yield.
	 * {@code CropYieldModel} could produce a number, but "eight pineapples against a predicted
	 * twenty-two watermelons" would put a wrong-sized ask on the list most trips and reintroduce
	 * exactly the pack-clogging this is here to remove. A short bin is topped up on site instead,
	 * from the bank that is fourteen tiles away.
	 *
	 * <p>False for anything that is not the big bin, so callers need no second test.
	 */
	private boolean guildWillFeed(FarmPatch bin)
	{
		if (bin.getImplementation() != PatchImplementation.BIG_COMPOST
			|| !compostRun.isFodderEnabled())
		{
			return false;
		}

		java.util.Set<Integer> allowed = compostRun.getFodderCrops();
		if (allowed.isEmpty())
		{
			return false;
		}

		for (FarmPatch patch : bin.getRegion().getPatches())
		{
			// The region's whole patch list, so availability has to be asked here - unlike
			// planStops, this is not already walking an available-only set.
			if (com.dooglemaps.data.CompostBin.forType(patch.getImplementation()) != null
				|| !availability.isAvailable(patch) || !inTheRun(patch))
			{
				continue;
			}
			PatchProjection projection = growthTimer.project(patch, stateStore.get(patch));
			if (projection == null || projection.getProduce() == null
				|| !allowed.contains(projection.getProduce().getItemID()))
			{
				continue;
			}
			// Laden already, or the clock says it will be by the time you get there.
			if (projection.hasProduceToPick() || projection.isReady())
			{
				return true;
			}
		}
		return false;
	}

	/** The bin counts {@link #binWork} hands the loadout. */
	public static final class BinWork
	{
		/** Bins with finished compost in them, or closed ones the clock says are done. */
		public final int readyBins;
		/** Compost left across the ready bins - the number of buckets emptying them takes. */
		public final int readyBuckets;
		/** Volcanic ash to upgrade every ready bin of supercompost, at each bin's own price. */
		public final int ashNeeded;
		/** Bins that are empty or part-filled, so the run could put produce in. */
		public final int fillableBins;
		/** Items those bins have room for, part-filled ones counted at their remainder. */
		public final int fillItems;

		BinWork(int readyBins, int readyBuckets, int ashNeeded, int fillableBins, int fillItems)
		{
			this.readyBins = readyBins;
			this.readyBuckets = readyBuckets;
			this.ashNeeded = ashNeeded;
			this.fillableBins = fillableBins;
			this.fillItems = fillItems;
		}
	}

	/**
	 * Whether a stripped bush or cactus is due a dig-and-replant this run.
	 *
	 * <p>The route-planning half of {@code GuidePlan}'s picked-clean branch, asked with the
	 * planner's looser stock test: seeds owned anywhere count, because the supply leg exists
	 * exactly to fetch the banked ones. Harvest-only groups never reach this — the caller
	 * answers them before the regrowing exception does.
	 */
	private boolean wantsReplantClear(FarmPatch patch)
	{
		// Two ways ground gets cleared for a replant, and a fruit tree is the second.
		//
		// A bush or a cactus comes straight out with a spade. A fruit tree is chopped and then
		// its stump is dug — two clicks rather than one — but the routing question is identical:
		// a picked-clean tree on a run that means to replant it is not finished with, and
		// treating it as finished is what made the run walk away from a stripped palm without
		// ever offering the chop. Reported from play, on a fruit tree run.
		//
		// Deliberately not added to SpadeClearedCrops. That set answers "does a spade take this
		// straight out", which {@code GuideTracker.contractSeedFetch} relies on to decide
		// whether ground can be cleared this trip, and a fruit tree cannot be — it wants an axe
		// first. Two questions, two answers, kept apart.
		boolean chopThenDig =
			patch.getImplementation() == com.dooglemaps.data.PatchImplementation.FRUIT_TREE;
		if (!chopThenDig
			&& !com.dooglemaps.data.SpadeClearedCrops.isSpadeCleared(patch.getImplementation()))
		{
			return false;
		}
		// The same null-guard actionableByGroup carries: a grouper with no answer must not
		// turn a grouping question into an NPE.
		PlantingGroup group = groups.groupFor(patch);
		if (group == null)
		{
			group = PlantingGroup.of(patch.getImplementation());
		}
		for (Seed seed : selection.getSelectedFor(group))
		{
			if (seedInventory.getOwnedPlantable(seed) >= seed.getSeedsPerPatch())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a regrowing crop on a harvest-only run is still refilling, so the trip should
	 * wait for the cap.
	 *
	 * <h2>Regrowth stops at the cap, which is what makes waiting free</h2>
	 *
	 * Wiki-checked across the regrowing families: a bush holds four berries, a cactus three
	 * spines, a fruit tree and calquat six fruit — one unit regrows per fixed tick and
	 * <b>nothing accumulates past the cap</b>, so a full plant produces nothing until picked
	 * while a part-full one is still working. Visiting early therefore buys nothing and
	 * costs a trip; for bushes the wiki additionally ties the guaranteed-four-plus-level
	 * yield to a fully regrown bush ("the minimum always being four unless harvested again
	 * before it has regrown all berries"). Reported from play as being routed back to
	 * half-full bushes.
	 *
	 * <p>Every run line, not only harvest-only. The first cut exempted full runs — "a full
	 * run is coming to dig the plant out and replant, and holding that trip hostage to
	 * berries the spade is about to destroy would be backwards" — and play refuted the
	 * argument on its own terms: the spade destroying whatever is unpicked is exactly why
	 * arriving early is the waste. A potato cactus visited at one potato of seven harvests
	 * one and digs the other six out of existence; twenty minutes' patience harvests seven.
	 * Reported from play: "we did go back to the potato cactus too soon... I want it full."
	 *
	 * <p>The contract's patch is the one exemption, exactly as it is for the shared-plot
	 * hold: its clock is Guildmaster Jane's, and stock on the old plant is nothing beside a
	 * reward that costs a whole growth cycle when it slips.
	 *
	 * <p>Planning-time only, like {@link #clusterHeld} and for the same reason: once the
	 * player is standing there picking, the stock falls and more starts regrowing — a
	 * completion filter would read "no longer full" as "not worth visiting" and finish the
	 * stop under their feet at the first berry. The stop completes picked-clean, as always.
	 *
	 * <p>The projection climbs the stock back up over elapsed time, so a bush observed
	 * half-full an hour ago and full by now is correctly included; a never-observed one has
	 * no stock to hold back and passes through untouched.
	 */
	private boolean heldForRegrowth(FarmPatch patch)
	{
		PlantingGroup group = groups.groupFor(patch);
		if (group != null && group.isContract())
		{
			return false;
		}

		// Never for the plot being stood on — the same guard clusterHeld carries, and it was
		// missing here.
		//
		// The paragraph above says this is planning-time only and spells out what a completion
		// filter would do: "finish the stop under their feet at the first berry". It then became
		// one, in isComplete, and did exactly that. Pick one berry of four and the bush drops
		// below its cap, so it is held; a one-patch stop then completes, leaves getRemaining(),
		// and GuideTracker.stopAt answers null — which sends computeStepsHere down its
		// between-stops branch, where the only thing left to say is the leaving errand. Reported
		// from play at the Ardougne monastery bush: told to note each white berry as it was
		// picked, with nothing ever telling you to pick the next one.
		//
		// The hold is about not making the TRIP. Standing there, the trip is already spent, so
		// there is nothing left for it to save and every reason to finish the plant. That is the
		// same reasoning clusterHeld's guard is built on, and both are really one question —
		// "is the player here" — asked in two places. See docs/code-review-2026-08c.md §1-2 for
		// why they should become one predicate rather than two copies.
		//
		// Every id the plot answers to, like clusterHeld's guard after the Catherby lesson:
		// the bare compare read a player one region over the boundary as elsewhere.
		if (com.dooglemaps.data.FarmingWorldData.claimsRegionId(patch.getRegion(),
			playerLocation.getRegionId()))
		{
			return false;
		}
		PatchProjection projection = growthTimer.project(patch, stateStore.get(patch));
		return projection != null && projection.regrows()
			&& projection.hasProduceToPick() && projection.isRegrowing();
	}

	/** The patch types that share a plot at the classic locations, and so ripen as a group. */
	private static final Set<PatchImplementation> CLUSTER_TYPES = EnumSet.of(
		PatchImplementation.ALLOTMENT, PatchImplementation.FLOWER, PatchImplementation.HERB);

	/**
	 * Whether this is a compost bin standing on one of those plots.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"I was just sent to ardougne farm without volcanic ash to fill the bin, and with
	 * watermelons still in progress, per our combined allotment/herb/flower patches rule, we
	 * shouldn't have even gone here"</i>. The hold was working exactly as written — the session
	 * log's own <i>Shared plots</i> line shows Ardougne <b>[free to visit]</b> at plan time with
	 * all four patches ripe, and the player serviced them. What brought them back an hour later
	 * was the <b>bin</b>: it was left composting, its clock came round, {@code binActionable}
	 * re-opened the stop, and nothing in {@link #clusterHeld} had an opinion because a bin is not
	 * one of {@link #CLUSTER_TYPES}. So the trip the hold exists to prevent was made anyway, for
	 * the one patch on that ground the hold could not see.
	 *
	 * <p>A bin is the best possible thing to make wait: its contents keep indefinitely, there is
	 * no disease clock on it, and it is standing among the patches that will want the compost. It
	 * is held on exactly the terms the plot is, so an unticked or harvest-only plot never holds it
	 * and it can never wait for ever.
	 */
	private boolean isClusterBin(FarmPatch patch)
	{
		if (com.dooglemaps.data.CompostBin.forType(patch.getImplementation()) == null)
		{
			return false;
		}
		for (FarmPatch sibling : patch.getRegion().getPatches())
		{
			if (CLUSTER_TYPES.contains(sibling.getImplementation()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether this patch's trip should wait for the rest of its plot.
	 *
	 * <p>The classic locations put an allotment pair, a flower and (except Prifddinas) a herb
	 * patch within a few tiles of each other, and they grow at different speeds — a marigold is
	 * done in twenty minutes, a ranarr in eighty. Left alone the run happily made the trip for
	 * whichever finished first, which is two trips where one was wanted. With the setting on,
	 * a cluster patch is held out of the plan until every <b>ticked</b> cluster patch on that
	 * ground has finished doing anything. Requested from play, and the scoping restated there:
	 * <i>"if it's in the immediate same patch area for specifically those three patch types,
	 * don't go there unless whatever is ticked out of those three are ready, diseased, or
	 * dead"</i> — so two ticked types wait for each other exactly as three do, and an unticked
	 * type is not part of the question.
	 *
	 * <p>What counts as finished, and its complement is the whole test:
	 *
	 * <ul>
	 *   <li><b>ready</b> — grown, or due by the clock. Nothing more is coming.</li>
	 *   <li><b>diseased or dead</b> — waiting on a crop that is dying is how it dies.</li>
	 *   <li><b>empty</b> — and this one is not optional. An empty patch is only filled by
	 *       visiting, so holding for it would hold the plot for ever.</li>
	 * </ul>
	 *
	 * <p>A crop still owed its protection payment also lets the trip through: that is an errand
	 * at the plot rather than a crop to wait on, and it is the same reason the payment keeps a
	 * patch actionable at all. Nor is the plot the player is standing on ever held: the whole
	 * point is saving the teleport, and that one is already spent.
	 *
	 * <p>Two places ask, with different scope. {@link #planStops} asks it raw — but a held
	 * plot still rides along into a region the run is stopping at anyway, because there is no
	 * teleport left to save there; see the note in that method. {@link #stillWanted} asks it
	 * only for patches the stop has already serviced once, which is what keeps a replanted
	 * flower from fetching the run back alone without letting a growing sibling complete the
	 * stop over ripe work the player has not finished — the reconciliation is written out at
	 * that method.
	 */
	private boolean clusterHeld(FarmPatch patch, Set<PatchImplementation> types)
	{
		if (!config.holdClustersUntilReady()
			|| !(CLUSTER_TYPES.contains(patch.getImplementation()) || isClusterBin(patch)))
		{
			return false;
		}

		// Never for the plot being stood on. The hold exists to save the teleport, and
		// standing there means it is already spent — starting a run at Falador should pick
		// Falador's ready flower whatever the herb beside it is doing.
		//
		// Asked of every region id that belongs to the plot, not the canonical one alone.
		// Catherby's plot spans four (11061/11062/11317/11318, see FarmingWorldData), so the
		// bare compare read a player one tile over the boundary — exactly where a rollover
		// leaves them — as elsewhere, and held the plot they were standing beside. The same
		// bug class RunStop.claimsRegion exists to prevent, caught here too.
		if (com.dooglemaps.data.FarmingWorldData.claimsRegionId(patch.getRegion(),
			playerLocation.getRegionId()))
		{
			return false;
		}

		for (FarmPatch sibling : patch.getRegion().getPatches())
		{
			if (!CLUSTER_TYPES.contains(sibling.getImplementation())
				|| !types.contains(sibling.getImplementation())
				|| !inTheRun(sibling)
				|| !availability.isAvailable(sibling))
			{
				continue;
			}

			// A contract patch never holds the plot it stands in.
			//
			// Reported from play: "our group rule about not harvesting allotment/herb/flower
			// patches until they're all ready should not apply in the farming guild where one of
			// them was a contract I just completed, this caused me to skip the flower/allotment
			// patches."
			//
			// Completing a contract plants the next crop Jane asked for, so the guild's plot
			// acquires a freshly sown patch at a moment nothing else there chose. The hold then
			// reads it exactly as it reads any growing crop and parks the whole plot behind it —
			// which is the one thing this setting must not do, because a contract patch will
			// essentially never come ripe in step with the plot. The ready flower and allotments
			// beside it were skipped, and would go on being skipped for as long as the contract
			// runs.
			//
			// The hold exists to save a teleport by arriving once, when everything is ready. That
			// bargain is between patches sharing a growth cycle. A contract keeps its own clock,
			// set by Guildmaster Jane, so it is not a party to it and cannot be waited for.
			//
			// Only the holding half is waived. The contract patch is still serviced when the run
			// gets there, and still held back from the plot's own ordering by contractComesFirst
			// — see GuideTracker. What changes is that it no longer speaks for its neighbours.
			com.dooglemaps.data.PlantingGroup siblingGroup = groups.groupFor(sibling);
			if (siblingGroup != null && siblingGroup.isContract())
			{
				continue;
			}

			PatchProjection projection = growthTimer.project(sibling, stateStore.get(sibling));
			if (projection != null && !projection.isEmpty()
				&& projection.getCropState() == CropState.GROWING
				&& !projection.isReady()
				&& !wantsProtectionPayment(sibling, projection))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Every shared plot and what the hold made of it, for the run-planned log.
	 *
	 * <h2>Why this exists</h2>
	 *
	 * "It routed me to a plot with a crop still growing" has now been reported against a hold
	 * whose own logic reads correctly, and the decision is unrecoverable after the fact: it is
	 * derived at {@code start} from four stores at once, and by the time the trip is noticed the
	 * crop has grown on. Every input is cheap to print and none of it is printed anywhere else —
	 * same reasoning as the run-planned line above it, and the seed-box highlight line.
	 *
	 * <p>Each plot names its own patches with the verdict that decided it, so a hold that did
	 * not happen says <b>which</b> patch let the trip through and on what grounds. The three
	 * ways that can happen without the hold being broken are all called out by name: the patch
	 * type is not ticked, the crop is owed a protection payment, or you are standing there.
	 */
	private String describeClusterHolds(Set<PatchImplementation> types)
	{
		Map<FarmRegion, List<String>> byPlot = new LinkedHashMap<>();
		for (PatchImplementation type : CLUSTER_TYPES)
		{
			for (FarmPatch patch : availability.getAvailablePatches(type))
			{
				byPlot.computeIfAbsent(patch.getRegion(), k -> new ArrayList<>())
					.add(patch.getDisplayName() + ": " + describeClusterPatch(patch, types));
			}
		}

		if (byPlot.isEmpty())
		{
			return "none available";
		}

		List<String> plots = new ArrayList<>();
		byPlot.forEach((region, patches) ->
		{
			boolean held = region.getPatches().stream()
				.filter(patch -> CLUSTER_TYPES.contains(patch.getImplementation()))
				.anyMatch(patch -> clusterHeld(patch, types));

			// A held plot in a region the run stops at anyway is not skipped, it rides along —
			// see planStops. Said here because "HELD" over a stop the route visibly makes reads
			// as the hold being broken, which is this line's whole reason to exist.
			boolean riding;
			synchronized (this)
			{
				riding = held && stops.containsKey(region.getRegionId());
			}
			plots.add(region.getName() + " ["
				+ (held
					? (riding ? "held, but the stop is made anyway - riding along" : "HELD")
					: "free to visit")
				+ "] " + patches);
		});
		return String.join("; ", plots);
	}

	/** One patch's side of {@link #describeClusterHolds} — the state, and what it counted for. */
	private String describeClusterPatch(FarmPatch patch, Set<PatchImplementation> types)
	{
		if (!types.contains(patch.getImplementation()) || !inTheRun(patch))
		{
			return "not ticked for this run, so it holds nothing";
		}
		if (com.dooglemaps.data.FarmingWorldData.claimsRegionId(patch.getRegion(),
			playerLocation.getRegionId()))
		{
			return "you are standing here, so nothing is held";
		}

		PatchProjection projection = growthTimer.project(patch, stateStore.get(patch));
		if (projection == null)
		{
			return "never observed, so it holds nothing";
		}
		if (projection.isEmpty())
		{
			return "empty - holds nothing, it needs the visit to be planted";
		}
		if (projection.getCropState() != CropState.GROWING)
		{
			return "finished (" + projection.getCropState() + ") - holds nothing";
		}
		if (projection.isReady())
		{
			return "grown, waiting on a check - holds nothing";
		}
		if (wantsProtectionPayment(patch, projection))
		{
			return "growing, but owed its protection payment - lets the trip through";
		}
		return "growing - HOLDS the plot";
	}

	/** Whether a growing crop is protectable, asked to be protected, and not yet paid for. */
	private boolean wantsProtectionPayment(FarmPatch patch, PatchProjection projection)
	{
		if (projection.isEmpty() || projection.getCropState() != CropState.GROWING
			|| !DiseaseRisk.isProtectable(patch))
		{
			return false;
		}

		com.dooglemaps.state.PatchSnapshot snapshot = stateStore.get(patch);
		if (snapshot != null && snapshot.isPatchProtected())
		{
			return false;
		}

		Seed growing = Seed.forProduce(projection.getProduce());
		return growing != null && protection.isProtecting(groups.groupFor(patch), growing);
	}

	/**
	 * Whether the run should start by collecting anything.
	 *
	 * <p>Only skipped when we positively know the seeds are already on the player. Having
	 * picked no seeds at all is not the same thing — it means we do not know what the trip
	 * needs, and defaulting to a bank is the useful answer there.
	 */
	private boolean needsSupplyTrip()
	{
		// The gear phase's supplies are a setup rather than a withdraw list, so every
		// farming-shaped clause below is the wrong question for it - the seed-source clause in
		// particular would park the leg on a hespori seed sitting in the bank that the player's
		// own loadout is about to cover. The pushed flag is the whole answer at both ends of
		// the leg: the guide pushes the handoff's verdict during the phase instead of the
		// loadout's. See InventorySetupsHandoff and isGearPhase.
		if (isGearPhase())
		{
			return withdrawOutstanding;
		}

		// A deposit trip is about the pack and nothing else, so the pack is its whole answer.
		// It used to fall through here — "the same visit collects anything wanted" — and the
		// ordinary clauses chained the leg open on the run's standing wants: vault seeds the
		// player had deliberately waived held a trip that existed to shed logs, so however
		// much they dropped, the run kept pointing at a bank. Reported from play: "the plugin
		// really doesn't like when I drop things, it thinks my pack is full." Dropping IS
		// dealing with the pack; the leg ends the moment there is room again, and anything
		// genuinely collectable re-arms its own trip through the ordinary reviews.
		if (currentLegIs(BankLegReason.DEPOSIT))
		{
			return packFull;
		}

		// A tool that exists only in the bank is as much a reason to open at one as a seed is:
		// arriving at a weedy patch without a rake means nothing at that stop can be done. Asked
		// first because it holds whether or not any seed was picked.
		//
		// The common case answers no without a bank in sight — the leprechaun stores every tool,
		// and now that his store is read rather than assumed, "he has it" is a fact rather than a
		// hope. See ToolNeeds.
		if (tools.anyOnlyInBank(runTypesSnapshot()))
		{
			return true;
		}

		if (selectedForThisRun().isEmpty())
		{
			// Nothing picked for anything this run visits, so we cannot know what it needs.
			// A bank is the useful default there — and being sent to one while standing on
			// work is handled separately, by starting the run where you are.
			return true;
		}
		if (!getSupplySources().isEmpty())
		{
			return true;
		}
		// The rest of the list: the axe, the protection payments, the compost. Opening at a bank
		// for those is the same decision as staying at one until they are collected, and asking
		// the same question at both ends is what keeps the two from disagreeing — see
		// suppliesOutstanding. This is the clause that sends a tree contract back for its axe.
		// Read from the pushed flag, which start() seeds from its caller for exactly this moment.
		return withdrawOutstanding;
	}

	/**
	 * Whether anything specific is still waiting to be collected.
	 *
	 * <h2>Not the same question as {@link #needsSupplyTrip()}, and the difference is the whole point</h2>
	 *
	 * That one decides whether to <b>open</b> at a bank, and it answers yes in a case this must
	 * answer no: when no seed has been picked for anything the run visits, it sends you to a bank
	 * on the reasoning that we cannot know what the trip needs and a bank is the useful default.
	 *
	 * <p>Sound for opening. Fatal for closing — "we do not know what you need" is not a state any
	 * amount of banking resolves, so using the same test for both ends left the leg unable to
	 * finish and the run parked at the bank permanently. {@code RunPlannerTest} caught it, which is
	 * exactly what those tests are for.
	 *
	 * <p>So this is the same test with that clause removed: a tool that exists only in the bank, or
	 * a seed the run is short of and can actually reach. Both are things a withdrawal makes go
	 * away.
	 *
	 * <p><b>Nothing unobtainable blocks.</b> {@link #getSupplySources()} only reports sources that
	 * genuinely hold the seed, so one you own none of anywhere is not outstanding — the leg
	 * completes and the run skips those patches, which {@code LoadoutSummary} says out loud.
	 *
	 * <h2>Asked of the withdraw list, which it did not used to be</h2>
	 *
	 * This derived its own answer from two of the loadout's inputs — a tool only in the bank, and a
	 * seed the run is short of — and so was blind to everything the loadout learned to ask for
	 * afterwards. Three things were on the list and could not close the leg: the axe, the
	 * protection payment, and a contract's own seed. All three were reported from play as arriving
	 * at a patch unable to do anything there.
	 *
	 * <p>{@code RunLoadout.anythingLeftToWithdraw} is the same question asked once, of the list the
	 * player is actually reading. The two clauses below are kept in front of it rather than
	 * deleted: they hold before a bank has ever been opened, where the loadout can only answer
	 * {@code UNKNOWN}, and they are the pair every existing test pins.
	 */
	private boolean suppliesOutstanding()
	{
		// Same early answers as needsSupplyTrip, and they must be: the two ends of the leg
		// have to agree, and neither the tool store nor the seed sources describe a gear stop
		// or a full pack.
		if (isGearPhase())
		{
			return withdrawOutstanding;
		}
		if (currentLegIs(BankLegReason.DEPOSIT))
		{
			// The pack is the whole answer, as in needsSupplyTrip — see the note there.
			return packFull;
		}

		if (tools.anyOnlyInBank(runTypesSnapshot()))
		{
			return true;
		}
		if (!getSupplySources().isEmpty())
		{
			return true;
		}
		// The pushed answer - see withdrawOutstanding. Refreshed every tick by the guide and
		// at the call sites that need it mid-tick, so reading it here is reading the list.
		return withdrawOutstanding;
	}

	/** The hespori's one region, derived from the data rather than repeated as a number. */
	private static final int HESPORI_REGION = com.dooglemaps.data.FarmingWorldData
		.getPatches(PatchImplementation.HESPORI).get(0).getRegion().getRegionId();

	/**
	 * Whether the run is in its gear phase: the hespori stop is planned and not yet done.
	 *
	 * <h2>A phase, not a property of the run</h2>
	 *
	 * "The run covers the hespori" was the first cut, and it painted the whole run with the
	 * gear doctrine — a mixed run's farming half then had no withdraw list, no bank filter and
	 * no highlights, forever. The doctrine is only right <b>up to</b> the hespori: gear up,
	 * fight, and then the run is a farm run again, with a bank trip in between that
	 * {@link #reviewGearSwap} owes it. So the phase ends the moment the hespori stop stops
	 * wanting work — killed and replanted, or waved past with the region skip.
	 *
	 * <p>And it never starts if the hespori was not planned at all: a growing hespori ticked
	 * in an everything-run contributes no stop, and the run stays an ordinary farm run — which
	 * is what lets the tick stay on permanently the way every other line does.
	 *
	 * <p>Asked of the run's own state rather than of the handoff class, deliberately: the
	 * planner's relationship with the loadout side is a pushed flag precisely so it never
	 * depends back on it. The handoff asks <i>this</i> — see {@code InventorySetupsHandoff}.
	 */
	/**
	 * The gear phase's answer as of the last tick, published for the hot paths.
	 *
	 * <p>{@link #computeGearPhase()} takes this planner's monitor and the growth stores'
	 * locks, and two of its callers turned out to be per-frame — the bank overlay's render
	 * and the infobox's any-thread update. A paint thread queueing on the planner's monitor
	 * is exactly the traffic {@code RunSnapshot}'s doctrine exists to remove, and it was
	 * reported from play as lock stalls the moment the resource monitor could see them. So
	 * the phase is computed where the tick already computes it — {@link #reviewGearSwap},
	 * {@code start} — and everyone else reads this volatile for free. A one-tick lag is
	 * already the phase's own tolerance everywhere it matters; the falling-edge arming reads
	 * the live computation, not this.
	 */
	private volatile boolean gearPhaseNow;

	public boolean isGearPhase()
	{
		return active && gearPhaseNow;
	}

	/** The live computation behind {@link #isGearPhase()}; tick-side callers only. */
	private boolean computeGearPhase()
	{
		synchronized (this)
		{
			if (!active || !runTypes.contains(PatchImplementation.HESPORI)
				|| skippedRegions.contains(HESPORI_REGION)
				|| !stops.containsKey(HESPORI_REGION))
			{
				return false;
			}
		}
		// The projection asks the stores, so it is asked with the monitor released - the
		// ordering rule at the top of the file.
		return hesporiAwaitsTheFight();
	}

	/**
	 * Whether the hespori is grown and waiting to be fought — the fact the gear phase is for.
	 *
	 * <h2>Grown, not merely actionable</h2>
	 *
	 * The phase first keyed on the hespori's stop existing and not being complete, and a stop
	 * exists for a weedy or empty hespori too — so the run demanded combat gear to rake
	 * weeds. And after the kill the patch reads as weeds or a fresh seedling, so a completion
	 * key made the swap-back wait for a replant the cave gear cannot perform: no seed, no
	 * farming tools, no way for the stop to finish. The boss being up is the whole reason the
	 * gear exists, so it is the whole test. A weedy, empty or freshly killed hespori is an
	 * ordinary patch on an ordinary run, raked and planted in farm gear like everything else.
	 *
	 * <h2>Grown by the almanac's test, not by the varbit alone</h2>
	 *
	 * Keying that on {@code HARVESTABLE} was the wrong way to ask it, and it shipped the
	 * failure this doc used to file under "rare". Only a <i>checked</i> patch reads
	 * HARVESTABLE — a hespori whose timer has run out but which nobody has stood in front of
	 * since still reads GROWING, which is the ordinary shape of an overnight hespori rather
	 * than an edge case. The panel already says as much in its own words: {@code "ready?"},
	 * where the question mark is exactly "the clock says done, I have not seen it".
	 *
	 * <p>Routing has always counted that state ({@link PatchProjection#isReady}, and see the
	 * stop test's note on grown-but-unchecked trees), so the run planned the hespori stop and
	 * packed a seed while the gear phase stayed off and handed the bank leg to the ordinary
	 * withdraw list — farmer's outfit and seed box, into a boss. Reported from play. The two
	 * tests now agree, which is the point: {@code isReady()} is what routes the stop and what
	 * arms the gear.
	 *
	 * <p>The narrowing that mattered survives, because {@code isEmpty()} and {@code isReady()}
	 * between them still refuse a weedy, empty or freshly killed hespori: none of those has
	 * finished growing, so the phase neither starts on one nor survives the kill. And the cost
	 * of the looser test falls the right way round — a projection that turns out wrong carries
	 * combat gear that was not needed, where the strict one walked into the cave unarmed.
	 *
	 * <p>A never-observed hespori still answers no: a first-ever visit travels in farm clothes
	 * and discovers the state, and the phase engages on the next run — or mid-run the moment
	 * the varbit is read, which narrows routing and steps to the cave even though no gear leg
	 * was armed for it; the player banks by hand in that genuinely rare case.
	 */
	private boolean hesporiAwaitsTheFight()
	{
		FarmPatch hespori = com.dooglemaps.data.FarmingWorldData
			.getPatches(PatchImplementation.HESPORI).get(0);
		PatchProjection projection = growthTimer.project(hespori, stateStore.get(hespori));
		return projection != null && !projection.isEmpty() && projection.isReady();
	}

	/** The hespori's stored state in words, for the run-planned Hespori check line. */
	private String describeHesporiState()
	{
		FarmPatch hespori = com.dooglemaps.data.FarmingWorldData
			.getPatches(PatchImplementation.HESPORI).get(0);
		PatchProjection projection = growthTimer.project(hespori, stateStore.get(hespori));
		if (projection == null)
		{
			return "never observed (it reads from the guild's west wing, by the cave)";
		}
		if (projection.isEmpty())
		{
			return "reads as empty";
		}
		String reading = "reads as " + projection.getProduce().getName() + " "
			+ projection.getCropState();
		if (projection.getCropState() == CropState.HARVESTABLE)
		{
			return reading + " - the boss is up";
		}
		// The gear phase arms on isReady(), so this line has to as well, or the one state that
		// caused the confusion — timer elapsed, patch unchecked, panel showing "ready?" —
		// reports as a plain GROWING while the run gears up for a fight.
		if (projection.isReady())
		{
			return reading + " - timer elapsed, so the boss is up unless the projection is wrong";
		}
		return reading;
	}

	/**
	 * Whether a run over these types would open with a gear phase — the pre-start form of
	 * {@link #isGearPhase}, for the button's "does this run start at a bank" question.
	 */
	public boolean wouldOpenWithGearPhase(Set<PatchImplementation> types)
	{
		return types.contains(PatchImplementation.HESPORI)
			&& hesporiAwaitsTheFight()
			&& planStops(types).containsKey(HESPORI_REGION);
	}

	/** The current supply leg's reason, under the monitor like the flag it rides with. */
	private synchronized boolean currentLegIs(BankLegReason reason)
	{
		return atBankLeg && bankLegReason == reason;
	}

	/** Each tool the run wants and where it is, for the {@code Run planned:} line. */
	private String describeTools()
	{
		List<String> parts = new ArrayList<>();
		for (ToolNeeds.Requirement requirement : tools.forRun(runTypesSnapshot()))
		{
			parts.add(requirement.getTool().getDisplayName() + "=" + requirement.getSource());
		}
		return parts.isEmpty() ? "none needed" : String.join(", ", parts);
	}

	/** The run's patch types, copied under the lock so callers can walk them without it. */
	/**
	 * The patch types this run actually covers.
	 *
	 * <h2>Why asking {@code RunTypeStore} directly is not the same question</h2>
	 *
	 * That store holds the boxes the player ticked, and for most of a run the two agree. They stop
	 * agreeing the moment a farming contract is taken mid-run: {@link #reviewContract} adds the
	 * contract's type to the live run, because the guild's patch for it has to be serviced whether
	 * or not the player ticked that type — and nothing told the store, which is right, since it is
	 * a record of a choice and the player did not make one.
	 *
	 * <p>So everything scoped to "what does this run need" has to ask the run, not the boxes.
	 * Reported from play: a yew contract accepted on a herb run left the withdraw list naming only
	 * teleports while the seed vault was outlined for a sapling the list had never heard of — the
	 * highlight was scoped to the run and the list to the boxes.
	 *
	 * <p>Falls back to the ticked types when nothing is running, because the panel prices up runs
	 * that have not been started and that is the only set there is then.
	 */
	public Set<PatchImplementation> coveredTypes()
	{
		synchronized (this)
		{
			if (active && !runTypes.isEmpty())
			{
				Set<PatchImplementation> copy = EnumSet.noneOf(PatchImplementation.class);
				copy.addAll(runTypes);
				return copy;
			}
		}

		// Outside the lock: RunPlanner -> RunTypeStore, the order everything else here takes them in.
		return runOptions.getSelected();
	}

	private Set<PatchImplementation> runTypesSnapshot()
	{
		synchronized (this)
		{
			Set<PatchImplementation> copy = EnumSet.noneOf(PatchImplementation.class);
			copy.addAll(runTypes);
			return copy;
		}
	}

	/**
	 * Seeds picked for patch types this run will actually visit.
	 *
	 * <p>Scoped to the run, which it was not before. Every selected seed was considered
	 * whatever the run covered, so a bush seed picked months ago could send a herb run to the
	 * bank — and it did: standing on ripe watermelons at Ardougne, the plugin asked for a
	 * teleport to a bank for seeds nothing on the trip needed.
	 *
	 * <p>Also skips a type with no actionable patch. Owning no seed for a patch that is not
	 * going to be planted is not a problem to solve.
	 *
	 * <h2>By planting group, which it did not used to be</h2>
	 *
	 * This asked {@code selection.getSelectedFor(PatchImplementation)} for each type in the run,
	 * and that overload cannot answer for a contract. A contract's seed is <b>derived</b> —
	 * {@code SeedSelectionStore.contractSelection()} reads it off the assignment — and is
	 * deliberately never written into the flat set of picks, because nobody picked it. Filtering
	 * that flat set by patch type therefore returns every seed except the one the contract needs.
	 *
	 * <p>{@code RunLoadout.addSeeds} has always resolved by <i>group</i> and so has always seen it.
	 * The two disagreeing is what put a yew on the withdraw list while every routing decision here
	 * — where to send the supply leg, whether the vault is owed, whether the shopping is finished —
	 * was made as though no yew existed. The run could and did leave the bank without it.
	 *
	 * <p>The same overload was wrong a second way in the same case. A contract adds its patch type
	 * to the run ({@link #reviewContract}), so asking by type pulled in <b>every tree seed ever
	 * ticked</b> for a run whose only tree patch is the contract's — a magic sapling selected
	 * months ago became a thing this trip had to go and fetch. Groups do not have that problem:
	 * the guild's tree patch belongs to the contract group and to nothing else.
	 *
	 * <p>So this now walks {@link #actionableByGroup} and asks per group, which is precisely what
	 * the loadout does. Sharing the shape rather than the code is deliberate — the two need
	 * different quantities, one patch's worth against the whole run's — but the set of seeds is
	 * one question and there is now one way of asking it.
	 */
	private Set<Seed> selectedForThisRun()
	{
		Set<PatchImplementation> types = runTypesSnapshot();

		if (types.isEmpty())
		{
			// No run in flight, so there is no run to scope to. Answering "nothing needed"
			// would be a worse lie than answering broadly: a caller asking this outside a run
			// wants to know about the seeds picked, not about a run that does not exist.
			return selection.getSelected();
		}

		Set<Seed> wanted = new LinkedHashSet<>();
		for (Map.Entry<PlantingGroup, List<FarmPatch>> entry : actionableByGroup(types).entrySet())
		{
			if (entry.getValue().isEmpty() || plantsNothing(entry.getKey()))
			{
				continue;
			}
			wanted.addAll(selection.getSelectedFor(entry.getKey()));
		}
		return wanted;
	}

	/**
	 * The seeds the run will actually put in the ground, which is a smaller set than the
	 * seeds picked.
	 *
	 * <h2>Why the difference held a supply leg open forever</h2>
	 *
	 * With seed priorities, picking a backup seed is normal: snape grass first, watermelons
	 * as the spill-over. The allocation gives the backup nothing when the first seed covers
	 * every patch — so the loadout, correctly, asks for no watermelons and the withdraw list
	 * empties once the snape grass is out. But {@link #getSupplySources} walked the <i>raw</i>
	 * selection and demanded a patch's worth of every picked seed, so the watermelons sitting
	 * in the bank held {@code suppliesOutstanding} true with nothing left on the list: the
	 * booths stayed outlined and the panel fell into its "nothing is picked" line, which was
	 * wrong twice over. Reported from play, at Camelot, on a mid-run seed detour.
	 *
	 * <p>So the sources question now walks the allocation's answer, the same
	 * {@link SeedAllocation} the loadout, the estimate and the guide share. Deliberately
	 * <b>without</b> the protection budget: this asks what to collect, and the payments that
	 * cap the budget may themselves be part of what is still to collect — capping by them
	 * here would drop a seed's container the moment its payments were missing, which is
	 * backwards. An uncapped allocation still applies the ranking and the stock limits,
	 * which is all the scoping this question needs.
	 *
	 * <p>Outside a run this falls back to the raw selection, like
	 * {@link #selectedForThisRun} and for the same reason.
	 */
	private Set<Seed> seedsWantedThisRun()
	{
		Set<PatchImplementation> types = runTypesSnapshot();

		if (types.isEmpty())
		{
			return selection.getSelected();
		}

		Set<Seed> wanted = new LinkedHashSet<>();
		for (Map.Entry<PlantingGroup, List<FarmPatch>> entry : actionableByGroup(types).entrySet())
		{
			if (entry.getValue().isEmpty() || plantsNothing(entry.getKey()))
			{
				continue;
			}

			Set<Seed> picked = selection.getSelectedFor(entry.getKey());
			Map<Seed, Integer> owned = new java.util.HashMap<>();
			for (Seed seed : picked)
			{
				owned.put(seed, seedInventory.getOwned(seed));
			}

			wanted.addAll(SeedAllocation.forPatches(entry.getValue(), picked, owned,
				seedInventory.getFarmingLevel(), ProtectionBudget.NONE)
				.counts().keySet());
		}
		return wanted;
	}

	/**
	 * Whether this group is being visited without anything going in the ground.
	 *
	 * <p>The same two cases {@code RunLoadout.plantsNothing} covers, and it has to be the same two
	 * or the run banks for one plan and plants another. An actionable patch is not a patch about to
	 * be sown: a harvest-only group is deliberately being left standing, and a contract whose crop
	 * has finished growing wants handing in, not replanting — what goes in it next is decided by
	 * whichever contract Jane gives out afterwards, which nobody knows yet.
	 *
	 * <p>Called with the lock released; {@link #ripeProduceIn} takes it for itself.
	 */
	private boolean plantsNothing(PlantingGroup group)
	{
		if (runOptions.isHarvestOnly(group))
		{
			return true;
		}
		if (!group.isContract())
		{
			return false;
		}
		Produce contract = groups.contractCrop();
		return contract != null && ripeProduceIn(group).containsKey(contract);
	}

	/** Stop regions, for the diagnostic. */
	private synchronized String stopRegions()
	{
		List<String> named = new ArrayList<>();
		for (RunStop stop : stops.values())
		{
			named.add(stop.getName() + "=" + stop.getRegion().getRegionId());
		}
		return named.toString();
	}

	/**
	 * What the run thinks you picked, for the diagnostic.
	 *
	 * <p>All four sources, not just the two on the player. "Still to collect" is decided off
	 * the bank and vault counts, so a line without them was half an answer for exactly the
	 * report it exists to settle — an empty collect list with seeds nowhere in the pack reads
	 * as a bug, and whether it is one turns entirely on what the store believes the bank
	 * holds. Reported from play, from this very line.
	 */
	private String describeSelectedForThisRun()
	{
		List<String> named = new ArrayList<>();
		for (Seed seed : selectedForThisRun())
		{
			named.add(seed.getName() + " x" + seed.getSeedsPerPatch()
				+ " (inv " + seedInventory.getCount(seed, SeedSource.INVENTORY)
				+ ", box " + seedInventory.getCount(seed, SeedSource.SEED_BOX)
				+ ", bank " + seedInventory.getCount(seed, SeedSource.BANK)
				+ ", vault " + seedInventory.getCount(seed, SeedSource.SEED_VAULT) + ")");
		}
		return named.isEmpty() ? "none" : named.toString();
	}

	/**
	 * How stale each seed source's counts are, for the same diagnostic.
	 *
	 * <p>A count of zero means two very different things from a source read a minute ago and
	 * one never read at all, and the decision above cannot tell them apart — but the reader
	 * of the log can, if the line says which.
	 */
	private String describeSeedSourceAges()
	{
		List<String> ages = new ArrayList<>();
		long now = java.time.Instant.now().getEpochSecond();
		for (SeedSource source : SeedSource.values())
		{
			long seen = seedInventory.getLastSeen(source);
			ages.add(source.name().toLowerCase() + " "
				+ (seen <= 0 ? "never read" : describeAge(now - seen)));
		}
		// The reconcile's pulse rides along because "inventory never read" appeared in play
		// with events demonstrably flowing — which of its stages is failing is the question,
		// and the counters answer it. See SeedInventoryStore.relearnInventoryFromClient.
		return String.join(", ", ages)
			+ "; inventory reconcile: " + seedInventory.describeReconcile();
	}

	/** A duration in the units a log reader thinks in: "40s ago", "12m ago", "3h ago", "2d ago". */
	private static String describeAge(long seconds)
	{
		if (seconds < 60)
		{
			return seconds + "s ago";
		}
		if (seconds < 3600)
		{
			return (seconds / 60) + "m ago";
		}
		if (seconds < 86400)
		{
			return (seconds / 3600) + "h ago";
		}
		return (seconds / 86400) + "d ago";
	}

	/**
	 * Whether the player is already standing somewhere this run has work.
	 *
	 * <p>If so the run starts <i>here</i>, whatever the bank leg would otherwise say. Being
	 * routed away from ripe crops you are stood on is wrong however much the trip needs
	 * collecting later, and the supplies are not forgotten — see {@code supplyOwed}.
	 */
	private boolean standingAtAStop()
	{
		// Through the cached position, because this is called from the Swing thread when the
		// Start run button is pressed and asking the player directly asserts the client thread.
		int region = playerLocation.getRegionId();
		if (region == -1)
		{
			return false;
		}

		synchronized (this)
		{
			// Scanned rather than looked up by key, because a stop can stand in more ground than
			// the one region it is filed under - see RunStop.claimsRegion. A run started among
			// the coral nurseries used to answer "standing on nothing" and open at a bank for
			// the compost bins, with weedy patches underfoot. Reported from play. The map is a
			// dozen entries, so the walk costs nothing worth keeping the key lookup for.
			for (RunStop here : stops.values())
			{
				if (here.claimsRegion(region) && !isComplete(here))
				{
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * Whether somewhere the run can collect supplies is in the region the player is standing in.
	 *
	 * <p>Asked so that "standing on work" does not override a bank you could reach without moving.
	 * The Farming Guild is the case that forces it — its bank, its seed vault and eleven of its
	 * patches share one region — but Catherby, Falador and Ardougne are all the same shape.
	 *
	 * <p>Built from {@link #getSupplyTargets()} rather than from the bank list, so it agrees with
	 * wherever the run would actually have routed: a run whose seeds are in the vault checks for
	 * the vault, not for any bank that happens to be nearby.
	 *
	 * <p>Called with no lock held, like the other two inputs to the bank-leg decision.
	 */
	private boolean supplyPointIsHere()
	{
		int region = playerLocation.getRegionId();
		if (region == -1)
		{
			return false;
		}

		for (WorldPoint target : getSupplyTargets())
		{
			if (target != null && target.getRegionID() == region)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Where the run's seeds have to be collected from.
	 *
	 * <p>The seed vault matters here because there is exactly one, in the Farming Guild.
	 * Routing to "the nearest bank" for seeds that are sitting in the vault sends the player
	 * to precisely the wrong side of the map. Seeds already in the inventory or seed box need
	 * no trip at all.
	 */
	public Set<SeedSource> getSupplySources()
	{
		Set<SeedSource> needed = EnumSet.noneOf(SeedSource.class);

		for (Seed seed : seedsWantedThisRun())
		{
			int required = seed.getSeedsPerPatch();
			int carried = seedInventory.getCount(seed, SeedSource.INVENTORY)
				+ seedInventory.getCount(seed, SeedSource.SEED_BOX);
			if (carried >= required)
			{
				continue;
			}

			// Enough to be worth the trip, not merely present. A single seed sitting in the
			// bank used to send the player there for an allotment that needs three, while the
			// vault that actually had them was never considered.
			boolean inBank = seedInventory.getCount(seed, SeedSource.BANK) >= required;
			boolean inVault = seedInventory.getCount(seed, SeedSource.SEED_VAULT) >= required;

			if (inBank)
			{
				needed.add(SeedSource.BANK);
			}
			else if (inVault)
			{
				needed.add(SeedSource.SEED_VAULT);
			}
			else if (seedInventory.getCount(seed, SeedSource.BANK) > 0)
			{
				// Neither has a full patch's worth. The bank is the better guess for topping
				// up, and it is where everything else for the run is anyway.
				needed.add(SeedSource.BANK);
			}
			else if (seedInventory.getCount(seed, SeedSource.SEED_VAULT) > 0)
			{
				needed.add(SeedSource.SEED_VAULT);
			}
		}
		return needed;
	}

	/**
	 * The places the opening leg could usefully end at.
	 *
	 * <p>The seed vault wins outright whenever it is needed, and does not sit alongside the
	 * banks as an alternative. Shortest Path routes to the <i>cheapest reachable</i> member of
	 * a target set, so offering both meant the nearest bank always won and the vault seeds were
	 * never collected — the run would arrive at a bank that did not have them.
	 *
	 * <p>Sending the player to the vault costs nothing when the bank is also needed, because
	 * the Farming Guild has a bank chest a few steps from the vault. And anyone with seeds in
	 * the vault necessarily has guild access, so it cannot route somewhere unreachable.
	 */
	private Set<WorldPoint> getSupplyTargets()
	{
		return supplyTargetsFor(getSupplySources());
	}

	/**
	 * Everywhere the supply leg still has to visit.
	 *
	 * <h2>Both, when the trip needs both</h2>
	 *
	 * This used to return the vault <i>instead of</i> the banks whenever a single seed lived there,
	 * collapsing a two-container errand into one. The rest of the plugin never agreed to that: the
	 * loadout lists the two separately because they are separate jobs, and the leg does not end
	 * until <b>both</b> are empty. So the route pointed at the vault while the withdraw list read
	 * "From the bank: yew, yew, rune pouch, book of the dead", and the player was left to work out
	 * which of the two the plugin meant. Reported from play.
	 *
	 * <p>Handing over both lets the router do what it does everywhere else here — pick whichever is
	 * cheapest to reach, then be asked again once that one is done. Neither is imposed first, which
	 * is the same "either order" the summary and the highlight already promise.
	 *
	 * <p>Banks are the answer to an empty set as well as to {@code BANK}. Empty means nothing needs
	 * <i>seeds</i>, which is not the same as nothing needing collecting — a tool that exists only in
	 * the bank puts the run on this leg with no seed source at all, and the vault holds nothing but
	 * seeds.
	 */
	private Set<WorldPoint> supplyTargetsFor(Set<SeedSource> sources)
	{
		Set<WorldPoint> targets = new LinkedHashSet<>();

		if (sources.contains(SeedSource.SEED_VAULT))
		{
			// The vault, and the one bank standing in the same room as it.
			//
			// There is exactly one seed vault in the game and it is in the Farming Guild, so a
			// trip that wants it is a trip to the guild whatever else it wants. Handing over
			// every bank as well let the router do exactly what it is asked to do — pick the
			// cheapest — and it picked whichever bank the player happened to be near, because
			// there is nearly always one closer than the guild. The vault then still had to
			// happen, from wherever that bank was. Reported from play: "when we start a run or
			// are on a bank trip and need to go to the seed vault, we need to navigate to the
			// farming guild's seed vault, getting navigated to one of the other banks isn't the
			// right move".
			//
			// The guild's chest is ten tiles from the vault, so this costs the bank half
			// nothing — one arrival serves both errands, which is the trip the player was making
			// anyway. That geometry is why this is a target change rather than a trade-off. See
			// BankLocations.FARMING_GUILD_BANK, seeded rather than learned, so it can be routed
			// to without the player ever having opened it.
			//
			// Still BOTH targets, never one: this method used to return the vault INSTEAD of the
			// banks whenever a single seed lived there, which pointed the route at the vault
			// while the withdraw list read "From the bank: yew, yew, rune pouch, book of the
			// dead". Also reported from play. Nothing here changes what the leg has to collect
			// or when it ends — only where it sends you.
			targets.add(banks.getSeedVault());
			if (sources.contains(SeedSource.BANK))
			{
				targets.add(banks.getFarmingGuildBank());
			}
			return targets;
		}

		if (sources.contains(SeedSource.BANK) || sources.isEmpty())
		{
			// Every bank the account can use; the router knows which is genuinely nearest.
			targets.addAll(banks.getUsableBanks());
		}
		return targets;
	}

	/**
	 * Ends the supply leg once there is nothing left to collect.
	 *
	 * <h2>Reaching a bank is not the same as having been to one</h2>
	 *
	 * This used to fire on the first bank container event and end the leg outright — so
	 * <i>opening</i> a bank finished the shopping, whether or not anything came out of it. Two
	 * things followed, and the second is the one that was reported:
	 *
	 * <ul>
	 *   <li>Open a bank, withdraw nothing, and the run moved on to the patches regardless.</li>
	 *   <li>With seeds in the <b>seed vault</b>, opening the guild's bank chest for the payments
	 *       ended the leg — and the vault, three steps away, never got its turn. The run then
	 *       arrived at the patches with no seed for them.</li>
	 * </ul>
	 *
	 * <p>So the condition is now the one that was always meant: <b>is anything still outstanding
	 * that we can actually go and get</b>. That is the same question {@link #needsSupplyTrip()}
	 * answers to decide whether to open at a bank in the first place, which is what makes the two
	 * ends of the leg agree.
	 *
	 * <p><b>An item you own none of does not block.</b> {@code getSupplySources} only counts seeds
	 * that are somewhere reachable, so a seed you have none of anywhere is not a supply source and
	 * the leg completes without it — the run goes ahead and skips those patches, which is what
	 * {@code LoadoutSummary} says out loud rather than leaving you to discover on arrival. Waiting
	 * at a bank for something that cannot be withdrawn would be a run that never starts.
	 *
	 * <p>Safe to call often, and called from the tick as well as from the bank capture: it costs a
	 * flag check unless a supply leg is actually in progress. Being driven by the tick is what lets
	 * the <i>vault</i> finish the leg too, since nothing about the vault fires a bank event.
	 */
	/**
	 * Whether the player has waved the supply leg past; see {@link #waiveBankLeg()}.
	 */
	private boolean bankLegWaived;

	/**
	 * Ends the supply leg on the player's say-so, whatever the withdraw list still wants.
	 *
	 * <p>The leg's exit condition is "nothing outstanding", and every outstanding item is
	 * cleared only by withdrawing it — so a payment the player has decided not to make, or a
	 * tool they mean to buy at a shop instead, parked the run at the bank with no way past
	 * it: {@code retarget()} returns early while at the leg, so even the travel skip could
	 * not get off it. This is the same escape hatch the travel leg has in
	 * {@code skipRegion}, scoped the same way — to this run, cleared with it.
	 */
	public void waiveBankLeg()
	{
		synchronized (this)
		{
			if (!atBankLeg)
			{
				return;
			}
			bankLegWaived = true;
			atBankLeg = false;
			bankLegReason = null;
			supplyOwed = false;
			postedSources = null;
		}
		log.debug("Supply leg waived by the player; routing to patches");
		retarget();
	}

	public void leaveBank()
	{
		synchronized (this)
		{
			if (!atBankLeg)
			{
				return;
			}
		}

		// Asked with the lock released: it walks the tool store, the seed selection and the
		// availability profile, and the ordering rule is RunPlanner -> Availability -> everything
		// else. Re-checked under the lock below rather than trusted, since the answer is computed
		// outside it.
		if (suppliesOutstanding())
		{
			// Still collecting, but possibly not from the same places. Emptying the vault leaves
			// the bank outstanding and vice versa, and each is a separate errand that the route
			// has to follow as it is finished.
			followSupplyProgress();
			return;
		}

		synchronized (this)
		{
			if (!atBankLeg)
			{
				return;
			}
			atBankLeg = false;
			bankLegReason = null;
			supplyOwed = false;
			postedSources = null;
			// Chosen afresh from the bank. The leg picked before the trip was cheapest from
			// wherever the run started, which is not where it is standing now.
			committedRegion = -1;
		}
		log.debug("Supplies collected; routing to patches");
		retarget();
	}

	/**
	 * Redraws the supply route when one of its containers has been emptied.
	 *
	 * <p>Only on a change, so the tick this is called from costs a set comparison rather than a
	 * cross-thread route post. Asked outside the lock, like every other question that walks the
	 * seed stores.
	 */
	private void followSupplyProgress()
	{
		Set<SeedSource> now = getSupplySources();

		synchronized (this)
		{
			if (!atBankLeg || now.equals(postedSources))
			{
				return;
			}
			postedSources = now;
		}

		log.debug("Supply sources narrowed to {}; redrawing", now);
		retarget();
	}

	private synchronized boolean owesSupplies()
	{
		return supplyOwed;
	}

	public void stop()
	{
		synchronized (this)
		{
			stops.clear();
			announced.clear();
			skippedRegions.clear();
			committedRegion = -1;
			runTypes.clear();
			active = false;
			atBankLeg = false;
			bankLegReason = null;
			supplyOwed = false;
			bankLegWaived = false;
			runCompletePending = false;
			postedSources = null;
			gearSwapDone = false;
			gearPhaseSeen = false;
			lastPackFull = false;
			gearPhaseNow = false;
		}
		// Same rule as start: the router is another plugin, reached over an event bus that
		// delivers synchronously, so it is never called with this lock held.
		router.clear();
	}

	public synchronized List<RunStop> getStops()
	{
		return Collections.unmodifiableList(new ArrayList<>(stops.values()));
	}

	/** Stops with anything left to do that the player has not waved past. */
	public synchronized List<RunStop> getRemaining()
	{
		List<RunStop> remaining = new ArrayList<>();
		for (RunStop stop : stops.values())
		{
			if (!skippedRegions.contains(stop.getRegion().getRegionId()) && !isComplete(stop))
			{
				remaining.add(stop);
			}
		}
		return remaining;
	}

	/**
	 * Drops a region from the rest of this run and routes to what is left.
	 *
	 * <p>The next run starts fresh: the set is cleared with the stops, in {@link #start} and
	 * {@link #stop}, which is the same lifecycle the per-step skips follow.
	 */
	public void skipRegion(int regionId)
	{
		synchronized (this)
		{
			skippedRegions.add(regionId);
		}
		log.info("Skipped region {} for the rest of this run", regionId);
		// Outside the lock, the same as every other retarget: it posts into another plugin's
		// event bus, which delivers synchronously.
		retarget();
	}

	/**
	 * Re-checks whether any stop has quietly finished, without waiting to be told.
	 *
	 * <h2>Because the event is not guaranteed to arrive</h2>
	 *
	 * {@link #onPatchChanged} is the fast path and covers the ordinary case, but it can only fire
	 * on a varbit <i>transition</i> the tracker actually witnesses — and there are stops that
	 * finish without one. The plain case is a patch this session has never seen: it is included in
	 * the run optimistically, on the reasoning that it may well be empty, and when you arrive the
	 * first varbit read has no previous value to differ from. If that patch is the only one at the
	 * stop, nothing ever reports a change and the run waits forever for news that is not coming.
	 *
	 * <p>Polling once a tick removes the whole class of problem rather than the instance of it.
	 * Completion is derived from patch state now, so asking again is cheap and always correct —
	 * there is no counter to get out of step, which is the entire reason the derived form was
	 * worth the change.
	 *
	 * <p>Costs one flag check per tick unless a run is under way, and a walk of the outstanding
	 * stops when one is. Called from the plugin's tick beside {@link #leaveBank()}.
	 */
	/**
	 * Pulls a contract's patch into the run, for one that was taken after the run was planned.
	 *
	 * <h2>Why the stop cannot simply have been planned with it</h2>
	 *
	 * The contract chain happens <i>inside</i> the Farming Guild stop: hand the finished one in,
	 * take the next, plant it before you leave. Which crop Jane names is not knowable until she
	 * names it — so at planning time there is no way to include the patch it wants, and by the time
	 * there is, {@link #planStops} has already run and the stop's list is fixed.
	 *
	 * <p>Everything downstream keys off that list. {@code GuideTracker.contractNote} checks whether
	 * the contract's patch is in the stop you are standing in and, finding it absent, said the run
	 * could not deal with the contract at all — "the patch it wants is not free, it will be picked
	 * up on the next run". Reported from play as a yew contract written off for the week, with a
	 * grown tree standing in the patch that only needed checking and clearing.
	 *
	 * <p>Adds a stop outright when the run has none in the guild, because a contract is the one
	 * thing that can create work in a region the player never selected: it can only be done there.
	 *
	 * <p>Polled beside {@link #reviewProgress}, and for the same reason — a contract is taken
	 * through a dialogue, and there is no event here worth trusting to catch every one.
	 */
	public void reviewContract()
	{
		// Unlike reviewProgress, this runs during the supply leg too. The guild holds a bank and the
		// seed vault as well as Jane, so a contract can perfectly well be taken while the run is
		// still collecting — and adopting the patch early costs nothing, since it only makes the
		// stop aware of work it will reach later.
		if (!active)
		{
			return;
		}

		Produce wanted = groups.contractCrop();
		if (wanted == null || !contractIsInTheRun(wanted))
		{
			return;
		}

		boolean joined = false;
		synchronized (this)
		{
			for (FarmPatch patch : availability.getAvailablePatches(wanted.getPatchImplementation()))
			{
				if (!groups.groupFor(patch).isContract() || !isActionable(patch))
				{
					continue;
				}

				int regionId = patch.getRegion().getRegionId();
				RunStop stop = stops.get(regionId);
				if (stop == null)
				{
					stops.put(regionId, new RunStop(patch.getRegion(),
						java.util.Collections.singletonList(patch)));
					log.debug("Contract added a stop at {}", patch.getRegion().getName());
					joined = true;
					continue;
				}

				if (!stop.contains(patch))
				{
					stop.adopt(patch);
					// It has work again, so a completion announced before the contract arrived must
					// not keep the stop from being routed to.
					announced.remove(regionId);
					log.debug("Contract patch {} joined the {} stop",
						patch.getDisplayName(), stop.getName());
					joined = true;
				}
			}

			// The run now covers a patch type it was never planned for, and everything that decides
			// what to carry is scoped to this set: which seeds count as "for this run", and which
			// tools. Without it the contract's own seed is not in the loadout and neither is the axe
			// its tree needs.
			if (joined)
			{
				joined = runTypes.add(wanted.getPatchImplementation());
			}
		}

		if (joined)
		{
			collectForTheContract();
		}
	}

	/**
	 * Whether this run was actually asked to do the farming contract.
	 *
	 * <h2>The one contract path that never consulted the tick</h2>
	 *
	 * Everything else asks {@link #inTheRun}, which reads the contract group's own run option —
	 * so a contract nobody ticked contributes no stops and no patches. {@link #reviewContract}
	 * bypasses that entirely by design, because its job is to <b>adopt</b> a patch the plan did
	 * not have: it tests the group and whether the patch is actionable, and neither of those
	 * knows what the player asked for.
	 *
	 * <p>So a compost-only run at the Farming Guild adopted the guild's bush patch for an
	 * assigned poison ivy contract, widened the run's types to match, and sent the player to a
	 * supply point to collect a seed they had not asked to plant — "Contract needs collecting
	 * for; routing to a supply point" in the log, on a run whose seed list was empty. Reported
	 * from play, twice.
	 *
	 * <p>The contract's line only exists while one is assigned and its patch belongs to this
	 * account, so an unticked answer covers both "there is no contract" and "there is one and
	 * the player is ignoring it". {@code GuideTracker} gates its guild hold-back on the same
	 * question.
	 */
	private boolean contractIsInTheRun(Produce wanted)
	{
		PatchImplementation type = wanted.getPatchImplementation();
		return type != null && runOptions.isSelected(
			com.dooglemaps.data.RunOption.full(PlantingGroup.contract(type)));
	}

	/**
	 * Sends the run back for whatever a mid-run contract has just made it need.
	 *
	 * <h2>Why the supply leg cannot simply have covered it</h2>
	 *
	 * The bank trip happens at the start, and a contract taken from Jane an hour later can want
	 * things nothing on that trip had any reason to bring: its seed, and — for a tree, bush or
	 * hardwood contract on a run that was never going to visit one — an axe. Reported from play as
	 * being told to check a magic tree with no axe and no sapling in the pack.
	 *
	 * <p>Asks the same two questions {@link #start} asks, for the same reasons, and honours the same
	 * rule about not teleporting you off work you are standing on — except that in the Farming Guild
	 * that rule never bites, because the bank and the seed vault are both a few steps from Jane.
	 *
	 * <p>Both questions are asked with the lock released: they walk the availability and patch
	 * stores, and the order has to stay RunPlanner → Availability → PatchStateStore.
	 */
	private void collectForTheContract()
	{
		// Owed before it is asked whether it is wanted, and that ordering is the fix.
		//
		// needsSupplyTrip reads withdrawOutstanding, which is pushed once a tick from the
		// withdraw list - so at this exact moment it still describes the run as it was BEFORE
		// reviewContract widened its types a few lines ago. It cannot know about the contract's
		// seed or its axe yet, answers no, and the old early return left with nothing recorded
		// at all: no leg armed, and supplyOwed never set, so the deferred pickup had nothing to
		// find either. A tree contract taken at the guild therefore never got a trip back for
		// the axe its patch needed. Reported from play.
		//
		// Recording the debt first costs nothing if it turns out to be unwanted: every consumer
		// re-asks needsSupplyTrip before acting on it.
		synchronized (this)
		{
			supplyOwed = true;
		}

		boolean wantsSupplies = needsSupplyTrip();
		if (!wantsSupplies)
		{
			return;
		}

		// Both read the stores, so they are asked before the lock is taken — see start().
		boolean canBankHere = supplyPointIsHere();
		boolean here = standingAtAStop();

		boolean collecting;
		synchronized (this)
		{
			supplyOwed = true;
			atBankLeg = atBankLeg || !here || canBankHere;
			collecting = atBankLeg;
		}

		// Re-posted even when the leg was already running, which is the case that looked broken.
		//
		// The route is posted once per leg, and getSupplyTargets is derived from the run's types —
		// which have just grown. Starting a run at a bank meant atBankLeg was already true, so the
		// old "only if we are diverting" test skipped the re-post and left the line pointing where
		// it was aimed before the contract existed, while the seed vault highlight, read fresh
		// every tick, had already moved. Same value, two ages of it.
		//
		// Fires once per contract: reviewContract only calls this when runTypes.add reports the
		// type is new, so the tick loop cannot turn it into a stream of route requests.
		if (collecting)
		{
			log.info("Contract needs collecting for; routing to a supply point");
			retarget();
		}
	}

	/**
	 * Arms the trip back to a bank once the hespori is done, so the farming gear comes back.
	 *
	 * <p>The other half of the gear phase. The hespori is fought in the loadout
	 * {@code InventorySetupsHandoff} had the player put on, so when the phase ends — the
	 * kill takes the patch out of HARVESTABLE, or the region is skipped — a mixed run's
	 * remaining stops cannot be serviced: the body is combat kit and the farming supplies
	 * are in the bank, the hespori's own replanting seed among them. Same shape as
	 * {@link #collectForTheContract}: supply owed, leg armed, route re-posted. The freshly
	 * killed patch is an ordinary weedy stop now, so the run comes back to rake and replant
	 * it in the right clothes. The phase itself has ended by now, so the leg runs under the ordinary
	 * farming doctrine — withdraw list, bank filter, highlights — plus the deposit-your-gear
	 * marks the overlay adds for this reason.
	 *
	 * <p>No standing-on-work deferral, deliberately: the ripe patches beside the cave
	 * entrance cannot be serviced in combat gear, so the bank genuinely comes first here.
	 *
	 * <p>Once per run, by the latch. A hespori-only run never arms it — {@code getRemaining}
	 * is empty and the ordinary completion path owns the ending; re-banking the gear is the
	 * player's own wind-down.
	 */
	private void reviewGearSwap()
	{
		// The falling edge of the phase itself, not the stop's completion. The kill leaves
		// the patch weedy or freshly seeded — a stop that cannot complete in cave gear, so a
		// completion key waited forever on a replant the swap-back trip exists to make
		// possible. The phase ends the moment the boss is no longer up (or the region is
		// skipped), and that is exactly when the farming gear is wanted back.
		boolean phaseNow = computeGearPhase();
		gearPhaseNow = phaseNow;
		synchronized (this)
		{
			if (phaseNow)
			{
				// Seen, so the edge below means "was fought this run" — a run whose hespori
				// was never grown must not earn a swap trip it never swapped for.
				gearPhaseSeen = true;
				return;
			}
			if (!gearPhaseSeen || gearSwapDone)
			{
				return;
			}
			gearSwapDone = true;
		}
		if (getRemaining().isEmpty())
		{
			return;
		}

		log.info("The hespori is done; routing to a bank to swap the farming gear back in");
		synchronized (this)
		{
			supplyOwed = true;
			atBankLeg = true;
			bankLegReason = BankLegReason.GEAR_SWAP;
			postedSources = null;
			committedRegion = -1;
		}
		retarget();
	}

	/**
	 * Arms a mid-run bank trip to shed a full pack, on runs that chop.
	 *
	 * <p>Requested from play for exactly the runs the withdraw list cannot see coming: a tree
	 * run's clearing fills the pack with logs — unnoted, unlike harvests, so no leprechaun
	 * makes them small — and every later stop then has no room to harvest into. The guide
	 * pushes "the pack is full on a run that chops" once a tick, because both halves of that
	 * judgment (the free-slot count, and whether these types swing an axe) belong to the
	 * carried-items and loadout side the planner never asks directly.
	 *
	 * <p>Armed on the false→true <b>edge</b> of that push, not the level — so a player who
	 * waves the trip past is honoured until the pack has had space and filled again, rather
	 * than being re-sent to the bank on the very next tick.
	 *
	 * <p>The leg ends when the pack does — the deposit clause in
	 * {@link #suppliesOutstanding()} — and only then. Emptied at a bank or shed on the ground,
	 * dealing with the pack is dealing with the trip; the run's other wants re-arm their own
	 * trips through the ordinary reviews rather than chaining this one open.
	 */
	private void reviewDepositTrip()
	{
		boolean fullNow = packFull;
		synchronized (this)
		{
			boolean edge = fullNow && !lastPackFull;
			lastPackFull = fullNow;
			if (!edge || atBankLeg)
			{
				return;
			}
		}
		if (isGearPhase())
		{
			// The combat pack is the setup's business, full or not.
			return;
		}
		if (getRemaining().isEmpty())
		{
			return;
		}

		log.info("The pack is full mid-run; routing to a bank to shed it");
		synchronized (this)
		{
			if (atBankLeg)
			{
				return;
			}
			supplyOwed = true;
			atBankLeg = true;
			bankLegReason = BankLegReason.DEPOSIT;
			postedSources = null;
			committedRegion = -1;
		}
		retarget();
	}

	public void reviewProgress()
	{
		if (!active)
		{
			return;
		}

		// The two mid-run bank trips, reviewed before the bank-leg early return below because
		// arming one IS entering a leg. Both are edges with their own latches, so a tick loop
		// cannot turn either into a stream of retargets.
		reviewGearSwap();
		reviewDepositTrip();

		if (atBankLeg)
		{
			return;
		}

		// A pending run-complete from retarget(), confirmed only against a later exemption
		// push than the one that produced it. A one-tick over-broad push arms this and is
		// then contradicted by the next tick's honest set; a real completion is re-affirmed
		// and the run ends one tick later than it used to, which nobody can see.
		boolean confirm;
		synchronized (this)
		{
			confirm = runCompletePending && exemptionPushes != runCompleteGeneration;
		}
		if (confirm)
		{
			if (getRemaining().isEmpty())
			{
				// Out of supplies is not out of work. Asked before ending rather than after,
				// because ending is not something a run recovers from.
				if (divertForSupplies())
				{
					return;
				}
				log.debug("Run complete - every stop finished");
				synchronized (this)
				{
					active = false;
					runCompletePending = false;
				}
				router.clear();
				return;
			}
			synchronized (this)
			{
				runCompletePending = false;
			}
		}

		boolean finishedSomething = false;
		synchronized (this)
		{
			// The announcement is an edge detector, so it must re-arm: a stop that completed
			// under an exemption (no seed) and then un-completed (seed withdrawn) could
			// otherwise never announce - and never retarget - when it completes for real.
			announced.removeIf(region ->
			{
				RunStop stop = stops.get(region);
				return stop != null && !isComplete(stop);
			});

			for (RunStop stop : stops.values())
			{
				if (isComplete(stop) && announced.add(stop.getRegion().getRegionId()))
				{
					log.debug("Stop at {} finished with nothing left to do", stop.getName());
					finishedSomething = true;
				}
			}
		}

		// Outside the lock, like every other route post. See start().
		if (finishedSomething)
		{
			// The same deferred-supply pickup onPatchChanged does. This polling path exists
			// precisely for completions whose varbit transition never arrives - a patch never
			// seen this session, a harvest-only stop, an exemption - and those used to walk
			// straight past the supply trip the run still owed.
			pickUpDeferredSupplies();
			retarget();
		}
	}

	/**
	 * Sends a run that has run out of supplies back for another load, instead of ending it.
	 *
	 * <h2>Why the run ended a load early</h2>
	 *
	 * A stop finishes when nothing at it is actionable <b>or</b> the guide has no step for it —
	 * see {@link #isComplete} and {@code nothingToDo}. That second clause is what stops a run
	 * waiting forever on a patch nobody can act on, and it is also exactly what a part-filled
	 * compost bin looks like when the produce has run out: still {@code FILLING}, still wanting
	 * fifteen more pineapples, and {@code CompostBinPlan.addFillStep} deliberately silent because
	 * a bin takes no notes and there are none in the pack. Every stop read complete, and the run
	 * ended with the bins half full and five hundred pineapples in the bank. Reported from play.
	 *
	 * <p>Bins are simply where it shows first. A pack holds one load and the fill is un-noted and
	 * unstackable, so <b>more than one trip is the normal case</b> rather than an edge — the
	 * loadout has said so in as many words since {@code fillReason} learned to.
	 *
	 * <h2>Why here and not in reviewSupplies</h2>
	 *
	 * {@link #reviewSupplies()} re-arms the leg the moment its clause goes true, which is right
	 * for a tool: without a spade there is nothing to do anywhere. Supplies are not like that.
	 * Emptying a bin into the leprechaun frees the very slots that make the fill row outstanding
	 * again, so the same eager test would have yanked the player back to the bank <i>while stood
	 * at a bin</i> with produce in the pack and a bin waiting for it.
	 *
	 * <p>So this is asked at the one moment it cannot interrupt anything: the run has walked
	 * every stop and each has said it wants nothing more. If a bank trip would change that
	 * answer, the trip is the run's next leg rather than its epitaph.
	 *
	 * <p>{@link #suppliesOutstanding()} rather than a bin-shaped test of its own, because the
	 * question is not about bins. It is "would going to a bank unblock anything", and that is a
	 * question the withdraw list already answers for seeds, tools, payments and fills alike —
	 * see {@code RunLoadout.anythingLeftToWithdraw}. Nothing unobtainable can loop it: a row goes
	 * {@code MISSING} rather than {@code WITHDRAW} when the bank has none, so an empty bank ends
	 * the run exactly as it does today.
	 *
	 * <p>A waived leg still outranks this. Skip step during the supply leg means no for the rest
	 * of the run, and an escape hatch that reopens is not one.
	 *
	 * @return true when the run was sent back to a bank instead of ending
	 */
	private boolean divertForSupplies()
	{
		synchronized (this)
		{
			if (bankLegWaived || atBankLeg)
			{
				return false;
			}
		}

		// Outside the lock, like every other store walk here: it reaches the loadout, the tool
		// store and availability. See the ordering note at the top of the file.
		if (!suppliesOutstanding())
		{
			return false;
		}

		synchronized (this)
		{
			// Re-checked under the lock, because the walk above ran without it.
			if (bankLegWaived || atBankLeg)
			{
				return false;
			}
			supplyOwed = true;
			atBankLeg = true;
			runCompletePending = false;
			// The next leg is the bank; the one after it is chosen from there.
			committedRegion = -1;
		}

		log.info("Out of supplies rather than out of work - going back for another load");
		retarget();
		return true;
	}

	/**
	 * Sends the run back for a tool it started with and no longer has.
	 *
	 * <h2>The gap this closes</h2>
	 *
	 * Whether a run needs a bank was decided once, at {@link #start}, and re-asked only for a
	 * contract taken mid-run. So a tool that leaves your pack <b>during</b> the run — deposited
	 * by accident is the reported way, but dropping one does it too — was never noticed by
	 * anything. The guide's own tool step could not cover it either: it offers the leprechaun's
	 * copy and is deliberately silent when he has none, on the grounds that a rake in your bank
	 * is the loadout's business before setting off. Both halves were true, and between them a
	 * spadeless run walked from patch to patch with nothing to say and nothing it could do.
	 *
	 * <p>So the question start asks is asked again, once a tick, against the only clause that
	 * can newly become true mid-run: {@link ToolNeeds#anyOnlyInBank}, which is exactly "the run
	 * needs this, you are not carrying it, and the leprechaun has not got one either". When the
	 * <b>leprechaun</b> has one this stays silent by construction — his store is not the bank,
	 * the clause is false, and the patch's own tool step already says "get it from him", which
	 * is the cheaper trip and the one to prefer.
	 *
	 * <p>Never re-arms a leg the player waived: Skip step during the supply leg means no for the
	 * rest of the run, and that has to outrank this or the escape hatch stops being one.
	 */
	public void reviewSupplies()
	{
		synchronized (this)
		{
			if (!active || atBankLeg || bankLegWaived)
			{
				return;
			}
		}

		// Outside the lock, like every store walk here: RunPlanner -> Availability -> patches.
		//
		// Two sources, because ToolNeeds does not know about all of them. It covers the farming
		// tools; the AXE is a RunLoadout row and always has been, so asking only that class left
		// the loudest case invisible - a contract taken from Jane for a patch still holding last
		// run's tree, where the guide asks for a chop and the axe is in the bank. Reported from
		// play. toolOutstanding is the withdraw list's own answer, pushed once a tick.
		//
		// Being a poll rather than a one-shot is what makes this reliable where
		// collectForTheContract is not: the pushed flag is a tick stale, and a tick later this
		// asks again.
		// Never during the gear phase. The withdraw list still names every farming tool the
		// combat loadout is not carrying - the axe, loudest of all - and this poll armed a
		// bank leg for it every tick while leaveBank, whose gear-phase answer is "the leg is
		// done", closed it again every tick: the run thrashed "Supplies collected" once a
		// tick and never left the bank. Reported from play. Those tools are the swap-back
		// leg's whole business; see reviewGearSwap.
		if (isGearPhase())
		{
			return;
		}

		if (!tools.anyOnlyInBank(runTypesSnapshot()) && !toolOutstanding)
		{
			return;
		}

		synchronized (this)
		{
			// Re-checked under the lock, because the store walk above ran without it.
			if (!active || atBankLeg || bankLegWaived)
			{
				return;
			}
			supplyOwed = true;
			atBankLeg = true;
			committedRegion = -1;
		}

		log.info("A tool the run needs is no longer on you or with the leprechaun - "
			+ "diverting to a bank: {}", describeTools());
		retarget();
	}

	/** Collects a supply trip deferred because the run started standing on work. */
	/**
	 * Sends the run to a supply point it is standing next to, once the stop here is done.
	 *
	 * <h2>The reported dead end, twice in one session</h2>
	 *
	 * <i>"never got routed back to the bank for watermelons to the big bin, I did it anyways and
	 * then it started to realize it"</i> — and twenty minutes later — <i>"just filled it and now
	 * its sending me to ardougne instead of to get the rest of my melons. its like its
	 * prioritizing the next patch over the bank step."</i>
	 *
	 * <p>Both are the same hole. Supplies that become outstanding <b>mid-run</b> — the emptied
	 * big bin's fill is the case that exists today — had exactly one collector:
	 * {@link #divertForSupplies()}, which by design fires only when the run would otherwise
	 * end. So a run with stops left walked away from the guild's own chest, fill in the bank
	 * fourteen tiles from the bin, and meant to come back for it at the end of the run by
	 * teleport. The session log shows it plainly: {@code Stop complete at Farming Guild} at
	 * 21:36 with the melons sitting in the chest, and {@code FILL_BIN} appearing only at 21:55,
	 * after the player had collected them unprompted.
	 *
	 * <h2>The gates, each of which is load-bearing</h2>
	 *
	 * <b>The stop here must be complete.</b> This is {@code divertForSupplies}' anti-yank rule
	 * one scope down: the fill row goes outstanding the moment the emptied bin frees the slots,
	 * which is mid-stop, stood at the bin, with harvests still to do — re-entering the bank leg
	 * there would blank the step list under the player's feet. Complete-here is the moment
	 * nothing is interrupted, and it is also when the run would otherwise commit to the next
	 * stop.
	 *
	 * <p><b>The supply point must be in this region.</b> The same line {@code start()} draws
	 * with {@code canBankHere}: "standing on work beats going shopping" is about travel, and a
	 * chest in the region you already occupy costs none. Anywhere else, the end-of-run divert
	 * keeps the job — a mid-run teleport to a bank and back is a cost the owner has not asked
	 * for.
	 *
	 * <p><b>A waived leg still wins.</b> Skip during a supply leg means no for the rest of the
	 * run, here as everywhere.
	 *
	 * <p>Per tick rather than on the completion edge, deliberately: at the moment the stop
	 * completes the pack is often still full of the harvest, the fill row asks for nothing
	 * (see {@code RunLoadout.fillBudget}), and only after the noting and depositing does it go
	 * WITHDRAW — by which time the edge has passed. The gates above make the steady-state
	 * cheap: the flag test is a pushed boolean, and the walk runs only while standing at a
	 * finished stop.
	 */
	public void reviewNearbySupplies()
	{
		synchronized (this)
		{
			if (!active || atBankLeg || bankLegWaived)
			{
				return;
			}
		}

		// Store walks, outside the lock like every other one — see the ordering note at the
		// top of the file.
		if (!suppliesOutstanding() || !supplyPointIsHere())
		{
			return;
		}

		// Read before the monitor, like supplyPointIsHere reads it — the location store has
		// its own lock and the planner's must not be held while taking it.
		int region = playerLocation.getRegionId();

		boolean collect;
		synchronized (this)
		{
			RunStop here = stops.get(region);
			collect = here != null && isComplete(here)
				&& !atBankLeg && !bankLegWaived;
			if (collect)
			{
				supplyOwed = true;
				atBankLeg = true;
				// The next leg is the supply point; the one after it is chosen from there.
				committedRegion = -1;
			}
		}

		if (collect)
		{
			log.info("Supplies outstanding and a supply point is in this region - "
				+ "collecting before moving on");
			retarget();
		}
	}

	private void pickUpDeferredSupplies()
	{
		synchronized (this)
		{
			// "No" means no for the rest of the run - a waived leg must not come back the
			// moment the next stop completes.
			if (bankLegWaived)
			{
				return;
			}
		}
		if (owesSupplies() && needsSupplyTrip())
		{
			synchronized (this)
			{
				atBankLeg = true;
			}
			log.debug("Picking up the supply trip that was deferred at the start");
		}
	}

	/**
	 * Told that a patch changed, so the run can see whether that finished a stop.
	 *
	 * <h2>"A patch changed", not "a patch was serviced"</h2>
	 *
	 * This used to be {@code markServiced}, and both the name and the contract were wrong. The
	 * caller decided what counted — {@code PatchInteractionTracker} called it only when a varbit
	 * changed into a growing crop — so a stop finished only if the player planted every patch in
	 * it. Anything else stranded the run: a harvest-only stop plants nothing at all, a patch with
	 * no seed has nothing to click, a dead crop with no axe cannot be cleared.
	 *
	 * <p>Now the caller reports the <i>event</i> and this decides the <i>meaning</i>, by asking
	 * {@link #isComplete(RunStop)} whether anything at the stop is still actionable. The tracker no
	 * longer needs an opinion about what a run considers finished, which is not a question the
	 * capture layer was ever in a position to answer.
	 *
	 * <h2>Announced once</h2>
	 *
	 * Completion is derived, so it stays true on every later change and would retarget repeatedly.
	 * {@link #announced} is what makes the transition an edge rather than a level. It cannot use
	 * the serviced hint for this: a patch commonly changes twice at one stop — harvest, then plant
	 * — and the stop becomes complete on the second, so a per-patch guard would swallow exactly the
	 * change that finished it.
	 *
	 * @return true if this change completed a stop
	 */
	public boolean onPatchChanged(FarmPatch patch)
	{
		boolean completedStop;
		RunStop stop;
		synchronized (this)
		{
			if (!active)
			{
				return false;
			}

			stop = stops.get(patch.getRegion().getRegionId());
			if (stop == null || !stop.contains(patch))
			{
				return false;
			}

			// A hint for ordering, not the completion test. See RunStop.isFullyServiced.
			stop.markServiced(patch);
			completedStop = isComplete(stop)
				&& announced.add(patch.getRegion().getRegionId());
		}

		// Only when the whole stop is done. Retargeting per patch was wrong twice over: it
		// asked Shortest Path for a fresh route four or five times while the player stood in
		// one place — Falador's allotments, flower, herb and bin are all one stop — and each
		// request is a cross-thread post, which is where the visible delay came from.
		//
		// The old reasoning was that the cheapest remaining target can change the moment
		// anything is crossed off. True, but not while you are still standing in the region
		// you are being routed to: the answer cannot improve until you leave.
		if (completedStop)
		{
			log.debug("Stop complete at {}", patch.getRegion().getName());

			// A supply trip deferred because the run started on top of some work is collected
			// now, rather than being quietly dropped.
			pickUpDeferredSupplies();
			retarget();
			return true;
		}

		// The stop is not finished, but the part of it you are standing in is.
		//
		// The rule above rests on "the answer cannot improve until you leave the region you are
		// being routed to", which holds for every stop the game files and not for the one
		// SharedStops builds: the Champions' Guild bush and the Lumbridge hops are one stop with
		// fifty tiles between them. Picking the bush left the run with no route and no reason to
		// ask for one until the player had walked most of the way to the hops by themselves and
		// crossed a region boundary. Reported from play.
		//
		// hasArrivedAt is the test because it is now exactly this question - is there still work
		// within ARRIVED_TILES of the player - so Falador keeps the optimisation this branch was
		// written for: after the allotment, the flower and the herb are still where you stand
		// and nothing is re-asked.
		if (!hasArrivedAt(stop, playerLocation.getRegionId()))
		{
			log.debug("Work left at {} but none of it here; re-asking for a route",
				stop.getName());
			retarget();
		}
		return false;
	}

	/**
	 * How close to a patch counts as having arrived, in tiles.
	 *
	 * <p>Ten: close enough that the patch is on screen and a drawn line to it would be telling
	 * you what you can already see, far enough that it does not blink out while you walk the last
	 * few steps. The owner's number.
	 */
	private static final int ARRIVED_TILES = 10;

	/**
	 * Whether the player is at this stop, as opposed to merely inside its map region.
	 *
	 * <h2>A region is 64x64 tiles, and that was the whole of the old test</h2>
	 *
	 * Arriving anywhere in a remaining stop's region cleared the route. That is right where the
	 * arrival point <b>is</b> the patches — Falador drops you among them, and a route line there
	 * competes with the per-patch guidance rather than adding to it, which is the reason the
	 * clear exists at all.
	 *
	 * <p>It is wrong wherever a region's arrival point is not its patches. Reported from play:
	 * the Brimhaven spirit tree and the palm tree patch are both region 11058, at
	 * {@code (2802,3203)} and {@code (2765,3213)} — <b>thirty-eight tiles apart</b>. Taking the
	 * tree put the player "at" the stop, the route was wiped on the arrival tick, and the palm
	 * tree was most of a screen away with nothing drawn. The line flashing first is the reply to
	 * the pre-teleport request landing a tick late and being cleared behind it.
	 *
	 * <p>So arrival is measured against the patches themselves now. The same region-as-position
	 * mistake as {@code GuideTracker.standingAt}'s edge tolerance and the highlight's varbit
	 * match, and answerable for the same reason: patch positions are real, from
	 * {@link PatchLocationStore}, and every region carries at least a wiki pin.
	 *
	 * <p><b>The seabed keeps its region test</b>, and has to. A coral nursery's patch is filed
	 * under the Great Conch while it physically stands at 13194, so its stored position is the
	 * ship rather than the seabed and a distance test would never fire — which is the exact bug
	 * {@code claimsRegion} was added to fix. {@code UnderwaterApproach.isAtPatch} is that half,
	 * kept whole.
	 *
	 * <p>Costs no extra routing. {@code GuideTracker.retargetIfMoved} only re-asks on a region
	 * change, so nothing new is posted while the player walks around a stop; the route drawn
	 * before arrival simply survives to the patch instead of being wiped at the boundary.
	 *
	 * <h2>And measured against the patches that still want doing</h2>
	 *
	 * Reported from play, between the Champions' Guild bush and the Lumbridge hops: no route
	 * for the walk. {@link com.dooglemaps.data.SharedStops} deliberately folds those two regions
	 * into one stop, because one teleport serves both — and every "a stop is one place" shortcut
	 * in this class then reads the fifty tiles between them as no distance at all. Arriving at
	 * the bush counted as arriving at the whole stop, so the route was wiped while half the work
	 * was still most of Lumbridge away.
	 *
	 * <p>A finished patch is not somewhere you have arrived; it is somewhere you have left. So
	 * the distance is measured only against patches that {@link #stillWanted}. On an ordinary
	 * single-region stop this changes nothing — Falador's patches are all within the ten tiles
	 * of each other, so whichever one is left is still "here" — and on the merged stop it is
	 * the whole of the fix.
	 */
	private boolean hasArrivedAt(RunStop stop, int playerRegion)
	{
		WorldPoint at = playerLocation.get();
		Set<String> blocked = nothingToDo;
		Set<PatchImplementation> types = runTypesSnapshot();

		boolean anythingLeftHere = false;
		for (FarmPatch patch : stop.getPatches())
		{
			// The seabed keeps its region test whole, and ungated: a coral nursery's stored
			// position is the ship, so this is the only test that can recognise standing among
			// them at all. Narrowing it would be narrowing the one thing that works.
			if (com.dooglemaps.data.UnderwaterApproach.isAtPatch(patch, playerRegion))
			{
				return true;
			}

			if (!stillWanted(stop, patch, blocked, types))
			{
				continue;
			}
			anythingLeftHere = true;

			if (at == null)
			{
				continue;
			}
			WorldPoint where = locations.getLocation(patch);
			if (where != null && where.getPlane() == at.getPlane()
				&& at.distanceTo(where) <= ARRIVED_TILES)
			{
				return true;
			}
		}

		// Nothing here wants doing at all — which the stop being in `remaining` says should be
		// impossible, so this is a race between the snapshot and the stop list rather than a
		// state. Answer it the old way, over every patch: the failure mode to avoid is drawing
		// a route to a stop the player is standing in, and the old test could not do that.
		return anythingLeftHere ? false : standingAmong(stop, at);
	}

	/**
	 * The first patch at this stop that still wants doing and that we know the way to.
	 *
	 * <p>{@code isKnown} for the same reason {@link #routeTargetsFor}'s dry half asks it: a patch
	 * nobody has stood beside has a <i>region centre</i> rather than a location, and routing to a
	 * guess when a known patch was available is how the Great Conch's calquat sent players to a
	 * table on the deck. Null when there is no such patch, which the caller falls back on.
	 */
	@Nullable
	private FarmPatch firstPatchStillWanted(RunStop stop)
	{
		Set<String> blocked = nothingToDo;
		Set<PatchImplementation> types = runTypesSnapshot();
		for (FarmPatch patch : stop.getPatches())
		{
			if (locations.isKnown(patch) && stillWanted(stop, patch, blocked, types))
			{
				return patch;
			}
		}
		return null;
	}

	/** The pre-{@link #stillWanted} arrival test, kept for the race noted in its caller. */
	private boolean standingAmong(RunStop stop, @Nullable WorldPoint at)
	{
		if (at == null)
		{
			return false;
		}
		for (FarmPatch patch : stop.getPatches())
		{
			WorldPoint where = locations.getLocation(patch);
			if (where != null && where.getPlane() == at.getPlane()
				&& at.distanceTo(where) <= ARRIVED_TILES)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The stop the run has settled on travelling to, by region id, or -1 while undecided.
	 *
	 * <h2>Deciding once is the point</h2>
	 *
	 * Every outstanding stop is handed to the router and it picks the cheapest to reach: that is
	 * the whole ordering strategy, and re-posting the shrinking set after each stop gives greedy
	 * nearest-first over real travel cost without this class knowing anything about the map.
	 *
	 * <p>"Cheapest to reach" is measured <b>from where the player is</b>, though, and
	 * {@code GuideTracker.retargetIfMoved} re-asks on every region change. So the answer moved
	 * while the player was travelling to it: cross a boundary on the way to Weiss and Ardougne
	 * might now be nearer, so the drawn line, the destination name, the via-hops and the
	 * highlighted teleport all swung round to Ardougne — and a few regions later, back again.
	 * With several run types ticked there is always another stop close enough to win a leg.
	 * Reported from play as the route and the infobox changing direction constantly.
	 *
	 * <p>The instance case of exactly this was already patched once, in {@link #retarget}: a
	 * house teleport flipping a Weiss run to the Ardougne bushes. That was the same bug wearing
	 * a hat — the fix there was to stop posting garbage start points, which cured the loudest
	 * instance of it and left the general one alone.
	 *
	 * <p>So the pick is made once and then kept. While this holds a region the router is sent
	 * that stop and nothing else, which is also what makes the drawn line agree with the panel
	 * by construction rather than by both happening to read the same reply. It is dropped when
	 * the stop leaves {@link #getRemaining} — finished, or waved past — and the next leg is
	 * chosen greedily all over again from wherever the player then is.
	 *
	 * <p>Guarded by this class's monitor like every other piece of run state.
	 */
	private int committedRegion = -1;

	/**
	 * Told which stop the drawn route ends at, so the run can stop re-deciding.
	 *
	 * <p>Pushed by the guide rather than read from the router here, and that is deliberate:
	 * {@code GuideTracker.destinationStop} already turns the reply's landing points into a stop,
	 * and it is careful about it — the payload may be the one target Shortest Path settled on or
	 * an echo of every target it was handed, so it names a stop <b>only when exactly one
	 * matches</b>. Duplicating that judgement here is how the panel and the route would come to
	 * disagree about where the run is going. Same arrangement as {@link #setNothingToDo} and
	 * {@link #setWithdrawOutstanding}.
	 *
	 * <p>Ignored during the supply leg, whose destination is a bank: the reply's landings can
	 * brush a patch stop's region on the way, and latching onto that would have the run commit
	 * to a stop it has not chosen yet and cannot see.
	 *
	 * @param regionId the destination stop's region, or -1 when the route does not name one
	 */
	public synchronized void commitDestination(int regionId)
	{
		if (regionId < 0 || atBankLeg)
		{
			// Not a decision either way. An unnamed route is the ordinary state for a second or
			// two after every request, and forgetting the commitment on it would put the run
			// straight back to re-deciding on every reply.
			return;
		}
		if (committedRegion != regionId)
		{
			log.debug("Run committed to {} for this leg", regionId);
			committedRegion = regionId;
		}
	}

	/**
	 * The stop the run has committed to travelling to, or null while it has not chosen one.
	 *
	 * <h2>The run's own answer, which the router's reply is only evidence for</h2>
	 *
	 * {@code GuideTracker.destinationStop} works the destination out of Shortest Path's reply —
	 * the landing points it sends back with the path — and that reply is not always usable. It
	 * can arrive with hops and no landing worth matching, it is discarded outright while the
	 * player is instanced (see {@code ShortestPathIntegration.onPluginMessage}), and it is wiped
	 * the moment a fresh route is asked for. Every one of those is a second or two where the leg
	 * has no name.
	 *
	 * <p>That used to be harmless, because nothing much depended on the name. It is not harmless
	 * now: the portal nexus row and the jewellery box line are matched <b>by destination</b>, so
	 * a nameless leg silently stops highlighting the thing you are meant to click. Reported from
	 * play as the Catherby teleport dropping out of the infobox and the nexus list on the way
	 * into the house — and the log shows the leg was already <i>"(unnamed leg)"</i> five seconds
	 * before the house was entered, which is what rules out the house itself being the cause.
	 *
	 * <p>Once the run has committed, it <b>knows</b> where it is going and does not have to infer
	 * it. The commitment is only ever set from an unambiguous reply in the first place — see
	 * {@link #commitDestination} — so preferring it adds no guessing, and it is dropped the
	 * moment the stop leaves {@link #getRemaining()}, at which point the reply decides the next
	 * leg exactly as before.
	 */
	@Nullable
	public synchronized RunStop committedStop()
	{
		if (committedRegion < 0 || atBankLeg)
		{
			return null;
		}
		for (RunStop stop : getRemaining())
		{
			if (stop.getRegion().getRegionId() == committedRegion)
			{
				return stop;
			}
		}
		return null;
	}

	/** Forgets the committed leg, so the next retarget picks greedily again. */
	private synchronized void releaseCommitment()
	{
		committedRegion = -1;
	}

	/**
	 * Entrana's stop, and the stop that must come first while both remain.
	 *
	 * <h2>The monks decide this ordering, not travel cost</h2>
	 *
	 * The boat to Entrana confiscates combat gear, and the Ardougne cloak — the teleport
	 * that serves the monastery bush stop at {@code 10290} — counts. So the two stops in
	 * either order are not symmetric: monastery first spends the cloak's teleport and banks
	 * it at the one banking moment the Entrana boat forces anyway; Entrana first means
	 * arriving back on the mainland with the cloak still banked and a <b>second</b> bank
	 * trip just to fetch the teleport. Requested from play: "we should always do the entrana
	 * run after the ardougne farm run... the cape is a combat item that you can't take to
	 * entrana, which means re-banking after."
	 *
	 * <p>A hand-curated pair, like {@code SharedStops}' — the fact lives with the monks and
	 * the cloak, not in any table this plugin could derive it from. Applied only to the
	 * fresh choice of leg: a committed Entrana leg, the standing-on-work rule and the skip
	 * all stand above it, and with the monastery done or skipped Entrana is offered exactly
	 * as before.
	 */
	private static final int ENTRANA_REGION = 11060;
	private static final int ARDOUGNE_MONASTERY_REGION = 10290;

	/** Withholds Entrana from a fresh choice while the monastery stop is still to make. */
	private static List<RunStop> entranaAfterTheCloak(List<RunStop> remaining)
	{
		boolean monasteryRemains = false;
		boolean entranaRemains = false;
		for (RunStop stop : remaining)
		{
			monasteryRemains |= stop.getRegion().getRegionId() == ARDOUGNE_MONASTERY_REGION;
			entranaRemains |= stop.getRegion().getRegionId() == ENTRANA_REGION;
		}
		if (!monasteryRemains || !entranaRemains)
		{
			return remaining;
		}

		List<RunStop> ordered = new ArrayList<>();
		for (RunStop stop : remaining)
		{
			if (stop.getRegion().getRegionId() != ENTRANA_REGION)
			{
				ordered.add(stop);
			}
		}
		return ordered;
	}

	/** The hespori's stop among these, or null — the gear phase's one destination. */
	@Nullable
	private static RunStop hesporiStopIn(List<RunStop> remaining)
	{
		for (RunStop stop : remaining)
		{
			if (stop.getRegion().getRegionId() == HESPORI_REGION)
			{
				return stop;
			}
		}
		return null;
	}

	/**
	 * The stops to hand the router: the committed one alone, or all of them while undecided.
	 *
	 * <p>The commitment is dropped here rather than watched for elsewhere, because this is the
	 * one place that already has the live remaining list in its hand — a stop that has finished
	 * or been waved past is simply not in it, and both are the moment to choose again.
	 */
	private List<RunStop> committedLeg(List<RunStop> remaining)
	{
		// The gear phase's one stop comes first, whatever is cheaper to reach: the player is
		// wearing the fight, and a farm patch serviced in combat gear with no farming supplies
		// is a patch stood next to. Overrides any committed leg for the same reason; the phase
		// ending releases it and the run chooses greedily again.
		if (isGearPhase())
		{
			RunStop cave = hesporiStopIn(remaining);
			if (cave != null)
			{
				return Collections.singletonList(cave);
			}
		}

		int committed;
		synchronized (this)
		{
			committed = committedRegion;
		}
		if (committed < 0)
		{
			return slowestFirst(entranaAfterTheCloak(remaining));
		}

		for (RunStop stop : remaining)
		{
			if (stop.getRegion().getRegionId() == committed)
			{
				return Collections.singletonList(stop);
			}
		}

		log.debug("The committed stop is done or skipped; choosing the next leg afresh");
		releaseCommitment();
		return slowestFirst(entranaAfterTheCloak(remaining));
	}

	/**
	 * Narrows a fresh choice of leg to the stops planting the slowest crop.
	 *
	 * <h2>Growth happens in the background, travel does not</h2>
	 *
	 * The router picks the cheapest reachable member of whatever it is handed, so handing it
	 * everything made the visiting order pure travel greed. Requested from play: the crop
	 * planted first starts growing first, so on a run that might be cut short, planting the
	 * longest clocks first banks the most experience per stop actually made — a magic tree's
	 * eight hours starts counting while the herbs are still being walked between.
	 *
	 * <p>Travel still decides <b>ties</b>: every stop planting the same slowest crop is handed
	 * over together and the router keeps its greedy pick among them, so a herb circuit is
	 * still walked nearest-first. And this is only the fresh choice — the committed leg, the
	 * standing-on-work rule, the gear phase's hespori-first and the shared-plot holds all
	 * stand above it, untouched.
	 *
	 * <p>A stop that plants nothing — harvest-only lines, cleared-but-unseeded patches, the
	 * bins — counts zero, so a run of only those keeps the old ordering entirely.
	 */
	private List<RunStop> slowestFirst(List<RunStop> remaining)
	{
		if (!config.slowestCropsFirst() || remaining.size() < 2)
		{
			return remaining;
		}

		int longest = 0;
		Map<RunStop, Integer> minutes = new LinkedHashMap<>();
		for (RunStop stop : remaining)
		{
			int stopMinutes = growthMinutes(stop);
			minutes.put(stop, stopMinutes);
			longest = Math.max(longest, stopMinutes);
		}
		if (longest == 0)
		{
			return remaining;
		}

		List<RunStop> slowest = new ArrayList<>();
		int cutoff = longest;
		minutes.forEach((stop, stopMinutes) ->
		{
			if (stopMinutes == cutoff)
			{
				slowest.add(stop);
			}
		});
		return slowest;
	}

	/**
	 * Minutes of the slowest crop this stop would plant, or zero when it plants nothing.
	 *
	 * <p>From the seed <i>selection</i> rather than the patch, because the crop that decides
	 * the priority is the one about to go in, not the one coming out. Harvest-only groups
	 * plant nothing by definition, and the bins are skipped before {@code groupFor} is even
	 * asked — see the note on that method about what it honestly reports for the big bin.
	 */
	private int growthMinutes(RunStop stop)
	{
		int longest = 0;
		for (FarmPatch patch : stop.getPatches())
		{
			if (patch.getImplementation() == PatchImplementation.COMPOST
				|| patch.getImplementation() == PatchImplementation.BIG_COMPOST)
			{
				continue;
			}
			PlantingGroup group = groups.groupFor(patch);
			if (group != null && runOptions.isHarvestOnly(group))
			{
				continue;
			}
			// The group's selection where there is one - it carries the protected split and
			// the contract's seed - and the type's otherwise, the same null-tolerance the
			// rest of this class extends to groupFor.
			Set<Seed> seeds = group != null
				? selection.getSelectedFor(group)
				: selection.getSelectedFor(patch.getImplementation());
			for (Seed seed : seeds)
			{
				longest = Math.max(longest, seed.getProduce().getMinutesToGrow());
			}
		}
		return longest;
	}

	/**
	 * Hands the router the leg the run is on, or every outstanding stop while it has yet to
	 * choose one.
	 *
	 * <p>See {@link #committedRegion} for why choosing once and keeping it is the whole point.
	 */
	public void retarget()
	{
		retarget(null);
	}

	/**
	 * As {@link #retarget()}, but telling the router where the journey really begins.
	 *
	 * <p>Exists for one situation: standing in the player-owned house, whose tiles are an
	 * instance the router cannot place, about to leave through the exit portal. The tracker
	 * hands the exterior portal's tile here so the drawn path starts at the front door
	 * rather than wherever the router guessed — see {@code GuideTracker.routeFromTheFrontDoor}.
	 */
	public void retarget(@Nullable WorldPoint start)
	{
		// No start-less requests from inside an instance. A request with no explicit start
		// defaults, upstream, to the RAW player position — which in an instance is the
		// virtual-area coordinates, garbage to the router — so the route recomputes from
		// nowhere and lands on an arbitrary stop. Reported from play: teleporting to the POH
		// flipped a Weiss run's destination to the Ardougne bushes the moment the house
		// loaded. While instanced, only a request that SAYS where it starts is posted — the
		// front-door reroute is exactly that — and leaving the instance retargets normally.
		//
		// The player's own house is the one instance with a knowable start: its front door.
		// Refusing outright there left a replanned run DEAD until the player walked out —
		// every retarget begins by wiping the route state, so a plan or replan made while
		// standing in the POH showed "Travel to the next patches" with no destination, no
		// via-lines and nothing outlined, and nothing ever asked the router again. Reported
		// from play, from the house at a run replanned inside it (region 56001 in the log).
		// The front-door start is the same compromise routeFromTheFrontDoor already makes;
		// any other instance still stays silent.
		if (start == null && isInstanced(playerLocation.getRegionId()))
		{
			// ...but never over a live plan that goes THROUGH this house. The first version
			// re-routed from the door on every in-house retarget, and entering the POH on a
			// jewellery-box leg threw away the box hop mid-teleport - the via-line vanished
			// and the row highlight, which reads the hop for its row name, fell back to the
			// stop's name and lit "R: Al Kharid" over "1: Emir's Arena" again. Reported from
			// play, twice. The router's own origins say whether the plan uses the house; a
			// plan that does keeps its route (the next click is furniture in this room, and
			// GuideTracker.routeFromTheFrontDoor owns any door-vs-furniture judgment), and
			// the door start serves only plans that leave on foot or the dead no-plan state
			// this fallback exists for.
			if (router.isRouteDepartsPoh())
			{
				return;
			}
			start = frontDoorWhenInside.get();
			if (start == null)
			{
				return;
			}
			log.debug("Retargeting from inside the house; routing from the front door at {}",
				start);
		}

		if (isAtBankLeg())
		{
			// Recorded as it is posted, so the leg can tell later whether the answer has moved on
			// without it — see followSupplyProgress.
			Set<SeedSource> sources = getSupplySources();
			synchronized (this)
			{
				postedSources = sources;
			}

			// Bank detours allowed here, and only here: the point of this leg is to collect.
			router.setTargets(supplyTargetsFor(sources), true, start);
			return;
		}

		List<RunStop> remaining = getRemaining();
		if (remaining.isEmpty())
		{
			// Not the end of the run yet - the first sighting only arms it. reviewProgress
			// confirms against a fresh exemption push; see exemptionPushes.
			synchronized (this)
			{
				if (!runCompletePending)
				{
					runCompletePending = true;
					runCompleteGeneration = exemptionPushes;
				}
			}
			router.clear();
			return;
		}
		synchronized (this)
		{
			runCompletePending = false;
		}

		// The gear phase's one destination, for the arrived test below as well as the
		// targets: the cave entrance shares the guild's region with a dozen patches, so a
		// player gearing up at the guild bank was "standing on work" by the ordinary rule,
		// the route was cleared, and the guide's step engine offered the big bin to someone
		// in combat kit. Reported from play: "when we gear up for hespori it should be our
		// only target until it's done". Work you cannot do in the gear you are wearing is
		// not work you are standing on.
		if (isGearPhase())
		{
			RunStop cave = hesporiStopIn(remaining);
			if (cave != null)
			{
				remaining = Collections.singletonList(cave);
			}
		}

		// Nothing is routed while there is work where you stand. Finishing a location before
		// travelling is the whole shape of a farm run — the wiki's guide is built on it — so a
		// route to the next stop is not just noise, it is an instruction competing with the one
		// guided mode is giving. It also stopped Shortest Path drawing a teleport-and-bank
		// route across the screen while the player was stood on ripe crops.
		//
		// claimsRegion, not the bare region id: the coral nurseries sit at 13194 while their
		// stop is filed under the Great Conch at 12581, so standing among them read as being
		// nowhere near them and the run drew a route back UP to the steps just walked down.
		// Reported from play. See RunStop.claimsRegion.
		int here = playerLocation.getRegionId();
		for (RunStop stop : remaining)
		{
			if (hasArrivedAt(stop, here))
			{
				router.clear();
				return;
			}
		}

		// Each stop contributes the ring of tiles around its patch, not the patch's own tile.
		// Shortest Path only finishes a search by stepping ONTO a target, and a bush or tree
		// patch tile is blocked — so the old single-tile target made every route end in its
		// no-progress cutoff instead of an arrival, and the line spent most of a journey being
		// recalculated rather than drawn. See PatchLocationStore.getRouteTargets.
		//
		// Only the leg being travelled, once the run has picked one. See committedRegion.
		List<WorldPoint> targets = new ArrayList<>();
		for (RunStop stop : committedLeg(remaining))
		{
			targets.addAll(routeTargetsFor(stop));
		}
		router.setTargets(targets, false, start);
	}

	/**
	 * Where to send the router for one stop, diverting to a landward approach when it needs one.
	 *
	 * <h2>The divert is the normal case, not the exception</h2>
	 *
	 * The coral nurseries and the seaweed patches are on the seabed and nothing can path to them,
	 * so the run asks for the steps on the dock — or the rowboat — instead; see
	 * {@code UnderwaterApproach}.
	 *
	 * <p>This used to ask {@code UnderwaterApproach.stillWanted}, which diverted <b>only while
	 * the player was standing in the approach's own region</b>. That is the one place the divert
	 * buys nothing, and everywhere else — which is to say, on the entire journey there — the
	 * router got the patch instead. A seabed patch has no learned location and never will
	 * (its objects carry no patch varbit, which is why {@code UnderwaterApproach.objectsFor}
	 * exists at all), so {@code PatchLocationStore} falls all the way through to the middle of
	 * the region: for the Great Conch, tile (3168, 2400), which in the world is a table on the
	 * ship's deck. Reported from play, in those words. The seaweed patches were worse still —
	 * region 15008's centre is out at y 10272, unreachable, so the route simply failed.
	 *
	 * <p>What {@code stillWanted} was defending against is real: keep the dock as the target
	 * after the dive and the router does exactly as it is told, plotting a course back UP,
	 * fairy rings and all. Also reported from play. But "the player has gone under" and "the
	 * player is not standing on the dock" are not the same statement, and reading the second as
	 * the first is what cost the journey its route.
	 *
	 * <p>The honest test for having gone under is standing in the stop's own region, which is
	 * how every other part of this class recognises arrival. It is also belt and braces:
	 * {@link #retarget} clears the route outright when the player is in a remaining stop's
	 * region, so this is a second lock on the same door rather than the only one.
	 *
	 * <h2>A stop can be half wet</h2>
	 *
	 * The Great Conch carries two coral patches <i>and</i> a calquat, so its stop is genuinely
	 * two places: a seabed reached from one region and a tree standing on the deck. Both go in.
	 * The router already picks the cheapest of everything it is handed, and once one of them is
	 * done the set shrinks to the other — which is the same greedy shape the run uses between
	 * stops, applied within one.
	 *
	 * <p>Note that the dry half is keyed off the first patch that <b>has</b> a location rather
	 * than {@code RunStop.getRouteTargets}, which always asks patch zero: on the Great Conch
	 * patch zero is a coral patch, so a calquat-only visit was being routed to the seabed's
	 * region centre — the same table — with no underwater patch involved at all.
	 *
	 * <h2>And only a dry half we actually know the way to</h2>
	 *
	 * {@code isKnown}, because a patch nobody has stood beside and that no pin was written down
	 * for has no location — it has a <i>region centre</i>, which
	 * {@link PatchLocationStore#getLocation} hands out so that an unvisited patch is still
	 * roughly routable. Roughly is fine when it is the only target.
	 * Here it is not: it sits in a set beside a landward approach read off the client to the
	 * tile, and the router picks the cheapest of what it is given without knowing that one of
	 * them is a guess. The Great Conch's calquat has never been seen, its centre is (3168, 2400)
	 * — a table on the deck — and the deck is nearer than the steps, so the guess won every
	 * time. Reported from play, twice, in those words.
	 *
	 * <p>Dropping it loses nothing. The stop is still routed to, by the half whose location is
	 * known, and arriving there is what teaches the plugin where the other half is.
	 */
	private java.util.List<WorldPoint> routeTargetsFor(RunStop stop)
	{
		// Insertion-ordered and de-duplicated: both coral patches share one set of steps, and
		// handing the router the same tile twice is noise in a message that crosses plugins.
		java.util.Set<WorldPoint> approaches = new LinkedHashSet<>();
		FarmPatch dryLand = null;
		for (FarmPatch patch : stop.getPatches())
		{
			com.dooglemaps.data.UnderwaterApproach.Approach approach =
				com.dooglemaps.data.UnderwaterApproach.forPatch(patch);
			if (approach != null)
			{
				approaches.addAll(approach.getRouteTargets());
			}
			else if (dryLand == null && locations.isKnown(patch))
			{
				dryLand = patch;
			}
		}

		// Every ordinary stop: the patch here that still wants doing, not patch zero.
		//
		// RunStop.getRouteTargets answers with patch zero on the reasoning that "they are all in
		// one region, so any of them lands the player in the right place, and walking between
		// them is trivial once there". True of every stop the game files, and false of the one
		// SharedStops builds: the Champions' Guild bush and the Lumbridge hops are one stop and
		// fifty tiles apart. With the bush picked and the hops still to do, aiming at patch zero
		// aimed at the tile the player was already standing on — Shortest Path finished the
		// search instantly and drew nothing, which is exactly what "no route between them"
		// looked like. Reported from play.
		//
		// Still one patch rather than every remaining one: the router only needs somewhere to
		// finish, each target is a ring of about seventeen tiles, and this message crosses a
		// plugin boundary. Falling back to patch zero keeps a stop routable in the race where
		// nothing reads as wanted.
		if (approaches.isEmpty())
		{
			FarmPatch wanted = firstPatchStillWanted(stop);
			return wanted == null
				? stop.getRouteTargets(locations)
				: locations.getRouteTargets(wanted);
		}

		List<WorldPoint> targets = new ArrayList<>();
		if (!stop.claimsRegion(playerLocation.getRegionId()))
		{
			targets.addAll(approaches);
		}
		if (dryLand != null)
		{
			targets.addAll(locations.getRouteTargets(dryLand));
		}
		return targets;
	}

	/**
	 * Whether this map region is an instance's virtual area rather than the overworld.
	 *
	 * <p>Instances live at x ≥ 6400, and a region id carries its x in the top byte in units
	 * of 64 tiles — so the boundary is region x-part 100. Unknown (-1) is <b>not</b> treated
	 * as instanced: before the first tick of a session there is no evidence either way, and
	 * the router's own defaulting has always covered that case.
	 */
	private static boolean isInstanced(int regionId)
	{
		return regionId >= 0 && (regionId >>> 8) >= 100;
	}

	/**
	 * Where the player's house opens onto the overworld, when they are standing in it —
	 * null anywhere else, including in every other kind of instance.
	 *
	 * <p>Handed in as a question rather than a dependency, the same arrangement
	 * {@code ShortestPathIntegration} has for its instance and portal knowledge: the
	 * answer lives in {@code PlayerHouse}, and the wiring is one line in the plugin.
	 */
	private java.util.function.Supplier<WorldPoint> frontDoorWhenInside = () -> null;

	/** Told how to ask for the house's front door; see {@link #frontDoorWhenInside}. */
	public void setHouseKnowledge(java.util.function.Supplier<WorldPoint> frontDoorWhenInside)
	{
		this.frontDoorWhenInside = frontDoorWhenInside;
	}

	/** Everything the run has left, for the panel's checklist. */
	public synchronized List<FarmPatch> getRemainingPatches()
	{
		List<FarmPatch> patches = new ArrayList<>();
		for (RunStop stop : stops.values())
		{
			// A skipped region's patches are off the checklist too: a list that keeps naming
			// the place you waved past reads as the run disagreeing with the skip.
			if (skippedRegions.contains(stop.getRegion().getRegionId()))
			{
				continue;
			}
			for (FarmPatch patch : stop.getPatches())
			{
				if (!stop.getServiced().contains(patch.getKey()))
				{
					patches.add(patch);
				}
			}
		}
		return patches;
	}

	/** Transports the current leg uses, if Shortest Path is installed and has said. */
	public Collection<String> getCurrentTransports()
	{
		return router.getCurrentTransports();
	}

	/**
	 * Whether the router's own plan goes through the player's house — any hop departing from
	 * inside the POH area, by the origins it reports. The honest version of every furniture
	 * question the guide used to answer from hop wording; see
	 * {@link ShortestPathIntegration#isRouteDepartsPoh}.
	 */
	public boolean routeUsesHouseFurniture()
	{
		return router.isRouteDepartsPoh();
	}

	/**
	 * The object the route's next hop goes through — "Spirit tree", "Climb-over Crumbling
	 * wall" — or null. Next, not first: see {@link #noteTravelProgress}.
	 */
	@Nullable
	public String getNextTransportObject()
	{
		return router.getNextTransportObject();
	}

	/**
	 * Tells the route where the player has got to, so hops already taken stop being "next".
	 *
	 * <p>Once a tick from the guide, which owns the tick and the player's tile; see
	 * {@link ShortestPathIntegration#noteProgress}.
	 */
	public void noteTravelProgress(@Nullable WorldPoint player)
	{
		router.noteProgress(player);
	}

	/** Whether a route has been asked for and not answered yet. Volatile read, no lock. */
	public boolean isRouteAnswerPending()
	{
		return router.isAwaitingRoute();
	}

	/**
	 * Where Shortest Path says the current leg ends, if it has said.
	 *
	 * <p>Not necessarily one point — see {@code ShortestPathIntegration}. The planner passes it
	 * straight through rather than interpreting it, because deciding what a set of two means
	 * needs the stop list, which the caller has.
	 */
	public Collection<WorldPoint> getCurrentDestinations()
	{
		return router.getCurrentDestinations();
	}

	@Nullable
	public synchronized RunStop getStopFor(FarmPatch patch)
	{
		RunStop stop = stops.get(patch.getRegion().getRegionId());
		return stop != null && stop.contains(patch) ? stop : null;
	}
}
