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
 * missing something, when the truth is the feature does not apply to them yet. A contract group is
 * held to the same rule: it exists only while one is assigned and the guild patch it wants is one
 * this account uses.
 *
 * <h2>The contract group is a move, not a copy</h2>
 *
 * While a contract is assigned, the Farming Guild patches of its type <b>leave</b> their ordinary
 * group and join the contract group. That is what makes every consequence fall out of machinery
 * that already exists — it gets a tab because tabs are built per group, and a run line because run
 * options are built per group — and, more usefully, it is what reserves the patch: the ordinary
 * herb group stops counting it, so the estimate cannot promise a snapdragon in a patch that is
 * spoken for, with no reservation logic anywhere.
 */
@Singleton
public class PlantingGroups
{
	private final DoogleMapsConfig config;
	private final ProtectedPatches protectedPatches;
	private final AvailabilityProfile availability;
	private final ContractState contracts;

	@Inject
	private PlantingGroups(DoogleMapsConfig config, ProtectedPatches protectedPatches,
		AvailabilityProfile availability, ContractState contracts)
	{
		this.config = config;
		this.protectedPatches = protectedPatches;
		this.availability = availability;
		this.contracts = contracts;
	}

	/** Whether protected patches are being kept apart from the rest of their type. */
	public boolean isSplit(PatchImplementation type)
	{
		return type == PatchImplementation.HERB
			&& config.separateProtectedHerbs()
			&& hasAnyProtected(type);
	}

