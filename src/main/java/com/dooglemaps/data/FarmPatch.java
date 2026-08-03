package com.dooglemaps.data;

import lombok.Getter;
import lombok.Setter;

/**
 * One farming patch in the world.
 *
 * <p>Patches are identified by {@code regionId.varbit}: varbit numbers are reused across
 * regions, so neither half identifies a patch on its own. That key is also what we
 * persist under, and it matches the key core Time Tracking uses, which lets us backfill
 * from its cache on first run.
 */
@Getter
public class FarmPatch
{
	/** Disambiguator within a region ("North", "South"), empty when the region has one of these. */
	private final String name;
	private final int varbit;
	private final PatchImplementation implementation;
	/** NPC id of the farmer who protects this patch, or -1 if it cannot be protected. */
	private final int farmer;
	/** Index used to tell apart patches sharing one farmer, or -1 when unambiguous. */
	private final int patchNumber;

	@Setter(lombok.AccessLevel.PACKAGE)
	private FarmRegion region;

	FarmPatch(String name, int varbit, PatchImplementation implementation, int farmer, int patchNumber)
	{
		this.name = name;
		this.varbit = varbit;
		this.implementation = implementation;
		this.farmer = farmer;
		this.patchNumber = patchNumber;
	}

	/** Stable identity, e.g. {@code 12083.4774}. */
	public String getKey()
	{
		return region.getRegionId() + "." + varbit;
	}

	/** Region plus disambiguator, e.g. "Falador North West". */
	public String getDisplayName()
	{
		return name.isEmpty() ? region.getName() : region.getName() + " " + name;
	}

	/** The patch's kind, which is also its sidebar tab and its seed-selection group. */
	public PatchImplementation getType()
	{
		return implementation;
	}

	public boolean isProtectable()
	{
		return farmer != -1;
	}

	@Override
	public String toString()
	{
		return getDisplayName() + " (" + implementation + ")";
	}
}
