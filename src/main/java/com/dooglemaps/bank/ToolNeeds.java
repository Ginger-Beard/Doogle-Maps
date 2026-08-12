package com.dooglemaps.bank;

import com.dooglemaps.data.FarmingTool;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Seed;
import com.dooglemaps.guide.CarriedItems;
import com.dooglemaps.state.BarbarianFarming;
import com.dooglemaps.state.LeprechaunStore;
import com.dooglemaps.state.SeedSelectionStore;
import com.dooglemaps.timer.GrowthTimer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Which tools a run needs, and where each one would have to come from.
 *
 * <p>Its own class rather than part of {@link RunLoadout} because two callers need the same
 * answer and they must not disagree: the loadout draws the rows, and {@code RunPlanner} decides
 * whether the run has to open with a bank leg. A tool sitting in the bank is a reason to visit
 * one, in exactly the way a seed is.
 *
 * <p>It depends only on leaves — the leprechaun's store, what is carried, the bank, the seed
 * selection — and calls back into nothing. That keeps the lock graph one-way with
 * {@code RunPlanner} above it; see {@code docs/NOTES.md}.
 *
 * <h2>Why a tool that is only in the bank matters at all</h2>
 *
 * The leprechaun stores every farming tool, so on an established account this answers
 * "everything is on site" and the run opens wherever the work is. That is the common case and
 * the reason the loadout says so little about tools.
 *
 * <p>The case it exists for is the other one: a tool that is <b>nowhere</b>. An account that has
 * never deposited with a leprechaun, a fresh ironman, or someone who dropped a rake and never
 * replaced it will arrive at a weedy patch and be unable to do anything with it. That is a
 * wasted trip that a single line before setting off would have prevented.
 */
@Singleton
public class ToolNeeds
{
	/**
	 * Where a tool has to come from before the run can use it.
	 *
	 * <p>Ordered by how much work each costs the player, which is also the order the panel wants
	 * to sort them in.
	 */
	public enum Source
	{
		/** On you already. Nothing to do. */
		CARRIED,

		/** In the leprechaun's store, so it is collected at the first patch rather than banked. */
		AT_LEPRECHAUN,

		/** Only in the bank, which is a reason for the run to start at one. */
		BANK,

		/** Not carried, not stored, not in the bank. The one worth interrupting someone for. */
		NOWHERE,

		/** The bank has not been opened yet, so absence proves nothing. */
		UNKNOWN
	}

	/** One tool, and what the player would have to do to have it. */
	public static final class Requirement
	{
		private final FarmingTool tool;
		private final Source source;

		Requirement(FarmingTool tool, Source source)
		{
			this.tool = tool;
			this.source = source;
		}

		public FarmingTool getTool()
		{
			return tool;
		}

		public Source getSource()
		{
			return source;
		}
	}

	private final LeprechaunStore leprechaun;
	private final CarriedItems carried;
	private final BankContents bank;
	private final SeedSelectionStore selection;
	private final GrowthTimer growthTimer;
	private final BarbarianFarming barbarianFarming;
	private final com.dooglemaps.state.AvailabilityProfile availability;
	private final com.dooglemaps.state.PatchStateStore stateStore;

	@Inject
	ToolNeeds(LeprechaunStore leprechaun, CarriedItems carried, BankContents bank,
		SeedSelectionStore selection, GrowthTimer growthTimer, BarbarianFarming barbarianFarming,
		com.dooglemaps.state.AvailabilityProfile availability,
		com.dooglemaps.state.PatchStateStore stateStore)
	{
		this.barbarianFarming = barbarianFarming;
		this.leprechaun = leprechaun;
		this.carried = carried;
		this.bank = bank;
		this.selection = selection;
		this.growthTimer = growthTimer;
		// Both leaves, like everything else here — see the class note on the lock graph.
		this.availability = availability;
		this.stateStore = stateStore;
	}

