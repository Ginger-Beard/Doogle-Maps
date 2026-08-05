package com.dooglemaps.guide;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.Seed;
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

	@Inject
	private GuideTracker(RunPlanner planner, PatchLocationStore locations, PatchStateStore patches,
		GrowthTimer growthTimer, SeedInventoryStore seeds, SeedSelectionStore selection,
		CompostSelectionStore compost, CarriedItems carried, PlayerLocation playerLocation,
		LeprechaunStore leprechaun, BarbarianFarming barbarianFarming, BankContents bank,
		PlayerHouse house, PlantingGroups groups, ProtectionSelectionStore protection,
		com.dooglemaps.state.RunTypeStore runTypes)
	{
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
		retargetIfMoved();

		List<GuideStep> steps = computeStepsHere();
		List<RunStop> remaining = planner.getRemaining();
		String destination = destinationName(remaining);

		// Only while travelling. Standing at a patch with work to do, the teleport is the last
		// thing anyone wants pointed at — the whole design is one instruction at a time.
		TravelHint hint = planner.isActive() && steps.isEmpty()
			? travelHint(destination)
			: null;

		WorldPoint player = playerLocation();
		RunStop here = player == null ? null : stopAt(player);

		status = new GuideStatus(steps, planner.isActive(), planner.isAtBankLeg(),
			remaining.size(), new ArrayList<>(planner.getCurrentTransports()),
			destination, hint, here == null ? null : here.getName());
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
	}

	private List<GuideStep> computeStepsHere()
	{
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
			return steps;
		}

		if (stop.getRegion().getRegionId() != workingRegion)
		{
			workingRegion = stop.getRegion().getRegionId();
			working = null;
		}

		List<FarmPatch> ordered = sortedByDistance(stop, player);
		FarmPatch first = chooseWorkingPatch(ordered, stop);

		if (first != null)
		{
			steps.addAll(stepsFor(first, patchesWanting(stop, first)));
		}
		for (FarmPatch patch : ordered)
		{
			if (!patch.getKey().equals(first == null ? null : first.getKey()))
			{
				steps.addAll(stepsFor(patch, patchesWanting(stop, patch)));
			}
		}

		appendLeprechaunErrands(steps, stop);
		return steps;
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
			steps.addAll(visit, errands);
		}
		else
		{
			steps.addAll(errands);
		}
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
			if (!produce.isCrop())
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
	@Nullable
	private FarmPatch chooseWorkingPatch(List<FarmPatch> ordered, RunStop stop)
	{
		if (working != null)
		{
			for (FarmPatch patch : ordered)
			{
				if (patch.getKey().equals(working) && !stepsFor(patch, patchesWanting(stop, patch))
					.isEmpty())
				{
					return patch;
				}
			}
		}

		for (FarmPatch patch : ordered)
		{
			if (!stepsFor(patch, patchesWanting(stop, patch)).isEmpty())
			{
				working = patch.getKey();
				return patch;
			}
		}

		working = null;
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

		Map<Seed, Integer> owned = new java.util.HashMap<>();
		Map<Integer, Integer> payments = new java.util.HashMap<>();
		for (Seed seed : selection.getSelectedFor(group))
		{
			owned.put(seed, seeds.getOwnedPlantable(seed));

			ProtectionPayment payment = ProtectionPayment.forSeed(seed);
			if (payment != null && protection.isProtecting(group, seed))
			{
				payments.put(payment.getItemID(),
					bank.getCount(payment.getItemID()) + carried.getCount(payment.getItemID()));
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

	/** The steps outstanding at one patch, for the panel's per-patch view. */
	public List<GuideStep> stepsFor(FarmPatch patch)
	{
		return stepsFor(patch, 1);
	}

	private List<GuideStep> stepsFor(FarmPatch patch, int patchesToTreat)
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
		return GuidePlan.forPatch(projection,
			snapshot == null ? null : snapshot.getCompost(),
			group, chosen, seeds, compost, carried, leprechaun, barbarianFarming,
			protection.isProtecting(group, chosen), runTypes.isHarvestOnly(group), patchesToTreat);
	}

	/**
	 * How many patches at this stop are waiting for the same compost as this one.
	 *
	 * <p>So the withdrawal can say "take 4" rather than "take some". Counted per <i>tier</i>,
	 * because a stop can mix them — ultra on the herbs and nothing on the hops is a normal way
	 * to farm, and both are patches here.
	 */
	private int patchesWanting(RunStop stop, FarmPatch patch)
	{
		CompostTier tier = compost.get(groups.groupFor(patch));
		if (tier == CompostTier.NONE)
		{
			return 0;
		}

		int count = 0;
		for (FarmPatch other : stop.getPatches())
		{
			if (compost.get(groups.groupFor(other)) != tier
				|| stop.getServiced().contains(other.getKey()))
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
	private void appendNoteBeforeLeaving(List<GuideStep> steps, RunStop stop)
	{
		Produce carrying = null;
		int most = 0;

		for (Produce produce : Produce.values())
		{
			if (!produce.isCrop())
			{
				continue;
			}
			int held = carried.getInventoryCount(produce.getItemID());
			if (held > most)
			{
				most = held;
				carrying = produce;
			}
		}

		if (carrying == null)
		{
			return;
		}

		steps.add(GuideStep.atLeprechaun(GuideAction.NOTE_AT_LEPRECHAUN,
			stop.getPatches().get(0), carrying.getItemID(), null,
			"Note your " + carrying.getName().toLowerCase()
				+ " with the leprechaun before moving on - unnoted crops cost a slot each."));
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
	 * <p>Built from {@link TeleportItems}, not from Shortest Path's transport strings. Those
	 * strings are for reading, not for matching — they are another plugin's display text and
	 * would have to be parsed, which breaks the moment it rewords one. The teleport table is
	 * already the plugin's own answer to "what reaches this region", it is a table of facts about
	 * items rather than advice, and it is what the bank loadout is built on, so using it here
	 * keeps the two agreeing.
	 *
	 * <p>Carried beats banked beats neither, which is the order of how much work each costs.
	 * "Neither" is still worth returning: the portal nexus and the jewellery box get you places
	 * without owning any item, so a destination alone is enough to highlight something.
	 */
	@Nullable
	private TravelHint travelHint(@Nullable String destination)
	{
		if (destination == null)
		{
			return null;
		}

		TeleportItems.Teleport banked = null;
		for (WorldPoint point : planner.getCurrentDestinations())
		{
			for (TeleportItems.Teleport teleport : TeleportItems.forRegion(point.getRegionID()))
			{
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
				if (teleport.teleportsYou() && carried.has(teleport.getItemId()))
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
		RunStop found = null;
		for (WorldPoint destination : planner.getCurrentDestinations())
		{
			for (RunStop stop : remaining)
			{
				if (stop.getRegion().getRegionId() == destination.getRegionID())
				{
					if (found != null && !found.getName().equals(stop.getName()))
					{
						return null;
					}
					found = stop;
				}
			}
		}
		return found == null ? null : found.getName();
	}

	/** The run stop the player is standing in, or null if they are between stops. */
	@Nullable
	private RunStop stopAt(WorldPoint player)
	{
		for (RunStop stop : planner.getRemaining())
		{
			if (stop.getRegion().getRegionId() == player.getRegionID())
			{
				return stop;
			}
		}
		return null;
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
		List<FarmPatch> ordered = new ArrayList<>(stop.getPatches());
		ordered.removeIf(patch -> stop.getServiced().contains(patch.getKey()));
		ordered.sort((a, b) -> Integer.compare(distance(player, a), distance(player, b)));
		return ordered;
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
