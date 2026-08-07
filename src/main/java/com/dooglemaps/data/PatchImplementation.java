// GENERATED FILE - DO NOT EDIT BY HAND.
// Regenerate with: python3 tools/generate_farming_data.py <runelite-client-sources>
//
// Mirrored from RuneLite core's net.runelite.client.plugins.timetracking.farming
// package (Copyright (c) 2018 Abex and the RuneLite contributors, BSD 2-clause).
// Those classes are package-private, so external plugins must carry their own copy.
// See ATTRIBUTION.md.
package com.dooglemaps.data;


import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A kind of farming patch: the decoder from its state varbit to what is growing
 * there, and the unit everything else groups by.
 *
 * <p>Each patch in the world has one varbit whose value encodes crop, crop state and
 * growth stage all at once. {@link #forVarbitValue(int)} decodes it.
 *
 * <p>This is deliberately also the sidebar's tab and the seed selector's grouping.
 * A loadout is chosen per patch type ("all herb patches get ranarr"), so the tab you
 * are looking at and the set of seeds you can pick from have to be the same concept —
 * keeping them as two enums would let them drift.
 */
@Getter
@RequiredArgsConstructor
public enum PatchImplementation
{
	HERB("Herb", "", false),
	ALLOTMENT("Allotment", "", false),
	FLOWER("Flower", "", false),
	HOPS("Hops", "", false),
	BUSH("Bush", "", true),
	TREE("Tree", "", true),
	FRUIT_TREE("Fruit tree", "", true),
	HARDWOOD_TREE("Hardwood", "Hardwood Trees", true),
	GRAPES("Grape", "", true),
	CACTUS("Cactus", "Cactus", true),
	CALQUAT("Calquat", "Calquat", true),
	CELASTRUS("Celastrus", "Celastrus", true),
	REDWOOD("Redwood", "Redwood Trees", true),
	SPIRIT_TREE("Spirit tree", "Spirit Trees", true),
	CRYSTAL_TREE("Crystal tree", "Crystal Tree", true),
	SEAWEED("Giant seaweed", "Seaweed", false),
	CORAL("Coral", "Coral", false),
	MUSHROOM("Mushroom", "", false),
	BELLADONNA("Belladonna", "Belladonna", false),
	HESPORI("Hespori", "", true),
	ANIMA("Anima", "", false),
	COMPOST("Compost bin", "Compost Bin", true),
	BIG_COMPOST("Big compost bin", "Big Compost Bin", true);

	/** Tab label. */
	private final String displayName;
	/** Farming contract name, empty when contracts never target this patch. */
	private final String contractName;
	/**
	 * True when the crop must be checked for health before it can be harvested, so a
	 * GROWING -> HARVESTABLE transition is a player action rather than a growth tick.
	 */
	private final boolean healthCheckRequired;

	/**
	 * Decodes a raw patch varbit value.
	 *
	 * @return the patch contents, or null if the value is not one this patch uses
	 */
	@Nullable
	public ProduceState forVarbitValue(int value)
	{
		return PatchRules.decode(this, value);
	}

	/**
	 * Whether this varbit value is a felled stump rather than a tree still standing.
	 *
	 * <h2>Two states the table cannot tell apart, and why it is not fixed there</h2>
	 *
	 * Every tree in {@code PatchRules} ends with a pair — magic is {@code 61} and {@code 62}, yew
	 * {@code 46} and {@code 47}, and so on for oak, willow and maple. The first is the checked tree
	 * you can still chop; the second is the stump left behind, which wants a spade. Both decode to
	 * the same {@code Produce}, the same {@code HARVESTABLE}, the same stage 0, so nothing
	 * downstream could distinguish them — and the guide said <i>"harvest the magic"</i> to both,
	 * forever, never once mentioning the stump or the spade.
	 *
	 * <p>The obvious repair is a third state in the table. It is the wrong one: {@code PatchRules}
	 * is generated from RuneLite core's own tables by {@code tools/generate_farming_data.py} and
	 * says so on its first line, and {@code CropState} exists to mirror core's vocabulary so the
	 * generated tables keep decoding. Editing either by hand survives exactly until the next
	 * regeneration, and core does not draw this distinction because core is not giving directions.
	 *
	 * <p>So it is derived instead, from a property of the table rather than an entry in it: a
	 * stump is a harvestable value whose <b>predecessor decodes to the same crop, also
	 * harvestable</b>. A standing tree's predecessor is the grown-but-unchecked value, which is
	 * {@code GROWING}. That holds for all five trees without naming any of them, and a sixth added
	 * upstream is covered the day it appears.
	 *
	 * <p>Restricted to {@link #TREE} and {@link #HARDWOOD_TREE}, which are the two that have the
	 * shape — one grown-unchecked value, then exactly two harvestable ones, for every crop they
	 * grow. The others must be excluded rather than merely being unlikely to match:
	 *
	 * <ul>
	 *   <li>a <b>bush</b> or <b>fruit tree</b> has a <i>run</i> of consecutive harvestable values
	 *       counting the produce left on it, so the second berry would read as a stump;</li>
	 *   <li><b>celastrus</b> has a counting run followed by a stage-0 value, which is the same
	 *       trap; and</li>
	 *   <li><b>redwood</b> has fifteen consecutive harvestable values and a layout this rule was
	 *       not derived from.</li>
	 * </ul>
	 *
	 * <h2>The whole three-value window, not just the value before</h2>
	 *
	 * Matching a harvestable value whose predecessor is also harvestable is not enough, and the
	 * table says so: {@code TREE} carries a second block of willow at <b>192 to 197</b>, six
	 * consecutive harvestable stage-0 values, and five of them have a harvestable predecessor. The
	 * looser rule called all five stumps.
	 *
	 * <p>So the whole shape is required — {@code GROWING}, {@code HARVESTABLE}, {@code HARVESTABLE},
	 * one crop, three consecutive values. That is the sequence a tree patch actually walks: grown
	 * and unchecked, checked and standing, felled. The willow block fails it because what precedes
	 * 192 is weeds, and 194 upwards fail it because what precedes them is harvestable rather than
	 * growing.
	 *
	 * <p>The stage-0 tests are belt and braces on top: a value counting produce carries a stage, a
	 * stump does not. See {@code TreeStumpTest}, which walks every value of every patch.
	 */
	public boolean isStumpVarbitValue(int value)
	{
		if (this != TREE && this != HARDWOOD_TREE)
		{
			return false;
		}

		ProduceState stump = forVarbitValue(value);
		if (stump == null || stump.getCropState() != CropState.HARVESTABLE || stump.getStage() != 0)
		{
			return false;
		}

		ProduceState standing = forVarbitValue(value - 1);
		if (standing == null
			|| standing.getProduce() != stump.getProduce()
			|| standing.getCropState() != CropState.HARVESTABLE
			|| standing.getStage() != 0)
		{
			return false;
		}

		ProduceState unchecked = forVarbitValue(value - 2);
		return unchecked != null
			&& unchecked.getProduce() == stump.getProduce()
			&& unchecked.getCropState() == CropState.GROWING;
	}

	/**
	 * Everything plantable in this kind of patch.
	 *
	 * <p>Derived from {@link Produce} rather than listed separately, so a crop added by
	 * a game update turns up here as soon as the data is regenerated.
	 */
	public List<Produce> getCrops()
	{
		List<Produce> crops = new ArrayList<>();
		for (Produce produce : Produce.values())
		{
			if (produce.getPatchImplementation() == this && produce.isCrop())
			{
				crops.add(produce);
			}
		}
		return Collections.unmodifiableList(crops);
	}

	/**
	 * Item icon for the tab, taken from the first crop this patch grows.
	 *
	 * <p>Derived rather than hand-picked so there is no per-patch icon list to keep in
	 * step with the game.
	 */
	public int getItemID()
	{
		List<Produce> crops = getCrops();
		for (Produce crop : crops)
		{
			if (crop.getItemID() > 0)
			{
				return crop.getItemID();
			}
		}
		return -1;
	}
}
