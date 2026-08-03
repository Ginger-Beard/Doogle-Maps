package com.dooglemaps.data;

import java.util.Arrays;
import java.util.List;
import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

/**
 * A named cluster of patches whose varbits arrive together.
 *
 * <p>The game sends a region's patch varbits whenever you are anywhere in (or sometimes
 * near) that region, which is what lets us refresh a patch by walking past it.
 */
@Getter
public class FarmRegion
{
	private final String name;
	private final int regionId;
	/**
	 * Whether the name reads as a definite place ("the Farming Guild") rather than a bare
	 * one ("Catherby"). Used for grammar in messages.
	 */
	private final boolean definite;
	private final List<FarmPatch> patches;

	private final RegionBounds bounds;

	FarmRegion(String name, int regionId, boolean definite, RegionBounds bounds, FarmPatch... patches)
	{
		this.name = name;
		this.regionId = regionId;
		this.definite = definite;
		this.bounds = bounds;
		this.patches = Arrays.asList(patches);
		for (FarmPatch patch : patches)
		{
			patch.setRegion(this);
		}
	}

	/**
	 * Whether this region's varbits are trustworthy at the given location.
	 *
	 * <p>A few regions overlap or send stale values from an upper floor, so their varbits
	 * must only be read from certain tiles.
	 */
	public boolean isInBounds(WorldPoint location)
	{
		return bounds.test(location);
	}

	@Override
	public String toString()
	{
		return name;
	}
}
