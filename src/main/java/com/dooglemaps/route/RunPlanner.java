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
		com.dooglemaps.DoogleMapsConfig config)
	{
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
			stops.putAll(planStops(types));
			runTypes.clear();
			runTypes.addAll(types);
			active = !stops.isEmpty();
		}

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
		}

		// At INFO, and deliberately verbose, because "why did it send me to a bank" has cost
		// several rounds of guessing and every input to that decision is on this one line.
		// Tools are on it for the same reason: they are now one of the things that can send you
		// to a bank, so leaving them off would put the line back to being half an answer.
		log.info("Run planned: {} stops {}; you are in region {} which is {}{}; "
				+ "seeds picked for this run: {}; still to collect: {}; tools: {} -> {}",
			stops.size(), stopRegions(), playerLocation.getRegionId(),
			here ? "a stop on this run" : "not a stop on this run",
			canBankHere ? " and has a supply point in it" : "",
			describeSelectedForThisRun(), getSupplySources(), describeTools(),
			atBankLeg ? "starting at a bank" : "starting where you are");

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
		for (PatchImplementation type : types)
		{
			for (FarmPatch patch : availability.getAvailablePatches(type))
			{
				if (!inTheRun(patch) || !isActionable(patch) || clusterHeld(patch, types))
				{
					continue;
				}
				byRegion.computeIfAbsent(patch.getRegion().getRegionId(), k -> new ArrayList<>()).add(patch);
			}
		}

		Map<Integer, RunStop> planned = new LinkedHashMap<>();
		for (List<FarmPatch> patches : byRegion.values())
		{
			FarmRegion region = patches.get(0).getRegion();
			planned.put(region.getRegionId(), new RunStop(region, patches));
		}
		return planned;
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
		PlantingGroup group = groups.groupFor(patch);
		if (group == null)
		{
			return true;
		}
		return runOptions.isSelected(com.dooglemaps.data.RunOption.full(group))
			|| runOptions.isSelected(com.dooglemaps.data.RunOption.harvestOnly(group));
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
				if (inTheRun(patch) && isActionable(patch) && !clusterHeld(patch, types))
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
				if (!inTheRun(patch) || !isActionable(patch) || clusterHeld(patch, types))
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
		for (FarmPatch patch : stop.getPatches())
		{
			// Actionable *and* actually doable. A patch the guide has no step for is one the
			// player cannot act on however long they stand there, so waiting on it is waiting
			// forever. See nothingToDo.
			if (isActionable(patch) && !blocked.contains(patch.getKey()))
			{
				return false;
			}
		}
		return true;
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
			previewStops(types), countActionable(types), byGroup, ripe, survival);
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

		// Harvest-only: something to pick counts and nothing else does. An empty bush patch is not a
		// reason to travel when the player has said they are not replanting, and a laden one is
		// precisely what they came for — so this narrows the trip rather than merely changing what
		// happens once you arrive.
		//
		// hasProduceToPick, not the raw HARVESTABLE state. A picked-clean bush is still
		// "harvestable" — grown, with a stock of zero — so this stayed true after the player had
		// stripped it, the stop never stopped being actionable, and a harvest-only run could not
		// finish a stop at all. See PatchProjection.hasProduceToPick.
		if (runOptions.isHarvestOnly(groups.groupFor(patch)))
		{
			return projection.hasProduceToPick();
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
			if (projection.regrows() && projection.getCropState() == CropState.HARVESTABLE)
			{
				return projection.hasProduceToPick();
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

	/** The patch types that share a plot at the classic locations, and so ripen as a group. */
	private static final Set<PatchImplementation> CLUSTER_TYPES = EnumSet.of(
		PatchImplementation.ALLOTMENT, PatchImplementation.FLOWER, PatchImplementation.HERB);

	/**
	 * Whether this patch's trip should wait for the rest of its plot.
	 *
	 * <p>The classic locations put an allotment pair, a flower and (except Prifddinas) a herb
	 * patch on the same ground, and they grow at different speeds — a marigold is done in
	 * twenty minutes, a ranarr in eighty. Left alone the run happily made the trip for
	 * whichever finished first, which is two trips where one was wanted. With the setting on,
	 * a cluster patch is held out of the plan while any <b>selected</b> sibling on the same
	 * plot is still healthily growing. Requested from play.
	 *
	 * <p>Only siblings of the types ticked for this run count, which is the whole point of the
	 * scoping: with just flower and herb ticked, a growing allotment holds nothing. A diseased
	 * sibling never holds the trip back — waiting on a crop that is dying is how it dies — and
	 * neither does one still owed its protection payment, for the same reason the payment
	 * keeps a patch actionable at all. Nor is the plot the player is standing on ever held:
	 * the whole point is saving the teleport, and that one is already spent.
	 *
	 * <p>Planning-time only, deliberately: this filter runs in {@link #planStops} and the
	 * counts that price it, never in {@link #isComplete}. Mid-run, a freshly replanted flower
	 * starts growing while the herb beside it is still being picked — a completion filter
	 * would read the herb as "not worth visiting" and finish the stop under the player.
	 */
	private boolean clusterHeld(FarmPatch patch, Set<PatchImplementation> types)
	{
		if (!config.holdClustersUntilReady()
			|| !CLUSTER_TYPES.contains(patch.getImplementation()))
		{
			return false;
		}

		// Never for the plot being stood on. The hold exists to save the teleport, and
		// standing there means it is already spent — starting a run at Falador should pick
		// Falador's ready flower whatever the herb beside it is doing.
		if (patch.getRegion().getRegionId() == playerLocation.getRegionId())
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

	/** What the run thinks you picked, for the diagnostic. */
	private String describeSelectedForThisRun()
	{
		List<String> named = new ArrayList<>();
		for (Seed seed : selectedForThisRun())
		{
			named.add(seed.getName() + " x" + seed.getSeedsPerPatch()
				+ " (inv " + seedInventory.getCount(seed, SeedSource.INVENTORY)
				+ ", box " + seedInventory.getCount(seed, SeedSource.SEED_BOX) + ")");
		}
		return named.isEmpty() ? "none" : named.toString();
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
			RunStop here = stops.get(region);
			return here != null && !isComplete(here);
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
			targets.add(banks.getSeedVault());
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
			supplyOwed = false;
			postedSources = null;
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
			runTypes.clear();
			active = false;
			atBankLeg = false;
			supplyOwed = false;
			bankLegWaived = false;
			runCompletePending = false;
			postedSources = null;
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
		if (wanted == null)
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

	public void reviewProgress()
	{
		if (!active || atBankLeg)
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

	/** Collects a supply trip deferred because the run started standing on work. */
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
		synchronized (this)
		{
			if (!active)
			{
				return false;
			}

			RunStop stop = stops.get(patch.getRegion().getRegionId());
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
		}
		return completedStop;
	}

	/**
	 * Hands every outstanding stop to the router, which picks the cheapest to reach.
	 *
	 * <p>This is the whole ordering strategy. Re-posting the shrinking set after each stop
	 * gives greedy nearest-first over real travel cost, without this class knowing anything
	 * about the map.
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

		// Nothing is routed while there is work where you stand. Finishing a location before
		// travelling is the whole shape of a farm run — the wiki's guide is built on it — so a
		// route to the next stop is not just noise, it is an instruction competing with the one
		// guided mode is giving. It also stopped Shortest Path drawing a teleport-and-bank
		// route across the screen while the player was stood on ripe crops.
		int here = playerLocation.getRegionId();
		for (RunStop stop : remaining)
		{
			if (stop.getRegion().getRegionId() == here)
			{
				router.clear();
				return;
			}
		}

		List<WorldPoint> targets = new ArrayList<>();
		for (RunStop stop : remaining)
		{
			targets.add(stop.getLocation(locations));
		}
		router.setTargets(targets, false, start);
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

	/** The object the route's first hop goes through — "Spirit tree" — or null. */
	@Nullable
	public String getFirstTransportObject()
	{
		return router.getFirstTransportObject();
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
