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
 * The two chance-to-save constants that drive a crop's yield.
 *
 * <p>Herbs, allotments, hops, celastrus and giant seaweed are harvested until their
 * "lives" run out, and every pick has a chance to cost no life at all. That chance is
 * interpolated between a level-1 value and a level-99 value, both fixed per crop and
 * both measured in 256ths. They are what makes a ranarr patch give nine herbs rather
 * than the three it starts with.
 *
 * <p>Scraped from each seed's OSRS Wiki page; see {@code tools/crop-yield.tsv}. Crops
 * that do not use the lives mechanic are absent, as are celastrus and the flowers,
 * whose constants Jagex has never published. {@link com.dooglemaps.timer.YieldEstimate}
 * turns these into an expected harvest.
 */
@Getter
@RequiredArgsConstructor
public enum CropYield
{
	POTATO(Seed.POTATO, 101, 180),
	ONION(Seed.ONION, 105, 180),
	CABBAGE(Seed.CABBAGE, 107, 180),
	TOMATO(Seed.TOMATO, 112, 180),
	SWEETCORN(Seed.SWEETCORN, 88, 180),
	STRAWBERRY(Seed.STRAWBERRY, 103, 180),
	WATERMELON(Seed.WATERMELON, 126, 180),
	SNAPE_GRASS(Seed.SNAPE_GRASS, 148, 195),
	BARLEY(Seed.BARLEY, 103, 180),
	HAMMERSTONE(Seed.HAMMERSTONE, 104, 180),
	ASGARNIAN(Seed.ASGARNIAN, 108, 180),
	JUTE(Seed.JUTE, 113, 180),
	YANILLIAN(Seed.YANILLIAN, 116, 180),
	FLAX(Seed.FLAX, 140, 194),
	KRANDORIAN(Seed.KRANDORIAN, 121, 180),
	WILDBLOOD(Seed.WILDBLOOD, 128, 180),
	HEMP(Seed.HEMP, 120, 178),
	COTTON(Seed.COTTON, 82, 142),
	GUAM(Seed.GUAM, 25, 80),
	MARRENTILL(Seed.MARRENTILL, 28, 80),
	TARROMIN(Seed.TARROMIN, 31, 80),
	HARRALANDER(Seed.HARRALANDER, 36, 80),
	RANARR(Seed.RANARR, 39, 80),
	TOADFLAX(Seed.TOADFLAX, 43, 80),
	IRIT(Seed.IRIT, 46, 80),
	AVANTOE(Seed.AVANTOE, 50, 80),
	KWUARM(Seed.KWUARM, 54, 80),
	SNAPDRAGON(Seed.SNAPDRAGON, 57, 80),
	CADANTINE(Seed.CADANTINE, 60, 80),
	LANTADYME(Seed.LANTADYME, 64, 80),
	DWARF_WEED(Seed.DWARF_WEED, 67, 80),
	TORSTOL(Seed.TORSTOL, 71, 80),
	SEAWEED(Seed.SEAWEED, 150, 210),
	HUASCA(Seed.HUASCA, 58, 80);

	private static final Map<Seed, CropYield> BY_SEED = new EnumMap<>(Seed.class);
	private static final Map<Produce, CropYield> BY_PRODUCE = new EnumMap<>(Produce.class);

	static
	{
		for (CropYield yield : values())
		{
			BY_SEED.put(yield.seed, yield);
			BY_PRODUCE.put(yield.seed.getProduce(), yield);
		}
	}

	private final Seed seed;
	/** Chance to save a life at Farming level 1, in 256ths. */
	private final int ctsLow;
	/** Chance to save a life at Farming level 99, in 256ths. */
	private final int ctsHigh;

	@Nullable
	public static CropYield forSeed(@Nullable Seed seed)
	{
		return seed == null ? null : BY_SEED.get(seed);
	}

	/** Yield constants for whatever is growing in a patch, or null if it has none. */
	@Nullable
	public static CropYield forProduce(@Nullable Produce produce)
	{
		return produce == null ? null : BY_PRODUCE.get(produce);
	}
}
