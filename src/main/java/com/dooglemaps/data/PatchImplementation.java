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
