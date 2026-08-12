package com.dooglemaps.data;

import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;

/**
 * Where to walk to reach a patch that is not on the walkable map at all.
 *
 * <h2>Why a router cannot be asked for the patch itself</h2>
 *
 * Shortest Path routes over a collision map of places you can stand. A coral nursery is on the
 * seabed, reached by putting the diving gear on and taking a set of steps down — so no path to
 * it exists, and asking for one gets the honest answer: <i>destination unreachable</i>. Reported
 * from play, with the object that actually wants clicking: <b>Steps, id 57904, at (3272, 2463)</b>.
 *
 * <p>So the run asks for the <b>landward approach</b> instead. That is a real tile on the real
 * map, the router paths to it happily, and the last few steps — gear on, click the steps — are
 * the player's, which is the same division of labour the plugin already uses for a fairy ring or
 * a spirit tree.
 *
 * <h2>Only where it has been seen to fail, with the object in hand</h2>
 *
 * Two entries, both added after the router actually refused the patch and both carrying an
 * object id and coordinates read off the client at the spot. The seaweed rowboat was left out
 * of the first pass on the grounds that nobody had reported it — which held until somebody did.
 *
 * <p>That restraint is still the rule rather than a formality: an invented approach point is
 * worse than none, because it routes somebody confidently to the wrong shore. Nothing goes in
 * here on the strength of "the patch looks underwater too".
 */
public final class UnderwaterApproach
{
	/** One landward point, and the object standing on it. */
	public static final class Approach
	{
		private final WorldPoint point;
		private final int objectId;
		private final String name;

		Approach(WorldPoint point, int objectId, String name)
		{
			this.point = point;
			this.objectId = objectId;
			this.name = name;
		}

		/** The tile to route to — walkable, unlike the patch. */
		public WorldPoint getPoint()
		{
			return point;
		}

		/** The scene object to outline once the player is close enough to see it. */
		public int getObjectId()
		{
			return objectId;
		}

		/** What to call it on screen. */
		public String getName()
		{
			return name;
		}

		/**
		 * The region the approach itself stands in — the shore, never the seabed.
		 *
		 * <p>Derived from the point rather than written down beside it, so the two cannot
		 * disagree. This is what {@link #stillWanted} compares against.
		 */
		public int getRegionId()
		{
			return point.getRegionID();
		}
	}

	/**
	 * The steps down to the Coral Nurseries, on the shore of the Great Conch.
	 *
	 * <p>Coordinates and object id both from play rather than from a wiki page, which is worth
	 * saying: they were read off the client at the spot, so they describe the thing the player
	 * actually clicked.
	 */
	private static final Approach CORAL_STEPS =
		new Approach(new WorldPoint(3272, 2463, 0), 57904, "Steps");

	/**
	 * The rowboat out to the Fossil Island underwater area.
	 *
	 * <p>Added when the router refused the seaweed patches exactly as it had refused the
	 * nurseries — the note here used to say seaweed was deliberately absent because nobody had
	 * reported it, which was true right up until somebody did. Same evidence as the steps:
	 * object id and coordinates read off the client at the spot.
	 *
	 * <p>A different shape of approach and the same answer. The nurseries are reached by steps
	 * down from a dock; this is a boat you row out in before diving. Neither is walkable, both
	 * are a real object on a real shore, and the run only has to get the player to the object.
	 */
	private static final Approach SEAWEED_ROWBOAT =
		new Approach(new WorldPoint(3761, 3898, 0), 30919, "Rowboat");

	/**
	 * The nursery objects on the seabed, for outlining a patch the varbit scan cannot find.
	 *
	 * <p>Read off the client at the spot, like the steps. The two nurseries cannot be told
	 * apart from the objects alone — nothing in them carries the patch varbit everything else
	 * keys on — so both are marked whichever one the step names. Two adjacent nurseries read
	 * as one place to go, which is the same call the dropped-crop highlight makes for two
	 * stacks on neighbouring squares.
	 */
	private static final int[] CORAL_NURSERY_OBJECTS = {58710, 58711};

	private UnderwaterApproach()
	{
	}

	/** How to reach this patch type on foot, or null when it is simply walkable. */
	@Nullable
	public static Approach forType(@Nullable PatchImplementation type)
	{
		if (type == PatchImplementation.CORAL)
		{
			return CORAL_STEPS;
		}
		return type == PatchImplementation.SEAWEED ? SEAWEED_ROWBOAT : null;
	}

	/** How to reach this patch on foot, or null when the router can have it directly. */
	@Nullable
	public static Approach forPatch(@Nullable FarmPatch patch)
	{
		return patch == null ? null : forType(patch.getImplementation());
	}

	/**
	 * Whether the landward approach is still the right thing to route to.
	 *
	 * <h2>Once you are down the steps it is the wrong answer, loudly</h2>
	 *
	 * The approach point is on the dock. Kept as the target after the dive, the router does
	 * exactly what it is asked and plots a course back up to it — through fairy rings, from the
	 * seabed. Reported from play: "when we click that we start trying to navigate back to it
	 * via fairy rings". So the substitution lasts only while the player is somewhere else; the
	 * moment they leave the dock's region the patch speaks for itself, and standing at the
	 * nursery needs no route at all.
	 *
	 * @param playerRegion the region the player is in, or -1 when it is not yet known
	 */
	public static boolean stillWanted(@Nullable Approach approach, int playerRegion)
	{
		if (approach == null)
		{
			return false;
		}
		// Unknown counts as "not there yet": before the first tick of a session there is no
		// evidence either way, and routing to the shore is the safe half of that guess.
		return playerRegion < 0 || playerRegion == approach.getRegionId();
	}

	/** The scene objects to outline for this patch, or empty when the varbit scan suffices. */
	public static int[] objectsFor(@Nullable PatchImplementation type)
	{
		return type == PatchImplementation.CORAL
			? CORAL_NURSERY_OBJECTS.clone()
			: new int[0];
	}

	/**
	 * The gear that has to be <b>worn</b> before going under, best first.
	 *
	 * <p>Worn, not merely carried, and that is the whole reason this exists separately from the
	 * loadout's rows: the loadout's job is to get the suit out of the bank, and it is satisfied
	 * the moment the pieces are in the pack. Standing on the dock with them in your inventory
	 * is exactly the state the game punishes — you take the steps down and drown, or are turned
	 * back. The owner's words: "you're intended to put on your diving gear before going under
	 * water".
	 *
	 * <p>The medallion first, because someone who owns one needs neither other piece and a
	 * highlight naming all three would be telling them to wear a helmet they replaced.
	 */
	public static int[] gearToWear()
	{
		return new int[]{
			net.runelite.api.gameval.ItemID.MEDALLION_OF_THE_DEEP,
			net.runelite.api.gameval.ItemID.HUNDRED_PIRATE_DIVING_HELMET,
			net.runelite.api.gameval.ItemID.HUNDRED_PIRATE_DIVING_BACKPACK,
		};
	}
}
