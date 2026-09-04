package com.dooglemaps.data;

import com.dooglemaps.timer.PatchProjection;
import java.util.EnumMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * The patch families a gardener will take coins to clear, and what he charges.
 *
 * <p>Wiki-checked: every ordinary tree, fruit tree and hardwood patch has a gardener who will
 * fell a grown, checked tree for 200 coins rather than the player swinging an axe and digging
 * the stump. The redwood is the exception at both ends of the price list — its gardener
 * (Alexandra, at the Farming Guild) charges 2,000, and hers is the <b>only</b> way a redwood is
 * ever cleared; a player cannot fell one at all.
 *
 * <p>Deliberately absent: the calquat, celastrus and crystal tree, and the spirit tree. Every
 * one of those is cleared by the player's own hand — a machete, an axe, or simply uprooting it —
 * and no gardener stands by any of their patches to offer coins instead. Everything else
 * (allotments, herbs, flowers, hops, bushes, cacti, grapes, seaweed, coral, mushrooms,
 * belladonna, hespori, anima) has no gardener at all.
 *
 * <p>Hand-written, on the model of {@link SpadeClearedCrops}: {@link PatchImplementation} is
 * generated from RuneLite core and cannot carry a fact core has no use for.
 */
public final class PayToClear
{
	/** What every payable gardener but Alexandra charges, whatever is standing in the patch. */
	private static final int ORDINARY_COST = 200;
	/** Alexandra's price — dearer, and the only route a redwood has. */
	private static final int REDWOOD_COST = 2000;

	private static final Map<PatchImplementation, Integer> COSTS = new EnumMap<>(PatchImplementation.class);

	static
	{
		COSTS.put(PatchImplementation.TREE, ORDINARY_COST);
		COSTS.put(PatchImplementation.FRUIT_TREE, ORDINARY_COST);
		COSTS.put(PatchImplementation.HARDWOOD_TREE, ORDINARY_COST);
		COSTS.put(PatchImplementation.REDWOOD, REDWOOD_COST);
	}

	private PayToClear()
	{
	}

	/** Whether a gardener will take coins to clear this kind of patch at all. */
	public static boolean supports(@Nullable PatchImplementation type)
	{
		return type != null && COSTS.containsKey(type);
	}

	/** What that costs, in coins, or 0 where no gardener will do it. */
	public static int cost(@Nullable PatchImplementation type)
	{
		return type == null ? 0 : COSTS.getOrDefault(type, 0);
	}

	/** Whether a gardener will take coins to clear whatever this seed grows into. */
	public static boolean supports(@Nullable Seed seed)
	{
		return seed != null && supports(seed.getPatchType());
	}

	/**
	 * Whether the player has a choice here worth recording a tick-box for.
	 *
	 * <p>{@link #supports} says a gardener will take the coins; this says there is an alternative
	 * to weigh them against. Only the redwood parts the two, and it parts them at both ends: no
	 * standing redwood is ever offered for payment ({@link #isClearable} leaves the family out —
	 * see its note), and the one state that can be named, {@code DEAD}, is cleared by Alexandra
	 * <b>unconditionally</b>, a spade being unable to touch it. So a redwood tick-box would be a
	 * control nothing reads: neither the guide's branch nor the loadout's total would ever ask it.
	 */
	public static boolean offersAChoice(@Nullable PatchImplementation type)
	{
		return supports(type) && type != PatchImplementation.REDWOOD;
	}

	/**
	 * Whether a gardener would take coins to clear whatever is standing in this patch right now.
	 *
	 * <p>THE predicate for a standing, payable crop — {@code RunPlanner.clearableIn} and the seed
	 * selector's own standing-crop rows both call this rather than keeping their own copy, so the
	 * loadout's coin total and the checkboxes offered in the UI can never disagree about which
	 * grown trees are on the table. A felled stump has nothing left to buy, an empty patch has
	 * nothing standing, and a diseased or dead crop is the gardener's own refusal — all three are
	 * excluded by requiring {@link PatchProjection#getCropState()} to be {@code HARVESTABLE} or
	 * {@link PatchProjection#needsHealthCheck()} to answer true, neither of which a stump, an
	 * empty patch, disease or death ever does. The redwood is excluded outright: its own varbit
	 * table gives fifteen consecutive {@code HARVESTABLE} values with no way to tell a tree still
	 * being harvested apart from one that is finished and wants clearing, so this predicate would
	 * count every growing redwood as clearable if it were let in. Its one nameable clearable
	 * state, {@code DEAD}, is counted separately by {@code RunPlanner.deadRedwoodsIn}.
	 */
	public static boolean isClearable(@Nullable PatchProjection projection)
	{
		if (projection == null || projection.getProduce() == null || projection.isEmpty()
			|| projection.isStump())
		{
			return false;
		}
		if (!supports(projection.getPatch().getImplementation())
			|| projection.getPatch().getImplementation() == PatchImplementation.REDWOOD)
		{
			return false;
		}
		return projection.getCropState() == CropState.HARVESTABLE || projection.needsHealthCheck();
	}
}
