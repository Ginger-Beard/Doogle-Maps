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
 * <p>Only the multi-location patch types are here. The one-offs — hespori, celastrus,
 * redwood, crystal tree, anima and friends — have a single location each, so the region
 * centre is already a fine approximation and is corrected on the first visit anyway.
 */
final class WikiPatchLocations
{
	private static final Map<Integer, WorldPoint> SEEDED = new HashMap<>();

	static
	{
		SEEDED.put(4922, new WorldPoint(1239, 3727, 0));   // Farming Guild (allotment/flower/herb)
		SEEDED.put(5421, new WorldPoint(1365, 2939, 0));   // Aldarin (hops)
		SEEDED.put(5423, new WorldPoint(1353, 3023, 0));   // Kastori (allotment/flower/herb)
		SEEDED.put(5427, new WorldPoint(1366, 3321, 0));   // Auburnvale (tree)
		SEEDED.put(6192, new WorldPoint(1586, 3099, 0));   // Civitas illa Fortis (allotment/flower/herb)
		SEEDED.put(6967, new WorldPoint(1735, 3555, 0));   // Kourend (allotment/flower/herb)
		SEEDED.put(9265, new WorldPoint(2347, 3162, 0));   // Lletya (fruit tree)
		SEEDED.put(9777, new WorldPoint(2490, 3180, 0));   // Tree Gnome Village (fruit tree)
		SEEDED.put(9781, new WorldPoint(2436, 3415, 0));   // Gnome Stronghold (tree)
		SEEDED.put(10288, new WorldPoint(2576, 3105, 0));   // Yanille (hops)
		SEEDED.put(10290, new WorldPoint(2618, 3226, 0));   // Ardougne (bush)
		SEEDED.put(10300, new WorldPoint(2592, 3864, 0));   // Etceteria (bush)
		SEEDED.put(10548, new WorldPoint(2667, 3375, 0));   // Ardougne (allotment/flower/herb)
		SEEDED.put(10551, new WorldPoint(2667, 3526, 0));   // Seers' Village (hops)
		SEEDED.put(11058, new WorldPoint(2765, 3213, 0));   // Brimhaven (fruit tree)
		SEEDED.put(11060, new WorldPoint(2811, 3337, 0));   // Entrana (hops)
		SEEDED.put(11062, new WorldPoint(2810, 3464, 0));   // Catherby (allotment/flower/herb)
		SEEDED.put(11317, new WorldPoint(2861, 3434, 0));   // Catherby (fruit tree)
		SEEDED.put(11321, new WorldPoint(2828, 3696, 0));   // Troll Stronghold (allotment/flower/herb)
		SEEDED.put(11325, new WorldPoint(2847, 3933, 0));   // Weiss (allotment/flower/herb)
		SEEDED.put(11570, new WorldPoint(2941, 3222, 0));   // Rimmington (bush)
		SEEDED.put(11573, new WorldPoint(2936, 3438, 0));   // Taverley (tree)
		SEEDED.put(11828, new WorldPoint(3004, 3373, 0));   // Falador (tree)
		SEEDED.put(12083, new WorldPoint(3055, 3308, 0));   // Falador (allotment/flower/herb)
		SEEDED.put(12594, new WorldPoint(3193, 3231, 0));   // Lumbridge (tree)
		SEEDED.put(12596, new WorldPoint(3182, 3358, 0));   // Champions' Guild (bush)
		SEEDED.put(12851, new WorldPoint(3229, 3315, 0));   // Lumbridge (hops)
		SEEDED.put(12854, new WorldPoint(3229, 3459, 0));   // Varrock (tree)
		SEEDED.put(13151, new WorldPoint(3291, 6100, 0));   // Prifddinas (allotment/flower/herb)
		SEEDED.put(14391, new WorldPoint(3602, 3526, 0));   // Morytania (allotment/flower/herb)
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
