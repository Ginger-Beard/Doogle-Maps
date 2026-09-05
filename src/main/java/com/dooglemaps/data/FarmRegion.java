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

	/**
	 * Whether a scene object physically standing in {@code regionId} could belong to this
	 * region: the canonical id, or any of the extra ids this region was registered with in
	 * {@link FarmingWorldData}.
	 *
	 * <p>A region is one 64x64 map square, but the ground it names is not always contained in
	 * one — the Great Conch farming ship spans thirteen regions, and its calquat stands in one
	 * of the extras rather than the region it is filed under. An object test that required
	 * {@code regionId == getRegionId()} would never find it.
	 *
	 * <p>An extra id is trusted only when it is not some <i>other</i> region's own canonical
	 * id. Port Sarim's spirit tree patch lists Falador's region (12083) as an extra — both
	 * regions' varbits can be live there — but Falador's allotment patch already owns 12083
	 * outright and shares the same varbit number ({@code FARMING_TRANSMIT_A}) with the spirit
	 * tree. Without this guard, an allotment object standing on its own canonical ground would
	 * satisfy a search for the spirit tree patch too. The canonical owner wins instead.
	 */
	public boolean covers(int regionId)
	{
		if (regionId == this.regionId)
		{
			return true;
		}

		if (!FarmingWorldData.claimsRegionId(this, regionId))
		{
			return false;
		}

		for (FarmRegion other : FarmingWorldData.getRegions())
		{
			if (other != this && other.getRegionId() == regionId)
			{
				return false;
			}
		}

		return true;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
