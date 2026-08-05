package com.dooglemaps.route;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.Seed;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Which seed goes in which patch.
 *
 * <p>Exists because the reward table and the guide were answering that question differently. The
 * table allocates by rank and spills when something runs out — three magics because the coconuts
 * cover three, then yews. The guide picked one seed for the whole patch type by its own rule, so
 * it would stand at the fourth tree and say "plant magic" for a tree the panel had already
 * budgeted a yew for. Both were internally consistent and they disagreed with each other, which
 * is the worst of the three possible states.
 *
 * <p>This is the shared answer. {@code RunEstimate} works in counts because it is pricing a run
 * that has not started; this works in patches because the guide is standing in front of one. They
 * apply the same ranking and the same budget, and a test asserts they agree.
 *
 * <h2>Recomputed rather than remembered</h2>
 *
 * Nothing is stored between calls, which sounds wasteful and is what makes it correct. As patches
 * are planted they leave the plantable set, their seeds leave the inventory and their payments
 * leave the budget — so the next allocation over what remains naturally continues where the last
 * one left off. Remembering an assignment would instead mean keeping it in step with a player who
 * plants things in their own order, which is the mistake {@link com.dooglemaps.guide.GuidePlan}
 * was written to avoid everywhere else.
 */
public final class SeedAllocation
{
	private final Map<String, Seed> byPatch;

	private SeedAllocation(Map<String, Seed> byPatch)
	{
		this.byPatch = byPatch;
	}

	/** The seed intended for this patch, or null if the run has nothing to put in it. */
	@Nullable
	public Seed seedFor(FarmPatch patch)
	{
		return patch == null ? null : byPatch.get(patch.getKey());
	}

	/** How many patches each seed was given, for comparing against the estimate. */
	public Map<Seed, Integer> counts()
	{
		Map<Seed, Integer> counts = new LinkedHashMap<>();
		for (Seed seed : byPatch.values())
		{
			counts.merge(seed, 1, Integer::sum);
		}
		return counts;
	}

	/**
	 * Assigns the picked seeds across these patches.
	 *
	 * @param patches   the patches wanting a seed, which the caller has already filtered to the
	 *                  ones a run would plant
	 * @param owned     plantable stock per seed, which is what limits how far a crop goes
	 * @param budget    how many patches of each crop can be paid for; see {@link ProtectionBudget}
	 */
	public static SeedAllocation forPatches(List<FarmPatch> patches, Set<Seed> selected,
		Map<Seed, Integer> owned, int level, ProtectionBudget budget)
	{
		Map<String, Seed> assigned = new LinkedHashMap<>();
		if (patches.isEmpty())
		{
			return new SeedAllocation(assigned);
		}

		// Sorted by key so the same inputs always produce the same assignment. Without it the
		// answer could depend on scene order, and the guide would appear to change its mind about
		// a patch it had already told you about.
		List<FarmPatch> ordered = new ArrayList<>(patches);
		ordered.sort(Comparator.comparing(FarmPatch::getKey));

		int next = 0;
		for (Seed seed : RunEstimate.bestFirst(selected, ordered.get(0).getImplementation(), level))
		{
			if (next >= ordered.size())
			{
				break;
			}

			int stock = owned.getOrDefault(seed, 0) / seed.getSeedsPerPatch();
			int fillable = Math.min(ordered.size() - next, stock);

			// The same cap the estimate applies, for the same reason: a crop the player asked to
			// protect only fills what the payments cover, and the rest of the patches fall
			// through to the next crop.
			fillable = Math.min(fillable, budget.affordablePatches(seed));
			if (fillable <= 0)
			{
				continue;
			}
			budget.spend(seed, fillable);

			for (int i = 0; i < fillable; i++)
			{
				assigned.put(ordered.get(next++).getKey(), seed);
			}
		}

		return new SeedAllocation(assigned);
	}
}
