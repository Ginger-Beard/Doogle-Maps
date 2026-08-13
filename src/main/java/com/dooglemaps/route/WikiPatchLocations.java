package com.dooglemaps.route;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;

/**
 * Starting positions for the patches a farm run actually visits.
 *
 * <p>Scraped from the OSRS Wiki's patch tables, where each location carries a
 * {@code {{Map|x,y}}} template. Every one of these was checked by converting the
 * coordinate to a map region id and confirming it lands in the region RuneLite associates
 * with that patch — all 31 matched, which is decent evidence that both sources agree.
 *
 * <p>These are per <i>location</i>, not per patch: Falador's two allotments, its flower
 * patch and its herb patch share one entry, because the wiki gives one map pin for the
 * cluster. That is close enough to route to, and {@link PatchLocationStore} replaces it
 * with the patch's exact position the first time you stand next to it.
 *
 * <h2>Every region, not only the crowded ones</h2>
 *
 * This used to cover the multi-location types alone, on the reasoning that a one-off patch
 * "has a single location each, so the region centre is already a fine approximation". That is
 * not what a region centre is. It is {@code (regionId >> 8) * 64 + 32} — arithmetic on an id,
 * with no knowledge of where anything stands — and it lands wherever it lands. For the Great
 * Conch it landed on a table on the ship's deck, which is where the run walked people; for the
 * seaweed patches it lands at y 10272, which nothing can reach, and the code worked around
 * that with an approach rather than fixing the pin.
 *
 * <p>It is worse than merely imprecise, because nothing downstream can tell a guess from a
 * measurement. A router handed several targets takes the cheapest, so a centre that happens to
 * lie nearer than the real destination beats it — see {@link PatchLocationStore#isKnown}.
 *
 * <p>So the eleven regions that had no pin were looked up and added: cactus, belladonna,
 * calquat, mushroom, spirit tree, the three hardwood groves, the vinery and the seabed. Only
 * the hespori is still missing, and deliberately — its wiki pin sits on {@code mapID=33}, an
 * instanced cave beneath the guild, so its coordinate is not on the same map as the rest and
 * would fail the region check for a reason that has nothing to do with being wrong.
 */
final class WikiPatchLocations
{
	private static final Map<Integer, WorldPoint> SEEDED = new HashMap<>();

	/**
	 * Pins for individual patches, where one pin for the region is not good enough.
	 *
	 * <h2>Where a region pin stops being honest</h2>
	 *
	 * At most locations the patches are one little farm — Falador's two allotments, its flower
	 * and its herb are all within a few tiles, and the wiki gives them one map pin because they
	 * are one place. The <b>Farming Guild</b> is not that: fourteen patches spread from the
	 * redwood at x 1229 to the cactus at x 1265 and from the anima at y 3723 to the fruit tree
	 * at y 3759, and every one of them was being routed to the herb patch. A handful of other
	 * places have one outlying patch in the same way — a spirit tree at the far end of
	 * Brimhaven, Etceteria and Hosidius, and the fruit tree away from the tree at the Gnome
	 * Stronghold.
	 *
	 * <p>Keyed by {@code FarmPatch.getKey()}, which is {@code region.varbit} — the same string
	 * the learned table uses, so the three tiers read the same way: learned, pinned here,
	 * pinned by region.
	 *
	 * <p>The flower patch and the compost bin have no map template of their own anywhere on the
	 * wiki, which is consistent rather than missing: they stand inside the allotment enclosure
	 * at every location that has them, the guild included. They take the allotment pin.
	 */
	private static final Map<String, WorldPoint> PER_PATCH = new HashMap<>();

