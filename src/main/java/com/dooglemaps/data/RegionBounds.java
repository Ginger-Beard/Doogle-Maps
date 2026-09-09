package com.dooglemaps.data;

import net.runelite.api.coords.WorldPoint;

/**
 * Extra location tests for regions whose patch varbits are not trustworthy everywhere.
 *
 * <p>Most regions send correct varbits anywhere inside them. A handful do not: two
 * regions can overlap and claim the same varbit numbers, or an upper floor sends values
 * belonging to a different patch. These predicates are hand-ported from the anonymous
 * {@code isInBounds} overrides in RuneLite core's {@code FarmingWorld}; the generator
 * emits a call to {@link #forRegion(int)} wherever core had one.
 */
@FunctionalInterface
public interface RegionBounds
{
	RegionBounds ALWAYS = location -> true;

	boolean test(WorldPoint location);

	static RegionBounds forRegion(int regionId)
	{
		switch (regionId)
		{
			// Catherby allotments/herb/flower: upstairs sends different varbits.
			case 11062:
				return location ->
				{
					if (location.getX() >= 2816 && location.getY() < 3456)
					{
						return location.getX() < 2840 && location.getY() >= 3440 && location.getPlane() == 0;
					}
					return true;
				};

			// Catherby fruit tree: always sent when upstairs in 11317.
			case 11317:
				return location -> location.getX() >= 2840 || location.getY() < 3440 || location.getPlane() == 1;

			// Falador allotments: split from the Port Sarim spirit tree patch, which shares
			// the region but sits south of it.
			case 12083:
				return location -> location.getY() >= 3272;

			// Port Sarim spirit tree: the other side of that same split.
			case 12082:
				return location -> location.getY() < 3272;

			// Fossil Island hardwood: varbits cover all of plane 0, but arrive a tick early
			// on certain ladders and stairs, where they still describe the old region.
			case 14651:
				return location ->
				{
					// Stairs to the house on the hill.
					if (location.getX() == 3753 && location.getY() >= 3868 && location.getY() <= 3870)
					{
						return false;
					}

					// East and west ladders to the rope bridge.
					if ((location.getX() == 3729 || location.getX() == 3728
						|| location.getX() == 3747 || location.getX() == 3746)
						&& location.getY() <= 3832 && location.getY() >= 3830)
					{
						return false;
					}

					return location.getPlane() == 0;
				};

			default:
				return ALWAYS;
		}
	}

	/**
	 * Whether a region's varbits may be believed from a map square that is not its own.
	 *
	 * <p>A region is registered under extra map squares so the plugin can name the place, route
	 * to it, and match its objects from a square or two away. None of that is a promise about
	 * varbits: every tree patch in the game answers on one transmitted varbit
	 * ({@code FARMING_TRANSMIT_A}), and the server sends it for the zone the player is standing
	 * in, so outside a patch's own zone that number is describing somebody else's crop. The game
	 * is honest about it and renders a placeholder in the patch until the real value arrives —
	 * see {@code PatchInteractionTracker.transmittingAt} for the run this cost.
	 *
	 * <p>So "anywhere" now means anywhere in the region's own map square, and reaching past it
	 * has to be vouched for. Two things vouch. A region carrying a predicate in
	 * {@link #forRegion} is already an exact statement of the tiles where its varbits are true,
	 * extra squares included — that is what those predicates were hand-ported for, and it is how
	 * Port Sarim's spirit tree stays readable from the strip of 12083 south of y=3272, how
	 * Catherby's allotments stay readable from 11061, and how Fossil Island's hardwoods stay
	 * readable from the eight squares of plane 0 around them. The rest are the handful of places
	 * whose ground genuinely spills over a map square, listed here.
	 */
	static boolean carriesBeyondItsOwnMapSquare(int regionId)
	{
		switch (regionId)
		{
			// The Farming Guild is bigger than one map square: its third tier — redwood,
			// celastrus, anima, fruit tree — stands north of y=3776, which is 4923 and not the
			// 4922 the region is filed under. Standing at the redwood is standing in an extra.
			case 4922:

			// The hespori's cave transmits as 5021, and so does the guild above it: its varbit
			// is sent to anyone standing in the guild, which is the whole reason 4922 was
			// registered on it. See the note on that registration in FarmingWorldData — the
			// sprout beside the cave entrance is the proof, not a guess.
			case 5021:

			// The Great Conch sails, so its patches are wherever the ship is; the calquat stands
			// in one of the thirteen squares the region is registered under rather than in the
			// one it is filed as. See FarmRegion.covers.
			case 12581:
				return true;

			default:
				return forRegion(regionId) != ALWAYS;
		}
	}
}
