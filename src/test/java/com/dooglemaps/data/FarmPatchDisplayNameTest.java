package com.dooglemaps.data;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * {@link FarmPatch#getDisplayName()} for the blank-named case.
 *
 * <h2>The reported bug</h2>
 *
 * Kastori's calquat and fruit tree both have an empty {@code name} - the region has no
 * disambiguator for either - so both read plain "Kastori" and a log line naming the patch
 * could not tell one from the other. Reported from play: a calquat payment logged as
 * "Kastori paid for" was read as the fruit tree's, because that is the only patch "Kastori"
 * had ever meant before. A region with exactly one patch has no such problem - "Falador"
 * alone already identifies it - so only a blank name shared by siblings needs the kind
 * appended.
 */
public class FarmPatchDisplayNameTest
{
	@Test
	public void kastoriCalquatReadsWithItsKind()
	{
		assertEquals("Kastori calquat", FarmingWorldData.getPatch("5423.4771").getDisplayName());
	}

	@Test
	public void kastoriFruitTreeReadsWithItsKind()
	{
		assertEquals("Kastori fruit tree", FarmingWorldData.getPatch("5423.4772").getDisplayName());
	}

	@Test
	public void greatConchCalquatReadsWithItsKind()
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.CALQUAT))
		{
			if ("Great Conch".equals(patch.getRegion().getName()))
			{
				assertEquals("Great Conch calquat", patch.getDisplayName());
				return;
			}
		}
		throw new AssertionError("no Great Conch calquat patch found");
	}

	/** Great Conch's coral patches already have their own disambiguator and are untouched. */
	@Test
	public void greatConchCoralPatchesKeepTheirOwnName()
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.CORAL))
		{
			if (!"Great Conch".equals(patch.getRegion().getName()))
			{
				continue;
			}
			assertEquals("Great Conch " + patch.getName(), patch.getDisplayName());
		}
	}

	/** A region with a single blank-named patch has nothing to disambiguate. */
	@Test
	public void aLoneBlankNamedPatchStaysBareRegionName()
	{
		FarmPatch faladorTree = null;
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.TREE))
		{
			if ("Falador".equals(patch.getRegion().getName()) && patch.getRegion().getPatches().size() == 1)
			{
				faladorTree = patch;
				break;
			}
		}
		if (faladorTree == null)
		{
			throw new AssertionError("no single-patch Falador tree region found");
		}
		assertEquals("Falador", faladorTree.getDisplayName());
	}

	/** Fossil Island's three hardwood patches already carry East/Middle/West and are untouched. */
	@Test
	public void fossilIslandNamedPatchesKeepTheirOwnName()
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.HARDWOOD_TREE))
		{
			if (!"Fossil Island".equals(patch.getRegion().getName()))
			{
				continue;
			}
			assertEquals("Fossil Island " + patch.getName(), patch.getDisplayName());
		}
	}
}
