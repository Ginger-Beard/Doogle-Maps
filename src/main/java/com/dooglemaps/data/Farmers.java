// GENERATED FILE - DO NOT EDIT BY HAND.
// Regenerate with: python3 tools/fetch_chatheads.py
//
// Names and chathead sprites from the OSRS Wiki; the art is Jagex's.
// See ATTRIBUTION.md.
package com.dooglemaps.data;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.runelite.api.gameval.NpcID;

/**
 * The gardeners you pay to protect a patch.
 *
 * <p>{@link FarmPatch#getFarmer()} holds an NPC id, which is the right thing to store and
 * useless to show. This turns it into a name, so a protected patch can say who was paid
 * rather than showing the same anonymous shield on all forty-nine of them.
 *
 * <p>Generated rather than typed out: the ids come from the mirrored world data and the
 * names from the wiki, so a farmer who is renamed or re-numbered is a regeneration rather
 * than a hand edit somebody has to remember to make.
 */
public final class Farmers
{
	private static final Map<Integer, String> NAMES = new HashMap<>();

	static
	{
		NAMES.put(NpcID.DANTAERA, "Dantaera");
		NAMES.put(NpcID.ELSTAN, "Elstan");
		NAMES.put(NpcID.FARMING_GARDENER_BUSH_1, "Dreven");
		NAMES.put(NpcID.FARMING_GARDENER_BUSH_2, "Taria");
		NAMES.put(NpcID.FARMING_GARDENER_BUSH_3, "Rhazien");
		NAMES.put(NpcID.FARMING_GARDENER_BUSH_4, "Torrell");
		NAMES.put(NpcID.FARMING_GARDENER_CACTUS, "Ayesha");
		NAMES.put(NpcID.FARMING_GARDENER_CALQUAT, "Imiago");
		NAMES.put(NpcID.FARMING_GARDENER_CALQUAT_2, "Tziuhtla");
		NAMES.put(NpcID.FARMING_GARDENER_CALQUAT_3, "Guppa");
		NAMES.put(NpcID.FARMING_GARDENER_FARMGUILD_CELASTRUS, "Taylor");
		NAMES.put(NpcID.FARMING_GARDENER_FARMGUILD_REDWOOD, "Alexandra");
		NAMES.put(NpcID.FARMING_GARDENER_FARMGUILD_T1, "Alan");
		NAMES.put(NpcID.FARMING_GARDENER_FARMGUILD_T2, "Rosie");
		NAMES.put(NpcID.FARMING_GARDENER_FARMGUILD_T3, "Nikkie");
		NAMES.put(NpcID.FARMING_GARDENER_FRUIT_1, "Bolongo");
		NAMES.put(NpcID.FARMING_GARDENER_FRUIT_2, "Gileth");
		NAMES.put(NpcID.FARMING_GARDENER_FRUIT_4, "Ellena");
		NAMES.put(NpcID.FARMING_GARDENER_FRUIT_7, "Ehecatl");
		NAMES.put(NpcID.FARMING_GARDENER_FRUIT_TREE_5, "Liliwen");
		NAMES.put(NpcID.FARMING_GARDENER_HARDWOOD_TREE_5, "Argo");
		NAMES.put(NpcID.FARMING_GARDENER_HOPS_1, "Selena");
		NAMES.put(NpcID.FARMING_GARDENER_HOPS_3, "Vasquen");
		NAMES.put(NpcID.FARMING_GARDENER_HOPS_4, "Rhonen");
		NAMES.put(NpcID.FARMING_GARDENER_HOPS_5, "Ercos");
		NAMES.put(NpcID.FARMING_GARDENER_SPIRIT_TREE_1, "Frizzy Skernip");
		NAMES.put(NpcID.FARMING_GARDENER_SPIRIT_TREE_2, "Yulf Squecks");
		NAMES.put(NpcID.FARMING_GARDENER_SPIRIT_TREE_3, "Praistan Ebola");
		NAMES.put(NpcID.FARMING_GARDENER_SPIRIT_TREE_4, "Lammy Langle");
		NAMES.put(NpcID.FARMING_GARDENER_SPIRIT_TREE_5, "Latlink Fastbell");
		NAMES.put(NpcID.FARMING_GARDENER_TREE_1, "Alain");
		NAMES.put(NpcID.FARMING_GARDENER_TREE_2, "Heskel");
		NAMES.put(NpcID.FARMING_GARDENER_TREE_3_02, "Treznor");
		NAMES.put(NpcID.FARMING_GARDENER_TREE_4, "Fayeth");
		NAMES.put(NpcID.FARMING_GARDENER_TREE_7, "Aub");
		NAMES.put(NpcID.FARMING_GARDENER_TREE_GNOME, "Prissy Scilla");
		NAMES.put(NpcID.FORTIS_GARDENER, "Harminia");
		NAMES.put(NpcID.FOSSIL_GARDENER_UNDERWATER, "Mernia");
		NAMES.put(NpcID.FOSSIL_SQUIRREL_GARDENER1, "Squirrel");
		NAMES.put(NpcID.FOSSIL_SQUIRREL_GARDENER2, "Squirrel");
		NAMES.put(NpcID.FOSSIL_SQUIRREL_GARDENER3, "Squirrel");
		NAMES.put(NpcID.FRANCIS, "Francis");
		NAMES.put(NpcID.FROG_QUEST_MARCELLUS, "Marcellus");
		NAMES.put(NpcID.GARTH, "Garth");
		NAMES.put(NpcID.HOSIDIUS_ALLOTMENT_GARDENER, "Marisi");
		NAMES.put(NpcID.KRAGEN, "Kragen");
		NAMES.put(NpcID.LYRA, "Lyra");
		NAMES.put(NpcID.PRIF_GARDENER, "Oswallt");
	}

	private Farmers()
	{
	}

	/** What this farmer is called, or null for an id we have no name for. */
	@Nullable
	public static String getName(int npcId)
	{
		return NAMES.get(npcId);
	}

	/** Every farmer we can name, for tests that check the sprites line up. */
	public static Map<Integer, String> getAll()
	{
		return Collections.unmodifiableMap(NAMES);
	}
}
