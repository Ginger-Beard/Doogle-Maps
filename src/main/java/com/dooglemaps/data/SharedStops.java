package com.dooglemaps.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Regions that are one trip, even though the game files them apart.
 *
 * <h2>A stop is a region, except where the teleport says otherwise</h2>
 *
 * {@link com.dooglemaps.route.RunStop}'s own note explains why the region is the right unit: it
 * is already how patches cluster, and fifteen of the game's farming regions carry more than one
 * patch type. That holds almost everywhere. It breaks where two regions share <b>one way in</b>,
 * because then the unit the player actually pays for is the teleport, not the region.
 *
 * <p>Lumbridge's hops patch and the Champions' Guild bush are the case that produced this.
 * They are filed under 12851 and 12596, so the run planned them as two stops — and since the
 * route is chosen a leg at a time by whichever stop is cheapest to reach, it would service one,
 * travel away, and come back to the same corner of the map later for the other. Reported from
 * play in those words: <i>"we end up back there at the same tele to champions guild later in the
 * run doing the bush or hops or vice versa"</i>.
 *
 * <h2>Only where one arrival genuinely serves both</h2>
 *
 * The same restraint {@link UnderwaterApproach} holds its approaches to, and for the same reason:
 * a wrong entry here does not merely fail to help, it drags a patch into a stop it does not
 * belong to and the run walks the length of a city between two things it thinks are adjacent.
 *
 * <p>So an entry goes in when arriving once and walking is plainly cheaper than a second
 * teleport — not merely when two regions touch on the map. Adjacency is not the test; several
 * farming regions border each other across water or a cliff.
 *
 * <p><b>The merge is conditional, and that is where the safety is.</b> It is applied as a pass
 * over stops the run has already planned (see {@code RunPlanner.mergeSharedStops}), so a region
 * only ever joins a stop that <i>exists</i>. Run the hops without the bush and Lumbridge is its
 * own stop exactly as before; nothing here can invent a trip or move one that was going to
 * happen anyway.
 */
public final class SharedStops
{
	/**
	 * The joining region, and the stop it joins.
	 *
	 * <p>Directional on purpose. The host is the one the run should route to and name, which for
	 * this pair is the Champions' Guild: it is the teleport's own destination, so arriving there
	 * and walking down to the hops is the trip the player was making anyway. Reversing it would
	 * name Lumbridge and route to a patch reached by walking back up.
	 */
	private static final Map<Integer, Integer> JOINS = joins();

	private static Map<Integer, Integer> joins()
	{
		Map<Integer, Integer> joins = new LinkedHashMap<>();
		// Lumbridge hops (12851) -> Champions' Guild (12596). One combat-bracelet teleport lands
		// between them; the patches are about fifty tiles apart on foot, which is well inside
		// what a stop already asks of the player at Ardougne.
		joins.put(12851, 12596);
		return Collections.unmodifiableMap(joins);
	}

	private SharedStops()
	{
	}

	/**
	 * The stop this region belongs to, or the region itself when it stands alone.
	 *
	 * <p>One hop only, deliberately: a chain would mean an entry's meaning depended on the rest
	 * of the table, and two entries that happened to point at each other would not terminate.
	 */
	public static int hostOf(int regionId)
	{
		Integer host = JOINS.get(regionId);
		return host == null ? regionId : host;
	}

	/** Whether this region is one the table folds into another stop. */
	public static boolean joinsAnother(int regionId)
	{
		return JOINS.containsKey(regionId);
	}
}
