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

/**
 * The varbit value ranges each kind of patch uses.
 *
 * <p>Within a range the growth stage is {@code base + coefficient * value}, which is
 * enough to express every pattern the game uses: a fixed stage, one counting up with
 * the varbit, or one counting down.
 *
 * <p>Kept apart from {@link PatchImplementation} on purpose. {@link Produce} names its
 * patch implementation, so Produce initialises after PatchImplementation; if
 * PatchImplementation's constants named Produce back, the two would initialise in a
 * cycle and one of them would see half-built constants. Nothing loads this class until
 * the first {@link #decode} call, long after both enums exist.
 */
final class PatchRules
{
	private static final Map<PatchImplementation, Rule[]> RULES =
		new EnumMap<>(PatchImplementation.class);

	static
	{
		RULES.put(PatchImplementation.MUSHROOM, new Rule[]{
			new Rule(0, 3, Produce.WEEDS, CropState.GROWING, 3, -1),
			new Rule(4, 9, Produce.MUSHROOM, CropState.GROWING, -4, 1),
			new Rule(10, 15, Produce.MUSHROOM, CropState.HARVESTABLE, -10, 1),
			new Rule(16, 20, Produce.MUSHROOM, CropState.DISEASED, -15, 1),
			new Rule(21, 25, Produce.MUSHROOM, CropState.DEAD, -20, 1),
			new Rule(26, 255, Produce.WEEDS, CropState.GROWING, 3, 0),
		});

		RULES.put(PatchImplementation.HESPORI, new Rule[]{
			new Rule(0, 3, Produce.WEEDS, CropState.GROWING, 3, -1),
			new Rule(4, 6, Produce.HESPORI, CropState.GROWING, -4, 1),
			new Rule(7, 8, Produce.HESPORI, CropState.HARVESTABLE, -7, 1),
			new Rule(9, 9, Produce.WEEDS, CropState.GROWING, 3, 0),
		});

		RULES.put(PatchImplementation.ALLOTMENT, new Rule[]{
			new Rule(0, 3, Produce.WEEDS, CropState.GROWING, 3, -1),
			new Rule(4, 5, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(6, 9, Produce.POTATO, CropState.GROWING, -6, 1),
			new Rule(10, 12, Produce.POTATO, CropState.HARVESTABLE, -10, 1),
			new Rule(13, 16, Produce.ONION, CropState.GROWING, -13, 1),
			new Rule(17, 19, Produce.ONION, CropState.HARVESTABLE, -17, 1),
			new Rule(20, 23, Produce.CABBAGE, CropState.GROWING, -20, 1),
			new Rule(24, 26, Produce.CABBAGE, CropState.HARVESTABLE, -24, 1),
			new Rule(27, 30, Produce.TOMATO, CropState.GROWING, -27, 1),
			new Rule(31, 33, Produce.TOMATO, CropState.HARVESTABLE, -31, 1),
			new Rule(34, 39, Produce.SWEETCORN, CropState.GROWING, -34, 1),
			new Rule(40, 42, Produce.SWEETCORN, CropState.HARVESTABLE, -40, 1),
			new Rule(43, 48, Produce.STRAWBERRY, CropState.GROWING, -43, 1),
			new Rule(49, 51, Produce.STRAWBERRY, CropState.HARVESTABLE, -49, 1),
			new Rule(52, 59, Produce.WATERMELON, CropState.GROWING, -52, 1),
			new Rule(60, 62, Produce.WATERMELON, CropState.HARVESTABLE, -60, 1),
			new Rule(63, 69, Produce.SNAPE_GRASS, CropState.GROWING, -63, 1),
			new Rule(70, 73, Produce.POTATO, CropState.GROWING, -70, 1),
			new Rule(74, 76, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(77, 80, Produce.ONION, CropState.GROWING, -77, 1),
			new Rule(81, 83, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(84, 87, Produce.CABBAGE, CropState.GROWING, -84, 1),
			new Rule(88, 90, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(91, 94, Produce.TOMATO, CropState.GROWING, -91, 1),
			new Rule(95, 97, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(98, 103, Produce.SWEETCORN, CropState.GROWING, -98, 1),
			new Rule(104, 106, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(107, 112, Produce.STRAWBERRY, CropState.GROWING, -107, 1),
			new Rule(113, 115, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(116, 123, Produce.WATERMELON, CropState.GROWING, -116, 1),
			new Rule(124, 127, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(128, 134, Produce.SNAPE_GRASS, CropState.GROWING, -128, 1),
			new Rule(135, 137, Produce.POTATO, CropState.DISEASED, -134, 1),
			new Rule(138, 140, Produce.SNAPE_GRASS, CropState.HARVESTABLE, -138, 1),
			new Rule(141, 141, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(142, 144, Produce.ONION, CropState.DISEASED, -141, 1),
			new Rule(145, 148, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(149, 151, Produce.CABBAGE, CropState.DISEASED, -148, 1),
			new Rule(152, 155, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(156, 158, Produce.TOMATO, CropState.DISEASED, -155, 1),
			new Rule(159, 162, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(163, 167, Produce.SWEETCORN, CropState.DISEASED, -162, 1),
			new Rule(168, 171, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(172, 176, Produce.STRAWBERRY, CropState.DISEASED, -171, 1),
			new Rule(177, 180, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(181, 187, Produce.WATERMELON, CropState.DISEASED, -180, 1),
			new Rule(188, 192, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(193, 195, Produce.SNAPE_GRASS, CropState.DEAD, -192, 1),
			new Rule(196, 198, Produce.SNAPE_GRASS, CropState.DISEASED, -195, 1),
			new Rule(199, 201, Produce.POTATO, CropState.DEAD, -198, 1),
			new Rule(202, 204, Produce.SNAPE_GRASS, CropState.DISEASED, -198, 1),
			new Rule(205, 205, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(206, 208, Produce.ONION, CropState.DEAD, -205, 1),
			new Rule(209, 211, Produce.SNAPE_GRASS, CropState.DEAD, -205, 1),
			new Rule(212, 212, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(213, 215, Produce.CABBAGE, CropState.DEAD, -212, 1),
			new Rule(216, 219, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(220, 222, Produce.TOMATO, CropState.DEAD, -219, 1),
			new Rule(223, 226, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(227, 231, Produce.SWEETCORN, CropState.DEAD, -226, 1),
			new Rule(232, 235, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(236, 240, Produce.STRAWBERRY, CropState.DEAD, -235, 1),
			new Rule(241, 244, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(245, 251, Produce.WATERMELON, CropState.DEAD, -244, 1),
			new Rule(252, 255, Produce.WEEDS, CropState.GROWING, 3, 0),
		});

		RULES.put(PatchImplementation.HERB, new Rule[]{
			new Rule(0, 3, Produce.WEEDS, CropState.GROWING, 3, -1),
			new Rule(4, 7, Produce.GUAM, CropState.GROWING, -4, 1),
			new Rule(8, 10, Produce.GUAM, CropState.HARVESTABLE, 10, -1),
			new Rule(11, 14, Produce.MARRENTILL, CropState.GROWING, -11, 1),
			new Rule(15, 17, Produce.MARRENTILL, CropState.HARVESTABLE, 17, -1),
			new Rule(18, 21, Produce.TARROMIN, CropState.GROWING, -18, 1),
			new Rule(22, 24, Produce.TARROMIN, CropState.HARVESTABLE, 24, -1),
			new Rule(25, 28, Produce.HARRALANDER, CropState.GROWING, -25, 1),
			new Rule(29, 31, Produce.HARRALANDER, CropState.HARVESTABLE, 31, -1),
			new Rule(32, 35, Produce.RANARR, CropState.GROWING, -32, 1),
			new Rule(36, 38, Produce.RANARR, CropState.HARVESTABLE, 38, -1),
			new Rule(39, 42, Produce.TOADFLAX, CropState.GROWING, -39, 1),
			new Rule(43, 45, Produce.TOADFLAX, CropState.HARVESTABLE, 45, -1),
			new Rule(46, 49, Produce.IRIT, CropState.GROWING, -46, 1),
			new Rule(50, 52, Produce.IRIT, CropState.HARVESTABLE, 52, -1),
			new Rule(53, 56, Produce.AVANTOE, CropState.GROWING, -53, 1),
			new Rule(57, 59, Produce.AVANTOE, CropState.HARVESTABLE, 59, -1),
			new Rule(60, 63, Produce.HUASCA, CropState.GROWING, -60, 1),
			new Rule(64, 66, Produce.HUASCA, CropState.HARVESTABLE, 66, -1),
			new Rule(67, 67, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(68, 71, Produce.KWUARM, CropState.GROWING, -68, 1),
			new Rule(72, 74, Produce.KWUARM, CropState.HARVESTABLE, 74, -1),
			new Rule(75, 78, Produce.SNAPDRAGON, CropState.GROWING, -75, 1),
			new Rule(79, 81, Produce.SNAPDRAGON, CropState.HARVESTABLE, 81, -1),
			new Rule(82, 85, Produce.CADANTINE, CropState.GROWING, -82, 1),
			new Rule(86, 88, Produce.CADANTINE, CropState.HARVESTABLE, 88, -1),
			new Rule(89, 92, Produce.LANTADYME, CropState.GROWING, -89, 1),
			new Rule(93, 95, Produce.LANTADYME, CropState.HARVESTABLE, 95, -1),
			new Rule(96, 99, Produce.DWARF_WEED, CropState.GROWING, -96, 1),
			new Rule(100, 102, Produce.DWARF_WEED, CropState.HARVESTABLE, 102, -1),
			new Rule(103, 106, Produce.TORSTOL, CropState.GROWING, -103, 1),
			new Rule(107, 109, Produce.TORSTOL, CropState.HARVESTABLE, 109, -1),
			new Rule(128, 130, Produce.GUAM, CropState.DISEASED, -127, 1),
			new Rule(131, 133, Produce.MARRENTILL, CropState.DISEASED, -130, 1),
			new Rule(134, 136, Produce.TARROMIN, CropState.DISEASED, -133, 1),
			new Rule(137, 139, Produce.HARRALANDER, CropState.DISEASED, -136, 1),
			new Rule(140, 142, Produce.RANARR, CropState.DISEASED, -139, 1),
			new Rule(143, 145, Produce.TOADFLAX, CropState.DISEASED, -142, 1),
			new Rule(146, 148, Produce.IRIT, CropState.DISEASED, -145, 1),
			new Rule(149, 151, Produce.AVANTOE, CropState.DISEASED, -148, 1),
			new Rule(152, 154, Produce.KWUARM, CropState.DISEASED, -151, 1),
			new Rule(155, 157, Produce.SNAPDRAGON, CropState.DISEASED, -154, 1),
			new Rule(158, 160, Produce.CADANTINE, CropState.DISEASED, -157, 1),
			new Rule(161, 163, Produce.LANTADYME, CropState.DISEASED, -160, 1),
			new Rule(164, 166, Produce.DWARF_WEED, CropState.DISEASED, -163, 1),
			new Rule(167, 169, Produce.TORSTOL, CropState.DISEASED, -166, 1),
			new Rule(170, 172, Produce.ANYHERB, CropState.DEAD, -169, 1),
			new Rule(173, 175, Produce.HUASCA, CropState.DISEASED, -172, 1),
			new Rule(176, 191, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(192, 195, Produce.GOUTWEED, CropState.GROWING, -192, 1),
			new Rule(196, 197, Produce.GOUTWEED, CropState.HARVESTABLE, 197, -1),
			new Rule(198, 200, Produce.GOUTWEED, CropState.DISEASED, -197, 1),
			new Rule(201, 203, Produce.GOUTWEED, CropState.DEAD, -200, 1),
			new Rule(204, 219, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(221, 255, Produce.WEEDS, CropState.GROWING, 3, 0),
		});

		RULES.put(PatchImplementation.FLOWER, new Rule[]{
			new Rule(0, 3, Produce.WEEDS, CropState.GROWING, 3, -1),
			new Rule(4, 7, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(8, 11, Produce.MARIGOLD, CropState.GROWING, -8, 1),
			new Rule(12, 12, Produce.MARIGOLD, CropState.HARVESTABLE, 0, 0),
			new Rule(13, 16, Produce.ROSEMARY, CropState.GROWING, -13, 1),
			new Rule(17, 17, Produce.ROSEMARY, CropState.HARVESTABLE, 0, 0),
			new Rule(18, 21, Produce.NASTURTIUM, CropState.GROWING, -18, 1),
			new Rule(22, 22, Produce.NASTURTIUM, CropState.HARVESTABLE, 0, 0),
			new Rule(23, 26, Produce.WOAD, CropState.GROWING, -23, 1),
			new Rule(27, 27, Produce.WOAD, CropState.HARVESTABLE, 0, 0),
			new Rule(28, 31, Produce.LIMPWURT, CropState.GROWING, -28, 1),
			new Rule(32, 32, Produce.LIMPWURT, CropState.HARVESTABLE, 0, 0),
			new Rule(33, 35, Produce.SCARECROW, CropState.GROWING, 35, -1),
			new Rule(36, 36, Produce.SCARECROW, CropState.GROWING, 0, 0),
			new Rule(37, 40, Produce.WHITE_LILY, CropState.GROWING, -37, 1),
			new Rule(41, 41, Produce.WHITE_LILY, CropState.HARVESTABLE, 0, 0),
			new Rule(42, 71, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(72, 75, Produce.MARIGOLD, CropState.GROWING, -72, 1),
			new Rule(76, 76, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(77, 80, Produce.ROSEMARY, CropState.GROWING, -77, 1),
			new Rule(81, 81, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(82, 85, Produce.NASTURTIUM, CropState.GROWING, -82, 1),
			new Rule(86, 86, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(87, 90, Produce.WOAD, CropState.GROWING, -87, 1),
			new Rule(91, 91, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(92, 95, Produce.LIMPWURT, CropState.GROWING, -92, 1),
			new Rule(96, 100, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(101, 104, Produce.WHITE_LILY, CropState.GROWING, -101, 1),
			new Rule(105, 136, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(137, 139, Produce.MARIGOLD, CropState.DISEASED, -136, 1),
			new Rule(140, 141, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(142, 144, Produce.ROSEMARY, CropState.DISEASED, -141, 1),
			new Rule(145, 146, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(147, 149, Produce.NASTURTIUM, CropState.DISEASED, -146, 1),
			new Rule(150, 151, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(152, 154, Produce.WOAD, CropState.DISEASED, -151, 1),
			new Rule(155, 156, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(157, 159, Produce.LIMPWURT, CropState.DISEASED, -156, 1),
			new Rule(160, 165, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(166, 168, Produce.WHITE_LILY, CropState.DISEASED, -165, 1),
			new Rule(169, 200, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(201, 204, Produce.MARIGOLD, CropState.DEAD, -200, 1),
			new Rule(205, 205, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(206, 209, Produce.ROSEMARY, CropState.DEAD, -205, 1),
			new Rule(210, 210, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(211, 214, Produce.NASTURTIUM, CropState.DEAD, -210, 1),
			new Rule(215, 215, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(216, 219, Produce.WOAD, CropState.DEAD, -215, 1),
			new Rule(220, 220, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(221, 224, Produce.LIMPWURT, CropState.DEAD, -220, 1),
			new Rule(225, 229, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(230, 233, Produce.WHITE_LILY, CropState.DEAD, -229, 1),
			new Rule(234, 255, Produce.WEEDS, CropState.GROWING, 3, 0),
		});

		RULES.put(PatchImplementation.BUSH, new Rule[]{
			new Rule(0, 3, Produce.WEEDS, CropState.GROWING, 3, -1),
			new Rule(4, 4, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(5, 9, Produce.REDBERRIES, CropState.GROWING, -5, 1),
			new Rule(10, 14, Produce.REDBERRIES, CropState.HARVESTABLE, -10, 1),
			new Rule(15, 20, Produce.CADAVABERRIES, CropState.GROWING, -15, 1),
			new Rule(21, 25, Produce.CADAVABERRIES, CropState.HARVESTABLE, -21, 1),
			new Rule(26, 32, Produce.DWELLBERRIES, CropState.GROWING, -26, 1),
			new Rule(33, 37, Produce.DWELLBERRIES, CropState.HARVESTABLE, -33, 1),
			new Rule(38, 45, Produce.JANGERBERRIES, CropState.GROWING, -38, 1),
			new Rule(46, 50, Produce.JANGERBERRIES, CropState.HARVESTABLE, -46, 1),
			new Rule(51, 58, Produce.WHITEBERRIES, CropState.GROWING, -51, 1),
			new Rule(59, 63, Produce.WHITEBERRIES, CropState.HARVESTABLE, -59, 1),
			new Rule(64, 69, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(70, 74, Produce.REDBERRIES, CropState.DISEASED, -69, 1),
			new Rule(75, 79, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(80, 85, Produce.CADAVABERRIES, CropState.DISEASED, -79, 1),
			new Rule(86, 90, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(91, 97, Produce.DWELLBERRIES, CropState.DISEASED, -90, 1),
			new Rule(98, 102, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(103, 110, Produce.JANGERBERRIES, CropState.DISEASED, -102, 1),
			new Rule(111, 115, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(116, 123, Produce.WHITEBERRIES, CropState.DISEASED, -115, 1),
			new Rule(124, 133, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(134, 138, Produce.REDBERRIES, CropState.DEAD, -133, 1),
			new Rule(139, 143, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(144, 149, Produce.CADAVABERRIES, CropState.DEAD, -143, 1),
			new Rule(150, 154, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(155, 161, Produce.DWELLBERRIES, CropState.DEAD, -154, 1),
			new Rule(162, 166, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(167, 174, Produce.JANGERBERRIES, CropState.DEAD, -166, 1),
			new Rule(175, 179, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(180, 187, Produce.WHITEBERRIES, CropState.DEAD, -179, 1),
			new Rule(188, 196, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(197, 204, Produce.POISON_IVY, CropState.GROWING, -197, 1),
			new Rule(205, 209, Produce.POISON_IVY, CropState.HARVESTABLE, -205, 1),
			new Rule(210, 216, Produce.POISON_IVY, CropState.DISEASED, -209, 1),
			new Rule(217, 224, Produce.POISON_IVY, CropState.DEAD, -216, 1),
			new Rule(225, 225, Produce.POISON_IVY, CropState.DISEASED, 8, 0),
			new Rule(226, 249, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(250, 250, Produce.REDBERRIES, CropState.GROWING, 5, 0),
			new Rule(251, 251, Produce.CADAVABERRIES, CropState.GROWING, 6, 0),
			new Rule(252, 252, Produce.DWELLBERRIES, CropState.GROWING, 7, 0),
			new Rule(253, 253, Produce.JANGERBERRIES, CropState.GROWING, 8, 0),
			new Rule(254, 254, Produce.WHITEBERRIES, CropState.GROWING, 8, 0),
			new Rule(255, 255, Produce.POISON_IVY, CropState.GROWING, 8, 0),
		});

		RULES.put(PatchImplementation.FRUIT_TREE, new Rule[]{
			new Rule(0, 3, Produce.WEEDS, CropState.GROWING, 3, -1),
			new Rule(4, 7, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(8, 13, Produce.APPLE, CropState.GROWING, -8, 1),
			new Rule(14, 20, Produce.APPLE, CropState.HARVESTABLE, -14, 1),
			new Rule(21, 26, Produce.APPLE, CropState.DISEASED, -20, 1),
			new Rule(27, 32, Produce.APPLE, CropState.DEAD, -26, 1),
			new Rule(33, 33, Produce.APPLE, CropState.HARVESTABLE, 0, 0),
			new Rule(34, 34, Produce.APPLE, CropState.GROWING, 6, 0),
			new Rule(35, 40, Produce.BANANA, CropState.GROWING, -35, 1),
			new Rule(41, 47, Produce.BANANA, CropState.HARVESTABLE, -41, 1),
			new Rule(48, 53, Produce.BANANA, CropState.DISEASED, -47, 1),
			new Rule(54, 59, Produce.BANANA, CropState.DEAD, -53, 1),
			new Rule(60, 60, Produce.BANANA, CropState.HARVESTABLE, 0, 0),
			new Rule(61, 61, Produce.BANANA, CropState.GROWING, 6, 0),
			new Rule(62, 71, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(72, 77, Produce.ORANGE, CropState.GROWING, -72, 1),
			new Rule(78, 84, Produce.ORANGE, CropState.HARVESTABLE, -78, 1),
			new Rule(85, 89, Produce.ORANGE, CropState.DISEASED, -84, 1),
			new Rule(90, 90, Produce.ORANGE, CropState.DISEASED, 6, 0),
			new Rule(91, 96, Produce.ORANGE, CropState.DEAD, -90, 1),
			new Rule(97, 97, Produce.ORANGE, CropState.HARVESTABLE, 0, 0),
			new Rule(98, 98, Produce.ORANGE, CropState.GROWING, 6, 0),
			new Rule(99, 104, Produce.CURRY, CropState.GROWING, -99, 1),
			new Rule(105, 111, Produce.CURRY, CropState.HARVESTABLE, -105, 1),
			new Rule(112, 117, Produce.CURRY, CropState.DISEASED, -111, 1),
			new Rule(118, 123, Produce.CURRY, CropState.DEAD, -117, 1),
			new Rule(124, 124, Produce.CURRY, CropState.HARVESTABLE, 0, 0),
			new Rule(125, 125, Produce.CURRY, CropState.GROWING, 6, 0),
			new Rule(126, 135, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(136, 141, Produce.PINEAPPLE, CropState.GROWING, -136, 1),
			new Rule(142, 148, Produce.PINEAPPLE, CropState.HARVESTABLE, -142, 1),
			new Rule(149, 154, Produce.PINEAPPLE, CropState.DISEASED, -148, 1),
			new Rule(155, 160, Produce.PINEAPPLE, CropState.DEAD, -154, 1),
			new Rule(161, 161, Produce.PINEAPPLE, CropState.HARVESTABLE, 0, 0),
			new Rule(162, 162, Produce.PINEAPPLE, CropState.GROWING, 6, 0),
			new Rule(163, 168, Produce.PAPAYA, CropState.GROWING, -163, 1),
			new Rule(169, 175, Produce.PAPAYA, CropState.HARVESTABLE, -169, 1),
			new Rule(176, 181, Produce.PAPAYA, CropState.DISEASED, -175, 1),
			new Rule(182, 187, Produce.PAPAYA, CropState.DEAD, -181, 1),
			new Rule(188, 188, Produce.PAPAYA, CropState.HARVESTABLE, 0, 0),
			new Rule(189, 189, Produce.PAPAYA, CropState.GROWING, 6, 0),
			new Rule(190, 199, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(200, 205, Produce.PALM, CropState.GROWING, -200, 1),
			new Rule(206, 212, Produce.PALM, CropState.HARVESTABLE, -206, 1),
			new Rule(213, 218, Produce.PALM, CropState.DISEASED, -212, 1),
			new Rule(219, 224, Produce.PALM, CropState.DEAD, -218, 1),
			new Rule(225, 225, Produce.PALM, CropState.HARVESTABLE, 0, 0),
			new Rule(226, 226, Produce.PALM, CropState.GROWING, 6, 0),
			new Rule(227, 232, Produce.DRAGONFRUIT, CropState.GROWING, -227, 1),
			new Rule(233, 239, Produce.DRAGONFRUIT, CropState.HARVESTABLE, -233, 1),
			new Rule(240, 245, Produce.DRAGONFRUIT, CropState.DISEASED, -239, 1),
			new Rule(246, 251, Produce.DRAGONFRUIT, CropState.DEAD, -245, 1),
			new Rule(252, 252, Produce.DRAGONFRUIT, CropState.HARVESTABLE, 0, 0),
			new Rule(253, 253, Produce.DRAGONFRUIT, CropState.GROWING, 6, 0),
			new Rule(254, 255, Produce.WEEDS, CropState.GROWING, 3, 0),
		});

		RULES.put(PatchImplementation.HOPS, new Rule[]{
			new Rule(0, 3, Produce.WEEDS, CropState.GROWING, 3, -1),
			new Rule(4, 7, Produce.HAMMERSTONE, CropState.GROWING, -4, 1),
			new Rule(8, 10, Produce.HAMMERSTONE, CropState.HARVESTABLE, 10, -1),
			new Rule(11, 13, Produce.HAMMERSTONE, CropState.DISEASED, -10, 1),
			new Rule(14, 18, Produce.ASGARNIAN, CropState.GROWING, -14, 1),
			new Rule(19, 21, Produce.ASGARNIAN, CropState.HARVESTABLE, 21, -1),
			new Rule(22, 25, Produce.ASGARNIAN, CropState.DISEASED, -21, 1),
			new Rule(26, 31, Produce.YANILLIAN, CropState.GROWING, -26, 1),
			new Rule(32, 34, Produce.YANILLIAN, CropState.HARVESTABLE, 34, -1),
			new Rule(35, 39, Produce.YANILLIAN, CropState.DISEASED, -34, 1),
			new Rule(40, 46, Produce.KRANDORIAN, CropState.GROWING, -40, 1),
			new Rule(47, 49, Produce.KRANDORIAN, CropState.HARVESTABLE, 49, -1),
			new Rule(50, 55, Produce.KRANDORIAN, CropState.DISEASED, -49, 1),
			new Rule(56, 63, Produce.WILDBLOOD, CropState.GROWING, -56, 1),
			new Rule(64, 66, Produce.WILDBLOOD, CropState.HARVESTABLE, 66, -1),
			new Rule(67, 73, Produce.WILDBLOOD, CropState.DISEASED, -66, 1),
			new Rule(74, 77, Produce.BARLEY, CropState.GROWING, -74, 1),
			new Rule(78, 80, Produce.BARLEY, CropState.HARVESTABLE, 80, -1),
			new Rule(81, 83, Produce.BARLEY, CropState.DISEASED, -80, 1),
			new Rule(84, 88, Produce.JUTE, CropState.GROWING, -84, 1),
			new Rule(89, 91, Produce.JUTE, CropState.HARVESTABLE, 91, -1),
			new Rule(92, 95, Produce.JUTE, CropState.DISEASED, -91, 1),
			new Rule(96, 98, Produce.FLAX, CropState.GROWING, -96, 1),
			new Rule(99, 101, Produce.FLAX, CropState.HARVESTABLE, 101, -1),
			new Rule(102, 103, Produce.FLAX, CropState.DISEASED, -101, 1),
			new Rule(104, 107, Produce.HEMP, CropState.GROWING, -104, 1),
			new Rule(108, 110, Produce.HEMP, CropState.HARVESTABLE, 110, -1),
			new Rule(111, 113, Produce.HEMP, CropState.DISEASED, -110, 1),
			new Rule(114, 118, Produce.COTTON, CropState.GROWING, -114, 1),
			new Rule(119, 121, Produce.COTTON, CropState.HARVESTABLE, 121, -1),
			new Rule(122, 125, Produce.COTTON, CropState.DISEASED, -121, 1),
			new Rule(126, 131, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(132, 135, Produce.HAMMERSTONE, CropState.GROWING, -132, 1),
			new Rule(136, 138, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(139, 141, Produce.HAMMERSTONE, CropState.DEAD, -138, 1),
			new Rule(142, 146, Produce.ASGARNIAN, CropState.GROWING, -142, 1),
			new Rule(147, 149, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(150, 153, Produce.ASGARNIAN, CropState.DEAD, -149, 1),
			new Rule(154, 159, Produce.YANILLIAN, CropState.GROWING, -154, 1),
			new Rule(160, 162, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(163, 167, Produce.YANILLIAN, CropState.DEAD, -162, 1),
			new Rule(168, 174, Produce.KRANDORIAN, CropState.GROWING, -168, 1),
			new Rule(175, 177, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(178, 183, Produce.KRANDORIAN, CropState.DEAD, -177, 1),
			new Rule(184, 191, Produce.WILDBLOOD, CropState.GROWING, -184, 1),
			new Rule(192, 194, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(195, 201, Produce.WILDBLOOD, CropState.DEAD, -194, 1),
			new Rule(202, 205, Produce.BARLEY, CropState.GROWING, -202, 1),
			new Rule(206, 208, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(209, 211, Produce.BARLEY, CropState.DEAD, -208, 1),
			new Rule(212, 216, Produce.JUTE, CropState.GROWING, -212, 1),
			new Rule(217, 219, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(220, 223, Produce.JUTE, CropState.DEAD, -219, 1),
			new Rule(224, 226, Produce.FLAX, CropState.GROWING, -224, 1),
			new Rule(227, 229, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(230, 231, Produce.FLAX, CropState.DEAD, -229, 1),
			new Rule(232, 235, Produce.HEMP, CropState.GROWING, -232, 1),
			new Rule(236, 238, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(239, 240, Produce.HEMP, CropState.DEAD, -238, 1),
			new Rule(241, 241, Produce.HEMP, CropState.DEAD, 3, 0),
			new Rule(242, 246, Produce.COTTON, CropState.GROWING, -242, 1),
			new Rule(247, 249, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(250, 253, Produce.COTTON, CropState.DEAD, -249, 1),
			new Rule(254, 255, Produce.WEEDS, CropState.GROWING, 3, 0),
		});

		RULES.put(PatchImplementation.TREE, new Rule[]{
			new Rule(0, 3, Produce.WEEDS, CropState.GROWING, 3, -1),
			new Rule(4, 7, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(8, 11, Produce.OAK, CropState.GROWING, -8, 1),
			new Rule(12, 12, Produce.OAK, CropState.GROWING, 4, 0),
			new Rule(13, 13, Produce.OAK, CropState.HARVESTABLE, 0, 0),
			new Rule(14, 14, Produce.OAK, CropState.HARVESTABLE, 0, 0),
			new Rule(15, 20, Produce.WILLOW, CropState.GROWING, -15, 1),
			new Rule(21, 21, Produce.WILLOW, CropState.GROWING, 6, 0),
			new Rule(22, 22, Produce.WILLOW, CropState.HARVESTABLE, 0, 0),
			new Rule(23, 23, Produce.WILLOW, CropState.HARVESTABLE, 0, 0),
			new Rule(24, 31, Produce.MAPLE, CropState.GROWING, -24, 1),
			new Rule(32, 32, Produce.MAPLE, CropState.GROWING, 8, 0),
			new Rule(33, 33, Produce.MAPLE, CropState.HARVESTABLE, 0, 0),
			new Rule(34, 34, Produce.MAPLE, CropState.HARVESTABLE, 0, 0),
			new Rule(35, 44, Produce.YEW, CropState.GROWING, -35, 1),
			new Rule(45, 45, Produce.YEW, CropState.GROWING, 10, 0),
			new Rule(46, 46, Produce.YEW, CropState.HARVESTABLE, 0, 0),
			new Rule(47, 47, Produce.YEW, CropState.HARVESTABLE, 0, 0),
			new Rule(48, 59, Produce.MAGIC, CropState.GROWING, -48, 1),
			new Rule(60, 60, Produce.MAGIC, CropState.GROWING, 12, 0),
			new Rule(61, 61, Produce.MAGIC, CropState.HARVESTABLE, 0, 0),
			new Rule(62, 62, Produce.MAGIC, CropState.HARVESTABLE, 0, 0),
			new Rule(63, 72, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(73, 75, Produce.OAK, CropState.DISEASED, -72, 1),
			new Rule(77, 77, Produce.OAK, CropState.DISEASED, 4, 0),
			new Rule(78, 79, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(80, 84, Produce.WILLOW, CropState.DISEASED, -79, 1),
			new Rule(86, 86, Produce.WILLOW, CropState.DISEASED, 6, 0),
			new Rule(87, 88, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(89, 95, Produce.MAPLE, CropState.DISEASED, -88, 1),
			new Rule(97, 97, Produce.MAPLE, CropState.DISEASED, 8, 0),
			new Rule(98, 99, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(100, 108, Produce.YEW, CropState.DISEASED, -99, 1),
			new Rule(110, 110, Produce.YEW, CropState.DISEASED, 10, 0),
			new Rule(111, 112, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(113, 123, Produce.MAGIC, CropState.DISEASED, -112, 1),
			new Rule(125, 125, Produce.MAGIC, CropState.DISEASED, 12, 0),
			new Rule(126, 136, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(137, 139, Produce.OAK, CropState.DEAD, -136, 1),
			new Rule(141, 141, Produce.OAK, CropState.DEAD, 4, 0),
			new Rule(142, 143, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(144, 148, Produce.WILLOW, CropState.DEAD, -143, 1),
			new Rule(150, 150, Produce.WILLOW, CropState.DEAD, 6, 0),
			new Rule(151, 152, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(153, 159, Produce.MAPLE, CropState.DEAD, -152, 1),
			new Rule(161, 161, Produce.MAPLE, CropState.DEAD, 8, 0),
			new Rule(162, 163, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(164, 172, Produce.YEW, CropState.DEAD, -163, 1),
			new Rule(174, 174, Produce.YEW, CropState.DEAD, 10, 0),
			new Rule(175, 176, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(177, 187, Produce.MAGIC, CropState.DEAD, -176, 1),
			new Rule(189, 189, Produce.MAGIC, CropState.DEAD, 12, 0),
			new Rule(190, 191, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(192, 197, Produce.WILLOW, CropState.HARVESTABLE, 0, 0),
			new Rule(198, 255, Produce.WEEDS, CropState.GROWING, 3, 0),
		});

		RULES.put(PatchImplementation.HARDWOOD_TREE, new Rule[]{
			new Rule(0, 3, Produce.WEEDS, CropState.GROWING, 3, -1),
			new Rule(4, 7, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(8, 14, Produce.TEAK, CropState.GROWING, -8, 1),
			new Rule(15, 15, Produce.TEAK, CropState.GROWING, 7, 0),
			new Rule(16, 16, Produce.TEAK, CropState.HARVESTABLE, 0, 0),
			new Rule(17, 17, Produce.TEAK, CropState.HARVESTABLE, 0, 0),
			new Rule(18, 23, Produce.TEAK, CropState.DISEASED, -17, 1),
			new Rule(24, 29, Produce.TEAK, CropState.DEAD, -23, 1),
			new Rule(30, 37, Produce.MAHOGANY, CropState.GROWING, -30, 1),
			new Rule(38, 38, Produce.MAHOGANY, CropState.GROWING, 8, 0),
			new Rule(39, 39, Produce.MAHOGANY, CropState.HARVESTABLE, 0, 0),
			new Rule(40, 40, Produce.MAHOGANY, CropState.HARVESTABLE, 0, 0),
			new Rule(41, 47, Produce.MAHOGANY, CropState.DISEASED, -40, 1),
			new Rule(48, 54, Produce.MAHOGANY, CropState.DEAD, -47, 1),
			new Rule(55, 62, Produce.CAMPHOR, CropState.GROWING, -55, 1),
			new Rule(63, 63, Produce.CAMPHOR, CropState.GROWING, 8, 0),
			new Rule(64, 64, Produce.CAMPHOR, CropState.HARVESTABLE, 0, 0),
			new Rule(65, 65, Produce.CAMPHOR, CropState.HARVESTABLE, 0, 0),
			new Rule(66, 72, Produce.CAMPHOR, CropState.DISEASED, -65, 1),
			new Rule(73, 79, Produce.CAMPHOR, CropState.DEAD, -72, 1),
			new Rule(80, 87, Produce.IRONWOOD, CropState.GROWING, -80, 1),
			new Rule(88, 88, Produce.IRONWOOD, CropState.GROWING, 8, 0),
			new Rule(89, 89, Produce.IRONWOOD, CropState.HARVESTABLE, 0, 0),
			new Rule(90, 90, Produce.IRONWOOD, CropState.HARVESTABLE, 0, 0),
			new Rule(91, 97, Produce.IRONWOOD, CropState.DISEASED, -90, 1),
			new Rule(98, 104, Produce.IRONWOOD, CropState.DEAD, -97, 1),
			new Rule(105, 113, Produce.ROSEWOOD, CropState.GROWING, -105, 1),
			new Rule(114, 114, Produce.ROSEWOOD, CropState.GROWING, 9, 0),
			new Rule(115, 115, Produce.ROSEWOOD, CropState.HARVESTABLE, 0, 0),
			new Rule(116, 116, Produce.ROSEWOOD, CropState.HARVESTABLE, 0, 0),
			new Rule(117, 124, Produce.ROSEWOOD, CropState.DISEASED, -116, 1),
			new Rule(125, 132, Produce.ROSEWOOD, CropState.DEAD, -124, 1),
			new Rule(133, 255, Produce.WEEDS, CropState.GROWING, 3, 0),
		});

		RULES.put(PatchImplementation.REDWOOD, new Rule[]{
			new Rule(0, 3, Produce.WEEDS, CropState.GROWING, 3, -1),
			new Rule(4, 7, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(8, 17, Produce.REDWOOD, CropState.GROWING, -8, 1),
			new Rule(18, 18, Produce.REDWOOD, CropState.HARVESTABLE, 0, 0),
			new Rule(19, 27, Produce.REDWOOD, CropState.DISEASED, -18, 1),
			new Rule(28, 36, Produce.REDWOOD, CropState.DEAD, -27, 1),
			new Rule(37, 37, Produce.REDWOOD, CropState.GROWING, 10, 0),
			new Rule(41, 55, Produce.REDWOOD, CropState.HARVESTABLE, 0, 0),
		});

		RULES.put(PatchImplementation.SPIRIT_TREE, new Rule[]{
			new Rule(0, 3, Produce.WEEDS, CropState.GROWING, 3, -1),
			new Rule(4, 7, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(8, 19, Produce.SPIRIT_TREE, CropState.GROWING, -8, 1),
			new Rule(20, 20, Produce.SPIRIT_TREE, CropState.GROWING, 12, 0),
			new Rule(21, 31, Produce.SPIRIT_TREE, CropState.DISEASED, -20, 1),
			new Rule(32, 43, Produce.SPIRIT_TREE, CropState.DEAD, -31, 1),
			new Rule(44, 44, Produce.SPIRIT_TREE, CropState.GROWING, 12, 0),
			new Rule(45, 63, Produce.WEEDS, CropState.GROWING, 3, 0),
		});

		RULES.put(PatchImplementation.ANIMA, new Rule[]{
			new Rule(0, 3, Produce.WEEDS, CropState.GROWING, 3, -1),
			new Rule(4, 7, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(8, 16, Produce.ATTAS, CropState.GROWING, -8, 1),
			new Rule(17, 25, Produce.IASOR, CropState.GROWING, -17, 1),
			new Rule(26, 34, Produce.KRONOS, CropState.GROWING, -26, 1),
			new Rule(35, 255, Produce.WEEDS, CropState.GROWING, 3, 0),
		});

		RULES.put(PatchImplementation.BELLADONNA, new Rule[]{
			new Rule(0, 3, Produce.WEEDS, CropState.GROWING, 3, -1),
			new Rule(4, 7, Produce.BELLADONNA, CropState.GROWING, -4, 1),
			new Rule(8, 8, Produce.BELLADONNA, CropState.HARVESTABLE, 0, 0),
			new Rule(9, 11, Produce.BELLADONNA, CropState.DISEASED, -8, 1),
			new Rule(12, 14, Produce.BELLADONNA, CropState.DEAD, -11, 1),
			new Rule(15, 255, Produce.WEEDS, CropState.GROWING, 3, 0),
		});

		RULES.put(PatchImplementation.CACTUS, new Rule[]{
			new Rule(0, 3, Produce.WEEDS, CropState.GROWING, 3, -1),
			new Rule(4, 7, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(8, 14, Produce.CACTUS, CropState.GROWING, -8, 1),
			new Rule(15, 18, Produce.CACTUS, CropState.HARVESTABLE, -15, 1),
			new Rule(19, 24, Produce.CACTUS, CropState.DISEASED, -18, 1),
			new Rule(25, 30, Produce.CACTUS, CropState.DEAD, -24, 1),
			new Rule(31, 31, Produce.CACTUS, CropState.GROWING, 7, 0),
			new Rule(32, 38, Produce.POTATO_CACTUS, CropState.GROWING, -32, 1),
			new Rule(39, 45, Produce.POTATO_CACTUS, CropState.HARVESTABLE, -39, 1),
			new Rule(46, 51, Produce.POTATO_CACTUS, CropState.DISEASED, -45, 1),
			new Rule(52, 57, Produce.POTATO_CACTUS, CropState.DEAD, -51, 1),
			new Rule(58, 58, Produce.POTATO_CACTUS, CropState.GROWING, 7, 0),
			new Rule(59, 255, Produce.WEEDS, CropState.GROWING, 3, 0),
		});

		RULES.put(PatchImplementation.CORAL, new Rule[]{
			new Rule(0, 3, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(4, 7, Produce.ELKHORN_CORAL, CropState.GROWING, -4, 1),
			new Rule(8, 8, Produce.ELKHORN_CORAL, CropState.GROWING, 4, 0),
			new Rule(9, 11, Produce.ELKHORN_CORAL, CropState.DISEASED, -8, 1),
			new Rule(12, 14, Produce.ELKHORN_CORAL, CropState.DEAD, -11, 1),
			new Rule(15, 18, Produce.PILLAR_CORAL, CropState.GROWING, -15, 1),
			new Rule(19, 19, Produce.PILLAR_CORAL, CropState.GROWING, 4, 0),
			new Rule(20, 22, Produce.PILLAR_CORAL, CropState.DISEASED, -19, 1),
			new Rule(23, 25, Produce.PILLAR_CORAL, CropState.DEAD, -22, 1),
			new Rule(26, 29, Produce.UMBRAL_CORAL, CropState.GROWING, -26, 1),
			new Rule(30, 30, Produce.UMBRAL_CORAL, CropState.GROWING, 4, 0),
			new Rule(31, 33, Produce.UMBRAL_CORAL, CropState.DISEASED, -30, 1),
			new Rule(34, 36, Produce.UMBRAL_CORAL, CropState.DEAD, -33, 1),
			new Rule(37, 255, Produce.WEEDS, CropState.GROWING, 0, 0),
		});

		RULES.put(PatchImplementation.SEAWEED, new Rule[]{
			new Rule(0, 3, Produce.WEEDS, CropState.GROWING, 3, -1),
			new Rule(4, 7, Produce.SEAWEED, CropState.GROWING, -4, 1),
			new Rule(8, 10, Produce.SEAWEED, CropState.HARVESTABLE, -8, 1),
			new Rule(11, 13, Produce.SEAWEED, CropState.DISEASED, -10, 1),
			new Rule(14, 16, Produce.SEAWEED, CropState.DEAD, -13, 1),
			new Rule(17, 255, Produce.WEEDS, CropState.GROWING, 3, 0),
		});

		RULES.put(PatchImplementation.CALQUAT, new Rule[]{
			new Rule(0, 3, Produce.WEEDS, CropState.GROWING, 3, -1),
			new Rule(4, 11, Produce.CALQUAT, CropState.GROWING, -4, 1),
			new Rule(12, 18, Produce.CALQUAT, CropState.HARVESTABLE, -12, 1),
			new Rule(19, 25, Produce.CALQUAT, CropState.DISEASED, -18, 1),
			new Rule(26, 33, Produce.CALQUAT, CropState.DEAD, -25, 1),
			new Rule(34, 34, Produce.CALQUAT, CropState.GROWING, 8, 0),
			new Rule(35, 255, Produce.WEEDS, CropState.GROWING, 3, 0),
		});

		RULES.put(PatchImplementation.CELASTRUS, new Rule[]{
			new Rule(0, 3, Produce.WEEDS, CropState.GROWING, 3, -1),
			new Rule(4, 7, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(8, 12, Produce.CELASTRUS, CropState.GROWING, -8, 1),
			new Rule(13, 13, Produce.CELASTRUS, CropState.GROWING, 5, 0),
			new Rule(14, 16, Produce.CELASTRUS, CropState.HARVESTABLE, -14, 1),
			new Rule(17, 17, Produce.CELASTRUS, CropState.HARVESTABLE, 0, 0),
			new Rule(18, 22, Produce.CELASTRUS, CropState.DISEASED, -17, 1),
			new Rule(23, 27, Produce.CELASTRUS, CropState.DEAD, -22, 1),
			new Rule(28, 28, Produce.CELASTRUS, CropState.HARVESTABLE, 0, 0),
			new Rule(29, 255, Produce.WEEDS, CropState.GROWING, 3, 0),
		});

		RULES.put(PatchImplementation.GRAPES, new Rule[]{
			new Rule(0, 1, Produce.WEEDS, CropState.GROWING, 3, 0),
			new Rule(2, 9, Produce.GRAPE, CropState.GROWING, -2, 1),
			new Rule(10, 10, Produce.GRAPE, CropState.GROWING, 7, 0),
			new Rule(11, 15, Produce.GRAPE, CropState.HARVESTABLE, -11, 1),
		});

		RULES.put(PatchImplementation.CRYSTAL_TREE, new Rule[]{
			new Rule(0, 3, Produce.WEEDS, CropState.GROWING, 3, -1),
			new Rule(8, 13, Produce.CRYSTAL_TREE, CropState.GROWING, -8, 1),
			new Rule(14, 14, Produce.CRYSTAL_TREE, CropState.GROWING, 6, 0),
			new Rule(15, 15, Produce.CRYSTAL_TREE, CropState.HARVESTABLE, 0, 0),
		});

		RULES.put(PatchImplementation.COMPOST, new Rule[]{
			new Rule(0, 0, Produce.EMPTY_COMPOST_BIN, CropState.EMPTY, 0, 0),
			new Rule(1, 15, Produce.COMPOST, CropState.FILLING, -1, 1),
			new Rule(16, 30, Produce.COMPOST, CropState.HARVESTABLE, -16, 1),
			new Rule(31, 31, Produce.COMPOST, CropState.GROWING, -31, 1),
			new Rule(32, 32, Produce.COMPOST, CropState.GROWING, -31, 1),
			new Rule(33, 47, Produce.SUPERCOMPOST, CropState.FILLING, -33, 1),
			new Rule(48, 62, Produce.SUPERCOMPOST, CropState.HARVESTABLE, -48, 1),
			new Rule(94, 94, Produce.COMPOST, CropState.GROWING, 2, 0),
			new Rule(95, 95, Produce.SUPERCOMPOST, CropState.GROWING, -95, 1),
			new Rule(96, 96, Produce.SUPERCOMPOST, CropState.GROWING, -95, 1),
			new Rule(126, 126, Produce.SUPERCOMPOST, CropState.GROWING, 2, 0),
			new Rule(129, 143, Produce.ROTTEN_TOMATO, CropState.FILLING, -129, 1),
			new Rule(144, 158, Produce.ROTTEN_TOMATO, CropState.HARVESTABLE, -144, 1),
			new Rule(159, 160, Produce.ROTTEN_TOMATO, CropState.GROWING, -159, 1),
			new Rule(176, 190, Produce.ULTRACOMPOST, CropState.HARVESTABLE, -176, 1),
		});

		RULES.put(PatchImplementation.BIG_COMPOST, new Rule[]{
			new Rule(0, 0, Produce.EMPTY_BIG_COMPOST_BIN, CropState.EMPTY, 0, 0),
			new Rule(1, 15, Produce.BIG_COMPOST, CropState.FILLING, -1, 1),
			new Rule(16, 30, Produce.BIG_COMPOST, CropState.HARVESTABLE, -16, 1),
			new Rule(33, 47, Produce.BIG_SUPERCOMPOST, CropState.FILLING, -33, 1),
			new Rule(48, 62, Produce.BIG_SUPERCOMPOST, CropState.HARVESTABLE, -48, 1),
			new Rule(63, 77, Produce.BIG_COMPOST, CropState.FILLING, -48, 1),
			new Rule(78, 92, Produce.BIG_COMPOST, CropState.HARVESTABLE, -63, 1),
			new Rule(93, 93, Produce.BIG_COMPOST, CropState.GROWING, 2, 0),
			new Rule(97, 99, Produce.BIG_SUPERCOMPOST, CropState.GROWING, -97, 1),
			new Rule(100, 114, Produce.BIG_SUPERCOMPOST, CropState.HARVESTABLE, -85, 1),
			new Rule(127, 128, Produce.BIG_COMPOST, CropState.GROWING, -127, 1),
			new Rule(129, 143, Produce.BIG_ROTTEN_TOMATO, CropState.FILLING, -129, 1),
			new Rule(144, 158, Produce.BIG_ROTTEN_TOMATO, CropState.HARVESTABLE, -144, 1),
			new Rule(159, 160, Produce.BIG_ROTTEN_TOMATO, CropState.GROWING, -159, 1),
			new Rule(161, 175, Produce.BIG_SUPERCOMPOST, CropState.FILLING, -146, 1),
			new Rule(176, 205, Produce.BIG_ULTRACOMPOST, CropState.HARVESTABLE, -176, 1),
			new Rule(207, 221, Produce.BIG_ROTTEN_TOMATO, CropState.HARVESTABLE, -192, 1),
			new Rule(222, 222, Produce.BIG_ROTTEN_TOMATO, CropState.GROWING, 2, 0),
			new Rule(223, 237, Produce.BIG_ROTTEN_TOMATO, CropState.FILLING, -208, 1),
		});

	}

	private PatchRules()
	{
	}

	@Nullable
	static ProduceState decode(PatchImplementation implementation, int value)
	{
		Rule[] rules = RULES.get(implementation);
		if (rules == null)
		{
			return null;
		}

		for (Rule rule : rules)
		{
			if (value >= rule.low && value <= rule.high)
			{
				return new ProduceState(rule.produce, rule.cropState,
					rule.base + (rule.coefficient * value));
			}
		}
		return null;
	}

	/** One varbit value range and the patch contents it decodes to. */
	private static final class Rule
	{
		final int low;
		final int high;
		final Produce produce;
		final CropState cropState;
		final int base;
		final int coefficient;

		Rule(int low, int high, Produce produce, CropState cropState, int base, int coefficient)
		{
			this.low = low;
			this.high = high;
			this.produce = produce;
			this.cropState = cropState;
			this.base = base;
			this.coefficient = coefficient;
		}
	}
}
