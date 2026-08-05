package com.dooglemaps.data;

import lombok.Value;

/**
 * A set of patches that get the same seed and the same compost.
 *
 * <p>Usually just a patch type — every herb patch, every allotment. The exception is the reason
 * this exists: with protected herbs split out, the disease-free patches are a separate decision
 * from the rest, because that is where the expensive seed goes and the ordinary ones get whatever
 * is cheap and plentiful.
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
	PatchImplementation type;

	/**
	 * Whether this covers only the patches of that type that cannot catch a disease.
	 *
	 * <p>When false the group covers <b>all</b> patches of the type — including the protected
	 * ones, when the split is switched off. It is not "the unprotected ones" unless a protected
	 * group also exists, which is what keeps the off state from needing its own special case.
	 */
	boolean protectedOnly;

	/** Every patch of a type, which is what a group is when nothing is split out. */
	public static PlantingGroup of(PatchImplementation type)
	{
		return new PlantingGroup(type, false);
	}

	/** Just the disease-free patches of a type. */
	public static PlantingGroup protectedOnly(PatchImplementation type)
	{
		return new PlantingGroup(type, true);
	}

	/**
	 * The key this group is stored under.
	 *
	 * <p>The unsplit form deliberately matches the bare enum name, so nothing already saved has
	 * to be migrated. See the class note.
	 */
	public String getKey()
	{
		return protectedOnly ? type.name() + "#protected" : type.name();
	}

	/** What the tab is called. */
	public String getDisplayName()
	{
		return protectedOnly ? type.getDisplayName() + " (protected)" : type.getDisplayName();
	}
}