	/**
	 * Whether an assigned contract has claimed patches of this type from this account.
	 *
	 * <p>Both halves are needed. A contract for a patch the account has switched off would
	 * otherwise produce a tab with nothing on it and a run line that visits nowhere.
	 */
	public boolean hasContract(PatchImplementation type)
	{
		if (contracts.getContractType() != type)
		{
			return false;
		}
		for (FarmPatch patch : availability.getAvailablePatches(type))
		{
			if (contracts.claims(patch))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Which group a patch belongs to.
	 *
	 * <p>The contract is asked first, because it is the narrower claim and the guild's patch can
	 * satisfy neither of the other two tests — it is not disease-free, so the protected split would
	 * never have taken it, and leaving it in the plain group is precisely the failure this exists
	 * to prevent.
	 *
	 * <p>With the split off, everything else falls into the plain group — including protected
	 * patches, which is what makes the off state behave exactly as it did before any of this
	 * existed.
	 */
	public PlantingGroup groupFor(FarmPatch patch)
	{
		if (patch == null)
		{
			return null;
		}

		PatchImplementation type = patch.getImplementation();
		if (contracts.claims(patch))
		{
			return PlantingGroup.contract(type);
		}
		return isSplit(type) && isProtected(patch)
			? PlantingGroup.protectedOnly(type)
			: PlantingGroup.of(type);
	}

	/**
	 * The groups a patch type presents, in the order they should be shown.
	 *
	 * <p>Contract first, then protected, then the rest. Both exceptions are shorter lists and more
	 * consequential decisions than the bulk — the contract is the highest-value thing in a run and
	 * the protected tab is where the ranarr goes — so neither should be found by scrolling past a
	 * dozen ordinary patches.
	 */
	public List<PlantingGroup> groupsFor(PatchImplementation type)
	{
		List<PlantingGroup> groups = new ArrayList<>();
		if (hasContract(type))
		{
			groups.add(PlantingGroup.contract(type));
		}
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
		PatchImplementation.HARDWOOD_TREE,
		PatchImplementation.CACTUS);

	/**
	 * Types whose crops regrow, so picking them clean is a run in its own right.
	 *
	 * <p>A grown bush or fruit tree keeps producing indefinitely. Visiting one to harvest is the
	 * common case and involves no seed, no compost and no clearing — which is why it is offered
	 * separately rather than folded into the full cycle.
	 */
	private static final java.util.Set<PatchImplementation> REGROWS = regrowingTypes();

	/**
	 * Derived from the produce data rather than listed by hand.
	 *
	 * <p>A hand-written pair missed the cactus patch, which regrows exactly like a bush and had
	 * no harvest-only line to offer — and nothing could have caught that, because the list was
	 * the only statement of what belonged in it. {@code getRegrowTickrate() > 0} is the game's
	 * own answer to "does this come back", so the set cannot fall behind the data again.
	 */
	private static java.util.Set<PatchImplementation> regrowingTypes()
	{
		java.util.Set<PatchImplementation> types =
			java.util.EnumSet.noneOf(PatchImplementation.class);
		for (com.dooglemaps.data.Produce produce : com.dooglemaps.data.Produce.values())
		{
			if (produce.getRegrowTickrate() > 0 && produce.getPatchImplementation() != null)
			{
				types.add(produce.getPatchImplementation());
			}
		}
		return java.util.Collections.unmodifiableSet(types);
	}

	/**
	 * Every line the run's patch-type list should offer.
	 *
	 * <h2>The types with a harvest-only variant come last</h2>
	 *
	 * Every option is a single line except those three, which are a pair — and mixing pairs in
	 * among singles is what pushes them across the two-column grid's row breaks. Grouped at the
	 * bottom, the singles fill whole rows between them and each pair lands on a row of its own,
	 * so the list reads as "the ordinary runs, then the three that can be picked clean".
	 *
	 * <p>Within each half the order is still the enum's, which is the tab strip's, so nothing is
	 * scrambled beyond the one grouping this is for.
	 */
	public java.util.List<com.dooglemaps.data.RunOption> runOptions()
	{
		java.util.List<com.dooglemaps.data.RunOption> options = new ArrayList<>();

		// The types with no harvest-only variant first, then the ones that have it — each
		// immediately followed by its own variant. Within each half the order is the enum's, so
		// it still reads the way the tab strip does.
		for (PatchImplementation type : PatchImplementation.values())
		{
			if (RUNNABLE.contains(type) && !REGROWS.contains(type))
			{
				addFullRuns(options, type);
			}
		}

		for (PatchImplementation type : PatchImplementation.values())
		{
			if (!RUNNABLE.contains(type) || !REGROWS.contains(type))
			{
				continue;
			}
			addFullRuns(options, type);
			options.add(com.dooglemaps.data.RunOption.harvestOnly(PlantingGroup.of(type)));
		}

		addContractRun(options);
		return options;
	}

	/**
	 * The contract's line, pinned to the very end of the list.
	 *
	 * <p>Pinned rather than sitting with its type, and both halves of that matter.
	 *
	 * <p><b>It is not a patch type.</b> Every other line is a standing choice about a kind of
	 * patch you own; this one is a job that moves — cactus this week, bushes the next — so filing
	 * it under whichever type it currently wants would have it jumping around the list. At a fixed
	 * end it is always in the same place, which is what a line you tick every run should be.
	 *
	 * <p><b>And in the middle it broke the pairs.</b> The list is two columns, and a full run and
	 * its harvest-only counterpart have to sit side by side on one row or they read as unrelated
	 * entries. A contract slotted in beside its own type landed between {@code Cactus} and
	 * {@code Cactus (H/O)} and split exactly the pair it was standing next to. Last is the one
	 * position that cannot disturb anything: everything above it keeps the parity it already had,
	 * and {@code RunPanel} gives this line a fresh row of its own.
	 *
	 * <p>The type still has to be carried on the group — it is what scopes the seed list, the
	 * compost and the yield model — but it is deliberately not what the line is filed under.
	 */
	private void addContractRun(java.util.List<com.dooglemaps.data.RunOption> options)
	{
		for (PatchImplementation type : PatchImplementation.values())
		{
			if (hasContract(type))
			{
				options.add(com.dooglemaps.data.RunOption.full(PlantingGroup.contract(type)));
			}
		}
	}

	/**
	 * A type's full-run lines, contract excluded.
	 *
	 * <p>The contract is added once at the end by {@link #addContractRun} instead — see there for
	 * why it must not appear inline.
	 */
	private void addFullRuns(java.util.List<com.dooglemaps.data.RunOption> options,
		PatchImplementation type)
	{
		for (PlantingGroup group : groupsFor(type))
		{
			if (!group.isContract())
			{
				options.add(com.dooglemaps.data.RunOption.full(group));
			}
		}
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
