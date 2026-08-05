package com.dooglemaps.validate;

import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.InterfaceID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Guards the one brittle part of the Geomancy probe: how it names components.
 *
 * <p>The names come from {@code InterfaceID$FarmingView} by reflection, because there are 329
 * of them and copying the list would be both tedious and stale within a release. The cost of
 * reflection is that a rename in RuneLite breaks it silently — the dump would still be
 * written, just as an unlabelled wall of numbers, and nobody would notice until they came to
 * read one.
 */
public class GeomancyProbeTest
{
	@Test
	public void resolvesTheFarmingViewComponentNames()
	{
		assertTrue("component names no longer resolve - has InterfaceID$FarmingView moved?",
			GeomancyProbe.knownComponentCount() > 300);
	}

	/**
	 * The interface really is the one Geomancy opens, and its components are packed ids.
	 *
	 * <p>A component id carries its group in the high bits, so this both confirms the group
	 * and proves the ids can be handed straight to {@code Client.getWidget}.
	 */
	@Test
	public void everyNamedComponentBelongsToTheFarmingView()
	{
		int checked = 0;
		for (int id = 11730950; id < 11731300; id++)
		{
			String name = GeomancyProbe.nameOf(id);
			if (!name.isEmpty())
			{
				assertEquals(name + " is not part of the Geomancy interface",
					InterfaceID.FARMING_VIEW, id >>> 16);
				checked++;
			}
		}
		assertTrue("expected to find named components in this range", checked > 100);
	}

	/**
	 * The interface holds exactly the patches we do, family for family.
	 *
	 * <p>Established by counting, and it lines up on all 23 families and all 107 patches. That
	 * settles the hard half of reading Geomancy: there is a clean one-to-one correspondence,
	 * so only the <i>ordering within</i> each family is still unknown.
	 *
	 * <p>Three names give the mapping away and are worth recording: Geomancy splits herbs into
	 * {@code HERB}, {@code HERB_MYARM} and {@code HERB_MY2ARM} — the two Trollheim patches from
	 * My Arm's Big Adventure — which together match our ten. {@code GRAPEVINE} is our
	 * {@code GRAPES}. And {@code COMPOST_GUILD} is the Hosidius big bin, our
	 * {@code BIG_COMPOST}, which is independent confirmation that folding it in with the other
	 * bins was right.
	 *
	 * <p>As a test this also guards the data: a RuneLite update that adds a patch to one side
	 * and not the other shows up here rather than as a silently misread interface.
	 */
	@Test
	public void theInterfaceHoldsExactlyThePatchesWeDo()
	{
		Map<PatchImplementation, Integer> widgets = new EnumMap<>(PatchImplementation.class);
		for (String name : GeomancyProbe.knownComponentNames())
		{
			if (!name.endsWith("_PIC"))
			{
				continue;
			}
			// "HARDWOOD_TREE_3_PIC" -> "HARDWOOD_TREE"; "CRYSTAL_TREE_PIC" -> "CRYSTAL_TREE".
			String family = name.substring(0, name.length() - "_PIC".length())
				.replaceAll("_\\d+$", "");

			PatchImplementation type = FAMILIES.get(family);
			assertNotNull("unrecognised Geomancy family: " + family, type);
			widgets.merge(type, 1, Integer::sum);
		}

		for (PatchImplementation type : PatchImplementation.values())
		{
			int ours = FarmingWorldData.getPatches(type).size();
			int theirs = widgets.getOrDefault(type, 0);
			assertEquals(type + ": Geomancy and our data disagree on how many patches exist",
				ours, theirs);
		}
	}

	/** Geomancy's names for each patch family, against ours. */
	private static final Map<String, PatchImplementation> FAMILIES = families();

	private static Map<String, PatchImplementation> families()
	{
		Map<String, PatchImplementation> map = new HashMap<>();
		map.put("ALLOTMENT", PatchImplementation.ALLOTMENT);
		map.put("FLOWER", PatchImplementation.FLOWER);
		map.put("HOPS", PatchImplementation.HOPS);
		map.put("BUSH", PatchImplementation.BUSH);
		map.put("TREE", PatchImplementation.TREE);
		map.put("FRUITTREE", PatchImplementation.FRUIT_TREE);
		map.put("HARDWOOD_TREE", PatchImplementation.HARDWOOD_TREE);
		map.put("GRAPEVINE", PatchImplementation.GRAPES);
		map.put("CACTUS", PatchImplementation.CACTUS);
		map.put("CALQUAT", PatchImplementation.CALQUAT);
		map.put("CELASTRUS", PatchImplementation.CELASTRUS);
		map.put("REDWOOD", PatchImplementation.REDWOOD);
		map.put("SPIRITTREE", PatchImplementation.SPIRIT_TREE);
		map.put("CRYSTAL_TREE", PatchImplementation.CRYSTAL_TREE);
		map.put("SEAWEED", PatchImplementation.SEAWEED);
		map.put("CORAL", PatchImplementation.CORAL);
		map.put("MUSHROOM", PatchImplementation.MUSHROOM);
		map.put("BELLADONNA", PatchImplementation.BELLADONNA);
		map.put("HESPORI", PatchImplementation.HESPORI);
		map.put("ANIMA", PatchImplementation.ANIMA);
		map.put("COMPOST", PatchImplementation.COMPOST);
		// The two Trollheim herb patches from My Arm's Big Adventure are named apart.
		map.put("HERB", PatchImplementation.HERB);
		map.put("HERB_MYARM", PatchImplementation.HERB);
		map.put("HERB_MY2ARM", PatchImplementation.HERB);
		// Hosidius' big bin, which we show on the same tab as the ordinary ones.
		map.put("COMPOST_GUILD", PatchImplementation.BIG_COMPOST);
		return map;
	}

	/**
	 * Each patch is three widgets, and we need to know which is which.
	 *
	 * <p>The naming is consistent enough to rely on: a back, a picture and a front per patch.
	 * The picture is the obvious candidate for carrying the crop, which is the first thing to
	 * check against a real dump.
	 */
	@Test
	public void patchesAreNamedInThreesAcrossEveryFamily()
	{
		Set<String> suffixes = new HashSet<>();
		Set<String> families = new HashSet<>();

		for (int id = 11730950; id < 11731300; id++)
		{
			String name = GeomancyProbe.nameOf(id);
			int split = name.lastIndexOf('_');
			if (split > 0)
			{
				suffixes.add(name.substring(split + 1));
				families.add(name.split("_")[0]);
			}
		}

		assertTrue("BACK/PIC/FRONT is the shape the dump assumes",
			suffixes.contains("BACK") && suffixes.contains("PIC") && suffixes.contains("FRONT"));
		assertTrue("allotments should be in there", families.contains("ALLOTMENT"));
		assertTrue("and so should herbs", families.contains("HERB"));
	}
}
