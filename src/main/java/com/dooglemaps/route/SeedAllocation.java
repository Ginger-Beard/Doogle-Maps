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
		return forPatches(patches, selected, owned, level, budget, null);
	}

	/**
	 * As above, with one patch given first claim on the scarcest good seed.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"when we're running out of a seed on this run and have 2 types (or more) for the
	 * patches, we should prompt for position 1 first, not position 2. I just had a ranarr seed
	 * (1) on me in my seed box, and snapdragons in my inv (2), and I was prompted to plant the
	 * snapdragon, not the ranarr."</i>
	 *
	 * <p>Nothing was wrong with the <i>ranking</i> — {@code RunEstimate.bestFirst} has honoured
	 * the player's click order since it replaced the expected-XP sort. The ranarr did get first
	 * pick. It got first pick of the <b>lowest-keyed patch</b>, because the sort below is by
	 * patch key, and the player was standing at a different one. One ranarr across five herb
	 * patches is a correct allocation that reserves the good seed for a patch you may reach last,
	 * or never.
	 *
	 * <p>So the patch in front of the player gets first claim. The key sort stays underneath it
	 * and still decides everything else, which keeps the property it was added for: the same
	 * inputs give the same answer, and the guide does not appear to change its mind about a patch
	 * it has already spoken about.
	 *
	 * <p><b>Counts are untouched.</b> This only decides <i>which</i> patch receives a seed, never
	 * how many of each the run plants — one ranarr and four snapdragons either way. That is what
	 * lets {@code AllocationAgreementTest} keep comparing this against {@code RunEstimate}, which
	 * works in counts and has no patches to order.
	 *
	 * @param firstClaim the patch to serve first, or null to order purely by key — which is what
	 *                   the loadout and the planner pass, because they are pricing a run rather
	 *                   than standing in one.
	 */
	public static SeedAllocation forPatches(List<FarmPatch> patches, Set<Seed> selected,
		Map<Seed, Integer> owned, int level, ProtectionBudget budget,
		@Nullable FarmPatch firstClaim)
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

		// ...and then the patch being worked on right now goes to the front, if it is one of
		// these. Moved rather than sorted around, so the order of everything else is exactly the
		// key order it would otherwise have been.
		if (firstClaim != null)
		{
			int at = -1;
			for (int i = 0; i < ordered.size(); i++)
			{
				if (ordered.get(i).getKey().equals(firstClaim.getKey()))
				{
					at = i;
					break;
				}
			}
			if (at > 0)
			{
				ordered.add(0, ordered.remove(at));
			}
		}

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