	/** Every tool this run wants, with where each would come from. */
	public List<Requirement> forRun(Set<PatchImplementation> types)
	{
		List<Requirement> requirements = new ArrayList<>();
		for (FarmingTool tool : requiredFor(types))
		{
			requirements.add(new Requirement(tool, sourceOf(tool)));
		}
		return requirements;
	}

	/** Whether anything this run needs is only obtainable from the bank. */
	public boolean anyOnlyInBank(Set<PatchImplementation> types)
	{
		for (Requirement requirement : forRun(types))
		{
			if (requirement.getSource() == Source.BANK)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The tools a run over these patch types cannot be done without.
	 *
	 * <p>Deliberately the short list. Every farming tool could be justified for some patch
	 * somewhere, and a loadout naming all seven would be back to the noise this plugin avoids —
	 * so this is the set whose absence actually stops the run.
	 */
	public Set<FarmingTool> requiredFor(Set<PatchImplementation> types)
	{
		// Only the types that are ground with crops in it. The compost bins reached here the
		// moment they became runnable, and every rule below is about soil - a bin-only run was
		// told to carry a rake and a spade for two lidded boxes. Nothing else changes for a
		// mixed run: the bins ride along and the ground types still answer for themselves.
		Set<PatchImplementation> ground = EnumSet.copyOf(types);
		ground.removeIf(type -> com.dooglemaps.data.CompostBin.forType(type) != null);

		Set<FarmingTool> tools = EnumSet.noneOf(FarmingTool.class);
		if (ground.isEmpty())
		{
			return tools;
		}

		// A rake, unless weeds never grow AND none are standing. Auto-weed is a Farming Guild
		// unlock the plugin already tracks for the growth timers, and telling someone who has it
		// to fetch a rake would be the kind of stale advice that makes a player stop reading the
		// rest — but "has auto-weed" is not "has no weeds". The unlock stops weeds GROWING; a
		// patch that was already weedy when it was bought, or one first reached afterwards, is
		// still weedy and still needs the rake. Reported from play: a weedy patch with the run
		// carrying nothing to clear it with, going straight to the compost step.
		//
		// Only when something on the run can actually BE weedy: a coral nursery's empty state
		// decodes as weeds but its menu has no Rake at all, and the vinery is the same - so a
		// run over only those two never wants the rake whatever auto-weed says.
		if (anythingRakeable(ground)
			&& (!growthTimer.isAutoweedEnabled() || anythingWeedy(ground)))
		{
			tools.add(FarmingTool.RAKE);
		}

		// A spade, always. Anything that dies has to be dug out, and a run that finds one dead
		// patch without one achieves nothing there.
		tools.add(FarmingTool.SPADE);

		// A dibber, if anything on this run is planted from a seed. Saplings go in by hand, so a
		// pure tree run genuinely does not want one — and neither does anyone with Barbarian
		// Farming, which removes the requirement outright. A coral frag is placed rather than
		// dibbed, so its selection does not count; see GuidePlan's sow branch.
		if (plantsAnySeed(ground) && !barbarianFarming.isUnlocked())
		{
			tools.add(FarmingTool.SEED_DIBBER);
		}

		// Secateurs for the families whose diseased crop is pruned back to health. Either pair
		// does the job; the magic ones are handled separately by the loadout, where the point
		// is the +10% rather than being able to act at all. Coral is in the list off core's
		// own menu comment - "Diseased elkhorn coral[Prune]" - and matters more than the
		// bushes do, because there is no plant cure alternative underwater to fall back on.
		if (ground.contains(PatchImplementation.BUSH)
			|| ground.contains(PatchImplementation.FRUIT_TREE)
			|| ground.contains(PatchImplementation.CORAL))
		{
			tools.add(FarmingTool.SECATEURS);
		}

		// A gardening trowel for the vinery, which is the one patch family whose SOIL needs
		// preparing: "players will need to use saltpetre on the patches with a gardening
		// trowel in order to treat the soil before planting". The loadout already banks the
		// saltpetre; without the trowel it is twelve patches' worth of fertiliser and no way
		// to apply it. (The trowel's other use, potting saplings, happens at a bank rather
		// than at a patch, so it is deliberately not asked for by the tree families.)
		if (ground.contains(PatchImplementation.GRAPES))
		{
			tools.add(FarmingTool.GARDENING_TROWEL);
		}

		return tools;
	}

	/** The types this run covers that a rake can ever touch; see the rake rule above. */
	private static boolean anythingRakeable(Set<PatchImplementation> types)
	{
		for (PatchImplementation type : types)
		{
			if (type != PatchImplementation.GRAPES && type != PatchImplementation.CORAL)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether any patch this run could reach has weeds on it right now.
	 *
	 * <p>Asked only when auto-weed says there should be none, so on the overwhelmingly common
	 * account this is never reached at all. The state is the same pair of stores the guide's own
	 * rake step reads, so the loadout cannot promise a rake the patch does not want, or stay
	 * quiet about one it does.
	 *
	 * <p>{@code stage > 0} is the test, not "is it weeds": a clean patch is also weeds, which is
	 * how the game encodes an empty one, and the stage runs backwards from a full patch at 3 to a
	 * raked one at 0. Same test as {@code GuidePlan}'s rake branch, deliberately.
	 */
	private boolean anythingWeedy(Set<PatchImplementation> types)
	{
		for (PatchImplementation type : types)
		{
			if (type == PatchImplementation.GRAPES || type == PatchImplementation.CORAL)
			{
				// Neither is ever raked; their empty states merely decode as weeds. For the
				// nursery that is core's own menu comment: "Coral nursery[Inspect,Guide]",
				// no Rake option.
				continue;
			}
			for (com.dooglemaps.data.FarmPatch patch : availability.getAvailablePatches(type))
			{
				com.dooglemaps.timer.PatchProjection projection =
					growthTimer.project(patch, stateStore.get(patch));
				if (projection != null
					&& projection.getProduce() == com.dooglemaps.data.Produce.WEEDS
					&& projection.getStage() > 0)
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Whether any seed picked for this run goes in with a dibber.
	 *
	 * <p>Asked of the selection rather than assumed from the patch type, because the two can
	 * disagree: a tree patch is planted from a sapling, and {@link Seed#isSapling()} is already
	 * the plugin's answer to that question everywhere else.
	 */
	private boolean plantsAnySeed(Set<PatchImplementation> types)
	{
		for (PatchImplementation type : types)
		{
			if (type == PatchImplementation.CORAL)
			{
				// A frag is placed on the nursery by hand, so its selection is not a reason
				// to carry a dibber.
				continue;
			}
			for (Seed seed : selection.getSelectedFor(type))
			{
				if (!seed.isSapling())
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Where one tool would come from, best case first.
	 *
	 * <p>Carried beats stored beats banked, because that is the order of how much they cost you:
	 * nothing, a click at the patch you were going to anyway, and a trip.
	 */
	public Source sourceOf(FarmingTool tool)
	{
		if (carried.has(tool.getItemID()))
		{
			return Source.CARRIED;
		}

		// The magic pair satisfies a need for plain secateurs — they are secateurs, only better.
		// Without this, someone carrying the enchanted ones would be sent to fetch the ordinary
		// pair they replaced.
		if (tool == FarmingTool.SECATEURS
			&& carried.has(FarmingTool.MAGIC_SECATEURS.getItemID()))
		{
			return Source.CARRIED;
		}

		if (leprechaun.has(tool)
			|| (tool == FarmingTool.SECATEURS && leprechaun.has(FarmingTool.MAGIC_SECATEURS)))
		{
			return Source.AT_LEPRECHAUN;
		}

		if (bank.has(tool.getItemID()))
		{
			return Source.BANK;
		}

		// Nothing anywhere. Only worth saying once both places have actually been read: the
		// leprechaun's store fills in on the first tick after login, but the bank stays unknown
		// until one is opened, and claiming a player owns no spade on the strength of a bank
		// nobody has looked in would be a false alarm every session.
		if (!bank.hasBeenSeen() || !leprechaun.hasBeenRead())
		{
			return Source.UNKNOWN;
		}
		return Source.NOWHERE;
	}
}
