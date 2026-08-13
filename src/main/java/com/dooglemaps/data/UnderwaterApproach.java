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
	/** One landward point, the object standing on it, and where it leads. */
	public static final class Approach
	{
		private final WorldPoint point;
		private final int objectId;
		private final String name;
		private final int underwaterRegionId;

		Approach(WorldPoint point, int objectId, String name, int underwaterRegionId)
		{
			this.point = point;
			this.objectId = objectId;
			this.name = name;
			this.underwaterRegionId = underwaterRegionId;
		}

		/**
		 * The region on the far side of the approach — the seabed the patches actually stand in.
		 *
		 * <h2>Why this has to be written down</h2>
		 *
		 * A {@link FarmPatch} is filed under the {@code FarmRegion} whose varbits carry it, and
		 * for the coral nurseries that is the <b>Great Conch</b>, region 12581: the ship the
		 * gardener stands on, not the seabed the patches are on. Everything that asks "is the
		 * player at this stop" compares against that one id, so standing among the nurseries
		 * answered no — and the consequences were the whole of two bug reports. The run routed
		 * the player back UP to the steps they had just come down, because the guard that stops
		 * routing while there is work underfoot never fired. Nothing produced a step for the
		 * patches in front of them, because the guide's {@code stopAt} found no stop. And a run
		 * started down there opened at a bank for the compost bins, because
		 * {@code standingAtAStop} said the player was standing on nothing.
		 *
		 * <p>Read straight out of {@code client.log}, which prints the region on the
		 * {@code Run planned:} line: <i>"you are in region 13194 which is not a stop on this
		 * run"</i>, logged from the nurseries. Not inferred, in other words — the same standard
		 * the object ids and coordinates above were held to.
		 *
		 * <p>The seaweed patches have the opposite arrangement and want no fixing: their
		 * {@code FarmRegion} <i>is</i> the underwater region, 15008, so the two agree already.
		 * It is stated here anyway rather than left null, because a rule with one member and one
		 * exception is a rule nobody can check.
		 */
		public int getUnderwaterRegionId()
		{
			return underwaterRegionId;
		}

		/** The tile the object stands on, for naming and outlining it. */
		public WorldPoint getPoint()
		{
			return point;
		}

		/**
		 * The tiles to route to: the object's own, and the ring of eight around it.
		 *
		 * <h2>Why one tile was not enough</h2>
		 *
		 * Shortest Path only ever finishes a search by stepping <b>onto</b> a target tile — there
		 * is no "adjacent counts" — and the tile written down here is the object's own. A set of
		 * steps leading into the sea is not a tile you can stand on any more than a tree is, so
		 * the search could never terminate on it. This is the identical fault
		 * {@link com.dooglemaps.route.PatchLocationStore#getRouteTargets} rings a bush for, and
		 * the same fix: the first walkable tile beside the steps ends the search instantly, and
		 * it is also exactly where the player wants to be stood.
		 *
		 * <p>It is worse here than for a bush, because the coral stop hands the router a second
		 * target — the calquat on the deck — and an unreachable target does not merely delay the
		 * route, it loses to the reachable one. Reported from play twice as being walked to a
		 * table on the Great Conch instead of the steps; the guess offered with the second
		 * report was to move the coordinates a tile west, which is right about the cause and
		 * fixed on one side only. Ringing it covers all four.
		 *
		 * <p>The centre stays in the set. An unreachable member of a target set never wins, so
		 * keeping it costs nothing and means an approach that <i>is</i> walkable still routes to
		 * the spot itself.
		 */
		public java.util.List<WorldPoint> getRouteTargets()
		{
			java.util.List<WorldPoint> targets = new java.util.ArrayList<>();
			targets.add(point);
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					if (dx != 0 || dy != 0)
					{
						targets.add(new WorldPoint(point.getX() + dx, point.getY() + dy,
							point.getPlane()));
					}
				}
			}
			return targets;
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
		 * disagree. Distinct from {@link #getUnderwaterRegionId()}, which is where it leads.
		 */
		public int getRegionId()
		{
			return point.getRegionID();
		}
	}

	/** The seabed below the steps; see {@link Approach#getUnderwaterRegionId()}. */
	private static final int CORAL_NURSERIES_REGION = 13194;

	/**
	 * The Fossil Island seabed, which is also the seaweed patches' own {@code FarmRegion} id —
	 * so unlike the nurseries, nothing here disagreed in the first place.
	 */
	private static final int FOSSIL_ISLAND_SEABED = 15008;

	/**
	 * The steps down to the Coral Nurseries, on the shore of the Great Conch.
	 *
	 * <p>Coordinates and object id both from play rather than from a wiki page, which is worth
	 * saying: they were read off the client at the spot, so they describe the thing the player
	 * actually clicked.
	 */
	private static final Approach CORAL_STEPS =
		new Approach(new WorldPoint(3272, 2463, 0), 57904, "Steps", CORAL_NURSERIES_REGION);

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
		new Approach(new WorldPoint(3761, 3898, 0), 30919, "Rowboat", FOSSIL_ISLAND_SEABED);

	/**
	 * The nursery objects on the seabed, for outlining a patch the varbit scan cannot find.
	 *
	 * <p>Read off the client at the spot, like the steps. Nothing in them carries the patch
	 * varbit everything else keys on, and the two ids do not map one-to-one onto the two
	 * nurseries — so this set alone cannot say which patch an object belongs to.
	 *
	 * <p>It no longer has to. Both nursery <b>positions</b> are known ({@code 12581.4771} and
	 * {@code .4772} in {@code MeasuredPatchLocations}, five tiles apart), so
	 * {@code GuideOverlay.findPatchObjects} narrows this set by where the objects stand. Until
	 * that existed both were marked whichever one the step named, which was reported from play
	 * as the pair lighting up together.
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

	/*
	 * A `stillWanted(approach, playerRegion)` lived here, answering "is the landward approach
	 * still the right thing to route to" with `playerRegion == approach.getRegionId()`.
	 *
	 * It was defending something real — keep the dock as the target after the dive and the
	 * router plots a course back UP to it, fairy rings and all, which was reported from play —
	 * but it defended it with the wrong test. "The player has gone under" and "the player is not
	 * standing on the dock" are different statements, and reading the second as the first meant
	 * the approach was used only while stood on it and never once on the way there. What the
	 * router got instead was the patch, which for a seabed patch is a region centre: a table on
	 * the Great Conch's deck, or a tile out at y 10272 that nothing can reach.
	 *
	 * Deleted rather than corrected, because the fixed test needs the stop's region and the
	 * stop is not a thing this class knows about. It lives in `RunPlanner.routeTargetsFor` now,
	 * next to the route it decides and next to the arrival test it doubles up on.
	 */

	/** The scene objects to outline for this patch, or empty when the varbit scan suffices. */
	public static int[] objectsFor(@Nullable PatchImplementation type)
	{
		return type == PatchImplementation.CORAL
			? CORAL_NURSERY_OBJECTS.clone()
			: new int[0];
	}

	/**
	 * Whether a player standing in this region is standing at this patch.
	 *
	 * <p>The seabed answer to a question every other patch answers with its {@code FarmRegion}.
	 * False for anything on dry land, which keeps callers to one test rather than two: a stop
	 * asks its own region first and only reaches this for the patches that need it.
	 */
	public static boolean isAtPatch(@Nullable FarmPatch patch, int playerRegion)
	{
		Approach approach = forPatch(patch);
		return approach != null && playerRegion == approach.getUnderwaterRegionId();
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
