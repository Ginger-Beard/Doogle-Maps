package com.dooglemaps.state;

import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Seed;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;

/**
 * What you could actually plant in a given kind of patch, right now.
 *
 * <p>A seed qualifies if you own it and meet its Farming level. Both halves matter: a list
 * of everything that goes in a herb patch is a wiki page, not a decision aid.
 */
@Singleton
public class PlantableResolver
{
	/** A seed with how many you have, and whether you can use it. */
	@Value
	public static class Plantable
	{
		Seed seed;
		int owned;

		/**
		 * How many are in a form that can go in the ground.
		 *
		 * <p>The same as {@link #owned} for everything but trees, where the seed has to become
		 * a sapling first. A pile of acorns is worth showing and is not a tree run.
		 */
		int plantable;
		boolean levelMet;

		/** Enough to fill at least one patch. */
		public boolean isUsable()
		{
			return levelMet && plantable >= seed.getSeedsPerPatch();
		}

		/** Owned, but not yet in a form that can be planted — tree seeds needing a plant pot. */
		public boolean needsPotting()
		{
			return seed.isSapling() && plantable < seed.getSeedsPerPatch() && owned > 0;
		}
	}

	private final SeedInventoryStore seeds;

	@Inject
	PlantableResolver(SeedInventoryStore seeds)
	{
		this.seeds = seeds;
	}

	/**
	 * Seeds for one patch type, in Farming level order.
	 *
	 * <p>Lowest requirement first, the same order the wiki's seed tables use and the order the
	 * seeds were unlocked in. It was previously grouped by what you could plant right now,
	 * highest level first, which sounds more helpful than it is: the grid then reshuffled as
	 * your stock changed, so the seed you wanted was never in the same place twice. A fixed
	 * order you can learn beats a clever one you cannot.
	 *
	 * <p>Whether a seed is usable is still shown — it is greyed out — just not sorted on.
	 *
	 * @param includeUnowned whether to list seeds you do not have, for reference
	 */
	public List<Plantable> forPatchType(PatchImplementation type, boolean includeUnowned)
	{
		int farmingLevel = seeds.getFarmingLevel();

		List<Plantable> result = new ArrayList<>();
		for (Seed seed : Seed.forPatchType(type))
		{
			int owned = seeds.getOwned(seed);
			if (owned == 0 && !includeUnowned)
			{
				continue;
			}
			result.add(new Plantable(seed, owned, seeds.getOwnedPlantable(seed),
				farmingLevel >= seed.getLevelRequirement()));
		}

		// Name as a tie-break so seeds sharing a level keep a stable order rather than
		// swapping places between refreshes.
		result.sort(Comparator
			.comparingInt((Plantable p) -> p.getSeed().getLevelRequirement())
			.thenComparing(p -> p.getSeed().getName()));
		return result;
	}
}
