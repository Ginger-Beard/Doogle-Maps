package com.dooglemaps.data;

import java.util.HashMap;
import java.util.Map;

/**
 * Farming levels that gate access to a patch, as opposed to planting in it.
 *
 * <h2>Only the Farming Guild, and only because it is a door</h2>
 *
 * Almost every patch in the game is reachable at level 1 — what you can <i>plant</i> is a
 * question about the seed, and {@link Seed#getLevel()} already answers it. The Farming Guild is
 * different: it is three walled tiers, each with its own Farming requirement, and a patch behind
 * a locked door is not a patch you can visit at all. Routing a level-50 account to the guild's
 * redwood patch sends them to a wall.
 *
 * <p>That makes this the same kind of fact as {@code AvailabilityProfile} — whether the patch
 * exists for this account — rather than a display preference, which is why it is applied there
 * and is deliberately not a setting. It is also the one form of availability that is genuinely
 * derivable, so asking the player to tick it off would be asking them to tell us something we
 * can see.
 *
 * <h2>The tiers</h2>
 *
 * <ul>
 *   <li><b>45</b> — entry, and the eastern wing: cactus, both allotments, flower, bush, and the
 *       big compost bin.</li>
 *   <li><b>65</b> — western wing: herb, tree, anima, and the Hespori cave beneath it.</li>
 *   <li><b>85</b> — northern wing: fruit tree, spirit tree, celastrus, redwood. (Redwood needs
 *       90 to <i>plant</i>, which is the seed's business, not the door's.)</li>
 * </ul>
 *
 * <p>Boostable in game, all three. Not modelled: a boost lasts minutes and a run is planned in
 * advance, so treating a boostable tier as open would route someone somewhere they can only
 * reach if they happen to be holding a pie. The patch appears the moment the base level does.
 */
public final class PatchRequirements
{
	/** Patch key to the Farming level needed to reach it. */
	private static final Map<String, Integer> LEVELS = new HashMap<>();

	/** The guild's own door. Everything inside is at least this. */
	private static final int GUILD_ENTRY = 45;

	private static void require(int level, String... patchKeys)
	{
		for (String key : patchKeys)
		{
			LEVELS.put(key, level);
		}
	}

	static
	{
		// Eastern wing, and the guild entrance itself.
		require(GUILD_ENTRY,
			"4922.7904",   // cactus
			"4922.4773",   // allotment north
			"4922.4774",   // allotment south
			"4922.7906",   // flower
			"4922.4772",   // bush
			"4922.7912");  // big compost bin

		// Western wing.
		require(65,
			"4922.4775",   // herb
			"4922.7905",   // tree
			"4922.7911",   // anima
			"5021.7908");  // hespori, in the cave below

		// Northern wing.
		require(85,
			"4922.7909",   // fruit tree
			"4922.4771",   // spirit tree
			"4922.7910",   // celastrus
			"4922.7907");  // redwood
	}

	private PatchRequirements()
	{
	}

	/**
	 * The Farming level needed to reach this patch, or 0 where anyone can walk to it.
	 *
	 * <p>Reaching, not planting. A level-45 account can stand at the guild's cactus patch without
	 * being able to plant a cactus in it, and the patch should still be listed — that is a seed
	 * you have not unlocked, not a place you cannot go.
	 */
	public static int levelFor(FarmPatch patch)
	{
		return patch == null ? 0 : LEVELS.getOrDefault(patch.getKey(), 0);
	}

	/**
	 * Whether this account can reach the patch.
	 *
	 * <p>A level of 0 means the level is not known yet — it is learned from an experience drop or
	 * read at login — and in that case everything is shown. Hiding patches because we have not
	 * looked yet would empty the guild's tabs on a fresh profile.
	 */
	public static boolean isReachable(FarmPatch patch, int farmingLevel)
	{
		return farmingLevel <= 0 || farmingLevel >= levelFor(patch);
	}

	/** Every patch this gates, for the test that checks the keys still exist. */
	public static Map<String, Integer> all()
	{
		return java.util.Collections.unmodifiableMap(LEVELS);
	}
}