	static
	{
		// The Farming Guild, which is the whole reason this map exists. Every coordinate from
		// the wiki's own {{Map}} templates except the four inside the allotment enclosure,
		// which take the enclosure's own polygon centre.
		guild("4773", 1266, 3730);   // allotment (north)
		guild("4774", 1266, 3730);   // allotment (south)
		guild("7906", 1266, 3730);   // flower - inside the enclosure
		guild("7912", 1266, 3730);   // big compost bin - likewise
		guild("4775", 1239, 3727);   // herb
		guild("7905", 1232, 3736);   // tree
		guild("4772", 1261, 3734);   // bush
		guild("7909", 1243, 3759);   // fruit tree
		guild("7904", 1265, 3748);   // cactus
		guild("7910", 1244, 3750);   // celastrus
		guild("7907", 1229, 3755);   // redwood
		guild("7911", 1232, 3723);   // anima
		guild("4771", 1253, 3750);   // spirit tree

		// A spirit tree at the far end of somewhere that has other patches.
		PER_PATCH.put("11058.4772", new WorldPoint(2802, 3203, 0));   // Brimhaven
		PER_PATCH.put("10300.4772", new WorldPoint(2613, 3858, 0));   // Etceteria
		PER_PATCH.put("6967.7904", new WorldPoint(1693, 3542, 0));    // Hosidius

		// Two patches at one place that are not beside each other.
		PER_PATCH.put("9781.4771", new WorldPoint(2436, 3415, 0));    // Gnome Stronghold tree
		PER_PATCH.put("9781.4772", new WorldPoint(2476, 3446, 0));    // ...and its fruit tree
		PER_PATCH.put("5423.4771", new WorldPoint(1367, 3032, 0));    // Kastori calquat
		PER_PATCH.put("5423.4772", new WorldPoint(1350, 3057, 0));    // Kastori fruit tree
		PER_PATCH.put("5423.4773", new WorldPoint(1353, 3023, 0));    // Kastori flower
		PER_PATCH.put("5427.4771", new WorldPoint(1366, 3321, 0));    // Nemus Retreat tree
		PER_PATCH.put("13151.4775", new WorldPoint(3292, 6119, 0));   // Prifddinas crystal tree

		// The Great Conch's calquat, given from play and confirmed by the wiki's own pin for
		// The Summer Shore. Its two coral patches are reached by UnderwaterApproach instead.
		PER_PATCH.put("12581.4773", new WorldPoint(3128, 2405, 0));
	}

	private static void guild(String varbit, int x, int y)
	{
		PER_PATCH.put("4922." + varbit, new WorldPoint(x, y, 0));
	}

	/** This patch's own pin, or null when the region's is the best there is. */
	@Nullable
	static WorldPoint forPatch(com.dooglemaps.data.FarmPatch patch)
	{
		return patch == null ? null : PER_PATCH.get(patch.getKey());
	}

	/** How many patches carry a pin of their own. */
	static int perPatchSize()
	{
		return PER_PATCH.size();
	}

