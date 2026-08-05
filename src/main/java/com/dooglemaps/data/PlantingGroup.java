package com.dooglemaps.data;

import lombok.Value;

/**
 * A set of patches that get the same seed and the same compost.
 *
 * <p>Usually just a patch type — every herb patch, every allotment. The exceptions are the reason
 * this exists: with protected herbs split out, the disease-free patches are a separate decision
 * from the rest, because that is where the expensive seed goes and the ordinary ones get whatever
 * is cheap and plentiful. A farming contract is the same shape of exception for a different
 * reason — the crop is chosen by Guildmaster Jane rather than by the player.
 *
 * <h2>A patch belongs to exactly one group</h2>
 *
 * Everything downstream leans on that: which tab lists the patch, which seed the guide plants in
 * it, which compost the run assumes, and which count the estimate prices. Two groups claiming the
 * same patch is precisely the arrangement that has the plugin telling you to plant something it
 * did not budget for. So a contract patch <b>moves</b> out of its ordinary group rather than being
 * listed in both — which is also what stops the estimate promising a snapdragon in a patch that is
 * already spoken for, with no reservation logic anywhere.
 *
 * <h2>Why this is not a {@link PatchImplementation} member</h2>
 *
 * That enum is generated from RuneLite's own sources, so a hand-added member would be dropped by
 * the next regeneration — silently, and long after whoever added it had stopped watching. This is
 * a grouping laid over the generated type instead, which is also more honest: the game has no
 * notion of a "protected herb patch", only of a herb patch that happens to be safe for you.
 *
 * <h2>The storage key is the compatibility contract</h2>
 *
 * {@link #getKey()} is what the seed and compost stores persist against, and an unsplit group's
 * key is <b>exactly the enum name</b> it always was. So a player who never turns the split on has
 * their existing choices read back unchanged, and turning it off again returns them to those same
 * choices rather than to an empty list.
 */
@Value
public class PlantingGroup
{
	/**
	 * Which patches of the type a group covers.
	 *
	 * <p>An enum rather than the pair of booleans this reads as, because two booleans would allow
	 * a protected-and-contract combination that means nothing — the guild's herb patch is not
	 * disease-free, and a group that claimed to be both would have no patches and two tabs.
	 */
	public enum Scope
	{
		/** Every patch of the type, which is what a group is when nothing is split out. */
		ALL,
		/** Just the disease-free ones. */
		PROTECTED,
		/** Just the Farming Guild ones growing the contract Guildmaster Jane assigned. */
		CONTRACT
	}

	PatchImplementation type;

	Scope scope;

	/** Every patch of a type, which is what a group is when nothing is split out. */
	public static PlantingGroup of(PatchImplementation type)
	{
		return new PlantingGroup(type, Scope.ALL);
	}

	/** Just the disease-free patches of a type. */
	public static PlantingGroup protectedOnly(PatchImplementation type)
	{
		return new PlantingGroup(type, Scope.PROTECTED);
	}

	/**
	 * The Farming Guild patches an assigned contract has claimed.
	 *
	 * <p>Takes the contract's <b>own</b> patch type rather than being a single standing "Contract"
	 * group, so there is no stable contract tab — it moves with the assignment. That is the point
	 * rather than a cost: the type is what scopes the seed list. {@code Seed.forPatchType} on it
	 * gives exactly the seeds that can go in that patch, so the selector, the compost dropdown, the
	 * yield model and the estimate all key off the same thing they already do. A typeless contract
	 * group would need a null case in every one of those lookups, and would have nothing to build a
	 * seed list from at all.
	 */
	public static PlantingGroup contract(PatchImplementation type)
	{
		return new PlantingGroup(type, Scope.CONTRACT);
	}

	/**
	 * Whether this covers only the patches of that type that cannot catch a disease.
	 *
	 * <p>False for an {@link Scope#ALL} group even when the split is switched off and it therefore
	 * contains the protected patches too. It is not "the unprotected ones" unless a protected group
	 * also exists, which is what keeps the off state from needing its own special case.
	 */
	public boolean isProtectedOnly()
	{
		return scope == Scope.PROTECTED;
	}

	/** Whether this is the group an assigned farming contract has claimed. */
	public boolean isContract()
	{
		return scope == Scope.CONTRACT;
	}

	/**
	 * The key this group is stored under.
	 *
	 * <p>The unsplit form deliberately matches the bare enum name, so nothing already saved has
	 * to be migrated. See the class note.
	 *
	 * <p>A contract keys on its own type — {@code HERB#contract}, {@code BUSH#contract} — so the
	 * compost and protection a player chose for a herb contract are still there the next time they
	 * are given one, independently of what they chose for a bush contract.
	 */
	public String getKey()
	{
		switch (scope)
		{
			case PROTECTED:
				return type.name() + "#protected";
			case CONTRACT:
				return type.name() + "#contract";
			default:
				return type.name();
		}
	}

	/** What the tab is called. */
	public String getDisplayName()
	{
		switch (scope)
		{
			case PROTECTED:
				return type.getDisplayName() + " (protected)";
			case CONTRACT:
				return type.getDisplayName() + " (contract)";
			default:
				return type.getDisplayName();
		}
	}
}
