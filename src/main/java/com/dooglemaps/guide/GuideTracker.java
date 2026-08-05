package com.dooglemaps.guide;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.Seed;
import com.dooglemaps.route.PatchLocationStore;
import com.dooglemaps.route.RunPlanner;
import com.dooglemaps.route.RunStop;
import com.dooglemaps.state.CompostSelectionStore;
import com.dooglemaps.state.PatchSnapshot;
import com.dooglemaps.state.PatchStateStore;
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

	@Inject
	private GuideTracker(RunPlanner planner, PatchLocationStore locations, PatchStateStore patches,
		GrowthTimer growthTimer, SeedInventoryStore seeds, SeedSelectionStore selection,
		CompostSelectionStore compost, CarriedItems carried, PlayerLocation playerLocation)
	{
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
	private volatile List<GuideStep> current = new ArrayList<>();

	@Subscribe
	public void onGameTick(GameTick event)
	{
		current = computeStepsHere();
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
		List<GuideStep> steps = current;
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
		return current;
	}

	/** Forgets the current guidance, so a stopped run stops instructing immediately. */
	public void reset()
	{
		current = new ArrayList<>();
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
			return steps;
		}

		for (FarmPatch patch : sortedByDistance(stop, player))
		{
			steps.addAll(stepsFor(patch, patchesWanting(stop, patch)));
		}

		appendNoteBeforeLeaving(steps, stop);
		appendReturnBuckets(steps, stop);
		return steps;
	}

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
		Seed chosen = GuidePlan.seedFor(patch.getImplementation(), selection, seeds);
		return GuidePlan.forPatch(projection,
			snapshot == null ? null : snapshot.getCompost(),
			chosen, seeds, compost, carried, patchesToTreat);
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
		CompostTier tier = compost.get(patch.getImplementation());
		if (tier == CompostTier.NONE)
		{
			return 0;
		}

		int count = 0;
		for (FarmPatch other : stop.getPatches())
		{
			if (compost.get(other.getImplementation()) != tier
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
