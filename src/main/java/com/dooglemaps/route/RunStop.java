package com.dooglemaps.route;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmRegion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

/**
 * One place you stop on a run, and everything you do while standing there.
 *
 * <h2>A stop is a region, not a patch</h2>
 * This is the single most useful thing in the whole planner, and it comes free from the
 * data. RuneLite groups patches by the region whose varbits carry them, and that grouping
 * is already exactly the grouping a player thinks in: Falador's two allotments, its flower
 * patch and its herb patch are one region, so servicing "allotments and flowers along with
 * herbs" costs no extra travel at all. Gnome Stronghold's tree and fruit tree are likewise
 * one region — so are Brimhaven's fruit tree and spirit tree, and the Farming Guild's
 * eleven patch types.
 *
 * <p>Fifteen of the game's forty-three farming regions hold more than one patch type. Treat
 * the region as the unit and most of what looks like "route grouping" stops being a routing
 * problem.
 */
@Getter
public class RunStop
{
	private final FarmRegion region;

	/**
	 * Patches here that this run will actually service.
	 *
	 * <p>Fixed when the run is planned, with one exception — see {@link #adopt}.
	 */
	private final List<FarmPatch> patches;

	/** Patches serviced so far this run. */
	private final Set<String> serviced = new LinkedHashSet<>();

	RunStop(FarmRegion region, List<FarmPatch> patches)
	{
		this.region = region;
		this.patches = new ArrayList<>(patches);
	}

	/**
	 * Takes on a patch the run did not plan for. Does nothing if it is already here.
	 *
	 * <h2>The one thing that can join a stop after it is planned</h2>
	 *
	 * A farming contract, because the contract chain hands one out <b>during</b> the stop: you turn
	 * the finished one in, Jane gives you the next, and it is meant to be planted before you leave.
	 * By then this stop's patch list is minutes old and was built without the faintest idea which
	 * crop she was about to name — so the patch it wants is simply not in it, and everything
	 * downstream reads that as "the run is not going there".
	 *
	 * <p>Reported from play as a yew contract written off for the week, with a grown tree standing
	 * in the patch the whole time.
	 *
	 * <p>Nothing else may do this. A stop that can gain arbitrary patches is a stop that can never
	 * be finished, and the completion test is derived from exactly this list.
	 */
	void adopt(FarmPatch patch)
	{
		if (!patches.contains(patch))
		{
			patches.add(patch);
		}
	}

	/** Defensive, because the list is no longer immutable. */
	public List<FarmPatch> getPatches()
	{
		return Collections.unmodifiableList(patches);
	}

	public String getName()
	{
		return region.getName();
	}

	/**
	 * Whether every patch here has been <b>watched</b> being dealt with.
	 *
	 * <h2>Not the same as "this stop is finished", which is why nothing asks this any more</h2>
	 *
	 * This used to be the run's definition of a completed stop, and it is the wrong shape for the
	 * job. {@link #serviced} is a progress counter: it only advances when
	 * {@code PatchInteractionTracker} watches a patch's varbit change into a growing crop. So a
	 * stop was finished only if the player <i>planted every patch in it</i> — and any patch they
	 * could not or would not plant left the run stranded, with no route drawn and no next
	 * instruction, which reads as the plugin having frozen.
	 *
	 * <p>Four ordinary situations hit it: a harvest-only stop, where nothing is ever planted at all;
	 * a patch with no seed allocated to it; a dead crop with no axe to clear it; and a patch that
	 * simply turns out to want nothing once you arrive.
	 *
	 * <p>{@code RunPlanner.isComplete} answers the real question instead, by asking whether any
	 * patch here is still <i>actionable</i> — the same test the stop was built from, so a stop ends
	 * exactly when it would no longer have been created. That is also the rule {@code GuidePlan}
	 * already applies per patch, and the two halves of the feature now agree.
	 *
	 * <p>Kept because it is still an honest fact and a useful hint — {@code GuideTracker} skips
	 * serviced patches when ordering — but nothing depends on it to end a run.
	 */
	public boolean isFullyServiced()
	{
		return serviced.size() >= patches.size();
	}

	void markServiced(FarmPatch patch)
	{
		serviced.add(patch.getKey());
	}

	boolean contains(FarmPatch patch)
	{
		return patches.contains(patch);
	}

	/**
	 * Where to route for this stop.
	 *
	 * <p>The first patch's position: they are all in one region, so any of them lands the
	 * player in the right place, and walking between them is trivial once there.
	 */
	public WorldPoint getLocation(PatchLocationStore locations)
	{
		return locations.getLocation(patches.get(0));
	}

	@Override
	public String toString()
	{
		return getName() + " (" + patches.size() + " patches)";
	}
}
