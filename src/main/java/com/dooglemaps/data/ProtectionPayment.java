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
import net.runelite.api.gameval.ItemID;

/**
 * What a farmer charges to protect a crop.
 *
 * <p>Payment may be noted, but has to be the exact item asked for — a farmer will not
 * take five apples in place of a basket of apples — so these are the precise items,
 * with a basket meaning the five-fruit one and a sack the ten-vegetable one.
 *
 * <p>Because it can be noted, a payment costs one inventory slot however large the
 * quantity: twenty-five coconuts for a magic tree travel as a single noted stack.
 *
 * <p>Crops missing from here cannot be farmer-protected at all — herbs, flowers,
 * mushrooms and belladonna have no protection option, and the immune crops need none.
 * The spirit tree is absent for a different reason: it is the one crop whose payment
 * is several different items at once, so it is left out rather than misrepresented.
 */
@Getter
@RequiredArgsConstructor
public enum ProtectionPayment
{
	POTATO(Produce.POTATO, ItemID.BUCKET_COMPOST, 2),
	ONION(Produce.ONION, ItemID.SACK_POTATO_10, 1),
	CABBAGE(Produce.CABBAGE, ItemID.SACK_ONION_10, 1),
	TOMATO(Produce.TOMATO, ItemID.SACK_CABBAGE_10, 2),
	SWEETCORN(Produce.SWEETCORN, ItemID.JUTE_FIBRE, 10),
	STRAWBERRY(Produce.STRAWBERRY, ItemID.BASKET_APPLE_5, 1),
	WATERMELON(Produce.WATERMELON, ItemID.CURRY_LEAF, 10),
	SNAPE_GRASS(Produce.SNAPE_GRASS, ItemID.JANGERBERRIES, 5),
	REDBERRIES(Produce.REDBERRIES, ItemID.SACK_CABBAGE_10, 4),
	CADAVABERRIES(Produce.CADAVABERRIES, ItemID.BASKET_TOMATO_5, 3),
	DWELLBERRIES(Produce.DWELLBERRIES, ItemID.BASKET_STRAWBERRY_5, 3),
	JANGERBERRIES(Produce.JANGERBERRIES, ItemID.WATERMELON, 6),
	WHITEBERRIES(Produce.WHITEBERRIES, ItemID.BITTERCAP_MUSHROOM, 8),
	BARLEY(Produce.BARLEY, ItemID.BUCKET_COMPOST, 3),
	HAMMERSTONE(Produce.HAMMERSTONE, ItemID.MARIGOLD, 1),
	ASGARNIAN(Produce.ASGARNIAN, ItemID.SACK_ONION_10, 1),
	JUTE(Produce.JUTE, ItemID.BARLEY_MALT, 6),
	YANILLIAN(Produce.YANILLIAN, ItemID.BASKET_TOMATO_5, 1),
	FLAX(Produce.FLAX, ItemID.GRAIN, 6),
	KRANDORIAN(Produce.KRANDORIAN, ItemID.SACK_CABBAGE_10, 3),
	WILDBLOOD(Produce.WILDBLOOD, ItemID.NASTURTIUM, 1),
	HEMP(Produce.HEMP, ItemID.FLAX, 6),
	COTTON(Produce.COTTON, ItemID.HEMP, 6),
	OAK(Produce.OAK, ItemID.BASKET_TOMATO_5, 1),
	WILLOW(Produce.WILLOW, ItemID.BASKET_APPLE_5, 1),
	MAPLE(Produce.MAPLE, ItemID.BASKET_ORANGE_5, 1),
	YEW(Produce.YEW, ItemID.CACTUS_SPINE, 10),
	MAGIC(Produce.MAGIC, ItemID.COCONUT, 25),
	APPLE(Produce.APPLE, ItemID.SWEETCORN, 9),
	BANANA(Produce.BANANA, ItemID.BASKET_APPLE_5, 4),
	ORANGE(Produce.ORANGE, ItemID.BASKET_STRAWBERRY_5, 3),
	CURRY(Produce.CURRY, ItemID.BASKET_BANANA_5, 5),
	PINEAPPLE(Produce.PINEAPPLE, ItemID.WATERMELON, 10),
	PAPAYA(Produce.PAPAYA, ItemID.PINEAPPLE, 10),
	PALM(Produce.PALM, ItemID.PAPAYA, 15),
	DRAGONFRUIT(Produce.DRAGONFRUIT, ItemID.COCONUT, 15),
	CACTUS(Produce.CACTUS, ItemID.CADAVABERRIES, 6),
	POTATO_CACTUS(Produce.POTATO_CACTUS, ItemID.SNAPE_GRASS, 8),
	TEAK(Produce.TEAK, ItemID.LIMPWURT_ROOT, 15),
	MAHOGANY(Produce.MAHOGANY, ItemID.YANILLIAN_HOPS, 25),
	CAMPHOR(Produce.CAMPHOR, ItemID.WHITE_BERRIES, 10),
	IRONWOOD(Produce.IRONWOOD, ItemID.CURRY_LEAF, 10),
	ROSEWOOD(Produce.ROSEWOOD, ItemID.DRAGONFRUIT, 8),
	ELKHORN_CORAL(Produce.ELKHORN_CORAL, ItemID.GIANT_SEAWEED, 5),
	PILLAR_CORAL(Produce.PILLAR_CORAL, ItemID.CORAL_ELKHORN, 5),
	UMBRAL_CORAL(Produce.UMBRAL_CORAL, ItemID.CORAL_PILLAR, 5),
	SEAWEED(Produce.SEAWEED, ItemID.FOSSIL_NUMULITE, 200),
	CALQUAT(Produce.CALQUAT, ItemID.POISONIVY_BERRIES, 8),
	CELASTRUS(Produce.CELASTRUS, ItemID.CACTUS_POTATO, 8),
	REDWOOD(Produce.REDWOOD, ItemID.DRAGONFRUIT, 6);

	private static final Map<Produce, ProtectionPayment> BY_PRODUCE =
		new EnumMap<>(Produce.class);

	static
	{
		for (ProtectionPayment payment : values())
		{
			BY_PRODUCE.put(payment.produce, payment);
		}
	}

	private final Produce produce;
	private final int itemID;
	/** How many of the item the farmer wants. */
	private final int quantity;

	/** The payment for a crop, or null if it cannot be protected. */
	@Nullable
	public static ProtectionPayment forProduce(@Nullable Produce produce)
	{
		return produce == null ? null : BY_PRODUCE.get(produce);
	}

	/** The payment for a seed's crop, or null if it cannot be protected. */
	@Nullable
	public static ProtectionPayment forSeed(@Nullable Seed seed)
	{
		return seed == null ? null : forProduce(seed.getProduce());
	}
}
