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
	PlantingGroups(DoogleMapsConfig config, ProtectedPatches protectedPatches,
		AvailabilityProfile availability, ContractState contracts)
	{
		this.config = config;
		this.protectedPatches = protectedPatches;
		this.availability = availability;
		this.contracts = contracts;
	}

	/**
	 * Whether protected patches are being kept apart from the rest of their type.
	 *
	 * <p>The cheap half of the condition is asked first, and that ordering is load-bearing rather
	 * than tidy. {@code hasAnyProtected} walks every available patch of the type, so evaluating it
	 * before the type check made a question about herbs cost a scan for cactus. Only herbs can
	 * split, so everything else now answers without touching a patch.
	 */
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
		if (contracts.getActiveContractType() != type)
		{
			return false;
		}
		for (FarmPatch patch : availability.getAvailablePatches(type))
		{
			if (contracts.claimsUntilHandedIn(patch))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The crop an assigned contract is waiting to be grown, or null when there is none.
	 *
	 * <p>Here rather than reaching for {@code ContractState} directly, because the callers asking
	 * this are already asking {@link #groupFor} in the same breath — "is this patch the contract's,
	 * and is the contract's crop what is in it" is one question, and splitting it across two
	 * collaborators is how the two answers drift apart.
	 *
	 * <p>Deliberately the <i>assigned</i> contract rather than one waiting to be handed in. A
	 * finished crop still standing in the patch is not something to make room for; it is something
	 * to give to Jane.
	 */
	@javax.annotation.Nullable
	public com.dooglemaps.data.Produce contractCrop()
	{
		return contracts.getContract();
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
	 *
	 * <h2>This is a hot path, which is not obvious from here</h2>
	 *
	 * It is called <b>per patch inside loops over patches</b> — the run planner's
	 * {@code isActionable}, {@code actionableByGroup}, {@code survivalOver} and
	 * {@code ripeProduceIn}, the sidebar's row and heading builds, and the guide's
	 * {@code patchesWanting}, which is itself nested and runs every game tick. Anything added here
	 * is paid for a hundred times over, so both branches are kept cheap by construction rather than
	 * by memoisation — see the note below on why there is no cache.
	 */
	public PlantingGroup groupFor(FarmPatch patch)
	{
		if (patch == null)
		{
			return null;
		}

		PatchImplementation type = patch.getImplementation();
		// Asked live, not cached. It looks like the obvious thing to memoise — it reads config —
		// but the region is checked before anything else, so the cost is paid only for the eleven
		// Farming Guild patches and is nothing at all for the rest. Against that, the input is
		// *another plugin's* config key, and a stale answer here files the guild's patch under the
		// wrong group. ContractStateTest asserts a contract takes effect immediately.
		//
		// claimsUntilHandedIn, not claims, and this is the difference between a working contract
		// and a wasted growth cycle.
		//
		// claims() goes false the instant the crop finishes, because the completion message clears
		// both config keys. The patch then fell straight back into its ordinary group — so with a
		// grown limpwurt contract sitting in the guild's flower patch, the run cheerfully offered
		// to compost it and plant an ordinary limpwurt on top. Doing that locks the contract out
		// until the replacement finishes growing, which is the single most expensive mistake this
		// plugin could talk somebody into.
		//
		// The patch is spoken for from assignment until the reward is collected, and that is the
		// span this has to cover. Asking for a *seed* over that span is a separate question and is
		// handled separately — see RunLoadout.contractIsStandingThere.
		if (contracts.claimsUntilHandedIn(patch))
		{
			return PlantingGroup.contract(type);
		}
		return isSplit(type) && isProtected(patch)
			? PlantingGroup.protectedOnly(type)
			: PlantingGroup.of(type);
	}

	/*
	 * There is deliberately no cache in this class, and it is worth writing down why, because
	 * `isSplit` walking the type's patch list from inside a per-patch `groupFor` is exactly the
	 * shape that invites one.
	 *
	 * It was tried. Every input has a change signal — ProtectedPatches and availability fire
	 * listeners, the settings arrive as ConfigChanged — so invalidation looked tractable, and it
	 * was wired to all three. `ProtectedTabTest.aLateUnlockAddsTheRunLineToo` failed anyway: it
	 * rebuilds the strip directly, which is a supported thing to do, and no signal reaches here on
	 * that path. The symptom was the protected herb tab and its run line silently failing to appear
	 * after an unlock, which is precisely the user-visible failure the split exists to produce
	 * correctly.
	 *
	 * The trade was bad on its own terms. Only herbs can split, so the walk is over ten to fourteen
	 * patches of cheap flag lookups — see `hasAnyProtected`, which now stops at the first one it
	 * finds rather than counting them all. That is not worth buying a class of bug whose failure
	 * mode is a category quietly going missing.
	 *
	 * The expensive thing in this area was never here: it was `GrowthTimer.project` reading config
	 * seven to fourteen times per patch. That one is cached, because its inputs are per-account
	 * constants with exactly one writer each.
	 */

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
	 * <h2>What kept the second group out until now</h2>
	 *
	 * Experience data, exactly as the old note here said — a runnable type with no row in
	 * {@code crop-xp.tsv} prices the whole trip at zero, which is worse than not offering it.
	 * The rows exist now (wiki-scraped per seed page; see {@code tools/crop-xp.tsv}), so the
	 * six specialist patches join the list.
	 *
	 * <p>The compost bins are still absent and are a different shape of thing: they take
	 * buckets and produce rather than a seed, so they get their own run rather than a place
	 * here. See {@code CompostBinRun}.
	 *
	 * <p>Two of these are underwater and cannot be reached without the gear on your back —
	 * see the diving rows in {@code RunLoadout}, which is what makes them safe to offer
	 * rather than a run that routes you to a place you cannot enter.
	 *
	 * <h2>What stays out, and why it is not an oversight</h2>
	 *
	 * <ul>
	 *   <li><b>Redwood</b> and <b>spirit tree</b> — planted once and then left. A farmed
	 *       redwood <i>regrows</i> after every chop, so the patch is never replanted, and a
	 *       grown spirit tree is a permanent teleport that is never harvested at all. The only
	 *       revisit either wants is a clearing after disease kills it, which is not a run.</li>
	 *   <li><b>The compost bins</b> — buckets and produce rather than a seed, so they get
	 *       their own line instead of a place here. See {@link #addBinRun}.</li>
	 * </ul>
	 *
	 * <p>The crystal tree and the anima patch are single patches rather than circuits, and
	 * are here anyway: both want replanting on a timer, both sit where a run already goes
	 * (Prifddinas, the Farming Guild), and the anima's whole value is that it buffs every
	 * <i>other</i> run — so a dead one is a quiet loss on every patch you own.
	 *
	 * <p>The <b>hespori</b> stayed out for years on the grounds that it grows a demi-boss to
	 * fight rather than a crop to service, which was always a statement about the loadout
	 * rather than the trip: the run's whole withdraw doctrine is farming supplies, and
	 * walking into that cave with a spade and seeds is walking in unarmed. That problem is
	 * now solved where it belongs — its bank leg hands the loadout to the player's own
	 * Inventory Setups profile instead of the withdraw list; see
	 * {@code InventorySetupsHandoff} — so the trip itself can finally be offered. Routing
	 * ends at the cave entrance, not the patch, because the cave is instanced; see
	 * {@code PatchLocationStore}.
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
		PatchImplementation.CACTUS,
		PatchImplementation.GRAPES,
		PatchImplementation.CRYSTAL_TREE,
		PatchImplementation.ANIMA,
		PatchImplementation.SEAWEED,
		PatchImplementation.MUSHROOM,
		PatchImplementation.BELLADONNA,
		PatchImplementation.CALQUAT,
		PatchImplementation.CELASTRUS,
		PatchImplementation.CORAL,
		PatchImplementation.HESPORI);

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
	 * Every line the run's patch-type list should offer, in alphabetical order.
	 *
	 * <h2>Alphabetical, because the list outgrew being read as a whole</h2>
	 *
	 * It used to be the enum's order with the harvest-only pairs grouped at the end, which was
	 * a layout argument: a pair has to sit side by side on one row, and putting them last kept
	 * the grid tidy. That reasoning was already hedged — {@code RunPanel.buildTypeBoxes} pads a
	 * fresh row for every pair anyway, precisely so the layout could not depend on the order —
	 * and with every patch type now runnable the list is long enough that finding a line
	 * matters more than the odd blank cell. Asked for from play.
	 *
	 * <p>A pair still moves as one: the full run and its harvest-only counterpart are adjacent
	 * wherever the name lands them, because two lines about the same patches reading as
	 * unrelated entries is the thing the grouping existed to prevent.
	 *
	 * <p>The <b>contract</b> is the one exception and stays pinned at the end; see
	 * {@link #addContractRun} for why it is not a patch type at all. The compost bins join the
	 * alphabet — "do my bins" is the same kind of standing choice as "do my herbs", and a line
	 * you tick every run should be where you would look for it.
	 */
	public java.util.List<com.dooglemaps.data.RunOption> runOptions()
	{
		List<com.dooglemaps.data.RunOption> paired = new ArrayList<>();
		for (PatchImplementation type : PatchImplementation.values())
		{
			if (RUNNABLE.contains(type))
			{
				addFullRuns(paired, type);
			}
		}
		addBinRun(paired);

		// Sorted on the full line's own label, so a pair's two halves cannot be separated by
		// something whose name happens to fall between them.
		paired.sort((a, b) -> a.getLabel().compareToIgnoreCase(b.getLabel()));

		java.util.List<com.dooglemaps.data.RunOption> options = new ArrayList<>();
		for (com.dooglemaps.data.RunOption option : paired)
		{
			options.add(option);
			if (REGROWS.contains(option.getType()) && !option.getGroup().isContract())
			{
				options.add(com.dooglemaps.data.RunOption.harvestOnly(option.getGroup()));
			}
		}

		addContractRun(options);
		return options;
	}

	/**
	 * The compost bins' line, which joins the alphabet rather than being pinned.
	 *
	 * <p>One line for both sizes, exactly as the sidebar's tab folds them — "the same thing at
	 * two sizes", see {@code PatchTabs}. The line carries the normal bin's group and
	 * {@code RunTypeStore.getSelected} widens the tick to cover the guild's big one, so a
	 * single tick means "do my bins" the way a single tab means "show my bins".
	 *
	 * <p>Deliberately <b>not</b> in {@code RUNNABLE}, although the bins are runnable in every
	 * ordinary sense. That path assumes a seed — the harvest-only derivation, the seed
	 * selector and the estimate all walk {@code Seed.forPatchType} — and a bin takes buckets
	 * and produce instead. Everything seed-shaped stays structurally unable to see it.
	 */
	private void addBinRun(java.util.List<com.dooglemaps.data.RunOption> options)
	{
		options.add(com.dooglemaps.data.RunOption.full(
			PlantingGroup.of(PatchImplementation.COMPOST)));
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

	/**
	 * Whether any patch of this type is disease-free, stopping at the first.
	 *
	 * <p>Not {@code countProtected(type) > 0}, which is what it used to be. This is asked from
	 * {@link #isSplit}, which {@link #groupFor} asks per patch, over the same list — so counting
	 * every patch to answer "is there one" made a linear loop quadratic for no benefit. Anyone with
	 * the split turned on has an unlock, so in practice this now returns on the first or second
	 * patch it looks at.
	 */
	private boolean hasAnyProtected(PatchImplementation type)
	{
		for (FarmPatch patch : availability.getAvailablePatches(type))
		{
			if (isProtected(patch))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * How many patches of this type the account has that cannot be diseased.
	 *
	 * <p>Public because it is the half of {@link #isSplit} that cannot be seen from the settings.
	 * When the protected tab does not appear, "the setting is on but nothing qualifies" and "the
	 * setting is off" look identical in the sidebar, and this is what tells them apart.
	 *
	 * <h2>Every such patch, not only the ones switched on</h2>
	 *
	 * This counted {@code availability.getAvailablePatches}, and that made the group's existence
	 * depend on the row toggles inside it — which is circular, and it showed:
	 *
	 * <ol>
	 *   <li>switch off every patch on the protected herb tab;
	 *   <li>{@code countProtected} is now zero, so {@link #isSplit} is false and the group stops
	 *       existing;
	 *   <li>{@code groupFor} puts those patches back in the plain herb group, so they reappear
	 *       under ordinary herbs;
	 *   <li>the protected tab, still on screen, matches none of them and reads <b>0/0</b> — with
	 *       nothing to click, because the rows that would switch them back on are the very rows
	 *       that vanished.
	 * </ol>
	 *
	 * <p>Reported exactly that way, down to the patches "popping back up in the regular herbs".
	 * A switched-off patch is a choice <i>within</i> a group and must not be able to delete the
	 * group — the same reason {@code PatchTypePanel} draws off patches as red rows rather than
	 * hiding them.
	 *
	 * <p>Counting the whole data set is safe because {@link #isProtected} is unlock-gated: Weiss
	 * only counts once <i>Making Friends with My Arm</i> is done, Harmony once the Morytania elite
	 * diary is. So this still answers "does this account have any", which is what the split is
	 * conditioned on — it just stops asking "and are they currently ticked".
	 */
	public int countProtected(PatchImplementation type)
	{
		int count = 0;
		for (FarmPatch patch : com.dooglemaps.data.FarmingWorldData.getPatches(type))
		{
			if (isProtected(patch))
			{
				count++;
			}
		}
		return count;
	}
}
