package com.dooglemaps.route;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Seed;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Six saplings across seven patches: the patch that goes without is the last one, never this one.
 *
 * <h2>Why this needed pinning down separately</h2>
 *
 * Two rules meet here, and the second was added after the first. Patches are served in key order
 * so the same inputs always give the same answer and the guide never appears to change its mind;
 * then the patch <b>in front of the player</b> was moved to the head of that order, so a scarce
 * top-ranked seed goes in the ground you are standing on rather than being reserved for a patch
 * you may reach last.
 *
 * <p>The consequence worth asserting is at the other end of the list. Moving a patch to the front
 * has to leave a genuine shortfall falling off the <i>back</i> — on the patch the run reaches last
 * — and it must not be able to leave the player standing at the one patch that got nothing. That
 * is the shape of the reported bug this was written alongside: a run with six papaya saplings in
 * the pack, standing at a patch it had nothing for.
 *
 * <p>Fruit trees because that is the run it came from, and because there are exactly seven of
 * them, which is one more than six.
 */
public class AShortfallLandsOnTheLastPatchNotTheCurrentOneTest
{
	@Test
	public void theUnfilledPatchIsTheLastByKeyAndTheOneUnderfootIsFilled()
	{
		List<FarmPatch> patches = fruitTrees();
		assertEquals("fixture: seven fruit tree patches, one more than the saplings",
			7, patches.size());

		Set<Seed> picked = new LinkedHashSet<>();
		picked.add(Seed.PAPAYA);

		Map<Seed, Integer> owned = new HashMap<>();
		owned.put(Seed.PAPAYA, 6);

		// The player is standing at the patch that sorts last of all, which is the case that
		// would strand them if first claim and the shortfall interfered with each other.
		FarmPatch underfoot = byKey(patches).get(patches.size() - 1);

		SeedAllocation allocation = SeedAllocation.forPatches(patches, picked, owned, 99,
			ProtectionBudget.NONE, underfoot);

		assertNotNull("the patch in front of the player is served first, so it is served",
			allocation.seedFor(underfoot));
		assertEquals("six saplings fill six patches", 6, allocation.counts()
			.getOrDefault(Seed.PAPAYA, 0).intValue());

		// With the underfoot patch lifted out of the key order, the one left over is the last of
		// what remains - the patch the run would reach last, and the cheapest one to go without.
		List<FarmPatch> rest = new ArrayList<>(byKey(patches));
		rest.remove(underfoot);
		FarmPatch lastOfTheRest = rest.get(rest.size() - 1);

		assertNull("the shortfall lands on the patch the run reaches last",
			allocation.seedFor(lastOfTheRest));
		for (FarmPatch patch : patches)
		{
			if (!patch.getKey().equals(lastOfTheRest.getKey()))
			{
				assertNotNull("every other patch is filled: " + patch.getKey(),
					allocation.seedFor(patch));
			}
		}
	}

	/** And with nobody standing anywhere, the shortfall lands on the key-last patch. */
	@Test
	public void withNoClaimItIsSimplyTheLastPatchByKey()
	{
		List<FarmPatch> patches = fruitTrees();

		Set<Seed> picked = new LinkedHashSet<>();
		picked.add(Seed.PAPAYA);

		Map<Seed, Integer> owned = new HashMap<>();
		owned.put(Seed.PAPAYA, 6);

		SeedAllocation allocation = SeedAllocation.forPatches(patches, picked, owned, 99,
			ProtectionBudget.NONE);

		FarmPatch last = byKey(patches).get(patches.size() - 1);
		assertNull("nothing was moved, so the last key goes without", allocation.seedFor(last));
	}

	private static List<FarmPatch> fruitTrees()
	{
		return new ArrayList<>(FarmingWorldData.getPatches(PatchImplementation.FRUIT_TREE));
	}

	/** The order the allocation serves patches in, which is the order it sorts them into. */
	private static List<FarmPatch> byKey(List<FarmPatch> patches)
	{
		List<FarmPatch> sorted = new ArrayList<>(patches);
		sorted.sort(Comparator.comparing(FarmPatch::getKey));
		return sorted;
	}
}
