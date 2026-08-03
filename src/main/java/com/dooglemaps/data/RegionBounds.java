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
}
