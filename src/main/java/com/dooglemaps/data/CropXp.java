// GENERATED FILE - DO NOT EDIT BY HAND.
// Regenerate with: python3 tools/generate_farming_data.py <runelite-client-sources>
//
// Mirrored from RuneLite core's net.runelite.client.plugins.timetracking.farming
// package (Copyright (c) 2018 Abex and the RuneLite contributors, BSD 2-clause).
// Those classes are package-private, so external plugins must carry their own copy.
// See ATTRIBUTION.md.
package com.dooglemaps.data;


import java.util.EnumMap;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Farming experience for each crop, per seed rather than per patch.
 *
 * <p>Scraped from the OSRS Wiki's seed tables; see {@code tools/crop-xp.tsv}. The
 * three awards are not all present for every crop, and the differences are the point:
 *
 * <ul>
 *   <li><b>Planting</b> — every crop, when the seed goes in.</li>
 *   <li><b>Check-health</b> — trees, hardwoods and bushes only, and it dwarfs the
 *       rest. A magic tree is over 13,000 for one click.</li>
 *   <li><b>Harvest</b> — per item picked. Trees give none at all: their logs are
 *       Woodcutting experience, not Farming.</li>
 * </ul>
 *
 * <p><b>Fruit trees pay both</b>, which is why they were absent for so long: the
 * patch/Seeds table gives one unlabelled figure that is neither the check award nor
 * the per-fruit rate but the sum of the whole cycle. The three components are on each
 * seed's own page, and six of the eight reconcile exactly against that total
 * ({@code plant + check + 6 x harvest}), which is what makes the split safe to use
 * rather than a guess. See {@code tools/crop-xp.tsv} for the two that do not.
 */
