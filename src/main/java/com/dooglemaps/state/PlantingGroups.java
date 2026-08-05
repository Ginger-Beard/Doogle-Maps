package com.dooglemaps.state;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Decides which planting group a patch belongs to, and which groups exist at all.
 *
 * <p>One place, because the answer has to be the same everywhere: the tab a patch is listed under,
 * the seed the guide tells you to plant in it, the compost the run assumes, and the count the
 * estimate prices. Those are four different files, and any disagreement between them shows up as
 * the plugin telling you to plant something it did not budget for.
 *
 * <h2>The split only appears when it would say something</h2>
 *
 * A protected group is offered only when the setting is on <i>and</i> the account actually has at
 * least one such patch. An empty second tab is worse than no second tab: it implies the player is
 * missing something, when the truth is the feature does not apply to them yet.
 */
@Singleton
public class PlantingGroups
{
	private final DoogleMapsConfig config;
	private final ProtectedPatches protectedPatches;
	private final AvailabilityProfile availability;

	@Inject
	private PlantingGroups(DoogleMapsConfig config, ProtectedPatches protectedPatches,
		AvailabilityProfile availability)
	{
		this.config = config;
		this.protectedPatches = protectedPatches;
		this.availability = availability;
	}

	/** Whether protected patches are being kept apart from the rest of their type. */
	public boolean isSplit(PatchImplementation type)
	{
		return type == PatchImplementation.HERB
			&& config.separateProtectedHerbs()
			&& hasAnyProtected(type);
	}

	/**
	 * Which group a patch belongs to.
	 *
	 * <p>With the split off, everything falls into the plain group — including protected patches,
	 * which is what makes the off state behave exactly as it did before any of this existed.
	 */
	public PlantingGroup groupFor(FarmPatch patch)
	{
		if (patch == null)
		{
			return null;
		}

		PatchImplementation type = patch.getImplementation();
		return isSplit(type) && isProtected(patch)
			? PlantingGroup.protectedOnly(type)
			: PlantingGroup.of(type);
	}

	/**
	 * The groups a patch type presents, in the order they should be shown.
	 *
	 * <p>Protected first. It is the shorter list and the more valuable decision — it is where the
	 * ranarr goes — so it should not be found by scrolling past a dozen ordinary patches.
	 */
	public List<PlantingGroup> groupsFor(PatchImplementation type)
	{
		List<PlantingGroup> groups = new ArrayList<>();
		if (isSplit(type))
		{
			groups.add(PlantingGroup.protectedOnly(type));
		}
		groups.add(PlantingGroup.of(type));
		return groups;
	}

	/**
	 * Patch types worth offering as a run.
	 *
	 * <p>The rest are one-offs you visit deliberately — and the compost bins, which have no seed
	 * at all. See {@code TODO.md}: this list wants to be everything eventually, and the gap is
	 * experience data rather than anything structural.
	 */
	private static final java.util.Set<PatchImplementation> RUNNABLE = java.util.EnumSet.of(
		PatchImplementation.HERB,
		PatchImplementation.ALLOTMENT,
		PatchImplementation.FLOWER,
		PatchImplementation.HOPS,
		PatchImplementation.BUSH,
		PatchImplementation.TREE,
		PatchImplementation.FRUIT_TREE,
		PatchImplementation.HARDWOOD_TREE);

	/**
	 * Types whose crops regrow, so picking them clean is a run in its own right.
	 *
	 * <p>A grown bush or fruit tree keeps producing indefinitely. Visiting one to harvest is the
	 * common case and involves no seed, no compost and no clearing — which is why it is offered
	 * separately rather than folded into the full cycle.
	 */
	private static final java.util.Set<PatchImplementation> REGROWS = java.util.EnumSet.of(
		PatchImplementation.BUSH,
		PatchImplementation.FRUIT_TREE);

	/**
	 * Every line the run's patch-type list should offer.
	 *
	 * <p>Order matters: the full run for a type, then its variants directly beneath, so the list
	 * reads as a type with options rather than as unrelated entries that happen to share a name.
	 */
	public java.util.List<com.dooglemaps.data.RunOption> runOptions()
	{
		java.util.List<com.dooglemaps.data.RunOption> options = new ArrayList<>();
		for (PatchImplementation type : PatchImplementation.values())
		{
			if (!RUNNABLE.contains(type))
			{
				continue;
			}

			for (PlantingGroup group : groupsFor(type))
			{
				options.add(com.dooglemaps.data.RunOption.full(group));
			}
			if (REGROWS.contains(type))
			{
				options.add(com.dooglemaps.data.RunOption.harvestOnly(PlantingGroup.of(type)));
			}
		}
		return options;
	}

	/** The patches this group covers, out of the ones the account uses. */
	public List<FarmPatch> patchesIn(PlantingGroup group)
	{
		List<FarmPatch> patches = new ArrayList<>();
		for (FarmPatch patch : availability.getAvailablePatches(group.getType()))
		{
			if (groupFor(patch).equals(group))
			{
				patches.add(patch);
			}
		}
		return patches;
	}

	/**
	 * Whether a patch is disease-free for this account.
	 *
	 * <p>Includes the one unlock that cannot be detected: Fortis Colosseum Champion status has no
	 * varbit, so the player declares it in the settings. Kept here rather than in
	 * {@link ProtectedPatches} so that class stays purely about what can be observed, and the one
	 * thing taken on trust is visible in a single place.
	 */
	public boolean isProtected(FarmPatch patch)
	{
		if (protectedPatches.isProtected(patch))
		{
			return true;
		}
		return config.fortisColosseumChampion()
			&& patch.getImplementation() == PatchImplementation.HERB
			&& patch.getRegion().getRegionId() == CIVITAS_ILLA_FORTIS;
	}

	/** Civitas illa Fortis, whose herb patch is safe with Colosseum Champion status. */
	private static final int CIVITAS_ILLA_FORTIS = 6192;

	private boolean hasAnyProtected(PatchImplementation type)
	{
		return countProtected(type) > 0;
	}

	/**
	 * How many patches of this type the account has that cannot be diseased.
	 *
	 * <p>Public because it is the half of {@link #isSplit} that cannot be seen from the settings.
	 * When the protected tab does not appear, "the setting is on but nothing qualifies" and "the
	 * setting is off" look identical in the sidebar, and this is what tells them apart.
	 */
	public int countProtected(PatchImplementation type)
	{
		int count = 0;
		for (FarmPatch patch : availability.getAvailablePatches(type))
		{
			if (isProtected(patch))
			{
				count++;
			}
		}
		return count;
	}
}
