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

	@Inject
	private ToolNeeds(LeprechaunStore leprechaun, CarriedItems carried, BankContents bank,
		SeedSelectionStore selection, GrowthTimer growthTimer, BarbarianFarming barbarianFarming)
	{
		this.barbarianFarming = barbarianFarming;
		this.leprechaun = leprechaun;
		this.carried = carried;
		this.bank = bank;
		this.selection = selection;
		this.growthTimer = growthTimer;
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
		Set<FarmingTool> tools = EnumSet.noneOf(FarmingTool.class);
		if (types.isEmpty())
		{
			return tools;
		}

		// A rake, unless weeds never grow. Auto-weed is a Farming Guild unlock and the plugin
		// already tracks it for the growth timers, so this costs nothing to get right — and
		// telling someone with auto-weed to fetch a rake would be the kind of stale advice that
		// makes a player stop reading the rest.
		if (!growthTimer.isAutoweedEnabled())
		{
			tools.add(FarmingTool.RAKE);
		}

		// A spade, always. Anything that dies has to be dug out, and a run that finds one dead
		// patch without one achieves nothing there.
		tools.add(FarmingTool.SPADE);

		// A dibber, if anything on this run is planted from a seed. Saplings go in by hand, so a
		// pure tree run genuinely does not want one — and neither does anyone with Barbarian
		// Farming, which removes the requirement outright.
		if (plantsAnySeed(types) && !barbarianFarming.isUnlocked())
		{
			tools.add(FarmingTool.SEED_DIBBER);
		}

		// Secateurs for the families that get pruned. Either pair does the job here; the magic
		// ones are handled separately by the loadout, where the point is the +10% rather than
		// being able to act at all.
		if (types.contains(PatchImplementation.BUSH) || types.contains(PatchImplementation.FRUIT_TREE))
		{
			tools.add(FarmingTool.SECATEURS);
		}

		return tools;
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