	static
	{
		SEEDED.put(4922, new WorldPoint(1239, 3727, 0));   // Farming Guild (allotment/flower/herb)
		SEEDED.put(5421, new WorldPoint(1365, 2939, 0));   // Aldarin (hops)
		SEEDED.put(5423, new WorldPoint(1353, 3023, 0));   // Kastori (allotment/flower/herb)
		SEEDED.put(5427, new WorldPoint(1366, 3321, 0));   // Auburnvale (tree)
		SEEDED.put(6192, new WorldPoint(1586, 3099, 0));   // Civitas illa Fortis (allotment/flower/herb)
		SEEDED.put(6702, new WorldPoint(1687, 2972, 0));   // Locus Oasis (hardwood), Avium Savannah
		SEEDED.put(6967, new WorldPoint(1735, 3555, 0));   // Kourend (allotment/flower/herb)
		SEEDED.put(7223, new WorldPoint(1816, 3565, 0));   // Hosidius Vinery (grapes) - centre of the wiki's 16x18 rectangle at 1808,3556
		SEEDED.put(9265, new WorldPoint(2347, 3162, 0));   // Lletya (fruit tree)
		SEEDED.put(9770, new WorldPoint(2471, 2703, 0));   // Anglers' Retreat (hardwood)
		SEEDED.put(9777, new WorldPoint(2490, 3180, 0));   // Tree Gnome Village (fruit tree)
		SEEDED.put(9781, new WorldPoint(2436, 3415, 0));   // Gnome Stronghold (tree)
		SEEDED.put(10288, new WorldPoint(2576, 3105, 0));   // Yanille (hops)
		SEEDED.put(10290, new WorldPoint(2618, 3226, 0));   // Ardougne (bush)
		SEEDED.put(10300, new WorldPoint(2592, 3864, 0));   // Etceteria (bush)
		SEEDED.put(10548, new WorldPoint(2667, 3375, 0));   // Ardougne (allotment/flower/herb)
		SEEDED.put(10551, new WorldPoint(2667, 3526, 0));   // Seers' Village (hops)
		SEEDED.put(11056, new WorldPoint(2796, 3101, 0));   // Tai Bwo Wannai (calquat)
		SEEDED.put(11058, new WorldPoint(2765, 3213, 0));   // Brimhaven (fruit tree)
		SEEDED.put(11060, new WorldPoint(2811, 3337, 0));   // Entrana (hops)
		SEEDED.put(11062, new WorldPoint(2810, 3464, 0));   // Catherby (allotment/flower/herb)
		SEEDED.put(11317, new WorldPoint(2861, 3434, 0));   // Catherby (fruit tree)
		SEEDED.put(11321, new WorldPoint(2828, 3696, 0));   // Troll Stronghold (allotment/flower/herb)
		SEEDED.put(11325, new WorldPoint(2847, 3933, 0));   // Weiss (allotment/flower/herb)
		SEEDED.put(11570, new WorldPoint(2941, 3222, 0));   // Rimmington (bush)
		SEEDED.put(11573, new WorldPoint(2936, 3438, 0));   // Taverley (tree)
		SEEDED.put(11828, new WorldPoint(3004, 3373, 0));   // Falador (tree)
		SEEDED.put(12082, new WorldPoint(3060, 3258, 0));   // Port Sarim (spirit tree)
		SEEDED.put(12083, new WorldPoint(3055, 3308, 0));   // Falador (allotment/flower/herb)
		// Great Conch (calquat). Not from the wiki: read off the client at the spot and given
		// from play, which is the same provenance UnderwaterApproach's steps and rowboat carry.
		// It is here rather than left to the region centre because the Conch's centre is
		// (3168, 2400) - a table on the deck - and the deck is nearer than the steps, so the
		// guess was beating the real approach and walking people to the table. Note the tile is
		// in region 12325 while the patch is filed under 12581: the ship spans thirteen regions
		// and its varbits are all on one of them, which is the same disagreement
		// UnderwaterApproach.getUnderwaterRegionId exists for.
		SEEDED.put(12340, new WorldPoint(3087, 3355, 0));   // Draynor Manor (belladonna)
		SEEDED.put(12581, new WorldPoint(3128, 2405, 0));
		SEEDED.put(12594, new WorldPoint(3193, 3231, 0));   // Lumbridge (tree)
		SEEDED.put(12596, new WorldPoint(3182, 3358, 0));   // Champions' Guild (bush)
		SEEDED.put(12851, new WorldPoint(3229, 3315, 0));   // Lumbridge (hops)
		SEEDED.put(12854, new WorldPoint(3229, 3459, 0));   // Varrock (tree)
		SEEDED.put(13106, new WorldPoint(3316, 3203, 0));   // Al Kharid (cactus)
		SEEDED.put(13151, new WorldPoint(3291, 6100, 0));   // Prifddinas (allotment/flower/herb)
		SEEDED.put(13622, new WorldPoint(3452, 3473, 0));   // Canifis (mushroom)
		SEEDED.put(14391, new WorldPoint(3602, 3526, 0));   // Morytania (allotment/flower/herb)
		SEEDED.put(14651, new WorldPoint(3709, 3835, 0));   // Fossil Island Mushroom Forest (hardwood) - centre of the wiki's polygon
		SEEDED.put(15008, new WorldPoint(3736, 10276, 0));   // Underwater (seaweed) - centre of the wiki's 4x10 rectangle at 3734,10271
		SEEDED.put(15148, new WorldPoint(3794, 2836, 0));   // Harmony (allotment/flower/herb)
	}

	private WikiPatchLocations()
	{
	}

	/** A known position for this region, or null to fall back to its centre. */
	@Nullable
	static WorldPoint forRegion(int regionId)
	{
		return SEEDED.get(regionId);
	}

	static int size()
	{
		return SEEDED.size();
	}
}
