package com.dooglemaps.state;

import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.ProduceState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The spirit tree simultaneous-plant cap: 1 at 83, 2 at 88, 3 at 93, 4 at 96, none of it at 99.
 *
 * <p>Wiki-checked. Before this the allocation assigned a seed to every empty spirit patch on
 * the account, and the guide instructed a planting the game refuses at the patch.
 */
public class SpiritTreesTest
{
	private PatchStateStore patches;

	@Before
	public void setUp() throws Exception
	{
		Map<String, String> stored = new HashMap<>();
		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer(i -> stored.get(i.getArgument(0) + "." + i.getArgument(1)));
		patches = construct(PatchStateStore.class, configManager, new com.google.gson.Gson());
		patches.load();
	}

	@Test
	public void theCapFollowsTheLevelTable()
	{
		assertEquals(1, SpiritTrees.capFor(83));
		assertEquals(1, SpiritTrees.capFor(87));
		assertEquals(2, SpiritTrees.capFor(88));
		assertEquals(2, SpiritTrees.capFor(92));
		assertEquals(3, SpiritTrees.capFor(93));
		assertEquals(4, SpiritTrees.capFor(96));
		assertEquals("99 lifts the cap entirely", Integer.MAX_VALUE, SpiritTrees.capFor(99));
	}

	/** A growing tree holds a slot; clearing to empty gives it back. */
	@Test
	public void aGrowingTreeHoldsASlotAndAnEmptyPatchDoesNot()
	{
		assertEquals("nothing planted, one slot free at 83",
			1, SpiritTrees.plantableNow(patches, 83));

		FarmPatch spirit = FarmingWorldData.getPatches(PatchImplementation.SPIRIT_TREE).get(0);
		recordState(spirit, CropState.GROWING);
		assertEquals("the growing tree is the one slot 83 has",
			0, SpiritTrees.plantableNow(patches, 83));
		assertEquals("but 88 has a second", 1, SpiritTrees.plantableNow(patches, 88));
	}

	/** The trim keeps agreement by sorting first, and never touches another group. */
	@Test
	public void theTrimIsSortedDeterministicAndScopedToSpiritTrees()
	{
		List<FarmPatch> spirits = FarmingWorldData.getPatches(PatchImplementation.SPIRIT_TREE);
		assertTrue("the world data has several spirit patches", spirits.size() >= 2);

		List<FarmPatch> trimmed = SpiritTrees.trimToCap(patches, 83,
			PlantingGroup.of(PatchImplementation.SPIRIT_TREE), spirits);
		assertEquals("one slot at 83, one patch offered", 1, trimmed.size());

		// Sorted-by-key first, so a differently-ordered caller gets the same survivor.
		List<FarmPatch> reversed = new java.util.ArrayList<>(spirits);
		java.util.Collections.reverse(reversed);
		assertEquals("same survivor whatever order the caller built its list in",
			trimmed.get(0).getKey(),
			SpiritTrees.trimToCap(patches, 83,
				PlantingGroup.of(PatchImplementation.SPIRIT_TREE), reversed).get(0).getKey());

		List<FarmPatch> herbs = FarmingWorldData.getPatches(PatchImplementation.HERB);
		assertEquals("other groups pass through untouched", herbs.size(),
			SpiritTrees.trimToCap(patches, 83,
				PlantingGroup.of(PatchImplementation.HERB), herbs).size());
	}

	/** Puts the patch into the first varbit state matching the crop state wanted. */
	private void recordState(FarmPatch patch, CropState state)
	{
		for (int value = 0; value < 256; value++)
		{
			ProduceState decoded = patch.getImplementation().forVarbitValue(value);
			if (decoded != null && decoded.getCropState() == state
				&& decoded.getProduce() != null
				&& decoded.getProduce() != com.dooglemaps.data.Produce.WEEDS)
			{
				patches.recordVarbit(patch, value, decoded);
				return;
			}
		}
		throw new AssertionError("no varbit puts a spirit tree into " + state);
	}
}