@Getter
@RequiredArgsConstructor
public enum CropXp
{
	GUAM(Seed.GUAM, 11.0, 0.0, 12.5),
	MARRENTILL(Seed.MARRENTILL, 13.5, 0.0, 15.0),
	TARROMIN(Seed.TARROMIN, 16.0, 0.0, 18.0),
	HARRALANDER(Seed.HARRALANDER, 21.5, 0.0, 24.0),
	RANARR(Seed.RANARR, 27.0, 0.0, 30.5),
	TOADFLAX(Seed.TOADFLAX, 34.0, 0.0, 38.5),
	IRIT(Seed.IRIT, 43.0, 0.0, 48.5),
	AVANTOE(Seed.AVANTOE, 54.5, 0.0, 61.5),
	KWUARM(Seed.KWUARM, 69.0, 0.0, 78.0),
	SNAPDRAGON(Seed.SNAPDRAGON, 87.5, 0.0, 98.5),
	HUASCA(Seed.HUASCA, 86.5, 0.0, 110.0),
	CADANTINE(Seed.CADANTINE, 106.5, 0.0, 120.0),
	LANTADYME(Seed.LANTADYME, 134.5, 0.0, 151.5),
	DWARF_WEED(Seed.DWARF_WEED, 170.5, 0.0, 192.0),
	TORSTOL(Seed.TORSTOL, 199.5, 0.0, 224.5),
	POTATO(Seed.POTATO, 8.0, 0.0, 9.0),
	ONION(Seed.ONION, 9.5, 0.0, 10.5),
	CABBAGE(Seed.CABBAGE, 10.0, 0.0, 11.5),
	TOMATO(Seed.TOMATO, 12.5, 0.0, 14.0),
	SWEETCORN(Seed.SWEETCORN, 17.0, 0.0, 19.0),
	STRAWBERRY(Seed.STRAWBERRY, 26.0, 0.0, 29.0),
	WATERMELON(Seed.WATERMELON, 48.5, 0.0, 54.5),
	SNAPE_GRASS(Seed.SNAPE_GRASS, 82.0, 0.0, 82.0),
	BARLEY(Seed.BARLEY, 8.5, 0.0, 9.5),
	HAMMERSTONE(Seed.HAMMERSTONE, 9.0, 0.0, 10.0),
	ASGARNIAN(Seed.ASGARNIAN, 10.9, 0.0, 12.0),
	JUTE(Seed.JUTE, 13.0, 0.0, 14.5),
	YANILLIAN(Seed.YANILLIAN, 14.5, 0.0, 16.0),
	FLAX(Seed.FLAX, 16.0, 0.0, 17.5),
	KRANDORIAN(Seed.KRANDORIAN, 17.5, 0.0, 19.5),
	WILDBLOOD(Seed.WILDBLOOD, 23.0, 0.0, 26.0),
	HEMP(Seed.HEMP, 33.0, 0.0, 37.0),
	COTTON(Seed.COTTON, 72.0, 0.0, 82.0),
	REDBERRIES(Seed.REDBERRIES, 11.5, 64.0, 4.5),
	CADAVABERRIES(Seed.CADAVABERRIES, 18.0, 102.5, 7.0),
	DWELLBERRIES(Seed.DWELLBERRIES, 31.5, 177.5, 12.0),
	JANGERBERRIES(Seed.JANGERBERRIES, 50.5, 284.5, 19.0),
	WHITEBERRIES(Seed.WHITEBERRIES, 78.0, 437.5, 29.0),
	POISON_IVY(Seed.POISON_IVY, 120.0, 675.0, 45.0),
	MARIGOLD(Seed.MARIGOLD, 8.5, 0.0, 47.0),
	ROSEMARY(Seed.ROSEMARY, 12.0, 0.0, 66.5),
	NASTURTIUM(Seed.NASTURTIUM, 19.5, 0.0, 111.0),
	WOAD(Seed.WOAD, 20.5, 0.0, 115.5),
	LIMPWURT(Seed.LIMPWURT, 21.5, 0.0, 120.0),
	WHITE_LILY(Seed.WHITE_LILY, 42.0, 0.0, 250.0),
	OAK(Seed.OAK, 14.0, 467.3, 0.0),
	WILLOW(Seed.WILLOW, 25.0, 1456.5, 0.0),
	MAPLE(Seed.MAPLE, 45.0, 3403.4, 0.0),
	YEW(Seed.YEW, 81.0, 7069.9, 0.0),
	MAGIC(Seed.MAGIC, 145.5, 13768.3, 0.0),
	APPLE(Seed.APPLE, 22.0, 1199.5, 8.5),
	BANANA(Seed.BANANA, 28.0, 1750.5, 10.5),
	ORANGE(Seed.ORANGE, 35.5, 2470.2, 13.5),
	CURRY(Seed.CURRY, 40.0, 2906.9, 15.0),
	PINEAPPLE(Seed.PINEAPPLE, 57.0, 4605.0, 21.5),
	PAPAYA(Seed.PAPAYA, 72.0, 6146.6, 27.0),
	PALM(Seed.PALM, 110.5, 10150.1, 41.5),
	DRAGONFRUIT(Seed.DRAGONFRUIT, 140.0, 17335.0, 70.0),
	TEAK(Seed.TEAK, 35.0, 7290.0, 0.0),
	MAHOGANY(Seed.MAHOGANY, 63.0, 15720.0, 0.0),
	CAMPHOR(Seed.CAMPHOR, 88.0, 17840.0, 0.0),
	IRONWOOD(Seed.IRONWOOD, 145.0, 20380.0, 0.0),
	ROSEWOOD(Seed.ROSEWOOD, 252.0, 23100.0, 0.0),
	CACTUS(Seed.CACTUS, 66.5, 374.0, 25.0),
	POTATO_CACTUS(Seed.POTATO_CACTUS, 68.0, 230.0, 68.0);

	private static final Map<Seed, CropXp> BY_SEED = new EnumMap<>(Seed.class);
	private static final Map<Produce, CropXp> BY_PRODUCE = new EnumMap<>(Produce.class);

	static
	{
		for (CropXp xp : values())
		{
			BY_SEED.put(xp.seed, xp);
			BY_PRODUCE.put(xp.seed.getProduce(), xp);
		}
	}

	private final Seed seed;
	/** Awarded when the seed is planted. */
	private final double plantXp;
	/** One-off award for checking health; 0 for crops that are not checked. */
	private final double checkXp;
	/** Per item picked; 0 for trees, which give none. */
	private final double harvestXp;

	@Nullable
	public static CropXp forSeed(@Nullable Seed seed)
	{
		return seed == null ? null : BY_SEED.get(seed);
	}

	/**
	 * Experience for whatever is growing in a patch.
	 *
	 * <p>Keyed on the produce rather than the seed because that is what a patch can
	 * actually tell us — the varbit says "a ranarr is growing here", never which seed
	 * went in. Returns null for crops with no published figures, chiefly fruit trees.
	 */
	@Nullable
	public static CropXp forProduce(@Nullable Produce produce)
	{
		return produce == null ? null : BY_PRODUCE.get(produce);
	}

	/**
	 * Experience for planting one patch and taking the given number of harvests.
	 *
	 * <p>For a tree the harvest count is irrelevant, which falls out naturally: its
	 * harvest award is zero and the check-health award carries the whole total.
	 */
	public double totalFor(double harvests)
	{
		return plantXp + checkXp + (harvestXp * Math.max(0, harvests));
	}
}
