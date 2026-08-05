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

	/** Patches here that this run will actually service. */
	private final List<FarmPatch> patches;

	/** Patches serviced so far this run. */
	private final Set<String> serviced = new LinkedHashSet<>();

	RunStop(FarmRegion region, List<FarmPatch> patches)
	{
		this.region = region;
		this.patches = Collections.unmodifiableList(new ArrayList<>(patches));
	}

	public String getName()
	{
		return region.getName();
	}

	/** Whether every patch at this stop has been dealt with. */
	public boolean isComplete()
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
