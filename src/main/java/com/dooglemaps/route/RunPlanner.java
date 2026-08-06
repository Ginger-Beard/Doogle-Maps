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
	 * order. See {@code NOTES.md} on lock ordering.
	 */
	private final ToolNeeds tools;

	/** Which herb patches this account's unlocks make immune. A leaf, like everything else here. */
	private final ProtectedPatches protectedPatches;
	private final PlantingGroups groups;
	private final ProtectionSelectionStore protection;

	/** The player's ticked run options, for the harvest-only filter. Named apart from the
	 * planner's own {@code runTypes} field, which is the live run's patch types. */
	private final RunTypeStore runOptions;

	/** Stops still to do, keyed by region id so a patch can be found quickly. */
	private final Map<Integer, RunStop> stops = new LinkedHashMap<>();

	@Getter
	private boolean active;

	/** True while the run is still routing to a bank rather than to patches. */
	@Getter
	private boolean atBankLeg;

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

	@Inject
	private RunPlanner(AvailabilityProfile availability, PatchLocationStore locations,
		BankLocationStore banks, SeedSelectionStore selection, SeedInventoryStore seedInventory,
		PatchStateStore stateStore, GrowthTimer growthTimer, ShortestPathIntegration router,
		PlayerLocation playerLocation, ToolNeeds tools, ProtectedPatches protectedPatches,
		PlantingGroups groups, ProtectionSelectionStore protection, RunTypeStore runOptions)
	{
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
		synchronized (this)
		{
			stops.clear();
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
				if (!isActionable(patch))
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
				if (isActionable(patch))
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
				if (!isActionable(patch))
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
	public synchronized RunEstimate.Survival survivalAcross(Set<PatchImplementation> types)
	{
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
	public synchronized RunEstimate.Survival survivalIn(PlantingGroup group)
	{
		return (seed, compost) ->
		{
			if (seed == null || group == null || seed.getPatchType() != group.getType())
			{
				return 1;
			}

			List<FarmPatch> patches = new ArrayList<>();
			for (FarmPatch patch : availability.getAvailablePatches(group.getType()))
			{
				PlantingGroup patchGroup = groups.groupFor(patch);
				if (patchGroup != null && patchGroup.equals(group))
				{
					patches.add(patch);
				}
			}
			return survivalOver(patches, seed, compost);
		};
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
			boolean paid = protection.isProtecting(groups.groupFor(patch), seed)
				&& DiseaseRisk.isProtectable(patch);
			total += DiseaseRisk.survivalChance(patch, seed.getProduce(), compost, paid,
				groups.isProtected(patch));
			counted++;
		}
		return counted == 0 ? 1 : total / counted;
	}

	private boolean isActionable(FarmPatch patch)
	{
		PatchProjection projection = growthTimer.project(patch, stateStore.get(patch));
		if (projection == null)
		{
			// Never seen it. Worth a look: it may well be empty.
			return true;
		}

		// Harvest-only: ripe counts and nothing else does. An empty bush patch is not a reason to
		// travel when the player has said they are not replanting, and a *grown* one is precisely
		// what they came for — so this narrows the trip rather than merely changing what happens
		// once you arrive.
		if (runOptions.isHarvestOnly(groups.groupFor(patch)))
		{
			return projection.isReady() || projection.getCropState() == CropState.HARVESTABLE;
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
		return !getSupplySources().isEmpty();
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
	 */
	private Set<Seed> selectedForThisRun()
	{
		Set<PatchImplementation> types;
		synchronized (this)
		{
			types = EnumSet.noneOf(PatchImplementation.class);
			types.addAll(runTypes);
		}

		if (types.isEmpty())
		{
			// No run in flight, so there is no run to scope to. Answering "nothing needed"
			// would be a worse lie than answering broadly: a caller asking this outside a run
			// wants to know about the seeds picked, not about a run that does not exist.
			return selection.getSelected();
		}

		Map<PatchImplementation, Integer> actionable = countActionable(types);

		Set<Seed> wanted = new LinkedHashSet<>();
		for (PatchImplementation type : types)
		{
			if (actionable.getOrDefault(type, 0) > 0)
			{
				wanted.addAll(selection.getSelectedFor(type));
			}
		}
		return wanted;
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
			return here != null && !here.isComplete();
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

		for (Seed seed : selectedForThisRun())
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
		Set<SeedSource> sources = getSupplySources();

		if (sources.contains(SeedSource.SEED_VAULT))
		{
			return new LinkedHashSet<>(Collections.singletonList(banks.getSeedVault()));
		}

		// Every bank the account can use; the router knows which is genuinely nearest.
		return new LinkedHashSet<>(banks.getUsableBanks());
	}

	/**
	 * Marks the bank leg done, so routing moves on to the patches.
	 *
	 * <p>Driven by the player actually reaching a bank rather than by us guessing when they
	 * have everything: what a trip needs depends on a loadout that does not exist yet, and
	 * pretending otherwise would strand the run at the bank.
	 */
	public void leaveBank()
	{
		synchronized (this)
		{
			if (!atBankLeg)
			{
				return;
			}
			atBankLeg = false;
			supplyOwed = false;
		}
		log.debug("Leaving the bank; routing to patches");
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
			runTypes.clear();
			active = false;
			atBankLeg = false;
			supplyOwed = false;
		}
		// Same rule as start: the router is another plugin, reached over an event bus that
		// delivers synchronously, so it is never called with this lock held.
		router.clear();
	}

	public synchronized List<RunStop> getStops()
	{
		return Collections.unmodifiableList(new ArrayList<>(stops.values()));
	}

	/** Stops with anything left to do. */
	public synchronized List<RunStop> getRemaining()
	{
		List<RunStop> remaining = new ArrayList<>();
		for (RunStop stop : stops.values())
		{
			if (!stop.isComplete())
			{
				remaining.add(stop);
			}
		}
		return remaining;
	}

	/**
	 * Records that a patch has been dealt with, advancing the run.
	 *
	 * <p>Driven by the same capture that fills the overview: when a patch's state changes
	 * to freshly planted, that stop is done with it.
	 *
	 * @return true if this completed a stop
	 */
	public boolean markServiced(FarmPatch patch)
	{
		boolean completedStop;
		synchronized (this)
		{
			if (!active)
			{
				return false;
			}

			RunStop stop = stops.get(patch.getRegion().getRegionId());
			if (stop == null || !stop.contains(patch) || stop.isComplete())
			{
				return false;
			}

			stop.markServiced(patch);
			completedStop = stop.isComplete();
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
			if (owesSupplies() && needsSupplyTrip())
			{
				synchronized (this)
				{
					atBankLeg = true;
				}
				log.debug("Picking up the supply trip that was deferred at the start");
			}
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
		if (isAtBankLeg())
		{
			// Bank detours allowed here, and only here: the point of this leg is to collect.
			router.setTargets(getSupplyTargets(), true);
			return;
		}

		List<RunStop> remaining = getRemaining();
		if (remaining.isEmpty())
		{
			router.clear();
			synchronized (this)
			{
				active = false;
			}
			return;
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
		router.setTargets(targets);
	}

	/** Everything the run has left, for the panel's checklist. */
	public synchronized List<FarmPatch> getRemainingPatches()
	{
		List<FarmPatch> patches = new ArrayList<>();
		for (RunStop stop : stops.values())
		{
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
